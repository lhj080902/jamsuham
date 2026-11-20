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
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus

/**
 * 화면이 꺼져도 접속을 유지하는 포그라운드 서비스.
 *  - 상단 알림을 띄워 시스템이 앱을 정리하지 않게 한다
 *  - CPU/와이파이 잠금으로 화면 꺼짐 상태에서도 네트워크를 살려 둔다
 *  - 네트워크가 다시 잡히면 세션에 알려 바로 재접속하게 한다
 */
class AfkService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var inForeground = false
    private lateinit var prefs: Prefs

    private val observer = object : SessionHub.Observer {
        override fun onStatus(status: SessionStatus) {
            if (status.state == SessionState.STOPPED) {
                shutdown()
            } else {
                updateNotification()
            }
        }

        override fun onLine(line: LogLine) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            main.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
        SessionHub.addObserver(observer)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.markActive(false)
                SessionHub.stopByUser()
                if (!SessionHub.isActive) shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!goForeground()) return START_NOT_STICKY
                beginSession()
            }
            else -> {
                // 시스템이 앱을 정리했다가 서비스를 다시 살린 경우 (intent == null)
                if (!prefs.shouldResumeAfterKill() || SessionHub.isActive) {
                    if (!SessionHub.isActive) stopSelf()
                    return START_NOT_STICKY
                }
                if (!goForeground()) return START_NOT_STICKY
                SessionHub.note("앱이 시스템에 의해 종료됐다가 다시 실행되어 이어서 접속합니다")
                beginSession()
            }
        }
        return START_STICKY
    }

    private fun beginSession() {
        val accounts = AccountStore(this)
        val creds = accounts.credentialsProvider(prefs.useMicrosoft, prefs.offlineName)
        prefs.markActive(true)
        acquireLocks()
        registerNetwork()
        SessionHub.start(this, prefs.sessionConfig(), creds)
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun goForeground(): Boolean {
        return try {
            val n = buildNotification(SessionHub.status)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            inForeground = true
            true
        } catch (t: Throwable) {
            SessionHub.log.e("포그라운드 서비스 시작 실패", t)
            SessionHub.note("백그라운드 유지 서비스를 시작하지 못했습니다: ${t.message}")
            prefs.markActive(false)
            stopSelf()
            false
        }
    }

    private fun shutdown() {
        main.removeCallbacks(ticker)
        prefs.markActive(false)
        releaseLocks()
        unregisterNetwork()
        if (inForeground) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            inForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        SessionHub.removeObserver(observer)
        main.removeCallbacks(ticker)
        releaseLocks()
        unregisterNetwork()
        if (SessionHub.isActive) {
            // 서비스가 사라지면 접속도 같이 정리한다 (세션만 남아 떠도는 일을 막음)
            SessionHub.release()
        }
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
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "Jamsuham:afk").apply {
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
        wakeLock = null
        wifiLock = null
    }

    private fun registerNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { SessionHub.onNetworkAvailable() }
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
        val ch = NotificationChannel(CHANNEL_ID, "잠수 접속 유지", NotificationManager.IMPORTANCE_LOW).apply {
            description = "잠수 중인 동안 표시되는 알림"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun updateNotification() {
        if (!inForeground) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(SessionHub.status))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(s: SessionStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AfkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (s.state) {
            SessionState.ONLINE -> "잠수 중"
            SessionState.WAITING_RECONNECT -> "재접속 대기"
            SessionState.CONNECTING, SessionState.PREPARING -> "접속 중…"
            else -> "잠수함"
        }
        val text = buildString {
            append(prefs.address.ifBlank { "서버" })
            if (s.state == SessionState.ONLINE && s.onlineSinceMs != null) {
                append(" · ").append(Formats.duration(System.currentTimeMillis() - s.onlineSinceMs!!))
            } else if (s.message.isNotBlank()) {
                append(" · ").append(s.message)
            }
            if (s.reconnectCount > 0) append(" · 재접속 ").append(s.reconnectCount).append("회")
        }
        val icon = resources.getIdentifier("ic_stat_afk", "drawable", packageName).takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_sync
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "연결 끊기", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "kr.jamsuham.app.START"
        const val ACTION_STOP = "kr.jamsuham.app.STOP"
        private const val CHANNEL_ID = "afk"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            val i = Intent(context, AfkService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AfkService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
