package kr.jamsuham.core.net

import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URL
import java.net.UnknownHostException

/** 모장 인증(세션) 서버가 살아 있는지 가볍게 확인 */
object MojangStatus {
    enum class Result {
        /** 정상 */
        UP,
        /** 응답이 5xx·429 이거나 응답이 너무 늦음 → 모장 쪽 장애로 본다 */
        DOWN,
        /** 주소를 못 찾거나 연결 자체가 안 됨 → 폰 인터넷 문제일 가능성이 높다 (모장 장애로 오인하지 않기) */
        NO_NETWORK,
    }

    fun check(timeoutMs: Int = 8000): Result {
        return try {
            @Suppress("DEPRECATION")
            val conn = URL("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=jamsuham_check&serverId=0")
                .openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                val code = conn.responseCode
                // 없는 접속을 물었으므로 정상이면 204. 응답만 오면 살아 있는 것으로 보고, 서버 오류(5xx)와 요청 제한(429)만 장애로 본다
                if (code in 100..499 && code != 429) Result.UP else Result.DOWN
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            if (isNoNetwork(e)) Result.NO_NETWORK else Result.DOWN
        }
    }

    /** 정상이면 true. 응답이 없거나 5xx·429 면 false */
    fun sessionServerUp(timeoutMs: Int = 8000): Boolean = check(timeoutMs) == Result.UP

    private fun isNoNetwork(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is UnknownHostException || t is ConnectException || t is NoRouteToHostException) return true
            t = t.cause
        }
        return false
    }
}
