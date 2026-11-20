package kr.jamsuham.app

import android.content.Context

/**
 * 무료/유료 구분. 서버 저장은 무료로 무제한, 동시에 잠수할 수 있는 서버 수만 제한한다.
 * 플레이스토어 결제를 붙이면 isPremium 이 결제 상태를 보게 바꾼다.
 * (지금은 테스트용: 첫 화면 아래 버전 글씨(v1.1.N)를 7번 누르면 켜고 끌 수 있음)
 */
object Plan {
    const val FREE_CONCURRENT = 1

    private fun prefs(c: Context) = c.getSharedPreferences("plan", Context.MODE_PRIVATE)

    fun isPremium(c: Context): Boolean = prefs(c).getBoolean("premium_test", false)

    fun setPremiumTest(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("premium_test", on).apply()
    }

    fun maxConcurrent(c: Context): Int = if (isPremium(c)) Int.MAX_VALUE else FREE_CONCURRENT
}
