package kr.jamsuham.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

/** 잠수 시작에 필요한 공통 처리 (계정 확인, 알림 권한) */
abstract class BaseActivity : Activity(), Starter {

    /** 이 화면을 만들 때의 설정 판 (설정 화면에서 디자인·언어·글자 크기를 바꾸고 돌아오면 다시 만든다) */
    private var settingsVersion = -1

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        AppSettings.init(this)
        settingsVersion = AppSettings.version
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (settingsVersion != AppSettings.version && settingsAffectLayout()) {
            settingsVersion = AppSettings.version
            recreate()
        }
    }

    /** 설정이 바뀌면 화면을 다시 만들지 (설정 화면 자신은 스스로 처리) */
    protected open fun settingsAffectLayout(): Boolean = true

    override fun startServer(serverId: String): Boolean = startServer(serverId, checkLimit = true)

    private fun startServer(serverId: String, checkLimit: Boolean): Boolean {
        val store = Store(this)
        val server = store.server(serverId) ?: return false
        if (server.address.isBlank()) {
            toast(tr("서버 주소를 먼저 입력해 주세요", "Please enter the server address first"))
            openEditor(serverId)
            return false
        }
        val accounts = AccountStore(this)
        if (accounts.get(server.accountId) == null) {
            AlertDialog.Builder(this)
                .setTitle(tr("계정이 필요합니다", "Account required"))
                .setMessage(tr("이 서버에 접속할 계정을 서버 설정에서 골라 주세요.", "Choose an account for this server in the server settings."))
                .setPositiveButton(tr("서버 설정", "Server settings")) { _, _ -> openEditor(serverId) }
                .setNegativeButton(tr("취소", "Cancel"), null)
                .show()
            return false
        }
        // 무료 버전: 동시에 잠수할 수 있는 서버 수 제한
        val others = SessionHub.activeIds() - serverId
        if (checkLimit && others.size >= Plan.maxConcurrent(this)) {
            val names = others.mapNotNull { store.server(it)?.displayName }.joinToString(", ")
            AlertDialog.Builder(this)
                .setTitle(tr("동시 잠수는 ${Plan.FREE_CONCURRENT}개 서버까지", "Up to ${Plan.FREE_CONCURRENT} server(s) at once"))
                .setMessage(tr("무료 버전은 한 번에 ${Plan.FREE_CONCURRENT}개 서버에만 잠수할 수 있습니다.\n지금 잠수 중: $names\n\n그 서버를 끊고 '${server.displayName}'(으)로 바꿀까요?\n(여러 서버 동시 잠수는 프리미엄에서 제공할 예정입니다)", "The free version can AFK on only ${Plan.FREE_CONCURRENT} server(s) at a time.\nCurrently AFK: $names\n\nDisconnect it and switch to '${server.displayName}'?\n(AFK on multiple servers at once is planned for premium)"))
                .setPositiveButton(tr("바꾸기", "Switch")) { _, _ ->
                    for (id in others) AfkService.stop(this, id)
                    startServer(serverId, checkLimit = false)
                }
                .setNegativeButton(tr("취소", "Cancel"), null)
                .show()
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        SessionHub.clearLines(serverId)
        return try {
            AfkService.start(this, serverId)
            true
        } catch (t: Throwable) {
            toast(tr("잠수 서비스를 시작하지 못했습니다: ${t.message}", "Couldn't start the AFK service: ${t.message}"))
            false
        }
    }

    fun openEditor(serverId: String?) {
        val i = Intent(this, ServerEditActivity::class.java)
        if (serverId != null) i.putExtra(ServerEditActivity.EXTRA_ID, serverId)
        startActivity(i)
    }

    fun openChat(serverId: String) {
        startActivity(Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_ID, serverId))
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ 채팅 기록 내보내기

    /** 내보낼 서버와 기간 (파일 위치를 고르는 동안 기억). 기간 null = 전부 */
    private var pendingExport: Pair<String, Int?>? = null

    /** 기간을 고르고 → 안드로이드 "파일 저장" 화면에서 위치를 골라 .txt 로 저장 (따로 권한 필요 없음) */
    fun exportChatLog(serverId: String) {
        val days = ChatLog.days(serverId)
        if (days.isEmpty()) {
            toast(tr("아직 저장된 채팅 기록이 없습니다", "No saved chat logs yet"))
            return
        }
        val choices = arrayOf(tr("오늘", "Today"), tr("최근 7일", "Last 7 days"), tr("전체 (${days.size}일치)", "All (${days.size} days)"))
        val ranges = arrayOf<Int?>(1, 7, null)
        AlertDialog.Builder(this)
            .setTitle(tr("채팅 기록 저장", "Save chat log"))
            .setItems(choices) { _, which ->
                pendingExport = serverId to ranges[which]
                val name = (Store(this).server(serverId)?.displayName ?: "서버").replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                val date = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(java.util.Date())
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, "잠수크래프트_${name}_$date.txt")
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_EXPORT_LOG)
                } catch (_: Throwable) {
                    pendingExport = null
                    toast(tr("파일 저장 화면을 열 수 없습니다", "Couldn't open the save file screen"))
                }
            }
            .setNegativeButton(tr("취소", "Cancel"), null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_LOG) return
        val job = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (job == null) {
            toast(tr("다시 시도해 주세요", "Please try again"))
            return
        }
        val (serverId, range) = job
        val serverName = Store(this).server(serverId)?.displayName ?: tr("서버", "Server")
        Thread {
            val result = try {
                val n = contentResolver.openOutputStream(uri)?.use { ChatLog.export(serverId, serverName, range, it) }
                    ?: throw java.io.IOException(tr("파일을 열 수 없습니다", "Couldn't open the file"))
                tr("채팅 기록 ${n}줄을 저장했습니다", "Saved ${n} lines of chat log")
            } catch (t: Throwable) {
                tr("저장하지 못했습니다: ${t.message}", "Couldn't save: ${t.message}")
            }
            runOnUiThread { toast(result) }
        }.apply { isDaemon = true; start() }
    }

    companion object {
        private const val REQ_EXPORT_LOG = 7301
    }
}
