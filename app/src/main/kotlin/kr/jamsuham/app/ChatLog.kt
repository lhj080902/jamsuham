package kr.jamsuham.app

import android.content.Context
import kr.jamsuham.core.LogLine
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 채팅 기록을 서버별·날짜별 텍스트 파일로 계속 저장한다 (files/chatlogs/<서버>/2026-11-20.txt).
 * 화면에는 최근 500줄만 남지만, 여기에는 앱이 꺼졌다 켜져도 [KEEP_DAYS] 일치가 남아서 나중에 파일로 내보낼 수 있다.
 * 파일 쓰기는 별도 스레드 하나에서 한다 (화면이 멈추지 않게).
 */
object ChatLog {
    private const val KEEP_DAYS = 30
    private lateinit var root: File
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "jamsuham-chatlog").apply { isDaemon = true } }
    private val dayFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }

    fun init(context: Context) {
        root = File(context.filesDir, "chatlogs")
        writer.execute { cleanup() }
    }

    private fun dir(serverId: String) = File(root, serverId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    /** 마지막으로 쓴 날짜 (날짜가 바뀌면 오래된 기록을 정리. 앱이 몇 주씩 켜져 있어도 30일 규칙이 지켜지게) */
    private var lastDay = ""

    fun append(serverId: String, line: LogLine) {
        if (!::root.isInitialized) return
        writer.execute {
            try {
                val day = dayFmt.get()!!.format(Date(line.timeMs))
                if (day != lastDay) {
                    if (lastDay.isNotEmpty()) cleanup()
                    lastDay = day
                }
                val d = dir(serverId).apply { mkdirs() }
                val f = File(d, "$day.txt")
                f.appendText(format(line) + "\n", Charsets.UTF_8)
            } catch (t: Throwable) {
                SessionHub.log.w("채팅 기록 저장 실패", t)
            }
        }
    }

    /** 한 줄: "[14:32:05] 채팅" / 시스템 안내는 "* ", 오류는 "! " 를 붙인다 */
    private fun format(line: LogLine): String {
        val mark = when (line.kind) {
            LogLine.Kind.CHAT -> ""
            LogLine.Kind.SYSTEM -> "* "
            LogLine.Kind.ERROR -> "! "
        }
        return "[" + timeFmt.get()!!.format(Date(line.timeMs)) + "] " + mark + line.text.plain.replace('\n', ' ')
    }

    /** 저장된 날짜 목록 (최신순) */
    fun days(serverId: String): List<String> {
        if (!::root.isInitialized) return emptyList()
        return dir(serverId).listFiles { f -> f.name.endsWith(".txt") }?.map { it.name.removeSuffix(".txt") }?.sortedDescending() ?: emptyList()
    }

    /**
     * 최근 [lastDays] 일치 기록을 하나의 텍스트로 내보낸다 (null = 전부). 백그라운드 스레드에서 부른다.
     * 아직 쓰는 중인 줄까지 들어가도록 기록 스레드가 밀린 일을 끝낸 뒤 읽는다.
     */
    fun export(serverId: String, serverName: String, lastDays: Int?, out: OutputStream): Int {
        writer.submit {}.get() // 밀린 기록을 먼저 파일에 쓴다
        val all = days(serverId).sorted()
        // 달력 기준: 오늘 포함 최근 lastDays 일 (기록이 없는 날은 건너뜀)
        val picked = if (lastDays == null) all else {
            val cutoff = dayFmt.get()!!.format(Date(System.currentTimeMillis() - (lastDays - 1) * 86_400_000L))
            all.filter { it >= cutoff }
        }
        var lines = 0
        out.bufferedWriter(Charsets.UTF_8).let { w ->
            w.write("잠수크래프트 채팅 기록 · $serverName\n")
            w.write("내보낸 시각: " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()) + "\n")
            w.write("(* = 앱 안내, ! = 오류)\n")
            for (day in picked) {
                w.write("\n===== $day =====\n")
                File(dir(serverId), "$day.txt").forEachLine(Charsets.UTF_8) { l ->
                    w.write(l)
                    w.write("\n")
                    lines++
                }
            }
            w.flush()
        }
        return lines
    }

    /** 서버를 지우면 그 서버의 기록도 지운다 */
    /** 저장된 채팅 기록 전체 용량 (바이트) */
    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 모든 서버의 채팅 기록을 지운다 */
    fun deleteAll() {
        writer.execute { root.listFiles()?.forEach { it.deleteRecursively() } }
    }

    fun delete(serverId: String) {
        if (!::root.isInitialized) return
        writer.execute { dir(serverId).deleteRecursively() }
    }

    /** [KEEP_DAYS] 일보다 오래된 날짜 파일을 지운다 */
    private fun cleanup() {
        try {
            val cutoff = dayFmt.get()!!.format(Date(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L))
            root.listFiles()?.forEach { d ->
                d.listFiles()?.forEach { f -> if (f.name.removeSuffix(".txt") < cutoff) f.delete() }
            }
        } catch (_: Throwable) {
        }
    }
}
