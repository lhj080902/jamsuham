package kr.jamsuham.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 폰이 다시 켜졌을 때(BOOT_COMPLETED)·앱이 업데이트된 직후(MY_PACKAGE_REPLACED) 잠수 중이던 서버에 이어서 접속한다.
 * 두 경우 모두 안드로이드가 백그라운드에서 포그라운드 서비스를 시작하도록 허용하며,
 * 우리 서비스 종류(specialUse)는 안드로이드 15 의 부팅 시 시작 제한에도 해당하지 않는다.
 * 직접 '끊기'를 누른 서버는 잠수 목록에서 빠지므로 이어서 접속하지 않는다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "폰이 다시 켜져서 이어서 접속합니다"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "앱이 업데이트되어 이어서 접속합니다"
            else -> return
        }
        val store = Store(context)
        val ids = store.activeServers().filter { id -> store.server(id)?.resumeOnBoot == true }.toSet()
        if (ids.isEmpty()) return
        try {
            AfkService.resume(context, ids, reason)
        } catch (t: Throwable) {
            // 일부 제조사 폰은 부팅 직후 시작을 막기도 한다 → 다음에 앱을 열면 사용자가 다시 시작
            SessionHub.log.w("이어서 잠수를 시작하지 못했습니다", t)
        }
    }
}
