package kr.jamsuham.core

import kr.jamsuham.core.text.ChatText
import java.util.UUID

/** 접속에 쓰는 계정 정보. accessToken 이 없으면 비정품(오프라인) 접속. */
data class JoinCredentials(val name: String, val uuid: UUID, val accessToken: String?) {
    val online: Boolean get() = accessToken != null

    companion object {
        fun offline(name: String) = JoinCredentials(
            name,
            UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)),
            null,
        )
    }
}

/** 접속 직전에 호출된다(백그라운드 스레드). 마이크로소프트 토큰 갱신 같은 네트워크 작업을 해도 된다. */
fun interface CredentialsProvider {
    @Throws(Exception::class)
    fun get(forceRefresh: Boolean): JoinCredentials
}

/** 로그인을 다시 해야 하는 계정 문제 */
class AuthException(message: String, val needsRelogin: Boolean = true, cause: Throwable? = null) : Exception(message, cause)

/** 실제로 TCP 연결할 주소와 핸드셰이크에 적을 주소 */
data class ResolvedTarget(
    val connectHost: String,
    val connectPort: Int,
    val handshakeHost: String,
    val handshakePort: Int,
)

/** 연결이 끝난 이유 */
sealed class CloseReason {
    /** 서버가 킥 메시지를 보냄 */
    data class Kicked(val text: ChatText, val duringLogin: Boolean) : CloseReason()

    /** 네트워크 끊김, 시간 초과, 접속 실패 등 (튕김) */
    data class Lost(val message: String) : CloseReason()

    /** 사용자가 직접 끊음 */
    object User : CloseReason()

    /** 서버가 다른 주소로 옮기라고 함 (1.20.5+ transfer) */
    data class Transfer(val host: String, val port: Int) : CloseReason()

    /** 마이크로소프트 토큰이 거부됨 (토큰 갱신 후 한 번 더 시도) */
    data class AuthFailed(val message: String) : CloseReason()

    /** 다시 시도해도 소용없는 문제 (정품 필요 등) */
    data class Fatal(val message: String) : CloseReason()
}

/** 세션 상태 */
enum class SessionState {
    IDLE,
    PREPARING,
    CONNECTING,
    ONLINE,
    WAITING_RECONNECT,
    STOPPED,
}

data class SessionStatus(
    val state: SessionState,
    val message: String = "",
    val serverVersion: String? = null,
    val onlineSinceMs: Long? = null,
    val nextAttemptAtMs: Long? = null,
    val reconnectCount: Int = 0,
    val totalOnlineMs: Long = 0,
)

/** 화면에 띄울 채팅/시스템 줄 */
data class LogLine(val timeMs: Long, val kind: Kind, val text: ChatText) {
    enum class Kind { CHAT, SYSTEM, ERROR }
}

/** 탭 목록(접속자 목록)의 한 명 */
data class PlayerInfo(
    val uuid: UUID,
    val name: String,
    /** 서버가 꾸며서 보내는 이름 (색, 칭호 등). 없으면 null */
    val displayName: ChatText?,
    val latencyMs: Int,
    val gameMode: Int,
    /** 스킨 이미지 주소 (https://textures.minecraft.net/...). 비정품 서버 등은 null */
    val skinUrl: String?,
    val order: Int,
) {
    val shownName: ChatText get() = displayName?.takeIf { it.plain.isNotBlank() } ?: ChatText.of(name)
}

/** 탭 목록 전체 + 위/아래 문구 */
data class PlayerList(val players: List<PlayerInfo>, val header: ChatText, val footer: ChatText) {
    companion object {
        val EMPTY = PlayerList(emptyList(), ChatText(emptyList()), ChatText(emptyList()))
    }
}

interface SessionListener {
    fun onStatus(status: SessionStatus) {}
    fun onLine(line: LogLine) {}
    fun onPlayers(list: PlayerList) {}
}

/** 아주 단순한 로거 (안드로이드에서는 Logcat 으로 연결) */
fun interface CoreLog {
    fun log(level: Int, message: String, error: Throwable?)

    fun i(msg: String) = log(INFO, msg, null)
    fun w(msg: String, t: Throwable? = null) = log(WARN, msg, t)
    fun e(msg: String, t: Throwable? = null) = log(ERROR, msg, t)

    companion object {
        const val INFO = 1
        const val WARN = 2
        const val ERROR = 3

        val STDOUT = CoreLog { level, message, error ->
            val tag = when (level) { INFO -> "I"; WARN -> "W"; else -> "E" }
            println("[$tag] $message")
            error?.printStackTrace(System.out)
        }
    }
}
