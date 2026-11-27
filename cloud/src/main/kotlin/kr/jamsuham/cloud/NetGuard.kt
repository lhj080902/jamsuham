package kr.jamsuham.cloud

import kr.jamsuham.core.ResolvedTarget
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 사용자가 적은 서버 주소로 이 컴퓨터가 대신 접속하므로, 내부망(공유기 관리 화면·이 컴퓨터의 다른 프로그램 등)을
 * 건드리는 데 악용되지 않게 막는다. 공인 주소만 허용하고, 확인한 IP 로 바로 접속하게 고정한다
 * (확인할 때와 접속할 때 DNS 답이 달라지는 속임수를 막기 위해).
 */
object NetGuard {
    private val HOST = Regex("^[A-Za-z0-9._:-]{1,253}$")

    fun filter(target: ResolvedTarget, allowPrivate: Boolean): ResolvedTarget {
        // 주소에 줄바꿈 같은 이상한 글자가 섞여 있으면 받지 않는다 (서버가 옮기라고 한 주소도 여기를 거친다)
        require(HOST.matches(target.connectHost) && HOST.matches(target.handshakeHost)) { "서버 주소에 쓸 수 없는 글자가 있습니다" }
        require(target.connectPort in 1..65535) { "포트 번호가 잘못됐습니다 (${target.connectPort})" }
        if (allowPrivate) return target
        require(target.connectPort >= 1024) { "이 포트(${target.connectPort})로는 클라우드에서 접속할 수 없습니다 (1024 이상만 가능)" }
        val addrs = InetAddress.getAllByName(target.connectHost)
        val ok = addrs.firstOrNull { isPublic(it) && !isLocal(it) }
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

    /** 이 컴퓨터의 랜카드 주소들 (주소, 대역 길이). 1분마다 다시 읽는다 */
    @Volatile private var own: List<Pair<InetAddress, Int>> = emptyList()
    @Volatile private var ownAt = 0L

    private fun ownAddresses(): List<Pair<InetAddress, Int>> {
        val now = System.currentTimeMillis()
        if (now - ownAt < 60_000) return own
        val out = ArrayList<Pair<InetAddress, Int>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (ia in nif.interfaceAddresses) out.add(ia.address to ia.networkPrefixLength.toInt())
            }
        } catch (_: Exception) {
        }
        own = out
        ownAt = now
        return out
    }

    /**
     * 이 컴퓨터 자신의 주소이거나, 같은 IPv6 대역(집 공유기 안의 다른 기기)인지.
     * IPv6 는 집 안 기기도 공인 주소를 받기 때문에 "공인 주소"만 봐서는 내부망을 못 가린다.
     * (IPv4 는 내부망이 사설 주소라 위에서 이미 걸러지고, 같은 대역의 공인 IPv4 이웃은 남의 서버라 막지 않는다)
     */
    fun isLocal(a: InetAddress): Boolean {
        val b = a.address
        for ((addr, prefix) in ownAddresses()) {
            if (addr == a) return true
            if (a is Inet6Address && addr is Inet6Address && prefix in 48..128 && !addr.isLinkLocalAddress) {
                val o = addr.address
                var same = true
                var bits = prefix
                var i = 0
                while (bits > 0 && i < 16) {
                    val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                    if ((o[i].toInt() and mask) != (b[i].toInt() and mask)) {
                        same = false
                        break
                    }
                    bits -= 8
                    i++
                }
                if (same) return true
            }
        }
        return false
    }
}
