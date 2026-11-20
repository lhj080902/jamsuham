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
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kr.jamsuham.core.DeathInfo
import kr.jamsuham.core.Inventory
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.Menu
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.Vitals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
    private val clockView: TextView
    private val menuBanner: LinearLayout
    private val menuBannerText: TextView
    private var menuDialog: android.app.AlertDialog? = null
    private var menuDialogBox: LinearLayout? = null
    private var lastMenuWindow = -1
    private val actionButton: Button
    private val playerPanel: PlayerPanel
    private val mapPage: MapPanel
    private val invPanel: InventoryPanel
    private val statusPanel: StatusPanel
    private val deathBanner: LinearLayout
    private val deathBannerText: TextView
    /** 메뉴 알림을 눌러 들어왔으면 화면이 뜨자마자 메뉴 창을 연다 */
    var openMenuOnAttach = false
    private var backDialog: android.app.AlertDialog? = null
    private val chatPage: LinearLayout
    private val pages: FrameLayout
    private var tabs: List<Button> = emptyList()
    private var page = 0
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
        if (showBack) row.addView(ui.button("←", "ghost") { requestBack() }, ui.lp(ui.dp(44), ui.dp(40)))
        statusDot = ui.dot(p.muted)
        row.addView(statusDot, ui.lp(ui.dp(10), ui.dp(10)).apply { rightMargin = ui.dp(8); leftMargin = ui.dp(4) })
        val titles = ui.vertical()
        titleView = ui.marquee(ui.text("", 17f, bold = true))
        clockView = ui.text("", 12f, p.muted).apply { setSingleLine(true) }
        detailView = ui.marquee(ui.text("", 12f, p.muted))
        titles.addView(titleView)
        val detailRow = ui.horizontal()
        detailRow.addView(clockView, ui.lp(Ui.WRAP, Ui.WRAP))
        detailRow.addView(detailView, ui.lp(0, Ui.WRAP, 1f))
        titles.addView(detailRow)
        row.addView(titles, ui.lp(0, Ui.WRAP, 1f))
        row.addView(ui.button("💾", "ghost") { (activity as? BaseActivity)?.exportChatLog(serverId) }, ui.lp(ui.dp(44), ui.dp(40)))
        row.addView(ui.button("⚙", "ghost") { onEdit() }, ui.lp(ui.dp(44), ui.dp(40)))
        // 접속 중에는 버튼을 숨겨 제목·상태 글씨 자리를 넓힌다 (끊기는 ← 버튼에서)
        actionButton = ui.button("접속", "primary") { toggle() }
        row.addView(actionButton, ui.lp(Ui.WRAP, ui.dp(40)).apply { leftMargin = ui.dp(4) })
        top.addView(row)
        addView(top, ui.lp())

        // --- 사망 알림 줄: 언제·누구에 의해·무엇으로 죽었는지 (✕ 로 닫기)
        deathBanner = ui.horizontal().apply {
            background = ui.rounded(if (p.dark) 0xFF3A1E22.toInt() else 0xFFFFE3E3.toInt(), 14)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(6), ui.dp(8))
            visibility = GONE
            setOnClickListener { showPage(4) }
        }
        deathBannerText = ui.text("", 13f, p.text).apply { maxLines = 3 }
        deathBanner.addView(deathBannerText, ui.lp(0, Ui.WRAP, 1f))
        deathBanner.addView(ui.button("✕", "ghost") { SessionHub.clearDeath(serverId) }, ui.lp(ui.dp(40), ui.dp(36)))
        addView(deathBanner, ui.lp().apply { topMargin = ui.dp(8) })

        // --- 페이지 탭: 채팅 / 지도 / 인벤 / 접속자 (좌우로 밀어서 넘김)
        val tabBar = ui.horizontal()
        tabs = listOf("채팅", "지도", "인벤", "접속자", "상태").mapIndexed { i, label ->
            ui.button(label, "plain") { showPage(i) }.also { b ->
                b.setPadding(ui.dp(2), 0, ui.dp(2), 0)
                b.setSingleLine(true)
                tabBar.addView(b, ui.lp(0, ui.dp(40), 1f).apply { if (i > 0) leftMargin = ui.dp(4) })
            }
        }
        addView(tabBar, ui.lp().apply { topMargin = ui.dp(8) })

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
        chatPage = ui.vertical()
        // 서버가 메뉴 창을 띄우면 채팅 위에 알림 줄
        menuBanner = ui.card().apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            visibility = GONE
        }
        menuBannerText = ui.marquee(ui.text("", 14f, bold = true))
        menuBanner.addView(menuBannerText, ui.lp(0, Ui.WRAP, 1f))
        menuBanner.addView(ui.button("메뉴 보기", "primary") { showMenuDialog() }, ui.lp(Ui.WRAP, ui.dp(38)).apply { leftMargin = ui.dp(8) })
        chatPage.addView(menuBanner, ui.lp().apply { bottomMargin = ui.dp(6) })
        chatPage.addView(logCard, ui.lp(Ui.MATCH, 0, 1f))
        mapPage = MapPanel(activity, ui, serverId)
        playerPanel = PlayerPanel(activity, ui)
        invPanel = InventoryPanel(activity, ui, serverId)
        statusPanel = StatusPanel(activity, ui)
        pages = FrameLayout(activity)
        listOf<View>(chatPage, mapPage, invPanel, playerPanel, statusPanel).forEachIndexed { i, v ->
            v.visibility = if (i == 0) VISIBLE else GONE
            pages.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
        addView(pages, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(8) })

        val swipe = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < ui.dp(400) || abs(vx) < abs(vy) * 1.5f) return false
                showPage(page + if (vx < 0) 1 else -1)
                return true
            }
        })
        list.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev); false }
        playerPanel.listView.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev); false }
        invPanel.scrollView.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev); false }
        statusPanel.scrollView.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev); false }
        mapPage.mapView.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev); mapPage.mapView.touch(ev); true }
        styleTabs()

        // --- 자동완성 칩
        chipRow = ui.horizontal()
        chipScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
            visibility = GONE
        }
        chatPage.addView(chipScroll, ui.lp().apply { topMargin = ui.dp(6) })

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
        chatPage.addView(inputRow, ui.lp().apply { topMargin = ui.dp(6) })
    }

    private fun showPage(target: Int) {
        val next = target.coerceIn(0, 4)
        if (next == page) return
        val views = listOf<View>(chatPage, mapPage, invPanel, playerPanel, statusPanel)
        val from = views[page]
        val to = views[next]
        val dir = if (next > page) 1 else -1
        val w = pages.width.toFloat().takeIf { it > 0 } ?: ui.dp(360).toFloat()
        if (page == 1) mapPage.stopMoving()
        page = next
        to.translationX = dir * w
        to.visibility = VISIBLE
        to.animate().translationX(0f).setDuration(200).start()
        from.animate().translationX(-dir * w).setDuration(200).withEndAction {
            if (views[page] !== from) from.visibility = GONE
            from.translationX = 0f
        }.start()
        if (next == 2) invPanel.show(SessionHub.inventory(serverId))
        if (next == 3) playerPanel.show(SessionHub.players(serverId))
        if (next == 4) statusPanel.show(SessionHub.vitals(serverId), SessionHub.lastDeath(serverId))
        styleTabs()
    }

    private fun styleTabs() {
        tabs.forEachIndexed { i, b -> ui.style(b, if (i == page) "primary" else "plain") }
    }

    /** 화면에 보이는 동안만 1초 갱신과 세션 알림을 받는다 (홈으로 나가 밤새 잠수할 때 배터리 절약) */
    private var live = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) resume()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        if (visibility == VISIBLE) resume() else pause()
    }

    private fun resume() {
        if (live) return
        live = true
        SessionHub.addObserver(this)
        adapter.reload()
        onPlayers(serverId, SessionHub.players(serverId))
        lastMenuWindow = SessionHub.menu(serverId)?.windowId ?: -1
        onMenu(serverId, SessionHub.menu(serverId))
        onInventory(serverId, SessionHub.inventory(serverId))
        showDeathBanner(SessionHub.lastDeath(serverId))
        if (page == 4) statusPanel.show(SessionHub.vitals(serverId), SessionHub.lastDeath(serverId))
        render(SessionHub.status(serverId))
        if (openMenuOnAttach) {
            openMenuOnAttach = false
            main.post { if (SessionHub.menu(serverId) != null && menuDialog?.isShowing != true) showMenuDialog() }
        }
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun pause() {
        if (!live) return
        live = false
        SessionHub.removeObserver(this)
        main.removeCallbacks(ticker)
        main.removeCallbacks(suggestTask)
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    override fun onStatus(serverId: String, status: SessionStatus) {
        if (serverId == this.serverId) render(status)
    }

    override fun onLine(serverId: String, line: LogLine) {
        if (serverId == this.serverId) adapter.add(line)
    }

    override fun onMenu(serverId: String, menu: Menu?) {
        if (serverId != this.serverId) return
        if (menu == null) {
            menuBanner.visibility = GONE
            menuDialog?.dismiss()
            menuDialog = null
            lastMenuWindow = -1
            return
        }
        menuBannerText.setTextIfChanged("서버 메뉴: " + menu.title.plain.ifBlank { "(제목 없음)" })
        menuBanner.visibility = VISIBLE
        if (menuDialog?.isShowing == true) {
            fillMenu(menu)
        } else if (menu.windowId != lastMenuWindow && isShown && store.server(serverId)?.autoClickRules.isNullOrEmpty()) {
            // 자동 클릭 규칙이 없으면 새 메뉴가 열릴 때 바로 보여 준다
            showMenuDialog()
        }
        lastMenuWindow = menu.windowId
    }

    private fun showMenuDialog() {
        val menu = SessionHub.menu(serverId) ?: return
        val box = ui.vertical().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }
        menuDialogBox = box
        val scroll = android.widget.ScrollView(activity).apply { addView(box) }
        menuDialog = android.app.AlertDialog.Builder(activity)
            .setTitle(menu.title.plain.ifBlank { "서버 메뉴" })
            .setView(scroll)
            .setPositiveButton("숨기기", null)
            .setNegativeButton("메뉴 닫기") { _, _ -> SessionHub.closeMenu(serverId) }
            .show()
        fillMenu(menu)
    }

    private fun fillMenu(menu: Menu) {
        val box = menuDialogBox ?: return
        box.removeAllViews()
        box.addView(ui.hint("누르면 그 칸을 클릭합니다. 길게 누르면 자동 클릭 규칙에 추가합니다."))
        val items = menu.items.filter { it.slot < menu.containerSlots }.sortedBy { it.slot }
        if (items.isEmpty()) box.addView(ui.text("빈 메뉴입니다", 14f, p.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
        for (item in items) {
            val row = ui.vertical().apply {
                background = ui.rounded(p.input, 10)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                isClickable = true
                setOnClickListener {
                    SessionHub.clickMenu(serverId, item.slot)
                    SessionHub.note(serverId, "메뉴 클릭: ${item.slot}번 칸 '${item.name.plain}'")
                }
                setOnLongClickListener { addRule(menu, item); true }
            }
            val head = ui.horizontal()
            head.addView(ui.text("#${item.slot}", 12f, p.muted), ui.lp(ui.dp(40), Ui.WRAP))
            head.addView(ui.marquee(ui.text(spans(item.name), 15f, bold = true)), ui.lp(0, Ui.WRAP, 1f))
            if (item.count > 1) head.addView(ui.text("x${item.count}", 12f, p.muted))
            row.addView(head)
            for (l in item.lore.take(6)) {
                if (l.plain.isBlank()) continue
                row.addView(ui.text(spans(l), 12f, p.muted).apply { setPadding(ui.dp(40), 0, 0, 0) })
            }
            box.addView(row, ui.lp().apply { topMargin = ui.dp(6) })
        }
    }

    private fun spans(t: kr.jamsuham.core.text.ChatText): CharSequence {
        val sb = SpannableStringBuilder()
        for (s in t.spans) {
            val start = sb.length
            sb.append(s.text)
            sb.setSpan(ForegroundColorSpan(s.color?.let { p.readable(it) } ?: p.text), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun addRule(menu: Menu, item: kr.jamsuham.core.MenuItem) {
        val server = store.server(serverId) ?: return
        val title = menu.title.plain.trim().ifBlank { "*" }
        val rule = "$title > ${item.name.plain.trim()}"
        android.app.AlertDialog.Builder(activity)
            .setTitle("자동 클릭 규칙 추가")
            .setMessage("다음부터 이 메뉴가 열리면 자동으로 누릅니다.\n\n$rule\n\n(서버 설정에서 고치거나 지울 수 있습니다)")
            .setPositiveButton("추가") { _, _ ->
                store.saveServer(server.copy(autoClickRules = (server.autoClickRules.filter { it != rule } + rule)))
                Toast.makeText(activity, "추가했습니다. 다음 접속부터 적용됩니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onInventory(serverId: String, inventory: Inventory) {
        if (serverId != this.serverId) return
        if (page == 2) invPanel.show(inventory)
    }

    /** ← 버튼이나 뒤로 가기: 잠수 중이면 끊을지 물어본다 */
    fun requestBack() {
        if (!SessionHub.isActive(serverId)) {
            onBack()
            return
        }
        if (backDialog?.isShowing == true) return
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(ui.hint("잠수를 유지하면 화면을 나가도 알림 줄에서 계속 접속해 있습니다."))
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("서버 목록으로 나갈까요?")
            .setView(box)
            .create()
        box.addView(ui.button("잠수 유지하고 나가기", "primary") { dialog.dismiss(); onBack() }, ui.lp(Ui.MATCH, ui.dp(46)).apply { topMargin = ui.dp(10) })
        box.addView(ui.button("연결 끊고 나가기", "danger") {
            dialog.dismiss()
            AfkService.stop(activity, serverId)
            onBack()
        }, ui.lp(Ui.MATCH, ui.dp(46)).apply { topMargin = ui.dp(8) })
        box.addView(ui.button("머무르기", "plain") { dialog.dismiss() }, ui.lp(Ui.MATCH, ui.dp(46)).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })
        backDialog = dialog
        dialog.show()
    }

    override fun onVitals(serverId: String, vitals: Vitals?) {
        if (serverId != this.serverId) return
        if (page == 4) statusPanel.show(vitals, SessionHub.lastDeath(serverId))
    }

    override fun onDeath(serverId: String, death: DeathInfo?) {
        if (serverId != this.serverId) return
        showDeathBanner(death)
        if (page == 4) statusPanel.show(SessionHub.vitals(serverId), SessionHub.lastDeath(serverId))
    }

    private fun showDeathBanner(death: DeathInfo?) {
        if (death == null) {
            deathBanner.visibility = GONE
            return
        }
        deathBannerText.text = "☠ ${StatusPanel.timeOf(death.atMs)} 사망\n${death.describe()}"
        deathBanner.visibility = VISIBLE
    }

    override fun onPlayers(serverId: String, list: PlayerList) {
        if (serverId != this.serverId) return
        tabs.getOrNull(3)?.text = "접속자 ${list.players.size}"
        playerPanel.show(list)
    }

    fun refreshTitle() = render(SessionHub.status(serverId))

    private fun render(s: SessionStatus) {
        val server = store.server(serverId)
        titleView.setTextIfChanged(server?.displayName ?: "삭제된 서버")
        val (label, color) = stateLabel(s.state)
        (statusDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        val now = System.currentTimeMillis()
        // 1초마다 바뀌는 시간은 따로 두고, 나머지는 바뀔 때만 갱신 (긴 글씨가 흐르다 처음으로 튀지 않게)
        clockView.setTextIfChanged(
            when (s.state) {
                SessionState.ONLINE -> s.onlineSinceMs?.let { "$label · ${Formats.clock(now - it)} · " } ?: "$label · "
                SessionState.WAITING_RECONNECT -> "$label · ${(((s.nextAttemptAtMs ?: now) - now).coerceAtLeast(0) / 1000)}초 뒤 · "
                else -> "$label · "
            },
        )
        val parts = ArrayList<String>()
        if ((s.state == SessionState.PREPARING || s.state == SessionState.CONNECTING) && s.message.isNotBlank()) parts.add(s.message)
        if (s.reconnectCount > 0) parts.add("재접속 ${s.reconnectCount}회")
        s.serverVersion?.let { parts.add(it) }
        detailView.setTextIfChanged(parts.joinToString(" · "))
        val vis = if (s.state.isActive()) GONE else VISIBLE
        if (actionButton.visibility != vis) actionButton.visibility = vis
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
