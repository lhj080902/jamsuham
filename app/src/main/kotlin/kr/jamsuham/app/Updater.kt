package kr.jamsuham.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 에 올라온 최신 빌드를 확인하고, 버튼 한 번으로 받아서 설치한다.
 * 빌드 번호는 GitHub Actions 실행 번호(versionCode)와 같다.
 */
object Updater {
    const val REPO = "lhj080902/jamsuham"

    /** [version] = 릴리스 제목의 "v1.2.0" 부분 (예전 릴리스는 없음) */
    data class Release(val build: Int, val apkUrl: String, val size: Long, val notes: String, val page: String, val version: String? = null)

    fun currentBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** 화면에 보여 줄 지금 버전 (예: "v1.2.0"). gradle.properties 의 jamsuham.version */
    fun versionLabel(context: Context): String {
        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "v" + (name ?: currentBuild(context).toString())
    }

    /** 새 릴리스의 버전 (릴리스 제목에 버전이 있으면 "v1.2.0", 없으면 "빌드 N") */
    fun versionLabel(release: Release): String = release.version?.let { "v$it" } ?: "빌드 ${release.build}"

    /** 최신 릴리스 정보. 네트워크 사용 - 백그라운드에서 호출 */
    @Throws(IOException::class)
    fun fetchLatest(): Release? {
        @Suppress("DEPRECATION")
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Jamsuham-Android")
            if (conn.responseCode != 200) return null
            val json = JsonParser.parseString(conn.inputStream.bufferedReader().readText()).asJsonObject
            val tag = json.get("tag_name")?.asString ?: return null
            val build = tag.removePrefix("build-").toIntOrNull() ?: return null
            val asset = json.getAsJsonArray("assets")
                ?.map { it.asJsonObject }
                ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk") == true } ?: return null
            return Release(
                build = build,
                apkUrl = asset.get("browser_download_url").asString,
                size = asset.get("size")?.asLong ?: 0L,
                notes = json.get("body")?.takeIf { !it.isJsonNull }?.asString ?: "",
                page = json.get("html_url")?.asString ?: "https://github.com/$REPO/releases",
                version = json.get("name")?.takeIf { !it.isJsonNull }?.asString
                    ?.let { Regex("v(\\d+\\.\\d+\\.\\d+)").find(it)?.groupValues?.get(1) },
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 다른 앱 설치 권한이 없으면 설정 화면으로 보낸다. 바로 설치할 수 있으면 true */
    fun ensureInstallPermission(activity: Activity): Boolean {
        if (activity.packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(activity, "'이 출처의 앱 허용'을 켠 뒤 다시 업데이트를 눌러 주세요", Toast.LENGTH_LONG).show()
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
        } catch (_: Throwable) {
        }
        return false
    }

    /** APK 를 받아서 설치 창을 띄운다. 네트워크 사용 - 백그라운드에서 호출 */
    @Throws(IOException::class)
    fun downloadAndInstall(context: Context, release: Release, onProgress: (Int) -> Unit) {
        val apk = File(context.cacheDir, "update.apk")
        @Suppress("DEPRECATION")
        var conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        // GitHub 은 다른 주소로 넘겨서 내려준다
        var hops = 0
        while (true) {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Jamsuham-Android")
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                @Suppress("DEPRECATION")
                conn = URL(loc).openConnection() as HttpURLConnection
                hops++
                continue
            }
            if (code != 200) throw IOException("다운로드 실패 (HTTP $code)")
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("jamsuham.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        // 설치 세션에 복사했으므로 수십 MB 짜리 임시 파일은 지운다
        apk.delete()
    }
}

/** 설치 결과를 받는다. 사용자 확인이 필요하면 확인 창을 띄운다. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 안드로이드 10+ 는 앱이 화면에 없을 때 다른 화면을 띄우는 것을 막는다(아무 표시 없이 무시됨).
                // 다운로드 중에 홈으로 나갔으면 알림으로 대신 알려서 눌러서 설치하게 한다
                if (JamsuApp.inForeground) {
                    try {
                        context.startActivity(confirm)
                        return
                    } catch (t: Throwable) {
                        SessionHub.log.w("설치 확인 창을 띄우지 못했습니다", t)
                    }
                }
                notifyInstall(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류"
                Toast.makeText(context, "업데이트 설치 실패: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notifyInstall(context: Context, confirm: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "앱 업데이트", android.app.NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(context, NOTIFY_ID, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val icon = context.resources.getIdentifier("ic_stat_afk", "drawable", context.packageName).takeIf { it != 0 }
            ?: android.R.drawable.stat_sys_download_done
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("업데이트를 받았습니다")
            .setContentText("눌러서 설치를 마쳐 주세요")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
            Toast.makeText(context, "업데이트를 받았습니다. 앱에서 다시 업데이트를 눌러 주세요", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION = "kr.jamsuham.app.UPDATE_STATUS"
        private const val CHANNEL = "update"
        private const val NOTIFY_ID = 100_001 // 메뉴 알림(1000~66535)·잠수 알림(7)과 겹치지 않게
    }
}
