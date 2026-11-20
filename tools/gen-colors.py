#!/usr/bin/env python3
"""
미니맵 블록 색 테이블 만들기 (1.21.4 블록 상태 번호 → ARGB 색).

필요한 것:
  - minecraft-data 의 blocks.json (블록 이름 ↔ 상태 번호 범위)
  - 마인크래프트 에셋 (blockstates / models/block / textures/block) — 예: InventivetalentDev/minecraft-assets 1.21.4

사용법:
  python3 tools/gen-colors.py <blocks.json> <assets/minecraft 폴더> core/src/main/resources/jamsuham/colors-1.21.4.bin

출력 형식 (gzip): int 상태 개수 N, 그 뒤 N 개의 int ARGB (빅엔디언). 0 = 투명(공기 등).
색은 위에서 본 면(top/end/all...) 텍스처의 평균색. 잔디·잎·물처럼 바이옴 색을 입히는 블록은 기본 바이옴 색을 곱한다.
"""
import gzip
import json
import os
import struct
import sys

from PIL import Image

blocks_json, assets, out_path = sys.argv[1:4]

TRANSPARENT = {"air", "cave_air", "void_air", "light", "barrier", "structure_void"}
GRASS = (0x91, 0xBD, 0x59)
FOLIAGE = (0x77, 0xAB, 0x2F)
TINTS = {
    "grass_block": GRASS, "short_grass": GRASS, "tall_grass": GRASS, "fern": GRASS, "large_fern": GRASS,
    "sugar_cane": GRASS, "potted_fern": GRASS,
    "oak_leaves": FOLIAGE, "jungle_leaves": FOLIAGE, "acacia_leaves": FOLIAGE, "dark_oak_leaves": FOLIAGE,
    "mangrove_leaves": (0x8D, 0xB1, 0x27), "vine": FOLIAGE,
    "birch_leaves": (0x80, 0xA7, 0x55), "spruce_leaves": (0x61, 0x99, 0x61),
    "water": (0x3F, 0x76, 0xE4), "water_cauldron": (0x3F, 0x76, 0xE4), "bubble_column": (0x3F, 0x76, 0xE4),
    "lily_pad": (0x20, 0x80, 0x30), "redstone_wire": (0xC0, 0x10, 0x10),
    "attached_melon_stem": (0xE0, 0xC7, 0x1C), "attached_pumpkin_stem": (0xE0, 0xC7, 0x1C),
    "melon_stem": (0x60, 0xA0, 0x30), "pumpkin_stem": (0x60, 0xA0, 0x30),
}
# 텍스처를 못 찾거나 이상하게 나오는 블록은 직접 지정
FIXED = {
    "water": (0x3F, 0x76, 0xE4), "lava": (0xD5, 0x5A, 0x12), "bubble_column": (0x3F, 0x76, 0xE4),
    "fire": (0xE0, 0x8A, 0x20), "soul_fire": (0x40, 0xC0, 0xD0), "nether_portal": (0x7A, 0x2A, 0xC8),
    "end_portal": (0x10, 0x18, 0x20), "end_gateway": (0x10, 0x18, 0x20), "moving_piston": (0x99, 0x88, 0x66),
}
KEY_PRIORITY = ["top", "end", "up", "all", "texture", "cross", "plant", "crop", "flower", "particle", "side"]

def load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return None

def model_textures(name, depth=0):
    """모델과 부모 모델의 textures 를 합친다 (자식이 우선)."""
    if depth > 10:
        return {}
    name = name.replace("minecraft:", "")
    m = load_json(os.path.join(assets, "models", name + ".json"))
    if not m:
        return {}
    tex = {}
    if "parent" in m:
        tex.update(model_textures(m["parent"], depth + 1))
    tex.update(m.get("textures", {}))
    return tex

def resolve(tex, key, depth=0):
    v = tex.get(key)
    while isinstance(v, str) and v.startswith("#") and depth < 10:
        v = tex.get(v[1:])
        depth += 1
    return v

def first_model(block):
    bs = load_json(os.path.join(assets, "blockstates", block + ".json"))
    if not bs:
        return None
    if "variants" in bs:
        v = next(iter(bs["variants"].values()))
    elif "multipart" in bs:
        v = bs["multipart"][0]["apply"]
    else:
        return None
    if isinstance(v, list):
        v = v[0]
    return v.get("model")

def avg_color(tex_name):
    tex_name = tex_name.replace("minecraft:", "")
    path = os.path.join(assets, "textures", tex_name + ".png")
    if not os.path.exists(path):
        return None
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    img = img.crop((0, 0, w, min(w, h)))  # 움직이는 텍스처는 첫 장면만
    r = g = b = n = 0
    for (pr, pg, pb, pa) in img.getdata():
        if pa < 30:
            continue
        r += pr; g += pg; b += pb; n += 1
    if n == 0:
        return None
    return (r // n, g // n, b // n)

def block_color(block):
    if block in TRANSPARENT:
        return None
    if block in FIXED:
        return FIXED[block]
    model = first_model(block)
    if not model:
        return None
    tex = model_textures(model)
    for key in KEY_PRIORITY + list(tex.keys()):
        t = resolve(tex, key)
        if isinstance(t, str) and not t.startswith("#"):
            c = avg_color(t)
            if c:
                if block in TINTS:
                    tr, tg, tb = TINTS[block]
                    c = (c[0] * tr // 255, c[1] * tg // 255, c[2] * tb // 255)
                return c
    return None

blocks = load_json(blocks_json)
n = max(b["maxStateId"] for b in blocks) + 1
colors = [0] * n
missing = []
for b in blocks:
    c = block_color(b["name"])
    if c is None:
        if b["name"] not in TRANSPARENT:
            missing.append(b["name"])
        continue
    argb = 0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2]
    for s in range(b["minStateId"], b["maxStateId"] + 1):
        colors[s] = argb

with gzip.open(out_path, "wb") as f:
    f.write(struct.pack(">i", n))
    f.write(struct.pack(">%di" % n, *[c - (1 << 32) if c >= (1 << 31) else c for c in colors]))
print("states", n, "missing", len(missing), missing[:40])
