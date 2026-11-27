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
