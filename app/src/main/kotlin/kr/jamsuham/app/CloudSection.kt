package kr.jamsuham.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 설정 화면의 "클라우드 잠수 (프리미엄)" 카드: 클라우드 서버 주소와, 계정별 연결·해제.
 * 연결하면 그 계정의 로그인 정보가 클라우드 서버에 저장되므로, 누르기 전에 무엇이 넘어가는지 알리고 동의를 받는다.
 */
class CloudSection(private val activity: Activity, private val ui: Ui) {
    private val accounts = AccountStore(activity)
    private val rows: LinearLayout = ui.vertical()
    private lateinit var urlInput: EditText
    private lateinit var urlState: TextView

    val card: LinearLayout = ui.card().apply {
        addView(ui.section(tr("클라우드 잠수 (프리미엄)", "Cloud AFK (premium)")))
        addView(ui.hint(tr(
            "클라우드 서버가 대신 접속해 있어서 폰을 꺼도 잠수가 유지되고, 웹에서도 같은 화면을 볼 수 있습니다. 계정을 연결한 뒤 서버 설정에서 '클라우드에서 잠수'를 켜세요.",
            "A cloud server stays connected for you, so AFK continues with your phone off and you can see the same screen on the web. Link an account, then turn on 'AFK in the cloud' in a server's settings.",
        )))
        addView(ui.label(tr("클라우드 서버 주소", "Cloud server address")))
        val urlRow = ui.horizontal()
        urlInput = ui.input("https://").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(CloudHub.url)
        }
        urlRow.addView(urlInput, ui.lp(0, Ui.WRAP, 1f))
        urlRow.addView(ui.button(tr("확인", "Check"), "plain") { saveUrl() }, ui.lp(Ui.WRAP, ui.dp(44)).apply { leftMargin = ui.dp(8) })
        addView(urlRow, ui.lp())
        urlState = ui.hint("")
        addView(urlState)
        addView(ui.label(tr("계정", "Accounts")))
        addView(rows)
    }

    init {
        CloudHub.init(activity)
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    private fun saveUrl() {
        val value = urlInput.text.toString().trim()
        // 연결한 계정이 있는데 주소를 바꾸면 그 연결은 예전 서버의 것이라 헷갈린다 → 먼저 해제하게 한다
        if (CloudHub.links().isNotEmpty() && value != CloudHub.url) {
            urlInput.setText(CloudHub.url)
            toast(tr("주소를 바꾸려면 먼저 연결한 계정을 모두 해제해 주세요", "Unlink all accounts before changing the address"))
            return
        }
        urlState.text = tr("확인하는 중…", "Checking…")
        CloudHub.checkUrl(activity, value) { ok, error ->
            if (activity.isFinishing || activity.isDestroyed) return@checkUrl
            if (ok != null) {
                CloudHub.setUrl(value)
                urlState.text = ok
            } else {
                urlState.text = error ?: ""
            }
        }
    }

    fun refresh() {
        rows.removeAllViews()
        val list = accounts.list()
        if (list.isEmpty()) {
            rows.addView(ui.text(tr("먼저 위에서 마이크로소프트 계정을 추가해 주세요", "Add a Microsoft account above first"), 14f, ui.p.muted))
            return
        }
        for (acc in list) {
            val linked = CloudHub.isLinked(acc.id)
            val row = ui.horizontal().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
            val texts = ui.vertical()
            texts.addView(ui.text(acc.name, 16f, bold = true))
            texts.addView(ui.text(CloudHub.describe(acc.id), 12f, ui.p.muted))
            row.addView(texts, ui.lp(0, Ui.WRAP, 1f))
            if (linked) row.addView(ui.button(tr("해제", "Unlink"), "ghost") { confirmUnlink(acc) }, ui.lp(Ui.WRAP, ui.dp(40)))
            else row.addView(ui.button(tr("연결", "Link"), "plain") { confirmLink(acc) }, ui.lp(Ui.WRAP, ui.dp(40)))
            rows.addView(row, ui.lp())
        }
    }

    private fun confirmLink(acc: Account) {
        val url = CloudHub.url
        if (url.isBlank()) {
            toast(tr("먼저 클라우드 서버 주소를 입력하고 '확인'을 눌러 주세요", "Enter the cloud server address and tap 'Check' first"))
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(tr("${acc.name} 계정을 클라우드에 연결", "Link ${acc.name} to the cloud"))
            .setMessage(tr(
                "이 계정의 마이크로소프트 로그인 정보를 아래 클라우드 서버로 보내 저장합니다. 클라우드 서버는 이 정보로 이 계정 대신 마인크래프트 서버에 접속하고, 주고받은 채팅을 앱·웹에 보여 줍니다.\n\n" +
                    "$url\n\n믿을 수 있는 서버에만 연결하세요. '해제'를 누르면 언제든 클라우드에 저장된 정보를 지울 수 있습니다.",
                "This account's Microsoft sign-in data will be sent to and stored on the cloud server below. The cloud server uses it to join Minecraft servers as this account and shows the chat in the app and on the web.\n\n" +
                    "$url\n\nOnly link to a server you trust. You can delete the data stored in the cloud at any time with 'Unlink'.",
            ))
            .setPositiveButton(tr("동의하고 연결", "Agree and link")) { _, _ ->
                toast(tr("연결하는 중…", "Linking…"))
                CloudHub.linkAccount(activity, acc.id) { error ->
                    if (activity.isFinishing || activity.isDestroyed) return@linkAccount
                    if (error == null) toast(tr("${acc.name} 계정을 클라우드에 연결했습니다", "Linked ${acc.name} to the cloud"))
                    else AlertDialog.Builder(activity).setTitle(tr("연결하지 못했습니다", "Couldn't link")).setMessage(error).setPositiveButton(tr("확인", "OK"), null).show()
                    refresh()
                }
            }
            .setNegativeButton(tr("취소", "Cancel"), null)
            .show()
    }

    private fun confirmUnlink(acc: Account) {
        val done = { error: String? ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                toast(error ?: tr("해제했습니다", "Unlinked"))
                refresh()
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(tr("${acc.name} 클라우드 연결 해제", "Unlink ${acc.name} from the cloud"))
            .setMessage(tr(
                "'모두 지우기'는 클라우드의 잠수를 모두 끊고, 클라우드 서버에 저장된 이 계정의 로그인 정보와 서버 목록을 지웁니다.\n'이 폰만'은 이 폰에서만 로그아웃하고 클라우드의 잠수는 그대로 둡니다.",
                "'Delete everything' stops all cloud AFK sessions and deletes this account's sign-in data and server list from the cloud server.\n'This phone only' signs out on this phone and leaves cloud AFK running.",
            ))
            .setPositiveButton(tr("모두 지우기", "Delete everything")) { _, _ -> CloudHub.unlinkAccount(acc.id, deleteOnServer = true, done) }
            .setNeutralButton(tr("이 폰만", "This phone only")) { _, _ -> CloudHub.unlinkAccount(acc.id, deleteOnServer = false, done) }
            .setNegativeButton(tr("취소", "Cancel"), null)
            .show()
    }
}
