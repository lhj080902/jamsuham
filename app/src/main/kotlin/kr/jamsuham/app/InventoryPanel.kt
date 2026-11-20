package kr.jamsuham.app

import android.app.Activity
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.jamsuham.core.Inventory
import kr.jamsuham.core.MenuItem
import kr.jamsuham.core.text.ChatText

/** 채팅 화면의 "인벤" 페이지: 내 인벤토리 보기 + 아이템 우클릭 사용 (버리기·옮기기 없음) */
class InventoryPanel(private val activity: Activity, private val ui: Ui, private val serverId: String) : LinearLayout(activity) {

    private val p = ui.p
    private val countView: TextView
    private val box: LinearLayout
    val scrollView: ScrollView
    private var shown: Inventory? = null
    private val expanded = HashSet<Int>()

    init {
        orientation = VERTICAL
        background = ui.rounded(p.card, 16)
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        elevation = ui.dp(8).toFloat()
        countView = ui.text("인벤토리", 16f, p.accent, bold = true)
        addView(countView)
        addView(ui.hint("아이템을 누르면 설명과 [사용] 버튼이 나옵니다. 사용 = 손에 들고 우클릭 한 번 (가방 아이템은 지금 든 핫바 칸과 바뀝니다)."))
        box = ui.vertical()
        scrollView = ScrollView(activity).apply { addView(box) }
        addView(scrollView, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(6) })
        show(Inventory.EMPTY)
    }

    fun show(inv: Inventory) {
        if (inv == shown) return
        shown = inv
        val bySlot = inv.slots.associateBy { it.slot }
        countView.text = "인벤토리 · ${inv.slots.count { it.slot in 5..45 }}칸 사용 중"
        box.removeAllViews()
        if (inv.slots.none { it.slot in 5..45 }) {
            box.addView(ui.text("비어 있거나 아직 서버에서 받지 못했습니다", 14f, p.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
            return
        }
        val heldSlot = 36 + inv.selectedHotbar
        section("손에 든 것", listOf(heldSlot), bySlot, heldSlot) { "${inv.selectedHotbar + 1}번" }
        section("핫바", (36..44).toList(), bySlot, heldSlot) { "${it - 35}" }
        section("갑옷 · 왼손", listOf(5, 6, 7, 8, 45), bySlot, heldSlot) {
            when (it) { 5 -> "머리"; 6 -> "몸통"; 7 -> "다리"; 8 -> "발"; else -> "왼손" }
        }
        section("가방", (9..35).toList(), bySlot, heldSlot) { "${it - 8}" }
    }

    private fun section(title: String, slots: List<Int>, bySlot: Map<Int, MenuItem>, heldSlot: Int, label: (Int) -> String) {
        val items = slots.mapNotNull { bySlot[it] }
        if (items.isEmpty()) {
            if (title == "손에 든 것") {
                box.addView(ui.text(title, 13f, p.muted, bold = true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
                box.addView(ui.text("빈손", 14f, p.muted).apply { setPadding(ui.dp(12), ui.dp(4), 0, ui.dp(4)) })
            }
            return
        }
        box.addView(ui.text(title, 13f, p.muted, bold = true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
        for (item in items) box.addView(row(item, label(item.slot), item.slot == heldSlot && title != "손에 든 것"), ui.lp().apply { topMargin = ui.dp(4) })
    }

    private fun row(item: MenuItem, label: String, held: Boolean): LinearLayout {
        val row = ui.vertical().apply {
            background = ui.rounded(p.input, 10)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        val head = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.text(label, 12f, if (held) p.accent else p.muted, bold = held), ui.lp(ui.dp(44), Ui.WRAP))
        head.addView(ui.marquee(ui.text(spans(item.name), 15f, bold = true)), ui.lp(0, Ui.WRAP, 1f))
        if (item.count > 1) head.addView(ui.text("x${item.count}", 13f, p.muted).apply { setPadding(ui.dp(8), 0, 0, 0) })
        row.addView(head)
        val details = ui.vertical().apply { setPadding(ui.dp(44), ui.dp(2), 0, 0) }
        for (l in item.lore.take(12)) {
            if (l.plain.isBlank()) continue
            details.addView(ui.text(spans(l), 12f, p.muted))
        }
        details.addView(ui.text(item.itemId, 11f, p.muted))
        if (item.slot in 9..45) {
            details.addView(ui.button("사용 (우클릭)", "primary") {
                if (SessionHub.useItem(serverId, item.slot)) {
                    SessionHub.note(serverId, "아이템 사용: ${item.name.plain}")
                    android.widget.Toast.makeText(activity, "'${item.name.plain}' 사용", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(activity, "접속 중일 때만 쓸 수 있습니다", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, ui.lp(Ui.WRAP, ui.dp(38)).apply { topMargin = ui.dp(6) })
        }
        details.visibility = if (item.slot in expanded) VISIBLE else GONE
        row.addView(details)
        row.isClickable = true
        row.setOnClickListener {
            if (!expanded.remove(item.slot)) expanded.add(item.slot)
            details.visibility = if (item.slot in expanded) VISIBLE else GONE
        }
        return row
    }

    private fun spans(t: ChatText): CharSequence {
        val sb = SpannableStringBuilder()
        for (s in t.spans) {
            val start = sb.length
            sb.append(s.text)
            sb.setSpan(ForegroundColorSpan(s.color?.let { p.readable(it) } ?: p.text), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
