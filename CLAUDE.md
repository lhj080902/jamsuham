# 잠수함 — 작업 인수인계 메모 (Claude Code 용)

마인크래프트 잠수(AFK) 안드로이드 앱 (폰·태블릿). 챗크래프트 같은 채팅 클라이언트.
사용자는 한국어로 대화하며 **존댓말(~합니다/~입니다)** 로 답한다. 코드 주석·UI 문구도 한국어.

## 구조
- `core/` — 순수 Kotlin/JVM. 마인크래프트 프로토콜 클라이언트 (안드로이드 의존 없음, PC에서 바로 테스트 가능)
  - 앱은 항상 **1.21.4(프로토콜 769)** 로 말하고, ViaVersion 5.12.2 + ViaBackwards 5.12.2 + ViaRewind 4.2.0 이
    서버 버전(1.8 ~ 26.x)으로 변환한다. `core/libs/*-android.jar` 는 `tools/ViaPatcher.java` 로 안드로이드용 패치한 것
    (`tools/update-via.sh` 로 다시 만든다). 패치는 Runtime.version 등 안드로이드에 없는 API 를 `kr.jamsuham.compat` 로 돌림
  - `AfkSession.kt` 접속·재접속·대기(서버 꺼짐 핑 대기, 모장 장애 대기, 백오프), 자동 명령어(메뉴 열려 있으면 미룸)
  - `net/McConnection.kt` 실제 패킷 처리 (네티 파이프라인: timeout → decrypt/encrypt → frame → (de)compress → via-codec → handler).
    1.21.4 패킷 번호는 파일 아래쪽 `PlayIn`/`PlayOut`/`ConfigIn` 상수. 상태 전환은 ACK 보내기 **전에** 바꿔야 함
  - `text/ChatText.kt` 채팅 JSON/NBT → 색 있는 글자 (리소스팩 전용 글꼴 글자는 숨김), `text/ItemNames.kt` 아이템 한국어 이름
  - `world/World.kt` 청크·충돌(이동 물리용), `BlockColors`(미니맵 색), 리소스 `core/src/main/resources/jamsuham/`
- `app/` — 안드로이드 UI (XML 없이 코드로 화면 구성, `Ui.kt` 도우미). minSdk 26, target/compile 36
  - `SessionHub` 서버별 세션 상태(메인 스레드 콜백), `AfkService` 포그라운드 서비스(알림, 화면 꺼져도 유지)
  - `MainActivity` 서버 목록(태블릿은 두 칸), `ChatPanel` 채팅/지도/인벤/접속자 4페이지, `MapPanel` 미니맵·이동·웨이포인트,
    `InventoryPanel`, `PlayerPanel`, `ServerEditActivity`(서버별 설정·메뉴 자동 클릭 규칙), `LoginActivity`(MS 로그인 WebView),
    `Store`(서버·웨이포인트·기록, Gson+SharedPreferences), `Plan`(무료 동시 잠수 1개 / 프리미엄), `Updater`(GitHub 릴리스로 앱 안 업데이트)

## 빌드·배포
- `main` 에 푸시하면 GitHub Actions(`.github/workflows/build.yml`)가 APK 를 만들고 릴리스 `build-N` 으로 올린다.
  versionCode = 실행 번호. 폰의 앱은 시작할 때 최신 릴리스를 확인해 업데이트 배너를 띄운다.
- 서명은 저장소의 `app/debug.keystore` (업데이트가 덮어써지도록 고정). 플레이스토어 출시 때는 비공개 키로 바꿔야 함
- 로컬: `./gradlew :app:assembleDebug` (JDK 17, Android SDK 필요)

## 테스트 (PC)
- `tools/test-server/server.js <버전> <포트>` — node-minecraft-protocol 가짜 서버 (`npm install` 후).
  리소스팩 요청, 강제 텔레포트, 상자 메뉴(13번 칸 = 적용), 인벤토리, 1.21.6+ 대화창 등을 보낸다.
  주의: 대화창(show_dialog)은 node 쪽 라이브러리가 첫 바이트를 가끔 쓰레기 값으로 보내 가끔 실패한다(앱 문제 아님)
- core 는 JVM 에서 `AfkSession(...)` 을 직접 만들어 테스트 서버에 붙여 보는 식으로 검증해 왔다
- 지원 버전 확인됨: 1.8.8, 1.12.2, 1.20.x, 1.21.4, 1.21.8, 26.1 (테스트 서버 기준)

## 해결 못 한 것 / 다음 할 일
- **도스온라인 리소스팩 창**: 실제 클라이언트에는 상자 창이 뜨는데 앱에는 "서버 메뉴 열림" 이 안 온다. 접속 직후 채팅 로그 확인 필요
- **플레이스토어 출시 준비**: 개발자 계정(만 18세 이상 명의), 테스터 12명·14일, 비공개 서명 키, 앱 안 업데이트(REQUEST_INSTALL_PACKAGES) 빼기,
  개인정보처리방침, 포그라운드 서비스 신고, Play 결제로 `Plan.isPremium` 연결 (지금은 "빌드 N" 7번 누르면 켜지는 테스트 토글 → 출시 전에 제거)
- **라이선스**: ViaVersion 은 GPL-3.0 → 앱에 넣어 배포하면 앱 소스도 GPL 로 공개해야 함
- **iOS**: 아이폰은 백그라운드에서 연결을 유지할 수 없어서, 클라우드 서버가 접속을 유지하고 앱은 화면만 보여 주는 구조가 필요
  (core 를 서버 쪽에 두면 GPL 배포 문제도 줄어듦)
- Play 프로텍트가 사이드로드 APK 를 "유해한 앱" 으로 막는 경우가 있음 (앱 안 설치 기능·공개 서명 키 때문으로 추정)
