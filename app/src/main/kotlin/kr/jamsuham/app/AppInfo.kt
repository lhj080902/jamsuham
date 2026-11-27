package kr.jamsuham.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView

/**
 * tr("정보", "About") 화면: 버전, 비공식 고지, 그리고 정책 문서를 **앱 안에서** 연다
 * (개인정보처리방침·이름/아이콘 이용 안내·오픈소스 라이선스 전문 — app/src/main/assets/legal/).
 * ViaVersion(GPL-3.0)을 포함해 배포하므로 라이선스 전문과 소스 코드 위치를 함께 알린다.
 */
object AppInfo {
    const val SOURCE_URL = "https://github.com/lhj080902/jamsuham"

    /** Play Console 에 넣는 개인정보처리방침 주소 (앱 안 문서와 같은 파일) */
    const val PRIVACY_URL = "https://github.com/lhj080902/jamsuham/blob/main/app/src/main/assets/legal/privacy-policy.md"

    /** 문의·신고를 받는 주소 (개인정보처리방침·이용 약관에 적힌 문의 주소와 같게 둘 것) */
    const val CONTACT_EMAIL = "jamsucraft@naver.com"

    /** 앱에 들어간 오픈소스: 이름, 라이선스, 라이선스 전문 파일 */
    private val LIBRARIES get() = listOf(
        Triple("ViaVersion · ViaBackwards · ViaRewind", "GPL-3.0", "GPL-3.0.txt"),
        Triple("MinecraftAuth (RaphiMC)", "LGPL-3.0", "LGPL-3.0.txt"),
        Triple("Netty", "Apache-2.0", "Apache-2.0.txt"),
        Triple("Gson", "Apache-2.0", "Apache-2.0.txt"),
        Triple("Guava", "Apache-2.0", "Apache-2.0.txt"),
        Triple(tr("Kotlin 표준 라이브러리", "Kotlin standard library"), "Apache-2.0", "Apache-2.0.txt"),
    )

    fun show(activity: Activity, ui: Ui) {
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(ui.text(tr("잠수크래프트 ", "Jamsucraft ") + Updater.versionLabel(activity) + tr(" (빌드 ${Updater.currentBuild(activity)})", " (build ${Updater.currentBuild(activity)})") + if (Flavor.PLAY) "" else tr(" · GitHub 판", " · GitHub edition"), 15f, bold = true))
        box.addView(ui.hint(
            tr("마인크래프트 공식 제품이 아닙니다. Mojang 또는 Microsoft 가 승인하거나 관련된 앱이 아닙니다.\n", "") +
                "NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.",
        ))
        box.addView(ui.hint(tr("서버마다 잠수·비공식 클라이언트에 대한 규칙이 다를 수 있으니, 서버 규칙을 확인하고 사용해 주세요.", "Rules on AFK and unofficial clients differ between servers, so check the server rules before using it.")))

        box.addView(ui.label(tr("정책", "Policies")))
        box.addView(ui.button(tr("이용 약관", "Terms of Use"), "plain") { showDocument(activity, ui, tr("이용 약관", "Terms of Use"), Terms.file) }, ui.gap(4))
        box.addView(ui.button(tr("개인정보처리방침", "Privacy policy"), "plain") { showDocument(activity, ui, tr("개인정보처리방침", "Privacy policy"), "privacy-policy.md") }, ui.gap(6))
        box.addView(ui.button(tr("이름·아이콘 이용 안내", "Name and icon usage"), "plain") { showDocument(activity, ui, tr("이름·아이콘 이용 안내", "Name and icon usage"), "trademark.md") }, ui.gap(6))

        box.addView(ui.label(tr("오픈소스 라이선스", "Open source licenses")))
        box.addView(ui.hint(tr("이 앱의 코드는 GPL-3.0 에 따라 공개되어 있습니다. 이름 \"잠수크래프트\"와 아이콘은 라이선스에 포함되지 않습니다.", "This app’s code is published under GPL-3.0. The name \"Jamsucraft\" and the icon are not covered by the license.")))
        for ((name, license, file) in LIBRARIES) {
            box.addView(ui.button("$name — $license", "plain") { showDocument(activity, ui, license, file) }, ui.gap(4))
        }
        box.addView(ui.button(tr("소스 코드 (GitHub)", "Source code (GitHub)"), "plain") { open(activity, SOURCE_URL) }, ui.gap(6))

        val scroll = ScrollView(activity).apply { addView(box) }
        AlertDialog.Builder(activity)
            .setTitle(tr("정보", "About"))
            .setView(scroll)
            .setPositiveButton(tr("닫기", "Close"), null)
            .show()
    }

    /** assets/legal/ 의 문서를 앱 안에서 연다 (.md 는 제목만 굵게 보이는 간단한 형태로) */
    fun showDocument(activity: Activity, ui: Ui, title: String, file: String) {
        val raw = try {
            activity.assets.open("legal/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            tr("문서를 열지 못했습니다: ${t.message}", "Couldn't open the document: ${t.message}")
        }
        val text: CharSequence = if (file.endsWith(".md")) renderMarkdown(raw) else raw
        val tv = TextView(activity).apply {
            this.text = text
            textSize = if (file.endsWith(".md")) 14f else 11f
            setTextColor(ui.p.text)
            setTextIsSelectable(true)
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            if (!file.endsWith(".md")) typeface = Typeface.MONOSPACE
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(tv) })
            .setPositiveButton(tr("닫기", "Close"), null)
            .show()
    }

    /** 정책 문서용 아주 간단한 마크다운 표시: # 제목은 굵고 크게, **굵게**, 표·목록은 읽기 좋은 글자로 */
    internal fun renderMarkdown(md: String): CharSequence {
        val sb = SpannableStringBuilder()
        for (rawLine in md.lines()) {
            var line = rawLine
            // 표: 구분 줄은 버리고, 칸은 " · " 로 잇는다
            if (line.trimStart().startsWith("|")) {
                if (line.replace("|", "").replace("-", "").replace(":", "").isBlank()) continue
                line = line.trim().trim('|').split('|').joinToString(" · ") { it.trim() }
            }
            val heading = Regex("^(#{1,6})\\s+(.*)").find(line)
            if (heading != null) {
                if (sb.isNotEmpty()) sb.append('\n')
                val start = sb.length
                sb.append(heading.groupValues[2])
                val size = if (heading.groupValues[1].length == 1) 1.3f else 1.12f
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(size), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append('\n')
                continue
            }
            if (line.startsWith("> ")) line = line.removePrefix("> ")
            line = line.replace(Regex("^(\\s*)- "), "$1• ")
            // [글자](주소) → 글자 (주소)
            line = line.replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1 ($2)")
            line = line.replace("`", "")
            // **굵게**
            val start = sb.length
            var rest = line
            while (true) {
                val m = Regex("\\*\\*(.+?)\\*\\*").find(rest) ?: break
                sb.append(rest.substring(0, m.range.first))
                val b = sb.length
                sb.append(m.groupValues[1])
                sb.setSpan(StyleSpan(Typeface.BOLD), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rest = rest.substring(m.range.last + 1)
            }
            sb.append(rest)
            if (sb.length > start || line.isBlank()) sb.append('\n')
        }
        return sb
    }

    private fun open(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            android.widget.Toast.makeText(activity, url, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
