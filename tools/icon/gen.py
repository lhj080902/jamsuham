# 픽셀 잠수함 아이콘 생성기: 격자 그림 → 안드로이드 VectorDrawable + 미리보기 SVG
import sys
ART = [
    "............B...",
    "......PPP.....B.",
    "......P.........",
    ".....LYYYY......",
    ".....YYYYY......",
    "..LYYYYYYYYYYY..",
    "TYYYYYYYYYYYYYY.",
    "TYYWWYYWWYYWWYYO",
    "TYYWWYYWWYYWWYYO",
    "TYYYYYYYYYYYYYY.",
    "..DDDDDDDDDDDD..",
]
COLORS = {
    "Y": "#FFFFC93C",  # 몸체 (노랑)
    "L": "#FFFFE08A",  # 밝은 면
    "P": "#FFFFC93C",  # 잠망경
    "D": "#FFC8901A",  # 아래 그림자
    "T": "#FFC8901A",  # 꼬리
    "O": "#FFC8901A",  # 앞코
    "W": "#FF9AD8FF",  # 창문
    "B": "#FF9AD8FF",  # 물방울
}
def rects(cell, ox, oy, keys):
    out = {}
    for y, row in enumerate(ART):
        for x, ch in enumerate(row):
            if ch in keys:
                c = keys[ch]
                out.setdefault(c, []).append(f"M{ox+x*cell:g},{oy+y*cell:g}h{cell:g}v{cell:g}h-{cell:g}z")
    return out
def vector(size, cell, ox, oy, keys, comment):
    paths = rects(cell, ox, oy, keys)
    body = "\n".join(f'    <path android:fillColor="{c}" android:pathData="{"".join(p)}" />' for c, p in paths.items())
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} (tools 쪽 gen.py 로 만든 픽셀 그림: 격자 한 칸 = {cell:g}) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="{size}"
    android:viewportHeight="{size}">
{body}
</vector>
'''
W, H = len(ART[0]), len(ART)
# 앱 아이콘 전경: 108 격자, 안전 영역(지름 66) 안에 들어가게 칸 3.5
cell = 3.5
ox, oy = (108 - W*cell)/2, (108 - H*cell)/2
open("ic_launcher_foreground.xml","w").write(vector(108, cell, ox, oy, COLORS, "앱 아이콘 전경: 픽셀 잠수함"))
# 알림 아이콘: 24 격자, 흰색 한 색 (안드로이드가 색을 입힘)
white = {k: "#FFFFFFFF" for k in COLORS}
white["W"] = None
white = {k:v for k,v in white.items() if v}
c2 = 1.4
open("ic_stat_afk.xml","w").write(vector(24, c2, (24-W*c2)/2, (24-H*c2)/2, white, "알림 아이콘: 픽셀 잠수함 (흰색)"))
# 미리보기 SVG (512, 배경 + 원형 마스크 흉내)
def svg(size, bg, circle):
    s = size/108
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108" shape-rendering="crispEdges">']
    if circle: parts.append('<defs><clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#c)">')
    parts.append(f'<rect width="108" height="108" fill="{bg}"/>')
    for c, ps in rects(cell, ox, oy, COLORS).items():
        parts.append(f'<path fill="#{c[3:]}" d="{"".join(ps)}"/>')
    if circle: parts.append('</g>')
    parts.append('</svg>')
    return "".join(parts)
open("preview_square.svg","w").write(svg(512, "#0B2A3D", False))
open("preview_round.svg","w").write(svg(512, "#0B2A3D", True))
print("ok", W, H, ox, oy)
