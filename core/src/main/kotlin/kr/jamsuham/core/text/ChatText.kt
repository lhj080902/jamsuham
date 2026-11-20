package kr.jamsuham.core.text

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.viaversion.nbt.tag.Tag
import com.viaversion.viaversion.util.ComponentUtil

/** 화면에 그릴 수 있게 정리한 한 줄의 채팅. color 는 0xRRGGBB, 없으면 null. */
data class ChatSpan(val text: String, val color: Int? = null, val bold: Boolean = false, val italic: Boolean = false)

data class ChatText(val spans: List<ChatSpan>) {
    val plain: String get() = spans.joinToString("") { it.text }

    override fun toString(): String = plain

    companion object {
        fun of(text: String, color: Int? = null) = ChatText(listOf(ChatSpan(text, color)))

        fun concat(vararg parts: ChatText) = ChatText(parts.flatMap { it.spans })

        /** 1.20.3+ NBT 텍스트 컴포넌트 */
        fun fromTag(tag: Tag?): ChatText {
            if (tag == null) return ChatText(emptyList())
            return try {
                fromJson(ComponentUtil.tagToJsonString(tag) ?: "")
            } catch (t: Throwable) {
                of(tag.toString())
            }
        }

        /** JSON 텍스트 컴포넌트 (로그인 단계 킥 메시지 등) */
        fun fromJson(json: String): ChatText {
            if (json.isBlank()) return ChatText(emptyList())
            return try {
                val el = JsonParser.parseString(json)
                val out = ArrayList<ChatSpan>()
                walk(el, Style(), out)
                ChatText(merge(out))
            } catch (t: Throwable) {
                fromLegacy(json)
            }
        }

        /** §색상 코드가 섞인 문자열 */
        fun fromLegacy(text: String): ChatText {
            val out = ArrayList<ChatSpan>()
            appendLegacy(text, Style(), out)
            return ChatText(merge(out))
        }

        /** hidden = 서버 리소스팩 전용 글꼴(아이콘 등). 앱에는 그 글꼴이 없어 깨진 글자로 보이므로 숨긴다 */
        private data class Style(val color: Int? = null, val bold: Boolean = false, val italic: Boolean = false, val hidden: Boolean = false)

        private val STANDARD_FONTS = setOf("minecraft:default", "default", "minecraft:uniform", "uniform")

        private fun walk(el: JsonElement, parent: Style, out: MutableList<ChatSpan>) {
            when {
                el.isJsonPrimitive -> appendLegacy(el.asString, parent, out)
                el.isJsonArray -> {
                    val arr = el.asJsonArray
                    if (arr.size() == 0) return
                    // 배열의 첫 요소가 부모, 나머지는 그 자식
                    val first = arr[0]
                    val style = if (first.isJsonObject) styleOf(first.asJsonObject, parent) else parent
                    walk(first, parent, out)
                    for (i in 1 until arr.size()) walk(arr[i], style, out)
                }
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    val style = styleOf(obj, parent)
                    when {
                        obj.has("text") -> appendLegacy(obj.get("text").asStringSafe(), style, out)
                        obj.has("translate") -> appendTranslated(obj, style, out)
                        obj.has("selector") -> appendLegacy(obj.get("selector").asStringSafe(), style, out)
                        obj.has("keybind") -> appendLegacy(obj.get("keybind").asStringSafe(), style, out)
                        obj.has("score") -> {
                            val score = obj.getAsJsonObject("score")
                            appendLegacy(score?.get("value")?.asStringSafe() ?: "", style, out)
                        }
                        obj.has("") -> appendLegacy(obj.get("").asStringSafe(), style, out) // NBT→JSON 변환 잔재
                    }
                    val extra = obj.get("extra")
                    if (extra != null && extra.isJsonArray) {
                        for (child in extra.asJsonArray) walk(child, style, out)
                    }
                }
            }
        }

        private fun JsonElement.asStringSafe(): String =
            if (isJsonPrimitive) asString else toString()

        private fun appendTranslated(obj: JsonObject, style: Style, out: MutableList<ChatSpan>) {
            val key = obj.get("translate").asStringSafe()
            val fallback = obj.get("fallback")?.asStringSafe()
            val args: JsonArray = obj.getAsJsonArray("with") ?: obj.getAsJsonArray("args") ?: JsonArray()
            // 번역표에 없는 사망 메시지는 "누구님이 무엇에 의해 사망했습니다 (원인)" 으로 만든다 (키가 그대로 보이지 않게)
            if (key.startsWith("death.") && Translations.get(key) == null) {
                val plains = (0 until args.size()).map { i ->
                    val tmp = ArrayList<ChatSpan>()
                    val a = args[i]
                    if (a.isJsonPrimitive && !(a as JsonPrimitive).isString) appendLegacy(a.asString, style, tmp) else walk(a, style, tmp)
                    tmp.joinToString("") { it.text }
                }
                appendLegacy(DeathMessages.sentence(key, plains), style, out)
                return
            }
            val pattern = Translations.get(key) ?: fallback ?: key
            var argIndex = 0
            val regex = Regex("%(?:(\\d+)\\$)?([sd%])")
            var last = 0
            for (m in regex.findAll(pattern)) {
                if (m.range.first > last) appendLegacy(pattern.substring(last, m.range.first), style, out)
                last = m.range.last + 1
                if (m.groupValues[2] == "%") {
                    appendLegacy("%", style, out)
                    continue
                }
                val idx = m.groupValues[1].toIntOrNull()?.minus(1) ?: argIndex++
                if (idx in 0 until args.size()) {
                    val a = args[idx]
                    if (a.isJsonPrimitive && !(a as JsonPrimitive).isString) appendLegacy(a.asString, style, out)
                    else walk(a, style, out)
                }
            }
            if (last < pattern.length) appendLegacy(pattern.substring(last), style, out)
        }

        private fun styleOf(obj: JsonObject, parent: Style): Style {
            var s = parent
            obj.get("color")?.let { c -> if (c.isJsonPrimitive) parseColor(c.asString)?.let { s = s.copy(color = it) } }
            obj.get("bold")?.let { b -> if (b.isJsonPrimitive) s = s.copy(bold = b.asBooleanLoose()) }
            obj.get("italic")?.let { b -> if (b.isJsonPrimitive) s = s.copy(italic = b.asBooleanLoose()) }
            obj.get("font")?.let { f -> if (f.isJsonPrimitive) s = s.copy(hidden = f.asString !in STANDARD_FONTS) }
            return s
        }

        private fun JsonElement.asBooleanLoose(): Boolean {
            val p = asJsonPrimitive
            return if (p.isBoolean) p.asBoolean else if (p.isNumber) p.asInt != 0 else p.asString.equals("true", true)
        }

        private val NAMED = mapOf(
            "black" to 0x000000, "dark_blue" to 0x0000AA, "dark_green" to 0x00AA00, "dark_aqua" to 0x00AAAA,
            "dark_red" to 0xAA0000, "dark_purple" to 0xAA00AA, "gold" to 0xFFAA00, "gray" to 0xAAAAAA,
            "dark_gray" to 0x555555, "blue" to 0x5555FF, "green" to 0x55FF55, "aqua" to 0x55FFFF,
            "red" to 0xFF5555, "light_purple" to 0xFF55FF, "yellow" to 0xFFFF55, "white" to 0xFFFFFF,
        )

        private val LEGACY = "0123456789abcdef".mapIndexed { i, c ->
            c to listOf(
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
            )[i]
        }.toMap()

        private fun parseColor(s: String): Int? {
            NAMED[s.lowercase()]?.let { return it }
            if (s.startsWith("#") && s.length == 7) return s.substring(1).toIntOrNull(16)
            return null
        }

        private fun appendLegacy(text: String, base: Style, out: MutableList<ChatSpan>) {
            if (text.isEmpty() || base.hidden) return
            if (text.indexOf('§') < 0) {
                val clean = stripPrivateUse(text)
                if (clean.isNotEmpty()) out.add(ChatSpan(clean, base.color, base.bold, base.italic))
                return
            }
            var style = base
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '§' && i + 1 < text.length) {
                    if (sb.isNotEmpty()) {
                        out.add(ChatSpan(stripPrivateUse(sb.toString()), style.color, style.bold, style.italic))
                        sb.setLength(0)
                    }
                    val code = text[i + 1].lowercaseChar()
                    style = when {
                        LEGACY.containsKey(code) -> Style(LEGACY[code])
                        code == 'l' -> style.copy(bold = true)
                        code == 'o' -> style.copy(italic = true)
                        code == 'r' -> base
                        else -> style
                    }
                    i += 2
                    continue
                }
                sb.append(ch)
                i++
            }
            if (sb.isNotEmpty()) out.add(ChatSpan(stripPrivateUse(sb.toString()), style.color, style.bold, style.italic))
        }

        /** 개인 사용 영역 문자(서버 전용 아이콘 글꼴에 흔히 쓰임)는 앱 글꼴에 없으니 뺀다 */
        private fun stripPrivateUse(s: String): String {
            if (s.none { it in '\uE000'..'\uF8FF' }) return s
            return s.filterNot { it in '\uE000'..'\uF8FF' }
        }

        private fun merge(spans: List<ChatSpan>): List<ChatSpan> {
            val out = ArrayList<ChatSpan>(spans.size)
            for (s in spans) {
                if (s.text.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null && last.color == s.color && last.bold == s.bold && last.italic == s.italic) {
                    out[out.size - 1] = last.copy(text = last.text + s.text)
                } else {
                    out.add(s)
                }
            }
            return out
        }
    }
}

/** 자주 보이는 번역 키. 없는 키는 서버가 준 fallback 또는 키 그대로 보여 준다. */
object Translations {
    private val map = mapOf(
        "chat.type.text" to "<%s> %s",
        "chat.type.announcement" to "[%s] %s",
        "chat.type.emote" to "* %s %s",
        "chat.type.admin" to "[%s: %s]",
        "multiplayer.player.joined" to "%s님이 게임에 참여했습니다",
        "multiplayer.player.left" to "%s님이 게임을 떠났습니다",
        "multiplayer.disconnect.kicked" to "관리자에 의해 추방되었습니다",
        "multiplayer.disconnect.server_shutdown" to "서버가 닫혔습니다",
        "multiplayer.disconnect.duplicate_login" to "다른 곳에서 같은 계정으로 접속했습니다",
        "multiplayer.disconnect.idling" to "너무 오래 가만히 있었습니다",
        "multiplayer.disconnect.not_whitelisted" to "화이트리스트에 없습니다",
        "multiplayer.disconnect.banned" to "이 서버에서 차단되었습니다",
        "multiplayer.disconnect.outdated_client" to "클라이언트 버전이 맞지 않습니다! 서버 버전: %s",
        "multiplayer.disconnect.incompatible" to "호환되지 않는 클라이언트입니다! %s 버전을 사용해 주세요",
        "multiplayer.disconnect.unverified_username" to "사용자 이름을 인증하지 못했습니다",
        "multiplayer.disconnect.authservers_down" to "인증 서버가 응답하지 않습니다",
        "multiplayer.disconnect.server_full" to "서버가 가득 찼습니다",
        "multiplayer.disconnect.flying" to "이 서버에서는 비행할 수 없습니다",
        "multiplayer.disconnect.timeout" to "시간 초과",
        "disconnect.timeout" to "시간 초과",
        "disconnect.closed" to "연결이 닫혔습니다",
        "disconnect.lost" to "연결이 끊어졌습니다",
        "disconnect.genericReason" to "%s",
        "disconnect.disconnected" to "서버와 연결이 끊어졌습니다",
        "chat.square_brackets" to "[%s]",
        "chat.disabled.missingProfileKey" to "채팅이 꺼져 있습니다: 이 서버는 서명된 채팅이 필요합니다 (명령어는 사용 가능)",
        "chat.disabled.options" to "채팅이 꺼져 있습니다",
    )

    fun get(key: String): String? {
        map[key]?.let { return it }
        // 사망 메시지의 "누구"(몹)·"무기"(아이템) 이름
        if (key.startsWith("entity.minecraft.")) {
            val id = key.removePrefix("entity.minecraft.")
            return ENTITIES[id] ?: id.replace('_', ' ')
        }
        if (key.startsWith("item.minecraft.") || key.startsWith("block.minecraft.")) {
            return ItemNames.name(key.substringAfter(".minecraft."))
        }
        return null
    }

    /** 자주 보이는 몹 이름 (마인크래프트 한국어 번역 기준) */
    private val ENTITIES = mapOf(
        "player" to "플레이어", "zombie" to "좀비", "zombie_villager" to "좀비 주민", "husk" to "허스크", "drowned" to "드라운드",
        "skeleton" to "스켈레톤", "stray" to "스트레이", "bogged" to "보그드", "wither_skeleton" to "위더 스켈레톤",
        "creeper" to "크리퍼", "spider" to "거미", "cave_spider" to "동굴 거미", "enderman" to "엔더맨", "endermite" to "엔더마이트",
        "witch" to "마녀", "slime" to "슬라임", "magma_cube" to "마그마 큐브", "blaze" to "블레이즈", "ghast" to "가스트",
        "piglin" to "피글린", "piglin_brute" to "난폭한 피글린", "zombified_piglin" to "좀비 피글린", "hoglin" to "호글린", "zoglin" to "조글린",
        "wither" to "위더", "ender_dragon" to "엔더 드래곤", "guardian" to "가디언", "elder_guardian" to "엘더 가디언",
        "phantom" to "팬텀", "pillager" to "약탈자", "vindicator" to "변명자", "evoker" to "소환사", "ravager" to "파괴수", "vex" to "벡스",
        "illusioner" to "환술사", "silverfish" to "좀벌레", "shulker" to "셜커", "warden" to "워든", "breeze" to "브리즈",
        "bee" to "벌", "wolf" to "늑대", "iron_golem" to "철 골렘", "polar_bear" to "북극곰", "llama" to "라마", "trader_llama" to "상인 라마",
        "dolphin" to "돌고래", "panda" to "판다", "goat" to "염소", "pufferfish" to "복어",
        "arrow" to "화살", "spectral_arrow" to "분광 화살", "trident" to "삼지창", "fireball" to "화염구", "small_fireball" to "작은 화염구",
        "tnt" to "TNT", "end_crystal" to "엔드 수정", "lightning_bolt" to "번개", "falling_block" to "떨어지는 블록", "potion" to "물약",
    )
}
