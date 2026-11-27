import kr.jamsuham.cloud.LaunchHooks
import kr.jamsuham.cloud.LaunchRequest
import kr.jamsuham.cloud.ReplaceBudget
import kr.jamsuham.cloud.RunHandle
import kr.jamsuham.cloud.Runner
import kr.jamsuham.cloud.Scheduler
import kr.jamsuham.cloud.link.FrameCipher
import kr.jamsuham.cloud.link.LinkCrypto
import kr.jamsuham.core.SessionListener
import java.io.File

// 클라우드 멀티 노드(본 서버 + 접속용 서버) 시험. 계획: docs/superpowers/plans/2026-11-27-cloud-multinode.md

/** 시험 결과를 모아 기존 시험과 같은 모양으로 찍는다 */
class Checks(private val mode: String) {
    private val results = ArrayList<Pair<String, Boolean>>()

    fun check(name: String, pass: Boolean) {
        results += name to pass
        println((if (pass) "  통과  " else "  실패  ") + name)
    }

    fun done(): Boolean {
        val failed = results.filter { !it.second }
        println("RESULT $mode ${results.size - failed.size}/${results.size}" + if (failed.isEmpty()) "" else " 실패: ${failed.map { it.first }}")
        return failed.isEmpty()
    }
}

/** 배정 규칙(Scheduler)과 다시 배정 횟수 제한(ReplaceBudget) — 서버 없이 확인 */
fun schedTest(): Boolean {
    val c = Checks("sched")
    fun fake(id: String, remote: Boolean, capacity: Int) = object : Runner {
        override val id = id
        override val remote = remote
        override val capacity = capacity
        override fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle = error("시험에서는 부르지 않는다")
    }

    val s = Scheduler()
    c.check("빈 목록 → null", s.pick("a") == null)
    c.check("추가", s.add(fake("local", remote = false, capacity = 2)) && s.add(fake("r1", remote = true, capacity = 2)))
    c.check("같은 이름은 거절", !s.add(fake("r1", true, 5)))
    c.check("처음: 동률이면 내장 우선", s.pick("a")?.id == "local")
    s.placed("local", "a")
    c.check("같은 서버는 덜 들어간 노드로", s.pick("a")?.id == "r1")
    c.check("다른 서버는 남은 자리 비율이 큰 노드로", s.pick("b")?.id == "r1")
    s.placed("local", "b")
    c.check("꽉 찬 노드는 건너뜀", s.load("local") == 2 && s.pick("c")?.id == "r1")
    s.placed("r1", "a"); s.placed("r1", "c")
    c.check("모두 차면 null", s.pick("d") == null)
    s.released("local", "a")
    c.check("자리 나면 다시", s.pick("d")?.id == "local" && s.onTarget("local", "a") == 0)
    c.check("없는 것을 풀어도 음수 안 됨", run { s.released("local", "zzz"); s.load("local") == 1 })
    c.check("노드 제거", s.remove("r1")?.id == "r1" && s.runners().map { it.id } == listOf("local"))

    val b = ReplaceBudget(3, 600_000L)
    c.check("다시 배정 3번까지", b.tryUse(0) && b.tryUse(1) && b.tryUse(2) && !b.tryUse(3))
    c.check("10분 지나면 다시 됨", b.tryUse(600_001L))
    return c.done()
}

/** 본 서버 ↔ 접속용 서버 연결의 암호화(LinkCrypto, FrameCipher) — 서버 없이 확인 */
fun linkCryptoTest(): Boolean {
    val c = Checks("linkcrypto")
    fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun fails(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }

    // RFC 5869 Test Case 1
    val okm = LinkCrypto.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
    c.check("HKDF RFC 5869 벡터", okm.contentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")))

    val psk = ByteArray(32) { it.toByte() }
    val nw = ByteArray(32) { 1 }
    val nm = ByteArray(32) { 2 }
    val w = FrameCipher(psk, nw, nm, isWorker = true)
    val m = FrameCipher(psk, nw, nm, isWorker = false)
    val f1 = w.seal("하나".toByteArray())
    val f2 = w.seal("둘".toByteArray())
    c.check("워커→마스터 왕복(순서대로)", String(m.open(f1)) == "하나" && String(m.open(f2)) == "둘")
    c.check("재전송 거절", fails { m.open(f1) })
    c.check("마스터→워커 왕복", String(w.open(m.seal("셋".toByteArray()))) == "셋")
    c.check("방향이 다르면 못 엶", fails { FrameCipher(psk, nw, nm, true).open(FrameCipher(psk, nw, nm, true).seal(byteArrayOf(1))) })
    c.check("한 바이트 바뀌면 거절", fails { val a = FrameCipher(psk, nw, nm, true).seal(byteArrayOf(1, 2, 3)); a[0] = (a[0] + 1).toByte(); FrameCipher(psk, nw, nm, false).open(a) })
    c.check("순서를 건너뛰면 거절", fails { val a = FrameCipher(psk, nw, nm, true); a.seal(byteArrayOf(1)); FrameCipher(psk, nw, nm, false).open(a.seal(byteArrayOf(2))) })
    c.check("키가 다르면 MAC 불일치", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, "a"), LinkCrypto.workerMac(ByteArray(32), nw, nm, "a")))
    c.check("이름이 다르면 MAC 불일치", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, "a"), LinkCrypto.workerMac(psk, nw, nm, "b")))
    c.check("워커 MAC 과 마스터 MAC 은 다름", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, ""), LinkCrypto.masterMac(psk, nw, nm)))
    c.check("키 길이 검사", fails { LinkCrypto.parseKey(java.util.Base64.getEncoder().encodeToString(ByteArray(31))) })
    c.check("키 파일: 만들고 다시 읽으면 같음", run {
        val f = File(TMP, "wk-test.key").also { it.delete() }
        LinkCrypto.loadOrCreateKey(f).contentEquals(LinkCrypto.loadOrCreateKey(f)) && f.readText().trim().length == 44
    })
    return c.done()
}
