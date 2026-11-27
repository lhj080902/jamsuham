import kr.jamsuham.cloud.LaunchHooks
import kr.jamsuham.cloud.LaunchRequest
import kr.jamsuham.cloud.ReplaceBudget
import kr.jamsuham.cloud.RunHandle
import kr.jamsuham.cloud.Runner
import kr.jamsuham.cloud.Scheduler
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.jamsuham.cloud.link.FrameCipher
import kr.jamsuham.cloud.link.Hello
import kr.jamsuham.cloud.link.LinkCrypto
import kr.jamsuham.cloud.link.LinkHandler
import kr.jamsuham.cloud.link.LinkNet
import kr.jamsuham.cloud.link.LinkPeer
import kr.jamsuham.core.ResolvedTarget
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.cloud.CloudClient
import kr.jamsuham.core.cloud.CloudEntry
import kr.jamsuham.core.cloud.CloudException
import kr.jamsuham.core.cloud.CloudStream
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.net.StatusPinger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

// 클라우드 멀티 노드(본 서버 + 접속용 서버) 시험. 계획: docs/superpowers/plans/2026-11-27-cloud-multinode.md

/** 시험 결과를 모아 기존 시험과 같은 모양으로 찍는다 */
class Checks(private val mode: String) {
    private val results = ArrayList<Pair<String, Boolean>>()

    fun check(name: String, pass: Boolean) {
        results += name to pass
        println((if (pass) "  통과  " else "  실패  ") + name)
    }

    fun done(): Boolean {
        val failed = results.filter { !it.second }
        println("RESULT $mode ${results.size - failed.size}/${results.size}" + if (failed.isEmpty()) "" else " 실패: ${failed.map { it.first }}")
        return failed.isEmpty()
    }
}

/** 배정 규칙(Scheduler)과 다시 배정 횟수 제한(ReplaceBudget) — 서버 없이 확인 */
fun schedTest(): Boolean {
    val c = Checks("sched")
    fun fake(id: String, remote: Boolean, capacity: Int) = object : Runner {
        override val id = id
        override val remote = remote
        override val capacity = capacity
        override fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle = error("시험에서는 부르지 않는다")
    }

    val s = Scheduler()
    c.check("빈 목록 → null", s.pick("a") == null)
    c.check("추가", s.add(fake("local", remote = false, capacity = 2)) && s.add(fake("r1", remote = true, capacity = 2)))
    c.check("같은 이름은 거절", !s.add(fake("r1", true, 5)))
    c.check("처음: 동률이면 내장 우선", s.pick("a")?.id == "local")
    s.placed("local", "a")
    c.check("같은 서버는 덜 들어간 노드로", s.pick("a")?.id == "r1")
    c.check("다른 서버는 남은 자리 비율이 큰 노드로", s.pick("b")?.id == "r1")
    s.placed("local", "b")
    c.check("꽉 찬 노드는 건너뜀", s.load("local") == 2 && s.pick("c")?.id == "r1")
    s.placed("r1", "a"); s.placed("r1", "c")
    c.check("모두 차면 null", s.pick("d") == null)
    s.released("local", "a")
    c.check("자리 나면 다시", s.pick("d")?.id == "local" && s.onTarget("local", "a") == 0)
    c.check("없는 것을 풀어도 음수 안 됨", run { s.released("local", "zzz"); s.load("local") == 1 })
    c.check("노드 제거", s.remove("r1")?.id == "r1" && s.runners().map { it.id } == listOf("local"))

    val b = ReplaceBudget(3, 600_000L)
    c.check("다시 배정 3번까지", b.tryUse(0) && b.tryUse(1) && b.tryUse(2) && !b.tryUse(3))
    c.check("10분 지나면 다시 됨", b.tryUse(600_001L))
    return c.done()
}

/** 본 서버 ↔ 접속용 서버 연결의 암호화(LinkCrypto, FrameCipher) — 서버 없이 확인 */
fun linkCryptoTest(): Boolean {
    val c = Checks("linkcrypto")
    fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun fails(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }

    // RFC 5869 Test Case 1
    val okm = LinkCrypto.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
    c.check("HKDF RFC 5869 벡터", okm.contentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")))

    val psk = ByteArray(32) { it.toByte() }
    val nw = ByteArray(32) { 1 }
    val nm = ByteArray(32) { 2 }
    val w = FrameCipher(psk, nw, nm, isWorker = true)
    val m = FrameCipher(psk, nw, nm, isWorker = false)
    val f1 = w.seal("하나".toByteArray())
    val f2 = w.seal("둘".toByteArray())
    c.check("워커→마스터 왕복(순서대로)", String(m.open(f1)) == "하나" && String(m.open(f2)) == "둘")
    c.check("재전송 거절", fails { m.open(f1) })
    c.check("마스터→워커 왕복", String(w.open(m.seal("셋".toByteArray()))) == "셋")
    c.check("방향이 다르면 못 엶", fails { FrameCipher(psk, nw, nm, true).open(FrameCipher(psk, nw, nm, true).seal(byteArrayOf(1))) })
    c.check("한 바이트 바뀌면 거절", fails { val a = FrameCipher(psk, nw, nm, true).seal(byteArrayOf(1, 2, 3)); a[0] = (a[0] + 1).toByte(); FrameCipher(psk, nw, nm, false).open(a) })
    c.check("순서를 건너뛰면 거절", fails { val a = FrameCipher(psk, nw, nm, true); a.seal(byteArrayOf(1)); FrameCipher(psk, nw, nm, false).open(a.seal(byteArrayOf(2))) })
    c.check("키가 다르면 MAC 불일치", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, "a"), LinkCrypto.workerMac(ByteArray(32), nw, nm, "a")))
    c.check("이름이 다르면 MAC 불일치", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, "a"), LinkCrypto.workerMac(psk, nw, nm, "b")))
    c.check("워커 MAC 과 마스터 MAC 은 다름", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, ""), LinkCrypto.masterMac(psk, nw, nm)))
    c.check("키 길이 검사", fails { LinkCrypto.parseKey(java.util.Base64.getEncoder().encodeToString(ByteArray(31))) })
    c.check("키 파일: 만들고 다시 읽으면 같음", run {
        val f = File(TMP, "wk-test.key").also { it.delete() }
        LinkCrypto.loadOrCreateKey(f).contentEquals(LinkCrypto.loadOrCreateKey(f)) && f.readText().trim().length == 44
    })
    return c.done()
}

// ------------------------------------------------------------ 접속용 서버 포트의 방어 (이 프로그램 안에서 포트만 열어 확인)

/**
 * 본 서버가 여는 접속용 서버 포트에 엉뚱한 것이 들어올 때의 동작. 서버 없이 실행: harness linknet
 * (실패한 접속이 쌓이면 이 프로그램 안에서 127.0.0.1 이 차단되므로, 차단 확인은 맨 끝에 둔다)
 */
fun linkNetTest(): Boolean {
    val c = Checks("linknet")
    val group = io.netty.channel.nio.NioEventLoopGroup(2)
    val psk = LinkCrypto.randomNonce()
    val server = LinkNet.serve("127.0.0.1", 0, psk, group) { _, peer ->
        object : LinkHandler {
            // 받은 것을 그대로 돌려준다
            override fun onMessage(msg: JsonObject) = peer.send(msg)
            override fun onClosed() {}
        }
    }
    val port = (server.localAddress() as java.net.InetSocketAddress).port

    /** 정상 접속을 해 본다: (연결된 상대, 받은 것들, 실패 사유) */
    class Dial {
        @Volatile var peer: LinkPeer? = null
        @Volatile var failed: String? = null
        val got = CopyOnWriteArrayList<JsonObject>()

        init {
            LinkNet.dial("127.0.0.1", port, psk, Hello("t1", 1, "test"), group, { p ->
                peer = p
                object : LinkHandler {
                    override fun onMessage(msg: JsonObject) { got += msg }
                    override fun onClosed() {}
                }
            }, { reason, _ -> failed = reason })
            waitFor(5000) { peer != null || failed != null }
        }
    }

    /** 인증 없이 [bytes] 를 보냈을 때 서버가 [withinMs] 안에 연결을 닫는지 */
    fun closes(bytes: ByteArray, withinMs: Int): Boolean = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = withinMs
        s.getOutputStream().write(bytes)
        s.getOutputStream().flush()
        try {
            s.getInputStream().read() == -1
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: java.io.IOException) {
            true
        }
    }

    try {
        val d = Dial()
        c.check("올바른 비밀키로 연결", d.peer != null)
        // 인증 뒤에는 큰 내용(채팅 기록·접속자 목록 등)도 양쪽으로 오갈 수 있어야 한다
        val big = "가".repeat(300_000)
        d.peer?.send(JsonObject().apply { addProperty("t", "echo"); addProperty("text", big) })
        c.check("인증 뒤에는 큰 내용도 오감 (약 900KB)", waitFor(5000) { d.got.any { it.get("text")?.asString == big } })
        d.peer?.close()

        // 인증 전에는 "1MB 를 보내겠다"는 머리말만으로 바로 끊어야 한다 (다 올 때까지 메모리에 쌓아 두지 않는다)
        c.check("인증 전의 큰 프레임(1MB 예고)은 바로 끊음", closes(byteArrayOf(0x00, 0x10, 0x00, 0x00, 1, 2, 3), 2000))
        c.check("길이가 4GB 인 프레임은 바로 끊음", closes(byteArrayOf(-1, -1, -1, -1, 1, 2, 3), 2000))
        c.check("HTTP 요청 같은 엉뚱한 데이터는 바로 끊음", closes("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(), 2000))
        val d2 = Dial()
        c.check("잘못된 접속 몇 번으로는 정상 접속이 막히지 않음", d2.peer != null)
        d2.peer?.close()

        repeat(10) { closes(byteArrayOf(-1, -1, -1, -1), 2000) }
        val d3 = Dial()
        c.check("잘못된 접속이 이어지면 그 IP 를 받지 않음", d3.peer == null && closes(ByteArray(0), 2000))
        d3.peer?.close()
    } finally {
        server.close()
        group.shutdownGracefully()
    }
    return c.done()
}

// ------------------------------------------------------------ 본 서버 + 접속용 서버를 실제로 띄워 보는 시험

/** 시험이 띄우는 프로세스 하나 (본 서버 또는 접속용 서버). 표준 출력을 줄 단위로 모아 둔다 */
class Proc(val name: String, installDir: File, val dir: File, args: List<String>, jvmOpts: List<String> = emptyList()) {
    val lines = CopyOnWriteArrayList<String>()
    private val p: Process

    init {
        dir.mkdirs()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd = listOf(java, "-Dfile.encoding=UTF-8") + jvmOpts +
            listOf("-cp", File(installDir, "lib").path + File.separator + "*", "kr.jamsuham.cloud.MainKt") + args
        p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        Thread {
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { lines += it; if (VERBOSE) println("      [$name] $it") }
        }.apply { isDaemon = true; start() }
        all += this
    }

    fun waitLog(re: Regex, timeoutMs: Long): Boolean = waitFor(timeoutMs) { lines.any { re.containsMatchIn(it) } }

    fun count(re: Regex): Int = lines.count { re.containsMatchIn(it) }

    fun kill() {
        p.destroyForcibly()
        p.waitFor(5, TimeUnit.SECONDS)
    }

    companion object {
        val all = CopyOnWriteArrayList<Proc>()
        val VERBOSE = System.getenv("NODE_VERBOSE") == "1"
    }
}

/** 실시간 연결로 받은 이벤트를 모아 둔다 */
class NodeSeen : CloudStream.Listener {
    val events = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    @Volatile var lastSeq = 0L
    override fun onConnection(connected: Boolean, error: String?) {}
    override fun onEvent(type: String, data: JsonObject, id: Long?) { events += type to data; if (id != null) lastSeq = id }
    override fun onAuthLost() {}
    fun statuses(sid: String) = events.filter { it.first == "status" && it.second.get("sid")?.asString == sid }.map { Wire.parseStatus(it.second.getAsJsonObject("status")) }
    fun states(sid: String) = statuses(sid).map { it.state }

    /** 지금 상태: 처음 받은 전체 상태(hello)와 그 뒤의 상태 소식 중 가장 나중 것 */
    fun status(sid: String): SessionStatus? {
        var last: SessionStatus? = null
        for ((type, data) in events) {
            if (type == "status" && data.get("sid")?.asString == sid) last = Wire.parseStatus(data.getAsJsonObject("status"))
            if (type == "hello") {
                data.getAsJsonArray("sessions")?.map { it.asJsonObject }?.firstOrNull { it.getAsJsonObject("entry")?.get("id")?.asString == sid }
                    ?.let { last = Wire.parseStatus(it.getAsJsonObject("status")) }
            }
        }
        return last
    }
    fun lines(sid: String) = events.filter { it.first == "line" && it.second.get("sid")?.asString == sid }.map { Wire.parseLine(it.second).second.text.plain }
    fun pos(sid: String) = events.lastOrNull { it.first == "pos" && it.second.get("sid")?.asString == sid }?.let { Wire.parsePos(it.second.get("pos")) }
}

/** 시험 계정 하나 (닉네임 로그인 + 실시간 연결) */
class NodeUser(base: String, name: String) {
    val app = CloudClient(base)
    val seen = NodeSeen()
    private val stream: CloudStream

    init {
        app.loginDev(name, "시험-$name")
        stream = CloudStream(app, { seen.lastSeq.takeIf { it > 0 } }, seen)
        stream.start()
    }

    fun code(block: (CloudClient) -> Unit): String? = try { block(app); null } catch (e: CloudException) { e.code }
    fun close() = stream.stop()
}

/**
 * 본 서버 1개 + 접속용 서버 2개를 이 PC 에서 띄워 원격 실행·배정·거절을 확인한다.
 * 먼저 띄울 것: `node server.js 1.21.4 <stay포트> stay`, `node server.js 1.21.4 <walk포트> walk`
 * 실행: harness cloudnode <jamsu-cloud 설치 폴더> <stay포트> <walk포트>   (자세히 보려면 NODE_VERBOSE=1)
 */
fun cloudNodeTest(install: String, stayPort: String, walkPort: String): Boolean {
    val c = Checks("cloudnode")
    val installDir = File(install).absoluteFile
    val root = File(TMP, "node").also { it.deleteRecursively() }
    val base = "http://127.0.0.1:18790"
    val linkPort = 18791
    val stay = stayPort.toInt()

    fun online(port: Int): Int = try {
        StatusPinger.ping(ResolvedTarget("127.0.0.1", port, "127.0.0.1", port), 769, 3000).online
    } catch (e: Exception) {
        -1
    }

    fun startMaster(jvm: List<String> = emptyList()): Proc {
        val dir = File(root, "master").also { it.mkdirs() }
        File(dir, "cloud.properties").writeText(
            "port=18790\nbind=127.0.0.1\npremiumMode=open\nallowPrivateTargets=true\nmaxAccountsPerTarget=2\n" +
                "localWorker=false\nworkerPort=$linkPort\nworkerBind=127.0.0.1\n",
        )
        val p = Proc("master", installDir, dir, listOf("cloud.properties"), listOf("-Djamsu.devLogin=true") + jvm)
        waitFor(20000) { try { CloudClient(base).info(); true } catch (_: Exception) { false } }
        return p
    }
    fun keyText() = File(root, "master/data/worker.key").readText().trim()
    fun startWorker(name: String, dirName: String = name, secret: String = keyText()): Proc {
        val dir = File(root, dirName).also { it.mkdirs() }
        File(dir, "worker.properties").writeText("master=127.0.0.1:$linkPort\nsecret=$secret\nname=$name\ncapacity=5\nallowPrivateTargets=true\n")
        return Proc(name, installDir, dir, listOf("worker", "worker.properties"))
    }

    /** 본 서버 로그에서 이 잠수가 마지막으로 배정된 노드 */
    fun placedOn(master: Proc, who: String, sid: String): String? =
        master.lines.mapNotNull { Regex("\\[배정] $who/$sid → (\\S+)").find(it)?.groupValues?.get(1) }.lastOrNull()

    try {
        var master = startMaster()
        c.check("본 서버가 비밀키 파일을 만듦", waitFor(5000) { File(root, "master/data/worker.key").exists() })
        val workers = HashMap<String, Proc>()
        workers["w1"] = startWorker("w1")
        workers["w2"] = startWorker("w2")
        c.check("워커 2개 연결", master.waitLog(Regex("\\[노드] 연결됨: w1"), 15000) && master.waitLog(Regex("\\[노드] 연결됨: w2"), 15000))

        val entry = CloudEntry(id = "h1", name = "노드 시험", address = "127.0.0.1:$stay", joinCommands = listOf("/잠수"))
        val a = NodeUser(base, "Tester")
        a.app.putEntry(entry)
        a.app.start("h1")
        c.check("원격 노드에서 접속 → ONLINE", waitFor(25000) { SessionState.ONLINE in a.seen.states("h1") } && placedOn(master, "Tester", "h1") in listOf("w1", "w2"))
        c.check("채팅 줄 수신", waitFor(5000) { a.seen.lines("h1").any { it.contains("반가워요") } })
        c.check("채팅 보내기", a.code { it.chat("h1", "노드 시험에서 보낸 채팅") } == null)
        c.check("메뉴 수신", waitFor(5000) { a.seen.events.any { it.first == "menu" && Wire.parseMenu(it.second.get("menu"))?.title?.plain == "리소스팩 적용" } })
        c.check("메뉴 클릭", a.code { it.menuClick("h1", 13) } == null && waitFor(5000) { a.seen.events.last { it.first == "menu" }.second.get("menu").isJsonNull })
        c.check("자동완성", a.app.suggest("h1", "/s")?.matches == listOf("spawn", "server"))
        c.check("접속자 수신", waitFor(5000) { a.seen.events.any { it.first == "players" && Wire.parsePlayers(it.second.getAsJsonObject("players")).players.size == 2 } })

        val b = NodeUser(base, "Tester_B")
        b.app.putEntry(entry)
        b.app.start("h1")
        c.check(
            "같은 서버의 둘째 계정은 다른 노드로",
            waitFor(25000) { SessionState.ONLINE in b.seen.states("h1") } &&
                placedOn(master, "Tester_B", "h1").let { it != null && it != placedOn(master, "Tester", "h1") },
        )
        val third = NodeUser(base, "Tester_C")
        third.app.putEntry(entry)
        c.check("서버별 인원은 전체 합계: 셋째는 자리 없음(limit)", third.code { it.start("h1") } == "limit" && online(stay) == 2)

        val walk = CloudEntry(id = "h3", name = "걷기 서버", address = "127.0.0.1:$walkPort", walkOnJoin = false)
        a.app.putEntry(walk)
        a.app.start("h3")
        c.check("원격 이동: 위치 수신", waitFor(25000) { a.seen.pos("h3") != null })
        val x0 = a.seen.pos("h3")?.x ?: 0.0
        var sendErr: String? = null
        repeat(4) { sendErr = sendErr ?: a.code { it.move("h3", 1f, 0f) }; Thread.sleep(400) }
        val x1 = a.seen.pos("h3")?.x ?: 0.0
        println("        → x ${"%.1f".format(x0)} → ${"%.1f".format(x1)}")
        c.check("동쪽으로 걸음", sendErr == null && x1 - x0 > 4.0)
        Thread.sleep(2500)
        val x2 = a.seen.pos("h3")?.x ?: 0.0
        Thread.sleep(1500)
        val x3 = a.seen.pos("h3")?.x ?: 0.0
        c.check("입력이 끊기면 스스로 멈춤", x2 == x3 && x2 - x1 < 9.0)
        a.app.stop("h3")

        b.app.stop("h1")
        c.check("끊기 → STOPPED + 실제 접속도 끊김", waitFor(8000) { b.seen.states("h1").lastOrNull() == SessionState.STOPPED } && waitFor(5000) { online(stay) == 1 })

        val bad = startWorker("w3", secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        c.check("틀린 비밀키는 거절", bad.waitLog(Regex("본 서버가 거절했습니다: 비밀키가 맞지 않습니다"), 15000) && CloudClient(base).info().get("api").asInt == Wire.API)
        bad.kill()
        val dup = startWorker("w1", dirName = "w1dup")
        c.check("같은 이름의 워커는 거절", dup.waitLog(Regex("본 서버가 거절했습니다: 같은 이름의"), 15000) && a.seen.states("h1").last() == SessionState.ONLINE)
        dup.kill()

        c.check("링크 버전이 다르면 거절", Socket("127.0.0.1", linkPort).use { s ->
            s.soTimeout = 6000
            val hello = JsonObject().apply {
                addProperty("t", "hello"); addProperty("ver", 999); addProperty("api", Wire.API); addProperty("version", "x")
                addProperty("name", "old"); addProperty("capacity", 1)
                addProperty("nonce", Base64.getEncoder().encodeToString(ByteArray(32)))
            }
            val out = DataOutputStream(s.getOutputStream())
            hello.toString().toByteArray(Charsets.UTF_8).let { out.writeInt(it.size); out.write(it); out.flush() }
            val din = DataInputStream(s.getInputStream())
            val reply = JsonParser.parseString(String(ByteArray(din.readInt()).also { din.readFully(it) }, Charsets.UTF_8)).asJsonObject
            println("        → ${reply.get("reason")?.asString}")
            reply.get("t").asString == "reject" && reply.get("code").asString == "version"
        })
        c.check(
            "엉뚱한 데이터는 그 연결만 닫힘",
            Socket("127.0.0.1", linkPort).use { s ->
                s.soTimeout = 6000
                s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                try { s.getInputStream().read() == -1 } catch (_: java.net.SocketTimeoutException) { false } catch (_: java.io.IOException) { true }
            } && CloudClient(base).info().get("api").asInt == Wire.API && a.seen.states("h1").last() == SessionState.ONLINE,
        )

        // 올바른 비밀키로 붙은 접속용 서버라도, 자기에게 배정되지 않은 실행의 로그인 정보는 받을 수 없어야 한다
        val group = io.netty.channel.nio.NioEventLoopGroup(1)
        val got = CopyOnWriteArrayList<JsonObject>()
        var rogue: LinkPeer? = null
        LinkNet.dial("127.0.0.1", linkPort, LinkCrypto.parseKey(keyText()), Hello("rogue", 1, "test"), group, { peer ->
            rogue = peer
            object : LinkHandler {
                override fun onMessage(msg: JsonObject) { got += msg }
                override fun onClosed() {}
            }
        }, { reason, _ -> println("        → rogue 연결 실패: $reason") })
        val rogueOk = waitFor(8000) { rogue != null }
        rogue?.send(JsonObject().apply { addProperty("t", "credsQ"); addProperty("rid", 1); addProperty("run", "nope"); addProperty("force", false) })
        val refused = waitFor(5000) { got.any { it.get("t")?.asString == "creds" } } &&
            got.first { it.get("t")?.asString == "creds" }.let { it.has("error") && !it.has("token") }
        rogue?.send(JsonObject().apply { addProperty("t", "ev"); addProperty("run", "nope"); addProperty("k", "status"); add("v", Wire.status(SessionStatus(SessionState.STOPPED, "가짜"))) })
        Thread.sleep(1000)
        c.check("배정받지 않은 실행의 토큰은 주지 않음", rogueOk && refused && rogue?.alive == true && a.seen.states("h1").last() == SessionState.ONLINE)
        rogue?.close()
        group.shutdownGracefully()

        c.check("접속용 서버 디스크에 계정 정보 없음", listOf("w1", "w2").all { w ->
            listOf("accounts", "servers", "master.key", "tokens.json", "worker.key", "premium.txt").none { File(root, "$w/data/$it").exists() } &&
                !File(root, "$w/cloud.properties").exists()
        })

        a.app.deleteEntry("h1")
        c.check("서버 항목을 지우면 원격 접속도 끊김", waitFor(8000) { online(stay) == 0 })

        // ---------------- B: 끊겼을 때 (프로세스를 강제로 죽여 본다) ----------------
        a.app.putEntry(entry)
        a.app.start("h1")
        b.app.start("h1")
        val ready = waitFor(25000) { a.seen.status("h1")?.state == SessionState.ONLINE && b.seen.status("h1")?.state == SessionState.ONLINE } && waitFor(5000) { online(stay) == 2 }
        val nodeA = placedOn(master, "Tester", "h1") ?: "w1"
        val nodeB = placedOn(master, "Tester_B", "h1") ?: "w2"
        c.check("준비: 두 계정이 서로 다른 노드에서 접속", ready && nodeA != nodeB)

        val t0 = System.currentTimeMillis()
        workers[nodeA]?.kill()
        c.check("노드가 죽으면 대기 상태로", waitFor(10000) {
            a.seen.status("h1").let { it?.state == SessionState.WAITING_RECONNECT && it.message.contains("접속용 서버 연결이 끊겨") }
        })
        c.check("죽은 직후 그 계정의 접속이 남아 있지 않음", waitFor(5000) { online(stay) == 1 })
        val moved = waitFor(45000) { placedOn(master, "Tester", "h1") == nodeB }
        val took = System.currentTimeMillis() - t0
        println("        → 다시 배정까지 ${took / 1000}초")
        c.check("다시 배정은 30초 뒤", moved && took >= 25000)
        c.check("다른 노드에서 다시 접속", waitFor(25000) { a.seen.status("h1")?.state == SessionState.ONLINE } && waitFor(5000) { online(stay) == 2 })

        workers[nodeB]?.kill()
        val bothWaiting = waitFor(10000) { a.seen.status("h1")?.state == SessionState.WAITING_RECONNECT && b.seen.status("h1")?.state == SessionState.WAITING_RECONNECT }
        val noSlot = waitFor(50000) { a.seen.status("h1")?.message?.contains("접속할 자리를 기다리는 중") == true }
        a.app.putEntry(CloudEntry(id = "h9", name = "자리 없음", address = "127.0.0.1:$walkPort", walkOnJoin = false))
        c.check("자리가 없으면: 직접 시작은 거절, 잠수 중이던 것은 대기", bothWaiting && noSlot && a.code { it.start("h9") } == "limit")
        b.app.stop("h1")
        c.check("대기 중인 잠수를 끊으면 멈춤", waitFor(5000) { b.seen.status("h1")?.state == SessionState.STOPPED })
        workers["w1"] = startWorker("w1")
        c.check("노드가 돌아오면 대기 중이던 것만 이어서 접속", waitFor(30000) { a.seen.status("h1")?.state == SessionState.ONLINE } &&
            waitFor(5000) { online(stay) == 1 } && b.seen.status("h1")?.state == SessionState.STOPPED)

        master.kill()
        c.check("본 서버가 죽으면 접속용 서버가 잠수를 모두 끊음",
            workers["w1"]!!.waitLog(Regex("본 서버 연결이 끊겨 잠수 1개를 끊었습니다"), 20000) && waitFor(5000) { online(stay) == 0 })
        master = startMaster()
        val a2 = NodeUser(base, "Tester")
        c.check("본 서버를 다시 켜면 이어서 접속", waitFor(90000) { workers["w1"]!!.count(Regex("본 서버에 연결했습니다")) >= 2 } &&
            waitFor(60000) { a2.seen.status("h1")?.state == SessionState.ONLINE } && waitFor(5000) { online(stay) == 1 })

        workers["w1"]!!.kill()
        master.kill()
        master = startMaster(listOf("-Djamsu.queueMaxMs=15000", "-Djamsu.queueRetryMs=3000"))
        val a3 = NodeUser(base, "Tester")
        val parked = waitFor(40000) { a3.seen.status("h1").let { it?.state == SessionState.STOPPED && it.message.contains("접속할 자리가 없어 멈췄습니다") } }
        workers["w1"] = startWorker("w1")
        c.check("오래 자리가 없으면 보류, 노드가 붙으면 이어서", parked && waitFor(30000) { a3.seen.status("h1")?.state == SessionState.ONLINE })

        a3.app.deleteAccount()
        c.check("계정을 지우면 원격 접속도 끊김", waitFor(8000) { online(stay) == 0 })
        a.close(); b.close(); third.close(); a2.close(); a3.close()
    } finally {
        for (p in Proc.all) p.kill()
    }
    return c.done()
}
