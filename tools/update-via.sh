#!/usr/bin/env bash
# ViaVersion / ViaBackwards / ViaRewind 를 새 버전으로 바꿀 때 쓰는 스크립트.
# 새 마인크래프트 버전이 나오면 Via 쪽 새 릴리스 번호를 넣고 실행하세요. (JDK 17 이상 필요)
#
#   ./tools/update-via.sh 5.12.2 5.12.2 4.2.0
#                         ^VV    ^VB    ^VR
set -euo pipefail
VV="${1:?ViaVersion 버전}"
VB="${2:?ViaBackwards 버전}"
VR="${3:?ViaRewind 버전}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

download() { # name tag file
  local url="https://github.com/ViaVersion/$1/releases/download/$2/$3"
  echo "다운로드: $url"
  curl -fL --retry 3 -o "$TMP/$3" "$url"
}
download ViaVersion   "$VV" "ViaVersion-$VV.jar"
download ViaBackwards "$VB" "ViaBackwards-$VB.jar"
download ViaRewind    "$VR" "ViaRewind-$VR.jar"

rm -f "$ROOT"/core/libs/*.jar
mkdir -p "$ROOT/core/libs"
java -Dstdout.encoding=UTF-8 "$ROOT/tools/ViaPatcher.java" "$TMP/ViaVersion-$VV.jar"   "$ROOT/core/libs/ViaVersion-$VV-android.jar"
java -Dstdout.encoding=UTF-8 "$ROOT/tools/ViaPatcher.java" "$TMP/ViaBackwards-$VB.jar" "$ROOT/core/libs/ViaBackwards-$VB-android.jar"
java -Dstdout.encoding=UTF-8 "$ROOT/tools/ViaPatcher.java" "$TMP/ViaRewind-$VR.jar"    "$ROOT/core/libs/ViaRewind-$VR-android.jar"
echo "완료: core/libs 를 확인하고 앱을 다시 빌드하세요."
