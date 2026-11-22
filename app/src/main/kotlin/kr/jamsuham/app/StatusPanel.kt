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
        setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        elevation = ui.dp(8).toFloat()

        val inner = ui.vertical()
        emptyView = ui.text("접속하면 체력과 배고픔이 보입니다", 14f, p.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(12)) }
        inner.addView(emptyView)

        body = ui.vertical()
        body.addView(ui.text("체력", 13f, p.muted))
        healthText = ui.text("", 20f, bold = true)
        body.addView(healthText)
        body.addView(healthBar, ui.lp(Ui.MATCH, ui.dp(16)).apply { topMargin = ui.dp(6) })
        healthSub = ui.text("", 12f, p.muted).apply { setPadding(0, ui.dp(4), 0, 0) }
        body.addView(healthSub)

        body.addView(ui.text("배고픔", 13f, p.muted).apply { setPadding(0, ui.dp(16), 0, 0) })
        foodText = ui.text("", 20f, bold = true)
        body.addView(foodText)
        body.addView(foodBar, ui.lp(Ui.MATCH, ui.dp(16)).apply { topMargin = ui.dp(6) })
        foodSub = ui.text("", 12f, p.muted).apply { setPadding(0, ui.dp(4), 0, 0) }
        body.addView(foodSub)
        inner.addView(body)

        inner.addView(ui.text("데이터 사용량", 13f, p.muted).apply { setPadding(0, ui.dp(20), 0, 0) })
        dataText = ui.text("", 15f, bold = true)
        inner.addView(dataText)
        dataSub = ui.text("", 12f, p.muted).apply { setPadding(0, ui.dp(2), 0, 0) }
        inner.addView(dataSub)

        inner.addView(ui.text("리소스팩 그림 글자 (칭호·아이콘)", 13f, p.muted).apply { setPadding(0, ui.dp(20), 0, 0) })
        packText = ui.text("", 15f, bold = true)
        inner.addView(packText)
        packSub = ui.text("", 12f, p.muted).apply {
            setPadding(0, ui.dp(2), 0, 0)
            setTextIsSelectable(true) // 리소스팩 주소를 복사해 문의할 수 있게
        }
        inner.addView(packSub)

        inner.addView(ui.text("마지막 사망", 13f, p.muted).apply { setPadding(0, ui.dp(20), 0, 0) })
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
            dataText.setTextIfChanged("아직 없습니다")
            dataSub.setTextIfChanged("")
        } else {
            dataText.setTextIfChanged("받음 ${bytes(traffic.received)} · 보냄 ${bytes(traffic.sent)}")
            dataSub.setTextIfChanged(
                (if (rate != null) "시간당 약 ${bytes(rate)} (알림과 같은 값, 1분마다 갱신)" else "1분 넘게 접속하면 시간당 사용량이 보입니다") +
                    "\n이번에 잠수를 시작한 뒤 합계 · 마인크래프트 데이터 기준이라 통신사 집계는 조금 더 많을 수 있습니다",
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
                (if (v.dead) "사망 · " else "") + "${num(hp)} / ${num(max)}" + (if (v.absorption > 0f) "  (+${num(v.absorption)})" else ""),
            )
            // 흡수 체력이 있으면 막대 전체 길이를 최대 체력 + 흡수로 잡고 뒤에 노란색으로 붙인다
            val total = max + v.absorption
            healthBar.set(hp / total, v.absorption / total)
            healthSub.setTextIfChanged(
                "하트 ${num(hp / 2f)} / ${num(max / 2f)}칸" +
                    (if (max != 20f) " · 최대 체력이 기본(20)과 다른 서버입니다" else "") +
                    (if (v.absorption > 0f) " · 흡수 ${num(v.absorption / 2f)}칸" else ""),
            )
            foodText.setTextIfChanged("${v.food} / 20")
            foodBar.set(v.food / 20f, 0f)
            foodSub.setTextIfChanged(
                "포화 ${num(v.saturation)}" + when {
                    v.food == 0 -> " · 굶주려서 체력이 줄어듭니다"
                    v.food <= 6 -> " · 배가 고파서 달리기를 할 수 없습니다"
                    v.food < 18 -> " · 18 이상이면 체력이 저절로 찹니다"
                    else -> ""
                },
            )
        }
        if (death == null) {
            deathTitle.setTextIfChanged("아직 없습니다")
            deathText.visibility = GONE
        } else {
            deathTitle.setTextIfChanged("☠ " + timeOf(death.atMs))
            deathText.visibility = VISIBLE
            deathText.setTextIfChanged(death.describe())
        }
    }

    private fun showPacks(packs: List<SessionHub.PackState>) {
        if (packs.isEmpty()) {
            packText.setTextIfChanged("서버가 리소스팩을 보내지 않았습니다")
            packSub.setTextIfChanged("리소스팩이 없는 서버는 칭호가 글자 그대로 보입니다")
            return
        }
        val total = packs.sumOf { it.glyphs ?: 0 }
        packText.setTextIfChanged(
            when {
                packs.all { it.skipped } -> "꺼져 있음 (서버 설정에서 켤 수 있습니다)"
                packs.any { it.glyphs == null && it.error == null && !it.skipped } -> "받는 중…"
                total > 0 -> "그림 글자 ${total}개 사용 중"
                packs.any { it.error != null } -> "불러오지 못함"
                else -> "리소스팩에 그림 글자가 없습니다"
            },
        )
        packSub.setTextIfChanged(
            packs.joinToString("\n") { s ->
                val state = when {
                    s.skipped -> "꺼짐"
                    s.error != null -> "실패: ${s.error}"
                    s.glyphs == null -> "받는 중"
                    else -> "글자 ${s.glyphs}개 · 글꼴 ${s.fonts}개"
                }
                "• $state\n  ${s.url}" + (s.report?.takeIf { s.glyphs == 0 }?.let { "\n  [팩 안 모습]\n  " + it.replace("\n", "\n  ") } ?: "")
            },
        )
    }

    /** 한 줄짜리 막대: [main] 비율만큼 주색, 이어서 [extra] 비율만큼 보조색 */
    private inner class Bar(private val color: Int, private val extraColor: Int) : View(this@StatusPanel.context) {
        private var main = 0f
        private var extra = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = p.divider
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
