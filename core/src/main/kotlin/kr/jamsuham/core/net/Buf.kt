package kr.jamsuham.core.net

import com.viaversion.nbt.tag.Tag
import com.viaversion.viaversion.api.type.Types
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 마인크래프트 프로토콜 기본 자료형 읽기/쓰기. */

fun ByteBuf.readVarInt(): Int {
    var value = 0
    var shift = 0
    while (true) {
        if (!isReadable) throw DecoderException("VarInt 가 중간에 끊겼습니다")
        val b = readByte().toInt()
        value = value or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) return value
        shift += 7
        if (shift >= 35) throw DecoderException("VarInt 가 너무 깁니다")
    }
}

fun ByteBuf.writeVarInt(v: Int): ByteBuf {
    var value = v
    while (true) {
        if (value and 0x7F.inv() == 0) {
            writeByte(value)
            return this
        }
        writeByte((value and 0x7F) or 0x80)
        value = value ushr 7
    }
}

fun varIntSize(v: Int): Int {
    var value = v
    var size = 1
    while (value and 0x7F.inv() != 0) {
        size++
        value = value ushr 7
    }
    return size
}

fun ByteBuf.readMcString(maxChars: Int = 32767): String {
    val len = readVarInt()
    if (len < 0 || len > maxChars * 3) throw DecoderException("문자열 길이가 잘못됐습니다: $len")
    if (len > readableBytes()) throw DecoderException("문자열이 중간에 끊겼습니다")
    val s = toString(readerIndex(), len, StandardCharsets.UTF_8)
    skipBytes(len)
    return s
}

fun ByteBuf.writeMcString(s: String): ByteBuf {
    val bytes = s.toByteArray(StandardCharsets.UTF_8)
    writeVarInt(bytes.size)
    writeBytes(bytes)
    return this
}

fun ByteBuf.readUuid(): UUID = UUID(readLong(), readLong())

fun ByteBuf.writeUuid(u: UUID): ByteBuf {
    writeLong(u.mostSignificantBits)
    writeLong(u.leastSignificantBits)
    return this
}

fun ByteBuf.readByteArrayVar(max: Int = 1 shl 20): ByteArray {
    val len = readVarInt()
    if (len < 0 || len > max || len > readableBytes()) throw DecoderException("바이트 배열 길이가 잘못됐습니다: $len")
    val out = ByteArray(len)
    readBytes(out)
    return out
}

fun ByteBuf.writeByteArrayVar(b: ByteArray): ByteBuf {
    writeVarInt(b.size)
    writeBytes(b)
    return this
}

/** 1.20.3+ 텍스트 컴포넌트 (이름 없는 NBT). */
fun ByteBuf.readAnonymousTag(): Tag? = Types.TAG.read(this)

fun ByteBuf.readOptionalAnonymousTag(): Tag? = if (readBoolean()) readAnonymousTag() else null
