package kr.jamsuham.core.world

import io.netty.buffer.ByteBuf
import kr.jamsuham.core.net.readVarInt
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.floor

/**
 * 주변 청크(블록)와 다른 플레이어 위치를 기억한다. 이동할 때 벽/바닥 판정과 미니맵에 쓴다.
 * 1.21.4 청크 형식만 읽으면 된다 (다른 버전은 ViaVersion 이 1.21.4 형식으로 바꿔 준다).
 *
 * 네트워크 스레드가 쓰고 화면 스레드가 읽는다. 화면이 잠깐 덜 갱신된 값을 보는 정도는 괜찮다.
 */
class World {
    private class Chunk(val sections: Array<ShortArray?>)

    private val chunks = ConcurrentHashMap<Long, Chunk>()
    @Volatile var minY = -64
        private set

    /** 다른 플레이어들 (엔티티 id → 정보) */
    val players = ConcurrentHashMap<Int, Entity>()

    data class Entity(val id: Int, val uuid: UUID, @Volatile var x: Double, @Volatile var y: Double, @Volatile var z: Double)

    /** 블록이 바뀔 때마다 1씩 오른다 (미니맵이 다시 그릴지 판단용) */
    @Volatile var version = 0L
        private set

    /** 접속·리스폰 패킷의 차원 종류에서 알아낸 아래 끝 높이 (데이터팩으로 높이를 바꾼 월드 대응). 모르면 null */
    @Volatile private var dimensionMinY: Int? = null

    fun setDimensionMinY(y: Int?) {
        dimensionMinY = y
        if (y != null) minY = y
    }

    fun clear() {
        version++
        chunks.clear()
        players.clear()
    }

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    fun isLoaded(bx: Int, bz: Int) = chunks.containsKey(key(bx shr 4, bz shr 4))

    // ------------------------------------------------------------ 블록

    fun stateAt(bx: Int, by: Int, bz: Int): Int {
        val c = chunks[key(bx shr 4, bz shr 4)] ?: return -1
        val si = (by - minY) shr 4
        if (by < minY || si >= c.sections.size) return 0
        val sec = c.sections[si] ?: return 0
        return sec[(((by - minY) and 15) shl 8) or ((bz and 15) shl 4) or (bx and 15)].toInt() and 0xFFFF
    }

    /**
     * 블록 충돌 높이 (블록 단위). 0 = 통과, 1 = 한 칸, 1.5 = 울타리.
     * 청크가 아직 없으면 벽으로 본다 (모르는 곳으로는 움직이지 않음).
     */
    fun heightAt(bx: Int, by: Int, bz: Int): Double {
        val s = stateAt(bx, by, bz)
        if (s < 0) return 1.0
        return Collision.height(s)
    }

    fun setBlock(bx: Int, by: Int, bz: Int, state: Int) {
        val c = chunks[key(bx shr 4, bz shr 4)] ?: return
        val si = (by - minY) shr 4
        if (by < minY || si >= c.sections.size) return
        var sec = c.sections[si] ?: ShortArray(4096).also { c.sections[si] = it }
        // 여러 섹션이 같이 쓰는 "한 가지 블록뿐인 섹션" 배열이면 고치기 전에 이 섹션만의 사본을 만든다
        if (uniform[sec[0].toInt() and 0xFFFF] === sec) {
            sec = sec.copyOf()
            c.sections[si] = sec
        }
        sec[(((by - minY) and 15) shl 8) or ((bz and 15) shl 4) or (bx and 15)] = state.toShort()
        version++
    }

    fun unload(cx: Int, cz: Int) {
        chunks.remove(key(cx, cz))
        version++
    }

    /** LEVEL_CHUNK_WITH_LIGHT 의 청크 데이터 부분 */
    fun loadChunk(cx: Int, cz: Int, data: ByteBuf) {
        val sections = ArrayList<ShortArray?>(24)
        try {
            while (data.isReadable) {
                if (data.readableBytes() < 3) break
                val nonAir = data.readShort().toInt()
                val blocks = readContainer(data, 4096, 4, 8, keep = true)
                readContainer(data, 64, 1, 3, keep = false) // 바이옴은 버림
                sections.add(if (nonAir == 0 && blocks == null) null else blocks)
                if (sections.size > MAX_SECTIONS) throw IllegalArgumentException("섹션이 너무 많음")
            }
        } catch (t: Exception) {
            // 이상한 청크 하나 때문에 잠수 연결이 끊기지 않도록 그 청크만 버린다 (미니맵·이동에 그 칸만 비어 보임)
            return
        }
        // 서버가 알려 준 차원 높이가 있으면 그걸 쓰고, 없으면 섹션 수로 짐작한다 (오버월드 24칸 = -64, 16칸 = 0)
        minY = dimensionMinY ?: when (sections.size) {
            24 -> -64
            16 -> 0
            else -> if (sections.size > 16) -64 else 0
        }
        chunks[key(cx, cz)] = Chunk(sections.toTypedArray())
        version++
    }

    /**
     * 한 가지 블록으로만 된 섹션(전부 돌 등)은 블록 값마다 배열 하나를 만들어 모든 청크가 같이 쓴다.
     * 섹션마다 8KB 씩 새로 만들면 주변 청크 수백 개 × 여러 서버 동시 잠수에서 메모리가 많이 든다. 고칠 때는 [setBlock] 이 사본을 만든다.
     */
    private val uniform = ConcurrentHashMap<Int, ShortArray>()

    /** 서버가 보낸 개수를 그대로 배열 크기로 쓰기 전에 확인한다 (잘못된 값이면 큰 배열을 만들다 앱이 죽을 수 있음) */
    private fun count(buf: ByteBuf, max: Int): Int {
        val n = buf.readVarInt()
        if (n < 0 || n > max) throw IllegalArgumentException("잘못된 개수 $n")
        return n
    }

    /** 팔레트 컨테이너를 읽어 값 배열로. 전부 공기(0)이거나 [keep] 이 false 면 null */
    private fun readContainer(buf: ByteBuf, size: Int, minIndirect: Int, maxIndirect: Int, keep: Boolean): ShortArray? {
        val bits = buf.readUnsignedByte().toInt()
        if (bits == 0) {
            val v = buf.readVarInt()
            val len = count(buf, size)
            buf.skipBytes(len * 8)
            if (!keep || v == 0) return null
            val value = v and 0xFFFF
            return uniform.getOrPut(value) { ShortArray(size) { value.toShort() } }
        }
        if (bits > 32) throw IllegalArgumentException("잘못된 비트 수 $bits")
        var palette: IntArray? = null
        val bpe: Int
        if (bits <= maxIndirect) {
            bpe = maxOf(bits, minIndirect)
            palette = IntArray(count(buf, size)) { buf.readVarInt() }
        } else {
            bpe = bits
        }
        val longs = LongArray(count(buf, size)) { buf.readLong() }
        if (!keep) return null
        val perLong = 64 / bpe
        val mask = (1L shl bpe) - 1
        val out = ShortArray(size)
        for (i in 0 until size) {
            val li = i / perLong
            if (li >= longs.size) break
            val raw = ((longs[li] ushr ((i % perLong) * bpe)) and mask).toInt()
            out[i] = (if (palette != null) palette.getOrElse(raw) { 0 } else raw).toShort()
        }
        return out
    }

    // ------------------------------------------------------------ 충돌

    /** 발 위치(x, y, z)의 플레이어 상자(0.6 x 1.8)가 블록과 겹치는지 */
    fun collides(x: Double, y: Double, z: Double): Boolean {
        val x0 = floor(x - HALF + EPS).toInt()
        val x1 = floor(x + HALF - EPS).toInt()
        val z0 = floor(z - HALF + EPS).toInt()
        val z1 = floor(z + HALF - EPS).toInt()
        val y0 = floor(y + EPS).toInt() - 1 // 울타리(1.5칸)는 아래 칸에서 올라온다
        val y1 = floor(y + HEIGHT - EPS).toInt()
        for (bx in x0..x1) for (bz in z0..z1) for (by in y0..y1) {
            val h = heightAt(bx, by, bz)
            if (h <= 0.0) continue
            val top = by + h
            if (top > y + EPS && by < y + HEIGHT - EPS) return true
        }
        return false
    }

    /** 발 아래로 얼마나 떨어질 수 있는지 (최대 maxDrop) */
    fun dropDistance(x: Double, y: Double, z: Double, maxDrop: Double): Double {
        var d = 0.0
        val step = 0.05
        while (d < maxDrop) {
            val next = minOf(step, maxDrop - d)
            if (collides(x, y - d - next, z)) {
                // 정확한 바닥 높이로 맞춘다
                var lo = d
                var hi = d + next
                repeat(8) {
                    val mid = (lo + hi) / 2
                    if (collides(x, y - mid, z)) hi = mid else lo = mid
                }
                return lo
            }
            d += next
        }
        return maxDrop
    }

    companion object {
        const val HALF = 0.3
        const val HEIGHT = 1.8
        const val EPS = 1e-4
        /** 세계 높이 최대 4064칸 = 254 섹션 */
        private const val MAX_SECTIONS = 256
    }
}

/** 블록 상태 id → 충돌 높이 (1.21.4 기준, resources 의 표) */
object Collision {
    private val table: ByteArray by lazy {
        try {
            Collision::class.java.getResourceAsStream("/jamsuham/collision-1.21.4.bin")!!.use { GZIPInputStream(it).readBytes() }
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }

    fun height(state: Int): Double {
        if (state == 0) return 0.0
        val t = table
        if (state >= t.size) return 1.0
        return (t[state].toInt() and 0xFF) / 16.0
    }

    val loaded: Boolean get() = table.isNotEmpty()
}

/** 미니맵 색: 1.21.4 블록 상태 번호 → ARGB (0 = 투명, 위에서 내려다볼 때 지나침) */
object BlockColors {
    private val table: IntArray by lazy {
        try {
            BlockColors::class.java.getResourceAsStream("/jamsuham/colors-1.21.4.bin")!!.use { raw ->
                val d = java.io.DataInputStream(GZIPInputStream(raw).buffered())
                IntArray(d.readInt()) { d.readInt() }
            }
        } catch (t: Throwable) {
            IntArray(0)
        }
    }

    fun of(state: Int): Int {
        val t = table
        if (state <= 0) return 0
        if (state >= t.size) return 0xFF808080.toInt()
        return t[state]
    }
}
