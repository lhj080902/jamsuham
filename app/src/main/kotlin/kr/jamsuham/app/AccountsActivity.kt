package kr.jamsuham.app

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView

/** 서버 설정에서 접속할 계정을 고르는 화면 (계정 추가·삭제도 가능). 계정 관리 자체는 설정 화면에 있다 */
class AccountsActivity : BaseActivity() {

    private lateinit var section: AccountsSection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        // 서버 설정에서 열었을 때만 "선택" 버튼
        val picking = callingActivity != null
        section = AccountsSection(this, ui, if (picking) { id -> choose(id) } else null)

        val root = ui.vertical()
        val header = ui.horizontal()
        header.addView(ui.button("←", "ghost") { finish() }, ui.lp(ui.dp(48), ui.dp(44)))
        header.addView(ui.title(if (picking) tr("계정 선택", "Choose account") else tr("계정 관리", "Accounts")), ui.lp(0, Ui.WRAP, 1f))
        root.addView(header)

        val scroll = ScrollView(this)
        val body = ui.vertical()
        body.addView(section.addCard, ui.lp())
        body.addView(section.listCard, ui.gap(10))
        scroll.addView(body)
        root.addView(scroll, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(8) })
        setupWindow(root, ui.p)
        setContentView(root)
    }

    private fun choose(id: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED, id))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = section.onLoginResult(requestCode, resultCode, data)
        // 서버 설정에서 들어왔으면 방금 로그인한 계정을 바로 선택
        if (id != null && callingActivity != null) setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED, id))
    }

    companion object {
        const val EXTRA_SELECTED = "selected_account"
    }
}
