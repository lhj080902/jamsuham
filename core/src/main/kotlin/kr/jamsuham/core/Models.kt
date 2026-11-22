package kr.jamsuham.core

import kr.jamsuham.core.text.ChatText
import java.util.UUID

/** 접속에 쓰는 계정 정보. accessToken 이 없으면 비정품(오프라인) 접속. */
data class JoinCredentials(val name: String, val uuid: UUID, val accessToken: String?) {
    val online: Boolean get() = accessToken != null

    /** 로그나 오류 보고에 토큰이 그대로 남지 않도록 가린다 (data class 기본 toString 은 토큰을 그대로 찍는다) */
    override fun toString(): String = "JoinCredentials(name=$name, uuid=$uuid, accessToken=${if (accessToken != null) "***" else "없음"})"

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

    /** 모장 인증(세션) 서버 장애·과부하. 복구될 때까지 접속하지 않고 기다린다 */
    data class ServiceDown(val message: String) : CloseReason()

    /** 모장 인증 서버가 이 계정의 요청을 잠깐 막음 (429). 자주 두드릴수록 안 풀리므로 점점 길게 기다린다 */
    data class RateLimited(val message: String) : CloseReason()

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

/**
 * 내 인벤토리 (보기 전용). 칸 번호는 게임의 인벤토리 창 기준:
 * 5~8 갑옷(머리·몸통·다리·발), 9~35 가방, 36~44 핫바, 45 왼손
 */
data class Inventory(val slots: List<MenuItem>, val selectedHotbar: Int) {
    companion object {
        val EMPTY = Inventory(emptyList(), 0)
    }
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

/**
 * 내 캐릭터 상태. 최대 체력은 서버가 보내는 max_health 속성을 따른다 (기본 20 = 하트 10칸, 서버·플러그인이 바꿀 수 있음).
 * absorption = 흡수(노란 하트) 체력.
 */
data class Vitals(
    val health: Float,
    val maxHealth: Float = 20f,
    val absorption: Float = 0f,
    val food: Int = 20,
    val saturation: Float = 0f,
) {
    val dead: Boolean get() = health <= 0f
}

/**
 * 사망 정보. 서버가 보낸 사망 메시지(번역 키 + 인자)에서 뽑는다. 알 수 없으면 각 칸이 null.
 * [killer] 누구에 의해, [cause] 무엇으로(원인), [weapon] 무기, [message] 사망 메시지 전체(한국어로 바꾼 것)
 */
data class DeathInfo(
    val atMs: Long,
    val killer: String? = null,
    val cause: String? = null,
    val weapon: String? = null,
    val message: String? = null,
) {
    /** "누구: 좀비 · 원인: 공격당함" 같은 한 줄. 아무것도 모르면 사망 메시지, 그것도 없으면 "사망했습니다" */
    fun describe(): String {
        val parts = listOfNotNull(
            killer?.let { tr("누구: $it", "Killer: $it") },
            cause?.let { tr("원인: $it", "Cause: $it") },
            weapon?.let { tr("무기: $it", "Weapon: $it") },
        )
        return when {
            parts.isNotEmpty() -> parts.joinToString(" · ")
            !message.isNullOrBlank() -> message
            else -> tr("사망했습니다", "You died")
        }
    }
}

/** 데이터 사용량 (이 잠수를 시작한 뒤 모든 접속 합계). onlineMs = 서버에 들어가 있던 시간 */
data class Traffic(val received: Long, val sent: Long, val onlineMs: Long) {
    val total: Long get() = received + sent

    /** 접속해 있던 시간 기준 시간당 사용량 (1분 미만이면 아직 모름 = null) */
    val perHour: Long? get() = if (onlineMs < 60_000) null else total * 3_600_000 / onlineMs
}

interface SessionListener {
    fun onStatus(status: SessionStatus) {}
    fun onLine(line: LogLine) {}
    fun onPlayers(list: PlayerList) {}
    /** 메뉴가 열리거나 바뀌면 메뉴, 닫히면 null */
    fun onMenu(menu: Menu?) {}
    fun onInventory(inventory: Inventory) {}
    /** 체력·허기가 바뀔 때. 연결이 끊기면 null */
    fun onVitals(vitals: Vitals?) {}
    /** 내 캐릭터가 죽었을 때 */
    fun onDeath(death: DeathInfo) {}
    /** 서버가 리소스팩을 보내라고 함 (앱은 실제로 적용하지 않고, 그림 글자만 읽어 채팅에 그린다) */
    fun onResourcePack(url: String, hash: String) {}
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
