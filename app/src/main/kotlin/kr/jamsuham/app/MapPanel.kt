package kr.jamsuham.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 미니맵 + 이동 버튼. 지도는 위가 북쪽.
 *  - 노란 화살표 = 나 (바라보는 방향)
 *  - 파란 점 = 다른 플레이어 (움직이면 바로 갱신)
 *  - 회색 = 내 발/머리 높이의 벽, 어두운 칸 = 밟을 수 있는 바닥, 빈 칸 = 구멍/허공
 */
class MapPanel(private val activity: Activity, private val ui: Ui, private val serverId: String) : LinearLayout(activity) {

    private val p = ui.p
    val mapView = MinimapView()
    private val coords: TextView
    private var mask = 0

    init {
        orientation = VERTICAL
        val mapCard = ui.card().apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
        mapCard.addView(mapView, ui.lp(Ui.MATCH, 0, 1f))
        val infoRow = ui.horizontal().apply { setPadding(ui.dp(4), ui.dp(6), 0, 0) }
        coords = ui.text("", 13f, p.muted)
        infoRow.addView(coords, ui.lp(0, Ui.WRAP, 1f))
        infoRow.addView(ui.button("−", "plain") { mapView.zoom(-1) }, ui.lp(ui.dp(44), ui.dp(38)))
        infoRow.addView(ui.button("+", "plain") { mapView.zoom(+1) }, ui.lp(ui.dp(44), ui.dp(38)).apply { leftMargin = ui.dp(6) })
        mapCard.addView(infoRow, ui.lp())
        addView(mapCard, ui.lp(Ui.MATCH, 0, 1f))

        // 이동 버튼: 누르고 있는 동안 움직임
        val pad = ui.card().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        val grid = ui.horizontal()
        val dpad = ui.vertical()
        val size = ui.dp(58)
        fun spacer() = View(activity)
        val r1 = ui.horizontal(); r1.addView(spacer(), ui.lp(size, size)); r1.addView(holdButton("▲", 1), ui.lp(size, size)); r1.addView(spacer(), ui.lp(size, size))
        val r2 = ui.horizontal(); r2.addView(holdButton("◀", 4), ui.lp(size, size)); r2.addView(spacer(), ui.lp(size, size)); r2.addView(holdButton("▶", 8), ui.lp(size, size))
        val r3 = ui.horizontal(); r3.addView(spacer(), ui.lp(size, size)); r3.addView(holdButton("▼", 2), ui.lp(size, size)); r3.addView(spacer(), ui.lp(size, size))
        dpad.addView(r1); dpad.addView(r2); dpad.addView(r3)
        grid.addView(dpad)
        val right = ui.vertical().apply { gravity = android.view.Gravity.CENTER }
        right.addView(holdButton("점프", 16, primary = true), ui.lp(ui.dp(96), ui.dp(96)))
        right.addView(ui.text("▲ 북  ▼ 남  ◀ 서  ▶ 동", 11f, p.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        grid.addView(right, ui.lp(0, Ui.WRAP, 1f))
        pad.addView(grid)
        pad.addView(ui.hint("아이템 사용 없이 걷기와 점프만 됩니다. 0.6칸 이하 턱(반 블록, 계단)은 자동으로 올라갑니다."))
        addView(pad, ui.lp().apply { topMargin = ui.dp(8) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun holdButton(label: String, bit: Int, primary: Boolean = false): Button {
        val b = ui.button(label, if (primary) "primary" else "plain") {}
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 18f else 22f)
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mask = mask or bit
                    v.alpha = 0.6f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mask = mask and bit.inv()
                    v.alpha = 1f
                }
            }
            SessionHub.setMovement(serverId, mask)
            true
        }
        return b
    }

    /** 페이지를 벗어나거나 화면이 꺼질 때 멈춘다 */
    fun stopMoving() {
        mask = 0
        SessionHub.setMovement(serverId, 0)
    }

    override fun onDetachedFromWindow() {
        stopMoving()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ 지도

    inner class MinimapView : View(activity) {
        private var cell = ui.dp(7).toFloat() // 블록 한 칸 크기
        private val fill = Paint()
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dp(11).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrow = Path()
        private val rect = RectF()
        private val colWall = if (p.dark) 0xFF6B7787.toInt() else 0xFF8A96A6.toInt()
        private val colFloor = if (p.dark) 0xFF2A384B.toInt() else 0xFFDCE3EC.toInt()
        private val colGap = if (p.dark) 0xFF101720.toInt() else 0xFFF7F9FC.toInt()
        private val colOther = 0xFF4DABF7.toInt()

        fun zoom(dir: Int) {
            cell = (cell + dir * ui.dp(2)).coerceIn(ui.dp(3).toFloat(), ui.dp(16).toFloat())
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(colGap)
            val conn = SessionHub.live(serverId)
            if (conn == null) {
                text.color = p.muted
                text.textAlign = Paint.Align.CENTER
                canvas.drawText("접속하면 지도가 보입니다", w / 2, h / 2, text)
                coords.text = ""
                postInvalidateDelayed(500)
                return
            }
            val world = conn.world
            val px = conn.currentX
            val py = conn.currentY
            val pz = conn.currentZ
            val feet = floor(py + 0.01).toInt()
            val cx = w / 2
            val cy = h / 2
            val rx = (w / 2 / cell).toInt() + 1
            val rz = (h / 2 / cell).toInt() + 1
            val bx0 = floor(px).toInt()
            val bz0 = floor(pz).toInt()
            for (dz in -rz..rz) {
                for (dx in -rx..rx) {
                    val bx = bx0 + dx
                    val bz = bz0 + dz
                    if (world.stateAt(bx, feet, bz) < 0) continue // 아직 안 받은 곳
                    val h0 = world.heightAt(bx, feet, bz)
                    val h1 = world.heightAt(bx, feet + 1, bz)
                    val below = world.heightAt(bx, feet - 1, bz)
                    val color = when {
                        h0 > 0.6 || h1 > 0.0 -> colWall
                        h0 > 0.0 || below > 0.0 -> colFloor
                        else -> continue
                    }
                    val sx = cx + ((bx - px).toFloat()) * cell
                    val sy = cy + ((bz - pz).toFloat()) * cell
                    fill.color = color
                    canvas.drawRect(sx, sy, sx + cell + 0.5f, sy + cell + 0.5f, fill)
                }
            }

            // 다른 플레이어
            val names = SessionHub.players(serverId).players.associateBy({ it.uuid }, { it.name })
            text.textAlign = Paint.Align.CENTER
            for (e in world.players.values) {
                val sx = cx + ((e.x - px).toFloat()) * cell
                val sy = cy + ((e.z - pz).toFloat()) * cell
                if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) continue
                dot.color = colOther
                canvas.drawCircle(sx, sy, ui.dp(5).toFloat(), dot)
                val label = names[e.uuid] ?: continue
                text.color = colOther
                canvas.drawText(label, sx, sy - ui.dp(8), text)
            }

            // 나 (바라보는 방향으로 향한 화살표)
            val yawRad = Math.toRadians(conn.currentYaw.toDouble())
            val fx = -sin(yawRad).toFloat()
            val fz = cos(yawRad).toFloat()
            val s = ui.dp(9).toFloat()
            arrow.reset()
            arrow.moveTo(cx + fx * s * 1.4f, cy + fz * s * 1.4f)
            arrow.lineTo(cx - fz * s * 0.8f - fx * s * 0.6f, cy + fx * s * 0.8f - fz * s * 0.6f)
            arrow.lineTo(cx + fz * s * 0.8f - fx * s * 0.6f, cy - fx * s * 0.8f - fz * s * 0.6f)
            arrow.close()
            dot.color = p.accent
            canvas.drawPath(arrow, dot)

            // 북쪽 표시
            text.color = p.text
            text.textAlign = Paint.Align.LEFT
            rect.set(ui.dp(6).toFloat(), ui.dp(6).toFloat(), ui.dp(30).toFloat(), ui.dp(28).toFloat())
            fill.color = p.card
            canvas.drawRoundRect(rect, ui.dp(6).toFloat(), ui.dp(6).toFloat(), fill)
            canvas.drawText("N ↑", ui.dp(9).toFloat(), ui.dp(22).toFloat(), text)

            coords.text = "X ${floor(px).toInt()}  Y ${floor(py).toInt()}  Z ${floor(pz).toInt()}  ·  주변 ${world.players.size}명"
            postInvalidateDelayed(100)
        }
    }
}
