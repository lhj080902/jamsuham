package kr.jamsuham.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kr.jamsuham.core.PlayerInfo
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.text.ChatText

/** 채팅 화면에서 옆으로 밀면 나오는 접속자 목록 (탭 목록과 같음) */
class PlayerPanel(private val activity: Activity, private val ui: Ui) : LinearLayout(activity) {

    private val p = ui.p
    private val headerView: TextView
    private val countView: TextView
    private val footerView: TextView
    private val list: ListView
    private val adapter = Adapter()
    private var players: List<PlayerInfo> = emptyList()

    /** 이 서버에서 내 닉네임. 내 줄에는 차단·신고 버튼을 두지 않는다 (이름으로 견준다 — 서버가 알려 주는 UUID 는 로그인 UUID 와 다를 수 있다) */
    var selfName: String? = null
    var onBlock: (PlayerInfo) -> Unit = {}
    var onUnblock: (PlayerInfo) -> Unit = {}
    var onReport: (PlayerInfo) -> Unit = {}

    init {
        orientation = VERTICAL
        background = ui.rounded(p.card, 16)
        setPadding(ui.dp(14) + ui.cardPad, ui.dp(12) + ui.cardPad, ui.dp(14) + ui.cardPad, ui.dp(12) + ui.cardPad)
        elevation = ui.dp(8).toFloat()

        countView = ui.text(tr("접속자", "Players"), 16f, p.accent, bold = true)
        addView(countView)
        headerView = ui.text("", 13f).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        addView(headerView, ui.lp())
        list = ListView(activity).apply {
            adapter = this@PlayerPanel.adapter
            divider = null
            setBackgroundColor(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
        }
        addView(list, ui.lp(Ui.MATCH, 0, 1f))
        footerView = ui.text("", 12f).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ui.dp(6), 0, 0) }
        addView(footerView, ui.lp())
    }

    val listView: ListView get() = list

    fun show(data: PlayerList) {
        players = data.players
        countView.text = tr("접속자 ${players.size}명", "${players.size} players")
        setSpans(headerView, data.header)
        setSpans(footerView, data.footer)
        headerView.visibility = if (data.header.plain.isBlank()) GONE else VISIBLE
        footerView.visibility = if (data.footer.plain.isBlank()) GONE else VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun setSpans(tv: TextView, text: ChatText) {
        tv.text = spannable(text, p.text)
    }

    /** 서버 리소스팩 그림 글자 (칭호 아이콘 등). ChatPanel 이 넣어 준다 */
    var glyphs: kr.jamsuham.core.pack.PackFonts.FontSet? = null

    /** [flat] 이면 서버가 준 색을 무시하고 [base] 한 가지 색으로 (차단한 사람은 흐리게) */
    private fun spannable(text: ChatText, base: Int, textPx: Float = ui.dp(14).toFloat(), flat: Boolean = false): SpannableStringBuilder =
        Glyphs.build(text, { s -> if (flat) base else s.color?.let { p.readable(it) } ?: base }, textPx, glyphs)

    /** 줄 끝의 작은 버튼 (차단·신고) */
    private fun smallButton(viewId: Int) = ui.button("", "plain") {}.apply {
        id = viewId
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f * ui.scale)
        setPadding(ui.dp(10), 0, ui.dp(10), 0)
        minWidth = 0
        minimumWidth = 0
        isFocusable = false
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = players.size
        override fun getItem(position: Int) = players[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: ui.horizontal().apply {
                setPadding(0, ui.dp(5), 0, ui.dp(5))
                val img = ImageView(activity).apply {
                    id = ID_FACE
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(img, ui.lp(ui.dp(32), ui.dp(32)).apply { rightMargin = ui.dp(10) })
                // 이름이 길면(칭호 등) 끝까지 갔다가 되돌아오는 왕복형으로 보여 준다
                addView(ui.pingPong(ui.text("", 15f).apply { id = ID_NAME }), ui.lp(0, Ui.WRAP, 1f))
                addView(ui.text("", 11f, p.muted).apply { id = ID_PING }, ui.lp(Ui.WRAP, Ui.WRAP).apply { leftMargin = ui.dp(6) })
                addView(smallButton(ID_BLOCK), ui.lp(Ui.WRAP, ui.dp(32)).apply { leftMargin = ui.dp(8) })
                addView(smallButton(ID_REPORT), ui.lp(Ui.WRAP, ui.dp(32)).apply { leftMargin = ui.dp(4) })
            }
            val player = players[position]
            val self = selfName?.let { it.equals(player.name, ignoreCase = true) } == true
            val blocked = !self && BlockList.isBlocked(player.name)
            row.findViewById<ImageView>(ID_FACE).alpha = if (blocked) 0.4f else 1f
            Faces.load(activity, row.findViewById(ID_FACE), player.skinUrl, player.name)
            row.findViewById<TextView>(ID_NAME).let {
                val shown = player.shownName.plain + if (blocked) "\u0000" else ""
                if (it.tag != shown) {
                    it.tag = shown
                    it.text = if (blocked) spannable(player.shownName, p.muted, flat = true) else spannable(player.shownName, p.text)
                }
            }
            // 차단·신고: 내 줄에는 없고, 차단한 사람은 [차단 해제] 하나만
            val block = row.findViewById<android.widget.Button>(ID_BLOCK)
            val report = row.findViewById<android.widget.Button>(ID_REPORT)
            block.visibility = if (self) GONE else VISIBLE
            report.visibility = if (self || blocked) GONE else VISIBLE
            block.text = if (blocked) tr("차단 해제", "Unblock") else tr("차단", "Block")
            block.setOnClickListener { if (blocked) onUnblock(player) else onBlock(player) }
            report.text = tr("신고", "Report")
            report.setOnClickListener { onReport(player) }
            val ping = row.findViewById<TextView>(ID_PING)
            ping.text = if (player.latencyMs > 0) "${player.latencyMs}ms" else ""
            ping.setTextColor(
                when {
                    player.latencyMs <= 0 -> p.muted
                    player.latencyMs < 150 -> p.ok
                    player.latencyMs < 400 -> p.warn
                    else -> p.err
                },
            )
            return row
        }
    }

    companion object {
        private val ID_FACE = View.generateViewId()
        private val ID_NAME = View.generateViewId()
        private val ID_PING = View.generateViewId()
        private val ID_BLOCK = View.generateViewId()
        private val ID_REPORT = View.generateViewId()
    }
}
