# Play 업로드 키 만들기 · 등록하기

Play 판(AAB)은 **비공개 업로드 키**로 서명해야 Play Console 에 올릴 수 있습니다. 저장소에 있는 `app/debug.keystore` 는 공개된 키라서 쓸 수 없습니다.
키는 **저장소에 올리지 말고**, GitHub Actions Secrets 에만 등록합니다.

> ⚠️ 업로드 키 파일과 비밀번호를 잃어버리면 Play Console 에서 키를 재설정해야 합니다(며칠 걸림).
> 파일은 USB·클라우드 등 **두 곳 이상에 따로 백업**하세요.

## 1. 키 만들기 (PC 에서 한 번만)
`keytool` 이 필요합니다. 아래 중 하나면 됩니다.
- **Android Studio 가 깔려 있으면** (Windows): `"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"` 를 그대로 씁니다.
- **아무것도 없으면**: https://adoptium.net 에서 **Temurin JDK 17** 을 설치하면 `keytool` 이 같이 깔립니다.

Windows 는 "명령 프롬프트" 또는 PowerShell, macOS 는 "터미널" 에서 키를 저장할 폴더로 이동한 뒤 실행합니다.

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Android Studio 의 keytool 을 쓰는 경우 (Windows):

```bash
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

- 비밀번호 두 번, 이름·조직 등을 물어봅니다. 이름 정도만 넣고 나머지는 비워도 됩니다.
- 만들어진 `upload.jks` 와 비밀번호를 안전한 곳에 백업합니다.

## 2. 파일을 base64 글자로 바꾸기
- macOS / Linux:

```bash
base64 -i upload.jks | tr -d '\n' > upload.jks.b64
```

- Windows (PowerShell):

```bash
[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks")) | Out-File -Encoding ascii upload.jks.b64
```

## 3. GitHub Secrets 에 등록
GitHub 저장소 → **Settings → Secrets and variables → Actions → New repository secret** 에서 네 개를 등록합니다.

| 이름 | 값 |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | `upload.jks.b64` 파일 내용 전체 |
| `UPLOAD_STORE_PASSWORD` | 키 저장소 비밀번호 |
| `UPLOAD_KEY_ALIAS` | `upload` (1단계 `-alias` 에 넣은 값) |
| `UPLOAD_KEY_PASSWORD` | 키 비밀번호 (보통 저장소 비밀번호와 같음) |

## 3-1. 잘 됐는지 확인
Secrets 를 등록한 뒤 GitHub → **Actions** → 맨 위 "Build APK" → 오른쪽 **Re-run all jobs** 로 다시 빌드합니다.
끝나면 그 실행 화면 아래 **Artifacts** 에 `jamsuham-play-aab` (뒤에 UNSIGNED 가 없는 이름) 가 보이면 성공입니다.

## 4. 빌드 받기
- `main` 에 올리면 GitHub Actions 가 Play 판 AAB 를 만듭니다. 실행 결과 화면 아래 **Artifacts** 에서 `jamsuham-play-aab` 를 받습니다.
- Secrets 가 없으면 이름이 `jamsuham-play-aab-UNSIGNED-debugkey` 로 나옵니다. 디버그 키로 서명된 것이라 **Play 에 올릴 수 없습니다**.
- 받은 zip 안의 `app-play-release.aab` 를 Play Console → 테스트(비공개 테스트) → 새 버전 만들기 에 올립니다.
- 처음 올릴 때 **"Play 앱 서명"** 을 사용하도록 안내가 나오면 그대로 따릅니다. Google 이 앱 서명 키를 보관하고, 우리는 업로드 키로만 서명합니다.

## 참고
- GitHub 판 APK(지금 폰에 깔린 것)는 계속 공개 디버그 키로 서명됩니다. Play 판과 서명이 달라서 **서로 덮어 설치되지 않습니다**. Play 판을 설치하려면 GitHub 판을 먼저 지워야 합니다.
- versionCode 는 GitHub Actions 실행 번호입니다. Play 에 올릴 때마다 이전보다 커야 하므로 그대로 쓰면 됩니다.
