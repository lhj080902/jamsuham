package kr.jamsuham.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.jamsuham.core.AfkSession
import kr.jamsuham.core.CoreLog
import kr.jamsuham.core.CredentialsProvider
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.Menu
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.SessionConfig
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
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
    }

    private class Entry {
        var session: AfkSession? = null
        var token: Any? = null
        var status = SessionStatus(SessionState.IDLE, "꺼짐")
        val lines = ArrayDeque<LogLine>()
        var players: PlayerList = PlayerList.EMPTY
        var menu: Menu? = null
    }

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

    private fun entry(id: String) = entries.getOrPut(id) { Entry() }

    fun addObserver(o: Observer) {
        observers.add(o)
    }

    fun removeObserver(o: Observer) {
        observers.remove(o)
    }

    fun status(serverId: String): SessionStatus = entries[serverId]?.status ?: SessionStatus(SessionState.IDLE, "꺼짐")

    fun lines(serverId: String): List<LogLine> = entries[serverId]?.lines?.let { ArrayList(it) } ?: emptyList()

    fun menu(serverId: String): Menu? = entries[serverId]?.menu

    fun clickMenu(serverId: String, slot: Int): Boolean = entries[serverId]?.session?.clickMenu(slot) ?: false

    fun closeMenu(serverId: String) {
        entries[serverId]?.session?.closeMenu()
    }

    fun players(serverId: String): PlayerList = entries[serverId]?.players ?: PlayerList.EMPTY

    fun isActive(serverId: String): Boolean = status(serverId).state.isActive()

    fun activeIds(): Set<String> = entries.filter { it.value.status.state.isActive() }.keys.toSet()

    val anyActive: Boolean get() = activeIds().isNotEmpty()

    /** 서비스에서만 부른다 */
    fun start(context: Context, serverId: String, config: SessionConfig, credentials: CredentialsProvider) {
        val e = entry(serverId)
        val myToken = Any()
        e.token = myToken
        e.session?.shutdown()
        e.lines.clear()
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
        publishStatus(serverId, SessionStatus(SessionState.PREPARING, "준비 중…"))
        s.start()
    }

    /** 사용자가 끊기 (재접속 안 함) */
    fun stop(serverId: String) {
        val e = entries[serverId] ?: return
        val s = e.session
        if (s == null || !e.status.state.isActive()) {
            publishStatus(serverId, e.status.copy(state = SessionState.STOPPED, message = "꺼짐", onlineSinceMs = null))
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
            if (e.status.state.isActive()) publishStatus(id, e.status.copy(state = SessionState.STOPPED, message = "꺼짐", onlineSinceMs = null))
        }
    }

    /** 이동 버튼 입력 */
    fun setMovement(serverId: String, mask: Int) {
        entries[serverId]?.session?.setMovement(mask)
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
        for (o in observers.toList()) o.onLine(serverId, line)
    }
}

fun SessionState.isActive(): Boolean =
    this == SessionState.PREPARING || this == SessionState.CONNECTING ||
        this == SessionState.ONLINE || this == SessionState.WAITING_RECONNECT
