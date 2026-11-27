# Play 심사 대비 (체험 모드 · 차단/신고 · 약관 · 아이템 잠금 · 왕복형 이름) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인 없이 앱 기능을 써 볼 수 있는 체험 모드와 Play UGC 정책이 요구하는 약관 동의·차단·신고를 넣고, 아이템 버리기 잠금과 왕복형 이름을 더해 앱 v2.3.0 을 만든다.

**Architecture:** core 에 녹화한 장면을 다시 보내는 작은 1.21.4 서버(`DemoServer`, 127.0.0.1 전용)를 두고, 앱은 체험 항목일 때 그 주소와 비정품 이름으로 **기존 접속 경로 그대로** 붙는다. 차단은 채팅 줄의 글자만 보고 판단하는 순수 함수(`ChatAuthor`, core)를 `SessionHub` 의 줄 들어오는 두 곳에서 쓴다. 신고는 `mailto:` 창, 약관 동의는 `BaseActivity` 에서 띄우는 닫을 수 없는 창.

**Tech Stack:** Kotlin/JVM 17 (core: Netty 4.1, Gson), Android(코드로 만든 화면, `Ui.kt`), node-minecraft-protocol(녹화 도구), PC 하네스(`tools/corebuild`), GitHub Actions 에뮬레이터.

**Spec:** `docs/superpowers/specs/2026-11-27-play-review-readiness-design.md`

## Global Constraints
- 작업 브랜치 `play-review` (`screenshots` 에서 딴다). **`main` 은 건드리지 않는다** (푸시하면 공개 릴리스). 작업 브랜치 푸시는 Task 10 에서만.
- 새 라이브러리 금지. `Wire`·`Wire.API`·`MenuItem`·`LogLine` 에 필드를 더하지 않는다.
- 앱 문구는 모두 `tr("한국어", "English")`. 이 계획에 적힌 문구는 그대로 쓴다 (Task 10 의 스크립트가 글자로 버튼을 찾는다).
- 비정품 이름(`JoinCredentials.offline`)은 `AfkService.begin` 의 체험 분기에서만 만든다. `AccountStore` 는 고치지 않는다.
- `DemoServer` 는 `127.0.0.1` 에만 연다.
- 스크린샷 모드(`Screenshots`)와 `screenshots.yml` 의 동작은 바꾸지 않는다 (약관 자동 동의만 추가).
- 확인 명령 (모든 작업 공통, 출력이 없으면 성공):
  - `./gradlew -p tools/corebuild :appcheck:compileKotlin -Pflavor=github`
  - `./gradlew -p tools/corebuild :appcheck:compileKotlin -Pflavor=play`
  - 하네스: `./gradlew -p tools/corebuild :harness:installDist` → `tools/corebuild/harness/build/install/harness/bin/harness <모드>` (끝줄 `RESULT …`, 성공하면 종료 코드 0)
- 커밋 메시지는 한국어, 끝에 `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- 저장소 파일은 CRLF 일 수 있다 (고칠 때 줄 끝을 바꾸지 말 것).

## Review Focus
1. **체험 서버가 꺼졌다 다시 켜짐** (앱 프로세스가 죽었다 살아나 포트가 바뀜): 다시 시작하면 새 포트로 접속돼야 한다 → Task 2 `demo` 의 `restart` 항목.
2. **차단 닉네임이 다른 이름의 일부이거나 한글 닉네임**: `Min` 을 차단해도 `<Coral_Min>` 줄은 보이고, `철수` 를 차단하면 `<철수> 안녕` 은 숨는다 → Task 5 `blockmatch`.
3. **약관 창과 화면 다시 만들기·거절**: 거절하면 앱이 닫히고 다시 열면 또 뜬다. 화면 회전·설정 변경으로 다시 만들어져도 창이 하나만 뜬다 → Task 10 스크립트의 거절 → 재실행 단계.
4. **메일 앱이 없는 기기에서 신고**: 죽지 않고 내용을 복사한 뒤 주소를 보여 준다 → Task 10 (에뮬레이터에는 메일 계정이 없다).
5. **앱 언어를 바꾼 뒤**: 잠금이 풀리지 않고(Task 7 `lockkey`), 다음 체험 접속은 바뀐 언어의 장면이 나온다(Task 2 `demo` 의 `lang` 항목 — 서버를 켠 때가 아니라 접속할 때 언어를 고른다).

---

### Task 1: 장면 녹화 도구와 녹화 파일

**Files:**
- Modify: `tools/test-server/showcase.js`
- Create: `tools/demo/record.js`
- Create: `core/src/main/resources/jamsuham/demo/scene-ko.bin.gz`, `scene-en.bin.gz`, `icon.png` (`tools/screenshots/icons/sea.png` 복사)

**Interfaces:**
- Produces (장면 파일 형식, Task 2 가 읽음): gzip 안에 레코드가 이어짐. 레코드 = `segment: u8` · `delayMs: varint` · `length: varint` · `bytes`(패킷 번호 varint + 내용, 압축 안 함).
  - `segment` 0=login, 1=config, 2=play, 3=menu, 4=slot. `delayMs` 는 같은 segment 의 앞 레코드로부터 지난 시간(play 만 의미 있음, 나머지는 0).
  - slot 레코드 = 인벤토리 한 칸의 `container_set_slot` 패킷 전체 (창 0, stateId 1 고정 → 번호 1바이트 + 창 1 + stateId 1 + 칸 2바이트 뒤가 개수). 
  - 넣지 않는 것: login 의 압축 설정(0x03), play 의 keep-alive(0x27)·ping(0x37).

- [ ] **Step 1: `showcase.js` 에 언어·체험 줄 추가.** 환경 변수 `SCENE_LANG=en` 이면 모든 글을 영어로 (닉네임은 그대로). 아래 값은 고정:

| | ko | en |
|---|---|---|
| 탭 머리글 | `§b§l바다마을 야생` | `§b§lSeaside Survival` |
| 메뉴 제목 | `바다마을 메뉴` | `Seaside Menu` |
| 메뉴 칸 10·11·13·15·16·22 | 마을 광장으로 이동 / 낚시터 / 잠수 보상 받기 / 상점 / 출석 체크 / 닫기 | Go to village square / Fishing spot / Claim AFK reward / Shop / Daily check-in / Close |
| 잠수 답 줄에 들어갈 말 | `잠수 모드가 켜졌습니다` | `AFK mode is on` |
| 마지막 채팅 줄에 들어갈 말 | `잠수 걸어 뒀어요` | `left my phone AFK` |

  `DEMO=1` 이면 마지막 채팅 뒤에 한 줄 더: `[안내] 메뉴를 열려면 ` + 누를 수 있는 `[메뉴 열기]`(run_command `/메뉴`) — 영어는 `[Guide] To open the menu, tap ` + `[Open menu]`(run_command `/menu`). 그리고 접속자 한 명(Tide_Runner)의 칭호를 `[바다마을 건축가]` / `[Seaside Architect]` 로 길게 (왕복형 이름 확인용). `DEMO` 가 없으면 지금과 글자 하나 다르지 않아야 한다.

- [ ] **Step 2: 기존 장면이 그대로인지 확인.** `node tools/test-server/server.js 1.21.4 25590 showcase` 를 띄우고 `harness showcase 25590 <메뉴 파일>` → `RESULT showcase … ` 뒤 종료 코드 0 (환경 변수 없이, `SHOWCASE_MENU_FILE` 은 지금처럼).

- [ ] **Step 3: `tools/demo/record.js` 작성.** 사용법 `node tools/demo/record.js <포트> <메뉴 파일> <출력.bin.gz>`. node-minecraft-protocol 클라이언트(1.21.4, 이름 `Jamsu_Diver`, 비정품)로 붙어 `raw` 이벤트의 패킷을 상태별로 모은다. 마지막 채팅 줄이 온 뒤 2초 지나면 메뉴 파일을 만들고, 그 뒤 3초 동안 온 `open_window`·`window_items`·`set_slot`(창 9) 를 menu segment 로 옮긴다. 창 0 의 `window_items` 에서 아이템이 있는 칸마다 서버용 직렬화기로 `set_slot`(windowId 0, stateId 1) 을 만들어 slot segment 에 넣는다. 끝에 한 줄 출력: `RECORDED login=<n> config=<n> play=<n> menu=<n> slot=<n> bytes=<압축 전> gz=<압축 후>`.

- [ ] **Step 4: 두 벌 녹화.** `DEMO=1` 로 서버를 띄워 `scene-ko.bin.gz`, `DEMO=1 SCENE_LANG=en` 으로 `scene-en.bin.gz`. 기대: `login=1 config>=2 play>=60 menu>=2 slot=18`, `gz` 가 각 200KB 이하.

- [ ] **Step 5: 커밋** (`체험 장면 녹화 도구와 녹화 파일 (한국어·영어)`).

---

### Task 2: 체험 서버 (core) + `harness demo`

**Files:**
- Create: `core/src/main/kotlin/kr/jamsuham/core/demo/DemoScene.kt`, `core/src/main/kotlin/kr/jamsuham/core/demo/DemoServer.kt`
- Modify: `tools/corebuild/harness/src/main/kotlin/Harness.kt` (모드 `demo`)

**Interfaces:**
- Consumes: Task 1 의 장면 파일. `kr.jamsuham.core.net` 의 `FrameCodec`, `readVarInt`/`writeVarInt`/`readMcString`/`writeMcString`, `PlayIn`(서버가 보내는 번호)·`PlayOut`(서버가 받는 번호)·`LoginIn`/`LoginOut`/`ConfigIn`/`ConfigOut`.
- Produces:
  - `object DemoServer { const val NAME = "Jamsu_Diver"; fun ensureStarted(): Int; fun address(): String; fun stop() }` — `address()` 는 `"127.0.0.1:<포트>"` (꺼져 있으면 켠다).
  - `class DemoScene` + `DemoScene.load(english: Boolean): DemoScene` (언어별로 한 번 읽어 기억).

- [ ] **Step 1: 실패하는 시험 `harness demo` 작성.** 한 번 실행에 아래를 순서대로 확인하고 `RESULT demo <항목>=<true|false> …` 를 찍는다. 하나라도 false 면 실패.
  - `ping`: `StatusPinger.ping` → `online == 37 && max == 100 && favicon != null && versionName == "1.21.4"`, 소개 글이 `낚시와 잠수 보상` (영어 `Fishing and AFK rewards`) 포함
  - `join`: `AfkSession`(`SessionConfig(DemoServer.address(), fixedVersion = "1.21.4", walkOnJoin = false)`, `JoinCredentials.offline(DemoServer.NAME)`) 이 ONLINE, 20초 안에 마지막 채팅 줄(`잠수 걸어 뒀어요`)이 옴
  - `scene`: 접속자 8명, 인벤토리 18칸, 체력 17, `blocks == listOf(9, 0, 86, 15, 14, 4340, 13526, 9)` (기존 `showcase` 모드와 같은 좌표)
  - `echo`: `sendChat("hello")` → 줄 `<Jamsu_Diver> hello`
  - `menu`: `sendChat("/메뉴")` → 메뉴 제목 `바다마을 메뉴`, 27칸 안 아이템 6개 → `clickMenuSlot(13)` → 메뉴가 닫히고 `잠수 보상 받기` 가 든 줄이 옴. 그 뒤 `sendChat("/메뉴")` 로 다시 열림
  - `drop`: 9..44 칸 중 개수가 2 이상인 칸에 `dropItem(slot, false)` → 그 칸 개수 −1, `dropItem(slot, true)` → 그 칸이 인벤토리에서 사라짐
  - `unknown`: `sendChat("/없는명령")` → `알 수 없는 명령어` 가 든 줄
  - `restart`: 세션을 끊고 `DemoServer.stop()` → `DemoServer.address()` 가 다시 켜고 핑이 됨
  - `lang`: `Lang.english = true` 로 바꾸고 새로 접속 → 메뉴 제목 `Seaside Menu`, 핑 소개 글 `Fishing and AFK rewards`
  - `loopback`: 서버 소켓의 주소가 loopback

- [ ] **Step 2: 실패 확인.** `:harness:installDist` 가 `DemoServer` 를 찾지 못해 컴파일 실패.

- [ ] **Step 3: `DemoScene` 구현** (Task 1 형식 읽기. slot 레코드는 칸 번호 → 패킷 바이트).

- [ ] **Step 4: `DemoServer` 구현.** Netty 서버(스레드 1개, 데몬), 파이프라인 `FrameCodec` + 처리기. 연결마다 상태 `HANDSHAKE → STATUS | LOGIN → CONFIG → PLAY`.
  - STATUS: 응답 JSON `{"version":{"name":"1.21.4","protocol":769},"players":{"max":100,"online":37},"description":{"text":"§b낚시와 잠수 보상"},"favicon":"data:image/png;base64,…"}` (영어 소개 글 `§bFishing and AFK rewards`), ping → pong.
  - LOGIN: 로그인 시작을 받으면 login 레코드를 보냄 → `LoginOut.ACK` 를 받으면 config 레코드 → `ConfigOut.FINISH` 를 받으면 play 레코드를 `delayMs` 간격으로. 장면 언어는 **로그인 시작을 받는 순간**의 `Lang.english`.
  - PLAY: 10초마다 `PlayIn.KEEP_ALIVE`. 받은 것 처리 —
    - `PlayOut.CHAT`: `<Jamsu_Diver> 말` 을 `PlayIn.SYSTEM_CHAT`(이름 없는 NBT 문자열 태그 + overlay=false)으로
    - `PlayOut.CHAT_COMMAND`: `메뉴`·`menu` → menu 레코드 전송 / `잠수`·`afk` → 답 없음(녹화에 이미 있음) / 그 밖 → `알 수 없는 명령어입니다` (`Unknown command`)
    - `PlayOut.CONTAINER_CLICK` 창 9: 칸이 10·11·13·15·16·22 면 `[메뉴] '<이름>' 을(를) 눌렀습니다` (`[Menu] You tapped '<name>'`, 이름은 Task 1 표) 뒤 `PlayIn.CONTAINER_CLOSE`(9)
    - `PlayOut.CONTAINER_CLICK` 창 0·mode 4: 연결별로 칸 개수를 기억해 button 0 이면 −1, 1 이면 0 → slot 레코드의 개수 바이트를 바꿔 보냄 (0 이면 개수 0 까지만 보냄)
    - `PlayOut.USE_ITEM`: `아이템을 사용했습니다` (`Item used`)
    - 나머지는 무시. 풀 수 없는 패킷이 와도 연결을 끊지 않는다.

- [ ] **Step 5: `harness demo` 통과 확인** (한국어), 그리고 `EN=1 harness demo` 는 필요 없음 — `lang` 항목이 영어를 확인한다. 기존 `harness showcase`(node 서버)도 그대로 통과.

- [ ] **Step 6: 커밋** (`체험 서버: 앱 안에서 도는 연습용 1.21.4 서버 (녹화 장면 다시 보내기)`).

---

### Task 3: 앱 체험 모드

**Files:**
- Create: `app/src/main/kotlin/kr/jamsuham/app/Demo.kt`
- Modify: `Store.kt`, `AfkService.kt`, `BaseActivity.kt`, `MainActivity.kt`, `SettingsActivity.kt`, `ServerEditActivity.kt`, `Images.kt`(ServerInfos) — 모두 `app/src/main/kotlin/kr/jamsuham/app/`

**Interfaces:**
- Consumes: `DemoServer.NAME`, `DemoServer.address()`.
- Produces:
  - `ServerEntry.demo: Boolean = false`, `ServerEntry.connectAddress: String`(체험이면 `DemoServer.address()`, 아니면 `address`). `displayName` 은 체험이고 이름이 비면 `tr("체험 서버", "Demo server")`. `sessionConfig()` 는 `connectAddress` 를 쓰고 체험이면 `joinCommands = listOf(tr("/잠수", "/afk"))`.
  - `object Demo { const val ID = "demo"; fun exists(store: Store): Boolean; fun add(store: Store): ServerEntry }` — `add` 가 넣는 값: `id = ID, demo = true, name = "", address = "", fixedVersion = "1.21.4", joinDelaySec = 2, resumeOnBoot = false, packGlyphs = false, walkOnJoin = false, cloud = false`.

- [ ] **Step 1: `Store.kt`·`Demo.kt`.** 위 Interfaces 대로.
- [ ] **Step 2: 시작 경로.** `BaseActivity.startServer`: 체험이면 주소 빈칸 검사·계정 검사·클라우드 확인을 건너뛰고 동시 잠수 제한·알림 권한부터 이어 간다. `AfkService.begin`: 체험이면 계정 검사 대신 `SessionHub.start(this, serverId, server.sessionConfig(), CredentialsProvider { JoinCredentials.offline(DemoServer.NAME) })`. `ServerInfos.refresh` 는 `connectAddress` 로 핑.
- [ ] **Step 3: 화면.**
  - `MainActivity` 빈 목록 카드에 두 번째 버튼 `tr("로그인 없이 체험해 보기", "Try without signing in")` → `Demo.add` 후 목록 다시 그림(넓은 화면이면 그 항목 선택).
  - 목록 줄: 체험이면 핑 정보가 없을 때 주소 대신 `tr("앱 안 연습용 서버", "Practice server inside the app")`, 계정 이름 자리에 `tr("체험 · 로그인 필요 없음", "Demo · no sign-in needed")`.
  - `SettingsActivity` "앱" 구역에 `tr("체험 서버 추가", "Add demo server")` 버튼 (`Demo.exists` 면 안 보임, 누르면 추가하고 `tr("서버 목록에 체험 서버를 추가했습니다", "Added the demo server to the list")` 토스트).
  - `ServerEditActivity`: 체험이면 이름 칸과 삭제 버튼만 보이고, 저장은 이름만 바꾼다 (다른 값은 그대로).
- [ ] **Step 4: 컴파일 확인** (github·play 둘 다 출력 없음).
- [ ] **Step 5: 커밋** (`체험 모드: 로그인 없이 앱 안 연습용 서버에 접속`).

---

### Task 4: 이용 약관 동의

**Files:**
- Create: `app/src/main/assets/legal/terms.md`, `terms-en.md`, `app/src/main/kotlin/kr/jamsuham/app/Terms.kt`
- Modify: `BaseActivity.kt`, `AppInfo.kt`, `Screenshots.kt`, `app/src/main/assets/legal/privacy-policy.md`

**Interfaces:**
- Produces: `object Terms { const val VERSION = 1; fun accepted(context: Context): Boolean; fun gate(activity: Activity) }` — 저장은 prefs `jamsuham_settings` 의 `terms_accepted`(Int). `AppInfo.CONTACT_EMAIL`(개인정보처리방침의 문의 주소와 같은 값, Task 6 이 씀).

- [ ] **Step 1: 약관 문서 두 벌.** 제목 순서 고정: 1 이 앱이 하는 일 / 2 금지 행위(욕설·혐오·괴롭힘, 성적 콘텐츠, 아동 착취, 불법 행위, 스팸·광고, 사칭) / 3 서버 규칙을 지킬 책임 / 4 신고와 차단 / 5 위반 시 조치 / 6 면책 / 7 문의 / 8 시행일(2026-11-27, 버전 1). 5번은 spec 5.5 의 "위반 시 조치" 내용대로 (지킬 수 없는 약속 금지). 영어판은 같은 뜻.
- [ ] **Step 2: `Terms.gate`.** `accepted` 가 아니면 닫을 수 없는 `AlertDialog`: 제목 `tr("이용 약관 동의", "Terms of Use")`, 요약 두세 문장, 안쪽 버튼 `tr("이용 약관 전문", "Full terms")`·`tr("개인정보처리방침", "Privacy policy")`(`AppInfo.showDocument`, 약관은 언어에 맞는 파일), 아래 버튼 `tr("동의하고 시작", "Agree and start")`(저장) / `tr("동의하지 않음", "Decline")`(`finishAffinity()`). 한 화면에 창이 둘 뜨지 않게 한다.
- [ ] **Step 3: 연결.** `BaseActivity.onCreate` 끝에서 `Terms.gate(this)`. `AppInfo` 에 `tr("이용 약관", "Terms of Use")` 버튼. `Screenshots.init` 은 켜져 있을 때 `terms_accepted = Terms.VERSION` 을 쓴다.
- [ ] **Step 4: 개인정보처리방침.** 추가: 신고를 보내면 그 내용(상대 닉네임·UUID·채팅·서버 주소)이 메일로 개발자에게 전달됨 / 차단 목록·아이템 잠금·약관 동의 기록은 기기에만 저장 / 이용 약관 안내. 기존 문장은 고치지 않는다.
- [ ] **Step 5: 컴파일 확인, 커밋** (`이용 약관과 첫 실행 동의 화면`).

---

### Task 5: 차단

**Files:**
- Create: `core/src/main/kotlin/kr/jamsuham/core/text/ChatAuthor.kt`, `app/src/main/kotlin/kr/jamsuham/app/BlockList.kt`
- Modify: `Harness.kt`(모드 `blockmatch`), `SessionHub.kt`, `PlayerPanel.kt`, `ChatPanel.kt`, `SettingsActivity.kt`, `JamsuApp.kt`

**Interfaces:**
- Produces:
  - `object ChatAuthor { fun area(plain: String): String; fun isFrom(plain: String, name: String): Boolean; fun namesIn(plain: String, candidates: Collection<String>): List<String> }` — `area` = 처음 나오는 구분 글자(`"> "`, `": "`, `" » "`, `" -> "`, `" ▶ "`) 앞까지, 없으면 앞 64자. `isFrom` = `area` 안에 `name` 이 대소문자 무시·낱말 경계(앞뒤가 영문·숫자·`_` 가 아님)로 있음. `namesIn` = 후보 중 `isFrom` 인 것.
  - `object BlockList { fun init(context: Context); val version: Int; fun list(): List<Blocked>; fun isBlocked(name: String): Boolean; fun blocks(line: LogLine): Boolean; fun add(name: String, uuid: java.util.UUID?); fun remove(name: String) }`, `data class Blocked(val name: String, val uuid: String?, val addedAtMs: Long)` — prefs `jamsuham_blocks`. `blocks` 는 `LogLine.Kind.CHAT` 줄만 본다.
  - `SessionHub.Observer.onLinesReset(serverId: String) {}`, `SessionHub.purgeBlocked()`(모든 서버의 줄 버퍼에서 차단 줄을 빼고 `onLinesReset`), `SessionHub.hideLine(serverId: String, line: LogLine)`.
  - `PlayerPanel`: `var selfName: String?`, `var onBlock: (PlayerInfo) -> Unit`, `var onUnblock: (PlayerInfo) -> Unit`, `var onReport: (PlayerInfo) -> Unit`.

- [ ] **Step 1: 실패하는 시험 `harness blockmatch`.** `RESULT blockmatch ok=<n>/<전체>`:

| 줄 | 이름 | isFrom |
|---|---|---|
| `<Steve> 안녕` | Steve | true |
| `[VIP] Steve: 안녕` | steve | true |
| `Steve -> 나: 안녕` | Steve | true |
| `Steve 님이 접속했습니다` | Steve | true |
| `[공지] 관리자: Steve 는 정지되었습니다` | Steve | false |
| `<Steve_2> 안녕` | Steve | false |
| `<Coral_Min> 오늘 저녁 낚시 대회 몇 시예요?` | Min | false |
| `<철수> 안녕` | 철수 | true |
| `[안내] 잠수 보상은 30분마다 지급됩니다.` | 잠수 | true |
| `` (빈 줄) | Steve | false |

  그리고 `namesIn("[텔레포트] Coral_Min 님이 같이 가자고 요청했습니다", listOf("Coral_Min", "Sea_Otter")) == listOf("Coral_Min")`.
- [ ] **Step 2: 실패 확인 → `ChatAuthor` 구현 → 통과 확인.**
- [ ] **Step 3: `BlockList` + `SessionHub`.** `publishLine`·`remoteLine` 맨 앞에서 `BlockList.blocks(line)` 이면 버림(화면·`ChatLog` 모두). `BlockList.add` 뒤에는 `purgeBlocked()`. `JamsuApp.onCreate` 에서 `BlockList.init`.
- [ ] **Step 4: 접속자 탭.** 한 줄 = 얼굴 · 이름 · 핑 · `tr("차단", "Block")` · `tr("신고", "Report")` (작은 글자 버튼, 높이 32dp). `player.name == selfName`(대소문자 무시)이면 버튼 없음. 차단된 사람은 이름을 `p.muted` 색으로, 버튼은 `tr("차단 해제", "Unblock")` 하나. `ChatPanel` 이 `selfName`(체험이면 `DemoServer.NAME`, 아니면 그 서버 계정 이름)과 콜백을 넣는다: 차단은 확인 창(`tr("'${name}' 님의 채팅을 숨길까요? 설정 → 차단한 사용자에서 풀 수 있습니다.", "Hide chat from '${name}'? You can undo this in Settings → Blocked users.")`) 뒤 `BlockList.add`. `onReport` 는 Task 6 에서 연결 (이 작업에서는 버튼만 두고 빈 동작).
- [ ] **Step 5: `ChatPanel.onLinesReset`** → 어댑터를 `SessionHub.lines(serverId)` 로 다시 채움.
- [ ] **Step 6: 설정 화면** `tr("차단한 사용자", "Blocked users")` 구역: 목록 + `tr("해제", "Unblock")`, 없으면 `tr("차단한 사용자가 없습니다", "No blocked users")`.
- [ ] **Step 7: 하네스·컴파일 확인, 커밋** (`차단: 접속자 탭에서 사용자 차단, 차단한 사람의 채팅 숨김`).

---

### Task 6: 신고와 채팅 줄 메뉴

**Files:**
- Create: `app/src/main/kotlin/kr/jamsuham/app/Reports.kt`
- Modify: `ChatPanel.kt`

**Interfaces:**
- Consumes: `BlockList`, `ChatAuthor.namesIn`, `SessionHub.hideLine`, `AppInfo.CONTACT_EMAIL`, `PlayerPanel.onReport`.
- Produces: `object Reports { fun show(activity: Activity, ui: Ui, serverId: String, name: String?, uuid: java.util.UUID?, chatLine: String?); fun body(server: String, name: String?, uuid: String?, reason: String, details: String, chatLine: String?, version: String, timeMs: Long): String }`.

- [ ] **Step 1: `Reports.show`.** 창 제목 `tr("신고", "Report")`. 사유(하나 고름): `tr("욕설·괴롭힘", "Abuse or harassment")` / `tr("스팸·광고", "Spam or ads")` / `tr("부적절한 닉네임·내용", "Inappropriate name or content")` / `tr("기타", "Other")`. 입력 칸 힌트 `tr("설명 (선택)", "Details (optional)")`. 체크 `tr("이 사용자도 차단", "Also block this user")`(이름을 알 때만, 기본 켜짐). 안내 `tr("이 앱은 서버의 사용자를 제재할 수 없습니다. 서버 운영진에게도 신고해 주세요.", "This app can't take action against players on a server. Please report to the server staff too.")`. 버튼 `tr("신고 보내기", "Send report")` / `tr("취소", "Cancel")`.
- [ ] **Step 2: 보내기.** `Intent.ACTION_SENDTO`(`mailto:`), 받는 사람 `AppInfo.CONTACT_EMAIL`, 제목 `[잠수크래프트 신고] <이름 또는 채팅>`, 본문 `Reports.body(...)`(닉네임, UUID, 서버 주소 — 체험이면 `demo`, 사유, 설명, 채팅 내용, 시각, 앱 버전을 한 줄씩). `ActivityNotFoundException` 이면 본문을 클립보드에 복사하고 `tr("메일 앱이 없어 신고 내용을 복사했습니다. 아래 주소로 보내 주세요:\n${addr}", "No mail app found, so the report was copied. Please send it to:\n${addr}")` 창. 체크돼 있으면 `BlockList.add`.
- [ ] **Step 3: 채팅 줄 길게 누르기.** `LogLine.Kind.CHAT` 줄이면 메뉴 `tr("이 채팅 신고", "Report this message")` / `tr("이 채팅 숨기기", "Hide this message")` / `tr("쓴 사람 차단", "Block the sender")` / `tr("그림 글자 정보", "Glyph info")`(기존 `showGlyphInfo`). 다른 종류의 줄은 지금처럼 바로 `showGlyphInfo`.
  - 쓴 사람 찾기: `ChatAuthor.namesIn(줄, 접속자 이름들)` 에서 내 이름을 뺀 것. 하나면 그 이름, 여럿이면 고르게, 없으면 닉네임 입력 칸(빈칸으로 시작).
  - 신고는 찾은 이름(없으면 null)과 줄 글자를 `Reports.show` 로.
- [ ] **Step 4: `PlayerPanel.onReport` 연결** (`Reports.show(…, name, uuid, null)`).
- [ ] **Step 5: 컴파일 확인, 커밋** (`신고: 접속자·채팅 줄 신고(메일), 채팅 줄 숨기기·쓴 사람 차단`).

---

### Task 7: 아이템 버리기 잠금

**Files:**
- Create: `app/src/main/kotlin/kr/jamsuham/app/DropLocks.kt`
- Modify: `core/src/main/kotlin/kr/jamsuham/core/text/ItemNames.kt`, `Harness.kt`(모드 `lockkey`), `SessionHub.kt`, `InventoryPanel.kt`, `Store.kt`, `JamsuApp.kt`

**Interfaces:**
- Produces:
  - `ItemNames.lockKey(itemId: String, shownName: String): String` — `shownName` 이 그 아이템의 한국어 기본 이름이나 영어 기본 이름(`titleCase`)과 같으면 `itemId`, 아니면 `itemId + "\n" + shownName`. `Lang.english` 값과 무관해야 한다.
  - `object DropLocks { fun init(context: Context); fun isLocked(serverId: String, item: MenuItem): Boolean; fun toggle(serverId: String, item: MenuItem): Boolean; fun clear(serverId: String) }` — prefs `jamsuham_locks`, 서버 id → 키 묶음.

- [ ] **Step 1: 실패하는 시험 `harness lockkey`:** `lockKey("minecraft:diamond_sword", "다이아몬드 검") == "minecraft:diamond_sword"` · `lockKey("minecraft:diamond_sword", "Diamond Sword") == "minecraft:diamond_sword"` · `lockKey("minecraft:diamond_sword", "전설의 검") == "minecraft:diamond_sword\n전설의 검"` · 위 셋이 `Lang.english` 를 true 로 바꿔도 같음.
- [ ] **Step 2: 구현 → 통과 확인.**
- [ ] **Step 3: `DropLocks`, 그리고 막기.** `SessionHub.dropItem` 은 그 칸의 아이템이 잠겨 있으면 false. `Store.deleteServer` 가 `DropLocks.clear` 를 부른다.
- [ ] **Step 4: 인벤토리 화면.** 펼친 칸에 `tr("잠금", "Lock")` / `tr("잠금 해제", "Unlock")` 버튼(누르면 화면 갱신). 잠긴 아이템은 이름 옆에 `tr("잠김", "Locked")`(강조색 작은 글자), 버리기 버튼 대신 `tr("잠긴 아이템은 버릴 수 없습니다", "Locked items can't be dropped")`. 사용 버튼은 그대로.
- [ ] **Step 5: 하네스·컴파일 확인, 커밋** (`아이템 버리기 잠금 (아이템 종류별, 서버별로 기억)`).

---

### Task 8: 왕복형 이름

**Files:**
- Modify: `app/src/main/kotlin/kr/jamsuham/app/Ui.kt`, `PlayerPanel.kt`

**Interfaces:**
- Produces: `Ui.pingPong(tv: TextView): TextView` — 한 줄, 말줄임 없음. 글이 칸보다 길 때만 `scrollX` 를 0 ↔ (글 폭 − 칸 폭)으로: 1.2초 멈춤 → 40dp/초로 끝까지 → 1.2초 멈춤 → 같은 속도로 되돌아옴, 되풀이. 글·크기가 바뀌면 처음부터, 화면에서 떨어지면 멈춤. 짧으면 `scrollX = 0` 으로 가만히.

- [ ] **Step 1: 구현하고 `PlayerPanel` 의 이름 칸을 `ui.marquee` 에서 `ui.pingPong` 으로.** 다른 `ui.marquee` 사용처는 그대로.
- [ ] **Step 2: 컴파일 확인, 커밋** (`접속자 이름: 길면 끝까지 갔다가 되돌아오는 왕복형`).

---

### Task 9: Play 판에서 클라우드 잠수 숨김

**Files:**
- Modify: `SettingsActivity.kt`, `ServerEditActivity.kt`, `JamsuApp.kt`

- [ ] **Step 1:** `Flavor.PLAY` 이면 설정 화면에 `CloudSection` 을 넣지 않고, 서버 설정의 클라우드 카드를 넣지 않고(저장할 때 `cloud` 는 원래 값 유지), `JamsuApp` 에서 `CloudHub.onForeground()` 를 부르지 않는다. `CloudHub.init` 은 그대로 (연결 기록이 없으면 아무 요청도 하지 않고, 다른 코드가 `CloudHub` 를 읽을 때 죽지 않게).
- [ ] **Step 2: 컴파일 확인** (play·github 둘 다). `grep -n "Flavor.PLAY" SettingsActivity.kt ServerEditActivity.kt JamsuApp.kt` 로 세 곳 확인.
- [ ] **Step 3: 커밋** (`Play 판: 클라우드 잠수 숨김`).

---

### Task 10: 에뮬레이터에서 눌러 보기

**Files:**
- Create: `.github/workflows/smoke.yml`, `tools/screenshots/smoke.sh`
- Modify: `tools/screenshots/ui.py` (`longtap <글자>` 추가)

- [ ] **Step 1: `smoke.yml`.** `workflow_dispatch` 와 `push: branches: [play-review]`. `screenshots.yml` 의 phone 구성(pixel_2, API 34 google_apis x86_64, KVM)을 쓰되 **`JAMSUHAM_SCREENSHOTS` 없이** `:app:assemblePlayDebug`, node 서버 없음. 아티팩트 `smoke-shots`.
- [ ] **Step 2: `smoke.sh`** (에뮬레이터 기본 언어가 영어라 영어 문구로 누른다). 순서와 남길 그림:
  1. 첫 실행 → `01-terms` → `Decline` → 앱이 닫힘 → 다시 실행 → 약관 창이 또 보임 → `Agree and start`
  2. `02-empty` → `Try without signing in` → `03-list` → `Start` → 15초
  3. `adb shell dumpsys activity services kr.jamsuham.app` 에 `isForeground=true` 가 있음을 log 에 기록
  4. `04-chat` → `Map` `05-map` → `Inv` → 아이템 누름 → `Lock` → `06-inv-locked` → `Players` `07-players`
  5. `Block`(첫 접속자) → 확인 → `08-blocked` → `Chat` `09-chat-after-block`
  6. `Players` → `Report` → `10-report` → `Send report` → `11-report-sent`
  7. `Chat` → 채팅 줄 `longtap` → `12-line-menu`
  8. 홈으로 → 화면 끄기 5초 → 켜기 → 앱 다시 열기 → `13-still-afk` → `Disconnect`
  누르지 못한 글자는 `찾지 못함: …` 으로 log 에 남는다 (기존 `ui.py` 동작).
- [ ] **Step 3: 브랜치를 올리고 실행 결과 확인.** `git push -u origin play-review` → `gh run watch` → 아티팩트를 받아 13장을 직접 보고 `log.txt` 에 `찾지 못함` 0건, `isForeground=true` 가 있는지 확인. 이상하면 해당 작업으로 돌아가 고치고 다시.
- [ ] **Step 4: 커밋** (`에뮬레이터에서 체험·약관·차단·신고·잠금 눌러 보기`).

---

### Task 11: 문서·버전·전체 확인

**Files:**
- Modify: `gradle.properties`, `CLAUDE.md`, `docs/store-listing.md`, `docs/HANDOFF.md`

- [ ] **Step 1:** `jamsuham.version=2.3.0`.
- [ ] **Step 2: `docs/store-listing.md`.** 로그인 세부정보 영어 안내문을 새로(체험: `Agree and start` → `Try without signing in` → `Start`, 그다음 심사용 계정으로 실제 서버 접속 방법, 마이크로소프트 코드 안내), 콘텐츠 등급(차단·신고 "예", 채팅 조정 "아니요"), 데이터 보안(Play 판은 클라우드 숨김 → 수집 안 함), 약관 안내.
- [ ] **Step 3: `CLAUDE.md`·`docs/HANDOFF.md`.** 체험 서버와 녹화 방법(`tools/demo/record.js`, 프로토콜을 올리면 다시 녹화), 차단·신고·약관·잠금·왕복형, Play 판 클라우드 숨김, 새 하네스 모드(`demo`, `blockmatch`, `lockkey`), `smoke.yml`.
- [ ] **Step 4: 전체 확인.** `harness demo`·`blockmatch`·`lockkey`·`wire`·`chatlimit` 통과, node 서버로 `harness showcase`·`drop` 통과, 컴파일 두 판 통과. 결과를 그대로 적는다.
- [ ] **Step 5: 커밋** (`v2.3.0: 문서·스토어 안내 정리`).
- [ ] **Step 6: 사용자에게 넘김.** 에뮬레이터 그림, 약관 초안, 새 로그인 안내문을 보여 주고 폰 확인을 부탁한다. `main` 병합·푸시(= 공개 릴리스, Play 용 AAB 생성)는 사용자가 좋다고 한 뒤에.
