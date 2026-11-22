package kr.jamsuham.app

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.Locale

/**
 * 앱 전체 설정 (설정 화면). 서버별 설정은 [ServerEntry] 에 있고, 여기 있는 리소스팩·재접속 값은 "새 서버의 기본값" 이다.
 * 화면이 자주 읽으므로 값은 메모리에 들고 있고, 바뀌면 [version] 이 올라가 열린 화면이 다시 그려진다.
 */
object AppSettings {
    /** 디자인 3종 */
    enum class Theme(val ko: String, val en: String) {
        CLASSIC("기본 (밤하늘)", "Classic (night sky)"),
        PIXEL("픽셀 (마인크래프트)", "Pixel (Minecraft)"),
        CLEAN("깔끔 (둥근 파랑)", "Clean (soft blue)"),
    }

    /** 밝기: 폰 설정 따라 / 밝게 / 어둡게 */
    enum class Night(val ko: String, val en: String) {
        SYSTEM("폰 설정 따라", "Follow system"),
        LIGHT("밝게", "Light"),
        DARK("어둡게", "Dark"),
    }

    /** 언어: 폰 설정 따라 / 한국어 / English */
    enum class Language(val ko: String, val en: String) {
        SYSTEM("폰 설정 따라", "Follow system"),
        KOREAN("한국어", "한국어"),
        ENGLISH("English", "English"),
    }

    /** 글자 크기 배율 */
    enum class TextScale(val ko: String, val en: String, val factor: Float) {
        SMALL("작게", "Small", 0.9f),
        NORMAL("보통", "Normal", 1.0f),
        LARGE("크게", "Large", 1.15f),
        HUGE("아주 크게", "Extra large", 1.3f),
    }

    private lateinit var sp: SharedPreferences

    /** 설정이 바뀔 때마다 1씩 오른다 (열려 있던 화면이 돌아왔을 때 다시 만들지 판단) */
    @Volatile var version = 0
        private set

    @Volatile var theme = Theme.CLASSIC; private set
    @Volatile var night = Night.SYSTEM; private set
    @Volatile var language = Language.SYSTEM; private set
    @Volatile var textScale = TextScale.NORMAL; private set
    /** 채팅 글자 크기(sp) */
    @Volatile var chatTextSp = 14; private set
    /** 새 서버 기본값 */
    @Volatile var defaultPackGlyphs = true; private set
    @Volatile var defaultPackWifiOnly = true; private set
    @Volatile var defaultResumeOnBoot = true; private set
    /** 잠수 알림에 데이터 사용량 줄 보이기 */
    @Volatile var notifyDataUsage = true; private set
    /** 채팅 화면을 보는 동안 화면 꺼짐 막기 */
    @Volatile var keepScreenOn = false; private set
    /** 채팅 줄 앞에 시간 표시 */
    @Volatile var chatTimestamps = true; private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("jamsuham_settings", Context.MODE_PRIVATE)
        theme = enumOr(sp.getString("theme", null), Theme.CLASSIC)
        night = enumOr(sp.getString("night", null), Night.SYSTEM)
        language = enumOr(sp.getString("language", null), Language.SYSTEM)
        textScale = enumOr(sp.getString("text_scale", null), TextScale.NORMAL)
        chatTextSp = sp.getInt("chat_text_sp", 14).coerceIn(10, 24)
        defaultPackGlyphs = sp.getBoolean("default_pack_glyphs", true)
        defaultPackWifiOnly = sp.getBoolean("default_pack_wifi_only", true)
        defaultResumeOnBoot = sp.getBoolean("default_resume_on_boot", true)
        notifyDataUsage = sp.getBoolean("notify_data_usage", true)
        keepScreenOn = sp.getBoolean("keep_screen_on", false)
        chatTimestamps = sp.getBoolean("chat_timestamps", true)
        applyNightMode(context)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: def

    private fun changed() {
        version++
    }

    fun setTheme(v: Theme) { theme = v; sp.edit().putString("theme", v.name).apply(); changed() }
    fun setNight(context: Context, v: Night) { night = v; sp.edit().putString("night", v.name).apply(); applyNightMode(context); changed() }
    fun setLanguage(v: Language) { language = v; sp.edit().putString("language", v.name).apply(); changed() }
    fun setTextScale(v: TextScale) { textScale = v; sp.edit().putString("text_scale", v.name).apply(); changed() }
    fun setChatTextSp(v: Int) { chatTextSp = v.coerceIn(10, 24); sp.edit().putInt("chat_text_sp", chatTextSp).apply(); changed() }
    fun setDefaultPackGlyphs(v: Boolean) { defaultPackGlyphs = v; sp.edit().putBoolean("default_pack_glyphs", v).apply(); changed() }
    fun setDefaultPackWifiOnly(v: Boolean) { defaultPackWifiOnly = v; sp.edit().putBoolean("default_pack_wifi_only", v).apply(); changed() }
    fun setDefaultResumeOnBoot(v: Boolean) { defaultResumeOnBoot = v; sp.edit().putBoolean("default_resume_on_boot", v).apply(); changed() }
    fun setNotifyDataUsage(v: Boolean) { notifyDataUsage = v; sp.edit().putBoolean("notify_data_usage", v).apply(); changed() }
    fun setKeepScreenOn(v: Boolean) { keepScreenOn = v; sp.edit().putBoolean("keep_screen_on", v).apply(); changed() }
    fun setChatTimestamps(v: Boolean) { chatTimestamps = v; sp.edit().putBoolean("chat_timestamps", v).apply(); changed() }

    /** 지금 영어로 보여야 하는지 */
    val english: Boolean
        get() = when (language) {
            Language.ENGLISH -> true
            Language.KOREAN -> false
            Language.SYSTEM -> Locale.getDefault().language != "ko"
        }

    /**
     * 밝기 설정을 시스템에 알려 대화상자 같은 기본 화면도 같은 밝기로 (안드로이드 12+).
     * 11 이하는 앱 색(Palette)만 바뀌고 시스템 대화상자는 폰 설정을 따른다.
     */
    private fun applyNightMode(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val um = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            um.setApplicationNightMode(
                when (night) {
                    Night.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    Night.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    Night.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        } catch (_: Throwable) {
        }
    }
}

/** 한국어/영어 문구 고르기. 화면은 이 함수로 문구를 만든다 (번역은 화면별로 차례대로 옮기는 중) */
fun tr(ko: String, en: String): String = if (AppSettings.english) en else ko
