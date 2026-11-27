package kr.jamsuham.cloud.link

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.netty.channel.Channel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.DefaultThreadFactory
import kr.jamsuham.cloud.Config
import kr.jamsuham.cloud.LaunchHooks
import kr.jamsuham.cloud.LaunchRequest
import kr.jamsuham.cloud.Log
import kr.jamsuham.cloud.RunHandle
import kr.jamsuham.cloud.Runner
import kr.jamsuham.cloud.Scheduler
import kr.jamsuham.cloud.jsonOf
import kr.jamsuham.core.AuthException
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.Traffic
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.cloud.arr
import kr.jamsuham.core.cloud.bool
import kr.jamsuham.core.cloud.int
import kr.jamsuham.core.cloud.long
import kr.jamsuham.core.cloud.obj
import kr.jamsuham.core.cloud.str
import kr.jamsuham.core.cloud.strOrNull
import kr.jamsuham.core.net.CommandSuggestions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 본 서버 쪽: 접속용 서버(다른 컴퓨터)의 연결을 받아 실행기([Runner])로 등록한다.
 * 접속용 서버는 시킨 잠수를 돌리기만 하고, 로그인 정보·자리는 필요할 때마다 이쪽에 되묻는다.
 *
 * [onAttached]·[onLost] 와 접속용 서버가 보낸 소식은 모두 연결 스레드(하나)에서 차례로 불린다
 * → "끊김 처리가 끝난 뒤에야 같은 이름의 새 연결이 등록된다"가 보장된다.
 */
class MasterLink(
    private val config: Config,
    private val scheduler: Scheduler,
    private val onAttached: (runnerId: String) -> Unit,
    private val onLost: (runnerId: String, graceful: Boolean) -> Unit,
) {
    private val group = NioEventLoopGroup(1, DefaultThreadFactory("jamsu-link", true))

    /** 되묻기 처리용 (로그인 정보 갱신은 네트워크를 기다릴 수 있어 연결 스레드에서 하지 않는다) */
    private val rpc = Executors.newCachedThreadPool(DefaultThreadFactory("jamsu-link-rpc", true))
    private var server: Channel? = null

    fun start() {
        val keyFile = File(config.dataDir, "worker.key")
        val key = LinkCrypto.loadOrCreateKey(keyFile)
        server = LinkNet.serve(config.workerBind, config.workerPort, key, group) { hello, peer -> accept(hello, peer) }
        Log.i("접속용 서버를 받습니다: ${config.workerBind}:${config.workerPort} (비밀키 ${keyFile.absolutePath} — 이 값을 접속용 서버 설정의 secret 에 적습니다)")
    }

    fun stop() {
        server?.close()
        group.shutdownGracefully()
        rpc.shutdownNow()
    }

    private fun accept(hello: Hello, peer: LinkPeer): LinkHandler {
        val name = hello.name
        if (name == "local" || !NAME.matches(name)) {
            throw LinkNet.Reject("name", "접속용 서버 이름은 영문·숫자·-_ 로 64자까지이고 local 은 쓸 수 없습니다")
        }
        val runner = RemoteRunner(name, hello.capacity, peer, rpc)
        if (!scheduler.add(runner)) throw LinkNet.Reject("name", "같은 이름의 접속용 서버가 이미 연결돼 있습니다")
        Log.i("[노드] 연결됨: $name (자리 ${hello.capacity}, v${hello.version}, ${peer.remoteIp})")
        runner.onGone = { graceful ->
            scheduler.remove(name)
            Log.w("[노드] 끊김: $name" + if (graceful) " (그쪽에서 종료)" else "")
            onLost(name, graceful)
        }
        onAttached(name)
        return runner
    }

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/** 접속용 서버 하나 = 실행기 하나. 자기가 시킨 실행([runs])만 안다 */
private class RemoteRunner(
    override val id: String,
    override val capacity: Int,
    private val peer: LinkPeer,
    private val rpc: Executor,
) : Runner, LinkHandler {
    override val remote = true
    var onGone: (graceful: Boolean) -> Unit = {}
    private val runs = ConcurrentHashMap<String, RemoteHandle>()
    private val suggests = ConcurrentHashMap<Long, Pair<Long, (CommandSuggestions?) -> Unit>>()
    private val rids = AtomicLong()
    @Volatile private var gotBye = false

    override fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle {
        check(peer.alive) { "접속용 서버 연결이 끊겼습니다" }
        val h = RemoteHandle(req.runId, events, hooks)
        runs[req.runId] = h
        peer.send(jsonOf("t" to "launch", "run" to req.runId, "uuid" to req.uuid, "entry" to Wire.entry(req.entry)))
        return h
    }

    override fun onMessage(msg: JsonObject) {
        when (msg.str("t")) {
            // 자기에게 배정된 실행의 소식만 받는다 (모르는 실행은 무시)
            "ev" -> runs[msg.str("run") ?: return]?.event(msg.str("k") ?: return, msg.get("v"))
            "credsQ" -> {
                val rid = msg.long("rid") ?: return
                val h = runs[msg.str("run") ?: ""]
                    ?: return peer.send(jsonOf("t" to "creds", "rid" to rid, "error" to "이 접속용 서버에 배정되지 않은 잠수입니다", "relogin" to false))
                val force = msg.bool("force") == true
                rpc.execute {
                    val reply = try {
                        val c = h.hooks.credentials(force)
                        jsonOf("t" to "creds", "rid" to rid, "name" to c.name, "uuid" to c.uuid.toString(), "token" to c.accessToken)
                    } catch (e: AuthException) {
                        jsonOf("t" to "creds", "rid" to rid, "error" to (e.message ?: "로그인이 필요합니다"), "relogin" to e.needsRelogin)
                    } catch (t: Throwable) {
                        jsonOf("t" to "creds", "rid" to rid, "error" to (t.message ?: "로그인 정보를 가져오지 못했습니다"), "relogin" to false)
                    }
                    peer.send(reply)
                }
            }
            "claimQ" -> {
                val rid = msg.long("rid") ?: return
                val h = runs[msg.str("run") ?: ""]
                    ?: return peer.send(jsonOf("t" to "claim", "rid" to rid, "error" to "이 접속용 서버에 배정되지 않은 잠수입니다"))
                val host = (msg.str("host") ?: "").take(255)
                val port = msg.int("port") ?: 0
                rpc.execute {
                    val reply = try {
                        require(host.isNotEmpty() && port in 1..65535) { "주소가 올바르지 않습니다" }
                        h.hooks.claimTarget(host, port)
                        jsonOf("t" to "claim", "rid" to rid)
                    } catch (t: Throwable) {
                        jsonOf("t" to "claim", "rid" to rid, "error" to (t.message ?: "자리를 잡지 못했습니다"))
                    }
                    peer.send(reply)
                }
            }
            "suggestR" -> {
                val cb = suggests.remove(msg.long("rid") ?: return)?.second ?: return
                cb(if (msg.has("matches")) CommandSuggestions(msg.int("start") ?: 0, msg.int("length") ?: 0, msg.arr("matches").mapNotNull { it.strOrNull() }) else null)
            }
            "bye" -> gotBye = true
            // "load" 는 참고용 (자리 수는 본 서버가 배정 기록으로 센다)
        }
    }

    override fun onClosed() {
        runs.clear()
        suggests.clear()
        onGone(gotBye)
    }

    private inner class RemoteHandle(private val runId: String, private val events: SessionListener, val hooks: LaunchHooks) : RunHandle {
        @Volatile private var lastPos: Wire.Pos? = null
        @Volatile private var lastTraffic: Traffic? = null

        private fun send(t: String, vararg more: Pair<String, Any?>): Boolean {
            if (!peer.alive) return false
            peer.send(jsonOf("t" to t, "run" to runId, *more))
            return true
        }

        /** 접속용 서버가 보낸 소식을 내장 실행기와 똑같은 모양(SessionListener)으로 넘긴다 */
        fun event(kind: String, v: JsonElement?) {
            when (kind) {
                "status" -> events.onStatus(Wire.parseStatus(v as? JsonObject))
                "line" -> (v as? JsonObject)?.let { events.onLine(Wire.parseLine(it).second) }
                "players" -> events.onPlayers(Wire.parsePlayers(v as? JsonObject))
                "menu" -> events.onMenu(Wire.parseMenu(v))
                "inventory" -> events.onInventory(Wire.parseInventory(v as? JsonObject))
                "vitals" -> events.onVitals(Wire.parseVitals(v))
                "death" -> Wire.parseDeath(v)?.let { events.onDeath(it) }
                "pack" -> (v as? JsonObject)?.let { events.onResourcePack(it.str("url") ?: return, it.str("hash") ?: "") }
                "dup" -> events.onDuplicateLogin()
                "pos" -> lastPos = Wire.parsePos(v)
                "traffic" -> (v as? JsonObject)?.let { lastTraffic = Traffic(it.long("rx") ?: 0, it.long("tx") ?: 0, it.long("onlineMs") ?: 0) }
            }
        }

        override fun stop() { send("stop") }

        override fun shutdown() {
            send("shutdown")
            runs.remove(runId)
        }

        override fun sendChat(text: String) = send("chat", "text" to text)
        override fun setStick(east: Float, south: Float) { send("stick", "east" to east, "south" to south) }
        override fun setMovement(mask: Int) { send("move", "mask" to mask) }
        override fun clickMenu(slot: Int) = send("click", "slot" to slot)
        override fun closeMenu() { send("closeMenu") }

        override fun requestSuggestions(text: String, callback: (CommandSuggestions?) -> Unit): Boolean {
            val now = System.currentTimeMillis()
            // 답이 오지 않은 예전 요청은 치운다
            suggests.entries.removeIf { now - it.value.first > 10_000 }
            val rid = rids.incrementAndGet()
            suggests[rid] = now to callback
            return send("suggest", "rid" to rid, "text" to text)
        }

        override fun traffic(): Traffic? = lastTraffic
        override fun position(): Wire.Pos? = lastPos
    }
}
