package kr.jamsuham.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 색상표. 디자인([AppSettings.Theme]) × 밝기(밝게/어둡게)로 색이 정해진다.
 * [radius] 모서리 둥글기 배율(픽셀 디자인은 0 = 각진 모서리), [stroke] 테두리 색(0 = 없음).
 */
class Palette(val dark: Boolean, val theme: AppSettings.Theme = AppSettings.Theme.CLASSIC) {
    private fun pick(classicDark: String, classicLight: String, pixelDark: String, pixelLight: String, cleanDark: String, cleanLight: String): Int =
        c(
            when (theme) {
                AppSettings.Theme.CLASSIC -> if (dark) classicDark else classicLight
                AppSettings.Theme.PIXEL -> if (dark) pixelDark else pixelLight
                AppSettings.Theme.CLEAN -> if (dark) cleanDark else cleanLight
            },
        )

    //                        기본(어둡/밝)            픽셀(어둡/밝)            깔끔(어둡/밝)
    val bg = pick("#0E1621", "#F3F5F9", "#1D1D1D", "#C6C6C6", "#0F1115", "#F7F8FC")
    val card = pick("#172231", "#FFFFFF", "#313131", "#DADADA", "#191C23", "#FFFFFF")
    val input = pick("#22324A", "#E9EEF5", "#484848", "#B4B4B4", "#252A34", "#EEF0F6")
    val text = pick("#E6EDF3", "#18202B", "#F2F2F2", "#1C1C1C", "#E8EAF0", "#1A1C24")
    val muted = pick("#8A99AD", "#667386", "#A8A8A8", "#4C4C4C", "#8B91A1", "#6A7083")
    val accent = pick("#FFC93C", "#E0A100", "#6CC349", "#3F8F28", "#7C9CFF", "#4A63E7")
    val onAccent = pick("#14181F", "#14181F", "#10200A", "#FFFFFF", "#0D1230", "#FFFFFF")
    val ok = pick("#3DDC84", "#1E9E57", "#6CC349", "#2E7D1E", "#4ADE9B", "#16A06A")
    val warn = pick("#FFB020", "#D48806", "#FFAA00", "#B87400", "#FFC062", "#C98000")
    val err = pick("#FF6B6B", "#D93A3A", "#FF5555", "#C62828", "#FF7A8A", "#E0405A")
    val info = pick("#8FB8DE", "#3A6EA5", "#55FFFF", "#00838F", "#9DB4FF", "#3F5BD6")
    val divider = pick("#24324A", "#DDE3EC", "#0F0F0F", "#8B8B8B", "#262B36", "#E3E6EE")
    /** 테두리 (픽셀 디자인만: 마인크래프트 창처럼 진한 테두리) */
    val stroke: Int = if (theme == AppSettings.Theme.PIXEL) c(if (dark) "#000000" else "#555555") else 0
    /** 모서리 둥글기 배율 */
    val radius: Float = when (theme) {
        AppSettings.Theme.CLASSIC -> 1f
        AppSettings.Theme.PIXEL -> 0f
        AppSettings.Theme.CLEAN -> 1.5f
    }

    /** 채팅 색이 배경과 너무 비슷하면 읽을 수 있게 바꾼다 */
    fun readable(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        val fixed = when {
            dark && lum < 70 -> 0xB0B8C4
            !dark && lum > 200 -> 0x4A5566
            !dark && rgb == 0xFFFF55 -> 0xB8860B // 노랑
            !dark && rgb == 0x55FFFF -> 0x008B8B // 하늘
            !dark && rgb == 0x55FF55 -> 0x2E8B2E // 연두
            else -> rgb
        }
        return 0xFF000000.toInt() or fixed
    }

    companion object {
        private fun c(s: String) = Color.parseColor(s)

        fun of(context: Context): Palette {
            AppSettings.init(context)
            val dark = when (AppSettings.night) {
                AppSettings.Night.DARK -> true
                AppSettings.Night.LIGHT -> false
                AppSettings.Night.SYSTEM -> {
                    // 안드로이드 12+ 에서 앱 밝기를 바꾸면 configuration 이 바뀐다. 시스템 값으로 판단
                    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    night == Configuration.UI_MODE_NIGHT_YES
                }
            }
            return Palette(dark, AppSettings.theme)
        }
    }
}

/** 코드로 화면을 만들 때 쓰는 도구 모음 */
class Ui(val ctx: Context) {
    val p = Palette.of(ctx)

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** 설정의 글자 크기 배율 */
    val scale: Float get() = AppSettings.textScale.factor

    fun text(s: CharSequence, size: Float = 14f, color: Int = p.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size * scale)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** 한 줄에 다 안 들어가는 글씨는 옆으로 천천히 흐르게 (마키) */
    fun marquee(tv: TextView): TextView = tv.apply {
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        setHorizontallyScrolling(true)
        isSelected = true
    }

    /**
     * 한 줄에 다 안 들어가는 글씨를 왕복시킨다: 잠깐 멈춤 → 끝까지 → 잠깐 멈춤 → 처음으로 되돌아옴.
     * (마키처럼 한 방향으로 돌지 않아서 이름의 앞·뒤를 번갈아 읽을 수 있다.) 다 들어가면 가만히 둔다.
     */
    fun pingPong(tv: TextView): TextView = tv.apply {
        setSingleLine(true)
        ellipsize = null
        setHorizontallyScrolling(true)
        PingPong(this, dp(40).toFloat())
    }

    fun title(s: String) = text(s, 22f, p.text, bold = true)
    fun section(s: String) = text(s, 15f, p.accent, bold = true).apply { setPadding(0, 0, 0, dp(8)) }
    fun label(s: String) = text(s, 13f, p.muted).apply { setPadding(0, dp(12), 0, dp(4)) }
    fun hint(s: String) = text(s, 12f, p.muted).apply { setPadding(0, dp(6), 0, 0) }

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp) * p.radius
        // 픽셀 디자인: 투명하지 않은 큰 면(카드·버튼·입력칸)에 진한 테두리
        if (p.stroke != 0 && Color.alpha(color) != 0 && radiusDp >= 10) setStroke(dp(2), p.stroke)
    }

    fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun vertical() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 카드 안쪽 여백. 픽셀 디자인은 테두리(2dp)가 있어서 그만큼 더 띄운다 */
    val cardPad: Int get() = if (p.stroke != 0) dp(4) else 0

    fun card() = vertical().apply {
        background = rounded(p.card, 16)
        setPadding(dp(16) + cardPad, dp(14) + cardPad, dp(16) + cardPad, dp(14) + cardPad)
    }

    fun input(hintText: String, singleLine: Boolean = true) = EditText(ctx).apply {
        hint = hintText
        setHintTextColor(p.muted)
        setTextColor(p.text)
        setSingleLine(singleLine)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
        background = rounded(p.input, 10)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        if (singleLine) inputType = InputType.TYPE_CLASS_TEXT
    }

    fun switch(s: String) = Switch(ctx).apply {
        text = s
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        setPadding(0, dp(6), 0, dp(6))
    }

    /** kind: "primary" | "danger" | "plain" | "ghost" */
    fun button(s: String, kind: String = "plain", onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
        style(this, kind)
    }

    fun style(b: Button, kind: String) {
        val (bg, fg) = when (kind) {
            "primary" -> p.accent to p.onAccent
            "danger" -> p.err to Color.WHITE
            "ghost" -> Color.TRANSPARENT to p.text
            else -> p.input to p.text
        }
        b.background = rounded(bg, 12)
        b.setTextColor(fg)
        // 버튼 높이는 대부분 고정이라 위아래 여백을 작게 (글자를 크게 해도 잘리지 않게).
        // 기호 하나짜리(←, ▲, ⚙ …) 좁은 버튼은 옆 여백도 거의 없앤다
        val side = if ((b.text?.length ?: 0) <= 2) dp(2) else dp(16)
        b.setPadding(side, dp(3), side, dp(3))
        b.gravity = Gravity.CENTER
    }

    /**
     * 너비가 정해진 버튼·글자(탭, 반반 나눈 버튼)는 글자가 길거나 글자 크기를 키우면 잘리므로,
     * 칸에 맞게 글자를 줄인다 (최대는 원래 크기). 너비가 내용에 맞춰지는(WRAP) 뷰에는 쓰지 않는다.
     */
    fun fit(tv: TextView, maxSp: Float = 15f, minSp: Float = 8f): TextView = tv.apply {
        // setSingleLine 은 가로 길이를 무한으로 재서 글자 줄이기가 동작하지 않는다 → 최대 1줄 + 가로 스크롤 끄기
        maxLines = 1
        setHorizontallyScrolling(false)
        val max = (maxSp * scale).toInt().coerceAtLeast(minSp.toInt() + 1)
        setAutoSizeTextTypeUniformWithConfiguration(minSp.toInt(), max, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    fun chip(s: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        background = rounded(p.input, 14)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    fun dot(color: Int) = View(ctx).apply { background = circle(color) }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun gap(top: Int) = lp().apply { topMargin = dp(top) }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/**
 * 안드로이드 15+ 는 화면 끝까지 그리므로 상태바/내비게이션바/키보드 만큼 띄운다.
 * 14 이하는 시스템이 알아서 띄워 준다.
 */
fun Activity.setupWindow(root: View, palette: Palette, basePadDp: Int = 12) {
    val density = resources.displayMetrics.density
    val pad = (basePadDp * density + 0.5f).toInt()
    root.setBackgroundColor(palette.bg)
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.bg))
    root.setPadding(pad, pad, pad, pad)
    // 앱 밝기 설정이 폰과 달라도 상태바 아이콘이 보이게 (안드로이드 11+)
    if (Build.VERSION.SDK_INT >= 30) {
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        root.post { window.insetsController?.setSystemBarsAppearance(if (palette.dark) 0 else light, light) }
    }
    if (Build.VERSION.SDK_INT < 35) {
        @Suppress("DEPRECATION")
        window.statusBarColor = palette.bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.bg
        if (!palette.dark) {
            @Suppress("DEPRECATION")
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
    }
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 35) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
        }
        insets
    }
}

/** 글씨가 바뀔 때만 바꾼다 (같은 글씨로 다시 넣으면 흐르던 글씨가 처음으로 돌아가서) */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) text = value
}

/**
 * [Ui.pingPong] 의 움직임: 글이 칸보다 길 때만 가로로 밀었다가 되돌린다.
 * 1.2초 멈춤 → 초당 [pxPerSec] 로 끝까지 → 1.2초 멈춤 → 같은 빠르기로 처음까지, 되풀이.
 * 글이 바뀌면 처음부터 다시 하고, 화면에서 떨어지면 멈춘다.
 */
private class PingPong(private val tv: TextView, private val pxPerSec: Float) :
    View.OnLayoutChangeListener, View.OnAttachStateChangeListener, android.text.TextWatcher {

    private var animator: android.animation.ValueAnimator? = null

    /** 지금 움직이는 거리 (글 폭 - 칸 폭). -1 = 아직 재지 않음 */
    private var range = -1

    init {
        tv.addOnLayoutChangeListener(this)
        tv.addOnAttachStateChangeListener(this)
        tv.addTextChangedListener(this)
    }

    override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) = restart()
    override fun onViewAttachedToWindow(v: View) = restart()
    override fun onViewDetachedFromWindow(v: View) = stop()
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        // 글이 바뀌었으니 처음부터 (새 글의 폭은 배치가 끝난 뒤에 잰다)
        stop()
        tv.post { restart() }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        range = -1
        tv.scrollTo(0, 0)
    }

    private fun restart() {
        val layout = tv.layout
        val box = tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight
        val text = if (layout == null || layout.lineCount == 0) 0 else Math.ceil(layout.getLineWidth(0).toDouble()).toInt()
        val wanted = if (box <= 0) 0 else (text - box).coerceAtLeast(0)
        if (wanted == range && (animator != null || wanted == 0)) return // 달라진 것이 없으면 하던 대로
        stop()
        range = wanted
        if (wanted == 0 || !tv.isAttachedToWindow) return
        val pause = 1200f
        val travel = (wanted / pxPerSec * 1000f).coerceAtLeast(300f)
        val total = pause + travel + pause + travel
        animator = android.animation.ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong()
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val at = a.animatedValue as Float
                val x = when {
                    at < pause -> 0f
                    at < pause + travel -> (at - pause) / travel
                    at < pause + travel + pause -> 1f
                    else -> 1f - (at - pause - travel - pause) / travel
                }
                tv.scrollTo((x.coerceIn(0f, 1f) * wanted).toInt(), 0)
            }
            start()
        }
    }
}
