package kr.jamsuham.core.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kr.jamsuham.core.AutoClickRule
import kr.jamsuham.core.DeathInfo
import kr.jamsuham.core.Inventory
import kr.jamsuham.core.LogLine
import kr.jamsuham.core.Menu
import kr.jamsuham.core.MenuItem
import kr.jamsuham.core.PlayerInfo
import kr.jamsuham.core.PlayerList
import kr.jamsuham.core.SessionConfig
import kr.jamsuham.core.SessionState
import kr.jamsuham.core.SessionStatus
import kr.jamsuham.core.Vitals
import kr.jamsuham.core.text.ChatClick
import kr.jamsuham.core.text.ChatSpan
import kr.jamsuham.core.text.ChatText
import java.util.UUID

/**
 * 클라우드에 올려 두는 서버 하나의 설정 (앱의 서버 설정에서 잠수에 필요한 것만).
 * 앱·웹·클라우드 서버가 같은 모양을 쓴다.
 */
data class CloudEntry(
    val id: String,
    val name: String = "",
    val address: String = "",
    val fixedVersion: String? = null,
    val joinCommands: List<String> = emptyList(),
    val joinDelaySec: Int = 3,
    val joinIntervalSec: Int = 3,
    val autoReconnect: Boolean = true,
    val reconnectOnKick: Boolean = true,
    val autoClickRules: List<String> = emptyList(),
    val walkOnJoin: Boolean = true,
) {
    fun sessionConfig() = SessionConfig(
        address = address,
        fixedVersion = fixedVersion,
        autoReconnect = autoReconnect,
        reconnectOnKick = reconnectOnKick,
        joinCommands = joinCommands.map { it.trim() }.filter { it.isNotEmpty() }.take(10),
        joinCommandDelaySec = joinDelaySec.coerceIn(0, 120),
        joinCommandIntervalSec = joinIntervalSec.coerceIn(0, 120),
        autoClickRules = autoClickRules.mapNotNull { AutoClickRule.parse(it) },
        walkOnJoin = walkOnJoin,
    )
}

/**
 * 클라우드 서버 ↔ 앱·웹이 주고받는 JSON 모양. 서버는 여기 함수로 만들고 앱은 여기 함수로 읽으므로
 * 한쪽만 바뀌어 어긋나는 일이 없다 (웹의 app.js 도 같은 키를 읽는다 — 키를 바꾸면 함께 고칠 것).
 */
object Wire {
    /** 주고받는 형식 번호. 호환되지 않게 바꾸면 올린다 (앱이 서버보다 새 번호면 "서버를 업데이트해 주세요") */
    const val API = 1

    /** 서버 주소를 비교용으로 정리한다 (대소문자·기본 포트·끝의 점 무시) */
    fun addressKey(address: String): String {
        var a = address.trim().lowercase()
        if (a.endsWith(":25565")) a = a.removeSuffix(":25565")
        return a.removeSuffix(".")
    }

    // ------------------------------------------------------------ 글자

    fun text(t: ChatText): JsonArray {
        val arr = JsonArray()
        for (s in t.spans) {
            val o = JsonObject()
            o.addProperty("t", s.text)
            s.color?.let { o.addProperty("c", it) }
            if (s.bold) o.addProperty("b", 1)
            if (s.italic) o.addProperty("i", 1)
            s.click?.let { c ->
                val ck = JsonObject()
                ck.addProperty("a", c.action.name)
                ck.addProperty("v", c.value)
                o.add("ck", ck)
            }
            s.hover?.let { o.addProperty("h", it) }
            s.glyphs?.let { o.addProperty("g", it) }
            s.font?.let { o.addProperty("f", it) }
            arr.add(o)
        }
        return arr
    }

    fun parseText(e: JsonElement?): ChatText {
        if (e == null || !e.isJsonArray) return ChatText(emptyList())
        val out = ArrayList<ChatSpan>()
        for (el in e.asJsonArray) {
            val o = el as? JsonObject ?: continue
            val click = o.obj("ck")?.let { ck ->
                val action = try {
                    ChatClick.Action.valueOf(ck.str("a") ?: "")
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (action != null) ChatClick(action, ck.str("v") ?: "") else null
            }
            out.add(
                ChatSpan(
                    text = o.str("t") ?: "",
                    color = o.int("c"),
                    bold = o.int("b") == 1,
                    italic = o.int("i") == 1,
                    click = click,
                    hover = o.str("h"),
                    glyphs = o.str("g"),
                    font = o.str("f"),
                ),
            )
        }
        return ChatText(out)
    }

    // ------------------------------------------------------------ 상태

    fun status(s: SessionStatus): JsonObject {
        val o = JsonObject()
        o.addProperty("state", s.state.name)
        o.addProperty("message", s.message)
        s.serverVersion?.let { o.addProperty("serverVersion", it) }
        s.onlineSinceMs?.let { o.addProperty("onlineSinceMs", it) }
        s.nextAttemptAtMs?.let { o.addProperty("nextAttemptAtMs", it) }
        o.addProperty("reconnectCount", s.reconnectCount)
        o.addProperty("totalOnlineMs", s.totalOnlineMs)
        return o
    }

    fun parseStatus(o: JsonObject?): SessionStatus {
        if (o == null) return SessionStatus(SessionState.IDLE)
        val state = try {
            SessionState.valueOf(o.str("state") ?: "IDLE")
        } catch (_: IllegalArgumentException) {
            SessionState.IDLE
        }
        return SessionStatus(
            state = state,
            message = o.str("message") ?: "",
            serverVersion = o.str("serverVersion"),
            onlineSinceMs = o.long("onlineSinceMs"),
            nextAttemptAtMs = o.long("nextAttemptAtMs"),
            reconnectCount = o.int("reconnectCount") ?: 0,
            totalOnlineMs = o.long("totalOnlineMs") ?: 0L,
        )
    }

    fun line(l: LogLine, seq: Long): JsonObject {
        val o = JsonObject()
        o.addProperty("seq", seq)
        o.addProperty("t", l.timeMs)
        o.addProperty("kind", l.kind.name)
        o.add("spans", text(l.text))
        return o
    }

    /** (순번, 줄). 순번은 계정 안에서 계속 커지는 값이라 이어 받기·중복 거르기에 쓴다 */
    fun parseLine(o: JsonObject): Pair<Long, LogLine> {
        val kind = try {
            LogLine.Kind.valueOf(o.str("kind") ?: "CHAT")
        } catch (_: IllegalArgumentException) {
            LogLine.Kind.CHAT
        }
        return (o.long("seq") ?: 0L) to LogLine(o.long("t") ?: System.currentTimeMillis(), kind, parseText(o.get("spans")))
    }

    fun players(p: PlayerList): JsonObject {
        val o = JsonObject()
        val arr = JsonArray()
        for (pl in p.players) {
            val j = JsonObject()
            j.addProperty("u", pl.uuid.toString())
            j.addProperty("n", pl.name)
            pl.displayName?.let { j.add("d", text(it)) }
            j.addProperty("p", pl.latencyMs)
            j.addProperty("g", pl.gameMode)
            pl.skinUrl?.let { j.addProperty("s", it) }
            j.addProperty("o", pl.order)
            arr.add(j)
        }
        o.add("players", arr)
        o.add("header", text(p.header))
        o.add("footer", text(p.footer))
        return o
    }

    fun parsePlayers(o: JsonObject?): PlayerList {
        if (o == null) return PlayerList.EMPTY
        val list = ArrayList<PlayerInfo>()
        for (el in o.arr("players")) {
            val j = el as? JsonObject ?: continue
            val uuid = try {
                UUID.fromString(j.str("u"))
            } catch (_: Exception) {
                continue
            }
            list.add(
                PlayerInfo(
                    uuid = uuid,
                    name = j.str("n") ?: "",
                    displayName = if (j.has("d")) parseText(j.get("d")) else null,
                    latencyMs = j.int("p") ?: 0,
                    gameMode = j.int("g") ?: 0,
                    skinUrl = j.str("s"),
                    order = j.int("o") ?: 0,
                ),
            )
        }
        return PlayerList(list, parseText(o.get("header")), parseText(o.get("footer")))
    }

    fun vitals(v: Vitals?): JsonElement {
        if (v == null) return JsonNull.INSTANCE
        // 숫자가 아닌 값(NaN·무한대)은 JSON 으로 쓸 수 없다 (받는 쪽 화면이 통째로 멈춘다)
        fun f(x: Float) = if (x.isFinite()) x else 0f
        val o = JsonObject()
        o.addProperty("h", f(v.health))
        o.addProperty("m", f(v.maxHealth).let { if (it > 0f) it else 20f })
        o.addProperty("a", f(v.absorption))
        o.addProperty("f", v.food)
        o.addProperty("s", f(v.saturation))
        return o
    }

    fun parseVitals(e: JsonElement?): Vitals? {
        val o = e as? JsonObject ?: return null
        return Vitals(
            health = o.float("h") ?: 0f,
            maxHealth = o.float("m") ?: 20f,
            absorption = o.float("a") ?: 0f,
            food = o.int("f") ?: 20,
            saturation = o.float("s") ?: 0f,
        )
    }

    private fun item(i: MenuItem): JsonObject {
        val o = JsonObject()
        o.addProperty("slot", i.slot)
        o.addProperty("id", i.itemId)
        o.addProperty("count", i.count)
        o.add("name", text(i.name))
        val lore = JsonArray()
        for (l in i.lore) lore.add(text(l))
        o.add("lore", lore)
        return o
    }

    private fun parseItem(o: JsonObject): MenuItem = MenuItem(
        slot = o.int("slot") ?: 0,
        itemId = o.str("id") ?: "",
        count = o.int("count") ?: 1,
        name = parseText(o.get("name")),
        lore = o.arr("lore").map { parseText(it) },
    )

    fun menu(m: Menu?): JsonElement {
        if (m == null) return JsonNull.INSTANCE
        val o = JsonObject()
        o.addProperty("w", m.windowId)
        o.add("title", text(m.title))
        o.addProperty("slots", m.containerSlots)
        val items = JsonArray()
        for (i in m.items) items.add(item(i))
        o.add("items", items)
        return o
    }

    fun parseMenu(e: JsonElement?): Menu? {
        val o = e as? JsonObject ?: return null
        return Menu(
            windowId = o.int("w") ?: 0,
            title = parseText(o.get("title")),
            containerSlots = o.int("slots") ?: 0,
            items = o.arr("items").mapNotNull { (it as? JsonObject)?.let(::parseItem) },
        )
    }

    fun inventory(inv: Inventory): JsonObject {
        val o = JsonObject()
        val items = JsonArray()
        for (i in inv.slots) items.add(item(i))
        o.add("slots", items)
        o.addProperty("selected", inv.selectedHotbar)
        return o
    }

    fun parseInventory(o: JsonObject?): Inventory {
        if (o == null) return Inventory.EMPTY
        return Inventory(o.arr("slots").mapNotNull { (it as? JsonObject)?.let(::parseItem) }, o.int("selected") ?: 0)
    }

    fun death(d: DeathInfo?): JsonElement {
        if (d == null) return JsonNull.INSTANCE
        val o = JsonObject()
        o.addProperty("at", d.atMs)
        d.killer?.let { o.addProperty("killer", it) }
        d.cause?.let { o.addProperty("cause", it) }
        d.weapon?.let { o.addProperty("weapon", it) }
        d.message?.let { o.addProperty("message", it) }
        return o
    }

    fun parseDeath(e: JsonElement?): DeathInfo? {
        val o = e as? JsonObject ?: return null
        return DeathInfo(o.long("at") ?: 0L, o.str("killer"), o.str("cause"), o.str("weapon"), o.str("message"))
    }

    /** 내 캐릭터 위치 (클라우드 잠수에서 이동할 때 화면에 좌표를 보여 주려고) */
    data class Pos(val x: Double, val y: Double, val z: Double, val world: String)

    fun pos(p: Pos?): JsonElement {
        if (p == null) return JsonNull.INSTANCE
        fun r(v: Double) = if (v.isFinite()) Math.round(v * 10) / 10.0 else 0.0
        val o = JsonObject()
        o.addProperty("x", r(p.x))
        o.addProperty("y", r(p.y))
        o.addProperty("z", r(p.z))
        o.addProperty("world", p.world.take(100))
        return o
    }

    fun parsePos(e: JsonElement?): Pos? {
        val o = e as? JsonObject ?: return null
        fun d(k: String) = o.get(k)?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asDouble else null } ?: 0.0
        return Pos(d("x"), d("y"), d("z"), o.str("world") ?: "")
    }

    // ------------------------------------------------------------ 서버 설정

    fun entry(e: CloudEntry): JsonObject {
        val o = JsonObject()
        o.addProperty("id", e.id)
        o.addProperty("name", e.name)
        o.addProperty("address", e.address)
        e.fixedVersion?.let { o.addProperty("fixedVersion", it) }
        o.add("joinCommands", strings(e.joinCommands))
        o.addProperty("joinDelaySec", e.joinDelaySec)
        o.addProperty("joinIntervalSec", e.joinIntervalSec)
        o.addProperty("autoReconnect", e.autoReconnect)
        o.addProperty("reconnectOnKick", e.reconnectOnKick)
        o.add("autoClickRules", strings(e.autoClickRules))
        o.addProperty("walkOnJoin", e.walkOnJoin)
        return o
    }

    /** 사용자가 보낸 값이므로 길이·개수를 잘라서 읽는다. id 가 없으면 [fallbackId] */
    fun parseEntry(o: JsonObject, fallbackId: String? = null): CloudEntry? {
        val id = (o.str("id") ?: fallbackId)?.takeIf { ID_REGEX.matches(it) } ?: return null
        return CloudEntry(
            id = id,
            name = (o.str("name") ?: "").trim().take(60),
            address = (o.str("address") ?: "").trim().take(255),
            fixedVersion = o.str("fixedVersion")?.trim()?.take(20)?.ifEmpty { null },
            joinCommands = o.arr("joinCommands").mapNotNull { it.strOrNull()?.take(256) }.filter { it.isNotBlank() }.take(10),
            joinDelaySec = (o.int("joinDelaySec") ?: 3).coerceIn(0, 120),
            joinIntervalSec = (o.int("joinIntervalSec") ?: 3).coerceIn(0, 120),
            autoReconnect = o.bool("autoReconnect") ?: true,
            reconnectOnKick = o.bool("reconnectOnKick") ?: true,
            autoClickRules = o.arr("autoClickRules").mapNotNull { it.strOrNull()?.take(200) }.filter { it.isNotBlank() }.take(20),
            walkOnJoin = o.bool("walkOnJoin") ?: true,
        )
    }

    val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    private fun strings(list: List<String>): JsonArray {
        val a = JsonArray()
        for (s in list) a.add(s)
        return a
    }
}

// ------------------------------------------------------------ JSON 읽기 도우미 (틀린 모양이 와도 예외 없이 null)

fun JsonObject.str(key: String): String? = get(key)?.strOrNull()
fun JsonObject.int(key: String): Int? = get(key)?.let { e -> if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else null }
fun JsonObject.long(key: String): Long? = get(key)?.let { e -> if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asLong else null }
fun JsonObject.float(key: String): Float? = get(key)?.let { e -> if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asFloat else null }
fun JsonObject.bool(key: String): Boolean? = get(key)?.let { e -> if (e.isJsonPrimitive && e.asJsonPrimitive.isBoolean) e.asBoolean else null }
fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
fun JsonObject.arr(key: String): List<JsonElement> = (get(key) as? JsonArray)?.toList() ?: emptyList()
fun JsonElement.strOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
