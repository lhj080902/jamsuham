#!/usr/bin/env python3
"""에뮬레이터 화면에서 글자를 찾아 누른다 (스토어 스크린샷 스크립트 shoot.sh, 눌러 보기 스크립트 smoke.sh 가 씀).

  ui.py tap <글자>      글자가 정확히 같은 첫 항목을 누른다. '^글자' 면 그 글자로 시작하는 항목, '~글자' 면 그 글자가 든 항목
  ui.py taplast <글자>  같은 글자가 여럿일 때 마지막 항목을 누른다 (창 제목과 버튼의 글자가 같을 때 버튼 쪽)
  ui.py longtap <글자>  길게 누른다
  ui.py moved <글자>    그 글자가 있는 줄의 그림이 몇 초 사이에 바뀌는지 본다 (흐르는 글씨 확인). 바뀌면 0, 그대로면 1
  ui.py texts           지금 화면에 보이는 글자 모두 출력 (문제 찾기용)

환경 변수: UI_NOCASE=1 이면 대소문자를 가리지 않는다 (안드로이드 기본 창의 버튼은 대문자로 바뀌어 보인다),
          UI_TRIES=<번> 찾아보는 횟수 (기본 8, 한 번에 약 1초)

화면이 계속 바뀌는 중이면 uiautomator 가 실패할 수 있어 몇 번 다시 시도한다.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def dump():
    for _ in range(6):
        out = subprocess.run(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True).stdout.decode("utf-8", "replace")
        start, end = out.find("<?xml"), out.rfind("</hierarchy>")
        if start >= 0 and end > start:
            try:
                root = ET.fromstring(out[start:end + len("</hierarchy>")])
            except ET.ParseError:
                root = None
            if root is not None:
                # 에뮬레이터가 느려 "앱이 응답하지 않음" 창이 뜨면 '대기'를 누르고 다시 읽는다
                found = list(nodes(root))
                if any("isn't responding" in t for t, _, _ in found):
                    for t, x, y in found:
                        if t == "Wait":
                            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
                    time.sleep(3)
                    continue
                return root
        time.sleep(1)
    return None


def nodes(root):
    for n in root.iter("node"):
        text = n.get("text") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if text and m:
            x1, y1, x2, y2 = map(int, m.groups())
            yield text, (x1 + x2) // 2, (y1 + y2) // 2


def matcher(want):
    """'^글자' = 시작, '~글자' = 포함, 그 밖에는 정확히 같음"""
    nocase = os.environ.get("UI_NOCASE") == "1"
    mode = want[0] if want[:1] in ("^", "~") else ""
    want = want[1:] if mode else want
    if nocase:
        want = want.lower()

    def match(text):
        if nocase:
            text = text.lower()
        if mode == "^":
            return text.startswith(want)
        if mode == "~":
            return want in text
        return text == want
    return match


def region(x1, y1, x2, y2):
    """화면 그림(raw RGBA)에서 그 네모 부분만 잘라 온다"""
    raw = subprocess.run(["adb", "exec-out", "screencap"], capture_output=True).stdout
    if len(raw) < 16:
        return None
    width = int.from_bytes(raw[0:4], "little")
    height = int.from_bytes(raw[4:8], "little")
    header = len(raw) - width * height * 4  # 안드로이드 버전에 따라 12 또는 16 바이트
    if width <= 0 or height <= 0 or header < 0:
        return None
    x1, x2 = max(0, x1), min(width, x2)
    y1, y2 = max(0, y1), min(height, y2)
    return b"".join(raw[header + (y * width + x1) * 4: header + (y * width + x2) * 4] for y in range(y1, y2))


def moved(want):
    match = matcher(want)
    root = dump()
    if root is None:
        print(f"찾지 못함: {want}")
        return 1
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m and match(n.get("text") or ""):
            x1, y1, x2, y2 = map(int, m.groups())
            first = region(x1, y1, x2, y2)
            for _ in range(8):
                time.sleep(0.7)
                if region(x1, y1, x2, y2) != first:
                    print(f"moved '{n.get('text')}' 움직임")
                    return 0
            print(f"실패: 움직이지 않음 '{n.get('text')}'")
            return 1
    print(f"찾지 못함: {want}")
    return 1


def main():
    cmd = sys.argv[1]
    if cmd == "texts":
        root = dump()
        print(" | ".join(t.replace("\n", " ") for t, _, _ in nodes(root)) if root is not None else "(화면을 읽지 못함)")
        return 0
    if cmd == "moved":
        return moved(sys.argv[2])
    match = matcher(sys.argv[2])
    for _ in range(int(os.environ.get("UI_TRIES", "8"))):
        root = dump()
        if root is not None:
            found = [(text, x, y) for text, x, y in nodes(root) if match(text)]
            if found:
                text, x, y = found[-1] if cmd == "taplast" else found[0]
                if cmd == "longtap":
                    subprocess.run(["adb", "shell", "input", "swipe", str(x), str(y), str(x), str(y), "900"])
                else:
                    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
                print(f"{cmd} '{text}' ({x},{y})")
                return 0
        time.sleep(1)
    print(f"찾지 못함: {sys.argv[2]}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
