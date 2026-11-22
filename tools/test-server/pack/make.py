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
# overlay.zip: pack.mcmeta 오버레이 (1.21.4 용 폴더가 기본 글꼴을 덮어씀, 옛 버전 폴더는 무시), BOM·주석이 있는 json
with zipfile.ZipFile('overlay.zip','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('pack.mcmeta',json.dumps({"pack":{"pack_format":34,"supported_formats":[34,99],"description":"ov"},
        "overlays":{"entries":[{"formats":[18,45],"directory":"old"},{"formats":{"min_inclusive":46,"max_inclusive":99},"directory":"ov_new"}]}}))
    z.writestr('assets/minecraft/font/default.json',json.dumps({"providers":[{"type":"bitmap","file":"minecraft:font/icons.png","ascent":7,"height":8,"chars":[""]}]}))
    z.writestr('ov_new/assets/minecraft/font/default.json','﻿// 1.21.4 용\n'+json.dumps({"providers":[{"type":"bitmap","file":"minecraft:font/new.png","ascent":7,"height":8,"chars":[""]}]}))
    z.writestr('old/assets/minecraft/font/default.json',json.dumps({"providers":[{"type":"bitmap","file":"minecraft:font/icons.png","ascent":7,"height":8,"chars":[""]}]}))
    z.writestr('assets/minecraft/textures/font/icons.png',icons)
    z.writestr('ov_new/assets/minecraft/textures/font/new.png',icons)
print(os.path.getsize('overlay.zip'))
# broken.zip: 그림 글자가 0개가 되는 팩 (그림 파일이 없는 글꼴, ttf 글꼴) → 진단 요약 확인용
with zipfile.ZipFile('broken.zip','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('pack.mcmeta',json.dumps({"pack":{"pack_format":46,"description":"broken"}}))
    z.writestr('assets/minecraft/font/default.json',json.dumps({"providers":[{"type":"bitmap","file":"minecraft:font/nothere.png","ascent":7,"chars":[""]},{"type":"ttf","file":"minecraft:x.ttf"}]}))
    z.writestr('assets/minecraft/textures/block/stone.png',icons)
print(os.path.getsize('broken.zip'))
# 보호된 팩 흉내: (1) 앞에 쓰레기를 붙여 목록 위치가 어긋남 (2) 끝 기록의 목록 위치를 엉터리로 (3) 로컬 헤더 extra 를 크게
import struct as _st
data=open('pack.zip','rb').read()
open('prefixed.zip','wb').write(os.urandom(5000)+data)
i=data.rfind(b'PK\x05\x06'); bad=bytearray(data); bad[i+16:i+20]=_st.pack('<I',0x12345)
open('fakeoffset.zip','wb').write(bytes(bad))
with zipfile.ZipFile('bigextra.zip','w',zipfile.ZIP_DEFLATED) as z:
    for info in zipfile.ZipFile('pack.zip').infolist():
        if info.filename.endswith('.ogg'): continue
        zi=zipfile.ZipInfo(info.filename); zi.compress_type=zipfile.ZIP_DEFLATED
        zi.extra=_st.pack('<HH',0xCAFE,3000)+os.urandom(3000)
        z.writestr(zi, zipfile.ZipFile('pack.zip').read(info.filename))
print('protected ok')
# fakesize.zip: 목록·로컬 헤더의 '압축 전 크기'를 엉터리(10바이트)로 적은 팩 (도스온라인처럼)
d=bytearray(open('pack.zip','rb').read())
p=0
while True:
    p=d.find(b'PK\x01\x02',p)
    if p<0: break
    nl=_st.unpack('<H',d[p+28:p+30])[0]; name=d[p+46:p+46+nl].decode()
    if name.endswith('.json'):
        d[p+24:p+28]=_st.pack('<I',10)
        lo=_st.unpack('<I',d[p+42:p+46])[0]; d[lo+22:lo+26]=_st.pack('<I',10)
    p+=4
open('fakesize.zip','wb').write(bytes(d))
# many.zip: 글꼴 120개 + 그림 120개 사이사이에 30KB 짜리 다른 파일 (묶어 받기·동시 받기 확인용, DELAY= 로 왕복 지연)
with zipfile.ZipFile('many.zip','w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('pack.mcmeta',json.dumps({"pack":{"pack_format":46,"description":"many"}}))
    for i in range(120):
        cp=0xE000+i
        z.writestr(f'assets/many/font/f{i}.json',json.dumps({"providers":[{"type":"bitmap","file":f"many:font/i{i}.png","ascent":7,"height":8,"chars":[chr(cp)]}]}))
        z.writestr(zipfile.ZipInfo(f'assets/many/sounds/s{i}.ogg'),os.urandom(30*1024),compress_type=zipfile.ZIP_STORED)
        z.writestr(f'assets/many/textures/font/i{i}.png',icons)
print('many', os.path.getsize('many.zip'))
