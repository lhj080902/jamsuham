package kr.jamsuham.cloud

import java.io.File
import kotlin.system.exitProcess

/**
 * 잠수크래프트 클라우드 서버.
 *   jamsu-cloud [설정 파일]                  서버 실행 (기본 cloud.properties)
 *   jamsu-cloud grant <닉네임|UUID> [일수]   프리미엄 주기 (일수를 안 적으면 기한 없음)
 *   jamsu-cloud revoke <닉네임|UUID>         프리미엄 빼기
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()?.takeIf { it == "grant" || it == "revoke" }
    val configPath = if (command == null) args.firstOrNull() else null
    val config = Config(File(configPath ?: System.getenv("JAMSU_CLOUD_CONFIG") ?: "cloud.properties"))
    config.dataDir.mkdirs()
    val premium = Premium(config)

    if (command != null) {
        val who = args.getOrNull(1) ?: run {
            Log.say("사용법: jamsu-cloud $command <닉네임 또는 UUID>" + if (command == "grant") " [일수]" else "")
            exitProcess(2)
        }
        val days = args.getOrNull(2)?.toIntOrNull()
        premium.edit(who, days, remove = command == "revoke")
        Log.say(if (command == "grant") "프리미엄을 줬습니다: $who" + (days?.let { " (${it}일)" } ?: " (기한 없음)") else "프리미엄을 뺐습니다: $who")
        Log.say("→ ${File(config.dataDir, "premium.txt").absolutePath} (켜져 있는 서버에 바로 반영됩니다)")
        return
    }

    Log.i("잠수크래프트 클라우드 서버 v$CLOUD_VERSION")
    // 클라우드는 지도를 그리지 않으므로 발밑 주변 청크만 담아 둔다 (여러 사람의 잠수가 한 프로그램에서 도니 메모리를 아낀다)
    kr.jamsuham.core.world.World.maxChunks = 200
    val store = DataStore(config.dataDir)
    val vault = Vault(config.dataDir)
    val auth = MsAuth(config, store, vault)
    val scheduler = Scheduler()
    if (config.localWorker) scheduler.add(LocalRunner("local", config.localCapacity, File(config.dataDir, "engine"), config.allowPrivateTargets, cloudCoreLog))
    val sessions = SessionManager(config, store, premium, auth, Presence(), scheduler)
    val http = HttpApi(config, store, premium, auth, sessions)
    try {
        http.start()
    } catch (e: java.net.BindException) {
        Log.w("포트 ${config.port} 를 열 수 없습니다 (이미 다른 프로그램이 쓰고 있거나 서버가 이미 켜져 있음)")
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        Log.i("서버를 끕니다. 잠수 중이던 서버는 다시 켜면 이어서 접속합니다")
        http.stop()
        sessions.shutdown()
    })

    Log.i("설정 파일: ${config.file.absolutePath}")
    Log.i("데이터 폴더: ${config.dataDir.absolutePath}")
    Log.i("웹 주소: http://${if (config.bind == "0.0.0.0") "이 컴퓨터 주소" else config.bind}:${config.port}")
    if (auth.msaConfig == null) Log.w("msClientId 가 비어 있어 마이크로소프트 로그인을 쓸 수 없습니다 (cloud.properties 에 적어 주세요)")
    if (config.premiumMode == "open") Log.w("premiumMode=open: 로그인한 누구나 클라우드 잠수를 쓸 수 있습니다")
    if (config.bind != "127.0.0.1" && config.bind != "localhost") Log.w("bind=${config.bind}: HTTPS 없이 인터넷에 직접 열면 로그인 정보가 노출될 수 있습니다. 앞에 Caddy·Cloudflare 터널을 두세요")
    if (config.devLogin) Log.w("시험 모드: 닉네임만으로 로그인할 수 있습니다 (실제 운영에서는 절대 켜지 마세요)")
    if (config.allowPrivateTargets) Log.w("allowPrivateTargets=true: 내부망 주소로도 접속합니다 (시험용)")
    if (!config.localWorker && config.workerPort == 0) Log.w("잠수를 돌릴 곳이 없습니다 (localWorker=false 인데 workerPort 도 꺼져 있음)")
    Log.i("계정 ${store.accountCount()}개")

    sessions.resumeAtBoot()
    Thread.currentThread().join()
}
