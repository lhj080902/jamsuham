package kr.jamsuham.app

import android.app.Application
import java.util.Locale

class JamsuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Netty 가 안드로이드에서 쓸데없이 네이티브 라이브러리를 찾지 않게 한다
        System.setProperty("io.netty.transport.noNative", "true")
    }
}

object Formats {
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d시간 %02d분", h, m)
        else String.format(Locale.ROOT, "%d분 %02d초", m, s)
    }

    /** 분 단위만: "45분", "2시간 13분" */
    fun minutes(ms: Long): String {
        val total = (ms / 60_000).coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }
}
