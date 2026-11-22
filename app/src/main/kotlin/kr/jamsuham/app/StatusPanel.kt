package kr.jamsuham.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.jamsuham.core.DeathInfo
import kr.jamsuham.core.Traffic
import kr.jamsuham.core.Vitals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 채팅 화면의 "상태" 탭: 체력(최대 체력·흡수 체력 포함), 허기·포화, 마지막 사망 기록.
 * 최대 체력은 서버가 보내는 값을 그대로 써서 바닐라(20 = 하트 10칸)보다 적거나 많아도 맞게 보인다.
 */
class StatusPanel(activity: Activity, private val ui: Ui) : LinearLayout(activity) {
    private val p = ui.p
    private val emptyView: TextView
    private val body: LinearLayout
    private val healthText: TextView
    private val healthBar = Bar(p.err, COLOR_ABSORB)
    private val healthSub: TextView
    private val foodText: TextView
    private val foodBar = Bar(COLOR_FOOD, 0)
    private val foodSub: TextView
    private val dataText: TextView
    private val dataSub: TextView
    private val packText: TextView
    private val packSub: TextView
    private val deathTitle: TextView
    private val deathText: TextView
    private val scroll: ScrollView

    init {
        orientation = VERTICAL
        background = ui.rounded(p.card, 16)
        setPadding(ui.dp(16) + ui.cardPad, ui.dp(14) + ui.cardPad, ui.dp(16) + ui.cardPad, ui.dp(14) + ui.cardPad)
        elevation = ui.dp(8).toFloat()

        val inner = ui.vertical()
        emptyView = ui.text(tr("접속하면 체력과 배고픔이 보입니다", "Connect to see health and hunger"), 14f, p.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(12)) }
        inner.addView(emptyView)

        body = ui.vertical()
        body.addView(ui.text(tr("체력", "Health"), 13f, p.muted))
        healthText = ui.text("", 20f, bold = true)
        body.addView(healthText)
        body.addView(healthBar, ui.lp(Ui.MATCH, ui.dp(16)).apply { topMargin = ui.dp(6) })
        healthSub = ui.text("", 12f, p.muted).apply { setPadding(0, ui.dp(4), 0, 0) }
        body.addView(healthSub)

        body.addView(ui.text(tr("배고픔", "Hunger"), 13f, p.muted).apply { setPadding(0, ui.dp(16), 0, 0) })
        foodText = ui.text("", 20f, bold = true)
        body.addView(foodText)
        body.addView(foodBar, ui.lp(Ui.MATCH, ui.dp(16)).apply { topMargin = ui.dp(6) })
        foodSub = ui.text("", 12f, p.muted).apply { setPadding(0, ui.dp(4), 0, 0) }
        body.addView(foodSub)
        inner.addView(body)

        inner.addView(ui.text(tr("데이터 사용량", "Data usage"), 13f, p.muted).apply { setPadding(0, ui.dp(20), 0, 0) })
        dataText = ui.text("", 15f, bold = true)
        inner.addView(dataText)
        dataSub = ui.text("", 12f, p.muted).apply { setPadding(0, ui.dp(2), 0, 0) }
        inner.addView(dataSub)

        inner.addView(ui.text(tr("리소스팩 그림 글자 (칭호·아이콘)", "Resource pack glyphs (titles, icons)"), 13f, p.muted).apply { setPadding(0, ui.dp(20), 0, 0) })
        packText = ui.text("", 15f, bold = true)
        inner.addView(packText)
        packSub = ui.text("", 12f, p.muted).apply {
            setPadding(0, ui.dp(2), 0, 0)
            setTextIsSelectable(true) // 리소스팩 주소를 복사해 문의할 수 있게
        }
        inner.addView(packSub)

        inner.addView(ui.text(tr("마지막 사망", "Last death"), 13f, p.muted).apply { setPadding(0, ui.dp(20), 0, 0) })
        deathTitle = ui.text("", 15f, bold = true)
        inner.addView(deathTitle)
        deathText = ui.text("", 13f).apply { setPadding(0, ui.dp(2), 0, 0) }
        inner.addView(deathText)

        scroll = ScrollView(activity).apply { addView(inner) }
        addView(scroll, ui.lp(Ui.MATCH, Ui.MATCH))
    }

    val scrollView: ScrollView get() = scroll

    /** [rate] 알림과 같은 1분마다 갱신되는 시간당 사용량, [packs] 서버 리소스팩별 그림 글자 상태 */
    fun show(v: Vitals?, death: DeathInfo?, traffic: Traffic?, rate: Long?, packs: List<SessionHub.PackState>) {
        showPacks(packs)
        if (traffic == null || traffic.total == 0L) {
            dataText.setTextIfChanged(tr("아직 없습니다", "None yet"))
            dataSub.setTextIfChanged("")
        } else {
            dataText.setTextIfChanged(tr("받음 ${bytes(traffic.received)} · 보냄 ${bytes(traffic.sent)}", "Received ${bytes(traffic.received)} · Sent ${bytes(traffic.sent)}"))
            dataSub.setTextIfChanged(
                (if (rate != null) tr("시간당 약 ${bytes(rate)} (알림과 같은 값, 1분마다 갱신)", "About ${bytes(rate)}/h (same as the notification, updated every minute)") else tr("1분 넘게 접속하면 시간당 사용량이 보입니다", "Hourly usage appears after a minute online")) +
                    tr("\n이번에 잠수를 시작한 뒤 합계 · 마인크래프트 데이터 기준이라 통신사 집계는 조금 더 많을 수 있습니다", "\nTotal since this AFK session started · counts Minecraft data only, so your carrier may count a bit more"),
            )
        }
        if (v == null) {
            emptyView.visibility = VISIBLE
            body.visibility = GONE
        } else {
            emptyView.visibility = GONE
            body.visibility = VISIBLE
            val max = if (v.maxHealth > 0f) v.maxHealth else 20f
            val hp = v.health.coerceAtLeast(0f)
            healthText.setTextIfChanged(
                (if (v.dead) tr("사망 · ", "Dead · ") else "") + "${num(hp)} / ${num(max)}" + (if (v.absorption > 0f) "  (+${num(v.absorption)})" else ""),
            )
            // 흡수 체력이 있으면 막대 전체 길이를 최대 체력 + 흡수로 잡고 뒤에 노란색으로 붙인다
            val total = max + v.absorption
            healthBar.set(hp / total, v.absorption / total)
            healthSub.setTextIfChanged(
                tr("하트 ${num(hp / 2f)} / ${num(max / 2f)}칸", "Hearts ${num(hp / 2f)} / ${num(max / 2f)}") +
                    (if (max != 20f) tr(" · 최대 체력이 기본(20)과 다른 서버입니다", " · This server uses a non-default max health (20)") else "") +
                    (if (v.absorption > 0f) tr(" · 흡수 ${num(v.absorption / 2f)}칸", " · Absorption ${num(v.absorption / 2f)} hearts") else ""),
            )
            foodText.setTextIfChanged("${v.food} / 20")
            foodBar.set(v.food / 20f, 0f)
            foodSub.setTextIfChanged(
                tr("포화 ${num(v.saturation)}", "Saturation ${num(v.saturation)}") + when {
                    v.food == 0 -> tr(" · 굶주려서 체력이 줄어듭니다", " · Starving, losing health")
                    v.food <= 6 -> tr(" · 배가 고파서 달리기를 할 수 없습니다", " · Too hungry to sprint")
                    v.food < 18 -> tr(" · 18 이상이면 체력이 저절로 찹니다", " · Health regenerates at 18 or more")
                    else -> ""
                },
            )
        }
        if (death == null) {
            deathTitle.setTextIfChanged(tr("아직 없습니다", "None yet"))
            deathText.visibility = GONE
        } else {
            deathTitle.setTextIfChanged("☠ " + timeOf(death.atMs))
            deathText.visibility = VISIBLE
            deathText.setTextIfChanged(death.describe())
        }
    }

    private fun showPacks(packs: List<SessionHub.PackState>) {
        if (packs.isEmpty()) {
            packText.setTextIfChanged(tr("서버가 리소스팩을 보내지 않았습니다", "The server did not send a resource pack"))
            packSub.setTextIfChanged(tr("리소스팩이 없는 서버는 칭호가 글자 그대로 보입니다", "Without a resource pack, titles are shown as plain text"))
            return
        }
        val total = packs.sumOf { it.glyphs ?: 0 }
        packText.setTextIfChanged(
            when {
                packs.all { it.skipped } -> tr("꺼져 있음 (서버 설정에서 켤 수 있습니다)", "Off (can be turned on in server settings)")
                packs.any { it.deferred && it.glyphs == null } -> tr("앱을 열어 두면 불러옵니다", "Loads while the app is open")
                packs.any { it.glyphs == null && it.error == null && !it.skipped } -> tr("받는 중…", "Downloading…")
                total > 0 -> tr("그림 글자 ${total}개 사용 중", "Using ${total} glyphs")
                packs.any { it.error != null } -> tr("불러오지 못함", "Failed to load")
                else -> tr("리소스팩에 그림 글자가 없습니다", "The resource pack has no glyphs")
            },
        )
        val diag = buildString {
            if (Glyphs.broken.isNotEmpty()) append(tr("\n읽지 못한 그림 ${Glyphs.broken.size}개: ", "\n${Glyphs.broken.size} unreadable images: ") + Glyphs.broken.take(3).joinToString { it.substringAfterLast('/') })
            if (Glyphs.missing.isNotEmpty()) append(tr("\n팩에 없는 글자 ${Glyphs.missing.size}개: ", "\n${Glyphs.missing.size} glyphs missing from pack: ") + Glyphs.missing.take(4).joinToString())
        }
        packSub.setTextIfChanged(diag.trimStart() + (if (diag.isNotEmpty()) "\n" else "") +
            packs.joinToString("\n") { s ->
                val state = when {
                    s.skipped -> tr("꺼짐", "Off")
                    s.error != null -> tr("실패: ${s.error}", "Failed: ${s.error}")
                    s.deferred && s.glyphs == null -> tr("앱을 열면 불러옴", "Loads when the app is opened")
                    s.glyphs == null -> tr("받는 중", "Downloading")
                    else -> tr("글자 ${s.glyphs}개 · 글꼴 ${s.fonts}개", "${s.glyphs} glyphs · ${s.fonts} fonts") +
                        (if (s.deferred) tr(" · 앱이 뒤에 있어 메모리에서 내림", " · unloaded while in background") else "") +
                        (s.report?.lines()?.firstOrNull { it.startsWith("받는 데") || it.startsWith("Download took") }?.let { " · $it" } ?: "")
                }
                "• $state\n  ${s.url}" + (s.report?.takeIf { s.glyphs == 0 || Glyphs.missing.isNotEmpty() }?.let { tr("\n  [팩 안 모습]\n  ", "\n  [Pack contents]\n  ") + it.replace("\n", "\n  ") } ?: "")
            },
        )
    }

    /** 한 줄짜리 막대: [main] 비율만큼 주색, 이어서 [extra] 비율만큼 보조색 */
    private inner class Bar(private val color: Int, private val extraColor: Int) : View(this@StatusPanel.context) {
        private var main = 0f
        private var extra = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        fun set(main: Float, extra: Float) {
            val m = main.coerceIn(0f, 1f)
            val e = extra.coerceIn(0f, 1f - m)
            if (m == this.main && e == this.extra) return
            this.main = m
            this.extra = e
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            // 픽셀 디자인은 각진 막대
            val r = if (p.radius == 0f) 0f else height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            // 빈 부분: 카드 배경과 구분되는 색 (흰 카드에서 막대가 안 보이던 문제)
            paint.color = p.input
            canvas.drawRoundRect(rect, r, r, paint)
            if (main > 0f) {
                rect.set(0f, 0f, max(height.toFloat(), width * main), height.toFloat())
                paint.color = color
                canvas.drawRoundRect(rect, r, r, paint)
            }
            if (extra > 0f) {
                rect.set(width * main, 0f, width * (main + extra), height.toFloat())
                paint.color = extraColor
                canvas.drawRoundRect(rect, r, r, paint)
            }
            // 테두리: 막대 전체 길이가 보이게
            val sw = ui.dp(if (p.stroke != 0) 2 else 1).toFloat().coerceAtLeast(1.5f)
            border.strokeWidth = sw
            border.color = if (p.stroke != 0) p.stroke else p.muted
            rect.set(sw / 2, sw / 2, width - sw / 2, height - sw / 2)
            val rr = (r - sw / 2).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, rr, rr, border)
        }
    }

    companion object {
        private const val COLOR_ABSORB = 0xFFF5C518.toInt()
        private const val COLOR_FOOD = 0xFFD9822B.toInt()

        /** 1234567 → "1.2MB" */
        fun bytes(b: Long): String = when {
            b >= 1L shl 30 -> String.format(Locale.ROOT, "%.2fGB", b / 1073741824.0)
            b >= 1L shl 20 -> String.format(Locale.ROOT, "%.1fMB", b / 1048576.0)
            b >= 1L shl 10 -> String.format(Locale.ROOT, "%.0fKB", b / 1024.0)
            else -> "${b}B"
        }

        /** 소수점이 없으면 정수로 (20.0 → 20, 7.5 → 7.5) */
        fun num(f: Float): String {
            val r = Math.round(f * 10f) / 10f
            return if (r == r.toLong().toFloat()) r.toLong().toString() else String.format(Locale.ROOT, "%.1f", r)
        }

        /** 오늘이면 "14:32:05", 아니면 "11/19 14:32" */
        fun timeOf(ms: Long): String {
            val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
            val today = day.format(Date()) == day.format(Date(ms))
            return SimpleDateFormat(if (today) "HH:mm:ss" else "MM/dd HH:mm", Locale.ROOT).format(Date(ms))
        }
    }
}
