package kr.jamsuham.app

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kr.jamsuham.core.SessionConfig
import java.util.UUID

/** 저장된 서버 하나 */
data class ServerEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    /** null = 자동 감지 */
    val fixedVersion: String? = null,
    /** AccountStore 의 계정 id */
    val accountId: String? = null,
    val joinCommands: List<String> = emptyList(),
    val joinDelaySec: Int = 3,
    val autoReconnect: Boolean = true,
    val reconnectOnKick: Boolean = true,
    /** 메뉴 자동 클릭 규칙 ("제목 > 아이템") */
    val autoClickRules: List<String> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { address.ifBlank { "이름 없는 서버" } }

    fun sessionConfig() = SessionConfig(
        address = address,
        fixedVersion = fixedVersion,
        autoReconnect = autoReconnect,
        reconnectOnKick = reconnectOnKick,
        joinCommands = joinCommands.map { it.trim() }.filter { it.isNotEmpty() }.take(10),
        joinCommandDelaySec = joinDelaySec.coerceIn(0, 120),
        autoClickRules = (autoClickRules ?: emptyList()).mapNotNull { kr.jamsuham.core.AutoClickRule.parse(it) },
    )
}

/** 서버 목록, 채팅 기록, 실행 상태 같은 앱 데이터 */
class Store(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("jamsuham", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ------------------------------------------------------------ servers

    fun servers(): List<ServerEntry> {
        val json = sp.getString("servers", null) ?: return migrateOld()
        return try {
            gson.fromJson<List<ServerEntry>>(json, object : TypeToken<List<ServerEntry>>() {}.type)
                ?.map { it.copy(joinCommands = it.joinCommands ?: emptyList(), autoClickRules = it.autoClickRules ?: emptyList()) } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun server(id: String): ServerEntry? = servers().firstOrNull { it.id == id }

    fun saveServer(entry: ServerEntry) {
        val list = servers().toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) list[i] = entry else list.add(entry)
        writeServers(list)
    }

    fun deleteServer(id: String) {
        writeServers(servers().filter { it.id != id })
        sp.edit().remove("history_$id").apply()
    }

    private fun writeServers(list: List<ServerEntry>) {
        sp.edit().putString("servers", gson.toJson(list)).apply()
    }

    /** 1.0 버전(서버 하나) 설정을 서버 목록으로 옮긴다 */
    private fun migrateOld(): List<ServerEntry> {
        val address = sp.getString("address", "") ?: ""
        if (address.isBlank()) return emptyList()
        val entry = ServerEntry(
            name = address,
            address = address,
            fixedVersion = sp.getString("fixed_version", null),
            accountId = if (sp.getBoolean("use_microsoft", true)) sp.getString("ms_uuid", null)?.let { "ms:$it" }
            else (sp.getString("offline_name", "") ?: "").takeIf { it.isNotBlank() }?.let { "offline:$it" },
            joinCommands = (sp.getString("join_commands", "") ?: "").lines().filter { it.isNotBlank() },
            autoReconnect = sp.getBoolean("auto_reconnect", true),
            reconnectOnKick = sp.getBoolean("reconnect_on_kick", true),
        )
        writeServers(listOf(entry))
        return listOf(entry)
    }

    // ------------------------------------------------------------ chat history (▲▼)

    fun history(serverId: String): List<String> {
        val raw = sp.getString("history_$serverId", "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(SEP)
    }

    fun addHistory(serverId: String, line: String) {
        val list = history(serverId).filter { it != line }.toMutableList()
        list.add(line)
        while (list.size > 50) list.removeAt(0)
        sp.edit().putString("history_$serverId", list.joinToString(SEP)).apply()
    }

    // ------------------------------------------------------------ 실행 중인 서버 (시스템이 앱을 정리했을 때 이어서 접속)

    fun setActive(serverIds: Set<String>) {
        sp.edit()
            .putStringSet("active_servers", serverIds)
            .putInt("active_boot", if (serverIds.isEmpty()) -1 else bootCount())
            .apply()
    }

    /** 같은 부팅 안에서 강제 종료된 경우에만 이어서 접속한다 (전원을 껐다 켠 경우는 제외) */
    fun activeToResume(): Set<String> {
        val ids = sp.getStringSet("active_servers", emptySet()) ?: emptySet()
        if (ids.isEmpty()) return emptySet()
        val boot = bootCount()
        return if (boot != -1 && sp.getInt("active_boot", -2) == boot) HashSet(ids) else emptySet()
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Throwable) {
        -1
    }

    // ------------------------------------------------------------ 기타

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    var skippedBuild: Int
        get() = sp.getInt("skipped_build", 0)
        set(v) = sp.edit().putInt("skipped_build", v).apply()

    companion object {
        private const val SEP = "\u0001"
    }
}
