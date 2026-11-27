package kr.jamsuham.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * 스토어 스크린샷 전용 모드. GitHub Actions 의 screenshots.yml 이 JAMSUHAM_SCREENSHOTS=true 로 빌드할 때만 켜진다
 * (매니페스트 meta-data kr.jamsuham.SCREENSHOTS). 배포하는 GitHub 판·Play 판에서는 항상 꺼져 있다.
 *
 * 켜지면: 에뮬레이터에서 MS 로그인을 할 수 없으므로 시험 계정 하나(비정품 이름 접속)를 쓰고,
 * 에뮬레이터 밖 PC 의 시험 서버(10.0.2.2 = PC 의 127.0.0.1)를 서버 목록에 넣고, 화면을 한국어로 고정한다.
 */
object Screenshots {
    @Volatile var on = false
        private set

    const val NAME = "Jamsu_Diver"
    val account = Account(id = "ms:screenshot", name = NAME, microsoft = true, uuid = "5d3c2a1b-0000-4000-8000-00000000a1f5")

    /** AppSettings.init 보다 먼저 부른다 (언어 기본값을 넣으려고) */
    fun init(context: Context) {
        on = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.get("kr.jamsuham.SCREENSHOTS")?.toString() == "true"
        } catch (_: Throwable) {
            false
        }
        if (!on) return
        val settings = context.getSharedPreferences("jamsuham_settings", Context.MODE_PRIVATE)
        if (!settings.contains("language")) settings.edit().putString("language", "KOREAN").apply()
        val store = Store(context)
        if (store.servers().isNotEmpty()) return
        fun server(n: Int, name: String, port: Int, commands: List<String> = emptyList()) = ServerEntry(
            id = "shot-$n",
            name = name,
            address = "10.0.2.2:$port",
            accountId = account.id,
            joinCommands = commands,
            joinDelaySec = 2,
            resumeOnBoot = false,
            packGlyphs = false,
            walkOnJoin = false,
        )
        store.saveServer(server(1, "바다마을 야생", 25565, listOf("/잠수")))
        store.saveServer(server(2, "하늘섬 경제", 25566))
        store.saveServer(server(3, "블록 미니게임", 25567))
    }
}
