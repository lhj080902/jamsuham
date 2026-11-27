import kr.jamsuham.core.text.ChatAuthor

/**
 * 차단 판단(`harness blockmatch`): 채팅 줄의 "쓴 사람 자리"에 그 닉네임이 낱말로 들어 있는지.
 * 앱은 이 함수로 차단한 사람의 줄을 숨긴다.
 */
fun blockMatchTest(): Boolean {
    data class Case(val line: String, val name: String, val expected: Boolean)
    val cases = listOf(
        Case("<Steve> 안녕", "Steve", true),
        Case("[VIP] Steve: 안녕", "steve", true), // 대소문자 무시
        Case("Steve -> 나: 안녕", "Steve", true), // 귓속말
        Case("Steve 님이 접속했습니다", "Steve", true), // 구분 글자가 없는 줄
        Case("[공지] 관리자: Steve 는 정지되었습니다", "Steve", false), // 남이 그 이름을 말한 줄
        Case("<Steve_2> 안녕", "Steve", false), // 다른 이름의 일부
        Case("<Coral_Min> 오늘 저녁 낚시 대회 몇 시예요?", "Min", false),
        Case("<철수> 안녕", "철수", true), // 한글 닉네임
        Case("[안내] 잠수 보상은 30분마다 지급됩니다.", "잠수", true), // 닉네임이 흔한 낱말이면 그 낱말이 앞쪽에 든 줄도 숨는다 (알고 둔 한계)
        Case("", "Steve", false),
        Case("<Steve> 안녕", "", false), // 빈 이름은 아무 줄도 가리지 않는다
        Case("<Steve> 안녕", " ", false),
        Case("Alex » Steve 어디야", "Steve", false), // » 뒤는 내용
        Case("[길드] Steve ▶ 모여라", "Steve", true),
        Case("a".repeat(70) + " Steve", "Steve", false), // 구분 글자가 없으면 앞 64자까지만 본다
        // 검토에서 나온 모양들
        Case("[Lv: 12] Steve: hi", "Steve", true), // 괄호 안의 구분 글자는 세지 않는다
        Case("(Lv: 3) [VIP] Steve: hi", "Steve", true),
        Case("[공지: 필독] 관리자: Steve 는 정지되었습니다", "Steve", false), // 괄호를 건너뛰어도 남이 말한 줄은 그대로
        Case("Alex ➤ Steve 어디야?", "Steve", false), // 다른 화살표 구분 글자
        Case("Alex» Steve 어디야", "Steve", false), // 앞에 빈칸이 없는 »
        Case("» Steve: hi", "Steve", true), // 줄 맨 앞의 꾸밈 글자는 구분 글자로 치지 않는다
        Case("<김철수> 안녕", "철수", false), // 한글 닉네임도 다른 이름의 뒷부분이면 아니다
        Case("철수님의 귓속말: 안녕", "철수", true), // 한글 닉네임 뒤에 붙는 말(님·의)은 괜찮다
        Case("Guild > Steve: hi", "Steve", false), // 알고 둔 한계: 채널 이름 뒤에 구분 글자가 또 오는 모양은 쓴 사람을 못 찾는다
    )
    var ok = 0
    for (c in cases) {
        val got = ChatAuthor.isFrom(c.line, c.name)
        if (got == c.expected) ok++ else println("  실패  '${c.line}' / '${c.name}' → $got (기대 ${c.expected}) 자리='${ChatAuthor.area(c.line)}'")
    }
    val total = cases.size + 2
    val names = ChatAuthor.namesIn("[텔레포트] Coral_Min 님이 같이 가자고 요청했습니다", listOf("Coral_Min", "Sea_Otter"))
    if (names == listOf("Coral_Min")) ok++ else println("  실패  namesIn → $names")
    val none = ChatAuthor.namesIn("<Haneul_Fish> 다들 안녕하세요", listOf("Coral_Min", "", "Sea_Otter"))
    if (none.isEmpty()) ok++ else println("  실패  namesIn(없음) → $none")
    println("RESULT blockmatch ok=$ok/$total")
    return ok == total
}

/**
 * 아이템 잠금 열쇠(`harness lockkey`): 아이템 종류 + 서버가 붙인 이름. 기본 이름(한국어든 영어든)은 "이름 없음"으로 쳐서,
 * 앱 언어를 바꿔도 잠금이 풀리지 않아야 한다.
 */
fun lockKeyTest(): Boolean {
    val N = kr.jamsuham.core.text.ItemNames
    val sword = "minecraft:diamond_sword"
    val checks = ArrayList<Pair<String, Boolean>>()
    for (english in listOf(false, true)) {
        kr.jamsuham.core.Lang.english = english
        val lang = if (english) "영어" else "한국어"
        checks += "$lang: 한국어 기본 이름 → 종류만" to (N.lockKey(sword, "다이아몬드 검") == sword)
        checks += "$lang: 영어 기본 이름 → 종류만" to (N.lockKey(sword, "Diamond Sword") == sword)
        checks += "$lang: 서버가 붙인 이름 → 종류 + 이름" to (N.lockKey(sword, "전설의 검") == sword + 10.toChar() + "전설의 검")
        checks += "$lang: 블록 아이템의 기본 이름" to (N.lockKey("minecraft:cobblestone", "조약돌") == "minecraft:cobblestone")
        checks += "$lang: 앞뒤 빈칸은 무시" to (N.lockKey(sword, " Diamond Sword ") == sword)
        checks += "$lang: 이름이 비면 종류만" to (N.lockKey(sword, "") == sword)
        checks += "$lang: 같은 이름, 다른 종류는 다른 열쇠" to (N.lockKey("minecraft:stick", "전설의 검") != N.lockKey(sword, "전설의 검"))
    }
    kr.jamsuham.core.Lang.english = false
    // 지금 언어로 보이는 기본 이름이 무엇이든 종류만 남는다 (앱이 실제로 넘기는 값)
    val ko = N.name(sword)
    kr.jamsuham.core.Lang.english = true
    val en = N.name(sword)
    kr.jamsuham.core.Lang.english = false
    checks += "보이는 기본 이름($ko / $en) 둘 다 같은 열쇠" to (N.lockKey(sword, ko) == sword && N.lockKey(sword, en) == sword)
    for ((name, pass) in checks) if (!pass) println("  실패  $name")
    val passed = checks.count { it.second }
    println("RESULT lockkey $passed/${checks.size}")
    return passed == checks.size
}

/**
 * 왕복형 이름의 움직임(`harness pingpong`): 지난 시간에 따라 얼마나 밀려 있어야 하는지.
 * 1.2초 멈춤 → 끝까지 → 1.2초 멈춤 → 처음까지, 되풀이. 앱의 접속자 탭이 이 값으로 이름을 민다.
 */
fun pingPongTest(): Boolean {
    val M = kr.jamsuham.core.text.PingPongMotion
    val range = 400 // 글이 칸보다 400px 길다
    val speed = 100f // 초당 100px → 한쪽으로 4초, 한 바퀴 10.4초
    fun near(a: Float, b: Float) = Math.abs(a - b) < 0.6f
    val checks = ArrayList<Pair<String, Boolean>>()
    checks += "처음에는 0" to near(M.offset(0, range, speed), 0f)
    checks += "처음 멈춤 동안 0" to near(M.offset(1199, range, speed), 0f)
    checks += "가는 길의 절반" to near(M.offset(1200 + 2000, range, speed), 200f)
    checks += "끝에 닿음" to near(M.offset(1200 + 4000, range, speed), 400f)
    checks += "끝에서 멈춤" to near(M.offset(1200 + 4000 + 600, range, speed), 400f)
    checks += "돌아오는 길의 절반" to near(M.offset(1200 + 4000 + 1200 + 2000, range, speed), 200f)
    checks += "한 바퀴 뒤 다시 0" to near(M.offset(10400, range, speed), 0f)
    checks += "다음 바퀴도 같다" to near(M.offset(10400 + 3200, range, speed), 200f)
    checks += "칸에 다 들어가면(범위 0) 움직이지 않는다" to (M.offset(5000, 0, speed) == 0f && M.offset(5000, -30, speed) == 0f)
    checks += "시간이 음수면 0" to (M.offset(-500, range, speed) == 0f)
    checks += "빠르기가 0 이하면 0" to (M.offset(5000, range, 0f) == 0f)
    checks += "아주 짧은 거리도 0.3초는 걸린다" to near(M.offset(1200 + 150, 10, speed), 5f)
    var inside = true
    var t = 0L
    while (t < 30000) {
        val x = M.offset(t, range, speed)
        if (x < 0f || x > range) inside = false
        t += 37
    }
    checks += "언제나 0 ~ 범위 안" to inside
    for ((name, pass) in checks) if (!pass) println("  실패  $name")
    val passed = checks.count { it.second }
    println("RESULT pingpong $passed/${checks.size}")
    return passed == checks.size
}
