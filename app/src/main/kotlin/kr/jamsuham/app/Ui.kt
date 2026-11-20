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

/** 색상표. 폰이 다크 모드면 어둡게, 아니면 밝게. */
class Palette(val dark: Boolean) {
    val bg = if (dark) c("#0E1621") else c("#F3F5F9")
    val card = if (dark) c("#172231") else c("#FFFFFF")
    val input = if (dark) c("#22324A") else c("#E9EEF5")
    val text = if (dark) c("#E6EDF3") else c("#18202B")
    val muted = if (dark) c("#8A99AD") else c("#667386")
    val accent = if (dark) c("#FFC93C") else c("#E0A100")
    val onAccent = c("#14181F")
    val ok = if (dark) c("#3DDC84") else c("#1E9E57")
    val warn = if (dark) c("#FFB020") else c("#D48806")
    val err = if (dark) c("#FF6B6B") else c("#D93A3A")
    val info = if (dark) c("#8FB8DE") else c("#3A6EA5")
    val divider = if (dark) c("#24324A") else c("#DDE3EC")

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
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return Palette(night == Configuration.UI_MODE_NIGHT_YES)
        }
    }
}

/** 코드로 화면을 만들 때 쓰는 도구 모음 */
class Ui(val ctx: Context) {
    val p = Palette.of(ctx)

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun text(s: CharSequence, size: Float = 14f, color: Int = p.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun title(s: String) = text(s, 22f, p.text, bold = true)
    fun section(s: String) = text(s, 15f, p.accent, bold = true).apply { setPadding(0, 0, 0, dp(8)) }
    fun label(s: String) = text(s, 13f, p.muted).apply { setPadding(0, dp(12), 0, dp(4)) }
    fun hint(s: String) = text(s, 12f, p.muted).apply { setPadding(0, dp(6), 0, 0) }

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
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

    fun card() = vertical().apply {
        background = rounded(p.card, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    fun input(hintText: String, singleLine: Boolean = true) = EditText(ctx).apply {
        hint = hintText
        setHintTextColor(p.muted)
        setTextColor(p.text)
        setSingleLine(singleLine)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = rounded(p.input, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (singleLine) inputType = InputType.TYPE_CLASS_TEXT
    }

    fun switch(s: String) = Switch(ctx).apply {
        text = s
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(6), 0, dp(6))
    }

    /** kind: "primary" | "danger" | "plain" | "ghost" */
    fun button(s: String, kind: String = "plain", onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
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
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    fun chip(s: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = s
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
    root.setPadding(pad, pad, pad, pad)
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
