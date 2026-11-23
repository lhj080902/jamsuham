# 정품 로그인 자체 client ID 만들기 · Mojang 승인 받기

지금 앱의 마이크로소프트(정품) 로그인은 **마인크래프트 공식 런처의 client ID** 를 빌려 쓴다 (`AccountStore.kt`).
GitHub 배포에서는 동작하지만, Play 스토어에 올리려면 **우리 앱 이름으로 된 client ID** 를 만들고 Mojang 의 승인을 받아야 한다.
코드는 이미 준비되어 있어서, 승인받은 ID 를 GitHub 에 등록하기만 하면 다음 빌드부터 자동으로 바뀐다.

> **진행 상황 (2026-11-22)**: Azure 앱 등록 완료 — 이름 Jamsucraft, client ID `9f60141c-29ac-4d71-8fa1-f89e32a085e7`,
> 테넌트 `befd22ca-7836-40d4-9a88-440c1d6ad0d4`, 개인 계정만. Mojang 승인 신청 제출함 → **2026-11-23 승인됨** (이제 ③ MS_CLIENT_ID 등록)

> 순서: ① Azure 에 앱 등록 → ② Mojang 에 승인 신청 → ③ 승인 메일을 받으면 GitHub 에 ID 등록
> 승인 전에 ③ 을 하면 로그인이 마지막 단계에서 실패한다(403 "Invalid app registration"). 반드시 승인 뒤에 등록할 것.

## ① Azure 에 앱 등록 (10분)
0. **디렉터리(테넌트) 먼저 만들기** — 2024년 6월부터 개인 계정만으로는 앱을 등록할 수 없다
   ("디렉터리 외부에서 애플리케이션을 만드는 기능은 더 이상 사용되지 않습니다" 안내가 뜸).
   시크릿 창에서 https://azure.microsoft.com/free 로 같은 개인 계정의 **무료 Azure 계정**을 만들면 새 디렉터리가 생기고 그 관리자가 된다.
   (휴대폰·카드 본인 확인을 요구할 수 있음. 앱 등록 자체는 무료)
1. https://portal.azure.com 에 그 계정으로 로그인 (오른쪽 위 계정 메뉴에서 새로 만든 디렉터리가 선택돼 있는지 확인)
2. 검색창에 **"앱 등록"(App registrations)** → **새 등록**
   - 이름: `Jamsucraft` (또는 `잠수크래프트`)
   - 지원되는 계정 유형: **개인 Microsoft 계정만** (Personal Microsoft accounts only)
   - 리디렉션 URI: 플랫폼 **"퍼블릭 클라이언트/네이티브(모바일 및 데스크톱)"**, 주소
     `https://login.microsoftonline.com/consumers/oauth2/nativeclient`
3. 등록하면 나오는 **애플리케이션(클라이언트) ID** (예: `1a2b3c4d-....`) 를 적어 둔다 — 이것이 client ID
4. 왼쪽 **인증(Authentication)** → 아래 **"퍼블릭 클라이언트 흐름 허용"** 을 **예** 로 → 저장
5. API 권한 화면에서 `XboxLive.signin` 이 목록에 안 보여도 정상이다 (승인 전에는 안 보인다는 사례가 많음). 따로 추가하지 않아도 된다.

## ② Mojang 에 Minecraft API 승인 신청
- 신청서: https://aka.ms/mce-reviewappid (Mojang 지원 문서 "Java Edition Game Service API Review or Application Process" 에서 안내하는 양식)
- 적을 내용 (예시)
  - Client ID: ①-3 의 ID
  - 앱 이름: 잠수크래프트 (Jamsucraft)
  - 설명: 안드로이드용 마인크래프트 자바 에디션 채팅·잠수(AFK) 클라이언트. 사용자가 고른 서버에 정품 계정으로 접속하고 채팅을 주고받는다. 오픈소스(GPL-3.0).
  - 소스: https://github.com/lhj080902/jamsuham
  - 개인정보처리방침: https://github.com/lhj080902/jamsuham/blob/main/app/src/main/assets/legal/privacy-policy.md
  - 연락 이메일: limhj080902@naver.com
- 승인까지 며칠~몇 주 걸릴 수 있다. 승인되면 메일이 온다.

## ③ 승인 후 GitHub 에 등록
GitHub 저장소 → **Settings → Secrets and variables → Actions → Variables 탭 → New repository variable**

| 이름 | 값 |
|---|---|
| `MS_CLIENT_ID` | ①-3 의 client ID |

(Secrets 탭에 같은 이름으로 넣어도 된다. client ID 는 비밀번호가 아니라서 Variables 로 충분)

그 뒤 `main` 에 올라가는 빌드부터 GitHub 판·Play 판 모두 자체 ID 로 로그인한다.
- 이미 저장된 계정은 저장할 때의 ID 로 계속 갱신되므로 그대로 쓸 수 있다. 문제가 있으면 계정을 지우고 다시 로그인.
- 확인: 새로 받은 앱에서 계정 추가 → 로그인 화면 주소가 `login.microsoftonline.com/consumers` 로 시작하면 자체 ID 로 로그인 중.

## 참고
- 코드: `AccountStore.msaConfig` — 매니페스트 meta-data `kr.jamsuham.MS_CLIENT_ID` 가 UUID 형식이면 자체 ID(엔드포인트 consumers, 범위 `XboxLive.signin offline_access`), 아니면 공식 런처 ID.
- 빌드: `app/build.gradle.kts` 의 `manifestPlaceholders["msClientId"]` ← 환경 변수 `JAMSUHAM_MS_CLIENT_ID` ← 워크플로의 `vars.MS_CLIENT_ID || secrets.MS_CLIENT_ID`.
