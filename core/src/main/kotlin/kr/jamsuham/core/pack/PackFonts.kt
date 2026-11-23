package kr.jamsuham.core.pack

import kr.jamsuham.core.tr
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.jamsuham.core.CoreLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * 서버 리소스팩의 "그림 글자"(비트맵 글꼴)만 골라 읽는다.
 *
 * 서버는 칭호·아이콘을 특정 글자(개인 사용 영역 문자, 한자 등)로 보내고, 리소스팩의 글꼴 설정
 * (assets/<ns>/font/<이름>.json 의 bitmap 공급자)이 그 글자를 그림 조각으로 바꾼다. 앱은 그 표와 그림만 있으면 된다.
 *
 * 리소스팩은 수십~수백 MB 라서 통째로 받지 않는다: zip 끝의 목록(central directory)만 받고, 글꼴 json 과
 * 그 json 이 가리키는 png 만 HTTP Range 로 골라 받는다. 서버가 Range 를 지원하지 않으면 [maxFullBytes] 까지만 전체를 받는다.
 * 결과는 [cacheDir]/<키>/ 에 저장해 다음 접속부터는 받지 않는다.
 */
object PackFonts {
    /** 리소스팩 서버가 부분 다운로드(HTTP Range)를 지원하지 않음 (앱이 이유를 따로 보여 줄 때 구분용) */
    class NoRangeException(msg: String) : IOException(msg)

    /** 그림 글자 하나: [image] 그림 파일(zip 경로)의 [col]열 [row]행 칸. 칸 크기 = 그림 크기 / [cols]·[rows] */
    data class Glyph(
        val image: String,
        val col: Int,
        val row: Int,
        val cols: Int,
        val rows: Int,
        /** 글자 높이 (바닐라 기본 글꼴 = 8) */
        val height: Int,
        /** 기준선 위 높이 */
        val ascent: Int,
    )

    /** 글꼴 id("minecraft:default", "server:rank" …) → (코드 포인트 → 그림 글자) + 그림 파일 */
    /**
     * [spaces] "space" 공급자의 글자별 가로 이동(픽셀, 음수 = 뒤로 당김). 서버는 배경 띠 그림 뒤에 음수 빈칸을 넣어
     * 그 위에 글자를 겹쳐 쓴다 → 앱은 겹쳐 그릴 수 없으므로 이런 배경 그림을 알아보고 건너뛰는 데 쓴다.
     */
    class FontSet(
        val fonts: Map<String, Map<Int, Glyph>>,
        val images: Map<String, ByteArray>,
        val report: String? = null,
        val spaces: Map<String, Map<Int, Float>> = emptyMap(),
    ) {
        val glyphCount: Int get() = fonts.values.sumOf { it.size }

        /** [font] 의 [cp] 가 빈칸 글자면 가로 이동(음수 = 뒤로), 아니면 null */
        fun advance(font: String?, cp: Int): Float? = spaces[font ?: DEFAULT_FONT]?.get(cp)

        fun glyph(font: String?, cp: Int): Glyph? = fonts[font ?: DEFAULT_FONT]?.get(cp)

        /** 모든 글꼴을 합친 표 (기본 글꼴 우선). 개인 사용 영역 글자는 팩 안에서 보통 하나뿐이라, 글꼴이 안 맞을 때 찾아 쓴다 */
        private val any: Map<Int, Glyph> by lazy {
            val m = HashMap<Int, Glyph>()
            fonts[DEFAULT_FONT]?.let { m.putAll(it) }
            for ((id, f) in fonts) if (id != DEFAULT_FONT) for ((cp, g) in f) m.putIfAbsent(cp, g)
            m
        }

        /** [cp] 가 들어 있는 아무 글꼴의 그림 글자 */
        fun glyphAny(cp: Int): Glyph? = any[cp]

        companion object {
            val EMPTY = FontSet(emptyMap(), emptyMap())

            /** 여러 리소스팩을 겹친다 (뒤의 팩이 같은 글자를 덮어씀, 바닐라와 같은 순서) */
            fun merge(sets: List<FontSet>): FontSet {
                if (sets.isEmpty()) return EMPTY
                if (sets.size == 1) return sets[0]
                val fonts = HashMap<String, HashMap<Int, Glyph>>()
                val images = HashMap<String, ByteArray>()
                val spaces = HashMap<String, HashMap<Int, Float>>()
                sets.forEachIndexed { i, set ->
                    for ((font, map) in set.spaces) spaces.getOrPut(font) { HashMap() }.putAll(map)
                    // 다른 팩의 같은 경로 그림과 섞이지 않게 팩 번호를 붙인다
                    for ((path, bytes) in set.images) images["$i/$path"] = bytes
                    for ((font, map) in set.fonts) {
                        val m = fonts.getOrPut(font) { HashMap() }
                        for ((cp, g) in map) m[cp] = g.copy(image = "$i/${g.image}")
                    }
                }
                return FontSet(fonts, images, null, spaces)
            }
        }
    }

    const val DEFAULT_FONT = "minecraft:default"
    private const val CACHE_VERSION = 6

    /** 부분 다운로드로 받는 양의 상한 (글꼴 json + png 합계) */
    private const val MAX_RANGED_BYTES = 40L * 1024 * 1024

    /** 그림 파일 하나의 상한 (이상한 팩이 거대한 그림을 쓰는 경우) */
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    private val gson = Gson()

    /**
     * 리소스팩 [url] 의 그림 글자를 읽는다 (네트워크 사용 - 백그라운드에서 호출).
     * [hash] 서버가 알려 준 SHA-1 (캐시 키, 없으면 주소로 만듦). [maxFullBytes] Range 를 지원하지 않을 때 전체를 받을 상한 (0 = 받지 않음).
     */
    @Throws(IOException::class)
    fun load(url: String, hash: String?, cacheDir: File, maxFullBytes: Long, log: CoreLog = CoreLog.STDOUT): FontSet {
        val key = cacheKey(url, hash)
        // 읽는 방식이 바뀌면 예전 캐시를 쓰지 않도록 판 번호를 붙인다 (v2: 오버레이 폴더 지원)
        val dir = File(cacheDir, cacheName(url, hash))
        readCache(dir)?.let { return it }

        val started = System.currentTimeMillis()
        val source: ZipSource = try {
            RangedZip.open(url, maxFullBytes, File(cacheDir, "$key.download"))
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException(tr("리소스팩을 열지 못했습니다: ${t.message}", "Couldn't open the resource pack: ${t.message}"), t)
        }
        try {
            var set = extract(source, log)
            // 부분 다운로드로 zip 목록을 하나도 못 읽었으면(보호된 팩 등), 받을 수 있는 크기일 때 통째로 받아
            // 자바 zip 읽기(마인크래프트와 같은 방식)로 다시 시도한다
            if (source is RangedZip && source.empty && source.total > maxFullBytes) {
                set = FontSet(set.fonts, set.images, spaces = set.spaces, report = set.report + tr("\n통째로 받기: ", "\nFull download: ") +
                    if (maxFullBytes <= 0) tr("와이파이에서만 받기라서 건너뜀 (와이파이에서 다시 접속하면 시도)", "skipped: Wi-Fi only (will try when you reconnect on Wi-Fi)") else tr("너무 커서 건너뜀 (${source.total / 1048576}MB)", "skipped: too large (${source.total / 1048576}MB)"))
            }
            if (source is RangedZip && source.empty && source.total <= maxFullBytes) {
                log.i("부분 다운로드로 zip 목록을 읽지 못해 리소스팩을 통째로 받습니다 (${source.total / 1048576}MB)")
                val full = try {
                    FullZip.download(open(url, null), maxFullBytes, File(cacheDir, "$key.download"))
                } catch (t: Throwable) {
                    set = FontSet(set.fonts, set.images, spaces = set.spaces, report = set.report + tr("\n통째로 받기 실패: ${t.message ?: t.javaClass.simpleName}", "\nFull download failed: ${t.message ?: t.javaClass.simpleName}"))
                    null
                }
                if (full != null) {
                    try {
                        val again = extract(full, log)
                        set = FontSet(again.fonts, again.images, spaces = again.spaces, report = tr("통째로 받아 다시 읽음\n", "Downloaded in full and re-read\n") + again.report + tr("\n[부분 다운로드 때]\n", "\n[With partial download]\n") + set.report)
                    } finally {
                        full.close()
                    }
                }
            }
            val secs = (System.currentTimeMillis() - started) / 1000.0
            set = FontSet(set.fonts, set.images, spaces = set.spaces, report = (set.report ?: "") + tr("\n받는 데 ${"%.1f".format(secs)}초", "\nDownload took ${"%.1f".format(secs)}s") + (source.stats?.let { " ($it)" } ?: ""))
            log.i("리소스팩 그림 글자 ${set.glyphCount}개, ${"%.1f".format(secs)}초 ${source.stats ?: ""}")
            // 그림 글자가 없으면 저장하지 않는다 (읽는 방법을 고친 뒤에도 실패한 결과를 계속 보여 주지 않게, 다음 접속 때 다시 시도)
            if (set.glyphCount > 0) writeCache(dir, set)
            return set
        } finally {
            source.close()
        }
    }

    /** zip 에서 글꼴 json 들을 읽고, bitmap 공급자가 쓰는 그림만 꺼낸다 */
    private fun extract(raw: ZipSource, log: CoreLog): FontSet {
        val notes = ArrayList<String>()
        raw.note?.let { notes += it }
        val zip = Overlays.apply(raw, log, notes)
        val names = zip.names()
        val fontRe = Regex("^assets/([^/]+)/font/(.+)\\.json$")
        // 글꼴 id → 공급자 목록 (참조 공급자는 나중에 펼침)
        val providers = HashMap<String, List<JsonObject>>()
        val errors = ArrayList<String>()
        val missing = LinkedHashSet<String>()
        val types = java.util.TreeMap<String, Int>()
        // 글꼴 설정들을 한꺼번에 미리 받는다
        zip.prefetch(names.filter { fontRe.containsMatchIn(it) })
        for (name in names) {
            val m = fontRe.find(name) ?: continue
            val id = "${m.groupValues[1]}:${m.groupValues[2]}"
            try {
                val json = parseJson(zip.read(name, 2L * 1024 * 1024)).asJsonObject
                providers[id] = json.getAsJsonArray("providers")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()
            } catch (t: Throwable) {
                log.w("글꼴 설정을 읽지 못했습니다: $name (${t.message})")
                errors += tr("글꼴 설정 읽기 실패 $name: ${t.message}", "Failed to read font config $name: ${t.message}")
            }
        }
        val fonts = HashMap<String, MutableMap<Int, Glyph>>()
        val images = HashMap<String, ByteArray>()
        val nameSet = names.toHashSet()
        // 그림 글자 공급자가 쓰는 그림들도 한꺼번에 미리 받는다
        zip.prefetch(
            providers.values.flatten().filter { it.get("type")?.asString == "bitmap" }
                .mapNotNull { p -> p.get("file")?.takeIf { it.isJsonPrimitive }?.asString?.let { texturePath(it) } }
                .filter { it in nameSet }.toSet(),
        )

        fun addProviders(target: MutableMap<Int, Glyph>, spaceTarget: MutableMap<Int, Float>, list: List<JsonObject>, depth: Int) {
            if (depth > 8) return
            for (p in list) {
                val type = p.get("type")?.asString ?: "?"
                if (depth == 0) types[type] = (types[type] ?: 0) + 1
                when (type) {
                    "reference" -> providers[normalize(p.get("id")?.asString ?: continue)]?.let { addProviders(target, spaceTarget, it, depth + 1) }
                    "bitmap" -> {
                        val file = p.get("file")?.asString ?: continue
                        val path = texturePath(file)
                        if (path !in nameSet) { missing += path; continue }
                        val rows = p.getAsJsonArray("chars")?.map { it.asString } ?: continue
                        if (rows.isEmpty()) continue
                        val cols = rows.maxOf { it.codePointCount(0, it.length) }
                        if (cols <= 0) continue
                        val height = p.get("height")?.asInt ?: 8
                        val ascent = p.get("ascent")?.asInt ?: 7
                        var used = false
                        rows.forEachIndexed { r, row ->
                            var c = 0
                            var i = 0
                            while (i < row.length) {
                                val cp = row.codePointAt(i)
                                // 0 은 빈 칸, 이미 앞 공급자가 정한 글자는 그대로 (바닐라: 먼저 나온 공급자가 우선)
                                if (cp != 0 && cp != 0x20 && cp !in target) {
                                    target[cp] = Glyph(path, c, r, cols, rows.size, height, ascent)
                                    used = true
                                }
                                c++
                                i += Character.charCount(cp)
                            }
                        }
                        if (used && path !in images) {
                            try {
                                images[path] = zip.read(path, MAX_IMAGE_BYTES)
                            } catch (t: Throwable) {
                                log.w("글꼴 그림을 읽지 못했습니다: $path (${t.message})")
                                errors += tr("그림 읽기 실패 $path: ${t.message}", "Failed to read image $path: ${t.message}")
                            }
                        }
                    }
                    "space" -> {
                        // 빈칸 글자의 가로 이동 (먼저 나온 공급자가 우선)
                        val adv = p.getAsJsonObject("advances") ?: continue
                        for ((k, v) in adv.entrySet()) {
                            if (k.isEmpty()) continue
                            val cp = k.codePointAt(0)
                            val a = try { v.asFloat } catch (_: Throwable) { continue }
                            if (cp !in spaceTarget) spaceTarget[cp] = a
                        }
                    }
                    else -> {} // ttf, unihex 등은 그림이 아니라 건너뜀
                }
            }
        }

        val spaces = HashMap<String, Map<Int, Float>>()
        for ((id, list) in providers) {
            val target = HashMap<Int, Glyph>()
            val spaceTarget = HashMap<Int, Float>()
            addProviders(target, spaceTarget, list, 0)
            if (spaceTarget.isNotEmpty()) spaces[id] = spaceTarget
            // 그림을 못 읽은 글자는 뺀다
            target.entries.removeIf { it.value.image !in images }
            if (target.isNotEmpty()) fonts[id] = target
        }
        // 그림 글자가 없을 때 이유를 찾을 수 있도록 팩 안 모습을 요약한다 (상태 탭에 보임)
        val report = buildString {
            append(tr("파일 ${names.size}개", "${names.size} files"))
            val tops = names.map { it.substringBefore('/') }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
            append(" (" + tops.joinToString { "${it.key} ${it.value}" } + ")")
            notes.forEach { append("\n").append(it) }
            append(tr("\n글꼴 설정 ${providers.size}개, 기본 글꼴 그림 글자 ${fonts[DEFAULT_FONT]?.size ?: 0}개", "\n${providers.size} font configs, ${fonts[DEFAULT_FONT]?.size ?: 0} glyphs in the default font"))
            if (providers.isNotEmpty()) append(": " + providers.keys.sorted().take(8).joinToString() + if (providers.size > 8) " …" else "")
            if (types.isNotEmpty()) append(tr("\n공급자 종류: ", "\nProvider types: ") + types.entries.joinToString { "${it.key} ${it.value}" })
            if (missing.isNotEmpty()) append(tr("\n없는 그림 ${missing.size}개: ", "\n${missing.size} missing images: ") + missing.take(3).joinToString() + if (missing.size > 3) " …" else "")
            errors.take(3).forEach { append("\n").append(it) }
            if (providers.isEmpty()) {
                val jsons = names.filter { it.endsWith(".json") && "font" in it }.take(3)
                if (jsons.isNotEmpty()) append(tr("\n글꼴처럼 보이는 파일: ", "\nFiles that look like fonts: ") + jsons.joinToString())
            }
        }
        return FontSet(fonts, images, report, spaces)
    }

    private fun cacheKey(url: String, hash: String?): String =
        hash?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.lowercase() ?: sha1(url)

    /** 이 팩의 그림 글자를 이미 받아 두었는지 (받지 않고 기기에서 바로 읽을 수 있는지) */
    fun isCached(cacheDir: File, url: String, hash: String?): Boolean = File(File(cacheDir, cacheName(url, hash)), "fonts.json").isFile

    /** 이 팩을 저장하는 폴더 이름 ([cacheDir] 아래) */
    fun cacheName(url: String, hash: String?): String = "${cacheKey(url, hash)}-v$CACHE_VERSION"

    /**
     * [keep] 에 든 팩(주소, SHA-1)만 남기고 [cacheDir] 의 다른 팩·받다 만 파일을 지운다.
     * 다른 서버로 옮기면 예전 서버의 팩을 지워 저장 공간을 아낀다. 지운 바이트 수를 돌려준다.
     */
    fun pruneCache(cacheDir: File, keep: Collection<Pair<String, String?>>): Long {
        val keepNames = keep.flatMap { (u, h) -> listOf(cacheName(u, h), "${cacheKey(u, h)}.download") }.toSet()
        var freed = 0L
        for (f in cacheDir.listFiles().orEmpty()) {
            if (f.name in keepNames) continue
            freed += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            f.deleteRecursively()
        }
        return freed
    }

    /** 마인크래프트처럼 너그럽게 읽는다 (BOM, 주석, 따옴표 없는 이름 등) */
    internal fun parseJson(bytes: ByteArray): com.google.gson.JsonElement {
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        val reader = com.google.gson.stream.JsonReader(java.io.StringReader(text))
        reader.isLenient = true
        return JsonParser.parseReader(reader)
    }

    /**
     * pack.mcmeta 의 오버레이(1.20.2+): 특정 버전용 폴더(예: "overlay_1_21/assets/...")가 기본 assets 를 덮어쓴다.
     * 앱은 1.21.4(팩 형식 46) 이상을 기준으로, 46 이상을 지원하는 오버레이를 적어 둔 순서대로 겹친다.
     */
    private object Overlays {
        private const val FORMAT = 46

        fun apply(zip: ZipSource, log: CoreLog, notes: MutableList<String>): ZipSource {
            val names = zip.names()
            if ("pack.mcmeta" !in names) return zip
            val dirs = try {
                val meta = parseJson(zip.read("pack.mcmeta", 1024 * 1024)).asJsonObject
                val entries = meta.getAsJsonObject("overlays")?.getAsJsonArray("entries") ?: return zip
                entries.mapNotNull { el ->
                    val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val dir = o.get("directory")?.asString?.trim('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    if (supportsNewer(o)) dir else { notes += tr("오버레이 건너뜀(옛 버전용): $dir", "Overlay skipped (for older versions): $dir"); null }
                }
            } catch (t: Throwable) {
                log.w("pack.mcmeta 를 읽지 못했습니다: ${t.message}")
                return zip
            }
            if (dirs.isEmpty()) return zip
            // 논리 경로(assets/...) → 실제 zip 경로. 뒤에 적힌 오버레이가 우선
            val map = LinkedHashMap<String, String>()
            for (n in names) if (n.startsWith("assets/")) map[n] = n
            for (d in dirs) {
                val prefix = "$d/"
                for (n in names) if (n.startsWith(prefix + "assets/")) map[n.substring(prefix.length)] = n
            }
            log.i("리소스팩 오버레이 ${dirs.size}개 적용: ${dirs.joinToString()}")
            notes += tr("오버레이 적용: ${dirs.joinToString()}", "Overlays applied: ${dirs.joinToString()}")
            return object : ZipSource {
                override fun names(): List<String> = map.keys.toList()
                override fun read(name: String, max: Long): ByteArray = zip.read(map[name] ?: name, max)
                override fun close() = zip.close()
                override fun prefetch(names: Collection<String>) = zip.prefetch(names.map { map[it] ?: it })
                override val stats: String? get() = zip.stats
            }
        }

        /** formats: 숫자 / [최소, 최대] / {min_inclusive, max_inclusive}. 1.21.9+ 는 min_format/max_format */
        private fun supportsNewer(o: JsonObject): Boolean {
            fun num(e: com.google.gson.JsonElement?): Int? = when {
                e == null || e.isJsonNull -> null
                e.isJsonPrimitive -> e.asNumber.toInt()
                e.isJsonArray && e.asJsonArray.size() > 0 -> e.asJsonArray[0].asNumber.toInt() // [주, 부] 형식
                else -> null
            }
            val max: Int? = o.get("formats")?.let { f ->
                when {
                    f.isJsonPrimitive -> f.asInt
                    f.isJsonArray -> f.asJsonArray.lastOrNull()?.asInt
                    f.isJsonObject -> num(f.asJsonObject.get("max_inclusive"))
                    else -> null
                }
            } ?: num(o.get("max_format"))
            return max == null || max >= FORMAT
        }
    }

    /** "ns:font/icons.png" → "assets/ns/textures/font/icons.png" */
    private fun texturePath(file: String): String {
        val id = normalize(file)
        val ns = id.substringBefore(':')
        return "assets/$ns/textures/${id.substringAfter(':')}"
    }

    private fun normalize(id: String): String = if (':' in id) id else "minecraft:$id"

    // ------------------------------------------------------------ 캐시

    private class CacheIndex(
        val fonts: Map<String, Map<String, Glyph>> = emptyMap(),
        val report: String? = null,
        val spaces: Map<String, Map<String, Float>>? = null,
    )

    private fun readCache(dir: File): FontSet? {
        val index = File(dir, "fonts.json")
        if (!index.isFile) return null
        return try {
            val data = gson.fromJson(index.readText(), CacheIndex::class.java) ?: return null
            val images = HashMap<String, ByteArray>()
            val fonts = data.fonts.mapValues { (_, m) ->
                m.mapKeys { it.key.toInt() }.onEach { (_, g) ->
                    if (g.image !in images) images[g.image] = File(dir, "img/" + safeName(g.image)).readBytes()
                }
            }
            val spaces = data.spaces.orEmpty().mapValues { (_, m) -> m.mapKeys { it.key.toInt() } }
            FontSet(fonts, images, data.report, spaces)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCache(dir: File, set: FontSet) {
        try {
            File(dir, "img").mkdirs()
            for ((path, bytes) in set.images) File(dir, "img/" + safeName(path)).writeBytes(bytes)
            val index = CacheIndex(
                set.fonts.mapValues { (_, m) -> m.mapKeys { it.key.toString() } },
                set.report,
                set.spaces.mapValues { (_, m) -> m.mapKeys { it.key.toString() } },
            )
            File(dir, "fonts.json").writeText(gson.toJson(index))
        } catch (_: Throwable) {
        }
    }

    private fun safeName(path: String) = path.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * 보호된 리소스팩의 망가뜨린 PNG 를 표준 PNG 로 고친다 (안드로이드가 읽을 수 있게).
     * 마인크래프트의 그림 읽기(stb_image)는 조각 검사값(CRC)·압축 검사값(adler32)을 보지 않아서, 보호 도구가 이 값들을
     * 틀리게 바꿔도 게임에서는 열리지만 안드로이드(libpng)는 거부한다. 조각을 다시 모아 IDAT 를 풀었다 다시 묶고 검사값을 새로 쓴다.
     * 고칠 수 없으면 null.
     */
    fun repairPng(png: ByteArray): ByteArray? {
        try {
            if (png.size < 8 || png[1] != 'P'.code.toByte() || png[2] != 'N'.code.toByte() || png[3] != 'G'.code.toByte()) return null
            fun int(o: Int) = ((png[o].toInt() and 0xFF) shl 24) or ((png[o + 1].toInt() and 0xFF) shl 16) or
                ((png[o + 2].toInt() and 0xFF) shl 8) or (png[o + 3].toInt() and 0xFF)
            val chunks = ArrayList<Pair<String, ByteArray>>()
            val idat = ByteArrayOutputStream()
            var p = 8
            while (p + 8 <= png.size) {
                val len = int(p)
                if (len < 0 || p + 8 + len > png.size) break
                val type = String(png, p + 4, 4, Charsets.ISO_8859_1)
                val data = png.copyOfRange(p + 8, p + 8 + len)
                when (type) {
                    "IDAT" -> idat.write(data)
                    "IEND" -> {}
                    // 그림에 꼭 필요한 조각만 남긴다 (이상한 보조 조각이 문제를 일으키지 않게)
                    "IHDR", "PLTE", "tRNS" -> chunks += type to data
                }
                if (type == "IEND") break
                p += 12 + len
            }
            if (chunks.firstOrNull()?.first != "IHDR" || idat.size() < 2) return null
            // zlib 머리(2바이트)를 건너뛰고 deflate 만 푼다 (끝의 adler32 는 검사하지 않음)
            val z = idat.toByteArray()
            val raw = ByteArrayOutputStream()
            val inf = Inflater(true)
            try {
                inf.setInput(z, 2, z.size - 2)
                val buf = ByteArray(64 * 1024)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    raw.write(buf, 0, n)
                    if (raw.size() > 64 * 1024 * 1024) return null
                }
            } finally {
                inf.end()
            }
            val def = java.util.zip.Deflater()
            val packed = ByteArrayOutputStream()
            try {
                def.setInput(raw.toByteArray())
                def.finish()
                val buf = ByteArray(64 * 1024)
                while (!def.finished()) packed.write(buf, 0, def.deflate(buf))
            } finally {
                def.end()
            }
            val out = ByteArrayOutputStream()
            out.write(png, 0, 8)
            fun chunk(type: String, data: ByteArray) {
                val t = type.toByteArray(Charsets.ISO_8859_1)
                out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array())
                out.write(t)
                out.write(data)
                val crc = java.util.zip.CRC32().apply { update(t); update(data) }
                out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
            }
            for ((t, d) in chunks) chunk(t, d)
            chunk("IDAT", packed.toByteArray())
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        } catch (_: Throwable) {
            return null
        }
    }

    /** PNG 의 가로·세로 (IHDR). 그림을 풀지 않고 크기만 안다 */
    fun pngSize(png: ByteArray): Pair<Int, Int>? {
        if (png.size < 24 || png[1] != 'P'.code.toByte() || png[2] != 'N'.code.toByte()) return null
        fun int(o: Int) = ((png[o].toInt() and 0xFF) shl 24) or ((png[o + 1].toInt() and 0xFF) shl 16) or
            ((png[o + 2].toInt() and 0xFF) shl 8) or (png[o + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }

    // ------------------------------------------------------------ zip 읽기

    private interface ZipSource {
        fun names(): List<String>
        fun read(name: String, max: Long): ByteArray
        fun close()
        /** 목록을 읽다 생긴 이상한 점 (진단용) */
        val note: String? get() = null
        /** 곧 읽을 파일들을 미리 한꺼번에 받아 둔다 (부분 다운로드일 때만 의미 있음) */
        fun prefetch(names: Collection<String>) {}
        /** 받은 양·요청 수 (진단용) */
        val stats: String? get() = null
    }

    /** 전체를 받아 파일로 둔 zip */
    private class FullZip(private val file: File) : ZipSource {
        private val zip = ZipFile(file)
        override fun names(): List<String> = zip.entries().toList().map { it.name }
        override fun read(name: String, max: Long): ByteArray {
            val e = zip.getEntry(name) ?: throw IOException(tr("없음: $name", "Missing: $name"))
            // 적힌 크기는 믿지 않고 읽으면서 max 로 제한
            return zip.getInputStream(e).use { input -> ByteArrayOutputStream().also { copyLimited(input, it, max) }.toByteArray() }
        }
        override fun close() {
            zip.close()
            file.delete()
        }

        companion object {
            /** 이미 열린 연결(전체 응답)에서 받아 파일로 저장 — 같은 팩을 두 번 받지 않게 */
            fun download(conn: HttpURLConnection, max: Long, to: File): FullZip {
                try {
                    if (conn.responseCode !in 200..299) throw IOException(tr("리소스팩 다운로드 실패 (HTTP ${conn.responseCode})", "Resource pack download failed (HTTP ${conn.responseCode})"))
                    val len = conn.contentLengthLong
                    if (len > max) throw IOException(tr("리소스팩이 너무 큽니다 (${len / 1048576}MB)", "The resource pack is too large (${len / 1048576}MB)"))
                    to.parentFile?.mkdirs()
                    conn.inputStream.use { input -> to.outputStream().use { out -> copyLimited(input, out, max) } }
                } finally {
                    conn.disconnect()
                }
                return FullZip(to)
            }
        }
    }

    /** HTTP Range 로 필요한 부분만 받는 zip */
    private class RangedZip(private val url: String, val total: Long) : ZipSource {
        /** [offset] 바로잡은 위치, [rawOffset] 목록에 적힌 위치 (바로잡은 곳에 파일이 없으면 이쪽을 시도) */
        private class Entry(val method: Int, val compSize: Long, val size: Long, val offset: Long, val rawOffset: Long = offset)

        private val entries = LinkedHashMap<String, Entry>()
        private val fetched = java.util.concurrent.atomic.AtomicLong()
        private val requests = java.util.concurrent.atomic.AtomicInteger()
        /** 미리 받아 둔 구간 (시작 위치, 내용) */
        private val blocks = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, ByteArray>>()

        override val stats: String get() = tr("요청 ${requests.get()}번, ${"%.1f".format(fetched.get() / 1048576.0)}MB", "${requests.get()} requests, ${"%.1f".format(fetched.get() / 1048576.0)}MB")

        /**
         * 파일마다 따로 요청하면 왕복 시간 × 수백 번이 걸린다. 팩 안에서 가까이 붙은 파일들은 한 요청으로 묶고(사이 간격 64KB 이하,
         * 한 묶음 4MB 이하), 묶음들을 동시에 6개씩 받는다. 실패한 묶음은 나중에 파일별로 다시 받는다.
         */
        override fun prefetch(names: Collection<String>) {
            val spans = names.mapNotNull { entries[it] }
                .map { it.offset to (it.offset + 30 + 1024 + it.compSize).coerceAtMost(total) }
                .filter { (a, b) -> blocks.none { (s, d) -> a >= s && b <= s + d.size } }
                .sortedBy { it.first }
            if (spans.isEmpty()) return
            fun group(gap: Long): List<LongArray> {
                val groups = ArrayList<LongArray>()
                for ((a, b) in spans) {
                    val last = groups.lastOrNull()
                    if (last != null && a - last[1] <= gap && b - last[0] <= 4L * 1024 * 1024) last[1] = maxOf(last[1], b)
                    else groups += longArrayOf(a, b)
                }
                return groups
            }
            // 묶느라 사이의 필요 없는 부분까지 너무 많이 받게 되면(16MB 넘게) 간격을 줄여 거의 필요한 부분만 받는다
            val budget = 16L * 1024 * 1024
            var groups = group(64 * 1024)
            if (groups.sumOf { it[1] - it[0] } > budget) groups = group(4 * 1024)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(6, groups.size))
            try {
                for (g in groups) pool.execute {
                    try {
                        blocks += g[0] to range(g[0], g[1] - 1)
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                pool.shutdown()
                pool.awaitTermination(3, java.util.concurrent.TimeUnit.MINUTES)
            }
        }

        /** [from]~[to] 를 미리 받아 둔 구간에서 꺼내고, 없으면 새로 받는다 */
        private fun bytes(from: Long, to: Long): ByteArray {
            val end = to.coerceAtMost(total - 1)
            for ((s, d) in blocks) {
                if (from >= s && end < s + d.size) return d.copyOfRange((from - s).toInt(), (end - s + 1).toInt())
            }
            return range(from, end)
        }
        override var note: String? = null

        override fun names(): List<String> = entries.keys.toList()

        /** 목록을 하나도 못 읽음 */
        val empty: Boolean get() = entries.isEmpty()

        override fun read(name: String, max: Long): ByteArray {
            val e = entries[name] ?: throw IOException(tr("없음: $name", "Missing: $name"))
            // 압축 전 크기(e.size)는 보호된 팩이 틀리게 적기도 해서 보지 않는다 (풀 때 max 로 제한)
            if (e.compSize > max) throw IOException(tr("너무 큼: $name", "Too large: $name"))
            // 로컬 헤더(30바이트 + 이름 + extra) 뒤에 압축 데이터. extra 길이를 모르니 넉넉히 받는다
            var at = e.offset
            var head = bytes(at, at + 30 + 1024 + e.compSize - 1)
            if (u32(head, 0) != 0x04034b50L && e.rawOffset != e.offset) {
                at = e.rawOffset
                head = range(at, at + 30 + 1024 + e.compSize - 1)
            }
            if (u32(head, 0) != 0x04034b50L) throw IOException(tr("zip 형식이 이상합니다: $name", "Malformed zip: $name"))
            val start = 30 + u16(head, 26) + u16(head, 28)
            // 보호된 팩은 로컬 헤더에 큰 extra 를 넣기도 한다: 모자라면 정확한 길이로 다시 받는다
            if (start + e.compSize > head.size) head = bytes(at, at + start + e.compSize - 1)
            val data = head.copyOfRange(start, (start + e.compSize).toInt().coerceAtMost(head.size))
            return when (e.method) {
                0 -> data
                8 -> inflate(data, e.size, max)
                else -> throw IOException(tr("지원하지 않는 압축 방식 ${e.method}: $name", "Unsupported compression method ${e.method}: $name"))
            }
        }

        override fun close() {
            blocks.clear()
        }

        private fun range(from: Long, to: Long): ByteArray {
            val end = to.coerceAtMost(total - 1)
            requests.incrementAndGet()
            if (fetched.addAndGet(end - from + 1) > MAX_RANGED_BYTES) throw IOException(tr("받을 양이 너무 많습니다", "Too much data to download"))
            val conn = open(url, "bytes=$from-$end")
            try {
                if (conn.responseCode != 206) throw IOException(tr("부분 다운로드 실패 (HTTP ${conn.responseCode})", "Partial download failed (HTTP ${conn.responseCode})"))
                return conn.inputStream.use { readLimited(it, end - from + 1) }
            } finally {
                conn.disconnect()
            }
        }

        /**
         * zip 끝(EOCD)과 목록을 읽어 항목 표를 만든다.
         * 자바(마인크래프트)처럼 목록의 실제 위치는 "끝 기록 위치 - 목록 크기"로 계산한다. 보호된 팩은 끝 기록의
         * 목록 위치(cdOffset)를 일부러 틀리게 적거나 앞에 다른 데이터를 붙이고, 가짜 끝 기록을 주석에 숨기기도 한다.
         * 자바는 cdOffset 을 무시하고 실제 위치와의 차이(delta)를 각 파일 위치에 더하므로 똑같이 한다.
         * 끝에서부터 끝 기록 후보를 찾아, 계산한 위치에 실제로 목록이 있는 것을 쓴다.
         */
        private fun readDirectory() {
            val tailLen = minOf(total, 66_000L)
            val tail = range(total - tailLen, total - 1)
            val tailStart = total - tailLen
            var lastDeclared = 0
            val tried = ArrayList<String>()
            for (eocd in tail.size - 22 downTo 0) {
                if (u32(tail, eocd) != 0x06054b50L) continue
                val declared = u16(tail, eocd + 10)
                val cdSize = u32(tail, eocd + 12)
                val cdOffset = u32(tail, eocd + 16)
                lastDeclared = declared
                if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) throw IOException(tr("ZIP64 는 부분 다운로드를 지원하지 않습니다", "ZIP64 is not supported with partial download"))
                val eocdAbs = tailStart + eocd
                // 실제 위치(자바 방식) → 안 되면 적힌 위치
                tried += tr("끝기록@$eocdAbs 목록크기 $cdSize 위치 $cdOffset", "EOCD@$eocdAbs dir size $cdSize offset $cdOffset")
                for (cdStart in listOf(eocdAbs - cdSize, cdOffset).distinct()) {
                    if (cdStart < 0 || cdStart + cdSize > eocdAbs || cdSize < 46) { tried += tr("  @$cdStart 범위 밖", "  @$cdStart out of range"); continue }
                    val cd = if (cdStart >= tailStart) {
                        tail.copyOfRange((cdStart - tailStart).toInt(), (cdStart - tailStart + cdSize).toInt())
                    } else {
                        // 목록 첫 부분만 먼저 확인해서 엉뚱한 곳을 몇 MB 씩 받지 않게
                        val sig = range(cdStart, cdStart + 3)
                        if (u32(sig, 0) != 0x02014b50L) { tried += tr("  @$cdStart 시작 ", "  @$cdStart starts with ") + sig.joinToString(" ") { "%02X".format(it) }; continue }
                        range(cdStart, cdStart + cdSize - 1)
                    }
                    if (u32(cd, 0) != 0x02014b50L) { tried += tr("  @$cdStart 시작 ", "  @$cdStart starts with ") + cd.take(4).joinToString(" ") { "%02X".format(it) }; continue }
                    val delta = cdStart - cdOffset
                    val parsed = parseDirectory(cd, delta)
                    if (parsed == 0) { tried += tr("  @$cdStart 항목 0개", "  @$cdStart 0 entries"); continue }
                    note = when {
                        parsed < declared -> tr("zip 목록 ${declared}개 중 ${parsed}개만 읽음 (보호된 팩일 수 있음)", "Read only ${parsed} of ${declared} zip entries (may be a protected pack)")
                        delta != 0L -> tr("보호된 팩: zip 목록 위치를 바로잡아 읽음", "Protected pack: read after correcting the zip directory offset")
                        else -> null
                    }
                    return
                }
            }
            if (lastDeclared > 0) {
                note = tr("zip 목록 ${lastDeclared}개 중 0개만 읽음 (보호된 팩일 수 있음)\n파일 크기 $total\n", "Read 0 of ${lastDeclared} zip entries (may be a protected pack)\nFile size $total\n") + tried.take(6).joinToString("\n")
                return
            }
            throw IOException(tr("zip 목록을 찾지 못했습니다", "Couldn't find the zip directory"))
        }

        /** 목록을 읽어 항목 표에 넣는다. 읽은 개수를 돌려준다 */
        private fun parseDirectory(cd: ByteArray, delta: Long): Int {
            entries.clear()
            var p = 0
            var parsed = 0
            while (p + 46 <= cd.size && u32(cd, p) == 0x02014b50L) {
                val method = u16(cd, p + 10)
                val comp = u32(cd, p + 20)
                val size = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28)
                val extraLen = u16(cd, p + 30)
                val commentLen = u16(cd, p + 32)
                val offset = u32(cd, p + 42)
                if (p + 46 + nameLen > cd.size) break
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                // 같은 이름이 여러 번 있으면 뒤의 것 (자바 ZipFile 과 같음)
                if (!name.endsWith("/")) entries[name] = Entry(method, comp, size, offset + delta, offset)
                p += 46 + nameLen + extraLen + commentLen
                parsed++
            }
            return parsed
        }

        companion object {
            /**
             * Range 를 지원하면 목록까지 읽은 RangedZip. 지원하지 않으면(서버가 전체를 보냄) 그 응답을 그대로
             * [maxFull] 까지 받아 FullZip 으로 (0 이면 받지 않고 실패).
             */
            fun open(url: String, maxFull: Long, fullFile: File): ZipSource {
                val conn = open(url, "bytes=0-0")
                if (conn.responseCode == 206) {
                    // Content-Range: bytes 0-0/12345
                    val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                    conn.disconnect()
                    if (total == null || total < 22) throw IOException(tr("리소스팩 파일이 이상합니다", "The resource pack file is malformed"))
                    return RangedZip(url, total).also { it.readDirectory() }
                }
                if (maxFull <= 0) {
                    conn.disconnect()
                    throw NoRangeException(tr("이 리소스팩 서버는 부분 다운로드를 지원하지 않습니다", "This resource pack server doesn't support partial downloads"))
                }
                return FullZip.download(conn, maxFull, fullFile)
            }
        }
    }

    private fun open(url: String, range: String?): HttpURLConnection {
        var u = url
        repeat(5) {
            @Suppress("DEPRECATION")
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Jamsuham-Android")
            if (range != null) conn.setRequestProperty("Range", range)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw IOException(tr("리소스팩 주소가 이동했지만 새 주소가 없습니다", "The resource pack URL redirected without a new location"))
                u = URL(URL(u), loc).toString()
                return@repeat
            }
            return conn
        }
        throw IOException(tr("리소스팩 주소가 너무 많이 이동했습니다", "The resource pack URL redirected too many times"))
    }

    private fun copyLimited(input: InputStream, out: java.io.OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException(tr("리소스팩이 너무 큽니다", "The resource pack is too large"))
            out.write(buf, 0, n)
        }
    }

    private fun readLimited(input: InputStream, expected: Long): ByteArray {
        val out = ByteArrayOutputStream(expected.toInt().coerceIn(0, 1 shl 20))
        copyLimited(input, out, expected + 1024)
        return out.toByteArray()
    }

    /**
     * deflate 풀기. 목록에 적힌 크기([size])는 참고만 한다: 보호된 팩은 이 값을 틀리게 적는데 자바(마인크래프트)는
     * 검사하지 않고 끝까지 푼다. 대신 [max] 를 넘으면 멈춘다 (압축 폭탄 방지).
     */
    private fun inflate(data: ByteArray, size: Long, max: Long): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(size.toInt().coerceIn(1024, 1 shl 20))
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > max) throw IOException(tr("압축을 풀면 너무 큽니다", "Too large when decompressed"))
            }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
}
