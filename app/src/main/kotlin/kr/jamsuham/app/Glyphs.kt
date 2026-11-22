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
                val why = if (maxFull == 0L && t.message?.contains("부분 다운로드") == true) {
                    "이 서버의 리소스팩은 통째로 받아야 해서 와이파이에 연결되면 받습니다"
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
    fun append(sb: SpannableStringBuilder, span: ChatSpan, color: Int, textPx: Float, set: PackFonts.FontSet?, tintColor: Int? = span.color) {
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
                    if (g != null && appendGlyph(sb, set, g, textPx, tintColor, flush = { if (run.isNotEmpty()) { appendText(sb, run.toString(), color, span.bold); run.setLength(0) } })) {
                        // 그림으로 넣음
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
            // 지정된 글꼴에 없으면, 개인 사용 영역 글자는 팩의 다른 글꼴에서 찾는다 (서버가 글꼴 지정을 빼먹는 경우)
            val g = set.glyph(span.font, cp) ?: if (ChatText.isPrivateUse(cp)) set.glyphAny(cp) else null
            if (g == null) miss(span.font, cp) else appendGlyph(sb, set, g, textPx, tintColor, flush = {})
            i += Character.charCount(cp)
        }
    }

    /** [text] 전체를 화면용 글자로 (메뉴·인벤·접속자 목록용) */
    fun build(text: ChatText, colorOf: (ChatSpan) -> Int, textPx: Float, set: PackFonts.FontSet?): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (s in text.spans) append(sb, s, colorOf(s), textPx, set)
        return sb
    }

    fun appendText(sb: SpannableStringBuilder, s: String, color: Int, bold: Boolean) {
        if (s.isEmpty()) return
        val start = sb.length
        sb.append(s)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendGlyph(sb: SpannableStringBuilder, set: PackFonts.FontSet, g: PackFonts.Glyph, textPx: Float, tint: Int?, flush: () -> Unit): Boolean {
        val bmp = cell(set, g) ?: return false
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
