package kr.jamsuham.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout

/**
 * 계정 관리 카드 두 개(계정 추가 / 저장된 계정). 설정 화면과, 서버 설정에서 계정을 고르는 화면이 같이 쓴다.
 * [onSelect] 가 있으면 각 계정에 "선택" 버튼이 붙는다. 로그인 결과는 액티비티가 받아 [onLoginResult] 로 넘긴다.
 */
class AccountsSection(
    private val activity: Activity,
    private val ui: Ui,
    private val onSelect: ((String) -> Unit)? = null,
) {
    private val accounts = AccountStore(activity)
    private val listBox: LinearLayout = ui.vertical()

    /** 계정 목록이 바뀌었을 때 (같은 화면의 클라우드 카드가 다시 그리도록) */
    var onChanged: (() -> Unit)? = null

    /** 계정 추가 카드 */
    val addCard: LinearLayout = ui.card().apply {
        addView(ui.section(tr("계정 추가", "Add account")))
        addView(ui.button(tr("마이크로소프트 계정으로 로그인", "Sign in with Microsoft"), "primary") {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(Intent(activity, LoginActivity::class.java), REQ_LOGIN)
        }, ui.lp(Ui.MATCH, ui.dp(50)))
        addView(ui.hint(tr(
            "마인크래프트 자바 에디션을 구매한 계정으로 로그인해 주세요. 앱 안에 마이크로소프트 로그인 화면이 뜨며, 비밀번호는 마이크로소프트에만 입력되고 앱에는 저장되지 않습니다.",
            "Sign in with an account that owns Minecraft: Java Edition. The Microsoft sign-in page opens inside the app. Your password goes only to Microsoft and is never stored in the app.",
        )))
    }

    /** 저장된 계정 카드 */
    val listCard: LinearLayout = ui.card().apply {
        addView(ui.section(tr("저장된 계정", "Saved accounts")))
        addView(listBox)
    }

    init {
        refresh()
    }

    fun refresh() {
        onChanged?.invoke()
        listBox.removeAllViews()
        val list = accounts.list()
        if (list.isEmpty()) {
            listBox.addView(ui.text(tr("아직 계정이 없습니다", "No accounts yet"), 14f, ui.p.muted))
            return
        }
        for (acc in list) {
            val row = ui.horizontal().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
            val texts = ui.vertical()
            texts.addView(ui.text(acc.name, 16f, bold = true))
            texts.addView(ui.text(tr("마이크로소프트 계정", "Microsoft account"), 12f, ui.p.muted))
            row.addView(texts, ui.lp(0, Ui.WRAP, 1f))
            onSelect?.let { select ->
                row.addView(ui.button(tr("선택", "Select"), "plain") { select(acc.id) }, ui.lp(Ui.WRAP, ui.dp(40)))
            }
            row.addView(ui.button(tr("삭제", "Delete"), "ghost") { confirmRemove(acc) }, ui.lp(Ui.WRAP, ui.dp(40)).apply { leftMargin = ui.dp(4) })
            listBox.addView(row, ui.lp())
        }
    }

    /** 액티비티의 onActivityResult 에서 부른다. 새로 로그인한 계정 id 를 돌려준다 (아니면 null) */
    fun onLoginResult(requestCode: Int, resultCode: Int, data: Intent?): String? {
        if (requestCode != REQ_LOGIN) return null
        refresh()
        return if (resultCode == Activity.RESULT_OK) data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT_ID) else null
    }

    private fun confirmRemove(acc: Account) {
        AlertDialog.Builder(activity)
            .setTitle(tr("계정 삭제", "Delete account"))
            .setMessage(
                tr("${acc.name} 계정을 이 앱에서 지울까요?\n(마이크로소프트 계정 자체는 지워지지 않습니다)", "Remove ${acc.name} from this app?\n(Your Microsoft account itself is not deleted)") +
                    if (CloudHub.isLinked(acc.id)) tr(
                        "\n\n이 계정은 클라우드 잠수에 연결되어 있습니다. 지우면 이 폰의 클라우드 연결만 해제되고, 클라우드에 저장된 정보와 잠수는 그대로 남습니다. 그것도 지우려면 먼저 아래 클라우드 잠수에서 '해제 → 모두 지우기'를 해 주세요.",
                        "\n\nThis account is linked to cloud AFK. Removing it only unlinks this phone; the data and AFK sessions in the cloud stay. To delete those too, first use 'Unlink → Delete everything' under Cloud AFK below.",
                    ) else "",
            )
            .setPositiveButton(tr("삭제", "Delete")) { _, _ ->
                // 계정이 없어지면 연결을 관리할 곳이 없어지므로 이 폰의 클라우드 연결도 같이 해제한다
                if (CloudHub.isLinked(acc.id)) CloudHub.unlinkAccount(acc.id, deleteOnServer = false) {}
                accounts.remove(acc.id)
                refresh()
            }
            .setNegativeButton(tr("취소", "Cancel"), null)
            .show()
    }

    companion object {
        const val REQ_LOGIN = 31
    }
}
