package kr.jamsuham.app

import android.content.res.Configuration
import android.os.Bundle
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
        root.addView(panel, ui.lp(Ui.MATCH, Ui.MATCH))
        setupWindow(root, ui.p, 8)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::panel.isInitialized) {
            if (Store(this).server(panel.serverId) == null) finish() else panel.refreshTitle()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    companion object {
        const val EXTRA_ID = "server_id"
    }
}
