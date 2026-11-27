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
