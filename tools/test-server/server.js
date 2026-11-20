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
