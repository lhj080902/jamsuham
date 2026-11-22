package kr.jamsuham.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kr.jamsuham.core.via.ViaSetup

/** 서버 추가/편집: 이름, 주소, 버전, 계정, 접속 후 자동 명령어, 재접속 옵션 */
class ServerEditActivity : BaseActivity() {

    private lateinit var ui: Ui
    private lateinit var store: Store
    private lateinit var accounts: AccountStore
    private var original: ServerEntry? = null

    private lateinit var nameInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var versionSpinner: Spinner
    private lateinit var accountSpinner: Spinner
    private lateinit var commandsInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var rulesInput: EditText
    private lateinit var autoReconnect: Switch
    private lateinit var reconnectOnKick: Switch
    private lateinit var packGlyphs: Switch
    private lateinit var resumeOnBoot: Switch
    private lateinit var packWifiOnly: Switch

    private val versionNames = ArrayList<String>()
    private var accountList: List<Account> = emptyList()
    private var pendingAccountId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = Store(this)
        accounts = AccountStore(this)
        original = intent.getStringExtra(EXTRA_ID)?.let { store.server(it) }
        val s = original ?: ServerEntry()
        pendingAccountId = s.accountId ?: accounts.list().firstOrNull()?.id

        val root = ui.vertical()
        val header = ui.horizontal()
        header.addView(ui.button("←", "ghost") { finish() }, ui.lp(ui.dp(48), ui.dp(44)))
        header.addView(ui.title(if (original == null) "서버 추가" else "서버 설정"), ui.lp(0, Ui.WRAP, 1f))
        header.addView(ui.button("저장", "primary") { save() }, ui.lp(Ui.WRAP, ui.dp(42)))
        root.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val form = ui.vertical()

        // 서버
        val serverCard = ui.card()
        serverCard.addView(ui.section("서버"))
        serverCard.addView(ui.label("이름 (목록에 보이는 이름)"))
        nameInput = ui.input("예: 메인서버").apply { setText(s.name) }
        serverCard.addView(nameInput, ui.lp())
        serverCard.addView(ui.label("주소"))
        addressInput = ui.input("예: play.example.kr 또는 1.2.3.4:25565").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(s.address)
        }
        serverCard.addView(addressInput, ui.lp())
        serverCard.addView(ui.label("버전"))
        versionNames.add(AUTO_LABEL)
        try {
            ViaSetup.selectableVersions().forEach { versionNames.add(it.name) }
        } catch (t: Throwable) {
            SessionHub.log.e("버전 목록 오류", t)
        }
        versionSpinner = spinner(versionNames)
        versionSpinner.setSelection(s.fixedVersion?.let { versionNames.indexOf(it).coerceAtLeast(0) } ?: 0)
        serverCard.addView(versionSpinner, ui.lp(Ui.MATCH, ui.dp(48)))
        serverCard.addView(ui.hint("자동 감지는 서버가 알려 주는 버전으로 접속합니다. 접속이 안 되면 버전을 직접 골라 보세요."))
        form.addView(serverCard, ui.lp())

        // 계정
        val accountCard = ui.card()
        accountCard.addView(ui.section("계정"))
        accountSpinner = Spinner(this)
        accountCard.addView(accountSpinner, ui.lp(Ui.MATCH, ui.dp(48)))
        accountCard.addView(ui.button("계정 관리 / 추가", "plain") {
            startActivityForResult(Intent(this, AccountsActivity::class.java), REQ_ACCOUNTS)
        }, ui.gap(8))
        form.addView(accountCard, ui.gap(10))

        // 자동 명령어
        val cmdCard = ui.card()
        cmdCard.addView(ui.section("접속 후 자동 실행"))
        commandsInput = ui.input("예: /잠수\n/server afk", singleLine = false).apply {
            minLines = 2
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(s.joinCommands.joinToString("\n"))
        }
        cmdCard.addView(commandsInput, ui.lp())
        val delayRow = ui.horizontal().apply { setPadding(0, ui.dp(10), 0, 0) }
        delayRow.addView(ui.text("접속 후 첫 명령어까지", 14f), ui.lp(0, Ui.WRAP, 1f))
        delayInput = ui.input("3").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setText(s.joinDelaySec.toString())
        }
        delayRow.addView(delayInput, ui.lp(ui.dp(70), Ui.WRAP))
        delayRow.addView(ui.text("초", 14f).apply { setPadding(ui.dp(6), 0, 0, 0) })
        cmdCard.addView(delayRow, ui.lp())
        val intervalRow = ui.horizontal().apply { setPadding(0, ui.dp(6), 0, 0) }
        intervalRow.addView(ui.text("명령어 사이 간격", 14f), ui.lp(0, Ui.WRAP, 1f))
        intervalInput = ui.input("3").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setText(s.joinIntervalSec.toString())
        }
        intervalRow.addView(intervalInput, ui.lp(ui.dp(70), Ui.WRAP))
        intervalRow.addView(ui.text("초", 14f).apply { setPadding(ui.dp(6), 0, 0, 0) })
        cmdCard.addView(intervalRow, ui.lp())
        cmdCard.addView(ui.hint("한 줄에 하나씩 적으면 접속·재접속할 때마다 위에서부터 차례로 보냅니다. " +
            "재접속하면 로비로 돌아가는 서버에서 잠수 서버로 다시 이동할 때 쓰세요.\n" +
            "명령어가 가끔 무시되면 서버가 너무 빠른 입력을 막는 것이니 '명령어 사이 간격'을 늘려 주세요 (예: 5초)."))
        form.addView(cmdCard, ui.gap(10))

        // 메뉴 자동 클릭
        val ruleCard = ui.card()
        ruleCard.addView(ui.section("메뉴 자동 클릭"))
        rulesInput = ui.input("예: 리소스팩 > 적용", singleLine = false).apply {
            minLines = 2
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(s.autoClickRules.joinToString("\n"))
        }
        ruleCard.addView(rulesInput, ui.lp())
        ruleCard.addView(ui.hint("서버가 상자 같은 메뉴 창을 띄우면 규칙대로 자동으로 누릅니다. 한 줄에 하나씩:\n" +
            "  메뉴 제목에 들어간 글자 > 누를 아이템 이름(또는 설명)에 들어간 글자\n" +
            "  예) 리소스팩 > 적용      제목 상관없이: * > 적용      칸 번호로: 리소스팩 > #13\n" +
            "채팅 화면의 메뉴 창에서 아이템을 길게 눌러도 규칙이 추가됩니다."))
        form.addView(ruleCard, ui.gap(10))

        // 재접속
        val optCard = ui.card()
        optCard.addView(ui.section("재접속"))
        autoReconnect = ui.switch("튕기면 자동으로 다시 접속").apply { isChecked = s.autoReconnect }
        reconnectOnKick = ui.switch("서버가 내보내도 다시 접속 (차단·화이트리스트 제외)").apply { isChecked = s.reconnectOnKick }
        optCard.addView(autoReconnect)
        optCard.addView(reconnectOnKick)
        resumeOnBoot = ui.switch("폰을 다시 켜거나 앱이 업데이트되면 이어서 잠수").apply { isChecked = s.resumeOnBoot }
        optCard.addView(resumeOnBoot)
        optCard.addView(ui.hint("직접 '끊기'를 누른 경우에는 다시 접속하지 않습니다. 폰 전원이 꺼져 있는 동안은 끊기지만, 위 옵션을 켜 두면 다시 켰을 때 이어서 접속합니다. " +
            "\"이미 프록시에 접속 중\"이라고 나오면 서버가 예전 접속을 정리할 때까지 기다렸다 다시 시도합니다."))
        form.addView(optCard, ui.gap(10))

        // 리소스팩 그림 글자
        val packCard = ui.card()
        packCard.addView(ui.section("리소스팩 그림 글자"))
        packGlyphs = ui.switch("리소스팩 그림 글자(칭호·아이콘) 표시").apply { isChecked = s.packGlyphs }
        packWifiOnly = ui.switch("큰 리소스팩은 와이파이에서만 받기").apply { isChecked = s.packWifiOnly }
        packCard.addView(packGlyphs)
        packCard.addView(packWifiOnly)
        packCard.addView(ui.hint("서버 리소스팩에서 글꼴 그림만 골라 받습니다(보통 1MB 이하). 부분 다운로드가 안 되는 팩은 통째로 받아야 해서, 위 옵션을 켜 두면 와이파이에서만 받습니다."))
        form.addView(packCard, ui.gap(10))

        if (original != null) {
            form.addView(ui.button("이 서버 삭제", "danger") { confirmDelete() }, ui.gap(16))
        }
        scroll.addView(form)
        root.addView(scroll, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(8) })
        setupWindow(root, ui.p)
        setContentView(root)
        loadAccounts()
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@ServerEditActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(ui.p.text)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadAccounts() {
        accountList = accounts.list()
        val labels = if (accountList.isEmpty()) listOf("계정 없음 - 아래 버튼으로 추가") else accountList.map { it.label }
        accountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        accountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(ui.p.text)
                pendingAccountId = accountList.getOrNull(position)?.id
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val idx = accountList.indexOfFirst { it.id == pendingAccountId }
        if (idx >= 0) accountSpinner.setSelection(idx)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ACCOUNTS) {
            data?.getStringExtra(AccountsActivity.EXTRA_SELECTED)?.let { pendingAccountId = it }
            if (accounts.get(pendingAccountId) == null) pendingAccountId = accounts.list().firstOrNull()?.id
            loadAccounts()
        }
    }

    private fun save() {
        val address = addressInput.text.toString().trim()
        if (address.isEmpty()) {
            toast("서버 주소를 입력해 주세요")
            return
        }
        val pos = versionSpinner.selectedItemPosition
        val entry = (original ?: ServerEntry()).copy(
            name = nameInput.text.toString().trim(),
            address = address,
            fixedVersion = if (pos <= 0) null else versionNames[pos],
            accountId = pendingAccountId,
            joinCommands = commandsInput.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(10),
            joinDelaySec = delayInput.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 3,
            joinIntervalSec = intervalInput.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 3,
            autoClickRules = rulesInput.text.toString().lines().map { it.trim() }.filter { it.contains('>') }.take(20),
            autoReconnect = autoReconnect.isChecked,
            reconnectOnKick = reconnectOnKick.isChecked,
            packGlyphs = packGlyphs.isChecked,
            resumeOnBoot = resumeOnBoot.isChecked,
            packWifiOnly = packWifiOnly.isChecked,
        )
        store.saveServer(entry)
        if (SessionHub.isActive(entry.id)) toast("저장했습니다. 바뀐 설정은 다음 접속부터 적용됩니다") else toast("저장했습니다")
        finish()
    }

    private fun confirmDelete() {
        val s = original ?: return
        AlertDialog.Builder(this)
            .setTitle("서버 삭제")
            .setMessage("'${s.displayName}' 을(를) 목록에서 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                if (SessionHub.isActive(s.id)) AfkService.stop(this, s.id)
                store.deleteServer(s.id)
                ServerInfos.forget(this, s.id)
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "server_id"
        private const val REQ_ACCOUNTS = 21
        private const val AUTO_LABEL = "자동 감지 (권장)"
    }
}
