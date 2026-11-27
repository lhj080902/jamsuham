# 클라우드 멀티 노드 (본 서버 + 접속용 서버) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 클라우드 서버를 "본 서버(관리) + 접속용 서버(실행)"로 나눌 수 있게 하되, 설정을 안 바꾼 기존 1대 설치는 그대로 동작하게 한다.

**Architecture:** `SessionManager` 가 `AfkSession` 을 직접 다루던 곳을 `Runner`/`RunHandle` 경계로 바꾼다. 내장 실행기(`LocalRunner`)는 지금 코드 그대로이고, 원격 접속용 서버는 같은 `LocalRunner` 를 돌리면서 본 서버로 먼저 TCP 연결(사전 공유 키 인증 + AES-GCM)해 `RemoteRunner` 로 보인다. 배정·서버별 인원·중복 접속 판단은 전부 본 서버가 한다.

**Tech Stack:** Kotlin/JVM 17, Netty 4.1(이미 core 의존성), Gson, JDK 표준 암호(HmacSHA256, AES/GCM/NoPadding). 새 라이브러리 없음.

**Spec:** `docs/superpowers/specs/2026-11-27-cloud-multinode-design.md` (실행 전에 같이 읽을 것)

## Global Constraints

- **core 모듈은 고치지 않는다** (`core/` 변경 0줄 → 앱 컴파일 확인 불필요). `Wire.API` = 1 그대로, `/api/*`·SSE 형식 변경 없음, 웹(`cloud/src/main/resources/web/`) 변경 없음.
- 새 설정이 없는 기존 `cloud.properties` 는 지금과 똑같이 동작 (`localWorker=true`, `workerPort=0`).
- 서버별 인원 제한(`maxAccountsPerTarget`/`targetLimits`)은 **본 서버 전체 합계**. 노드(IP)별로 세는 코드를 만들지 않는다.
- 로그인 정보·토큰은 접속용 서버 디스크에 쓰지 않는다 (메모리에만).
- 추가 라이브러리 금지. 배포 파일은 `jamsu-cloud.zip` 하나(같은 jar 가 두 역할).
- 코드 주석·로그·사용자 문구는 한국어, 기존 파일의 문체를 따른다. 사용자에게 보이는 문구에 이모지 금지.
- 버전: `gradle.properties` `jamsuham.version=2.1.0`.
- 빌드: `./gradlew -p tools/corebuild :cloud:installDist :harness:installDist` (서버가 켜진 채 빌드 금지 — jar 가 바뀌어 응답이 깨짐).
- 커밋은 `feature/cloud-multinode` 브랜치에만. **`main` 에 push 금지** (push 하면 릴리스가 자동 게시됨) — push·merge 는 사용자가 지시할 때만.
- 시험용 시간 조절은 JVM 옵션으로만: `-Djamsu.replaceDelayMs`(기본 30000), `-Djamsu.queueRetryMs`(기본 10000), `-Djamsu.queueMaxMs`(기본 600000). 설정 파일 항목으로 만들지 않는다 (`jamsu.devLogin` 과 같은 방식).

## Review Focus

스펙이 암시하지만 놓치기 쉬운 입력·상황. 각 줄의 시험은 괄호 안 Task 에 넣었다.

1. 사용자가 **자리를 기다리는 중(대기)** 인 잠수를 끊음 → 바로 STOPPED 가 되고, 나중에 자리가 나도 접속하지 않아야 함 (Task 4, B4)
2. **본 서버만 재시작** (접속용 서버는 살아 있음) → 접속용 서버가 잠수를 끊고 다시 붙은 뒤, 잠수가 이어서 접속돼야 함 (Task 4, B6·B7)
3. `workerPort` 에 **엉뚱한 데이터**(포트 스캐너의 `GET / HTTP/1.1`, 4GB 길이 프레임) → 그 연결만 닫히고 본 서버는 멀쩡해야 함 (Task 3, A11)
4. 원격 노드에서 도는 잠수의 **서버 항목·계정을 삭제** → 접속용 서버 쪽 접속도 끊겨야 함 (유령 접속 금지) (Task 3, A14)
5. 인증된 접속용 서버가 **자기에게 배정되지 않은 실행**의 토큰을 달라고 하거나 이벤트를 보냄 → 토큰을 주지 않고 무시 (Task 3, A12)

## File Structure

| 파일 | 책임 |
|---|---|
| `cloud/src/main/kotlin/kr/jamsuham/cloud/Runner.kt` (새) | `Runner`·`RunHandle`·`LaunchRequest`·`LaunchHooks`, `LocalRunner`(AfkSession 을 감쌈), `Scheduler`(배정·노드별 수), `ReplaceBudget` |
| `cloud/.../link/LinkCrypto.kt` (새) | HKDF, 핸드셰이크 MAC, `FrameCipher`, 키 파일 |
| `cloud/.../link/LinkNet.kt` (새) | Netty 파이프라인·핸드셰이크·핑, `LinkPeer`/`LinkHandler` (본 서버·접속용 서버 공용) |
| `cloud/.../link/MasterLink.kt` (새) | 본 서버 쪽: 접속 받기, `RemoteRunner`/`RemoteHandle`, 되묻기(creds·claim) 처리 |
| `cloud/.../link/Worker.kt` (새) | 접속용 서버: `WorkerConfig`, `workerMain`, 실행 목록, 재연결 |
| `cloud/.../Sessions.kt` (수정) | `box.session` → `box.run`, 배정, 대기(큐)·다시 배정·보류 |
| `cloud/.../Config.kt`, `Main.kt` (수정) | 새 설정 4개, `worker` 명령, 조립 |
| `tools/corebuild/harness/build.gradle.kts` (수정) | `implementation(project(":cloud"))` 추가 (단위 시험이 cloud 클래스를 부름) |
| `tools/corebuild/harness/src/main/kotlin/CloudNodeTest.kt` (새) | 새 시험 모드 `sched`·`linkcrypto`·`cloudnode` (기존 `Harness.kt` 는 `when` 에 3줄만 추가) |
| `docs/CLOUD.md`, `CLAUDE.md`, `gradle.properties` (수정) | 문서·버전 |

---

### Task 0: 기준선 — 이 PC 에서 기존 시험이 통과하는지 확인 (코드 변경 없음)

**Files:** 없음 (브랜치 생성만)

- [ ] **Step 1:** `git checkout -b feature/cloud-multinode`
- [ ] **Step 2:** `cd tools/test-server && npm install` (node_modules 가 비어 있음)
- [ ] **Step 3:** `./gradlew -p tools/corebuild :cloud:installDist :harness:installDist` → BUILD SUCCESSFUL
- [ ] **Step 4:** 시험 MC 서버 3개를 백그라운드로: `node tools/test-server/server.js 1.21.4 25590 stay`, `… 25591 dupe`, `… 25592 walk`
- [ ] **Step 5:** 임시 폴더에 `cloud.properties`(`port=18787`, `premiumMode=open`, `allowPrivateTargets=true`, `maxAccountsPerTarget=2`)를 두고 `JAVA_OPTS=-Djamsu.devLogin=true` 로 클라우드 서버 실행
- [ ] **Step 6:** `harness cloud http://127.0.0.1:18787 25590 25591 25592` → `RESULT cloud 38/38`. `harness wire` → 15/15, `harness chatlimit` → 10/10
- [ ] **Step 7:** 통과하지 못하는 항목이 있으면 **멈추고 사용자에게 보고** (리팩터링 전부터 깨져 있던 것과 구분해야 함). 결과 숫자를 기록해 둔다.

---

### Task 1: `Runner` 경계 + `LocalRunner` + `Scheduler` (동작 변화 없음)

**Files:**
- Create: `cloud/src/main/kotlin/kr/jamsuham/cloud/Runner.kt`
- Modify: `cloud/src/main/kotlin/kr/jamsuham/cloud/Sessions.kt`, `Config.kt`, `Main.kt`
- Modify: `tools/corebuild/harness/build.gradle.kts`, `tools/corebuild/harness/src/main/kotlin/Harness.kt`(when 에 `"sched" -> ok = schedTest()`)
- Test: `tools/corebuild/harness/src/main/kotlin/CloudNodeTest.kt` (`schedTest`)

**Interfaces:**
- Produces (`Runner.kt`, 패키지 `kr.jamsuham.cloud`):
```kotlin
class LaunchRequest(val runId: String, val uuid: String, val entry: CloudEntry)

interface LaunchHooks {
    /** AuthException 을 그대로 던진다 (needsRelogin 유지) */
    fun credentials(forceRefresh: Boolean): JoinCredentials
    /** 자리가 없으면 IllegalArgumentException(사용자에게 보일 문구) */
    fun claimTarget(connectHost: String, connectPort: Int)
}

interface RunHandle {
    fun stop()
    fun shutdown()
    fun sendChat(text: String): Boolean
    fun setStick(east: Float, south: Float)
    fun setMovement(mask: Int)
    fun clickMenu(slot: Int): Boolean
    fun closeMenu()
    fun requestSuggestions(text: String, callback: (CommandSuggestions?) -> Unit): Boolean
    fun traffic(): Traffic?
    /** 접속해 있고 걸을 수 있을 때만 (아니면 null) */
    fun position(): Wire.Pos?
}

interface Runner {
    val id: String          // 내장은 "local"
    val remote: Boolean
    val capacity: Int
    fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle
}

class LocalRunner(override val id: String, override val capacity: Int,
                  engineDir: File, allowPrivateTargets: Boolean, log: CoreLog) : Runner   // remote = false

class Scheduler {
    fun add(r: Runner): Boolean                 // 같은 id 가 이미 있으면 false
    fun remove(id: String): Runner?
    fun runners(): List<Runner>
    fun pick(addressKey: String): Runner?       // 자리 없으면 null
    fun placed(runnerId: String, addressKey: String)
    fun released(runnerId: String, addressKey: String)
    fun load(runnerId: String): Int
    fun onTarget(runnerId: String, addressKey: String): Int
}

class ReplaceBudget(private val max: Int = 3, private val windowMs: Long = 600_000L) {
    fun tryUse(now: Long): Boolean              // 최근 windowMs 안에 max 번 썼으면 false (쓰지 않음)
}
```
- `Config` 에 추가: `val localWorker: Boolean`(기본 true), `val localCapacity: Int`(기본 `maxActiveSessions`, 0..5000), `val workerPort: Int`(기본 0, 0..65535), `val workerBind: String`(기본 `"0.0.0.0"`). `DEFAULT` 틀 끝에 네 항목을 설명과 함께 추가 (설명: 접속용 서버는 `docs/CLOUD.md` 참고).
- `SessionManager` 생성자에 `private val scheduler: Scheduler` 추가. `SessionBox.session: AfkSession?` → `var run: RunHandle?` + `var runner: String?`.

- [ ] **Step 1: 실패하는 시험 작성** — `CloudNodeTest.kt` 에 `fun schedTest(): Boolean`. 가짜 `Runner`(launch 는 호출 안 함)로:
```kotlin
val s = Scheduler()
check("빈 목록 → null", s.pick("a") == null)
check("추가", s.add(fake("local", remote = false, capacity = 2)) && s.add(fake("r1", remote = true, capacity = 2)))
check("같은 이름은 거절", !s.add(fake("r1", true, 5)))
check("처음: 동률이면 내장 우선", s.pick("a")?.id == "local")
s.placed("local", "a")
check("같은 서버는 덜 들어간 노드로", s.pick("a")?.id == "r1")
check("다른 서버는 남은 자리 비율이 큰 노드로", s.pick("b")?.id == "r1")
s.placed("local", "b")
check("꽉 찬 노드는 건너뜀", s.load("local") == 2 && s.pick("c")?.id == "r1")
s.placed("r1", "a"); s.placed("r1", "c")
check("모두 차면 null", s.pick("d") == null)
s.released("local", "a")
check("자리 나면 다시", s.pick("d")?.id == "local" && s.onTarget("local", "a") == 0)
check("없는 것을 풀어도 음수 안 됨", run { s.released("local", "zzz"); s.load("local") == 1 })
check("노드 제거", s.remove("r1")?.id == "r1" && s.runners().map { it.id } == listOf("local"))
val b = ReplaceBudget(3, 600_000L)
check("다시 배정 3번까지", b.tryUse(0) && b.tryUse(1) && b.tryUse(2) && !b.tryUse(3))
check("10분 지나면 다시 됨", b.tryUse(600_001L))
```
출력 형식은 기존 시험과 같게 (`  통과  이름` / `RESULT sched n/n`).
- [ ] **Step 2:** harness `build.gradle.kts` 에 `implementation(project(":cloud"))` 추가, 빌드 → `Scheduler` 없음으로 컴파일 실패 확인
- [ ] **Step 3: `Runner.kt` 구현.**
  - `Scheduler.pick` 순서: 남은 자리(`capacity - load > 0`) 있는 노드 중 ① `onTarget(id, key)` 최소 ② `(capacity - load).toDouble() / capacity` 최대 ③ `remote == false` 우선. 내부 상태는 `synchronized`.
  - `LocalRunner.launch`: 지금 `Sessions.kt` `launch()` 의 `AfkSession(...)` 생성부를 옮긴다 — `AfkSession(engineDir, req.entry.sessionConfig(), { f -> hooks.credentials(f) }, events, log, targetFilter = { t -> NetGuard.filter(t, allowPrivateTargets).also { hooks.claimTarget(it.connectHost, it.connectPort) } })` 후 `start()`. `position()` 은 지금 `Sessions.position(b)` 의 연결 부분(`liveConnection`·`canWalk`·좌표·월드 이름).
- [ ] **Step 4: `Sessions.kt` 를 경계에 맞춘다** (동작은 그대로)
  - `launch()`: `val runner = scheduler.pick(key) ?: return false` (반환형 `Boolean`). `box.runner = runner.id` 를 **`setStatus(PREPARING)` 전에** 넣는다. `hooks.claimTarget` 구현 = 지금 `targetFilter` 안의 `if (box.gen === gen) claimIp(...)`. `runId = randomId()`. 배정되면 `Log.i("[배정] ${store.account(uuid)?.name ?: uuid.take(8)}/$id → ${runner.id}")`.
  - `start()`: 지금의 `launch(h, box, note)` 자리에서 `false` 면 `StartResult.Limit("지금 클라우드 서버가 가득 찼습니다. 잠시 뒤 다시 시도해 주세요")`.
  - `countActive(box, true)` 에서 `scheduler.placed(box.runner!!, key)`, `countActive(box, false)` 에서 `scheduler.released(runner, key)` (센 노드 id 를 box 에 같이 기억해 풀 때 그 값을 쓴다).
  - 나머지 `box.session?.X` 11곳 → `box.run?.X`. `move()` 의 `conn.canWalk` → `run.position() != null`, `conn == null` 검사는 `run == null` 로.
  - `Main.kt`: `Scheduler()` 를 만들고 `config.localWorker` 면 `LocalRunner("local", config.localCapacity, File(config.dataDir, "engine"), config.allowPrivateTargets, coreLog)` 추가 → `SessionManager(…, scheduler)`. `coreLog` 는 `SessionManager` 안에 있던 것을 최상위 `val cloudCoreLog` 로 옮겨 같이 쓴다. `localWorker=false` 이고 `workerPort=0` 이면 `Log.w("잠수를 돌릴 곳이 없습니다 (localWorker=false 인데 workerPort 도 꺼져 있음)")`.
- [ ] **Step 5:** 빌드 후 `harness sched` → `RESULT sched 13/13`
- [ ] **Step 6: 회귀 확인** — Task 0 과 같은 구성으로 `harness cloud …` → **38/38**, `harness wire` 15/15, `harness chatlimit` 10/10. 본 서버 로그에 `[배정] Tester/h1 → local` 이 보이는지 확인.
- [ ] **Step 7: Commit** — `git add cloud tools/corebuild && git commit -m "클라우드: 잠수 실행부를 Runner 경계로 분리 (동작 변화 없음)"`

---

### Task 2: 링크 암호화 (`LinkCrypto`, `FrameCipher`)

**Files:**
- Create: `cloud/src/main/kotlin/kr/jamsuham/cloud/link/LinkCrypto.kt`
- Modify: `Harness.kt` (`"linkcrypto" -> ok = linkCryptoTest()`), `CloudNodeTest.kt`

**Interfaces:**
- Produces (패키지 `kr.jamsuham.cloud.link`):
```kotlin
object LinkCrypto {
    const val LINK_VERSION = 1
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray    // RFC 5869, HMAC-SHA256
    fun workerMac(psk: ByteArray, nonceW: ByteArray, nonceM: ByteArray, name: String): ByteArray
    fun masterMac(psk: ByteArray, nonceW: ByteArray, nonceM: ByteArray): ByteArray
    fun macEquals(a: ByteArray, b: ByteArray): Boolean                                      // MessageDigest.isEqual
    fun parseKey(base64: String): ByteArray        // 32바이트가 아니면 IllegalArgumentException
    fun loadOrCreateKey(file: File): ByteArray     // 없으면 32바이트 랜덤을 base64 로 저장 (Vault 의 master.key 처럼 권한 좁히기 시도)
}
class FrameCipher(psk: ByteArray, nonceW: ByteArray, nonceM: ByteArray, isWorker: Boolean) {
    fun seal(plain: ByteArray): ByteArray          // 보내는 방향 카운터 사용 후 +1
    fun open(sealed: ByteArray): ByteArray         // 받는 방향 카운터 사용 후 +1. 실패하면 GeneralSecurityException
}
```
- 고정 값: `workerMac = HMAC-SHA256(psk, "jamsu-link-w" ‖ nonceW ‖ nonceM ‖ utf8(name))`, `masterMac = HMAC-SHA256(psk, "jamsu-link-m" ‖ nonceM ‖ nonceW)`. 방향별 키 = `hkdf(psk, salt = nonceW ‖ nonceM, info = "jamsu-link w2m" / "jamsu-link m2w", 32)`. AES-256-GCM, 태그 128비트, nonce 12바이트 = `00 00 00 00` + 8바이트 big-endian 카운터(0 부터). **카운터는 전송하지 않는다** (받는 쪽이 자기 기대값으로 풀므로 재전송·순서 바꾸기·빠뜨리기가 모두 인증 실패).

- [ ] **Step 1: 실패하는 시험** — `linkCryptoTest()`:
```kotlin
fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
// RFC 5869 Test Case 1
val okm = LinkCrypto.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
check("HKDF RFC 5869 벡터", okm.contentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")))
val psk = ByteArray(32) { it.toByte() }; val nw = ByteArray(32) { 1 }; val nm = ByteArray(32) { 2 }
val w = FrameCipher(psk, nw, nm, isWorker = true); val m = FrameCipher(psk, nw, nm, isWorker = false)
val f1 = w.seal("하나".toByteArray()); val f2 = w.seal("둘".toByteArray())
check("워커→마스터 왕복(순서대로)", String(m.open(f1)) == "하나" && String(m.open(f2)) == "둘")
check("재전송 거절", fails { m.open(f1) })
check("마스터→워커 왕복", String(w.open(m.seal("셋".toByteArray()))) == "셋")
check("방향이 다르면 못 엶", fails { FrameCipher(psk, nw, nm, true).open(FrameCipher(psk, nw, nm, true).seal(byteArrayOf(1))) })
check("한 바이트 바뀌면 거절", fails { val a = FrameCipher(psk, nw, nm, true).seal(byteArrayOf(1, 2, 3)); a[0] = (a[0] + 1).toByte(); FrameCipher(psk, nw, nm, false).open(a) })
check("순서를 건너뛰면 거절", fails { val a = FrameCipher(psk, nw, nm, true); a.seal(byteArrayOf(1)); FrameCipher(psk, nw, nm, false).open(a.seal(byteArrayOf(2))) })
check("키가 다르면 MAC 불일치", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, "a"), LinkCrypto.workerMac(ByteArray(32), nw, nm, "a")))
check("이름이 다르면 MAC 불일치", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, "a"), LinkCrypto.workerMac(psk, nw, nm, "b")))
check("워커 MAC 과 마스터 MAC 은 다름", !LinkCrypto.macEquals(LinkCrypto.workerMac(psk, nw, nm, ""), LinkCrypto.masterMac(psk, nw, nm)))
check("키 길이 검사", fails { LinkCrypto.parseKey(java.util.Base64.getEncoder().encodeToString(ByteArray(31))) })
check("키 파일: 만들고 다시 읽으면 같음", run { val f = File(TMP, "wk-test.key").also { it.delete() }; LinkCrypto.loadOrCreateKey(f).contentEquals(LinkCrypto.loadOrCreateKey(f)) && f.readText().trim().length == 44 })
```
(`fails { }` = 예외가 나면 true 인 도우미)
- [ ] **Step 2:** 빌드 → 컴파일 실패 확인
- [ ] **Step 3:** `LinkCrypto.kt` 구현 (JDK `javax.crypto.Mac`, `Cipher` 만 사용)
- [ ] **Step 4:** `harness linkcrypto` → `RESULT linkcrypto 12/12`
- [ ] **Step 5: Commit** — `git commit -m "클라우드: 본 서버-접속용 서버 연결 암호화(HKDF + AES-GCM)"`

---

### Task 3: 원격 접속용 서버 — `LinkNet` · `MasterLink`(`RemoteRunner`) · `worker` 모드

**Files:**
- Create: `cloud/.../link/LinkNet.kt`, `cloud/.../link/MasterLink.kt`, `cloud/.../link/Worker.kt`
- Modify: `cloud/.../Main.kt`, `cloud/.../Sessions.kt`(`runnerAttached`/`runnerLost` 자리만 — 이 Task 에서는 `runnerLost` 가 그 노드의 잠수를 "접속용 서버 연결이 끊겼습니다" 로 **멈추기만** 한다. 다시 배정은 Task 4)
- Test: `CloudNodeTest.kt` (`cloudNodeTest` A 단계), `Harness.kt` (`"cloudnode" -> ok = cloudNodeTest(args[1], args[2], args[3])`)

**Interfaces:**
- Consumes: Task 1 의 `Runner`·`RunHandle`·`LaunchRequest`·`LaunchHooks`·`LocalRunner`·`Scheduler`, Task 2 의 `LinkCrypto`·`FrameCipher`
- Produces:
```kotlin
// LinkNet.kt
interface LinkHandler { fun onMessage(msg: JsonObject); fun onClosed() }
class LinkPeer {                       // 인증이 끝난 연결 하나
    val remoteIp: String
    val alive: Boolean
    fun send(msg: JsonObject)          // 암호화해 보냄. 채널이 밀려 쓰기 불가면 연결을 닫는다(=끊김 처리)
    fun close()
}
class Hello(val name: String, val capacity: Int, val version: String)
object LinkNet {
    const val MAX_FRAME = 4 * 1024 * 1024
    const val PING_MS = 5_000L; const val DEAD_MS = 15_000L; const val HANDSHAKE_MS = 5_000L
    /** accept 가 null 대신 거절 사유(한국어)를 주려면 Reject 를 던진다 */
    class Reject(val code: String, message: String) : Exception(message)
    fun serve(bind: String, port: Int, psk: ByteArray, group: EventLoopGroup, accept: (Hello, LinkPeer) -> LinkHandler): Channel
    fun dial(host: String, port: Int, psk: ByteArray, hello: Hello, group: EventLoopGroup,
             onUp: (LinkPeer) -> LinkHandler, onFail: (reason: String) -> Unit)
}
// MasterLink.kt
class MasterLink(config: Config, scheduler: Scheduler,
                 onAttached: (runnerId: String) -> Unit, onLost: (runnerId: String, graceful: Boolean) -> Unit) {
    fun start(); fun stop()
}
// Worker.kt
class WorkerConfig(val file: File)     // master, secret, name, capacity(기본 200, 1..5000), allowPrivateTargets, dataDir
fun workerMain(configFile: File)       // 끝나지 않음 (Ctrl+C 로 종료)
// Sessions.kt
fun runnerAttached(runnerId: String)   // 이 Task 에서는 로그만
fun runnerLost(runnerId: String, graceful: Boolean)
```

**프로토콜 (정확한 형식)**
- 프레임: 4바이트 big-endian 길이 N + N바이트. `N > MAX_FRAME` 이면 즉시 닫음.
- 핸드셰이크 (평문 JSON 프레임 4개, `HANDSHAKE_MS` 안에 안 끝나면 닫음):
  1. W→M `{"t":"hello","ver":1,"api":<Wire.API>,"version":"<CLOUD_VERSION>","name":"…","capacity":n,"nonce":"<base64 32B>"}`
  2. M→W `{"t":"challenge","nonce":"<base64 32B>"}` 또는 `{"t":"reject","code":"…","reason":"…"}`
  3. W→M `{"t":"auth","mac":"<base64 workerMac>"}`
  4. M→W `{"t":"welcome","mac":"<base64 masterMac>"}` 또는 reject
- reject `code`: `version`(`ver != LINK_VERSION || api != Wire.API` → 사유 `"본 서버와 접속용 서버의 버전이 다릅니다 (본 서버 v<X>, 접속용 v<Y>)"`), `auth`(MAC 불일치 → `"비밀키가 맞지 않습니다"`), `name`(이름이 `local`·비어 있음·64자 초과·이미 연결됨 → `"같은 이름의 접속용 서버가 이미 연결돼 있습니다"` 등), `blocked`.
- 실패한 핸드셰이크(형식 오류·시간 초과·`auth`)는 IP 별로 세어 **60초에 10번** 넘으면 **10분** 동안 그 IP 는 연결 즉시 닫음.
- welcome 뒤 모든 프레임은 `FrameCipher` 로 감싼 UTF-8 JSON. 풀기 실패 → 닫음.
- 핑: `IdleStateHandler` — 5초 동안 보낸 게 없으면 `{"t":"ping"}`, 15초 동안 받은 게 없으면 닫음. `ping` 은 `LinkHandler` 로 넘기지 않는다.
- 출력 버퍼 high water mark 8MB.

**메시지 (암호화된 JSON)**
| 방향 | 형식 |
|---|---|
| M→W | `{"t":"launch","run":id,"uuid":u,"entry":Wire.entry}` · `{"t":"stop"\|"shutdown"\|"closeMenu","run":id}` · `{"t":"chat","run":id,"text":s}` · `{"t":"stick","run":id,"east":f,"south":f}` · `{"t":"move","run":id,"mask":n}` · `{"t":"click","run":id,"slot":n}` · `{"t":"suggest","run":id,"rid":n,"text":s}` |
| M→W (응답) | `{"t":"creds","rid":n,"name":s,"uuid":s,"token":s?}` 또는 `{"t":"creds","rid":n,"error":s,"relogin":b}` · `{"t":"claim","rid":n}` 또는 `{"t":"claim","rid":n,"error":s}` |
| W→M | `{"t":"ev","run":id,"k":K,"v":…}` — K: `status`(Wire.status) `line`(Wire.line(l, 0)) `players`(Wire.players) `menu`(Wire.menu) `inventory`(Wire.inventory) `vitals`(Wire.vitals) `death`(Wire.death) `pack`({url,hash}) `dup`(v 없음) `pos`(Wire.pos) `traffic`({rx,tx,onlineMs}) |
| W→M | `{"t":"suggestR","rid":n,"start":n,"length":n,"matches":[…]}` (없으면 `rid` 만) · `{"t":"credsQ","rid":n,"run":id,"force":b}` · `{"t":"claimQ","rid":n,"run":id,"host":s,"port":n}` · `{"t":"load","load":n,"heapMb":n}`(30초마다) · `{"t":"bye"}` |

**동작 규칙**
- 접속용 서버: `launch` → `LocalRunner.launch(req, 이벤트를 ev 로 보내는 listener, 되묻는 hooks)`. `hooks.credentials`/`claimTarget` 은 `credsQ`/`claimQ` 를 보내고 응답을 **최대 20초** 기다림 (넘으면 `IOException("본 서버가 응답하지 않습니다")`). `creds.error` → `AuthException(error, relogin)`, `claim.error` → `IllegalArgumentException(error)`.
- 접속용 서버: `pos` 는 0.25초마다 각 실행의 `position()` 을 보고 **바뀌었을 때만**, `traffic` 은 60초마다. JSON 이 1,000,000자를 넘는 이벤트는 보내지 않고 `Log.w`.
- 접속용 서버: 연결이 끊기면(닫힘·15초 무응답) **모든 실행을 `shutdown()`** 하고 `Log.w("[노드] 본 서버 연결이 끊겨 잠수 ${n}개를 끊었습니다")`, 5·10·30·60초(최대 60) 간격으로 재연결. 연결되면 `Log.i("[노드] 본 서버에 연결했습니다")`. 거절당하면 `Log.w("[노드] 본 서버가 거절했습니다: $reason")` 후 60초 간격으로 다시 시도. 종료 훅: `bye` 보내고 모든 실행 `shutdown()`.
- 접속용 서버: `World.maxChunks = 200`, `NetGuard` 는 자기 `allowPrivateTargets` 기준. 데이터 폴더에는 `engine/` 만 생긴다.
- `WorkerConfig`: 파일이 없으면 설명 달린 틀을 만들고 "worker.properties 를 채운 뒤 다시 실행해 주세요" 를 찍고 종료 코드 2. `master`·`secret` 이 비었으면 같은 안내로 종료.
- 본 서버(`MasterLink`): `workerPort > 0` 일 때만 시작. 키는 `LinkCrypto.loadOrCreateKey(File(config.dataDir, "worker.key"))`, 시작 로그에 파일 경로. 인증되면 `RemoteRunner(id = name, remote = true, capacity)` 를 `scheduler.add` (false 면 `Reject("name", …)`), `Log.i("[노드] 연결됨: $name (자리 $capacity, v$version)")`, `onAttached(name)`. 닫히면 `scheduler.remove`, `Log.w("[노드] 끊김: $name")`, `onLost(name, graceful = bye 를 받았는지)`.
- `RemoteRunner` 는 자기가 `launch` 한 `run` id 만 안다. **모르는 `run` 의 `ev` 는 무시, `credsQ`/`claimQ` 는 `error` 로 응답** (다른 계정 토큰을 못 받게). `credsQ`·`claimQ` 처리는 Netty 스레드가 아니라 별도 스레드 풀(`link-rpc`, 캐시드)에서 (`auth.credentials` 가 네트워크를 기다릴 수 있음).
- `RemoteHandle`: `sendChat`/`clickMenu`/`requestSuggestions` 는 연결이 살아 있으면 보내고 `true`. 자동완성 콜백은 `rid` 로 맞추고 5초 지나면 버림. `position()`/`traffic()` 은 마지막으로 받은 값. `shutdown()` 은 `shutdown` 을 보내고 목록에서 뺌.
- 받은 `ev` → `Wire.parse*` 로 되돌려 `launch` 때 받은 `SessionListener` 를 그대로 부른다 (`SessionManager` 쪽 코드는 내장·원격이 같음).
- `Main.kt`: 첫 인자가 `worker` 면 `workerMain(File(args.getOrNull(1) ?: "worker.properties"))`. 사용법 주석에 한 줄 추가.

- [ ] **Step 1: 시험 뼈대 + A 단계 작성** — `cloudNodeTest(installDir, stayPort, walkPort)`:
  - 도우미 `class Proc(name, args, jvmOpts, dir)`: `java -cp "<installDir>/lib/*" <jvmOpts> kr.jamsuham.cloud.MainKt <args>` 를 `System.getProperty("java.home")/bin/java` 로 실행, 표준 출력을 UTF-8 로 줄 단위 수집, `waitLog(Regex, timeoutMs): Boolean`, `kill()`(`destroyForcibly` + 대기). 시험이 끝나면(예외 포함) 띄운 프로세스를 모두 죽인다.
  - 구성: `HARNESS_TMP/node/master/cloud.properties` = `port=18790 bind=127.0.0.1 premiumMode=open allowPrivateTargets=true maxAccountsPerTarget=2 localWorker=false workerPort=18791 workerBind=127.0.0.1`, JVM `-Djamsu.devLogin=true`. 워커 2개(`w1`,`w2`): `master=127.0.0.1:18791 secret=<master/data/worker.key 내용> capacity=5 allowPrivateTargets=true`. 시작 전에 `HARNESS_TMP/node` 를 지운다.
  - MC 접속자 수 확인 도우미 `online(port)` = `StatusPinger.ping(ResolvedTarget("127.0.0.1", port, "127.0.0.1", port), 769).online`.
  - 검사 항목 (이름은 그대로 쓸 것):
    - A1 `워커 2개 연결` — 본 서버 로그 `\[노드\] 연결됨: w1`, `w2`
    - A2 `원격 노드에서 접속 → ONLINE` — Tester 가 `h1`(stay) 시작, 로그 `\[배정\] Tester/h1 → (w1|w2)`
    - A3 `채팅 줄 수신`("반가워요"), `채팅 보내기`, `메뉴 수신`(제목 "리소스팩 적용"), `메뉴 클릭`(13번 → 메뉴 닫힘), `자동완성`(`/s` → `[spawn, server]`), `접속자 수신`(2명) — 기존 `cloudTest` 의 같은 검사와 같은 조건
    - A4 `같은 서버의 둘째 계정은 다른 노드로` — Tester_B 의 `[배정]` 노드 ≠ Tester 의 노드
    - A5 `서버별 인원은 전체 합계: 셋째는 자리 없음(limit)` — Tester_C `start` 의 code == `limit`, 그리고 `online(stayPort) == 2`
    - A6 `원격 이동: 위치 수신`, `동쪽으로 걸음`(x 4칸 이상 증가), `입력이 끊기면 스스로 멈춤` — walk 서버, 기존 검사와 같은 조건
    - A7 `끊기 → STOPPED + 실제 접속도 끊김` — Tester_B `stop` 뒤 `online(stayPort) == 1`
    - A8 `틀린 비밀키는 거절` — `secret` 을 다른 값으로 한 워커 `w3` 로그 `본 서버가 거절했습니다: 비밀키가 맞지 않습니다`, 본 서버 `info()` 정상
    - A9 `같은 이름의 워커는 거절` — 이름 `w1` 인 둘째 프로세스 로그 `같은 이름의`, 기존 `h1` 은 계속 ONLINE
    - A10 `링크 버전이 다르면 거절` — 하네스가 소켓으로 직접 `{"t":"hello","ver":999,…}` 프레임을 보내고 `reject`/`code=version` 을 받음
    - A11 `엉뚱한 데이터는 그 연결만 닫힘` — 소켓에 `GET / HTTP/1.1\r\n\r\n` 을 보내면 6초 안에 닫히고(read == -1), 그 뒤 본 서버 `info()` 정상·`h1` ONLINE 유지
    - A12 `배정받지 않은 실행의 토큰은 주지 않음` — 하네스가 `LinkNet.dial`(올바른 키, 이름 `rogue`)로 붙어 `{"t":"credsQ","rid":1,"run":"nope","force":false}` → `creds` 응답에 `error` 있고 `token` 없음. `{"t":"ev","run":"nope","k":"status",…}` 를 보내도 연결 유지·`h1` 상태 그대로
    - A13 `접속용 서버 디스크에 계정 정보 없음` — `w1`,`w2` 의 data 폴더에 `accounts`·`servers`·`master.key`·`tokens.json`·`worker.key` 없음
    - A14 `서버 항목을 지우면 원격 접속도 끊김` — Tester 가 `h1` 을 `deleteEntry` → `online(stayPort) == 0`
  - 끝: `RESULT cloudnode n/n`
- [ ] **Step 2:** 빌드 → `LinkNet` 등이 없어 컴파일 실패 확인
- [ ] **Step 3:** `LinkNet.kt` 구현 (Netty `LengthFieldBasedFrameDecoder`/`LengthFieldPrepender`, 핸드셰이크 상태 기계, `IdleStateHandler`)
- [ ] **Step 4:** `MasterLink.kt` 구현 (`RemoteRunner`, `RemoteHandle`, 되묻기 처리, 실패 횟수 제한)
- [ ] **Step 5:** `Worker.kt` 구현, `Main.kt` 연결. `SessionManager.runnerLost` = 그 노드의 활성 잠수를 `box.gen = null; box.run = null` 후 `STOPPED`/"접속용 서버 연결이 끊겼습니다" 로 (Task 4 에서 다시 배정으로 바꿈)
- [ ] **Step 6:** 시험 MC 서버 `stay`(25590)·`walk`(25592)를 띄우고 `harness cloudnode cloud/build/install/jamsu-cloud 25590 25592` → A 단계 전부 통과 (`dialog` 관련 가끔 실패는 CLAUDE.md 에 적힌 node 라이브러리 문제 — 이 시험은 1.21.4 라 해당 없음)
- [ ] **Step 7: 회귀** — Task 0 구성으로 `harness cloud …` 38/38 (내장 경로가 그대로인지)
- [ ] **Step 8: Commit** — `git commit -m "클라우드: 접속용 서버(worker) 모드와 본 서버 연결"`

---

### Task 4: 장애 처리 — 다시 배정·대기·보류

**Files:**
- Modify: `cloud/.../Sessions.kt`
- Test: `CloudNodeTest.kt` (`cloudNodeTest` B 단계)

**Interfaces:**
- Consumes: Task 1 `Scheduler`·`ReplaceBudget`, Task 3 `runnerAttached`/`runnerLost` 호출 지점
- Produces (`SessionBox` 에 추가): `var queued = false`(자리·시각을 기다리는 중), `var queuedAt = 0L`, `var notBefore = 0L`, `var parked = false`(자리가 없어 멈춤 — "잠수 중" 표시는 남김), `val replaces = ReplaceBudget()`.

**규칙 (스펙 5.2·5.4)**
- 시간 값: `REPLACE_DELAY_MS = Long.getLong("jamsu.replaceDelayMs", 30_000)`, `QUEUE_RETRY_MS = Long.getLong("jamsu.queueRetryMs", 10_000)`, `QUEUE_MAX_MS = Long.getLong("jamsu.queueMaxMs", 600_000)`, 배정 간격 2초.
- `setStatus`: 인원 계산을 `countActive(box, active && !box.queued)` 로 (대기 중은 `maxActiveSessions`·서버별 인원·노드 load 에 **세지 않음**; `countActive` 는 이미 멱등). "잠수 중" 표시(`sf.running`)는 `active` 면 추가, 비활성이면 **`box.parked` 가 아닐 때만** 제거.
- `runnerLost(id, graceful)`: 그 노드의 box 마다(계정 잠금 안에서) `gen = null; run = null; moving 제거`. 활성이었으면 —
  - `replaces.tryUse(now)` 가 false → `STOPPED` "접속용 서버가 자주 끊겨 잠수를 멈췄습니다" (ERROR 줄도 남김)
  - 아니면 `queued = true; queuedAt = now; notBefore = max(if (graceful) now else now + REPLACE_DELAY_MS, 직전 status.nextAttemptAtMs ?: 0)`, 상태 `WAITING_RECONNECT` "접속용 서버 연결이 끊겨 다른 곳에서 다시 접속합니다"(`nextAttemptAtMs = notBefore`), 같은 문구의 SYSTEM 줄
- `tick()` 에 `placeQueued()` (1초마다): `queued && now >= notBefore` 인 box 를, **전체에서 2초에 하나씩** 다시 시작 시도. 시작 전 검사는 `start()` 의 것을 그대로(프리미엄·앱에서 잠수 중·전체 상한·서버별 인원) — 공용 private 함수로 뽑아 쓴다.
  - 배정 성공 → `queued = false`, 줄 "다른 접속용 서버에서 이어서 접속합니다"(재시작 이어 접속은 지금 문구 유지)
  - 자리 없음(노드 없음·전체 상한·서버별 인원) → 상태 `WAITING_RECONNECT` "접속할 자리를 기다리는 중…", `notBefore = now + QUEUE_RETRY_MS`. `now - queuedAt > QUEUE_MAX_MS` 면 `parked = true; queued = false` 후 `STOPPED` "접속할 자리가 없어 멈췄습니다. 자리가 나면 이어서 접속합니다"
  - 그 밖의 거절(프리미엄 만료·앱에서 잠수 중 등) → `queued = false`, `STOPPED` + 그 사유 문구
- `resumeAtBoot`: 지금처럼 2초 간격. 자리가 없어서 못 시작한 경우에만 표시를 지우지 않고 **대기로 넣는다** (`queued = true`, `queuedAt = now`, `notBefore = now`). 다른 사유는 지금처럼 표시를 내림.
- `runnerAttached(id)`: `parked` 인 box 를 모두 `parked = false; queued = true; queuedAt = now; notBefore = now` (노드가 돌아오면 이어서 접속).
- 사용자 `stop()`·`stopAll()`·`deleteEntry()`·`dropAccount()`: `queued = false; parked = false` 로 하고 "잠수 중" 표시를 내린다 (대기·보류 중이던 것도 확실히 멈춤). `stop()` 은 이미 `STOPPED`(보류)여도 표시를 내려야 한다.
- 사용자 `start()` 가 대기·보류 중인 box 에 오면: 대기 중이면 지금 상태를 다시 알리고 `Ok`(이미 진행 중), 보류 중이면 `parked = false` 후 보통 시작.

- [ ] **Step 1: B 단계 시험 작성** (A 단계 뒤에 이어서, 같은 프로세스 구성. 필요한 곳에서 프로세스를 죽이고 다시 띄운다)
  - 준비: Tester `h1`(stay), Tester_B `h1`(stay) 가 서로 다른 노드에서 ONLINE, `online(stayPort) == 2`
  - B1 `노드가 죽으면 대기 상태로` — Tester 의 노드 프로세스를 `kill()` → 10초 안에 Tester `h1` 상태 `WAITING_RECONNECT` + 문구에 "접속용 서버 연결이 끊겨"
  - B2 `죽은 직후 그 계정의 접속이 남아 있지 않음` — `online(stayPort) == 1` (Tester_B 만)
  - B3 `30초 뒤 다른 노드에서 다시 접속` — 45초 안에 Tester `h1` ONLINE, 새 `[배정] Tester/h1 → <남은 노드>`, `online(stayPort) == 2`. B1 시점부터 재배정 로그까지 25초 이상 걸렸는지도 확인 (`다시 배정은 30초 뒤`)
  - B4 `자리가 없으면: 직접 시작은 거절, 잠수 중이던 것은 대기` — 남은 노드도 `kill()` → Tester·Tester_B 가 대기 상태, 45초 뒤 문구 "접속할 자리를 기다리는 중". Tester 가 새 항목 `h9`(walk) `start` → code `limit`
  - B5 `대기 중인 잠수를 끊으면 멈춤` — Tester_B `stop("h1")` → STOPPED
  - B6 `노드가 돌아오면 대기 중이던 것만 이어서 접속` — `w1` 재시작 → 30초 안에 Tester `h1` ONLINE, Tester_B `h1` 은 계속 STOPPED, `online(stayPort) == 1`
  - B7 `본 서버가 죽으면 접속용 서버가 잠수를 모두 끊음` — 본 서버 `kill()` → 20초 안에 `w1` 로그 `본 서버 연결이 끊겨 잠수 1개를 끊었습니다`, `online(stayPort) == 0`
  - B8 `본 서버를 다시 켜면 이어서 접속` — 같은 폴더로 본 서버 재시작 → 90초 안에 `w1` 로그 `본 서버에 연결했습니다`, Tester `h1` ONLINE(새 로그인으로 hello 를 받아 확인), `online(stayPort) == 1`
  - B9 `10분(시험에서는 15초) 지나도 자리가 없으면 보류, 노드가 붙으면 이어서` — `w1` `kill()`, 본 서버를 `-Djamsu.queueMaxMs=15000 -Djamsu.queueRetryMs=3000` 로 재시작 → 40초 안에 Tester `h1` STOPPED + 문구 "접속할 자리가 없어 멈췄습니다" → `w1` 재시작 → 30초 안에 ONLINE
  - B10 `계정을 지우면 원격 접속도 끊김` — Tester `deleteAccount()` → `online(stayPort) == 0`
- [ ] **Step 2:** `harness cloudnode …` → B1 은 통과(멈춤)하지 못하고 B 단계 실패 확인 (Task 3 의 `runnerLost` 는 멈추기만 하므로)
- [ ] **Step 3:** `Sessions.kt` 에 위 규칙 구현
- [ ] **Step 4:** `harness cloudnode …` → A·B 전부 통과
- [ ] **Step 5: 회귀** — `harness cloud …` 38/38, `harness sched`, `harness linkcrypto`, `harness wire`, `harness chatlimit`
- [ ] **Step 6: Commit** — `git commit -m "클라우드: 접속용 서버가 끊기면 다른 곳에 다시 배정, 자리 없으면 대기"`

---

### Task 5: 문서·버전·전체 확인

**Files:**
- Modify: `docs/CLOUD.md`, `CLAUDE.md`, `gradle.properties`, `docs/HANDOFF.md`(버전 줄·남은 일), `.claude/memory/project-status-2026-11-27.md`

- [ ] **Step 1:** `gradle.properties` → `jamsuham.version=2.1.0`
- [ ] **Step 2:** `docs/CLOUD.md` — 4번 설정 표에 `localWorker`·`localCapacity`·`workerPort`·`workerBind` 추가, 새 절 **"16. 접속용 서버 붙이기 (여러 대로 늘리기)"**: 그림, 본 서버에서 `workerPort` 켜기 → `data/worker.key` 값을 접속용 서버의 `worker.properties` `secret=` 에 → `bin/jamsu-cloud worker` 실행, `worker.properties` 표, 방화벽(본 서버의 `workerPort` 만 열면 됨, 접속용 서버는 열 포트 없음), 끊겼을 때 동작(15초·30초·10분 규칙), **서버별 인원은 노드를 늘려도 전체 합계라는 점과 이유**(10번 절과 연결), 업데이트 순서(본 서버와 접속용 서버를 같은 버전으로, 본 서버 먼저 끄기). 12번 데이터 절에 `worker.key`("공유하면 안 되는 파일") 추가. 1번의 메모리 문장에 `-Xmx` 안내 한 줄 추가(자바는 기본으로 램의 1/4 만 씀 → `JAVA_OPTS=-Xmx10g`).
- [ ] **Step 3:** `CLAUDE.md` "클라우드 잠수" 항목에 구조(`Runner.kt`, `link/`), 시험 방법(`harness sched`·`linkcrypto`·`cloudnode <설치폴더> <stay포트> <walk포트>`), 전체 합계 원칙, "실제 원격 노드로는 아직 안 돌려 봄" 을 추가. "다음 세션에서 이어서 할 일" 의 버전 표기 갱신.
- [ ] **Step 4:** 전체 빌드·시험을 처음부터 다시: `:cloud:installDist :harness:installDist` → `harness cloud` 38/38, `wire` 15/15, `chatlimit` 10/10, `sched`, `linkcrypto`, `cloudnode` 전부. 웹 시험 `node tools/test-server/cloud/web-test.js <스크린샷 폴더> 25592` 23/23 (Playwright 가 없으면 `npm install --no-save playwright && npx playwright install chromium`; 설치가 안 되면 **건너뛰었다고 보고**).
- [ ] **Step 5:** `git diff main --stat -- core app` 가 비어 있는지 확인 (core·앱 무변경)
- [ ] **Step 6:** 메모리 갱신 (구현 상태·남은 확인: 실제 원격 노드 검증), **Commit** — `git commit -m "v2.1.0: 클라우드 접속용 서버(멀티 노드) 문서·버전"`
- [ ] **Step 7:** 사용자에게 결과 보고 (통과 숫자 그대로, 건너뛴 것 명시). `main` 병합·push 는 사용자가 정한다.
