package kr.jamsuham.core.text

import com.google.gson.JsonParser
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
        return names["item.minecraft.$key"] ?: names["block.minecraft.$key"]
            ?: key.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
