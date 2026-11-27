# 클라우드 잠수 — 본 서버 + 접속용 서버(멀티 노드) 설계

작성 2026-11-27 · 대상 버전 cloud v2.1.0 · 상태: 사용자 승인 대기

## User spec (exact quote)
- "자 만약에 클라우드 서버 여러대 띄워 IP 나누기를 한다면 어떤식으로 진행하는거야 ?"
- "ㄱㄱ 스펙쓰고 바로 진행하자 그냥. 어짜피 서버 바로 돌릴거야."
- (지금 쓸 수 있는 컴퓨터) "한대고 일단 아는 형 서버야."
- (방식 ② 선택) "너 추천대로 진행해."

## 1. 의도
- 지금 클라우드 서버(`cloud/`)는 프로그램 하나가 **관리**(로그인·데이터·웹/앱 API·SSE)와 **실행**(`AfkSession` 으로 마인크래프트 접속)을 모두 한다.
- 이것을 **본 서버(master) 1대 + 접속용 서버(worker) 여러 대**로 나눌 수 있게 만든다. 사용자는 주소 하나(본 서버)만 쓰고, 본 서버가 잠수를 남는 접속용 서버에 배정한다.
- 당장은 아는 형 서버 **1대**에서 돈다 → 본 서버 안의 "내장 실행기"(`LocalRunner`)만 실제로 쓰인다. 원격 접속용 서버는 서버를 더 빌리는 날 설정만으로 붙도록 지금 같이 만든다.
- 목적: 용량(램·CPU·회선) 확장, 허용받은 서버의 인원을 여러 IP 로 나눠 서버 쪽 부담 줄이기.

## 2. 목표 / 비목표 / 반목표
**목표**
1. 설정을 하나도 안 바꾼 기존 설치는 지금과 똑같이 동작한다 (내장 실행기, 같은 포트, 같은 data 폴더).
2. `jamsu-cloud worker <설정>` 으로 접속용 서버를 띄우면 본 서버에 **먼저 연결**해 붙는다 (접속용 서버 쪽은 포트 개방·공유기 설정 불필요).
3. 서버별 인원 제한(`maxAccountsPerTarget`/`targetLimits`)과 중복 접속 판단은 **본 서버가 전체 합계로** 센다.
4. 마이크로소프트 로그인 정보는 본 서버에만 저장한다. 접속용 서버는 접속 순간 받은 마인크래프트 토큰을 **메모리에만** 들고 있는다.
5. 웹·앱 API(`Wire`, `/api/*`, SSE)는 바뀌지 않는다 → 앱·웹 수정 없음, `Wire.API` 그대로.

**비목표 (이번에 안 함)**
- 운영자용 관리 화면, 접속용 서버 자동 설치·배포
- 잠수를 끊지 않고 다른 접속용 서버로 옮기기 (옮길 때는 재접속)
- 본 서버 자체를 여러 대로 (본 서버는 1대)
- 노드별 지도·리소스팩 등 새 기능

**반목표 (이렇게 되면 실패)**
- 서버별 인원을 **IP(노드)마다** 세어, 노드를 늘릴수록 마인크래프트 서버의 같은 IP 제한을 피해 가는 구조
- 접속용 서버 디스크에 로그인 정보·토큰이 남음
- 같은 계정이 두 노드에서 동시에 같은 서버에 접속 (서로 튕겨 냄)
- 기존 1대 운영이 깨짐

## 3. 제약
- 자바 17, 추가 라이브러리 없이 (지금 의존성: core 의 Netty 4.1·Gson·MinecraftAuth 등). 메이븐에서 새 라이브러리를 받기 어려운 환경이었음(Jetty 429)
- 빌드: `./gradlew -p tools/corebuild :cloud:installDist`. 앱은 이 환경에서 못 돌리므로 시험은 PC 하네스로
- 같은 jar 하나가 본 서버·접속용 서버 둘 다 된다 (배포 파일 `jamsu-cloud.zip` 하나 유지)

## 4. 방식 (검토한 것)
| 방식 | 내용 | 결과 |
|---|---|---|
| ① 구조만 나누기 | `Runner` 경계 + 내장 실행기만 | 작고 안전하지만 원격은 나중에 다시 작업 |
| **② ① + 원격 접속용 서버 (선택)** | 접속용 서버가 본 서버로 TCP 연결, 암호화·인증 후 JSON 주고받음 | **사용자 선택** ("너 추천대로 진행해") — 서버 늘리는 날 설정만으로 붙음 |
| ③ 접속용 서버가 HTTP API 를 열고 본 서버가 호출 | 노드마다 포트 개방·HTTPS 필요 | 설정이 번거롭고 얻는 것 없음 → 제외 |

## 5. 설계

### 5.1 경계: `Runner` / `RunHandle` (cloud 모듈)
`SessionManager` 가 `AfkSession` 을 직접 만들고 부르던 곳(`box.session?.…`)을 모두 아래 경계로 바꾼다.
```kotlin
interface Runner {                       // 잠수를 실제로 돌리는 곳 하나 (내장 또는 원격 노드)
    val id: String                       // "local" 또는 접속용 서버 이름
    fun capacity(): Int                  // 받을 수 있는 최대 잠수 수
    fun load(): Int                      // 지금 돌리는 잠수 수
    fun launch(req: LaunchRequest, events: SessionListener, hooks: LaunchHooks): RunHandle
}
interface RunHandle {
    fun stop(); fun shutdown()
    fun sendChat(text: String): Boolean
    fun setStick(east: Float, south: Float); fun setMovement(mask: Int)
    fun clickMenu(slot: Int): Boolean; fun closeMenu()
    fun requestSuggestions(text: String, cb: (CommandSuggestions?) -> Unit): Boolean
    fun traffic(): Traffic?              // 원격은 마지막으로 받은 값
    fun position(): Wire.Pos?            // 원격은 마지막으로 받은 값
}
class LaunchRequest(val sid: String, val uuid: String, val entry: CloudEntry)
interface LaunchHooks {                  // 실행기가 본 서버에 되묻는 것
    fun credentials(forceRefresh: Boolean): JoinCredentials   // = auth.credentials(uuid, …)
    fun claimTarget(connectHost: String, connectPort: Int)    // = claimIp; 자리 없으면 예외(문구 그대로)
}
```
- `LocalRunner`: 지금 `launch()` 안의 `AfkSession(...)` 생성 코드를 그대로 옮긴 것. `targetFilter` 에서 `NetGuard.filter` 후 `hooks.claimTarget`.
- `RemoteRunner`: 원격 노드 연결 하나를 감싼다(5.3).
- `SessionBox.session: AfkSession?` → `SessionBox.run: RunHandle?` + `SessionBox.runner: String?`(어느 노드인지).
- `position()` 은 지금 `liveConnection` 에서 좌표·월드 이름을 읽는 코드(`Sessions.kt` `position(b)`)를 `LocalRunner` 쪽으로 옮긴다.

### 5.2 배정 (`Scheduler`, 본 서버)
- 실행기 목록 = 내장(`localWorker=true` 일 때) + 지금 연결된 원격 노드.
- 시작할 때: 남은 자리(`capacity - load`)가 있는 실행기 중 ① **같은 마인크래프트 서버(`addressKey`)에 이미 들어가 있는 잠수가 가장 적은 곳**(허용받은 서버의 인원을 여러 IP 로 나눠 서버 쪽 부담을 줄임), ② 그다음 **남은 자리 비율이 가장 큰 곳**, ③ 같으면 원격보다 내장 우선. 그래서 `Runner.launch` 전에 `Scheduler` 가 노드별·주소별 수를 안다(본 서버가 배정 기록으로 셈). 나눠 놓아도 서버별 인원 제한은 계속 전체 합계라서 허용 수를 넘지 않는다. 아무 데도 자리가 없으면 `StartResult.NoRunner` → 사용자가 직접 시작한 경우 "지금 클라우드 서버가 가득 찼습니다" 문구로 바로 거절.
- **재시작 이어서 접속(`resumeAtBoot`)·노드 끊김 다시 배정은 자리가 없으면 지우지 않고 기다린다**: "잠수 중" 표시를 남기고 상태를 "접속할 자리를 기다리는 중…"(기존 `SessionState` 중 대기 계열, 문구만 다름 → Wire 변경 없음)으로 두고 10초마다 다시 배정을 시도, 10분 지나도 자리가 없으면 그 문구로 멈춤(표시는 남김 → 다음 재시작 때 다시 시도). 기다리는 동안은 `maxActiveSessions`·서버별 인원에 **세지 않는다**(배정되는 순간 지금의 확인을 다시 거침).
- 서버별 인원 확인(`activeByKey`, `activeByIp`)·계정별 동시 수·중복 접속 확인은 지금처럼 `SessionManager.start()` 에서 본 서버가 한다 (노드와 무관한 전체 합계). `claimIp` 는 원격 노드도 `LaunchHooks.claimTarget` 으로 본 서버에 묻는다.
- `maxActiveSessions` 는 계속 "전체 상한". 내장 실행기 용량은 `localCapacity`(기본 = `maxActiveSessions`).

### 5.3 본 서버 ↔ 접속용 서버 연결 (`link/` 패키지)
**연결 방향**: 접속용 서버 → 본 서버 `workerPort`(기본 0 = 끔, 켜면 예: 8788). 본 서버는 `workerBind`(기본 `0.0.0.0`)에서 받는다.

**전송**: Netty TCP, `LengthFieldBasedFrameDecoder`(4바이트 길이, 프레임 최대 1MB) 위에 암호화된 프레임.

**암호화·인증 (사전 공유 키, 표준 구성만 사용)**
- 비밀키: 본 서버가 처음 `workerPort` 를 켤 때 `data/worker.key`(32바이트 랜덤, base64)를 만든다. 운영자가 그 값을 접속용 서버 설정 `secret=` 에 붙여 넣는다.
- 핸드셰이크: 접속용 서버 `HELLO{ver, name, nonceW(32B)}` → 본 서버 `CHALLENGE{nonceM(32B)}` → 접속용 서버 `AUTH{HMAC-SHA256(key, "w"‖nonceW‖nonceM‖name)}` → 본 서버가 상수 시간 비교 후 `WELCOME{HMAC-SHA256(key,"m"‖nonceM‖nonceW)}` (서로 인증).
- 세션 키: HKDF-SHA256(key, salt = nonceW‖nonceM) → 방향별 AES-256-GCM 키 2개. 프레임 nonce = 4바이트 0 + 8바이트 증가 카운터(방향별), 카운터가 기대값과 다르면 연결 끊음(재전송·순서 바꾸기 차단).
- 핸드셰이크 실패·5초 안에 안 끝남 → 끊음. 같은 IP 에서 1분에 10번 넘게 실패하면 10분 거부.
- `HELLO.ver` 는 `LINK_VERSION`(정수). 다르면 본 서버가 "본 서버와 접속용 서버의 버전이 다릅니다 (본 서버 vX, 접속용 vY)" 로 거절하고 양쪽 로그에 남김.
- 같은 이름의 노드가 이미 연결돼 있으면 새 연결을 거절(로그).

**메시지 (암호화된 프레임 안의 JSON, `t` = 종류)**
| 방향 | t | 내용 |
|---|---|---|
| M→W | `launch` | `sid`(노드 안 고유 실행 id = `uuid/serverId#gen`), `entry`(`Wire.entry`) |
| M→W | `chat` `stick` `move` `click` `closeMenu` `stop` `shutdown` | 대상 `sid` + 값 |
| M→W | `suggest` | `sid`, `text`, `rid`(요청 번호) |
| M→W | `creds` | `rid`, `ok` + `name`·`uuid`·`accessToken` 또는 `error`(문구, `reLogin`) |
| M→W | `claim` | `rid`, `ok` 또는 `error`(문구) |
| W→M | `status` `line` `players` `menu` `inventory` `vitals` `death` `pack` `dup` | `sid` + `Wire.*` 형식 그대로 (Wire 의 직렬화·`parse*` 재사용) |
| W→M | `pos`(바뀌었을 때 최대 0.25초마다) `traffic`(60초마다) | `sid` + 값 |
| W→M | `suggestR` | `rid` + 결과 |
| W→M | `credsQ` | `rid`, `sid`, `force` |
| W→M | `claimQ` | `rid`, `sid`, `host`, `port` |
| 양쪽 | `ping`/`pong` | 5초마다. 15초 동안 아무것도 못 받으면 끊긴 것으로 봄 |
| W→M | `load` | 30초마다 `capacity`, `load`, 메모리 사용량 (본 서버 로그용) |

- `credsQ`/`claimQ` 는 접속용 서버의 세션 스레드에서 **기다리는** 호출이다 → 응답을 최대 20초 기다리고, 넘으면 예외(세션은 지금의 인증 실패·재시도 규칙대로).
- 본 서버는 `credsQ`·`claimQ`·이벤트의 `sid` 가 **그 노드에 배정한 실행**인지 확인하고, 아니면 무시(다른 계정 토큰을 달라고 할 수 없게).
- 이벤트 크기 한도는 지금 본 서버 한도(줄 24KB·이벤트 600KB)를 본 서버가 받은 뒤 그대로 적용.

### 5.4 장애 처리
- **접속용 서버가 본 서버 연결을 잃으면**(15초 무응답 포함) 자기가 돌리던 잠수를 **모두 즉시 끊는다** (같은 계정이 두 곳에서 접속하는 것을 막는 안전장치). 그리고 5초·10초·30초…(최대 60초) 간격으로 다시 연결을 시도한다.
- **본 서버가 노드 연결을 잃으면**: 그 노드의 잠수를 "접속용 서버 연결이 끊겨 다른 곳에서 다시 접속합니다" 상태로 두고, **30초 뒤**(접속용 서버가 끊김을 알아차리는 15초보다 길게) 남은 실행기에 다시 배정한다. 자리가 없으면 그 문구로 멈추고 "잠수 중" 표시는 남겨 노드가 돌아오면 이어서 접속.
- 다시 배정은 2초 간격으로 차례로 (모장 요청 제한, 지금 `resumeAtBoot` 와 같은 규칙).
- **본 서버가 다시 켜지면**: 접속용 서버들은 연결을 잃었으므로 이미 다 끊었다 → 지금처럼 `resumeAtBoot` 가 다시 배정한다. 첫 30초는 노드가 다시 붙을 시간을 주기 위해 원격 노드에 자리가 날 때까지 기다리지 않고, 그때 있는 실행기로 배정한다 (내 판단: 빈 내장 실행기가 없으면 노드가 붙는 대로 이어서 배정).
- 접속용 서버를 끌 때(Ctrl+C) `bye` 를 보내 본 서버가 바로 다시 배정하게 한다.
- **다시 배정 남발 막기**: 다시 배정하면 새 `AfkSession` 이 만들어져 엔진의 재접속 보호 카운터(정품 인증 실패 3번, 모장 429 대기, 킥 8번 뒤 3분 대기)가 0 부터 시작한다. core 는 고치지 않고, 본 서버가 잠수마다 "최근 10분 동안 다시 배정한 횟수"를 세어 **3번을 넘으면** "접속용 서버가 자주 끊겨 잠수를 멈췄습니다" 로 멈춘다. 그리고 다시 배정할 때 직전 실행이 마지막으로 보고한 상태가 모장 대기·킥 대기 중이었으면, 그 대기 시각(`SessionStatus.nextAttemptAtMs`)이 지난 뒤에 새 실행기에 배정한다. 중복 로그인으로 멈춘 잠수는 다시 배정하지 않는다.

### 5.5 설정
본 서버 `cloud.properties` (새 항목, 없으면 기본값 = 지금 동작)
| 항목 | 뜻 | 기본 |
|---|---|---|
| `localWorker` | 본 서버 안에서도 잠수를 돌릴지 | true |
| `localCapacity` | 내장 실행기 최대 잠수 수 | `maxActiveSessions` |
| `workerPort` | 접속용 서버를 받는 포트 (0 = 받지 않음) | 0 |
| `workerBind` | 그 포트를 여는 주소 | 0.0.0.0 |

접속용 서버 `worker.properties` (`jamsu-cloud worker [파일]`, 처음 실행하면 틀을 만들어 주고 끝냄)
| 항목 | 뜻 | 기본 |
|---|---|---|
| `master` | 본 서버 `주소:포트` | (필수) |
| `secret` | 본 서버 `data/worker.key` 의 값 | (필수) |
| `name` | 이 노드 이름 (로그·배정 표시) | 컴퓨터 이름 |
| `capacity` | 받을 최대 잠수 수 | 200 |
| `allowPrivateTargets` | 시험용 | false |
| `dataDir` | 버전 변환기 설정(engine) 폴더 | data |

- `NetGuard`(내부망·자기 주소 차단)는 **실제로 접속하는 노드에서** 그 노드의 주소 기준으로 검사한다.
- 접속용 서버도 `World.maxChunks = 200`.

### 5.6 바뀌는 파일 (예상)
- `cloud/.../Sessions.kt` — `box.session` 사용처를 `RunHandle` 로, `launch()` 의 실행 부분을 `Runner` 로, 노드 끊김 시 다시 배정
- 새 파일 `Runner.kt`(경계·`LocalRunner`·`Scheduler`), `link/LinkCrypto.kt`, `link/MasterLink.kt`(본 서버 쪽 수신·`RemoteRunner`), `link/WorkerMain.kt`(접속용 서버 쪽)
- `Config.kt` 새 항목, `Main.kt` `worker` 명령, `docs/CLOUD.md` "접속용 서버 붙이기" 절, `CLAUDE.md` 클라우드 항목, `gradle.properties` 2.1.0
- 하네스 `Harness.kt` 새 모드

## 6. 시험 (완료 조건)
1. 기존 `harness cloud … [walk포트]` 38개 항목이 **설정 변경 없이** 그대로 통과 (내장 실행기 경로), `harness wire`·`harness chatlimit` 통과, 웹 시험 `web-test.js` 23개 통과
2. 새 `harness cloudnode`: 본 서버(`localWorker=false`, `workerPort` 켬) + 접속용 서버 2개(같은 PC, 다른 이름)를 띄워
   - 시작 → 원격 노드에서 접속, 채팅·명령 보내기, 이동·위치, 메뉴 클릭, 자동완성, 끊기가 웹 API 로 그대로 됨
   - 서버별 인원: `maxAccountsPerTarget=2` 에서 계정 3개를 같은 서버로 → 노드가 2개여도 3번째는 거절 (**전체 합계**)
   - 노드 하나 강제 종료 → 30초 뒤 다른 노드에서 다시 접속, 그 사이 같은 계정이 두 곳에 접속하지 않음
   - 본 서버 강제 종료 → 접속용 서버가 15초 안에 잠수를 모두 끊음
   - 틀린 `secret`·다른 `LINK_VERSION` → 거절, 로그 문구 확인
   - 접속용 서버 data 폴더에 토큰·계정 파일이 없음
3. `-Pflavor=github`/`play` 앱 컴파일 확인 (core 를 건드릴 경우)

## 7. 빌더에게 맡기는 것
- 클래스·파일 세부 이름, 메시지 JSON 키 철자, 로그 문구 다듬기
- `Scheduler` 내부 구현, 재연결 백오프의 정확한 값(위 범위 안에서)

## 8. 내가 정한 것 (사용자 확인 필요)
- 버전: **2.1.0** (부 버전 — 앱·웹·Wire 가 안 바뀌는 클라우드 내부 구조 변경이라서. 3.0.0 으로 하자면 바꿈)
- 암호화: TLS 대신 사전 공유 키 + HMAC 상호 인증 + HKDF + AES-GCM. 이유: 자바 17 만으로 인증서 생성이 번거롭고(Netty 자체 서명 인증서는 BouncyCastle 이나 JDK 내부 API 필요) 운영자가 인증서를 관리할 필요가 없음. 모두 JDK 표준 알고리즘
- 노드 끊김 시 30초 뒤 다시 배정, 접속용 서버는 본 서버를 잃으면(15초 무응답) 즉시 모두 끊기. 짧은 회선 끊김에도 잠수가 재접속되는 대가가 있지만, 같은 계정 두 곳 접속(서로 튕김·서버 의심)을 확실히 막는 쪽을 택함. 지금은 노드가 내장 1개뿐이라 실제로 영향 없음
- 배정은 같은 서버의 잠수를 노드에 고르게 나눔(인원 제한은 전체 합계 유지)
- 자리가 없을 때: 직접 시작은 바로 거절, 재시작 이어서 접속·노드 끊김 다시 배정은 10분까지 기다림
- 다시 배정은 10분에 3번까지, 직전 대기 시각 존중 (core 수정 없이)
- 같은 jar 하나로 두 역할

## Unresolved
- 실제 원격 노드는 아직 없음 → 원격 경로의 실사용 검증은 서버를 늘리는 날로 이월 (PC 에서 프로세스 여러 개로만 시험)
- 노드 상태를 웹에서 보는 운영자 화면은 이번 범위 밖 (본 서버 로그로만)
