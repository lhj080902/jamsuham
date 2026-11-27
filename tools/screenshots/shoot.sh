#!/usr/bin/env bash
# 스토어 스크린샷 찍기. GitHub Actions(screenshots.yml)의 에뮬레이터 안에서 돈다.
#   shoot.sh <phone|tablet>
# 미리 필요한 것: JAMSUHAM_SCREENSHOTS=true 로 빌드한 Play 판 디버그 APK (Screenshots.kt 가 시험 계정·서버를 넣음),
#                 PC 쪽에 시험 서버 3개 (25565 = showcase, 25566·25567 = 목록에 보일 서버), 메뉴를 여는 파일 경로 SHOWCASE_MENU_FILE
# 결과: shots/<종류>/*.png (밝은 화면), *-dark.png (어두운 화면), log.txt (화면마다 보인 글자)
set -u
KIND=${1:-phone}
OUT=shots/$KIND
PKG=kr.jamsuham.app
APK=app/build/outputs/apk/play/debug/app-play-debug.apk
UI="python3 tools/screenshots/ui.py"
MENU_FILE=${SHOWCASE_MENU_FILE:-/tmp/showcase-menu}
mkdir -p "$OUT"
LOG=$OUT/log.txt
: > "$LOG"

shot() { # shot <이름>
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "== $1: $($UI texts)" >> "$LOG"
  echo "shot $1"
}
tap() { $UI tap "$1" | tee -a "$LOG"; }
demo() { adb shell am broadcast -a com.android.systemui.demo "$@" > /dev/null; }

adb root > /dev/null 2>&1; sleep 2; adb wait-for-device
adb shell setprop persist.sys.timezone Asia/Seoul
adb shell settings put system time_12_24 24
# 채팅 시간이 장면(저녁 9시 낚시 대회 전)과 맞게 기기 시계를 오늘 저녁 7시 30분으로
adb shell settings put global auto_time 0
adb shell "date $(TZ=Asia/Seoul date +%m%d)1930$(TZ=Asia/Seoul date +%Y).00" > /dev/null
adb shell settings put secure show_ime_with_hard_keyboard 0
# 제스처 내비게이션: 태블릿 아래쪽에 다른 앱 아이콘 줄(작업 표시줄)이 계속 보이지 않게
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural > /dev/null
adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +$PKG > /dev/null

# 상태 줄을 깔끔하게 (시계는 채팅 시간과 같게 기기 시간으로 고정)
status_bar() {
  adb shell settings put global sysui_demo_allowed 1
  demo -e command enter
  demo -e command clock -e hhmm "$(adb shell date +%H%M | tr -d '\r')"
  demo -e command battery -e level 100 -e plugged false
  demo -e command network -e wifi show -e level 4 -e fully true
  demo -e command network -e mobile show -e datatype none -e level 4 -e fully true
  demo -e command notifications -e visible false
}
status_bar

tour() { # tour <파일 이름 뒤에 붙일 글자> — 채팅 화면이 보이는 상태에서 시작
  local s=$1
  tap "채팅"; sleep 1
  shot "02-chat$s"
  tap "지도"; sleep 4
  shot "03-map$s"
  tap "인벤"; sleep 2
  tap "다이아몬드 검"; sleep 1
  shot "04-inventory$s"
  tap "다이아몬드 검"
  tap "^접속자"; sleep 2
  shot "05-players$s"
  tap "상태"; sleep 2
  shot "06-status$s"
  tap "채팅"; sleep 1
  touch "$MENU_FILE"; sleep 4
  shot "07-menu$s"
  tap "메뉴 닫기"; sleep 2
}

adb shell am start -W -n $PKG/.MainActivity > /dev/null
sleep 10 # 서버 목록의 서버 정보(이름·접속자 수) 받기
shot "00-servers-before"
tap "시작"
sleep 5
status_bar # 채팅에 찍히는 시간과 같은 분으로
sleep 10 # 접속 + 채팅이 다 올라올 때까지
tour ""
if [ "$KIND" = phone ]; then
  adb shell am start -W -n $PKG/.MainActivity --activity-clear-top > /dev/null
  sleep 3
fi
shot "01-servers"

# 어두운 화면으로 한 번 더
adb shell cmd uimode night yes
sleep 5
if [ "$KIND" = phone ]; then
  shot "01-servers-dark"
  tap "바다마을 야생"
  sleep 3
fi
tour "-dark"
[ "$KIND" = tablet ] && shot "01-servers-dark"

adb shell cmd uimode night no
demo -e command exit
ls -la "$OUT"
exit 0
