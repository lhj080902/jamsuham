package kr.jamsuham.app

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.text.ChatAuthor
import java.util.UUID

/** 차단한 사용자 한 명. uuid 는 차단할 때 접속자 목록에서 알 수 있었던 경우에만 (신고·확인용, 판단은 이름으로 한다) */
data class Blocked(val name: String = "", val uuid: String? = null, val addedAtMs: Long = 0)

/**
 * 차단한 사용자 목록 (앱 전체에서 하나, 이 기기에만 저장 — prefs `jamsuham_blocks`).
 * 차단한 사람의 채팅 줄은 화면에도 채팅 기록 파일에도 남기지 않는다 ([SessionHub] 가 줄이 들어올 때 거른다).
 * 누가 쓴 줄인지는 글자로 판단한다 ([ChatAuthor]). 상대방은 그 서버에 그대로 있고, 이 기기의 화면에서만 숨는다.
 */
object BlockList {
    private const val PREFS = "jamsuham_blocks"
    private const val KEY = "blocked"
    private val gson = Gson()
    private var sp: SharedPreferences? = null

    @Volatile private var items: List<Blocked> = emptyList()

    /** 목록이 바뀔 때마다 올라간다 */
    @Volatile var version = 0
        private set

    fun init(context: Context) {
        if (sp != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp = prefs
        items = try {
            gson.fromJson<List<Blocked>>(prefs.getString(KEY, "[]"), object : TypeToken<List<Blocked>>() {}.type)
                ?.filter { !it.name.isNullOrBlank() } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun list(): List<Blocked> = items

    fun isBlocked(name: String): Boolean {
        val wanted = name.trim()
        return wanted.isNotEmpty() && items.any { it.name.equals(wanted, ignoreCase = true) }
    }

    /** 이 줄을 숨겨야 하는지: 채팅 줄이고, 차단한 사람이 쓴 것으로 보일 때 (앱이 띄우는 안내·오류 줄은 숨기지 않는다) */
    fun blocks(line: LogLine): Boolean {
        if (line.kind != LogLine.Kind.CHAT) return false
        val all = items
        if (all.isEmpty()) return false
        val plain = line.text.plain
        return all.any { ChatAuthor.isFrom(plain, it.name) }
    }

    /** 차단한다. 이미 화면에 있던 그 사람의 줄도 지운다 */
    fun add(name: String, uuid: UUID?) {
        val wanted = name.trim()
        if (wanted.isEmpty() || isBlocked(wanted)) return
        write(items + Blocked(wanted, uuid?.toString(), System.currentTimeMillis()))
        SessionHub.purgeBlocked()
    }

    fun remove(name: String) {
        val wanted = name.trim()
        write(items.filter { !it.name.equals(wanted, ignoreCase = true) })
    }

    private fun write(list: List<Blocked>) {
        items = list
        version++
        sp?.edit()?.putString(KEY, gson.toJson(list))?.apply()
    }
}
