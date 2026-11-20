package kr.jamsuham.core.text

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.viaversion.nbt.tag.Tag
import com.viaversion.viaversion.util.ComponentUtil
import kr.jamsuham.core.DeathInfo

/**
 * 사망 메시지 분석. 바닐라는 "death.attack.mob" 같은 번역 키와 인자(1 = 죽은 사람, 2 = 죽인 것, 3 = 무기)로 보낸다.
 * 그 키로 원인을, 인자로 누구·무기를 뽑는다. 플러그인이 직접 쓴 글이면 그 글을 그대로 쓴다.
 */
object DeathMessages {

    fun parse(tag: Tag?, atMs: Long = System.currentTimeMillis()): DeathInfo {
        if (tag == null) return DeathInfo(atMs)
        val json = try {
            ComponentUtil.tagToJsonString(tag)
        } catch (_: Throwable) {
            null
        } ?: return DeathInfo(atMs)
        return parseJson(json, atMs)
    }

    fun parseJson(json: String, atMs: Long = System.currentTimeMillis()): DeathInfo {
        val plain = try {
            ChatText.fromJson(json).plain.trim()
        } catch (_: Throwable) {
            ""
        }
        val root = try {
            JsonParser.parseString(json)
        } catch (_: Throwable) {
            null
        }
        val node = root?.let { findDeathNode(it, 0) }
            ?: return DeathInfo(atMs, message = plain.ifBlank { null })
        val key = node.get("translate").asString
        val args = node.getAsJsonArray("with") ?: node.getAsJsonArray("args") ?: JsonArray()
        val killer = argText(args, 1)
        val weapon = argText(args, 2)?.removeSurrounding("[", "]")?.ifBlank { null }
        return DeathInfo(atMs, killer = killer, cause = causeOf(key), weapon = weapon, message = plain.ifBlank { null })
    }

    /** 맨 위 또는 extra 안쪽에서 "death." 로 시작하는 번역 키를 가진 부분을 찾는다 */
    private fun findDeathNode(el: JsonElement, depth: Int): JsonObject? {
        if (depth > 4) return null
        if (el.isJsonArray) {
            for (c in el.asJsonArray) findDeathNode(c, depth + 1)?.let { return it }
            return null
        }
        if (!el.isJsonObject) return null
        val obj = el.asJsonObject
        val t = obj.get("translate")
        if (t != null && t.isJsonPrimitive && t.asString.startsWith("death.")) return obj
        obj.get("extra")?.let { findDeathNode(it, depth + 1)?.let { found -> return found } }
        return null
    }

    private fun argText(args: JsonArray, i: Int): String? {
        if (i >= args.size()) return null
        return try {
            ChatText.fromJson(args[i].toString()).plain.trim().ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    /** 채팅에 보이는 사망 문장 (번역표에 없는 death.* 키를 한국어로) */
    fun sentence(key: String, args: List<String>): String {
        val victim = args.getOrNull(0)?.ifBlank { null } ?: "누군가"
        val killer = args.getOrNull(1)?.ifBlank { null }
        val weapon = args.getOrNull(2)?.removeSurrounding("[", "]")?.ifBlank { null }
        val cause = causeOf(key)
        return buildString {
            append(victim)
            if (killer != null) append("님이 ").append(killer).append("에 의해 사망했습니다") else append("님이 사망했습니다")
            if (cause != null) append(" (").append(cause).append(")")
            if (weapon != null) append(" · 무기: ").append(weapon)
        }
    }

    /**
     * 번역 키 → 원인. "death.attack.arrow.item" 처럼 뒤에 .player/.item 이 붙은 변형은 앞부분으로 찾는다.
     * 원인이 따로 없는 키(generic 등)는 null.
     */
    fun causeOf(key: String): String? {
        var k = key.removePrefix("death.")
        if (k.startsWith("fell.")) return "높은 곳에서 떨어짐"
        while (true) {
            CAUSES[k]?.let { return it.ifBlank { null } }
            val cut = k.lastIndexOf('.')
            if (cut <= 0) return null
            k = k.substring(0, cut)
        }
    }

    private val CAUSES = mapOf(
        "attack.mob" to "공격당함",
        "attack.player" to "공격당함",
        "attack.arrow" to "화살에 맞음",
        "attack.trident" to "삼지창에 찔림",
        "attack.fireball" to "화염구에 맞음",
        "attack.witherSkull" to "위더 해골에 맞음",
        "attack.thrown" to "던진 물체에 맞음",
        "attack.indirectMagic" to "마법",
        "attack.magic" to "마법",
        "attack.even_more_magic" to "마법",
        "attack.explosion" to "폭발",
        "attack.fireworks" to "폭죽 폭발",
        "attack.badRespawnPoint" to "침대·리스폰 정박기 폭발",
        "attack.inFire" to "불에 탐",
        "attack.onFire" to "불에 탐",
        "attack.campfire" to "모닥불",
        "attack.lava" to "용암",
        "attack.hotFloor" to "마그마 블록",
        "attack.lightningBolt" to "번개",
        "attack.drown" to "익사",
        "attack.dryout" to "물 밖에서 말라 죽음",
        "attack.starve" to "굶주림",
        "attack.cactus" to "선인장",
        "attack.sweetBerryBush" to "달콤한 열매 덤불",
        "attack.fall" to "낙하",
        "attack.flyIntoWall" to "벽에 부딪힘",
        "attack.outOfWorld" to "세계 밖으로 떨어짐",
        "attack.outsideBorder" to "월드 경계 밖",
        "attack.inWall" to "벽 속에서 질식",
        "attack.cramming" to "끼여서 압사",
        "attack.freeze" to "얼어 죽음",
        "attack.wither" to "시듦",
        "attack.dragonBreath" to "드래곤의 숨결",
        "attack.anvil" to "떨어지는 모루",
        "attack.fallingBlock" to "떨어지는 블록",
        "attack.fallingStalactite" to "떨어지는 종유석",
        "attack.stalagmite" to "석순에 찔림",
        "attack.sting" to "쏘임",
        "attack.thorns" to "가시",
        "attack.sonic_boom" to "음파 폭발",
        "attack.mace_smash" to "철퇴",
        "attack.spit" to "침에 맞음",
        "attack.genericKill" to "",
        "attack.generic" to "",
        "attack.message_too_long" to "",
    )
}
