# 잠수함 — 마인크래프트 잠수용 안드로이드 앱

챗크래프트처럼 마인크래프트 서버에 채팅 전용으로 접속해서 **잠수(AFK) 시간을 쌓는** 안드로이드 앱입니다.
폰·태블릿 모두 지원하며, 튕겼을 때 자동으로 다시 접속하는 기능을 중심으로 만들었습니다.

## 주요 기능

| 기능 | 설명 |
|---|---|
| 서버 버전 자동 감지 | 서버 목록 핑으로 버전을 알아내 그 버전으로 접속합니다. 실패하면 목록에서 직접 고를 수 있습니다. |
| 넓은 버전 지원 | 서버 **1.8 ~ 26.3** (ViaVersion 5.12.2 / ViaBackwards 5.12.2 / ViaRewind 4.2.0 기준) |
| 자동 재접속 | 튕겼을 때만 다시 접속합니다. 직접 "연결 끊기"를 누르거나 전원이 꺼진 경우에는 다시 접속하지 않습니다. |
| "이미 프록시에 접속 중" 대응 | 이 킥을 받으면 프록시가 예전 접속을 정리할 때까지 15초 → 25초 → … 점점 길게 기다렸다가 다시 시도합니다. |
| 리소스팩 우회 | 서버가 리소스팩을 요청하면 실제로 받지 않고 "수락 → 다운로드 완료 → 적용 성공"으로 응답합니다(실제 기기처럼 약간 시간차를 둠). |
| 접속 후 자동 명령어 | 접속·재접속할 때마다 `/잠수`, `/server afk` 같은 명령어를 자동으로 보냅니다. 재접속하면 로비로 돌아가는 서버용입니다. |
| 백그라운드 유지 | 포그라운드 서비스 + CPU/와이파이 잠금으로 화면이 꺼져도 접속을 유지합니다. 네트워크가 다시 잡히면 바로 재접속합니다. |
| 정품 / 비정품 | 마이크로소프트 계정(디바이스 코드 로그인) 또는 비정품 닉네임으로 접속합니다. |
| 기타 | 사망 시 자동 리스폰, SRV 레코드(포트 없는 도메인) 지원, 채팅 보기/보내기, 서명 채팅 확인 응답(장시간 잠수 시 킥 방지) |

## 빌드하기

필요한 것: **JDK 17 이상**, **Android SDK 36** (Android Studio 를 설치하면 같이 깔립니다)

### 방법 A — Android Studio
1. Android Studio 에서 이 폴더(`Jamsuham`)를 엽니다.
2. Gradle Sync 가 끝나면 폰을 USB 로 연결하고 ▶ Run 을 누릅니다.
3. APK 파일만 필요하면: `Build > Build App Bundle(s) / APK(s) > Build APK(s)`

### 방법 B — 명령줄
```bash
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```

### 방법 C — GitHub Actions (PC 없이)
이 폴더를 GitHub 저장소에 올리면 `.github/workflows/build.yml` 이 자동으로 APK 를 만듭니다.
저장소의 **Actions** 탭 → 최근 실행 → `jamsuham-debug-apk` 를 내려받으면 됩니다.

## 사용법
1. **서버 주소** 입력 (예: `play.example.kr`, `1.2.3.4:25565`)
2. **버전**은 "자동 감지 (권장)" 그대로 두기
3. **계정**: 정품이면 "로그인" → 브라우저에서 코드 입력 → 마인크래프트를 산 마이크로소프트 계정으로 로그인
4. (선택) **접속 후 자동 실행**에 잠수 서버 이동 명령어 입력
5. **배터리 최적화 예외로 설정** 버튼을 한 번 눌러 두기 (화면 꺼짐 중 끊김 방지에 중요)
6. **잠수 시작**

## 재접속 규칙

| 상황 | 동작 |
|---|---|
| 네트워크 끊김 / 서버 응답 없음(30초) | 3초 → 5초 → 10초 → 20초 → 30초 → 60초 간격으로 재시도. 네트워크가 다시 잡히면 2초 뒤 바로 재시도 |
| "이미 프록시에 접속 중" 류의 킥 | 15초 → 25초 → 35초 → 45초 → 60초 간격으로 재시도 |
| 그 밖의 킥 (서버 재시작 등) | 10초 → 20초 → 30초 → 60초 (설정에서 끌 수 있음) |
| 차단·화이트리스트·인증 실패로 보이는 킥 | 멈춤 (계속 두드리지 않음) |
| 사용자가 "연결 끊기" | 멈춤 |
| 폰 전원 꺼짐 / 재부팅 | 다시 켜도 자동으로 접속하지 않음 |
| 시스템이 메모리 부족으로 앱을 정리 | 같은 부팅 안이면 서비스가 다시 살아나 이어서 접속 |

1분 이상 접속을 유지하면 대기 시간이 처음 단계로 돌아갑니다.

## 구조

```
core/   순수 Kotlin(JVM) 모듈 — 접속 엔진. 안드로이드 없이 PC 에서도 돌아갑니다.
  net/McConnection.kt   로그인·암호화·압축, keep-alive, 텔레포트 확인, 리소스팩 응답, 채팅
  AfkSession.kt         주소 해석 → 버전 감지 → 접속 → 끊김 이유별 재접속
  via/ViaSetup.kt       ViaVersion 을 "클라이언트 쪽 번역기"로 초기화
  net/StatusPinger.kt   서버 목록 핑(버전 감지)
  net/AddressResolver.kt SRV 레코드 조회
  libs/                 안드로이드용으로 고친 ViaVersion/ViaBackwards/ViaRewind jar
app/    안드로이드 앱 — 화면(MainActivity), 포그라운드 서비스(AfkService), 계정 저장(AccountStore)
tools/  ViaPatcher.java, update-via.sh, test-server/
```

앱은 항상 **1.21.4(프로토콜 769)** 로 말하고, 서버 버전이 다르면 파이프라인 중간의 ViaVersion 이
패킷을 그 서버 버전에 맞게 바꿉니다. 그래서 앱 코드를 고치지 않아도 Via 만 올리면 새 버전을 지원합니다.

## 새 마인크래프트 버전이 나오면

ViaVersion 쪽에서 새 버전을 지원하는 릴리스가 나오면 번호만 바꿔 실행하고 앱을 다시 빌드하세요.
```bash
./tools/update-via.sh 5.12.2 5.12.2 4.2.0   # ViaVersion ViaBackwards ViaRewind
```
안드로이드에는 `Runtime.version()` 같은 일부 Java API 가 없어서 Via 원본 jar 는 그대로 쓰면 바로 멈춥니다.
`tools/ViaPatcher.java` 가 그런 호출을 `core/src/main/java/kr/jamsuham/compat` 의 대체 코드로 바꿔 줍니다.

## 테스트한 내용
- PC 에서 core 모듈을 실제로 실행해 테스트 서버(node-minecraft-protocol)에 접속:
  **1.8.8 / 1.12.2 / 1.20.4 / 1.21.4 / 1.21.11 / 26.1** 모두 버전 감지 → 접속 → 채팅 송수신 →
  리소스팩 응답 → 강제 텔레포트 확인 → 자동 명령어까지 정상
- 재접속 시나리오: "already connected" 킥 → 15초 뒤 재접속, 소켓 강제 끊김 → 재접속, 밴 킥 → 멈춤,
  사용자 끊기 → 재접속 안 함, 네트워크 복구 시 대기 단축, 75초 연속 접속 유지
- 구형 안드로이드(8~13) 경로를 흉내 낸 상태로도 위 테스트 통과
- (참고) PC 테스트는 ViaProxy 에 들어 있는 Netty 4.2 로 돌렸고, 앱은 안드로이드에서 더 오래 검증된 Netty 4.1 을 씁니다. 쓰는 API 는 같습니다.
- 앱 코드는 `android.jar`(API 36) 기준으로 컴파일 확인, 사용하는 API 를 API 26(안드로이드 8) 기준으로 검사

**직접 확인하지 못한 것** (빌드 환경에서 Google/Maven 저장소에 접근할 수 없었음):
Gradle 로 APK 를 만드는 과정, 실제 폰에서의 실행, 정품 로그인, 26.3 실서버. 처음 빌드나 실행에서
오류가 나면 오류 메시지를 그대로 알려 주세요.

## 알아 둘 점
- **iOS 는 지원하지 않습니다.** iOS 는 앱이 백그라운드로 가면 몇 분 안에 네트워크를 끊어서 잠수용으로 쓸 수 없습니다.
- 서명 채팅을 강제하는 서버(`enforce-secure-profile=true`)에서는 일반 채팅이 막힐 수 있습니다. **명령어(/…)는 됩니다.**
- 리소스팩 우회나 잠수 자체를 금지하는 서버도 있으니 서버 규칙을 확인하세요.
- 마이크로소프트 로그인 정보는 앱 전용 저장소에만 저장됩니다.
- **라이선스**: ViaVersion 계열이 GPLv3 이므로, 이 앱을 다른 사람에게 배포하려면 앱 소스도 GPLv3 로 공개해야 합니다. 개인적으로 쓰는 데는 상관없습니다.

## 사용한 오픈소스
- ViaVersion / ViaBackwards / ViaRewind — https://github.com/ViaVersion (GPLv3)
- MinecraftAuth — https://github.com/RaphiMC/MinecraftAuth (LGPLv3)
- Netty — https://netty.io (Apache 2.0)
- Gson, Guava — Google (Apache 2.0)
- 패킷 번호·구조 확인: ViaVersion 소스의 패킷 정의, minecraft-data(https://github.com/PrismarineJS/minecraft-data)
