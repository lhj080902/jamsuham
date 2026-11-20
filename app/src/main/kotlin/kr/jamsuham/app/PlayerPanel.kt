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

    init {
        orientation = VERTICAL
        background = ui.rounded(p.card, 16)
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        elevation = ui.dp(8).toFloat()

        countView = ui.text("접속자", 16f, p.accent, bold = true)
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
        countView.text = "접속자 ${players.size}명"
        setSpans(headerView, data.header)
        setSpans(footerView, data.footer)
        headerView.visibility = if (data.header.plain.isBlank()) GONE else VISIBLE
        footerView.visibility = if (data.footer.plain.isBlank()) GONE else VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun setSpans(tv: TextView, text: ChatText) {
        tv.text = spannable(text, p.text)
    }

    private fun spannable(text: ChatText, base: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (s in text.spans) {
            val start = sb.length
            sb.append(s.text)
            sb.setSpan(ForegroundColorSpan(s.color?.let { p.readable(it) } ?: base), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
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
                addView(ui.marquee(ui.text("", 15f)).apply { id = ID_NAME }, ui.lp(0, Ui.WRAP, 1f))
                addView(ui.text("", 11f, p.muted).apply { id = ID_PING }, ui.lp(Ui.WRAP, Ui.WRAP))
            }
            val player = players[position]
            Faces.load(activity, row.findViewById(ID_FACE), player.skinUrl, player.name)
            row.findViewById<TextView>(ID_NAME).let { if (it.text.toString() != player.shownName.plain) it.text = spannable(player.shownName, p.text) }
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
    }
}
