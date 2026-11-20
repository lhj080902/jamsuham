package kr.jamsuham.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.jamsuham.core.AfkSession
import kr.jamsuham.core.CoreLog
import kr.jamsuham.core.CredentialsProvider
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.SessionConfig
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import java.util.ArrayDeque

/**
 * 화면(Activity)과 서비스가 함께 보는 세션 상태. 모든 콜백은 메인 스레드에서 전달된다.
 */
object SessionHub {

    interface Observer {
        fun onStatus(status: SessionStatus) {}
        fun onLine(line: LogLine) {}
    }

    private const val MAX_LINES = 500
    private val main = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<Observer>()
    private val buffer = ArrayDeque<LogLine>()
    private var session: AfkSession? = null
    /** 지금 세션을 가리키는 표식. 예전 세션이 늦게 보내는 상태 변화는 무시한다. */
    private var token: Any? = null

    var status: SessionStatus = SessionStatus(SessionState.IDLE, "대기 중")
        private set

    val lines: List<LogLine> get() = ArrayList(buffer)

    val isActive: Boolean
        get() = status.state == SessionState.PREPARING || status.state == SessionState.CONNECTING ||
            status.state == SessionState.ONLINE || status.state == SessionState.WAITING_RECONNECT

    val log = CoreLog { level, message, error ->
        when (level) {
            CoreLog.INFO -> Log.i("Jamsuham", message)
            CoreLog.WARN -> Log.w("Jamsuham", message, error)
            else -> Log.e("Jamsuham", message, error)
        }
    }

    fun addObserver(o: Observer) {
        observers.add(o)
    }

    fun removeObserver(o: Observer) {
        observers.remove(o)
    }

    /** 서비스에서만 부른다 */
    fun start(context: Context, config: SessionConfig, credentials: CredentialsProvider) {
        val myToken = Any()
        token = myToken
        session?.shutdown()
        val listener = object : SessionListener {
            override fun onStatus(status: SessionStatus) {
                main.post { if (token === myToken) publishStatus(status) }
            }

            override fun onLine(line: LogLine) {
                main.post { if (token === myToken) publishLine(line) }
            }
        }
        val s = AfkSession(context.filesDir, config, credentials, listener, log, AndroidDns(context))
        session = s
        s.start()
    }

    /** 사용자가 끊기 (재연결 안 함) */
    fun stopByUser() {
        val s = session
        if (s == null) {
            publishStatus(SessionStatus(SessionState.STOPPED, "연결을 끊었습니다", totalOnlineMs = status.totalOnlineMs))
        } else {
            s.stop()
        }
    }

    /** 서비스가 종료될 때 */
    fun release() {
        token = null
        session?.shutdown()
        session = null
        if (isActive) publishStatus(status.copy(state = SessionState.STOPPED, message = "연결을 끊었습니다", onlineSinceMs = null))
    }

    fun sendChat(text: String): Boolean = session?.sendChat(text) ?: false

    fun onNetworkAvailable() {
        session?.onNetworkAvailable()
    }

    fun clearLines() {
        buffer.clear()
    }

    fun note(text: String) {
        publishLine(LogLine(System.currentTimeMillis(), LogLine.Kind.SYSTEM, kr.jamsuham.core.text.ChatText.of(text)))
    }

    private fun publishStatus(s: SessionStatus) {
        status = s
        for (o in observers.toList()) o.onStatus(s)
    }

    private fun publishLine(line: LogLine) {
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        for (o in observers.toList()) o.onLine(line)
    }
}
