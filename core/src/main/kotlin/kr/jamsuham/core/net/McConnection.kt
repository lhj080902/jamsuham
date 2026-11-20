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
import kr.jamsuham.core.AutoClickRule
import kr.jamsuham.core.JoinCredentials
import kr.jamsuham.core.Inventory
import kr.jamsuham.core.Menu
import kr.jamsuham.core.MenuItem
import kr.jamsuham.core.PlayerInfo
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.ResolvedTarget
import kr.jamsuham.core.text.ChatText
import kr.jamsuham.core.via.ViaSetup
import kr.jamsuham.core.world.Collision
import kr.jamsuham.core.world.World
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
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
    fun onPlayers(list: PlayerList) {}
    fun onMenu(menu: Menu?) {}
    fun onInventory(inventory: Inventory) {}
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
    private val autoClickRules: List<AutoClickRule> = emptyList(),
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
    // 열린 메뉴
    @Volatile private var menuId = -1

    /** 이 서버가 정품 인증(모장 세션 서버)을 요구했는지 */
    @Volatile var usedSessionServer = false
        private set

    /** 지금 있는 월드 이름 (예: minecraft:overworld, 멀티버스 월드면 minecraft:월드이름) */
    @Volatile var worldName: String = ""
        private set
    private var invStateId = 0
    private var useSequence = 1

    /** 서버 메뉴(상자 창)가 열려 있는지. 열려 있는 동안에는 접속 후 자동 명령어를 미룬다 */
    val hasOpenMenu: Boolean get() = menuId > 0

    /** 마지막으로 메뉴가 닫힌 시각 (닫힌 직후에는 서버가 리소스팩 적용 등을 처리하므로 잠깐 기다린다) */
    @Volatile var lastMenuClosedMs = 0L
        private set
    private var menuTitle = ChatText(emptyList())
    private var menuType = 0
    private var menuStateId = 0
    private var menuSlots: Array<MenuItem?> = emptyArray()
    private var menuAutoClicked = false
    private var menuFlushPending = false

    /** 내 인벤토리 (창 0번, 46칸) */
    private val invSlots = arrayOfNulls<MenuItem>(46)
    private var heldSlot = 0
    private var invFlushPending = false

    /** 주변 블록과 다른 플레이어 위치 (이동·미니맵용) */
    val world = World()
    private val players = LinkedHashMap<UUID, PlayerInfo>()
    private val listedPlayers = HashSet<UUID>()
    private var tabHeader = ChatText(emptyList())
    private var tabFooter = ChatText(emptyList())
    private var playersFlushPending = false
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
                    p.addLast(Pipe.VIA, SafeViaCodec(user, log))
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
                    usedSessionServer = true
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
                closeReason = when {
                    e.tokenProblem -> CloseReason.AuthFailed(e.message ?: "인증 실패")
                    e.rateLimited -> CloseReason.RateLimited(e.message ?: "모장 요청 제한")
                    e.serviceDown -> CloseReason.ServiceDown(e.message ?: "모장 인증 서버 장애")
                    else -> CloseReason.Lost(e.message ?: "인증 실패")
                }
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
                try {
                    buf.readInt() // 엔티티 번호
                    buf.readBoolean() // 하드코어
                    repeat(buf.readVarInt()) { buf.readMcString() } // 월드 목록
                    buf.readVarInt(); buf.readVarInt(); buf.readVarInt() // 최대 인원, 시야, 시뮬레이션 거리
                    buf.readBoolean(); buf.readBoolean(); buf.readBoolean()
                    buf.readVarInt() // 차원 종류
                    worldName = buf.readMcString()
                } catch (_: Exception) {
                }
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
                try {
                    buf.readVarInt()
                    worldName = buf.readMcString()
                } catch (_: Exception) {
                }
                world.clear()
                if (menuId > 0) clearMenu()
                vy = 0.0
                needPlayerLoaded = true
                respawnPending = false
            }
            PlayIn.CHUNK_BATCH_FINISHED -> send(PlayOut.CHUNK_BATCH_RECEIVED) { it.writeFloat(10f) }
            PlayIn.START_CONFIGURATION -> {
                world.clear()
                positionTask?.cancel(false)
                hasPosition = false
                state = State.CONFIG
                send(PlayOut.CONFIGURATION_ACKNOWLEDGED)
            }
            PlayIn.COOKIE_REQUEST -> cookieResponse(PlayOut.COOKIE_RESPONSE, buf)
            PlayIn.TRANSFER -> transfer(buf)
            PlayIn.LEVEL_CHUNK -> {
                val cx = buf.readInt()
                val cz = buf.readInt()
                buf.readAnonymousTag() // heightmaps
                val size = buf.readVarInt()
                world.loadChunk(cx, cz, buf.readSlice(size))
            }
            PlayIn.FORGET_CHUNK -> {
                val cz = buf.readInt()
                val cx = buf.readInt()
                world.unload(cx, cz)
            }
            PlayIn.BLOCK_UPDATE -> {
                val v = buf.readLong()
                world.setBlock((v shr 38).toInt(), (v shl 52 shr 52).toInt(), (v shl 26 shr 38).toInt(), buf.readVarInt())
            }
            PlayIn.SECTION_BLOCKS_UPDATE -> {
                val v = buf.readLong()
                val sx = (v shr 42).toInt()
                val sy = (v shl 44 shr 44).toInt()
                val sz = (v shl 22 shr 42).toInt()
                val n = buf.readVarInt()
                repeat(n) {
                    val e = buf.readVarLong()
                    val local = (e and 0xFFF).toInt()
                    world.setBlock((sx shl 4) + ((local shr 8) and 15), (sy shl 4) + (local and 15), (sz shl 4) + ((local shr 4) and 15), (e ushr 12).toInt())
                }
            }
            PlayIn.ADD_ENTITY -> {
                val id = buf.readVarInt()
                val uuid = buf.readUuid()
                val type = buf.readVarInt()
                val ex = buf.readDouble()
                val ey = buf.readDouble()
                val ez = buf.readDouble()
                if (type == PLAYER_ENTITY_TYPE) world.players[id] = World.Entity(id, uuid, ex, ey, ez)
            }
            PlayIn.MOVE_ENTITY_POS, PlayIn.MOVE_ENTITY_POS_ROT -> {
                val e = world.players[buf.readVarInt()]
                if (e != null) {
                    e.x += buf.readShort() / 4096.0
                    e.y += buf.readShort() / 4096.0
                    e.z += buf.readShort() / 4096.0
                }
            }
            PlayIn.TELEPORT_ENTITY, PlayIn.ENTITY_POSITION_SYNC -> {
                val e = world.players[buf.readVarInt()]
                if (e != null) {
                    val nx = buf.readDouble()
                    val ny = buf.readDouble()
                    val nz = buf.readDouble()
                    buf.skipBytes(24 + 8) // 속도, 방향
                    val rel = if (id == PlayIn.TELEPORT_ENTITY && buf.readableBytes() >= 5) buf.readInt() else 0
                    e.x = if (rel and 1 != 0) e.x + nx else nx
                    e.y = if (rel and 2 != 0) e.y + ny else ny
                    e.z = if (rel and 4 != 0) e.z + nz else nz
                }
            }
            PlayIn.REMOVE_ENTITIES -> {
                val n = buf.readVarInt()
                repeat(n) { world.players.remove(buf.readVarInt()) }
            }
            PlayIn.OPEN_SCREEN -> {
                menuId = buf.readVarInt()
                menuType = buf.readVarInt()
                menuTitle = ChatText.fromTag(buf.readAnonymousTag())
                menuSlots = emptyArray()
                menuAutoClicked = false
                events.onInfo("서버 메뉴 열림: ${menuTitle.plain.ifBlank { "(제목 없음)" }}")
            }
            PlayIn.CONTAINER_SET_CONTENT -> {
                val wid = buf.readVarInt()
                val stateId = buf.readVarInt()
                if (wid == menuId && wid != 0) {
                    menuStateId = stateId
                    val n = buf.readVarInt()
                    menuSlots = Array(n) { i -> readMenuItem(buf, i) }
                    scheduleMenuFlush()
                } else if (wid == 0) {
                    invStateId = stateId
                    val n = buf.readVarInt()
                    for (i in 0 until n) {
                        val item = readMenuItem(buf, i)
                        if (i < invSlots.size) invSlots[i] = item
                    }
                    scheduleInventoryFlush()
                }
            }
            PlayIn.CONTAINER_SET_SLOT -> {
                val wid = buf.readVarInt()
                val stateId = buf.readVarInt()
                val slot = buf.readShort().toInt()
                if (wid == menuId && wid != 0 && slot in menuSlots.indices) {
                    menuStateId = stateId
                    menuSlots[slot] = readMenuItem(buf, slot)
                    scheduleMenuFlush()
                } else if (wid == 0 && slot in invSlots.indices) {
                    invStateId = stateId
                    invSlots[slot] = readMenuItem(buf, slot)
                    scheduleInventoryFlush()
                }
            }
            PlayIn.SET_PLAYER_INVENTORY -> {
                // 인벤토리 번호(0~8 핫바, 9~35 가방, 36~39 발·다리·몸통·머리, 40 왼손) → 창 칸 번호
                val idx = buf.readVarInt()
                val slot = when (idx) {
                    in 0..8 -> 36 + idx
                    in 9..35 -> idx
                    in 36..39 -> 44 - idx
                    40 -> 45
                    else -> -1
                }
                if (slot >= 0) {
                    invSlots[slot] = readMenuItem(buf, slot)
                    scheduleInventoryFlush()
                }
            }
            PlayIn.SET_HELD_SLOT -> {
                heldSlot = buf.readVarInt().coerceIn(0, 8)
                scheduleInventoryFlush()
            }
            PlayIn.CONTAINER_CLOSE -> {
                val wid = buf.readVarInt()
                if (wid == menuId) clearMenu()
            }
            PlayIn.PLAYER_INFO_UPDATE -> playerInfoUpdate(buf)
            PlayIn.PLAYER_INFO_REMOVE -> {
                val n = buf.readVarInt()
                repeat(n) {
                    val id = buf.readUuid()
                    players.remove(id)
                    listedPlayers.remove(id)
                }
                schedulePlayersFlush()
            }
            PlayIn.TAB_LIST -> {
                tabHeader = ChatText.fromTag(buf.readAnonymousTag())
                tabFooter = ChatText.fromTag(buf.readAnonymousTag())
                schedulePlayersFlush()
            }
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

    /** 1.21.4 플레이어 정보 갱신. 동작 비트: 0 추가, 1 채팅세션, 2 게임모드, 3 목록표시, 4 핑, 5 표시이름, 6 정렬순서, 7 모자 */
    private fun playerInfoUpdate(buf: ByteBuf) {
        val actions = buf.readUnsignedByte().toInt()
        val n = buf.readVarInt()
        repeat(n) {
            val id = buf.readUuid()
            var p = players[id]
            if (actions and 0x01 != 0) {
                val name = buf.readMcString(16)
                var skin: String? = null
                val props = buf.readVarInt()
                repeat(props) {
                    val pn = buf.readMcString()
                    val pv = buf.readMcString()
                    if (buf.readBoolean()) buf.readMcString()
                    if (pn == "textures") skin = skinUrlFromTextures(pv)
                }
                p = PlayerInfo(id, name, null, 0, 0, skin, 0)
            }
            if (actions and 0x02 != 0 && buf.readBoolean()) {
                buf.readUuid()
                buf.readLong()
                buf.readByteArrayVar()
                buf.readByteArrayVar()
            }
            if (actions and 0x04 != 0) {
                val gm = buf.readVarInt()
                p = p?.copy(gameMode = gm)
            }
            if (actions and 0x08 != 0) {
                if (buf.readBoolean()) listedPlayers.add(id) else listedPlayers.remove(id)
            }
            if (actions and 0x10 != 0) {
                val ping = buf.readVarInt()
                p = p?.copy(latencyMs = ping)
            }
            if (actions and 0x20 != 0) {
                val tag = buf.readOptionalAnonymousTag()
                p = p?.copy(displayName = tag?.let { ChatText.fromTag(it) })
            }
            if (actions and 0x40 != 0) {
                val order = buf.readVarInt()
                p = p?.copy(order = order)
            }
            if (actions and 0x80 != 0) buf.readBoolean()
            if (p != null) players[id] = p
        }
        schedulePlayersFlush()
    }

    private fun skinUrlFromTextures(base64: String): String? = try {
        val json = String(java.util.Base64.getDecoder().decode(base64), Charsets.UTF_8)
        val url = com.google.gson.JsonParser.parseString(json).asJsonObject
            .getAsJsonObject("textures")?.getAsJsonObject("SKIN")?.get("url")?.asString
        url?.replaceFirst("http://", "https://")
    } catch (_: Throwable) {
        null
    }

    /** 목록이 한꺼번에 바뀌는 경우가 많아 0.3초 모았다가 알린다 */
    private fun schedulePlayersFlush() {
        if (playersFlushPending) return
        val ch = channel ?: return
        playersFlushPending = true
        ch.eventLoop().schedule({
            playersFlushPending = false
            val list = players.values
                .filter { listedPlayers.contains(it.uuid) }
                .sortedWith(compareByDescending<PlayerInfo> { it.order }.thenBy { if (it.gameMode == 3) 1 else 0 }.thenBy { it.name.lowercase() })
            events.onPlayers(PlayerList(list, tabHeader, tabFooter))
        }, 300, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------ 메뉴 (상자 GUI)

    private fun readMenuItem(buf: ByteBuf, slot: Int): MenuItem? {
        val item = com.viaversion.viaversion.api.type.types.version.VersionedTypes.V1_21_4.item.read(buf) ?: return null
        if (item.isEmpty) return null
        val data = item.dataContainer()
        val custom = data.get(com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.CUSTOM_NAME)
        val itemName = data.get(com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.ITEM_NAME)
        val lore = data.get(com.viaversion.viaversion.api.minecraft.data.StructuredDataKey.LORE)?.map { ChatText.fromTag(it) } ?: emptyList()
        val id = itemIdentifier(item.identifier())
        // 리소스팩 전용 글꼴로만 된 이름은 앱에서 안 보이므로 기본 아이템 이름(한국어)으로 대신한다
        val name = custom?.let { ChatText.fromTag(it) }?.takeIf { it.plain.isNotBlank() }
            ?: itemName?.let { ChatText.fromTag(it) }?.takeIf { it.plain.isNotBlank() }
            ?: ChatText.of(kr.jamsuham.core.text.ItemNames.name(id))
        return MenuItem(slot, id, item.amount(), name, lore)
    }

    private fun itemIdentifier(id: Int): String = try {
        val protocol = com.viaversion.viaversion.api.Via.getManager().protocolManager
            .getProtocol(com.viaversion.viaversion.protocols.v1_21_2to1_21_4.Protocol1_21_2To1_21_4::class.java)
        protocol?.mappingData?.fullItemMappings?.mappedIdentifier(id) ?: "item#$id"
    } catch (_: Throwable) {
        "item#$id"
    }

    /** 일반 상자 메뉴(9x1~9x6)는 줄 수 x 9 칸, 그 밖에는 전체에서 내 인벤토리 36칸을 뺀 만큼 */
    private fun containerSize(total: Int): Int = when (menuType) {
        in 0..5 -> (menuType + 1) * 9
        else -> (total - 36).coerceAtLeast(0)
    }.coerceAtMost(total)

    private fun currentMenu(): Menu? {
        if (menuId <= 0) return null
        return Menu(menuId, menuTitle, containerSize(menuSlots.size), menuSlots.filterNotNull())
    }

    private fun clearMenu() {
        if (menuId > 0) lastMenuClosedMs = System.currentTimeMillis()
        menuId = -1
        menuSlots = emptyArray()
        events.onMenu(null)
    }

    private fun scheduleInventoryFlush() {
        if (invFlushPending) return
        val ch = channel ?: return
        invFlushPending = true
        ch.eventLoop().schedule({
            invFlushPending = false
            events.onInventory(Inventory(invSlots.filterNotNull(), heldSlot))
        }, 300, TimeUnit.MILLISECONDS)
    }

    private fun scheduleMenuFlush() {
        if (menuFlushPending) return
        val ch = channel ?: return
        menuFlushPending = true
        ch.eventLoop().schedule({
            menuFlushPending = false
            val menu = currentMenu() ?: return@schedule
            events.onMenu(menu)
            if (!menuAutoClicked) {
                for (rule in autoClickRules) {
                    val target = rule.matches(menu) ?: continue
                    menuAutoClicked = true
                    val wid = menu.windowId
                    // 사람이 누르는 것처럼 잠깐 기다렸다 클릭
                    ch.eventLoop().schedule({
                        if (menuId == wid) {
                            events.onInfo("자동 클릭: ${target.slot}번 칸 '${target.name.plain}' (규칙: ${rule.title} > ${rule.item})")
                            clickMenuSlot(target.slot)
                        }
                    }, 800, TimeUnit.MILLISECONDS)
                    break
                }
            }
        }, 150, TimeUnit.MILLISECONDS)
    }

    /** 메뉴 칸 왼쪽 클릭. 아무 스레드에서나 불러도 된다 */
    fun clickMenuSlot(slot: Int): Boolean {
        val ch = channel ?: return false
        ch.eventLoop().execute {
            if (menuId <= 0 || state != State.PLAY) return@execute
            send(PlayOut.CONTAINER_CLICK) {
                it.writeVarInt(menuId)
                it.writeVarInt(menuStateId)
                it.writeShort(slot)
                it.writeByte(0) // 왼쪽 버튼
                it.writeVarInt(0) // 집기(PICKUP)
                it.writeVarInt(0) // 바뀐 칸 없음 (서버가 다시 보내 줌)
                it.writeVarInt(0) // 커서에 든 아이템 없음
            }
        }
        return true
    }

    /**
     * 인벤토리 아이템 우클릭 사용. [slot] 은 인벤토리 창 칸 번호 (36~44 핫바, 9~35 가방, 45 왼손).
     * 핫바면 그 칸을 손에 들고, 가방이면 지금 든 핫바 칸과 바꾼 뒤 사용한다. 아무 스레드에서나 불러도 된다.
     */
    fun useItem(slot: Int): Boolean {
        val ch = channel ?: return false
        if (state != State.PLAY) return false
        ch.eventLoop().execute {
            var hand = 0
            when (slot) {
                in 36..44 -> {
                    val hotbar = slot - 36
                    if (hotbar != heldSlot) {
                        send(PlayOut.SET_CARRIED_ITEM) { it.writeShort(hotbar) }
                        heldSlot = hotbar
                    }
                }
                in 9..35 -> {
                    // 가방 칸과 지금 든 핫바 칸 바꾸기 (숫자 키 누르기와 같음)
                    send(PlayOut.CONTAINER_CLICK) {
                        it.writeVarInt(0)
                        it.writeVarInt(invStateId)
                        it.writeShort(slot)
                        it.writeByte(heldSlot)
                        it.writeVarInt(2) // SWAP
                        it.writeVarInt(0)
                        it.writeVarInt(0)
                    }
                }
                45 -> hand = 1
                else -> return@execute
            }
            ch.eventLoop().schedule({
                if (state != State.PLAY) return@schedule
                send(PlayOut.USE_ITEM) {
                    it.writeVarInt(hand)
                    it.writeVarInt(useSequence++)
                    it.writeFloat(currentYaw)
                    it.writeFloat(0f)
                }
            }, 150, TimeUnit.MILLISECONDS)
        }
        return true
    }

    fun closeMenu() {
        val ch = channel ?: return
        ch.eventLoop().execute {
            if (menuId <= 0) return@execute
            send(PlayOut.CONTAINER_CLOSE) { it.writeVarInt(menuId) }
            clearMenu()
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
        vy = 0.0
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
    /**
     * 20틱(초당 20번) 반복. 이동 버튼을 누르고 있거나 공중에 떠 있으면 간단한 물리로 움직이고,
     * 가만히 있으면 바닐라처럼 1초마다 현재 위치를 다시 보낸다.
     */
    private fun startPositionReminder() {
        val ch = channel ?: return
        positionTask?.cancel(false)
        idleTicks = 0
        positionTask = ch.eventLoop().scheduleAtFixedRate({
            if (state == State.PLAY && hasPosition && ch.isActive) {
                try {
                    tickMovement()
                } catch (t: Throwable) {
                    log.w("이동 처리 오류", t)
                }
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    private var idleTicks = 0
    private var vy = 0.0
    private var onGround = true

    /** 이동 입력 (비트: 1 북, 2 남, 4 서, 8 동, 16 점프). 화면에서 아무 스레드나 불러도 된다. */
    @Volatile var movementInput = 0

    val currentX: Double get() = x
    val currentY: Double get() = y
    val currentZ: Double get() = z
    val currentYaw: Float get() = yaw

    private fun tickMovement() {
        val input = movementInput
        var dx = 0.0
        var dz = 0.0
        if (input and 1 != 0) dz -= 1.0
        if (input and 2 != 0) dz += 1.0
        if (input and 4 != 0) dx -= 1.0
        if (input and 8 != 0) dx += 1.0
        val wantsJump = input and 16 != 0
        val moving = dx != 0.0 || dz != 0.0
        val physics = (moving || wantsJump || !onGround) && Collision.loaded &&
            world.isLoaded(kotlin.math.floor(x).toInt(), kotlin.math.floor(z).toInt())
        if (!physics) {
            if (++idleTicks >= 20) {
                idleTicks = 0
                send(PlayOut.MOVE_PLAYER_POS) {
                    it.writeDouble(x); it.writeDouble(y); it.writeDouble(z)
                    it.writeByte(if (onGround) 0x01 else 0x00)
                }
            }
            return
        }
        idleTicks = 0
        if (moving) {
            val len = kotlin.math.sqrt(dx * dx + dz * dz)
            dx = dx / len * WALK_SPEED
            dz = dz / len * WALK_SPEED
            // 바라보는 방향: 0 = 남(+Z), 90 = 서(-X), 180 = 북(-Z), 270 = 동(+X)
            yaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
            moveAxis(dx, 0.0)
            moveAxis(0.0, dz)
        }
        if (wantsJump && onGround) {
            vy = 0.42
            onGround = false
        }
        if (vy > 0) {
            var up = vy
            if (world.collides(x, y + up, z)) {
                var lo = 0.0
                var hi = up
                repeat(10) { val mid = (lo + hi) / 2; if (world.collides(x, y + mid, z)) hi = mid else lo = mid }
                up = lo
                vy = 0.0
            }
            y += up
            onGround = false
        } else {
            val fall = -vy
            val probe = maxOf(fall, 0.001)
            val d = world.dropDistance(x, y, z, probe)
            if (d < probe - 1e-6) {
                y -= d
                onGround = true
                vy = 0.0
            } else {
                y -= fall
                onGround = false
            }
        }
        if (!onGround) vy = (vy - 0.08) * 0.98
        if (y < world.minY - 64) y = (world.minY - 64).toDouble() // 공허 아래로 끝없이 떨어지지 않게
        send(PlayOut.MOVE_PLAYER_POS_ROT) {
            it.writeDouble(x); it.writeDouble(y); it.writeDouble(z)
            it.writeFloat(yaw); it.writeFloat(pitch)
            it.writeByte(if (onGround) 0x01 else 0x00)
        }
    }

    /** 한 축으로 움직이기. 막히면 0.6칸까지 자동으로 올라선다(계단, 반 블록) */
    private fun moveAxis(mx: Double, mz: Double) {
        val nx = x + mx
        val nz = z + mz
        if (!world.collides(nx, y, nz)) {
            x = nx
            z = nz
            return
        }
        if (onGround && !world.collides(x, y + STEP, z) && !world.collides(nx, y + STEP, nz)) {
            val drop = world.dropDistance(nx, y + STEP, nz, STEP)
            x = nx
            z = nz
            y = y + STEP - drop
        }
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

    private class SessionJoinException(
        message: String,
        val tokenProblem: Boolean,
        val serviceDown: Boolean = false,
        val rateLimited: Boolean = false,
    ) : Exception(message)

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
            if (code == 429) {
                throw SessionJoinException("모장 인증 서버가 잠깐 요청을 막았습니다 (429, 짧은 시간에 접속을 너무 자주 시도함)", false, rateLimited = true)
            }
            // 오류 내용이 웹페이지(HTML)이면 길기만 하고 쓸모없으므로 상태 코드만 보여 준다
            val detail = if (err.trimStart().startsWith("<")) "" else " " + err.take(120)
            throw SessionJoinException("모장 인증 서버 오류 ($code)$detail", false, serviceDown = code >= 500)
        } catch (e: IOException) {
            throw SessionJoinException("모장 인증 서버에 연결하지 못했습니다: ${describe(e)}", false, serviceDown = true)
        } finally {
            conn.disconnect()
        }
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val READ_TIMEOUT_SEC = 30
        const val PLAYER_ENTITY_TYPE = 147 // 1.21.4 엔티티 종류 번호
        const val WALK_SPEED = 0.2158 // 블록/틱 (초당 4.3칸, 바닐라 걷기)
        const val STEP = 0.6

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
 * 버전 변환 중 오류가 난 패킷 하나 때문에 접속이 끊기지 않게, 그 패킷만 버리고 계속한다.
 * (ViaProxy 의 "변환 오류 무시" 옵션과 같은 방식)
 */
internal class SafeViaCodec(user: com.viaversion.viaversion.api.connection.UserConnection, private val log: CoreLog) : ViaCodecHandler(user) {
    private companion object {
        val LOG_ALL = System.getProperty("jamsuham.viaLogAll") == "true"
    }
    private var lastLog = 0L
    private var suppressed = 0

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        try {
            super.channelRead(ctx, msg)
        } catch (t: Throwable) {
            if (t is com.viaversion.viaversion.exception.CancelCodecException) throw t
            val now = System.currentTimeMillis()
            if (now - lastLog > 10_000 || LOG_ALL) {
                log.w("버전 변환 중 패킷 하나를 처리하지 못해 건너뜁니다" + (if (suppressed > 0) " (그 사이 ${suppressed}개 더)" else ""), t)
                lastLog = now
                suppressed = 0
            } else {
                suppressed++
            }
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
    const val ADD_ENTITY = 0x01
    const val CONTAINER_CLOSE = 0x12
    const val CONTAINER_SET_CONTENT = 0x13
    const val CONTAINER_SET_SLOT = 0x15
    const val OPEN_SCREEN = 0x35
    const val BLOCK_UPDATE = 0x09
    const val ENTITY_POSITION_SYNC = 0x20
    const val FORGET_CHUNK = 0x22
    const val LEVEL_CHUNK = 0x28
    const val MOVE_ENTITY_POS = 0x2F
    const val MOVE_ENTITY_POS_ROT = 0x30
    const val REMOVE_ENTITIES = 0x47
    const val SECTION_BLOCKS_UPDATE = 0x4E
    const val TELEPORT_ENTITY = 0x77
    const val CHUNK_BATCH_FINISHED = 0x0C
    const val PLAYER_INFO_REMOVE = 0x3F
    const val PLAYER_INFO_UPDATE = 0x40
    const val TAB_LIST = 0x74
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
    const val SET_HELD_SLOT = 0x63
    const val SET_PLAYER_INVENTORY = 0x66
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
    const val CONTAINER_CLICK = 0x10
    const val SET_CARRIED_ITEM = 0x33
    const val USE_ITEM = 0x3D
    const val CONTAINER_CLOSE = 0x11
    const val COOKIE_RESPONSE = 0x13
    const val KEEP_ALIVE = 0x1A
    const val MOVE_PLAYER_POS = 0x1C
    const val MOVE_PLAYER_POS_ROT = 0x1D
    const val PLAYER_LOADED = 0x2A
    const val PONG = 0x2B
    const val RESOURCE_PACK = 0x2F
}
