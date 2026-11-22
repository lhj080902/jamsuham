# 잠수크래프트 iOS 제작 계획서

작성일: 2026-11-20 · 대상: 잠수크래프트(마인크래프트 잠수 앱) iOS 버전

---

## 1. 결론 요약

**아이폰 안에서 마인크래프트 연결을 계속 붙잡아 두는 방법은 없습니다.** 그래서 구조를 아래처럼 바꿉니다.

```
[마인크래프트 서버] ⇄ [잠수크래프트 클라우드 서버 (지금의 core/ 그대로 실행)] ⇄ [아이폰 앱 (화면·알림만)]
                                   │
                                   └─ APNs 푸시 / 라이브 액티비티로 상태 알림
```

- 접속을 유지하는 일은 **클라우드 서버**가 합니다. 지금 `core/`(AfkSession, McConnection, ViaVersion)를 PC용 JVM 에서 그대로 돌립니다.
- 아이폰 앱은 **원격 조종기**입니다. 켜 있을 때는 채팅·지도·인벤을 실시간으로 보여 주고, 꺼져 있을 때는 푸시 알림과 잠금 화면 라이브 액티비티로 상태를 알립니다.
- 부수 효과: ViaVersion(GPL-3.0) 코드가 아이폰 앱에 들어가지 않습니다. 서버에서 실행만 하는 것은 GPL 의 "배포"가 아니므로 라이선스 부담이 크게 줄어듭니다(AGPL 이 아니라 GPL 이기 때문).

---

## 2. iOS 백그라운드 실행 — 조사 결과

| 방법 | 연결 유지 가능? | 앱스토어 심사 | 판단 |
|---|---|---|---|
| 무음 오디오 재생 (`audio` 백그라운드 모드) | 기술적으로는 잠깐 됨 | **가이드라인 2.5.4 로 반려.** 들리는 오디오를 실제로 재생하는 앱(음악·스트리밍)만 허용 | ❌ |
| VoIP 모드 (`voip`, PushKit, CallKit) | 통화 중에만 | 실제 VoIP 기능이 없으면 반려. Apple DTS: "VoIP 앱이 아니면 CallKit 사용 불가" | ❌ |
| 위치 모드 (`location`) | 됨 | 위치가 핵심 기능이 아니면 반려 + 배터리 소모 | ❌ |
| BGTaskScheduler (앱 새로 고침) | 아니요. 약 30초, 실행 시점을 iOS 가 정함 | 허용 | ❌ (잠수 불가) |
| BGProcessingTask | 아니요. 충전·대기 중에만 몇 분 | 허용 | ❌ |
| 사용자가 앱을 강제 종료 | 백그라운드 실행 전부 중단 (iOS 설계) | - | - |
| **클라우드 서버 + APNs 푸시** | 서버가 유지 | 허용 (일반적인 구조) | ✅ **채택** |
| **라이브 액티비티 (ActivityKit)** | 화면 표시만, 푸시로 갱신 | 허용 | ✅ 보조로 채택 |

핵심 근거
- Apple 은 백그라운드 서비스를 "정해진 용도"로만 쓰게 하며(가이드라인 2.5.4), 무음 오디오는 정당한 오디오 재생으로 보지 않는다는 답변과 반려 사례가 다수 있습니다.
- Apple 포럼 답변: iOS 앱에서 백그라운드 소켓을 유지하는 것은 "거의 불가능", 대신 푸시를 쓰라고 권합니다.
- 라이브 액티비티는 **최대 8시간 갱신 + 종료 후 잠금 화면에 4시간 (총 12시간)** 표시됩니다. 서버에서 APNs 로 시작·갱신·종료할 수 있습니다.
  → 8시간이 지나면 새로 시작해야 하므로 "잠수 내내 표시"는 안 되고, 상태 요약용으로 씁니다.

---

## 2-1. 클라우드 잠수 = 프리미엄 기능 (결정 2026-11-22)
- 클라우드 서버가 대신 접속을 유지하는 기능은 **안드로이드에서도 "폰 전원이 꺼져도 잠수 유지"** 가 되므로, 아이폰 지원과 함께 **유료(프리미엄) 기능**으로 만든다.
- 서버 비용이 매달 들기 때문에 구독형이 맞다 (Play 결제 / StoreKit).
- 무료 사용자는 지금처럼 폰에서 직접 잠수하고, 폰이 다시 켜지면 이어서 접속하는 기능(v1.4.1)을 쓴다.
- 프리미엄 판정은 서버가 한다 (앱 안 잠금만으로는 오픈소스라 풀 수 있지만, 서버 기능은 복제할 수 없음).

## 3. 전체 구성

### 3-1. 클라우드 서버 (`server/` 모듈 새로 만들기)
- 언어: Kotlin/JVM (core 를 그대로 의존). 안드로이드용 패치 jar 대신 **원본 ViaVersion jar** 사용 가능.
- 역할
  - 사용자별·마인크래프트 서버별 `AfkSession` 실행·관리 (재접속, 대기, 자동 명령어 = 지금 기능 그대로)
  - 앱과 통신: **WebSocket** (채팅·상태 실시간), **REST** (서버 목록·설정·메뉴 자동 클릭 규칙)
  - **APNs** 발송: 킥당함, 재접속 실패, 서버 꺼짐, 귓속말·키워드 언급, 라이브 액티비티 갱신
  - MS 계정 토큰 보관(암호화 저장)
- 로그인 방식: 서버에서 **디바이스 코드 로그인**(MinecraftAuth 지원) → 앱이 코드만 보여 주고 사용자가 microsoft.com/link 에 입력.
  아이폰 앱이 마이크로소프트 토큰을 직접 다룰 필요가 없어집니다.
- 추천 스택: Ktor 서버(WebSocket 내장) + PostgreSQL(또는 처음엔 SQLite) + 도커 이미지.

### 3-2. 아이폰 앱 (`ios/`)
- **SwiftUI 네이티브** 추천. 이유:
  - 라이브 액티비티·위젯은 어차피 Swift(WidgetKit/ActivityKit)로 만들어야 함
  - 앱이 "화면만" 담당하므로 코드가 작음 (core 공유 필요 없음)
  - 대안: Compose Multiplatform(1.8.0 부터 iOS 정식) — 안드로이드 UI 를 Compose 로 옮길 계획이 있을 때만 이득
- 화면: 지금 안드로이드와 같은 구성 — 서버 목록 / 채팅 / 지도 / 인벤 / 접속자 / 서버 설정
- 통신: `URLSessionWebSocketTask` (앱이 켜져 있을 때만 연결), 꺼지면 푸시로만 받음

### 3-3. 안드로이드 앱 (선택)
- 지금처럼 폰에서 직접 접속하는 모드는 유지하고, 같은 서버를 쓰는 **"클라우드 잠수" 모드**를 나중에 추가 가능
  (폰 배터리·데이터 절약, 기기 바꿔도 잠수 유지).

---

## 4. 단계별 일정 (대략)

| 단계 | 내용 | 완료 기준 | 예상 |
|---|---|---|---|
| 0. 준비 | Apple 개발자 프로그램 가입(연 99달러, 한국 ₩129,000), 테스트용 아이폰, Mac 또는 GitHub Actions macOS 러너 | 개발자 계정 승인 | 1주 |
| 1. 서버 뼈대 | `server/` 모듈, AfkSession 여러 개 동시 실행, WebSocket 으로 채팅 송수신 | 테스트 서버(`tools/test-server`)에 붙어 채팅이 웹소켓으로 나옴 | 2주 |
| 2. 계정·보안 | 앱 회원 인증(Sign in with Apple 또는 자체 토큰), MS 디바이스 코드 로그인, 토큰 암호화 저장 | 실제 MS 계정으로 서버가 접속 | 1~2주 |
| 3. iOS MVP | 서버 목록·추가, 잠수 시작/중지, 채팅 보기·보내기 | TestFlight 로 내 폰에서 동작 | 3주 |
| 4. 알림 | APNs 연동, 알림 종류별 켜기/끄기 | 앱을 끈 상태에서 킥 알림 수신 | 1주 |
| 5. 라이브 액티비티 | 잠금 화면/다이내믹 아일랜드: 서버명·잠수 시간·상태 | 푸시로 갱신 확인 | 1주 |
| 6. 나머지 기능 | 지도(서버가 청크 요약 전송), 인벤, 접속자, 메뉴 자동 클릭 규칙 | 안드로이드와 기능 맞춤 | 3~4주 |
| 7. 결제 | StoreKit 구독 (서버 비용이 매달 들기 때문에 구독형 추천) | 무료 1개 / 구독 시 여러 개 | 1~2주 |
| 8. 심사·출시 | 개인정보처리방침, 심사용 데모 계정·설명, 스크린샷 | 앱스토어 승인 | 1~2주 |

---

## 5. 미리 알아 둘 위험과 대응

1. **데이터센터 IP 차단**: 일부 서버는 VPN/호스팅 IP 를 막는 플러그인을 씁니다. 같은 IP 에서 여러 계정이 들어가면 제한될 수도 있습니다.
   → 1단계에서 자주 쓰는 서버(도스온라인 등)에 클라우드 IP 로 접속되는지 먼저 확인. 필요하면 국내 리전·IP 분산.
2. **같은 계정 동시 접속**: 사용자가 PC 로 접속하면 클라우드 세션이 "다른 곳에서 로그인" 으로 튕깁니다.
   → 이 킥 사유는 재접속하지 말고 멈춘 뒤 알림 보내기.
3. **토큰 보관 책임**: 서버에 MS 토큰이 쌓이므로 유출 시 피해가 큽니다. → 암호화 저장, 최소 권한, 개인정보처리방침에 명시.
4. **서버 비용**: 세션 1개당 메모리·CPU 를 1단계에서 실측한 뒤 무료/구독 한도와 가격을 정합니다.
5. **심사**: 디지털 서비스(클라우드 잠수) 판매는 앱 안 결제(StoreKit) 필수. 심사관이 써 볼 수 있게 테스트 서버·계정 준비.
6. **Mac 필요**: Xcode 는 macOS 전용. Mac 이 없으면 GitHub Actions macOS 러너로 빌드·TestFlight 업로드 가능(분당 요금이 리눅스보다 비쌈). 시뮬레이터 디버깅은 Mac 이 있는 편이 훨씬 편합니다.

---

## 6. 바로 다음 할 일

1. `server/` 모듈 만들어 core 를 JVM 서버에서 여러 세션 실행 (Mac 없이 지금 환경에서 바로 가능)
2. 클라우드 IP 로 실제 자주 가는 서버 접속 테스트
3. 앱 ↔ 서버 메시지 형식(JSON) 정의서 작성 → iOS·안드로이드 공통으로 사용
4. Apple 개발자 계정 가입 진행

---

## 출처

Apple 공식
- [Apple Developer Program 등록 안내 (한국어)](https://developer.apple.com/kr/support/enrollment/)
- [멤버십 비교 (한국어)](https://developer.apple.com/kr/support/compare-memberships/)
- [Configuring background execution modes](https://developer.apple.com/documentation/xcode/configuring-background-execution-modes)
- [Displaying live data with Live Activities (8시간/12시간 제한)](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities)
- [Starting and updating Live Activities with ActivityKit push notifications](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications)
- [WWDC23 — Update Live Activities with push notifications](https://developer.apple.com/videos/play/wwdc2023/10185/)
- [BGProcessingTask](https://developer.apple.com/documentation/backgroundtasks/bgprocessingtask)

Apple 개발자 포럼 (DTS 답변·반려 사례)
- [Audio and VoIP background mode](https://developer.apple.com/forums/thread/786110)
- [Implement webrtc voice calls in the background (CallKit 은 VoIP 앱 전용)](https://developer.apple.com/forums/thread/774784)
- [App Review - Background Audio capabilities not accepted](https://developer.apple.com/forums/thread/91872)
- [Repeatedly rejected for Guideline 2.5.4](https://developer.apple.com/forums/thread/826849)
- [Using Background Mode audio to run the application for few additional minutes](https://developer.apple.com/forums/thread/105443)
- [Regarding Long-Term Socket Connections in iOS Apps](https://developer.apple.com/forums/thread/765841)
- [Keep socket open in background?](https://developer.apple.com/forums/thread/91625)
- [What's the max duration for a live activity?](https://developer.apple.com/forums/thread/797676)
- [How accurate is BGTaskScheduler?](https://developer.apple.com/forums/thread/725675)

기타
- [JetBrains — Compose Multiplatform 1.8.0, iOS 정식 (한국어)](https://blog.jetbrains.com/ko/kotlin/2025/05/compose-multiplatform-1-8-0-ios-compose-multiplatform/)
- [fastlane — GitHub Actions 연동](https://docs.fastlane.tools/best-practices/continuous-integration/github/)
- [The best way to build, test and upload an iOS app without owning a Mac (Apple 포럼)](https://developer.apple.com/forums/thread/815497)
- [GPLv2 blocks VLC from Apple's App Store (Computerworld)](https://www.computerworld.com/article/1347653/gplv2-blocks-vlc-from-apple-s-app-store.html)
