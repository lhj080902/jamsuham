package kr.jamsuham.cloud

import java.io.File
import java.util.Properties

/**
 * 서버 설정 (cloud.properties). 처음 실행하면 설명이 달린 기본 파일을 만든다.
 * 시험 전용 스위치(닉네임만으로 로그인)는 설정 파일이 아니라 JVM 옵션 -Djamsu.devLogin=true 로만 켤 수 있다.
 */
class Config(val file: File) {
    private val p = Properties()

    init {
        if (!file.exists()) {
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(DEFAULT)
        }
        // 윈도우 메모장이 파일 맨 앞에 붙이는 표시(BOM)는 떼고 읽는다
        p.load(java.io.StringReader(file.readText(Charsets.UTF_8).removePrefix("\uFEFF")))
    }

    private fun str(key: String, def: String) = (p.getProperty(key) ?: def).trim()
    private fun int(key: String, def: Int) = str(key, def.toString()).toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean) = str(key, def.toString()).equals("true", ignoreCase = true)

    val port = int("port", 8787)
    val bind = str("bind", "127.0.0.1")
    val dataDir: File = File(str("dataDir", "data")).let { if (it.isAbsolute) it else File(file.absoluteFile.parentFile, it.path) }
    /** Mojang 이 승인한 자체 Azure 앱 client ID (앱과 같은 값). 비어 있으면 마이크로소프트 로그인을 쓸 수 없다 */
    val msClientId = str("msClientId", "")
    /** 앞에 리버스 프록시(Caddy·Cloudflare 터널)를 두었는지. 켜면 이 컴퓨터 안에서 온 요청의 X-Forwarded-Proto 와 아래 헤더를 믿는다 */
    val trustProxy = bool("trustProxy", true)
    /**
     * 프록시가 실제 접속자 IP 를 적어 주는 헤더. 요청 횟수 제한에 쓴다.
     * 프록시가 직접 적는 헤더 하나만 믿어야 한다 (접속자가 마음대로 적어 보낼 수 있는 헤더를 믿으면 제한을 피하거나 남의 제한을 대신 채울 수 있다).
     *   X-Forwarded-For  = Caddy·nginx (맨 뒤에 프록시가 붙인 값을 쓴다)
     *   CF-Connecting-IP = Cloudflare 터널
     *   none             = 헤더를 믿지 않음 (프록시 없이 직접 받을 때)
     */
    val clientIpHeader = str("clientIpHeader", "X-Forwarded-For")
    /** allowlist = premium.txt 에 적힌 계정만 / open = 로그인한 누구나 */
    val premiumMode = str("premiumMode", "allowlist").lowercase()
    val maxSessionsPerAccount = int("maxSessionsPerAccount", 2).coerceIn(1, 50)
    val maxServersPerAccount = int("maxServersPerAccount", 20).coerceIn(1, 200)
    val maxActiveSessions = int("maxActiveSessions", 200).coerceIn(1, 5000)
    /**
     * 같은 마인크래프트 서버에 클라우드에서 동시에 들어갈 수 있는 계정 수 (0 = 제한 없음).
     * 클라우드의 모든 계정은 이 컴퓨터의 IP 하나로 나가므로, 서버의 "같은 IP 접속 수 제한"에 걸리지 않게 여기서 먼저 줄인다.
     */
    val maxAccountsPerTarget = int("maxAccountsPerTarget", 3).coerceIn(0, 5000)
    /** 서버별로 다르게: "주소=수" 를 쉼표로 (예: 운영진이 이 IP 를 허용해 준 서버). 주소는 사용자가 적는 그대로 */
    val targetLimits: Map<String, Int> = str("targetLimits", "").split(',').mapNotNull { part ->
        val i = part.lastIndexOf('=')
        if (i <= 0) null else part.substring(i + 1).trim().toIntOrNull()?.let { kr.jamsuham.core.cloud.Wire.addressKey(part.substring(0, i)) to it }
    }.toMap()

    fun targetLimit(addressKey: String): Int = targetLimits[addressKey] ?: maxAccountsPerTarget

    /** 내부망·이 컴퓨터 주소(127.0.0.1, 192.168.x.x 등)로 접속 허용. 시험할 때만 켠다 */
    val allowPrivateTargets = bool("allowPrivateTargets", false)

    /** 시험 전용: 닉네임만으로 로그인 (비정품 시험 서버용). 설정 파일로는 못 켠다 */
    val devLogin: Boolean = System.getProperty("jamsu.devLogin") == "true"

    companion object {
        private val DEFAULT = """
            |# 잠수크래프트 클라우드 서버 설정
            |# 바꾼 뒤에는 서버를 다시 켜야 적용됩니다. (# 으로 시작하는 줄은 설명입니다)
            |
            |# 웹 주소의 포트. 브라우저에서 http://이컴퓨터주소:8787 로 들어갑니다.
            |port=8787
            |
            |# 접속을 받을 주소.
            |#   127.0.0.1 = 이 컴퓨터 안에서만 (권장: 앞에 Caddy 나 Cloudflare 터널을 두고 HTTPS 로 공개)
            |#   0.0.0.0   = 같은 공유기의 다른 기기에서도 (집 안 시험용. HTTPS 없이 인터넷에 그대로 열지 마세요)
            |bind=127.0.0.1
            |
            |# 마이크로소프트 로그인에 쓰는 client ID (Mojang 승인을 받은 Azure 앱. 잠수크래프트 앱과 같은 값)
            |msClientId=
            |
            |# 앞에 리버스 프록시(Caddy, Cloudflare 터널 등)가 있으면 true
            |trustProxy=true
            |
            |# 프록시가 실제 접속자 IP 를 적어 주는 헤더 (요청 횟수 제한에 씀). 쓰는 프록시에 맞는 것 하나만:
            |#   X-Forwarded-For  = Caddy, nginx
            |#   CF-Connecting-IP = Cloudflare 터널
            |#   none             = 프록시 없이 직접 받을 때
            |clientIpHeader=X-Forwarded-For
            |
            |# 누가 쓸 수 있는지: allowlist = premium.txt 에 적은 계정만 / open = 로그인한 누구나
            |premiumMode=allowlist
            |
            |# 한 계정이 동시에 잠수할 수 있는 서버 수
            |maxSessionsPerAccount=2
            |
            |# 한 계정이 저장할 수 있는 서버 수
            |maxServersPerAccount=20
            |
            |# 이 컴퓨터 전체에서 동시에 돌릴 잠수 수 (컴퓨터 사양에 맞춰 조절. 시험에서는 기본 약 230MB + 잠수 하나당 수 MB,
            |# 실제 서버는 지형 데이터 때문에 더 쓸 수 있음)
            |maxActiveSessions=200
            |
            |# 같은 마인크래프트 서버에 클라우드에서 동시에 들어갈 수 있는 계정 수 (0 = 제한 없음)
            |# 클라우드의 모든 계정은 이 컴퓨터의 IP 하나로 나갑니다. 서버마다 "같은 IP 에서 몇 명까지" 제한이 있으므로
            |# 그보다 적게 두세요. 넘기면 그 서버에서 이 IP 가 통째로 차단되거나 계정이 부계정으로 처리될 수 있습니다.
            |maxAccountsPerTarget=3
            |
            |# 서버별로 다르게 두기: 운영진에게 이 IP 를 허용받은 서버 등 (주소=수, 쉼표로 구분)
            |# 예) targetLimits=play.example.kr=30,mc.example.com=10
            |targetLimits=
            |
            |# 데이터(계정·서버 목록·암호화 키)를 둘 폴더
            |dataDir=data
            |
            |# 내부망 주소(127.0.0.1, 192.168.x.x 등)의 마인크래프트 서버에 접속 허용 (시험할 때만 true)
            |allowPrivateTargets=false
            |
        """.trimMargin()
    }
}
