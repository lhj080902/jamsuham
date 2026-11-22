package kr.jamsuham.core.text

import com.google.gson.JsonParser
import kr.jamsuham.core.Lang
import java.util.zip.GZIPInputStream

/** 아이템 ID → 한국어 이름 (마인크래프트 1.21.4 ko_kr 번역에서 아이템·블록 이름만 뽑아 둠) */
object ItemNames {
    private val names: Map<String, String> by lazy {
        try {
            val stream = ItemNames::class.java.getResourceAsStream("/jamsuham/items-ko.json.gz") ?: return@lazy emptyMap()
            GZIPInputStream(stream).reader(Charsets.UTF_8).use { r ->
                JsonParser.parseReader(r).asJsonObject.entrySet().associate { it.key to it.value.asString }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun name(id: String): String {
        val key = id.removePrefix("minecraft:")
        // 영어 모드: 영어 번역표는 따로 두지 않고 ID 에서 만든다 ("diamond_sword" → "Diamond Sword")
        if (Lang.english) return titleCase(key)
        return names["item.minecraft.$key"] ?: names["block.minecraft.$key"]
            ?: titleCase(key)
    }

    /** "diamond_sword" → "Diamond Sword" (밑줄을 띄어쓰기로, 단어 첫 글자 대문자) */
    fun titleCase(key: String): String =
        key.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
