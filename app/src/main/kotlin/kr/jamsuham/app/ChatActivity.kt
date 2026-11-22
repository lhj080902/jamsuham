package kr.jamsuham.app

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import android.widget.LinearLayout

/** 폰에서 서버 하나의 채팅 화면 */
class ChatActivity : BaseActivity() {

    private lateinit var panel: ChatPanel
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null || Store(this).server(id) == null) {
            finish()
            return
        }
        val ui = Ui(this)
        root = ui.vertical()
        panel = ChatPanel(this, ui, id, showBack = true, onBack = { finish() }, onEdit = { openEditor(id) })
        panel.openMenuOnAttach = intent.getBooleanExtra(EXTRA_OPEN_MENU, false)
        // 뒤로 가기도 ← 버튼과 똑같이 물어본다 (안드로이드 13+ 는 새 방식)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { panel.requestBack() }
        }
        root.addView(panel, ui.lp(Ui.MATCH, Ui.MATCH))
        setupWindow(root, ui.p, 8)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 설정: 채팅 화면을 보는 동안 화면 꺼짐 막기
        if (AppSettings.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::panel.isInitialized) {
            if (Store(this).server(panel.serverId) == null) finish() else panel.refreshTitle()
        }
    }

    @Deprecated("Android 12 이하용")
    override fun onBackPressed() {
        if (::panel.isInitialized) panel.requestBack() else super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    companion object {
        const val EXTRA_ID = "server_id"
        const val EXTRA_OPEN_MENU = "open_menu"
    }
}
