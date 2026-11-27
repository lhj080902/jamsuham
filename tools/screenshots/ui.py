#!/usr/bin/env python3
"""에뮬레이터 화면에서 글자를 찾아 누른다 (스토어 스크린샷 스크립트 shoot.sh 가 씀).

  ui.py tap <글자>      글자가 정확히 같은 첫 항목을 누른다. '^글자' 면 그 글자로 시작하는 항목
  ui.py texts           지금 화면에 보이는 글자 모두 출력 (문제 찾기용)

화면이 계속 바뀌는 중이면 uiautomator 가 실패할 수 있어 몇 번 다시 시도한다.
"""
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


def main():
    cmd = sys.argv[1]
    if cmd == "texts":
        root = dump()
        print(" | ".join(t.replace("\n", " ") for t, _, _ in nodes(root)) if root is not None else "(화면을 읽지 못함)")
        return 0
    want = sys.argv[2]
    prefix = want.startswith("^")
    want = want[1:] if prefix else want
    for _ in range(8):
        root = dump()
        if root is not None:
            for text, x, y in nodes(root):
                if (text.startswith(want) if prefix else text == want):
                    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
                    print(f"tap '{text}' ({x},{y})")
                    return 0
        time.sleep(1)
    print(f"찾지 못함: {sys.argv[2]}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
