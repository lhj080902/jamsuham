package kr.jamsuham.cloud

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.jamsuham.core.cloud.CloudEntry
import kr.jamsuham.core.cloud.Wire
import kr.jamsuham.core.cloud.arr
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 계정 하나 = 마인크래프트 계정 하나. [auth] 는 암호화한 마이크로소프트 로그인 정보 */
data class AccountRec(
    val uuid: String,
    val name: String,
    val createdAt: Long,
    val lastLoginAt: Long,
    val auth: String?,
    /** 시험용 닉네임 로그인 계정 */
    val dev: Boolean = false,
)

/** 로그인한 기기(브라우저·앱) 하나. 토큰 자체는 저장하지 않고 해시만 둔다 */
data class TokenRec(
    val hash: String,
    val uuid: String,
    /** web / app */
    val kind: String,
    /** "웹 · Chrome", "앱 · SM-S911N" 같은 표시 이름 */
    val label: String,
    val createdAt: Long,
    @Volatile var lastSeenAt: Long,
)

/** 한 계정의 서버 목록과 "잠수 중이어야 하는 서버" (서버 컴퓨터가 다시 켜지면 이어서 접속) */
class ServerFile(val entries: LinkedHashMap<String, CloudEntry> = LinkedHashMap(), val running: LinkedHashSet<String> = LinkedHashSet())

/**
 * 디스크 저장소. JSON 파일 몇 개로 충분한 규모다 (계정 수백 개).
 *   accounts/<uuid>.json  계정 + 암호화한 로그인 정보
 *   servers/<uuid>.json   서버 목록 + 잠수 중 표시
 *   tokens.json           로그인한 기기들 (토큰 해시)
 */
class DataStore(private val dir: File) {
    private val gson = Gson()
    private val accountsDir = File(dir, "accounts").apply { mkdirs() }
    private val serversDir = File(dir, "servers").apply { mkdirs() }
    private val tokensFile = File(dir, "tokens.json")

    private val accounts = ConcurrentHashMap<String, AccountRec>()
    private val tokens = ConcurrentHashMap<String, TokenRec>()
    private val servers = ConcurrentHashMap<String, ServerFile>()
    @Volatile private var tokensDirty = false

    init {
        accountsDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val a = gson.fromJson(f.readText(), AccountRec::class.java)
                if (a?.uuid != null) accounts[a.uuid] = a
            } catch (t: Throwable) {
                Log.w("계정 파일을 읽지 못했습니다: ${f.name}", t)
            }
        }
        if (tokensFile.exists()) {
            try {
                for (el in JsonParser.parseString(tokensFile.readText()).asJsonArray) {
                    val t = gson.fromJson(el, TokenRec::class.java)
                    if (t?.hash != null && accounts.containsKey(t.uuid)) tokens[t.hash] = t
                }
            } catch (t: Throwable) {
                Log.w("tokens.json 을 읽지 못했습니다", t)
            }
        }
    }

    // ------------------------------------------------------------ 계정

    fun account(uuid: String): AccountRec? = accounts[uuid]

    fun accountCount(): Int = accounts.size

    fun allAccounts(): List<AccountRec> = accounts.values.toList()

    fun saveAccount(a: AccountRec) {
        accounts[a.uuid] = a
        File(accountsDir, "${a.uuid}.json").writeAtomic(gson.toJson(a))
    }

    /** 계정과 그 계정의 모든 것(로그인 정보·서버 목록·기기)을 지운다 */
    fun deleteAccount(uuid: String) {
        accounts.remove(uuid)
        File(accountsDir, "$uuid.json").delete()
        servers.remove(uuid)
        File(serversDir, "$uuid.json").delete()
        if (tokens.values.removeIf { it.uuid == uuid }) saveTokensNow()
    }

    // ------------------------------------------------------------ 기기(토큰)

    /** 새 토큰을 만들어 돌려준다 (원문은 이때 한 번만 알 수 있다) */
    fun issueToken(uuid: String, kind: String, label: String): String {
        val token = randomToken()
        val now = System.currentTimeMillis()
        tokens[sha256Hex(token)] = TokenRec(sha256Hex(token), uuid, kind, label.take(60), now, now)
        // 기기를 너무 많이 쌓아 두지 않는다 (계정당 가장 최근 10개)
        val mine = tokens.values.filter { it.uuid == uuid }.sortedByDescending { it.lastSeenAt }
        for (old in mine.drop(10)) tokens.remove(old.hash)
        saveTokensNow()
        return token
    }

    fun findToken(token: String): TokenRec? {
        val t = tokens[sha256Hex(token)] ?: return null
        val now = System.currentTimeMillis()
        // 90일 동안 안 쓴 기기는 로그아웃 처리
        if (now - t.lastSeenAt > 90L * 86_400_000) {
            tokens.remove(t.hash)
            tokensDirty = true
            return null
        }
        if (now - t.lastSeenAt > 600_000) {
            t.lastSeenAt = now
            tokensDirty = true
        }
        return t
    }

    fun revokeToken(hash: String) {
        if (tokens.remove(hash) != null) saveTokensNow()
    }

    fun tokensOf(uuid: String): List<TokenRec> = tokens.values.filter { it.uuid == uuid }

    @Synchronized
    private fun saveTokensNow() {
        tokensDirty = false
        tokensFile.writeAtomic(gson.toJson(tokens.values.toList()))
    }

    /** 마지막 사용 시각 같은 자잘한 변경은 가끔 모아서 저장한다 */
    fun flush() {
        if (tokensDirty) saveTokensNow()
    }

    // ------------------------------------------------------------ 서버 목록

    fun servers(uuid: String): ServerFile = servers.getOrPut(uuid) {
        val f = File(serversDir, "$uuid.json")
        val sf = ServerFile()
        if (f.exists()) {
            try {
                val o = JsonParser.parseString(f.readText()).asJsonObject
                for (el in o.arr("entries")) {
                    val e = (el as? JsonObject)?.let { Wire.parseEntry(it) } ?: continue
                    sf.entries[e.id] = e
                }
                for (el in o.arr("running")) if (el.isJsonPrimitive) sf.running.add(el.asString)
            } catch (t: Throwable) {
                Log.w("서버 목록 파일을 읽지 못했습니다: ${f.name}", t)
            }
        }
        sf
    }

    fun saveServers(uuid: String) {
        val sf = servers[uuid] ?: return
        val o = JsonObject()
        val entries = JsonArray()
        val running = JsonArray()
        synchronized(sf) {
            for (e in sf.entries.values) entries.add(Wire.entry(e))
            for (r in sf.running) running.add(r)
        }
        o.add("entries", entries)
        o.add("running", running)
        File(serversDir, "$uuid.json").writeAtomic(o.toString())
    }

    /** 서버 컴퓨터가 켜질 때: 잠수 중이던 (계정, 서버) 들 */
    fun runningAtBoot(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (a in accounts.values) {
            val sf = servers(a.uuid)
            for (id in sf.running) if (sf.entries.containsKey(id)) out.add(a.uuid to id)
        }
        return out
    }
}

/**
 * 누가 클라우드 잠수를 쓸 수 있는지 (유료 사용자 목록). data/premium.txt 를 고치면 서버를 끄지 않아도 바로 반영된다.
 * 한 줄에 한 명:  <닉네임 또는 UUID> [만료일 YYYY-MM-DD]   (# 뒤는 메모)
 */
class Premium(private val config: Config) {
    private val file = File(config.dataDir, "premium.txt")
    private data class Row(val key: String, val untilMs: Long?)

    @Volatile private var rows: List<Row> = emptyList()
    @Volatile private var loadedAt = -1L

    init {
        if (!file.exists()) {
            config.dataDir.mkdirs()
            file.writeText(
                """
                |# 클라우드 잠수를 쓸 수 있는 계정 목록 (cloud.properties 의 premiumMode=allowlist 일 때)
                |# 한 줄에 한 명: 마인크래프트 닉네임 또는 UUID, 그 뒤에 만료일(선택)
                |# 이 파일은 저장하면 바로 반영됩니다 (서버를 다시 켤 필요 없음)
                |#
                |# 예)
                |# Steve                      # 기한 없음
                |# Alex 2027-01-31            # 2027년 1월 31일까지
                |# 069a79f4-44e9-4726-a5be-fca90e38aaf5 2026-12-31
                |
                """.trimMargin(),
            )
        }
    }

    private fun reload() {
        val m = file.lastModified()
        if (m == loadedAt) return
        val out = ArrayList<Row>()
        try {
            for (raw in file.readText().removePrefix("\uFEFF").lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                val until = parts.getOrNull(1)?.let { d ->
                    try {
                        // 만료일 당일 끝까지 쓸 수 있게 다음 날 0시
                        java.time.LocalDate.parse(d).plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        Log.w("premium.txt: 날짜를 읽지 못했습니다 (YYYY-MM-DD 형식): $raw")
                        0L
                    }
                }
                out.add(Row(parts[0].lowercase().replace("-", ""), until))
            }
        } catch (t: Throwable) {
            Log.w("premium.txt 를 읽지 못했습니다", t)
        }
        rows = out
        loadedAt = m
    }

    data class Status(val active: Boolean, val untilMs: Long?)

    fun status(uuid: String, name: String): Status {
        if (config.premiumMode == "open") return Status(true, null)
        reload()
        val keys = setOf(uuid.lowercase().replace("-", ""), name.lowercase())
        val now = System.currentTimeMillis()
        var best: Status? = null
        for (r in rows) {
            if (r.key !in keys) continue
            if (r.untilMs == null) return Status(true, null)
            if (r.untilMs > now && (best == null || (best.untilMs ?: 0) < r.untilMs)) best = Status(true, r.untilMs)
        }
        return best ?: Status(false, null)
    }

    /** 명령줄 도구용: 줄을 추가하거나(같은 사람의 예전 줄은 지움) 지운다 */
    fun edit(who: String, days: Int?, remove: Boolean) {
        val key = who.lowercase().replace("-", "")
        val kept = file.readText().removePrefix("\uFEFF").lines().dropLastWhile { it.isEmpty() }.filter { raw ->
            val first = raw.substringBefore('#').trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            first.lowercase().replace("-", "") != key || first.isEmpty()
        }.toMutableList()
        if (!remove) {
            val until = days?.let { java.time.LocalDate.now().plusDays(it.toLong()).toString() }
            kept.add(if (until != null) "$who $until" else who)
        }
        file.writeAtomic(kept.joinToString("\n") + "\n")
    }
}
