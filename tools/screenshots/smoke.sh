#!/usr/bin/env bash
# 눌러 보기: 배포판과 같은 설정의 Play 판 디버그 빌드(스크린샷 모드 아님)를 에뮬레이터에 깔고
# 첫 실행 약관 → 체험 서버 → 잠금·차단·신고 → 화면을 꺼도 유지 를 순서대로 눌러 본다.
# GitHub Actions(smoke.yml)의 에뮬레이터 안에서 돈다. 에뮬레이터 기본 언어가 영어라 영어 문구로 누른다.
# 결과: shots/smoke/*.png, log.txt (화면마다 보인 글자·누른 것. 못 누른 것은 "찾지 못함: …"), crash.txt (앱이 죽었으면 그 기록)
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
# 있으면 누르고 없으면 넘어간다 (기기에 따라 뜨기도 안 뜨기도 하는 창)
maybe() {
  local out
  if out=$(UI_TRIES=3 $UI tap "$1"); then note "$out"; else note "(없어서 넘어감) $1"; fi
}
focus() { adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r'; }
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

# ---------------------------------------------------------------- 5. 차단
tap "Block"; sleep 1
shot "08a-block-confirm"
taplast "Block"; sleep 2
shot "08-blocked"
tap "Chat"; sleep 1
shot "09-chat-after-block"

# ---------------------------------------------------------------- 6. 신고
tap "^Players"; sleep 2
tap "Report"; sleep 1
shot "10-report"
tap "Send report"; sleep 4
shot "11-report-sent"
note "신고 보내기 뒤의 화면: $(focus)"
# 메일 앱이 없으면 앱이 "복사했습니다" 창을 띄운다. 메일 앱이 열렸으면 뒤로 돌아온다
maybe "OK"
case "$(focus)" in *$PKG*) ;; *) adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 2; note "뒤로 간 뒤의 화면: $(focus)";; esac
shot "11b-players-after-report"

# ---------------------------------------------------------------- 7. 채팅 줄 길게 누르기
tap "Chat"; sleep 1
longtap "~Welcome to Seaside"; sleep 1
shot "12-line-menu"
tap "Hide this message"; sleep 1
shot "12b-line-hidden"

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

# 앱이 죽은 적이 있는지
adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
note "crash.txt 줄 수: $(wc -l < "$OUT/crash.txt")"
note "찾지 못함 건수: $(grep -c '^찾지 못함' "$LOG")"
ls -la "$OUT"
exit 0
