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
    /** 채팅 기록 파일에 남긴 마지막 줄 순번 (같은 줄을 두 번 남기지 않게) */
    val loggedSeq: Long = 0,
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

    private class Conn(var link: CloudLink, val client: CloudClient) {
        var stream: CloudStream? = null
        var connected = false
        var error: String? = null
        /** 앱과 클라우드 서버의 형식 번호가 다를 때의 안내 (연결돼 있어도 보여 준다) */
        var notice: String? = null
        /** 이 계정이 지금 어느 매체에서 어느 서버에 접속 중인지 (클라우드가 알려 준 목록) */
        var holders: List<CloudClient.Holder> = emptyList()
        /** 이 프로세스에서 받은 마지막 줄 순번 (끊겼다 붙을 때 이어 받는 기준) */
        @Volatile var memSeq = 0L
        var loggedSeq = link.loggedSeq
        /** 서버별 리소스팩 (주소, SHA-1) */
        val packs = HashMap<String, ArrayList<Pair<String, String>>>()
        /** 폰 잠수 목록을 마지막으로 알린 내용·시각 */
        var reported: List<Pair<String, String>>? = null
        var reportedAt = 0L
    }

    private val conns = HashMap<String, Conn>()
    private var foreground = false
    private val saveSeq = Runnable { persistSeq() }

    /** 연결 상태·"어디서 실행 중" 표시가 바뀔 때마다 오른다 (목록 화면이 1초마다 다시 그리며 본다) */
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
        return Conn(l, client).also { conns[accountId] = it }
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
        val s = Store(app).server(sid) ?: return false
        return s.cloud && s.accountId == c.link.accountId
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
                    main.removeCallbacks(saveSeq)
                    main.postDelayed(saveSeq, 5000)
                }
                if (mine(c, sid)) SessionHub.remoteLine(sid!!, line, save)
            }
            "status" -> if (mine(c, sid)) SessionHub.remoteStatus(sid!!, Wire.parseStatus(d.obj("status")))
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
            "entryRemoved" -> d.str("id")?.let { id ->
                // 웹에서 지운 서버: 앱 목록에는 남기고 폰 잠수용으로 돌린다
                if (mine(c, id)) {
                    val store = Store(app)
                    store.server(id)?.let { store.saveServer(it.copy(cloud = false)) }
                    SessionHub.remoteEnd(id)
                }
                c.packs.remove(id)
            }
            "presence" -> {
                c.holders = CloudClient.parseHolders(d)
                version++
            }
            "bye" -> authLost(c)
        }
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
                saveLink(updated.copy(loggedSeq = c.loggedSeq))
            }
        }
        val api = d.int("api") ?: Wire.API
        c.notice = when {
            api > Wire.API -> tr("앱을 업데이트해 주세요 (클라우드 서버가 더 새 버전)", "Please update the app (the cloud server is newer)")
            api < Wire.API -> tr("클라우드 서버를 업데이트해 주세요 (앱이 더 새 버전)", "Please update the cloud server (the app is newer)")
            else -> null
        }
        c.holders = CloudClient.parseHolders(d)
        for (el in d.arr("sessions")) (el as? JsonObject)?.let { upsert(c, it) }
        version++
    }

    /** 클라우드의 서버 하나(설정 + 현재 상태)를 앱에 맞춘다 */
    private fun upsert(c: Conn, snap: JsonObject) {
        val entry = Wire.parseEntry(snap.obj("entry") ?: return) ?: return
        val store = Store(app)
        var server = store.server(entry.id)
        val dirty = dirtyIds()
        // 앱에서 지웠는데 그때 클라우드에 닿지 못해 남아 있던 서버: 다시 지우고 앱 목록에 되살리지 않는다
        if (server == null && entry.id in deletedIds()) {
            io.execute {
                try {
                    c.client.deleteEntry(entry.id)
                    main.post { forgetDeleted(entry.id) }
                } catch (_: Exception) {
                }
            }
            return
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
            !server.cloud || server.accountId != c.link.accountId -> return
            // 앱에서 고친 것이 아직 클라우드에 못 올라갔으면 앱 쪽 설정을 다시 올린다
            entry.id in dirty -> push(c, server)
            // 웹에서 설정을 고쳤으면 앱에도 반영
            server.cloudEntry() != entry -> {
                server = fromCloud(server, entry)
                store.saveServer(server)
            }
        }
        val id = entry.id
        SessionHub.remoteBegin(app, id)
        SessionHub.remoteStatus(id, Wire.parseStatus(snap.obj("status")))
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

    /** 클라우드 로그인이 풀렸다 (웹에서 연결 해제했거나 만료): 연결 기록을 지우고 그 계정의 서버를 폰 잠수용으로 돌린다 */
    private fun authLost(c: Conn) {
        val accountId = c.link.accountId
        dropLocal(accountId, tr("클라우드 연결이 해제되었습니다. 설정에서 다시 연결할 수 있습니다", "The cloud link was removed. You can link again in Settings"))
        try {
            Toast.makeText(app, tr("${c.link.name}: 클라우드 연결이 해제되었습니다", "${c.link.name}: cloud link removed"), Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
    }

    /** 이 폰의 연결 기록을 지운다 */
    private fun dropLocal(accountId: String, message: String) {
        conns.remove(accountId)?.let { c ->
            c.stream?.stop()
            c.stream = null
        }
        writeLinks(links().filter { it.accountId != accountId })
        val store = Store(app)
        for (s in store.servers()) {
            if (s.cloud && s.accountId == accountId) {
                store.saveServer(s.copy(cloud = false))
                SessionHub.remoteEnd(s.id, message)
            }
        }
    }

    private fun persistSeq() {
        main.removeCallbacks(saveSeq)
        val list = links()
        var changed = false
        val out = list.map { l ->
            val seq = conns[l.accountId]?.loggedSeq ?: l.loggedSeq
            if (seq != l.loggedSeq) {
                changed = true
                l.copy(loggedSeq = seq)
            } else l
        }
        if (changed) sp.edit().putString("links", gson.toJson(out)).apply()
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
     * 연결 해제. [deleteOnServer] 면 클라우드의 잠수를 모두 끊고 서버에 저장된 이 계정의 로그인 정보·서버 목록도 지운다
     * (아니면 이 폰만 로그아웃).
     */
    fun unlinkAccount(accountId: String, deleteOnServer: Boolean, done: (String?) -> Unit) {
        val c = conn(accountId)
        if (c == null) {
            dropLocal(accountId, tr("꺼짐", "Off"))
            done(null)
            return
        }
        Thread({
            val error: String? = try {
                if (deleteOnServer) c.client.deleteAccount() else c.client.logout()
                null
            } catch (e: CloudException) {
                // 이미 풀려 있었으면 성공으로 본다
                if (e.status == 401) null else e.message
            } catch (e: Exception) {
                tr("클라우드 서버에 연결하지 못했습니다: ${e.message}", "Couldn't reach the cloud server: ${e.message}")
            }
            main.post {
                if (error == null) dropLocal(accountId, tr("꺼짐", "Off"))
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
        val local = Store(activity).servers().firstOrNull {
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
        val entry = server.cloudEntry()
        io.execute {
            try {
                c.client.putEntry(entry)
                main.post { markClean(entry.id) }
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
            conn(server.accountId)?.let { authLost(it) }
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

    private fun run(serverId: String, what: String, block: (CloudClient) -> Unit): Boolean {
        if (!::app.isInitialized) return false
        val server = Store(app).server(serverId) ?: return false
        val c = conn(server.accountId) ?: return false
        io.execute {
            try {
                block(c.client)
            } catch (e: CloudException) {
                main.post { note(serverId, "$what: ${e.message}") }
            } catch (e: Exception) {
                main.post { note(serverId, tr("$what: 클라우드 서버에 연결하지 못했습니다 (${e.message})", "$what: couldn't reach the cloud server (${e.message})")) }
            }
        }
        return true
    }

    fun stop(serverId: String) {
        if (!run(serverId, tr("끊기 실패", "Disconnect failed")) { it.stop(serverId) }) SessionHub.remoteEnd(serverId)
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
        val server = Store(app).server(serverId) ?: return false
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

    private fun dirtyIds(): Set<String> = sp.getStringSet("dirty", emptySet()) ?: emptySet()

    private fun markDirty(id: String) {
        sp.edit().putStringSet("dirty", dirtyIds() + id).apply()
    }

    private fun markClean(id: String) {
        val d = dirtyIds()
        if (id in d) sp.edit().putStringSet("dirty", d - id).apply()
    }

    private fun deletedIds(): Set<String> = sp.getStringSet("deleted", emptySet()) ?: emptySet()

    private fun forgetDeleted(id: String) {
        val d = deletedIds()
        if (id in d) sp.edit().putStringSet("deleted", d - id).apply()
    }

    private fun push(c: Conn, server: ServerEntry) {
        val entry = server.cloudEntry()
        io.execute {
            try {
                c.client.putEntry(entry)
                main.post { markClean(entry.id) }
            } catch (_: Exception) {
                // 다음에 연결될 때 다시 올린다
            }
        }
    }

    /** 서버 설정을 저장한 뒤에 부른다 ([old] 는 고치기 전, 새 서버면 null) */
    fun entrySaved(context: Context, old: ServerEntry?, new: ServerEntry) {
        init(context)
        if (old != null && old.cloud && (!new.cloud || old.accountId != new.accountId)) {
            // 클라우드 잠수를 껐거나 계정을 바꿨다: 예전 계정의 클라우드에서 지운다 (잠수 중이면 끊긴다)
            conn(old.accountId)?.let { c ->
                io.execute {
                    try {
                        c.client.deleteEntry(old.id)
                    } catch (_: Exception) {
                    }
                }
            }
            SessionHub.remoteEnd(old.id)
        }
        if (new.cloud) {
            val c = conn(new.accountId) ?: return
            markDirty(new.id)
            SessionHub.remoteBegin(context, new.id)
            push(c, new)
        }
    }

    /** 서버를 지운 뒤에 부른다 */
    fun entryDeleted(context: Context, old: ServerEntry) {
        init(context)
        if (!old.cloud) return
        markClean(old.id)
        // 클라우드에서 지워진 것을 확인할 때까지 기억해 둔다 (연결이 안 돼 못 지웠으면 다음에 붙을 때 다시 지움)
        sp.edit().putStringSet("deleted", (deletedIds() + old.id).toList().takeLast(50).toSet()).apply()
        conn(old.accountId)?.let { c ->
            io.execute {
                try {
                    c.client.deleteEntry(old.id)
                    main.post { forgetDeleted(old.id) }
                } catch (e: CloudException) {
                    // 클라우드에 원래 없던 서버
                    if (e.status == 404) main.post { forgetDeleted(old.id) }
                } catch (_: Exception) {
                }
            }
        }
        SessionHub.remoteEnd(old.id)
    }

    // ------------------------------------------------------------ 어느 매체에서 실행 중인지

    /**
     * 이 서버(같은 계정·같은 주소)가 다른 매체에서 돌고 있으면 그 매체 (없으면 null).
     * 폰 잠수용 서버는 클라우드 쪽을, 클라우드 잠수용 서버는 다른 폰·PC 쪽을 본다.
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
            main.post { done(error) }
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
        val store = Store(context)
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
                        version++
                    }
                } catch (e: CloudException) {
                    if (e.status == 401) main.post { authLost(c) }
                } catch (_: Exception) {
                    // 다음 1분 뒤에 다시 알린다
                    main.post { c.reportedAt = 0 }
                }
            }, "jamsuham-cloud-presence").apply { isDaemon = true }.start()
        }
    }
}
