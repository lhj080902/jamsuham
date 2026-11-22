package kr.jamsuham.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kr.jamsuham.core.world.BlockColors
import kr.jamsuham.core.world.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 미니맵 + 이동 버튼 + 웨이포인트. 지도는 위가 북쪽.
 *  - 블록은 위에서 내려다본 색 (내 머리 높이 아래에서 가장 위 블록). 높을수록 밝고 낮을수록 어둡다
 *  - 노란 화살표 = 나, 파란 점 = 다른 플레이어, 마름모 = 웨이포인트 (같은 월드에 있는 것만)
 *  - 지도를 누르거나 길게 누르면 그 자리에 웨이포인트를 만든다 (가까운 웨이포인트를 누르면 수정)
 */
class MapPanel(private val activity: Activity, private val ui: Ui, private val serverId: String) : LinearLayout(activity) {

    private val p = ui.p
    private val store = Store(activity)
    /** 그릴 때마다 저장소를 읽지 않도록 들고 있는 웨이포인트 목록 */
    private var wps: List<Waypoint> = store.waypoints(serverId)
    val mapView = MinimapView()
    private val coords: TextView
    private var mask = 0

    init {
        orientation = VERTICAL
        val mapCard = ui.card().apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
        mapCard.addView(mapView, ui.lp(Ui.MATCH, 0, 1f))
        val infoRow = ui.horizontal().apply { setPadding(ui.dp(4), ui.dp(6), 0, 0); gravity = Gravity.CENTER_VERTICAL }
        // 움직일 때마다 글씨가 바뀌므로 흐르는 글씨(마키)로 두면 매번 처음으로 돌아간다 → 두 줄로 나눠 그대로 보여 준다
        coords = ui.text("", 13f, p.muted).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoRow.addView(coords, ui.lp(0, Ui.WRAP, 1f))
        infoRow.addView(ui.button("−", "plain") { mapView.zoom(-1) }, ui.lp(ui.dp(44), ui.dp(38)))
        infoRow.addView(ui.button("+", "plain") { mapView.zoom(+1) }, ui.lp(ui.dp(44), ui.dp(38)).apply { leftMargin = ui.dp(6) })
        mapCard.addView(infoRow, ui.lp())
        val wpRow = ui.horizontal().apply { setPadding(0, ui.dp(6), 0, 0) }
        wpRow.addView(ui.fit(ui.button("+ 웨이포인트", "primary") { openWaypointDialog(null, null) }), ui.lp(0, ui.dp(40), 1f))
        wpRow.addView(ui.fit(ui.button("웨이포인트 목록", "plain") { openWaypointList() }), ui.lp(0, ui.dp(40), 1f).apply { leftMargin = ui.dp(6) })
        mapCard.addView(wpRow, ui.lp())
        addView(mapCard, ui.lp(Ui.MATCH, 0, 1f))

        // 이동 버튼: 누르고 있는 동안 움직임
        val pad = ui.card().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        val grid = ui.horizontal()
        val dpad = ui.vertical()
        val size = ui.dp(54)
        fun spacer() = View(activity)
        val r1 = ui.horizontal(); r1.addView(spacer(), ui.lp(size, size)); r1.addView(holdButton("▲", 1), ui.lp(size, size)); r1.addView(spacer(), ui.lp(size, size))
        val r2 = ui.horizontal(); r2.addView(holdButton("◀", 4), ui.lp(size, size)); r2.addView(spacer(), ui.lp(size, size)); r2.addView(holdButton("▶", 8), ui.lp(size, size))
        val r3 = ui.horizontal(); r3.addView(spacer(), ui.lp(size, size)); r3.addView(holdButton("▼", 2), ui.lp(size, size)); r3.addView(spacer(), ui.lp(size, size))
        dpad.addView(r1); dpad.addView(r2); dpad.addView(r3)
        grid.addView(dpad)
        val right = ui.vertical().apply { gravity = Gravity.CENTER }
        right.addView(holdButton("점프", 16, primary = true), ui.lp(ui.dp(90), ui.dp(90)))
        right.addView(ui.text("▲ 북  ▼ 남  ◀ 서  ▶ 동", 11f, p.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        grid.addView(right, ui.lp(0, Ui.WRAP, 1f))
        pad.addView(grid)
        pad.addView(ui.hint("0.6칸 이하 턱(반 블록, 계단)은 자동으로 올라갑니다. 아이템 사용은 인벤 탭에서 합니다."))
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

    // ------------------------------------------------------------ 웨이포인트

    private fun currentWorld(): String = SessionHub.live(serverId)?.worldName.orEmpty()

    /** [existing] 가 있으면 수정, 없으면 새로 만들기. [at] = 처음 채울 좌표 (없으면 내 위치) */
    private fun openWaypointDialog(existing: Waypoint?, at: IntArray?) {
        val conn = SessionHub.live(serverId)
        val start = at ?: conn?.let { intArrayOf(floor(it.currentX).toInt(), floor(it.currentY).toInt(), floor(it.currentZ).toInt()) } ?: intArrayOf(0, 64, 0)
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)) }
        fun label(s: String) = ui.text(s, 12f, p.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) }

        box.addView(label("이름"))
        val nameIn = ui.input("예: 집, 광산 입구").apply { setText(existing?.name ?: "웨이포인트 ${store.waypoints(serverId).size + 1}") }
        box.addView(nameIn, ui.lp())
        box.addView(label("월드 (멀티버스 등 월드가 여러 개인 서버 구분용)"))
        val worldIn = ui.input("예: minecraft:overworld").apply { setText(existing?.world ?: currentWorld()) }
        box.addView(worldIn, ui.lp())
        box.addView(label("좌표 X / Y / Z"))
        val xyz = ui.horizontal()
        fun numIn(v: Int) = ui.input("0").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(v.toString())
        }
        val xIn = numIn(existing?.x ?: start[0])
        val yIn = numIn(existing?.y ?: start[1])
        val zIn = numIn(existing?.z ?: start[2])
        xyz.addView(xIn, ui.lp(0, Ui.WRAP, 1f))
        xyz.addView(yIn, ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(6) })
        xyz.addView(zIn, ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(6) })
        box.addView(xyz, ui.lp())
        if (conn != null) {
            box.addView(ui.button("지금 내 위치로", "ghost") {
                xIn.setText(floor(conn.currentX).toInt().toString())
                yIn.setText(floor(conn.currentY).toInt().toString())
                zIn.setText(floor(conn.currentZ).toInt().toString())
                worldIn.setText(conn.worldName)
            }, ui.lp(Ui.WRAP, ui.dp(36)))
        }
        box.addView(label("색"))
        var color = existing?.color ?: COLORS[store.waypoints(serverId).size % COLORS.size]
        val colorRow = ui.horizontal()
        val swatches = ArrayList<View>()
        fun refreshSwatches() {
            swatches.forEachIndexed { i, v ->
                v.background = ui.rounded(COLORS[i], 20).apply { if (COLORS[i] == color) setStroke(ui.dp(3), p.text) }
            }
        }
        for (c in COLORS) {
            val v = View(activity)
            v.setOnClickListener { color = c; refreshSwatches() }
            swatches.add(v)
            colorRow.addView(v, ui.lp(ui.dp(32), ui.dp(32)).apply { rightMargin = ui.dp(6) })
        }
        refreshSwatches()
        box.addView(colorRow, ui.lp().apply { bottomMargin = ui.dp(8) })

        val b = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "웨이포인트 만들기" else "웨이포인트 수정")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                val x = xIn.text.toString().toIntOrNull()
                val y = yIn.text.toString().toIntOrNull()
                val z = zIn.text.toString().toIntOrNull()
                if (x == null || y == null || z == null) {
                    Toast.makeText(activity, "좌표를 숫자로 입력해 주세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = nameIn.text.toString().trim().ifBlank { "웨이포인트" }.take(30)
                val wp = existing?.copy(name = name, world = worldIn.text.toString().trim(), x = x, y = y, z = z, color = color)
                    ?: Waypoint(name = name, world = worldIn.text.toString().trim(), x = x, y = y, z = z, color = color)
                store.saveWaypoint(serverId, wp)
                wps = store.waypoints(serverId)
                mapView.invalidate()
            }
            .setNegativeButton("취소", null)
        if (existing != null) {
            b.setNeutralButton("삭제") { _, _ ->
                store.deleteWaypoint(serverId, existing.id)
                wps = store.waypoints(serverId)
                mapView.invalidate()
            }
        }
        b.show()
    }

    private fun openWaypointList() {
        val list = store.waypoints(serverId)
        if (list.isEmpty()) {
            Toast.makeText(activity, "웨이포인트가 없습니다. 지도를 누르거나 + 웨이포인트로 만드세요", Toast.LENGTH_LONG).show()
            return
        }
        val conn = SessionHub.live(serverId)
        val world = currentWorld()
        val box = ui.vertical().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8)) }
        var dialog: AlertDialog? = null
        for (wp in list.sortedWith(compareBy({ it.world != world }, { it.name }))) {
            val row = ui.horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.rounded(p.input, 10)
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                isClickable = true
            }
            row.addView(View(activity).apply { background = ui.rounded(wp.color, 8) }, ui.lp(ui.dp(16), ui.dp(16)).apply { rightMargin = ui.dp(10) })
            val texts = ui.vertical()
            texts.addView(ui.marquee(ui.text(wp.name, 15f, bold = true)))
            val dist = if (conn != null && (wp.world == world || wp.world.isBlank())) " · ${hypot(wp.x - conn.currentX, wp.z - conn.currentZ).toInt()}m" else ""
            texts.addView(ui.marquee(ui.text("${wp.x}, ${wp.y}, ${wp.z} · ${wp.world.ifBlank { "월드 미지정" }}$dist", 12f, p.muted)))
            row.addView(texts, ui.lp(0, Ui.WRAP, 1f))
            row.setOnClickListener {
                dialog?.dismiss()
                openWaypointDialog(wp, null)
            }
            box.addView(row, ui.lp().apply { topMargin = ui.dp(6) })
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("웨이포인트 (${list.size}개)")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------ 지도

    inner class MinimapView : View(activity) {
        private var cell = ui.dp(6).toFloat() // 블록 한 칸 크기
        private val fill = Paint()
        private val bmpPaint = Paint().apply { isFilterBitmap = false }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ui.dp(11).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(2).toFloat() }
        private val arrow = Path()
        private val diamond = Path()
        private val rect = RectF()
        private val colGap = if (p.dark) 0xFF101720.toInt() else 0xFFE9EEF4.toInt()
        private val colOther = 0xFF4DABF7.toInt()

        // 위에서 본 색을 1블록 = 1픽셀 비트맵으로 만들어 두고 확대해서 그린다
        private var bmp: Bitmap? = null
        private var pixels = IntArray(0)
        private var tops = IntArray(0)
        private var bmpX0 = 0
        private var bmpZ0 = 0
        private var bmpSize = 0
        private var bmpFeet = Int.MIN_VALUE
        private var bmpVersion = -1L
        private var bmpWorld: World? = null
        private var bmpBuiltAt = 0L

        private val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapAt(e.x, e.y)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                tapAt(e.x, e.y)
            }
        })

        /** ChatPanel 이 좌우 넘기기와 같이 쓰도록 터치를 넘겨준다 */
        fun touch(ev: MotionEvent) = gestures.onTouchEvent(ev)

        fun zoom(dir: Int) {
            cell = (cell + dir * ui.dp(2)).coerceIn(ui.dp(2).toFloat(), ui.dp(16).toFloat())
            invalidate()
        }

        private fun tapAt(tx: Float, ty: Float) {
            val conn = SessionHub.live(serverId)
            if (conn == null) {
                openWaypointDialog(null, null)
                return
            }
            val px = conn.currentX
            val pz = conn.currentZ
            val cx = width / 2f
            val cy = height / 2f
            // 가까운 웨이포인트를 눌렀으면 수정
            val world = conn.worldName
            val near = wps.filter { it.world == world || it.world.isBlank() }.minByOrNull {
                val sx = cx + ((it.x + 0.5 - px).toFloat()) * cell
                val sy = cy + ((it.z + 0.5 - pz).toFloat()) * cell
                hypot(sx - tx, sy - ty)
            }
            if (near != null) {
                val sx = cx + ((near.x + 0.5 - px).toFloat()) * cell
                val sy = cy + ((near.z + 0.5 - pz).toFloat()) * cell
                if (hypot(sx - tx, sy - ty) < ui.dp(22)) {
                    openWaypointDialog(near, null)
                    return
                }
            }
            val bx = floor(px + (tx - cx) / cell).toInt()
            val bz = floor(pz + (ty - cy) / cell).toInt()
            val i = bx - bmpX0
            val j = bz - bmpZ0
            val y = if (i in 0 until bmpSize && j in 0 until bmpSize && tops[j * bmpSize + i] != Int.MIN_VALUE) tops[j * bmpSize + i] + 1 else floor(conn.currentY).toInt()
            openWaypointDialog(null, intArrayOf(bx, y, bz))
        }

        /** 백그라운드에서 만든 지도 한 장 (메인 스레드에서 비트맵에 옮긴다) */
        private inner class Built(
            val pixels: IntArray, val tops: IntArray, val size: Int,
            val x0: Int, val z0: Int, val feet: Int, val version: Long, val world: World,
        )

        /** 지금 백그라운드에서 만드는 중인지 (한 번에 하나만) */
        private var building = false

        /**
         * 지도 비트맵을 백그라운드 스레드에서 다시 만든다. 반경이 크면(최대 325×325칸 × 높이 수십 칸) 수백만 번 블록을 읽어서
         * 메인 스레드에서 하면 걸을 때마다 버벅이거나 "응답 없음" 이 뜰 수 있다. 만드는 동안은 이전 비트맵을 제자리에 그린다.
         */
        private fun requestRebuild(world: World, bx0: Int, bz0: Int, feet: Int, radius: Int) {
            if (building) return
            building = true
            // 만드는 도중에 청크가 바뀌면 다음 그리기에서 다시 만들도록 시작 전에 버전을 읽어 둔다
            val version = world.version
            MAP_BUILDER.execute {
                val built = try {
                    build(world, bx0, bz0, feet, radius, version)
                } catch (t: Throwable) {
                    SessionHub.log.w("지도 만들기 실패", t)
                    null
                }
                post {
                    building = false
                    if (built != null) apply(built)
                    invalidate()
                }
            }
        }

        private fun apply(b: Built) {
            if (bmp == null || bmpSize != b.size) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(b.size, b.size, Bitmap.Config.ARGB_8888)
                bmpSize = b.size
            }
            bmp!!.setPixels(b.pixels, 0, b.size, 0, 0, b.size, b.size)
            pixels = b.pixels
            tops = b.tops
            bmpX0 = b.x0
            bmpZ0 = b.z0
            bmpFeet = b.feet
            bmpVersion = b.version
            bmpWorld = b.world
            bmpBuiltAt = System.currentTimeMillis()
        }

        /** 백그라운드 스레드에서 실행 (뷰의 필드는 건드리지 않는다) */
        private fun build(world: World, bx0: Int, bz0: Int, feet: Int, radius: Int, version: Long): Built {
            val size = radius * 2 + 1
            val pixels = IntArray(size * size)
            val tops = IntArray(size * size)
            val x0 = bx0 - radius
            val z0 = bz0 - radius
            // 밖에 있으면(머리 위가 뚫려 있으면) 나무 같은 높은 것도 보이게 위에서부터, 안이면 지붕을 건너뛰게 머리 높이부터
            var outdoors = true
            for (yy in feet + 2..feet + 24) {
                val st = world.stateAt(bx0, yy, bz0)
                if (st > 0 && BlockColors.of(st) != 0) { outdoors = false; break }
            }
            val startY = if (outdoors) feet + 24 else feet + 2
            for (j in 0 until size) {
                for (i in 0 until size) {
                    val bx = x0 + i
                    val bz = z0 + j
                    var color = 0
                    var top = Int.MIN_VALUE
                    // 위에서 아래로 내려가며 처음 보이는 블록
                    var y = startY
                    val bottom = feet - 40
                    while (y >= bottom) {
                        val s = world.stateAt(bx, y, bz)
                        if (s < 0) break // 아직 안 받은 청크
                        val c = BlockColors.of(s)
                        if (c != 0) {
                            color = shade(c, y - feet)
                            top = y
                            break
                        }
                        y--
                    }
                    pixels[j * size + i] = color
                    tops[j * size + i] = top
                }
            }
            return Built(pixels, tops, size, x0, z0, feet, version, world)
        }

        /** 발밑(-1)이 기본 밝기. 벽(발 높이 이상)은 밝게, 낮은 곳은 어둡게 */
        private fun shade(c: Int, dy: Int): Int {
            val f = if (dy >= 0) 1.3f else max(0.4f, 1f - 0.07f * (-1 - dy))
            val r = min(255, (Color.red(c) * f).toInt())
            val g = min(255, (Color.green(c) * f).toInt())
            val b = min(255, (Color.blue(c) * f).toInt())
            return Color.rgb(r, g, b)
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
                coords.setTextIfChanged("")
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
            val bx0 = floor(px).toInt()
            val bz0 = floor(pz).toInt()
            val radius = (max(w, h) / 2 / cell).toInt().coerceIn(4, 160) + 2
            val now = System.currentTimeMillis()
            val stale = bmp == null || bmpSize != radius * 2 + 1 || bmpWorld !== world ||
                abs(bx0 - (bmpX0 + bmpSize / 2)) > 0 || abs(bz0 - (bmpZ0 + bmpSize / 2)) > 0 || feet != bmpFeet ||
                (world.version != bmpVersion && now - bmpBuiltAt > 400)
            if (stale) requestRebuild(world, bx0, bz0, feet, radius)
            bmp?.let {
                canvas.save()
                canvas.translate(cx + ((bmpX0 - px).toFloat()) * cell, cy + ((bmpZ0 - pz).toFloat()) * cell)
                canvas.scale(cell, cell)
                canvas.drawBitmap(it, 0f, 0f, bmpPaint)
                canvas.restore()
            }

            // 웨이포인트 (같은 월드만, 화면 밖이면 가장자리에 방향 표시)
            val worldName = conn.worldName
            text.textAlign = Paint.Align.CENTER
            val edge = ui.dp(14).toFloat()
            for (wp in wps) {
                if (wp.world.isNotBlank() && worldName.isNotBlank() && wp.world != worldName) continue
                var sx = cx + ((wp.x + 0.5 - px).toFloat()) * cell
                var sy = cy + ((wp.z + 0.5 - pz).toFloat()) * cell
                val inside = sx in edge..(w - edge) && sy in edge..(h - edge)
                if (!inside) {
                    // 중심에서 그 방향으로 가장자리까지
                    val dx = sx - cx
                    val dy = sy - cy
                    val t = min((w / 2 - edge) / max(abs(dx), 0.001f), (h / 2 - edge) / max(abs(dy), 0.001f))
                    sx = cx + dx * t
                    sy = cy + dy * t
                }
                val r = if (inside) ui.dp(7).toFloat() else ui.dp(5).toFloat()
                diamond.reset()
                diamond.moveTo(sx, sy - r); diamond.lineTo(sx + r, sy); diamond.lineTo(sx, sy + r); diamond.lineTo(sx - r, sy); diamond.close()
                dot.color = wp.color
                canvas.drawPath(diamond, dot)
                stroke.color = Color.WHITE
                canvas.drawPath(diamond, stroke)
                val dist = hypot(wp.x + 0.5 - px, wp.z + 0.5 - pz).toInt()
                val label = if (inside) wp.name else "${wp.name} ${dist}m"
                text.color = if (p.dark) Color.WHITE else 0xFF1B2430.toInt()
                text.setShadowLayer(ui.dp(2).toFloat(), 0f, 0f, if (p.dark) Color.BLACK else Color.WHITE)
                val ly = if (sy - r - ui.dp(4) < edge) sy + r + ui.dp(12) else sy - r - ui.dp(4)
                canvas.drawText(label, sx.coerceIn(ui.dp(40).toFloat(), w - ui.dp(40)), ly, text)
                text.clearShadowLayer()
            }

            // 다른 플레이어
            val names = SessionHub.players(serverId).players.associateBy({ it.uuid }, { it.name })
            for (e in world.players.values) {
                val sx = cx + ((e.x - px).toFloat()) * cell
                val sy = cy + ((e.z - pz).toFloat()) * cell
                if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) continue
                dot.color = colOther
                canvas.drawCircle(sx, sy, ui.dp(5).toFloat(), dot)
                stroke.color = Color.WHITE
                canvas.drawCircle(sx, sy, ui.dp(5).toFloat(), stroke)
                val label = names[e.uuid] ?: continue
                text.color = colOther
                text.setShadowLayer(ui.dp(2).toFloat(), 0f, 0f, if (p.dark) Color.BLACK else Color.WHITE)
                canvas.drawText(label, sx, sy - ui.dp(8), text)
                text.clearShadowLayer()
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
            stroke.color = Color.BLACK
            canvas.drawPath(arrow, stroke)

            // 북쪽 표시
            text.color = p.text
            text.textAlign = Paint.Align.LEFT
            rect.set(ui.dp(6).toFloat(), ui.dp(6).toFloat(), ui.dp(30).toFloat(), ui.dp(28).toFloat())
            fill.color = p.card
            canvas.drawRoundRect(rect, ui.dp(6).toFloat(), ui.dp(6).toFloat(), fill)
            canvas.drawText("N ↑", ui.dp(9).toFloat(), ui.dp(22).toFloat(), text)

            val worldShort = worldName.removePrefix("minecraft:")
            coords.setTextIfChanged("X ${floor(px).toInt()}  Y ${floor(py).toInt()}  Z ${floor(pz).toInt()}\n" +
                (if (worldShort.isNotBlank()) "$worldShort  ·  " else "") + "주변 ${world.players.size}명")
            postInvalidateDelayed(100)
        }
    }

    companion object {
        /** 지도 비트맵을 만드는 스레드 하나 (모든 지도 화면이 같이 쓴다. 화면을 열 때마다 스레드가 늘지 않도록) */
        private val MAP_BUILDER = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "jamsuham-map").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        val COLORS = intArrayOf(
            0xFFFF5252.toInt(), 0xFFFFA726.toInt(), 0xFFFFEB3B.toInt(), 0xFF66BB6A.toInt(),
            0xFF26C6DA.toInt(), 0xFF42A5F5.toInt(), 0xFFAB47BC.toInt(), 0xFFEC407A.toInt(),
        )
    }
}
