# FastPack 작업 인수인계 (2026-11-25, 최신 0.4.1)

VS Code(로컬 Claude Code)에서 이어서 작업하기 위한 메모입니다.

## 0. 코드 위치 — 꼭 먼저 확인
- FastPack 0.4.0·0.4.1 은 **`claude/sharp-lamport-xup92m` 브랜치에만** 있고, **`main` 에는 아직 합쳐지지 않았습니다.**
  `main` 에는 0.3.0 까지만 있습니다. 그 사이 `main` 은 앱 v1.7.1 로 따로 진행됐습니다.
- 로컬에서 가져오기:
  ```bash
  git fetch origin claude/sharp-lamport-xup92m
  git checkout claude/sharp-lamport-xup92m
  ```
- `main` 에 합치려면 (앱 코드와 겹치는 파일이 없어서 충돌은 거의 없을 것):
  ```bash
  git checkout main && git pull
  git merge claude/sharp-lamport-xup92m
  ```
  이 브랜치는 `tools/fastpack-mod/`, `.github/workflows/mod.yml`, `CLAUDE.md`, `docs/store/feature-1024x500.png`, `docs/store-listing.md` 만 바꿨습니다.
  `CLAUDE.md` 는 main 쪽도 바뀌었으면 충돌이 날 수 있으니 두 쪽 내용을 모두 남기면 됩니다.

## 1. 무엇을 하는 모드인가
마인크래프트 자바 **1.21.4** Fabric 클라이언트 모드. 서버 리소스팩(도스온라인 등 큰 팩) 접속을 빠르게 합니다. 루나 클라이언트(ichor)에서도 동작 확인.
- **나눠 받기 (0.1)**: 8MB 넘는 팩을 HTTP Range 로 4MB 조각·연결 6개로 동시에 받음. 해시 검사·경로는 바닐라와 같음, 실패하면 바닐라로.
- **재접속 때 다시 적용 안 함 (0.2)**: 나갈 때 팩을 내리지 않고 남겨 두고, 같은 서버에 같은 팩으로 들어오면 리소스 다시 읽기를 건너뜀. 다른 서버·싱글 월드면 정리.
- **게임을 다시 켜도 유지 (0.3)**: 마지막 서버 팩을 `config/fastpack-lastpack.properties` 에 저장 → 다음 실행 때 첫 로딩에 같이 불러옴.
- **게임 안 설정 창 + 채팅 알림 (0.4.0)**: Mod Menu → FastPack → 설정. 나만 보이는 채팅으로 `[FastPack] …` 알림.
- **튕김 위험 수정 (0.4.1)**: 아래 4번 참고.

## 2. 파일 지도 (`tools/fastpack-mod/src/main/java/kr/jamsuham/fastpack/`)
| 파일 | 역할 |
|---|---|
| `FastPackClient` | 진입점. 켜짐 로그(루나·Mod Menu 여부) |
| `FastPackConfig` | 설정 `config/fastpack.properties`: `keepAcrossRestart`·`skipReapply`·`parallelDownload`·`chatMessages` (기본 모두 true) |
| `FastPackState` | "마지막 서버 팩" 저장·불러오기 (`keepAcrossRestart && skipReapply` 일 때만) |
| `FastPackLog` | `log()` = System.out + SLF4J (루나는 SLF4J 가 안 보여서 System.out 필수) / `chat()` = 로그 + 채팅 알림 큐(최대 20개, 3분 지난 건 버림) |
| `PackKeeper` | 설정 창이 "남겨 둔 팩"을 확인·정리하는 인터페이스. `Holder.instance` 에 DownloadedPackSource 를 잡아 둠 |
| `ParallelDownload` | 나눠 받기 본체 |
| `mixin/HttpUtilMixin` | `HttpUtil.downloadFile` 맨 앞 → 나눠 받기 시도 |
| `mixin/DownloadedPackSourceMixin` | 재접속·재시작 기능 본체 (`startReload` 건너뛰기, `<init>` 에서 지난 팩 미리 불러오기, `configureForServerControl`/`configureForLocalWorld` 에서 정리, `onRecovery` 처리). `PackKeeper` 구현 |
| `mixin/MinecraftMixin` | `tick` HEAD 에서 플레이어가 있으면 채팅 알림 큐를 내보냄 |
| `gui/FastPackScreen` | 설정 창 (켜기/끄기 4개 + "남겨 둔 팩 지금 정리") |
| `gui/ModMenuIntegration` | Mod Menu 진입점 (`fabric.mod.json` entrypoints `modmenu`, suggests) |

## 3. 빌드
- 로컬(PC): JDK 21 로 `tools/fastpack-mod` 에서 `./gradlew build` → `build/libs/fastpack-0.4.1.jar`
  (Fabric·Mojang·TerraformersMC maven 이 열려 있으면 됨. Claude 클라우드 환경은 막혀 있어서 CI 로만 빌드했음)
- CI: `.github/workflows/mod.yml` — `tools/fastpack-mod/**` 를 바꿔 푸시하면 자동 빌드, 아티팩트 `fastpack-mod`.
  믹스인 대상(refmap)·`downloadFile` 시그니처 검사, 리소스팩 관련 메서드를 누가 부르는지 출력하는 단계 포함.
- 버전: `tools/fastpack-mod/gradle.properties` 의 `mod_version` (지금 0.4.1), Mod Menu `modmenu_version=13.0.3` (compileOnly, jar 에 안 들어감)
- 0.4.1 CI 빌드 성공: https://github.com/lhj080902/jamsuham/actions/runs/45143270936

## 4. 꼭 알아야 할 함정 (0.4.1 에서 고친 것)
- **`configureForServerControl` 은 접속 스레드(`ConnectScreen$1.run`)에서 불린다** (CI 바이트코드로 확인).
  여기서 `reloadResourcePacks()` 를 바로 부르면 렌더 스레드가 아니라 튕길 수 있음 → `minecraft.execute(...)` 로 게임 스레드에 넘김.
  리소스 다시 읽기는 항상 게임 스레드에서.
- **서버 구분은 `"호스트:포트"`** (`InetSocketAddress.getHostString()`). 예전 `"호스트/IP:포트"` 는 IP 가 바뀌는 서버를 다른 서버로 봤음.
- **`onRecovery` 때 남겨 둔 팩을 packSource 에서 뺌**: 바닐라 복구는 우리가 넣은 팩을 몰라서 그대로 두고 다시 실패할 수 있음.
- 스레드 사이에서 쓰는 믹스인 필드는 `volatile`.

## 5. 시험 상태
- 루나에서 확인됨: 0.2.0 재접속 건너뛰기(도스온라인), 0.4.x 켜짐·믹스인 적용·다운로드 가로채기 로그 (2026-11-23 로그에 `fastpack 0.4.1` 로드 확인)
- **아직 확인 안 됨**: Mod Menu 설정 버튼이 보이는지, 채팅 알림이 뜨는지, 0.3.0 "게임 다시 켜도 유지" 실제 동작
- 루나 "리소스팩 새로고침 실패" 후 튕김: 루나 자체 버그로 결론(다른 사람도 겪음, FastPack 무관). 사용자가 런처를 새로 만들어서 더 다루지 않음.
  참고로 도스 팩은 블록 아틀라스가 16384×16384 라 그래픽카드 한계에 닿아 있음.
- 0.3.1 이라는 버전은 없음 (0.3.0 → 0.4.0)

## 6. 다음에 할 수 있는 일
- 위 "아직 확인 안 됨" 3가지 시험
- 필요하면 FastPack 을 별도 저장소로 분리 (잠수크래프트 앱 저장소와 안 꼬이게):
  ```bash
  git subtree split --prefix=tools/fastpack-mod -b fastpack-only
  ```
  → 새 저장소에 `fastpack-only` 브랜치를 푸시. 이때 `.github/workflows/mod.yml` 은 경로(`working-directory: tools/fastpack-mod`)를 루트로 고쳐 옮겨야 하고,
  `build.gradle` 의 `jar { from(rootProject.file('../../LICENSE')) }` 도 LICENSE 위치에 맞게 고쳐야 함.
