# 잠수크래프트(잠수함) — 작업 인수인계 메모 (Claude Code 용)

마인크래프트 잠수(AFK) 안드로이드 앱 (폰·태블릿). 챗크래프트 같은 채팅 클라이언트.
앱 이름은 **잠수크래프트**(2026-11 에 '잠수함'에서 변경). 저장소·패키지 이름(`kr.jamsuham.app`)과 코드 안 이름은 jamsuham 그대로 (패키지를 바꾸면 기존 설치본이 업데이트되지 않음).
아이콘은 배너의 바닷속 노란 잠수함 그림(`res/drawable-nodpi/ic_launcher_bg.png` 432×432, 적응형 아이콘 배경 레이어, Play 용 512 는 `docs/store/icon-512.png`).
테마(단색) 아이콘·알림 아이콘은 예전 픽셀 잠수함 벡터(`tools/icon/gen.py`). 메인 배너는 디자인별 `assets/banner_*.webp`
사용자는 한국어로 대화하며 **존댓말(~합니다/~입니다)** 로 답한다. 코드 주석·UI 문구도 한국어.

## 구조
- `core/` — 순수 Kotlin/JVM. 마인크래프트 프로토콜 클라이언트 (안드로이드 의존 없음, PC에서 바로 테스트 가능)
  - 앱은 항상 **1.21.4(프로토콜 769)** 로 말하고, ViaVersion 5.12.2 + ViaBackwards 5.12.2 + ViaRewind 4.2.0 이
    서버 버전(1.8 ~ 26.x)으로 변환한다. `core/libs/*-android.jar` 는 `tools/ViaPatcher.java` 로 안드로이드용 패치한 것
    (`tools/update-via.sh` 로 다시 만든다). 패치는 Runtime.version 등 안드로이드에 없는 API 를 `kr.jamsuham.compat` 로 돌림
  - `AfkSession.kt` 접속·재접속·대기(서버 꺼짐 핑 대기, 모장 장애 대기, 백오프), 자동 명령어(메뉴 열려 있으면 미룸)
  - `net/McConnection.kt` 실제 패킷 처리 (네티 파이프라인: timeout → decrypt/encrypt → frame → (de)compress → via-codec → handler).
    1.21.4 패킷 번호는 파일 아래쪽 `PlayIn`/`PlayOut`/`ConfigIn` 상수. 상태 전환은 ACK 보내기 **전에** 바꿔야 함
  - `text/ChatText.kt` 채팅 JSON/NBT → 색 있는 글자 + 클릭 동작(clickEvent/click_event)·설명(hoverEvent), 글 속 http 주소 자동 링크 (리소스팩 전용 글꼴 글자는 숨김), `text/ItemNames.kt` 아이템 한국어 이름,
    `text/DeathMessages.kt` 사망 메시지(death.* 번역 키) → 누구·원인·무기. 체력은 SET_HEALTH + 속성 0x7C(max_health=18) + 엔티티 정보 0x5D(흡수=15번)
  - `pack/PackFonts.kt` 서버 리소스팩의 그림 글자(비트맵 글꼴)만 읽음: zip 끝 목록 + 글꼴 json + png 만 HTTP Range 로 골라 받고(안 되면 전체, 상한),
    pack.mcmeta 오버레이(팩 형식 46 이상용 폴더)도 겹쳐 읽음, json 은 너그럽게(BOM·주석) 읽음. files/packfonts/<sha1>-v5/ 에 캐시(그림 글자 0개면 저장 안 함). 보호된 팩 대응: 목록 위치를 자바처럼 계산·엉터리 크기 무시·망가진 PNG 는 `repairPng`. 묶어 받기(간격 64KB)+동시 6개. 가로 5배 넘는 그림 글자(공지 배경 띠)는 안 그림. ChatText 는 전용 글꼴·개인 사용 영역 글자를 버리지 않고 `ChatSpan.glyphs`(+`font`)로 보관 → 앱 `Glyphs.kt` 가 ImageSpan 으로 그림
  - `world/World.kt` 청크·충돌(이동 물리용), `BlockColors`(미니맵 색), 리소스 `core/src/main/resources/jamsuham/`
- `app/` — 안드로이드 UI (XML 없이 코드로 화면 구성, `Ui.kt` 도우미). minSdk 26, target/compile 36
  - `SessionHub` 서버별 세션 상태(메인 스레드 콜백), `AfkService` 포그라운드 서비스(알림, 화면 꺼져도 유지)
  - `MainActivity` 서버 목록(태블릿은 두 칸), `ChatPanel` 채팅/지도/인벤/접속자/상태 5페이지 + 사망 알림 줄 + 💾 채팅 기록 저장,
    `StatusPanel`(체력·최대 체력·흡수·허기·데이터 사용량·리소스팩 그림 글자 상태(팩 주소·실패 이유)·마지막 사망), `ChatLog`(채팅을 files/chatlogs/<서버>/날짜.txt 로 30일 보관, SAF 로 .txt 내보내기), `MapPanel` 미니맵·이동·웨이포인트,
    `InventoryPanel`, `PlayerPanel`, `ServerEditActivity`(서버별 설정·메뉴 자동 클릭 규칙), `LoginActivity`(MS 로그인 WebView),
    `Store`(서버·웨이포인트·기록, Gson+SharedPreferences),
    `SettingsActivity`(메인 ⚙ 설정: 계정 관리(`AccountsSection`, AccountsActivity 는 서버 설정의 계정 고르기용), 디자인 3종·밝기·언어·글자 크기, 채팅 글자 크기·시간·화면 켜 두기,
    새 서버 기본값(리소스팩·이어서 잠수), 알림 데이터 줄, 저장 공간) — 값은 `AppSettings`(jamsuham_settings), 바뀌면 version 이 올라 BaseActivity 가 화면을 다시 만듦.
    디자인은 `Palette(dark, theme)` 색표 + radius(픽셀=0)·stroke(픽셀 테두리). 영어는 앱 `tr("한국어", "English")`(AppSettings), 엔진은 `kr.jamsuham.core.tr`(`Lang.english`, 앱이 설정에 맞춰 바꿈).
    사망 메시지·킥 사유·아이템 이름도 영어 표(ChatText.Translations.EN, DeathMessages.CAUSES_EN, ItemNames.titleCase). 로그·매칭용 문자열·법률 문서는 한국어 그대로.
    PC 테스트 하네스는 한국어 문구로 확인하므로 기본 Lang.english=false (EN=1 환경 변수로 영어 확인),
    데이터 사용량은 `SessionHub.ratePerHour`(AfkService 가 1분마다 refreshRates) 하나를 알림과 상태 탭이 같이 씀,
    `AccountStore`(MS 토큰은 files/accounts/ 에 Keystore AES-GCM 으로 암호화 저장 `TokenCrypto`, 예전 평문도 읽음. 백업·기기 이전에서 제외 `res/xml/data_extraction_rules.xml`), `Plan`(무료 동시 잠수 1개 / 프리미엄), `Updater`(GitHub 릴리스로 앱 안 업데이트)

## 빌드·배포
- 빌드 종류(productFlavors): **github**(GitHub 릴리스 배포, 앱 안 업데이트·배터리 예외 직접 요청·프리미엄 테스트 토글 있음)
  / **play**(Google Play 용, 위 기능 없음). 판별은 `Flavor.PLAY` (`app/src/<flavor>/kotlin/.../Flavor.kt`),
  github 전용 권한·UpdateReceiver 는 `app/src/github/AndroidManifest.xml`
- `main` 에 푸시하면 GitHub Actions(`.github/workflows/build.yml`)가 GitHub 판 APK 를 릴리스 `build-N` 으로 올리고(main 에서만),
  Play 판 AAB 는 아티팩트로 만든다. versionCode = 실행 번호. 다른 브랜치는 workflow_dispatch 로 빌드만 시험 가능(릴리스 게시 안 함)
- **버전 규칙(주.부.수)**: 보이는 버전은 `gradle.properties` 의 `jamsuham.version` 을 직접 고친다. 변경을 올릴 때 함께 올릴 것
  - 주(맨 앞): 엔진·구조 변경 / 부(가운데): 큰 기능·대규모 패치(수는 0) / 수(맨 뒤): 버그 수정·작은 변경
  - versionCode 는 GitHub Actions 실행 번호(자동). 릴리스 제목 "잠수함 vX.Y.Z (빌드 #N)" 에서 앱이 버전을 읽어 업데이트 배너에 표시
- 서명: GitHub 판은 저장소의 `app/debug.keystore`(공개). Play 판 release 는 Secrets 업로드 키(`docs/RELEASE_KEY.md`), 없으면 디버그 키
- 출시 문서: `docs/PLAYSTORE_PLAN.md`(전체 과정), `docs/store-listing.md`(Play Console 입력 초안), `app/src/main/assets/legal/privacy-policy.md`, 앱 "정보" 화면 `AppInfo.kt`
- 로컬: `./gradlew :app:assembleGithubDebug` / `:app:bundlePlayRelease` (JDK 17, Android SDK 필요)

## 테스트 (PC)
- `tools/test-server/server.js <버전> <포트>` — node-minecraft-protocol 가짜 서버 (`npm install` 후).
  리소스팩 요청, 강제 텔레포트, 상자 메뉴(13번 칸 = 적용), 인벤토리, 1.21.6+ 대화창 등을 보낸다.
  주의: 대화창(show_dialog)은 node 쪽 라이브러리가 첫 바이트를 가끔 쓰레기 값으로 보내 가끔 실패한다(앱 문제 아님)
  시나리오: `full`(이미 접속 킥 → 소켓 끊김 → 밴), `stay`(유지), `dupe`(중복 로그인 킥 → 앱이 멈춰야 함), `authkick`(정품 인증 실패 킥 → 2번 재시도 후 멈춤), `vitals`(1.21.4: 체력 31.5/최대 40/흡수 4 → 좀비에게 사망), `click`(누를 수 있는 채팅: [수락] 명령어 버튼 + 설명, 링크), `pack`(그림 글자 채팅, `PACK_URL=` 로 시험용 팩 서버 지정), `walk`(1.21.4 평평한 땅 청크만 보냄 → 접속 걷기 확인, 받은 위치를 로그로. `npm install --no-save prismarine-chunk` 불필요, 손으로 만든 청크)
- core 는 JVM 에서 `AfkSession(...)` 을 직접 만들어 테스트 서버에 붙여 보는 식으로 검증해 왔다
- 클라우드(Claude Code 웹) 환경은 dl.google.com 이 막혀 안드로이드 플러그인을 못 받는다 → 루트 빌드 대신
  임시 폴더에 core 만 include 한 Gradle 설정을 만들어 컴파일·테스트하고, app 코드는 Maven Central 의
  `org.robolectric:android-all:16-robolectric-13921718`(API 36) 을 compileOnly 로 두고 Kotlin 컴파일만 확인한다 (APK 빌드는 GitHub Actions)
- 지원 버전 확인됨: 1.8.8, 1.12.2, 1.20.x, 1.21.4, 1.21.8, 26.1 (테스트 서버 기준)

## 재접속 판단 규칙 (AfkSession)
- 중복 로그인(다른 곳에서 같은 계정 접속, `duplicate_login`) → 멈춤 / 프록시 "already connected"(내 새 접속이 거절됨) → 기다렸다 재접속
- 정품 인증 실패 킥 → 토큰 새로 받아 재시도, 3번 연속이면 멈춤 / 모장 429 → 60·120·300·600초 대기
- 접속할 때마다(서버 설정 '들어가면 한두 걸음', 기본 켜짐) 발밑 청크가 오고 메뉴가 닫히면 북쪽 0.35초 → 남쪽 0.35초 걸어 제자리 (`scheduleJoinWalk`)
- 일반 킥이 8번 넘게 이어지면 최소 3분 대기 (수백 번 로그인해 429 를 부르지 않도록). 연속 횟수는 1분 이상 버텨야 초기화

## 해결 못 한 것 / 다음 할 일
- (해결) 도스온라인: 리소스팩 창 메뉴 열림·자동 클릭, 보호된 팩의 칭호 그림 표시까지 확인됨 (v1.5.11~1.5.13)
- **출시 전 가장 큰 남은 일**: 정품 로그인이 마인크래프트 공식 런처의 client ID(`MsaConstants.JAVA_TITLE_ID`, AccountStore.msaConfig)를 쓴다 →
  Play 출시 전 자체 Azure 앱 + Mojang 의 Minecraft API 승인을 받아 교체해야 함. 코드는 준비됨: 저장소 변수 MS_CLIENT_ID 를 등록하면
  매니페스트 meta-data 로 들어가 자체 ID(consumers 엔드포인트)로 로그인. 절차 docs/MS_LOGIN.md, 업로드 키 docs/RELEASE_KEY.md.
  2026-11-22: Azure 앱(client ID 9f60141c-29ac-4d71-8fa1-f89e32a085e7) 등록·Mojang 신청 제출, 승인 대기 중
- **플레이스토어 출시 준비**: 개발자 계정(만 18세 이상 명의), 테스터 12명·14일, 비공개 서명 키, 앱 안 업데이트(REQUEST_INSTALL_PACKAGES) 빼기,
  포그라운드 서비스 신고(문구는 store-listing.md), Play 결제로 `Plan.isPremium` 연결 (GitHub 판만 버전 글씨 7번 테스트 토글, Play 판은 항상 무료),
  자체 MS client ID(Mojang 승인) — Play 판 빌드 분리·정보 화면·개인정보처리방침은 완료
- **라이선스**: ViaVersion 은 GPL-3.0 → 앱에 넣어 배포하면 앱 소스도 GPL 로 공개해야 함
- **iOS**: 아이폰은 백그라운드에서 연결을 유지할 수 없어서, 클라우드 서버가 접속을 유지하고 앱은 화면만 보여 주는 구조가 필요
  (core 를 서버 쪽에 두면 GPL 배포 문제도 줄어듦)
- Play 프로텍트가 사이드로드 APK 를 "유해한 앱" 으로 막는 경우가 있음 (앱 안 설치 기능·공개 서명 키 때문으로 추정)
