package kr.jamsuham.core

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import kr.jamsuham.core.net.AddressResolver
import kr.jamsuham.core.net.ConnectionEvents
import kr.jamsuham.core.net.McConnection
import kr.jamsuham.core.net.MojangStatus
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
    /** 자동 명령어 사이 간격 (서버가 명령어를 너무 빨리 보내면 무시하는 경우가 많아서 서버마다 조절) */
    val joinCommandIntervalSec: Int = 3,
    /** 메뉴 자동 클릭 규칙 */
    val autoClickRules: List<AutoClickRule> = emptyList(),
    /** 들어가면 한두 걸음 걸었다가 돌아오기 (움직이지 않으면 채팅·명령어를 막는 서버용) */
    val walkOnJoin: Boolean = true,
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
/** 접속 걷기: 한쪽으로 걷는 시간 (초당 4.3칸이라 약 1.5칸) */
private const val WALK_STEP_MS = 350L

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

    // 아래 상태는 모두 exec 스레드에서만 바꾼다 (userStopped 만 예외: stop() 이 바로 표시해서 진행 중인 준비 단계를 멈춘다)
    @Volatile private var userStopped = false
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

    /** 서버가 꺼져 있어 핑만 하며 기다린 횟수 (0 = 켜져 있음) */
    private var offlineChecks = 0
    /** 이 서버가 정품 인증을 요구하는지 (한 번 접속해 보면 알게 됨) */
    private var serverRequiresAuth = false
    /** 모장 인증 서버 장애를 감지함 → 복구 확인 전까지 접속 안 함 */
    private var mojangDown = false
    private var mojangChecks = 0
    /** 모장 429(요청 제한)를 연달아 받은 횟수 */
    private var rateLimitHits = 0
    /** "Failed to verify username" 같은 인증 킥을 연달아 받은 횟수 */
    private var authKicks = 0
    @Volatile private var generation = 0
    /** 끝난 접속들이 주고받은 바이트 합계 (지금 접속분은 [traffic] 에서 더한다) */
    @Volatile private var rxDone = 0L
    @Volatile private var txDone = 0L
    @Volatile private var onlineSinceShared: Long? = null
    @Volatile private var totalOnlineShared = 0L
    @Volatile private var status = SessionStatus(SessionState.IDLE)

    val currentStatus: SessionStatus get() = status

    /** 이 잠수를 시작한 뒤의 데이터 사용량 (아무 스레드에서나) */
    val traffic: Traffic
        get() {
            val c = connection
            val since = onlineSinceShared
            return Traffic(
                received = rxDone + (c?.bytesReceived ?: 0L),
                sent = txDone + (c?.bytesSent ?: 0L),
                onlineMs = totalOnlineShared + (since?.let { System.currentTimeMillis() - it } ?: 0L),
            )
        }

    fun start() = submit {
        userStopped = false
        failures = 0
        offlineChecks = 0
        mojangChecks = 0
        rateLimitHits = 0
        authKicks = 0
        attempt()
    }

    /** 사용자가 직접 끊기. 재연결하지 않는다. */
    fun stop() {
        // 세션 스레드가 핑·DNS·토큰 갱신을 기다리는 중이어도 다음 단계로 넘어가지 않도록 바로 표시한다
        userStopped = true
        // 아직 접속 전(준비·대기 중)이면 화면에 바로 "끊음" 을 보여 준다. 세션 스레드는 막힌 작업이 끝난 뒤 정리한다
        if (connection == null && status.state != SessionState.STOPPED) {
            status = SessionStatus(SessionState.STOPPED, "연결을 끊었습니다", status.serverVersion,
                reconnectCount = status.reconnectCount, totalOnlineMs = status.totalOnlineMs)
            listener.onStatus(status)
        }
        submit { stopNow() }
    }

    private fun stopNow() {
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

    /** 조이스틱 방향 (동쪽·남쪽 성분 -1~1, 0·0 이면 멈춤) */
    fun setStick(east: Float, south: Float) {
        connection?.setStick(east, south)
    }

    /** 열린 메뉴의 칸 클릭 (왼쪽 클릭) */
    fun clickMenu(slot: Int): Boolean = connection?.clickMenuSlot(slot) ?: false

    /** 인벤토리 아이템 우클릭 사용 */
    fun useItem(slot: Int): Boolean = if (status.state == SessionState.ONLINE) connection?.useItem(slot) ?: false else false

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
                // 서버가 정해 준 주소·포트를 그대로 쓴다 ("주소:포트" 글자로 합쳤다 다시 나누면 IPv6 주소에서 포트가 사라진다)
                ResolvedTarget(transfer.first, transfer.second, transfer.first, transfer.second)
            } else {
                setStatus(SessionState.PREPARING, "서버 주소 확인 중…")
                AddressResolver.resolve(config.address, dns)
            }
            if (userStopped) return

            val version = resolveVersion(target) ?: return
            if (userStopped) return
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
            if (userStopped) return

            // 정품 인증이 필요한 서버인데 모장 인증 서버가 죽어 있으면 접속해 봐야 실패하므로 기다린다
            if (creds.online && (mojangDown || serverRequiresAuth)) {
                setStatus(SessionState.PREPARING, "모장 인증 서버 확인 중…", version.name)
                val mojang = MojangStatus.check()
                if (userStopped) return
                if (mojang == MojangStatus.Result.NO_NETWORK) {
                    // 폰 인터넷 문제를 모장 장애로 오인해 몇 분씩 기다리지 않도록 일반 튕김처럼 짧게 재시도
                    handleClosed(CloseReason.Lost("인터넷에 연결되지 않은 것 같습니다 (모장 인증 서버에 닿지 않음)"))
                    return
                }
                if (mojang == MojangStatus.Result.DOWN) {
                    mojangDown = true
                    mojangChecks++
                    if (mojangChecks == 1) line(LogLine.Kind.SYSTEM, "모장 인증 서버에 문제가 있어 보여 접속하지 않고 복구될 때까지 기다립니다")
                    val wait = longArrayOf(30, 60, 120, 180, 300)[(mojangChecks - 1).coerceAtMost(4)]
                    scheduleCheck(wait, "모장 인증 서버 장애 · ${wait}초 뒤 다시 확인")
                    return
                }
                if (mojangDown) line(LogLine.Kind.SYSTEM, "모장 인증 서버가 정상입니다. 접속합니다")
                mojangDown = false
                mojangChecks = 0
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

    /**
     * 서버가 켜져 있는지 핑으로 먼저 확인하고 버전을 정한다.
     * 꺼져 있으면(연결 거부·시간 초과) 접속 시도 없이 기다렸다가 다시 핑만 한다. 결정할 수 없으면 null.
     */
    private fun resolveVersion(target: ResolvedTarget): ProtocolVersion? {
        val fixed = config.fixedVersion?.let { name ->
            ViaSetup.versionByName(name) ?: run {
                stopWithError("지원하지 않는 버전입니다: $name")
                return null
            }
        }
        setStatus(SessionState.PREPARING, if (offlineChecks > 0) "서버가 켜졌는지 확인 중…" else "서버 확인 중…")
        val ping = try {
            StatusPinger.ping(target, fixed?.version ?: ViaSetup.NATIVE_PROTOCOL)
        } catch (e: Exception) {
            if (userStopped) return null
            if (isUnreachable(e)) {
                offlineChecks++
                if (offlineChecks == 1) {
                    line(LogLine.Kind.SYSTEM, "서버가 꺼져 있거나 응답이 없습니다 (${McConnection.describe(e)}). 켜질 때까지 접속하지 않고 확인만 합니다")
                }
                val max = config.maxBackoffSec.coerceAtLeast(10).toLong()
                val wait = longArrayOf(10, 15, 30, 60)[(offlineChecks - 1).coerceAtMost(3)].coerceAtMost(max)
                scheduleCheck(wait, "서버 꺼짐 · ${wait}초 뒤 다시 확인")
                return null
            }
            // 연결은 되는데 목록 응답만 이상한 서버: 아는 버전이 있으면 그걸로 접속
            val known = fixed ?: detectedVersion
            if (known != null) {
                log.w("핑 응답 이상, ${known.name} 로 시도: ${e.message}")
                return known
            }
            handleClosed(CloseReason.Lost("서버 버전을 확인하지 못했습니다: ${McConnection.describe(e)}"))
            return null
        }
        if (offlineChecks > 0) {
            line(LogLine.Kind.SYSTEM, "서버가 켜졌습니다. 접속합니다")
            offlineChecks = 0
        }
        if (fixed != null) return fixed
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

    private fun isUnreachable(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.net.ConnectException || t is java.net.SocketTimeoutException ||
                t is java.net.NoRouteToHostException || t is java.net.UnknownHostException ||
                t is java.io.EOFException
            ) return true
            t = t.cause
        }
        return false
    }

    private inner class Events(private val gen: Int) : ConnectionEvents {
        override fun onJoined() = submit {
            if (generation != gen) return@submit
            onlineSince = System.currentTimeMillis()
            onlineSinceShared = onlineSince
            authRetried = false
            rateLimitHits = 0
            if (connection?.usedSessionServer == true) serverRequiresAuth = true
            line(LogLine.Kind.SYSTEM, "서버에 들어갔습니다")
            setStatus(SessionState.ONLINE, "접속 중", detectedVersion?.name ?: config.fixedVersion)
            // 1분 이상 버티면 실패 횟수 초기화 (들어가자마자 킥하는 서버에서 횟수가 매번 0 으로 돌아가지 않도록 접속 순간이 아니라 1분 뒤에)
            exec.schedule({
                if (status.state == SessionState.ONLINE && generation == gen) {
                    failures = 0
                    authKicks = 0
                }
            }, 60, TimeUnit.SECONDS)
            if (config.walkOnJoin) scheduleJoinWalk(gen)
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

        override fun onVitals(vitals: Vitals) {
            if (generation == gen) listener.onVitals(vitals)
        }

        override fun onDeath(death: DeathInfo) {
            if (generation == gen) listener.onDeath(death)
        }

        override fun onResourcePack(url: String, hash: String) {
            if (generation == gen) listener.onResourcePack(url, hash)
        }

        // 이미 처리한(세대가 지난) 연결의 종료 알림은 무시한다
        override fun onClosed(reason: CloseReason) = submit { if (generation == gen) handleClosed(reason) }
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
            // 다음 명령어까지 서버별 간격만큼 기다린다 (최소 0.5초)
            val intervalMs = (config.joinCommandIntervalSec.coerceIn(0, 120) * 1000L).coerceAtLeast(500L)
            exec.schedule({ step(i + 1) }, intervalMs, TimeUnit.MILLISECONDS)
        }
        exec.schedule({ step(0) }, (firstAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    /**
     * 들어가서 한 번도 움직이지 않으면 채팅·명령어를 막거나 화면을 안 띄우는 서버가 있어서, 접속할 때마다
     * 북쪽으로 약 1.5칸 걸었다가 남쪽으로 되돌아온다. 발밑 청크가 아직 없으면(걸어도 서버에 안 보냄) 준비될 때까지,
     * 서버 메뉴(리소스팩 창 등)가 열려 있으면 닫힐 때까지 기다린다. 사용자가 직접 움직이는 중이면 하지 않는다.
     */
    private fun scheduleJoinWalk(gen: Int) {
        val start = System.currentTimeMillis()
        fun alive() = generation == gen && status.state == SessionState.ONLINE && !userStopped

        fun step() {
            if (!alive()) return
            val conn = connection ?: return
            val waited = System.currentTimeMillis() - start
            if ((conn.hasOpenMenu || !conn.canWalk) && waited < MENU_WAIT_MAX_MS) {
                exec.schedule({ step() }, 500, TimeUnit.MILLISECONDS)
                return
            }
            if (!conn.canWalk || conn.movementInput != 0) return
            conn.movementInput = 1 // 북
            exec.schedule({
                if (alive() && connection === conn && conn.movementInput == 1) conn.movementInput = 2 // 남 (되돌아오기)
                exec.schedule({
                    if (connection === conn && conn.movementInput == 2) conn.movementInput = 0
                    if (alive()) line(LogLine.Kind.SYSTEM, "움직여야 하는 서버를 위해 한두 걸음 걸었다가 돌아왔습니다")
                }, WALK_STEP_MS, TimeUnit.MILLISECONDS)
            }, WALK_STEP_MS, TimeUnit.MILLISECONDS)
        }
        exec.schedule({ step() }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun handleClosed(reason: CloseReason) {
        // 세대를 먼저 올려서, 끊기기 직전에 예약된 접속자·메뉴·인벤토리 갱신이 비운 화면을 다시 채우지 못하게 한다
        generation++
        if (connection?.usedSessionServer == true) serverRequiresAuth = true
        connection?.let { c ->
            rxDone += c.bytesReceived
            txDone += c.bytesSent
        }
        connection = null
        listener.onPlayers(PlayerList.EMPTY)
        listener.onMenu(null)
        listener.onInventory(Inventory.EMPTY)
        listener.onVitals(null)
        onlineSince?.let { totalOnlineMs += System.currentTimeMillis() - it }
        onlineSince = null
        totalOnlineShared = totalOnlineMs
        onlineSinceShared = null
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
            is CloseReason.ServiceDown -> {
                serverRequiresAuth = true
                mojangDown = true
                mojangChecks = 0
                schedule(30)
                return
            }
            is CloseReason.RateLimited -> {
                // 30초마다 두드리면 제한이 계속 연장되므로 60 → 120 → 300 → 600초로 늘린다. 모장 상태 확인으로 줄이지 않는다
                rateLimitHits++
                reconnectCount++
                val wait = RATE_LIMIT_WAIT[(rateLimitHits - 1).coerceAtMost(RATE_LIMIT_WAIT.size - 1)]
                schedule(wait, "모장 요청 제한 · ${wait}초 뒤 다시 접속합니다")
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
                if (isDuplicateLogin(plain)) {
                    // PC 등에서 같은 계정으로 들어간 것: 다시 들어가면 그쪽을 튕겨 내므로 멈춘다
                    stopWithError("다른 곳(PC 등)에서 같은 계정으로 접속해서 잠수를 멈췄습니다. 다시 잠수하려면 시작을 눌러 주세요")
                    return
                }
                if (isAuthKick(plain)) {
                    // 대부분 모장 인증 서버의 일시 장애나 토큰 만료 → 토큰을 새로 받고 모장 상태를 확인한 뒤 다시 시도
                    authKicks++
                    if (authKicks >= AUTH_KICK_LIMIT) {
                        stopWithError("정품 인증 실패가 ${authKicks}번 이어져서 멈춥니다: $plain")
                        return
                    }
                    authRetried = true
                    serverRequiresAuth = true
                    reconnectCount++
                    val wait = if (authKicks == 1) 10L else 60L
                    line(LogLine.Kind.SYSTEM, "정품 인증에 실패했습니다. 토큰을 새로 받아 ${wait}초 뒤 다시 시도합니다 (${authKicks}/${AUTH_KICK_LIMIT - 1})")
                    schedule(wait)
                    return
                }
                if (isPermanentKick(plain)) {
                    stopWithError("다시 접속하지 않습니다 (차단·화이트리스트로 보이는 킥): $plain")
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

    private fun schedule(delaySec: Long, message: String = "${delaySec}초 뒤 다시 접속합니다") {
        pending?.cancel(false)
        pending = null
        if (userStopped) return
        val at = System.currentTimeMillis() + delaySec * 1000
        status = status.copy(
            state = SessionState.WAITING_RECONNECT,
            message = message,
            nextAttemptAtMs = at,
            onlineSinceMs = null,
            reconnectCount = reconnectCount,
            totalOnlineMs = totalOnlineMs,
        )
        listener.onStatus(status)
        pending = exec.schedule({ attempt() }, delaySec, TimeUnit.SECONDS)
    }

    /** 접속은 하지 않고 확인만 다시 하는 대기 (서버 꺼짐·모장 장애) */
    private fun scheduleCheck(delaySec: Long, message: String) {
        schedule(delaySec, message)
    }

    private fun backoffSeconds(reason: CloseReason, n: Int): Long = computeBackoff(reason, n, config.maxBackoffSec)

    private fun stopWithError(message: String) {
        pending?.cancel(false)
        pending = null
        line(LogLine.Kind.ERROR, message)
        setStatus(SessionState.STOPPED, message)
    }

    private fun setStatus(state: SessionState, message: String, version: String? = status.serverVersion) {
        // 사용자가 끊은 뒤에는 늦게 끝난 준비 단계가 "접속 중…" 같은 상태로 덮어쓰지 못하게 한다
        if (userStopped && state != SessionState.STOPPED) return
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
        /** 연속 인증 킥이 이 횟수가 되면 멈춘다 */
        private const val AUTH_KICK_LIMIT = 3
        private val RATE_LIMIT_WAIT = longArrayOf(60, 120, 300, 600)

        /**
         * 내 새 접속이 거절됨: 프록시(번지코드·벨로시티)가 아직 옛 연결을 붙잡고 있음 → 기다렸다 다시 접속.
         * (킥 설정과 상관없이 재접속한다)
         */
        private val ALREADY_CONNECTED = listOf(
            "already connected", "already online", "already logged", "already playing",
            "이미 접속", "이미 연결", "이미 서버", "이미 온라인", "중복 접속",
        )

        /**
         * 내 연결이 밀려남: 다른 곳(PC 등)에서 같은 계정으로 새로 들어옴 → 다시 들어가면 그쪽을 튕기므로 멈춘다.
         * 바닐라·페이퍼는 번역 키 multiplayer.disconnect.duplicate_login 으로 보낸다 (ChatText 가 한국어로 번역).
         */
        private val DUPLICATE_LOGIN = listOf(
            "logged in from another", "duplicate_login", "다른 곳에서 같은 계정으로 접속", "다른 곳에서 접속", "다른 위치에서 접속", "중복 로그인",
        )

        /** 정품 인증 실패 킥: 대부분 일시적(모장 장애·토큰 만료)이라 몇 번은 다시 시도한다 */
        private val AUTH_KICK = listOf(
            "failed to verify username", "invalid session", "인증하지 못했",
        )

        private val PERMANENT = listOf(
            "banned", "you are banned", "blacklist", "not whitelisted", "whitelist",
            "차단", "밴", "영구", "화이트리스트", "블랙리스트", "정지된 계정", "정품 계정",
        )

        private fun String.hasAny(words: List<String>): Boolean {
            val t = lowercase(Locale.ROOT)
            return words.any { t.contains(it) }
        }

        fun isAlreadyConnected(reason: CloseReason?): Boolean {
            if (reason !is CloseReason.Kicked) return false
            val plain = reason.text.plain
            return plain.hasAny(ALREADY_CONNECTED) && !plain.hasAny(DUPLICATE_LOGIN)
        }

        fun isDuplicateLogin(text: String): Boolean = text.hasAny(DUPLICATE_LOGIN)

        fun isAuthKick(text: String): Boolean = text.hasAny(AUTH_KICK)

        fun isPermanentKick(text: String): Boolean {
            if (text.hasAny(ALREADY_CONNECTED) || text.hasAny(DUPLICATE_LOGIN) || text.hasAny(AUTH_KICK)) return false
            return text.hasAny(PERMANENT)
        }

        /**
         * 재접속 대기 시간. 단계표를 다 쓰면 [maxBackoffSec] 까지 두 배씩 늘린다.
         * 들어가자마자 계속 킥당하는 경우(가득 참·봇 차단 등) 몇 시간 동안 수백 번 로그인해 모장 429 를 부르지 않도록,
         * 킥이 8번 넘게 이어지면 설정과 상관없이 최소 3분은 기다린다.
         */
        fun computeBackoff(reason: CloseReason, n: Int, maxBackoffSec: Int): Long {
            val max = maxBackoffSec.coerceAtLeast(10).toLong()
            val steps: LongArray = when {
                isAlreadyConnected(reason) -> longArrayOf(15, 25, 35, 45, 60)
                reason is CloseReason.Kicked -> longArrayOf(10, 20, 30, 60)
                else -> longArrayOf(3, 5, 10, 20, 30, 60)
            }
            val i = (n - 1).coerceAtLeast(0)
            var wait = if (i < steps.size) {
                steps[i]
            } else {
                val doublings = (i - steps.size + 1).coerceAtMost(10)
                steps.last() shl doublings
            }
            wait = wait.coerceAtMost(max)
            if (reason is CloseReason.Kicked && !isAlreadyConnected(reason) && n > 8) wait = wait.coerceAtLeast(180)
            return wait
        }

        fun describe(reason: CloseReason): String = when (reason) {
            is CloseReason.Kicked -> "서버에서 끊음: ${reason.text.plain.ifBlank { "(메시지 없음)" }}"
            is CloseReason.Lost -> reason.message
            is CloseReason.User -> "연결을 끊었습니다"
            is CloseReason.Transfer -> "서버 이동: ${reason.host}:${reason.port}"
            is CloseReason.Fatal -> reason.message
            is CloseReason.AuthFailed -> reason.message
            is CloseReason.ServiceDown -> "${reason.message} → 모장 인증 서버가 복구될 때까지 기다립니다"
            is CloseReason.RateLimited -> reason.message
        }
    }
}
