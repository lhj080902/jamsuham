package kr.jamsuham.core.pack

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
    class FontSet(val fonts: Map<String, Map<Int, Glyph>>, val images: Map<String, ByteArray>, val report: String? = null) {
        val glyphCount: Int get() = fonts.values.sumOf { it.size }

        fun glyph(font: String?, cp: Int): Glyph? = fonts[font ?: DEFAULT_FONT]?.get(cp)

        companion object {
            val EMPTY = FontSet(emptyMap(), emptyMap())

            /** 여러 리소스팩을 겹친다 (뒤의 팩이 같은 글자를 덮어씀, 바닐라와 같은 순서) */
            fun merge(sets: List<FontSet>): FontSet {
                if (sets.isEmpty()) return EMPTY
                if (sets.size == 1) return sets[0]
                val fonts = HashMap<String, HashMap<Int, Glyph>>()
                val images = HashMap<String, ByteArray>()
                sets.forEachIndexed { i, set ->
                    // 다른 팩의 같은 경로 그림과 섞이지 않게 팩 번호를 붙인다
                    for ((path, bytes) in set.images) images["$i/$path"] = bytes
                    for ((font, map) in set.fonts) {
                        val m = fonts.getOrPut(font) { HashMap() }
                        for ((cp, g) in map) m[cp] = g.copy(image = "$i/${g.image}")
                    }
                }
                return FontSet(fonts, images)
            }
        }
    }

    const val DEFAULT_FONT = "minecraft:default"
    private const val CACHE_VERSION = 3

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
        val key = hash?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.lowercase() ?: sha1(url)
        // 읽는 방식이 바뀌면 예전 캐시를 쓰지 않도록 판 번호를 붙인다 (v2: 오버레이 폴더 지원)
        val dir = File(cacheDir, "$key-v$CACHE_VERSION")
        readCache(dir)?.let { return it }

        val source: ZipSource = try {
            RangedZip.open(url, maxFullBytes, File(cacheDir, "$key.download"))
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException("리소스팩을 열지 못했습니다: ${t.message}", t)
        }
        try {
            val set = extract(source, log)
            writeCache(dir, set)
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
        for (name in names) {
            val m = fontRe.find(name) ?: continue
            val id = "${m.groupValues[1]}:${m.groupValues[2]}"
            try {
                val json = parseJson(zip.read(name, 2L * 1024 * 1024)).asJsonObject
                providers[id] = json.getAsJsonArray("providers")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject } ?: emptyList()
            } catch (t: Throwable) {
                log.w("글꼴 설정을 읽지 못했습니다: $name (${t.message})")
                errors += "글꼴 설정 읽기 실패 $name: ${t.message}"
            }
        }
        val fonts = HashMap<String, MutableMap<Int, Glyph>>()
        val images = HashMap<String, ByteArray>()
        val nameSet = names.toHashSet()

        fun addProviders(target: MutableMap<Int, Glyph>, list: List<JsonObject>, depth: Int) {
            if (depth > 8) return
            for (p in list) {
                val type = p.get("type")?.asString ?: "?"
                if (depth == 0) types[type] = (types[type] ?: 0) + 1
                when (type) {
                    "reference" -> providers[normalize(p.get("id")?.asString ?: continue)]?.let { addProviders(target, it, depth + 1) }
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
                                errors += "그림 읽기 실패 $path: ${t.message}"
                            }
                        }
                    }
                    else -> {} // space, ttf, unihex 등은 그림이 아니라 건너뜀
                }
            }
        }

        for ((id, list) in providers) {
            val target = HashMap<Int, Glyph>()
            addProviders(target, list, 0)
            // 그림을 못 읽은 글자는 뺀다
            target.entries.removeIf { it.value.image !in images }
            if (target.isNotEmpty()) fonts[id] = target
        }
        // 그림 글자가 없을 때 이유를 찾을 수 있도록 팩 안 모습을 요약한다 (상태 탭에 보임)
        val report = buildString {
            append("파일 ${names.size}개")
            val tops = names.map { it.substringBefore('/') }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
            append(" (" + tops.joinToString { "${it.key} ${it.value}" } + ")")
            notes.forEach { append("\n").append(it) }
            append("\n글꼴 설정 ${providers.size}개")
            if (providers.isNotEmpty()) append(": " + providers.keys.sorted().take(8).joinToString() + if (providers.size > 8) " …" else "")
            if (types.isNotEmpty()) append("\n공급자 종류: " + types.entries.joinToString { "${it.key} ${it.value}" })
            if (missing.isNotEmpty()) append("\n없는 그림 ${missing.size}개: " + missing.take(3).joinToString() + if (missing.size > 3) " …" else "")
            errors.take(3).forEach { append("\n").append(it) }
            if (providers.isEmpty()) {
                val jsons = names.filter { it.endsWith(".json") && "font" in it }.take(3)
                if (jsons.isNotEmpty()) append("\n글꼴처럼 보이는 파일: " + jsons.joinToString())
            }
        }
        return FontSet(fonts, images, report)
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
                    if (supportsNewer(o)) dir else { notes += "오버레이 건너뜀(옛 버전용): $dir"; null }
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
            notes += "오버레이 적용: ${dirs.joinToString()}"
            return object : ZipSource {
                override fun names(): List<String> = map.keys.toList()
                override fun read(name: String, max: Long): ByteArray = zip.read(map[name] ?: name, max)
                override fun close() = zip.close()
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

    private class CacheIndex(val fonts: Map<String, Map<String, Glyph>> = emptyMap(), val report: String? = null)

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
            FontSet(fonts, images, data.report)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCache(dir: File, set: FontSet) {
        try {
            File(dir, "img").mkdirs()
            for ((path, bytes) in set.images) File(dir, "img/" + safeName(path)).writeBytes(bytes)
            val index = CacheIndex(set.fonts.mapValues { (_, m) -> m.mapKeys { it.key.toString() } }, set.report)
            File(dir, "fonts.json").writeText(gson.toJson(index))
        } catch (_: Throwable) {
        }
    }

    private fun safeName(path: String) = path.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

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
    }

    /** 전체를 받아 파일로 둔 zip */
    private class FullZip(private val file: File) : ZipSource {
        private val zip = ZipFile(file)
        override fun names(): List<String> = zip.entries().toList().map { it.name }
        override fun read(name: String, max: Long): ByteArray {
            val e = zip.getEntry(name) ?: throw IOException("없음: $name")
            if (e.size > max) throw IOException("너무 큼: $name")
            return zip.getInputStream(e).use { it.readBytes() }
        }
        override fun close() {
            zip.close()
            file.delete()
        }

        companion object {
            /** 이미 열린 연결(전체 응답)에서 받아 파일로 저장 — 같은 팩을 두 번 받지 않게 */
            fun download(conn: HttpURLConnection, max: Long, to: File): FullZip {
                try {
                    if (conn.responseCode !in 200..299) throw IOException("리소스팩 다운로드 실패 (HTTP ${conn.responseCode})")
                    val len = conn.contentLengthLong
                    if (len > max) throw IOException("리소스팩이 너무 큽니다 (${len / 1048576}MB)")
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
    private class RangedZip(private val url: String, private val total: Long) : ZipSource {
        private class Entry(val method: Int, val compSize: Long, val size: Long, val offset: Long)

        private val entries = LinkedHashMap<String, Entry>()
        private var fetched = 0L
        override var note: String? = null

        override fun names(): List<String> = entries.keys.toList()

        override fun read(name: String, max: Long): ByteArray {
            val e = entries[name] ?: throw IOException("없음: $name")
            if (e.size > max || e.compSize > max) throw IOException("너무 큼: $name")
            // 로컬 헤더(30바이트 + 이름 + extra) 뒤에 압축 데이터. extra 길이를 모르니 넉넉히 받는다
            val head = range(e.offset, e.offset + 30 + 1024 + e.compSize - 1)
            if (u32(head, 0) != 0x04034b50L) throw IOException("zip 형식이 이상합니다: $name")
            val start = 30 + u16(head, 26) + u16(head, 28)
            val data = head.copyOfRange(start, (start + e.compSize).toInt().coerceAtMost(head.size))
            return when (e.method) {
                0 -> data
                8 -> inflate(data, e.size)
                else -> throw IOException("지원하지 않는 압축 방식 ${e.method}: $name")
            }
        }

        override fun close() {}

        private fun range(from: Long, to: Long): ByteArray {
            val end = to.coerceAtMost(total - 1)
            fetched += end - from + 1
            if (fetched > MAX_RANGED_BYTES) throw IOException("받을 양이 너무 많습니다")
            val conn = open(url, "bytes=$from-$end")
            try {
                if (conn.responseCode != 206) throw IOException("부분 다운로드 실패 (HTTP ${conn.responseCode})")
                return conn.inputStream.use { readLimited(it, end - from + 1) }
            } finally {
                conn.disconnect()
            }
        }

        /** zip 끝(EOCD)과 목록을 읽어 항목 표를 만든다 */
        private fun readDirectory() {
            val tailLen = minOf(total, 66_000L)
            val tail = range(total - tailLen, total - 1)
            var eocd = -1
            for (i in tail.size - 22 downTo 0) if (u32(tail, i) == 0x06054b50L) { eocd = i; break }
            if (eocd < 0) throw IOException("zip 목록을 찾지 못했습니다")
            val declared = u16(tail, eocd + 10)
            val cdSize = u32(tail, eocd + 12)
            val cdOffset = u32(tail, eocd + 16)
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) throw IOException("ZIP64 는 부분 다운로드를 지원하지 않습니다")
            val tailStart = total - tailLen
            val cd = if (cdOffset >= tailStart) {
                tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart + cdSize).toInt())
            } else {
                range(cdOffset, cdOffset + cdSize - 1)
            }
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
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                if (!name.endsWith("/")) entries[name] = Entry(method, comp, size, offset)
                p += 46 + nameLen + extraLen + commentLen
                parsed++
            }
            if (parsed < declared) note = "zip 목록 ${declared}개 중 ${parsed}개만 읽음 (보호된 팩일 수 있음)"
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
                    if (total == null || total < 22) throw IOException("리소스팩 파일이 이상합니다")
                    return RangedZip(url, total).also { it.readDirectory() }
                }
                if (maxFull <= 0) {
                    conn.disconnect()
                    throw IOException("이 리소스팩 서버는 부분 다운로드를 지원하지 않습니다")
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
                if (loc == null) throw IOException("리소스팩 주소가 이동했지만 새 주소가 없습니다")
                u = URL(URL(u), loc).toString()
                return@repeat
            }
            return conn
        }
        throw IOException("리소스팩 주소가 너무 많이 이동했습니다")
    }

    private fun copyLimited(input: InputStream, out: java.io.OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("리소스팩이 너무 큽니다")
            out.write(buf, 0, n)
        }
    }

    private fun readLimited(input: InputStream, expected: Long): ByteArray {
        val out = ByteArrayOutputStream(expected.toInt().coerceIn(0, 1 shl 20))
        copyLimited(input, out, expected + 1024)
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, size: Long): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(size.toInt().coerceIn(0, 1 shl 20))
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > size + 1024) throw IOException("압축 해제 크기가 이상합니다")
            }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
}
