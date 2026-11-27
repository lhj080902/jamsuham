import com.google.gson.JsonObject
import kr.jamsuham.cloud.AccountRec
import kr.jamsuham.cloud.Config
import kr.jamsuham.cloud.DataStore
import kr.jamsuham.cloud.LaunchHooks
import kr.jamsuham.cloud.LaunchRequest
import kr.jamsuham.cloud.MsAuth
import kr.jamsuham.cloud.Premium
import kr.jamsuham.cloud.Presence
import kr.jamsuham.cloud.RunHandle
import kr.jamsuham.cloud.Runner
import kr.jamsuham.cloud.Scheduler
import kr.jamsuham.cloud.SessionBox
import kr.jamsuham.cloud.SessionManager
import kr.jamsuham.cloud.Vault
import kr.jamsuham.cloud.link.Hello
import kr.jamsuham.cloud.link.LinkCrypto
import kr.jamsuham.cloud.link.LinkHandler
import kr.jamsuham.cloud.link.LinkNet
import kr.jamsuham.cloud.link.LinkPeer
import kr.jamsuham.cloud.link.MasterLink
import kr.jamsuham.cloud.link.Worker
import kr.jamsuham.cloud.link.WorkerConfig
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.Traffic
import kr.jamsuham.core.cloud.CloudEntry
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.net.CommandSuggestions
import kr.jamsuham.core.text.ChatText
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

// 클라우드 멀티 노드: 프로세스를 띄우지 않고 본 서버(SessionManager)·접속용 서버(Worker)의 판단만 빠르게 확인한다.
// 실행: harness nodeunit   (마인크래프트 서버·클라우드 서버 불필요, 약 30초)

/** 시키는 대로 기록만 하는 가짜 실행기. 엔진처럼, 접속 전인 잠수를 끊으면 그 자리에서 "끊었습니다" 를 알린다 */
class FakeRunner(override val id: String, override val capacity: Int = 100, override val remote: Boolean = true) : Runner {
    inner class Run(val req: LaunchRequest, val events: SessionListener, val hooks: LaunchHooks) : RunHandle {
        @Volatile var stops = 0

        /** false = 끊으라는 말이 닿지 않는 상황 (연결이 죽은 접속용 서버) */
        @Volatile var delivers = true

        private fun stopped() {
            if (delivers) events.onStatus(SessionStatus(SessionState.STOPPED, "연결을 끊었습니다"))
        }

        override fun stop() {
            stops++
            stopped()
        }

        override fun shutdown() = stopped()
        override fun sendChat(text: String) = true
        override fun setStick(east: Float, south: Float) {}
        override fun setMovement(mask: Int) {}
        override fun clickMenu(slot: Int) = true
        override fun closeMenu() {}
        override fun requestSuggestions(text: String, callback: (CommandSuggestions?) -> Unit) = false
        override fun traffic(): Traffic? = null
        override fun position(): Wire.Pos? = null
        fun online() = events.onStatus(SessionStatus(SessionState.ONLINE, "접속 중", onlineSinceMs = System.currentTimeMillis()))
    }

    val runs = CopyOnWriteArrayList<Run>()

    override fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle = Run(req, events, hooks).also { runs += it }
}

/** 임시 폴더에 꾸린 본 서버의 잠수 관리부 (웹·접속용 서버 연결 없이) */
class Rig(name: String, extra: String = "") {
    private val dir = File(TMP, "nodeunit-$name").also { it.deleteRecursively(); it.mkdirs() }
    val config: Config
    val store: DataStore
    val scheduler = Scheduler()
    val sessions: SessionManager
    private val auth: MsAuth

    init {
        File(dir, "cloud.properties").writeText("premiumMode=open\nallowPrivateTargets=true\nmaxAccountsPerTarget=0\nmaxSessionsPerAccount=5\n$extra")
        config = Config(File(dir, "cloud.properties"))
        store = DataStore(config.dataDir)
        auth = MsAuth(config, store, Vault(config.dataDir))
        sessions = SessionManager(config, store, Premium(config), auth, Presence(), scheduler)
    }

    /** 시험 계정을 만들고 서버 항목 하나(h1)를 넣는다 */
    fun user(name: String): AccountRec {
        val acc = auth.devLogin(name)
        sessions.putEntry(acc.uuid, CloudEntry(id = "h1", name = "시험", address = "mc.example.org"))
        return acc
    }

    fun box(acc: AccountRec): SessionBox = sessions.hub(acc.uuid).boxes["h1"]!!
    fun state(acc: AccountRec): SessionState = box(acc).status.state

    /** "잠수 중이어야 하는 서버" 표시가 있는지 */
    fun marked(acc: AccountRec): Boolean = store.servers(acc.uuid).let { sf -> synchronized(sf) { "h1" in sf.running } }

    /** 이 계정의 서버 목록 저장이 실패하게 만든다 (임시 파일 자리에 폴더를 둔다) */
    fun breakSaving(acc: AccountRec) {
        File(config.dataDir, "servers/${acc.uuid}.json.tmp").mkdirs()
    }

    /** 접속용 서버 연결이 끊긴 것처럼 (MasterLink 가 하는 순서대로) */
    fun lose(runnerId: String, graceful: Boolean) {
        scheduler.remove(runnerId)
        sessions.runnerLost(runnerId, graceful)
    }

    fun close() = sessions.shutdown()
}

private fun throws(block: () -> Unit): Boolean = try {
    block()
    false
} catch (_: Exception) {
    true
}

fun nodeUnitTest(): Boolean {
    // 시간 값은 SessionManager 가 처음 쓰이기 전에 정해야 한다
    System.setProperty("jamsu.devLogin", "true")
    System.setProperty("jamsu.replaceDelayMs", "300")
    System.setProperty("jamsu.queueRetryMs", "500")
    System.setProperty("jamsu.queueMaxMs", "3000")
    val c = Checks("nodeunit")

    // ---------------- 본 서버: 끊기 요청이 닿지 않은 채 접속용 서버가 끊겼을 때 ----------------
    Rig("stop").let { r ->
        val w1 = FakeRunner("w1")
        val w2 = FakeRunner("w2")
        r.scheduler.add(w1)
        val a = r.user("Tester")
        val b = r.user("Tester_B")
        r.sessions.start(a, "h1", false)
        r.sessions.start(b, "h1", false)
        val runA = w1.runs.first { it.req.uuid == a.uuid }
        val runB = w1.runs.first { it.req.uuid == b.uuid }
        runA.online()
        runB.online()
        r.scheduler.add(w2)
        // Tester 가 끊기를 눌렀지만 접속용 서버와의 연결이 이미 죽어 있어 전달되지 않는다
        runA.delivers = false
        r.sessions.stop(a.uuid, "h1")
        val before = r.state(a) == SessionState.ONLINE && runA.stops == 1
        r.lose("w1", graceful = false)
        c.check("전달되지 않은 끊기: 접속용 서버가 끊기면 그대로 멈춤", before && r.state(a) == SessionState.STOPPED && !r.marked(a))
        c.check("끊지 않은 잠수는 다른 곳에서 다시 접속", waitFor(5000) { w2.runs.any { it.req.uuid == b.uuid } } && r.marked(b))
        Thread.sleep(2500)
        c.check("끊은 잠수는 되살아나지 않음", w2.runs.none { it.req.uuid == a.uuid } && r.state(a) == SessionState.STOPPED)
        r.close()
    }

    // ---------------- 본 서버: 한 계정을 처리하다 오류가 나도 나머지는 계속 ----------------
    Rig("isolate").let { r ->
        val w1 = FakeRunner("w1")
        r.scheduler.add(w1)
        val users = listOf("Iso_A", "Iso_B", "Iso_C").map { r.user(it) }
        for (u in users) r.sessions.start(u, "h1", false)
        for (run in w1.runs) {
            run.online()
            run.delivers = false
        }
        for (u in users) {
            r.sessions.stop(u.uuid, "h1")
            r.breakSaving(u)
        }
        try {
            r.lose("w1", graceful = false)
        } catch (_: Throwable) {
        }
        println("        → " + users.joinToString { r.state(it).name })
        c.check("한 계정 처리에서 오류가 나도 나머지 계정을 계속 처리", users.all { r.state(it) == SessionState.STOPPED })
        r.close()
    }

    // ---------------- 본 서버: 다시 배정 횟수 제한 ----------------
    Rig("budget").let { r ->
        val w1 = FakeRunner("w1")
        r.scheduler.add(w1)
        val a = r.user("Tester")
        r.sessions.start(a, "h1", false)
        w1.runs.last().online()

        /** w1 이 끊겼다가 바로 다시 붙는다. 그 잠수가 다시 맡겨지면 true */
        fun bounce(graceful: Boolean): Boolean {
            val n = w1.runs.size
            r.lose("w1", graceful)
            r.scheduler.add(w1)
            r.sessions.runnerAttached("w1")
            if (!waitFor(6000) { w1.runs.size == n + 1 }) return false
            w1.runs.last().online()
            return true
        }
        val graceful = (1..4).map { bounce(graceful = true) }
        c.check("정상 종료로 옮겨진 것은 횟수 제한에 세지 않음", graceful.all { it } && r.state(a) == SessionState.ONLINE)
        val hard = (1..4).map { bounce(graceful = false) }
        println("        → 갑자기 끊김 4번: $hard, ${r.box(a).status.message}")
        c.check("갑자기 끊긴 것은 10분에 3번까지, 넘으면 멈춤", hard == listOf(true, true, true, false) &&
            r.state(a) == SessionState.STOPPED && r.box(a).status.message.contains("자주 끊겨") && !r.marked(a))
        r.close()
    }

    // ---------------- 본 서버: 로그인 정보는 지금 돌고 있는 실행에만 ----------------
    Rig("creds").let { r ->
        val w1 = FakeRunner("w1")
        r.scheduler.add(w1)
        val a = r.user("Tester")
        r.sessions.start(a, "h1", false)
        val run1 = w1.runs.last()
        c.check("잠수 중인 실행에는 로그인 정보를 줌", run1.hooks.credentials(false).name == "Tester")
        run1.online()
        r.sessions.stop(a.uuid, "h1")
        c.check("끊은 실행에는 로그인 정보를 주지 않음", r.state(a) == SessionState.STOPPED && throws { run1.hooks.credentials(false) })
        r.sessions.start(a, "h1", false)
        val run2 = w1.runs.last()
        c.check("다시 시작하면 새 실행에만 줌", run2 !== run1 && throws { run1.hooks.credentials(true) } && run2.hooks.credentials(false).name == "Tester")
        r.close()
    }

    // ---------------- 본 서버: 붙는 도중 오류가 난 접속용 서버 ----------------
    Rig("attach", "workerPort=18793\nworkerBind=127.0.0.1\nlocalWorker=false\n").let { r ->
        var boom = true
        val link = MasterLink(r.config, r.scheduler, { if (boom) throw IllegalStateException("시험용 오류") }, { _, _ -> })
        link.start()
        val key = LinkCrypto.loadOrCreateKey(File(r.config.dataDir, "worker.key"))
        val group = io.netty.channel.nio.NioEventLoopGroup(1)

        /** 붙어 본다: 붙었으면 그 연결, 아니면 null */
        fun dial(): LinkPeer? {
            var peer: LinkPeer? = null
            var failed: String? = null
            LinkNet.dial("127.0.0.1", 18793, key, Hello("z1", 5, "test"), group, { p ->
                peer = p
                object : LinkHandler {
                    override fun onMessage(msg: JsonObject) {}
                    override fun onClosed() {}
                }
            }, { reason, _ -> failed = reason })
            waitFor(5000) { peer != null || failed != null }
            return peer
        }
        val first = dial()
        c.check("붙는 도중 오류가 나면 실행기 목록에 남지 않음", first == null && waitFor(2000) { r.scheduler.runners().none { it.id == "z1" } })
        boom = false
        val second = dial()
        c.check("그 뒤 같은 이름으로 다시 붙을 수 있음", second != null && r.scheduler.runners().any { it.id == "z1" })
        second?.close()
        link.stop()
        group.shutdownGracefully()
        r.close()
    }

    // ---------------- 본 서버: 끄겠다고 알린 접속용 서버가 뒤따라 보낸 소식 ----------------
    Rig("bye", "workerPort=18794\nworkerBind=127.0.0.1\nlocalWorker=false\n").let { r ->
        val link = MasterLink(r.config, r.scheduler, { r.sessions.runnerAttached(it) }, { id, graceful -> r.sessions.runnerLost(id, graceful) })
        link.start()
        val key = LinkCrypto.loadOrCreateKey(File(r.config.dataDir, "worker.key"))
        val group = io.netty.channel.nio.NioEventLoopGroup(1)
        val launched = CopyOnWriteArrayList<String>()
        var peer: LinkPeer? = null
        LinkNet.dial("127.0.0.1", 18794, key, Hello("z2", 5, "test"), group, { p ->
            peer = p
            object : LinkHandler {
                override fun onMessage(msg: JsonObject) {
                    if (msg.get("t")?.asString == "launch") launched += msg.get("run").asString
                }
                override fun onClosed() {}
            }
        }, { _, _ -> })
        waitFor(5000) { r.scheduler.runners().any { it.id == "z2" } }
        val a = r.user("Tester")
        r.sessions.start(a, "h1", false)
        waitFor(3000) { launched.isNotEmpty() }
        fun status(state: SessionState, message: String) = peer?.send(JsonObject().apply {
            addProperty("t", "ev"); addProperty("run", launched.firstOrNull()); addProperty("k", "status")
            add("v", Wire.status(SessionStatus(state, message)))
        })
        status(SessionState.ONLINE, "접속 중")
        val online = waitFor(3000) { r.state(a) == SessionState.ONLINE }
        // 끄겠다고 알린 뒤에 "끊었습니다" 가 뒤따라 온 경우 (끄는 순간과 엇갈려 나간 소식)
        peer?.send(JsonObject().apply { addProperty("t", "bye") })
        status(SessionState.STOPPED, "연결을 끊었습니다")
        Thread.sleep(500)
        peer?.close()
        val moved = waitFor(5000) { r.box(a).queued }
        println("        → ${r.state(a).name}, ${r.box(a).status.message}")
        c.check("끄겠다고 알린 뒤의 '끊었습니다' 는 사용자가 끊은 것으로 치지 않음", online && moved && r.marked(a))
        link.stop()
        group.shutdownGracefully()
        r.close()
    }

    // ---------------- 접속용 서버 (가짜 본 서버에 붙여서) ----------------
    run {
        val psk = LinkCrypto.randomNonce()
        val group = io.netty.channel.nio.NioEventLoopGroup(2)
        val got = CopyOnWriteArrayList<JsonObject>()
        var master: LinkPeer? = null
        val server = LinkNet.serve("127.0.0.1", 0, psk, group) { _, peer ->
            master = peer
            object : LinkHandler {
                override fun onMessage(msg: JsonObject) {
                    got += msg
                    // 자리·로그인 정보를 물으면 그대로 내준다
                    val reply = when (msg.get("t")?.asString) {
                        "claimQ" -> "claim"
                        "credsQ" -> "creds"
                        else -> return
                    }
                    peer.send(JsonObject().apply { addProperty("t", reply); add("rid", msg.get("rid")); addProperty("name", "Tester"); addProperty("uuid", java.util.UUID.randomUUID().toString()) })
                }
                override fun onClosed() {}
            }
        }
        val port = (server.localAddress() as java.net.InetSocketAddress).port
        val dir = File(TMP, "nodeunit-worker").also { it.deleteRecursively(); it.mkdirs() }
        val bom = 0xFEFF.toChar()
        File(dir, "worker.properties").writeText("${bom}master=127.0.0.1:$port\nsecret=${Base64.getEncoder().encodeToString(psk)}\nname=wt\ncapacity=5\n")
        val cfg = WorkerConfig(File(dir, "worker.properties"))
        c.check("메모장이 붙인 표시(BOM)가 있어도 설정을 읽음", cfg.masterHost == "127.0.0.1" && cfg.masterPort == port)

        val fake = FakeRunner("wt", remote = false)
        val worker = Worker(cfg, psk, fake)
        worker.start()
        fun kinds() = got.filter { it.get("t")?.asString == "ev" }.map { it.get("k").asString }
        fun launch(runId: String) {
            val n = fake.runs.size
            master?.send(JsonObject().apply {
                addProperty("t", "launch"); addProperty("run", runId); addProperty("uuid", "u-$runId")
                add("entry", Wire.entry(CloudEntry(id = "h1", name = "시험", address = "mc.example.org")))
            })
            waitFor(3000) { fake.runs.size == n + 1 }
        }
        val attached = waitFor(5000) { master != null }
        launch("r1")
        c.check("본 서버가 시킨 잠수를 실행기에 맡김", attached && fake.runs.size == 1)
        val run = fake.runs.first()

        val refused = try {
            run.hooks.claimTarget("127.0.0.1", port)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        c.check("본 서버의 접속용 서버 포트로는 접속하지 않음", refused && got.none { it.get("t")?.asString == "claimQ" })
        val asked = !throws { run.hooks.claimTarget("203.0.113.5", 25565) }
        c.check("다른 주소는 본 서버에 자리를 물어봄", asked && got.any { it.get("t")?.asString == "claimQ" && it.get("host")?.asString == "203.0.113.5" })

        got.clear()
        repeat(100) { i -> run.events.onPlayers(PlayerList(emptyList(), ChatText.of("머리말 $i"), ChatText.of(""))) }
        Thread.sleep(3500)
        val frames = got.filter { it.get("t")?.asString == "ev" && it.get("k")?.asString == "players" }
        println("        → 접속자 목록 100번 → 보낸 것 ${frames.size}번")
        c.check("접속자 목록은 가장 최근 것만 모아서 보냄", frames.size in 1..2 &&
            Wire.parsePlayers(frames.last().getAsJsonObject("v")).header.plain == "머리말 99")

        got.clear()
        run.events.onPlayers(PlayerList(emptyList(), ChatText.of("마지막"), ChatText.of("")))
        run.events.onStatus(SessionStatus(SessionState.STOPPED, "연결을 끊었습니다"))
        waitFor(3000) { "status" in kinds() }
        c.check("상태 소식 앞에, 모아 둔 것을 먼저 보냄", kinds().indexOf("players").let { it >= 0 && it < kinds().indexOf("status") })

        launch("r2")
        got.clear()
        // 접속용 서버를 끈다: 가짜 실행기는 엔진처럼 "끊었습니다" 를 알리지만, 그것이 본 서버로 가면 본 서버가 사용자가 끊은 것으로 안다
        worker.shutdown()
        val bye = waitFor(3000) { got.any { it.get("t")?.asString == "bye" } }
        Thread.sleep(500)
        println("        → 끌 때 보낸 것: " + got.map { it.get("t").asString + (it.get("k")?.let { k -> "/" + k.asString } ?: "") })
        c.check("끌 때는 '끊었습니다' 를 본 서버에 보내지 않음 (다른 곳으로 옮겨지게)", bye && "status" !in kinds())
        server.close()
        group.shutdownGracefully()
    }
    return c.done()
}
