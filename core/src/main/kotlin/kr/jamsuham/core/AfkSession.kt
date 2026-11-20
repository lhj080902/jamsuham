package kr.jamsuham.core

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import kr.jamsuham.core.net.AddressResolver
import kr.jamsuham.core.net.ConnectionEvents
import kr.jamsuham.core.net.McConnection
import kr.jamsuham.core.net.RawDnsQuery
import kr.jamsuham.core.net.StatusPinger
import kr.jamsuham.core.text.ChatText
import kr.jamsuham.core.via.ViaSetup
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class SessionConfig(
    /** "play.example.com", "1.2.3.4:25566" 등 */
    val address: String,
    /** null 이면 서버 버전 자동 감지 */
    val fixedVersion: String? = null,
    /** 튕겼을 때 자동 재연결 */
    val autoReconnect: Boolean = true,
    /** 서버가 킥했을 때도 재연결 (차단/화이트리스트 킥은 제외) */
    val reconnectOnKick: Boolean = true,
    /** 재연결 대기 최대값 */
    val maxBackoffSec: Int = 60,
    /** 접속할 때마다 자동으로 보낼 명령어/채팅 (예: "/잠수", "/server afk") */
    val joinCommands: List<String> = emptyList(),
    /** 접속 후 첫 명령어까지 기다릴 시간 */
    val joinCommandDelaySec: Int = 3,
    /** 메뉴 자동 클릭 규칙 */
    val autoClickRules: List<AutoClickRule> = emptyList(),
)

/**
 * 잠수 세션 하나를 관리한다: 주소 해석 → 버전 감지 → 접속 → 끊기면 이유를 보고 재연결.
 *
 * 재연결 규칙
 *  - 사용자가 [stop] 을 누르면 다시 연결하지 않는다.
 *  - 앱이 꺼지거나 폰 전원이 꺼지면 이 객체도 사라지므로 당연히 재연결하지 않는다.
 *  - 네트워크가 끊겨 튕긴 경우: 짧게 기다렸다 재연결, 네트워크가 돌아오면 바로 재시도.
 *  - "이미 프록시에 접속 중" 킥: 프록시가 옛 연결을 정리할 때까지(보통 30초 안팎) 점점 길게 기다린다.
 *  - 차단/화이트리스트 킥: 계속 두드리면 안 되므로 멈춘다.
 */
private const val MENU_WAIT_MAX_MS = 120_000L

class AfkSession(
    private val dataDir: File,
    private val config: SessionConfig,
    private val credentials: CredentialsProvider,
    private val listener: SessionListener,
    private val log: CoreLog = CoreLog.STDOUT,
    private val dns: RawDnsQuery? = AddressResolver.UdpDns(listOf("1.1.1.1", "8.8.8.8")),
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jamsuham-session").apply { isDaemon = true }
    }

    // 아래 상태는 모두 exec 스레드에서만 바꾼다
    private var userStopped = false
    @Volatile private var connection: McConnection? = null
    private var pending: ScheduledFuture<*>? = null
    private var failures = 0
    private var reconnectCount = 0
    private var lastReason: CloseReason? = null
    private var onlineSince: Long? = null
    private var totalOnlineMs = 0L
    private var detectedVersion: ProtocolVersion? = null
    private var transferTo: Pair<String, Int>? = null
    private var authRetried = false
    private var generation = 0
    private var status = SessionStatus(SessionState.IDLE)

    val currentStatus: SessionStatus get() = status

    fun start() = submit {
        userStopped = false
        failures = 0
        attempt()
    }

    /** 사용자가 직접 끊기. 재연결하지 않는다. */
    fun stop() = submit {
        userStopped = true
        pending?.cancel(false)
        pending = null
        val c = connection
        if (c != null) {
            c.disconnect()
        } else {
            setStatus(SessionState.STOPPED, "연결을 끊었습니다")
        }
    }

    /** 세션 객체를 버릴 때 */
    fun shutdown() {
        stop()
        submit { exec.shutdown() }
    }

    fun sendChat(text: String): Boolean {
        val c = connection ?: return false
        return c.sendChat(text)
    }

    /** 이동 입력 (비트: 1 북, 2 남, 4 서, 8 동, 16 점프). 손을 떼면 0 */
    fun setMovement(mask: Int) {
        connection?.movementInput = mask
    }

    /** 열린 메뉴의 칸 클릭 (왼쪽 클릭) */
    fun clickMenu(slot: Int): Boolean = connection?.clickMenuSlot(slot) ?: false

    /** 열린 메뉴 닫기 */
    fun closeMenu() {
        connection?.closeMenu()
    }

    /** 지금 연결(미니맵이 위치·주변 블록·다른 플레이어를 읽는 데 씀). 접속 중이 아니면 null */
    val liveConnection: McConnection? get() = connection

    /** 명령어 자동완성 후보 요청 (결과는 아무 스레드에서나 올 수 있음) */
    fun requestSuggestions(text: String, callback: (kr.jamsuham.core.net.CommandSuggestions?) -> Unit): Boolean {
        val c = connection ?: return false
        return c.requestSuggestions(text, callback)
    }

    /** 네트워크(와이파이/데이터)가 다시 잡혔을 때 앱이 불러 준다 */
    fun onNetworkAvailable() = submit {
        if (userStopped || status.state != SessionState.WAITING_RECONNECT) return@submit
        val floorSec = if (isAlreadyConnected(lastReason)) 15L else 2L
        val remaining = (status.nextAttemptAtMs ?: 0L) - System.currentTimeMillis()
        if (remaining > floorSec * 1000) {
            line(LogLine.Kind.SYSTEM, "네트워크가 다시 연결돼서 ${floorSec}초 뒤 재접속합니다")
            schedule(floorSec)
        }
    }

    // ------------------------------------------------------------------

    /** 세션 스레드에서 실행. 이미 종료된 세션이면 조용히 무시한다. */
    private fun submit(block: () -> Unit) {
        try {
            exec.execute(block)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun attempt() {
        if (userStopped) return
        pending = null
        try {
            if (!ViaSetup.isInitialized) {
                setStatus(SessionState.PREPARING, "버전 변환기 준비 중… (처음 한 번만 오래 걸립니다)")
                val t0 = System.currentTimeMillis()
                ViaSetup.init(File(dataDir, "via"))
                log.i("ViaVersion 준비 완료 (${System.currentTimeMillis() - t0}ms)")
            }

            val transfer = transferTo
            val target = if (transfer != null) {
                AddressResolver.resolve("${transfer.first}:${transfer.second}", null)
            } else {
                setStatus(SessionState.PREPARING, "서버 주소 확인 중…")
                AddressResolver.resolve(config.address, dns)
            }

            val version = resolveVersion(target) ?: return
            ViaSetup.prepare(version)

            setStatus(SessionState.PREPARING, "계정 확인 중…", version.name)
            val creds = try {
                credentials.get(forceRefresh = authRetried)
            } catch (e: AuthException) {
                if (e.needsRelogin) {
                    stopWithError("계정 로그인이 필요합니다: ${e.message}")
                    return
                }
                throw e
            }

            setStatus(SessionState.CONNECTING, "접속 중… (${target.connectHost}:${target.connectPort})", version.name)
            generation++
            val conn = McConnection(target, version, creds, Events(generation), log, transferIntent = transfer != null, autoClickRules = config.autoClickRules)
            connection = conn
            transferTo = null
            conn.connect()
        } catch (e: IllegalArgumentException) {
            stopWithError(e.message ?: "잘못된 설정")
        } catch (t: Throwable) {
            log.w("접속 준비 실패", t)
            handleClosed(CloseReason.Lost("접속 준비 실패: ${McConnection.describe(t)}"))
        }
    }

    /** 고정 버전이 있으면 그것, 없으면 서버 핑으로 감지. 결정할 수 없으면 null (세션은 멈춘다) */
    private fun resolveVersion(target: ResolvedTarget): ProtocolVersion? {
        val fixed = config.fixedVersion
        if (fixed != null) {
            val v = ViaSetup.versionByName(fixed)
            if (v == null) {
                stopWithError("지원하지 않는 버전입니다: $fixed")
                return null
            }
            return v
        }
        setStatus(SessionState.PREPARING, "서버 버전 확인 중…")
        val ping = try {
            StatusPinger.ping(target, ViaSetup.newestProtocol())
        } catch (e: Exception) {
            // 핑이 안 되면 서버가 꺼졌거나 네트워크 문제. 전에 감지한 버전이 있으면 그걸로 시도
            val cached = detectedVersion
            if (cached != null) {
                log.w("핑 실패, 이전에 감지한 ${cached.name} 로 시도: ${e.message}")
                return cached
            }
            handleClosed(CloseReason.Lost("서버에 연결할 수 없습니다: ${McConnection.describe(e)}"))
            return null
        }
        val v = ViaSetup.versionForProtocol(ping.protocol)
        if (v == null) {
            stopWithError(
                "서버 버전을 자동으로 알아내지 못했습니다 (서버 응답: ${ping.versionName}, 프로토콜 ${ping.protocol}). " +
                    "설정에서 버전을 직접 골라 주세요.",
            )
            return null
        }
        if (detectedVersion != v) {
            line(LogLine.Kind.SYSTEM, "서버 버전 감지: ${v.name} (${ping.versionName}, 접속자 ${ping.online}/${ping.max}, ${ping.latencyMs}ms)")
        }
        detectedVersion = v
        return v
    }

    private inner class Events(private val gen: Int) : ConnectionEvents {
        override fun onJoined() = submit {
            onlineSince = System.currentTimeMillis()
            authRetried = false
            line(LogLine.Kind.SYSTEM, "서버에 들어갔습니다")
            setStatus(SessionState.ONLINE, "접속 중", detectedVersion?.name ?: config.fixedVersion)
            // 1분 이상 버티면 실패 횟수 초기화
            exec.schedule({ if (status.state == SessionState.ONLINE && generation == gen) failures = 0 }, 60, TimeUnit.SECONDS)
            scheduleJoinCommands(gen)
        }

        override fun onChat(text: ChatText, system: Boolean) {
            listener.onLine(LogLine(System.currentTimeMillis(), LogLine.Kind.CHAT, text))
        }

        override fun onInfo(message: String) = line(LogLine.Kind.SYSTEM, message)

        override fun onPlayers(list: PlayerList) {
            if (generation == gen) listener.onPlayers(list)
        }

        override fun onMenu(menu: Menu?) {
            if (generation == gen) listener.onMenu(menu)
        }

        override fun onInventory(inventory: Inventory) {
            if (generation == gen) listener.onInventory(inventory)
        }

        override fun onClosed(reason: CloseReason) = submit { handleClosed(reason) }
    }

    /**
     * 재접속하면 로비로 떨어지는 서버가 많아서, 접속할 때마다 잠수 명령어를 다시 보낸다.
     * 서버 메뉴(리소스팩 적용 창 등)가 열려 있으면 명령어가 메뉴를 날려 버릴 수 있으므로,
     * 메뉴가 닫힐 때까지(직접 고르거나 자동 클릭) 기다렸다가 2초 뒤에 이어서 보낸다. 최대 2분까지만 기다린다.
     */
    private fun scheduleJoinCommands(gen: Int) {
        val commands = config.joinCommands.map { it.trim() }.filter { it.isNotEmpty() }
        if (commands.isEmpty()) return
        val firstAt = System.currentTimeMillis() + config.joinCommandDelaySec.coerceAtLeast(0) * 1000L
        var waitingForMenu = false

        fun step(i: Int) {
            if (generation != gen || status.state != SessionState.ONLINE || userStopped || i >= commands.size) return
            val conn = connection
            val menuOpen = conn?.hasOpenMenu == true
            if (menuOpen && System.currentTimeMillis() - firstAt < MENU_WAIT_MAX_MS) {
                if (!waitingForMenu) {
                    waitingForMenu = true
                    line(LogLine.Kind.SYSTEM, "서버 메뉴가 열려 있어서 자동 실행을 잠시 미룹니다 (메뉴에서 고르거나 닫으면 이어서 실행)")
                }
                exec.schedule({ step(i) }, 1, TimeUnit.SECONDS)
                return
            }
            waitingForMenu = false
            // 메뉴가 방금 닫혔다: 서버가 리소스팩 적용 등을 끝낼 시간(2초)을 준다
            val sinceClose = System.currentTimeMillis() - (conn?.lastMenuClosedMs ?: 0L)
            if (sinceClose in 0 until 2000) {
                exec.schedule({ step(i) }, 2000 - sinceClose, TimeUnit.MILLISECONDS)
                return
            }
            val cmd = commands[i]
            val ok = conn?.sendChat(cmd) == true
            line(LogLine.Kind.SYSTEM, if (ok) "자동 실행: $cmd" else "자동 실행 실패: $cmd")
            exec.schedule({ step(i + 1) }, 1500, TimeUnit.MILLISECONDS)
        }
        exec.schedule({ step(0) }, (firstAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun handleClosed(reason: CloseReason) {
        connection = null
        listener.onPlayers(PlayerList.EMPTY)
        listener.onMenu(null)
        listener.onInventory(Inventory.EMPTY)
        onlineSince?.let { totalOnlineMs += System.currentTimeMillis() - it }
        onlineSince = null
        lastReason = reason

        val text = describe(reason)
        if (reason !is CloseReason.User) line(if (reason is CloseReason.Kicked) LogLine.Kind.ERROR else LogLine.Kind.SYSTEM, text)

        if (userStopped || reason is CloseReason.User) {
            setStatus(SessionState.STOPPED, "연결을 끊었습니다")
            return
        }
        when (reason) {
            is CloseReason.Fatal -> {
                stopWithError(reason.message)
                return
            }
            is CloseReason.AuthFailed -> {
                if (!authRetried) {
                    authRetried = true
                    line(LogLine.Kind.SYSTEM, "토큰을 새로 받아 다시 시도합니다")
                    schedule(2)
                } else {
                    stopWithError("${reason.message}")
                }
                return
            }
            is CloseReason.Transfer -> {
                transferTo = reason.host to reason.port
                line(LogLine.Kind.SYSTEM, "서버가 ${reason.host}:${reason.port} 로 이동시킵니다")
                schedule(1)
                return
            }
            is CloseReason.Kicked -> {
                val plain = reason.text.plain
                if (isPermanentKick(plain)) {
                    stopWithError("다시 접속하지 않습니다 (차단·화이트리스트·인증 문제로 보이는 킥): $plain")
                    return
                }
                if (!config.reconnectOnKick && !isAlreadyConnected(reason)) {
                    setStatus(SessionState.STOPPED, "서버에서 킥당했습니다")
                    return
                }
            }
            else -> {}
        }
        if (!config.autoReconnect) {
            setStatus(SessionState.STOPPED, text)
            return
        }
        failures++
        reconnectCount++
        schedule(backoffSeconds(reason, failures))
    }

    private fun schedule(delaySec: Long) {
        pending?.cancel(false)
        val at = System.currentTimeMillis() + delaySec * 1000
        status = status.copy(
            state = SessionState.WAITING_RECONNECT,
            message = "${delaySec}초 뒤 다시 접속합니다",
            nextAttemptAtMs = at,
            onlineSinceMs = null,
            reconnectCount = reconnectCount,
            totalOnlineMs = totalOnlineMs,
        )
        listener.onStatus(status)
        pending = exec.schedule({ attempt() }, delaySec, TimeUnit.SECONDS)
    }

    private fun backoffSeconds(reason: CloseReason, n: Int): Long {
        val max = config.maxBackoffSec.coerceAtLeast(10).toLong()
        val steps: LongArray = when {
            isAlreadyConnected(reason) -> longArrayOf(15, 25, 35, 45, 60)
            reason is CloseReason.Kicked -> longArrayOf(10, 20, 30, 60)
            else -> longArrayOf(3, 5, 10, 20, 30, 60)
        }
        return steps[(n - 1).coerceIn(0, steps.size - 1)].coerceAtMost(max)
    }

    private fun stopWithError(message: String) {
        pending?.cancel(false)
        pending = null
        line(LogLine.Kind.ERROR, message)
        setStatus(SessionState.STOPPED, message)
    }

    private fun setStatus(state: SessionState, message: String, version: String? = status.serverVersion) {
        status = SessionStatus(
            state = state,
            message = message,
            serverVersion = version,
            onlineSinceMs = onlineSince,
            nextAttemptAtMs = null,
            reconnectCount = reconnectCount,
            totalOnlineMs = totalOnlineMs,
        )
        listener.onStatus(status)
    }

    private fun line(kind: LogLine.Kind, text: String) {
        listener.onLine(LogLine(System.currentTimeMillis(), kind, ChatText.of(text)))
    }

    companion object {
        private val ALREADY_CONNECTED = listOf(
            "already connected", "already online", "already logged", "already playing",
            "logged in from another", "이미 접속", "이미 연결", "이미 서버", "이미 온라인", "중복 접속", "중복 로그인",
        )
        private val PERMANENT = listOf(
            "banned", "you are banned", "blacklist", "not whitelisted", "whitelist",
            "차단", "밴", "영구", "화이트리스트", "블랙리스트", "정지된 계정",
            "failed to verify username", "invalid session", "인증하지 못했", "정품 계정",
        )

        fun isAlreadyConnected(reason: CloseReason?): Boolean {
            if (reason !is CloseReason.Kicked) return false
            val t = reason.text.plain.lowercase(Locale.ROOT)
            return ALREADY_CONNECTED.any { t.contains(it) }
        }

        fun isPermanentKick(text: String): Boolean {
            val t = text.lowercase(Locale.ROOT)
            if (ALREADY_CONNECTED.any { t.contains(it) }) return false
            return PERMANENT.any { t.contains(it) }
        }

        fun describe(reason: CloseReason): String = when (reason) {
            is CloseReason.Kicked -> "서버에서 끊음: ${reason.text.plain.ifBlank { "(메시지 없음)" }}"
            is CloseReason.Lost -> reason.message
            is CloseReason.User -> "연결을 끊었습니다"
            is CloseReason.Transfer -> "서버 이동: ${reason.host}:${reason.port}"
            is CloseReason.Fatal -> reason.message
            is CloseReason.AuthFailed -> reason.message
        }
    }
}
