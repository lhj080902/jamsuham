package kr.jamsuham.core.net

import java.net.HttpURLConnection
import java.net.URL

/** 모장 인증(세션) 서버가 살아 있는지 가볍게 확인 */
object MojangStatus {
    /** 정상이면 true. 응답이 없거나 5xx·429 면 false */
    fun sessionServerUp(timeoutMs: Int = 8000): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val conn = URL("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=jamsuham_check&serverId=0")
                .openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                val code = conn.responseCode
                // 없는 접속을 물었으므로 정상이면 204. 응답만 오면 살아 있는 것으로 보고, 서버 오류(5xx)와 요청 제한(429)만 장애로 본다
                code in 100..499 && code != 429
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}
