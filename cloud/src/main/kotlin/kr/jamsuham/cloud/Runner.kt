package kr.jamsuham.cloud

import kr.jamsuham.core.AfkSession
import kr.jamsuham.core.CoreLog
import kr.jamsuham.core.JoinCredentials
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.Traffic
import kr.jamsuham.core.cloud.CloudEntry
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.net.CommandSuggestions
import java.io.File
import java.util.ArrayDeque

/*
 * 잠수를 "실제로 돌리는 곳"의 경계.
 *   본 서버(SessionManager)는 누가·어디에·몇 명까지 접속하는지 정하고,
 *   실행기(Runner)는 시킨 잠수를 돌리며 소식을 SessionListener 로 돌려준다.
 * 실행기는 본 서버 안에 내장된 것(LocalRunner)일 수도, 다른 컴퓨터의 접속용 서버(link/MasterLink 의 RemoteRunner)일 수도 있다.
 */

/** 엔진(core)의 경고를 서버 로그로 */
val cloudCoreLog = CoreLog { level, message, error -> if (level >= CoreLog.WARN) Log.w("[엔진] $message", error) }

/** 잠수 하나를 시작해 달라는 요청. [runId] 는 이번 실행의 고유 번호 (같은 서버를 다시 시작하면 새 번호) */
class LaunchRequest(val runId: String, val uuid: String, val entry: CloudEntry)

/** 실행기가 잠수 도중 본 서버에 되묻는 것 (로그인 정보와 자리는 본 서버만 안다) */
interface LaunchHooks {
    /** 접속에 쓸 로그인 정보. 실패하면 AuthException 을 그대로 던진다 */
    fun credentials(forceRefresh: Boolean): JoinCredentials

    /** 실제로 접속할 IP·포트의 자리를 잡는다. 자리가 없으면 IllegalArgumentException(사용자에게 보일 문구) */
    fun claimTarget(connectHost: String, connectPort: Int)
}

/** 돌고 있는 잠수 하나를 조작하는 손잡이 */
interface RunHandle {
    /** 사용자가 끊음 (재접속하지 않는다) */
    fun stop()

    /** 이 실행을 버린다 */
    fun shutdown()
    fun sendChat(text: String): Boolean
    fun setStick(east: Float, south: Float)
    fun setMovement(mask: Int)
    fun clickMenu(slot: Int): Boolean
    fun closeMenu()
    fun requestSuggestions(text: String, callback: (CommandSuggestions?) -> Unit): Boolean
    fun traffic(): Traffic?

    /** 지금 위치. 접속해 있고 걸을 수 있을 때만 (아니면 null) */
    fun position(): Wire.Pos?
}

interface Runner {
    /** 내장 실행기는 "local", 접속용 서버는 그 서버의 이름 */
    val id: String
    val remote: Boolean

    /** 동시에 돌릴 수 있는 잠수 수 */
    val capacity: Int
    fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle
}

/** 이 프로그램 안에서 바로 돌리는 실행기 (본 서버의 내장 실행기, 접속용 서버의 실행부가 같이 쓴다) */
class LocalRunner(
    override val id: String,
    override val capacity: Int,
    private val engineDir: File,
    private val allowPrivateTargets: Boolean,
    private val log: CoreLog,
) : Runner {
    override val remote = false

    override fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle {
        val s = AfkSession(
            engineDir, req.entry.sessionConfig(), { forceRefresh -> hooks.credentials(forceRefresh) }, events, log,
            targetFilter = { target ->
                // 내부망·이 컴퓨터 주소 검사는 실제로 접속하는 이 컴퓨터 기준으로 한다
                val t = NetGuard.filter(target, allowPrivateTargets)
                hooks.claimTarget(t.connectHost, t.connectPort)
                t
            },
        )
        s.start()
        return LocalHandle(s)
    }

    private class LocalHandle(private val s: AfkSession) : RunHandle {
        override fun stop() = s.stop()
        override fun shutdown() = s.shutdown()
        override fun sendChat(text: String) = s.sendChat(text)
        override fun setStick(east: Float, south: Float) = s.setStick(east, south)
        override fun setMovement(mask: Int) = s.setMovement(mask)
        override fun clickMenu(slot: Int) = s.clickMenu(slot)
        override fun closeMenu() = s.closeMenu()
        override fun requestSuggestions(text: String, callback: (CommandSuggestions?) -> Unit) = s.requestSuggestions(text, callback)
        override fun traffic(): Traffic = s.traffic

        override fun position(): Wire.Pos? {
            val c = s.liveConnection ?: return null
            if (!c.canWalk) return null
            return Wire.Pos(c.currentX, c.currentY, c.currentZ, c.worldName)
        }
    }
}

/**
 * 잠수를 어느 실행기에 맡길지 고른다. 실행기마다 지금 몇 개를 돌리는지, 어느 마인크래프트 서버에 몇 개가 들어가 있는지 여기서 센다.
 * (서버별 "전체" 인원 제한은 SessionManager 가 따로 센다 — 실행기를 늘려도 그 합계는 늘지 않는다)
 */
class Scheduler {
    private val runners = LinkedHashMap<String, Runner>()

    // 실행기 id → 주소 키 → 그 실행기에서 그 서버에 들어가 있는 잠수 수
    private val counts = HashMap<String, HashMap<String, Int>>()

    /** 같은 id 가 이미 있으면 넣지 않고 false */
    @Synchronized
    fun add(r: Runner): Boolean {
        if (runners.containsKey(r.id)) return false
        runners[r.id] = r
        return true
    }

    @Synchronized
    fun remove(id: String): Runner? {
        counts.remove(id)
        return runners.remove(id)
    }

    @Synchronized
    fun runners(): List<Runner> = runners.values.toList()

    @Synchronized
    fun load(runnerId: String): Int = counts[runnerId]?.values?.sum() ?: 0

    @Synchronized
    fun onTarget(runnerId: String, addressKey: String): Int = counts[runnerId]?.get(addressKey) ?: 0

    /**
     * 자리가 남은 실행기 중에서 고른다. 없으면 null.
     *  1) 같은 마인크래프트 서버에 이미 들어가 있는 잠수가 가장 적은 곳 (한 서버의 인원을 여러 컴퓨터에 고르게 나눈다)
     *  2) 남은 자리 비율이 가장 큰 곳
     *  3) 같으면 내장 실행기 (사이에 통신이 없어 반응이 빠르다)
     */
    @Synchronized
    fun pick(addressKey: String): Runner? =
        runners.values.filter { it.capacity - load(it.id) > 0 }.minWithOrNull(
            compareBy<Runner> { onTarget(it.id, addressKey) }
                .thenByDescending { (it.capacity - load(it.id)).toDouble() / it.capacity }
                .thenBy { it.remote },
        )

    @Synchronized
    fun placed(runnerId: String, addressKey: String) {
        counts.getOrPut(runnerId) { HashMap() }.merge(addressKey, 1, Int::plus)
    }

    @Synchronized
    fun released(runnerId: String, addressKey: String) {
        val m = counts[runnerId] ?: return
        val n = m[addressKey] ?: return
        if (n <= 1) m.remove(addressKey) else m[addressKey] = n - 1
        if (m.isEmpty()) counts.remove(runnerId)
    }
}

/**
 * 잠수 하나를 "다른 실행기로 다시 배정"한 횟수 제한 ([windowMs] 안에 [max] 번까지).
 * 다시 배정하면 엔진의 재접속 보호 카운터가 처음부터 시작하므로, 접속용 서버가 계속 끊겼다 붙었다 할 때 로그인이 끝없이 반복되지 않게 한다.
 */
class ReplaceBudget(private val max: Int = 3, private val windowMs: Long = 600_000L) {
    private val used = ArrayDeque<Long>()

    /** 쓸 수 있으면 한 번 쓰고 true. 이미 다 썼으면 false (쓰지 않는다) */
    @Synchronized
    fun tryUse(now: Long): Boolean {
        while (used.isNotEmpty() && now - used.first() > windowMs) used.removeFirst()
        if (used.size >= max) return false
        used.addLast(now)
        return true
    }
}
