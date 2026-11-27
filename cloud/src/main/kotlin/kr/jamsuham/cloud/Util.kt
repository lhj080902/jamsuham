package kr.jamsuham.cloud

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Log {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss")

    /**
     * 한글이 깨지지 않게: 콘솔 창에 찍을 때는 그 창의 글자 형식(윈도우 명령 프롬프트는 CP949), 파일로 넘길 때는 UTF-8.
     */
    private val out: java.io.PrintStream = run {
        val charset = try {
            System.console()?.charset()
        } catch (_: Throwable) {
            null
        } ?: Charsets.UTF_8
        java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, charset)
    }

    @Synchronized
    fun i(msg: String) = out.println("${fmt.format(Date())} $msg")

    @Synchronized
    fun w(msg: String, t: Throwable? = null) {
        out.println("${fmt.format(Date())} [경고] $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    /** 시각 없이 그대로 (명령줄 도구의 안내) */
    @Synchronized
    fun say(msg: String) = out.println(msg)
}

/** 파일을 통째로 바꿔 쓴다 (쓰다가 꺼져도 예전 파일이 남도록 임시 파일에 쓴 뒤 바꿔치기) */
fun File.writeAtomic(text: String) {
    absoluteFile.parentFile?.mkdirs()
    val tmp = File(absoluteFile.parentFile, "$name.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private val random = SecureRandom()

/** 추측할 수 없는 접속 토큰 (256비트) */
fun randomToken(): String = ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

fun randomId(): String = ByteArray(12).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

/**
 * 마이크로소프트 로그인 토큰을 디스크에 암호화해서 두는 금고 (AES-256-GCM).
 * 키는 data/master.key 에 있다 → 계정 파일만 새어 나가서는 토큰을 읽을 수 없다. 이 키 파일은 백업·공유하지 말 것.
 */
class Vault(dir: File) {
    private val key: SecretKeySpec

    init {
        dir.mkdirs()
        // 데이터 폴더 전체를 이 계정만 들여다볼 수 있게 (리눅스·맥. 서버 목록의 자동 명령어에 비밀번호가 들어 있을 수 있다)
        try {
            Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        } catch (_: Exception) {
        }
        val f = File(dir, "master.key")
        val bytes = if (f.exists()) {
            Base64.getDecoder().decode(f.readText().trim())
        } else {
            ByteArray(32).also { random.nextBytes(it) }.also { b ->
                // 리눅스·맥: 만들 때부터 이 계정만 읽을 수 있게 (윈도우는 이 방식을 지원하지 않아 보통 파일로 만든다)
                try {
                    Files.createFile(f.toPath(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
                } catch (_: Exception) {
                }
                f.writeText(Base64.getEncoder().encodeToString(b))
            }
        }
        require(bytes.size == 32) { "master.key 가 올바르지 않습니다" }
        key = SecretKeySpec(bytes, "AES")
    }

    fun seal(text: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return "v1:" + Base64.getEncoder().encodeToString(iv + c.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun open(sealed: String): String {
        require(sealed.startsWith("v1:")) { "알 수 없는 암호화 형식" }
        val raw = Base64.getDecoder().decode(sealed.substring(3))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject {
    val o = JsonObject()
    for ((k, v) in pairs) {
        when (v) {
            null -> {}
            is String -> o.addProperty(k, v)
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            is com.google.gson.JsonElement -> o.add(k, v)
            else -> o.addProperty(k, v.toString())
        }
    }
    return o
}

/** 버전 (빌드할 때 gradle.properties 의 jamsuham.version 을 넣는다) */
val CLOUD_VERSION: String by lazy {
    try {
        Config::class.java.getResourceAsStream("/jamsu-cloud-version.txt")?.use { String(it.readBytes()).trim() } ?: "dev"
    } catch (_: Exception) {
        "dev"
    }
}
