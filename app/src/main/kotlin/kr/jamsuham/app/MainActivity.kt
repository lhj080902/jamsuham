package kr.jamsuham.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus

/**
 * 첫 화면: 서버 목록. 서버를 누르면 채팅 화면.
 * 폭이 600dp 이상(태블릿·가로 화면)이면 왼쪽에 목록, 오른쪽에 고른 서버의 채팅을 같이 보여 준다.
 */
class MainActivity : BaseActivity(), SessionHub.Observer {

    private lateinit var ui: Ui
    private lateinit var store: Store
    private lateinit var accounts: AccountStore
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var listPane: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var bannerBox: LinearLayout
    private var detailPane: ChatPanel? = null
    private var selectedId: String? = null
    private var updateBanner: View? = null
    private var updating = false

    private val ticker = object : Runnable {
        override fun run() {
            renderList()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = Store(this)
        accounts = AccountStore(this)
        selectedId = savedInstanceState?.getString("selected")
        root = LinearLayout(this)
        buildListPane()
        setupWindow(root, ui.p)
        arrange()
        setContentView(root)
        // 잠수 중 상단 고정 알림(끊기/앱 열기 버튼)을 띄우려면 알림 권한이 필요하다
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected", selectedId)
    }

    override fun onStart() {
        super.onStart()
        SessionHub.addObserver(this)
        if (selectedId != null && store.server(selectedId!!) == null) selectedId = null
        arrange()
        renderBanners()
        refreshInfos()
        main.post(ticker)
        checkUpdate(manual = false)
    }

    override fun onStop() {
        SessionHub.removeObserver(this)
        main.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    override fun onStatus(serverId: String, status: SessionStatus) = renderList()
    override fun onLine(serverId: String, line: LogLine) {}

    private fun isWide() = resources.configuration.screenWidthDp >= 600

    // ------------------------------------------------------------ 배치

    private fun arrange() {
        (listPane.parent as? ViewGroup)?.removeView(listPane)
        detailPane?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root.removeAllViews()
        if (isWide()) {
            root.orientation = LinearLayout.HORIZONTAL
            root.addView(listPane, ui.lp(0, Ui.MATCH, 4f))
            val list = store.servers()
            if (selectedId == null) selectedId = list.firstOrNull()?.id
            val id = selectedId
            if (id != null) {
                if (detailPane?.serverId != id) {
                    detailPane = ChatPanel(this, ui, id, showBack = false, onBack = {}, onEdit = { openEditor(id) })
                }
                root.addView(detailPane, ui.lp(0, Ui.MATCH, 6f).apply { leftMargin = ui.dp(12) })
            } else {
                detailPane = null
                val empty = ui.card().apply { gravity = Gravity.CENTER }
                empty.addView(ui.text("왼쪽에서 서버를 추가해 주세요", 15f, ui.p.muted))
                root.addView(empty, ui.lp(0, Ui.MATCH, 6f).apply { leftMargin = ui.dp(12) })
            }
        } else {
            root.orientation = LinearLayout.VERTICAL
            detailPane = null
            root.addView(listPane, ui.lp(Ui.MATCH, Ui.MATCH))
        }
        renderList()
    }

    private fun buildListPane() {
        listPane = ui.vertical()
        val header = ui.horizontal()
        val titles = ui.vertical()
        titles.addView(ui.text("잠수크래프트", 26f, bold = true))
        titles.addView(ui.text("마인크래프트 잠수 클라이언트", 13f, ui.p.muted))
        header.addView(titles, ui.lp(0, Ui.WRAP, 1f))
        header.addView(ui.button("계정", "plain") { startActivity(Intent(this, AccountsActivity::class.java)) }, ui.lp(Ui.WRAP, ui.dp(42)))
        header.addView(ui.button("+ 서버", "primary") { openEditor(null) }, ui.lp(Ui.WRAP, ui.dp(42)).apply { leftMargin = ui.dp(6) })
        listPane.addView(header)

        bannerBox = ui.vertical()
        listPane.addView(bannerBox, ui.lp())

        val scroll = ScrollView(this).apply { isFillViewport = true }
        listBox = ui.vertical()
        scroll.addView(listBox)
        listPane.addView(scroll, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(10) })

        val footer = ui.horizontal().apply { setPadding(0, ui.dp(6), 0, 0) }
        // 작은 회색 글씨로 버전 표시 (예: v1.1.13)
        val version = Updater.versionLabel(this)
        val buildLabel = ui.text(version + if (Plan.isPremium(this)) " · 프리미엄(테스트)" else "", 11f, ui.p.muted)
        var taps = 0
        buildLabel.setOnClickListener {
            // 결제를 붙이기 전 테스트용: 7번 누르면 프리미엄 켜기/끄기 (GitHub 판만. Play 판에는 없음)
            if (Flavor.PLAY) return@setOnClickListener
            if (++taps >= 7) {
                taps = 0
                val on = !Plan.isPremium(this)
                Plan.setPremiumTest(this, on)
                buildLabel.text = version + if (on) " · 프리미엄(테스트)" else ""
                toast(if (on) "테스트용 프리미엄을 켰습니다 (동시 잠수 제한 없음)" else "테스트용 프리미엄을 껐습니다")
            }
        }
        footer.addView(buildLabel, ui.lp(0, Ui.WRAP, 1f))
        footer.addView(ui.button("정보", "ghost") { AppInfo.show(this, ui) }, ui.lp(Ui.WRAP, ui.dp(36)))
        // Play 판은 Play 스토어가 업데이트하므로 앱 안 업데이트 버튼이 없다
        if (!Flavor.PLAY) footer.addView(ui.button("업데이트 확인", "ghost") { checkUpdate(manual = true) }, ui.lp(Ui.WRAP, ui.dp(36)))
        listPane.addView(footer, ui.lp())
    }

    // ------------------------------------------------------------ 서버 목록

    private var lastSignature = ""
    private class CardViews(val dot: View, val detail: TextView, val button: Button, val icon: ImageView, val motd: TextView)
    private val cardViews = HashMap<String, CardViews>()
    private val lastPing = HashMap<String, Long>()

    /** 서버 아이콘·문구·접속자 수 새로 가져오기 (서버마다 1분에 한 번) */
    private fun refreshInfos() {
        val now = System.currentTimeMillis()
        for (s in store.servers()) {
            if (now - (lastPing[s.id] ?: 0) < 60_000) continue
            lastPing[s.id] = now
            ServerInfos.refresh(this, s) { renderList() }
        }
    }

    private fun renderList() {
        val servers = store.servers()
        val signature = servers.joinToString("|") { it.id + it.displayName + it.address + it.accountId } + "#" + selectedId + isWide()
        if (signature != lastSignature) {
            lastSignature = signature
            rebuildCards(servers)
        }
        val now = System.currentTimeMillis()
        for (s in servers) {
            val views = cardViews[s.id] ?: continue
            val dot = views.dot
            val detail = views.detail
            val button = views.button
            ServerInfos.icon(this, s.id)?.let { views.icon.setImageBitmap(it) }
            val info = ServerInfos.info(s.id)
            views.motd.setTextIfChanged(when {
                info == null -> s.address
                info.motd.isNotBlank() -> info.motd.lines().joinToString(" ") { it.trim() } + "  ·  ${info.online}/${info.max}명"
                else -> "${s.address}  ·  ${info.online}/${info.max}명"
            })
            val st = SessionHub.status(s.id)
            val (label, color) = when (st.state) {
                SessionState.ONLINE -> "잠수 중" to ui.p.ok
                SessionState.WAITING_RECONNECT -> "재접속 대기" to ui.p.warn
                SessionState.PREPARING, SessionState.CONNECTING -> "접속 중" to ui.p.warn
                else -> "꺼짐" to ui.p.muted
            }
            (dot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            val parts = arrayListOf(label)
            if (st.state == SessionState.ONLINE) st.onlineSinceMs?.let { parts.add(Formats.minutes(now - it)) }
            if (st.state == SessionState.WAITING_RECONNECT) st.nextAttemptAtMs?.let { parts.add("${((it - now).coerceAtLeast(0)) / 1000}초 뒤") }
            if (st.reconnectCount > 0 && st.state.isActive()) parts.add("재접속 ${st.reconnectCount}회")
            accounts.get(s.accountId)?.let { parts.add(it.name) } ?: parts.add("계정 없음")
            detail.setTextIfChanged(parts.joinToString(" · "))
            val active = st.state.isActive()
            val label2 = if (active) "끊기" else "시작"
            if (button.text.toString() != label2) {
                button.text = label2
                ui.style(button, if (active) "danger" else "primary")
            }
        }
    }

    private fun rebuildCards(servers: List<ServerEntry>) {
        listBox.removeAllViews()
        cardViews.clear()
        if (servers.isEmpty()) {
            val empty = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            empty.addView(ui.text("아직 서버가 없습니다", 16f, bold = true))
            empty.addView(ui.text("오른쪽 위 '+ 서버'를 눌러 잠수할 서버를 추가하세요", 13f, ui.p.muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(10)) })
            empty.addView(ui.button("+ 서버 추가", "primary") { openEditor(null) }, ui.lp(Ui.WRAP, ui.dp(44)))
            listBox.addView(empty, ui.lp())
            return
        }
        for ((i, s) in servers.withIndex()) {
            val selected = isWide() && s.id == selectedId
            val card = ui.card().apply {
                if (selected) background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ui.p.card)
                    cornerRadius = ui.dp(16).toFloat()
                    setStroke(ui.dp(2), ui.p.accent)
                }
                isClickable = true
                setOnClickListener { select(s.id) }
                setOnLongClickListener { openEditor(s.id); true }
            }
            val row = ui.horizontal()
            val icon = ImageView(this).apply {
                background = ui.rounded(ui.p.input, 12)
                clipToOutline = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(applicationInfo.icon)
            }
            row.addView(icon, ui.lp(ui.dp(48), ui.dp(48)).apply { rightMargin = ui.dp(12) })
            val texts = ui.vertical()
            val nameRow = ui.horizontal()
            val dot = ui.dot(ui.p.muted)
            nameRow.addView(dot, ui.lp(ui.dp(9), ui.dp(9)).apply { rightMargin = ui.dp(6) })
            nameRow.addView(ui.marquee(ui.text(s.displayName, 17f, bold = true)), ui.lp(0, Ui.WRAP, 1f))
            texts.addView(nameRow)
            val motd = ui.marquee(ui.text(s.address, 12f, ui.p.muted))
            texts.addView(motd)
            val detail = ui.marquee(ui.text("", 12f, ui.p.muted)).apply { setPadding(0, ui.dp(2), 0, 0) }
            texts.addView(detail)
            row.addView(texts, ui.lp(0, Ui.WRAP, 1f))
            val button = ui.button("시작", "primary") { toggle(s.id) }
            row.addView(button, ui.lp(Ui.WRAP, ui.dp(42)).apply { leftMargin = ui.dp(8) })
            card.addView(row)
            listBox.addView(card, ui.lp().apply { if (i > 0) topMargin = ui.dp(10) })
            cardViews[s.id] = CardViews(dot, detail, button, icon, motd)
        }
        listBox.addView(ui.hint("서버를 길게 누르면 설정을 바꿀 수 있습니다."), ui.lp())
    }

    private fun select(id: String) {
        if (isWide()) {
            selectedId = id
            arrange()
        } else {
            openChat(id)
        }
    }

    private fun toggle(id: String) {
        if (SessionHub.isActive(id)) AfkService.stop(this, id) else if (startServer(id)) {
            if (!isWide()) openChat(id) else select(id)
        }
    }

    // ------------------------------------------------------------ 배너 (배터리 / 업데이트)

    private fun renderBanners() {
        bannerBox.removeAllViews()
        // 알림을 꺼 두면 잠수 중 고정 알림(끊기 버튼)과 서버 메뉴 알림이 아무 표시 없이 사라진다
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) {
            bannerBox.addView(banner(
                "알림이 꺼져 있어요",
                "잠수 중 알림(끊기 버튼)과 서버 메뉴·보상 알림을 받으려면 알림을 켜 주세요.",
                "켜기",
            ) { requestNotifications() }, ui.gap(10))
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            bannerBox.addView(banner(
                "화면이 꺼지면 끊길 수 있어요",
                "배터리 최적화에서 이 앱을 빼 두면 오래 잠수해도 안정적입니다.",
                "설정하기",
            ) { requestBatteryExemption() }, ui.gap(10))
        }
        updateBanner?.let { bannerBox.addView(it, ui.gap(10)) }
    }

    private fun banner(title: String, body: String, action: String, onClick: () -> Unit): View {
        val card = ui.card().apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }
        val row = ui.horizontal()
        val texts = ui.vertical()
        texts.addView(ui.text(title, 14f, bold = true))
        texts.addView(ui.text(body, 12f, ui.p.muted))
        row.addView(texts, ui.lp(0, Ui.WRAP, 1f))
        row.addView(ui.button(action, "primary", onClick), ui.lp(Ui.WRAP, ui.dp(40)).apply { leftMargin = ui.dp(8) })
        card.addView(row)
        return card
    }

    private fun requestNotifications() {
        // 한 번 거절한 뒤라면 권한 창을 다시 띄울 수 있다. "다시 묻지 않음" 이거나 알림 자체를 끈 경우는 설정 화면으로 보낸다
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Throwable) {
            toast("설정 화면을 열 수 없습니다")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderBanners()
    }

    private fun requestBatteryExemption() {
        if (Flavor.PLAY) {
            // Play 판: 예외를 직접 요청하지 않고 설정 화면으로 안내만 한다 (Play 정책상 직접 요청은 허용 용도가 제한됨)
            toast("목록에서 '잠수크래프트'를 찾아 '최적화 안 함'(또는 '제한 없음')으로 바꿔 주세요")
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("설정 화면을 열 수 없습니다")
            }
            return
        }
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("설정 화면을 열 수 없습니다")
            }
        }
    }

    private fun checkUpdate(manual: Boolean) {
        if (Flavor.PLAY) return
        val now = System.currentTimeMillis()
        if (!manual && now - store.lastUpdateCheck < 10 * 60 * 1000) return
        store.lastUpdateCheck = now
        if (manual) toast("업데이트 확인 중…")
        Thread {
            val release = try {
                Updater.fetchLatest()
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val current = Updater.currentBuild(this)
                if (release != null && release.build > current) {
                    updateBanner = banner(
                        "새 버전이 있어요 (${Updater.versionLabel(release)})",
                        "지금 버전: ${Updater.versionLabel(this)}. 누르면 받아서 바로 설치합니다.",
                        "업데이트",
                    ) { startUpdate(release) }
                    renderBanners()
                } else if (manual) {
                    toast(if (release == null) "업데이트 정보를 가져오지 못했습니다" else "최신 버전입니다 (${Updater.versionLabel(this)})")
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startUpdate(release: Updater.Release) {
        if (updating) return
        if (!Updater.ensureInstallPermission(this)) return
        val go = {
            updating = true
            val dialog = AlertDialog.Builder(this)
                .setTitle("업데이트 받는 중")
                .setMessage("0%")
                .setCancelable(false)
                .show()
            Thread {
                try {
                    Updater.downloadAndInstall(this, release) { pct -> runOnUiThread { dialog.setMessage("$pct%") } }
                    runOnUiThread { dialog.setMessage("설치 창을 여는 중…") }
                    main.postDelayed({ if (dialog.isShowing) dialog.dismiss(); updating = false }, 4000)
                } catch (t: Throwable) {
                    runOnUiThread {
                        dialog.dismiss()
                        updating = false
                        toast("업데이트 실패: ${t.message}")
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        if (SessionHub.anyActive) {
            AlertDialog.Builder(this)
                .setTitle("업데이트")
                .setMessage("설치하는 동안 앱이 다시 시작돼서 지금 잠수 중인 접속이 끊깁니다. 계속할까요?")
                .setPositiveButton("업데이트") { _, _ -> go() }
                .setNegativeButton("나중에", null)
                .show()
        } else {
            go()
        }
    }
}
