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

/** 서버가 띄운 메뉴 창(상자 GUI 등)의 한 칸 */
data class MenuItem(
    val slot: Int,
    val itemId: String,
    val count: Int,
    val name: ChatText,
    val lore: List<ChatText>,
) {
    /** 이름 + 설명 (규칙 비교용 순수 글자) */
    val searchText: String get() = (listOf(name.plain) + lore.map { it.plain }).joinToString("\n")
}

/** 서버가 띄운 메뉴 창. containerSlots 개 칸이 메뉴 부분이고 그 뒤는 내 인벤토리 */
data class Menu(
    val windowId: Int,
    val title: ChatText,
    val containerSlots: Int,
    val items: List<MenuItem>,
)

/**
 * 메뉴 자동 클릭 규칙. 메뉴 제목에 [title] 이 들어 있으면 이름/설명에 [item] 이 들어간 칸을 클릭한다.
 * 한 줄 형식: "제목 > 아이템"   (제목을 * 로 쓰면 아무 메뉴, 아이템을 #13 처럼 쓰면 13번 칸)
 */
data class AutoClickRule(val title: String, val item: String) {
    fun matches(menu: Menu): MenuItem? {
        if (title != "*" && title.isNotEmpty() && !menu.title.plain.contains(title, ignoreCase = true)) return null
        val target = item.trim()
        if (target.startsWith("#")) {
            val slot = target.substring(1).toIntOrNull() ?: return null
            return menu.items.firstOrNull { it.slot == slot }
        }
        return menu.items.firstOrNull { it.slot < menu.containerSlots && it.searchText.contains(target, ignoreCase = true) }
    }

    companion object {
        fun parse(line: String): AutoClickRule? {
            val i = line.indexOf('>')
            if (i < 0) return null
            val t = line.substring(0, i).trim()
            val it = line.substring(i + 1).trim()
            if (it.isEmpty()) return null
            return AutoClickRule(t.ifEmpty { "*" }, it)
        }
    }
}

interface SessionListener {
    fun onStatus(status: SessionStatus) {}
    fun onLine(line: LogLine) {}
    fun onPlayers(list: PlayerList) {}
    /** 메뉴가 열리거나 바뀌면 메뉴, 닫히면 null */
    fun onMenu(menu: Menu?) {}
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
