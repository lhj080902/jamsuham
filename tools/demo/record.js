// 앱 안 체험 서버(core 의 DemoServer)가 다시 보낼 장면을 녹화한다.
// 장면은 스토어 스크린샷과 같은 tools/test-server/showcase.js (지어낸 서버·닉네임만).
//
// 1) 장면 서버:  cd tools/test-server && DEMO=1 SHOWCASE_MENU_FILE=<메뉴 파일> node server.js 1.21.4 <포트> showcase
//               (영어판은 SCENE_LANG=en 을 더한다)
// 2) 녹화:      node tools/demo/record.js <포트> <메뉴 파일> <출력.bin.gz>
// 결과는 core/src/main/resources/jamsuham/demo/scene-ko.bin.gz, scene-en.bin.gz 에 둔다.
// 앱이 말하는 프로토콜(1.21.4)을 올리면 다시 녹화해야 한다.
//
// 파일 형식 (gzip 안에 레코드가 이어짐):
//   segment u8 · delayMs varint · length varint · bytes (패킷 번호 varint + 내용, 압축 안 함)
//   segment: 0 login(로그인 성공) 1 config 2 play 3 menu(메뉴 열기) 4 slot(인벤토리 한 칸씩)
//   delayMs: 같은 segment 의 앞 레코드에서 지난 시간 (play 만 뜻이 있고 나머지는 0)
//   slot: 창 0 · stateId 1 의 set_slot 패킷 = [번호][창 0][stateId 1][칸 2바이트][개수]… → 체험 서버가 개수 바이트만 바꿔 다시 보낸다
//   넣지 않는 것: login 의 압축 설정, play 의 keep_alive·ping
const path = require('path')
const fs = require('fs')
const zlib = require('zlib')
const mc = require(require.resolve('minecraft-protocol', { paths: [path.join(__dirname, '../test-server')] }))

const [portStr, menuFile, outFile] = process.argv.slice(2)
if (!outFile) { console.error('사용법: node record.js <포트> <메뉴 파일> <출력.bin.gz>'); process.exit(2) }
const VERSION = '1.21.4'
const SEG = { login: 0, config: 1, play: 2, menu: 3, slot: 4 }
const MENU_WINDOW = 9
const IDLE_BEFORE_MENU_MS = 4000 // 장면 안의 가장 긴 쉬는 틈(2.6초)보다 길게
const IDLE_AFTER_MENU_MS = 2000

const records = []
const count = { login: 0, config: 0, play: 0, menu: 0, slot: 0 }
const add = (seg, delay, buf) => { records.push({ seg: SEG[seg], delay, buf: Buffer.from(buf) }); count[seg]++ }
let lastPlayAt = 0
let lastPacketAt = Date.now()
let sawChat = false
let menuAsked = false
let inventory = null

const client = mc.createClient({ host: '127.0.0.1', port: Number(portStr), username: 'Jamsu_Diver', version: VERSION, auth: 'offline' })
client.on('error', (e) => { console.error('오류:', e.message); process.exit(1) })
client.on('end', (r) => { console.error('서버가 연결을 끊음:', r); process.exit(1) })
client.on('packet', (data, meta, buffer) => {
  if (meta.name === 'keep_alive' || meta.name === 'ping') return
  if (meta.state === 'login') { if (meta.name === 'success') add('login', 0, buffer); return }
  if (meta.state === 'configuration') { add('config', 0, buffer); return }
  if (meta.state !== 'play') return
  const now = Date.now()
  lastPacketAt = now
  const forMenu = meta.name === 'open_window' || ((meta.name === 'window_items' || meta.name === 'set_slot') && data.windowId === MENU_WINDOW)
  if (forMenu) { add('menu', 0, buffer); return }
  if (meta.name === 'window_items' && data.windowId === 0) inventory = data.items
  if (meta.name === 'system_chat' || meta.name === 'player_chat') sawChat = true
  add('play', lastPlayAt ? now - lastPlayAt : 0, buffer)
  lastPlayAt = now
})

const started = Date.now()
const timer = setInterval(() => {
  const idle = Date.now() - lastPacketAt
  if (!menuAsked && sawChat && idle > IDLE_BEFORE_MENU_MS) {
    menuAsked = true
    lastPacketAt = Date.now()
    fs.writeFileSync(menuFile, '')
  } else if (menuAsked && count.menu > 0 && idle > IDLE_AFTER_MENU_MS) {
    clearInterval(timer)
    finish()
  } else if (Date.now() - started > 60000) {
    console.error('시간 초과: 장면이 끝나지 않음', JSON.stringify(count))
    process.exit(1)
  }
}, 250)

function varint (v) { const b = []; do { let x = v & 0x7f; v >>>= 7; if (v) x |= 0x80; b.push(x) } while (v); return Buffer.from(b) }

function finish () {
  if (!inventory) { console.error('인벤토리를 받지 못함'); process.exit(1) }
  // 인벤토리 한 칸씩: 서버가 보내는 것과 같은 직렬화기로 set_slot 을 만든다
  const serializer = mc.createSerializer({ state: 'play', isServer: true, version: VERSION, customPackets: {} })
  inventory.forEach((item, slot) => {
    if (!item || !item.itemCount) return
    const buf = serializer.createPacketBuffer({ name: 'set_slot', params: { windowId: 0, stateId: 1, slot, item } })
    const ok = buf[1] === 0 && buf[2] === 1 && buf.readInt16BE(3) === slot && buf[5] === item.itemCount
    if (!ok) { console.error('set_slot 모양이 예상과 다름', slot, buf.subarray(0, 8).toString('hex')); process.exit(1) }
    add('slot', 0, buf)
  })
  const raw = Buffer.concat(records.flatMap(r => [Buffer.from([r.seg]), varint(r.delay), varint(r.buf.length), r.buf]))
  const gz = zlib.gzipSync(raw, { level: 9 })
  fs.writeFileSync(outFile, gz)
  console.log(`RECORDED login=${count.login} config=${count.config} play=${count.play} menu=${count.menu} slot=${count.slot} bytes=${raw.length} gz=${gz.length}`)
  process.exit(0)
}
