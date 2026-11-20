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

    override fun startServer(serverId: String): Boolean = startServer(serverId, checkLimit = true)

    private fun startServer(serverId: String, checkLimit: Boolean): Boolean {
        val store = Store(this)
        val server = store.server(serverId) ?: return false
        if (server.address.isBlank()) {
            toast("서버 주소를 먼저 입력해 주세요")
            openEditor(serverId)
            return false
        }
        val accounts = AccountStore(this)
        if (accounts.get(server.accountId) == null) {
            AlertDialog.Builder(this)
                .setTitle("계정이 필요합니다")
                .setMessage("이 서버에 접속할 계정을 서버 설정에서 골라 주세요.")
                .setPositiveButton("서버 설정") { _, _ -> openEditor(serverId) }
                .setNegativeButton("취소", null)
                .show()
            return false
        }
        // 무료 버전: 동시에 잠수할 수 있는 서버 수 제한
        val others = SessionHub.activeIds() - serverId
        if (checkLimit && others.size >= Plan.maxConcurrent(this)) {
            val names = others.mapNotNull { store.server(it)?.displayName }.joinToString(", ")
            AlertDialog.Builder(this)
                .setTitle("동시 잠수는 ${Plan.FREE_CONCURRENT}개 서버까지")
                .setMessage("무료 버전은 한 번에 ${Plan.FREE_CONCURRENT}개 서버에만 잠수할 수 있습니다.\n지금 잠수 중: $names\n\n그 서버를 끊고 '${server.displayName}'(으)로 바꿀까요?\n(여러 서버 동시 잠수는 프리미엄에서 제공할 예정입니다)")
                .setPositiveButton("바꾸기") { _, _ ->
                    for (id in others) AfkService.stop(this, id)
                    startServer(serverId, checkLimit = false)
                }
                .setNegativeButton("취소", null)
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
            toast("잠수 서비스를 시작하지 못했습니다: ${t.message}")
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
}
