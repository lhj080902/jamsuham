package kr.jamsuham.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kr.jamsuham.core.AuthException
import kr.jamsuham.core.DeathInfo
import kr.jamsuham.core.Inventory
import kr.jamsuham.core.JoinCredentials
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.Menu
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.SessionListener
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.Vitals
import kr.jamsuham.core.cloud.CloudEntry
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.net.CommandSuggestions
import kr.jamsuham.core.text.ChatText
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun SessionState.isActive(): Boolean =
    this == SessionState.PREPARING || this == SessionState.CONNECTING ||
        this == SessionState.ONLINE || this == SessionState.WAITING_RECONNECT

/** 실시간으로 내보내는 이벤트 하나 ([id] 는 채팅 줄의 순번 — 끊겼다 붙을 때 이어 받는 기준) */
class Event(val type: String, val data: String, val id: Long? = null)

/** 실시간 연결 하나 (브라우저 탭·앱). 받는 쪽이 느려 줄이 밀리면 끊는다 → 다시 붙으면 현재 상태부터 새로 받는다 */
class Subscriber(val tokenHash: String) {
    val queue = LinkedBlockingQueue<Event>(4000)
    @Volatile var closed = false
        private set
    /** 끊기로 한 시각 (받는 쪽이 읽지 않아 스레드가 쓰기에서 멈춰 있으면, 얼마 뒤 연결 자체를 닫아 스레드를 풀어 준다) */
    @Volatile var closedAt = 0L
        private set
    /** 쌓여 있는 글자 수 (개수뿐 아니라 크기로도 한도를 둔다) */
    private val queued = java.util.concurrent.atomic.AtomicLong()

    fun offer(e: Event) {
        if (closed) return
        if (queued.addAndGet(e.data.length.toLong()) > MAX_QUEUED_CHARS || !queue.offer(e)) close()
    }

    fun taken(e: Event) {
        queued.addAndGet(-e.data.length.toLong())
    }

    /** 이 연결을 끊는다. 기다리고 있던 내보내기 스레드가 바로 깨어나 끝나도록 빈 이벤트를 하나 넣는다 */
    fun close() {
        if (closed) return
        closedAt = System.currentTimeMillis()
        closed = true
        queue.offer(WAKE)
    }

    companion object {
        val WAKE = Event("", "")
        private const val MAX_QUEUED_CHARS = 8_000_000L
    }
}

/** 서버 하나의 잠수 상태 (세션이 꺼져 있어도 마지막 상태·채팅은 남겨 둔다) */
class SessionBox(@Volatile var entry: CloudEntry) {
    /** 지금(또는 마지막으로) 이 잠수를 돌린 실행 손잡이와 그 실행기 이름 */
    var run: RunHandle? = null
    var runner: String? = null
    var gen: Any? = null
    @Volatile var status = SessionStatus(SessionState.IDLE, "꺼짐")
    var players: PlayerList = PlayerList.EMPTY
    var playersDirty = false
    var vitals: Vitals? = null
    var vitalsDirty = false
    var menu: Menu? = null
    var inventory: Inventory = Inventory.EMPTY
    var inventoryDirty = false
    var death: DeathInfo? = null
    /** 이번 접속에서 서버가 보낸 리소스팩 (주소 → SHA-1). 앱이 그림 글자를 직접 받아 그린다 */
    val packs = LinkedHashMap<String, String>()
    /** 최근 채팅 줄 (순번, 내보낼 JSON) */
    val lines = ArrayDeque<Pair<Long, String>>()
    /** 다른 곳(PC 게임 등)에서 같은 계정으로 들어와 밀려난 시각 (0 = 없음) */
    @Volatile var duplicateAt = 0L
    /** 최근 채팅 보낸 시각들 (도배 방지) */
    val sentAt = ArrayDeque<Long>()
    /** 잠수 중으로 세어 둔 주소 키 (세고 있지 않으면 null) */
    var activeKey: String? = null
    /** 잠수 중으로 세어 둔 실행기 (세고 있지 않으면 null) */
    var countedRunner: String? = null
    /** 실제로 접속한 IP:포트 (자리 수를 세어 둔 것. 없으면 null) */
    @Volatile var ipKey: String? = null
    /** 이동 입력이 유효한 시각 (이때까지 새 입력이 없으면 멈춘다) */
    @Volatile var moveUntil = 0L
    /** 마지막으로 내보낸 위치 (같으면 다시 내보내지 않는다) */
    var lastPos: String? = null

    /**
     * 맡길 곳(자리)을 기다리는 중: 접속용 서버가 끊겨 다른 곳으로 옮기는 중이거나, 다시 켰는데 자리가 없을 때.
     * 화면에는 "다시 접속 대기"로 보이지만 실제 접속은 없으므로 잠수 수(전체·서버별·실행기별)에는 세지 않는다.
     */
    var queued = false
    /** 자리를 기다리기 시작한 시각, 다음에 시도할 시각 */
    var queuedAt = 0L
    var notBefore = 0L
    /** 대기가 풀려 접속할 때 채팅에 남길 안내 */
    var queueNote: String? = null
    /** 오래 기다려도 자리가 없어 멈춰 둔 것. "잠수 중" 표시는 남아 있어, 접속용 서버가 붙거나 다시 켜면 이어서 접속한다 */
    var parked = false
    /** 다른 실행기로 다시 배정한 횟수 제한 */
    val replaces = ReplaceBudget()
    /** 이번 실행에 끊으라고 시켰다 (접속용 서버와의 연결이 죽어 있으면 그 말이 닿지 않았을 수 있다) */
    var stopAsked = false
}

class AccountHub(val uuid: String) {
    val boxes = LinkedHashMap<String, SessionBox>()
    val subs = CopyOnWriteArrayList<Subscriber>()
    private var seq = 0L

    /** 계정 안에서 계속 커지는 줄 순번. 서버를 다시 켜도 뒤로 가지 않게 시각을 바탕으로 한다 */
    fun nextSeq(): Long {
        seq = maxOf(seq + 1, System.currentTimeMillis())
        return seq
    }
}

/** 앱이 폰에서 직접 잠수 중이라고 알려 온 것 (1분마다 갱신, 2분 30초 동안 소식이 없으면 끝난 것으로 본다) */
class Presence {
    class Hold(val device: String, val label: String, val address: String, val state: String, val since: Long, @Volatile var seenAt: Long)

    // 계정 → 기기(토큰 해시) → 주소 키 → 표시
    private val map = ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, Hold>>>()

    /** 이 기기의 잠수 목록을 통째로 바꾼다. 목록이 달라졌으면 true */
    fun report(uuid: String, device: String, label: String, sessions: List<Pair<String, String>>): Boolean {
        val now = System.currentTimeMillis()
        val byDevice = map.getOrPut(uuid) { ConcurrentHashMap() }
        val old = byDevice[device] ?: emptyMap()
        val next = LinkedHashMap<String, Hold>()
        for ((address, state) in sessions.take(20)) {
            val key = Wire.addressKey(address)
            if (key.isEmpty()) continue
            next[key] = Hold(device, label, address.take(255), state.take(30), old[key]?.since ?: now, now)
        }
        if (next.isEmpty()) byDevice.remove(device) else byDevice[device] = next
        return old.keys != next.keys || old.any { (k, v) -> next[k]?.state != v.state }
    }

    fun forget(uuid: String, device: String): Boolean = map[uuid]?.remove(device) != null

    fun forgetAccount(uuid: String) {
        map.remove(uuid)
    }

    fun holds(uuid: String): List<Hold> {
        val now = System.currentTimeMillis()
        return map[uuid]?.values?.flatMap { it.values }?.filter { now - it.seenAt < TTL_MS } ?: emptyList()
    }

    fun holder(uuid: String, addressKey: String): Hold? = holds(uuid).firstOrNull { Wire.addressKey(it.address) == addressKey }

    /** 소식이 끊긴 기록을 치운다. 치운 계정들을 돌려준다 (화면 표시를 고치도록) */
    fun sweep(): Set<String> {
        val now = System.currentTimeMillis()
        val changed = HashSet<String>()
        for ((uuid, byDevice) in map) {
            for ((device, holds) in byDevice) {
                if (holds.values.all { now - it.seenAt >= TTL_MS }) {
                    byDevice.remove(device)
                    changed.add(uuid)
                }
            }
        }
        return changed
    }

    companion object {
        const val TTL_MS = 150_000L
    }
}

/** 잠수 시작 요청의 결과 */
sealed class StartResult {
    object Ok : StartResult()
    object NotFound : StartResult()
    object NotPremium : StartResult()
    class Limit(val message: String) : StartResult()
    /** 다른 매체가 이 서버에 접속 중. [kind] app = 폰 앱에서 직접 잠수 중 / other = 다른 클라이언트(PC 게임 등)가 방금 들어옴 */
    class InUse(val kind: String, val label: String, val canForce: Boolean) : StartResult()
    class Bad(val message: String) : StartResult()
}

class SessionManager(
    private val config: Config,
    private val store: DataStore,
    private val premium: Premium,
    private val auth: MsAuth,
    val presence: Presence,
    private val scheduler: Scheduler,
) {
    private val hubs = ConcurrentHashMap<String, AccountHub>()
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cloud-ticker").apply { isDaemon = true } }
    @Volatile private var shuttingDown = false
    private var ticks = 0L

    /** 지금 이동 입력이 들어와 있는 세션들 (위치를 자주 알리고, 입력이 끊기면 멈추려고 따로 본다) */
    private val moving = ConcurrentHashMap<SessionBox, AccountHub>()

    /** 마지막으로 잠수를 실행기에 맡긴 시각 (자리를 기다리던 잠수들을 한꺼번에 시작하지 않으려고 본다) */
    @Volatile private var lastLaunchAt = 0L

    init {
        ticker.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
        ticker.scheduleWithFixedDelay({ moveTick() }, 250, 250, TimeUnit.MILLISECONDS)
    }

    fun hub(uuid: String): AccountHub = hubs.getOrPut(uuid) {
        AccountHub(uuid).also { h ->
            val sf = store.servers(uuid)
            synchronized(sf) { for (e in sf.entries.values) h.boxes[e.id] = SessionBox(e) }
        }
    }

    // 잠수 중인 세션 수 (전체 / 마인크래프트 서버 주소별). 계정 잠금 없이 읽을 수 있게 따로 센다
    // (한 계정의 잠금을 든 채 다른 계정들을 훑으면 서로 물릴 수 있다)
    private val activeTotal = AtomicInteger()
    private val activeByKey = ConcurrentHashMap<String, AtomicInteger>()
    /** 실제 접속 IP:포트 → 그 서버에 들어가 있는 세션들 */
    private val activeByIp = ConcurrentHashMap<String, MutableSet<SessionBox>>()

    fun activeCount(): Int = activeTotal.get()

    /** hub 잠금 안에서 부른다. 세션이 잠수 상태로 들어가거나 나올 때 수를 맞춘다 */
    private fun countActive(box: SessionBox, active: Boolean) {
        if (active) {
            if (box.activeKey != null) return
            val key = Wire.addressKey(box.entry.address)
            box.activeKey = key
            activeTotal.incrementAndGet()
            activeByKey.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            box.runner?.let { r -> scheduler.placed(r, key); box.countedRunner = r }
        } else {
            val key = box.activeKey ?: return
            box.activeKey = null
            activeTotal.decrementAndGet()
            // 0 이 되면 지운다 (사용자가 적은 주소마다 항목이 쌓이지 않게)
            activeByKey.computeIfPresent(key) { _, n -> if (n.decrementAndGet() <= 0) null else n }
            box.countedRunner?.let { scheduler.released(it, key) }
            box.countedRunner = null
            box.ipKey?.let { ip -> activeByIp.computeIfPresent(ip) { _, set -> set.remove(box); if (set.isEmpty()) null else set } }
            box.ipKey = null
        }
    }

    /**
     * 실제로 접속하는 IP·포트 기준의 자리 확인 (세션 스레드에서, 주소를 확인한 직후에 부른다).
     * 같은 서버를 다른 이름(별칭 도메인)으로 적어 서버별 계정 수 제한을 피하는 것을 막는다. 자리가 없으면 예외 → 세션이 그 문구로 멈춘다.
     */
    private fun claimIp(box: SessionBox, ipKey: String, cap: Int) {
        if (box.ipKey == ipKey) return
        box.ipKey?.let { old -> activeByIp.computeIfPresent(old) { _, set -> set.remove(box); if (set.isEmpty()) null else set } }
        box.ipKey = null
        val set = activeByIp.computeIfAbsent(ipKey) { ConcurrentHashMap.newKeySet() }
        if (cap > 0 && set.size >= cap && box !in set) {
            throw IllegalArgumentException("지금 이 서버는 클라우드 자리(${cap}개)가 모두 찼습니다. 자리가 나면 다시 시도하거나 폰 앱으로 접속해 주세요")
        }
        set.add(box)
        box.ipKey = ipKey
    }

    // ------------------------------------------------------------ 내보내기

    private fun publish(h: AccountHub, type: String, data: JsonObject, id: Long? = null) {
        if (h.subs.isEmpty()) return
        val json = data.toString()
        // 서버가 보낸 내용이 비정상적으로 크면 내보내지 않는다 (한 서버가 모든 사용자의 연결을 느리게 만들지 못하게)
        if (json.length > MAX_EVENT_CHARS) return
        val e = Event(type, json, id)
        for (s in h.subs) s.offer(e)
    }

    /** 이 계정의 다른 화면들에 알림 하나를 보낸다 (새 기기 로그인 등) */
    fun notify(uuid: String, type: String, data: JsonObject) {
        val h = hubs[uuid] ?: return
        synchronized(h) { publish(h, type, data) }
    }

    /** 접속자 목록은 앞에서부터 [MAX_PLAYERS_SENT] 명까지만 내보낸다 */
    private fun playersJson(list: PlayerList) =
        Wire.players(if (list.players.size > MAX_PLAYERS_SENT) list.copy(players = list.players.sortedBy { it.order }.take(MAX_PLAYERS_SENT)) else list)

    private fun boxJson(b: SessionBox): JsonObject {
        val packs = JsonArray()
        for ((url, hash) in b.packs) packs.add(jsonOf("url" to url, "hash" to hash))
        val t = b.run?.traffic()
        return jsonOf(
            "entry" to Wire.entry(b.entry),
            "status" to Wire.status(b.status),
            "players" to playersJson(b.players),
            "vitals" to Wire.vitals(b.vitals),
            "menu" to Wire.menu(b.menu),
            "inventory" to Wire.inventory(b.inventory),
            "death" to Wire.death(b.death),
            "packs" to packs,
            "traffic" to t?.let { jsonOf("rx" to it.received, "tx" to it.sent, "onlineMs" to it.onlineMs) },
            "pos" to Wire.pos(position(b)),
            "duplicateAt" to b.duplicateAt.takeIf { it > 0 },
        )
    }

    /** 이 계정의 서버들이 지금 어느 매체에서 돌고 있는지 (화면의 "○○에서 실행 중" 표시) */
    private fun presenceJson(h: AccountHub): JsonArray {
        val out = JsonArray()
        for (b in h.boxes.values) {
            if (b.status.state.isActive()) {
                out.add(jsonOf("key" to Wire.addressKey(b.entry.address), "address" to b.entry.address, "holder" to "cloud",
                    "label" to "클라우드", "state" to b.status.state.name, "since" to b.status.onlineSinceMs, "sid" to b.entry.id))
            } else if (b.duplicateAt > 0 && System.currentTimeMillis() - b.duplicateAt < OTHER_HOLD_MS) {
                out.add(jsonOf("key" to Wire.addressKey(b.entry.address), "address" to b.entry.address, "holder" to "other",
                    "label" to "다른 클라이언트(PC 게임 등)", "since" to b.duplicateAt, "sid" to b.entry.id))
            }
        }
        for (hold in presence.holds(h.uuid)) {
            out.add(jsonOf("key" to Wire.addressKey(hold.address), "address" to hold.address, "holder" to "app",
                "label" to hold.label, "state" to hold.state, "since" to hold.since))
        }
        return out
    }

    private fun publishPresence(h: AccountHub) = publish(h, "presence", jsonOf("presence" to presenceJson(h)))

    fun presenceOf(uuid: String): JsonArray = hub(uuid).let { h -> synchronized(h) { presenceJson(h) } }

    /** 다른 기기에서 앱이 직접 잠수하는 목록이 바뀌었을 때 */
    fun presenceChanged(uuid: String) {
        val h = hubs[uuid] ?: return
        synchronized(h) { publishPresence(h) }
    }

    fun accountJson(acc: AccountRec): JsonObject {
        val p = premium.status(acc.uuid, acc.name)
        return jsonOf("uuid" to acc.uuid, "name" to acc.name, "premium" to p.active, "premiumUntil" to p.untilMs, "dev" to acc.dev.takeIf { it })
    }

    private fun helloJson(h: AccountHub, acc: AccountRec): JsonObject {
        val sessions = JsonArray()
        for (b in h.boxes.values) sessions.add(boxJson(b))
        return jsonOf(
            "api" to Wire.API,
            "version" to CLOUD_VERSION,
            "serverTime" to System.currentTimeMillis(),
            "account" to accountJson(acc),
            "limits" to jsonOf("sessions" to config.maxSessionsPerAccount, "servers" to config.maxServersPerAccount),
            "sessions" to sessions,
            "presence" to presenceJson(h),
        )
    }

    /**
     * 실시간 연결을 붙인다. 먼저 현재 상태 전체(hello)를 보내고, [since] 보다 뒤의 채팅 줄을 이어서 보낸 뒤 실시간으로 넘어간다.
     * [since] 가 없으면(처음 여는 웹) 서버마다 최근 300줄.
     */
    fun attach(acc: AccountRec, sub: Subscriber, since: Long?) {
        val h = hub(acc.uuid)
        synchronized(h) {
            // 한 계정에 연결을 너무 많이 쌓지 않는다 (탭을 계속 여는 경우 가장 오래된 것부터 끊음)
            while (h.subs.size >= MAX_SUBS) h.subs.removeAt(0).close()
            sub.offer(Event("hello", helloJson(h, acc).toString()))
            val replay = ArrayList<Pair<Long, String>>()
            for (b in h.boxes.values) {
                if (since != null) b.lines.filterTo(replay) { it.first > since }
                else replay.addAll(b.lines.toList().takeLast(300))
            }
            replay.sortBy { it.first }
            for ((seq, json) in replay) sub.offer(Event("line", json, seq))
            sub.offer(Event("ready", "{}"))
            h.subs.add(sub)
        }
    }

    fun detach(uuid: String, sub: Subscriber) {
        hubs[uuid]?.subs?.remove(sub)
    }

    /** 이 기기(토큰)의 실시간 연결을 끊는다 (로그아웃) */
    fun closeSubs(uuid: String, tokenHash: String?) {
        val h = hubs[uuid] ?: return
        for (s in h.subs) if (tokenHash == null || s.tokenHash == tokenHash) {
            s.offer(Event("bye", "{}"))
            s.close()
        }
    }

    // ------------------------------------------------------------ 서버 목록

    fun putEntry(uuid: String, entry: CloudEntry): String? {
        val h = hub(uuid)
        val sf = store.servers(uuid)
        synchronized(h) {
            val existing = h.boxes[entry.id]
            if (existing == null && h.boxes.size >= config.maxServersPerAccount) return "서버는 ${config.maxServersPerAccount}개까지 저장할 수 있습니다"
            val box = existing ?: SessionBox(entry).also { h.boxes[entry.id] = it }
            box.entry = entry
            synchronized(sf) { sf.entries[entry.id] = entry }
            store.saveServers(uuid)
            publish(h, "entry", jsonOf("session" to boxJson(box)))
            if (existing != null) publishPresence(h)
        }
        return null
    }

    fun deleteEntry(uuid: String, id: String): Boolean {
        val h = hub(uuid)
        val sf = store.servers(uuid)
        synchronized(h) {
            val box = h.boxes.remove(id) ?: return false
            box.gen = null
            box.run?.shutdown()
            box.run = null
            box.queued = false
            box.parked = false
            moving.remove(box)
            countActive(box, false)
            synchronized(sf) {
                sf.entries.remove(id)
                sf.running.remove(id)
            }
            store.saveServers(uuid)
            publish(h, "entryRemoved", jsonOf("id" to id))
            publishPresence(h)
        }
        return true
    }

    // ------------------------------------------------------------ 시작·끊기

    fun start(acc: AccountRec, id: String, force: Boolean, note: String? = null): StartResult {
        val h = hub(acc.uuid)
        synchronized(h) {
            val box = h.boxes[id] ?: return StartResult.NotFound
            if (box.status.state.isActive() && premium.status(acc.uuid, acc.name).active && box.entry.address.isNotBlank()) {
                // 이미 잠수 중(자리를 기다리는 중 포함): 요청한 쪽 화면이 지금 상태로 맞춰지도록 한 번 더 알린다
                publish(h, "status", jsonOf("sid" to id, "status" to Wire.status(box.status)))
                return StartResult.Ok
            }
            startBlock(h, acc, box, force)?.let { return it.result }
            if (!launch(h, box, note)) return StartResult.Limit(FULL_TEXT)
            return StartResult.Ok
        }
    }

    /** 시작할 수 없는 이유. [noRoom] = 지금 자리가 없을 뿐이라 기다리면 풀릴 수 있는 것 */
    private class StartBlock(val result: StartResult, val noRoom: Boolean = false)

    /** hub 잠금 안에서 부른다. 이 잠수를 지금 시작해도 되는지 확인한다 (되면 null). 직접 시작·이어서 접속·다시 배정이 같이 쓴다 */
    private fun startBlock(h: AccountHub, acc: AccountRec, box: SessionBox, force: Boolean): StartBlock? {
        if (!premium.status(acc.uuid, acc.name).active) return StartBlock(StartResult.NotPremium)
        if (box.entry.address.isBlank()) return StartBlock(StartResult.Bad("서버 주소를 먼저 입력해 주세요"))
        val key = Wire.addressKey(box.entry.address)
        // 같은 서버에 이 계정이 다른 매체로 들어가 있으면 시작하지 않는다 (서로 튕겨 내는 것을 막음)
        presence.holder(acc.uuid, key)?.let { return StartBlock(StartResult.InUse("app", it.label, canForce = false)) }
        h.boxes.values.firstOrNull { it !== box && it.status.state.isActive() && Wire.addressKey(it.entry.address) == key }
            ?.let { return StartBlock(StartResult.InUse("cloud", "클라우드 (${it.entry.name.ifBlank { it.entry.address }})", canForce = false)) }
        if (!force && box.duplicateAt > 0 && System.currentTimeMillis() - box.duplicateAt < OTHER_HOLD_MS) {
            return StartBlock(StartResult.InUse("other", "다른 클라이언트(PC 게임 등)", canForce = true))
        }
        val mine = h.boxes.values.count { it !== box && it.status.state.isActive() }
        if (mine >= config.maxSessionsPerAccount) {
            return StartBlock(StartResult.Limit("동시에 잠수할 수 있는 서버는 ${config.maxSessionsPerAccount}개까지입니다. 다른 서버를 끊고 다시 시도해 주세요"))
        }
        if (activeCount() >= config.maxActiveSessions) return StartBlock(StartResult.Limit(FULL_TEXT), noRoom = true)
        // 한 마인크래프트 서버에 클라우드 계정이 너무 많이 들어가지 않게 한다. 접속용 서버를 늘려도 이 합계는 늘지 않는다 (전체 기준)
        val cap = config.targetLimit(key)
        if (cap > 0 && (activeByKey[key]?.get() ?: 0) >= cap) {
            return StartBlock(StartResult.Limit("지금 이 서버는 클라우드 자리(${cap}개)가 모두 찼습니다. 자리가 나면 다시 시도하거나 폰 앱으로 접속해 주세요"), noRoom = true)
        }
        return null
    }

    /** hub 잠금 안에서 부른다. 맡길 실행기(자리)가 없으면 false (상태는 바꾸지 않는다) */
    private fun launch(h: AccountHub, box: SessionBox, note: String?): Boolean {
        val uuid = h.uuid
        val id = box.entry.id
        val runner = scheduler.pick(Wire.addressKey(box.entry.address)) ?: return false
        val gen = Any()
        box.gen = gen
        box.run?.shutdown()
        box.run = null
        box.duplicateAt = 0
        box.packs.clear()
        box.players = PlayerList.EMPTY
        box.vitals = null
        box.menu = null
        box.inventory = Inventory.EMPTY
        publish(h, "players", jsonOf("sid" to id).also { it.add("players", playersJson(PlayerList.EMPTY)) })
        publish(h, "vitals", jsonOf("sid" to id, "vitals" to Wire.vitals(null)))
        publish(h, "menu", jsonOf("sid" to id, "menu" to Wire.menu(null)))

        fun mine(block: () -> Unit) {
            if (shuttingDown) return
            synchronized(h) { if (box.gen === gen) block() }
        }

        val listener = object : SessionListener {
            override fun onStatus(status: SessionStatus) = mine { setStatus(h, box, status) }
            override fun onLine(line: LogLine) = mine { addLine(h, box, line) }
            override fun onPlayers(list: PlayerList) = mine { box.players = list; box.playersDirty = true }
            override fun onVitals(vitals: Vitals?) = mine { box.vitals = vitals; box.vitalsDirty = true }
            override fun onInventory(inventory: Inventory) = mine { box.inventory = inventory; box.inventoryDirty = true }
            override fun onMenu(menu: Menu?) = mine {
                box.menu = menu
                publish(h, "menu", jsonOf("sid" to id, "menu" to Wire.menu(menu)))
            }
            override fun onDeath(death: DeathInfo) = mine {
                box.death = death
                publish(h, "death", jsonOf("sid" to id, "death" to Wire.death(death)))
            }
            override fun onResourcePack(url: String, hash: String) = mine {
                if (url.startsWith("http") && box.packs.size < 8) {
                    box.packs[url] = hash
                    publish(h, "pack", jsonOf("sid" to id, "url" to url, "hash" to hash))
                }
            }
            override fun onDuplicateLogin() = mine { box.duplicateAt = System.currentTimeMillis() }
        }
        val hooks = object : LaunchHooks {
            override fun credentials(forceRefresh: Boolean): JoinCredentials {
                // 지금 돌고 있는 실행에만 준다 (끊었거나 다른 곳으로 옮긴 잠수를 맡았던 접속용 서버가 토큰을 계속 받아 가지 못하게).
                // 계정 잠금을 잡고 확인한다: 시작 직후에는 "준비 중" 상태가 적히기 전일 수 있다
                val live = synchronized(h) { box.gen === gen && box.status.state.isActive() }
                if (!live) throw AuthException("이미 끝난 잠수입니다", needsRelogin = false)
                return auth.credentials(uuid, forceRefresh)
            }
            override fun claimTarget(connectHost: String, connectPort: Int) {
                if (box.gen === gen) claimIp(box, "$connectHost:$connectPort", config.targetLimit(Wire.addressKey(box.entry.address)))
            }
        }
        // 실행기가 바로 접속을 시작하지만, 그쪽 소식은 이 계정의 잠금이 풀린 뒤에야 처리되므로 아래 "준비 중" 이 먼저 나간다
        box.run = try {
            runner.launch(LaunchRequest(randomId(), uuid, box.entry), listener, hooks)
        } catch (e: IllegalStateException) {
            // 고른 직후에 그 접속용 서버의 연결이 끊긴 경우
            Log.w("${runner.id} 에 잠수를 맡기지 못했습니다", e)
            return false
        }
        box.runner = runner.id
        box.queued = false
        box.parked = false
        box.queueNote = null
        box.stopAsked = false
        lastLaunchAt = System.currentTimeMillis()
        Log.i("[배정] ${store.account(uuid)?.name ?: uuid.take(8)}/$id → ${runner.id}")
        setStatus(h, box, SessionStatus(SessionState.PREPARING, "준비 중…"))
        if (note != null) addLine(h, box, LogLine(System.currentTimeMillis(), LogLine.Kind.SYSTEM, ChatText.of(note)))
        return true
    }

    /** hub 잠금 안에서 부른다 */
    private fun setStatus(h: AccountHub, box: SessionBox, status: SessionStatus) {
        val wasActive = box.status.state.isActive()
        box.status = status
        val active = status.state.isActive()
        if (!active || box.queued) {
            // 끊긴 뒤에 예전 체력·메뉴·접속자가 남아 보이지 않게
            box.vitals = null; box.vitalsDirty = true
            box.players = PlayerList.EMPTY; box.playersDirty = true
            if (box.menu != null) {
                box.menu = null
                publish(h, "menu", jsonOf("sid" to box.entry.id, "menu" to Wire.menu(null)))
            }
        }
        publish(h, "status", jsonOf("sid" to box.entry.id, "status" to Wire.status(status)))
        // 자리를 기다리는 동안은 실제 접속이 없으므로 세지 않는다 (이미 맞으면 아무것도 하지 않는다)
        countActive(box, active && !box.queued)
        if (wasActive != active) {
            // "잠수 중이어야 하는 서버" 표시 (서버 컴퓨터가 다시 켜지면 이어서 접속). 자리가 없어 멈춰 둔 것은 표시를 남긴다
            if (active) mark(h, box, true) else if (!box.parked) mark(h, box, false)
            publishPresence(h)
        }
    }

    /** "잠수 중이어야 하는 서버" 표시를 올리거나 내린다 */
    private fun mark(h: AccountHub, box: SessionBox, on: Boolean) {
        val sf = store.servers(h.uuid)
        val changed = synchronized(sf) { if (on) sf.running.add(box.entry.id) else sf.running.remove(box.entry.id) }
        if (changed) store.saveServers(h.uuid)
    }

    /** hub 잠금 안에서 부른다 */
    private fun addLine(h: AccountHub, box: SessionBox, line: LogLine) {
        val seq = h.nextSeq()
        var o = Wire.line(line, seq)
        o.addProperty("sid", box.entry.id)
        var json = o.toString()
        if (json.length > MAX_LINE_CHARS) {
            // 비정상적으로 긴 줄(수십만 글자)은 앞부분만 남긴다. 1,500줄씩 쌓아 두므로 그대로 두면 메모리를 다 쓴다
            val cut = LogLine(line.timeMs, line.kind, ChatText.of(line.text.plain.take(2000) + " … (너무 긴 줄이라 잘랐습니다)"))
            o = Wire.line(cut, seq)
            o.addProperty("sid", box.entry.id)
            json = o.toString()
        }
        box.lines.addLast(seq to json)
        while (box.lines.size > MAX_LINES) box.lines.removeFirst()
        if (h.subs.isNotEmpty()) {
            val e = Event("line", json, seq)
            for (s in h.subs) s.offer(e)
        }
    }

    fun stop(uuid: String, id: String): Boolean {
        val h = hub(uuid)
        synchronized(h) {
            val box = h.boxes[id] ?: return false
            if (box.queued || box.parked) {
                halt(h, box, "꺼짐", null)
                return true
            }
            val s = box.run
            if (s == null || !box.status.state.isActive()) {
                if (box.status.state != SessionState.STOPPED) setStatus(h, box, box.status.copy(state = SessionState.STOPPED, message = "꺼짐", onlineSinceMs = null))
            } else {
                box.stopAsked = true
                s.stop()
            }
            return true
        }
    }

    /** hub 잠금 안에서 부른다. 자리를 기다리거나 멈춰 둔 잠수를 완전히 멈춘다 (다시 켜도 이어서 접속하지 않는다) */
    private fun halt(h: AccountHub, box: SessionBox, message: String, line: LogLine.Kind?) {
        box.queued = false
        box.parked = false
        box.queueNote = null
        if (line != null) addLine(h, box, LogLine(System.currentTimeMillis(), line, ChatText.of(message)))
        setStatus(h, box, SessionStatus(SessionState.STOPPED, message, reconnectCount = box.status.reconnectCount, totalOnlineMs = box.status.totalOnlineMs))
        // 멈춰 둔 것은 이미 STOPPED 라서 위에서 표시가 내려가지 않는다
        mark(h, box, false)
    }

    // ------------------------------------------------------------ 접속용 서버·자리 대기

    /** hub 잠금 안에서 부른다. 자리를 기다리는 줄에 넣는다 ([notBefore] 전에는 시도하지 않는다) */
    private fun enqueue(h: AccountHub, box: SessionBox, notBefore: Long, message: String, note: String?) {
        box.parked = false
        box.queued = true
        // 기다린 시간은 처음 시도할 수 있는 때부터 잰다
        box.queuedAt = notBefore
        box.notBefore = notBefore
        box.queueNote = note
        setStatus(h, box, SessionStatus(SessionState.WAITING_RECONNECT, message, nextAttemptAtMs = notBefore,
            reconnectCount = box.status.reconnectCount, totalOnlineMs = box.status.totalOnlineMs))
    }

    /** 접속용 서버가 붙었을 때: 자리가 없어 멈춰 둔 잠수를 다시 줄에 넣는다 */
    fun runnerAttached(runnerId: String) {
        if (shuttingDown) return
        val now = System.currentTimeMillis()
        for (h in hubs.values) {
            synchronized(h) {
                for (b in h.boxes.values) if (b.parked) enqueue(h, b, now, WAIT_ROOM_TEXT, "자리가 나서 이어서 접속합니다")
            }
        }
    }

    /**
     * 접속용 서버의 연결이 끊겼을 때 ([graceful] = 그쪽에서 끄면서 알려 옴). 거기서 돌던 잠수를 다른 곳에 다시 맡긴다.
     * 그쪽이 살아 있는데 연결만 끊긴 경우에는 그쪽도 연결이 끊긴 것을 알아채고(15초) 잠수를 끊을 때까지 기다린 뒤에 옮긴다
     * — 바로 옮기면 한 계정이 두 곳에서 접속해 서로 튕겨 낸다.
     */
    fun runnerLost(runnerId: String, graceful: Boolean) {
        // 본 서버를 끄는 중이면 그대로 둔다 ("잠수 중" 표시가 남아 있어 다시 켜면 이어서 접속한다)
        if (shuttingDown) return
        val now = System.currentTimeMillis()
        for (h in hubs.values) {
            synchronized(h) {
                for (b in h.boxes.values) {
                    if (b.runner != runnerId || b.run == null) continue
                    // 잠수 하나를 정리하다 오류가 나도(저장 실패 등) 나머지는 계속 정리한다 — 건너뛰면 실행기도 없이 "접속 중" 으로 남는다
                    try {
                        lostRun(h, b, graceful, now)
                    } catch (e: Exception) {
                        Log.w("[노드] 끊긴 잠수를 정리하지 못했습니다 (${h.uuid.take(8)}/${b.entry.id})", e)
                    }
                }
            }
        }
    }

    /** hub 잠금 안에서 부른다. 이 잠수를 돌리던 접속용 서버가 끊겼다 */
    private fun lostRun(h: AccountHub, b: SessionBox, graceful: Boolean, now: Long) {
        b.gen = null
        b.run = null
        moving.remove(b)
        if (!b.status.state.isActive()) return
        if (b.stopAsked) {
            // 끊으라고 시켰는데 그 말이 닿기 전에 연결이 끊겼다: 다른 곳에서 되살리지 않고 그대로 멈춘다
            halt(h, b, "연결을 끊었습니다", null)
            return
        }
        // 접속용 서버를 정상 종료(업데이트 등)한 것은 횟수에 세지 않는다
        if (!graceful && !b.replaces.tryUse(now)) {
            halt(h, b, "접속용 서버가 자주 끊겨 잠수를 멈췄습니다", LogLine.Kind.ERROR)
            return
        }
        val text = "접속용 서버 연결이 끊겨 다른 곳에서 다시 접속합니다"
        // 엔진이 재접속을 미루고 있었다면(모장 요청 제한 등) 그 시각보다 앞당기지 않는다
        val at = maxOf(if (graceful) now else now + REPLACE_DELAY_MS, b.status.nextAttemptAtMs ?: 0)
        addLine(h, b, LogLine(now, LogLine.Kind.SYSTEM, ChatText.of(text)))
        enqueue(h, b, at, text, "다른 접속용 서버에서 이어서 접속합니다")
    }

    /** 자리를 기다리는 잠수를 맡겨 본다 (1초마다). 한꺼번에 로그인하지 않게 [PLACE_GAP_MS] 에 하나씩만 시작한다 */
    private fun placeQueued() {
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < PLACE_GAP_MS) return
        val due = ArrayList<Pair<AccountHub, SessionBox>>()
        for (h in hubs.values) {
            synchronized(h) { for (b in h.boxes.values) if (b.queued && now >= b.notBefore) due.add(h to b) }
        }
        if (due.isEmpty()) return
        // 오래 기다린 것부터
        due.sortBy { it.second.notBefore }
        for ((h, b) in due) {
            val started = synchronized(h) {
                if (hubs[h.uuid] !== h || h.boxes[b.entry.id] !== b || !b.queued) return@synchronized false
                val acc = store.account(h.uuid)
                if (acc == null) {
                    b.queued = false
                    return@synchronized false
                }
                val block = startBlock(h, acc, b, force = true)
                when {
                    block == null -> if (launch(h, b, b.queueNote)) return@synchronized true else waitRoom(h, b, now)
                    block.noRoom -> waitRoom(h, b, now)
                    else -> halt(h, b, refusal(block.result), LogLine.Kind.ERROR)
                }
                false
            }
            if (started) return
        }
    }

    /** hub 잠금 안에서 부른다. 지금은 자리가 없다: 조금 뒤 다시 보고, 너무 오래되면 멈춰 둔다 */
    private fun waitRoom(h: AccountHub, b: SessionBox, now: Long) {
        if (now - b.queuedAt > QUEUE_MAX_MS) {
            val text = "접속할 자리가 없어 멈췄습니다. 자리가 나면 이어서 접속합니다"
            b.queued = false
            b.parked = true
            addLine(h, b, LogLine(now, LogLine.Kind.ERROR, ChatText.of(text)))
            setStatus(h, b, SessionStatus(SessionState.STOPPED, text, reconnectCount = b.status.reconnectCount, totalOnlineMs = b.status.totalOnlineMs))
            return
        }
        b.notBefore = now + QUEUE_RETRY_MS
        setStatus(h, b, SessionStatus(SessionState.WAITING_RECONNECT, WAIT_ROOM_TEXT, nextAttemptAtMs = b.notBefore,
            reconnectCount = b.status.reconnectCount, totalOnlineMs = b.status.totalOnlineMs))
    }

    /** 기다리던 잠수를 시작할 수 없게 됐을 때 화면에 남길 문구 */
    private fun refusal(r: StartResult): String = when (r) {
        is StartResult.NotPremium -> "프리미엄 기간이 끝나 클라우드 잠수를 멈췄습니다"
        is StartResult.InUse -> "${r.label}에서 이 서버에 접속 중이라 클라우드 잠수를 멈췄습니다"
        is StartResult.Limit -> r.message
        is StartResult.Bad -> r.message
        else -> "잠수를 이어서 시작하지 못했습니다"
    }

    /** 계정의 잠수를 모두 끊는다 (프리미엄 만료·연결 해제). [why] 는 채팅에 남길 안내 */
    fun stopAll(uuid: String, why: String?) {
        val h = hubs[uuid] ?: return
        synchronized(h) {
            for (b in h.boxes.values) {
                if (b.queued || b.parked) {
                    halt(h, b, why ?: "꺼짐", if (why != null) LogLine.Kind.ERROR else null)
                    continue
                }
                if (!b.status.state.isActive()) continue
                if (why != null) addLine(h, b, LogLine(System.currentTimeMillis(), LogLine.Kind.ERROR, ChatText.of(why)))
                b.stopAsked = true
                b.run?.stop()
            }
        }
    }

    /** 계정을 지울 때: 세션·기록·연결을 모두 정리한다 */
    fun dropAccount(uuid: String) {
        val h = hubs.remove(uuid) ?: return
        synchronized(h) {
            for (b in h.boxes.values) {
                b.gen = null
                b.run?.shutdown()
                b.run = null
                b.queued = false
                b.parked = false
                moving.remove(b)
                countActive(b, false)
            }
            for (s in h.subs) {
                s.offer(Event("bye", "{}"))
                s.close()
            }
            h.subs.clear()
            h.boxes.clear()
        }
        presence.forgetAccount(uuid)
    }

    // ------------------------------------------------------------ 조작

    /** 채팅·명령어 보내기. 실패하면 이유를 돌려준다 */
    fun chat(uuid: String, id: String, text: String): String? {
        val clean = text.replace(Regex("[\\r\\n\\u0000-\\u001f\\u007f§]"), " ").trim()
        if (clean.isEmpty()) return "보낼 내용이 없습니다"
        if (clean.length > 256) return "한 번에 256자까지 보낼 수 있습니다"
        val h = hub(uuid)
        synchronized(h) {
            val box = h.boxes[id] ?: return "서버를 찾을 수 없습니다"
            val now = System.currentTimeMillis()
            while (box.sentAt.isNotEmpty() && now - box.sentAt.first() > 10_000) box.sentAt.removeFirst()
            if (box.sentAt.size >= 20) return "너무 빨리 보내고 있습니다. 잠시 뒤 다시 보내 주세요"
            if (box.status.state != SessionState.ONLINE || box.run?.sendChat(clean) != true) return "서버에 접속해 있을 때만 보낼 수 있습니다"
            box.sentAt.addLast(now)
            return null
        }
    }

    // ------------------------------------------------------------ 이동

    private fun position(b: SessionBox): Wire.Pos? {
        if (b.status.state != SessionState.ONLINE) return null
        return b.run?.position()
    }

    /** hub 잠금 안에서 부른다. 위치가 바뀌었으면 알린다 */
    private fun publishPos(h: AccountHub, b: SessionBox) {
        if (h.subs.isEmpty()) return
        val json = Wire.pos(position(b)).toString()
        if (json == b.lastPos) return
        b.lastPos = json
        publish(h, "pos", jsonOf("sid" to b.entry.id).also { it.add("pos", com.google.gson.JsonParser.parseString(json)) })
    }

    /**
     * 이동 입력 (조이스틱 방향 + 점프). 실패하면 이유를 돌려준다.
     * 입력은 [MOVE_HOLD_MS] 동안만 유효하다 → 화면이 누르고 있는 동안 계속 다시 보내야 하고,
     * 브라우저를 닫거나 연결이 끊겨 "손 뗌"이 오지 않아도 캐릭터가 혼자 계속 걸어가지 않는다.
     */
    fun move(uuid: String, id: String, east: Float, south: Float, jump: Boolean): String? {
        val h = hub(uuid)
        synchronized(h) {
            val box = h.boxes[id] ?: return "서버를 찾을 수 없습니다"
            val s = box.run
            if (s == null || box.status.state != SessionState.ONLINE) return "서버에 접속해 있을 때만 움직일 수 있습니다"
            val e = if (east.isFinite()) east.coerceIn(-1f, 1f) else 0f
            val so = if (south.isFinite()) south.coerceIn(-1f, 1f) else 0f
            val stop = e == 0f && so == 0f && !jump
            if (!stop && s.position() == null) return "아직 움직일 수 없습니다 (주변 지형을 받는 중입니다. 잠시 뒤 다시 해 주세요)"
            s.setStick(e, so)
            s.setMovement(if (jump) 16 else 0)
            if (stop) {
                box.moveUntil = 0
            } else {
                box.moveUntil = System.currentTimeMillis() + MOVE_HOLD_MS
                moving[box] = h
            }
            return null
        }
    }

    /** 0.25초마다: 움직이는 세션의 위치를 알리고, 입력이 끊긴 세션은 멈춘다 */
    private fun moveTick() {
        try {
            val now = System.currentTimeMillis()
            for ((box, h) in moving) {
                synchronized(h) {
                    if (now > box.moveUntil) {
                        box.run?.setStick(0f, 0f)
                        box.run?.setMovement(0)
                        moving.remove(box)
                    }
                    publishPos(h, box)
                }
            }
        } catch (t: Throwable) {
            Log.w("이동 처리 오류", t)
        }
    }

    fun menuClick(uuid: String, id: String, slot: Int): Boolean {
        val h = hub(uuid)
        return synchronized(h) { h.boxes[id]?.run?.clickMenu(slot) ?: false }
    }

    fun menuClose(uuid: String, id: String) {
        val h = hub(uuid)
        synchronized(h) { h.boxes[id]?.run?.closeMenu() }
    }

    fun clearDeath(uuid: String, id: String) {
        val h = hub(uuid)
        synchronized(h) {
            val box = h.boxes[id] ?: return
            if (box.death == null) return
            box.death = null
            publish(h, "death", jsonOf("sid" to id, "death" to Wire.death(null)))
        }
    }

    /** 명령어 자동완성 후보 (최대 3초 기다림) */
    fun suggest(uuid: String, id: String, text: String): CommandSuggestions? {
        val h = hub(uuid)
        val s = synchronized(h) { h.boxes[id]?.run } ?: return null
        val latch = CountDownLatch(1)
        val out = AtomicReference<CommandSuggestions?>(null)
        if (!s.requestSuggestions(text.take(256)) { r -> out.set(r); latch.countDown() }) return null
        latch.await(3, TimeUnit.SECONDS)
        return out.get()
    }

    // ------------------------------------------------------------ 주기 작업

    private fun tick() {
        try {
            ticks++
            for (h in hubs.values) {
                synchronized(h) {
                    for (b in h.boxes.values) {
                        val id = b.entry.id
                        if (b.vitalsDirty) {
                            b.vitalsDirty = false
                            publish(h, "vitals", jsonOf("sid" to id, "vitals" to Wire.vitals(b.vitals)))
                        }
                        if (b.inventoryDirty) {
                            b.inventoryDirty = false
                            publish(h, "inventory", jsonOf("sid" to id, "inventory" to Wire.inventory(b.inventory)))
                        }
                        // 접속자 목록은 핑이 바뀔 때마다 오므로 2초에 한 번만 내보낸다
                        if (b.playersDirty && ticks % 2 == 0L) {
                            b.playersDirty = false
                            publish(h, "players", jsonOf("sid" to id).also { it.add("players", playersJson(b.players)) })
                        }
                        publishPos(h, b)
                        if (ticks % 60 == 0L && b.status.state.isActive()) {
                            b.run?.traffic()?.let { t ->
                                publish(h, "traffic", jsonOf("sid" to id, "traffic" to jsonOf("rx" to t.received, "tx" to t.sent, "onlineMs" to t.onlineMs)))
                            }
                        }
                    }
                }
            }
            placeQueued()
            if (ticks % 30 == 0L) for (uuid in presence.sweep()) presenceChanged(uuid)
            if (ticks % 5 == 0L) auth.sweepDevices()
            if (ticks % 300 == 0L) {
                store.flush()
                // 계정이 지워졌는데 남아 있는 것이 있으면 정리한다 (지우는 도중에 시작 요청이 겹친 경우)
                for (h in hubs.values) if (store.account(h.uuid) == null) dropAccount(h.uuid)
                val now = System.currentTimeMillis()
                for (acc in store.allAccounts()) {
                    if (premium.status(acc.uuid, acc.name).active) {
                        if (acc.lapsedAt != null) store.saveAccount(acc.copy(lapsedAt = null))
                        continue
                    }
                    // 프리미엄 기간이 끝난 계정: 잠수를 멈추고, 그 뒤 7일이 지나도 그대로면 저장된 로그인 정보·서버 목록을 지운다
                    stopAll(acc.uuid, "프리미엄 기간이 끝나 클라우드 잠수를 멈췄습니다")
                    when {
                        acc.lapsedAt == null -> store.saveAccount(acc.copy(lapsedAt = now))
                        now - acc.lapsedAt > LAPSED_KEEP_MS -> {
                            Log.i("프리미엄이 끝난 지 7일이 지난 계정의 정보를 지웁니다: ${acc.name}")
                            auth.deleteAccount(acc.uuid)
                            dropAccount(acc.uuid)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("주기 작업 오류", t)
        }
    }

    /**
     * 서버 컴퓨터가 켜졌을 때: 잠수 중이던 서버들에 2초 간격으로 다시 접속한다 (한꺼번에 로그인하면 모장 요청 제한에 걸림).
     * 아직 자리가 없으면(접속용 서버가 붙기 전 등) 자리를 기다리는 줄에 넣는다.
     */
    fun resumeAtBoot() {
        val list = store.runningAtBoot()
        if (list.isEmpty()) return
        Log.i("이어서 접속할 잠수 ${list.size}개")
        var delay = 1L
        for ((uuid, id) in list) {
            ticker.schedule({
                try {
                    val acc = store.account(uuid) ?: return@schedule
                    val note = "클라우드 서버가 다시 켜져서 이어서 접속합니다"
                    val h = hub(uuid)
                    synchronized(h) {
                        val box = h.boxes[id] ?: return@schedule
                        if (box.status.state.isActive()) return@schedule
                        val block = startBlock(h, acc, box, force = true)
                        when {
                            block == null -> if (!launch(h, box, note)) enqueue(h, box, System.currentTimeMillis(), WAIT_ROOM_TEXT, note)
                            block.noRoom -> enqueue(h, box, System.currentTimeMillis(), WAIT_ROOM_TEXT, note)
                            // 프리미엄 만료 등으로 시작하지 못했으면 "잠수 중" 표시를 내린다
                            else -> mark(h, box, false)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("이어서 접속 실패", t)
                }
            }, delay, TimeUnit.SECONDS)
            delay += 2
        }
    }

    /** 서버 프로그램을 끌 때: 접속을 정리한다. "잠수 중" 표시는 그대로 두어 다시 켜면 이어서 접속한다 */
    fun shutdown() {
        shuttingDown = true
        ticker.shutdownNow()
        for (h in hubs.values) {
            synchronized(h) {
                for (s in h.subs) s.close()
                for (b in h.boxes.values) b.run?.shutdown()
            }
        }
        store.flush()
    }

    companion object {
        private const val MAX_LINES = 1500
        private const val MAX_SUBS = 8
        /** 채팅 줄 하나(JSON)의 최대 크기. 넘으면 앞부분만 남긴다 */
        private const val MAX_LINE_CHARS = 24_000
        /** 이벤트 하나(JSON)의 최대 크기. 넘으면 내보내지 않는다 */
        private const val MAX_EVENT_CHARS = 600_000
        private const val MAX_PLAYERS_SENT = 500
        /** 이동 입력 하나가 유효한 시간 (화면은 누르고 있는 동안 0.5초마다 다시 보낸다) */
        private const val MOVE_HOLD_MS = 1500L
        /** 프리미엄이 끝난 뒤 로그인 정보를 남겨 두는 기간 (그 안에 다시 결제하면 그대로 이어 쓴다) */
        private const val LAPSED_KEEP_MS = 7L * 86_400_000
        /** 다른 클라이언트(PC 게임)에 밀려난 뒤 이 시간 동안은 "다른 곳에서 접속 중일 수 있음"으로 표시하고 확인을 받는다 */
        const val OTHER_HOLD_MS = 30 * 60_000L
        private const val FULL_TEXT = "지금 클라우드 서버가 가득 찼습니다. 잠시 뒤 다시 시도해 주세요"
        private const val WAIT_ROOM_TEXT = "접속할 자리를 기다리는 중…"

        // 아래 시간 값은 시험에서 줄일 수 있게 실행 옵션(-Djamsu.…)으로도 받는다
        /** 접속용 서버 연결이 끊긴 뒤 다른 곳에 다시 맡기기까지 기다리는 시간 (그쪽이 잠수를 끊을 시간) */
        private val REPLACE_DELAY_MS = java.lang.Long.getLong("jamsu.replaceDelayMs", 30_000L)
        /** 자리가 없을 때 다시 알아보는 간격, 이만큼 기다려도 없으면 멈춰 둔다 */
        private val QUEUE_RETRY_MS = java.lang.Long.getLong("jamsu.queueRetryMs", 10_000L)
        private val QUEUE_MAX_MS = java.lang.Long.getLong("jamsu.queueMaxMs", 600_000L)
        /** 기다리던 잠수를 시작하는 간격 */
        private const val PLACE_GAP_MS = 2_000L
    }
}
