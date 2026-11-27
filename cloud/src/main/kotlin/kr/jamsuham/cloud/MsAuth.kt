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

    class DeviceLogin(val id: String, val code: MsaDeviceCode, val createdAt: Long) {
        /** pending / done / error */
        @Volatile var state = "pending"
        @Volatile var error: LoginException? = null
        @Volatile var result: Pair<JavaAuthManager, Profile>? = null
        /** 결과를 한 번 가져갔는지 (토큰을 두 번 내주지 않는다) */
        @Volatile var claimed = false
    }

    private val devices = ConcurrentHashMap<String, DeviceLogin>()

    @Throws(LoginException::class)
    fun startDevice(): DeviceLogin {
        val cfg = msaConfig ?: throw LoginException("not_configured", "이 서버에는 마이크로소프트 로그인이 설정되어 있지 않습니다 (cloud.properties 의 msClientId)")
        // 끝났거나 오래된 로그인은 치운다
        val now = System.currentTimeMillis()
        devices.values.removeIf { now - it.createdAt > 20 * 60_000 }
        if (devices.size > 200) throw LoginException("busy", "로그인 요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요")
        val client = http()
        val service = DeviceCodeMsaAuthService(client, cfg, { _ -> }, 15 * 60_000)
        val code = try {
            service.requestDeviceCode()
        } catch (e: IOException) {
            throw LoginException("ms_unreachable", "마이크로소프트 로그인 서버에 연결하지 못했습니다: ${e.message}")
        }
        val login = DeviceLogin(randomToken(), code, now)
        devices[login.id] = login
        Thread({
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
        }, "device-login").apply { isDaemon = true }.start()
        return login
    }

    fun device(id: String): DeviceLogin? = devices[id]

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
        // 로그인 설정(주소·범위)은 넘겨받은 값이 아니라 이 서버의 설정으로 바꿔 끼운다
        val own = try {
            auth.add("msaApplicationConfig", MsaApplicationConfig.toJson(cfg))
            JavaAuthManager.fromJson(http(), auth)
        } catch (t: Throwable) {
            throw LoginException("bad_auth", "로그인 정보를 읽지 못했습니다. 앱에서 계정에 다시 로그인한 뒤 연결해 주세요")
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
                // 갱신된 토큰을 저장 (다른 스레드가 그 사이 계정을 지웠으면 되살리지 않는다)
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
