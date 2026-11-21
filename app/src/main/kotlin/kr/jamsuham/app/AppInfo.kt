package kr.jamsuham.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView

/**
 * "정보" 화면: 버전, 비공식 고지, 개인정보처리방침, 오픈소스 라이선스·소스 코드 위치.
 * ViaVersion(GPL-3.0)을 포함해 배포하므로 앱 소스를 공개하고 위치를 알려야 한다.
 */
object AppInfo {
    const val SOURCE_URL = "https://github.com/lhj080902/jamsuham"
    const val PRIVACY_URL = "https://github.com/lhj080902/jamsuham/blob/main/docs/privacy-policy.md"

    /** 앱에 들어간 오픈소스와 라이선스 */
    private val LIBRARIES = listOf(
        "ViaVersion · ViaBackwards · ViaRewind" to "GPL-3.0",
        "MinecraftAuth (RaphiMC)" to "LGPL-3.0",
        "Netty" to "Apache-2.0",
        "Gson" to "Apache-2.0",
        "Guava" to "Apache-2.0",
        "Kotlin 표준 라이브러리" to "Apache-2.0",
    )

    fun show(activity: Activity, ui: Ui) {
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(ui.text("잠수함 " + Updater.versionLabel(activity) + if (Flavor.PLAY) "" else " (GitHub 판)", 15f, bold = true))
        box.addView(ui.hint(
            "마인크래프트 공식 제품이 아닙니다. Mojang 또는 Microsoft 가 승인하거나 관련된 앱이 아닙니다.\n" +
                "NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.",
        ))
        box.addView(ui.hint("서버마다 잠수·비공식 클라이언트에 대한 규칙이 다를 수 있으니, 서버 규칙을 확인하고 사용해 주세요."))

        box.addView(ui.label("개인정보"))
        box.addView(ui.hint("로그인 정보·서버 목록·채팅 기록은 이 기기에만 저장되며 개발자에게 전송되지 않습니다."))
        box.addView(ui.button("개인정보처리방침 보기", "plain") { open(activity, PRIVACY_URL) }, ui.gap(6))

        box.addView(ui.label("오픈소스 라이선스"))
        for ((name, license) in LIBRARIES) box.addView(ui.hint("$name — $license"))
        box.addView(ui.hint("이 앱은 GPL-3.0 에 따라 소스 코드를 공개합니다."))
        box.addView(ui.button("소스 코드 (GitHub)", "plain") { open(activity, SOURCE_URL) }, ui.gap(6))

        val scroll = ScrollView(activity).apply { addView(box) }
        AlertDialog.Builder(activity)
            .setTitle("정보")
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun open(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            android.widget.Toast.makeText(activity, url, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
