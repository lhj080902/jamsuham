import kr.jamsuham.core.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class Rec : SessionListener {
    val vitals = CopyOnWriteArrayList<Vitals?>()
    val deaths = CopyOnWriteArrayList<DeathInfo>()
    override fun onVitals(vitals: Vitals?) { this.vitals += vitals; println("${ts()} [체력] $vitals") }
    override fun onDeath(death: DeathInfo) { deaths += death; println("${ts()} [사망] $death / ${death.describe()}") }
    val states = CopyOnWriteArrayList<SessionStatus>()
    val lines = CopyOnWriteArrayList<String>()
    val t0 = System.currentTimeMillis()
    fun ts() = "%5.1fs".format((System.currentTimeMillis() - t0) / 1000.0)
    override fun onStatus(status: SessionStatus) { states += status; println("${ts()} [상태] ${status.state} ${status.message}") }
    override fun onLine(line: LogLine) { lines += line.text.plain; println("${ts()} [줄] ${line.kind} ${line.text.plain}") }
    override fun onMenu(menu: Menu?) { }
}


/** 시험용 임시 폴더 (캐시·데이터). 환경 변수 HARNESS_TMP 로 바꿀 수 있음 */
val TMP: String = System.getenv("HARNESS_TMP") ?: (System.getProperty("java.io.tmpdir") + "/jamsuham-harness")

fun session(addr: String, rec: Rec) = AfkSession(File("" + TMP + "/data"),
    SessionConfig(addr), { JoinCredentials.offline("Tester") }, rec, log = CoreLog { _, _, _ -> }, dns = null)

fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
    return cond()
}

fun main(args: Array<String>) {
    val mode = args[0]
    if (System.getenv("EN") == "1") kr.jamsuham.core.Lang.english = true
    val rec = Rec()
    var ok: Boolean
    when (mode) {
        "dupe" -> {
            val s = session("127.0.0.1:${args[1]}", rec); s.start()
            ok = waitFor(60_000) { rec.states.lastOrNull()?.state == SessionState.STOPPED }
            Thread.sleep(15_000) // 재접속을 시도하지 않는지 지켜봄
            val joins = rec.lines.count { it == "서버에 들어갔습니다" }
            ok = ok && joins == 1 && rec.states.last().state == SessionState.STOPPED && rec.lines.any { it.contains("다른 곳(PC 등)") }
            println("RESULT dupe joins=$joins ok=$ok final=${rec.states.last().state} lines=${rec.lines.takeLast(4)}")
        }
        "authkick" -> {
            val s = session("127.0.0.1:${args[1]}", rec); s.start()
            ok = waitFor(150_000) { rec.states.lastOrNull()?.state == SessionState.STOPPED }
            val retries = rec.lines.count { it.contains("정품 인증에 실패했습니다") }
            ok = ok && retries == 2 && rec.lines.any { it.contains("3번 이어져서 멈춥니다") }
            println("RESULT authkick retries=$retries ok=$ok")
        }
        "full" -> {
            val s = session("127.0.0.1:${args[1]}", rec); s.start()
            ok = waitFor(150_000) { rec.states.lastOrNull()?.state == SessionState.STOPPED }
            val joins = rec.lines.count { it == "서버에 들어갔습니다" }
            ok = ok && joins == 3 && rec.lines.any { it.contains("차단·화이트리스트") }
            println("RESULT full joins=$joins ok=$ok")
        }
        "stop" -> {
            // 응답 없는 주소(블랙홀) → 핑이 6초 기다리는 중에 끊기
            val s = session(args.getOrElse(1) { "10.255.255.1:25565" }, rec); s.start()
            waitFor(10_000) { rec.states.any { it.message.contains("서버 확인") } }
            Thread.sleep(500)
            val t = System.currentTimeMillis(); s.stop()
            ok = waitFor(10_000) { rec.states.lastOrNull()?.state == SessionState.STOPPED }
            val ms = System.currentTimeMillis() - t
            Thread.sleep(9_000) // 늦게 끝난 핑이 상태를 덮어쓰지 않는지
            ok = ok && ms < 500 && rec.states.last().state == SessionState.STOPPED
            println("RESULT stop latencyMs=$ms final=${rec.states.last().state} ok=$ok")
        }
        "backoff" -> {
            val k = CloseReason.Kicked(kr.jamsuham.core.text.ChatText.of("Server is full"), false)
            val a = CloseReason.Kicked(kr.jamsuham.core.text.ChatText.of("You are already connected to this proxy!"), false)
            val l = CloseReason.Lost("x")
            val d = kr.jamsuham.core.text.ChatText.of("다른 곳에서 같은 계정으로 접속했습니다")
            println("kick60  " + (1..12).map { AfkSession.computeBackoff(k, it, 60) })
            println("kick300 " + (1..12).map { AfkSession.computeBackoff(k, it, 300) })
            println("lost300 " + (1..10).map { AfkSession.computeBackoff(l, it, 300) })
            println("already " + (1..10).map { AfkSession.computeBackoff(a, it, 60) })
            println("dupe=${AfkSession.isDuplicateLogin(d.plain)} alreadyOnDupe=${AfkSession.isAlreadyConnected(CloseReason.Kicked(d,false))} " +
                "permBan=${AfkSession.isPermanentKick("You are banned")} permAuth=${AfkSession.isPermanentKick("Failed to verify username!")} auth=${AfkSession.isAuthKick("Failed to verify username!")}")
            ok = true
        }
        "miny" -> {
            val s = session("127.0.0.1:${args[1]}", rec); s.start()
            waitFor(30_000) { rec.states.lastOrNull()?.state == SessionState.ONLINE }
            Thread.sleep(3000)
            val w = s.liveConnection?.world
            val f = s.liveConnection!!.javaClass.getDeclaredField("dimensionMinY").apply { isAccessible = true }
            println("REG dimensionMinY=${f.get(s.liveConnection)}")
            println("RESULT miny minY=${w?.minY} creds=${JoinCredentials("A", java.util.UUID.randomUUID(), "SECRET-TOKEN")}")
            ok = w?.minY == -64
            s.stop(); Thread.sleep(500)
        }
        "report" -> {
            val set = kr.jamsuham.core.pack.PackFonts.load("http://127.0.0.1:${args[1]}/x.zip", null, File("" + TMP + "/packcache-rep-" + System.nanoTime()), (System.getenv("MAXFULL") ?: "0").toLong())
            println("REPORT glyphs=${set.glyphCount}\n${set.report}")
            ok = set.report != null
            println("RESULT report ok=$ok")
        }
        "stick" -> {
            val s = AfkSession(File("" + TMP + "/data"), SessionConfig("127.0.0.1:${args[1]}", walkOnJoin = false), { JoinCredentials.offline("Tester") }, rec, log = CoreLog { _, _, _ -> }, dns = null)
            s.start()
            fun pos(n: String) = s.liveConnection?.let { c -> c.javaClass.getDeclaredField(n).apply { isAccessible = true }.getDouble(c) } ?: Double.NaN
            waitFor(15_000) { s.liveConnection?.canWalk == true }
            Thread.sleep(300)
            val x0 = pos("x"); val z0 = pos("z")
            s.setStick(0.7071f, -0.7071f) // 북동
            Thread.sleep(1000)
            s.setStick(0f, 0f)
            Thread.sleep(300)
            val x1 = pos("x"); val z1 = pos("z")
            Thread.sleep(500)
            val x2 = pos("x")
            println("RESULT stick dx=${x1 - x0} dz=${z1 - z0} afterStop=${x2 - x1}")
            ok = x1 - x0 > 2.0 && z0 - z1 > 2.0 && kotlin.math.abs((x1 - x0) - (z0 - z1)) < 0.5 && x2 == x1
            s.stop(); Thread.sleep(500)
        }
        "png" -> {
            // 정상 PNG 를 만들고 → CRC·adler32 를 망가뜨림 → 고친 뒤 픽셀이 같은지
            val img = java.awt.image.BufferedImage(16, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 16) for (y in 0 until 8) img.setRGB(x, y, if (x < 6) 0xFFFF0000.toInt() else 0x400000FF)
            val good = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
            val bad = good.copyOf()
            // 모든 조각의 CRC 를 뒤집고, IDAT 끝 4바이트(adler32)도 뒤집는다
            var p = 8
            while (p + 8 <= bad.size) {
                val len = java.nio.ByteBuffer.wrap(bad, p, 4).int
                val type = String(bad, p + 4, 4)
                if (type == "IDAT") for (i in 1..4) bad[p + 8 + len - i] = (bad[p + 8 + len - i].toInt() xor 0x5A).toByte()
                for (i in 0 until 4) bad[p + 8 + len + i] = (bad[p + 8 + len + i].toInt() xor 0xFF).toByte()
                p += 12 + len
            }
            val badReads = runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bad)) }.getOrNull() != null
            val fixed = kr.jamsuham.core.pack.PackFonts.repairPng(bad)
            val back = fixed?.let { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(it)) }
            val same = back != null && (0 until 16).all { x -> (0 until 8).all { y -> back.getRGB(x, y) == img.getRGB(x, y) } }
            // 고친 PNG 의 CRC 가 모두 맞는지 직접 검사 (안드로이드 libpng 처럼)
            var crcOk = fixed != null
            if (fixed != null) { var q = 8; while (q + 8 <= fixed.size) { val len = java.nio.ByteBuffer.wrap(fixed, q, 4).int; val c = java.util.zip.CRC32().apply { update(fixed, q + 4, 4 + len) }.value.toInt(); if (c != java.nio.ByteBuffer.wrap(fixed, q + 8 + len, 4).int) crcOk = false; q += 12 + len } }
            val goodToo = kr.jamsuham.core.pack.PackFonts.repairPng(good)?.let { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(it)) } != null
            println("RESULT png badReadsBefore=$badReads fixedSame=$same crcOk=$crcOk goodStillOk=$goodToo")
            ok = same && crcOk && goodToo
        }
        "msa" -> {
            val c = net.raphimc.minecraftauth.msa.model.MsaApplicationConfig("12345678-1234-1234-1234-123456789abc", net.raphimc.minecraftauth.msa.data.MsaConstants.SCOPE_OFFLINE_ACCESS, null, null, net.raphimc.minecraftauth.msa.data.MsaEnvironment.MICROSOFT_ONLINE_CONSUMERS)
            println("URL " + c.environment.authorizeUrl + " params=" + c.authCodeParameters + " redirect=" + (c.redirectUri ?: c.environment.nativeClientUrl) + " title=" + c.isTitleClientId)
            ok = !c.isTitleClientId
        }
        "prune" -> {
            val dir = File("" + TMP + "/packcache-prune-" + System.nanoTime())
            val a = "http://127.0.0.1:${args[1]}/a.zip"; val b = "http://127.0.0.1:${args[1]}/b.zip"
            kr.jamsuham.core.pack.PackFonts.load(a, null, dir, 0); kr.jamsuham.core.pack.PackFonts.load(b, null, dir, 0)
            val before = dir.list()!!.sorted()
            val freed = kr.jamsuham.core.pack.PackFonts.pruneCache(dir, listOf(b to null))
            val after = dir.list()!!.sorted()
            val fromCache = kr.jamsuham.core.pack.PackFonts.load("http://127.0.0.1:1/x.zip".let { b }, null, dir, 0).glyphCount
            println("RESULT prune before=$before after=$after freed=$freed bGlyphsFromCache=$fromCache")
            ok = before.size == 2 && after == listOf(kr.jamsuham.core.pack.PackFonts.cacheName(b, null)) && freed > 0 && fromCache == 5
        }
        "spaces" -> {
            val dir = File("" + TMP + "/packcache-space-" + System.nanoTime())
            val u = "http://127.0.0.1:${args[1]}/space.zip"
            val a = kr.jamsuham.core.pack.PackFonts.load(u, null, dir, 0)
            val b = kr.jamsuham.core.pack.PackFonts.load(u, null, dir, 0) // 두 번째는 캐시에서
            println("RESULT spaces fresh=${a.advance(null, 0xE011)},${a.advance(null, 0xE012)} cached=${b.advance(null, 0xE011)} glyphs=${a.glyphCount}")
            ok = a.advance(null, 0xE011) == -40f && a.advance(null, 0xE012) == -1f && b.advance(null, 0xE011) == -40f && a.glyphCount == 2
        }
        "walk" -> {
            val lines = CopyOnWriteArrayList<String>()
            val r2 = object : SessionListener { override fun onLine(line: LogLine) { lines += line.text.plain } }
            val s = AfkSession(File("" + TMP + "/data"), SessionConfig("127.0.0.1:${args[1]}"), { JoinCredentials.offline("Tester") }, r2, log = CoreLog { _, _, _ -> }, dns = null)
            s.start()
            fun pos(n: String) = s.liveConnection?.let { c -> c.javaClass.getDeclaredField(n).apply { isAccessible = true }.getDouble(c) } ?: Double.NaN
            val zs = CopyOnWriteArrayList<Double>()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 12_000 && lines.none { "걸음" in it }) { zs += pos("z"); Thread.sleep(50) }
            Thread.sleep(500)
            zs += pos("z")
            val start = zs.firstOrNull { !it.isNaN() && it != 0.0 } ?: Double.NaN
            val minZ = zs.filter { !it.isNaN() }.minOrNull() ?: Double.NaN
            println("RESULT walk start=$start minZ=$minZ end=${zs.last()} line=${lines.firstOrNull { "걸음" in it }}")
            ok = lines.any { "걸음" in it } && start - minZ > 1.0 && kotlin.math.abs(zs.last() - start) < 0.3
            s.stop(); Thread.sleep(500)
        }
        "world" -> {
            val w = kr.jamsuham.core.world.World()
            fun section(buf: io.netty.buffer.ByteBuf, blockValue: Int) {
                buf.writeShort(4096)            // non-air
                buf.writeByte(0); writeVar(buf, blockValue); writeVar(buf, 0) // 블록: 한 가지 값
                buf.writeByte(0); writeVar(buf, 1); writeVar(buf, 0)          // 바이옴
            }
            val b1 = io.netty.buffer.Unpooled.buffer(); repeat(24) { section(b1, 1) }
            val b2 = io.netty.buffer.Unpooled.buffer(); repeat(24) { section(b2, 1) }
            w.setDimensionMinY(-64)
            w.loadChunk(0, 0, b1); w.loadChunk(1, 0, b2)
            val before = w.stateAt(5, 0, 5) == 1 && w.stateAt(20, 0, 5) == 1
            w.setBlock(5, 0, 5, 9)
            val cow = w.stateAt(5, 0, 5) == 9 && w.stateAt(6, 0, 5) == 1 && w.stateAt(20, 0, 5) == 1
            // 잘못된 청크: 팔레트 개수가 엄청 큼 → 예외 없이 그 청크만 버림
            val bad = io.netty.buffer.Unpooled.buffer(); bad.writeShort(10); bad.writeByte(4); writeVar(bad, 2_000_000_000)
            val noCrash = runCatching { w.loadChunk(5, 5, bad) }.isSuccess && w.stateAt(5 * 16, 0, 5 * 16) == -1
            println("RESULT world shared=$before copyOnWrite=$cow badChunkDropped=$noCrash")
            ok = before && cow && noCrash
        }
        "vitals" -> {
            val s = session("127.0.0.1:${args[1]}", rec); s.start()
            waitFor(30_000) { rec.deaths.isNotEmpty() }
            Thread.sleep(800)
            val d = rec.deaths.firstOrNull()
            val v = rec.vitals.filterNotNull().firstOrNull { it.maxHealth == 40f && it.absorption == 4f }
            println("RESULT vitals vital=$v death=${d?.describe()} log=${rec.lines.firstOrNull { it.contains("사망") }}")
            ok = v != null && v.health == 31.5f && v.food == 17 && d?.killer == "좀비" && d.cause == "공격당함"
            s.stop(); Thread.sleep(500)
        }
        "deathparse" -> {
            val cases = listOf(
                """{"translate":"death.attack.arrow.item","with":[{"text":"Steve"},{"text":"Alex"},{"translate":"chat.square_brackets","with":[{"translate":"item.minecraft.bow"}]}]}""",
                """{"translate":"death.fell.accident.ladder","with":[{"text":"Steve"}]}""",
                """{"translate":"death.attack.lava","with":[{"text":"Steve"}]}""",
                """{"text":"Steve가 함정에 빠졌다"}""",
                """{"text":""}""",
            )
            for (c in cases) { val d = kr.jamsuham.core.text.DeathMessages.parseJson(c, 0); println("CASE ${d.describe()}  | msg=${d.message}") }
            println("CHAT " + kr.jamsuham.core.text.ChatText.fromJson(cases[0]).plain)
            ok = true
        }
        "idle" -> {
            val s = session("127.0.0.1:${args[1]}", rec); s.start()
            waitFor(30_000) { rec.states.lastOrNull()?.state == SessionState.ONLINE }
            Thread.sleep(2500) // 텔레포트(100.5, 70, 100.5)까지 기다림
            val c = s.liveConnection!!
            // 발밑(청크 6,6)에 y -64~63 이 전부 돌인 평평한 지형을 넣는다
            val buf = io.netty.buffer.Unpooled.buffer()
            repeat(24) { i ->
                val stone = i < 8
                buf.writeShort(if (stone) 4096 else 0)
                buf.writeByte(0); writeVar(buf, if (stone) 1 else 0); writeVar(buf, 0)
                buf.writeByte(0); writeVar(buf, 1); writeVar(buf, 0)
            }
            c.world.setDimensionMinY(-64)
            c.world.loadChunk(6, 6, buf)
            val f = c.javaClass.getDeclaredField("positionTask").apply { isAccessible = true }
            fun delays(n: Int): List<Long> = (1..n).map { Thread.sleep(150); (f.get(c) as java.util.concurrent.ScheduledFuture<*>).getDelay(java.util.concurrent.TimeUnit.MILLISECONDS) }
            val idle1 = delays(8)
            val y0 = c.currentY
            s.setMovement(1) // 북쪽으로 걷기
            val moving = delays(8)
            s.setMovement(0)
            Thread.sleep(3000) // 땅에 떨어져 멈출 시간
            val y1 = c.currentY
            val idle2 = delays(8)
            val t = s.traffic
            println("RESULT idle idleDelays=$idle1 movingDelays=$moving idleAgain=$idle2 y=$y0->$y1 traffic=$t")
            ok = idle1.max() > 300 && moving.max() <= 50 && idle2.max() > 300 && y1 == 64.0 && t.received > 0 && t.sent > 0
            Thread.sleep(6000) // 서버가 가만히 있는 동안 받는 위치 패킷 수를 세도록
            s.stop(); Thread.sleep(800)
        }
        "click" -> {
            val cases = listOf(
                """{"text":"","extra":[{"text":"[수락]","color":"green","clickEvent":{"action":"run_command","value":"/tpaccept"},"hoverEvent":{"action":"show_text","contents":{"text":"눌러서 수락"}}},{"text":" "},{"text":"[거절]","clickEvent":{"action":"run_command","value":"/tpdeny"}}]}""",
                """{"text":"디스코드: https://discord.gg/abc123. 놀러오세요"}""",
                """{"text":"https://discord.gg/abc에서 확인 (https://a.kr/x?y=1&z=2)"}""",
                """{"translate":"death.attack.badRespawnPoint.message","with":[{"text":"Steve"},{"translate":"death.attack.badRespawnPoint.link"}]}""",
                """{"text":"사이트","click_event":{"action":"open_url","url":"https://example.com"}}""",
                """{"text":"§a[복사]§r 코드","clickEvent":{"action":"copy_to_clipboard","value":"ABC-123"}}""",
                """{"text":"명령어 제안","clickEvent":{"action":"suggest_command","value":"/msg Steve "}}""",
            )
            for (c in cases) {
                val t = kr.jamsuham.core.text.ChatText.fromJson(c)
                println("CASE " + t.spans.joinToString(" | ") { "'${it.text}'" + (it.click?.let { k -> " ->${k.action}:${k.value}" } ?: "") + (it.hover?.let { h -> " (설명:$h)" } ?: "") })
            }
            val t1 = kr.jamsuham.core.text.ChatText.fromJson(cases[0]).spans
            val t2 = kr.jamsuham.core.text.ChatText.fromJson(cases[1]).spans
            val t4 = kr.jamsuham.core.text.ChatText.fromJson(cases[5]).spans
            ok = t1.first { it.text == "[수락]" }.click?.value == "/tpaccept" && t1.first { it.text == "[수락]" }.hover == "눌러서 수락" &&
                t1.first { it.text == "[거절]" }.click?.value == "/tpdeny" &&
                t2.any { it.text == "https://discord.gg/abc123" && it.click?.action == kr.jamsuham.core.text.ChatClick.Action.OPEN_URL } &&
                t4.all { it.click?.value == "ABC-123" }
            println("RESULT click ok=$ok")
        }
        "clicknet" -> {
            val got = CopyOnWriteArrayList<LogLine>()
            val r2 = object : SessionListener { override fun onLine(line: LogLine) { got += line } }
            val s = AfkSession(File("" + TMP + "/data"), SessionConfig("127.0.0.1:${args[1]}"), { JoinCredentials.offline("Tester") }, r2, log = CoreLog { _, _, _ -> }, dns = null)
            s.start()
            waitFor(20_000) { got.any { l -> l.text.spans.any { it.click != null } } }
            val line = got.firstOrNull { l -> l.text.spans.any { it.click != null } }
            println("RESULT clicknet " + (line?.text?.spans?.joinToString(" | ") { "'${it.text}'" + (it.click?.let { k -> " ->${k.action}:${k.value}" } ?: "") + (it.hover?.let { h -> " (설명:$h)" } ?: "") } ?: "없음"))
            ok = line != null && line.text.spans.any { it.click?.value == "/tpaccept" && it.hover == "눌러서 수락" } && line.text.spans.any { it.click?.value == "https://example.com" }
            s.stop(); Thread.sleep(500)
        }
        "joincmd" -> {
            val sent = CopyOnWriteArrayList<Pair<Long, String>>()
            var sess: AfkSession? = null
            val r2 = object : SessionListener {
                override fun onLine(line: LogLine) { val t = line.text.plain; if (t.startsWith("자동 실행")) { sent += System.currentTimeMillis() to t; println("${rec.ts()} $t") } }
                override fun onMenu(menu: Menu?) { if (menu != null) Thread { Thread.sleep(300); sess?.clickMenu(13) }.start() } // 리소스팩 창 닫기
            }
            sess = AfkSession(File("" + TMP + "/data"),
                SessionConfig("127.0.0.1:${args[1]}", joinCommands = listOf("/one", "/two", "/three"), joinCommandDelaySec = 1, joinCommandIntervalSec = 3),
                { JoinCredentials.offline("Tester") }, r2, log = CoreLog { _, _, _ -> }, dns = null)
            sess.start()
            waitFor(40_000) { sent.size >= 3 }
            val gaps = sent.zipWithNext { a, b -> b.first - a.first }
            println("RESULT joincmd sent=${sent.map { it.second }} gapsMs=$gaps")
            ok = sent.size == 3 && gaps.all { it in 2900..3500 }
            sess.stop(); Thread.sleep(500)
        }
        "probe" -> {
            val addr = args[1]
            val target = try { kr.jamsuham.core.net.AddressResolver.resolve(addr, kr.jamsuham.core.net.AddressResolver.UdpDns(listOf("1.1.1.1", "8.8.8.8"))) } catch (t: Throwable) { println("RESOLVE FAIL $t"); kr.jamsuham.core.net.AddressResolver.resolve(addr, null) }
            println("TARGET $target")
            kr.jamsuham.core.via.ViaSetup.init(File("" + TMP + "/data/via"))
            val newest = com.viaversion.viaversion.api.protocol.version.ProtocolVersion.getProtocols().filter { it.isKnown && it.version > 0 }.maxOf { it.version }
            for ((name, proto) in listOf("1.8" to 47, "1.12.2" to 340, "1.20.4" to 765, "1.21.4" to 769, "최신" to newest)) {
                try {
                    val p = kr.jamsuham.core.net.StatusPinger.ping(target, proto)
                    println("PING as $name($proto) -> server says protocol=${p.protocol} version='${p.versionName}' players=${p.online}/${p.max}")
                } catch (t: Throwable) { println("PING as $name($proto) FAIL ${t.javaClass.simpleName}: ${t.message}") }
            }
            ok = true
        }
        "packtest" -> {
            val port = args[1]
            val cache = File("" + TMP + "/packcache-" + System.nanoTime())
            fun stats(): Long = java.net.URL("http://127.0.0.1:$port/stats").readText().toLong()
            fun describe(set: kr.jamsuham.core.pack.PackFonts.FontSet) = set.fonts.entries.joinToString("; ") { (f, m) -> f + "=" + m.entries.joinToString(",") { (cp, g) -> "U+%04X->%s[%d,%d of %dx%d h%d]".format(cp, g.image.substringAfterLast('/'), g.col, g.row, g.cols, g.rows, g.height) } }
            stats()
            val ranged = kr.jamsuham.core.pack.PackFonts.load("http://127.0.0.1:$port/pack.zip", "a".repeat(40), cache, 0, CoreLog { _, m, _ -> println("LOG $m") })
            val rangedBytes = stats()
            println("RANGED bytes=$rangedBytes glyphs=${ranged.glyphCount} ${describe(ranged)}")
            val full = kr.jamsuham.core.pack.PackFonts.load("http://127.0.0.1:$port/pack.zip?norange", "b".repeat(40), cache, 50L shl 20)
            val fullBytes = stats()
            println("FULL bytes=$fullBytes glyphs=${full.glyphCount}")
            val noFull = runCatching { kr.jamsuham.core.pack.PackFonts.load("http://127.0.0.1:$port/pack.zip?norange", "c".repeat(40), cache, 0) }
            println("NOFULL ${noFull.exceptionOrNull()?.message}")
            val cached = kr.jamsuham.core.pack.PackFonts.load("http://127.0.0.1:1/unreachable.zip", "a".repeat(40), cache, 0)
            println("CACHED glyphs=${cached.glyphCount} imgSize=${kr.jamsuham.core.pack.PackFonts.pngSize(cached.images.values.first())}")
            val ct = kr.jamsuham.core.text.ChatText.fromJson("""{"text":"\ue001[VIP] 철수 \u9c57","extra":[{"text":"\ue100","font":"server:rank","color":"gold"},{"text":" 안녕"}]}""")
            println("CHAT plain='${ct.plain}' spans=" + ct.spans.joinToString(" | ") { "'${it.text}'" + (it.glyphs?.let { g -> " glyphs=" + g.codePoints().toArray().joinToString(",") { "U+%04X".format(it) } } ?: "") + (it.font?.let { " font=$it" } ?: "") })
            val d = ranged.fonts["minecraft:default"]; val r = ranged.fonts["server:rank"]
            ok = rangedBytes < 200_000 && ranged.glyphCount == 5 && d?.containsKey(0xE001) == true && d.containsKey(0x9C57) && r?.containsKey(0xE100) == true && r.containsKey(0xE001) &&
                full.glyphCount == 5 && noFull.isFailure && cached.glyphCount == 5 &&
                ct.plain == "[VIP] 철수 \u9c57 안녕" && ct.spans.any { it.glyphs == "\ue100" && it.font == "server:rank" } && ct.spans.first().glyphs == "\ue001"
            println("RESULT packtest ok=$ok")
        }
        "overlay" -> {
            val set = kr.jamsuham.core.pack.PackFonts.load("http://127.0.0.1:${args[1]}/overlay.zip", null, File("" + TMP + "/packcache-ov-" + System.nanoTime()), 0, CoreLog { _, m, _ -> println("LOG $m") })
            val d = set.fonts["minecraft:default"]
            println("RESULT overlay keys=${d?.keys?.map { "%04X".format(it) }} img=${d?.values?.map { it.image }?.toSet()}")
            ok = d != null && d.keys == setOf(0xE500, 0xE501) && d.values.all { it.image == "assets/minecraft/textures/font/new.png" }
        }
        "packnet" -> {
            val packs = CopyOnWriteArrayList<Pair<String, String>>()
            val got = CopyOnWriteArrayList<LogLine>()
            val r2 = object : SessionListener {
                override fun onLine(line: LogLine) { got += line }
                override fun onResourcePack(url: String, hash: String) { packs += url to hash }
            }
            val s = AfkSession(File("" + TMP + "/data"), SessionConfig("127.0.0.1:${args[1]}"), { JoinCredentials.offline("Tester") }, r2, log = CoreLog { _, _, _ -> }, dns = null)
            s.start()
            waitFor(20_000) { packs.isNotEmpty() && got.any { l -> l.text.spans.any { it.glyphs != null } } }
            val line = got.firstOrNull { l -> l.text.spans.any { it.glyphs != null } }
            val set = packs.firstOrNull()?.let { (u, h) -> kr.jamsuham.core.pack.PackFonts.load(u, h, File("" + TMP + "/packcache-net-" + System.nanoTime()), 0) }
            // 채팅의 그림 글자가 팩에서 다 찾아지는지 (앱이 그림으로 그릴 수 있는지)
            val found = line?.text?.spans?.flatMap { sp -> (sp.glyphs ?: "").codePoints().toArray().map { cp -> set?.glyph(sp.font, cp) != null } + (sp.text.codePoints().toArray().filter { it == 0x9C57 }.map { cp -> set?.glyph(sp.font, cp) != null }) }
            println("RESULT packnet packs=${packs.map { it.first }} plain='${line?.text?.plain}' glyphSpans=${line?.text?.spans?.count { it.glyphs != null }} allFound=$found")
            ok = packs.isNotEmpty() && line != null && found != null && found.isNotEmpty() && found.all { it }
            s.stop(); Thread.sleep(500)
        }
        else -> error(mode)
    }
    System.exit(if (ok) 0 else 1)
}

fun writeVar(buf: io.netty.buffer.ByteBuf, v0: Int) { var v = v0; while (true) { if (v and 0x7F.inv() == 0) { buf.writeByte(v); return }; buf.writeByte((v and 0x7F) or 0x80); v = v ushr 7 } }
