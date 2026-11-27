package kr.jamsuham.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.Traffic
import kr.jamsuham.core.cloud.CloudClient
import kr.jamsuham.core.cloud.CloudEntry
import kr.jamsuham.core.cloud.CloudException
import kr.jamsuham.core.cloud.CloudStream
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.cloud.arr
import kr.jamsuham.core.cloud.bool
import kr.jamsuham.core.cloud.int
import kr.jamsuham.core.cloud.long
import kr.jamsuham.core.cloud.obj
import kr.jamsuham.core.cloud.str
import kr.jamsuham.core.net.CommandSuggestions
import kr.jamsuham.core.text.ChatText
import java.util.concurrent.Executors

/** 앱의 계정 하나를 클라우드에 연결한 기록. [token] 은 Keystore 로 암호화한 클라우드 접속 토큰 */
data class CloudLink(
    val accountId: String = "",
    val url: String = "",
    val uuid: String = "",
    val name: String = "",
    val token: String = "",
    val premium: Boolean = false,
    val premiumUntil: Long? = null,
)

/**
 * 클라우드 잠수: 서버 컴퓨터가 대신 접속해 있고, 앱은 그 상태·채팅을 받아 보여 주며 조작만 한다.
 * 클라우드에서 온 내용은 [SessionHub] 의 remote* 함수로 넣어서 채팅 화면이 폰 잠수와 똑같이 그린다.
 * 화면에 닿는 것은 모두 메인 스레드에서, 네트워크는 백그라운드 스레드에서 한다.
 */
object CloudHub {
    private const val PREFS = "jamsuham_cloud"
    private val main = Handler(Looper.getMainLooper())
    /** 채팅·클릭 같은 조작은 보낸 순서대로 */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "jamsuham-cloud").apply { isDaemon = true } }
    private val gson = Gson()
    private lateinit var app: Context
    /** 서버 목록 (이벤트마다 새로 만들면 목록을 매번 다시 해석하므로 하나를 계속 쓴다) */
    private val store: Store by lazy { Store(app) }

    private class Conn(var link: CloudLink, val client: CloudClient, var loggedSeq: Long) {
        var stream: CloudStream? = null
        var connected = false
        var error: String? = null
        /** 앱과 클라우드 서버의 형식 번호가 다를 때의 안내 (연결돼 있어도 보여 준다) */
        var notice: String? = null
        /** 이 계정이 지금 어느 매체에서 어느 서버에 접속 중인지 (클라우드가 알려 준 목록) */
        var holders: List<CloudClient.Holder> = emptyList()
        /** [holders] 를 마지막으로 받은 시각 (0 = 받은 적 없음) */
        var holdersAt = 0L
        /** 이 프로세스에서 받은 마지막 줄 순번 (끊겼다 붙을 때 이어 받는 기준) */
        @Volatile var memSeq = 0L
        /** 폰 시계 - 클라우드 시계 (접속 시간·재접속까지 남은 시간을 폰 시계로 맞춰 보여 주려고) */
        var skew = 0L
        /** 서버별 리소스팩 (주소, SHA-1) */
        val packs = HashMap<String, ArrayList<Pair<String, String>>>()
        /** 폰 잠수 목록을 마지막으로 알린 내용·시각 */
        var reported: List<Pair<String, String>>? = null
        var reportedAt = 0L
    }

    private val conns = HashMap<String, Conn>()
    private var foreground = false
    private var seqSavePending = false
    private val saveSeq = Runnable { persistSeq() }

    /** 연결 상태·"어디서 실행 중" 표시가 바뀔 때마다 오른다 */
    @Volatile var version = 0
        private set

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    private val sp get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val userAgent: String by lazy {
        val v = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (_: Throwable) {
            null
        }
        "Jamsucraft/${v ?: "?"} (Android)"
    }

    // ------------------------------------------------------------ 주소·연결 기록

    /** 빌드할 때 넣은 기본 클라우드 주소 (저장소 변수 CLOUD_URL). 없으면 빈 글자 */
    val defaultUrl: String by lazy {
        try {
            app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData?.getString("kr.jamsuham.CLOUD_URL")?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    /** 클라우드 서버 주소 (설정에서 바꾼 값, 없으면 기본값) */
    val url: String get() = sp.getString("url", null)?.takeIf { it.isNotBlank() } ?: defaultUrl

    fun setUrl(v: String) {
        sp.edit().putString("url", v.trim()).apply()
        version++
    }

    fun links(): List<CloudLink> = try {
        gson.fromJson<List<CloudLink>>(sp.getString("links", "[]"), object : TypeToken<List<CloudLink>>() {}.type) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    fun link(accountId: String?): CloudLink? = if (accountId == null) null else links().firstOrNull { it.accountId == accountId }

    fun isLinked(accountId: String?): Boolean = link(accountId) != null

    private fun writeLinks(list: List<CloudLink>) {
        sp.edit().putString("links", gson.toJson(list)).apply()
        version++
    }

    private fun saveLink(l: CloudLink) = writeLinks(links().filter { it.accountId != l.accountId } + l)

    private fun conn(accountId: String?): Conn? {
        if (accountId == null) return null
        conns[accountId]?.let { return it }
        val l = link(accountId) ?: return null
        val token = AccountStore.openSecret(l.token) ?: return null
        val client = try {
            CloudClient(l.url, token, userAgent)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // 채팅 기록 파일에 남긴 마지막 줄 순번은 연결을 해제해도 남겨 둔다 (다시 연결했을 때 같은 줄을 또 남기지 않게)
        return Conn(l, client, sp.getLong("seq_$accountId", 0L)).also { conns[accountId] = it }
    }

    /** 설정 화면용: 이 계정의 연결 상태 한 줄 */
    fun describe(accountId: String): String {
        val l = link(accountId) ?: return tr("연결 안 됨", "Not linked")
        val c = conns[accountId]
        val premium = when {
            !l.premium -> tr("프리미엄 아님", "No premium")
            l.premiumUntil != null -> tr("프리미엄 ~", "Premium until ") + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(l.premiumUntil - 1))
            else -> tr("프리미엄", "Premium")
        }
        val state = when {
            c?.notice != null -> c.notice!!
            c?.connected == true -> tr("연결됨", "Connected")
            c?.error != null -> tr("연결 끊김: ${c.error}", "Disconnected: ${c.error}")
            else -> tr("연결해 둠", "Linked")
        }
        return "$state · $premium"
    }

    /**
     * 서버 목록용: 클라우드 잠수 서버의 연결 문제 한마디 (문제없으면 null).
     * 연결이 끊겨 있으면 화면의 상태는 마지막으로 받은 것이므로 그렇다고 알려 준다.
     */
    fun linkProblem(accountId: String?): String? {
        if (!::app.isInitialized) return null
        if (!isLinked(accountId)) return tr("연결 필요", "link required")
        val c = conns[accountId] ?: return null
        return if (foreground && !c.connected && c.stream != null) tr("연결 중…", "connecting…") else null
    }

    // ------------------------------------------------------------ 앱이 앞에 있을 때만 실시간 연결

    fun onForeground() {
        if (!::app.isInitialized) return
        foreground = true
        ensureStreams()
    }

    /** 앱이 뒤로 갔다: 실시간 연결을 끊는다 (잠수는 클라우드에서 계속 돈다. 배터리·데이터를 쓰지 않게) */
    fun onBackground() {
        if (!::app.isInitialized) return
        foreground = false
        for (c in conns.values) {
            c.stream?.stop()
            c.stream = null
            c.connected = false
        }
        persistSeq()
        version++
    }

    /** 네트워크가 다시 잡혔을 때 (기다리지 않고 바로 다시 붙는다) */
    fun onNetworkAvailable() {
        for (c in conns.values) c.stream?.retryNow()
    }

    private fun ensureStreams() {
        if (!foreground) return
        for (l in links()) {
            val c = conn(l.accountId) ?: continue
            if (c.stream?.running == true) continue
            // 실시간 연결은 끊을 때 서로 엉키지 않게 전용 연결 객체를 쓴다
            val client = CloudClient(l.url, c.client.token, userAgent)
            // 예전 연결이 늦게 보낸 이벤트는 버린다 (지금 연결인지 확인)
            val self = arrayOfNulls<CloudStream>(1)
            val stream = CloudStream(client, { c.memSeq.takeIf { it > 0 } }, object : CloudStream.Listener {
                override fun onConnection(connected: Boolean, error: String?) {
                    main.post {
                        if (c.stream !== self[0]) return@post
                        c.connected = connected
                        c.error = if (connected) null else error
                        version++
                    }
                }

                override fun onEvent(type: String, data: JsonObject, id: Long?) {
                    main.post { if (c.stream === self[0]) onEvent(c, type, data) }
                }

                override fun onAuthLost() {
                    main.post { if (c.stream === self[0]) authLost(c) }
                }
            })
            self[0] = stream
            c.stream = stream
            stream.start()
        }
    }

    // ------------------------------------------------------------ 클라우드에서 온 이벤트 (메인 스레드)

    /** 이 폰에서 클라우드 잠수로 쓰는 서버인지 (같은 계정) */
    private fun mine(c: Conn, sid: String?): Boolean {
        if (sid == null) return false
        val s = store.server(sid) ?: return false
        return s.cloud && s.accountId == c.link.accountId
    }

    /** 클라우드 시계로 적힌 시각을 폰 시계로 옮긴다 */
    private fun status(c: Conn, o: JsonObject?): SessionStatus {
        val s = Wire.parseStatus(o)
        if (c.skew == 0L) return s
        return s.copy(onlineSinceMs = s.onlineSinceMs?.plus(c.skew), nextAttemptAtMs = s.nextAttemptAtMs?.plus(c.skew))
    }

    private fun onEvent(c: Conn, type: String, d: JsonObject) {
        val sid = d.str("sid")
        when (type) {
            "hello" -> hello(c, d)
            "line" -> {
                val (seq, line) = Wire.parseLine(d)
                if (seq <= c.memSeq) return
                c.memSeq = seq
                val save = seq > c.loggedSeq
                if (save) {
                    c.loggedSeq = seq
                    // 5초에 한 번만 저장한다 (줄이 올 때마다 미루면 채팅이 계속 오는 서버에서는 영영 저장되지 않는다)
                    if (!seqSavePending) {
                        seqSavePending = true
                        main.postDelayed(saveSeq, 5000)
                    }
                }
                if (mine(c, sid)) SessionHub.remoteLine(sid!!, line, save)
            }
            "status" -> if (mine(c, sid)) SessionHub.remoteStatus(sid!!, status(c, d.obj("status")))
            "players" -> if (mine(c, sid)) SessionHub.remotePlayers(sid!!, Wire.parsePlayers(d.obj("players")))
            "vitals" -> if (mine(c, sid)) SessionHub.remoteVitals(sid!!, Wire.parseVitals(d.get("vitals")))
            "menu" -> if (mine(c, sid)) SessionHub.remoteMenu(sid!!, Wire.parseMenu(d.get("menu")))
            "inventory" -> if (mine(c, sid)) SessionHub.remoteInventory(sid!!, Wire.parseInventory(d.obj("inventory")))
            "death" -> if (mine(c, sid)) SessionHub.remoteDeath(sid!!, Wire.parseDeath(d.get("death")))
            "traffic" -> if (mine(c, sid)) SessionHub.remoteTraffic(sid!!, parseTraffic(d.obj("traffic")))
            "pack" -> if (sid != null) {
                val url = d.str("url") ?: return
                val list = c.packs.getOrPut(sid) { ArrayList() }
                list.removeAll { it.first == url }
                list.add(url to (d.str("hash") ?: ""))
                if (mine(c, sid)) SessionHub.remotePacks(sid, list.toList())
            }
            "entry" -> d.obj("session")?.let { upsert(c, it) }
            "entryRemoved" -> d.str("id")?.let { removedInCloud(c, it) }
            "presence" -> {
                c.holders = CloudClient.parseHolders(d)
                c.holdersAt = System.currentTimeMillis()
                version++
            }
            "newDevice" -> try {
                // 이 계정에 다른 기기가 로그인했다: 본인이 한 것이 아니면 알아챌 수 있게 알려 준다
                Toast.makeText(app, tr("${c.link.name}: 클라우드에 새 기기가 로그인했습니다 (${d.str("label") ?: "?"})", "${c.link.name}: a new device signed in to the cloud (${d.str("label") ?: "?"})"), Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {
            }
            "bye" -> authLost(c)
        }
    }

    /** 클라우드에서(웹에서) 지운 서버: 앱 목록에는 남기고 폰 잠수용으로 돌린다 */
    private fun removedInCloud(c: Conn, id: String) {
        c.packs.remove(id)
        forgetGone(c.link.accountId, id)
        if (!mine(c, id)) return
        store.server(id)?.let { store.saveServer(it.copy(cloud = false)) }
        SessionHub.remoteEnd(id, tr("클라우드에서 지워진 서버입니다 (이 폰에서 잠수하도록 바꿨습니다)", "This server was removed from the cloud (switched to AFK on this phone)"))
    }

    private fun parseTraffic(o: JsonObject?): Traffic? =
        if (o == null) null else Traffic(o.long("rx") ?: 0L, o.long("tx") ?: 0L, o.long("onlineMs") ?: 0L)

    private fun hello(c: Conn, d: JsonObject) {
        d.obj("account")?.let { a ->
            val updated = c.link.copy(
                name = a.str("name") ?: c.link.name,
                premium = a.bool("premium") ?: false,
                premiumUntil = a.long("premiumUntil"),
            )
            if (updated != c.link) {
                c.link = updated
                saveLink(updated)
            }
        }
        val api = d.int("api") ?: Wire.API
        c.notice = when {
            api > Wire.API -> tr("앱을 업데이트해 주세요 (클라우드 서버가 더 새 버전)", "Please update the app (the cloud server is newer)")
            api < Wire.API -> tr("클라우드 서버를 업데이트해 주세요 (앱이 더 새 버전)", "Please update the cloud server (the app is newer)")
            else -> null
        }
        d.long("serverTime")?.let { c.skew = System.currentTimeMillis() - it }
        c.holders = CloudClient.parseHolders(d)
        c.holdersAt = System.currentTimeMillis()
        val inCloud = HashSet<String>()
        for (el in d.arr("sessions")) {
            val snap = el as? JsonObject ?: continue
            snap.obj("entry")?.str("id")?.let { inCloud.add(it) }
            upsert(c, snap)
        }
        // 앱에는 클라우드 잠수로 돼 있는데 클라우드에 없는 서버
        val dirty = dirtyIds()
        for (s in store.servers()) {
            if (!s.cloud || s.accountId != c.link.accountId || s.id in inCloud) continue
            // 아직 못 올린 것이면 지금 올리고, 아니면 웹에서 지웠거나 클라우드 데이터가 초기화된 것 → 화면에 "잠수 중"으로 남지 않게 정리
            if (s.id in dirty) push(c, s) else removedInCloud(c, s.id)
        }
        version++
    }

    /** 클라우드의 서버 하나(설정 + 현재 상태)를 앱에 맞춘다 */
    private fun upsert(c: Conn, snap: JsonObject) {
        val entry = Wire.parseEntry(snap.obj("entry") ?: return) ?: return
        var server = store.server(entry.id)
        val isMine = server != null && server.cloud && server.accountId == c.link.accountId
        // 앱에서 지웠거나 폰 잠수로 돌렸는데 그때 클라우드에 닿지 못해 남아 있던 서버: 다시 지우고 앱에 되살리지 않는다
        if (isGone(c.link.accountId, entry.id)) {
            if (isMine) {
                forgetGone(c.link.accountId, entry.id) // 그 뒤에 다시 클라우드 잠수로 켠 경우
            } else {
                deleteInCloud(c, entry.id)
                return
            }
        }
        when {
            // 웹에서 만든 서버 → 앱 목록에도 추가
            server == null -> {
                server = fromCloud(ServerEntry(
                    id = entry.id, accountId = c.link.accountId, cloud = true,
                    packGlyphs = AppSettings.defaultPackGlyphs, packWifiOnly = AppSettings.defaultPackWifiOnly, resumeOnBoot = false,
                ), entry)
                store.saveServer(server)
            }
            // 이 폰에서는 폰 잠수로 쓰는 서버 (또는 다른 계정): 건드리지 않는다
            !isMine -> return
            // 앱에서 고친 것이 아직 클라우드에 못 올라갔으면 앱 쪽 설정을 다시 올린다
            entry.id in dirtyIds() -> push(c, server)
            // 웹에서 설정을 고쳤으면 앱에도 반영
            server.cloudEntry() != entry -> {
                server = fromCloud(server, entry)
                store.saveServer(server)
            }
        }
        val id = entry.id
        SessionHub.remoteBegin(app, id)
        SessionHub.remoteStatus(id, status(c, snap.obj("status")))
        SessionHub.remoteState(id, Wire.parsePlayers(snap.obj("players")), Wire.parseVitals(snap.get("vitals")), Wire.parseMenu(snap.get("menu")), Wire.parseInventory(snap.obj("inventory")))
        SessionHub.remoteDeath(id, Wire.parseDeath(snap.get("death")))
        SessionHub.remoteTraffic(id, parseTraffic(snap.obj("traffic")))
        val packs = ArrayList<Pair<String, String>>()
        for (el in snap.arr("packs")) (el as? JsonObject)?.let { p -> p.str("url")?.let { packs.add(it to (p.str("hash") ?: "")) } }
        c.packs[id] = packs
        if (packs.isNotEmpty()) SessionHub.remotePacks(id, packs)
    }

    private fun fromCloud(s: ServerEntry, e: CloudEntry) = s.copy(
        name = e.name, address = e.address, fixedVersion = e.fixedVersion, joinCommands = e.joinCommands,
        joinDelaySec = e.joinDelaySec, joinIntervalSec = e.joinIntervalSec, autoReconnect = e.autoReconnect,
        reconnectOnKick = e.reconnectOnKick, autoClickRules = e.autoClickRules, walkOnJoin = e.walkOnJoin,
    )

    /** 클라우드 로그인이 풀렸다 (웹에서 연결 해제했거나 만료) */
    private fun authLost(c: Conn) {
        dropLocal(c.link.accountId, tr("클라우드 연결이 해제되었습니다. 설정 → 클라우드 잠수에서 다시 연결해 주세요", "The cloud link was removed. Link again in Settings → Cloud AFK"))
        try {
            Toast.makeText(app, tr("${c.link.name}: 클라우드 연결이 해제되었습니다", "${c.link.name}: cloud link removed"), Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }

    /**
     * 이 폰의 연결 기록을 지운다. 서버의 "클라우드에서 잠수" 설정은 그대로 둔다
     * (다시 연결하면 그대로 이어 쓰고, 그 전에 시작을 누르면 "연결이 필요합니다"로 안내한다 —
     *  폰 잠수로 돌려 버리면 폰에서 접속해 클라우드에 남아 있는 잠수를 튕겨 낼 수 있다).
     */
    private fun dropLocal(accountId: String, message: String) {
        persistSeq()
        conns.remove(accountId)?.let { c ->
            c.stream?.stop()
            c.stream = null
        }
        writeLinks(links().filter { it.accountId != accountId })
        for (s in store.servers()) {
            if (s.cloud && s.accountId == accountId) SessionHub.remoteEnd(s.id, message)
        }
    }

    /** 채팅 기록 파일에 남긴 마지막 줄 순번을 저장한다 (앱이 꺼졌다 켜져도 같은 줄을 두 번 남기지 않게) */
    fun persistSeq() {
        if (!::app.isInitialized) return
        main.removeCallbacks(saveSeq)
        seqSavePending = false
        var edit: android.content.SharedPreferences.Editor? = null
        for ((accountId, c) in conns) {
            if (sp.getLong("seq_$accountId", 0L) != c.loggedSeq) edit = (edit ?: sp.edit()).putLong("seq_$accountId", c.loggedSeq)
        }
        edit?.apply()
    }

    // ------------------------------------------------------------ 계정 연결·해제

    /**
     * 계정을 클라우드에 연결한다: 이 계정의 로그인 정보를 클라우드 서버로 보내고(사용자가 동의한 뒤에만 부를 것) 접속 토큰을 받는다.
     * [done] 은 메인 스레드에서: 성공하면 null, 실패하면 이유.
     */
    fun linkAccount(context: Context, accountId: String, done: (String?) -> Unit) {
        init(context)
        val base = try {
            CloudClient.normalize(url)
        } catch (e: IllegalArgumentException) {
            done(e.message)
            return
        }
        Thread({
            val error: String? = try {
                val client = CloudClient(base, null, userAgent)
                val info = client.info()
                val api = info.int("api") ?: 0
                if (api > Wire.API) throw java.io.IOException(tr("앱을 업데이트해 주세요 (클라우드 서버가 더 새 버전입니다)", "Please update the app (the cloud server is newer)"))
                if (api < Wire.API) throw java.io.IOException(tr("클라우드 서버를 업데이트해 주세요 (앱이 더 새 버전입니다)", "Please update the cloud server (the app is newer)"))
                val auth = AccountStore(context).exportAuth(accountId)
                val login = client.loginTransfer(auth, Build.MODEL ?: "Android")
                val a = login.account
                val link = CloudLink(
                    accountId = accountId, url = base, uuid = a.str("uuid") ?: "", name = a.str("name") ?: "",
                    token = AccountStore.sealSecret(login.token), premium = a.bool("premium") ?: false, premiumUntil = a.long("premiumUntil"),
                )
                main.post {
                    conns.remove(accountId)?.stream?.stop()
                    saveLink(link)
                    ensureStreams()
                }
                null
            } catch (e: CloudException) {
                when (e.code) {
                    "not_premium" -> tr(
                        "${e.data?.str("name") ?: ""} 계정은 클라우드 잠수(프리미엄)를 쓸 수 있는 계정이 아닙니다.\n운영자에게 닉네임을 알려 주세요.",
                        "The account ${e.data?.str("name") ?: ""} doesn't have cloud AFK (premium).\nSend your nickname to the operator.",
                    )
                    else -> e.message
                }
            } catch (e: Exception) {
                tr("클라우드 서버에 연결하지 못했습니다: ${e.message}", "Couldn't reach the cloud server: ${e.message}")
            }
            main.post { done(error) }
        }, "jamsuham-cloud-link").apply { isDaemon = true }.start()
    }

    /**
     * 연결 해제. [done] 은 메인 스레드에서 (실패 이유, 성공하면 null).
     * - [deleteOnServer] = true: 클라우드의 잠수를 모두 끊고 서버에 저장된 이 계정의 로그인 정보·서버 목록을 지운다.
     *   클라우드에 닿지 못하면 실패로 알리고 연결은 그대로 둔다 (지워졌다고 믿게 하지 않으려고).
     * - false: 이 폰만 로그아웃. 클라우드에 닿지 못해도 이 폰의 연결 기록은 지운다.
     */
    fun unlinkAccount(accountId: String, deleteOnServer: Boolean, done: (String?) -> Unit) {
        if (!::app.isInitialized) {
            done(null)
            return
        }
        val c = conn(accountId)
        if (c == null) {
            dropLocal(accountId, tr("꺼짐", "Off"))
            done(null)
            return
        }
        // 내가 해제하는 것이므로 클라우드가 보내는 "로그아웃됨" 알림은 받지 않는다
        c.stream?.stop()
        c.stream = null
        c.connected = false
        if (!deleteOnServer) {
            val client = c.client
            dropLocal(accountId, tr("꺼짐", "Off"))
            Thread({
                try {
                    client.logout()
                } catch (_: Exception) {
                    // 클라우드에 닿지 못했어도 이 폰에서는 해제됐다 (클라우드의 기기 기록은 90일 뒤 저절로 지워진다)
                }
            }, "jamsuham-cloud-unlink").apply { isDaemon = true }.start()
            done(null)
            return
        }
        Thread({
            val error: String? = try {
                c.client.deleteAccount()
                null
            } catch (e: CloudException) {
                // 이미 풀려 있었으면 성공으로 본다
                if (e.status == 401) null else e.message
            } catch (e: Exception) {
                tr("클라우드 서버에 연결하지 못해 지우지 못했습니다 (${e.message}). 나중에 다시 시도하거나, '이 폰만'으로 이 폰의 연결만 해제할 수 있습니다.",
                    "Couldn't reach the cloud server, so nothing was deleted (${e.message}). Try again later, or use 'This phone only' to unlink just this phone.")
            }
            main.post {
                if (error == null) dropLocal(accountId, tr("꺼짐", "Off")) else ensureStreams()
                done(error)
            }
        }, "jamsuham-cloud-unlink").apply { isDaemon = true }.start()
    }

    /** 주소가 잠수크래프트 클라우드 서버인지 확인한다. [done] 은 메인 스레드에서 (성공 문구, 실패 이유) */
    fun checkUrl(context: Context, value: String, done: (ok: String?, error: String?) -> Unit) {
        init(context)
        val base = try {
            CloudClient.normalize(value)
        } catch (e: IllegalArgumentException) {
            done(null, e.message)
            return
        }
        Thread({
            try {
                val info = CloudClient(base, null, userAgent).info()
                main.post { done(tr("확인했습니다 (서버 v${info.str("version") ?: "?"})", "Verified (server v${info.str("version") ?: "?"})"), null) }
            } catch (e: Exception) {
                main.post { done(null, tr("확인하지 못했습니다: ${e.message}", "Couldn't verify: ${e.message}")) }
            }
        }, "jamsuham-cloud-check").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ 잠수 시작·끊기·조작

    private fun note(serverId: String, text: String, kind: LogLine.Kind = LogLine.Kind.ERROR) {
        SessionHub.remoteLine(serverId, LogLine(System.currentTimeMillis(), kind, ChatText.of(text)), save = true)
    }

    private fun alert(activity: Activity, title: String, message: String, positive: String? = null, onPositive: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        val b = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
        if (positive != null && onPositive != null) {
            b.setPositiveButton(positive) { _, _ -> onPositive() }.setNegativeButton(tr("취소", "Cancel"), null)
        } else {
            b.setPositiveButton(tr("확인", "OK"), null)
        }
        try {
            b.show()
        } catch (_: Throwable) {
        }
    }

    /** 클라우드에서 잠수 시작. 요청을 보냈으면 true (결과는 채팅 화면 상태로 온다) */
    fun start(activity: Activity, server: ServerEntry, force: Boolean = false): Boolean {
        init(activity)
        val c = conn(server.accountId)
        if (c == null) {
            alert(activity, tr("클라우드 연결이 필요합니다", "Cloud link required"),
                tr("이 계정은 클라우드에 연결되어 있지 않습니다. 설정 → 클라우드 잠수에서 연결하거나, 서버 설정에서 '클라우드에서 잠수'를 꺼 주세요.", "This account isn't linked to the cloud. Link it in Settings → Cloud AFK, or turn off 'AFK in the cloud' in the server settings."))
            return false
        }
        // 같은 계정으로 같은 서버에 이 폰이 이미 들어가 있으면 서로 튕겨 내므로 막는다
        val key = Wire.addressKey(server.address)
        val local = store.servers().firstOrNull {
            it.id != server.id && it.accountId == server.accountId && Wire.addressKey(it.address) == key &&
                SessionHub.isActive(it.id) && !SessionHub.isRemote(it.id)
        }
        if (local != null) {
            alert(activity, tr("이미 실행 중입니다", "Already running"),
                tr("이미 이 폰(${local.displayName})에서 실행 중입니다. 그쪽 연결을 해제하고 다시 시도해 주세요.", "Already running on this phone (${local.displayName}). Disconnect it and try again."))
            return false
        }
        SessionHub.remoteBegin(activity, server.id)
        SessionHub.remoteStatus(server.id, SessionStatus(SessionState.PREPARING, tr("클라우드에 요청 중…", "Asking the cloud…")))
        ensureStreams()
        // 실시간 연결이 다시 붙기를 기다리는 중이었으면 바로 붙인다 (결과를 빨리 보여 주려고)
        c.stream?.retryNow()
        val entry = server.cloudEntry()
        io.execute {
            try {
                c.client.putEntry(entry)
                main.post { markClean(entry) }
                c.client.start(entry.id, force)
            } catch (e: CloudException) {
                main.post { startFailed(activity, server, e) }
            } catch (e: Exception) {
                main.post {
                    val msg = tr("클라우드 서버에 연결하지 못했습니다: ${e.message}", "Couldn't reach the cloud server: ${e.message}")
                    SessionHub.remoteStatus(server.id, SessionStatus(SessionState.STOPPED, msg))
                    note(server.id, msg)
                }
            }
        }
        return true
    }

    private fun startFailed(activity: Activity, server: ServerEntry, e: CloudException) {
        val msg = e.message ?: tr("시작하지 못했습니다", "Couldn't start")
        SessionHub.remoteStatus(server.id, SessionStatus(SessionState.STOPPED, msg))
        if (e.status == 401) {
            conns[server.accountId]?.let { authLost(it) }
            return
        }
        if (e.code == "in_use") {
            // 다른 매체에서 이미 이 서버에 접속 중
            if (e.data?.bool("canForce") == true) {
                alert(activity, tr("다른 곳에서 접속 중일 수 있습니다", "May be connected elsewhere"), msg + "\n" + tr("계속할까요?", "Continue?"),
                    tr("그래도 시작", "Start anyway")) { start(activity, server, force = true) }
            } else {
                alert(activity, tr("이미 실행 중입니다", "Already running"), msg)
            }
            return
        }
        note(server.id, msg)
        alert(activity, tr("클라우드에서 시작할 수 없습니다", "Couldn't start in the cloud"), msg)
    }

    /** 클라우드에 조작 하나를 보낸다 (순서대로). 실패하면 채팅에 이유를 남긴다. [onGone] 은 클라우드에 그 서버가 없을 때(404) */
    private fun run(serverId: String, what: String, onGone: (() -> Unit)? = null, block: (CloudClient) -> Unit): Boolean {
        if (!::app.isInitialized) return false
        val server = store.server(serverId) ?: return false
        val c = conn(server.accountId) ?: return false
        io.execute {
            try {
                block(c.client)
            } catch (e: CloudException) {
                main.post {
                    when {
                        e.status == 401 -> conns[server.accountId]?.let { authLost(it) }
                        e.status == 404 && onGone != null -> onGone()
                        else -> note(serverId, "$what: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                main.post { note(serverId, tr("$what: 클라우드 서버에 연결하지 못했습니다 (${e.message})", "$what: couldn't reach the cloud server (${e.message})")) }
            }
        }
        return true
    }

    fun stop(serverId: String) {
        val sent = run(serverId, tr("끊기 실패", "Disconnect failed"), onGone = {
            // 클라우드에 이미 없는 서버: "잠수 중"으로 남지 않게 꺼짐으로
            SessionHub.remoteStatus(serverId, SessionStatus(SessionState.STOPPED, tr("꺼짐", "Off")))
        }) { it.stop(serverId) }
        if (!sent) SessionHub.remoteEnd(serverId)
    }

    fun sendChat(serverId: String, text: String): Boolean = run(serverId, tr("보내지 못했습니다", "Not sent")) { it.chat(serverId, text) }

    fun clickMenu(serverId: String, slot: Int): Boolean = run(serverId, tr("메뉴 클릭 실패", "Menu click failed")) { it.menuClick(serverId, slot) }

    fun closeMenu(serverId: String) {
        run(serverId, tr("메뉴 닫기 실패", "Couldn't close the menu")) { it.menuClose(serverId) }
    }

    fun clearDeath(serverId: String) {
        run(serverId, tr("사망 알림 지우기 실패", "Couldn't clear the death notice")) { it.clearDeath(serverId) }
    }

    /** 자동완성 후보 (결과는 아무 스레드에서나 온다) */
    fun requestSuggestions(serverId: String, text: String, callback: (CommandSuggestions?) -> Unit): Boolean {
        if (!::app.isInitialized) return false
        val server = store.server(serverId) ?: return false
        val c = conn(server.accountId) ?: return false
        Thread({
            callback(try {
                c.client.suggest(serverId, text)
            } catch (_: Exception) {
                null
            })
        }, "jamsuham-cloud-suggest").apply { isDaemon = true }.start()
        return true
    }

    // ------------------------------------------------------------ 서버 설정을 클라우드와 맞추기

    /** 앱에서 고쳤는데 아직 클라우드에 못 올린 서버들 */
    private fun dirtyIds(): Set<String> = sp.getStringSet("dirty", emptySet()) ?: emptySet()

    private fun markDirty(id: String) {
        sp.edit().putStringSet("dirty", dirtyIds() + id).apply()
    }

    /** [uploaded] 가 지금 설정과 같을 때만 "올렸음"으로 표시한다 (올리는 사이에 또 고쳤으면 그대로 둔다) */
    private fun markClean(uploaded: CloudEntry) {
        val d = dirtyIds()
        if (uploaded.id !in d) return
        val current = store.server(uploaded.id)
        if (current == null || current.cloudEntry() == uploaded) sp.edit().putStringSet("dirty", d - uploaded.id).apply()
    }

    private fun push(c: Conn, server: ServerEntry) {
        val entry = server.cloudEntry()
        io.execute {
            try {
                c.client.putEntry(entry)
                main.post { markClean(entry) }
            } catch (_: Exception) {
                // 다음에 연결될 때(hello) 다시 올린다
            }
        }
    }

    /** 클라우드에서 지워야 하는데 아직 확인하지 못한 서버들 ("계정|서버") */
    private fun goneKeys(): Set<String> = sp.getStringSet("gone", emptySet()) ?: emptySet()

    private fun isGone(accountId: String, id: String): Boolean = "$accountId|$id" in goneKeys()

    private fun markGone(accountId: String, id: String) {
        sp.edit().putStringSet("gone", (goneKeys() + "$accountId|$id").toList().takeLast(100).toSet()).apply()
    }

    private fun forgetGone(accountId: String, id: String) {
        val g = goneKeys()
        if ("$accountId|$id" in g) sp.edit().putStringSet("gone", g - "$accountId|$id").apply()
    }

    /** 클라우드에서 서버를 지운다 (잠수 중이면 끊긴다). 닿지 못하면 표시를 남겨 두었다가 다음에 연결될 때 다시 지운다 */
    private fun deleteInCloud(c: Conn, id: String) {
        val accountId = c.link.accountId
        markGone(accountId, id)
        io.execute {
            try {
                c.client.deleteEntry(id)
                main.post { forgetGone(accountId, id) }
            } catch (e: CloudException) {
                // 클라우드에 원래 없던 서버
                if (e.status == 404) main.post { forgetGone(accountId, id) }
            } catch (_: Exception) {
            }
        }
    }

    /** 서버 설정을 저장한 뒤에 부른다 ([old] 는 고치기 전, 새 서버면 null) */
    fun entrySaved(context: Context, old: ServerEntry?, new: ServerEntry) {
        init(context)
        if (old != null && old.cloud && (!new.cloud || old.accountId != new.accountId)) {
            // 클라우드 잠수를 껐거나 계정을 바꿨다: 예전 계정의 클라우드에서 지운다 (잠수 중이면 끊긴다)
            val c = conn(old.accountId)
            if (c != null) deleteInCloud(c, old.id) else old.accountId?.let { markGone(it, old.id) }
            SessionHub.remoteEnd(old.id)
        }
        if (new.cloud) {
            val c = conn(new.accountId) ?: return
            forgetGone(c.link.accountId, new.id)
            markDirty(new.id)
            // 이 폰에서 잠수하던 서버면 여기서 끊기고 "꺼짐"이 된다
            SessionHub.remoteBegin(context, new.id)
            push(c, new)
        }
    }

    /** 서버를 지운 뒤에 부른다 */
    fun entryDeleted(context: Context, old: ServerEntry) {
        init(context)
        if (!old.cloud) return
        val d = dirtyIds()
        if (old.id in d) sp.edit().putStringSet("dirty", d - old.id).apply()
        val c = conn(old.accountId)
        if (c != null) deleteInCloud(c, old.id) else old.accountId?.let { markGone(it, old.id) }
        SessionHub.remoteEnd(old.id)
    }

    // ------------------------------------------------------------ 어느 매체에서 실행 중인지

    /**
     * 이 서버(같은 계정·같은 주소)가 다른 매체에서 돌고 있으면 그 매체 (없으면 null).
     * 폰 잠수용 서버는 클라우드 쪽을, 클라우드 잠수용 서버는 PC 게임 쪽을 본다. 마지막으로 받은 목록 기준이다.
     */
    fun elsewhere(server: ServerEntry): CloudClient.Holder? {
        val c = conns[server.accountId] ?: return null
        val key = Wire.addressKey(server.address)
        return c.holders.firstOrNull {
            it.key == key && when {
                server.cloud -> it.holder == "other"
                else -> it.holder == "cloud"
            }
        }
    }

    /** 목록에 보일 한마디 ("클라우드에서 실행 중" 등). 없으면 null */
    fun elsewhereLabel(server: ServerEntry): String? {
        val h = elsewhere(server) ?: return null
        return if (h.holder == "cloud") tr("클라우드에서 실행 중", "Running in the cloud") else tr("다른 클라이언트(PC 등)에서 접속함", "Joined from another client (e.g. PC)")
    }

    /**
     * 폰에서 잠수를 시작하기 전에: 같은 계정이 같은 서버에 클라우드로 들어가 있는지 확인한다.
     * 실시간 연결이 붙어 있으면 받아 둔 목록으로 바로 답하고, 아니면 클라우드에 한 번 물어본다.
     * [done] 은 메인 스레드에서: 막아야 하면 그 매체, 아니면 null (클라우드에 닿지 못해 알 수 없을 때도 null — 시작을 막지 않는다).
     * 돌려주는 값: 바로 답했으면 true, 물어보는 중이면 false.
     */
    fun checkLocalStart(server: ServerEntry, done: (CloudClient.Holder?) -> Unit): Boolean {
        val c = if (::app.isInitialized) conn(server.accountId) else null
        if (c == null) {
            done(null)
            return true
        }
        val key = Wire.addressKey(server.address)
        fun find() = c.holders.firstOrNull { it.key == key && it.holder == "cloud" }
        if (c.connected && c.holdersAt > 0) {
            done(find())
            return true
        }
        Thread({
            val fresh = try {
                // 클라우드가 꺼져 있어도 시작이 오래 멈춰 있지 않게 짧게만 기다린다
                CloudClient(c.link.url, c.client.token, userAgent).presence(4000)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (fresh != null) {
                    c.holders = fresh
                    c.holdersAt = System.currentTimeMillis()
                    version++
                    done(find())
                } else {
                    done(null)
                }
            }
        }, "jamsuham-cloud-check").apply { isDaemon = true }.start()
        return false
    }

    /** 클라우드에서 도는 같은 서버를 끊는다 (폰에서 시작하려는데 막혔을 때 고르는 버튼) */
    fun stopHolder(server: ServerEntry, holder: CloudClient.Holder, done: (String?) -> Unit) {
        val c = conn(server.accountId)
        val sid = holder.sid
        if (c == null || sid == null) {
            done(tr("클라우드에 연결되어 있지 않습니다", "Not linked to the cloud"))
            return
        }
        io.execute {
            val error = try {
                c.client.stop(sid)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (error == null) {
                    // 실시간 연결이 없을 때도 바로 다시 시작할 수 있게 받아 둔 목록에서 뺀다
                    c.holders = c.holders.filterNot { it.holder == "cloud" && it.sid == sid }
                    version++
                }
                done(error)
            }
        }
    }

    /**
     * 이 폰에서 직접 잠수 중인 서버들을 클라우드에 알린다 (웹·다른 기기에 "앱에서 실행 중"으로 보이고, 클라우드에서 같은 서버를 시작하지 못하게).
     * 잠수 서비스가 상태가 바뀔 때와 1분마다 부른다. 연결한 계정이 없으면 아무것도 하지 않는다.
     */
    fun reportLocal(context: Context) {
        init(context)
        val all = links()
        if (all.isEmpty()) return
        val now = System.currentTimeMillis()
        for (l in all) {
            val c = conn(l.accountId) ?: continue
            val list = store.servers()
                .filter { it.accountId == l.accountId && SessionHub.isActive(it.id) && !SessionHub.isRemote(it.id) }
                // 재접속 대기·접속 중을 오갈 때마다 알리지 않게 두 가지로만 나눈다
                .map { it.address to (if (SessionHub.status(it.id).state == SessionState.ONLINE) "ONLINE" else "CONNECTING") }
            // 알릴 것이 없고 전에도 없었으면 보내지 않는다. 같은 내용은 50초에 한 번만
            if (list.isEmpty() && c.reported.isNullOrEmpty()) continue
            if (list == c.reported && now - c.reportedAt < 50_000) continue
            c.reported = list
            c.reportedAt = now
            Thread({
                try {
                    val holders = c.client.reportPresence(list)
                    main.post {
                        c.holders = holders
                        c.holdersAt = System.currentTimeMillis()
                        version++
                    }
                } catch (e: CloudException) {
                    if (e.status == 401) main.post { if (conns[l.accountId] === c) authLost(c) }
                } catch (_: Exception) {
                    // 다음 1분 뒤에 다시 알린다
                    main.post { c.reportedAt = 0 }
                }
            }, "jamsuham-cloud-presence").apply { isDaemon = true }.start()
        }
    }
}
