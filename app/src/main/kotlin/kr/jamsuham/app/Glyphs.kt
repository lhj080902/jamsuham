package kr.jamsuham.app

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.util.LruCache
import kr.jamsuham.core.pack.PackFonts
import kr.jamsuham.core.text.ChatSpan
import kr.jamsuham.core.text.ChatText
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 서버 리소스팩의 "그림 글자"(칭호·아이콘 글자)를 채팅에 그림으로 그린다.
 *
 * 서버가 리소스팩을 보내라고 하면([load]) 백그라운드에서 그 팩의 글꼴 설정·그림만 받아(core 의 PackFonts)
 * 서버별로 보관하고, 화면은 [append] 로 채팅 조각을 글자 + 그림으로 만든다. 팩이 없거나 아직 못 받았으면 지금처럼 숨긴다.
 */
object Glyphs {
    /** Range 를 지원하지 않는 팩을 통째로 받을 상한 */
    private const val MAX_FULL_BYTES = 150L * 1024 * 1024

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "jamsuham-glyphs").apply { isDaemon = true } }

    /** 팩 주소 → 읽은 그림 글자 (같은 팩을 쓰는 서버끼리 같이 씀) */
    private val loaded = ConcurrentHashMap<String, PackFonts.FontSet>()
    /** 받는 중인 팩 → 끝나면 부를 콜백들 (받는 사이 재접속해도 결과를 놓치지 않게, 메인 스레드에서만 다룸) */
    private val waiting = HashMap<String, MutableList<(PackFonts.FontSet?, String?) -> Unit>>()

    /** 이미 읽어 둔 팩 (없으면 null) */
    fun cached(url: String): PackFonts.FontSet? = loaded[url]

    /**
     * 리소스팩 [url] 의 그림 글자를 읽기 시작한다. 끝나면 [done] 을 메인 스레드에서 부른다 (실패하면 null, 이유).
     * 같은 팩은 한 번만 받는다. 메인 스레드에서 부른다.
     */
    fun load(context: Context, url: String, hash: String, wifiOnly: Boolean, done: (PackFonts.FontSet?, String?) -> Unit) {
        loaded[url]?.let { done(it, null); return }
        waiting[url]?.let { it.add(done); return }
        waiting[url] = mutableListOf(done)
        val app = context.applicationContext
        val metered = (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        // 모바일 데이터(요금제)에서 '와이파이에서만' 이면 필요한 부분만 받고, 통째로 받아야 하는 팩은 받지 않는다
        val maxFull = if (wifiOnly && metered) 0L else MAX_FULL_BYTES
        loader.execute {
            val result = try {
                val set = PackFonts.load(url, hash, File(app.filesDir, "packfonts"), maxFull, SessionHub.log)
                loaded[url] = set
                set to null
            } catch (t: Throwable) {
                val why = if (maxFull == 0L && t is PackFonts.NoRangeException) {
                    tr("이 서버의 리소스팩은 통째로 받아야 해서 와이파이에 연결되면 받습니다", "This server's resource pack must be downloaded whole, so it will download on Wi-Fi")
                } else {
                    t.message ?: t.javaClass.simpleName
                }
                null to why
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                for (cb in waiting.remove(url).orEmpty()) cb(result.first, result.second)
            }
        }
    }

    /** 메모리에 올린 그림 글자를 모두 내린다 (앱이 뒤로 갔을 때). 기기의 파일은 그대로라 다시 열면 빠르게 읽는다 */
    fun releaseMemory() {
        loaded.clear()
        bitmaps.evictAll()
        cells.evictAll()
    }

    /** 지금 쓰는 팩([inUse]: 주소, SHA-1)만 남기고 예전 서버의 팩 파일을 지운다 (백그라운드에서) */
    fun pruneCache(context: Context, inUse: List<Pair<String, String>>) {
        if (inUse.isEmpty()) return
        val dir = File(context.applicationContext.filesDir, "packfonts")
        val keep = inUse.map { (u, h) -> u to h.ifBlank { null } }
        loader.execute {
            try {
                val freed = PackFonts.pruneCache(dir, keep)
                if (freed > 0) SessionHub.log.i("예전 리소스팩 그림 글자 ${freed / 1024}KB 를 지웠습니다")
            } catch (_: Throwable) {
            }
        }
    }

    /** 받아 둔 리소스팩 그림 글자 용량 (바이트) */
    fun cacheBytes(context: Context): Long =
        File(context.filesDir, "packfonts").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 받아 둔 그림 글자를 지운다 (지금 접속 중인 서버는 다음 접속 때 다시 받음) */
    fun clearCache(context: Context) {
        loaded.clear()
        broken.clear()
        missing.clear()
        bitmaps.evictAll()
        cells.evictAll()
        File(context.filesDir, "packfonts").deleteRecursively()
    }

    // ------------------------------------------------------------ 그리기

    /** 그림 파일 → 풀어 둔 비트맵 (메모리를 너무 쓰지 않게 개수 제한) */
    private val bitmaps = LruCache<String, Bitmap>(64)
    /** 채팅에 나왔는데 팩에서 못 찾은 그림 글자 ("글꼴 U+XXXX", 진단용, 최대 50개) */
    val missing: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    private fun miss(font: String?, cp: Int) {
        if (missing.size < 50) missing.add("${font ?: PackFonts.DEFAULT_FONT} U+%04X".format(cp))
    }

    /** 고쳐도 읽지 못한 그림 (매번 다시 시도하지 않게, 상태 탭에 개수를 보여 줌) */
    val broken: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** "그림|열|행" → 잘라 낸 글자 비트맵 (오른쪽 빈 칸은 잘라 냄, 바닐라와 같음) */
    private val cells = LruCache<String, Bitmap>(512)
    private val solids = java.util.WeakHashMap<Bitmap, Boolean>()

    /** 보이는 픽셀이 75% 이상인 꽉 찬 그림인지 (긴 칭호는 글자 사이가 비어 있어서 아님) */
    private fun isSolid(bmp: Bitmap): Boolean = synchronized(solids) {
        solids.getOrPut(bmp) {
            val w = bmp.width
            val h = bmp.height
            if (w * h == 0) return@getOrPut false
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            px.count { (it ushr 24) >= 128 } >= w * h * 0.75
        }
    }

    /** (글자 비트맵, 칠할 색) → "한 가지 색으로 꽉 찬 띠"인지 (공지 배경 조각, 한 번만 계산) */
    private val flatBars = java.util.WeakHashMap<Bitmap, HashMap<Int, Boolean>>()

    /**
     * 가로가 세로보다 길고, **실제로 그려질 색**(글자 색을 곱한 뒤)이 대부분 한 가지 색으로 꽉 찬 사각형이면 배경 띠 조각으로 본다.
     * 원본은 여러 색이어도 서버가 검은색으로 칠하면 검은 막대가 되므로 곱한 뒤의 색으로 판단한다.
     * (칭호·아이콘은 글자 모양·여러 색이 있어서 해당하지 않음)
     */
    private fun isFlatBar(bmp: Bitmap, tint: Int?): Boolean = synchronized(flatBars) {
        val key = tint ?: -1
        flatBars.getOrPut(bmp) { HashMap() }.getOrPut(key) {
            val w = bmp.width
            val h = bmp.height
            if (w < h * 2 || w * h == 0) return@getOrPut false
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val tr = if (tint == null) 255 else (tint shr 16) and 0xFF
            val tg = if (tint == null) 255 else (tint shr 8) and 0xFF
            val tb = if (tint == null) 255 else tint and 0xFF
            var opaque = 0
            val counts = HashMap<Int, Int>()
            var fullRows = 0
            for (y in 0 until h) {
                var rowOpaque = 0
                for (x in 0 until w) {
                    val c = px[y * w + x]
                    if ((c ushr 24) < 128) continue
                    opaque++
                    rowOpaque++
                    // 글자 색을 곱한 실제 색, 비슷한 색은 같은 색으로 (채널당 상위 3비트)
                    val r = (((c shr 16) and 0xFF) * tr / 255) and 0xE0
                    val g = (((c shr 8) and 0xFF) * tg / 255) and 0xE0
                    val b = ((c and 0xFF) * tb / 255) and 0xE0
                    val q = (r shl 16) or (g shl 8) or b
                    counts[q] = (counts[q] ?: 0) + 1
                }
                if (rowOpaque >= w * 0.8) fullRows++
            }
            if (opaque == 0) return@getOrPut true
            val top = counts.values.maxOrNull() ?: 0
            // 거의 빈틈 없이 꽉 찬 사각형(80% 이상)이고 그 90% 이상이 한 색일 때만 배경 띠 (글자 칭호는 글자 사이가 비어 있음)
            top >= opaque * 0.9 && opaque >= w * h * 0.8 && fullRows >= h * 0.6
        }
    }

    private fun cell(set: PackFonts.FontSet, g: PackFonts.Glyph): Bitmap? {
        val key = "${g.image}|${g.col}|${g.row}"
        cells.get(key)?.let { return it }
        val sheet = bitmaps.get(g.image) ?: run {
            if (g.image in broken) return null
            val bytes = set.images[g.image] ?: return null
            val opts = BitmapFactory.Options().apply { inScaled = false }
            // 보호된 팩은 PNG 검사값을 망가뜨려 둔다: 안드로이드가 못 읽으면 고쳐서 다시 읽는다
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: PackFonts.repairPng(bytes)?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
            if (bmp == null) {
                broken.add(g.image)
                return null
            }
            bmp.also { bitmaps.put(g.image, it) }
        }
        val cw = sheet.width / g.cols
        val ch = sheet.height / g.rows
        if (cw <= 0 || ch <= 0) return null
        val x0 = g.col * cw
        val y0 = g.row * ch
        if (x0 + cw > sheet.width || y0 + ch > sheet.height) return null
        // 오른쪽 끝의 투명한 열은 버린다 (글자 폭)
        var w = cw
        while (w > 0) {
            var any = false
            for (y in 0 until ch) if (Color.alpha(sheet.getPixel(x0 + w - 1, y0 + y)) != 0) { any = true; break }
            if (any) break
            w--
        }
        if (w <= 0) return null
        return Bitmap.createBitmap(sheet, x0, y0, w, ch).also { cells.put(key, it) }
    }

    /**
     * 채팅 조각 하나를 [sb] 에 붙인다: 글자는 [color] 색(·굵게), 그림 글자는 그림으로.
     * [set] 이 없거나 그 글자의 그림이 없으면 그림 글자는 숨긴다(예전과 같음). [textPx] 글자 크기(px).
     * 빈 조각은 건너뛴다 (길이 0 구간에 서식을 걸면 안드로이드가 예외를 낸다).
     */
    /** 위치 열쇠: 조각 번호 · 글자 부분(0 = text, 1 = glyphs) · 그 안의 글자 위치 */
    private fun key(span: Int, part: Int, offset: Int): Long = (span.toLong() shl 32) or (part.toLong() shl 31) or offset.toLong()

    /** 그 글자의 그림 (지정 글꼴에 없으면 개인 사용 영역 글자는 다른 글꼴에서) */
    private fun glyphOf(set: PackFonts.FontSet, font: String?, cp: Int): PackFonts.Glyph? =
        set.glyph(font, cp) ?: if (ChatText.isPrivateUse(cp)) set.glyphAny(cp) else null

    /**
     * 한 줄(조각들)에서 **배경으로 깔리는 그림 글자**의 위치를 찾는다.
     * 마인크래프트 서버는 배경 띠 그림 뒤에 "뒤로 당기는 빈칸"(space 공급자의 음수 가로 이동)을 넣어 그 위에 글자를 겹쳐 쓴다.
     * 채팅 앱은 겹쳐 그릴 수 없으므로, 큰 음수 빈칸 바로 앞에 이어진 그림들을 배경으로 보고 건너뛴다.
     * (-1~-2 같은 작은 음수는 글자 사이 좁히기라서 무시: 뒤로 당기는 거리가 앞 그림 폭의 절반 이상일 때만)
     */
    fun backgroundMarks(spans: List<ChatSpan>, set: PackFonts.FontSet?): Set<Long> {
        if (set == null || set.spaces.isEmpty()) return emptySet()
        class E(val key: Long, val font: String?, val cp: Int)
        val seq = ArrayList<E>()
        spans.forEachIndexed { si, sp ->
            var i = 0
            while (i < sp.text.length) { val cp = sp.text.codePointAt(i); seq += E(key(si, 0, i), sp.font, cp); i += Character.charCount(cp) }
            val gl = sp.glyphs ?: ""
            i = 0
            while (i < gl.length) { val cp = gl.codePointAt(i); seq += E(key(si, 1, i), sp.font, cp); i += Character.charCount(cp) }
        }
        val marks = HashSet<Long>()
        for ((idx, e) in seq.withIndex()) {
            val adv = set.advance(e.font, e.cp) ?: continue
            if (adv >= 0) continue
            // 바로 앞에 이어진 그림 글자들
            var j = idx - 1
            var width = 0f
            val run = ArrayList<Long>()
            while (j >= 0) {
                val p = seq[j]
                val g = glyphOf(set, p.font, p.cp) ?: break
                width += glyphAdvance(set, g)
                run += p.key
                j--
            }
            if (run.isNotEmpty() && -adv >= width * 0.5f) marks += run
        }
        return marks
    }

    /** 그림 글자의 마인크래프트 가로 이동(픽셀, 대략): 잘라 낸 폭을 글자 높이에 맞춘 값 + 1 */
    private fun glyphAdvance(set: PackFonts.FontSet, g: PackFonts.Glyph): Float {
        val bmp = cell(set, g) ?: return 0f
        return bmp.width * (g.height.toFloat() / bmp.height.coerceAtLeast(1)) + 1f
    }

    /**
     * 채팅 조각 하나를 [sb] 에 붙인다: 글자는 [color] 색(·굵게), 그림 글자는 그림으로.
     * [set] 이 없거나 그 글자의 그림이 없으면 그림 글자는 숨긴다(예전과 같음). [textPx] 글자 크기(px).
     * [skip] 은 [backgroundMarks] 결과, [spanIndex] 는 이 조각의 줄 안 번호 (배경 그림 건너뛰기용).
     * 빈 조각은 건너뛴다 (길이 0 구간에 서식을 걸면 안드로이드가 예외를 낸다).
     */
    fun append(
        sb: SpannableStringBuilder, span: ChatSpan, color: Int, textPx: Float, set: PackFonts.FontSet?,
        tintColor: Int? = span.color, skip: Set<Long> = emptySet(), spanIndex: Int = 0,
    ) {
        val text = span.text
        if (text.isNotEmpty()) {
            if (set == null || set.fonts[span.font ?: PackFonts.DEFAULT_FONT].isNullOrEmpty()) {
                appendText(sb, text, color, span.bold)
            } else {
                // 기본 글꼴의 한자 등을 리소스팩이 그림으로 바꾼 경우: 그 글자만 그림으로
                val run = StringBuilder()
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i)
                    val g = set.glyph(span.font, cp)
                    if (key(spanIndex, 0, i) in skip) {
                        // 배경 그림: 건너뜀
                    } else if (g != null && appendGlyph(sb, set, g, textPx, tintColor, flush = { if (run.isNotEmpty()) { appendText(sb, run.toString(), color, span.bold); run.setLength(0) } })) {
                        // 그림으로 넣음
                    } else if (ChatText.isPrivateUse(cp)) {
                        // 그리지 못한 개인 사용 영역 글자는 앱 글꼴로 깨지므로 넣지 않는다
                    } else {
                        run.appendCodePoint(cp)
                    }
                    i += Character.charCount(cp)
                }
                if (run.isNotEmpty()) appendText(sb, run.toString(), color, span.bold)
            }
        }
        val glyphs = span.glyphs ?: return
        if (set == null) return
        var i = 0
        while (i < glyphs.length) {
            val cp = glyphs.codePointAt(i)
            if (key(spanIndex, 1, i) !in skip) {
                // 지정된 글꼴에 없으면, 개인 사용 영역 글자는 팩의 다른 글꼴에서 찾는다 (서버가 글꼴 지정을 빼먹는 경우)
                val g = glyphOf(set, span.font, cp)
                if (g != null) appendGlyph(sb, set, g, textPx, tintColor, flush = {})
                else if (set.advance(span.font, cp) == null) miss(span.font, cp) // 빈칸 글자는 원래 그림이 없음
            }
            i += Character.charCount(cp)
        }
    }

    /**
     * 진단용: 한 줄이 어떤 그림 글자·빈칸 글자로 이뤄졌는지 글로 정리한다 (채팅 줄을 길게 누르면 보임).
     * 배경 띠가 남거나 칭호가 사라질 때 원인을 찾는 데 쓴다.
     */
    fun describe(spans: List<ChatSpan>, set: PackFonts.FontSet?): String {
        if (set == null) return tr("이 서버의 리소스팩 그림 글자를 아직 불러오지 않았습니다", "Resource pack glyphs for this server aren't loaded yet")
        val skip = backgroundMarks(spans, set)
        val sb = StringBuilder()
        var n = 0
        spans.forEachIndexed { si, sp ->
            val color = sp.color?.let { "#%06X".format(it) } ?: "-"
            fun one(part: Int, off: Int, cp: Int) {
                if (n++ > 80) return
                val font = sp.font ?: PackFonts.DEFAULT_FONT
                val g = glyphOf(set, sp.font, cp)
                val adv = set.advance(sp.font, cp)
                val what = when {
                    g != null -> {
                        val bmp = cell(set, g)
                        val size = bmp?.let { "${it.width}×${it.height}" } ?: tr("그림 못 읽음", "unreadable")
                        val flags = buildList {
                            if (key(si, part, off) in skip) add(tr("배경(뒤 빈칸)", "background(space)"))
                            if (bmp != null && bmp.width > bmp.height * BACKGROUND_ASPECT && isSolid(bmp)) add(tr("긴 띠", "long bar"))
                            if (bmp != null && isFlatBar(bmp, sp.color?.takeIf { it != 0xFFFFFF })) add(tr("단색 띠", "flat bar"))
                        }
                        tr("그림 $size h${g.height}", "image $size h${g.height}") + (if (flags.isEmpty()) "" else " [" + flags.joinToString() + "]")
                    }
                    adv != null -> tr("빈칸 ${"%+.0f".format(adv)}", "space ${"%+.0f".format(adv)}")
                    ChatText.isPrivateUse(cp) -> tr("팩에 없음", "not in pack")
                    else -> return
                }
                sb.append("#$si U+%04X $font $color → $what\n".format(cp))
            }
            var i = 0
            while (i < sp.text.length) { val cp = sp.text.codePointAt(i); one(0, i, cp); i += Character.charCount(cp) }
            val gl = sp.glyphs ?: ""
            i = 0
            while (i < gl.length) { val cp = gl.codePointAt(i); one(1, i, cp); i += Character.charCount(cp) }
            if (sp.text.isNotBlank() && n <= 80) sb.append("#$si \"${sp.text.take(16)}\" $color\n")
        }
        return sb.toString().ifBlank { tr("이 줄에는 그림 글자가 없습니다", "No glyphs in this line") }
    }

    /** [text] 전체를 화면용 글자로 (메뉴·인벤·접속자 목록용) */
    fun build(text: ChatText, colorOf: (ChatSpan) -> Int, textPx: Float, set: PackFonts.FontSet?): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val skip = backgroundMarks(text.spans, set)
        text.spans.forEachIndexed { i, s -> append(sb, s, colorOf(s), textPx, set, skip = skip, spanIndex = i) }
        return sb
    }

    fun appendText(sb: SpannableStringBuilder, s: String, color: Int, bold: Boolean) {
        if (s.isEmpty()) return
        val start = sb.length
        sb.append(s)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 배경 띠로 보고 빼는 가로세로 비율 (칭호·아이콘은 이보다 짧다) */
    private const val BACKGROUND_ASPECT = 5f

    /**
     * 그림 글자 하나를 붙인다. 못 그리면 false.
     * 가로로 아주 긴 그림(공지 배경 띠, 밑줄 등)이나 한 가지 색으로 꽉 찬 띠 조각은 마인크래프트에서 "뒤로 당기는 빈칸 글자"로 글자 밑에 겹쳐 깔리는데,
     * 채팅 앱은 겹쳐 그릴 수 없어서 긴 막대만 남는다 → 그리지 않고 건너뛴다 (true 를 돌려 글자로도 넣지 않음).
     */
    private fun appendGlyph(sb: SpannableStringBuilder, set: PackFonts.FontSet, g: PackFonts.Glyph, textPx: Float, tint: Int?, flush: () -> Unit): Boolean {
        val bmp = cell(set, g) ?: return false
        val drawTint = if (tint != null && tint != 0xFFFFFF) tint else null
        if ((bmp.width > bmp.height * BACKGROUND_ASPECT && isSolid(bmp)) || isFlatBar(bmp, drawTint)) return true
        flush()
        // 바닐라 기본 글자 높이 8 = 글자 크기 정도. 너무 큰 그림은 줄 높이의 2.5배로 제한
        val targetH = (textPx * 0.95f * g.height / 8f).coerceIn(textPx * 0.4f, textPx * 2.5f)
        val scale = targetH / bmp.height
        val d = BitmapDrawable(Resources.getSystem(), bmp).apply {
            setFilterBitmap(false) // 픽셀 그림이 뭉개지지 않게
            setBounds(0, 0, (bmp.width * scale).toInt().coerceAtLeast(1), targetH.toInt().coerceAtLeast(1))
            // 바닐라처럼 글자 색을 곱한다 (흰색이면 그대로)
            if (tint != null && tint != 0xFFFFFF) colorFilter = PorterDuffColorFilter(0xFF000000.toInt() or tint, PorterDuff.Mode.MULTIPLY)
        }
        val start = sb.length
        sb.append('￼')
        sb.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_BASELINE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return true
    }
}
