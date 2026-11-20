package kr.jamsuham.core.net

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import com.viaversion.viaversion.platform.ViaChannelInitializer
import com.viaversion.viaversion.platform.ViaCodecHandler
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.concurrent.DefaultThreadFactory
import kr.jamsuham.core.CloseReason
import kr.jamsuham.core.CoreLog
import kr.jamsuham.core.JoinCredentials
import kr.jamsuham.core.ResolvedTarget
import kr.jamsuham.core.text.ChatText
import kr.jamsuham.core.via.ViaSetup
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/** 연결 하나에서 일어나는 일을 세션에 알려 준다. Netty 스레드에서 불린다. */
interface ConnectionEvents {
    fun onJoined()
    fun onChat(text: ChatText, system: Boolean)
    fun onInfo(message: String)
    fun onClosed(reason: CloseReason)
}

/**
 * 마인크래프트 서버 접속 하나. 앱은 1.21.4(프로토콜 769)로 말하고, 서버 버전이 다르면
 * 파이프라인 중간의 ViaVersion 이 패킷을 변환한다.
 *
 * 하는 일: 로그인/암호화/압축, 설정 단계 응답, keep-alive, 텔레포트 확인,
 * 리소스팩 "적용 완료" 응답, 사망 시 리스폰, 채팅 수신/전송, 서명 채팅 확인 응답.
 */
class McConnection(
    private val target: ResolvedTarget,
    private val serverVersion: ProtocolVersion,
    private val credentials: JoinCredentials,
    private val events: ConnectionEvents,
    private val log: CoreLog,
    private val transferIntent: Boolean = false,
) {
    private enum class State { HANDSHAKE, LOGIN, CONFIG, PLAY }

    @Volatile private var channel: Channel? = null
    @Volatile private var state = State.HANDSHAKE
    private val closed = AtomicBoolean(false)
    @Volatile private var closeReason: CloseReason? = null
    @Volatile private var joined = false

    // 플레이 상태
    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var yaw = 0f
    private var pitch = 0f
    private var hasPosition = false
    private var needPlayerLoaded = false
    private var respawnPending = false
    private var pendingChatAcks = 0
    private var positionTask: ScheduledFuture<*>? = null
    private val random = SecureRandom()
    private var nextSuggestionId = 1
    private val pendingSuggestions = HashMap<Int, (CommandSuggestions?) -> Unit>()

    fun connect() {
        val bootstrap = Bootstrap()
            .group(group)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .attr(ViaSetup.TARGET_VERSION, serverVersion)
            .handler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    val p = ch.pipeline()
                    p.addLast(Pipe.TIMEOUT, ReadTimeoutHandler(READ_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS))
                    p.addLast(Pipe.FRAME, FrameCodec())
                    val user = ViaChannelInitializer.createUserConnection(ch, true)
                    p.addLast(Pipe.VIA, ViaCodecHandler(user))
                    p.addLast(Pipe.HANDLER, Handler())
                }
            })
        log.i("접속 시도: ${target.connectHost}:${target.connectPort} (서버 버전 ${serverVersion.name})")
        bootstrap.connect(target.connectHost, target.connectPort).addListener(ChannelFutureListener { f ->
            if (!f.isSuccess) {
                finish(CloseReason.Lost("접속 실패: ${describe(f.cause())}"))
            } else {
                channel = f.channel()
                if (closed.get()) f.channel().close()
            }
        })
    }

    /** 사용자가 끊음 */
    fun disconnect() {
        closeReason = CloseReason.User
        val ch = channel
        if (ch == null) finish(CloseReason.User) else ch.close()
    }

    /** 채팅 또는 명령어(/로 시작) 보내기 */
    fun sendChat(text: String): Boolean {
        val ch = channel ?: return false
        if (state != State.PLAY || !ch.isActive) return false
        val msg = text.trim()
        if (msg.isEmpty()) return false
        ch.eventLoop().execute {
            if (msg.startsWith("/")) {
                send(PlayOut.CHAT_COMMAND) { it.writeMcString(msg.substring(1).take(32767)) }
            } else {
                val offset = pendingChatAcks
                pendingChatAcks = 0
                send(PlayOut.CHAT) {
                    it.writeMcString(msg.take(256))
                    it.writeLong(System.currentTimeMillis())
                    it.writeLong(random.nextLong())
                    it.writeBoolean(false) // 서명 없음
                    it.writeVarInt(offset)
                    it.writeZero(3) // 확인한 메시지 비트셋(20비트)
                }
            }
        }
        return true
    }

    /**
     * 게임에서 Tab 을 누른 것처럼 서버에 명령어 후보를 묻는다. 결과는 Netty 스레드에서 [callback] 으로 온다.
     * 3초 안에 답이 없으면 null.
     */
    fun requestSuggestions(text: String, callback: (CommandSuggestions?) -> Unit): Boolean {
        val ch = channel ?: return false
        if (state != State.PLAY || !ch.isActive) return false
        ch.eventLoop().execute {
            val id = nextSuggestionId++
            pendingSuggestions[id] = callback
            send(PlayOut.COMMAND_SUGGESTION) {
                it.writeVarInt(id)
                it.writeMcString(text.take(32500))
            }
            ch.eventLoop().schedule({ pendingSuggestions.remove(id)?.invoke(null) }, 3, TimeUnit.SECONDS)
        }
        return true
    }

    private fun finish(reason: CloseReason) {
        if (!closed.compareAndSet(false, true)) return
        positionTask?.cancel(false)
        channel?.let { if (it.isOpen) it.close() }
        events.onClosed(reason)
    }

    private fun send(id: Int, body: (ByteBuf) -> Unit = {}) {
        val ch = channel ?: return
        val buf = ch.alloc().buffer()
        buf.writeVarInt(id)
        body(buf)
        ch.writeAndFlush(buf).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
    }

    private inner class Handler : SimpleChannelInboundHandler<ByteBuf>() {

        override fun channelActive(ctx: ChannelHandlerContext) {
            channel = ctx.channel()
            // 핸드셰이크 (ViaVersion 이 여기서 서버 버전으로 바꿔 보낸다)
            send(0x00) {
                it.writeVarInt(ViaSetup.NATIVE_PROTOCOL)
                it.writeMcString(target.handshakeHost)
                it.writeShort(target.handshakePort)
                it.writeVarInt(if (transferIntent) 3 else 2)
            }
            state = State.LOGIN
            send(LoginOut.HELLO) {
                it.writeMcString(credentials.name)
                it.writeUuid(credentials.uuid)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            finish(closeReason ?: CloseReason.Lost(if (joined) "서버와 연결이 끊어졌습니다" else "로그인 중 연결이 끊어졌습니다"))
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            val reason = when (cause) {
                is ReadTimeoutException -> CloseReason.Lost("서버 응답 없음 (${READ_TIMEOUT_SEC}초)")
                is IOException -> CloseReason.Lost("네트워크 오류: ${describe(cause)}")
                else -> {
                    log.e("패킷 처리 오류", cause)
                    CloseReason.Lost("패킷 처리 오류: ${describe(cause)}")
                }
            }
            if (closeReason == null) closeReason = reason
            ctx.close()
        }

        override fun channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
            val id = buf.readVarInt()
            when (state) {
                State.LOGIN -> handleLogin(ctx, id, buf)
                State.CONFIG -> handleConfig(id, buf)
                State.PLAY -> handlePlay(id, buf)
                State.HANDSHAKE -> {}
            }
        }
    }

    // ------------------------------------------------------------------ login

    private fun handleLogin(ctx: ChannelHandlerContext, id: Int, buf: ByteBuf) {
        when (id) {
            LoginIn.DISCONNECT -> kick(ChatText.fromJson(buf.readMcString()))
            LoginIn.HELLO -> handleEncryption(ctx, buf)
            LoginIn.FINISHED -> {
                val uuid = buf.readUuid()
                val name = buf.readMcString(16)
                log.i("로그인 성공: $name ($uuid)")
                // 상태를 먼저 바꾼다: ViaVersion 이 이 응답을 보내는 도중에
                // 대기시켜 둔 패킷들을 바로 넘겨주기 때문이다 (1.20.1 이하 서버)
                state = State.CONFIG
                send(LoginOut.ACK)
                sendClientInformation(ConfigOut.CLIENT_INFORMATION)
                send(ConfigOut.CUSTOM_PAYLOAD) {
                    it.writeMcString("minecraft:brand")
                    it.writeMcString("vanilla")
                }
            }
            LoginIn.COMPRESSION -> {
                val threshold = buf.readVarInt()
                setCompression(ctx.channel(), threshold)
            }
            LoginIn.CUSTOM_QUERY -> {
                val messageId = buf.readVarInt()
                send(LoginOut.CUSTOM_QUERY_ANSWER) {
                    it.writeVarInt(messageId)
                    it.writeBoolean(false) // 모르는 요청
                }
            }
            LoginIn.COOKIE_REQUEST -> {
                val key = buf.readMcString()
                send(LoginOut.COOKIE_RESPONSE) {
                    it.writeMcString(key)
                    it.writeBoolean(false)
                }
            }
        }
    }

    private fun handleEncryption(ctx: ChannelHandlerContext, buf: ByteBuf) {
        val serverId = buf.readMcString(20)
        val publicKeyBytes = buf.readByteArrayVar()
        val verifyToken = buf.readByteArrayVar()
        val shouldAuthenticate = if (buf.isReadable) buf.readBoolean() else true
        val secret = ByteArray(16).also { random.nextBytes(it) }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyBytes))

        // 비정품 계정이면 세션 인증 없이 진행한다. 정품 전용 서버라면 서버가
        // "Failed to verify username" 같은 킥을 보내고, 세션이 그걸 보고 멈춘다.
        val doJoin = shouldAuthenticate && credentials.online
        if (shouldAuthenticate && !credentials.online) {
            events.onInfo("서버가 암호화를 요청했지만 비정품 계정이라 인증 없이 진행합니다")
        }
        val ch = ctx.channel()
        blocking.execute {
            try {
                if (doJoin) {
                    joinSession(credentials, serverHash(serverId, secret, publicKeyBytes))
                }
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsa.init(Cipher.ENCRYPT_MODE, publicKey)
                val encSecret = rsa.doFinal(secret)
                rsa.init(Cipher.ENCRYPT_MODE, publicKey)
                val encToken = rsa.doFinal(verifyToken)
                ch.eventLoop().execute {
                    send(LoginOut.KEY) {
                        it.writeByteArrayVar(encSecret)
                        it.writeByteArrayVar(encToken)
                    }
                    // 이 응답부터 양방향 암호화
                    ch.pipeline().addBefore(Pipe.FRAME, Pipe.DECRYPT, DecryptHandler(secret))
                    ch.pipeline().addBefore(Pipe.FRAME, Pipe.ENCRYPT, EncryptHandler(secret))
                }
            } catch (e: SessionJoinException) {
                closeReason = if (e.tokenProblem) CloseReason.AuthFailed(e.message ?: "인증 실패") else CloseReason.Lost(e.message ?: "인증 실패")
                ch.close()
            } catch (t: Throwable) {
                log.e("암호화 처리 실패", t)
                closeReason = CloseReason.Lost("암호화 처리 실패: ${describe(t)}")
                ch.close()
            }
        }
    }

    private fun setCompression(ch: Channel, threshold: Int) {
        val p = ch.pipeline()
        if (threshold < 0) {
            if (p.get(Pipe.DECOMPRESS) != null) p.remove(Pipe.DECOMPRESS)
            if (p.get(Pipe.COMPRESS) != null) p.remove(Pipe.COMPRESS)
            return
        }
        val d = p.get(Pipe.DECOMPRESS)
        if (d is DecompressHandler) {
            d.setCompressionThreshold(threshold)
            (p.get(Pipe.COMPRESS) as? CompressHandler)?.setCompressionThreshold(threshold)
        } else {
            p.addBefore(Pipe.VIA, Pipe.DECOMPRESS, DecompressHandler(threshold))
            p.addBefore(Pipe.VIA, Pipe.COMPRESS, CompressHandler(threshold))
        }
    }

    // ------------------------------------------------------------------ configuration

    private fun handleConfig(id: Int, buf: ByteBuf) {
        when (id) {
            ConfigIn.COOKIE_REQUEST -> cookieResponse(ConfigOut.COOKIE_RESPONSE, buf)
            ConfigIn.DISCONNECT -> kick(ChatText.fromTag(buf.readAnonymousTag()))
            ConfigIn.FINISH -> {
                state = State.PLAY // 먼저 바꿔야 Via 가 바로 넘겨주는 접속 패킷을 놓치지 않는다
                send(ConfigOut.FINISH)
            }
            ConfigIn.KEEP_ALIVE -> {
                val v = buf.readLong()
                send(ConfigOut.KEEP_ALIVE) { it.writeLong(v) }
            }
            ConfigIn.PING -> {
                val v = buf.readInt()
                send(ConfigOut.PONG) { it.writeInt(v) }
            }
            ConfigIn.RESOURCE_PACK_PUSH -> resourcePack(buf, ConfigOut.RESOURCE_PACK)
            ConfigIn.TRANSFER -> transfer(buf)
            ConfigIn.SELECT_KNOWN_PACKS -> {
                val count = buf.readVarInt()
                var hasCore = false
                repeat(count) {
                    val ns = buf.readMcString()
                    val packId = buf.readMcString()
                    val version = buf.readMcString()
                    if (ns == "minecraft" && packId == "core" && version == ViaSetup.NATIVE_NAME) hasCore = true
                }
                send(ConfigOut.SELECT_KNOWN_PACKS) {
                    if (hasCore) {
                        it.writeVarInt(1)
                        it.writeMcString("minecraft")
                        it.writeMcString("core")
                        it.writeMcString(ViaSetup.NATIVE_NAME)
                    } else {
                        it.writeVarInt(0)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ play

    private fun handlePlay(id: Int, buf: ByteBuf) {
        when (id) {
            PlayIn.LOGIN -> {
                if (!joined) {
                    joined = true
                    events.onJoined()
                }
                needPlayerLoaded = true
                startPositionReminder()
            }
            PlayIn.KEEP_ALIVE -> {
                val v = buf.readLong()
                send(PlayOut.KEEP_ALIVE) { it.writeLong(v) }
            }
            PlayIn.PING -> {
                val v = buf.readInt()
                send(PlayOut.PONG) { it.writeInt(v) }
            }
            PlayIn.PLAYER_POSITION -> teleport(buf)
            PlayIn.DISCONNECT -> kick(ChatText.fromTag(buf.readAnonymousTag()))
            PlayIn.SYSTEM_CHAT -> {
                val content = ChatText.fromTag(buf.readAnonymousTag())
                val overlay = buf.readBoolean()
                if (!overlay && content.plain.isNotBlank()) events.onChat(content, true)
            }
            PlayIn.PLAYER_CHAT -> playerChat(buf)
            PlayIn.DISGUISED_CHAT -> {
                val message = ChatText.fromTag(buf.readAnonymousTag())
                skipChatTypeHolder(buf)
                val name = ChatText.fromTag(buf.readAnonymousTag())
                events.onChat(ChatText.concat(ChatText.of("<"), name, ChatText.of("> "), message), false)
            }
            PlayIn.RESOURCE_PACK_PUSH -> resourcePack(buf, PlayOut.RESOURCE_PACK)
            PlayIn.SET_HEALTH -> {
                val health = buf.readFloat()
                if (health <= 0f) scheduleRespawn() else respawnPending = false
            }
            PlayIn.PLAYER_COMBAT_KILL -> {
                buf.readVarInt()
                val msg = ChatText.fromTag(buf.readAnonymousTag())
                events.onInfo("사망: ${msg.plain}")
                scheduleRespawn()
            }
            PlayIn.RESPAWN -> {
                needPlayerLoaded = true
                respawnPending = false
            }
            PlayIn.CHUNK_BATCH_FINISHED -> send(PlayOut.CHUNK_BATCH_RECEIVED) { it.writeFloat(10f) }
            PlayIn.START_CONFIGURATION -> {
                positionTask?.cancel(false)
                hasPosition = false
                state = State.CONFIG
                send(PlayOut.CONFIGURATION_ACKNOWLEDGED)
            }
            PlayIn.COOKIE_REQUEST -> cookieResponse(PlayOut.COOKIE_RESPONSE, buf)
            PlayIn.TRANSFER -> transfer(buf)
            PlayIn.COMMAND_SUGGESTIONS -> {
                val id = buf.readVarInt()
                val start = buf.readVarInt()
                val length = buf.readVarInt()
                val count = buf.readVarInt()
                val matches = ArrayList<String>(minOf(count, 200))
                repeat(count) {
                    val m = buf.readMcString()
                    buf.readOptionalAnonymousTag() // 툴팁
                    if (matches.size < 200) matches.add(m)
                }
                pendingSuggestions.remove(id)?.invoke(CommandSuggestions(start, length, matches))
            }
        }
    }

    private fun teleport(buf: ByteBuf) {
        val teleportId = buf.readVarInt()
        val nx = buf.readDouble()
        val ny = buf.readDouble()
        val nz = buf.readDouble()
        buf.skipBytes(24) // 속도 dx, dy, dz
        val nyaw = buf.readFloat()
        val npitch = buf.readFloat()
        val flags = buf.readInt()
        x = if (flags and 0x01 != 0) x + nx else nx
        y = if (flags and 0x02 != 0) y + ny else ny
        z = if (flags and 0x04 != 0) z + nz else nz
        yaw = if (flags and 0x08 != 0) yaw + nyaw else nyaw
        pitch = if (flags and 0x10 != 0) pitch + npitch else npitch
        hasPosition = true
        send(PlayOut.ACCEPT_TELEPORTATION) { it.writeVarInt(teleportId) }
        send(PlayOut.MOVE_PLAYER_POS_ROT) {
            it.writeDouble(x); it.writeDouble(y); it.writeDouble(z)
            it.writeFloat(yaw); it.writeFloat(pitch)
            it.writeByte(0)
        }
        if (needPlayerLoaded) {
            needPlayerLoaded = false
            send(PlayOut.PLAYER_LOADED)
        }
    }

    /** 바닐라 클라이언트처럼 1초마다 현재 위치를 다시 보낸다 */
    private fun startPositionReminder() {
        val ch = channel ?: return
        positionTask?.cancel(false)
        positionTask = ch.eventLoop().scheduleAtFixedRate({
            if (state == State.PLAY && hasPosition && ch.isActive) {
                send(PlayOut.MOVE_PLAYER_POS) {
                    it.writeDouble(x); it.writeDouble(y); it.writeDouble(z)
                    it.writeByte(0x01) // onGround
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun scheduleRespawn() {
        if (respawnPending) return
        respawnPending = true
        val ch = channel ?: return
        events.onInfo("사망해서 1초 뒤 리스폰합니다")
        ch.eventLoop().schedule({
            if (state == State.PLAY) send(PlayOut.CLIENT_COMMAND) { it.writeVarInt(0) }
        }, 1, TimeUnit.SECONDS)
        ch.eventLoop().schedule({ respawnPending = false }, 5, TimeUnit.SECONDS)
    }

    private fun playerChat(buf: ByteBuf) {
        buf.readUuid() // sender
        buf.readVarInt() // index
        val signed = buf.readBoolean()
        if (signed) buf.skipBytes(256)
        val plain = buf.readMcString(256)
        buf.readLong() // timestamp
        buf.readLong() // salt
        val prevCount = buf.readVarInt()
        repeat(prevCount) {
            val pid = buf.readVarInt()
            if (pid == 0) buf.skipBytes(256)
        }
        val unsigned = buf.readOptionalAnonymousTag()
        val filterType = buf.readVarInt()
        if (filterType == 2) {
            val longs = buf.readVarInt()
            buf.skipBytes(longs * 8)
        }
        skipChatTypeHolder(buf)
        val name = ChatText.fromTag(buf.readAnonymousTag())
        val body = if (unsigned != null) ChatText.fromTag(unsigned) else ChatText.of(plain)
        events.onChat(ChatText.concat(ChatText.of("<"), name, ChatText.of("> "), body), false)

        if (signed) {
            pendingChatAcks++
            if (pendingChatAcks > 64) {
                val n = pendingChatAcks
                pendingChatAcks = 0
                send(PlayOut.CHAT_ACK) { it.writeVarInt(n) }
            }
        }
    }

    private fun skipChatTypeHolder(buf: ByteBuf) {
        val holder = buf.readVarInt()
        if (holder == 0) {
            repeat(2) { // chat, narration
                buf.readMcString()
                val params = buf.readVarInt()
                repeat(params) { buf.readVarInt() }
                buf.readOptionalAnonymousTag()
            }
        }
    }

    private fun resourcePack(buf: ByteBuf, responseId: Int) {
        val uuid = buf.readUuid()
        val url = buf.readMcString()
        buf.readMcString(40) // hash
        val forced = buf.readBoolean()
        events.onInfo("리소스팩 요청${if (forced) "(필수)" else ""} → 실제로 받지 않고 적용한 것으로 응답: $url")
        // 3 = 수락, 4 = 다운로드 완료, 0 = 적용 성공. 실제 클라이언트처럼 약간 시간차를 둔다.
        val ch = channel ?: return
        val expectedState = state
        fun reply(status: Int) {
            if (state == expectedState && ch.isActive) {
                send(responseId) {
                    it.writeUuid(uuid)
                    it.writeVarInt(status)
                }
            }
        }
        reply(3)
        ch.eventLoop().schedule({ reply(4) }, 700, TimeUnit.MILLISECONDS)
        ch.eventLoop().schedule({ reply(0) }, 1200, TimeUnit.MILLISECONDS)
    }

    private fun cookieResponse(id: Int, buf: ByteBuf) {
        val key = buf.readMcString()
        send(id) {
            it.writeMcString(key)
            it.writeBoolean(false)
        }
    }

    private fun transfer(buf: ByteBuf) {
        val host = buf.readMcString()
        val port = buf.readVarInt()
        closeReason = CloseReason.Transfer(host, port)
        channel?.close()
    }

    private fun kick(text: ChatText) {
        closeReason = CloseReason.Kicked(text, !joined)
        channel?.close()
    }

    private fun sendClientInformation(id: Int) {
        send(id) {
            it.writeMcString("ko_kr")
            it.writeByte(2) // 시야 거리 2청크 (데이터/배터리 절약)
            it.writeVarInt(0) // 채팅 모두 표시
            it.writeBoolean(true) // 채팅 색상
            it.writeByte(0x7F) // 스킨 부위
            it.writeVarInt(1) // 오른손
            it.writeBoolean(false) // 텍스트 필터링
            it.writeBoolean(true) // 서버 목록에 표시
            it.writeVarInt(2) // 파티클 최소
        }
    }

    // ------------------------------------------------------------------ session server

    private class SessionJoinException(message: String, val tokenProblem: Boolean) : Exception(message)

    private fun joinSession(c: JoinCredentials, hash: String) {
        val token = c.accessToken ?: throw SessionJoinException("정품 계정이 아닙니다", true)
        @Suppress("DEPRECATION")
        val conn = URL("https://sessionserver.mojang.com/session/minecraft/join").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = "{\"accessToken\":\"${jsonEscape(token)}\",\"selectedProfile\":\"${c.uuid.toString().replace("-", "")}\",\"serverId\":\"${jsonEscape(hash)}\"}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 204 || code == 200) return
            val err = (conn.errorStream ?: conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code == 401 || code == 403) {
                throw SessionJoinException("마이크로소프트 인증이 만료됐거나 거부됐습니다 ($code) - 다시 로그인해 주세요", true)
            }
            throw SessionJoinException("세션 서버 오류 $code ${err.take(200)}", false)
        } catch (e: IOException) {
            throw SessionJoinException("세션 서버에 연결하지 못했습니다: ${describe(e)}", false)
        } finally {
            conn.disconnect()
        }
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val READ_TIMEOUT_SEC = 30

        /** 모든 연결이 같이 쓰는 Netty 스레드 */
        @Suppress("DEPRECATION")
        val group: EventLoopGroup by lazy { NioEventLoopGroup(2, DefaultThreadFactory("jamsuham-net", true)) }

        /** 세션 서버 요청처럼 오래 걸리는 작업용 */
        private val blocking = Executors.newCachedThreadPool(DefaultThreadFactory("jamsuham-io", true))

        fun serverHash(serverId: String, secret: ByteArray, publicKey: ByteArray): String {
            val sha = MessageDigest.getInstance("SHA-1")
            sha.update(serverId.toByteArray(Charsets.ISO_8859_1))
            sha.update(secret)
            sha.update(publicKey)
            return BigInteger(sha.digest()).toString(16)
        }

        fun describe(t: Throwable?): String {
            if (t == null) return "알 수 없음"
            val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            return msg.take(200)
        }
    }
}

/**
 * 서버가 준 명령어 후보. 입력한 글자의 [start] 부터 [length] 글자를 후보 하나로 바꾸면 된다.
 */
data class CommandSuggestions(val start: Int, val length: Int, val matches: List<String>) {
    fun apply(input: String, match: String): String {
        val s = start.coerceIn(0, input.length)
        val e = (start + length).coerceIn(s, input.length)
        return input.substring(0, s) + match + input.substring(e)
    }
}

/** 1.21.4 패킷 번호 (ViaVersion 의 ClientboundPackets1_21_2 / ServerboundPackets1_21_4 기준) */
internal object LoginIn {
    const val DISCONNECT = 0x00
    const val HELLO = 0x01
    const val FINISHED = 0x02
    const val COMPRESSION = 0x03
    const val CUSTOM_QUERY = 0x04
    const val COOKIE_REQUEST = 0x05
}

internal object LoginOut {
    const val HELLO = 0x00
    const val KEY = 0x01
    const val CUSTOM_QUERY_ANSWER = 0x02
    const val ACK = 0x03
    const val COOKIE_RESPONSE = 0x04
}

internal object ConfigIn {
    const val COOKIE_REQUEST = 0x00
    const val CUSTOM_PAYLOAD = 0x01
    const val DISCONNECT = 0x02
    const val FINISH = 0x03
    const val KEEP_ALIVE = 0x04
    const val PING = 0x05
    const val RESOURCE_PACK_PUSH = 0x09
    const val TRANSFER = 0x0B
    const val SELECT_KNOWN_PACKS = 0x0E
}

internal object ConfigOut {
    const val CLIENT_INFORMATION = 0x00
    const val COOKIE_RESPONSE = 0x01
    const val CUSTOM_PAYLOAD = 0x02
    const val FINISH = 0x03
    const val KEEP_ALIVE = 0x04
    const val PONG = 0x05
    const val RESOURCE_PACK = 0x06
    const val SELECT_KNOWN_PACKS = 0x07
}

internal object PlayIn {
    const val CHUNK_BATCH_FINISHED = 0x0C
    const val COMMAND_SUGGESTIONS = 0x10
    const val COOKIE_REQUEST = 0x16
    const val DISCONNECT = 0x1D
    const val DISGUISED_CHAT = 0x1E
    const val KEEP_ALIVE = 0x27
    const val LOGIN = 0x2C
    const val PING = 0x37
    const val PLAYER_CHAT = 0x3B
    const val PLAYER_COMBAT_KILL = 0x3E
    const val PLAYER_POSITION = 0x42
    const val RESOURCE_PACK_PUSH = 0x4B
    const val RESPAWN = 0x4C
    const val SET_HEALTH = 0x62
    const val START_CONFIGURATION = 0x70
    const val SYSTEM_CHAT = 0x73
    const val TRANSFER = 0x7A
}

internal object PlayOut {
    const val ACCEPT_TELEPORTATION = 0x00
    const val CHAT_ACK = 0x04
    const val CHAT_COMMAND = 0x05
    const val CHAT = 0x07
    const val CHUNK_BATCH_RECEIVED = 0x09
    const val CLIENT_COMMAND = 0x0A
    const val COMMAND_SUGGESTION = 0x0D
    const val CONFIGURATION_ACKNOWLEDGED = 0x0E
    const val COOKIE_RESPONSE = 0x13
    const val KEEP_ALIVE = 0x1A
    const val MOVE_PLAYER_POS = 0x1C
    const val MOVE_PLAYER_POS_ROT = 0x1D
    const val PLAYER_LOADED = 0x2A
    const val PONG = 0x2B
    const val RESOURCE_PACK = 0x2F
}
