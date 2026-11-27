#!/usr/bin/env python3
"""찍은 스크린샷을 Play 스토어에 올릴 모양으로 다듬는다 (제자리에서 고침).

  finish.py <shots 폴더>

- Play 는 투명도가 있는 PNG 를 받지 않는다 → 24비트(RGB)로 바꾼다
- 태블릿(2560x1600): 화면 아래 작업 표시줄에 다른 앱 아이콘(지메일·크롬 등)이 같이 찍힌다 → 그 줄(아래 120px)을 잘라 낸다
- 00-*.png 는 접속 전 확인용이라 지운다
"""
import glob
import os
import sys

from PIL import Image

TASKBAR = 120  # 2560x1600(320dpi) 태블릿의 작업 표시줄 높이


def main():
    root = sys.argv[1]
    for path in sorted(glob.glob(os.path.join(root, "**", "*.png"), recursive=True)):
        if os.path.basename(path).startswith("00-"):
            os.remove(path)
            continue
        img = Image.open(path).convert("RGB")
        if img.size == (2560, 1600):
            img = img.crop((0, 0, 2560, 1600 - TASKBAR))
        img.save(path, optimize=True)
        print(os.path.relpath(path, root), img.size)


if __name__ == "__main__":
    main()
