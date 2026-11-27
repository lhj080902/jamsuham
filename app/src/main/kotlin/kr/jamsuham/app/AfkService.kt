package kr.jamsuham.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kr.jamsuham.core.CredentialsProvider
import kr.jamsuham.core.JoinCredentials
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.SessionConfig
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.demo.DemoServer

/**
 * 화면이 꺼져도 접속을 유지하는 포그라운드 서비스. 잠수 중인 서버가 하나라도 있으면 살아 있다.
 *  - 상단 알림을 띄워 시스템이 앱을 정리하지 않게 한다
 *  - CPU/와이파이 잠금으로 화면 꺼짐 상태에서도 네트워크를 살려 둔다
 *  - 네트워크가 다시 잡히면 세션들에 알려 바로 재접속하게 한다
 */
class AfkService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    /** 화면이 꺼져 있을 때도 효과가 있는 잠금 (잠수의 핵심 상황) */
    private var wifiLock: WifiManager.WifiLock? = null
    /** 화면이 켜져 있고 앱이 앞에 있을 때만 효과가 있는 저지연 잠금 (안드로이드 10+) */
    private var wifiLowLatencyLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var inForeground = false
    private lateinit var store: Store
    private lateinit var accounts: AccountStore

    private val observer = object : SessionHub.Observer {
        override fun onStatus(serverId: String, status: SessionStatus) {
            store.setActive(SessionHub.activeIds())
            // 이 폰에서 잠수 중인 서버가 바뀌었으면 클라우드에 알린다 (연결한 계정이 있을 때만. 웹에 "앱에서 실행 중"으로 보임)
            CloudHub.reportLocal(this@AfkService)
            // 아직 포그라운드로 올라가기 전(시작 명령을 처리하기 전)에 온 이벤트로는 끄지 않는다
            // (클라우드 잠수의 상태 이벤트가 그 사이에 올 수 있다. 그때 끄면 시스템이 앱을 강제 종료한다)
            if (!SessionHub.anyActive) {
                if (inForeground) shutdown()
            } else {
                updateNotification()
            }
        }

        override fun onLine(serverId: String, line: LogLine) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            SessionHub.refreshRates()
            updateNotification()
            CloudHub.reportLocal(this@AfkService)
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        accounts = AccountStore(this)
        createChannel()
        SessionHub.addObserver(observer)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverId = intent?.getStringExtra(EXTRA_SERVER)
        when (intent?.action) {
            ACTION_STOP -> {
                if (serverId != null) SessionHub.stop(serverId)
                if (!SessionHub.anyActive) shutdown()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALL -> {
                SessionHub.activeIds().forEach { SessionHub.stop(it) }
                if (!SessionHub.anyActive) shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // startForegroundService 로 시작됐으므로 어떤 경우든 먼저 포그라운드로 올려야 앱이 죽지 않는다
                if (!goForeground()) return START_NOT_STICKY
                if (serverId == null) {
                    if (!SessionHub.anyActive) shutdown()
                    return START_NOT_STICKY
                }
                if (!begin(serverId) && !SessionHub.anyActive) {
                    shutdown()
                    return START_NOT_STICKY
                }
            }
            else -> {
                // ACTION_RESUME: 폰이 다시 켜졌거나 앱이 업데이트됨 (BootReceiver) / intent == null: 시스템이 앱을 정리했다가 다시 살림
                val fromBoot = intent?.action == ACTION_RESUME
                val resume = if (fromBoot) {
                    intent?.getStringArrayExtra(EXTRA_SERVERS)?.toSet() ?: emptySet()
                } else {
                    store.activeToResume()
                }
                if (resume.isEmpty() || SessionHub.anyActive) {
                    // startForegroundService 로 시작된 경우(ACTION_RESUME)는 일단 포그라운드로 올렸다가 정리해야 앱이 강제 종료되지 않는다
                    if (fromBoot && !inForeground) goForeground()
                    if (!SessionHub.anyActive) shutdown()
                    return START_NOT_STICKY
                }
                if (!goForeground()) return START_NOT_STICKY
                for (id in resume.take(Plan.maxConcurrent(this))) {
                    // 접속을 시작하면 채팅 기록을 비우므로 안내는 시작한 뒤에 남긴다
                    if (begin(id)) SessionHub.note(id, intent?.getStringExtra(EXTRA_REASON) ?: tr("앱이 시스템에 의해 종료됐다가 다시 실행되어 이어서 접속합니다", "The app was stopped by the system and restarted, so reconnecting"))
                }
                // 그 사이 지워진 서버뿐이라 아무것도 시작하지 못했으면 "준비 중" 알림만 남기지 말고 정리한다
                if (!SessionHub.anyActive) {
                    shutdown()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    /**
     * 잠수 하나를 시작한다. 시작하지 못했으면(지워진 서버·계정 없음) false.
     * 서비스 정리는 부르는 쪽에서 한 번만 한다 (여러 개를 이어서 시작하는 도중에 서비스를 닫지 않도록)
     */
    private fun begin(serverId: String): Boolean {
        val server = store.server(serverId) ?: return false
        // 클라우드에서 잠수하는 서버는 이 폰에서 접속하지 않는다 (예전 "이어서 잠수" 기록에 남아 있던 경우 등)
        if (server.cloud) return false
        val credentials: CredentialsProvider
        val config: SessionConfig
        if (server.demo) {
            // 체험 서버: 계정 없이 지어낸 이름으로 앱 안 연습용 서버에 붙는다. 비정품 이름은 여기서만 만든다 (실제 서버에는 쓰지 않는다)
            credentials = CredentialsProvider { JoinCredentials.offline(DemoServer.NAME) }
            config = try {
                server.sessionConfig()
            } catch (t: Throwable) {
                SessionHub.note(serverId, tr("체험 서버를 켜지 못했습니다: ${t.message}", "Couldn't start the demo server: ${t.message}"), LogLine.Kind.ERROR)
                return false
            }
        } else {
            val accountId = server.accountId
            if (accountId == null || accounts.get(accountId) == null) {
                SessionHub.note(serverId, tr("이 서버에 사용할 계정이 없습니다. 서버 설정에서 계정을 골라 주세요", "No account set for this server. Please choose one in the server settings"), LogLine.Kind.ERROR)
                return false
            }
            credentials = accounts.provider(accountId)
            config = server.sessionConfig()
        }
        acquireLocks()
        registerNetwork()
        SessionHub.start(this, serverId, config, credentials)
        store.setActive(SessionHub.activeIds())
        main.removeCallbacks(ticker)
        main.post(ticker)
        return true
    }

    private fun goForeground(): Boolean {
        if (inForeground) return true
        return try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            inForeground = true
            true
        } catch (t: Throwable) {
            SessionHub.log.e("포그라운드 서비스 시작 실패", t)
            store.setActive(emptySet())
            stopSelf()
            false
        }
    }

    private fun shutdown() {
        main.removeCallbacks(ticker)
        store.setActive(emptySet())
        releaseLocks()
        unregisterNetwork()
        if (inForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        SessionHub.removeObserver(observer)
        main.removeCallbacks(ticker)
        releaseLocks()
        unregisterNetwork()
        // 서비스가 사라지면 접속도 같이 정리한다 (세션만 남아 떠도는 일을 막음)
        if (SessionHub.anyActive) SessionHub.releaseAll()
        super.onDestroy()
    }

    // ------------------------------------------------------------ locks / network

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jamsuham:afk").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        // LOW_LATENCY 잠금은 화면이 켜져 있고 앱이 앞에 있을 때만 동작한다. 두 잠금을 같이 잡으면
        // 그 밖의 경우(화면 꺼짐 잠수)에는 HIGH_PERF 잠금이 적용된다 (WifiManager 공식 문서)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Jamsuham:afk").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && wifiLowLatencyLock?.isHeld != true) {
            wifiLowLatencyLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Jamsuham:afk-ll").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        try {
            wifiLowLatencyLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
        wifiLock = null
        wifiLowLatencyLock = null
    }

    private fun registerNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    SessionHub.onNetworkAvailable()
                    CloudHub.onNetworkAvailable()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (t: Throwable) {
            SessionHub.log.w("네트워크 감시 등록 실패", t)
        }
    }

    private fun unregisterNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, tr("잠수 접속 유지", "AFK connection"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("잠수 중인 동안 표시되는 알림", "Shown while AFK")
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun updateNotification() {
        if (!inForeground) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val active = SessionHub.activeIds().mapNotNull { id -> store.server(id)?.let { it to SessionHub.status(id) } }
        val now = System.currentTimeMillis()
        val online = active.count { it.second.state == SessionState.ONLINE }
        val title = when {
            active.isEmpty() -> tr("잠수크래프트", "Jamsucraft")
            active.size == 1 -> "${active[0].first.displayName} · ${stateText(active[0].second)}"
            else -> tr("잠수 중 ${online}/${active.size}개 서버", "AFK on ${online}/${active.size} servers")
        }
        val lines = active.flatMap { (server, st) ->
            val time = if (st.state == SessionState.ONLINE && st.onlineSinceMs != null) " · " + Formats.minutes(now - st.onlineSinceMs!!) else ""
            val rc = if (st.reconnectCount > 0) tr(" · 재접속 ${st.reconnectCount}회", " · ${st.reconnectCount} reconnects") else ""
            // 아랫줄: 접속해 있던 시간 기준 시간당 데이터 사용량 (1분 미만이면 측정 중)
            val perHour = SessionHub.ratePerHour(server.id)
            val data = tr("데이터 사용량 ", "Data usage ") + (perHour?.let { StatusPanel.bytes(it) + "/h" } ?: tr("측정 중", "measuring"))
            if (AppSettings.notifyDataUsage) listOf("${server.displayName}: ${stateText(st)}$time$rc", data)
            else listOf("${server.displayName}: ${stateText(st)}$time$rc")
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 서버가 하나면 그 서버만, 여러 개면 전부 끊기
        val single = active.singleOrNull()?.first
        val stopIntent = if (single != null) {
            Intent(this, AfkService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SERVER, single.id)
        } else {
            Intent(this, AfkService::class.java).setAction(ACTION_STOP_ALL)
        }
        val stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openChat = if (single != null) {
            // 뒤로 가기를 누르면 서버 목록으로 돌아가게 목록 → 채팅 순서로 연다
            PendingIntent.getActivities(
                this, 2,
                arrayOf(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, single.id),
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else open
        val icon = resources.getIdentifier("ic_stat_afk", "drawable", packageName).takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_sync
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: tr("준비 중…", "Preparing…"))
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n").ifEmpty { tr("준비 중…", "Preparing…") }))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, tr("앱 열기", "Open app"), openChat).build())
            .addAction(Notification.Action.Builder(null, if (single != null) tr("연결 끊기", "Disconnect") else tr("모두 끊기", "Disconnect all"), stop).build())
        return builder.build()
    }

    private fun stateText(s: SessionStatus) = when (s.state) {
        SessionState.ONLINE -> tr("잠수 중", "AFK")
        SessionState.WAITING_RECONNECT -> tr("재접속 대기", "Waiting to reconnect")
        SessionState.CONNECTING, SessionState.PREPARING -> tr("접속 중", "Connecting")
        else -> tr("꺼짐", "Off")
    }

    companion object {
        const val ACTION_START = "kr.jamsuham.app.START"
        const val ACTION_STOP = "kr.jamsuham.app.STOP"
        const val ACTION_STOP_ALL = "kr.jamsuham.app.STOP_ALL"
        const val ACTION_RESUME = "kr.jamsuham.app.RESUME"
        const val EXTRA_SERVER = "server_id"
        const val EXTRA_SERVERS = "server_ids"
        const val EXTRA_REASON = "reason"
        private const val CHANNEL_ID = "afk"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context, serverId: String) {
            context.startForegroundService(Intent(context, AfkService::class.java).setAction(ACTION_START).putExtra(EXTRA_SERVER, serverId))
        }

        /** 폰이 다시 켜졌거나 앱이 업데이트된 뒤 [serverIds] 잠수를 이어서 시작 ([reason] 은 채팅에 남길 안내) */
        fun resume(context: Context, serverIds: Set<String>, reason: String) {
            context.startForegroundService(
                Intent(context, AfkService::class.java).setAction(ACTION_RESUME)
                    .putExtra(EXTRA_SERVERS, serverIds.toTypedArray())
                    .putExtra(EXTRA_REASON, reason),
            )
        }

        fun stop(context: Context, serverId: String) {
            // 클라우드 잠수는 이 서비스가 아니라 클라우드에 끊어 달라고 한다
            if (SessionHub.isRemote(serverId)) {
                CloudHub.init(context)
                SessionHub.stop(serverId)
                return
            }
            context.startService(Intent(context, AfkService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SERVER, serverId))
        }
    }
}
