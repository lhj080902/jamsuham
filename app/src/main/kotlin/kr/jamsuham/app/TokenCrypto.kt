package kr.jamsuham.app

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 로그인 토큰 파일 암호화 형식 (안드로이드 의존 없음 → PC 에서 그대로 테스트할 수 있다).
 *
 * 형식: "JSE1:" + Base64(IV 12바이트 + AES-GCM 암호문·인증 태그)
 * 예전 버전이 쓴 평문 JSON("{" 로 시작)도 그대로 읽는다.
 */
object TokenCrypto {
    private const val PREFIX = "JSE1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun seal(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        // IV 는 직접 만들지 않고 암호화기가 고르게 한다 (안드로이드 Keystore 는 호출자가 IV 를 정하는 것을 기본으로 막는다)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv != null && iv.size == 12) { "예상과 다른 IV 길이: ${iv?.size}" }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + body)
    }

    /** 평문(예전 형식)이면 그대로 돌려준다. 키가 다르거나 내용이 손상됐으면 예외 */
    fun open(stored: String, key: () -> SecretKey): String {
        if (!isEncrypted(stored)) return stored
        val raw = Base64.getDecoder().decode(stored.substring(PREFIX.length))
        require(raw.size > 12 + TAG_BITS / 8) { "암호문이 너무 짧습니다" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
