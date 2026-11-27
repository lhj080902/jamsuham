#!/usr/bin/env bash
# 눌러 보기: 배포판과 같은 설정의 Play 판 디버그 빌드(스크린샷 모드 아님)를 에뮬레이터에 깔고
# 첫 실행 약관 → 체험 서버 → 잠금·차단·신고 → 화면을 꺼도 유지 를 순서대로 눌러 본다.
# GitHub Actions(smoke.yml)의 에뮬레이터 안에서 돈다. 에뮬레이터 기본 언어가 영어라 영어 문구로 누른다.
# 결과: shots/smoke/*.png, log.txt (화면마다 보인 글자·누른 것. 못 누른 것은 "찾지 못함: …", 기대와 다른 것은 "실패: …"), crash.txt (앱이 죽었으면 그 기록)
# 끝에 smoke-verdict.sh 가 판정한다: 못 누른 것·실패·앱 충돌이 있거나 포그라운드 서비스가 아니면 이 스크립트도 실패로 끝난다
set -u
OUT=shots/smoke
PKG=kr.jamsuham.app
APK=app/build/outputs/apk/play/debug/app-play-debug.apk
# 안드로이드 기본 창(AlertDialog)의 버튼은 대문자로 바뀌어 보이므로 대소문자를 가리지 않는다
export UI_NOCASE=1
UI="python3 tools/screenshots/ui.py"
mkdir -p "$OUT"
LOG=$OUT/log.txt
: > "$LOG"

note() { echo "$*" | tee -a "$LOG"; }
shot() { # shot <이름>
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "== $1: $($UI texts)" >> "$LOG"
  echo "shot $1"
}
tap() { $UI tap "$1" | tee -a "$LOG"; }
taplast() { $UI taplast "$1" | tee -a "$LOG"; }
longtap() { $UI longtap "$1" | tee -a "$LOG"; }
moved() { $UI moved "$1" | tee -a "$LOG"; }
# 화면을 아래로 내려 가며 찾아 누른다 (긴 설정 화면)
find_tap() {
  local i out
  for i in 1 2 3 4 5 6 7 8 9 10; do
    if out=$(UI_TRIES=1 $UI tap "$1"); then note "$out"; return 0; fi
    adb shell input swipe 540 1500 540 800 300; sleep 1
  done
  note "찾지 못함: $1"
}
# 있으면 안 되는 글자가 화면에 보이면 실패로 적는다
absent() {
  if $UI texts | grep -qi -- "$1"; then note "실패: 보이면 안 되는 글자가 보임 '$1'"; else note "없음 확인: $1"; fi
}
frames() { adb shell dumpsys gfxinfo $PKG 2>/dev/null | tr -d '\r' | grep -m1 "Total frames rendered" | tr -dc 0-9; }
# 있으면 누르고 없으면 넘어간다 (기기에 따라 뜨기도 안 뜨기도 하는 창)
maybe() {
  local out
  if out=$(UI_TRIES=3 $UI tap "$1"); then note "$out"; else note "(없어서 넘어감) $1"; fi
}
focus() { adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r'; }
# 다른 앱(메일 등)이 앞에 있으면 뒤로 가기로 돌아온다. 앱이 앞에 있으면 아무것도 누르지 않는다
back_to_app() {
  local i
  for i in 1 2 3; do
    case "$(focus)" in *$PKG*) return 0;; esac
    adb shell input keyevent KEYCODE_BACK; sleep 2
  done
  note "앱으로 돌아오지 못함: $(focus)"
}
service() { adb shell dumpsys activity services $PKG 2>/dev/null | tr -d '\r' | grep -E "ServiceRecord|isForeground" | head -4; }

adb root > /dev/null 2>&1; sleep 2; adb wait-for-device
adb shell settings put secure show_ime_with_hard_keyboard 0
adb install -r "$APK"
adb logcat -c

# ---------------------------------------------------------------- 1. 첫 실행: 이용 약관
adb shell am start -W -n $PKG/.MainActivity > /dev/null
sleep 6
shot "01-terms"
tap "Decline"
sleep 3
note "동의하지 않음 뒤의 화면: $(focus)"
adb shell am start -W -n $PKG/.MainActivity > /dev/null
sleep 4
shot "01b-terms-again"
tap "Agree and start"
sleep 3
# 약관에 동의한 뒤에 알림 권한을 묻는다 (안드로이드 13+)
shot "01c-notification-permission"
maybe "Allow"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2> /dev/null
sleep 2

# ---------------------------------------------------------------- 2. 체험 서버
shot "02-empty"
tap "Try without signing in"
sleep 5 # 서버 목록의 서버 정보(이름·접속자 수) 받기
shot "03-list"
tap "Start"
sleep 18 # 접속 + 채팅이 다 올라올 때까지

# ---------------------------------------------------------------- 3. 포그라운드 서비스로 돌고 있는지
note "잠수 서비스: $(service | tr '\n' ' ')"
if service | grep -q "isForeground=true"; then note "isForeground=true 확인"; else note "찾지 못함: isForeground=true"; fi

# ---------------------------------------------------------------- 4. 채팅·지도·인벤(잠금)·접속자
shot "04-chat"
tap "Map"; sleep 4
shot "05-map"
tap "Inv"; sleep 2
tap "Diamond Sword"; sleep 1
tap "Lock"; sleep 1
shot "06-inv-locked"
tap "^Players"; sleep 2
shot "07-players"
# 긴 이름이 실제로 움직이는지 (왕복형), 탭을 바꾸면 그리기를 멈추는지
moved "~Tide_Runner"
f1=$(frames); sleep 5; f2=$(frames)
tap "Status"; sleep 2
f3=$(frames); sleep 5; f4=$(frames)
note "5초 동안 그린 화면 수: 접속자 탭 $((f2 - f1)), 다른 탭으로 바꾼 뒤 $((f4 - f3))"
if [ $((f4 - f3)) -ge 100 ]; then note "실패: 접속자 탭을 떠난 뒤에도 계속 그림 ($((f4 - f3)))"; fi
tap "^Players"; sleep 2

# ---------------------------------------------------------------- 5. 차단
tap "Block"; sleep 1
shot "08a-block-confirm"
taplast "Block"; sleep 2
shot "08-blocked"
tap "Chat"; sleep 1
shot "09-chat-after-block"

# ---------------------------------------------------------------- 6. 신고 (메일 앱이 있을 때)
tap "^Players"; sleep 2
tap "Report"; sleep 1
shot "10-report"
tap "Send report"; sleep 4
shot "11-report-sent"
note "신고 보내기 뒤의 화면: $(focus)"
# 메일 앱이 열렸으면 앱으로 돌아온다 (한 번에 하나씩 — 더 누르면 앱의 "목록으로 나갈까요?" 창이 뜬다)
back_to_app
shot "11b-players-after-report"
tap "Chat"; sleep 1
shot "11c-chat-after-report"

# ---------------------------------------------------------------- 7. 채팅 줄 길게 누르기
longtap "~AFK mode is on"; sleep 1
shot "12-line-menu"
# 메일 앱이 없는 기기에서의 신고: 죽지 않고, 내용을 복사한 뒤 보낼 주소를 보여 줘야 한다
adb shell pm disable-user --user 0 com.google.android.gm > /dev/null 2>&1
tap "Report this message"; sleep 1
shot "12b-report-line"
tap "Send report"; sleep 3
shot "12c-report-no-mail-app"
note "메일 앱 없이 신고 보내기 뒤의 화면: $(focus)"
maybe "OK"
back_to_app
longtap "~AFK mode is on"; sleep 1
tap "Hide this message"; sleep 1
shot "12d-line-hidden"
longtap "~<Haneul_Fish>"; sleep 1
tap "Block the sender"; sleep 1
shot "12e-block-sender-confirm"
taplast "Block"; sleep 2
shot "12f-chat-after-block-sender"

# ---------------------------------------------------------------- 7b. 서버 메뉴 (체험 서버의 메뉴 창)
tap "Chat or /command"; sleep 1
adb shell input text "/menu"; sleep 1
tap "Send"; sleep 3
shot "12g-menu"
tap "Claim AFK reward"; sleep 1
shot "12h-menu-item"
# 체험 서버에는 자동 클릭 규칙을 만들 수 없다 (서버 설정에서 지울 수 없으므로)
absent "Auto-click rule"
tap "Click"; sleep 3
shot "12i-menu-clicked"

# ---------------------------------------------------------------- 8. 홈 → 화면 끄기 → 다시 열기
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell input keyevent KEYCODE_SLEEP
sleep 5
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell wm dismiss-keyguard
adb shell input keyevent 82
sleep 1
note "화면을 껐다 켠 뒤의 잠수 서비스: $(service | tr '\n' ' ')"
adb shell am start -W -n $PKG/.MainActivity > /dev/null
sleep 3
shot "13-still-afk"
adb shell am start -W -n $PKG/.MainActivity --activity-clear-top > /dev/null
sleep 3
shot "13b-list-afk"
tap "Disconnect"
sleep 3
shot "14-disconnected"
note "끊은 뒤의 잠수 서비스: $(service | tr '\n' ' ')"

# ---------------------------------------------------------------- 9. 설정: 차단한 사용자
tap "⚙ Settings"; sleep 2
find_tap "Unblock"; sleep 2
shot "15-settings-after-unblock"
adb shell input keyevent KEYCODE_BACK; sleep 2

# ---------------------------------------------------------------- 10. 체험 서버 설정 → 지우기 → 설정에서 다시 추가
longtap "Demo server"; sleep 2
shot "16-demo-editor"
tap "Save"; sleep 2
longtap "Demo server"; sleep 2
tap "Delete this server"; sleep 1
taplast "Delete"; sleep 2
shot "17-list-after-delete"
tap "⚙ Settings"; sleep 2
find_tap "Add demo server"; sleep 2
shot "18-settings-demo-added"
adb shell input keyevent KEYCODE_BACK; sleep 2
shot "19-list-demo-again"
tap "Start"; sleep 10
note "다시 추가한 체험 서버: $(service | tr '\n' ' ')"
shot "20-demo-again-chat"
adb shell am start -W -n $PKG/.MainActivity --activity-clear-top > /dev/null
sleep 3
tap "Disconnect"; sleep 2

# 앱이 죽은 적이 있는지
adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
ls -la "$OUT"
bash tools/screenshots/smoke-verdict.sh "$OUT"
