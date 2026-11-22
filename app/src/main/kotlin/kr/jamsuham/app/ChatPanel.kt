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
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.TextPaint
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
    /** 좌우로 밀어 페이지 넘기기 (링크 위에서 밀어도 넘어가도록 링크 터치 처리에서도 같이 씀) */
    private lateinit var swipe: GestureDetector

    private var historyIndex = -1 // -1 = 입력 중인 새 글
    private var draft = ""
    private var suggestSeq = 0
    private val suggestTask = Runnable { requestSuggestions() }

    private val ticker = object : Runnable {
        override fun run() {
            render(SessionHub.status(serverId))
            // 상태 탭을 보고 있으면 데이터 사용량·접속 시간도 1초마다 갱신
            if (page == 4) refreshStatus(SessionHub.vitals(serverId))
            main.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL

        // --- 상단 상태 줄
        val top = ui.card().apply { setPadding(ui.dp(12) + ui.cardPad, ui.dp(10) + ui.cardPad, ui.dp(12) + ui.cardPad, ui.dp(10) + ui.cardPad) }
        val row = ui.horizontal()
        if (showBack) row.addView(ui.button("←", "ghost") { requestBack() }, ui.lp(ui.dp(44), ui.dp(40)))
        statusDot = ui.dot(p.muted)
        row.addView(statusDot, ui.lp(ui.dp(10), ui.dp(10)).apply { rightMargin = ui.dp(8); leftMargin = ui.dp(4) })
        val titles = ui.vertical()
        titleView = ui.marquee(ui.text("", 17f, bold = true))
        clockView = ui.text("", 12f, p.muted).apply { setSingleLine(true); visibility = GONE }
        // 상태·시간·재접속 횟수·버전을 한 줄에: 흐르지 않고 넘치면 끝을 "…" 로 줄인다 (따로 흐르며 엇갈리지 않게)
        detailView = ui.text("", 12f, p.muted).apply {
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titles.addView(titleView)
        val detailRow = ui.horizontal()
        detailRow.addView(clockView, ui.lp(Ui.WRAP, Ui.WRAP))
        detailRow.addView(detailView, ui.lp(0, Ui.WRAP, 1f))
        titles.addView(detailRow)
        row.addView(titles, ui.lp(0, Ui.WRAP, 1f))
        row.addView(ui.button("💾", "ghost") { (activity as? BaseActivity)?.exportChatLog(serverId) }, ui.lp(ui.dp(44), ui.dp(40)))
        row.addView(ui.button("⚙", "ghost") { onEdit() }, ui.lp(ui.dp(44), ui.dp(40)))
        // 접속 중에는 버튼을 숨겨 제목·상태 글씨 자리를 넓힌다 (끊기는 ← 버튼에서)
        actionButton = ui.button(tr("접속", "Connect"), "primary") { toggle() }
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
        tabs = listOf(tr("채팅", "Chat"), tr("지도", "Map"), tr("인벤", "Inv"), tr("접속자", "Players"), tr("상태", "Status")).mapIndexed { i, label ->
            ui.button(label, "plain") { showPage(i) }.also { b ->
                ui.fit(b)
                b.setPadding(ui.dp(4), 0, ui.dp(4), 0)
                tabBar.addView(b, ui.lp(0, ui.dp(40), 1f).apply { if (i > 0) leftMargin = ui.dp(4) })
            }
        }
        addView(tabBar, ui.lp().apply { topMargin = ui.dp(8) })

        val logCard = ui.card().apply { setPadding(ui.dp(12) + ui.cardPad, ui.dp(8) + ui.cardPad, ui.dp(12) + ui.cardPad, ui.dp(8) + ui.cardPad) }
        list = ListView(activity).apply {
            adapter = this@ChatPanel.adapter
            // 줄을 길게 누르면: 이 줄의 그림 글자 구성 (칭호·배경 띠 문제 진단용, 복사해서 보낼 수 있음)
            setOnItemLongClickListener { _, _, position, _ ->
                val line = this@ChatPanel.adapter.getItem(position) as? LogLine ?: return@setOnItemLongClickListener false
                showGlyphInfo(line)
                true
            }
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
        menuBanner.addView(ui.button(tr("메뉴 보기", "View menu"), "primary") { showMenuDialog() }, ui.lp(Ui.WRAP, ui.dp(38)).apply { leftMargin = ui.dp(8) })
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

        swipe = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < ui.dp(400) || abs(vx) < abs(vy) * 1.5f) return false
                // 끝에서 더 넘기면 처음으로, 처음에서 거꾸로 넘기면 끝으로 (한 바퀴 돎)
                val step = if (vx < 0) 1 else -1
                showPage(page + step, step)
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
        input = ui.input(tr("채팅 또는 /명령어", "Chat or /command")).apply {
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
        inputRow.addView(ui.button(tr("전송", "Send"), "primary") { send() }, ui.lp(Ui.WRAP, ui.dp(46)).apply { leftMargin = ui.dp(6) })
        chatPage.addView(inputRow, ui.lp().apply { topMargin = ui.dp(6) })
    }

    /**
     * [target] 페이지로 넘긴다. 범위를 넘으면 반대쪽 끝으로 돈다 (상태 → 채팅, 채팅 → 상태).
     * [swipeDir] 밀어서 넘긴 방향(+1 = 다음, -1 = 이전). 한 바퀴 돌 때도 민 방향대로 화면이 움직이게 쓴다.
     */
    private fun showPage(target: Int, swipeDir: Int = 0) {
        val views = listOf<View>(chatPage, mapPage, invPanel, playerPanel, statusPanel)
        val next = ((target % views.size) + views.size) % views.size
        if (next == page) return
        // 채팅 입력 중에 다른 페이지로 넘기면 키보드를 닫는다 (키보드가 열린 채로 지도·인벤이 가려지지 않게)
        if (page == 0) hideKeyboard()
        val from = views[page]
        val to = views[next]
        val dir = if (swipeDir != 0) swipeDir else if (next > page) 1 else -1
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
        if (next == 4) refreshStatus(SessionHub.vitals(serverId))
        styleTabs()
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        hideChips()
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
        playerPanel.glyphs = SessionHub.glyphs(serverId)
        adapter.reload()
        onPlayers(serverId, SessionHub.players(serverId))
        lastMenuWindow = SessionHub.menu(serverId)?.windowId ?: -1
        onMenu(serverId, SessionHub.menu(serverId))
        onInventory(serverId, SessionHub.inventory(serverId))
        showDeathBanner(SessionHub.lastDeath(serverId))
        if (page == 4) refreshStatus(SessionHub.vitals(serverId))
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
        menuBannerText.setTextIfChanged(tr("서버 메뉴: ", "Server menu: ") + menu.title.plain.ifBlank { tr("(제목 없음)", "(untitled)") })
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
            .setTitle(menu.title.plain.ifBlank { tr("서버 메뉴", "Server menu") })
            .setView(scroll)
            .setPositiveButton(tr("숨기기", "Hide"), null)
            .setNegativeButton(tr("메뉴 닫기", "Close menu")) { _, _ -> SessionHub.closeMenu(serverId) }
            .show()
        fillMenu(menu)
    }

    private fun fillMenu(menu: Menu) {
        val box = menuDialogBox ?: return
        box.removeAllViews()
        box.addView(ui.hint(tr("누르면 설명이 보이고, 그 창의 '클릭'을 눌러야 실제로 누릅니다 (구매 등). 길게 누르면 자동 클릭 규칙에 추가합니다.", "Tap to see details; the item is only clicked when you press 'Click' in that window (purchases etc.). Long-press to add an auto-click rule.")))
        val items = menu.items.filter { it.slot < menu.containerSlots }.sortedBy { it.slot }
        if (items.isEmpty()) box.addView(ui.text(tr("빈 메뉴입니다", "Empty menu"), 14f, p.muted).apply { setPadding(0, ui.dp(12), 0, 0) })
        for (item in items) {
            val row = ui.vertical().apply {
                background = ui.rounded(p.input, 10)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                isClickable = true
                // 바로 누르지 않고 설명(가격 등)을 먼저 보여 준다 — PC 에서 마우스를 올려 보는 것과 같음
                setOnClickListener { showMenuItem(menu, item) }
                setOnLongClickListener { addRule(menu, item); true }
            }
            val head = ui.horizontal()
            head.addView(ui.text("#${item.slot}", 12f, p.muted), ui.lp(ui.dp(40), Ui.WRAP))
            head.addView(ui.marquee(ui.text(spans(item.name), 15f, bold = true)), ui.lp(0, Ui.WRAP, 1f))
            if (item.count > 1) head.addView(ui.text("x${item.count}", 12f, p.muted))
            row.addView(head)
            // 목록에는 설명을 2줄만 미리 보여 주고, 전체는 눌러서 본다
            val lore = item.lore.filter { it.plain.isNotBlank() }
            for (l in lore.take(2)) {
                row.addView(ui.text(spans(l), 12f, p.muted).apply { setPadding(ui.dp(40), 0, 0, 0); maxLines = 1 })
            }
            if (lore.size > 2) row.addView(ui.text(tr("… 설명 ${lore.size - 2}줄 더 (눌러서 보기)", "… ${lore.size - 2} more lines (tap to view)"), 11f, p.muted).apply { setPadding(ui.dp(40), 0, 0, 0) })
            box.addView(row, ui.lp().apply { topMargin = ui.dp(6) })
        }
    }

    /** 메뉴 아이템 설명 창: 이름·개수·설명 전체. '클릭'을 눌러야 서버에 실제로 클릭을 보낸다 */
    private fun showMenuItem(menu: Menu, item: kr.jamsuham.core.MenuItem) {
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(ui.text(spans(item.name).ifEmpty { item.itemId }, 17f, bold = true))
        val info = buildString {
            append(tr("#${item.slot}번 칸", "Slot #${item.slot}"))
            if (item.count > 1) append(tr(" · ${item.count}개", " · ×${item.count}"))
            if (item.itemId.isNotBlank()) append(" · ").append(kr.jamsuham.core.text.ItemNames.name(item.itemId))
        }
        box.addView(ui.text(info, 12f, p.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(8)) })
        val lore = item.lore
        if (lore.none { it.plain.isNotBlank() }) {
            box.addView(ui.text(tr("설명이 없습니다", "No description"), 13f, p.muted))
        } else {
            for (l in lore) box.addView(ui.text(spans(l).ifEmpty { " " }, 14f))
        }
        android.app.AlertDialog.Builder(activity)
            .setView(android.widget.ScrollView(activity).apply { addView(box) })
            .setPositiveButton(tr("클릭", "Click")) { _, _ ->
                // 설명을 보는 사이 메뉴가 닫혔거나 바뀌었으면 누르지 않는다
                val now = SessionHub.menu(serverId)
                if (now == null || now.windowId != menu.windowId) {
                    Toast.makeText(activity, tr("메뉴가 닫혔거나 바뀌어서 누르지 않았습니다", "Not clicked: the menu was closed or changed"), Toast.LENGTH_SHORT).show()
                } else if (SessionHub.clickMenu(serverId, item.slot)) {
                    SessionHub.note(serverId, tr("메뉴 클릭: ${item.slot}번 칸 '${item.name.plain}'", "Menu click: slot ${item.slot} '${item.name.plain}'"))
                } else {
                    Toast.makeText(activity, tr("누르지 못했습니다", "Could not click"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(tr("자동 클릭 규칙", "Auto-click rule")) { _, _ -> addRule(menu, item) }
            .setNegativeButton(tr("닫기", "Close"), null)
            .show()
    }

    private fun spans(t: kr.jamsuham.core.text.ChatText): CharSequence =
        Glyphs.build(t, { s -> s.color?.let { p.readable(it) } ?: p.text }, ui.dp(15).toFloat(), SessionHub.glyphs(serverId))

    private fun addRule(menu: Menu, item: kr.jamsuham.core.MenuItem) {
        val server = store.server(serverId) ?: return
        val title = menu.title.plain.trim().ifBlank { "*" }
        val rule = "$title > ${item.name.plain.trim()}"
        android.app.AlertDialog.Builder(activity)
            .setTitle(tr("자동 클릭 규칙 추가", "Add auto-click rule"))
            .setMessage(tr("다음부터 이 메뉴가 열리면 자동으로 누릅니다.\n\n$rule\n\n(서버 설정에서 고치거나 지울 수 있습니다)", "From now on this will be clicked automatically when this menu opens.\n\n$rule\n\n(You can edit or delete it in server settings)"))
            .setPositiveButton(tr("추가", "Add")) { _, _ ->
                store.saveServer(server.copy(autoClickRules = (server.autoClickRules.filter { it != rule } + rule)))
                Toast.makeText(activity, tr("추가했습니다. 다음 접속부터 적용됩니다", "Added. Applies from the next connection"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(tr("취소", "Cancel"), null)
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
        box.addView(ui.hint(tr("잠수를 유지하면 화면을 나가도 알림 줄에서 계속 접속해 있습니다.", "If you keep AFK, you stay connected from the notification even after leaving this screen.")))
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle(tr("서버 목록으로 나갈까요?", "Back to the server list?"))
            .setView(box)
            .create()
        box.addView(ui.button(tr("잠수 유지하고 나가기", "Keep AFK and leave"), "primary") { dialog.dismiss(); onBack() }, ui.lp(Ui.MATCH, ui.dp(46)).apply { topMargin = ui.dp(10) })
        box.addView(ui.button(tr("연결 끊고 나가기", "Disconnect and leave"), "danger") {
            dialog.dismiss()
            AfkService.stop(activity, serverId)
            onBack()
        }, ui.lp(Ui.MATCH, ui.dp(46)).apply { topMargin = ui.dp(8) })
        box.addView(ui.button(tr("머무르기", "Stay"), "plain") { dialog.dismiss() }, ui.lp(Ui.MATCH, ui.dp(46)).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })
        backDialog = dialog
        dialog.show()
    }

    override fun onVitals(serverId: String, vitals: Vitals?) {
        if (serverId != this.serverId) return
        if (page == 4) refreshStatus(vitals)
    }

    override fun onDeath(serverId: String, death: DeathInfo?) {
        if (serverId != this.serverId) return
        showDeathBanner(death)
        if (page == 4) refreshStatus(SessionHub.vitals(serverId))
    }

    private fun showDeathBanner(death: DeathInfo?) {
        if (death == null) {
            deathBanner.visibility = GONE
            return
        }
        deathBannerText.text = tr("☠ ${StatusPanel.timeOf(death.atMs)} 사망\n${death.describe()}", "☠ Died ${StatusPanel.timeOf(death.atMs)}\n${death.describe()}")
        deathBanner.visibility = VISIBLE
    }

    override fun onGlyphs(serverId: String) {
        if (serverId != this.serverId) return
        // 그림 글자가 생겼으니 채팅·접속자·인벤·메뉴를 다시 그린다
        playerPanel.glyphs = SessionHub.glyphs(serverId)
        adapter.notifyDataSetChanged()
        playerPanel.show(SessionHub.players(serverId))
        if (page == 2) invPanel.show(SessionHub.inventory(serverId))
        SessionHub.menu(serverId)?.let { if (menuDialog?.isShowing == true) fillMenu(it) }
        if (page == 4) refreshStatus(SessionHub.vitals(serverId))
    }

    private fun refreshStatus(vitals: kr.jamsuham.core.Vitals?) {
        statusPanel.show(
            vitals, SessionHub.lastDeath(serverId), SessionHub.traffic(serverId),
            SessionHub.ratePerHour(serverId), SessionHub.packStates(serverId),
        )
    }

    override fun onPlayers(serverId: String, list: PlayerList) {
        if (serverId != this.serverId) return
        tabs.getOrNull(3)?.text = tr("접속자 ${list.players.size}", "Players ${list.players.size}")
        playerPanel.show(list)
    }

    fun refreshTitle() = render(SessionHub.status(serverId))

    private fun render(s: SessionStatus) {
        val server = store.server(serverId)
        titleView.setTextIfChanged(server?.displayName ?: tr("삭제된 서버", "Deleted server"))
        val (label, color) = stateLabel(s.state)
        (statusDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        val now = System.currentTimeMillis()
        val parts = ArrayList<String>()
        parts.add(
            when (s.state) {
                SessionState.ONLINE -> s.onlineSinceMs?.let { "$label · ${Formats.clock(now - it)}" } ?: label
                SessionState.WAITING_RECONNECT -> tr("$label · ${(((s.nextAttemptAtMs ?: now) - now).coerceAtLeast(0) / 1000)}초 뒤", "$label · in ${(((s.nextAttemptAtMs ?: now) - now).coerceAtLeast(0) / 1000)}s")
                else -> label
            },
        )
        if ((s.state == SessionState.PREPARING || s.state == SessionState.CONNECTING) && s.message.isNotBlank()) parts.add(s.message)
        if (s.reconnectCount > 0) parts.add(tr("재접속 ${s.reconnectCount}회", "Reconnects ${s.reconnectCount}"))
        s.serverVersion?.let { parts.add(it) }
        detailView.setTextIfChanged(parts.joinToString(" · "))
        val vis = if (s.state.isActive()) GONE else VISIBLE
        if (actionButton.visibility != vis) actionButton.visibility = vis
    }

    private fun stateLabel(st: SessionState) = when (st) {
        SessionState.ONLINE -> tr("잠수 중", "AFK") to p.ok
        SessionState.WAITING_RECONNECT -> tr("재접속 대기", "Waiting to reconnect") to p.warn
        SessionState.PREPARING, SessionState.CONNECTING -> tr("접속 중", "Connecting") to p.warn
        else -> tr("꺼짐", "Off") to p.muted
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
            Toast.makeText(activity, tr("서버에 접속한 뒤에 보낼 수 있습니다", "Connect to the server before sending"), Toast.LENGTH_SHORT).show()
            return
        }
        if (SessionHub.sendChat(serverId, msg)) {
            store.addHistory(serverId, msg)
            SessionHub.note(serverId, tr("나: $msg", "Me: $msg"))
            historyIndex = -1
            draft = ""
            input.setText("")
            hideChips()
        } else {
            Toast.makeText(activity, tr("보내지 못했습니다", "Could not send"), Toast.LENGTH_SHORT).show()
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

    /** 채팅 속 누를 수 있는 글자 (색은 원래대로 두고 밑줄만) */
    private inner class ChatLinkSpan(val span: kr.jamsuham.core.text.ChatSpan) : ClickableSpan() {
        override fun onClick(widget: View) = onChatClick(span)
        override fun updateDrawState(ds: TextPaint) {
            // 링크만 밑줄. 서버가 거의 모든 채팅에 설명·귓속말 버튼을 붙이는 경우가 많아서, 다 밑줄을 그으면 읽기 어렵다
            ds.isUnderlineText = span.click?.action == kr.jamsuham.core.text.ChatClick.Action.OPEN_URL
        }
    }

    /**
     * 목록 줄 안의 링크만 눌리게 하는 터치 처리. LinkMovementMethod 를 쓰면 줄 전체가 터치를 가져가
     * 목록 스크롤·좌우 넘기기가 어색해지므로, 손가락 아래에 링크가 있을 때만 터치를 받는다.
     */
    private val LinkTouch = View.OnTouchListener { v, ev ->
        val tv = v as TextView
        // 줄이 터치를 가져가도 좌우 넘기기는 되게 한다
        if (::swipe.isInitialized) swipe.onTouchEvent(ev)
        val text = tv.text as? Spanned ?: return@OnTouchListener false
        val layout = tv.layout ?: return@OnTouchListener false
        val x = ev.x.toInt() - tv.totalPaddingLeft + tv.scrollX
        val y = ev.y.toInt() - tv.totalPaddingTop + tv.scrollY
        val lineIdx = layout.getLineForVertical(y)
        val inLine = x >= layout.getLineLeft(lineIdx) && x <= layout.getLineRight(lineIdx)
        val off = layout.getOffsetForHorizontal(lineIdx, x.toFloat())
        val link = if (inLine) text.getSpans(off, off, ChatLinkSpan::class.java).firstOrNull() else null
        when (ev.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pressedLink = link
                pressedAt = ev.eventTime
                downX = ev.x
                downY = ev.y
                link != null
            }
            android.view.MotionEvent.ACTION_UP -> {
                // 손가락을 움직였으면(밀기) 누른 것으로 보지 않는다
                val slop = android.view.ViewConfiguration.get(tv.context).scaledTouchSlop
                val moved = abs(ev.x - downX) > slop || abs(ev.y - downY) > slop
                val hit = link != null && link === pressedLink && !moved
                pressedLink = null
                if (hit) {
                    val long = ev.eventTime - pressedAt >= LONG_PRESS_MS
                    val hover = link!!.span.hover
                    // 길게 누르면 설명 글, 짧게 누르면 클릭 동작 (클릭 동작이 없으면 짧게 눌러도 아무 일 없음)
                    if (long && hover != null) showHover(link.span.text, hover)
                    else if (!long && link.span.click != null) link.onClick(tv)
                    else if (long) link.onClick(tv)
                }
                hit
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                pressedLink = null
                false
            }
            else -> pressedLink != null
        }
    }
    private var pressedLink: ChatLinkSpan? = null
    private var downX = 0f
    private var downY = 0f
    private var pressedAt = 0L
    private val LONG_PRESS_MS = 450L

    /** 채팅 글자를 눌렀을 때: 링크 열기(확인) · 명령어 보내기 · 입력창에 넣기 · 복사 · 설명 보기 */
    private fun onChatClick(span: kr.jamsuham.core.text.ChatSpan) {
        val click = span.click ?: return
        when (click.action) {
            kr.jamsuham.core.text.ChatClick.Action.OPEN_URL -> {
                val url = click.value.trim()
                val uri = android.net.Uri.parse(url)
                if (uri.scheme?.lowercase() !in setOf("http", "https")) {
                    Toast.makeText(activity, tr("열 수 없는 주소입니다", "This address cannot be opened"), Toast.LENGTH_SHORT).show()
                    return
                }
                // 서버가 보낸 링크라 어디로 가는지 먼저 보여 준다
                android.app.AlertDialog.Builder(activity)
                    .setTitle(tr("링크 열기", "Open link"))
                    .setMessage(url + (span.hover?.let { "\n\n$it" } ?: ""))
                    .setPositiveButton(tr("열기", "Open")) { _, _ ->
                        try {
                            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                        } catch (_: Throwable) {
                            Toast.makeText(activity, tr("링크를 열 앱이 없습니다", "No app can open this link"), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNeutralButton(tr("복사", "Copy")) { _, _ -> copyText(url) }
                    .setNegativeButton(tr("취소", "Cancel"), null)
                    .show()
            }
            kr.jamsuham.core.text.ChatClick.Action.RUN_COMMAND -> {
                val cmd = click.value.trim()
                if (cmd.isEmpty()) return
                if (SessionHub.status(serverId).state != SessionState.ONLINE) {
                    Toast.makeText(activity, tr("서버에 접속한 뒤에 누를 수 있습니다", "Connect to the server before tapping"), Toast.LENGTH_SHORT).show()
                    return
                }
                // 바닐라처럼 바로 보낸다 ([수락]·[거절] 같은 버튼)
                if (SessionHub.sendChat(serverId, cmd)) SessionHub.note(serverId, tr("눌러서 실행: $cmd", "Ran by tap: $cmd"))
                else Toast.makeText(activity, tr("보내지 못했습니다", "Could not send"), Toast.LENGTH_SHORT).show()
            }
            kr.jamsuham.core.text.ChatClick.Action.SUGGEST_COMMAND -> {
                if (page != 0) showPage(0)
                input.setText(click.value)
                input.setSelection(input.text.length)
                input.requestFocus()
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(input, 0)
            }
            kr.jamsuham.core.text.ChatClick.Action.COPY -> copyText(click.value)
        }
    }

    private fun copyText(s: String) {
        val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(tr("잠수크래프트", "Jamsucraft"), s))
        Toast.makeText(activity, tr("복사했습니다", "Copied"), Toast.LENGTH_SHORT).show()
    }

    private fun showGlyphInfo(line: LogLine) {
        val info = Glyphs.describe(line.text.spans, SessionHub.glyphs(serverId))
        val body = ui.text(line.text.plain.take(80) + "\n\n" + info, 12f).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(tr("이 줄의 그림 글자", "Glyphs in this line"))
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton(tr("복사", "Copy")) { _, _ ->
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("glyphs", line.text.plain + "\n" + info))
                android.widget.Toast.makeText(activity, tr("복사했습니다", "Copied"), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(tr("닫기", "Close"), null)
            .show()
    }

    private fun showHover(title: String, text: String) {
        android.app.AlertDialog.Builder(activity)
            .setTitle(title.trim().take(40).ifBlank { tr("설명", "Details") })
            .setMessage(text)
            .setPositiveButton(tr("닫기", "Close"), null)
            .show()
    }

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
                setPadding(ui.dp(2), ui.dp(3), ui.dp(2), ui.dp(3))
            }
            // 설정의 채팅 글자 크기 (바꾸면 바로 반영)
            val sizeSp = AppSettings.chatTextSp.toFloat()
            if (tv.tag != sizeSp) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                tv.tag = sizeSp
            }
            val line = items[position]
            val sb = SpannableStringBuilder()
            val glyphs = SessionHub.glyphs(serverId)
            if (AppSettings.chatTimestamps) append(sb, time.format(Date(line.timeMs)) + "  ", p.muted, false)
            val base = when (line.kind) {
                LogLine.Kind.CHAT -> p.text
                LogLine.Kind.SYSTEM -> p.info
                LogLine.Kind.ERROR -> p.err
            }
            // 배경으로 깔리는 그림 글자(뒤에 음수 빈칸이 오는 것)는 건너뛴다
            val skip = Glyphs.backgroundMarks(line.text.spans, glyphs)
            for ((spanIndex, span) in line.text.spans.withIndex()) {
                val c = if (line.kind == LogLine.Kind.CHAT && span.color != null) p.readable(span.color!!) else base
                val start = sb.length
                // 글자 + 리소스팩 그림 글자 (그림이 없으면 숨김)
                Glyphs.append(sb, span, c, tv.textSize, glyphs, skip = skip, spanIndex = spanIndex)
                // 서버가 붙인 클릭 동작(링크·명령어 버튼 등)이나 설명 글이 있으면 누를 수 있게 (빈 조각은 건너뜀)
                if ((span.click != null || span.hover != null) && sb.length > start) {
                    sb.setSpan(ChatLinkSpan(span), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            tv.text = sb
            tv.setOnTouchListener(LinkTouch)
            return tv
        }

        private fun append(sb: SpannableStringBuilder, s: String, color: Int, bold: Boolean) = Glyphs.appendText(sb, s, color, bold)
    }
}

/** 서버 시작은 권한 확인 등 액티비티 쪽 일이 있어서 액티비티가 구현한다 */
interface Starter {
    fun startServer(serverId: String): Boolean
}
