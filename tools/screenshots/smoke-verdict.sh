#!/usr/bin/env bash
# smoke.sh 가 남긴 결과를 보고 판정한다.
#   smoke-verdict.sh <결과 폴더>
# 못 누른 것("찾지 못함: …")·기대와 다른 것("실패: …")·앱 충돌 기록이 있거나, 포그라운드 서비스 확인이 없으면 실패(종료 코드 1).
# (smoke.sh 가 늘 0 으로 끝나면 화면을 못 눌렀어도 작업이 초록불이 된다)
DIR=${1:-shots/smoke}
LOG=$DIR/log.txt
if [ ! -f "$LOG" ]; then
  echo "판정: 실패 — $LOG 가 없음"
  exit 1
fi
miss=$(grep -c '^찾지 못함: ' "$LOG")
bad=$(grep -c '^실패: ' "$LOG")
fg=$(grep -c '^isForeground=true 확인' "$LOG")
crash=0
[ -f "$DIR/crash.txt" ] && crash=$(grep -c 'kr\.jamsuham\.app' "$DIR/crash.txt")
verdict="통과"
if [ "$miss" -ne 0 ] || [ "$bad" -ne 0 ] || [ "$crash" -ne 0 ] || [ "$fg" -lt 1 ]; then verdict="실패"; fi
echo "판정: $verdict — 못 누른 것 $miss, 기대와 다른 것 $bad, 앱 충돌 기록 $crash 줄, 포그라운드 서비스 확인 $fg" | tee -a "$LOG"
grep -E '^(찾지 못함|실패): ' "$LOG"
[ "$verdict" = "통과" ]
