package kr.jamsuham.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import kr.jamsuham.core.net.AddressResolver
import kr.jamsuham.core.net.StatusPinger
import kr.jamsuham.core.via.ViaSetup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 플레이어 얼굴(프사). 스킨 이미지에서 얼굴(8x8)과 모자 층을 잘라 크게 키운다.
 * 스킨 주소가 없는 경우(비정품 서버 등)는 이름 첫 글자로 만든 그림을 쓴다.
 */
object Faces {
    private const val SIZE = 64
    private val memory = LruCache<String, Bitmap>(200)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun load(context: Context, view: ImageView, skinUrl: String?, name: String) {
        view.tag = skinUrl ?: name
        val key = skinUrl ?: return view.setImageBitmap(letter(name))
        memory.get(key)?.let { return view.setImageBitmap(it) }
        view.setImageBitmap(letter(name))
        if (failed.contains(key)) return
        val dir = File(context.cacheDir, "faces").apply { mkdirs() }
        pool.execute {
            val file = File(dir, sha1(key) + ".png")
            // 데이터 절약 모드: 새 얼굴 그림은 받지 않고 글자 아이콘으로 둔다 (받아 둔 것은 그대로 씀, 실패로 기록하지 않아 와이파이에서 다시 받음)
            if (!file.exists() && AppSettings.saveDataNow()) return@execute
            val bmp = try {
                if (file.exists()) BitmapFactory.decodeFile(file.path) else {
                    val skin = download(key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    skin?.let { face(it) }?.also { f -> file.outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
            } catch (_: Throwable) {
                null
            }
            if (bmp == null) {
                failed.add(key)
                return@execute
            }
            memory.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    private fun face(skin: Bitmap): Bitmap? {
        if (skin.width < 64 || skin.height < 32) return null
        val scale = skin.width / 64 // 고해상도 스킨 대응
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        val dst = Rect(0, 0, SIZE, SIZE)
        c.drawBitmap(skin, Rect(8 * scale, 8 * scale, 16 * scale, 16 * scale), dst, paint) // 얼굴
        c.drawBitmap(skin, Rect(40 * scale, 8 * scale, 48 * scale, 16 * scale), dst, paint) // 모자 층
        return out
    }

    private val letterCache = LruCache<String, Bitmap>(100)
    private val colors = intArrayOf(0xFF5C7CFA.toInt(), 0xFF20C997.toInt(), 0xFFFF922B.toInt(), 0xFFCC5DE8.toInt(), 0xFF339AF0.toInt(), 0xFFF06595.toInt())

    fun letter(name: String): Bitmap {
        letterCache.get(name)?.let { return it }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(colors[(name.hashCode() and 0x7FFFFFFF) % colors.size])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.5f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val ch = name.trim().firstOrNull()?.uppercase() ?: "?"
        val y = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(ch, SIZE / 2f, y, paint)
        letterCache.put(name, bmp)
        return bmp
    }

    internal fun download(url: String): ByteArray? {
        @Suppress("DEPRECATION")
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Jamsuham-Android")
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** 서버 목록 핑 결과(아이콘, 문구, 접속자 수) */
data class ServerInfo(val motd: String, val online: Int, val max: Int, val version: String, val updatedAt: Long)

/**
 * 서버 아이콘과 접속자 수를 가져와 저장한다. 아이콘은 files/favicons/<서버id>.png
 */
object ServerInfos {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val infos = ConcurrentHashMap<String, ServerInfo>()
    private val icons = LruCache<String, Bitmap>(50)
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun info(id: String): ServerInfo? = infos[id]

    fun icon(context: Context, id: String): Bitmap? {
        icons.get(id)?.let { return it }
        val f = file(context, id)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { icons.put(id, it) }
    }

    private fun file(context: Context, id: String) = File(File(context.filesDir, "favicons").apply { mkdirs() }, "$id.png")

    /** 백그라운드에서 핑. 끝나면 onDone 을 메인 스레드에서 부른다 */
    fun refresh(context: Context, server: ServerEntry, onDone: () -> Unit) {
        if (server.address.isBlank() || !running.add(server.id)) return
        val app = context.applicationContext
        pool.execute {
            try {
                val target = AddressResolver.resolve(server.address, AndroidDns(app))
                val ping = StatusPinger.ping(target, ViaSetup.NATIVE_PROTOCOL, 5000)
                infos[server.id] = ServerInfo(ping.motd.plain.trim(), ping.online, ping.max, ping.versionName, System.currentTimeMillis())
                ping.favicon?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        icons.put(server.id, bmp)
                        file(app, server.id).writeBytes(bytes)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                running.remove(server.id)
                main.post(onDone)
            }
        }
    }

    fun forget(context: Context, id: String) {
        infos.remove(id)
        icons.remove(id)
        file(context, id).delete()
    }
}
