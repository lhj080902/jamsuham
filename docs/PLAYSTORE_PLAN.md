# 잠수함 플레이스토어 출시 — 전체 과정

작성일: 2026-11-20 · 대상: 안드로이드 앱 Google Play 출시

> 법률 자문이 아닙니다. 공식 문서와 커뮤니티 자료를 정리한 것이므로, 돈이 오가는 결정(유료화, 사업자 등록 등) 전에는 원문과 전문가 확인을 권합니다.
> 정책은 자주 바뀌니, 각 단계를 진행할 때 출처 링크로 최신 내용을 다시 확인하세요.

---

## 전체 흐름과 예상 기간

```
0. 먼저 정할 것 (명의 · 라이선스 · MS 로그인 승인 · 유료화)    ← 오래 걸리고 결과가 불확실
1. 개발자 계정 만들기 (25달러, 신원 확인)                        1~수일
2. 앱 코드 출시용으로 정리 (Play 판 빌드, AAB, 서명 등)           1~2주
3. Play Console 에 앱 만들기 + 스토어 등록정보                    1~2일
4. 앱 콘텐츠 신고 (개인정보처리방침, 데이터 보안, 등급 등)         1~3일
5. 비공개 테스트: 테스터 12명 × 14일 연속                         최소 14일 (+ 테스트판 심사 수일)
6. 프로덕션 접근 신청 → 심사                                      약 7일
7. 정식 출시 → 출시 후 관리
```
→ 모두 순조로우면 **약 4~6주**입니다. 단, 0번의 마이크로소프트 로그인 승인은 기간과 결과를 알 수 없습니다.

---

## 0단계. 먼저 정할 것 (출시를 막을 수 있음)

### 0-1. 누구 명의로 할지
- Play Console 은 개인·조직 계정 모두 **만 18세 이상**만 등록할 수 있고, 계정 소유권은 **나중에 다른 사람에게 넘길 수 없습니다**.
- 2023년 11월 13일 이후 만든 **개인 계정**은 정식 출시 전에 "테스터 12명 × 14일" 비공개 테스트를 거쳐야 합니다. 조직(사업자) 계정은 이 요건이 없다고 알려져 있습니다.

### 0-2. 마이크로소프트(정품) 로그인 승인 — 가장 먼저 신청
- 지금 코드(`AccountStore.kt`)는 `MsaConstants.JAVA_TITLE_ID`, 즉 **마인크래프트 공식 런처의 앱 ID** 를 빌려 로그인합니다. 공개 배포 앱이 남의 앱 ID 를 쓰는 것은 문제가 될 수 있습니다.
- 정식 방법은 이렇습니다.
  1. Azure 에 **자체 앱을 등록**해 client ID 를 받습니다.
  2. Mojang 에 **Minecraft API 사용 승인 신청서**를 냅니다(이메일, 앱 이름, client ID, tenant ID, EULA 동의).
  3. 승인 전에는 `api.minecraftservices.com` 이 **403** 을 돌려줍니다.
- 2026년 2월에 개인 개발자의 비상업 런처가 거절됐다는 글이 있어, **승인 여부는 불확실**합니다.
- 승인되면 `AccountStore.msaConfig` 의 client ID·리다이렉트 주소만 바꾸면 됩니다.
- 승인이 안 되면 **비정품(오프라인) 계정 전용**으로 출시하거나, 정품 로그인을 뺀 채 출시하는 수밖에 없습니다.

### 0-3. 오픈소스 라이선스 방향
| 라이브러리 | 라이선스 | 의무 |
|---|---|---|
| ViaVersion / ViaBackwards / ViaRewind | **GPL-3.0** | 앱을 배포하면 **앱 전체 소스를 GPL-3.0 으로 공개**하고, 소스 위치를 알려야 함 |
| MinecraftAuth | LGPL-3.0 | 앱을 GPL 로 공개하면 함께 충족 |
| Netty, Gson, Guava | Apache-2.0 | 앱 안에 라이선스 고지 필요 |

- **A. 앱 소스 GPL-3.0 공개** (가장 간단): GitHub 저장소를 공개하고, 스토어 설명과 앱 안 "오픈소스 라이선스" 화면에 링크를 넣습니다. 유료 판매도 할 수 있지만, 누구나 소스를 받아 빌드할 수 있습니다.
- **B. ViaVersion 을 서버로 빼기** (iOS 계획서의 클라우드 구조): 앱 소스는 공개하지 않아도 되지만, 서버 비용과 작업량이 큽니다.
- **C. 직접 버전 변환 구현**: 현실적으로 매우 어렵습니다.

### 0-4. 유료화 여부
- **무료** 출시가 가장 단순합니다. 사업자 등록이나 결제 연동이 필요 없습니다.
- **프리미엄(구독·인앱 상품)** 을 팔려면 다음이 필요합니다.
  - Google Play 결제 연동(디지털 기능 판매는 Play 결제 필수)
  - 판매자 프로필(정산 계좌)
  - 한국 사업자 등록·통신판매업 신고 확인(9단계)
  - Mojang 상업 이용 가이드라인 확인(8단계)
- → **처음에는 무료로 내고, 나중에 프리미엄을 붙이는 것**을 추천합니다.

---

## 1단계. 개발자 계정 만들기
1. Google 계정으로 [Play Console](https://play.google.com/console) 가입 → 계정 유형(개인/조직)을 고릅니다.
2. **등록비 25달러**를 한 번 결제합니다. 신용·체크카드만 되고, 선불카드는 안 된다는 안내가 있습니다.
3. **신원 확인**: 이름·주소·연락처 등을 입력하고 신분 확인 절차를 거칩니다. 조직 계정은 D-U-N-S 번호가 필요합니다.
4. **개발자 프로필**: 스토어에 공개될 개발자 이름과 연락 이메일을 정합니다. 유료화하면 주소 등이 공개될 수 있다는 후기가 있습니다.
- 참고: **2027년부터는 Play 밖(GitHub 등)에서 배포하는 앱도 개발자 등록이 필요**해집니다. 2026년 9월 브라질·인도네시아·싱가포르·태국부터 시작해 2027년에 전 세계로 넓어질 예정입니다.
  - Play Console 신원 확인을 마치면 이 등록도 함께 해결됩니다.
  - 지금처럼 GitHub 로 APK 를 나눠 주는 방식을 계속하더라도 언젠가는 필요합니다.

## 2단계. 앱 코드를 출시용으로 정리 (제가 할 수 있는 부분)
| 할 일 | 이유 |
|---|---|
| **Play 판 / GitHub 판 빌드 분리** (flavor) | Play 앱은 스스로 업데이트할 수 없음 → Play 판에서 Updater·`REQUEST_INSTALL_PACKAGES`·UpdateReceiver 제거 |
| **AAB(App Bundle)로 빌드** | 새 앱은 APK 대신 AAB 로 올려야 함 (2021.8~) |
| **타깃 API 36** | 지금 36 이라 이미 충족 (2026.8.31 부터 새 앱·업데이트는 API 36 이상 필수) |
| **비공개 업로드 키로 서명** + Play 앱 서명 | 지금은 공개된 debug 키로 서명 중. GitHub Actions Secrets 에 업로드 키 보관 |
| **배터리 최적화 예외 방식 변경** | 직접 요청(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)은 허용 용도가 제한됨 → 설정 화면으로 안내만 |
| **프리미엄 테스트 토글 제거** (버전 글씨 7번) | 출시판에 남기면 안 됨. 유료화한다면 Play 결제 연결 |
| **오픈소스 라이선스 화면** | GPL·LGPL·Apache 고지 + 소스 저장소 링크 |
| **비공식 고지 문구** | 앱 안·스토어 설명에 "마인크래프트 공식 제품이 아님" (8단계) |
| **자체 MS client ID** | 0-2 승인 후 교체 |
| **개인정보처리방침 링크** | 앱 안(설정이나 정보 화면)에서도 열 수 있게 |

- 지금 폰에 설치된 앱(공개 debug 키로 서명)은 Play 판으로 **덮어 설치할 수 없습니다**. 한 번 지우고 다시 설치해야 하고, 로그인·서버 목록도 초기화됩니다.

## 3단계. Play Console 에 앱 만들기 + 스토어 등록정보
- **앱 만들기**: 앱 이름, 기본 언어(한국어), 앱/게임 구분(마인크래프트 보조 도구이므로 "앱"의 "도구" 계열이 무난), 무료/유료를 정합니다.
  - **무료로 낸 앱은 나중에 유료 앱으로 바꿀 수 없습니다.** 인앱 결제는 나중에 추가할 수 있습니다.
- **그래픽**
  - 앱 아이콘: 512×512 PNG(32비트, 투명 가능, 1MB 이하)
  - 그래픽 이미지(필수): 1024×500 JPEG 또는 24비트 PNG(투명 불가)
  - 휴대전화 스크린샷: 2~8장, JPEG 또는 24비트 PNG. 짧은 변 320px 이상, 긴 변 3840px 이하, 긴 변이 짧은 변의 2.3배 이하
  - 태블릿 스크린샷(선택): 태블릿 두 칸 화면이 있으니 넣으면 좋음
  - 순위·가격 같은 배지나 글자를 넣으면 안 되고, 다른 제품처럼 보이면 안 됩니다. 마인크래프트 로고·캐릭터도 넣지 않습니다.
- **설명**: 짧은 설명과 자세한 설명. 비공식 고지 문구와 "서버 규칙에서 잠수를 금지할 수 있음" 안내를 넣습니다.

## 4단계. 앱 콘텐츠 신고 (Play Console → 앱 콘텐츠)
빠뜨리면 출시 전 검사에서 걸립니다.

| 항목 | 우리 앱 답변 방향 |
|---|---|
| **개인정보처리방침 URL** | 필수. 아래 "개인정보처리방침에 적을 것" 참고. GitHub Pages 등에 올림 |
| **광고** | 광고 없음 |
| **앱 액세스(로그인 안내)** | 심사자가 써 볼 수 있게 안내: "비정품 닉네임 계정 + 공개 테스트 서버 주소"로 로그인 없이 확인하는 방법 적기 (심사자가 마인크래프트 정품이 없을 수 있음) |
| **콘텐츠 등급(IARC 설문)** | 다른 사용자와 채팅 가능 → "사용자 간 상호작용" 체크 |
| **타깃 연령층** | 13세 이상(또는 18세 이상)으로 설정. 아동을 포함하면 가족 정책이 붙어 훨씬 까다로움 |
| **데이터 보안(Data safety)** | 개발자 서버로 수집·공유하는 데이터 없음. 로그인 토큰·채팅 기록은 기기 안에만 저장(암호화). 전송 구간 암호화 여부 등 |
| **포그라운드 서비스 신고** | `specialUse` 유형. 잠수 시작 → 알림이 뜨고 화면을 꺼도 유지되는 **시연 영상 링크**(유튜브 일부 공개 등) + 설명 |
| 뉴스·금융·건강·정부 앱 | 해당 없음 |

### 개인정보처리방침에 적을 것
- 한국 개인정보보호법상 개인정보를 처리하면 처리방침을 정해 **공개해야 하고**, 안 하면 **과태료 대상**입니다. 항목은 개인정보보호위원회 「개인정보 처리방침 작성지침」(2024.4 개정)을 따르는 것이 좋습니다.
- 우리 앱 기준으로 적을 내용
  - **기기에만 저장**: 마이크로소프트·마인크래프트 로그인 토큰(Keystore 암호화), 서버 목록, 채팅 기록(30일 뒤 자동 삭제), 웨이포인트
  - **연결하는 곳**: 마이크로소프트·Xbox·Mojang 인증 서버, 사용자가 입력한 마인크래프트 서버, (GitHub 판만) GitHub 업데이트 확인
  - **개발자 서버로 보내는 개인정보 없음**, 제3자 제공 없음
  - 파기: 앱 삭제나 계정 삭제 시 기기에서 삭제
  - 문의처(이메일)

## 5단계. 테스트
1. **내부 테스트**(선택, 최대 100명, 심사가 빠름): 나와 친구 몇 명이 먼저 설치해 봅니다.
2. **비공개 테스트**(필수): 테스터 **12명 이상이 14일 연속 참여**해야 합니다.
   - 테스터는 이메일 목록이나 구글 그룹으로 초대하고, 테스트 링크에서 "테스터 되기"를 누른 뒤 설치합니다.
   - 중간에 빠지는 사람이 생기면 14일이 다시 시작될 수 있습니다. **공개 테스트는 인정되지 않습니다.**
   - 실제로 써 보지 않으면 거절될 수 있다는 후기가 있습니다. 테스터들이 앱을 실제로 쓰고 의견을 남기게 하세요.
   - 비공개 테스트판 자체도 심사가 있습니다(보통 수일).
3. 테스트 중에는 Play Console 의 **출시 전 보고서**(자동 기기 테스트)와 비정상 종료 보고서로 문제를 고칩니다.

## 6단계. 프로덕션(정식 출시) 접근 신청
- 조건을 채우면 Play Console 대시보드에서 신청합니다. 자동으로 열리지 않습니다.
- 질문은 세 부분입니다: 비공개 테스트에 대해 / 앱에 대해 / 출시 준비 상태.
  - 테스터를 어떻게 모았는지, 받은 의견과 고친 점, 앱의 대상과 가치를 구체적으로 적습니다.
- 심사는 보통 **7일 안팎**입니다. 거절되면 이유를 보고 고친 뒤 다시 신청합니다.

## 7단계. 정식 출시와 출시 후 관리
- **단계적 출시**(예: 10% → 50% → 100%)로 문제를 먼저 확인할 수 있습니다.
- 출시 후 해야 할 것
  - **매년 타깃 API 올리기**: 최신 안드로이드 출시 후 1년 안의 API 를 타깃해야 함
  - 정책 위반 메일 대응, 리뷰 답변, 비정상 종료 보고서 확인
  - 업데이트할 때마다 같은 업로드 키로 서명(키를 잃어버리면 Play Console 에서 재설정 요청)
- GitHub 판을 계속 배포한다면 2027년 개발자 등록 요건(1단계 참고)을 챙겨야 합니다.

## 8단계. 마인크래프트 상표·저작물
- 앱 이름·아이콘에 **"Minecraft" 이름, 로고, 캐릭터(크리퍼 등)를 쓰지 않습니다.** 공식 제품처럼 보이면 안 됩니다. "잠수함"이라는 이름은 괜찮습니다.
- 설명에 "마인크래프트 자바 에디션 서버용"처럼 **설명 목적으로 쓰는 정도**가 일반적입니다. 아래와 같은 비공식 고지 문구를 넣는 것이 안전합니다.
  - 예: `NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.`
  - 2차 자료 기준이므로 [Minecraft Usage Guidelines](https://minecraft.net/usage-guidelines) 원문을 확인하세요.
- **유료화**한다면 Mojang 상업 이용 가이드라인을 확인해야 합니다. 브랜드를 쓰는 상업적 이용에는 제한이 있습니다.
- 앱 안의 마인크래프트 유래 데이터
  - `items-ko.json.gz`: 공식 한국어 번역에서 아이템 이름을 뽑은 것
  - `colors-1.21.4.bin`, `collision-1.21.4.bin`: 블록 색·충돌 정보
  - 위험은 낮다고 보지만 확인이 필요합니다. 스크린샷에 게임 화면을 크게 넣는 것도 주의하세요.
- 스킨은 Mojang 서버에서 받아 보여 주기만 하므로 문제없습니다.

## 9단계. 한국 법·세금 (유료화할 때 특히)
- 앱 안에서 디지털 상품(프리미엄)을 직접 팔면 **사업자 등록(응용 소프트웨어 개발·공급업 등)과 통신판매업 신고**가 필요하다는 안내가 많습니다. 광고 수익만 있으면 대상이 아니라고 합니다.
- 공식 확인이 안 된 내용이므로 **세무서·정부24·Play Console 안내**로 확인하세요.
- 개인정보처리방침 공개 의무는 **무료여도 적용**됩니다(4단계).
- **국가장학금 영향**: 사업자등록 자체로 못 받는다는 규정은 찾지 못했습니다. 다만 국가장학금 지원구간은 학생 본인 + 부모님의 소득·재산(근로·사업 소득 합산)으로 정해지므로, 사업·후원 **수익이 커지면 구간이 올라갈 수 있습니다**.
  - 학생 본인 사업소득의 정확한 환산 방식은 공식 자료에서 확인하지 못했습니다.
  - → 사업자등록 전에 **한국장학재단 콜센터(1599-2000)** 에 문의하세요.

## 10단계. 후원·수익 (나중에 진행 — 지금은 계획만)
**핵심 규칙**: Play 앱 **안에서는** Play 결제가 아닌 결제 수단(토스·Buy Me a Coffee 등)으로 유도하면 안 됩니다. **앱 밖**(웹사이트·GitHub)에서 알리는 것은 자유롭다고 Google 이 안내합니다. "후원"만 따로 다룬 정책 문구는 찾지 못했으니, Play 판 앱 안에는 외부 후원 링크를 넣지 않는 것이 안전합니다.

| 방법 | 정책 | 수수료 | 메모 |
|---|---|---|---|
| ① 앱 안 "후원하기" (Play 결제, 여러 번 살 수 있는 상품) | 안전 | 구글 수수료 | Play 판매자 등록 필요 → 사업자·통신판매업 확인 대상(9단계) |
| ② GitHub Sponsors | 앱 밖 | 개인 후원 0% | 한국 지원 지역. GPL 로 소스를 공개하면 잘 맞음 |
| ③ 토스 송금·투네이션·Buy Me a Coffee | 앱 밖에만 | 서비스별 | README·웹사이트에 걸기. **Play 판 앱 안 금지**, GitHub 판 앱 안은 괜찮음 |
| ④ 프리미엄 (동시 잠수 여러 개) | 안전 | 구글 수수료 | `Plan` 에 이미 설계됨. 가장 안정적인 수익 |
| ⑤ 광고 | 가능 | - | 비추천 (잠수 앱은 화면을 거의 안 봄, 데이터 보안 신고 부담) |

- **추천 조합**: Play 판은 ①(+ 나중에 ④), 앱 밖은 ② + ③, GitHub 판 앱에는 ③ 링크
- **판매자 상태(EU 디지털 서비스법)**: 가입할 때는 수익 활동이 없으니 **비판매자**로 선언합니다.
  - 후원·프리미엄을 붙이는 **업데이트 직전에** 둘 중 하나를 고릅니다.
    - **판매자로 변경**: 주소·전화번호가 EU 스토어 페이지에 공개됩니다.
    - **EEA·영국 배포 제외**: 이 경우 비판매자로 둘 수 있다는 설명이 있지만, Play Console 안내로 확인해야 합니다.
  - 수익이 있는데 비판매자로 두면 수정 요구나 조치를 받을 수 있습니다.
  - 참고: [판매자 상태 선언 (Play Console 도움말)](https://support.google.com/googleplay/android-developer/answer/14659200?hl=en-GB) · [Google Play Trader Status 정리 (fdaytalk)](https://www.fdaytalk.com/google-play-trader-status/)
- **구현 메모**: Play 결제 라이브러리는 Google Maven 에서 받으므로 클라우드 환경에서는 컴파일 확인이 안 되고, GitHub Actions 로 빌드 확인. 실제 결제 시험은 Play Console 에 올린 뒤 가능
- **세금**: 정기적으로 받는 후원금은 종합소득세 신고 대상이라는 국세청 관계자 설명이 있음 (계좌 후원 포함). 금액이 쌓이면 홈택스·세무사로 확인

## 11단계. 권리 보호 — 저작권 등록·상표 등록 (선택)
> **결정 (2026-11-22): 보류.** 비용 대비 효과가 낮다고 판단해 저작권·상표 등록은 하지 않습니다. 같은 이름·아이콘의 복제판이 나오면 Play 사칭 신고와 상표 정책(TRADEMARKS.md)으로 대응합니다. 아래는 나중에 다시 검토할 때를 위한 정리입니다.

> 저작권은 만드는 순간 자동으로 생기며, 등록은 "권리를 생기게 하는 것"이 아니라 **증거·공시**입니다. GPL 로 공개해도 저작권은 그대로 개발자에게 있습니다.

### 등록하면 좋은 점 (한국)
- 등록된 저작자는 **저작자로 추정**되고, 창작 후 1년 안에 등록한 창작일도 추정됩니다.
- 등록된 권리를 침해한 사람은 **과실이 있는 것으로 추정**됩니다 (저작권법 제125조 제4항).
- **법정손해배상**(손해액을 증명하지 않아도 저작물마다 1천만 원 이하, 영리·고의면 5천만 원 이하)은 **침해 전에 등록**돼 있어야 청구할 수 있습니다 (제125조의2).
- 오픈소스에서 실제로 쓰이는 경우: 누가 소스를 가져가 **GPL 을 어기고**(소스 비공개·출처 삭제) 판매할 때 대응할 근거가 됩니다. GPL 대로 쓰는 포크는 막을 수 없습니다.

### ⚠️ 이 앱의 주의점: AI 로 작성한 코드
- 문화체육관광부·한국저작권위원회 「생성형 AI 활용 저작물의 저작권 등록 안내서」(2025.6): 등록은 **인간의 창작적 기여 부분만** 가능하고, AI 가 만든 부분은 등록 대상이 아닙니다.
- 이 저장소의 코드는 상당 부분을 AI(Claude)와 함께 작성했습니다 (커밋 기록에도 남아 있음).
  - 기능 설계·선택·구성, 직접 고친 부분 등 인간의 기여를 어떻게 인정받을지는 불확실합니다.
- → **신청 전에 한국저작권위원회 등록 상담(1800-5455)에 "AI 를 활용해 작성한 프로그램"이라고 밝히고 상담**하세요. 사실과 다르게 신청하면 등록이 취소되거나 문제가 될 수 있습니다.
- 등록 대상에서 **제외할 것**: ViaVersion·ViaBackwards·ViaRewind·MinecraftAuth·Netty 등 외부 라이브러리, 마인크래프트 유래 데이터(아이템 이름·블록 색 등).

### 한국저작권위원회 프로그램 저작권 등록
- 신청: 인터넷(cros.or.kr)·방문·우편. 처리 기간 약 7일
- 준비: 등록신청서(프로그램), 저작물명세서, 복제물(소스 코드 일부 등), 등록면허세 영수증, 수수료
- 수수료·등록면허세: 수만 원 수준이라는 안내가 있지만 **프로그램 금액은 확인하지 못함** → 위원회에 확인
- 상담: 1800-5455 (2번)

### 상표 등록 (이름 "잠수함"·아이콘) — 브랜드 보호에는 이게 더 중요
- 복제판이 같은 이름·아이콘을 쓰는 것을 막는 건 저작권이 아니라 **상표**입니다. 상표 정책(TRADEMARKS.md)에 법적 힘을 더해 줍니다.
- 특허청(특허로)에 출원합니다. 앱이면 보통 **제9류(소프트웨어)**, 필요하면 **제42류(서비스)** 를 함께 출원합니다.
- 비용(2025.1 시행 징수규칙 기준, 2차 자료)
  - 출원료: 전자출원 1류당 약 4만 6천~5만 2천 원
  - 등록료: 10년분 1류당 약 20만 1천 원
- 출원 전에 **KIPRIS 에서 같은·비슷한 상표가 이미 있는지 검색**하세요. "잠수함"은 흔한 단어라 이미 등록된 상표가 있을 수 있습니다.
- 상표는 AI 작성 여부와 상관없습니다.

### 미국 저작권청 (선택)
- 전자 신청 수수료: 단독 저작자 $45, 일반 $65 (**2027.1.5 수수료 변경 예정**)
- 소스 코드 앞 25쪽·뒤 25쪽 제출
- 미국 시장이 주 대상이 아니면 우선순위가 낮습니다. AI 작성 부분은 미국도 등록 대상이 아닙니다.

### 추천 순서
1. **상표 출원** ("잠수함" 9류): KIPRIS 검색 → 출원. 브랜드 보호 효과가 가장 직접적입니다.
2. **저작권 등록**: 위원회 상담으로 AI 작성 코드의 등록 가능 범위를 먼저 확인한 뒤 결정합니다.
3. 미국 등록: 해외 사용자가 많아지면 검토합니다.

참고
- [한국저작권위원회 저작권 등록 안내](https://www.copyright.or.kr/business/registration/index.do) · [문화체육관광부 — 저작권 등록 제도](https://www.mcst.go.kr/kor/s_policy/copyright/knowledge/know12.jsp)
- [저작권법 제125조의2 법정손해배상 (해설)](https://korea.legal/wiki/%ec%a0%80%ec%9e%91%ea%b6%8c%eb%b2%95-%ec%a0%9c125%ec%a1%b0%ec%9d%982/)
- [생성형 AI 활용 저작물 등록 안내서 주요 내용 (법률신문)](https://www.lawtimes.co.kr/news/articleView.html?idxno=209882) · [AI 로 만든 결과물, 인간 기여 부분만 저작권 인정 (다음 뉴스, 2026)](https://v.daum.net/v/20260421144511956)
- [특허청](https://www.kipo.go.kr/ko/) · [상표 출원 비용 정리 (헬프미 법률사무소)](https://www.help-me.kr/blog/article/%EC%83%81%ED%91%9C%EC%B6%9C%EC%9B%90%EB%B9%84%EC%9A%A9%EC%96%BC%EB%A7%88%EB%82%98%EB%93%A4%EA%B9%8C%EC%9A%94/)
- [미국 저작권청 수수료](https://www.copyright.gov/about/fees.html) · [미국 저작권청 Circular 61 (프로그램 등록·소스 제출)](https://www.copyright.gov/circs/circ61.pdf)

---

## 체크리스트 요약
- [ ] 명의 결정 (만 18세 이상, 이전 불가)
- [ ] Mojang Minecraft API 승인 신청 (자체 Azure 앱)
- [ ] 라이선스 방향 결정 (GPL 소스 공개 / 서버 구조)
- [ ] 유료화 여부 결정 (처음엔 무료 추천)
- [ ] Play Console 가입 + 25달러 + 신원 확인
- [ ] 코드: Play 판 분리, AAB, 비공개 서명, 배터리 예외 방식, 토글 제거, 라이선스 화면, 비공식 고지, client ID 교체
- [ ] 개인정보처리방침 페이지 공개
- [ ] 스토어 등록정보 (아이콘 512, 그래픽 1024×500, 스크린샷 2장 이상, 설명)
- [ ] 앱 콘텐츠 신고 (광고·앱 액세스·등급·타깃 연령·데이터 보안·포그라운드 서비스 영상)
- [ ] 테스터 12명 모집 → 비공개 테스트 14일 연속
- [ ] 프로덕션 접근 신청 → 심사
- [ ] 단계적 출시 → 출시 후 관리 (매년 타깃 API)
- [ ] (나중에) 후원: GitHub Sponsors 개설, 앱 밖 후원 링크, Play 판 "후원하기"(Play 결제), 프리미엄
- [ ] (후원 업데이트 전) 판매자 상태를 판매자로 변경 또는 EEA·영국 배포 제외
- [x] ~~(선택) 저작권·상표 등록~~ → 보류 (복제판은 Play 사칭 신고 + 상표 정책으로 대응)

---

## 출처
Google Play 공식
- [신규 개인 개발자 계정 앱 테스트 요건 (12명·14일)](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Play Console 시작하기 (만 18세 이상)](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en)
- [개발자 계정 유형 선택](https://support.google.com/googleplay/android-developer/answer/13634885?hl=en)
- [개발자 계정 생성에 필요한 정보](https://support.google.com/googleplay/android-developer/answer/13628312?hl=en)
- [앱 만들기 및 설정](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)
- [스토어 그래픽 자산 (아이콘·그래픽 이미지·스크린샷)](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)
- [앱 심사 준비 (앱 콘텐츠 신고)](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en)
- [타깃 연령층·앱 콘텐츠 관리](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en)
- [콘텐츠 등급 요건](https://support.google.com/googleplay/android-developer/answer/9859655?hl=en)
- [출시 전 검사](https://support.google.com/googleplay/android-developer/answer/14807773)
- [REQUEST_INSTALL_PACKAGES 권한 정책](https://support.google.com/googleplay/android-developer/answer/12085295)
- [기기 및 네트워크 악용 정책 (자체 업데이트 금지)](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)
- [포그라운드 서비스 신고 요건](https://support.google.com/googleplay/android-developer/answer/13392821)
- [타깃 API 수준 요건](https://developer.android.com/google/play/requirements/target-sdk) · [타깃 API 정책](https://support.google.com/googleplay/android-developer/answer/11917020)
- [Android App Bundle 의무화 (Android Developers Blog, 2021)](https://android-developers.googleblog.com/2021/06/the-future-of-android-app-bundles-is.html)
- [Android 개발자 인증 전체 확대 (Android Developers Blog, 2026.3)](https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html) · [Android 개발자 인증 이해하기](https://support.google.com/android-developer-console/answer/16561738)
- [Android 개발자 인증 안내 (한국어)](https://developer.android.com/developer-verification/guides?hl=ko)
- [구글 플레이 결제 정책 FAQ (한국어)](https://developers-kr.googleblog.com/2020/09/commerce-update-faqs.html)

마이크로소프트·마인크래프트
- [Minecraft Usage Guidelines](https://minecraft.net/usage-guidelines)
- [Minecraft EULA·상업 이용 가이드라인 변경 안내](https://www.minecraft.net/en-us/article/minecraft-eula-and-commercial-usage-guidelines-updates)
- [Microsoft Authentication Scheme (Minecraft Wiki, 신규 Azure 앱 승인 필요)](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Microsoft_Authentication_Scheme)
- [Microsoft Q&A — Minecraft 런처용 XboxLive.signin 권한 문의 (2026.2)](https://learn.microsoft.com/en-us/answers/questions/5768276/how-to-get-xboxlive-signin-permission-for-azure-ap)
- [minecraft-launcher-lib — Microsoft 로그인 (승인 신청 안내)](https://minecraft-launcher-lib.readthedocs.io/en/latest/tutorial/microsoft_login.html)

라이선스
- [SFLC GPL 준수 가이드](https://softwarefreedom.org/resources/2014/SFLC-Guide_to_GPL_Compliance_2d_ed.html)
- [F-Droid 포럼 — GPL 앱의 Google Play 배포](https://forum.f-droid.org/t/is-it-legal-to-publish-f-droid-apps-on-google-play/12499)
- MinecraftAuth 라이선스: Maven POM 의 `LGPL-3.0 License` 표기 확인

한국 (개인정보·사업자)
- [개인정보 처리방침, 어떻게 작성할까 (로톡)](https://www.lawtalk.co.kr/posts/38307) · [개정 개인정보 처리방침 작성 방법 (로톡)](https://www.lawtalk.co.kr/posts/85853)
- [개인정보 보호법 제30조 (IT위키)](https://itwiki.kr/w/개인정보%20보호법%20제30조)
- [앱 출시 전 참고해야 할 법령 (브런치)](https://brunch.co.kr/@ihmin/643)
- [Google 개발자 계정 만들기 (채널톡 문서, 25달러·선불카드 불가)](https://docs.channel.io/firstmall/ko/articles/Google-%EA%B0%9C%EB%B0%9C%EC%9E%90-%EA%B3%84%EC%A0%95-%EB%A7%8C%EB%93%A4%EA%B8%B0-a1b85cc3)
- [앱 개발자 통신판매업 관련 문답 (인프런)](https://www.inflearn.com/questions/1619606)
- [1인 개발자 구글플레이 주소 노출 후기 (다모앙)](https://damoang.net/development/1222)

국가장학금
- [한국장학재단 — 국가장학금 알리미](https://www.kosaf.go.kr/ko/prelAplyAlimi.do) · [학자금 지원구간 안내](https://www.kosaf.go.kr/ko/scholar.do?pg=scholarship06_01) · [가구원 정보제공 동의](https://www.kosaf.go.kr/ko/fmlyInfoAgmt.do)

후원·수익
- [Google Play 결제 정책 이해하기](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en) · [결제 정책](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en)
- [구글플레이의 새로운 정책 — 결제 선택 프로그램 (한국어, 2026)](https://blog.google/intl/ko-kr/products/android-play-hardware/a-new-era-for-choice-and-openness-kr/)
- [GitHub Sponsors 소개 (한국어 문서)](https://docs.github.com/ko/sponsors/getting-started-with-github-sponsors/about-github-sponsors) · [GitHub Sponsors 30개 지역 확대 (한국 포함)](https://github.blog/news-insights/company-news/github-sponsors-available-in-30-new-regions-2/)
- [과세 사각지대 '유튜브 후원금' (다음 뉴스)](https://v.daum.net/v/20220829093005831) · [크리에이터를 위한 사업자등록과 세무 가이드 (토스페이먼츠)](https://www.tosspayments.com/blog/articles/creator)

테스트·심사 기간 (비공식)
- [Google Play 비공개 테스트 설정·심사 기간 (LearnDash)](https://learndash.com/support/kb/learndash-app/uncategorized/configure-google-play-closed-testing/)
- [프로덕션 접근 거절 이유와 대처 (Indie Hackers)](https://www.indiehackers.com/post/google-play-production-access-rejected-why-fixes-1efcef20e8)
