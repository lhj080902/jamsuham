package kr.jamsuham.core.net

import com.google.gson.JsonParser
import kr.jamsuham.core.ResolvedTarget
import kr.jamsuham.core.text.ChatText
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

data class ServerPing(
    val protocol: Int,
    val versionName: String,
    val motd: ChatText,
    val online: Int,
    val max: Int,
    val latencyMs: Long,
    /** 서버 아이콘 PNG (64x64). 없으면 null */
    val favicon: ByteArray? = null,
)

/** 서버 목록 핑으로 서버 버전(프로토콜 번호)을 알아낸다. 블로킹 호출. */
object StatusPinger {

    @Throws(IOException::class)
    fun ping(target: ResolvedTarget, clientProtocol: Int, timeoutMs: Int = 6000): ServerPing {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target.connectHost, target.connectPort), timeoutMs)
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            val handshake = ByteArrayOutputStream()
            writeVarInt(handshake, 0x00)
            writeVarInt(handshake, clientProtocol)
            writeString(handshake, target.handshakeHost)
            handshake.write((target.handshakePort shr 8) and 0xFF)
            handshake.write(target.handshakePort and 0xFF)
            writeVarInt(handshake, 1) // status
            val frame = ByteArrayOutputStream()
            writeVarInt(frame, handshake.size())
            handshake.writeTo(frame)
            writeVarInt(frame, 1) // status request 길이
            writeVarInt(frame, 0x00)
            val started = System.nanoTime()
            out.write(frame.toByteArray())
            out.flush()

            val length = readVarInt(input)
            if (length <= 0 || length > 1 shl 21) throw IOException("잘못된 응답 길이 $length")
            val body = ByteArray(length)
            input.readFully(body)
            val latency = (System.nanoTime() - started) / 1_000_000
            val bin = DataInputStream(body.inputStream())
            val id = readVarInt(bin)
            if (id != 0x00) throw IOException("예상하지 못한 응답 패킷 $id")
            val jsonLen = readVarInt(bin)
            val jsonBytes = ByteArray(jsonLen)
            bin.readFully(jsonBytes)
            return parse(String(jsonBytes, Charsets.UTF_8), latency)
        }
    }

    fun parse(json: String, latencyMs: Long): ServerPing {
        val root = JsonParser.parseString(json).asJsonObject
        val version = root.getAsJsonObject("version")
        val protocol = version?.get("protocol")?.asInt ?: -1
        val name = version?.get("name")?.asString ?: "?"
        val players = root.getAsJsonObject("players")
        val desc = root.get("description")
        val motd = when {
            desc == null -> ChatText(emptyList())
            desc.isJsonPrimitive -> ChatText.fromLegacy(desc.asString)
            else -> ChatText.fromJson(desc.toString())
        }
        return ServerPing(
            protocol = protocol,
            versionName = name,
            motd = motd,
            online = players?.get("online")?.asInt ?: 0,
            max = players?.get("max")?.asInt ?: 0,
            latencyMs = latencyMs,
            favicon = root.get("favicon")?.takeIf { it.isJsonPrimitive }?.asString?.let { decodeFavicon(it) },
        )
    }

    private fun decodeFavicon(dataUri: String): ByteArray? = try {
        val comma = dataUri.indexOf(',')
        if (comma < 0) null else java.util.Base64.getMimeDecoder().decode(dataUri.substring(comma + 1))
    } catch (_: Throwable) {
        null
    }

    private fun writeVarInt(out: ByteArrayOutputStream, v: Int) {
        var value = v
        while (true) {
            if (value and 0x7F.inv() == 0) {
                out.write(value)
                return
            }
            out.write((value and 0x7F) or 0x80)
            value = value ushr 7
        }
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeVarInt(out, b.size)
        out.write(b)
    }

    private fun readVarInt(input: InputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("서버가 응답 중에 연결을 끊었습니다")
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
            if (shift >= 35) throw IOException("VarInt 가 너무 깁니다")
        }
    }
}
