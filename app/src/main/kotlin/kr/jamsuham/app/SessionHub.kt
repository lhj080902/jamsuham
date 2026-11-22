package kr.jamsuham.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.jamsuham.core.AfkSession
import kr.jamsuham.core.CoreLog
import kr.jamsuham.core.CredentialsProvider
import kr.jamsuham.core.DeathInfo
import kr.jamsuham.core.Inventory
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.Menu
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.SessionConfig
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.Vitals
import kr.jamsuham.core.net.CommandSuggestions
import kr.jamsuham.core.text.ChatText
import java.util.ArrayDeque

/**
 * 서버별 잠수 세션 상태. 여러 서버에 동시에 잠수할 수 있다.
 * 화면과 서비스가 함께 보며, 모든 콜백은 메인 스레드에서 전달된다.
 */
object SessionHub {

    interface Observer {
        fun onStatus(serverId: String, status: SessionStatus) {}
        fun onLine(serverId: String, line: LogLine) {}
        fun onPlayers(serverId: String, list: PlayerList) {}
        fun onMenu(serverId: String, menu: Menu?) {}
        fun onInventory(serverId: String, inventory: Inventory) {}
        fun onVitals(serverId: String, vitals: Vitals?) {}
        /** 사망했을 때, 또는 사망 알림을 지웠을 때(null) */
        fun onDeath(serverId: String, death: DeathInfo?) {}
        /** 리소스팩 그림 글자를 새로 불러왔을 때 (채팅을 다시 그림) */
        fun onGlyphs(serverId: String) {}
    }

    private class Entry {
        var session: AfkSession? = null
        var token: Any? = null
        var status = SessionStatus(SessionState.IDLE, tr("꺼짐", "Off"))
        val lines = ArrayDeque<LogLine>()
        var players: PlayerList = PlayerList.EMPTY
        var menu: Menu? = null
        var inventory: Inventory = Inventory.EMPTY
        var notifiedMenuWindow = -1
        var vitals: Vitals? = null
        /** 마지막 사망 (화면 위 알림 줄에 띄움. 닫으면 null) */
        var lastDeath: DeathInfo? = null
        /** 이번 접속에서 서버가 보낸 리소스팩 주소 (순서대로, 뒤의 팩이 덮어씀) */
        val packUrls = ArrayList<String>()
        /** 팩 주소 → 서버가 알려 준 SHA-1 (캐시 위치를 찾는 데 씀) */
        val packHashes = HashMap<String, String>()
        /** 팩별 그림 글자 상태 (상태 탭에 보여 줌) */
        val packStates = LinkedHashMap<String, PackState>()
        /** 그 팩들에서 읽은 그림 글자 (아직 없으면 null) */
        var glyphs: kr.jamsuham.core.pack.PackFonts.FontSet? = null
    }

    /** 리소스팩 하나의 그림 글자 상태. [glyphs] 읽은 그림 글자 수 (받는 중·실패면 null), [error] 실패 이유 */
    data class PackState(
        val url: String,
        val glyphs: Int?,
        val fonts: Int = 0,
        val error: String? = null,
        val skipped: Boolean = false,
        val report: String? = null,
        /** 앱이 뒤에 있어서 메모리에 올리지 않음 (앱을 열면 다시 읽음) */
        val deferred: Boolean = false,
    )

    /** 1분마다 다시 계산하는 시간당 데이터 사용량 (알림과 상태 탭이 같은 값을 보도록) */
    private val rates = HashMap<String, Long>()

    private const val MAX_LINES = 500
    private val main = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<Observer>()
    private val entries = HashMap<String, Entry>()

    val log = CoreLog { level, message, error ->
        when (level) {
            CoreLog.INFO -> Log.i("Jamsuham", message)
            CoreLog.WARN -> Log.w("Jamsuham", message, error)
            else -> Log.e("Jamsuham", message, error)
        }
    }

    private lateinit var appContext: Context

    private fun entry(id: String) = entries.getOrPut(id) { Entry() }

    fun addObserver(o: Observer) {
        observers.add(o)
    }

    fun removeObserver(o: Observer) {
        observers.remove(o)
    }

    fun status(serverId: String): SessionStatus = entries[serverId]?.status ?: SessionStatus(SessionState.IDLE, tr("꺼짐", "Off"))

    fun lines(serverId: String): List<LogLine> = entries[serverId]?.lines?.let { ArrayList(it) } ?: emptyList()

    fun menu(serverId: String): Menu? = entries[serverId]?.menu

    fun clickMenu(serverId: String, slot: Int): Boolean = entries[serverId]?.session?.clickMenu(slot) ?: false

    fun useItem(serverId: String, slot: Int): Boolean = entries[serverId]?.session?.useItem(slot) ?: false

    fun closeMenu(serverId: String) {
        entries[serverId]?.session?.closeMenu()
    }

    fun inventory(serverId: String): Inventory = entries[serverId]?.inventory ?: Inventory.EMPTY

    fun players(serverId: String): PlayerList = entries[serverId]?.players ?: PlayerList.EMPTY

    fun vitals(serverId: String): Vitals? = entries[serverId]?.vitals

    /** 이 서버 잠수를 시작한 뒤의 데이터 사용량 (시작한 적 없으면 null) */
    fun traffic(serverId: String): kr.jamsuham.core.Traffic? = entries[serverId]?.session?.traffic

    /**
     * 시간당 데이터 사용량 (접속해 있던 시간 기준 평균). 알림과 상태 탭이 똑같이 보이도록 [refreshRates] 를 부를 때(1분마다)만 바뀐다.
     * 1분 미만 접속이면 null (측정 중).
     */
    fun ratePerHour(serverId: String): Long? = rates[serverId]

    /** 서비스가 1분마다 부른다 */
    fun refreshRates() {
        for ((id, e) in entries) {
            val r = e.session?.traffic?.perHour
            if (r == null) rates.remove(id) else rates[id] = r
        }
    }

    /** 이 서버 리소스팩들의 그림 글자 상태 */
    fun packStates(serverId: String): List<PackState> = entries[serverId]?.packStates?.values?.toList() ?: emptyList()

    fun lastDeath(serverId: String): DeathInfo? = entries[serverId]?.lastDeath

    /** 이 서버 리소스팩의 그림 글자 (없으면 null → 그림 글자는 숨김) */
    fun glyphs(serverId: String): kr.jamsuham.core.pack.PackFonts.FontSet? = entries[serverId]?.glyphs

    /**
     * 팩 [url] 의 그림 글자를 읽어 [e] 에 겹친다 (받아 둔 것이 있으면 기기에서 바로 읽음).
     * 앱이 뒤로 가 있으면 메모리를 아끼려고 읽지 않고, 앱을 다시 열 때 [restoreGlyphs] 가 읽는다.
     * [announce] 채팅에 "N개를 불러왔습니다" 를 남길지 (앱을 다시 열 때 다시 읽는 경우는 조용히).
     */
    private fun loadPack(serverId: String, e: Entry, url: String, announce: Boolean) {
        val token = e.token
        if (!JamsuApp.inForeground) {
            e.packStates[url] = PackState(url, null, error = null, deferred = true)
            notifyGlyphs(serverId)
            return
        }
        e.packStates[url] = PackState(url, null)
        notifyGlyphs(serverId)
        val server = Store(appContext).server(serverId)
        Glyphs.load(appContext, url, e.packHashes[url].orEmpty(), server?.packWifiOnly ?: true) { set, why ->
            if (e.token !== token) return@load
            if (set == null) {
                e.packStates[url] = PackState(url, null, error = why ?: tr("알 수 없는 오류", "Unknown error"))
                publishLine(serverId, LogLine(System.currentTimeMillis(), LogLine.Kind.SYSTEM, ChatText.of(tr("리소스팩 그림 글자를 불러오지 못했습니다: $why", "Couldn't load resource pack glyphs: $why"))))
                notifyGlyphs(serverId)
                return@load
            }
            e.packStates[url] = PackState(url, set.glyphCount, set.fonts.size, report = set.report)
            // 받아 둔 팩들을 서버가 보낸 순서대로 겹친다
            val sets = e.packUrls.mapNotNull { u -> if (u == url) set else Glyphs.cached(u) }
            e.glyphs = kr.jamsuham.core.pack.PackFonts.FontSet.merge(sets)
            if (announce) {
                val msg = if (set.glyphCount > 0) tr("리소스팩 그림 글자 ${set.glyphCount}개를 불러왔습니다", "Loaded ${set.glyphCount} resource pack glyphs")
                else tr("이 서버의 리소스팩에는 그림 글자가 없습니다", "This server's resource pack has no glyphs")
                publishLine(serverId, LogLine(System.currentTimeMillis(), LogLine.Kind.SYSTEM, ChatText.of(msg)))
            }
            // 지금 쓰는 팩만 기기에 남기고 예전 서버의 팩은 지운다 (저장 공간 절약)
            Glyphs.pruneCache(appContext, entries.values.flatMap { en -> en.packUrls.map { it to en.packHashes[it].orEmpty() } })
            notifyGlyphs(serverId)
        }
    }

    /** 앱이 뒤로 갔다: 그림 글자를 메모리에서 내린다 (큰 팩은 수십 MB). 기기에 받아 둔 파일은 그대로 */
    fun releaseGlyphs() {
        var any = false
        for ((id, e) in entries) {
            if (e.glyphs == null) continue
            e.glyphs = null
            for (url in e.packUrls) e.packStates[url]?.let { if (it.glyphs != null) e.packStates[url] = it.copy(deferred = true) }
            any = true
            notifyGlyphs(id)
        }
        if (any) Glyphs.releaseMemory()
    }

    /** 앱을 다시 열었다: 내렸던(또는 뒤에 있을 때 미뤘던) 그림 글자를 기기에서 다시 읽는다 */
    fun restoreGlyphs() {
        if (!::appContext.isInitialized) return
        for ((id, e) in entries) {
            if (!e.status.state.isActive() && e.session == null) continue
            for (url in e.packUrls.toList()) {
                val st = e.packStates[url]
                if (e.glyphs == null && st != null && !st.skipped && (st.deferred || st.glyphs != null)) loadPack(id, e, url, announce = false)
            }
        }
    }

    private fun notifyGlyphs(serverId: String) {
        for (o in observers.toList()) o.onGlyphs(serverId)
    }

    /** 사망 알림 줄 닫기 */
    fun clearDeath(serverId: String) {
        val e = entries[serverId] ?: return
        if (e.lastDeath == null) return
        e.lastDeath = null
        for (o in observers.toList()) o.onDeath(serverId, null)
    }

    fun isActive(serverId: String): Boolean = status(serverId).state.isActive()

    fun activeIds(): Set<String> = entries.filter { it.value.status.state.isActive() }.keys.toSet()

    val anyActive: Boolean get() = activeIds().isNotEmpty()

    /** 서비스에서만 부른다 */
    fun start(context: Context, serverId: String, config: SessionConfig, credentials: CredentialsProvider) {
        appContext = context.applicationContext
        val e = entry(serverId)
        val myToken = Any()
        e.token = myToken
        e.session?.shutdown()
        e.lines.clear()
        // 새 접속에서 서버가 리소스팩을 다시 보내면 그 순서대로 다시 겹친다 (그림 글자는 받기 전까지 이전 것을 그대로 씀)
        e.packUrls.clear()
        e.packHashes.clear()
        e.packStates.clear()
        rates.remove(serverId)
        // 이전 세션의 체력·메뉴·인벤·접속자가 새 접속에 남아 보이지 않게 비운다
        // (토큰을 먼저 바꿔서 이전 세션의 "비움" 알림은 무시되므로 여기서 직접 비움)
        e.vitals = null
        e.menu = null
        e.inventory = Inventory.EMPTY
        e.players = PlayerList.EMPTY
        for (o in observers.toList()) {
            o.onVitals(serverId, null)
            o.onMenu(serverId, null)
            o.onInventory(serverId, Inventory.EMPTY)
            o.onPlayers(serverId, PlayerList.EMPTY)
        }
        val listener = object : SessionListener {
            override fun onStatus(status: SessionStatus) {
                main.post { if (e.token === myToken) publishStatus(serverId, status) }
            }

            override fun onLine(line: LogLine) {
                main.post { if (e.token === myToken) publishLine(serverId, line) }
            }

            override fun onMenu(menu: Menu?) {
                main.post {
                    if (e.token === myToken) {
                        e.menu = menu
                        for (o in observers.toList()) o.onMenu(serverId, menu)
                        // 앱을 안 보고 있을 때 자동 클릭 규칙에 안 걸리는 메뉴가 열리면 알림으로 알려 준다 (보상 받기 등)
                        if (menu == null) {
                            e.notifiedMenuWindow = -1
                            MenuNotifier.cancel(appContext, serverId)
                        } else if (menu.windowId != e.notifiedMenuWindow && !JamsuApp.inForeground &&
                            config.autoClickRules.none { it.matches(menu) != null }
                        ) {
                            e.notifiedMenuWindow = menu.windowId
                            MenuNotifier.show(appContext, serverId, menu.title.plain)
                        }
                    }
                }
            }

            override fun onInventory(inventory: Inventory) {
                main.post {
                    if (e.token === myToken) {
                        e.inventory = inventory
                        for (o in observers.toList()) o.onInventory(serverId, inventory)
                    }
                }
            }

            override fun onVitals(vitals: Vitals?) {
                main.post {
                    if (e.token === myToken) {
                        e.vitals = vitals
                        for (o in observers.toList()) o.onVitals(serverId, vitals)
                    }
                }
            }

            override fun onResourcePack(url: String, hash: String) {
                main.post {
                    if (e.token !== myToken) return@post
                    val server = Store(appContext).server(serverId)
                    if (!url.startsWith("http")) return@post
                    if (server?.packGlyphs == false) {
                        e.packStates[url] = PackState(url, null, skipped = true)
                        notifyGlyphs(serverId)
                        return@post
                    }
                    if (url !in e.packUrls) e.packUrls.add(url)
                    e.packHashes[url] = hash
                    loadPack(serverId, e, url, announce = true)
                }
            }

            override fun onDeath(death: DeathInfo) {
                main.post {
                    if (e.token === myToken) {
                        e.lastDeath = death
                        for (o in observers.toList()) o.onDeath(serverId, death)
                        // 앱을 안 보고 있을 때 죽으면 알림으로 알려 준다
                        if (!JamsuApp.inForeground) DeathNotifier.show(appContext, serverId, death)
                    }
                }
            }

            override fun onPlayers(list: PlayerList) {
                main.post {
                    if (e.token === myToken) {
                        e.players = list
                        for (o in observers.toList()) o.onPlayers(serverId, list)
                    }
                }
            }
        }
        val s = AfkSession(context.filesDir, config, credentials, listener, log, AndroidDns(context))
        e.session = s
        publishStatus(serverId, SessionStatus(SessionState.PREPARING, tr("준비 중…", "Preparing…")))
        s.start()
    }

    /** 사용자가 끊기 (재접속 안 함) */
    fun stop(serverId: String) {
        val e = entries[serverId] ?: return
        val s = e.session
        if (s == null || !e.status.state.isActive()) {
            publishStatus(serverId, e.status.copy(state = SessionState.STOPPED, message = tr("꺼짐", "Off"), onlineSinceMs = null))
        } else {
            s.stop()
        }
    }

    /** 서비스가 사라질 때 모든 세션 정리 */
    fun releaseAll() {
        for ((id, e) in entries) {
            e.token = null
            e.session?.shutdown()
            e.session = null
            e.players = PlayerList.EMPTY
            e.menu = null
            e.inventory = Inventory.EMPTY
            if (::appContext.isInitialized) MenuNotifier.cancel(appContext, id)
            if (e.status.state.isActive()) publishStatus(id, e.status.copy(state = SessionState.STOPPED, message = tr("꺼짐", "Off"), onlineSinceMs = null))
        }
    }

    /** 이동 버튼 입력 */
    fun setMovement(serverId: String, mask: Int) {
        entries[serverId]?.session?.setMovement(mask)
    }

    /** 조이스틱 입력 (동쪽·남쪽 성분) */
    fun setStick(serverId: String, east: Float, south: Float) {
        entries[serverId]?.session?.setStick(east, south)
    }

    /** 미니맵용: 지금 접속 중인 연결 (위치·주변 블록·다른 플레이어) */
    fun live(serverId: String): kr.jamsuham.core.net.McConnection? {
        val e = entries[serverId] ?: return null
        if (e.status.state != SessionState.ONLINE) return null
        return e.session?.liveConnection
    }

    fun sendChat(serverId: String, text: String): Boolean = entries[serverId]?.session?.sendChat(text) ?: false

    /** 자동완성 후보 요청. 결과는 메인 스레드로 */
    fun requestSuggestions(serverId: String, text: String, callback: (CommandSuggestions?) -> Unit): Boolean {
        val s = entries[serverId]?.session ?: return false
        return s.requestSuggestions(text) { result -> main.post { callback(result) } }
    }

    fun onNetworkAvailable() {
        for (e in entries.values) e.session?.onNetworkAvailable()
    }

    fun clearLines(serverId: String) {
        entries[serverId]?.lines?.clear()
    }

    fun note(serverId: String, text: String, kind: LogLine.Kind = LogLine.Kind.SYSTEM) {
        publishLine(serverId, LogLine(System.currentTimeMillis(), kind, ChatText.of(text)))
    }

    private fun publishStatus(serverId: String, s: SessionStatus) {
        entry(serverId).status = s
        for (o in observers.toList()) o.onStatus(serverId, s)
    }

    private fun publishLine(serverId: String, line: LogLine) {
        val buf = entry(serverId).lines
        buf.addLast(line)
        while (buf.size > MAX_LINES) buf.removeFirst()
        ChatLog.append(serverId, line)
        for (o in observers.toList()) o.onLine(serverId, line)
    }
}

fun SessionState.isActive(): Boolean =
    this == SessionState.PREPARING || this == SessionState.CONNECTING ||
        this == SessionState.ONLINE || this == SessionState.WAITING_RECONNECT
