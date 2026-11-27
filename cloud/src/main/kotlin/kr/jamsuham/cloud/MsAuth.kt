package kr.jamsuham.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.jamsuham.core.AuthException
import kr.jamsuham.core.JoinCredentials
import net.lenni0451.commons.httpclient.HttpClient
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.java.JavaAuthManager
import net.raphimc.minecraftauth.msa.data.MsaEnvironment
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/** 로그인 실패를 사용자에게 보여 줄 코드·문구로 */
class LoginException(val code: String, message: String) : Exception(message)

/**
 * 마이크로소프트 로그인. 두 가지 길이 있다.
 *  - 웹: 기기 코드 방식 (microsoft.com/link 에 코드를 입력) — 서버가 직접 토큰을 받는다
 *  - 앱: 앱이 이미 가진 로그인 정보를 넘겨받는다 (사용자가 앱에서 "클라우드에 연결"을 눌렀을 때만)
 * 어느 쪽이든 서버가 모장에 직접 물어 본 프로필(UUID·닉네임)만 믿는다.
 */
class MsAuth(private val config: Config, private val store: DataStore, private val vault: Vault) {

    /** 앱과 같은 설정 (자체 client ID, 개인 계정 전용 엔드포인트). client ID 가 없으면 null */
    val msaConfig: MsaApplicationConfig? = config.msClientId
        .takeIf { it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) }
        ?.let {
            MsaApplicationConfig(
                it, "XboxLive.signin offline_access", null,
                "https://login.microsoftonline.com/consumers/oauth2/nativeclient",
                MsaEnvironment.MICROSOFT_ONLINE_CONSUMERS,
            )
        }

    private fun http(): HttpClient = MinecraftAuth.createHttpClient("JamsucraftCloud/$CLOUD_VERSION")

    data class Profile(val uuid: String, val name: String)

    // ------------------------------------------------------------ 웹: 기기 코드 로그인

    class DeviceLogin(
        val id: String,
        val code: MsaDeviceCode,
        val createdAt: Long,
        /** 로그인을 시작한 브라우저에만 준 값의 해시 (다른 곳에서 이 로그인을 가로채 받지 못하게) */
        val nonceHash: String,
        /** 시작한 쪽 (요청 횟수 제한용 묶음) */
        val client: String,
    ) {
        /** 화면이 마지막으로 결과를 물어본 시각 (화면을 닫으면 더 묻지 않으므로 그때 치운다) */
        @Volatile var polledAt = createdAt
        @Volatile var thread: Thread? = null
        /** pending / done / error */
        @Volatile var state = "pending"
        @Volatile var error: LoginException? = null
        @Volatile var result: Pair<JavaAuthManager, Profile>? = null
        /** 결과를 한 번 가져갔는지 (토큰을 두 번 내주지 않는다) */
        @Volatile var claimed = false
    }

    private val devices = ConcurrentHashMap<String, DeviceLogin>()

    /**
     * 화면이 닫혔거나(45초 동안 결과를 묻지 않음) 너무 오래된 로그인 요청을 치운다. 몇 초마다 부른다.
     * 치우지 않으면 요청만 잔뜩 만들어 로그인 자리를 다 채워 버릴 수 있다.
     */
    fun sweepDevices() {
        val now = System.currentTimeMillis()
        for (d in devices.values) {
            if (now - d.polledAt > 45_000 || now - d.createdAt > 16 * 60_000) {
                devices.remove(d.id)
                d.result = null
                d.thread?.interrupt()
            }
        }
    }

    /** [nonce] 시작한 브라우저에만 주는 값, [who] 시작한 쪽을 묶는 이름(IP 묶음) */
    @Throws(LoginException::class)
    fun startDevice(nonce: String, who: String): DeviceLogin {
        val cfg = msaConfig ?: throw LoginException("not_configured", "이 서버에는 마이크로소프트 로그인이 설정되어 있지 않습니다 (cloud.properties 의 msClientId)")
        sweepDevices()
        val now = System.currentTimeMillis()
        if (devices.values.count { it.client == who } >= 3) throw LoginException("busy", "진행 중인 로그인이 이미 있습니다. 잠시 뒤 다시 시도해 주세요")
        if (devices.size >= 200) throw LoginException("busy", "로그인 요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요")
        val client = http()
        val service = DeviceCodeMsaAuthService(client, cfg, { _ -> }, 15 * 60_000)
        val code = try {
            service.requestDeviceCode()
        } catch (e: IOException) {
            throw LoginException("ms_unreachable", "마이크로소프트 로그인 서버에 연결하지 못했습니다: ${e.message}")
        }
        val login = DeviceLogin(randomToken(), code, now, sha256Hex(nonce), who)
        devices[login.id] = login
        login.thread = Thread({
            try {
                val msa = service.getToken(code)
                login.result = finish(JavaAuthManager.create(client).msaApplicationConfig(cfg).login(msa))
                login.state = "done"
            } catch (e: LoginException) {
                login.error = e
                login.state = "error"
            } catch (_: TimeoutException) {
                login.error = LoginException("expired", "로그인 시간이 지났습니다. 처음부터 다시 해 주세요")
                login.state = "error"
            } catch (t: Throwable) {
                login.error = LoginException("ms_error", "마이크로소프트 로그인에 실패했습니다: ${t.message}")
                login.state = "error"
            }
        }, "device-login").apply { isDaemon = true; start() }
        return login
    }

    /** 결과를 물어볼 때: 시작한 브라우저가 맞는지([nonce]) 확인하고 돌려준다 */
    fun device(id: String, nonce: String?): DeviceLogin? {
        val d = devices[id] ?: return null
        if (nonce == null || sha256Hex(nonce) != d.nonceHash) return null
        d.polledAt = System.currentTimeMillis()
        return d
    }

    fun forgetDevice(id: String) {
        devices.remove(id)
    }

    // ------------------------------------------------------------ 앱: 로그인 정보 넘겨받기

    @Throws(LoginException::class)
    fun fromTransfer(auth: JsonObject): Pair<JavaAuthManager, Profile> {
        val cfg = msaConfig ?: throw LoginException("not_configured", "이 서버에는 마이크로소프트 로그인이 설정되어 있지 않습니다")
        val manager = try {
            JavaAuthManager.fromJson(http(), auth)
        } catch (t: Throwable) {
            throw LoginException("bad_auth", "로그인 정보를 읽지 못했습니다. 앱에서 계정에 다시 로그인한 뒤 연결해 주세요")
        }
        // 공식 런처 ID 로 로그인한 예전 계정은 받지 않는다 (서버는 승인받은 자체 ID 만 쓴다)
        if (!manager.msaApplicationConfig.clientId.equals(cfg.clientId, ignoreCase = true)) {
            throw LoginException("client_mismatch", "예전 방식으로 로그인한 계정입니다. 앱의 계정 관리에서 이 계정을 지우고 다시 로그인한 뒤 연결해 주세요")
        }
        // 넘겨받은 것 중 마이크로소프트 토큰(갱신 토큰)만 쓰고, 나머지는 이 서버의 설정으로 처음부터 다시 받는다.
        // 중간 단계의 토큰들을 그대로 믿으면 "A 계정의 중간 토큰 + B 계정의 갱신 토큰"처럼 섞어 넣을 수 있다.
        val msa = manager.msaToken.cached ?: throw LoginException("bad_auth", "로그인 정보가 비어 있습니다. 앱에서 계정에 다시 로그인한 뒤 연결해 주세요")
        val own = JavaAuthManager.create(http()).msaApplicationConfig(cfg).login(msa)
        try {
            // 갱신 토큰이 실제로 통하는지, 그 토큰의 주인이 누구인지부터 확인한다
            own.msaToken.refresh()
        } catch (e: IOException) {
            throw LoginException("bad_auth", "로그인이 만료됐습니다. 앱의 계정 관리에서 다시 로그인한 뒤 연결해 주세요")
        }
        return finish(own)
    }

    /** 토큰을 새로 받아 보고(위조·만료 확인) 모장이 알려 준 프로필을 돌려준다 */
    @Throws(LoginException::class)
    private fun finish(manager: JavaAuthManager): Pair<JavaAuthManager, Profile> {
        try {
            manager.minecraftToken.refresh()
            val profile = manager.minecraftProfile.refresh()
            return manager to Profile(profile.id.toString(), profile.name)
        } catch (e: IOException) {
            val m = (e.message ?: "").lowercase()
            if (m.contains("not_found") || m.contains("notfound") || m.contains("404")) {
                throw LoginException("no_minecraft", "이 마이크로소프트 계정에서 마인크래프트 자바 에디션을 찾지 못했습니다")
            }
            throw LoginException("ms_error", "마인크래프트 계정을 확인하지 못했습니다: ${e.message}")
        }
    }

    /** 로그인 정보를 암호화해 저장한다 */
    fun saveLogin(manager: JavaAuthManager, profile: Profile): AccountRec {
        val now = System.currentTimeMillis()
        val old = store.account(profile.uuid)
        val rec = AccountRec(profile.uuid, profile.name, old?.createdAt ?: now, now, vault.seal(JavaAuthManager.toJson(manager).toString()))
        store.saveAccount(rec)
        return rec
    }

    /** 시험 전용 (-Djamsu.devLogin=true): 닉네임만으로 계정을 만든다. 비정품 시험 서버에만 들어갈 수 있다 */
    fun devLogin(name: String): AccountRec {
        require(config.devLogin)
        require(name.matches(Regex("^[A-Za-z0-9_]{3,16}$"))) { "닉네임은 영문/숫자/_ 3~16자" }
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()
        val now = System.currentTimeMillis()
        val rec = AccountRec(uuid, name, store.account(uuid)?.createdAt ?: now, now, null, dev = true)
        store.saveAccount(rec)
        return rec
    }

    // ------------------------------------------------------------ 접속할 때마다: 토큰 확인·갱신

    private val locks = ConcurrentHashMap<String, Any>()

    /** 계정과 그 계정의 모든 것을 지운다. 토큰 갱신과 같은 잠금을 써서, 갱신 중이던 스레드가 지운 계정을 다시 저장하지 못한다 */
    fun deleteAccount(uuid: String) {
        synchronized(locks.getOrPut(uuid) { Any() }) { store.deleteAccount(uuid) }
        locks.remove(uuid)
    }

    /** 세션이 접속 직전에 부른다 (세션 스레드). 필요하면 토큰을 갱신하고, 바뀐 토큰을 다시 암호화해 저장한다 */
    @Throws(Exception::class)
    fun credentials(uuid: String, forceRefresh: Boolean): JoinCredentials {
        val acc = store.account(uuid) ?: throw AuthException("클라우드에 연결된 계정을 찾을 수 없습니다. 다시 연결해 주세요")
        if (acc.dev) {
            if (!config.devLogin) throw AuthException("시험용 계정은 쓸 수 없습니다")
            return JoinCredentials.offline(acc.name)
        }
        val sealed = acc.auth ?: throw AuthException("${acc.name} 계정에 다시 로그인해 주세요")
        synchronized(locks.getOrPut(uuid) { Any() }) {
            val manager = try {
                JavaAuthManager.fromJson(http(), JsonParser.parseString(vault.open(sealed)).asJsonObject)
            } catch (t: Throwable) {
                throw AuthException("저장된 로그인 정보를 읽지 못했습니다. 다시 로그인해 주세요", true, t)
            }
            try {
                val token = if (forceRefresh) manager.minecraftToken.refresh() else {
                    manager.minecraftToken.refreshIfExpired()
                    manager.minecraftToken.upToDate
                }
                manager.minecraftProfile.refreshIfExpired()
                val profile = manager.minecraftProfile.upToDate
                // 저장된 로그인 정보가 이 계정의 것이 아니면 쓰지 않는다 (다른 계정으로 접속하게 되는 것을 막음)
                if (!profile.id.toString().equals(uuid, ignoreCase = true)) {
                    throw AuthException("저장된 로그인 정보가 이 계정과 맞지 않습니다. 다시 로그인(연결)해 주세요")
                }
                // 갱신된 토큰을 저장 (이 잠금 안에서만 지우고 저장하므로, 그 사이 지워진 계정을 되살리지 않는다)
                store.account(uuid)?.let { cur ->
                    store.saveAccount(cur.copy(name = profile.name, auth = vault.seal(JavaAuthManager.toJson(manager).toString())))
                }
                return JoinCredentials(profile.name, profile.id, token.token)
            } catch (e: IOException) {
                val msg = e.message ?: ""
                val m = msg.lowercase()
                if (listOf("invalid_grant", "expired", "revoked", "unauthorized", "not_found", "notfound").any { m.contains(it) }) {
                    throw AuthException("${acc.name} 계정의 로그인이 만료됐습니다. 다시 로그인(연결)해 주세요", true, e)
                }
                throw IOException("마이크로소프트 인증 서버에 연결하지 못했습니다: $msg", e)
            }
        }
    }
}
