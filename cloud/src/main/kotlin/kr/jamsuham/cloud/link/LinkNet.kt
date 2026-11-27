package kr.jamsuham.cloud.link

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.WriteBufferWaterMark
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import kr.jamsuham.cloud.Log
import kr.jamsuham.cloud.jsonOf
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.cloud.int
import kr.jamsuham.core.cloud.str
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 인증이 끝난 연결에서 받는 것 */
interface LinkHandler {
    fun onMessage(msg: JsonObject)
    fun onClosed()
}

/** 접속용 서버가 자기를 소개하는 값 */
class Hello(val name: String, val capacity: Int, val version: String)

/** 인증이 끝난 연결 하나. 보내는 것은 모두 연결의 스레드에서 차례로 암호화해 나간다 (프레임 번호 순서가 어긋나지 않게) */
class LinkPeer internal constructor(private val ch: Channel, private val cipher: FrameCipher) {
    val remoteIp: String = (ch.remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "?"
    val alive: Boolean get() = ch.isActive

    fun send(msg: JsonObject) {
        // 글자로 바꾸는 일은 부른 쪽 스레드에서 한다 (연결 스레드 하나에 모든 잠수의 소식이 몰리지 않게)
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        // 받는 쪽은 프레임 한도를 넘는 것을 받으면 연결을 끊는다 → 비정상적으로 큰 것은 보내지 않는다
        if (bytes.size > LinkNet.MAX_SEND) {
            Log.w("[연결] 너무 큰 내용(${msg.str("t")}/${msg.str("k") ?: ""}, ${bytes.size / 1024}KB)은 보내지 않습니다")
            return
        }
        ch.eventLoop().execute {
            if (!ch.isActive) return@execute
            // 받는 쪽이 읽지 못해 밀리면 쌓아 두지 않고 끊는다 (끊긴 것으로 처리돼 잠수가 다른 곳으로 옮겨진다)
            if (!ch.isWritable) {
                Log.w("[연결] 상대가 받지 못해 밀려서 끊습니다 ($remoteIp)")
                ch.close()
                return@execute
            }
            ch.writeAndFlush(Unpooled.wrappedBuffer(cipher.seal(bytes)))
        }
    }

    fun close() {
        ch.close()
    }

    internal fun open(sealed: ByteArray): ByteArray = cipher.open(sealed)
}

/**
 * 본 서버 ↔ 접속용 서버 연결 (TCP). 접속용 서버가 본 서버로 먼저 연결한다.
 *   프레임 = 길이 4바이트 + 내용. 처음 네 프레임(hello → challenge → auth → welcome)은 평문 JSON 으로 서로를 확인하고,
 *   그 뒤는 모두 [FrameCipher] 로 암호화한 JSON 이다. 5초마다 ping, 15초 동안 아무것도 못 받으면 끊긴 것으로 본다.
 */
object LinkNet {
    const val MAX_FRAME = 4 * 1024 * 1024
    /** 인증 전(서로 확인하는 네 프레임)의 프레임 한도. 아무나 붙을 수 있는 동안에는 큰 것을 받아 두지 않는다 */
    const val HANDSHAKE_MAX_FRAME = 16 * 1024
    /** 내용 하나의 최대 크기 (한글 100만 자쯤). 프레임 한도 안에 넉넉히 들어온다 */
    const val MAX_SEND = 3_000_000
    const val PING_MS = 5_000L
    const val DEAD_MS = 15_000L
    const val HANDSHAKE_MS = 5_000L

    /** 실패한 연결 시도: 한 IP 에서 [FAIL_WINDOW_MS] 안에 [FAIL_MAX] 번을 넘으면 [BLOCK_MS] 동안 받지 않는다 */
    private const val FAIL_MAX = 10
    private const val FAIL_WINDOW_MS = 60_000L
    private const val BLOCK_MS = 600_000L

    /** 본 서버가 인증된 접속용 서버를 받지 않을 때 던진다 ([message] 는 접속용 서버 로그에 그대로 찍힌다) */
    class Reject(val code: String, message: String) : Exception(message)

    private fun plain(ch: Channel, o: JsonObject) = ch.writeAndFlush(Unpooled.wrappedBuffer(o.toString().toByteArray(Charsets.UTF_8)))

    private fun parse(buf: ByteBuf): JsonObject = parse(ByteArray(buf.readableBytes()).also { buf.readBytes(it) })

    private fun parse(bytes: ByteArray): JsonObject = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject

    private fun b64(o: JsonObject, key: String, size: Int): ByteArray {
        val bytes = Base64.getDecoder().decode(o.str(key) ?: throw IllegalArgumentException("$key 없음"))
        require(bytes.size == size) { "$key 길이" }
        return bytes
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * 길이 4바이트 + 내용으로 프레임을 나눈다. 한도를 넘는 길이가 적혀 있으면 내용이 오기를 기다리지 않고 바로 오류를 낸다.
     * 한도는 인증 전에는 작게([HANDSHAKE_MAX_FRAME]), 인증이 끝나면 [MAX_FRAME] 으로 올린다.
     */
    private class Frames : ByteToMessageDecoder() {
        @Volatile var limit = HANDSHAKE_MAX_FRAME

        override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
            if (buf.readableBytes() < 4) return
            val len = buf.getInt(buf.readerIndex())
            if (len < 0 || len > limit) {
                buf.skipBytes(buf.readableBytes())
                throw TooLongFrameException("프레임 길이가 올바르지 않습니다 ($len)")
            }
            if (buf.readableBytes() < 4 + len) return
            buf.skipBytes(4)
            out.add(buf.readRetainedSlice(len))
        }
    }

    private fun pipeline(ch: SocketChannel, handler: Conn) {
        val frames = Frames()
        handler.frames = frames
        ch.pipeline().addLast(
            IdleStateHandler(DEAD_MS, PING_MS, 0, TimeUnit.MILLISECONDS),
            frames,
            LengthFieldPrepender(4),
            handler,
        )
    }

    /** 인증이 끝난 뒤의 공통 처리: 암호를 풀어 넘기고, 조용하면 ping 을 보내고, 오래 조용하면 끊는다 */
    private abstract class Conn : SimpleChannelInboundHandler<ByteBuf>() {
        var frames: Frames? = null
        protected var peer: LinkPeer? = null
        protected var handler: LinkHandler? = null

        /** 인증이 끝났을 때: 이제부터 큰 프레임도 받는다 */
        protected fun authed(p: LinkPeer, h: LinkHandler) {
            frames?.limit = MAX_FRAME
            handler = h
            peer = p
        }

        protected abstract fun handshake(ctx: ChannelHandlerContext, msg: JsonObject)
        protected abstract fun failed(ctx: ChannelHandlerContext, why: String)

        override fun channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
            val p = peer
            if (p == null) {
                handshake(ctx, parse(buf))
                return
            }
            val msg = parse(p.open(ByteArray(buf.readableBytes()).also { buf.readBytes(it) }))
            if (msg.str("t") == "ping") return
            // 내용 하나를 처리하다 난 오류로 연결(= 그 접속용 서버의 모든 잠수)을 끊지 않는다. 끊는 것은 풀 수 없는 프레임(위)뿐이다
            try {
                handler?.onMessage(msg)
            } catch (e: Exception) {
                Log.w("[연결] 받은 내용(${msg.str("t")})을 처리하지 못했습니다", e)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is IdleStateEvent) {
                when (evt.state()) {
                    IdleState.READER_IDLE -> if (peer == null) failed(ctx, "응답이 없습니다") else ctx.close()
                    IdleState.WRITER_IDLE -> peer?.send(jsonOf("t" to "ping"))
                    else -> {}
                }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            if (peer == null) failed(ctx, cause.message ?: cause.javaClass.simpleName) else {
                Log.w("[연결] 오류로 끊습니다", cause)
                ctx.close()
            }
        }
    }

    // ------------------------------------------------------------ 본 서버 쪽

    /**
     * 접속용 서버를 받는다. 인증이 끝나면 [accept] 를 부르고(연결 스레드에서), 받지 않으려면 [Reject] 를 던진다.
     * 돌려준 채널을 닫으면 더 받지 않는다.
     */
    fun serve(bind: String, port: Int, psk: ByteArray, group: EventLoopGroup, accept: (Hello, LinkPeer) -> LinkHandler): Channel {
        val fails = ConcurrentHashMap<String, ArrayDeque<Long>>()
        val blockedUntil = ConcurrentHashMap<String, Long>()

        fun failure(ip: String) {
            val now = System.currentTimeMillis()
            val q = fails.computeIfAbsent(ip) { ArrayDeque() }
            synchronized(q) {
                q.addLast(now)
                while (q.isNotEmpty() && now - q.first() > FAIL_WINDOW_MS) q.removeFirst()
                if (q.size > FAIL_MAX) {
                    q.clear()
                    blockedUntil[ip] = now + BLOCK_MS
                    Log.w("[연결] $ip 에서 잘못된 접속 시도가 이어져 10분 동안 받지 않습니다")
                }
            }
        }

        return ServerBootstrap().group(group).channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(4 * 1024 * 1024, 8 * 1024 * 1024))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val ip = ch.remoteAddress()?.address?.hostAddress ?: "?"
                    val until = blockedUntil[ip]
                    if (until != null) {
                        if (System.currentTimeMillis() < until) {
                            ch.close()
                            return
                        }
                        blockedUntil.remove(ip)
                    }
                    pipeline(ch, object : Conn() {
                        private var hello: Hello? = null
                        private var nonceW = ByteArray(0)
                        private val nonceM = LinkCrypto.randomNonce()

                        override fun channelActive(ctx: ChannelHandlerContext) {
                            ctx.executor().schedule({ if (peer == null && ctx.channel().isActive) failed(ctx, "시간 초과") }, HANDSHAKE_MS, TimeUnit.MILLISECONDS)
                        }

                        /** 사유를 알려 주고 닫는다. 본 서버 로그에도 남긴다 ([name] 은 아직 확인되지 않은 값일 수 있어 글자를 걸러 찍는다) */
                        private fun reject(ctx: ChannelHandlerContext, code: String, reason: String, name: String) {
                            Log.w("[노드] 거절: ${name.filter { !it.isISOControl() }.take(64)} ($ip) — $reason")
                            plain(ctx.channel(), jsonOf("t" to "reject", "code" to code, "reason" to reason)).addListener { ctx.close() }
                        }

                        /** 형식이 틀렸거나 응답이 없는 연결 (포트 스캐너 등): 횟수를 세고 조용히 닫는다 */
                        override fun failed(ctx: ChannelHandlerContext, why: String) {
                            failure(ip)
                            ctx.close()
                        }

                        override fun handshake(ctx: ChannelHandlerContext, msg: JsonObject) {
                            val h = hello
                            if (h == null) {
                                if (msg.str("t") != "hello") return failed(ctx, "hello 가 아님")
                                if (msg.int("ver") != LinkCrypto.LINK_VERSION || msg.int("api") != Wire.API) {
                                    val theirs = (msg.str("version") ?: "?").filter { !it.isISOControl() }.take(20)
                                    // 잘못된 접속으로 센다 (비밀키 없이도 올 수 있는 길이라, 거절 로그를 끝없이 쌓게 두지 않는다.
                                    // 버전이 다른 접속용 서버는 1분에 한 번만 다시 붙어 보므로 이것으로 막히지 않는다)
                                    failure(ip)
                                    return reject(ctx, "version", "본 서버와 접속용 서버의 버전이 다릅니다 (본 서버 v${kr.jamsuham.cloud.CLOUD_VERSION}, 접속용 v$theirs)", msg.str("name") ?: "?")
                                }
                                nonceW = b64(msg, "nonce", LinkCrypto.NONCE_BYTES)
                                hello = Hello((msg.str("name") ?: "").take(100), (msg.int("capacity") ?: 0).coerceIn(1, 5000), (msg.str("version") ?: "?").take(20))
                                plain(ctx.channel(), jsonOf("t" to "challenge", "nonce" to b64(nonceM)))
                                return
                            }
                            if (msg.str("t") != "auth") return failed(ctx, "auth 가 아님")
                            if (!LinkCrypto.macEquals(b64(msg, "mac", 32), LinkCrypto.workerMac(psk, nonceW, nonceM, h.name))) {
                                failure(ip)
                                return reject(ctx, "auth", "비밀키가 맞지 않습니다", h.name)
                            }
                            val p = LinkPeer(ctx.channel(), FrameCipher(psk, nonceW, nonceM, isWorker = false))
                            val accepted = try {
                                accept(h, p)
                            } catch (r: Reject) {
                                return reject(ctx, r.code, r.message ?: "받을 수 없습니다", h.name)
                            } catch (e: Exception) {
                                Log.w("[노드] 접속용 서버를 받는 중 오류", e)
                                return reject(ctx, "error", "본 서버 오류로 받지 못했습니다", h.name)
                            }
                            // welcome 이 먼저 나가야 한다: accept 안에서 보낸 것들은 이 함수가 끝난 뒤에 나간다 (LinkPeer.send 가 뒤로 미룸)
                            plain(ctx.channel(), jsonOf("t" to "welcome", "mac" to b64(LinkCrypto.masterMac(psk, nonceW, nonceM))))
                            authed(p, accepted)
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            handler?.onClosed()
                        }
                    })
                }
            })
            .bind(bind, port).sync().channel()
    }

    // ------------------------------------------------------------ 접속용 서버 쪽

    /**
     * 본 서버에 연결한다. 인증이 끝나면 [onUp], 그 전에 실패하면 [onFail] (rejected = 본 서버가 사유를 대며 거절함) 을 한 번 부른다.
     * 연결된 뒤에 끊기면 [onUp] 이 돌려준 [LinkHandler.onClosed] 가 불린다.
     */
    fun dial(
        host: String, port: Int, psk: ByteArray, hello: Hello, group: EventLoopGroup,
        onUp: (LinkPeer) -> LinkHandler, onFail: (reason: String, rejected: Boolean) -> Unit,
    ) {
        var reported = false
        fun fail(reason: String, rejected: Boolean = false) {
            if (reported) return
            reported = true
            onFail(reason, rejected)
        }
        Bootstrap().group(group).channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark(4 * 1024 * 1024, 8 * 1024 * 1024))
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    pipeline(ch, object : Conn() {
                        private val nonceW = LinkCrypto.randomNonce()
                        private var nonceM: ByteArray? = null

                        override fun channelActive(ctx: ChannelHandlerContext) {
                            plain(ctx.channel(), jsonOf(
                                "t" to "hello", "ver" to LinkCrypto.LINK_VERSION, "api" to Wire.API, "version" to hello.version,
                                "name" to hello.name, "capacity" to hello.capacity, "nonce" to b64(nonceW),
                            ))
                            ctx.executor().schedule({ if (peer == null && ctx.channel().isActive) failed(ctx, "본 서버가 응답하지 않습니다") }, HANDSHAKE_MS, TimeUnit.MILLISECONDS)
                        }

                        override fun failed(ctx: ChannelHandlerContext, why: String) {
                            fail(why)
                            ctx.close()
                        }

                        override fun handshake(ctx: ChannelHandlerContext, msg: JsonObject) {
                            when (msg.str("t")) {
                                "reject" -> {
                                    fail(msg.str("reason") ?: "거절", rejected = true)
                                    ctx.close()
                                }
                                "challenge" -> {
                                    val m = b64(msg, "nonce", LinkCrypto.NONCE_BYTES)
                                    nonceM = m
                                    plain(ctx.channel(), jsonOf("t" to "auth", "mac" to b64(LinkCrypto.workerMac(psk, nonceW, m, hello.name))))
                                }
                                "welcome" -> {
                                    val m = nonceM ?: return failed(ctx, "순서가 맞지 않습니다")
                                    // 본 서버도 같은 비밀키를 아는지 확인한다 (가짜 본 서버에 붙지 않게)
                                    if (!LinkCrypto.macEquals(b64(msg, "mac", 32), LinkCrypto.masterMac(psk, nonceW, m))) {
                                        return failed(ctx, "본 서버를 확인할 수 없습니다 (비밀키가 다름)")
                                    }
                                    val p = LinkPeer(ctx.channel(), FrameCipher(psk, nonceW, m, isWorker = true))
                                    authed(p, onUp(p))
                                }
                                else -> failed(ctx, "알 수 없는 응답")
                            }
                        }

                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            val h = handler
                            if (h != null) h.onClosed() else fail("연결이 끊겼습니다")
                        }
                    })
                }
            })
            .connect(host, port).addListener { f -> if (!f.isSuccess) fail(f.cause()?.message ?: "연결할 수 없습니다") }
    }
}
