package kr.jamsuham.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView

/**
 * 이용 약관 동의. 동의하기 전에는 어느 화면도 쓸 수 없다 ([BaseActivity] 가 화면을 만들 때마다 확인).
 * 동의 버전 하나([VERSION])가 이용 약관과 개인정보처리방침을 함께 덮는다 — 둘 중 하나가 크게 바뀌면 올려서 다시 동의를 받는다.
 *
 * 검사는 화면에만 둔다: 업데이트·재부팅 때 이어서 시작되는 잠수와 전에 넣어 둔 자동 명령어는 동의 전에도 돈다
 * (서비스에서 막으면 업데이트한 밤에 잠수가 말없이 멈춘다). 새로 설치한 사람은 화면을 거쳐야만 잠수를 시작할 수 있다.
 */
object Terms {
    const val VERSION = 1

    const val PREFS = "jamsuham_settings"
    const val KEY = "terms_accepted"

    /** 앱 언어에 맞는 약관 문서 (assets/legal/) */
    val file: String get() = if (AppSettings.english) "terms-en.md" else "terms.md"

    fun accepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0) >= VERSION

    /**
     * 아직 동의하지 않았으면 닫을 수 없는 동의 창을 띄우고 그 창을 돌려준다 (동의했으면 null).
     * 거절하면 앱의 화면을 모두 닫는다. 다음에 열면 다시 묻는다.
     */
    fun gate(activity: Activity, onAccepted: () -> Unit = {}): AlertDialog? {
        if (accepted(activity)) return null
        val ui = Ui(activity)
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(ui.text(tr(
            "잠수크래프트는 마인크래프트 서버의 채팅을 보여 주고 보낼 수 있는 앱입니다. 계속하려면 이용 약관과 개인정보처리방침에 동의해 주세요.",
            "Jamsucraft shows and sends chat on Minecraft servers. To continue, please agree to the Terms of Use and the Privacy Policy.",
        ), 14f))
        box.addView(ui.text(tr(
            "• 욕설·혐오·괴롭힘, 성적인 내용, 불법 행위, 스팸·광고, 사칭을 하지 않습니다.\n" +
                "• 부적절한 사용자나 채팅은 앱에서 차단하고 신고할 수 있습니다.\n" +
                "• 접속하는 서버의 규칙을 지킬 책임은 이용자에게 있습니다.",
            "• No abuse, hate or harassment, sexual content, illegal activity, spam or ads, or impersonation.\n" +
                "• You can block and report inappropriate users and messages in the app.\n" +
                "• You are responsible for following the rules of the servers you join.",
        ), 13f, ui.p.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(6)) })
        box.addView(ui.button(tr("이용 약관 전문", "Full terms"), "plain") {
            AppInfo.showDocument(activity, ui, tr("이용 약관", "Terms of Use"), file)
        }, ui.gap(4))
        box.addView(ui.button(tr("개인정보처리방침", "Privacy policy"), "plain") {
            AppInfo.showDocument(activity, ui, tr("개인정보처리방침", "Privacy policy"), "privacy-policy.md")
        }, ui.gap(6))
        return AlertDialog.Builder(activity)
            .setTitle(tr("이용 약관 동의", "Terms of Use"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setCancelable(false)
            .setPositiveButton(tr("동의하고 시작", "Agree and start")) { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, VERSION).apply()
                onAccepted()
            }
            .setNegativeButton(tr("동의하지 않음", "Decline")) { _, _ -> activity.finishAffinity() }
            .show()
    }
}
