package kr.jamsuham.app

import android.content.Context
import android.content.SharedPreferences
import kr.jamsuham.core.MenuItem
import kr.jamsuham.core.text.ItemNames

/**
 * 아이템 버리기 잠금 (실수로 버리지 않게). 서버별로 기억한다 — prefs `jamsuham_locks`, 서버 id → 잠근 아이템 열쇠 묶음.
 * 열쇠는 아이템 종류 + 서버가 붙인 이름([ItemNames.lockKey])이라, 같은 종류·같은 이름의 아이템은 어느 칸에 있어도 함께 잠긴다.
 * 막는 것은 버리기뿐이다 (사용은 그대로).
 */
object DropLocks {
    private const val PREFS = "jamsuham_locks"
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun keys(serverId: String): Set<String> = sp?.getStringSet(serverId, null) ?: emptySet()

    private fun key(item: MenuItem) = ItemNames.lockKey(item.itemId, item.name.plain)

    fun isLocked(serverId: String, item: MenuItem): Boolean = key(item) in keys(serverId)

    /** 잠금을 뒤집는다. 돌려주는 값은 바뀐 뒤의 상태 (true = 잠김) */
    fun toggle(serverId: String, item: MenuItem): Boolean {
        val k = key(item)
        val set = HashSet(keys(serverId)) // getStringSet 이 준 묶음은 고치면 안 된다
        val locked = if (set.remove(k)) false else set.add(k)
        sp?.edit()?.putStringSet(serverId, set)?.apply()
        return locked
    }

    /** 서버를 지울 때 */
    fun clear(serverId: String) {
        sp?.edit()?.remove(serverId)?.apply()
    }
}
