package kr.jamsuham.cloud.link

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.DefaultThreadFactory
import kr.jamsuham.cloud.CLOUD_VERSION
import kr.jamsuham.cloud.LaunchHooks
import kr.jamsuham.cloud.LaunchRequest
import kr.jamsuham.cloud.LocalRunner
import kr.jamsuham.cloud.Log
import kr.jamsuham.cloud.RunHandle
import kr.jamsuham.cloud.Runner
import kr.jamsuham.cloud.cloudCoreLog
import kr.jamsuham.cloud.jsonOf
import kr.jamsuham.core.AuthException
import kr.jamsuham.core.DeathInfo
import kr.jamsuham.core.Inventory
import kr.jamsuham.core.JoinCredentials
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.Menu
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.Vitals
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.cloud.bool
import kr.jamsuham.core.cloud.float
import kr.jamsuham.core.cloud.int
import kr.jamsuham.core.cloud.long
import kr.jamsuham.core.cloud.obj
import kr.jamsuham.core.cloud.str
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/** 윈도우 메모장이 파일 맨 앞에 붙이는 표시 */
private val BOM = 0xFEFF.toChar().toString()

/** 접속용 서버 설정 (worker.properties) */
class WorkerConfig(val file: File) {
    private val p = Properties().also { it.load(java.io.StringReader(file.readText(Charsets.UTF_8).removePrefix(BOM))) }

    private fun str(key: String, def: String) = (p.getProperty(key) ?: def).trim()

    /** 본 서버 주소:포트 */
    val master = str("master", "")
    val masterHost: String get() = master.substringBeforeLast(':')
    val masterPort: Int get() = master.substringAfterLast(':', "").toIntOrNull() ?: 0

    /** 본 서버의 data/worker.key 내용 */
    val secret = str("secret", "")
    val name = str("name", "").ifEmpty {
        try {
            java.net.InetAddress.getLocalHost().hostName.replace(Regex("[^A-Za-z0-9_-]"), "-").take(64)
        } catch (_: Exception) {
            "worker"
        }
    }
    val capacity = (str("capacity", "200").toIntOrNull() ?: 200).coerceIn(1, 5000)
    val allowPrivateTargets = str("allowPrivateTargets", "false").equals("true", ignoreCase = true)
    val dataDir: File = File(str("dataDir", "data")).let { if (it.isAbsolute) it else File(file.absoluteFile.parentFile, it.path) }

    companion object {
        val DEFAULT = """
            |# 잠수크래프트 접속용 서버 설정
            |# 접속용 서버는 본 서버가 시키는 잠수를 대신 돌리는 프로그램입니다. 로그인 정보는 본 서버에만 있고 여기에는 저장하지 않습니다.
            |# 바꾼 뒤에는 다시 켜야 적용됩니다.
            |
            |# 본 서버의 주소:포트 (본 서버 cloud.properties 의 workerPort)
            |master=
            |
            |# 본 서버의 data/worker.key 파일 내용을 그대로 적습니다 (남에게 보여 주지 마세요)
            |secret=
            |
            |# 이 접속용 서버의 이름 (영문·숫자·-_). 본 서버 로그에 이 이름으로 보입니다. 비워 두면 컴퓨터 이름
            |name=
            |
            |# 이 컴퓨터에서 동시에 돌릴 잠수 수 (잠수 하나에 메모리 약 20~45MB)
            |capacity=200
            |
            |# 버전 변환기 설정을 둘 폴더
            |dataDir=data
            |
            |# 내부망 주소(127.0.0.1, 192.168.x.x 등)의 마인크래프트 서버에 접속 허용 (시험할 때만 true)
            |allowPrivateTargets=false
            |
        """.trimMargin()
    }
}

/** `jamsu-cloud worker [설정 파일]` — 접속용 서버로 실행한다 (Ctrl+C 로 끌 때까지 돈다) */
fun workerMain(configFile: File) {
    fun fill(): Nothing {
        Log.say("${configFile.absolutePath} 의 master(본 서버 주소:포트)와 secret(본 서버 data/worker.key 내용)을 채운 뒤 다시 실행해 주세요")
        exitProcess(2)
    }
    if (!configFile.exists()) {
        configFile.absoluteFile.parentFile?.mkdirs()
        configFile.writeText(WorkerConfig.DEFAULT)
        Log.say("설정 파일을 만들었습니다.")
        fill()
    }
    val cfg = WorkerConfig(configFile)
    if (cfg.masterHost.isEmpty() || cfg.masterPort !in 1..65535 || cfg.secret.isEmpty()) fill()
    val psk = try {
        LinkCrypto.parseKey(cfg.secret)
    } catch (e: IllegalArgumentException) {
        Log.say(e.message ?: "비밀키가 올바르지 않습니다")
        exitProcess(2)
    }

    Log.i("잠수크래프트 접속용 서버 v$CLOUD_VERSION (이름 ${cfg.name}, 자리 ${cfg.capacity})")
    // 본 서버와 같게: 지도를 그리지 않으므로 발밑 주변 청크만 담아 둔다
    kr.jamsuham.core.world.World.maxChunks = 200
    if (cfg.allowPrivateTargets) Log.w("allowPrivateTargets=true: 내부망 주소로도 접속합니다 (시험용)")
    val worker = Worker(cfg, psk)
    Runtime.getRuntime().addShutdownHook(Thread { worker.shutdown() })
    worker.start()
    Thread.currentThread().join()
}

/**
 * 접속용 서버의 몸통: 본 서버에 연결해 두고, 시키는 잠수를 [LocalRunner] 로 돌리며 소식을 그대로 돌려보낸다.
 * 본 서버와의 연결을 잃으면 돌리던 잠수를 모두 끊는다 — 본 서버가 같은 계정을 다른 곳에서 다시 접속시키므로,
 * 여기서 계속 붙어 있으면 한 계정이 두 곳에서 접속해 서로 튕겨 내게 된다.
 */
class Worker(private val cfg: WorkerConfig, private val psk: ByteArray, runner: Runner? = null) {
    private val group = NioEventLoopGroup(1, DefaultThreadFactory("jamsu-link", true))
    private val timer = Executors.newSingleThreadScheduledExecutor(DefaultThreadFactory("jamsu-worker-tick", true))
    /** 잠수를 실제로 돌리는 곳 (시험에서는 가짜 실행기를 넣는다) */
    private val runner: Runner = runner ?: LocalRunner(cfg.name, cfg.capacity, File(cfg.dataDir, "engine"), cfg.allowPrivateTargets, cloudCoreLog)

    /** 실행 번호 → 돌고 있는 잠수 */
    private val runs = ConcurrentHashMap<String, RunHandle>()
    private val lastPos = ConcurrentHashMap<String, String>()

    /**
     * 자주 바뀌는 상태(접속자·체력·인벤토리)는 올 때마다 보내지 않고, 실행마다 가장 최근 값만 모아 두었다가 주기적으로 보낸다
     * (접속자 목록은 서버에 따라 1초에도 몇 번씩 통째로 온다): 실행 번호 → 종류 → 보낼 내용
     */
    private val snaps = ConcurrentHashMap<String, ConcurrentHashMap<String, () -> JsonElement?>>()

    /** 모아 둔 것을 꺼내 보내는 동안의 잠금 (모아 둔 것이 상태 소식보다 늦게 나가지 않게 순서를 지킨다) */
    private val flushLock = Any()
    private var snapTicks = 0L

    /** 본 서버에 되물어 놓고 답을 기다리는 것 */
    private val asks = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val rids = AtomicLong()
    @Volatile private var peer: LinkPeer? = null
    @Volatile private var stopping = false
    private var failures = 0

    fun start() {
        cfg.dataDir.mkdirs()
        connect()
        timer.scheduleWithFixedDelay({ quietly { posTick() } }, 250, 250, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay({ quietly { snapTick() } }, 1, 1, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ quietly { trafficTick() } }, 60, 60, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({
            quietly {
                val rt = Runtime.getRuntime()
                peer?.send(jsonOf("t" to "load", "load" to runs.size, "heapMb" to (rt.totalMemory() - rt.freeMemory()) / 1_048_576))
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w("[노드] 주기 작업 오류", t)
        }
    }

    private fun connect() {
        if (stopping) return
        LinkNet.dial(cfg.masterHost, cfg.masterPort, psk, Hello(cfg.name, cfg.capacity, CLOUD_VERSION), group, { p ->
            failures = 0
            peer = p
            Log.i("[노드] 본 서버에 연결했습니다 (${cfg.master})")
            object : LinkHandler {
                override fun onMessage(msg: JsonObject) = handle(p, msg)
                override fun onClosed() {
                    dropAll()
                    retry(RETRY_SEC[0])
                }
            }
        }, { reason, rejected ->
            if (rejected) {
                Log.w("[노드] 본 서버가 거절했습니다: $reason")
                retry(60)
            } else {
                val wait = RETRY_SEC[failures.coerceAtMost(RETRY_SEC.size - 1)]
                failures++
                Log.w("[노드] 본 서버에 연결하지 못했습니다: $reason (${wait}초 뒤 다시 시도)")
                retry(wait)
            }
        })
    }

    private fun retry(sec: Int) {
        if (stopping) return
        timer.schedule({ quietly { connect() } }, sec.toLong(), TimeUnit.SECONDS)
    }

    /** 본 서버 연결을 잃었을 때: 돌리던 잠수를 모두 끊는다 */
    private fun dropAll() {
        peer = null
        val n = runs.size
        for (h in runs.values) h.shutdown()
        runs.clear()
        lastPos.clear()
        snaps.clear()
        for (f in asks.values) f.completeExceptionally(IOException("본 서버 연결이 끊겼습니다"))
        asks.clear()
        if (!stopping) Log.w("[노드] 본 서버 연결이 끊겨 잠수 ${n}개를 끊었습니다")
    }

    fun shutdown() {
        stopping = true
        Log.i("[노드] 접속용 서버를 끕니다")
        peer?.send(jsonOf("t" to "bye"))
        for (h in runs.values) h.shutdown()
        runs.clear()
        // bye 가 나갈 시간을 잠깐 준다
        Thread.sleep(300)
        peer?.close()
    }

    // ------------------------------------------------------------ 본 서버가 시킨 일

    private fun handle(p: LinkPeer, msg: JsonObject) {
        val t = msg.str("t") ?: return
        if (t == "creds" || t == "claim") {
            asks.remove(msg.long("rid") ?: return)?.complete(msg)
            return
        }
        val runId = msg.str("run") ?: return
        if (t == "launch") {
            launch(p, runId, msg)
            return
        }
        val h = runs[runId] ?: return
        when (t) {
            "stop" -> h.stop()
            "shutdown" -> {
                runs.remove(runId)
                lastPos.remove(runId)
                snaps.remove(runId)
                h.shutdown()
            }
            "chat" -> h.sendChat(msg.str("text") ?: return)
            "stick" -> h.setStick(msg.float("east") ?: 0f, msg.float("south") ?: 0f)
            "move" -> h.setMovement(msg.int("mask") ?: 0)
            "click" -> h.clickMenu(msg.int("slot") ?: return)
            "closeMenu" -> h.closeMenu()
            "suggest" -> {
                val rid = msg.long("rid") ?: return
                val asked = h.requestSuggestions((msg.str("text") ?: "").take(256)) { r ->
                    if (r == null) p.send(jsonOf("t" to "suggestR", "rid" to rid)) else {
                        val matches = JsonArray()
                        r.matches.take(200).forEach { matches.add(it) }
                        p.send(jsonOf("t" to "suggestR", "rid" to rid, "start" to r.start, "length" to r.length, "matches" to matches))
                    }
                }
                if (!asked) p.send(jsonOf("t" to "suggestR", "rid" to rid))
            }
        }
    }

    private fun launch(p: LinkPeer, runId: String, msg: JsonObject) {
        val uuid = msg.str("uuid") ?: return
        val entry = msg.obj("entry")?.let { Wire.parseEntry(it) } ?: return
        runs.remove(runId)?.shutdown()
        snaps.remove(runId)

        fun ev(kind: String, v: JsonElement?) {
            // 이 실행을 시킨 연결이 살아 있을 때만 (연결이 바뀐 뒤 늦게 온 소식은 버린다).
            // 끄는 중에 엔진이 알리는 "끊었습니다" 도 보내지 않는다 — 보내면 본 서버가 사용자가 끊은 것으로 알고 다른 곳으로 옮기지 않는다
            if (stopping || peer !== p) return
            p.send(jsonOf("t" to "ev", "run" to runId, "k" to kind, "v" to v))
        }

        /** 가장 최근 값만 남겨 둔다 (보내는 것은 snapTick) */
        fun snap(kind: String, json: () -> JsonElement?) {
            if (stopping || peer !== p) return
            snaps.getOrPut(runId) { ConcurrentHashMap() }[kind] = json
        }
        val listener = object : SessionListener {
            override fun onStatus(status: SessionStatus) = synchronized(flushLock) {
                // 모아 둔 접속자·체력을 먼저 보낸다 (끊긴 뒤에 예전 값이 뒤늦게 가서 화면에 남지 않게)
                flush(p, runId, SNAP_KINDS)
                ev("status", Wire.status(status))
            }
            override fun onLine(line: LogLine) = ev("line", Wire.line(line, 0))
            override fun onPlayers(list: PlayerList) = snap("players") { Wire.players(list) }
            override fun onMenu(menu: Menu?) = ev("menu", Wire.menu(menu))
            override fun onInventory(inventory: Inventory) = snap("inventory") { Wire.inventory(inventory) }
            override fun onVitals(vitals: Vitals?) = snap("vitals") { Wire.vitals(vitals) }
            override fun onDeath(death: DeathInfo) = ev("death", Wire.death(death))
            override fun onResourcePack(url: String, hash: String) = ev("pack", jsonOf("url" to url, "hash" to hash))
            override fun onDuplicateLogin() = ev("dup", null)
        }
        val hooks = object : LaunchHooks {
            override fun credentials(forceRefresh: Boolean): JoinCredentials {
                val r = ask(p, jsonOf("t" to "credsQ", "run" to runId, "force" to forceRefresh))
                r.str("error")?.let { throw AuthException(it, r.bool("relogin") == true) }
                // 받은 토큰은 이번 접속에만 쓰고 어디에도 저장하지 않는다
                return JoinCredentials(r.str("name") ?: "", UUID.fromString(r.str("uuid")), r.str("token"))
            }

            override fun claimTarget(connectHost: String, connectPort: Int) {
                // 본 서버의 접속용 서버 포트는 마인크래프트 서버가 아니다. 사용자가 그 주소를 적어 여기서 접속을 시도하게 두면,
                // 본 서버가 잘못된 접속으로 세어 이 컴퓨터의 IP 를 한동안 받지 않는다
                if (connectPort == cfg.masterPort && isMasterHost(connectHost)) throw IllegalArgumentException("이 주소로는 클라우드에서 접속할 수 없습니다")
                val r = ask(p, jsonOf("t" to "claimQ", "run" to runId, "host" to connectHost, "port" to connectPort))
                r.str("error")?.let { throw IllegalArgumentException(it) }
            }
        }
        runs[runId] = runner.launch(LaunchRequest(runId, uuid, entry), listener, hooks)
    }

    private fun isMasterHost(host: String): Boolean = try {
        val target = java.net.InetAddress.getByName(host)
        java.net.InetAddress.getAllByName(cfg.masterHost).any { it == target }
    } catch (_: Exception) {
        false
    }

    /** 본 서버에 묻고 답을 기다린다 (잠수 스레드에서 부른다) */
    private fun ask(p: LinkPeer, msg: JsonObject): JsonObject {
        val rid = rids.incrementAndGet()
        val f = CompletableFuture<JsonObject>()
        asks[rid] = f
        msg.addProperty("rid", rid)
        try {
            if (peer !== p) throw IOException("본 서버 연결이 끊겼습니다")
            p.send(msg)
            return f.get(ASK_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IOException("본 서버가 응답하지 않습니다")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IOException(e.cause?.message ?: "본 서버 연결이 끊겼습니다")
        } finally {
            asks.remove(rid)
        }
    }

    // ------------------------------------------------------------ 주기적으로 알리는 것

    /** [flushLock] 을 든 채 부른다. 그 실행의 모아 둔 상태 중 [kinds] 를 보낸다 */
    private fun flush(p: LinkPeer, runId: String, kinds: List<String>) {
        if (stopping || peer !== p) return
        val m = snaps[runId] ?: return
        for (k in kinds) {
            val json = m.remove(k) ?: continue
            p.send(jsonOf("t" to "ev", "run" to runId, "k" to k, "v" to json()))
        }
    }

    /** 모아 둔 상태를 보낸다 (1초마다. 접속자 목록은 본 서버가 화면에 내보내는 주기에 맞춰 2초마다) */
    private fun snapTick() {
        val p = peer ?: return
        val kinds = if (++snapTicks % 2 == 0L) SNAP_KINDS else SNAP_FAST
        synchronized(flushLock) {
            for ((runId, m) in snaps) {
                flush(p, runId, kinds)
                // 끝난 실행의 빈 자리는 치운다
                if (m.isEmpty() && !runs.containsKey(runId)) snaps.remove(runId, m)
            }
        }
    }

    /** 위치가 바뀐 잠수만 알린다 (0.25초마다) */
    private fun posTick() {
        val p = peer ?: return
        for ((runId, h) in runs) {
            val json = Wire.pos(h.position())
            val s = json.toString()
            if (lastPos.put(runId, s) != s) p.send(jsonOf("t" to "ev", "run" to runId, "k" to "pos", "v" to json))
        }
    }

    private fun trafficTick() {
        val p = peer ?: return
        for ((runId, h) in runs) {
            val t = h.traffic() ?: continue
            p.send(jsonOf("t" to "ev", "run" to runId, "k" to "traffic", "v" to jsonOf("rx" to t.received, "tx" to t.sent, "onlineMs" to t.onlineMs)))
        }
    }

    companion object {
        private val RETRY_SEC = intArrayOf(5, 10, 30, 60)
        private const val ASK_TIMEOUT_SEC = 20L
        private val SNAP_KINDS = listOf("players", "vitals", "inventory")
        private val SNAP_FAST = listOf("vitals", "inventory")
    }
}
