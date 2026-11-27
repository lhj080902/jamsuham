package kr.jamsuham.core.text

/**
 * 왕복형 글씨의 움직임 계산 (앱 `Ui.pingPong` 이 쓴다. 화면 코드와 떼어 두어 PC 에서 시험한다).
 * 잠깐 멈춤 → 끝까지 → 잠깐 멈춤 → 처음까지, 되풀이.
 */
object PingPongMotion {
    /** 양쪽 끝에서 멈춰 있는 시간 */
    const val PAUSE_MS = 1200L

    /** 아주 짧은 거리도 이만큼은 걸려서 움직인다 (튀어 보이지 않게) */
    private const val MIN_TRAVEL_MS = 300f

    /**
     * 시작한 지 [elapsedMs] 지났을 때 글씨를 왼쪽으로 얼마나 밀어야 하는지 (0 ~ [range] 픽셀).
     * [range] = 글 폭 - 칸 폭 (0 이하면 다 들어가는 것이라 움직이지 않는다), [pxPerSec] = 빠르기.
     */
    fun offset(elapsedMs: Long, range: Int, pxPerSec: Float): Float {
        if (range <= 0 || pxPerSec <= 0f || elapsedMs < 0) return 0f
        val travel = (range / pxPerSec * 1000f).coerceAtLeast(MIN_TRAVEL_MS)
        val cycle = PAUSE_MS + travel + PAUSE_MS + travel
        val at = (elapsedMs % cycle.toLong().coerceAtLeast(1)).toFloat()
        val part = when {
            at < PAUSE_MS -> 0f
            at < PAUSE_MS + travel -> (at - PAUSE_MS) / travel
            at < PAUSE_MS + travel + PAUSE_MS -> 1f
            else -> 1f - (at - PAUSE_MS - travel - PAUSE_MS) / travel
        }
        return part.coerceIn(0f, 1f) * range
    }
}
