import kr.jamsuham.cloud.LaunchHooks
import kr.jamsuham.cloud.LaunchRequest
import kr.jamsuham.cloud.ReplaceBudget
import kr.jamsuham.cloud.RunHandle
import kr.jamsuham.cloud.Runner
import kr.jamsuham.cloud.Scheduler
import kr.jamsuham.core.SessionListener

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
