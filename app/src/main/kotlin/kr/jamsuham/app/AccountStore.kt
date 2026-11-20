package kr.jamsuham.app

import android.content.Context
import com.google.gson.JsonParser
import kr.jamsuham.core.AuthException
import kr.jamsuham.core.CredentialsProvider
import kr.jamsuham.core.JoinCredentials
import net.lenni0451.commons.httpclient.HttpClient
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.java.JavaAuthManager
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService
import net.raphimc.minecraftauth.msa.service.util.ParamMsaAuthServiceSupplier
import java.io.File
import java.io.IOException
import java.util.function.Consumer

/**
 * 마이크로소프트(정품) 계정 저장/로그인. 토큰은 앱 전용 저장소에만 보관한다.
 * 모든 메서드는 네트워크를 쓰므로 백그라운드 스레드에서 불러야 한다(이름 조회 제외).
 */
class AccountStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val file = File(app.filesDir, "microsoft_account.json")
    private val lock = Any()

    val hasMicrosoft: Boolean get() = file.exists()
    val microsoftName: String? get() = if (hasMicrosoft) prefs.msName else null

    private fun http(): HttpClient = MinecraftAuth.createHttpClient("Jamsuham/1.0 (Android)")

    /** 디바이스 코드 로그인. 사용자가 브라우저에서 코드를 입력할 때까지 막힌다(최대 5분). */
    @Throws(Exception::class)
    fun loginWithDeviceCode(onCode: (MsaDeviceCode) -> Unit): String {
        val supplier = ParamMsaAuthServiceSupplier<Consumer<MsaDeviceCode>> { client, config, consumer ->
            DeviceCodeMsaAuthService(client, config, consumer)
        }
        val manager = JavaAuthManager.create(http()).login(supplier, Consumer { onCode(it) })
        val profile = try {
            manager.minecraftProfile.upToDate
        } catch (e: IOException) {
            throw AuthException("이 계정에서 마인크래프트 자바 에디션 프로필을 찾지 못했습니다 (${e.message})", true, e)
        }
        manager.minecraftToken.upToDate
        synchronized(lock) {
            save(manager)
            prefs.msName = profile.name
            prefs.msUuid = profile.id.toString()
        }
        return profile.name
    }

    fun logout() {
        synchronized(lock) {
            file.delete()
            prefs.msName = null
            prefs.msUuid = null
        }
    }

    /** 접속 직전에 토큰을 확인(필요하면 갱신)해서 돌려준다 */
    @Throws(Exception::class)
    fun microsoftCredentials(forceRefresh: Boolean): JoinCredentials = synchronized(lock) {
        if (!file.exists()) throw AuthException("마이크로소프트 계정으로 로그인해 주세요")
        val manager = try {
            JavaAuthManager.fromJson(http(), JsonParser.parseString(file.readText()).asJsonObject)
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
            save(manager)
            prefs.msName = profile.name
            JoinCredentials(profile.name, profile.id, token.token)
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (looksLikeRevoked(msg)) {
                throw AuthException("마이크로소프트 로그인이 만료됐습니다. 다시 로그인해 주세요", true, e)
            }
            // 네트워크 문제는 세션이 잠시 뒤 다시 시도한다
            throw IOException("마이크로소프트 인증 서버에 연결하지 못했습니다: $msg", e)
        }
    }

    fun credentialsProvider(useMicrosoft: Boolean, offlineName: String): CredentialsProvider =
        if (useMicrosoft) {
            CredentialsProvider { force -> microsoftCredentials(force) }
        } else {
            val name = offlineName.trim()
            CredentialsProvider {
                if (!NAME_REGEX.matches(name)) throw AuthException("닉네임은 영문/숫자/_ 3~16자로 입력해 주세요")
                JoinCredentials.offline(name)
            }
        }

    private fun save(manager: JavaAuthManager) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JavaAuthManager.toJson(manager).toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun looksLikeRevoked(msg: String): Boolean {
        val m = msg.lowercase()
        return listOf("invalid_grant", "expired", "revoked", "unauthorized", "401", "not_found", "notfound").any { m.contains(it) }
    }

    companion object {
        val NAME_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")
    }
}
