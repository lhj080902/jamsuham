package kr.jamsuham.app

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.Locale

class JamsuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Netty 가 안드로이드에서 쓸데없이 네이티브 라이브러리를 찾지 않게 한다
        System.setProperty("io.netty.transport.noNative", "true")
        ChatLog.init(this)
        AppSettings.init(this)
        // 화면이 하나라도 보이는지 (메뉴 알림을 띄울지 판단용)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0) {
                    // 앱을 다시 열었다: 내려 둔 리소스팩 그림 글자를 다시 읽는다
                    main.removeCallbacks(release)
                    SessionHub.restoreGlyphs()
                }
            }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // 앱이 뒤로 갔다: 잠깐 다른 앱을 보는 경우를 위해 30초 뒤에 그림 글자를 메모리에서 내린다
                if (started == 0) main.postDelayed(release, 30_000)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val release = Runnable { if (!inForeground) SessionHub.releaseGlyphs() }

    companion object {
        private var started = 0
        val inForeground: Boolean get() = started > 0
    }
}

/** 서버 메뉴가 열렸다는 알림. 누르면 그 서버 채팅 화면에서 메뉴 창이 바로 열린다 */
object MenuNotifier {
    private const val CHANNEL = "menu"

    private fun id(serverId: String) = 1000 + (serverId.hashCode() and 0xffff)

    fun show(context: Context, serverId: String, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("서버 메뉴 알림", "Server menu alerts"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = tr("잠수 중 서버가 메뉴 창을 열었을 때 (자동 클릭 규칙에 없는 메뉴)", "When a server opens a menu while AFK (menus not covered by auto-click rules)")
        })
        val name = Store(context).server(serverId)?.displayName ?: tr("서버", "Server")
        val open = PendingIntent.getActivities(
            context, id(serverId),
            arrayOf(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                Intent(context, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, serverId).putExtra(ChatActivity.EXTRA_OPEN_MENU, true),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = context.resources.getIdentifier("ic_stat_afk", "drawable", context.packageName).takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_more
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(tr("$name · 서버 메뉴가 열렸습니다", "$name · A server menu opened"))
            .setContentText(title.ifBlank { tr("(제목 없음)", "(untitled)") } + tr(" — 눌러서 직접 선택", " — tap to choose"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        try {
            nm.notify(id(serverId), n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, serverId: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id(serverId))
    }
}

/** 앱을 안 보고 있을 때 죽으면 알림. 누르면 그 서버 채팅 화면이 열린다 */
object DeathNotifier {
    private const val CHANNEL = "death"

    private fun id(serverId: String) = 200_000 + (serverId.hashCode() and 0xffff) // 메뉴 알림·업데이트 알림과 겹치지 않게

    fun show(context: Context, serverId: String, death: kr.jamsuham.core.DeathInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("사망 알림", "Death alerts"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = tr("잠수 중 캐릭터가 죽었을 때", "When your character dies while AFK")
        })
        val name = Store(context).server(serverId)?.displayName ?: tr("서버", "Server")
        val open = PendingIntent.getActivities(
            context, id(serverId),
            arrayOf(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                Intent(context, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, serverId),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = context.resources.getIdentifier("ic_stat_afk", "drawable", context.packageName).takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_error
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(tr("$name · 사망했습니다", "$name · You died"))
            .setContentText(death.describe())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id(serverId), n)
        } catch (_: SecurityException) {
        }
    }
}

object Formats {
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, tr("%d시간 %02d분", "%dh %02dm"), h, m)
        else String.format(Locale.ROOT, tr("%d분 %02d초", "%dm %02ds"), m, s)
    }

    /** 분 단위만: "45분", "2시간 13분" */
    fun minutes(ms: Long): String {
        val total = (ms / 60_000).coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return if (h > 0) tr("${h}시간 ${m}분", "${h}h ${m}m") else tr("${m}분", "${m}m")
    }

    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }
}
