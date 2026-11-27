package kr.jamsuham.cloud.link

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 본 서버 ↔ 접속용 서버 연결의 암호화. 두 서버가 같은 비밀키(32바이트, data/worker.key)를 미리 나눠 갖는다.
 *  - 서로 인증: 양쪽이 낸 난수(nonce)와 비밀키로 만든 HMAC 을 주고받아, 상대가 비밀키를 아는지 확인한다
 *  - 연결마다 새 키: HKDF(비밀키, 두 난수) → 방향별 AES-256-GCM 키
 *  - 프레임마다 번호(카운터)가 nonce 에 들어가고 그 번호는 보내지 않는다 → 재전송·순서 바꾸기·빠뜨리기는 모두 풀기 실패
 * 모두 자바 표준 알고리즘만 쓴다 (인증서를 만들거나 관리할 필요가 없다).
 */
object LinkCrypto {
    /** 본 서버·접속용 서버가 주고받는 형식 번호. 호환되지 않게 바꾸면 올린다 */
    const val LINK_VERSION = 1
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 32

    private val random = SecureRandom()

    fun randomNonce(): ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** HKDF (RFC 5869, HMAC-SHA256) */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    /** 접속용 서버가 "나는 비밀키를 안다"를 보이는 값 */
    fun workerMac(psk: ByteArray, nonceW: ByteArray, nonceM: ByteArray, name: String): ByteArray =
        hmac(psk, "jamsu-link-w".toByteArray(Charsets.UTF_8), nonceW, nonceM, name.toByteArray(Charsets.UTF_8))

    /** 본 서버가 "나도 비밀키를 안다"를 보이는 값 */
    fun masterMac(psk: ByteArray, nonceW: ByteArray, nonceM: ByteArray): ByteArray =
        hmac(psk, "jamsu-link-m".toByteArray(Charsets.UTF_8), nonceM, nonceW)

    /** 걸리는 시간이 내용에 따라 달라지지 않는 비교 */
    fun macEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun parseKey(base64: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(base64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("비밀키 형식이 올바르지 않습니다 (본 서버의 data/worker.key 내용을 그대로 적어 주세요)")
        }
        require(bytes.size == KEY_BYTES) { "비밀키 길이가 올바르지 않습니다 (본 서버의 data/worker.key 내용을 그대로 적어 주세요)" }
        return bytes
    }

    /** 비밀키 파일을 읽는다. 없으면 새로 만든다 */
    fun loadOrCreateKey(file: File): ByteArray {
        if (file.exists()) return parseKey(file.readText())
        val bytes = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        file.absoluteFile.parentFile?.mkdirs()
        // 리눅스·맥: 만들 때부터 이 계정만 읽을 수 있게 (윈도우는 이 방식을 지원하지 않아 보통 파일로 만든다)
        try {
            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: Exception) {
        }
        file.writeText(Base64.getEncoder().encodeToString(bytes))
        return bytes
    }
}

/**
 * 인증이 끝난 연결 하나의 암호화기. 양쪽이 같은 비밀키·같은 난수로 만들고, [isWorker] 만 다르다.
 * 보내는 쪽과 받는 쪽이 각자 프레임 번호를 세므로, 한 번이라도 어긋나면 그 뒤로는 모두 풀리지 않는다 (그때는 연결을 끊는다).
 */
class FrameCipher(psk: ByteArray, nonceW: ByteArray, nonceM: ByteArray, isWorker: Boolean) {
    private val sendKey: SecretKeySpec
    private val recvKey: SecretKeySpec
    private var sent = 0L
    private var received = 0L

    init {
        val salt = nonceW + nonceM
        val w2m = SecretKeySpec(LinkCrypto.hkdf(psk, salt, "jamsu-link w2m".toByteArray(Charsets.UTF_8), 32), "AES")
        val m2w = SecretKeySpec(LinkCrypto.hkdf(psk, salt, "jamsu-link m2w".toByteArray(Charsets.UTF_8), 32), "AES")
        sendKey = if (isWorker) w2m else m2w
        recvKey = if (isWorker) m2w else w2m
    }

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    @Synchronized
    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sent++)))
        return c.doFinal(plain)
    }

    /** 풀지 못하면(변조·재전송·순서 어긋남) GeneralSecurityException */
    @Synchronized
    fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(received++)))
        return c.doFinal(sealed)
    }
}
