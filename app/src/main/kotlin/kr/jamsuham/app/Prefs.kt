package kr.jamsuham.app

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kr.jamsuham.core.SessionConfig

/** 앱 설정 저장소 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("jamsuham", Context.MODE_PRIVATE)

    var address: String
        get() = sp.getString("address", "") ?: ""
        set(v) = sp.edit().putString("address", v.trim()).apply()

    /** null = 자동 감지 */
    var fixedVersion: String?
        get() = sp.getString("fixed_version", null)
        set(v) = sp.edit().putString("fixed_version", v).apply()

    var useMicrosoft: Boolean
        get() = sp.getBoolean("use_microsoft", true)
        set(v) = sp.edit().putBoolean("use_microsoft", v).apply()

    var offlineName: String
        get() = sp.getString("offline_name", "") ?: ""
        set(v) = sp.edit().putString("offline_name", v.trim()).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean("auto_reconnect", true)
        set(v) = sp.edit().putBoolean("auto_reconnect", v).apply()

    var reconnectOnKick: Boolean
        get() = sp.getBoolean("reconnect_on_kick", true)
        set(v) = sp.edit().putBoolean("reconnect_on_kick", v).apply()

    /** 접속 후 자동 실행할 명령어 (한 줄에 하나) */
    var joinCommands: String
        get() = sp.getString("join_commands", "") ?: ""
        set(v) = sp.edit().putString("join_commands", v).apply()

    var msName: String?
        get() = sp.getString("ms_name", null)
        set(v) = sp.edit().putString("ms_name", v).apply()

    var msUuid: String?
        get() = sp.getString("ms_uuid", null)
        set(v) = sp.edit().putString("ms_uuid", v).apply()

    /**
     * 세션이 켜져 있는 동안 true. 시스템이 앱을 강제로 정리했다가 서비스를 다시 살릴 때
     * 이어서 접속할지 판단하는 데 쓴다. 폰이 재부팅된 경우(부팅 횟수가 다르면)는 이어 붙이지 않는다.
     */
    fun markActive(active: Boolean) {
        sp.edit()
            .putBoolean("active", active)
            .putInt("active_boot", if (active) bootCount() else -1)
            .apply()
    }

    fun shouldResumeAfterKill(): Boolean {
        if (!sp.getBoolean("active", false)) return false
        val boot = bootCount()
        return boot != -1 && sp.getInt("active_boot", -2) == boot
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Throwable) {
        -1
    }

    fun sessionConfig(): SessionConfig = SessionConfig(
        address = address,
        fixedVersion = fixedVersion,
        autoReconnect = autoReconnect,
        reconnectOnKick = reconnectOnKick,
        joinCommands = joinCommands.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(10),
    )
}
