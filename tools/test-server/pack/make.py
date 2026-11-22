import zlib, struct, zipfile, json, os
def png(w,h,pix):  # pix(x,y)->(r,g,b,a)
    raw=b''.join(b'\x00'+b''.join(bytes(pix(x,y)) for x in range(w)) for y in range(h))
    def chunk(t,d): return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
# icons.png: 16x8, 칸 2개(8x8). 0번 칸 = 빨강 6x6, 1번 칸 = 파랑 4x8
icons=png(16,8,lambda x,y:(255,0,0,255) if x<6 and 1<=y<7 else ((0,0,255,255) if 8<=x<12 else (0,0,0,0)))
rank=png(20,10,lambda x,y:(255,200,0,255) if 1<=x<19 and 1<=y<9 else (0,0,0,0))
with zipfile.ZipFile('pack.zip','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('pack.mcmeta',json.dumps({"pack":{"pack_format":46,"description":"test"}}))
    z.writestr('assets/minecraft/font/default.json',json.dumps({"providers":[{"type":"bitmap","file":"minecraft:font/icons.png","ascent":7,"height":8,"chars":["鱗"]}]},ensure_ascii=False))
    z.writestr('assets/server/font/rank.json',json.dumps({"providers":[{"type":"reference","id":"minecraft:default"},{"type":"bitmap","file":"server:font/rank.png","ascent":8,"height":10,"chars":[""]}]},ensure_ascii=False))
    z.writestr('assets/minecraft/textures/font/icons.png',icons)
    z.writestr('assets/server/textures/font/rank.png',rank)
    z.writestr(zipfile.ZipInfo('assets/minecraft/sounds/filler.ogg'),os.urandom(5*1024*1024),compress_type=zipfile.ZIP_STORED)
print(os.path.getsize('pack.zip'))
