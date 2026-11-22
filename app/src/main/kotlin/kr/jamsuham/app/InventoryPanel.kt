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
        setPadding(ui.dp(14) + ui.cardPad, ui.dp(12) + ui.cardPad, ui.dp(14) + ui.cardPad, ui.dp(12) + ui.cardPad)
        elevation = ui.dp(8).toFloat()
        countView = ui.text(tr("인벤토리", "Inventory"), 16f, p.accent, bold = true)
        addView(countView)
        addView(ui.hint(tr("아이템을 누르면 설명과 [사용] 버튼이 나옵니다. 사용 = 손에 들고 우클릭 한 번 (가방 아이템은 지금 든 핫바 칸과 바뀝니다).", "Tap an item for details and a [Use] button. Use = hold it and right-click once (backpack items swap with the selected hotbar slot).")))
        box = ui.vertical()
        scrollView = ScrollView(activity).apply { addView(box) }
        addView(scrollView, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(6) })
        show(Inventory.EMPTY)
    }

    fun show(inv: Inventory) {
        if (inv == shown) return
        shown = inv
        val bySlot = inv.slots.associateBy { it.slot }
        countView.text = tr("인벤토리 · ${inv.slots.count { it.slot in 5..45 }}칸 사용 중", "Inventory · ${inv.slots.count { it.slot in 5..45 }} slots used")
        box.removeAllViews()
        if (inv.slots.none { it.slot in 5..45 }) {
            box.addView(ui.text(tr("비어 있거나 아직 서버에서 받지 못했습니다", "Empty, or not received from the server yet"), 14f, p.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
            return
        }
        val heldSlot = 36 + inv.selectedHotbar
        section(tr("손에 든 것", "In hand"), listOf(heldSlot), bySlot, heldSlot) { tr("${inv.selectedHotbar + 1}번", "#${inv.selectedHotbar + 1}") }
        section(tr("핫바", "Hotbar"), (36..44).toList(), bySlot, heldSlot) { "${it - 35}" }
        section(tr("갑옷 · 왼손", "Armor · Offhand"), listOf(5, 6, 7, 8, 45), bySlot, heldSlot) {
            when (it) { 5 -> tr("머리", "Head"); 6 -> tr("몸통", "Chest"); 7 -> tr("다리", "Legs"); 8 -> tr("발", "Feet"); else -> tr("왼손", "Offhand") }
        }
        section(tr("가방", "Backpack"), (9..35).toList(), bySlot, heldSlot) { "${it - 8}" }
    }

    private fun section(title: String, slots: List<Int>, bySlot: Map<Int, MenuItem>, heldSlot: Int, label: (Int) -> String) {
        val items = slots.mapNotNull { bySlot[it] }
        if (items.isEmpty()) {
            if (title == tr("손에 든 것", "In hand")) {
                box.addView(ui.text(title, 13f, p.muted, bold = true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
                box.addView(ui.text(tr("빈손", "Empty hand"), 14f, p.muted).apply { setPadding(ui.dp(12), ui.dp(4), 0, ui.dp(4)) })
            }
            return
        }
        box.addView(ui.text(title, 13f, p.muted, bold = true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
        for (item in items) box.addView(row(item, label(item.slot), item.slot == heldSlot && title != tr("손에 든 것", "In hand")), ui.lp().apply { topMargin = ui.dp(4) })
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
            details.addView(ui.button(tr("사용 (우클릭)", "Use (right-click)"), "primary") {
                if (SessionHub.useItem(serverId, item.slot)) {
                    SessionHub.note(serverId, tr("아이템 사용: ${item.name.plain}", "Used item: ${item.name.plain}"))
                    android.widget.Toast.makeText(activity, tr("'${item.name.plain}' 사용", "Used '${item.name.plain}'"), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(activity, tr("접속 중일 때만 쓸 수 있습니다", "Only available while connected"), android.widget.Toast.LENGTH_SHORT).show()
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

    private fun spans(t: ChatText): CharSequence =
        Glyphs.build(t, { s -> s.color?.let { p.readable(it) } ?: p.text }, ui.dp(14).toFloat(), SessionHub.glyphs(serverId))
}
