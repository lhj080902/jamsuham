package kr.jamsuham.core.demo

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.ScheduledFuture
import kr.jamsuham.core.Lang
import kr.jamsuham.core.net.ConfigOut
import kr.jamsuham.core.net.FrameCodec
import kr.jamsuham.core.net.LoginOut
import kr.jamsuham.core.net.PlayIn
import kr.jamsuham.core.net.PlayOut
import kr.jamsuham.core.net.readMcString
import kr.jamsuham.core.net.readVarInt
import kr.jamsuham.core.net.writeMcString
import kr.jamsuham.core.net.writeVarInt
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 체험 서버: 앱 안에서 도는 작은 연습용 1.21.4 서버.
 * 로그인 없이 앱의 화면(채팅·지도·인벤토리·접속자·메뉴)을 써 볼 수 있게, 녹화해 둔 장면([DemoScene])을 다시 보내고
 * 채팅 되돌려 주기·`/메뉴`·`/잠수`·메뉴 누르기·버리기·사용만 직접 답한다. 지어낸 서버와 닉네임만 나온다.
 *
 * 이 기기 안(127.0.0.1)에서만 열리고, 포트는 켤 때마다 운영체제가 정한다. 인터넷으로 나가는 것은 없다.
 * 장면의 언어는 서버를 켠 때가 아니라 **접속할 때**의 [Lang.english] 를 따른다.
 */
object DemoServer {
    /** 체험 서버에서 쓰는 지어낸 닉네임 (장면의 접속자 목록에도 이 이름이 있다) */
    const val NAME = "Jamsu_Diver"

    private const val MENU_WINDOW = 9
    private const val KEEP_ALIVE_SEC = 10L
    private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private var group: EventLoopGroup? = null
    private var channel: Channel? = null
    private var port = 0

    /** 켜져 있지 않으면 켠다. 돌려주는 값은 포트 */
    @Synchronized
    fun ensureStarted(): Int {
        if (channel?.isActive == true) return port
        stop()
        val g = NioEventLoopGroup(1, DefaultThreadFactory("jamsuham-demo", true))
        try {
            val ch = ServerBootstrap().group(g)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(FrameCodec(), Connection())
                    }
                })
                .bind(LOOPBACK, 0).sync().channel()
            group = g
            channel = ch
            port = (ch.localAddress() as InetSocketAddress).port
            return port
        } catch (e: Throwable) {
            g.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
            throw e
        }
    }

    /** 접속할 주소 ("127.0.0.1:포트"). 꺼져 있으면 켠다 */
    fun address(): String = "127.0.0.1:${ensureStarted()}"

    /** 끈다 (열려 있던 접속도 끊긴다). 다시 [address] 를 부르면 새 포트로 켜진다 */
    @Synchronized
    fun stop() {
        channel?.close()?.syncUninterruptibly()
        group?.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS)?.syncUninterruptibly()
        channel = null
        group = null
        port = 0
    }

    private val icon: String? by lazy {
        runCatching {
            DemoServer::class.java.getResourceAsStream("/jamsuham/demo/icon.png")!!.use { "data:image/png;base64," + Base64.getEncoder().encodeToString(it.readBytes()) }
        }.getOrNull()
    }

    /** 서버 목록에 보일 내용 (스토어 스크린샷의 첫 서버와 같은 값) */
    private fun statusJson(english: Boolean): String {
        val motd = if (english) "§bFishing and AFK rewards" else "§b낚시와 잠수 보상"
        val favicon = icon?.let { ",\"favicon\":\"$it\"" } ?: ""
        return "{\"version\":{\"name\":\"1.21.4\",\"protocol\":769},\"players\":{\"max\":100,\"online\":37},\"description\":{\"text\":\"$motd\"}$favicon}"
    }

    /** 메뉴 칸 → 이름 (장면의 메뉴와 같은 글, tools/test-server/showcase.js) */
    private val MENU_KO = mapOf(10 to "마을 광장으로 이동", 11 to "낚시터", 13 to "잠수 보상 받기", 15 to "상점", 16 to "출석 체크", 22 to "닫기")
    private val MENU_EN = mapOf(10 to "Go to village square", 11 to "Fishing spot", 13 to "Claim AFK reward", 15 to "Shop", 16 to "Daily check-in", 22 to "Close")

    private enum class State { HANDSHAKE, STATUS, LOGIN, CONFIG, PLAY }

    /** 접속 하나. 모든 일은 그 접속의 네티 스레드에서 일어난다 */
    private class Connection : SimpleChannelInboundHandler<ByteBuf>() {
        private var state = State.HANDSHAKE
        private var english = false
        private var scene: DemoScene? = null

        /** 인벤토리 칸 → 남은 개수 (버리면 줄어든다. 접속마다 처음 값으로 돌아감) */
        private val counts = HashMap<Int, Int>()
        private val timers = ArrayList<ScheduledFuture<*>>()

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            // 풀 수 없는 패킷이 와도 그 패킷만 버리고 접속은 둔다
            try {
                handle(ctx, msg)
            } catch (_: Exception) {
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            timers.forEach { it.cancel(false) }
            timers.clear()
            super.channelInactive(ctx)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.close()
        }

        private fun handle(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val id = msg.readVarInt()
            when (state) {
                State.HANDSHAKE -> {
                    if (id != 0x00) return
                    msg.readVarInt() // 프로토콜 번호
                    msg.readMcString(255)
                    msg.readUnsignedShort()
                    when (msg.readVarInt()) {
                        1 -> state = State.STATUS
                        2 -> state = State.LOGIN
                        else -> ctx.close()
                    }
                }
                State.STATUS -> when (id) {
                    0x00 -> send(ctx, 0x00) { it.writeMcString(statusJson(Lang.english)) }
                    0x01 -> {
                        val value = msg.readLong()
                        send(ctx, 0x01) { it.writeLong(value) }
                    }
                }
                State.LOGIN -> when (id) {
                    LoginOut.HELLO -> {
                        english = Lang.english
                        val loaded = runCatching { DemoScene.load(english) }.getOrNull()
                        if (loaded == null) {
                            ctx.close()
                            return
                        }
                        scene = loaded
                        loaded.login.forEach { raw(ctx, it) }
                        ctx.flush()
                    }
                    LoginOut.ACK -> {
                        val s = scene ?: return
                        state = State.CONFIG
                        s.config.forEach { raw(ctx, it) }
                        ctx.flush()
                    }
                }
                State.CONFIG -> if (id == ConfigOut.FINISH) {
                    state = State.PLAY
                    startPlay(ctx)
                }
                State.PLAY -> play(ctx, id, msg)
            }
        }

        /** 녹화한 장면을 녹화한 때의 간격대로 보낸다 */
        private fun startPlay(ctx: ChannelHandlerContext) {
            val s = scene ?: return
            for ((slot, bytes) in s.slots) counts[slot] = bytes[DemoScene.COUNT_AT].toInt()
            var at = 0L
            for (packet in s.play) {
                at += packet.delayMs
                if (at == 0L) {
                    raw(ctx, packet.bytes)
                } else {
                    timers += ctx.executor().schedule({
                        if (ctx.channel().isActive) {
                            raw(ctx, packet.bytes)
                            ctx.flush()
                        }
                    }, at, TimeUnit.MILLISECONDS)
                }
            }
            ctx.flush()
            timers += ctx.executor().scheduleAtFixedRate({
                if (ctx.channel().isActive) send(ctx, PlayIn.KEEP_ALIVE) { it.writeLong(System.nanoTime()) }
            }, KEEP_ALIVE_SEC, KEEP_ALIVE_SEC, TimeUnit.SECONDS)
        }

        private fun play(ctx: ChannelHandlerContext, id: Int, msg: ByteBuf) {
            when (id) {
                PlayOut.CHAT -> chat(ctx, "<$NAME> " + msg.readMcString(256))
                PlayOut.CHAT_COMMAND -> command(ctx, msg.readMcString(32767))
                PlayOut.CONTAINER_CLICK -> {
                    val window = msg.readVarInt()
                    msg.readVarInt() // stateId
                    val slot = msg.readShort().toInt()
                    val button = msg.readByte().toInt()
                    val mode = msg.readVarInt()
                    if (window == MENU_WINDOW) menuClick(ctx, slot)
                    else if (window == 0 && mode == 4) drop(ctx, slot, all = button == 1)
                }
                PlayOut.USE_ITEM -> chat(ctx, say("§7아이템을 사용했습니다", "§7Item used"))
            }
        }

        private fun command(ctx: ChannelHandlerContext, command: String) {
            when (command.trim().substringBefore(' ').lowercase()) {
                "메뉴", "menu" -> {
                    scene?.menu?.forEach { raw(ctx, it) }
                    ctx.flush()
                }
                "잠수", "afk" -> chat(ctx, say("§a[잠수] §f잠수 모드가 켜졌습니다. 보상을 기다리는 중…", "§a[AFK] §fAFK mode is on. Waiting for rewards…"))
                "tpaccept" -> chat(ctx, say("§b[텔레포트] §f요청을 수락했습니다", "§b[Teleport] §fRequest accepted"))
                "tpdeny" -> chat(ctx, say("§b[텔레포트] §f요청을 거절했습니다", "§b[Teleport] §fRequest denied"))
                else -> chat(ctx, say("§c알 수 없는 명령어입니다. §f/메뉴§c, §f/잠수§c 를 써 보세요", "§cUnknown command. Try §f/menu§c or §f/afk"))
            }
        }

        private fun menuClick(ctx: ChannelHandlerContext, slot: Int) {
            val name = (if (english) MENU_EN else MENU_KO)[slot] ?: return
            chat(ctx, say("§6[메뉴] §f'$name' 을(를) 눌렀습니다", "§6[Menu] §fYou tapped '$name'"))
            send(ctx, PlayIn.CONTAINER_CLOSE) { it.writeVarInt(MENU_WINDOW) }
        }

        /** 버리기: 그 칸의 개수를 줄여서 다시 알려 준다 (전부면 빈 칸) */
        private fun drop(ctx: ChannelHandlerContext, slot: Int, all: Boolean) {
            val base = scene?.slots?.get(slot) ?: return
            val left = counts[slot] ?: return
            if (left <= 0) return
            val now = if (all) 0 else left - 1
            counts[slot] = now
            val packet = if (now == 0) base.copyOf(DemoScene.COUNT_AT + 1) else base.copyOf()
            packet[DemoScene.COUNT_AT] = now.toByte()
            raw(ctx, packet)
            ctx.flush()
        }

        private fun say(ko: String, en: String) = if (english) en else ko

        /** 시스템 채팅 한 줄 (§ 색 코드가 든 글) */
        private fun chat(ctx: ChannelHandlerContext, text: String) {
            send(ctx, PlayIn.SYSTEM_CHAT) {
                it.writeBytes(textTag(text))
                it.writeBoolean(false) // 화면 가운데 글(액션바)이 아님
            }
        }

        private fun send(ctx: ChannelHandlerContext, id: Int, body: (ByteBuf) -> Unit) {
            val buf = ctx.alloc().buffer()
            buf.writeVarInt(id)
            body(buf)
            ctx.writeAndFlush(buf)
        }

        private fun raw(ctx: ChannelHandlerContext, bytes: ByteArray) {
            ctx.write(Unpooled.wrappedBuffer(bytes))
        }
    }

    /** 이름 없는 NBT 묶음 {text: "…"} — 1.21.4 채팅 글의 모양 */
    private fun textTag(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(10) // 묶음
        out.writeByte(8) // 문자열
        out.writeUTF("text")
        out.writeUTF(text)
        out.writeByte(0) // 끝
        return bytes.toByteArray()
    }
}
