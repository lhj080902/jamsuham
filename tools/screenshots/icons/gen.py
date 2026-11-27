"""스크린샷용 시험 서버 아이콘 3개 (64x64, 16x16 픽셀 그림을 4배). 실제 서버 아이콘이 아니라 지어낸 그림이다.
python gen.py  → sea.png, sky.png, blocks.png"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))


def draw(name, rows, colors, bg):
    img = Image.new("RGB", (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            img.putpixel((x, y), bg(y) if ch == "." else colors[ch])
    img.resize((64, 64), Image.NEAREST).save(os.path.join(HERE, name + ".png"))


def grad(top, bottom):
    return lambda y: tuple(int(top[i] + (bottom[i] - top[i]) * y / 15) for i in range(3))


draw("sea", [
    "................",
    "..........o.....",
    "....o...........",
    "................",
    "....FFFFFF...T..",
    "...FFFFFFFF.TT..",
    "..FFEFFFFFFTTT..",
    "..FFFFFFFFFTTT..",
    "...FFFFFFFF.TT..",
    "....FFFFFF...T..",
    "................",
    ".w............w.",
    ".ww....s.....ww.",
    "SSSSSSSSSSSSSSSS",
    "SSSSSSSSSSSSSSSS",
    "SSSSSSSSSSSSSSSS",
], {"F": (245, 166, 35), "T": (214, 126, 20), "E": (30, 30, 40), "o": (200, 240, 255), "w": (60, 170, 90), "S": (227, 211, 163), "s": (150, 150, 150)},
    grad((40, 170, 190), (20, 90, 150)))

draw("sky", [
    "................",
    "..CCC...........",
    ".CCCCC.LL...CC..",
    "..CCC.LLLL.CCCC.",
    ".......TT.......",
    "....GGGGGGGG....",
    "...GGGGGGGGGG...",
    "...DDDDDDDDDD...",
    "....DDDDDDDD....",
    ".....DDDDDD.....",
    "......DDDD......",
    ".......DD.......",
    ".CC.............",
    "CCCC.......CCC..",
    ".CC.......CCCCC.",
    "................",
], {"C": (255, 255, 255), "G": (93, 187, 75), "D": (139, 90, 43), "L": (46, 139, 58), "T": (110, 70, 30)},
    grad((90, 180, 255), (170, 220, 255)))

draw("blocks", [
    "................",
    ".RRRRRR..YYYYYY.",
    ".RrrrrR..YyyyyY.",
    ".RrrrrR..YyyyyY.",
    ".RrrrrR..YyyyyY.",
    ".RrrrrR..YyyyyY.",
    ".RRRRRR..YYYYYY.",
    "................",
    "................",
    ".GGGGGG..BBBBBB.",
    ".GggggG..BbbbbB.",
    ".GggggG..BbbbbB.",
    ".GggggG..BbbbbB.",
    ".GggggG..BbbbbB.",
    ".GGGGGG..BBBBBB.",
    "................",
], {"R": (170, 40, 40), "r": (230, 70, 70), "Y": (190, 150, 20), "y": (250, 210, 60), "G": (40, 130, 60), "g": (80, 200, 100), "B": (40, 80, 180), "b": (80, 140, 250)},
    lambda y: (31, 37, 64))
