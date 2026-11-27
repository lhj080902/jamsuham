// 스토어 스크린샷용 장면 (1.21.4 전용). server.js 의 'showcase' 시나리오가 부른다.
// 지어낸 서버·닉네임만 쓴다 — 실제 서버 이름, 실제 사람 닉네임·스킨은 넣지 않는다.
// 메뉴는 SHOWCASE_MENU_FILE 로 지정한 파일이 생기면 연다 (스크린샷 스크립트가 원하는 때에 `touch`).
const fs = require('fs')

module.exports = function ({ mcData, nbt, text, sendChat, sendPosition, varint, safe, log }) {
  const PEOPLE = [
    { uuid: '5d3c2a1b-0000-4000-8000-00000000a1f5', name: 'Jamsu_Diver', ping: 38, display: null },
    { uuid: '0c1d2e3f-1111-4111-8111-000000000001', name: 'Haneul_Fish', ping: 52, display: '§6[VIP] §fHaneul_Fish' },
    { uuid: '0c1d2e3f-1111-4111-8111-000000000002', name: 'Coral_Min', ping: 74, display: '§b[어부] §fCoral_Min' },
    { uuid: '0c1d2e3f-1111-4111-8111-000000000003', name: 'Sea_Otter', ping: 31, display: null },
    { uuid: '0c1d2e3f-1111-4111-8111-000000000004', name: 'Moon_Jelly', ping: 120, display: '§d[MVP] §fMoon_Jelly' },
    { uuid: '0c1d2e3f-1111-4111-8111-000000000005', name: 'Blue_Whale', ping: 88, display: null },
    { uuid: '0c1d2e3f-1111-4111-8111-000000000006', name: 'Pearl_Kang', ping: 45, display: '§a[건축가] §fPearl_Kang' },
    { uuid: '0c1d2e3f-1111-4111-8111-000000000007', name: 'Tide_Runner', ping: 63, display: null }
  ]

  function showcase (client) {
    const at = (ms, fn) => setTimeout(() => safe(fn), ms)
    const sys = (s) => sendChat(client, s)
    const say = (who, msg) => playerChat(client, PEOPLE[who], msg)
    at(200, () => sendChunks(client))
    at(300, () => sendPosition(client, 10.5, 64, -3.5))
    at(400, () => sendInventory(client))
    at(500, () => sendPlayers(client))
    at(600, () => client.write('update_health', { health: 17, food: 18, foodSaturation: 3 }))
    at(700, () => sys('§e[공지] §f바다마을 야생 서버에 오신 것을 환영합니다!'))
    at(900, () => sys('§7[안내] 잠수 보상은 30분마다 지급됩니다. /잠수 로 켜고 끌 수 있어요.'))
    const onCommand = (d) => {
      if (d.command === '잠수') at(300, () => sys('§a[잠수] §f잠수 모드가 켜졌습니다. 보상을 기다리는 중…'))
    }
    client.on('chat_command', onCommand)
    client.on('chat_command_signed', onCommand)
    at(3500, () => say(1, '다들 안녕하세요 ㅎㅎ'))
    at(4000, () => say(2, '오늘 저녁 낚시 대회 몇 시예요?'))
    at(4500, () => say(3, '9시요! 1등 상품 다이아 블록이래요'))
    at(5000, () => client.write('system_chat', {
      isActionBar: false,
      content: nbt.comp({ text: nbt.string(''), extra: nbt.list(nbt.comp([
        { text: nbt.string('[텔레포트] '), color: nbt.string('aqua') },
        { text: nbt.string('Coral_Min 님이 같이 가자고 요청했습니다 ') },
        { text: nbt.string('[수락]'), color: nbt.string('green'), clickEvent: nbt.comp({ action: nbt.string('run_command'), value: nbt.string('/tpaccept') }), hoverEvent: nbt.comp({ action: nbt.string('show_text'), contents: nbt.comp({ text: nbt.string('눌러서 수락') }) }) },
        { text: nbt.string(' ') },
        { text: nbt.string('[거절]'), color: nbt.string('red'), clickEvent: nbt.comp({ action: nbt.string('run_command'), value: nbt.string('/tpdeny') }) }
      ])) })
    }))
    at(5600, () => sys('§6[보상] §f잠수 30분 보상: §b다이아몬드 x1 §f지급 완료!'))
    at(6100, () => say(4, '벌써 3시간째 잠수 중 ㅋㅋ'))
    at(6500, () => say(1, '저도 폰으로 잠수 걸어 뒀어요'))

    // 메뉴: 파일이 생기면 한 번 연다
    const trigger = process.env.SHOWCASE_MENU_FILE
    if (trigger) {
      const timer = setInterval(() => {
        if (!fs.existsSync(trigger)) return
        safe(() => fs.unlinkSync(trigger))
        log('>> showcase menu')
        safe(() => openMenu(client))
      }, 500)
      client.on('end', () => clearInterval(timer))
    }
    client.on('window_click', (d) => {
      log(`MENU CLICK slot=${d.slot} window=${d.windowId}`)
      if (d.windowId === 9 && d.slot === 13) safe(() => { client.write('close_window', { windowId: 9 }); sys('§6[보상] §b다이아몬드 x1 §f을 받았습니다') })
    })
  }

  function playerChat (client, who, msg) {
    client.write('player_chat', {
      globalIndex: 0, senderUuid: who.uuid, index: 0, signature: undefined, plainMessage: msg,
      timestamp: BigInt(Date.now()), salt: 0n, previousMessages: [], unsignedChatContent: undefined,
      filterType: 0, type: { chatType: 1 }, networkName: text(who.name), networkTargetName: undefined
    })
  }

  function sendPlayers (client) {
    client.write('player_info', {
      action: { add_player: true, update_listed: true, update_latency: true, update_display_name: true },
      data: PEOPLE.map(p => ({ uuid: p.uuid, player: { name: p.name, properties: [] }, listed: 1, latency: p.ping, displayName: p.display ? text(p.display) : undefined }))
    })
    client.write('playerlist_header', { header: text('§b§l바다마을 야생'), footer: text('§7접속자 37명 · 잠수 보상 30분') })
  }

  const itemId = (n) => mcData.itemsByName[n].id
  const plain = (n, count = 1) => ({ itemCount: count, itemId: itemId(n), addedComponentCount: 0, removedComponentCount: 0, components: [], removeComponents: [] })
  const named = (n, name, lore, count = 1) => ({ itemCount: count, itemId: itemId(n), addedComponentCount: 2, removedComponentCount: 0,
    components: [{ type: 'custom_name', data: text(name) }, { type: 'lore', data: lore.map(l => text(l)) }], removeComponents: [] })

  function sendInventory (client) {
    const items = Array(46).fill({ itemCount: 0 })
    items[5] = plain('iron_helmet'); items[6] = plain('iron_chestplate'); items[7] = plain('iron_leggings'); items[8] = plain('iron_boots')
    items[36] = plain('diamond_sword'); items[37] = plain('diamond_pickaxe'); items[38] = plain('fishing_rod'); items[39] = plain('cooked_beef', 32)
    items[40] = plain('torch', 64); items[41] = plain('oak_planks', 64); items[42] = plain('bread', 16)
    items[9] = plain('diamond', 7); items[10] = plain('cobblestone', 64); items[11] = plain('cod', 21); items[12] = plain('salmon', 9)
    items[13] = plain('iron_ingot', 23); items[14] = plain('oak_log', 48); items[45] = plain('shield')
    client.write('window_items', { windowId: 0, stateId: 1, items, carriedItem: { itemCount: 0 } })
    client.write('held_item_slot', { slot: 0 })
  }

  /** 서버 메뉴(상자 3줄). 13번 칸을 누르면 닫히고 보상 문구가 나온다 */
  function openMenu (client) {
    client.write('open_window', { windowId: 9, inventoryType: 2, windowTitle: text('바다마을 메뉴') })
    const items = Array(27 + 36).fill({ itemCount: 0 })
    items[10] = named('compass', '§a마을 광장으로 이동', ['§7스폰 지점으로 돌아갑니다'])
    items[11] = named('fishing_rod', '§b낚시터', ['§7낚시 대회가 열리는 곳', '§e오늘 밤 9시 대회!'])
    items[13] = named('diamond', '§b잠수 보상 받기', ['§7잠수 30분마다 받을 수 있습니다', '§a지금 받을 수 있는 보상: 다이아몬드 x1'])
    items[15] = named('emerald', '§a상점', ['§7아이템을 사고팝니다'])
    items[16] = named('clock', '§e출석 체크', ['§7연속 출석 6일째', '§c오늘은 아직 안 했어요'])
    items[22] = named('barrier', '§c닫기', ['§7메뉴를 닫습니다'])
    client.write('window_items', { windowId: 9, stateId: 1, items, carriedItem: { itemCount: 0 } })
  }

  // ------------------------------------------------------------ 월드 (지도 화면용 작은 마을)

  const state = (n) => mcData.blocksByName[n].defaultState
  const B = {
    stone: state('stone'), dirt: state('dirt'), grass: state('grass_block'), water: state('water'), sand: state('sand'),
    planks: state('oak_planks'), path: state('dirt_path'), farmland: mcData.blocksByName.farmland.minStateId + 7,
    cobble: state('cobblestone'), glass: state('glass'), wheat: mcData.blocksByName.wheat.maxStateId,
    log: state('oak_log'), leaves: state('oak_leaves'), poppy: state('poppy'), dandelion: state('dandelion')
  }
  const hash = (a, b) => { let h = (a * 374761393 + b * 668265263) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return (h ^ (h >>> 16)) >>> 0 }

  /** 땅 높이 y=63. 풀밭 + 길 + 연못 + 집 + 밭 + 나무. 플레이어는 (10, 64, -4) 풀밭 위 */
  function blockAt (x, y, z) {
    if (y < 60) return B.stone
    if (y > 68) return 0
    const pond = Math.hypot(x + 8, z - 14)
    const inHouse = x >= 16 && x <= 24 && z >= -16 && z <= -8
    const inFarm = x >= -24 && x <= -12 && z >= -22 && z <= -12
    const onPath = (z >= -2 && z <= -1) || (x >= 4 && x <= 5 && z > -1)
    if (y < 63) return pond < 7 ? B.water : B.dirt
    if (y === 63) {
      if (pond < 7) return B.water
      if (pond < 9) return B.sand
      if (inHouse) return B.planks
      if (inFarm) return z === -17 ? B.water : B.farmland
      if (onPath) return B.path
      return B.grass
    }
    if (inHouse) {
      const edge = x === 16 || x === 24 || z === -16 || z === -8
      const door = z === -8 && x === 20
      if (!edge || door || y > 66) return 0
      return y === 65 && (x === 18 || x === 22 || z === -12) ? B.glass : B.cobble
    }
    if (inFarm) return y === 64 && z !== -17 ? B.wheat : 0
    // 나무: 7칸 격자마다 셋 중 하나꼴, 다른 것과 겹치지 않는 자리에만
    const tx = Math.round(x / 7) * 7
    const tz = Math.round(z / 7) * 7
    const tree = hash(tx, tz) % 3 === 0 && Math.hypot(tx + 8, tz - 14) > 12 &&
      !(tx >= 13 && tx <= 27 && tz >= -19 && tz <= -5) && !(tx >= -27 && tx <= -9 && tz >= -25 && tz <= -9) &&
      Math.abs(tz + 1.5) > 4 && !(Math.abs(tx - 4.5) < 4 && tz > -1) && Math.hypot(tx - 10, tz + 4) > 6
    if (tree) {
      const dx = Math.abs(x - tx)
      const dz = Math.abs(z - tz)
      if (dx === 0 && dz === 0 && y <= 67) return B.log
      const r = y === 68 ? 1 : 2
      if (y >= 66 && y <= 68 && dx <= r && dz <= r && !(dx === 2 && dz === 2)) return B.leaves
      return 0
    }
    if (y === 64 && !onPath && pond >= 9) {
      const h = hash(x, z) % 40
      if (h === 0) return B.poppy
      if (h === 1) return B.dandelion
    }
    return 0
  }

  /** 1.21.4 청크 구역 하나: 한 가지 값이면 팔레트 없이, 아니면 4비트 팔레트(구역 안 블록 종류 16개까지) */
  function section (at) {
    const ids = new Array(4096)
    const palette = []
    let count = 0
    for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      const id = at(x, y, z)
      if (id !== 0) count++
      let k = palette.indexOf(id)
      if (k < 0) { k = palette.length; palette.push(id) }
      ids[(y * 16 + z) * 16 + x] = k
    }
    const out = [(count >> 8) & 0xff, count & 0xff]
    if (palette.length === 1) {
      out.push(0, ...varint(palette[0]), 0)
    } else {
      if (palette.length > 16) throw new Error('구역 안 블록 종류가 16개를 넘음')
      out.push(4, ...varint(palette.length))
      for (const p of palette) out.push(...varint(p))
      out.push(...varint(256))
      for (let l = 0; l < 256; l++) {
        let v = 0n
        for (let j = 0; j < 16; j++) v |= BigInt(ids[l * 16 + j]) * (1n << BigInt(j * 4))
        const b = Buffer.alloc(8)
        b.writeBigUInt64BE(v)
        out.push(...b)
      }
    }
    out.push(0, ...varint(0), 0) // 생물 군계: 한 가지
    return out
  }

  function sendChunks (client) {
    for (let cx = -3; cx <= 3; cx++) for (let cz = -3; cz <= 3; cz++) {
      const data = []
      for (let i = 0; i < 24; i++) {
        const y0 = -64 + i * 16
        data.push(...section((x, y, z) => blockAt(cx * 16 + x, y0 + y, cz * 16 + z)))
      }
      client.write('map_chunk', { x: cx, z: cz, heightmaps: { type: 'compound', name: '', value: {} }, chunkData: Buffer.from(data), blockEntities: [],
        skyLightMask: [], blockLightMask: [], emptySkyLightMask: [], emptyBlockLightMask: [], skyLight: [], blockLight: [] })
    }
  }

  return showcase
}
