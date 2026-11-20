package kr.jamsuham.core.net

import com.viaversion.viaversion.protocols.v1_8to1_9.provider.CompressionProvider
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.codec.MessageToMessageDecoder
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 파이프라인 핸들러 이름. ViaVersion 의 기본 CompressionProvider 와 이름을 맞춘다. */
object Pipe {
    const val TIMEOUT = "timeout"
    const val DECRYPT = "decrypt"
    const val ENCRYPT = "encrypt"
    const val FRAME = "frame"
    const val DECOMPRESS = "decompress"
    const val COMPRESS = "compress"
    const val VIA = "via-codec"
    const val HANDLER = "handler"
}

/** 패킷 앞에 붙는 VarInt 길이. */
class FrameCodec : ByteToMessageCodec<ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        val len = msg.readableBytes()
        out.ensureWritable(varIntSize(len) + len)
        out.writeVarInt(len)
        out.writeBytes(msg, msg.readerIndex(), len)
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        while (buf.isReadable) {
            buf.markReaderIndex()
            var length = 0
            var shift = 0
            var complete = false
            for (i in 0 until 3) {
                if (!buf.isReadable) break
                val b = buf.readByte().toInt()
                length = length or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) {
                    complete = true
                    break
                }
                if (i == 2) throw CorruptedFrameException("패킷 길이가 21비트를 넘습니다")
            }
            if (!complete) {
                buf.resetReaderIndex()
                return
            }
            if (buf.readableBytes() < length) {
                buf.resetReaderIndex()
                return
            }
            out.add(buf.readRetainedSlice(length))
        }
    }
}

/** AES/CFB8 복호화. */
class DecryptHandler(secret: ByteArray) : MessageToMessageDecoder<ByteBuf>() {
    private val cipher: Cipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(secret))
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val len = msg.readableBytes()
        val input = ByteArray(len)
        msg.readBytes(input)
        val result = ctx.alloc().heapBuffer(len)
        val output = cipher.update(input) ?: ByteArray(0)
        result.writeBytes(output)
        out.add(result)
    }
}

/** AES/CFB8 암호화. */
class EncryptHandler(secret: ByteArray) : MessageToByteEncoder<ByteBuf>() {
    private val cipher: Cipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(secret))
    }

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        val input = ByteArray(msg.readableBytes())
        msg.readBytes(input)
        val output = cipher.update(input) ?: ByteArray(0)
        out.writeBytes(output)
    }
}

/** 압축 해제. ViaVersion 이 1.8 서버의 압축 설정을 처리할 때도 이 핸들러의 값을 바꾼다. */
class DecompressHandler(private var threshold: Int) : ByteToMessageDecoder(), CompressionProvider.CompressionHandler {
    private val inflater = Inflater()

    override fun setCompressionThreshold(threshold: Int) {
        this.threshold = threshold
    }

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        if (!buf.isReadable) return
        val dataLength = buf.readVarInt()
        if (dataLength == 0) {
            out.add(buf.readRetainedSlice(buf.readableBytes()))
            return
        }
        if (dataLength < threshold) throw DecoderException("압축 기준보다 작은 압축 패킷: $dataLength < $threshold")
        if (dataLength > MAX_UNCOMPRESSED) throw DecoderException("압축 해제 크기가 너무 큽니다: $dataLength")
        val compressed = ByteArray(buf.readableBytes())
        buf.readBytes(compressed)
        inflater.setInput(compressed)
        val result = ByteArray(dataLength)
        try {
            val n = inflater.inflate(result)
            if (n != dataLength) throw DecoderException("압축 해제 길이가 맞지 않습니다: $n != $dataLength")
        } finally {
            inflater.reset()
        }
        out.add(ctx.alloc().heapBuffer(dataLength).writeBytes(result))
    }

    override fun handlerRemoved0(ctx: ChannelHandlerContext) {
        inflater.end()
    }

    companion object {
        const val MAX_UNCOMPRESSED = 8 * 1024 * 1024
    }
}

/** 압축. */
class CompressHandler(private var threshold: Int) : MessageToByteEncoder<ByteBuf>(), CompressionProvider.CompressionHandler {
    private val deflater = Deflater()
    private val chunk = ByteArray(8192)

    override fun setCompressionThreshold(threshold: Int) {
        this.threshold = threshold
    }

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: ByteBuf) {
        val len = msg.readableBytes()
        if (len < threshold) {
            out.writeVarInt(0)
            out.writeBytes(msg)
            return
        }
        val input = ByteArray(len)
        msg.readBytes(input)
        out.writeVarInt(len)
        deflater.setInput(input)
        deflater.finish()
        while (!deflater.finished()) {
            val n = deflater.deflate(chunk)
            out.writeBytes(chunk, 0, n)
        }
        deflater.reset()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        deflater.end()
        super.handlerRemoved(ctx)
    }
}
