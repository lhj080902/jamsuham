# 잠수크래프트(잠수함) — 작업 인수인계 메모 (Claude Code 용)

마인크래프트 잠수(AFK) 안드로이드 앱 (폰·태블릿). 챗크래프트 같은 채팅 클라이언트.
앱 이름은 **잠수크래프트**(2026-11 에 '잠수함'에서 변경). 저장소·패키지 이름(`kr.jamsuham.app`)과 코드 안 이름은 jamsuham 그대로 (패키지를 바꾸면 기존 설치본이 업데이트되지 않음).
아이콘은 배너의 바닷속 노란 잠수함 그림(`res/drawable-nodpi/ic_launcher_bg.png` 432×432, 적응형 아이콘 배경 레이어, Play 용 512 는 `docs/store/icon-512.png`).
테마(단색) 아이콘·알림 아이콘은 예전 픽셀 잠수함 벡터(`tools/icon/gen.py`). 메인 배너는 디자인별 `assets/banner_*.webp`
사용자는 한국어로 대화하며 **존댓말(~합니다/~입니다)** 로 답한다. 코드 주석·UI 문구도 한국어.

사람이 읽는 전체 정리(저장소·등록해 둔 값·만든 것·남은 일·CLI 에서 이어가기)는 **`docs/HANDOFF.md`**. 2026-11-27 부터 사용자는 CLI 에서 이어서 작업한다.

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
    pack.mcmeta 오버레이(팩 형식 46 이상용 폴더)도 겹쳐 읽음, json 은 너그럽게(BOM·주석) 읽음. files/packfonts/<sha1>-v6/ 에 캐시(그림 글자 0개면 저장 안 함). 보호된 팩 대응: 목록 위치를 자바처럼 계산·엉터리 크기 무시·망가진 PNG 는 `repairPng`. 묶어 받기(간격 64KB)+동시 6개. 배경 띠 건너뛰기: 뒤로 당기는 빈칸(space 공급자 음수 이동 `FontSet.spaces` 또는 **높이가 음수인 1×1 그림 글자** — 도스온라인 방식, U+F80x h-130 등)이 이어지면 그 합이 바로 앞 그림 폭의 절반 이상일 때 그 그림을 `Glyphs.backgroundMarks` 로 건너뜀(음수 높이 그림은 안 그림), 예비로 꽉 찬 5배 긴 띠만. 채팅 줄 길게 누르기 = 그림 글자 진단 창(복사). ChatText 는 전용 글꼴·개인 사용 영역 글자를 버리지 않고 `ChatSpan.glyphs`(+`font`)로 보관 → 앱 `Glyphs.kt` 가 ImageSpan 으로 그림
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

## 클라우드 잠수 (cloud/, v2.0.0~)
- 구조: **`cloud/`** = 서버 컴퓨터에서 도는 JVM 프로그램(core 의 `AfkSession` 을 그대로 씀) + 웹 화면(`cloud/src/main/resources/web/` index.html·app.js·style.css, 빌드 도구 없는 순수 JS).
  웹·앱은 REST + SSE(`/api/events`)로 같은 잠수를 보고 조작한다. 웹 서버는 자바 기본 `com.sun.net.httpserver` (추가 라이브러리 없음 — Jetty 는 이 환경에서 메이븐 429 로 못 받았음). 설치·운영 문서 `docs/CLOUD.md`
- 주고받는 JSON 은 **`core/cloud/Wire.kt`** 하나로 서버가 만들고 앱이 읽는다(웹 app.js 는 같은 키를 손으로 맞춤 — 키를 바꾸면 함께 고칠 것, `Wire.API` 번호).
  앱 쪽 통신은 `core/cloud/CloudClient.kt`(`CloudStream` 재연결 포함) → PC 하네스 `cloud` 모드로 실제 서버에 붙여 검증한다 (앱은 이 환경에서 못 돌리므로)
- 서버 파일: `Http.kt`(라우팅·인증: 웹=HttpOnly 쿠키+`X-Jamsu` 헤더, 앱=Bearer / SSE / 정적 파일), `Sessions.kt`(계정별 `AccountHub`·`SessionBox`, 시작·끊기·재시작 시 이어 접속, `Presence`),
  `MsAuth.kt`(웹=기기 코드 로그인, 앱=로그인 정보 넘겨받기 `login/transfer`, 접속 때 토큰 갱신), `DataStore.kt`(JSON 파일·`Premium` = data/premium.txt 허용 목록), `NetGuard.kt`(내부망 주소 차단), `Util.kt`(`Vault` AES-GCM)
- **중복 접속 규칙** (계정 UUID + `Wire.addressKey(주소)` 기준): 클라우드 잠수 중 → 폰 직접 잠수 차단(앱 `BaseActivity.startServer` 가 `CloudHub.elsewhere` 확인) /
  폰 직접 잠수 중(`AfkService` 가 1분마다 `CloudHub.reportLocal`, TTL 150초) → 클라우드 시작 409 `in_use` / PC 게임이 들어와 밀려남(`SessionListener.onDuplicateLogin`) → 30분 동안 "other" 표시, 다시 시작하면 확인(`force`).
  PC 게임 자체는 막을 수 없음(FastPack 이 접속 전에 클라우드에 묻게 하면 가능 — 미구현)
- **같은 IP 문제**: 프록시로 계정마다 IP 를 바꾸는 우회는 만들지 않기로 함(서버 규칙 회피). 대신 `maxAccountsPerTarget`(기본 3)·`targetLimits` 로 서버별 계정 수를 제한하고, 운영진 허용을 받은 서버만 늘린다
- 앱: `CloudHub`(연결 기록 prefs `jamsuham_cloud`, 계정별 SSE — 앱이 앞에 있을 때만, 이벤트 → `SessionHub.remote*`), `CloudSection`(설정 카드), `ServerEntry.cloud`.
  `SessionHub.activeIds()/anyActive` 는 **폰 잠수만**(서비스·알림·이어서 잠수·무료 동시 제한), `isActive` 는 둘 다. 앱의 클라우드 잠수 화면에서는 지도·이동·아이템 사용 불가(미구현)
- **웹 이동 (v2.0.1)**: `POST /api/servers/{id}/move {east, south, jump}` → `SessionManager.move` 가 `setStick`·`setMovement(16)`. 입력은 1.5초만 유효(`MOVE_HOLD_MS`) → 화면이 누르는 동안 0.45초마다 다시 보냄,
  끊기면 `moveTick`(0.25초)이 멈춤. 위치는 `pos` 이벤트(`Wire.Pos`, 소수 1자리). 웹은 이동 탭(조이스틱·점프·WASD), 메뉴 창의 "＋ 자동 클릭에 추가"(`addRule`). 앱 쪽은 `CloudClient.move` 만 있고 화면에는 안 붙임
- 시험: 시험 MC 서버 2개(`stay`, `dupe`) + 클라우드 서버(`JAVA_OPTS=-Djamsu.devLogin=true`, 설정 `premiumMode=open allowPrivateTargets=true maxAccountsPerTarget=2`)를 띄우고
  `harness cloud http://127.0.0.1:<포트> <stay포트> <dupe포트> [walk포트]` (walk 까지 주면 38개 항목), 서버 없이 `harness wire`(JSON 왕복 15개)·`harness chatlimit`(악의적 채팅 한도 10개).
  웹은 `node tools/test-server/cloud/web-test.js <스크린샷 폴더> [walk포트]` (Playwright, 주소 127.0.0.1:18787 고정, 닉네임 시험 로그인 — `-Djamsu.devLogin=true` 일 때만 보임. walk 까지 23개 항목).
  폰·태블릿 터치는 `node tools/test-server/cloud/web-touch-test.js <스크린샷 폴더> <walk포트>` (Galaxy S9+·Galaxy Tab S4 흉내, 조이스틱+점프 동시 터치. 2026-11-27 폰·태블릿 통과 확인.
  계정 수 제한에 안 걸리게 끝에 계정을 지우도록 고친 뒤로는 다시 돌려 보지 않았음). 실제 폰 브라우저에서는 아직 안 봄 — 주소창 때문에 아래쪽 입력창이 가려지면 `#main` 높이를 `100dvh` 로
- 보안 검토(2026-11-27, 별도 에이전트)에서 고친 것: 채팅 해석 한도(`ChatText` Budget: 노드 2만·글자 6만·깊이 48, 400KB 넘는 JSON 은 해석 안 함), 메뉴 칸 256·접속자 2000·청크 상한(`World.maxChunks`, 클라우드는 200),
  줄 24KB·이벤트 600KB 상한, 접속자 IP 는 설정한 헤더 하나만(`clientIpHeader`, IPv6 는 /64 로 묶음), 기기 코드 로그인은 시작한 브라우저에 쿠키로 묶고 결과 확인을 POST 로,
  안 쓰는 로그인 요청 45초 뒤 정리, 실시간 연결 깨우기·강제 닫기·전체 450개 상한, 로그인 정보 넘겨받을 때 갱신 토큰부터 전체 재발급 + 접속 때 UUID 대조, 새 기기 알림·기기별 로그아웃,
  프리미엄 종료 7일 뒤 정보 삭제, NetGuard(자기 주소·같은 IPv6 대역·주소 글자 검사·실제 IP 기준 자리 수).
  남은 약점(문서에 적음): 기기 코드 방식 자체의 피싱 가능성(인증 코드+PKCE 로 바꾸려면 Azure 앱에 웹 리디렉션 주소 등록 필요), 닉네임으로 준 프리미엄은 닉네임을 넘겨받은 사람에게 넘어감, 윈도우에서 data 폴더 권한
  빌드: `./gradlew -p tools/corebuild :cloud:installDist` → `cloud/build/install/jamsu-cloud/bin/jamsu-cloud <설정파일>`. 주의: 서버가 켜진 채 installDist 하면 jar 가 바뀌어 응답이 깨진다(끄고 빌드)
- 실제 마이크로소프트 로그인(기기 코드·넘겨받기)은 이 환경에서 시험 못 함(MS 서버 막힘) → 사용자가 서버를 띄운 뒤 확인 필요
- CI: `build.yml` 이 `:cloud:distZip` 을 릴리스에 `jamsu-cloud.zip` 으로 같이 올림. 앱 기본 주소는 저장소 변수 `CLOUD_URL`
- **v1.7.1 로그인 버그(수정됨 v2.0.0)**: `JavaAuthManager.create(client).login(msaToken)` 에 `.msaApplicationConfig(msaConfig)` 를 안 넘겨 자체 client ID 토큰이 공식 런처 설정으로 처리됨 → 새 계정 추가 실패 가능. 지금은 넘김 (클라우드 `MsAuth` 도 동일)

## PC 모드 (tools/fastpack-mod)
- FastPack 0.3.0: ⓪ 게임을 다시 켜도 — `FastPackState` 가 마지막 서버 팩(UUID·경로·서버 주소)을 config/fastpack-lastpack.properties 에 저장,
  `DownloadedPackSource.<init>` TAIL 에서 그 팩을 packSource 에 넣어 첫 로딩에 포함 → 같은 서버 접속 시 건너뜀 (config/fastpack.properties keepAcrossRestart=false 로 끔)
- ① 재접속 때 같은 서버 팩이면 다시 적용 안 함 — `DownloadedPackSourceMixin` 이 `startReload` 에서
  나갈 때 팩 내리기·같은 팩 다시 올리기를 건너뛰고 `onReloadSuccess` 로 성공 처리, 다른 서버(주소 비교)·싱글 월드면 정리.
  루나 클라이언트(ichor)에서 도스온라인 재접속 확인됨. 루나는 SLF4J 로그가 안 보여서 System.out 으로 찍음
- ② 처음 받을 때: 서버 리소스팩을 HTTP Range 로 6개 연결·4MB 조각으로 나눠 받고
  바닐라와 같은 해시 검사·같은 경로로 넘김(팩 바이트는 그대로). 실패하면 바닐라 다운로드로. `HttpUtil.downloadFile` 에 Mixin(공식 매핑).
- 빌드는 GitHub Actions `.github/workflows/mod.yml` 만 (이 클라우드 환경은 Fabric·Mojang 서버가 막힘). 산출물 `fastpack-mod`

## 빌드·배포
- 빌드 종류(productFlavors): **github**(GitHub 릴리스 배포, 앱 안 업데이트·배터리 예외 직접 요청·프리미엄 테스트 토글 있음)
  / **play**(Google Play 용, 위 기능 없음). 판별은 `Flavor.PLAY` (`app/src/<flavor>/kotlin/.../Flavor.kt`),
  github 전용 권한·UpdateReceiver 는 `app/src/github/AndroidManifest.xml`
- `main` 에 푸시하면 GitHub Actions(`.github/workflows/build.yml`)가 GitHub 판 APK 와 클라우드 서버(`jamsu-cloud.zip`)를 릴리스 `build-N` 으로 올리고(main 에서만),
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
- 클라우드(Claude Code 웹) 환경은 dl.google.com 이 막혀 안드로이드 플러그인을 못 받는다 → 루트 빌드 대신 **`tools/corebuild/`**
  (core 만 include 한 별도 Gradle 설정)을 쓴다. app 코드는 Maven Central 의 `org.robolectric:android-all:16-robolectric-13921718`(API 36)
  을 compileOnly 로 두고 Kotlin 컴파일만 확인 (APK 빌드는 GitHub Actions)
  - 앱 컴파일 확인: `./gradlew -p tools/corebuild :appcheck:compileKotlin -Pflavor=github` (그리고 `-Pflavor=play`). 출력이 없으면 성공
  - 시험 프로그램: `./gradlew -p tools/corebuild :harness:installDist` → `tools/corebuild/harness/build/install/harness/bin/harness <모드> [포트]`
    모드: dupe, authkick, vitals, click, walk, stick, miny, world, deathparse, packtest, packnet, overlay, report, prune, spaces, png, msa …
    (테스트 서버를 먼저 띄움: `node tools/test-server/server.js 1.21.4 <포트> <시나리오>`, 팩은 `node tools/test-server/pack/serve.js <포트> <zip>`,
    시험 팩은 `cd tools/test-server/pack && python3 make.py`. `EN=1` 이면 영어 모드, 임시 폴더는 `HARNESS_TMP`)
- 지원 버전 확인됨: 1.8.8, 1.12.2, 1.20.x, 1.21.4, 1.21.8, 26.1 (테스트 서버 기준)

## 계정
- **정품(마이크로소프트) 계정만** 쓴다. 비정품 닉네임 접속은 v1.7.1(2026-11-23)에서 뺐다 (사용자 요청, Mojang 승인 자체 client ID 로 전환과 함께).
  예전에 저장한 비정품 계정은 `AccountStore.list()` 가 걸러서 지운다(`Account.microsoft=false`). core 의 `JoinCredentials.offline` 은 PC 시험 하네스용으로만 남김

## 재접속 판단 규칙 (AfkSession)
- 중복 로그인(다른 곳에서 같은 계정 접속, `duplicate_login`) → 멈춤 / 프록시 "already connected"(내 새 접속이 거절됨) → 기다렸다 재접속
- 정품 인증 실패 킥 → 토큰 새로 받아 재시도, 3번 연속이면 멈춤 / 모장 429 → 60·120·300·600초 대기
- 접속할 때마다(서버 설정 '들어가면 한두 걸음', 기본 켜짐) 발밑 청크가 오고 메뉴가 닫히면 북쪽 0.35초 → 남쪽 0.35초 걸어 제자리 (`scheduleJoinWalk`)
- 일반 킥이 8번 넘게 이어지면 최소 3분 대기 (수백 번 로그인해 429 를 부르지 않도록). 연속 횟수는 1분 이상 버텨야 초기화

## 다음 세션에서 이어서 할 일 (2026-11-27 기준, 앱·클라우드 v2.0.1 / FastPack 0.3.0)
- **클라우드 잠수 실사용 확인 (사용자)**: 서버 컴퓨터에 jamsu-cloud.zip 설치 → msClientId 입력 → 웹 기기 코드 로그인 / 앱 "연결"(v2.0.0 에서 새로 로그인한 계정) / 클라우드에서 시작·채팅·끊기 /
  폰 잠수 중 클라우드 시작 차단·반대 방향 차단이 실제 폰에서 되는지. HTTPS 공개(Cloudflare 터널 또는 Caddy), 저장소 변수 CLOUD_URL
- 클라우드 다음 작업 후보: 앱의 클라우드 화면에 이동 붙이기(서버 API 는 있음), 웹·앱의 클라우드 지도, 아이템 사용, 웹의 리소스팩 그림 글자, 앱이 뒤에 있을 때 사망·메뉴 푸시 알림, 결제 연동(지금은 premium.txt 수동), 운영자용 관리 화면,
  FastPack 이 PC 접속 전에 클라우드에 묻기, 도스온라인 운영진에게 보낼 공유 IP 허용 요청 글
- v2.0.0 새 로그인(자체 client ID) 확인: 계정 추가 → 주소가 login.microsoftonline.com/consumers → 로그인·서버 접속 (v1.7.1 은 버그로 실패했을 수 있음)
- (완료 2026-11-23) **업로드 서명 키**: 사용자가 upload.jks 를 만들고 Secrets 4개 등록 → 시험 빌드에서 `jamsuham-play-aab`(서명됨) 확인
- **FastPack 다음 작업 (사용자 요청, 다음 세션 첫 작업)**:
  ① 게임 안 설정 창 — Mod Menu 연동(루나 모드 목록에 modmenu 있음, maven.terraformersmc.com 의 com.terraformersmc:modmenu 1.21.4용을 compileOnly,
  fabric.mod.json entrypoints "modmenu", suggests). Screen 에 버튼: 게임 다시 켜도 유지 / 재접속 때 다시 적용 안 함 / 나눠 받기 / 채팅 알림 켜기·끄기,
  "남겨 둔 팩 지금 정리"(DownloadedPackSource 인스턴스를 <init> 에서 정적 참조로 잡아 dropKept 호출). 값은 config/fastpack.properties (FastPackState 에 키 추가, 믹스인에서 확인)
  ② 로그를 나만 보이는 채팅으로 — gui.getChat().addMessage(Component.literal("[FastPack] …")), 팩 처리는 접속 설정 단계라 바로 띄우면 안 보임 →
  큐에 모아 두었다가 Minecraft.tick(MinecraftMixin) 에서 player != null 일 때 내보내기. System.out 로그는 그대로 유지
  ③ 0.3.0(게임 다시 켜도 유지) 시험 결과 확인
- **(승인됨 2026-11-23) Mojang Minecraft API**: 승인 메일 받음 → 사용자가 저장소 Variables 에 MS_CLIENT_ID 등록 후 새 빌드에서
  계정 추가 시 로그인 주소가 login.microsoftonline.com/consumers 인지, 로그인·서버 접속이 되는지 확인 (실패 시 403 Invalid app registration 여부)
- **접속자 전체 목록 버튼**: 사용자가 도스온라인에서 `/list`·`/glist` 결과를 보내 주면 그 형식으로 파싱해 보여 주는 버튼 만들기 (서버가 Tab 목록엔 일부만 보냄)
- Play 출시: 그래픽 1024×500(배너로 만들어 줄 수 있음)·스크린샷·테스터 12명 14일·앱 콘텐츠 신고(docs/store-listing*.md)
- 나중: 클라우드 24시간 잠수(프리미엄, 아는 형 서버) — 같은 IP 다계정 제한·모장 429·토큰 보관 책임 설명함, iOS, 후원·Play 결제
- 학교 와이파이 차단 우회는 도와주지 않기로 함 (요청서 초안·데이터 절약 모드로 대신)

## 해결 못 한 것 / 다음 할 일
- (해결) 도스온라인: 리소스팩 창 메뉴 열림·자동 클릭, 보호된 팩의 칭호 그림 표시까지 확인됨 (v1.5.11~1.5.13)
- **출시 전 가장 큰 남은 일**: 정품 로그인이 마인크래프트 공식 런처의 client ID(`MsaConstants.JAVA_TITLE_ID`, AccountStore.msaConfig)를 쓴다 →
  Play 출시 전 자체 Azure 앱 + Mojang 의 Minecraft API 승인을 받아 교체해야 함. 코드는 준비됨: 저장소 변수 MS_CLIENT_ID 를 등록하면
  매니페스트 meta-data 로 들어가 자체 ID(consumers 엔드포인트)로 로그인. 절차 docs/MS_LOGIN.md, 업로드 키 docs/RELEASE_KEY.md.
  2026-11-22: Azure 앱(client ID 9f60141c-29ac-4d71-8fa1-f89e32a085e7) 등록·Mojang 신청 제출 → 2026-11-23 승인(allow list 등록)
- **플레이스토어 출시 준비**: 개발자 계정(만 18세 이상 명의), 테스터 12명·14일, 비공개 서명 키, 앱 안 업데이트(REQUEST_INSTALL_PACKAGES) 빼기,
  포그라운드 서비스 신고(문구는 store-listing.md), Play 결제로 `Plan.isPremium` 연결 (GitHub 판만 버전 글씨 7번 테스트 토글, Play 판은 항상 무료),
  자체 MS client ID(Mojang 승인) — Play 판 빌드 분리·정보 화면·개인정보처리방침은 완료
- **라이선스**: ViaVersion 은 GPL-3.0 → 앱에 넣어 배포하면 앱 소스도 GPL 로 공개해야 함
- **iOS**: 아이폰은 백그라운드에서 연결을 유지할 수 없어서, 클라우드 서버가 접속을 유지하고 앱은 화면만 보여 주는 구조가 필요
  (core 를 서버 쪽에 두면 GPL 배포 문제도 줄어듦)
- Play 프로텍트가 사이드로드 APK 를 "유해한 앱" 으로 막는 경우가 있음 (앱 안 설치 기능·공개 서명 키 때문으로 추정)
