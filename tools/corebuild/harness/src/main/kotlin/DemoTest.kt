import kr.jamsuham.core.*
import kr.jamsuham.core.demo.DemoServer
import kr.jamsuham.core.net.AddressResolver
import kr.jamsuham.core.net.StatusPinger
import kr.jamsuham.core.via.ViaSetup
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 앱 안 체험 서버(core 의 DemoServer) 시험. 다른 서버 없이 혼자 돈다: `harness demo`
 * 앱이 하는 것과 같은 길(AfkSession, 비정품 이름, 1.21.4 고정)로 붙어서 장면·채팅·메뉴·버리기·다시 켜기·언어를 본다.
 */
fun demoTest(): Boolean {
    Lang.english = false
    val checks = LinkedHashMap<String, Boolean>()

    class View : SessionListener {
        val lines = CopyOnWriteArrayList<LogLine>()
        @Volatile var players = PlayerList.EMPTY
        @Volatile var inv = Inventory.EMPTY
        @Volatile var menu: Menu? = null
        @Volatile var vit: Vitals? = null
        @Volatile var state: SessionState? = null
        override fun onStatus(status: SessionStatus) { state = status.state }
        override fun onLine(line: LogLine) { lines += line; println("[줄] ${line.text.plain}") }
        override fun onPlayers(list: PlayerList) { players = list }
        override fun onInventory(inventory: Inventory) { inv = inventory }
        override fun onMenu(menu: Menu?) { this.menu = menu }
        override fun onVitals(vitals: Vitals?) { if (vitals != null) vit = vitals }
        fun has(part: String) = lines.any { it.text.plain.contains(part) }
    }
    fun open(view: View) = AfkSession(File("$TMP/data"), SessionConfig(DemoServer.address(), fixedVersion = "1.21.4", walkOnJoin = false),
        { JoinCredentials.offline(DemoServer.NAME) }, view, log = CoreLog { _, _, _ -> }, dns = null).also { it.start() }
    fun ping(address: String = DemoServer.address()) = StatusPinger.ping(AddressResolver.resolve(address, null), ViaSetup.NATIVE_PROTOCOL, 3000)

    // 서버 목록 핑
    val p = ping()
    println("핑: ${p.versionName} ${p.protocol} ${p.online}/${p.max} '${p.motd.plain}' 아이콘 ${p.favicon?.size}")
    checks["ping"] = p.online == 37 && p.max == 100 && p.favicon != null && p.versionName == "1.21.4" && p.protocol == 769 && p.motd.plain.contains("낚시와 잠수 보상")

    // 접속 → 녹화한 장면
    val v = View()
    val s = open(v)
    val joined = waitFor(20_000) { v.has("잠수 걸어 뒀어요") }
    checks["join"] = joined && v.state == SessionState.ONLINE
    waitFor(5_000) { v.has("[메뉴 열기]") }
    Thread.sleep(300)
    val w = s.liveConnection?.world
    fun st(x: Int, y: Int, z: Int) = w?.stateAt(x, y, z) ?: -2
    val blocks = listOf(st(10, 63, -4), st(10, 64, -4), st(-8, 63, 14), st(20, 63, -12), st(16, 64, -12), st(-20, 64, -20), st(10, 63, -2), st(40, 63, 40))
    val guide = v.lines.any { l -> l.text.spans.any { it.text == "[메뉴 열기]" && it.click?.value == "/메뉴" } }
    println("장면: 접속자 ${v.players.players.size} 인벤 ${v.inv.slots.size} 체력 ${v.vit?.health} 블록 $blocks 안내 $guide")
    checks["scene"] = v.players.players.size == 8 && v.inv.slots.size == 18 && v.vit?.health == 17f &&
        blocks == listOf(9, 0, 86, 15, 14, 4340, 13526, 9) && guide

    // 채팅은 그대로 돌아온다
    s.sendChat("hello")
    checks["echo"] = waitFor(5_000) { v.lines.any { it.text.plain == "<Jamsu_Diver> hello" } }

    // /잠수
    s.sendChat("/잠수")
    checks["afk"] = waitFor(5_000) { v.has("잠수 모드가 켜졌습니다") }

    // /메뉴 → 칸 누르기 → 닫힘 → 다시 열림
    s.sendChat("/메뉴")
    val opened = waitFor(5_000) { v.menu?.title?.plain == "바다마을 메뉴" && v.menu!!.items.count { it.slot < 27 } == 6 }
    s.clickMenu(13)
    val closed = waitFor(5_000) { v.menu == null } && waitFor(5_000) { v.has("'잠수 보상 받기' 을(를) 눌렀습니다") }
    s.sendChat("/메뉴")
    val reopened = waitFor(5_000) { v.menu != null }
    s.closeMenu()
    waitFor(3_000) { v.menu == null }
    println("메뉴: 열림 $opened 닫힘 $closed 다시 $reopened")
    checks["menu"] = opened && closed && reopened

    // 버리기: 1개 → 개수 -1, 전부 → 칸이 빔
    Thread.sleep(300)
    val item = v.inv.slots.firstOrNull { it.slot in 9..44 && it.count >= 2 }
    var one = false
    var all = false
    if (item != null) {
        s.dropItem(item.slot, false)
        one = waitFor(5_000) { v.inv.slots.firstOrNull { it.slot == item.slot }?.count == item.count - 1 }
        s.dropItem(item.slot, true)
        all = waitFor(5_000) { v.inv.slots.none { it.slot == item.slot } }
    }
    println("버리기: 칸 ${item?.slot} ${item?.count}개 → 1개 $one 전부 $all, 남은 칸 ${v.inv.slots.size}")
    checks["drop"] = one && all && v.inv.slots.size == 17

    // 사용
    s.useItem(36)
    checks["use"] = waitFor(5_000) { v.has("아이템을 사용했습니다") }

    // 누를 수 있는 채팅의 명령어, 없는 명령어
    s.sendChat("/tpaccept")
    val accept = waitFor(5_000) { v.has("수락했습니다") }
    s.sendChat("/없는명령")
    checks["unknown"] = accept && waitFor(5_000) { v.has("알 수 없는 명령어") }

    // 꺼졌다 다시 켜짐: 옛 포트는 닫히고, 주소를 다시 물으면 새로 켜져서 핑이 된다
    s.stop()
    waitFor(5_000) { v.state == SessionState.STOPPED }
    val oldAddress = DemoServer.address()
    DemoServer.stop()
    val oldClosed = runCatching { ping(oldAddress) }.isFailure
    val again = runCatching { ping() }.getOrNull()
    println("다시 켜기: 옛 주소 $oldAddress 닫힘 $oldClosed → 새 주소 ${DemoServer.address()} 핑 ${again?.online}")
    checks["restart"] = oldClosed && again?.online == 37

    // 언어: 서버를 다시 켜지 않아도 다음 접속부터 바뀐 언어의 장면
    Lang.english = true
    val v2 = View()
    val s2 = open(v2)
    val joinedEn = waitFor(20_000) { v2.has("left my phone AFK") }
    s2.sendChat("/menu")
    val menuEn = waitFor(5_000) { v2.menu?.title?.plain == "Seaside Menu" }
    s2.clickMenu(13)
    val tappedEn = waitFor(5_000) { v2.has("You tapped 'Claim AFK reward'") }
    val motdEn = ping().motd.plain
    println("영어: 접속 $joinedEn 메뉴 $menuEn 누름 $tappedEn 소개 '$motdEn'")
    checks["lang"] = joinedEn && menuEn && tappedEn && motdEn.contains("Fishing and AFK rewards")
    s2.stop()
    waitFor(5_000) { v2.state == SessionState.STOPPED }
    Lang.english = false

    // 이 기기 안에서만 열려 있다 (다른 주소로는 못 들어옴)
    val port = DemoServer.ensureStarted()
    val other = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address }
    val outside = other != null && runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(other, port), 1500) } }.isSuccess
    println("바깥 주소 $other 로 접속됨: $outside")
    checks["loopback"] = DemoServer.address() == "127.0.0.1:$port" && !outside
    DemoServer.stop()

    println("RESULT demo " + checks.entries.joinToString(" ") { "${it.key}=${it.value}" })
    return checks.values.all { it }
}
