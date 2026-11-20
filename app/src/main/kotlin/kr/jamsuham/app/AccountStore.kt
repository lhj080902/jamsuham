package kr.jamsuham.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kr.jamsuham.core.AuthException
import kr.jamsuham.core.CredentialsProvider
import kr.jamsuham.core.JoinCredentials
import net.lenni0451.commons.httpclient.HttpClient
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.java.JavaAuthManager
import net.raphimc.minecraftauth.msa.data.MsaConstants
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig
import net.raphimc.minecraftauth.msa.request.MsaAuthCodeTokenRequest
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** 계정 하나. microsoft = 정품, 아니면 비정품 닉네임 */
data class Account(
    val id: String = "",
    val name: String = "",
    val microsoft: Boolean = false,
    val uuid: String? = null,
) {
    val label: String get() = if (microsoft) "$name (정품)" else "$name (비정품)"
}

/**
 * 계정 목록. 마이크로소프트 토큰은 앱 전용 저장소(files/accounts/)에만 보관한다.
 * 네트워크를 쓰는 메서드는 백그라운드 스레드에서 불러야 한다.
 */
class AccountStore(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("jamsuham_accounts", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "accounts").apply { mkdirs() }
    private val gson = Gson()

    // 서버 목록 화면이 1초마다 서버마다 계정을 찾으므로, 저장된 글자가 그대로면 지난번 해석 결과를 다시 쓴다
    @Volatile private var cachedJson: String? = null
    @Volatile private var cachedList: List<Account> = emptyList()

    fun list(): List<Account> {
        migrateOld()
        val json = sp.getString("accounts", null) ?: return emptyList()
        if (json == cachedJson) return cachedList
        val list = try {
            gson.fromJson<List<Account>>(json, object : TypeToken<List<Account>>() {}.type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        cachedList = list
        cachedJson = json
        return list
    }

    fun get(id: String?): Account? = if (id == null) null else list().firstOrNull { it.id == id }

    private fun write(list: List<Account>) {
        sp.edit().putString("accounts", gson.toJson(list)).apply()
    }

    fun addOffline(name: String): Account {
        val n = name.trim()
        if (!NAME_REGEX.matches(n)) throw IllegalArgumentException("닉네임은 영문/숫자/_ 3~16자로 입력해 주세요")
        val existing = list().firstOrNull { !it.microsoft && it.name.equals(n, true) }
        if (existing != null) return existing
        val acc = Account(id = "offline:$n", name = n, microsoft = false)
        write(list() + acc)
        return acc
    }

    fun remove(id: String) {
        synchronized(LOCK) {
            write(list().filter { it.id != id })
            file(id).delete()
        }
    }

    private fun file(id: String) = File(dir, id.replace(':', '_').replace('/', '_') + ".json")

    // ------------------------------------------------------------ 마이크로소프트 로그인 (앱 안 로그인 화면)

    private fun http(): HttpClient = MinecraftAuth.createHttpClient("Jamsuham/1.0 (Android)")

    private val msaConfig = MsaApplicationConfig(MsaConstants.JAVA_TITLE_ID, MsaConstants.SCOPE_TITLE_AUTH)

    /** 로그인 화면에 띄울 주소 */
    fun loginUrl(): String {
        val params = msaConfig.authCodeParameters.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        return msaConfig.environment.authorizeUrl + "?" + params
    }

    /** 로그인이 끝나면 이 주소로 돌아온다 (뒤에 ?code=... 가 붙음) */
    fun redirectPrefix(): String = msaConfig.redirectUri ?: msaConfig.environment.nativeClientUrl

    /** 로그인 화면에서 받은 코드로 토큰을 받아 계정을 저장한다 */
    @Throws(Exception::class)
    fun finishMicrosoftLogin(authCode: String): Account {
        val client = http()
        val msaToken = client.executeAndHandle(MsaAuthCodeTokenRequest(msaConfig, authCode))
        val manager = JavaAuthManager.create(client).login(msaToken)
        val profile = try {
            manager.minecraftProfile.upToDate
        } catch (e: IOException) {
            throw AuthException("이 마이크로소프트 계정에서 마인크래프트 자바 에디션을 찾지 못했습니다 (${e.message})", true, e)
        }
        manager.minecraftToken.upToDate
        val acc = Account(id = "ms:${profile.id}", name = profile.name, microsoft = true, uuid = profile.id.toString())
        synchronized(LOCK) {
            save(acc.id, manager)
            write(list().filter { it.id != acc.id } + acc)
        }
        return acc
    }

    /** 접속 직전에 토큰을 확인(필요하면 갱신)해서 돌려준다 */
    @Throws(Exception::class)
    fun credentials(accountId: String, forceRefresh: Boolean): JoinCredentials {
        val acc = get(accountId) ?: throw AuthException("계정을 찾을 수 없습니다. 서버 설정에서 계정을 다시 골라 주세요")
        if (!acc.microsoft) return JoinCredentials.offline(acc.name)
        synchronized(LOCK) {
            val f = file(acc.id)
            if (!f.exists()) throw AuthException("${acc.name} 계정에 다시 로그인해 주세요")
            val manager = try {
                val text = TokenCrypto.open(f.readText()) { tokenKey() ?: throw IllegalStateException("암호화 키를 찾을 수 없습니다") }
                JavaAuthManager.fromJson(http(), JsonParser.parseString(text).asJsonObject)
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
                save(acc.id, manager)
                if (profile.name != acc.name) write(list().map { if (it.id == acc.id) it.copy(name = profile.name) else it })
                return JoinCredentials(profile.name, profile.id, token.token)
            } catch (e: IOException) {
                val msg = e.message ?: ""
                if (looksRevoked(msg)) throw AuthException("${acc.name} 계정의 로그인이 만료됐습니다. 계정 관리에서 다시 로그인해 주세요", true, e)
                throw IOException("마이크로소프트 인증 서버에 연결하지 못했습니다: $msg", e)
            }
        }
    }

    fun provider(accountId: String): CredentialsProvider = CredentialsProvider { force -> credentials(accountId, force) }

    private fun save(id: String, manager: JavaAuthManager) {
        val f = file(id)
        val tmp = File(dir, f.name + ".tmp")
        val json = JavaAuthManager.toJson(manager).toString()
        // Keystore 키로 암호화해서 저장. 일부 기기에서 Keystore 가 고장 나 있으면 로그인이 풀리지 않도록 예전처럼 평문으로 저장
        val stored = try {
            tokenKey()?.let { TokenCrypto.seal(json, it) } ?: json
        } catch (t: Throwable) {
            SessionHub.log.w("토큰 암호화 실패, 평문으로 저장합니다", t)
            json
        }
        tmp.writeText(stored)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun looksRevoked(msg: String): Boolean {
        val m = msg.lowercase()
        return listOf("invalid_grant", "expired", "revoked", "unauthorized", "not_found", "notfound").any { m.contains(it) }
    }

    /** 1.0 버전에서 쓰던 단일 계정을 목록으로 옮긴다 */
    private fun migrateOld() {
        if (sp.getBoolean("migrated", false)) return
        sp.edit().putBoolean("migrated", true).apply()
        val old = File(app.filesDir, "microsoft_account.json")
        val oldPrefs = app.getSharedPreferences("jamsuham", Context.MODE_PRIVATE)
        val result = ArrayList<Account>()
        val name = oldPrefs.getString("ms_name", null)
        val uuid = oldPrefs.getString("ms_uuid", null)
        if (old.exists() && name != null && uuid != null) {
            val acc = Account(id = "ms:$uuid", name = name, microsoft = true, uuid = uuid)
            old.copyTo(file(acc.id), overwrite = true)
            old.delete()
            result.add(acc)
        }
        val offline = oldPrefs.getString("offline_name", "") ?: ""
        if (NAME_REGEX.matches(offline)) result.add(Account(id = "offline:$offline", name = offline))
        if (result.isNotEmpty()) write(result)
    }

    companion object {
        val NAME_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")
        private val LOCK = Any()
        private const val KEY_ALIAS = "jamsuham_tokens"

        /**
         * 토큰 파일을 암호화하는 AES 키. 안드로이드 Keystore 안에 만들어져 앱 밖으로 꺼낼 수 없다.
         * Keystore 를 쓸 수 없는 기기면 null (그때는 평문으로 저장).
         */
        @Synchronized
        private fun tokenKey(): javax.crypto.SecretKey? = try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey) ?: run {
                val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                gen.generateKey()
            }
        } catch (t: Throwable) {
            SessionHub.log.w("Keystore 키를 준비하지 못했습니다", t)
            null
        }
    }
}
