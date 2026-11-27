package kr.jamsuham.core.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.jamsuham.core.net.CommandSuggestions
import kr.jamsuham.core.tr
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

/** 클라우드 서버가 "안 된다"고 답한 것 (네트워크 문제는 IOException). [code] 는 서버가 준 이유 코드 */
class CloudException(val status: Int, val code: String, message: String, val data: JsonObject? = null) : Exception(message)

/**
 * 잠수크래프트 클라우드 서버와 이야기하는 쪽 (앱이 쓴다. PC 시험 프로그램으로 같은 코드를 검증한다).
 * 모든 함수는 네트워크를 쓰므로 백그라운드 스레드에서 부를 것.
 */
class CloudClient(baseUrl: String, @Volatile var token: String? = null, private val userAgent: String = "Jamsucraft") {
    val base: String = normalize(baseUrl)

    class Login(val token: String, val account: JsonObject)

    // ------------------------------------------------------------ 요청

    private fun open(method: String, path: String, timeoutMs: Int): HttpURLConnection {
        val conn = URI.create("$base/api/$path").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = minOf(10_000, timeoutMs)
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("X-Jamsu-Client", "app")
        // 서버는 "바꾸는 요청"에 이 헤더가 있어야 받는다 (브라우저에서 다른 사이트가 몰래 보내는 요청을 막는 장치)
        conn.setRequestProperty("X-Jamsu", "1")
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return conn
    }

    @Throws(IOException::class, CloudException::class)
    fun request(method: String, path: String, body: JsonObject? = null, timeoutMs: Int = 20_000): JsonObject {
        val conn = open(method, path, timeoutMs)
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            } else if (method != "GET") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            val json = try {
                JsonParser.parseString(text) as? JsonObject
            } catch (_: Exception) {
                null
            }
            if (status !in 200..299) {
                throw CloudException(status, json?.str("error") ?: "http_$status", json?.str("message") ?: tr("클라우드 서버 오류 ($status)", "Cloud server error ($status)"), json)
            }
            return json ?: throw IOException(tr("클라우드 서버의 답을 읽지 못했습니다 (주소가 맞는지 확인해 주세요)", "Couldn't read the cloud server's reply (check the address)"))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 계정

    /** 이 주소가 잠수크래프트 클라우드 서버인지 확인하고 정보를 돌려준다 */
    fun info(): JsonObject {
        val o = request("GET", "info")
        if (o.str("name") != "jamsucraft-cloud") throw IOException(tr("잠수크래프트 클라우드 서버가 아닙니다", "This isn't a Jamsucraft cloud server"))
        return o
    }

    fun me(): JsonObject = request("GET", "me")

    /** 앱이 가진 로그인 정보를 넘겨 계정을 클라우드에 연결한다. 받은 토큰은 [token] 에도 넣는다 */
    fun loginTransfer(auth: JsonObject, device: String): Login {
        val body = JsonObject()
        body.add("auth", auth)
        body.addProperty("device", device)
        return login(request("POST", "login/transfer", body, 60_000))
    }

    /** 시험 전용 (서버를 -Djamsu.devLogin=true 로 켰을 때만) */
    fun loginDev(name: String, device: String): Login {
        val body = JsonObject()
        body.addProperty("name", name)
        body.addProperty("client", "app")
        body.addProperty("device", device)
        return login(request("POST", "login/dev", body))
    }

    private fun login(o: JsonObject): Login {
        val t = o.str("token") ?: throw IOException(tr("클라우드 서버가 토큰을 주지 않았습니다", "The cloud server returned no token"))
        token = t
        return Login(t, o.obj("account") ?: JsonObject())
    }

    fun logout() {
        request("POST", "logout")
    }

    /** 연결 해제: 클라우드의 잠수를 모두 끊고 서버에 저장된 이 계정의 정보를 지운다 */
    fun deleteAccount() {
        request("DELETE", "account")
    }

    // ------------------------------------------------------------ 서버·잠수

    fun putEntry(e: CloudEntry) {
        request("PUT", "servers/${e.id}", Wire.entry(e))
    }

    fun deleteEntry(id: String) {
        request("DELETE", "servers/$id")
    }

    /**
     * 클라우드에서 잠수 시작. 다른 매체에서 이미 접속 중이면 CloudException(code = "in_use") —
     * data 의 holder(app/cloud/other)·label·canForce 를 보고 안내한다. [force] 는 canForce 일 때만 통한다.
     */
    fun start(id: String, force: Boolean = false) {
        val body = JsonObject()
        body.addProperty("force", force)
        request("POST", "servers/$id/start", body)
    }

    fun stop(id: String) {
        request("POST", "servers/$id/stop")
    }

    fun chat(id: String, text: String) {
        val body = JsonObject()
        body.addProperty("text", text)
        request("POST", "servers/$id/chat", body)
    }

    fun menuClick(id: String, slot: Int) {
        val body = JsonObject()
        body.addProperty("slot", slot)
        request("POST", "servers/$id/menu/click", body)
    }

    /**
     * 이동 입력: [east] 동(+X)쪽, [south] 남(+Z)쪽 성분 (-1~1), [jump] 점프. 0, 0, false 면 멈춤.
     * 누르고 있는 동안 0.5초마다 다시 보내야 한다 (1.5초 동안 소식이 없으면 클라우드가 스스로 멈춘다 — 연결이 끊겨 계속 걸어가는 일을 막음).
     */
    fun move(id: String, east: Float, south: Float, jump: Boolean = false) {
        val body = JsonObject()
        body.addProperty("east", east)
        body.addProperty("south", south)
        body.addProperty("jump", jump)
        request("POST", "servers/$id/move", body, 8000)
    }

    fun menuClose(id: String) {
        request("POST", "servers/$id/menu/close")
    }

    fun clearDeath(id: String) {
        request("POST", "servers/$id/death/clear")
    }

    fun suggest(id: String, text: String): CommandSuggestions? {
        val body = JsonObject()
        body.addProperty("text", text)
        val o = request("POST", "servers/$id/suggest", body)
        val matches = o.arr("matches").mapNotNull { it.strOrNull() }
        if (matches.isEmpty()) return null
        return CommandSuggestions(o.int("start") ?: 0, o.int("length") ?: 0, matches)
    }

    /**
     * 이 기기가 폰에서 직접 잠수 중인 서버들(주소, 상태)을 알리고, 이 계정이 지금 어느 매체에서 돌고 있는지 목록을 받는다.
     * 1분마다, 그리고 시작·끊을 때 부른다 (2분 30초 동안 소식이 없으면 서버는 끝난 것으로 본다).
     */
    fun reportPresence(sessions: List<Pair<String, String>>): List<Holder> {
        val arr = JsonArray()
        for ((address, state) in sessions) {
            val o = JsonObject()
            o.addProperty("address", address)
            o.addProperty("state", state)
            arr.add(o)
        }
        val body = JsonObject()
        body.add("sessions", arr)
        return parseHolders(request("POST", "presence", body))
    }

    fun presence(timeoutMs: Int = 20_000): List<Holder> = parseHolders(request("GET", "presence", timeoutMs = timeoutMs))

    /** 어느 매체가 어느 서버에 접속 중인지. [holder] cloud / app / other(PC 게임 등) */
    data class Holder(val key: String, val address: String, val holder: String, val label: String, val state: String?, val sinceMs: Long?, val sid: String?)

    // ------------------------------------------------------------ 실시간 이벤트

    @Volatile private var streamConn: HttpURLConnection? = null

    /**
     * 실시간 이벤트를 받는다 (끊길 때까지 돌아오지 않는다). 처음에 hello(현재 상태 전체)가 오고,
     * [since] 뒤의 채팅 줄이 이어서 온 다음 ready, 그 뒤로 실시간 이벤트가 온다.
     * 서버가 15초마다 보내는 신호가 45초 동안 없으면 끊긴 것으로 본다.
     */
    @Throws(IOException::class, CloudException::class)
    fun stream(since: Long?, onOpen: () -> Unit, alive: () -> Boolean = { true }, onEvent: (type: String, data: JsonObject, id: Long?) -> Unit) {
        val conn = open("GET", "events" + (since?.let { "?since=$it" } ?: ""), 45_000)
        conn.setRequestProperty("Accept", "text/event-stream")
        streamConn = conn
        try {
            val status = conn.responseCode
            if (status != 200) {
                val text = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                val json = try {
                    JsonParser.parseString(text) as? JsonObject
                } catch (_: Exception) {
                    null
                }
                throw CloudException(status, json?.str("error") ?: "http_$status", json?.str("message") ?: tr("클라우드 서버 오류 ($status)", "Cloud server error ($status)"), json)
            }
            onOpen()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var type = "message"
                var id: Long? = null
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    // 그만 받기로 했으면(다른 스레드가 끊으려 했는데 못 끊은 경우 포함) 다음 신호 때 끝낸다
                    if (!alive()) break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                val json = try {
                                    JsonParser.parseString(data.toString()) as? JsonObject
                                } catch (_: Exception) {
                                    null
                                }
                                if (json != null) onEvent(type, json, id)
                            }
                            type = "message"
                            id = null
                            data.setLength(0)
                        }
                        line.startsWith(":") -> {}
                        line.startsWith("event:") -> type = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trimStart())
                        line.startsWith("id:") -> id = line.substring(3).trim().toLongOrNull()
                    }
                }
            }
        } finally {
            if (streamConn === conn) streamConn = null
            conn.disconnect()
        }
    }

    /** 다른 스레드에서 [stream] 을 끊는다 */
    fun closeStream() {
        try {
            streamConn?.disconnect()
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun parseHolders(o: JsonObject): List<Holder> = o.arr("presence").mapNotNull { el ->
            val j = el as? JsonObject ?: return@mapNotNull null
            Holder(j.str("key") ?: return@mapNotNull null, j.str("address") ?: "", j.str("holder") ?: "", j.str("label") ?: "", j.str("state"), j.long("since"), j.str("sid"))
        }

        /**
         * 사용자가 적은 주소를 정리한다. 로그인 토큰이 오가므로 https 만 받는다
         * (같은 공유기 안의 주소·이 기기 자신은 시험용으로 http 허용).
         */
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) throw IllegalArgumentException(tr("클라우드 서버 주소를 입력해 주세요", "Please enter the cloud server address"))
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            val uri = try {
                URI.create(u)
            } catch (_: Exception) {
                throw IllegalArgumentException(tr("클라우드 서버 주소가 올바르지 않습니다", "The cloud server address is invalid"))
            }
            val host = uri.host ?: throw IllegalArgumentException(tr("클라우드 서버 주소가 올바르지 않습니다", "The cloud server address is invalid"))
            if (uri.scheme == "http" && !isLocalHost(host)) {
                throw IllegalArgumentException(tr("https:// 주소만 쓸 수 있습니다 (로그인 정보를 보호하기 위해)", "Only https:// addresses are allowed (to protect your sign-in data)"))
            }
            return u
        }

        fun isLocalHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
            val p = h.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || h.count { it == '.' } != 3) return false
            return p[0] == 127 || p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
        }
    }
}

/**
 * 실시간 연결을 계속 붙여 두는 일꾼. 끊기면 2 → 4 → 8 → 15 → 30초 간격으로 다시 붙고,
 * 붙을 때마다 [since] 가 알려 주는 "마지막으로 받은 줄 순번" 뒤부터 이어 받는다.
 */
class CloudStream(
    private val client: CloudClient,
    private val since: () -> Long?,
    private val listener: Listener,
) {
    interface Listener {
        /** 붙었다(true) / 끊겼다(false, [error] 이유) */
        fun onConnection(connected: Boolean, error: String?) {}
        fun onEvent(type: String, data: JsonObject, id: Long?)
        /** 로그인이 풀렸다 (다른 곳에서 연결 해제했거나 만료). 더 붙지 않는다 */
        fun onAuthLost() {}
    }

    @Volatile private var thread: Thread? = null
    @Volatile private var stopped = true
    private val wake = Object()

    val running: Boolean get() = !stopped

    @Synchronized
    fun start() {
        if (!stopped) return
        stopped = false
        thread = Thread({ loop() }, "jamsuham-cloud-stream").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        stopped = true
        client.closeStream()
        synchronized(wake) { wake.notifyAll() }
        thread = null
    }

    /** 기다리는 중이면 바로 다시 붙는다 (네트워크가 돌아왔을 때) */
    fun retryNow() {
        synchronized(wake) { wake.notifyAll() }
    }

    private fun loop() {
        var failures = 0
        while (!stopped) {
            var opened = false
            var error: String? = null
            try {
                client.stream(since(), {
                    opened = true
                    if (!stopped) listener.onConnection(true, null)
                }, { !stopped }) { type, data, id ->
                    if (!stopped) listener.onEvent(type, data, id)
                }
            } catch (e: CloudException) {
                if (e.status == 401) {
                    if (!stopped) {
                        stopped = true
                        listener.onAuthLost()
                    }
                    return
                }
                error = e.message
            } catch (e: IOException) {
                error = e.message ?: e.javaClass.simpleName
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            if (stopped) return
            listener.onConnection(false, error)
            failures = if (opened) 0 else failures + 1
            val waitSec = longArrayOf(2, 4, 8, 15, 30)[failures.coerceIn(0, 4)]
            synchronized(wake) {
                if (!stopped) {
                    try {
                        wake.wait(waitSec * 1000)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
    }
}
