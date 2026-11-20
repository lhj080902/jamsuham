package kr.jamsuham.core.via

import com.viaversion.viabackwards.ViaBackwardsPlatformImpl
import com.viaversion.viarewind.ViaRewindPlatformImpl
import com.viaversion.viaversion.ViaManagerImpl
import com.viaversion.viaversion.api.Via
import com.viaversion.viaversion.api.connection.UserConnection
import com.viaversion.viaversion.api.platform.ViaPlatformLoader
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import com.viaversion.viaversion.api.protocol.version.VersionProvider
import com.viaversion.viaversion.api.protocol.version.VersionType
import com.viaversion.viaversion.commands.ViaCommandHandler
import com.viaversion.viaversion.configuration.AbstractViaConfig
import com.viaversion.viaversion.platform.NoopInjector
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform
import com.viaversion.viaversion.protocol.version.BaseVersionProvider
import io.netty.util.AttributeKey
import kr.jamsuham.core.net.Pipe
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ViaVersion(+ViaBackwards, ViaRewind)을 "클라이언트 쪽 번역기"로 초기화한다.
 * 앱은 항상 [NATIVE] 버전으로 말하고, ViaVersion 이 서버 버전에 맞게 패킷을 바꿔 준다.
 */
object ViaSetup {

    /** 앱이 직접 구현한 프로토콜 버전 */
    val NATIVE: ProtocolVersion get() = ProtocolVersion.v1_21_4
    const val NATIVE_PROTOCOL = 769
    const val NATIVE_NAME = "1.21.4"

    /** 접속할 서버 버전. 채널을 만들 때 넣어 둔다. */
    val TARGET_VERSION: AttributeKey<ProtocolVersion> = AttributeKey.valueOf("jamsuham-target-version")

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(dataDir: File) {
        if (initialized) return
        dataDir.mkdirs()
        // 서버용 경고/업데이트 확인 등은 끈다
        Logger.getLogger("ViaVersion").level = Level.WARNING
        ViaManagerImpl.initAndLoad(
            AppViaPlatform(dataDir),
            AppInjector(),
            ViaCommandHandler(false),
            AppPlatformLoader(),
            Runnable { ViaBackwardsPlatformImpl() },
            Runnable { ViaRewindPlatformImpl() },
        )
        // 메뉴 아이템을 1.21.4 형식으로 읽으려면 1.21.4 아이템 데이터가 준비돼 있어야 한다
        try {
            Via.getManager().protocolManager.completeMappingDataLoading(
                com.viaversion.viaversion.protocols.v1_21_2to1_21_4.Protocol1_21_2To1_21_4::class.java,
            )
        } catch (_: Throwable) {
        }
        initialized = true
    }

    val isInitialized: Boolean get() = initialized

    /** 선택 목록에 보여 줄 서버 버전들 (최신 → 1.8). */
    fun selectableVersions(): List<ProtocolVersion> =
        ProtocolVersion.getProtocols()
            .filter { it.versionType == VersionType.RELEASE && it.newerThanOrEqualTo(ProtocolVersion.v1_8) }
            .sortedDescending()

    /** 서버가 알려 준 프로토콜 번호를 ViaVersion 이 아는 버전으로 바꾼다. 모르면 null. */
    fun versionForProtocol(protocol: Int): ProtocolVersion? {
        if (!ProtocolVersion.isRegistered(VersionType.RELEASE, protocol)) return null
        val v = ProtocolVersion.getProtocol(VersionType.RELEASE, protocol)
        return if (isSupported(v)) v else null
    }

    fun isSupported(server: ProtocolVersion): Boolean {
        if (server == NATIVE) return true
        if (!server.isKnown || server.olderThan(ProtocolVersion.v1_8)) return false
        return Via.getManager().protocolManager.getProtocolPath(NATIVE, server) != null
    }

    fun versionByName(name: String): ProtocolVersion? =
        selectableVersions().firstOrNull { it.name == name || it.includedVersions.contains(name) }
}

private class AppViaPlatform(dataDir: File) : UserConnectionViaVersionPlatform(dataDir) {
    override fun createLogger(name: String): Logger = Logger.getLogger(name)
    override fun getPlatformName(): String = "Jamsuham"
    override fun getPlatformVersion(): String = "1.0"
    override fun createConfig(): AbstractViaConfig = AppViaConfig(File(dataFolder, "viaversion.yml"), logger)

    override fun kickPlayer(connection: UserConnection, message: String): Boolean {
        connection.channel?.close()
        return true
    }
}

private class AppViaConfig(file: File, logger: Logger) : AbstractViaConfig(file, logger) {
    override fun isCheckForUpdates(): Boolean = false
}

private class AppInjector : NoopInjector() {
    override fun getEncoderName(): String = Pipe.VIA
    override fun getDecoderName(): String = Pipe.VIA
}

private class AppPlatformLoader : ViaPlatformLoader {
    override fun load() {
        Via.getManager().providers.use(VersionProvider::class.java, AppVersionProvider())
    }

    override fun unload() {}
}

private class AppVersionProvider : BaseVersionProvider() {
    override fun getClosestServerProtocol(connection: UserConnection): ProtocolVersion {
        return connection.channel?.attr(ViaSetup.TARGET_VERSION)?.get() ?: ViaSetup.NATIVE
    }
}
