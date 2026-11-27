package kr.jamsuham.app

/**
 * 체험 모드: 로그인 없이 앱을 써 볼 수 있게 서버 목록에 넣는 항목 하나.
 * 접속하는 곳은 앱 안에서 도는 연습용 서버([kr.jamsuham.core.demo.DemoServer])라 계정도 인터넷도 필요 없다.
 * 설정은 여기 적힌 값으로 정해져 있고, 화면에서는 이름 바꾸기와 지우기만 된다 ([ServerEditActivity]).
 */
object Demo {
    const val ID = "demo"

    fun exists(store: Store): Boolean = store.server(ID) != null

    /** 목록에 체험 서버를 넣는다 (이미 있으면 그대로 돌려준다) */
    fun add(store: Store): ServerEntry {
        store.server(ID)?.let { return it }
        val entry = ServerEntry(
            id = ID,
            demo = true,
            name = "", // 비워 두면 앱 언어에 맞춰 "체험 서버" / "Demo server" 로 보인다
            address = "",
            fixedVersion = "1.21.4",
            joinDelaySec = 2,
            resumeOnBoot = false,
            packGlyphs = false,
            walkOnJoin = false,
            cloud = false,
        )
        store.saveServer(entry)
        return entry
    }
}
