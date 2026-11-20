# 잠수함 플레이스토어 출시 준비 체크리스트

작성일: 2026-11-20 · 대상: 안드로이드 앱 Google Play 출시

> 법률 자문이 아닙니다. 공식 문서와 커뮤니티 자료를 정리한 것이므로, 돈이 오가는 결정(유료화, 사업자 등록 등) 전에는 원문과 전문가 확인을 권합니다.

---

## 0. 한눈에 보기 (출시를 막을 수 있는 순서)

| # | 항목 | 지금 상태 | 해야 할 일 | 막히면 |
|---|---|---|---|---|
| 1 | 개발자 계정 | 없음 | **만 18세 이상 본인 명의**로 Play Console 가입 (소유권 이전 불가) | 가입 자체 불가 |
| 2 | 마이크로소프트 로그인 | 마인크래프트 **공식 런처의 client ID 를 빌려 씀** | 자체 Azure 앱 등록 + **Mojang 승인 신청서** 제출 | 승인 없으면 로그인 403 |
| 3 | GPL-3.0 (ViaVersion) | 앱 안에 포함 | 앱 소스를 **GPL-3.0 으로 공개**하거나 구조 변경 | 라이선스 위반 |
| 4 | 앱 안 업데이트 | `REQUEST_INSTALL_PACKAGES` + Updater | Play 판에서 **제거** | 심사 거절 |
| 5 | 테스트 | - | **테스터 12명 × 14일 연속** 비공개 테스트 | 프로덕션 신청 불가 |
| 6 | 서명 키 | 공개된 debug 키 | Play 앱 서명 + **비공개 업로드 키** | - |

---

## 1. 개발자 계정
- Google 은 개인·조직 계정 모두 **만 18세 이상**이어야 등록할 수 있다고 안내합니다. 계정 소유권은 나중에 다른 사람에게 넘길 수 없으므로, 처음부터 누구 명의로 할지 정해야 합니다.
- 2023년 11월 13일 이후 만든 **개인 계정**은 프로덕션(정식 출시) 전에 **비공개 테스트에 12명 이상이 14일 연속 참여**해야 합니다.
  - 중간에 빠지는 사람이 생기면 14일이 다시 시작될 수 있습니다. **공개 테스트는 인정되지 않습니다.**
  - 조건을 채워도 자동으로 열리지 않고, 프로덕션 접근을 따로 신청해야 합니다.
  - 조직(사업자) 계정은 이 요건이 없다고 알려져 있습니다.

## 2. 마이크로소프트(정품) 로그인 — 가장 먼저 확인할 것
- 지금 코드(`AccountStore.kt`)는 `MsaConstants.JAVA_TITLE_ID`, 즉 **마인크래프트 공식 런처의 앱 ID** 로 로그인합니다. 개인용으로는 동작하지만, 공개 배포 앱이 남의 앱 ID 를 쓰는 것은 문제가 될 수 있습니다.
- 정식 방법은 이렇습니다.
  1. Azure 에 **자체 앱을 등록**해 client ID 를 받습니다.
  2. Mojang 의 **Minecraft API 사용 승인 신청서**를 냅니다(이메일, 앱 이름, client ID, tenant ID, EULA 동의).
  3. 승인 전에는 `api.minecraftservices.com` 이 **403** 을 돌려줍니다.
- 2026년 2월에 개인 개발자의 비상업 런처가 거절됐다는 Microsoft Q&A 글이 있어, **개인 프로젝트가 승인될지는 불확실**합니다.
- → 출시 준비 중 가장 오래 걸리고, 결과도 장담할 수 없는 부분입니다. **가장 먼저 신청**하세요.
- 승인되면 앱의 로그인 설정(client ID, 리다이렉트 주소)만 바꾸면 됩니다(`AccountStore.msaConfig`).

## 3. 오픈소스 라이선스 (저작권)
| 라이브러리 | 라이선스 | 의무 |
|---|---|---|
| ViaVersion / ViaBackwards / ViaRewind | **GPL-3.0** | 앱을 배포하면 **앱 전체 소스를 GPL-3.0 으로 공개**하고, 스토어 설명이나 앱 안에 소스 위치를 알려야 함 |
| MinecraftAuth | LGPL-3.0 | 앱이 GPL 로 공개되면 함께 충족 |
| Netty, Gson, Guava | Apache-2.0 | 앱 안에 라이선스 고지 화면 필요 |

선택지는 세 가지입니다.
- **A. 앱 소스를 GPL-3.0 으로 공개** (가장 간단)
  - GitHub 저장소를 공개하고, 스토어 설명과 앱 안 "오픈소스 라이선스" 화면에 저장소 링크를 넣습니다.
  - 유료 판매도 할 수 있습니다. 다만 누구나 소스를 받아 직접 빌드할 수 있습니다.
- **B. ViaVersion 을 앱에서 빼고 서버에서 실행** (iOS 계획서의 클라우드 구조)
  - 서버에서 실행만 하는 것은 GPL 의 "배포"가 아니므로 앱 소스를 공개하지 않아도 됩니다.
  - 대신 서버 비용이 들고 작업량이 큽니다.
- **C. ViaVersion 없이 직접 버전 변환을 구현** — 현실적으로 매우 어렵습니다.

※ 공개된 저장소의 `app/debug.keystore` 는 반드시 출시용 키와 분리해야 합니다(6번 참고).

## 4. Google Play 정책 대응 (코드 수정 필요)
1. **앱 안 업데이트 제거**: Play 앱은 Play 를 거치지 않고 스스로 업데이트할 수 없습니다. `REQUEST_INSTALL_PACKAGES` 도 자기 업데이트 용도로는 쓸 수 없습니다.
   - → 빌드를 두 종류(flavor)로 나눕니다. **Play 판**은 Updater·권한·UpdateReceiver 를 빼고, **GitHub 판**은 지금처럼 둡니다.
2. **포그라운드 서비스 신고**: 안드로이드 14 이상을 대상으로 하는 앱은 Play Console 의 "앱 콘텐츠"에서 포그라운드 서비스 유형을 신고해야 합니다.
   - 우리 앱은 `specialUse` 유형을 씁니다.
   - **기능 시연 영상 링크**(사용자가 잠수를 시작하면 알림이 뜨고 화면을 꺼도 유지되는 모습)와 설명이 필요합니다.
3. **배터리 최적화 예외**: 지금은 예외를 직접 요청합니다(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Play 에서는 허용 용도가 제한되어 거절될 위험이 있습니다.
   - → 설정 화면으로 안내만 하는 방식(`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`)으로 바꾸는 것이 안전합니다.
4. **프리미엄 테스트 토글 제거**: 버전 글씨를 7번 누르면 켜지는 토글을 없애고, **Google Play 결제**를 붙여야 합니다. 디지털 기능 판매는 Play 결제가 필수입니다.

## 5. 제출 서류
- **개인정보처리방침 URL** (필수). 적을 내용은 다음과 같습니다.
  - 기기에 저장하는 것: 마이크로소프트·마인크래프트 로그인 토큰(암호화), 서버 목록, 채팅 기록(30일).
  - 연결하는 곳: 마이크로소프트·Mojang 인증 서버, 사용자가 입력한 마인크래프트 서버, (GitHub 판만) GitHub 업데이트 확인.
  - 개발자 서버로 보내는 개인정보는 없다는 점.
- **데이터 보안(Data safety) 양식**: 위 내용과 똑같이 작성합니다.
- **콘텐츠 등급(IARC) 설문**: 다른 사용자와 채팅할 수 있으므로 "사용자 간 상호작용"에 체크합니다.
- **스토어 등록정보**: 설명, 스크린샷, 아이콘, 연락 이메일.

## 6. 서명 키
- Play 앱 서명을 사용하고, **업로드 키는 비공개**로 보관합니다(GitHub Actions 라면 Secrets).
- 지금 폰에 설치된 앱(공개 debug 키로 서명)은 Play 판으로 **덮어 설치할 수 없습니다**. 한 번 지우고 다시 설치해야 합니다(데이터·로그인 초기화).

## 7. 마인크래프트 상표·저작물
- 앱 이름·아이콘에 **"Minecraft" 이름, 로고, 캐릭터(크리퍼 등)를 쓰지 않습니다.** 공식 제품처럼 보이면 안 됩니다. "잠수함"이라는 이름은 괜찮습니다.
- 설명에 "마인크래프트 자바 에디션 서버용"처럼 **설명 목적으로 쓰는 정도**가 일반적입니다. 아래와 같은 비공식 고지 문구를 넣는 것이 안전합니다.
  - 예: `NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.`
  - 이 문구는 2차 자료 기준이므로, 현재 [Minecraft Usage Guidelines](https://minecraft.net/usage-guidelines) 원문을 확인하세요.
- **유료화**는 Mojang 상업 이용 가이드라인을 확인해야 합니다. 게임 자산이 아니라 앱 자체 기능을 파는 것이지만, 브랜드를 쓰는 상업적 이용에는 제한이 있습니다.
- 앱 안의 마인크래프트 유래 데이터
  - `items-ko.json.gz`: 공식 한국어 번역에서 아이템 이름을 뽑은 것
  - `colors-1.21.4.bin`, `collision-1.21.4.bin`: 블록 색·충돌 정보
  - 원 저작물에서 나온 데이터라 위험은 낮다고 보지만 확인이 필요합니다. 스크린샷에 게임 화면을 크게 넣는 것도 주의하세요.
- 앱 설명에 **"서버 규칙에서 잠수·비공식 클라이언트를 금지할 수 있으니 확인 후 사용"** 안내를 넣는 것을 권합니다.

## 8. 결제·세금 (한국, 유료화할 때만)
- 앱 안에서 디지털 상품(프리미엄)을 직접 팔면 **사업자 등록과 통신판매업 신고**가 필요하다는 안내가 많습니다. 광고 수익만 있으면 대상이 아니라고 합니다.
- 수익화 계정은 개발자 인증 때 주소 등이 공개될 수 있다는 후기가 있습니다.
- 둘 다 공식 확인이 안 된 내용이므로, **세무서·정부24·Play Console 안내**로 확인하세요.

---

## 추천 순서
1. **Mojang API 승인 신청** (결과가 불확실하고 오래 걸림) + 개발자 계정 명의 결정
2. **라이선스 방향 결정** (A 소스 공개 / B 서버 구조)
3. 코드 정리: Play 판 빌드 분리(업데이트 제거), 배터리 예외 방식 변경, 오픈소스 고지 화면, 비공식 고지 문구, 자체 client ID
4. 개인정보처리방침 페이지 만들기 + 데이터 보안·콘텐츠 등급·포그라운드 서비스 영상
5. 비공개 테스트 (12명 × 14일) → 프로덕션 신청
6. (유료화 시) Play 결제 연결 + 사업자·통신판매업 확인

---

## 출처
Google Play 공식
- [신규 개인 개발자 계정 앱 테스트 요건 (12명·14일)](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Play Console 시작하기 (만 18세 이상)](https://support.google.com/googleplay/android-developer/answer/6112435?hl=en)
- [개발자 계정 유형 선택](https://support.google.com/googleplay/android-developer/answer/13634885?hl=en)
- [REQUEST_INSTALL_PACKAGES 권한 정책](https://support.google.com/googleplay/android-developer/answer/12085295)
- [기기 및 네트워크 악용 정책 (자체 업데이트 금지)](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)
- [포그라운드 서비스·전체 화면 인텐트 요건](https://support.google.com/googleplay/android-developer/answer/13392821)
- [구글 플레이 결제 정책 FAQ (한국어 블로그)](https://developers-kr.googleblog.com/2020/09/commerce-update-faqs.html)

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

한국 (사업자·통신판매업, 공식 아님)
- [앱 개발자 통신판매업 관련 문답 (인프런)](https://www.inflearn.com/questions/1619606)
- [1인 개발자 구글플레이 주소 노출 후기 (다모앙)](https://damoang.net/development/1222)
