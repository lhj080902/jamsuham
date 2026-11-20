package kr.jamsuham.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView

/** 계정 관리: 정품(마이크로소프트) 로그인, 비정품 닉네임 추가, 삭제 */
class AccountsActivity : BaseActivity() {

    private lateinit var ui: Ui
    private lateinit var accounts: AccountStore
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        accounts = AccountStore(this)

        val root = ui.vertical()
        val header = ui.horizontal()
        header.addView(ui.button("←", "ghost") { finish() }, ui.lp(ui.dp(48), ui.dp(44)))
        header.addView(ui.title("계정 관리"), ui.lp(0, Ui.WRAP, 1f))
        root.addView(header)

        val scroll = ScrollView(this)
        val body = ui.vertical()
        val addCard = ui.card()
        addCard.addView(ui.section("계정 추가"))
        addCard.addView(ui.button("마이크로소프트 계정으로 로그인 (정품)", "primary") {
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
        }, ui.lp(Ui.MATCH, ui.dp(50)))
        addCard.addView(ui.hint("앱 안에 마이크로소프트 로그인 화면이 뜹니다. 비밀번호는 마이크로소프트에만 입력되고 앱에는 저장되지 않습니다."))
        addCard.addView(ui.button("비정품 닉네임 추가", "plain") { addOffline() }, ui.gap(12).apply { height = ui.dp(46) })
        body.addView(addCard, ui.lp())

        val listCard = ui.card()
        listCard.addView(ui.section("저장된 계정"))
        listBox = ui.vertical()
        listCard.addView(listBox)
        body.addView(listCard, ui.gap(10))
        scroll.addView(body)
        root.addView(scroll, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(8) })
        setupWindow(root, ui.p)
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        listBox.removeAllViews()
        val list = accounts.list()
        if (list.isEmpty()) {
            listBox.addView(ui.text("아직 계정이 없습니다", 14f, ui.p.muted))
            return
        }
        for (acc in list) {
            val row = ui.horizontal().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
            val texts = ui.vertical()
            texts.addView(ui.text(acc.name, 16f, bold = true))
            texts.addView(ui.text(if (acc.microsoft) "정품 · 마이크로소프트" else "비정품 닉네임", 12f, ui.p.muted))
            row.addView(texts, ui.lp(0, Ui.WRAP, 1f))
            row.addView(ui.button("선택", "plain") {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED, acc.id))
                finish()
            }, ui.lp(Ui.WRAP, ui.dp(40)))
            row.addView(ui.button("삭제", "ghost") { confirmRemove(acc) }, ui.lp(Ui.WRAP, ui.dp(40)).apply { leftMargin = ui.dp(4) })
            listBox.addView(row, ui.lp())
        }
    }

    private fun addOffline() {
        val input = ui.input("영문/숫자/_ 3~16자").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input, ui.lp()) }
        AlertDialog.Builder(this)
            .setTitle("비정품 닉네임")
            .setView(box)
            .setPositiveButton("추가") { _, _ ->
                try {
                    accounts.addOffline(input.text.toString())
                    refresh()
                } catch (e: IllegalArgumentException) {
                    toast(e.message ?: "잘못된 닉네임")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmRemove(acc: Account) {
        AlertDialog.Builder(this)
            .setTitle("계정 삭제")
            .setMessage("${acc.name} 계정을 이 앱에서 지울까요?" + if (acc.microsoft) "\n(마이크로소프트 계정 자체는 지워지지 않습니다)" else "")
            .setPositiveButton("삭제") { _, _ ->
                accounts.remove(acc.id)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            val id = data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT_ID)
            if (id != null && callingActivity != null) {
                // 서버 설정에서 들어왔으면 방금 로그인한 계정을 바로 선택
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED, id))
            }
            refresh()
        }
    }

    companion object {
        const val EXTRA_SELECTED = "selected_account"
        private const val REQ_LOGIN = 31
    }
}
