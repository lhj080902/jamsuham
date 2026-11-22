package kr.jamsuham.core.net

import kr.jamsuham.core.tr
import kr.jamsuham.core.ResolvedTarget
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

data class SrvRecord(val priority: Int, val weight: Int, val port: Int, val target: String)

/** 원시 DNS 응답을 받아 오는 방법. 안드로이드에서는 시스템 DnsResolver 를 쓰고, 없으면 UDP 로 직접 묻는다. */
fun interface RawDnsQuery {
    /** 응답 바이트를 돌려준다. 실패하면 null */
    fun query(name: String, type: Int): ByteArray?
}

/**
 * "play.example.com" 처럼 포트 없이 적은 주소는 마인크래프트처럼 _minecraft._tcp SRV 레코드를 먼저 찾는다.
 */
object AddressResolver {

    const val DEFAULT_PORT = 25565
    private const val TYPE_SRV = 33

    /** "host", "host:port", "[ipv6]:port" 를 나눈다 */
    fun split(address: String): Pair<String, Int?> {
        val a = address.trim()
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end > 0) {
                val host = a.substring(1, end)
                val port = a.substring(end + 1).removePrefix(":").toIntOrNull()
                return host to port
            }
        }
        val colon = a.lastIndexOf(':')
        if (colon > 0 && a.indexOf(':') == colon) {
            val port = a.substring(colon + 1).toIntOrNull()
            if (port != null && port in 1..65535) return a.substring(0, colon) to port
        }
        return a to null
    }

    fun resolve(address: String, dns: RawDnsQuery?): ResolvedTarget {
        val (host, port) = split(address)
        require(host.isNotEmpty()) { tr("서버 주소를 입력해 주세요", "Please enter a server address") }
        if (port != null) return ResolvedTarget(host, port, host, port)
        if (isIpLiteral(host)) return ResolvedTarget(host, DEFAULT_PORT, host, DEFAULT_PORT)
        val srv = try {
            dns?.query("_minecraft._tcp.$host", TYPE_SRV)?.let { pickSrv(parseSrv(it)) }
        } catch (t: Throwable) {
            null
        }
        if (srv != null && srv.target.isNotEmpty()) {
            val t = srv.target.removeSuffix(".")
            // 바닐라처럼 핸드셰이크에는 SRV 대상 주소를 적는다
            return ResolvedTarget(t, srv.port, t, srv.port)
        }
        return ResolvedTarget(host, DEFAULT_PORT, host, DEFAULT_PORT)
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    fun pickSrv(records: List<SrvRecord>): SrvRecord? {
        if (records.isEmpty()) return null
        val best = records.minOf { it.priority }
        return records.filter { it.priority == best }.maxByOrNull { it.weight }
    }

    // ------------------------------------------------------------ DNS

    /** UDP 로 직접 DNS 서버에 묻는다 */
    class UdpDns(private val servers: List<String>, private val timeoutMs: Int = 2500) : RawDnsQuery {
        private val random = SecureRandom()

        override fun query(name: String, type: Int): ByteArray? {
            val id = random.nextInt(0x10000)
            val packet = buildQuery(id, name, type)
            for (server in servers) {
                try {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = timeoutMs
                        val addr = InetAddress.getByName(server)
                        socket.send(DatagramPacket(packet, packet.size, addr, 53))
                        val buf = ByteArray(4096)
                        val resp = DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        if (resp.length >= 12 && ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id) {
                            return buf.copyOf(resp.length)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                }
            }
            return null
        }
    }

    fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD
        out.write(0); out.write(1) // QDCOUNT
        repeat(6) { out.write(0) }
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.size in 1..63) { tr("잘못된 도메인: $name", "Invalid domain: $name") }
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0); out.write(1) // IN
        return out.toByteArray()
    }

    /** DNS 응답에서 SRV 레코드만 꺼낸다 */
    fun parseSrv(msg: ByteArray): List<SrvRecord> {
        if (msg.size < 12) return emptyList()
        fun u16(p: Int) = ((msg[p].toInt() and 0xFF) shl 8) or (msg[p + 1].toInt() and 0xFF)
        val flags = u16(2)
        if (flags and 0x000F != 0) return emptyList() // RCODE 오류(NXDOMAIN 등)
        val qd = u16(4)
        val an = u16(6)
        var p = 12
        repeat(qd) {
            p = skipName(msg, p)
            p += 4
        }
        val out = ArrayList<SrvRecord>()
        repeat(an) {
            p = skipName(msg, p)
            if (p + 10 > msg.size) return out
            val type = u16(p)
            val rdLen = u16(p + 8)
            val rd = p + 10
            if (rd + rdLen > msg.size) return out
            if (type == TYPE_SRV && rdLen >= 7) {
                val priority = u16(rd)
                val weight = u16(rd + 2)
                val port = u16(rd + 4)
                val target = readName(msg, rd + 6)
                out.add(SrvRecord(priority, weight, port, target))
            }
            p = rd + rdLen
        }
        return out
    }

    private fun skipName(msg: ByteArray, start: Int): Int {
        var p = start
        while (p < msg.size) {
            val len = msg[p].toInt() and 0xFF
            if (len == 0) return p + 1
            if (len and 0xC0 == 0xC0) return p + 2
            p += 1 + len
        }
        return p
    }

    private fun readName(msg: ByteArray, start: Int): String {
        val sb = StringBuilder()
        var p = start
        var jumps = 0
        while (p < msg.size && jumps < 20) {
            val len = msg[p].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                p = ((len and 0x3F) shl 8) or (msg[p + 1].toInt() and 0xFF)
                jumps++
                continue
            }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(msg, p + 1, len, Charsets.US_ASCII))
            p += 1 + len
        }
        return sb.toString()
    }
}
