package kr.jamsuham.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.via.ViaSetup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 화면은 XML 없이 코드로 만든다. 폭이 600dp 이상(태블릿, 가로 화면)이면 설정과 채팅을
 * 나란히, 그보다 좁으면(세로 폰) 탭으로 나눠 보여 준다.
 */
class MainActivity : Activity(), SessionHub.Observer {

    private lateinit var prefs: Prefs
    private lateinit var accounts: AccountStore
    private val main = Handler(Looper.getMainLooper())

    // 패널
    private lateinit var root: LinearLayout
    private lateinit var topPanel: LinearLayout
    private lateinit var settingsPanel: ScrollView
    private lateinit var chatPanel: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var tabSettings: Button
    private lateinit var tabChat: Button
    private var showChatTab = false

    // 상태
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var actionButton: Button

    // 설정
    private lateinit var addressInput: EditText
    private lateinit var versionSpinner: Spinner
    private lateinit var accountGroup: RadioGroup
    private lateinit var radioMs: RadioButton
    private lateinit var radioOffline: RadioButton
    private lateinit var msRow: LinearLayout
    private lateinit var msName: TextView
    private lateinit var msButton: Button
    private lateinit var offlineInput: EditText
    private lateinit var autoReconnect: Switch
    private lateinit var reconnectOnKick: Switch
    private lateinit var batteryButton: Button
    private lateinit var joinCommandsInput: EditText
    private val versionNames = ArrayList<String>()

    // 채팅
    private lateinit var chatList: ListView
    private lateinit var chatInput: EditText
    private lateinit var sendButton: Button
    private val chatAdapter = ChatAdapter()

    private var loading = false
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    private var loginThread: Thread? = null
    private var loginDialog: AlertDialog? = null

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus(SessionHub.status)
            main.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        accounts = AccountStore(this)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = C_BG
            @Suppress("DEPRECATION")
            window.navigationBarColor = C_BG
        }

        buildViews()
        loadSettings()
        showChatTab = SessionHub.isActive
        arrange()
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        SessionHub.addObserver(this)
        chatAdapter.reload()
        renderStatus(SessionHub.status)
        updateBatteryButton()
        main.post(ticker)
    }

    override fun onStop() {
        SessionHub.removeObserver(this)
        main.removeCallbacks(ticker)
        saveSettings()
        super.onStop()
    }

    override fun onDestroy() {
        loginDialog?.dismiss()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    // ------------------------------------------------------------ observer

    override fun onStatus(status: SessionStatus) {
        renderStatus(status)
        if (status.state == SessionState.ONLINE && !isWide() && !showChatTab) {
            showChatTab = true
            arrange()
        }
    }

    override fun onLine(line: LogLine) {
        chatAdapter.add(line)
    }

    // ------------------------------------------------------------ layout

    private fun isWide(): Boolean = resources.configuration.screenWidthDp >= 600

    private fun arrange() {
        listOf(topPanel, settingsPanel, chatPanel, tabBar).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        root.removeAllViews()
        applyPadding()
        if (isWide()) {
            root.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(topPanel, LinearLayout.LayoutParams(-1, -2))
            left.addView(settingsPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            root.addView(left, LinearLayout.LayoutParams(0, -1, 4f))
            settingsPanel.visibility = View.VISIBLE
            chatPanel.visibility = View.VISIBLE
            root.addView(chatPanel, LinearLayout.LayoutParams(0, -1, 6f).apply { leftMargin = dp(12) })
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.addView(topPanel, LinearLayout.LayoutParams(-1, -2))
            root.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            root.addView(settingsPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            root.addView(chatPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            settingsPanel.visibility = if (showChatTab) View.GONE else View.VISIBLE
            chatPanel.visibility = if (showChatTab) View.VISIBLE else View.GONE
            styleTab(tabSettings, !showChatTab)
            styleTab(tabChat, showChatTab)
        }
    }

    private fun applyPadding() {
        val pad = dp(12)
        root.setPadding(pad + insetLeft, pad + insetTop, pad + insetRight, pad + insetBottom)
    }

    private fun buildViews() {
        root = LinearLayout(this).apply {
            setBackgroundColor(C_BG)
            // 안드로이드 15+ 는 화면 끝까지 그리므로 상태바/내비게이션바/키보드 영역만큼 직접 띄운다
            setOnApplyWindowInsetsListener { _, insets ->
                // 안드로이드 14 이하는 시스템이 알아서 띄워 주므로 그대로 둔다
                if (Build.VERSION.SDK_INT >= 35) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                    insetLeft = bars.left; insetTop = bars.top; insetRight = bars.right; insetBottom = bars.bottom
                }
                applyPadding()
                insets
            }
        }

        // --- 상단: 제목 + 상태 카드
        topPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("잠수함", 24f, C_TEXT, bold = true))
        titles.addView(text("마인크래프트 잠수 클라이언트", 13f, C_MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        topPanel.addView(header)

        val statusCard = card()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply { background = circle(C_MUTED) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
        statusTitle = text("대기 중", 18f, C_TEXT, bold = true)
        statusRow.addView(statusTitle, LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(statusRow)
        statusDetail = text("", 13f, C_MUTED).apply { setPadding(0, dp(6), 0, dp(10)) }
        statusCard.addView(statusDetail)
        actionButton = button("잠수 시작", primary = true) { onActionPressed() }
        statusCard.addView(actionButton, LinearLayout.LayoutParams(-1, dp(52)))
        topPanel.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // --- 탭 (좁은 화면)
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabSettings = button("설정", primary = false) { showChatTab = false; arrange() }
        tabChat = button("채팅 / 기록", primary = false) { showChatTab = true; arrange() }
        tabBar.addView(tabSettings, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        tabBar.addView(tabChat, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6) })

        // --- 설정
        settingsPanel = ScrollView(this).apply { isFillViewport = true }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val serverCard = card()
        serverCard.addView(sectionTitle("서버"))
        addressInput = input("예: play.example.kr 또는 1.2.3.4:25565").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        serverCard.addView(addressInput, LinearLayout.LayoutParams(-1, -2))
        serverCard.addView(label("버전"))
        versionSpinner = Spinner(this)
        versionNames.add(AUTO_LABEL)
        try {
            ViaSetup.selectableVersions().forEach { versionNames.add(it.name) }
        } catch (t: Throwable) {
            SessionHub.log.e("버전 목록을 불러오지 못했습니다", t)
        }
        versionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, versionNames)
        serverCard.addView(versionSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        serverCard.addView(hint("자동 감지는 서버가 알려 주는 버전으로 접속합니다. 접속이 안 되면 버전을 직접 골라 보세요."))
        settings.addView(serverCard, LinearLayout.LayoutParams(-1, -2))

        val accountCard = card()
        accountCard.addView(sectionTitle("계정"))
        accountGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        radioMs = RadioButton(this).apply { id = View.generateViewId(); text = "정품 (마이크로소프트 계정)"; setTextColor(C_TEXT) }
        radioOffline = RadioButton(this).apply { id = View.generateViewId(); text = "비정품 닉네임 (복돌 서버용)"; setTextColor(C_TEXT) }
        accountGroup.addView(radioMs)
        accountGroup.addView(radioOffline)
        accountCard.addView(accountGroup)
        msRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        msName = text("로그인 안 됨", 14f, C_TEXT)
        msRow.addView(msName, LinearLayout.LayoutParams(0, -2, 1f))
        msButton = button("로그인", primary = false) { onMicrosoftButton() }
        msRow.addView(msButton, LinearLayout.LayoutParams(-2, dp(42)))
        accountCard.addView(msRow)
        offlineInput = input("닉네임 (영문/숫자/_ 3~16자)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        accountCard.addView(offlineInput, LinearLayout.LayoutParams(-1, -2))
        accountGroup.setOnCheckedChangeListener { _, _ -> updateAccountRows() }
        settings.addView(accountCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val commandCard = card()
        commandCard.addView(sectionTitle("접속 후 자동 실행"))
        joinCommandsInput = input("예: /잠수\n/server afk").apply {
            setSingleLine(false)
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        commandCard.addView(joinCommandsInput, LinearLayout.LayoutParams(-1, -2))
        commandCard.addView(hint("한 줄에 하나씩 적으면 접속(재접속 포함)할 때마다 3초 뒤부터 차례로 보냅니다. " +
            "재접속하면 로비로 돌아가는 서버에서 잠수 서버로 다시 이동할 때 쓰세요."))
        settings.addView(commandCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val optionCard = card()
        optionCard.addView(sectionTitle("재접속"))
        autoReconnect = switch("튕기면 자동으로 다시 접속")
        reconnectOnKick = switch("서버가 내보내도 다시 접속 (차단·화이트리스트 제외)")
        optionCard.addView(autoReconnect)
        optionCard.addView(reconnectOnKick)
        optionCard.addView(hint("직접 '연결 끊기'를 누르거나 폰 전원이 꺼진 경우에는 다시 접속하지 않습니다. " +
            "\"이미 프록시에 접속 중\"이라고 나오면 서버가 예전 접속을 정리할 때까지 기다렸다 다시 시도합니다."))
        batteryButton = button("배터리 최적화 예외로 설정 (화면 꺼짐 중 끊김 방지)", primary = false) { requestBatteryExemption() }
        optionCard.addView(batteryButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        settings.addView(optionCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        settingsPanel.addView(settings)

        // --- 채팅
        chatPanel = card().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val chatHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chatHeader.addView(sectionTitle("채팅 / 기록"), LinearLayout.LayoutParams(0, -2, 1f))
        chatHeader.addView(button("지우기", primary = false) {
            SessionHub.clearLines()
            chatAdapter.reload()
        }, LinearLayout.LayoutParams(-2, dp(38)))
        chatPanel.addView(chatHeader)
        chatList = ListView(this).apply {
            adapter = chatAdapter
            divider = null
            isStackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            setBackgroundColor(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
        }
        chatPanel.addView(chatList, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chatInput = input("채팅 또는 /명령어").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    sendChat()
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(chatInput, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = button("전송", primary = true) { sendChat() }
        inputRow.addView(sendButton, LinearLayout.LayoutParams(-2, dp(46)).apply { leftMargin = dp(8) })
        chatPanel.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    // ------------------------------------------------------------ settings

    private fun loadSettings() {
        loading = true
        addressInput.setText(prefs.address)
        val fixed = prefs.fixedVersion
        val idx = if (fixed == null) 0 else versionNames.indexOf(fixed).coerceAtLeast(0)
        versionSpinner.setSelection(idx)
        versionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(C_TEXT)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (prefs.useMicrosoft) radioMs.isChecked = true else radioOffline.isChecked = true
        offlineInput.setText(prefs.offlineName)
        joinCommandsInput.setText(prefs.joinCommands)
        autoReconnect.isChecked = prefs.autoReconnect
        reconnectOnKick.isChecked = prefs.reconnectOnKick
        updateAccountRows()
        loading = false
    }

    private fun saveSettings() {
        if (loading) return
        prefs.address = addressInput.text.toString()
        val pos = versionSpinner.selectedItemPosition
        prefs.fixedVersion = if (pos <= 0) null else versionNames[pos]
        prefs.useMicrosoft = radioMs.isChecked
        prefs.offlineName = offlineInput.text.toString()
        prefs.joinCommands = joinCommandsInput.text.toString()
        prefs.autoReconnect = autoReconnect.isChecked
        prefs.reconnectOnKick = reconnectOnKick.isChecked
    }

    private fun updateAccountRows() {
        val ms = radioMs.isChecked
        msRow.visibility = if (ms) View.VISIBLE else View.GONE
        offlineInput.visibility = if (ms) View.GONE else View.VISIBLE
        val name = accounts.microsoftName
        msName.text = if (name != null) "로그인됨: $name" else "로그인 안 됨"
        msButton.text = if (name != null) "로그아웃" else "로그인"
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        listOf<View>(addressInput, versionSpinner, radioMs, radioOffline, msButton, offlineInput, joinCommandsInput, autoReconnect, reconnectOnKick)
            .forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.5f }
    }

    // ------------------------------------------------------------ actions

    private fun onActionPressed() {
        if (SessionHub.isActive) {
            AfkService.stop(this)
            return
        }
        saveSettings()
        if (prefs.address.isBlank()) {
            toast("서버 주소를 입력해 주세요")
            return
        }
        if (prefs.useMicrosoft && !accounts.hasMicrosoft) {
            toast("먼저 마이크로소프트 계정으로 로그인해 주세요")
            return
        }
        if (!prefs.useMicrosoft && !AccountStore.NAME_REGEX.matches(prefs.offlineName)) {
            toast("닉네임은 영문/숫자/_ 3~16자로 입력해 주세요")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        SessionHub.clearLines()
        chatAdapter.reload()
        try {
            AfkService.start(this)
        } catch (t: Throwable) {
            toast("접속 서비스를 시작하지 못했습니다: ${t.message}")
            return
        }
        if (!isWide()) {
            showChatTab = true
            arrange()
        }
    }

    private fun sendChat() {
        val msg = chatInput.text.toString().trim()
        if (msg.isEmpty()) return
        if (SessionHub.status.state != SessionState.ONLINE) {
            toast("서버에 접속한 뒤에 보낼 수 있습니다")
            return
        }
        if (SessionHub.sendChat(msg)) {
            chatInput.setText("")
            SessionHub.note("나: $msg")
        } else {
            toast("보내지 못했습니다")
        }
    }

    private fun onMicrosoftButton() {
        if (accounts.hasMicrosoft) {
            AlertDialog.Builder(this)
                .setTitle("로그아웃")
                .setMessage("저장된 마이크로소프트 로그인 정보를 지울까요?")
                .setPositiveButton("로그아웃") { _, _ ->
                    accounts.logout()
                    updateAccountRows()
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        if (loginThread?.isAlive == true) return
        val waiting = AlertDialog.Builder(this)
            .setTitle("마이크로소프트 로그인")
            .setMessage("로그인 코드를 받는 중…")
            .setNegativeButton("취소") { _, _ -> loginThread?.interrupt() }
            .setCancelable(false)
            .show()
        loginDialog = waiting
        loginThread = Thread {
            try {
                val name = accounts.loginWithDeviceCode { code ->
                    runOnUiThread { showDeviceCode(code.userCode, code.directVerificationUri, code.verificationUri) }
                }
                runOnUiThread {
                    loginDialog?.dismiss()
                    updateAccountRows()
                    toast("$name 계정으로 로그인했습니다")
                }
            } catch (_: InterruptedException) {
                runOnUiThread { loginDialog?.dismiss() }
            } catch (t: Throwable) {
                SessionHub.log.w("로그인 실패", t)
                runOnUiThread {
                    loginDialog?.dismiss()
                    if (!isFinishing) {
                        AlertDialog.Builder(this)
                            .setTitle("로그인 실패")
                            .setMessage(t.message ?: t.javaClass.simpleName)
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
            }
        }.apply {
            name = "ms-login"
            isDaemon = true
            start()
        }
    }

    private fun showDeviceCode(userCode: String, directUri: String, uri: String) {
        loginDialog?.dismiss()
        if (isFinishing) return
        val msg = "1. 아래 버튼을 눌러 브라우저를 엽니다 (코드는 자동으로 복사됩니다)\n" +
            "2. 코드가 미리 입력되어 있지 않으면 붙여넣기\n" +
            "3. 마인크래프트를 산 마이크로소프트 계정으로 로그인\n\n" +
            "코드: $userCode\n주소: $uri\n\n로그인을 마치면 이 창이 자동으로 닫힙니다."
        loginDialog = AlertDialog.Builder(this)
            .setTitle("마이크로소프트 로그인")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("코드 복사 + 브라우저 열기", null)
            .setNegativeButton("취소") { _, _ -> loginThread?.interrupt() }
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("코드", userCode))
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(directUri)))
                    } catch (_: Throwable) {
                        toast("브라우저를 열 수 없습니다. $uri 에 직접 접속해 주세요")
                    }
                }
            }
    }

    private fun requestBatteryExemption() {
        try {
            @Suppress("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                toast("설정 화면을 열 수 없습니다")
            }
        }
    }

    private fun updateBatteryButton() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryButton.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ status

    private fun renderStatus(s: SessionStatus) {
        val (title, color) = when (s.state) {
            SessionState.IDLE -> "대기 중" to C_MUTED
            SessionState.PREPARING -> "준비 중" to C_WARN
            SessionState.CONNECTING -> "접속 중…" to C_WARN
            SessionState.ONLINE -> "잠수 중" to C_OK
            SessionState.WAITING_RECONNECT -> "재접속 대기" to C_WARN
            SessionState.STOPPED -> "멈춤" to C_MUTED
        }
        statusTitle.text = title
        (statusDot.background as? GradientDrawable)?.setColor(color)
        val now = System.currentTimeMillis()
        val parts = ArrayList<String>()
        when (s.state) {
            SessionState.ONLINE -> s.onlineSinceMs?.let { parts.add("이번 접속 ${Formats.clock(now - it)}") }
            SessionState.WAITING_RECONNECT -> {
                val left = ((s.nextAttemptAtMs ?: now) - now).coerceAtLeast(0) / 1000
                parts.add("${left}초 뒤 다시 접속")
            }
            else -> if (s.message.isNotBlank()) parts.add(s.message)
        }
        val total = s.totalOnlineMs + (if (s.state == SessionState.ONLINE) s.onlineSinceMs?.let { now - it } ?: 0 else 0)
        if (total > 0) parts.add("누적 ${Formats.duration(total)}")
        if (s.reconnectCount > 0) parts.add("재접속 ${s.reconnectCount}회")
        s.serverVersion?.let { parts.add("버전 $it") }
        statusDetail.text = parts.joinToString("  ·  ")

        val active = SessionHub.isActive
        actionButton.text = if (active) "연결 끊기" else "잠수 시작"
        styleButton(actionButton, primary = !active, danger = active)
        setSettingsEnabled(!active)
    }

    // ------------------------------------------------------------ chat list

    private inner class ChatAdapter : BaseAdapter() {
        private val items = ArrayList<LogLine>()
        private val time = SimpleDateFormat("HH:mm", Locale.KOREA)

        fun reload() {
            items.clear()
            items.addAll(SessionHub.lines)
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
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(2), dp(3), dp(2), dp(3))
                setTextIsSelectable(false)
            }
            val line = items[position]
            val sb = SpannableStringBuilder()
            appendColored(sb, time.format(Date(line.timeMs)) + "  ", C_MUTED, false)
            val base = when (line.kind) {
                LogLine.Kind.CHAT -> C_TEXT
                LogLine.Kind.SYSTEM -> C_INFO
                LogLine.Kind.ERROR -> C_ERR
            }
            for (span in line.text.spans) {
                val c = if (line.kind == LogLine.Kind.CHAT) readable(span.color ?: base) else base
                appendColored(sb, span.text, c, span.bold)
            }
            tv.text = sb
            return tv
        }

        private fun appendColored(sb: SpannableStringBuilder, s: String, color: Int, bold: Boolean) {
            val start = sb.length
            sb.append(s)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /** 어두운 배경에서 안 보이는 검정/남색 글자는 밝게 */
        private fun readable(rgb: Int): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b)
            val c = if (lum < 70) 0xB0B8C4 else rgb
            return 0xFF000000.toInt() or c
        }
    }

    // ------------------------------------------------------------ view helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionTitle(s: String) = text(s, 16f, C_ACCENT, bold = true).apply { setPadding(0, 0, 0, dp(8)) }
    private fun label(s: String) = text(s, 13f, C_MUTED).apply { setPadding(0, dp(10), 0, dp(2)) }
    private fun hint(s: String) = text(s, 12f, C_MUTED).apply { setPadding(0, dp(6), 0, 0) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(C_CARD)
            cornerRadius = dp(16).toFloat()
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(C_MUTED)
        setTextColor(C_TEXT)
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = GradientDrawable().apply {
            setColor(C_INPUT)
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun switch(s: String) = Switch(this).apply {
        text = s
        setTextColor(C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _: CompoundButton, _: Boolean -> saveSettings() }
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        stateListAnimator = null
        setOnClickListener { onClick() }
        styleButton(this, primary, false)
    }

    private fun styleButton(b: Button, primary: Boolean, danger: Boolean) {
        val bg = when {
            danger -> C_ERR
            primary -> C_ACCENT
            else -> C_INPUT
        }
        b.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(12).toFloat()
        }
        b.setTextColor(if (primary && !danger) C_BG else C_TEXT)
        b.setPadding(dp(14), 0, dp(14), 0)
    }

    private fun styleTab(b: Button, selected: Boolean) {
        b.background = GradientDrawable().apply {
            setColor(if (selected) C_ACCENT else C_CARD)
            cornerRadius = dp(12).toFloat()
        }
        b.setTextColor(if (selected) C_BG else C_TEXT)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_NOTIFICATIONS = 10
        private const val AUTO_LABEL = "자동 감지 (권장)"

        private val C_BG = Color.parseColor("#0E1621")
        private val C_CARD = Color.parseColor("#172231")
        private val C_INPUT = Color.parseColor("#22324A")
        private val C_TEXT = Color.parseColor("#E6EDF3")
        private val C_MUTED = Color.parseColor("#8A99AD")
        private val C_ACCENT = Color.parseColor("#FFC93C")
        private val C_OK = Color.parseColor("#3DDC84")
        private val C_WARN = Color.parseColor("#FFB020")
        private val C_ERR = Color.parseColor("#FF6B6B")
        private val C_INFO = Color.parseColor("#8FB8DE")
    }
}
