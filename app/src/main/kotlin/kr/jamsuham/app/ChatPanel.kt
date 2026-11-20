package kr.jamsuham.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 서버 하나의 채팅 화면: 상단 상태 줄(접속/끊기), 채팅 기록, 명령어 자동완성 칩, ▲▼ 이전 명령어, 입력창.
 * ChatActivity(폰)와 MainActivity 오른쪽 칸(태블릿)에서 같이 쓴다.
 */
class ChatPanel(
    private val activity: Activity,
    private val ui: Ui,
    val serverId: String,
    private val showBack: Boolean,
    private val onBack: () -> Unit,
    private val onEdit: () -> Unit,
) : LinearLayout(activity), SessionHub.Observer {

    private val store = Store(activity)
    private val p = ui.p
    private val main = Handler(Looper.getMainLooper())

    private val statusDot: View
    private val titleView: TextView
    private val detailView: TextView
    private val actionButton: Button
    private val list: ListView
    private val chipScroll: HorizontalScrollView
    private val chipRow: LinearLayout
    private val input: EditText
    private val adapter = LineAdapter()

    private var historyIndex = -1 // -1 = 입력 중인 새 글
    private var draft = ""
    private var suggestSeq = 0
    private val suggestTask = Runnable { requestSuggestions() }

    private val ticker = object : Runnable {
        override fun run() {
            render(SessionHub.status(serverId))
            main.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL

        // --- 상단 상태 줄
        val top = ui.card().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        val row = ui.horizontal()
        if (showBack) row.addView(ui.button("←", "ghost") { onBack() }, ui.lp(ui.dp(44), ui.dp(40)))
        statusDot = ui.dot(p.muted)
        row.addView(statusDot, ui.lp(ui.dp(10), ui.dp(10)).apply { rightMargin = ui.dp(8); leftMargin = ui.dp(4) })
        val titles = ui.vertical()
        titleView = ui.text("", 17f, bold = true).apply { setSingleLine(true) }
        detailView = ui.text("", 12f, p.muted).apply { setSingleLine(true) }
        titles.addView(titleView)
        titles.addView(detailView)
        row.addView(titles, ui.lp(0, Ui.WRAP, 1f))
        row.addView(ui.button("⚙", "ghost") { onEdit() }, ui.lp(ui.dp(44), ui.dp(40)))
        actionButton = ui.button("잠수 시작", "primary") { toggle() }
        row.addView(actionButton, ui.lp(Ui.WRAP, ui.dp(40)).apply { leftMargin = ui.dp(4) })
        top.addView(row)
        addView(top, ui.lp())

        // --- 채팅 기록
        val logCard = ui.card().apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) }
        list = ListView(activity).apply {
            adapter = this@ChatPanel.adapter
            divider = null
            isStackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            setBackgroundColor(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
        }
        logCard.addView(list, ui.lp(Ui.MATCH, Ui.MATCH))
        addView(logCard, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(8) })

        // --- 자동완성 칩
        chipRow = ui.horizontal()
        chipScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
            visibility = GONE
        }
        addView(chipScroll, ui.lp().apply { topMargin = ui.dp(6) })

        // --- 입력 줄: ▲ ▼ [입력] [전송]
        val inputRow = ui.horizontal()
        inputRow.addView(ui.button("▲", "plain") { historyMove(+1) }, ui.lp(ui.dp(42), ui.dp(46)))
        inputRow.addView(ui.button("▼", "plain") { historyMove(-1) }, ui.lp(ui.dp(42), ui.dp(46)).apply { leftMargin = ui.dp(4) })
        input = ui.input("채팅 또는 /명령어").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    send(); true
                } else false
            }
            // 블루투스 키보드: 방향키 위/아래 = 이전 명령어, Tab = 첫 번째 후보
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { historyMove(+1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { historyMove(-1); true }
                    KeyEvent.KEYCODE_TAB -> { (chipRow.getChildAt(0) as? TextView)?.performClick(); true }
                    else -> false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    main.removeCallbacks(suggestTask)
                    val t = s?.toString() ?: ""
                    if (t.startsWith("/")) main.postDelayed(suggestTask, 250) else hideChips()
                }
            })
        }
        inputRow.addView(input, ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(6) })
        inputRow.addView(ui.button("전송", "primary") { send() }, ui.lp(Ui.WRAP, ui.dp(46)).apply { leftMargin = ui.dp(6) })
        addView(inputRow, ui.lp().apply { topMargin = ui.dp(6) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SessionHub.addObserver(this)
        adapter.reload()
        render(SessionHub.status(serverId))
        main.post(ticker)
    }

    override fun onDetachedFromWindow() {
        SessionHub.removeObserver(this)
        main.removeCallbacks(ticker)
        main.removeCallbacks(suggestTask)
        super.onDetachedFromWindow()
    }

    override fun onStatus(serverId: String, status: SessionStatus) {
        if (serverId == this.serverId) render(status)
    }

    override fun onLine(serverId: String, line: LogLine) {
        if (serverId == this.serverId) adapter.add(line)
    }

    fun refreshTitle() = render(SessionHub.status(serverId))

    private fun render(s: SessionStatus) {
        val server = store.server(serverId)
        titleView.text = server?.displayName ?: "삭제된 서버"
        val (label, color) = stateLabel(s.state)
        (statusDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        val now = System.currentTimeMillis()
        val parts = ArrayList<String>()
        parts.add(label)
        when (s.state) {
            SessionState.ONLINE -> s.onlineSinceMs?.let { parts.add(Formats.clock(now - it)) }
            SessionState.WAITING_RECONNECT -> parts.add("${(((s.nextAttemptAtMs ?: now) - now).coerceAtLeast(0) / 1000)}초 뒤 재접속")
            SessionState.PREPARING, SessionState.CONNECTING -> if (s.message.isNotBlank()) parts.add(s.message)
            else -> {}
        }
        if (s.reconnectCount > 0) parts.add("재접속 ${s.reconnectCount}회")
        s.serverVersion?.let { parts.add(it) }
        detailView.text = parts.joinToString(" · ")
        val active = s.state.isActive()
        actionButton.text = if (active) "끊기" else "잠수 시작"
        ui.style(actionButton, if (active) "danger" else "primary")
    }

    private fun stateLabel(st: SessionState) = when (st) {
        SessionState.ONLINE -> "잠수 중" to p.ok
        SessionState.WAITING_RECONNECT -> "재접속 대기" to p.warn
        SessionState.PREPARING, SessionState.CONNECTING -> "접속 중" to p.warn
        else -> "꺼짐" to p.muted
    }

    private fun toggle() {
        if (SessionHub.isActive(serverId)) {
            AfkService.stop(activity, serverId)
        } else {
            (activity as? Starter)?.startServer(serverId)
        }
    }

    // ------------------------------------------------------------ 보내기 / 기록

    private fun send() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty()) return
        if (SessionHub.status(serverId).state != SessionState.ONLINE) {
            Toast.makeText(activity, "서버에 접속한 뒤에 보낼 수 있습니다", Toast.LENGTH_SHORT).show()
            return
        }
        if (SessionHub.sendChat(serverId, msg)) {
            store.addHistory(serverId, msg)
            SessionHub.note(serverId, "나: $msg")
            historyIndex = -1
            draft = ""
            input.setText("")
            hideChips()
        } else {
            Toast.makeText(activity, "보내지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /** dir = +1 이전(위), -1 다음(아래) */
    private fun historyMove(dir: Int) {
        val history = store.history(serverId)
        if (history.isEmpty()) return
        if (historyIndex == -1) draft = input.text.toString()
        val next = (historyIndex + dir).coerceIn(-1, history.size - 1)
        if (next == historyIndex) return
        historyIndex = next
        val text = if (next == -1) draft else history[history.size - 1 - next]
        input.setText(text)
        input.setSelection(text.length)
    }

    // ------------------------------------------------------------ 자동완성

    private fun requestSuggestions() {
        val text = input.text.toString()
        if (!text.startsWith("/") || SessionHub.status(serverId).state != SessionState.ONLINE) {
            hideChips()
            return
        }
        val seq = ++suggestSeq
        SessionHub.requestSuggestions(serverId, text) { result ->
            if (seq != suggestSeq || input.text.toString() != text) return@requestSuggestions
            if (result == null || result.matches.isEmpty()) {
                hideChips()
                return@requestSuggestions
            }
            chipRow.removeAllViews()
            result.matches.take(40).forEach { m ->
                val chip = ui.chip(m) {
                    val applied = result.apply(text, m)
                    input.setText(applied)
                    input.setSelection(applied.length)
                }
                chipRow.addView(chip, ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(6) })
            }
            chipScroll.visibility = VISIBLE
            chipScroll.scrollTo(0, 0)
        }
    }

    private fun hideChips() {
        chipRow.removeAllViews()
        chipScroll.visibility = GONE
    }

    // ------------------------------------------------------------ 목록

    private inner class LineAdapter : BaseAdapter() {
        private val items = ArrayList<LogLine>()
        private val time = SimpleDateFormat("HH:mm", Locale.KOREA)

        fun reload() {
            items.clear()
            items.addAll(SessionHub.lines(serverId))
            notifyDataSetChanged()
        }

        fun add(line: LogLine) {
            items.add(line)
            if (items.size > 500) items.removeAt(0)
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(ui.dp(2), ui.dp(3), ui.dp(2), ui.dp(3))
            }
            val line = items[position]
            val sb = SpannableStringBuilder()
            append(sb, time.format(Date(line.timeMs)) + "  ", p.muted, false)
            val base = when (line.kind) {
                LogLine.Kind.CHAT -> p.text
                LogLine.Kind.SYSTEM -> p.info
                LogLine.Kind.ERROR -> p.err
            }
            for (span in line.text.spans) {
                val c = if (line.kind == LogLine.Kind.CHAT && span.color != null) p.readable(span.color!!) else base
                append(sb, span.text, c, span.bold)
            }
            tv.text = sb
            return tv
        }

        private fun append(sb: SpannableStringBuilder, s: String, color: Int, bold: Boolean) {
            val start = sb.length
            sb.append(s)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/** 서버 시작은 권한 확인 등 액티비티 쪽 일이 있어서 액티비티가 구현한다 */
interface Starter {
    fun startServer(serverId: String): Boolean
}
