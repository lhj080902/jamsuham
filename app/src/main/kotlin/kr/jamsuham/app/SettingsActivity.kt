package kr.jamsuham.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 설정: 계정 · 화면(디자인 3종, 밝기, 언어, 글자 크기) · 채팅 · 새 서버 기본값(리소스팩, 이어서 잠수) · 알림 · 저장 공간 · 앱 정보.
 * 화면 모양이 바뀌는 설정은 고르면 바로 이 화면을 다시 만들어 보여 준다 (돌아가면 다른 화면도 다시 그려짐).
 */
class SettingsActivity : BaseActivity() {

    private lateinit var ui: Ui
    private lateinit var accounts: AccountsSection
    /** 스크롤 위치를 다시 만든 뒤에도 유지 */
    private var scroll: ScrollView? = null

    override fun settingsAffectLayout() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build(savedInstanceState?.getInt(KEY_SCROLL) ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, scroll?.scrollY ?: 0)
    }

    private fun build(scrollY: Int) {
        ui = Ui(this)
        val p = ui.p
        val root = ui.vertical()
        val header = ui.horizontal()
        header.addView(ui.button("←", "ghost") { finish() }, ui.lp(ui.dp(48), ui.dp(44)))
        header.addView(ui.title(tr("설정", "Settings")), ui.lp(0, Ui.WRAP, 1f))
        root.addView(header)

        val body = ui.vertical()

        // ---------------------------------------------------------------- 계정
        accounts = AccountsSection(this, ui)
        body.addView(accounts.listCard, ui.lp())
        body.addView(accounts.addCard, ui.gap(10))

        // ---------------------------------------------------------------- 클라우드 잠수
        body.addView(CloudSection(this, ui).card, ui.gap(10))

        // ---------------------------------------------------------------- 화면
        val look = ui.card()
        look.addView(ui.section(tr("화면", "Appearance")))
        look.addView(ui.label(tr("디자인", "Design")))
        look.addView(themePicker())
        look.addView(ui.label(tr("밝기", "Brightness")))
        look.addView(choices(AppSettings.Night.entries, AppSettings.night, { tr(it.ko, it.en) }) { AppSettings.setNight(this, it); rebuild() })
        look.addView(ui.label(tr("언어 · Language", "Language · 언어")))
        look.addView(choices(AppSettings.Language.entries, AppSettings.language, { tr(it.ko, it.en) }) { AppSettings.setLanguage(it); rebuild() })
        look.addView(ui.hint(tr(
            "서버가 보내는 채팅과 메뉴 글자는 서버가 정한 언어 그대로 나옵니다.",
            "Chat and menu text sent by the server stays in the server's language.",
        )))
        look.addView(ui.label(tr("글자 크기", "Text size")))
        look.addView(choices(AppSettings.TextScale.entries, AppSettings.textScale, { tr(it.ko, it.en) }) { AppSettings.setTextScale(it); rebuild() })
        body.addView(look, ui.gap(10))

        // ---------------------------------------------------------------- 채팅
        val chat = ui.card()
        chat.addView(ui.section(tr("채팅", "Chat")))
        chat.addView(ui.label(tr("채팅 글자 크기", "Chat text size")))
        val sizeRow = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val sample = ui.text("", 14f)
        fun showSize() {
            sample.text = tr("가나다 ABC 123 · ${AppSettings.chatTextSp}", "Hello ABC 123 · ${AppSettings.chatTextSp}")
            sample.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, AppSettings.chatTextSp.toFloat())
        }
        showSize()
        sizeRow.addView(ui.button("−", "plain") { AppSettings.setChatTextSp(AppSettings.chatTextSp - 1); showSize() }, ui.lp(ui.dp(48), ui.dp(42)))
        sizeRow.addView(sample, ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(12) })
        sizeRow.addView(ui.button("+", "plain") { AppSettings.setChatTextSp(AppSettings.chatTextSp + 1); showSize() }, ui.lp(ui.dp(48), ui.dp(42)))
        chat.addView(sizeRow)
        chat.addView(toggle(tr("채팅 줄 앞에 시간 표시", "Show time before each chat line"), AppSettings.chatTimestamps) { AppSettings.setChatTimestamps(it) })
        chat.addView(toggle(tr("채팅 화면을 보는 동안 화면 켜 두기", "Keep the screen on while viewing chat"), AppSettings.keepScreenOn) { AppSettings.setKeepScreenOn(it) })
        body.addView(chat, ui.gap(10))

        // ---------------------------------------------------------------- 새 서버 기본값
        val defaults = ui.card()
        defaults.addView(ui.section(tr("새 서버 기본값", "Defaults for new servers")))
        defaults.addView(toggle(tr("리소스팩 그림 글자(칭호·아이콘) 표시", "Show resource pack glyphs (titles, icons)"), AppSettings.defaultPackGlyphs) { AppSettings.setDefaultPackGlyphs(it) })
        defaults.addView(toggle(tr("큰 리소스팩은 와이파이에서만 받기", "Download large packs on Wi-Fi only"), AppSettings.defaultPackWifiOnly) { AppSettings.setDefaultPackWifiOnly(it) })
        defaults.addView(toggle(tr("폰을 다시 켜거나 앱이 업데이트되면 이어서 잠수", "Resume AFK after reboot or app update"), AppSettings.defaultResumeOnBoot) { AppSettings.setDefaultResumeOnBoot(it) })
        defaults.addView(ui.hint(tr(
            "서버를 새로 추가할 때 이 값으로 시작합니다. 서버마다 서버 설정에서 따로 바꿀 수 있습니다.",
            "New servers start with these values. You can still change them per server.",
        )))
        defaults.addView(ui.button(tr("지금 있는 서버에도 모두 적용", "Apply to all existing servers"), "plain") { applyToAll() }, ui.gap(10).apply { height = ui.dp(44) })
        body.addView(defaults, ui.gap(10))

        // ---------------------------------------------------------------- 데이터
        val data = ui.card()
        data.addView(ui.section(tr("데이터 절약", "Data saver")))
        data.addView(choices(AppSettings.DataSaver.entries, AppSettings.dataSaver, { tr(it.ko, it.en) }) { AppSettings.setDataSaver(it); rebuild() })
        data.addView(ui.hint(tr(
            "켜 두면 접속자 얼굴 그림과 리소스팩 그림 글자를 새로 받지 않습니다 (이미 받아 둔 것은 그대로 보임). " +
                "지형은 원래부터 가장 적게(시야 2청크) 받고 있고, 주변 사람·몹 움직임처럼 서버가 보내는 데이터는 줄일 수 없습니다.",
            "When on, player faces and resource pack glyphs aren't downloaded (already saved ones still show). " +
                "Terrain is already kept to the minimum (2-chunk view distance); data the server sends, such as nearby players and mobs, can't be reduced.",
        )))
        body.addView(data, ui.gap(10))

        // ---------------------------------------------------------------- 알림
        val notify = ui.card()
        notify.addView(ui.section(tr("알림", "Notifications")))
        notify.addView(toggle(tr("잠수 알림에 데이터 사용량 표시", "Show data usage in the AFK notification"), AppSettings.notifyDataUsage) { AppSettings.setNotifyDataUsage(it) })
        notify.addView(ui.button(tr("안드로이드 알림 설정 열기", "Open Android notification settings"), "plain") {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Throwable) {
                toast(tr("설정 화면을 열 수 없습니다", "Couldn't open settings"))
            }
        }, ui.gap(8).apply { height = ui.dp(44) })
        body.addView(notify, ui.gap(10))

        // ---------------------------------------------------------------- 저장 공간
        val storage = ui.card()
        storage.addView(ui.section(tr("저장 공간", "Storage")))
        val packSize = ui.text(tr("리소스팩 그림 글자: 계산 중…", "Resource pack glyphs: calculating…"), 14f)
        val logSize = ui.text(tr("채팅 기록: 계산 중…", "Chat logs: calculating…"), 14f)
        storage.addView(packSize)
        storage.addView(ui.button(tr("그림 글자 캐시 비우기", "Clear glyph cache"), "plain") {
            confirm(tr("받아 둔 리소스팩 그림 글자를 지울까요? 다음 접속 때 다시 받습니다.", "Delete downloaded glyphs? They'll be downloaded again on next join.")) {
                Glyphs.clearCache(this)
                measure(packSize, logSize)
            }
        }, ui.gap(6).apply { height = ui.dp(42) })
        storage.addView(logSize, ui.gap(12))
        storage.addView(ui.button(tr("채팅 기록 모두 지우기", "Delete all chat logs"), "danger") {
            confirm(tr("모든 서버의 채팅 기록(최근 30일)을 지울까요? 되돌릴 수 없습니다.", "Delete chat logs of all servers (last 30 days)? This can't be undone.")) {
                ChatLog.deleteAll()
                // 지우는 작업이 끝난 뒤 다시 잰다
                window.decorView.postDelayed({ measure(packSize, logSize) }, 500)
            }
        }, ui.gap(6).apply { height = ui.dp(42) })
        body.addView(storage, ui.gap(10))
        measure(packSize, logSize)

        // ---------------------------------------------------------------- 앱
        val app = ui.card()
        app.addView(ui.section(tr("앱", "App")))
        app.addView(ui.text(tr("잠수크래프트 ", "Jamsucraft ") + Updater.versionLabel(this), 14f, p.muted))
        app.addView(ui.button(tr("정보 · 라이선스 · 개인정보처리방침", "About · Licenses · Privacy"), "plain") { AppInfo.show(this, ui) }, ui.gap(8).apply { height = ui.dp(44) })
        if (!Flavor.PLAY && Build.VERSION.SDK_INT >= 23) {
            app.addView(ui.button(tr("배터리 최적화 설정", "Battery optimization settings"), "plain") {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Throwable) {
                    toast(tr("설정 화면을 열 수 없습니다", "Couldn't open settings"))
                }
            }, ui.gap(8).apply { height = ui.dp(44) })
        }
        body.addView(app, ui.gap(10))

        val sv = ScrollView(this).apply { addView(body) }
        scroll = sv
        root.addView(sv, ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = ui.dp(8) })
        setupWindow(root, p)
        setContentView(root)
        if (scrollY > 0) sv.post { sv.scrollTo(0, scrollY) }
    }

    /** 디자인 고르기: 각 디자인의 색으로 그린 작은 견본 카드 */
    private fun themePicker(): HorizontalScrollView {
        val row = ui.horizontal()
        for (t in AppSettings.Theme.entries) {
            val sp = Palette(ui.p.dark, t)
            val r = sp.radius
            fun shape(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = ui.dp(radius) * r
                if (sp.stroke != 0) setStroke(ui.dp(2), sp.stroke)
            }
            val selected = t == AppSettings.theme
            val box = ui.vertical().apply {
                background = shape(sp.bg, 14).apply { if (selected) setStroke(ui.dp(3), ui.p.accent) }
                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                setOnClickListener {
                    if (!selected) {
                        AppSettings.setTheme(t)
                        rebuild()
                    }
                }
            }
            val miniCard = ui.vertical().apply {
                background = shape(sp.card, 10)
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8))
            }
            miniCard.addView(TextView(this).apply { text = tr("서버", "Server"); setTextColor(sp.text); textSize = 12f })
            miniCard.addView(TextView(this).apply { text = tr("● 잠수 중", "● AFK"); setTextColor(sp.ok); textSize = 10f })
            miniCard.addView(TextView(this).apply {
                text = tr("시작", "Start"); setTextColor(sp.onAccent); textSize = 11f; gravity = Gravity.CENTER
                background = shape(sp.accent, 8)
                setPadding(0, ui.dp(3), 0, ui.dp(3))
            }, ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(6) })
            box.addView(miniCard, ui.lp(ui.dp(104), Ui.WRAP))
            box.addView(TextView(this).apply {
                text = (if (selected) "✓ " else "") + tr(t.ko, t.en)
                setTextColor(sp.text); textSize = 12f
                setPadding(0, ui.dp(6), 0, 0)
            })
            row.addView(box, ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(8) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** 여러 개 중 하나 고르기 (칩 줄) */
    private fun <T> choices(options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit): HorizontalScrollView {
        val row = ui.horizontal()
        for (o in options) {
            val chip = ui.chip(label(o)) { if (o != current) onPick(o) }
            if (o == current) {
                chip.background = ui.rounded(ui.p.accent, 14)
                chip.setTextColor(ui.p.onAccent)
            }
            row.addView(chip, ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = ui.dp(6) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = ui.switch(label).apply {
        isChecked = value
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(tr("확인", "OK")) { _, _ -> action() }
            .setNegativeButton(tr("취소", "Cancel"), null)
            .show()
    }

    private fun applyToAll() {
        val store = Store(this)
        val list = store.servers()
        if (list.isEmpty()) {
            toast(tr("저장된 서버가 없습니다", "No saved servers"))
            return
        }
        confirm(tr("서버 ${list.size}개의 리소스팩·이어서 잠수 설정을 위 값으로 바꿀까요?", "Change resource pack and resume settings of ${list.size} servers to these values?")) {
            for (s in list) {
                store.saveServer(
                    s.copy(
                        packGlyphs = AppSettings.defaultPackGlyphs,
                        packWifiOnly = AppSettings.defaultPackWifiOnly,
                        resumeOnBoot = AppSettings.defaultResumeOnBoot,
                    ),
                )
            }
            toast(tr("적용했습니다", "Applied"))
        }
    }

    /** 용량은 파일을 훑어야 해서 백그라운드에서 잰다 */
    private fun measure(packSize: TextView, logSize: TextView) {
        Thread {
            val pack = Glyphs.cacheBytes(this)
            val log = ChatLog.totalBytes()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                packSize.text = tr("리소스팩 그림 글자: ", "Resource pack glyphs: ") + StatusPanel.bytes(pack)
                logSize.text = tr("채팅 기록: ", "Chat logs: ") + StatusPanel.bytes(log)
            }
        }.apply { isDaemon = true; start() }
    }

    /** 화면 모양이 바뀌는 설정: 이 화면을 바로 다시 만든다 */
    private fun rebuild() {
        val y = scroll?.scrollY ?: 0
        build(y)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        accounts.onLoginResult(requestCode, resultCode, data)
    }

    companion object {
        private const val KEY_SCROLL = "scroll"
    }
}
