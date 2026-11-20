// 테스트용 마인크래프트 서버 (node-minecraft-protocol)
// node server.js <version> <port> [scenario]
// scenario: full (기본) = 1번째 접속: "already connected" 킥 / 2번째: 소켓 강제 끊기 / 3번째: 밴 킥
//           stay = 계속 유지
const mc = require('minecraft-protocol')
const nbt = require('prismarine-nbt')
const crypto = require('crypto')

const [version, portStr, scenario = 'full'] = process.argv.slice(2)
const server = mc.createServer({
  'online-mode': false,
  encryption: true,
  host: '127.0.0.1',
  port: Number(portStr),
  version,
  compressionThreshold: 64,
  motd: '§b잠수 테스트 서버',
  maxPlayers: 20
})
const mcData = require('minecraft-data')(server.version)
const nbtChat = mcData.supportFeature('chatPacketsUseNbtComponents')
const log = (...a) => console.log(`[srv ${version}]`, ...a)
const text = (s) => nbtChat ? nbt.comp({ text: nbt.string(s) }) : JSON.stringify({ text: s })
let joins = 0

server.on('listening', () => log('listening', portStr))
server.on('error', (e) => log('server error', e.message))

server.on('playerJoin', (client) => {
  joins++
  const n = joins
  log(`JOIN #${n}`, client.username, 'proto', client.protocolVersion)
  const counts = {}
  client.on('packet', (data, meta) => {
    counts[meta.name] = (counts[meta.name] || 0) + 1
    if (['keep_alive', 'tick_end', 'chunk_batch_received'].includes(meta.name)) return
    if (meta.name === 'position' && counts.position > 2) return
    log(`C->S #${n}`, meta.state, meta.name, JSON.stringify(data, (k, v) => typeof v === 'bigint' ? v.toString() : v).slice(0, 160))
  })
  // 명령어 자동완성 테스트: 입력의 첫 단어 자리에 후보를 돌려준다
  client.on('tab_complete', (data) => {
    const cmds = ['spawn', 'server', '잠수', 'warp']
    if (mcData.isNewerOrEqualTo('1.13')) {
      const text = data.text || ''
      const word = text.replace(/^\//, '')
      const matches = cmds.filter(c => c.startsWith(word)).map(c => ({ match: c, tooltip: undefined }))
      client.write('tab_complete', { transactionId: data.transactionId, start: text.startsWith('/') ? 1 : 0, length: word.length, matches })
    } else {
      const word = (data.text || '').replace(/^\//, '')
      client.write('tab_complete', { matches: cmds.filter(c => c.startsWith(word)).map(c => '/' + c) })
    }
  })
  client.on('end', (r) => log(`END #${n}`, r || '', 'counts', JSON.stringify(counts)))
  client.on('error', (e) => log(`ERR #${n}`, e.message))

  try {
    sendLogin(client)
    sendPosition(client, 10.5, 64, -3.5)
  } catch (e) {
    log('login write failed', e.stack)
  }
  setTimeout(() => safe(() => sendChat(client, '§a어서오세요 §e테스트 서버입니다')), 300)
  setTimeout(() => safe(() => sendPlayerChat(client, 'Steve', '반가워요')), 500)
  setTimeout(() => safe(() => sendResourcePack(client)), 700)
  setTimeout(() => safe(() => sendPosition(client, 100.5, 70, 100.5)), 1200) // 강제 텔포
  setTimeout(() => safe(() => sendPlayers(client)), 900)
  setTimeout(() => safe(() => openPackMenu(client)), 1500)
  setTimeout(() => safe(() => sendInventory(client)), 1000)
  client.on('window_click', (d) => {
    log(`MENU CLICK #${n} slot=${d.slot} window=${d.windowId}`)
    if (d.slot === 13) safe(() => { client.write('close_window', { windowId: 7 }); sendChat(client, '§a리소스팩이 적용되었습니다') })
  })

  if (scenario === 'full') {
    setTimeout(() => {
      if (n === 1) { log('>> kick already connected'); kick(client, 'You are already connected to this proxy!') }
      if (n === 2) { log('>> destroy socket'); client.socket.destroy() }
      if (n >= 3) { log('>> kick banned'); kick(client, '§cYou are banned from this server.') }
    }, 5000)
  }
})

function safe (fn) { try { fn() } catch (e) { log('write error', e.message) } }

function sendLogin (client) {
  if (mcData.loginPacket) {
    const lp = mcData.loginPacket
    client.write('login', {
      ...lp,
      entityId: client.id,
      isHardcore: false,
      gameMode: 0,
      previousGameMode: 1,
      worldName: 'minecraft:overworld',
      hashedSeed: [0, 0],
      maxPlayers: 20,
      viewDistance: 10,
      reducedDebugInfo: false,
      enableRespawnScreen: true,
      isDebug: false,
      isFlat: false,
      enforcesSecureChat: false
    })
  } else {
    client.write('login', {
      entityId: client.id, gameMode: 0, dimension: 0, difficulty: 1, maxPlayers: 20, levelType: 'default', reducedDebugInfo: false
    })
  }
}

let tp = 1
function sendPosition (client, x, y, z) {
  const ver = mcData.version
  if (mcData.isNewerOrEqualTo('1.21.2')) {
    client.write('position', { teleportId: tp++, x, y, z, dx: 0, dy: 0, dz: 0, yaw: 90, pitch: 0, flags: {} })
  } else if (mcData.isNewerOrEqualTo('1.9')) {
    client.write('position', { x, y, z, yaw: 90, pitch: 0, flags: 0, teleportId: tp++, dismountVehicle: false })
  } else {
    client.write('position', { x, y, z, yaw: 90, pitch: 0, flags: 0 })
  }
  void ver
}

function sendChat (client, s) {
  if (mcData.isNewerOrEqualTo('1.19')) {
    client.write('system_chat', { content: text(s), isActionBar: false, type: 1, formattedMessage: JSON.stringify({ text: s }) })
  } else {
    client.write('chat', { message: JSON.stringify({ text: s }), position: 1, sender: '00000000-0000-0000-0000-000000000000' })
  }
}

function sendPlayerChat (client, name, msg) {
  if (!mcData.isNewerOrEqualTo('1.21.2')) return
  client.write('player_chat', {
    globalIndex: 0,
    senderUuid: '11111111-2222-3333-4444-555555555555',
    index: 0,
    signature: undefined,
    plainMessage: msg,
    timestamp: BigInt(Date.now()),
    salt: 0n,
    previousMessages: [],
    unsignedChatContent: undefined,
    filterType: 0,
    type: { chatType: 1 },
    networkName: text(name),
    networkTargetName: undefined
  })
}

// 탭 목록 테스트: 스킨 있는 정품 플레이어 1명 + 색 이름 1명 + 목록 위/아래 문구
function sendPlayers (client) {
  const skin = Buffer.from(JSON.stringify({ textures: { SKIN: { url: 'http://textures.minecraft.net/texture/1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b' } } })).toString('base64')
  const people = [
    { uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', name: 'Notch', props: [{ name: 'textures', value: skin, signature: undefined }], ping: 42, display: null },
    { uuid: '22222222-3333-4444-5555-666666666666', name: 'Jamsu_Kim', props: [], ping: 120, display: '§6[VIP] §fJamsu_Kim' }
  ]
  if (mcData.isNewerOrEqualTo('1.21.4')) {
    client.write('player_info', {
      action: { add_player: true, update_listed: true, update_latency: true, update_display_name: true },
      data: people.map(p => ({ uuid: p.uuid, player: { name: p.name, properties: p.props }, listed: 1, latency: p.ping, displayName: p.display ? text(p.display) : undefined }))
    })
    client.write('playerlist_header', { header: text('§b§l잠수 테스트 서버'), footer: text('§7접속자 2명') })
  } else if (!mcData.isNewerOrEqualTo('1.19.3')) {
    client.write('player_info', {
      action: 0,
      data: people.map(p => ({ uuid: p.uuid, name: p.name, properties: p.props, gamemode: 0, ping: p.ping, displayName: p.display ? JSON.stringify({ text: p.display }) : undefined }))
    })
    client.write('playerlist_header', { header: JSON.stringify({ text: '§b§l잠수 테스트 서버' }), footer: JSON.stringify({ text: '§7접속자 2명' }) })
  }
}

// 인벤토리 보기 테스트: 핫바에 다이아 검, 머리에 철 투구, 가방에 리소스팩 글꼴 이름 아이템
function sendInventory (client) {
  if (mcData.isNewerOrEqualTo('1.21.4')) {
    const it = n => mcData.itemsByName[n].id
    const items = Array(46).fill({ itemCount: 0 })
    items[36] = { itemCount: 1, itemId: it('diamond_sword'), addedComponentCount: 0, removedComponentCount: 0, components: [], removeComponents: [] }
    items[5] = { itemCount: 1, itemId: it('iron_helmet'), addedComponentCount: 0, removedComponentCount: 0, components: [], removeComponents: [] }
    items[9] = { itemCount: 3, itemId: it('paper'), addedComponentCount: 1, removedComponentCount: 0,
      components: [{ type: 'custom_name', data: nbt.comp({ text: nbt.string('\ue001'), font: nbt.string('dos:icons') }) }], removeComponents: [] }
    client.write('window_items', { windowId: 0, stateId: 1, items, carriedItem: { itemCount: 0 } })
    client.write('set_player_inventory', { slotId: 1, contents: { itemCount: 64, itemId: it('cobblestone'), addedComponentCount: 0, removedComponentCount: 0, components: [], removeComponents: [] } })
    client.write('held_item_slot', { slot: 1 })
  } else if (!mcData.isNewerOrEqualTo('1.13')) {
    const items = Array(46).fill({ blockId: -1 })
    items[36] = { blockId: 276, itemCount: 1, itemDamage: 0 }
    items[5] = { blockId: 306, itemCount: 1, itemDamage: 0 }
    items[37] = { blockId: 4, itemCount: 64, itemDamage: 0 }
    client.write('window_items', { windowId: 0, items })
    client.write('held_item_slot', { slot: 1 })
  }
}

// 도스온라인처럼 접속하면 리소스팩 적용 메뉴(상자 GUI)를 띄운다. 13번 칸을 누르면 적용
function openPackMenu (client) {
  const title = '§2리소스팩 적용'
  if (mcData.isNewerOrEqualTo('1.21.4')) {
    client.write('open_window', { windowId: 7, inventoryType: 2, windowTitle: text(title) })
    const lime = mcData.itemsByName.lime_concrete.id
    const red = mcData.itemsByName.red_concrete.id
    const mk = (id, name, lore) => ({ itemCount: 1, itemId: id, addedComponentCount: 2, removedComponentCount: 0,
      components: [{ type: 'custom_name', data: text(name) }, { type: 'lore', data: lore.map(l => text(l)) }], removeComponents: [] })
    const items = Array(27 + 36).fill({ itemCount: 0 })
    items[11] = mk(red, '§c취소', ['§7접속을 끊습니다'])
    items[13] = mk(lime, '§a리소스팩 적용하기', ['§7클릭하면 리소스팩이 적용됩니다'])
    client.write('window_items', { windowId: 7, stateId: 1, items, carriedItem: { itemCount: 0 } })
  } else if (!mcData.isNewerOrEqualTo('1.13')) {
    client.write('open_window', { windowId: 7, inventoryType: 'minecraft:chest', windowTitle: JSON.stringify({ text: title }), slotCount: 27 })
    const mk = (dmg, name, lore) => ({ blockId: 251, itemCount: 1, itemDamage: dmg, nbtData: { type: 'compound', name: '', value: { display: { type: 'compound', value: {
      Name: { type: 'string', value: name }, Lore: { type: 'list', value: { type: 'string', value: lore } } } } } } })
    const items = Array(27 + 36).fill({ blockId: -1 })
    items[11] = mk(14, '§c취소', ['§7접속을 끊습니다'])
    items[13] = mk(5, '§a리소스팩 적용하기', ['§7클릭하면 리소스팩이 적용됩니다'])
    client.write('window_items', { windowId: 7, items })
  }
}

function sendResourcePack (client) {
  if (mcData.isNewerOrEqualTo('1.20.3')) {
    client.write('add_resource_pack', {
      uuid: crypto.randomUUID(), url: 'https://example.com/pack.zip', hash: 'a'.repeat(40), forced: true, promptMessage: undefined
    })
  } else {
    client.write('resource_pack_send', { url: 'https://example.com/pack.zip', hash: 'a'.repeat(40) })
  }
}

function kick (client, s) {
  const reason = nbtChat ? text(s) : JSON.stringify({ text: s })
  client.write('kick_disconnect', { reason })
  setTimeout(() => client.end(), 100)
}
