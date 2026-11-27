package kr.jamsuham.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.cloud.arr
import kr.jamsuham.core.cloud.bool
import kr.jamsuham.core.cloud.int
import kr.jamsuham.core.cloud.obj
import kr.jamsuham.core.cloud.str
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 요청을 이 응답으로 끝낸다 */
class ApiError(val status: Int, val code: String, message: String, val extra: JsonObject? = null) : Exception(message)

/**
 * 웹 화면(정적 파일)과 API, 실시간 이벤트(SSE)를 내보내는 HTTP 서버.
 * 인증: 웹은 쿠키(자바스크립트가 읽을 수 없음), 앱은 Authorization: Bearer 토큰.
 */
class HttpApi(
    private val config: Config,
    private val store: DataStore,
    private val premium: Premium,
    private val auth: MsAuth,
    private val sessions: SessionManager,
) {
    private lateinit var server: HttpServer
    private val limiter = RateLimiter()
    /** 열려 있는 실시간 연결들 (연결 → 그 연결을 강제로 닫는 방법) */
    private val streams = ConcurrentHashMap<Subscriber, HttpExchange>()
    private val sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "http-sweeper").apply { isDaemon = true } }

    fun start() {
        // 응답이 조금씩 나가는 실시간 연결이 바로바로 전달되게
        System.setProperty("sun.net.httpserver.nodelay", "true")
        // 요청을 아주 천천히 보내며 연결을 붙잡는 공격을 줄인다 (응답 시간 제한은 실시간 연결 때문에 두지 않는다)
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) System.setProperty("sun.net.httpserver.maxReqTime", "30")
        server = HttpServer.create(InetSocketAddress(config.bind, config.port), 200)
        // 실시간 연결 하나가 스레드 하나를 쓴다
        server.executor = ThreadPoolExecutor(8, 600, 60, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "http").apply { isDaemon = true }
        }
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        // 끊기로 했는데 3초가 지나도 안 끝난 실시간 연결은 소켓을 닫아 버린다.
        // (받는 쪽이 읽지 않으면 내보내는 스레드가 쓰기에서 멈춰 있어서, 닫아 주지 않으면 스레드가 계속 묶여 있다)
        sweeper.scheduleWithFixedDelay({
            try {
                val now = System.currentTimeMillis()
                for ((sub, ex) in streams) {
                    if (sub.closed && now - sub.closedAt > 3000) {
                        streams.remove(sub)
                        try {
                            ex.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        sweeper.shutdownNow()
        try {
            server.stop(0)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ 공통

    private class Who(val token: TokenRec, val account: AccountRec, val viaCookie: Boolean)

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path ?: "/"
            ex.responseHeaders.apply {
                set("X-Content-Type-Options", "nosniff")
                set("X-Frame-Options", "DENY")
                set("Referrer-Policy", "no-referrer")
                set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https://textures.minecraft.net; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            }
            if (path.startsWith("/api/")) api(ex, path.removePrefix("/api/").trim('/').split('/')) else static(ex, path)
        } catch (e: ApiError) {
            val o = jsonOf("error" to e.code, "message" to e.message)
            e.extra?.entrySet()?.forEach { (k, v) -> o.add(k, v) }
            send(ex, e.status, o)
        } catch (_: IOException) {
            // 상대가 연결을 끊음
        } catch (t: Throwable) {
            Log.w("요청 처리 오류 ${ex.requestMethod} ${ex.requestURI.path}", t)
            try {
                send(ex, 500, jsonOf("error" to "server_error", "message" to "서버 오류가 났습니다"))
            } catch (_: Throwable) {
            }
        } finally {
            ex.close()
        }
    }

    private fun send(ex: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun ok(ex: HttpExchange, vararg pairs: Pair<String, Any?>) = send(ex, 200, jsonOf("ok" to true, *pairs))

    /** 요청 본문(JSON 객체). 64KB 넘으면 거절 ([max] 로 바꿀 수 있음) */
    private fun body(ex: HttpExchange, max: Int = 65_536): JsonObject {
        val bytes = ex.requestBody.readNBytes(max + 1)
        if (bytes.size > max) throw ApiError(413, "too_large", "요청이 너무 큽니다")
        if (bytes.isEmpty()) return JsonObject()
        return try {
            JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            throw ApiError(400, "bad_json", "요청 형식이 올바르지 않습니다")
        }
    }

    /**
     * 요청한 쪽을 묶는 이름 (요청 횟수 제한용). 앞에 프록시가 있으면 설정한 헤더 하나에서만 읽는다.
     * IP 모양이 아닌 값은 버리고(접속자가 아무 글자나 넣어 보낼 수 있다), IPv6 는 앞 64비트로 묶는다
     * (IPv6 사용자는 주소를 수없이 바꿀 수 있어서 주소 하나하나로 세면 제한이 소용없다).
     */
    private fun clientIp(ex: HttpExchange): String {
        val remote = ex.remoteAddress.address
        if (config.trustProxy && remote != null && (remote.isLoopbackAddress || remote.isSiteLocalAddress)) {
            val raw = when (config.clientIpHeader.lowercase()) {
                "cf-connecting-ip" -> ex.requestHeaders.getFirst("CF-Connecting-IP")
                // 프록시는 자기가 본 주소를 맨 뒤에 붙인다. 앞쪽은 접속자가 적어 보낸 것일 수 있다
                "x-forwarded-for" -> ex.requestHeaders["X-Forwarded-For"]?.lastOrNull()?.split(',')?.lastOrNull()
                else -> null
            }?.trim()
            if (raw != null) bucket(raw)?.let { return it }
        }
        return remote?.let { bucket(it.hostAddress) } ?: "?"
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]{2,45}$")

    private fun bucket(ip: String): String? {
        val v = ip.substringBefore('%')
        if (IPV4.matches(v)) return v
        if (!v.contains(':') || !IPV6.matches(v)) return null
        return try {
            // 글자 그대로의 IP 는 DNS 를 묻지 않고 바로 해석된다
            val b = java.net.InetAddress.getByName(v).address
            if (b.size == 4) b.joinToString(".") { (it.toInt() and 0xFF).toString() }
            else b.take(8).joinToString("") { "%02x".format(it) } + "::/64"
        } catch (_: Exception) {
            null
        }
    }

    private fun isHttps(ex: HttpExchange): Boolean =
        config.trustProxy && ex.requestHeaders.getFirst("X-Forwarded-Proto")?.equals("https", ignoreCase = true) == true

    private fun cookie(ex: HttpExchange, name: String): String? {
        for (h in ex.requestHeaders["Cookie"] ?: emptyList()) {
            for (part in h.split(';')) {
                val i = part.indexOf('=')
                if (i > 0 && part.substring(0, i).trim() == name) return part.substring(i + 1).trim()
            }
        }
        return null
    }

    private fun setCookie(ex: HttpExchange, token: String?) {
        val secure = if (isHttps(ex)) "; Secure" else ""
        val v = if (token != null) "$COOKIE=$token; Max-Age=${90 * 86400}" else "$COOKIE=; Max-Age=0"
        ex.responseHeaders.add("Set-Cookie", "$v; Path=/; HttpOnly; SameSite=Strict$secure")
    }

    /** 로그인한 요청인지 확인한다. 아니면 401 */
    private fun who(ex: HttpExchange): Who {
        val bearer = ex.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim()
        val raw = bearer ?: cookie(ex, COOKIE) ?: throw ApiError(401, "login_required", "로그인이 필요합니다")
        val token = store.findToken(raw) ?: throw ApiError(401, "login_required", "로그인이 만료됐습니다. 다시 로그인해 주세요")
        val acc = store.account(token.uuid) ?: throw ApiError(401, "login_required", "계정을 찾을 수 없습니다. 다시 로그인해 주세요")
        // 시험용(닉네임) 계정은 시험 모드로 켰을 때만 쓸 수 있다
        if (acc.dev && !config.devLogin) throw ApiError(401, "login_required", "쓸 수 없는 계정입니다")
        return Who(token, acc, viaCookie = bearer == null)
    }

    /**
     * 쿠키로 로그인한 브라우저의 "바꾸는 요청"은 우리 웹 화면이 보낸 것만 받는다 (다른 사이트가 몰래 보내는 요청 차단).
     * 다른 사이트는 이 헤더를 붙일 수 없다.
     */
    private fun guardWrite(ex: HttpExchange) {
        if (ex.requestMethod == "GET" || ex.requestMethod == "HEAD") return
        val hasBearer = ex.requestHeaders.getFirst("Authorization")?.startsWith("Bearer ") == true
        if (!hasBearer && ex.requestHeaders.getFirst("X-Jamsu") == null) throw ApiError(403, "forbidden", "허용되지 않은 요청입니다")
    }

    private fun limit(key: String, max: Int, windowSec: Int, message: String = "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요") {
        if (!limiter.allow(key, max, windowSec)) throw ApiError(429, "rate_limited", message)
    }

    private fun deviceLabel(ex: HttpExchange, kind: String, given: String?): String {
        val name = given?.replace(Regex("[\\p{Cntrl}]"), "")?.trim()?.take(40)
        if (kind == "app") return "앱 · " + (name?.ifEmpty { null } ?: "휴대폰")
        val ua = ex.requestHeaders.getFirst("User-Agent") ?: ""
        val browser = when {
            ua.contains("Edg/") -> "Edge"
            ua.contains("Whale/") -> "Whale"
            ua.contains("SamsungBrowser/") -> "삼성 인터넷"
            ua.contains("Firefox/") -> "Firefox"
            ua.contains("Chrome/") -> "Chrome"
            ua.contains("Safari/") -> "Safari"
            else -> "브라우저"
        }
        return "웹 · $browser"
    }

    // ------------------------------------------------------------ API

    private fun api(ex: HttpExchange, seg: List<String>) {
        val method = ex.requestMethod
        val ip = clientIp(ex)
        guardWrite(ex)
        limit("ip:$ip", 600, 60)
        val a = seg.getOrNull(0) ?: ""
        val b = seg.getOrNull(1)
        val c = seg.getOrNull(2)
        when {
            // 누구나: 이 서버가 무엇인지 (앱이 주소를 확인할 때 씀)
            a == "info" && method == "GET" -> send(ex, 200, jsonOf(
                "name" to "jamsucraft-cloud", "api" to Wire.API, "version" to CLOUD_VERSION,
                "msLogin" to (auth.msaConfig != null), "dev" to config.devLogin.takeIf { it },
                "premiumMode" to config.premiumMode,
            ))

            // ---- 로그인 (웹: 기기 코드)
            a == "login" && b == "device" && c == null && method == "POST" -> {
                limit("login:$ip", 8, 600, "로그인 시도가 너무 많습니다. 10분 뒤 다시 시도해 주세요")
                // 이 로그인을 시작한 브라우저만 결과를 받아 갈 수 있게, 그 브라우저에만 쿠키로 값을 하나 준다
                val nonce = randomToken()
                val d = try {
                    auth.startDevice(nonce, ip)
                } catch (e: LoginException) {
                    throw ApiError(if (e.code == "busy") 429 else 503, e.code, e.message ?: "")
                }
                val secure = if (isHttps(ex)) "; Secure" else ""
                ex.responseHeaders.add("Set-Cookie", "$LOGIN_COOKIE=$nonce; Max-Age=1200; Path=/api/login; HttpOnly; SameSite=Strict$secure")
                send(ex, 200, jsonOf(
                    "id" to d.id, "userCode" to d.code.userCode, "url" to d.code.verificationUri,
                    "directUrl" to d.code.directVerificationUri,
                    "expiresInSec" to ((d.code.expireTimeMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0),
                ))
            }
            // 결과 확인. 주소가 아니라 본문으로 id 를 받고(주소는 프록시 기록에 남는다), 로그인 쿠키를 주므로 POST + 우리 화면의 요청만 받는다
            a == "login" && b == "device" && c == "poll" && method == "POST" -> {
                limit("poll:$ip", 120, 60)
                val id = body(ex).str("id") ?: throw ApiError(400, "bad_request", "로그인 요청 번호가 없습니다")
                val d = auth.device(id, cookie(ex, LOGIN_COOKIE))
                    ?: throw ApiError(404, "not_found", "로그인 요청을 찾을 수 없습니다. 처음부터 다시 해 주세요")
                when (d.state) {
                    "pending" -> send(ex, 200, jsonOf("state" to "pending"))
                    "error" -> {
                        auth.forgetDevice(id)
                        send(ex, 200, jsonOf("state" to "error", "error" to d.error?.code, "message" to d.error?.message))
                    }
                    else -> {
                        val (manager, profile) = d.result ?: throw ApiError(500, "server_error", "로그인 결과가 없습니다")
                        synchronized(d) {
                            if (d.claimed) throw ApiError(404, "not_found", "이미 처리된 로그인입니다")
                            d.claimed = true
                        }
                        auth.forgetDevice(id)
                        ex.responseHeaders.add("Set-Cookie", "$LOGIN_COOKIE=; Max-Age=0; Path=/api/login; HttpOnly; SameSite=Strict")
                        finishLogin(ex, manager, profile, "web", null)
                    }
                }
            }
            // ---- 로그인 (앱: 가진 로그인 정보를 넘김)
            a == "login" && b == "transfer" && method == "POST" -> {
                limit("login:$ip", 8, 600, "로그인 시도가 너무 많습니다. 10분 뒤 다시 시도해 주세요")
                val o = body(ex, 262_144)
                val authJson = o.obj("auth") ?: throw ApiError(400, "bad_request", "로그인 정보가 없습니다")
                val (manager, profile) = try {
                    auth.fromTransfer(authJson)
                } catch (e: LoginException) {
                    throw ApiError(400, e.code, e.message ?: "")
                }
                finishLogin(ex, manager, profile, "app", o.str("device"))
            }
            // ---- 시험 전용 로그인
            a == "login" && b == "dev" && method == "POST" -> {
                if (!config.devLogin) throw ApiError(404, "not_found", "없는 주소입니다")
                val o = body(ex)
                val acc = try {
                    auth.devLogin(o.str("name") ?: "")
                } catch (e: IllegalArgumentException) {
                    throw ApiError(400, "bad_request", e.message ?: "")
                }
                val kind = if (o.str("client") == "app") "app" else "web"
                issue(ex, acc, kind, o.str("device"))
            }

            a == "logout" && method == "POST" -> {
                val w = who(ex)
                sessions.closeSubs(w.account.uuid, w.token.hash)
                if (sessions.presence.forget(w.account.uuid, w.token.hash)) sessions.presenceChanged(w.account.uuid)
                store.revokeToken(w.token.hash)
                setCookie(ex, null)
                ok(ex)
            }
            // 연결 해제: 잠수를 끊고 서버에 저장된 이 계정의 모든 정보(로그인 토큰·서버 목록·기기)를 지운다
            a == "account" && b == null && method == "DELETE" -> {
                val w = who(ex)
                // 저장된 로그인 정보를 먼저 지운 뒤 세션을 정리한다 (지우는 사이에 온 시작 요청이 토큰을 다시 쓰지 못하게)
                auth.deleteAccount(w.account.uuid)
                sessions.dropAccount(w.account.uuid)
                setCookie(ex, null)
                Log.i("계정 삭제: ${w.account.name}")
                ok(ex)
            }
            a == "me" && method == "GET" -> {
                val w = who(ex)
                val devices = JsonArray()
                for (t in store.tokensOf(w.account.uuid).sortedByDescending { it.lastSeenAt }) {
                    devices.add(jsonOf("id" to t.hash.take(12), "label" to t.label, "kind" to t.kind, "lastSeenAt" to t.lastSeenAt, "current" to (t.hash == w.token.hash)))
                }
                ok(ex, "account" to sessions.accountJson(w.account), "devices" to devices,
                    "limits" to jsonOf("sessions" to config.maxSessionsPerAccount, "servers" to config.maxServersPerAccount))
            }

            // 다른 기기 하나를 로그아웃시킨다 (본인이 한 로그인이 아닌 기기가 보일 때)
            a == "devices" && b != null && c == "revoke" && method == "POST" -> {
                val w = who(ex)
                val hash = store.revokeDevice(w.account.uuid, b) ?: throw ApiError(404, "not_found", "기기를 찾을 수 없습니다")
                sessions.closeSubs(w.account.uuid, hash)
                if (sessions.presence.forget(w.account.uuid, hash)) sessions.presenceChanged(w.account.uuid)
                ok(ex)
            }

            a == "events" && method == "GET" -> events(ex, who(ex))

            // ---- 앱이 폰에서 직접 잠수 중인 서버를 알림 (1분마다) + 클라우드 쪽 상태를 받아 감
            a == "presence" && method == "POST" -> {
                val w = who(ex)
                val o = body(ex)
                val list = o.arr("sessions").mapNotNull { el ->
                    val j = el as? JsonObject ?: return@mapNotNull null
                    (j.str("address") ?: return@mapNotNull null) to (j.str("state") ?: "ONLINE")
                }
                if (sessions.presence.report(w.account.uuid, w.token.hash, w.token.label, list)) sessions.presenceChanged(w.account.uuid)
                ok(ex, "presence" to sessions.presenceOf(w.account.uuid))
            }
            a == "presence" && method == "GET" -> {
                val w = who(ex)
                ok(ex, "presence" to sessions.presenceOf(w.account.uuid))
            }

            a == "servers" && b != null -> server(ex, who(ex), b, c, seg.getOrNull(3), method)

            else -> throw ApiError(404, "not_found", "없는 주소입니다")
        }
    }

    /** 로그인 마무리: 프리미엄 확인 → 저장 → 토큰 발급 */
    private fun finishLogin(ex: HttpExchange, manager: net.raphimc.minecraftauth.java.JavaAuthManager, profile: MsAuth.Profile, kind: String, device: String?) {
        if (!premium.status(profile.uuid, profile.name).active) {
            // 프리미엄이 아닌 계정의 로그인 토큰은 서버에 저장하지 않는다.
            // 예전에 프리미엄이었을 때 저장해 둔 것이 남아 있으면 그것도 지운다 (본인이 로그인했으므로 본인 확인이 된 것)
            if (store.account(profile.uuid) != null) {
                auth.deleteAccount(profile.uuid)
                sessions.dropAccount(profile.uuid)
            }
            Log.i("프리미엄이 아닌 계정의 로그인: ${profile.name} (${profile.uuid})")
            throw ApiError(402, "not_premium", "${profile.name} 계정은 클라우드 잠수(프리미엄)를 쓸 수 있는 계정이 아닙니다",
                jsonOf("name" to profile.name, "uuid" to profile.uuid))
        }
        val acc = auth.saveLogin(manager, profile)
        Log.i("로그인: ${acc.name} ($kind)")
        issue(ex, acc, kind, device)
    }

    private fun issue(ex: HttpExchange, acc: AccountRec, kind: String, device: String?) {
        val label = deviceLabel(ex, kind, device)
        // 이미 로그인해 있는 화면들에 알린다 (본인이 한 로그인이 아니면 알아채고 그 기기를 로그아웃시킬 수 있게)
        sessions.notify(acc.uuid, "newDevice", jsonOf("label" to label, "kind" to kind))
        val (token, evicted) = store.issueToken(acc.uuid, kind, label)
        for (h in evicted) sessions.closeSubs(acc.uuid, h)
        if (kind == "web") {
            // 웹은 토큰을 쿠키로만 준다 (화면의 자바스크립트가 읽을 수 없어 훔치기 어렵다)
            setCookie(ex, token)
            send(ex, 200, jsonOf("state" to "done", "account" to sessions.accountJson(acc)))
        } else {
            send(ex, 200, jsonOf("state" to "done", "token" to token, "account" to sessions.accountJson(acc)))
        }
    }

    private fun server(ex: HttpExchange, w: Who, id: String, action: String?, sub: String?, method: String) {
        val uuid = w.account.uuid
        if (!Wire.ID_REGEX.matches(id)) throw ApiError(400, "bad_request", "서버 id 가 올바르지 않습니다")
        when {
            action == null && method == "PUT" -> {
                val o = body(ex)
                o.addProperty("id", id)
                val entry = Wire.parseEntry(o) ?: throw ApiError(400, "bad_request", "서버 설정이 올바르지 않습니다")
                if (entry.address.isBlank()) throw ApiError(400, "bad_request", "서버 주소를 입력해 주세요")
                sessions.putEntry(uuid, entry)?.let { throw ApiError(409, "limit", it) }
                ok(ex)
            }
            action == null && method == "DELETE" -> {
                if (!sessions.deleteEntry(uuid, id)) throw ApiError(404, "not_found", "서버를 찾을 수 없습니다")
                ok(ex)
            }
            action == "start" && method == "POST" -> {
                val force = body(ex).bool("force") == true
                when (val r = sessions.start(w.account, id, force)) {
                    is StartResult.Ok -> ok(ex)
                    is StartResult.NotFound -> throw ApiError(404, "not_found", "서버를 찾을 수 없습니다")
                    is StartResult.NotPremium -> throw ApiError(402, "not_premium", "클라우드 잠수는 프리미엄 계정만 쓸 수 있습니다")
                    is StartResult.Limit -> throw ApiError(409, "limit", r.message)
                    is StartResult.Bad -> throw ApiError(400, "bad_request", r.message)
                    is StartResult.InUse -> throw ApiError(
                        409, "in_use",
                        if (r.kind == "other") "${r.label}에서 이 서버에 접속 중일 수 있습니다. 지금 시작하면 그쪽 접속이 끊깁니다"
                        else "이미 ${r.label}에서 실행 중입니다. 그쪽 연결을 해제하고 다시 시도해 주세요",
                        jsonOf("holder" to r.kind, "label" to r.label, "canForce" to r.canForce),
                    )
                }
            }
            action == "stop" && method == "POST" -> {
                if (!sessions.stop(uuid, id)) throw ApiError(404, "not_found", "서버를 찾을 수 없습니다")
                ok(ex)
            }
            action == "chat" && method == "POST" -> {
                sessions.chat(uuid, id, body(ex).str("text") ?: "")?.let { throw ApiError(409, "not_sent", it) }
                ok(ex)
            }
            action == "move" && method == "POST" -> {
                // 누르고 있는 동안 계속 다시 보내므로 넉넉하게 (1초에 20번까지)
                limit("move:$uuid", 1200, 60)
                val o = body(ex)
                fun f(k: String) = o.get(k)?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asFloat else null } ?: 0f
                sessions.move(uuid, id, f("east"), f("south"), o.bool("jump") == true)?.let { throw ApiError(409, "not_sent", it) }
                ok(ex)
            }
            action == "menu" && sub == "click" && method == "POST" -> {
                val slot = body(ex).int("slot") ?: throw ApiError(400, "bad_request", "칸 번호가 없습니다")
                if (!sessions.menuClick(uuid, id, slot)) throw ApiError(409, "not_sent", "지금은 누를 수 없습니다 (메뉴가 닫혔거나 접속 중이 아님)")
                ok(ex)
            }
            action == "menu" && sub == "close" && method == "POST" -> {
                sessions.menuClose(uuid, id)
                ok(ex)
            }
            action == "death" && sub == "clear" && method == "POST" -> {
                sessions.clearDeath(uuid, id)
                ok(ex)
            }
            action == "suggest" && method == "POST" -> {
                limit("suggest:$uuid", 120, 60)
                val text = body(ex).str("text") ?: ""
                val s = sessions.suggest(uuid, id, text)
                val matches = JsonArray()
                s?.matches?.take(50)?.forEach { matches.add(it) }
                ok(ex, "start" to (s?.start ?: 0), "length" to (s?.length ?: 0), "matches" to matches)
            }
            else -> throw ApiError(404, "not_found", "없는 주소입니다")
        }
    }

    // ------------------------------------------------------------ 실시간 이벤트 (SSE)

    private fun events(ex: HttpExchange, w: Who) {
        val query = ex.requestURI.rawQuery ?: ""
        // 끊겼다 다시 붙는 브라우저는 마지막으로 받은 줄 순번을 Last-Event-ID 로 알려 준다
        val since = ex.requestHeaders.getFirst("Last-Event-ID")?.toLongOrNull()
            ?: Regex("(?:^|&)since=(\\d+)").find(query)?.groupValues?.get(1)?.toLongOrNull()
        ex.responseHeaders.apply {
            set("Content-Type", "text/event-stream; charset=utf-8")
            set("Cache-Control", "no-cache, no-transform")
            set("X-Accel-Buffering", "no")
        }
        // 실시간 연결 하나가 스레드 하나를 쓰므로 전체 수에 한도를 둔다 (API 요청이 쓸 스레드를 남겨 둔다)
        if (streams.size >= MAX_STREAMS) throw ApiError(503, "busy", "지금 연결이 너무 많습니다. 잠시 뒤 다시 시도해 주세요")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        val sub = Subscriber(w.token.hash)
        streams[sub] = ex
        try {
            out.write("retry: 3000\n\n".toByteArray())
            out.flush()
            sessions.attach(w.account, sub, since)
            while (!sub.closed) {
                val e = sub.queue.poll(15, TimeUnit.SECONDS)
                if (e == null) {
                    // 조용할 때도 연결이 살아 있는지 양쪽이 알 수 있게
                    out.write(": ping\n\n".toByteArray())
                    out.flush()
                    // 그 사이 로그아웃·기기 정리·계정 삭제됐으면 끊는다
                    if (!store.tokenAlive(w.token.hash) || store.account(w.account.uuid) == null) break
                    continue
                }
                if (e === Subscriber.WAKE) continue
                sub.taken(e)
                val sb = StringBuilder(e.data.length + 48)
                if (e.id != null) sb.append("id: ").append(e.id).append('\n')
                sb.append("event: ").append(e.type).append('\n')
                sb.append("data: ").append(e.data).append("\n\n")
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                // 밀린 것이 없을 때만 내보낸다 (줄이 몰릴 때 한 번에)
                if (sub.queue.isEmpty()) out.flush()
            }
            // 남은 마지막 이벤트(bye)까지 보내고 끝낸다
            while (true) {
                val e = sub.queue.poll() ?: break
                if (e === Subscriber.WAKE || e.type != "bye") continue
                out.write("event: ${e.type}\ndata: ${e.data}\n\n".toByteArray(Charsets.UTF_8))
            }
            out.flush()
        } catch (_: InterruptedException) {
        } finally {
            sub.close()
            streams.remove(sub)
            sessions.detach(w.account.uuid, sub)
        }
    }

    // ------------------------------------------------------------ 웹 화면 파일

    private val staticCache = ConcurrentHashMap<String, ByteArray>()

    private fun static(ex: HttpExchange, path: String) {
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") throw ApiError(405, "method", "허용되지 않는 요청입니다")
        val name = when (path) {
            "/", "/index.html" -> "index.html"
            else -> path.removePrefix("/")
        }
        val type = TYPES[name.substringAfterLast('.', "")]
        if (type == null || !name.matches(Regex("[A-Za-z0-9_.-]+"))) throw ApiError(404, "not_found", "없는 주소입니다")
        val bytes = staticCache[name] ?: (javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() }
            ?: throw ApiError(404, "not_found", "없는 주소입니다")).also { if (!config.devLogin) staticCache[name] = it }
        ex.responseHeaders.set("Content-Type", type)
        ex.responseHeaders.set("Cache-Control", "no-cache")
        if (ex.requestMethod == "HEAD") {
            ex.sendResponseHeaders(200, -1)
        } else {
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        const val COOKIE = "jamsu_session"
        /** 기기 코드 로그인을 시작한 브라우저를 알아보는 쿠키 */
        const val LOGIN_COOKIE = "jamsu_login"
        /** 동시에 열어 둘 수 있는 실시간 연결 수 (스레드 풀 600 중 API 요청 몫을 남긴다) */
        private const val MAX_STREAMS = 450
        private val TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "png" to "image/png",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "webmanifest" to "application/manifest+json",
        )
    }
}

/** 아주 단순한 요청 횟수 제한 (키마다 일정 시간 동안 몇 번까지) */
class RateLimiter {
    private class Window(var start: Long, var count: Int)

    private val map = ConcurrentHashMap<String, Window>()
    @Volatile private var lastSweep = 0L

    fun allow(key: String, max: Int, windowSec: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSweep > 60_000 || map.size > 50_000) {
            lastSweep = now
            map.entries.removeIf { now - it.value.start > 900_000 }
            // 그래도 너무 크면(수없이 많은 주소에서 두드리는 경우) 비운다 — 표 자체가 메모리를 다 쓰는 것보다 낫다
            if (map.size > 50_000) map.clear()
        }
        val k = "$key/$windowSec"
        val w = map.getOrPut(k) { Window(now, 0) }
        synchronized(w) {
            if (now - w.start > windowSec * 1000L) {
                w.start = now
                w.count = 0
            }
            w.count++
            return w.count <= max
        }
    }
}
