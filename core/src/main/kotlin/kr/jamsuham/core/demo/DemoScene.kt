package kr.jamsuham.core.demo

import java.util.zip.GZIPInputStream

/**
 * 체험 서버가 다시 보낼 녹화 장면 (리소스 `jamsuham/demo/scene-ko.bin.gz`, `scene-en.bin.gz`).
 * 만드는 법과 파일 형식은 `tools/demo/record.js` 에 있다. 패킷은 1.21.4 그대로의 바이트(번호 + 내용)다.
 */
internal class DemoScene private constructor(
    /** 로그인 성공 */
    val login: List<ByteArray>,
    /** 설정 단계 (레지스트리 … 설정 끝) */
    val config: List<ByteArray>,
    /** 게임 단계: 앞 패킷에서 [Timed.delayMs] 뒤에 보낸다 */
    val play: List<Timed>,
    /** 메뉴 열기 (창 9) */
    val menu: List<ByteArray>,
    /** 인벤토리 칸 번호 → 그 칸의 set_slot 패킷. [COUNT_AT] 번째 바이트가 개수 */
    val slots: Map<Int, ByteArray>,
) {
    class Timed(val delayMs: Int, val bytes: ByteArray)

    companion object {
        /** set_slot 패킷 = [번호][창 0][stateId 1][칸 2바이트][개수]… */
        const val COUNT_AT = 5

        private val cache = arrayOfNulls<DemoScene>(2)

        @Synchronized
        fun load(english: Boolean): DemoScene {
            val index = if (english) 1 else 0
            cache[index]?.let { return it }
            val name = if (english) "scene-en.bin.gz" else "scene-ko.bin.gz"
            val stream = DemoScene::class.java.getResourceAsStream("/jamsuham/demo/$name") ?: error("체험 장면 파일이 없습니다: $name")
            val data = stream.use { GZIPInputStream(it).readBytes() }
            return parse(data).also { cache[index] = it }
        }

        private fun parse(data: ByteArray): DemoScene {
            val login = ArrayList<ByteArray>()
            val config = ArrayList<ByteArray>()
            val play = ArrayList<Timed>()
            val menu = ArrayList<ByteArray>()
            val slots = LinkedHashMap<Int, ByteArray>()
            var at = 0
            fun varInt(): Int {
                var value = 0
                var shift = 0
                while (true) {
                    val b = data[at++].toInt()
                    value = value or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) return value
                    shift += 7
                    require(shift < 35) { "잘못된 장면 파일" }
                }
            }
            while (at < data.size) {
                val segment = data[at++].toInt()
                val delay = varInt()
                val length = varInt()
                require(length >= 1 && at + length <= data.size) { "잘못된 장면 파일" }
                val bytes = data.copyOfRange(at, at + length)
                at += length
                when (segment) {
                    0 -> login += bytes
                    1 -> config += bytes
                    2 -> play += Timed(delay, bytes)
                    3 -> menu += bytes
                    4 -> {
                        require(bytes.size > COUNT_AT) { "잘못된 장면 파일" }
                        slots[((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)] = bytes
                    }
                    else -> error("잘못된 장면 파일")
                }
            }
            return DemoScene(login, config, play, menu, slots)
        }
    }
}
