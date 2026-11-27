package kr.jamsuham.cloud

import kr.jamsuham.core.ResolvedTarget
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 사용자가 적은 서버 주소로 이 컴퓨터가 대신 접속하므로, 내부망(공유기 관리 화면·이 컴퓨터의 다른 프로그램 등)을
 * 건드리는 데 악용되지 않게 막는다. 공인 주소만 허용하고, 확인한 IP 로 바로 접속하게 고정한다
 * (확인할 때와 접속할 때 DNS 답이 달라지는 속임수를 막기 위해).
 */
object NetGuard {
    fun filter(target: ResolvedTarget, allowPrivate: Boolean): ResolvedTarget {
        if (allowPrivate) return target
        require(target.connectPort >= 1024) { "이 포트(${target.connectPort})로는 클라우드에서 접속할 수 없습니다 (1024 이상만 가능)" }
        val addrs = InetAddress.getAllByName(target.connectHost)
        val ok = addrs.firstOrNull { isPublic(it) }
            ?: throw IllegalArgumentException("이 주소로는 클라우드에서 접속할 수 없습니다 (내부망·특수 주소)")
        return target.copy(connectHost = ok.hostAddress)
    }

    fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet4Address) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 0 || b0 >= 240) return false                 // 0.0.0.0/8, 예약·브로드캐스트
            if (b0 == 100 && b1 in 64..127) return false           // 100.64.0.0/10 (통신사 내부망)
            if (b0 == 192 && b1 == 0 && (b[2].toInt() and 0xFF) == 0) return false // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false  // 198.18.0.0/15 (시험용)
            return true
        }
        if (a is Inet6Address) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return false                // fc00::/7 (사설)
            if (b0 == 0x00 && b1 == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B) return false // 64:ff9b::/96 (IPv4 로 바뀌는 주소)
            if (b0 == 0x20 && b1 == 0x02) return false             // 2002::/16 (IPv4 가 들어 있는 주소)
            if (b0 == 0 && b1 == 0) return false                   // ::/16 (IPv4 호환 등 특수)
            return true
        }
        return false
    }
}
