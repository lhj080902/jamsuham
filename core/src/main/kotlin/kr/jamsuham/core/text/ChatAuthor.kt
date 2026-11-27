package kr.jamsuham.core.text

/**
 * 채팅 줄을 누가 썼는지 글자만 보고 짐작한다 (차단·신고용).
 * 서버마다 채팅 모양이 달라서(`<이름> 말`, `[칭호] 이름: 말`, `이름 -> 나: 말` …) 서버가 알려 주는 보낸 사람 정보에 기대지 않는다.
 *
 * "쓴 사람 자리" = 처음 나오는 구분 글자 앞까지. 구분 글자가 없으면(접속 알림 등) 앞 64자.
 * 대괄호·소괄호 안의 구분 글자(`[Lv: 12]`)와 줄 맨 앞의 꾸밈 글자(`» 이름: 말`)는 구분 글자로 치지 않는다.
 * 그 자리에 닉네임이 낱말로 들어 있으면 그 사람의 줄로 본다. 남이 그 이름을 **말한** 줄(`관리자: Steve 는 …`)은 해당하지 않는다.
 *
 * 한계: 서버가 닉네임을 그림 글자나 별명으로만 보여 주면 찾지 못하고, 닉네임이 흔한 낱말이면 그 낱말로 시작하는 안내 줄도 걸린다.
 * 채널 이름 뒤에 구분 글자가 또 오는 모양(`Guild > 이름: 말`)은 쓴 사람을 찾지 못하고,
 * 구분 글자가 없는 줄(`Alex was slain by Steve`)은 앞 64자 안에 이름이 있으면 그 사람의 줄로 본다.
 */
object ChatAuthor {
    private val SEPARATORS = listOf("> ", ": ", "» ", " -> ", " ▶ ", " ➤ ", " ➜ ", " ▸ ")
    private const val AREA_WITHOUT_SEPARATOR = 64

    /** 이보다 긴 괄호 묶음은 칭호가 아니라고 보고 건너뛰지 않는다 (짝이 안 맞는 `[` 하나가 줄 전체를 삼키지 않게) */
    private const val MAX_BRACKET = 32

    /** 줄에서 쓴 사람이 적히는 앞부분 */
    fun area(plain: String): String {
        val skip = insideBrackets(plain)
        var end = -1
        for (s in SEPARATORS) {
            var i = plain.indexOf(s)
            // 괄호 안에 있거나, 앞에 아무 글자도 없는(줄 맨 앞 꾸밈) 것은 넘어간다
            while (i >= 0 && (skip[i] || plain.substring(0, i).isBlank())) i = plain.indexOf(s, i + 1)
            if (i >= 0 && (end < 0 || i < end)) end = i
        }
        return if (end >= 0) plain.substring(0, end) else plain.take(AREA_WITHOUT_SEPARATOR)
    }

    /** 글자마다, 닫힌 대괄호·소괄호 묶음 안에 있는지 */
    private fun insideBrackets(plain: String): BooleanArray {
        val inside = BooleanArray(plain.length)
        var i = 0
        while (i < plain.length) {
            val close = when (plain[i]) {
                '[' -> ']'
                '(' -> ')'
                else -> 0.toChar()
            }
            val end = if (close == 0.toChar()) -1 else plain.indexOf(close, i + 1)
            if (end > i && end - i <= MAX_BRACKET) {
                for (k in i..end) inside[k] = true
                i = end + 1
            } else {
                i++
            }
        }
        return inside
    }

    /** [plain] 줄이 [name] 이 쓴 것으로 보이는지 (대소문자 무시, 다른 이름의 일부면 아님) */
    fun isFrom(plain: String, name: String): Boolean {
        val wanted = name.trim()
        if (wanted.isEmpty()) return false
        val where = area(plain)
        // 한글 같은 글자로 시작하는 닉네임은 앞에 다른 글자가 붙어 있으면 다른 이름으로 본다 (`철수` 와 `김철수`).
        // 뒤에는 `님`·`의` 같은 말이 붙을 수 있어서 영문·숫자·밑줄만 가린다
        val strictBefore = !isNameChar(wanted[0])
        var from = 0
        while (true) {
            val i = where.indexOf(wanted, from, ignoreCase = true)
            if (i < 0) return false
            val before = if (i == 0) ' ' else where[i - 1]
            val after = if (i + wanted.length >= where.length) ' ' else where[i + wanted.length]
            val joinedBefore = isNameChar(before) || (strictBefore && before.isLetterOrDigit())
            if (!joinedBefore && !isNameChar(after)) return true
            from = i + 1
        }
    }

    /** [candidates] 가운데 이 줄을 쓴 것으로 보이는 이름들 */
    fun namesIn(plain: String, candidates: Collection<String>): List<String> = candidates.filter { isFrom(plain, it) }

    /** 마인크래프트 닉네임에 쓰는 글자 (영문·숫자·밑줄) */
    private fun isNameChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'
}
