// 잠수크래프트 클라우드 웹 화면.
// 서버가 보내는 JSON 모양은 core 의 Wire.kt 와 같다 (키를 바꾸면 함께 고칠 것).
// 서버에서 온 글자는 모두 textContent 로만 넣는다 (HTML 로 해석하지 않음).
'use strict';

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

// ------------------------------------------------------------ 언어
let english = (() => {
  try {
    const saved = localStorage.getItem('jamsu_lang');
    if (saved) return saved === 'en';
  } catch (_) { /* 저장소를 못 쓰는 브라우저 */ }
  return !(navigator.language || 'ko').toLowerCase().startsWith('ko');
})();
const t = (ko, en) => (english ? en : ko);
function applyLang() {
  document.documentElement.lang = english ? 'en' : 'ko';
  document.title = t('잠수크래프트 클라우드', 'Jamsucraft Cloud');
  for (const e of document.querySelectorAll('[data-t]')) {
    const [ko, en] = e.dataset.t.split('|');
    e.textContent = t(ko, en);
  }
  $('btn-lang').textContent = english ? '한국어' : 'EN';
  $('input').placeholder = t('채팅 또는 /명령어', 'Chat or /command');
}

// ------------------------------------------------------------ 상태
const state = {
  account: null,
  limits: { sessions: 1, servers: 20 },
  sessions: new Map(), // sid → { entry, status, players, vitals, menu, death, traffic, lines: [], lastSeq, history: [] }
  presence: [],
  selected: null,
  tab: 'chat',
  dev: false,
};
let source = null;
let pollTimer = null;
let clockTimer = null;

const ACTIVE = new Set(['PREPARING', 'CONNECTING', 'ONLINE', 'WAITING_RECONNECT']);
const isActive = (s) => !!s && ACTIVE.has(s.status.state);
const addressKey = (a) => {
  let k = (a || '').trim().toLowerCase();
  if (k.endsWith(':25565')) k = k.slice(0, -6);
  return k.replace(/\.$/, '');
};

// ------------------------------------------------------------ API
async function api(method, path, body) {
  const res = await fetch('/api/' + path, {
    method,
    headers: { 'X-Jamsu': '1', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
    cache: 'no-store',
  });
  let data = {};
  try { data = await res.json(); } catch (_) { /* 본문 없음 */ }
  if (!res.ok) {
    const err = new Error(data.message || t('요청에 실패했습니다', 'Request failed') + ' (' + res.status + ')');
    err.status = res.status;
    err.code = data.error;
    err.data = data;
    throw err;
  }
  return data;
}

function toast(text) {
  const e = $('toast');
  e.textContent = text;
  e.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { e.hidden = true; }, 3200);
}

/** 서버 요청을 하고, 실패하면 이유를 띄운다. 로그인이 풀렸으면 로그인 화면으로 */
async function act(fn) {
  try {
    return await fn();
  } catch (e) {
    if (e.status === 401) { showLogin(); return undefined; }
    toast(e.message);
    return undefined;
  }
}

// ------------------------------------------------------------ 대화상자
function dialog(build) {
  const d = $('dialog');
  d.replaceChildren();
  const close = () => { $('shade').hidden = true; d.replaceChildren(); dialog.onClose = null; };
  build(d, close);
  $('shade').hidden = false;
  return close;
}
function closeDialog() {
  const f = dialog.onClose;
  $('shade').hidden = true;
  $('dialog').replaceChildren();
  dialog.onClose = null;
  if (f) f();
}
function actions(d, ...buttons) {
  const row = el('div', 'actions');
  for (const [label, cls, fn] of buttons) {
    const b = el('button', cls, label);
    b.type = 'button';
    b.onclick = fn;
    row.append(b);
  }
  d.append(row);
  return row;
}
function confirmBox(title, message, okLabel, onOk, danger) {
  dialog((d, close) => {
    d.append(el('h2', null, title), el('p', null, message));
    actions(d, [t('취소', 'Cancel'), 'ghost', close], [okLabel, danger ? 'danger' : 'primary', () => { close(); onOk(); }]);
  });
}
function messageBox(title, message) {
  dialog((d, close) => {
    d.append(el('h2', null, title), el('p', null, message));
    actions(d, [t('확인', 'OK'), 'primary', close]);
  });
}

// ------------------------------------------------------------ 로그인
function showLogin(errorText) {
  stopStream();
  clearInterval(pollTimer);
  state.account = null;
  $('main').hidden = true;
  $('login').hidden = false;
  $('login-start').hidden = false;
  $('login-code').hidden = true;
  $('login-error').hidden = !errorText;
  $('login-error').textContent = errorText || '';
  $('login-dev').hidden = !state.dev;
}

async function startMsLogin() {
  $('login-error').hidden = true;
  $('btn-ms').disabled = true;
  try {
    const d = await api('POST', 'login/device', {});
    $('user-code').textContent = d.userCode;
    $('btn-open').href = d.directUrl || d.url;
    $('login-start').hidden = true;
    $('login-code').hidden = false;
    const until = Date.now() + d.expiresInSec * 1000;
    const tick = async () => {
      const left = Math.max(0, Math.round((until - Date.now()) / 1000));
      $('code-wait').textContent = t(`로그인을 기다리는 중… (남은 시간 ${Math.floor(left / 60)}분 ${left % 60}초)`, `Waiting for sign-in… (${Math.floor(left / 60)}m ${left % 60}s left)`);
      if (left <= 0) { showLogin(t('로그인 시간이 지났습니다. 다시 시도해 주세요', 'The sign-in timed out. Please try again')); return; }
      if (tick.busy || left % 3 !== 0) return;
      tick.busy = true;
      try {
        const r = await api('POST', 'login/device/poll', { id: d.id });
        if (r.state === 'done') { clearInterval(pollTimer); await enter(); }
        else if (r.state === 'error') showLogin(r.message || t('로그인에 실패했습니다', 'Sign-in failed'));
      } catch (e) {
        showLogin(loginErrorText(e));
      } finally {
        tick.busy = false;
      }
    };
    clearInterval(pollTimer);
    pollTimer = setInterval(tick, 1000);
    tick();
  } catch (e) {
    showLogin(e.message);
  } finally {
    $('btn-ms').disabled = false;
  }
}

function loginErrorText(e) {
  if (e.code === 'not_premium' && e.data && e.data.name) {
    return t(
      `${e.data.name} 계정은 클라우드 잠수(프리미엄)를 쓸 수 있는 계정이 아닙니다.\n운영자에게 아래 닉네임을 알려 주세요.\n\n닉네임: ${e.data.name}\nUUID: ${e.data.uuid}`,
      `The account ${e.data.name} doesn't have cloud AFK (premium).\nSend this nickname to the operator.\n\nNickname: ${e.data.name}\nUUID: ${e.data.uuid}`);
  }
  return e.message;
}

async function devLogin() {
  try {
    await api('POST', 'login/dev', { name: $('dev-name').value.trim() || 'Tester' });
    await enter();
  } catch (e) {
    showLogin(e.message);
  }
}

/** 로그인돼 있으면 본 화면으로 */
async function enter() {
  let me;
  try {
    me = await api('GET', 'me');
  } catch (e) {
    showLogin(e.status === 401 ? '' : e.message);
    return;
  }
  state.account = me.account;
  state.limits = me.limits;
  $('login').hidden = true;
  $('main').hidden = false;
  renderAccount();
  startStream();
}

// ------------------------------------------------------------ 실시간 연결
function stopStream() {
  if (source) { source.close(); source = null; }
}

function startStream() {
  stopStream();
  const es = new EventSource('/api/events');
  source = es;
  const on = (type, fn) => es.addEventListener(type, (ev) => {
    if (source !== es) return;
    let data;
    try { data = JSON.parse(ev.data); } catch (_) { return; } // 깨진 이벤트 하나 때문에 화면 전체가 멈추지 않게
    fn(data);
  });
  es.onopen = () => { $('conn').hidden = true; };
  es.onerror = () => {
    if (source !== es) return;
    $('conn').hidden = false;
    $('conn').textContent = t('다시 연결 중…', 'Reconnecting…');
    // 브라우저가 알아서 다시 붙지만, 로그인이 풀려 영영 닫힌 경우는 직접 확인한다
    if (es.readyState === EventSource.CLOSED) {
      setTimeout(async () => {
        if (source !== es) return;
        try { await api('GET', 'me'); startStream(); } catch (e) { if (e.status === 401) showLogin(); else setTimeout(() => { if (source === es) startStream(); }, 3000); }
      }, 1500);
    }
  };
  on('hello', onHello);
  on('line', (d) => addLine(d));
  on('status', (d) => { const s = state.sessions.get(d.sid); if (s) { s.status = d.status; renderList(); if (d.sid === state.selected) renderSessionHead(); if (state.tab === 'status') renderStatus(); } });
  on('players', (d) => { const s = state.sessions.get(d.sid); if (s) { s.players = d.players; if (d.sid === state.selected) { renderTabs(); if (state.tab === 'players') renderPlayers(); } } });
  on('vitals', (d) => { const s = state.sessions.get(d.sid); if (s) { s.vitals = d.vitals; if (d.sid === state.selected && state.tab === 'status') renderStatus(); } });
  on('traffic', (d) => { const s = state.sessions.get(d.sid); if (s) { s.traffic = d.traffic; if (d.sid === state.selected && state.tab === 'status') renderStatus(); } });
  on('menu', (d) => { const s = state.sessions.get(d.sid); if (s) { s.menu = d.menu; if (d.sid === state.selected) renderMenuNotice(); } });
  on('death', (d) => { const s = state.sessions.get(d.sid); if (s) { s.death = d.death; if (d.sid === state.selected) { renderDeath(); if (state.tab === 'status') renderStatus(); } } });
  on('inventory', () => {});
  on('pack', () => {});
  on('entry', (d) => { upsertSession(d.session); renderList(); if (d.session.entry.id === state.selected) renderSessionHead(); });
  on('entryRemoved', (d) => { state.sessions.delete(d.id); if (state.selected === d.id) select(null); renderList(); });
  on('presence', (d) => { state.presence = d.presence; renderList(); if (state.selected) renderSessionHead(); });
  on('newDevice', (d) => { toast(t(`이 계정에 새 기기가 로그인했습니다: ${d.label} (본인이 아니면 계정 → 기기에서 로그아웃시키세요)`, `A new device signed in to this account: ${d.label} (if this wasn't you, sign it out under Account)`)); });
  on('bye', () => { showLogin(t('로그아웃되었습니다', 'You have been signed out')); });
}

function upsertSession(snap) {
  const old = state.sessions.get(snap.entry.id);
  const s = old || { lines: [], lastSeq: 0, history: [], historyAt: -1 };
  s.entry = snap.entry;
  s.status = snap.status;
  s.players = snap.players || { players: [], header: [], footer: [] };
  s.vitals = snap.vitals || null;
  s.menu = snap.menu || null;
  s.death = snap.death || null;
  s.traffic = snap.traffic || s.traffic || null;
  state.sessions.set(snap.entry.id, s);
  return s;
}

function onHello(d) {
  state.account = d.account;
  state.limits = d.limits;
  state.presence = d.presence;
  const seen = new Set();
  for (const snap of d.sessions) { upsertSession(snap); seen.add(snap.entry.id); }
  for (const id of [...state.sessions.keys()]) if (!seen.has(id)) state.sessions.delete(id);
  if (state.selected && !state.sessions.has(state.selected)) state.selected = null;
  renderAccount();
  renderList();
  renderSession();
}

// ------------------------------------------------------------ 채팅 줄
const MAX_LINES = 1500;

function addLine(d) {
  const s = state.sessions.get(d.sid);
  if (!s || d.seq <= s.lastSeq) return; // 다시 붙을 때 겹쳐 온 줄
  s.lastSeq = d.seq;
  s.lines.push(d);
  if (s.lines.length > MAX_LINES) s.lines.splice(0, s.lines.length - MAX_LINES);
  if (d.sid !== state.selected) return;
  const box = $('chat');
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  box.append(lineNode(d));
  while (box.childElementCount > MAX_LINES) box.firstElementChild.remove();
  if (atBottom) box.scrollTop = box.scrollHeight;
}

const dark = () => !window.matchMedia('(prefers-color-scheme: light)').matches;

/** 채팅 색이 배경과 너무 비슷하면 읽을 수 있게 바꾼다 (앱의 Palette.readable 과 같은 규칙) */
function readable(rgb) {
  const r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
  const lum = 0.299 * r + 0.587 * g + 0.114 * b;
  let c = rgb;
  if (dark()) { if (lum < 70) c = 0xB0B8C4; }
  else if (lum > 200) c = 0x4A5566;
  else if (rgb === 0xFFFF55) c = 0xB8860B;
  else if (rgb === 0x55FFFF) c = 0x008B8B;
  else if (rgb === 0x55FF55) c = 0x2E8B2E;
  return '#' + c.toString(16).padStart(6, '0');
}

/** 색·굵기·클릭 동작이 있는 글자 조각들을 [parent] 에 붙인다 */
function appendSpans(parent, spans, clickable) {
  for (const sp of spans || []) {
    if (!sp.t) continue; // 리소스팩 그림 글자(g)는 웹에서는 그리지 않는다
    const e = el('span', null, sp.t);
    if (sp.c != null) e.style.color = readable(sp.c);
    if (sp.b) e.style.fontWeight = '700';
    if (sp.i) e.style.fontStyle = 'italic';
    if (sp.h) e.title = sp.h;
    if (clickable && sp.ck) {
      e.classList.add('click');
      e.onclick = () => clickAction(sp.ck);
    }
    parent.append(e);
  }
}

function clickAction(ck) {
  const v = ck.v || '';
  if (ck.a === 'OPEN_URL') {
    if (!/^https?:\/\//i.test(v)) return;
    confirmBox(t('링크 열기', 'Open link'), v, t('열기', 'Open'), () => window.open(v, '_blank', 'noopener,noreferrer'));
  } else if (ck.a === 'RUN_COMMAND') {
    confirmBox(t('보내기', 'Send'), v, t('보내기', 'Send'), () => sendChat(v));
  } else if (ck.a === 'SUGGEST_COMMAND') {
    $('input').value = v;
    $('input').focus();
  } else if (ck.a === 'COPY') {
    copyText(v);
  }
}

function copyText(v) {
  const done = () => toast(t('복사했습니다', 'Copied'));
  if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(v).then(done, () => {});
  else {
    const ta = el('textarea');
    ta.value = v;
    document.body.append(ta);
    ta.select();
    try { document.execCommand('copy'); done(); } catch (_) { /* 복사 불가 */ }
    ta.remove();
  }
}

const hhmm = (ms) => { const d = new Date(ms); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };

function lineNode(d) {
  const row = el('div', 'line ' + (d.kind === 'SYSTEM' ? 'system' : d.kind === 'ERROR' ? 'error' : ''));
  row.append(el('span', 'time', hhmm(d.t)));
  appendSpans(row, d.spans, true);
  return row;
}

// ------------------------------------------------------------ 그리기
function renderAccount() {
  const a = state.account;
  if (!a) return;
  let text = a.name;
  if (a.premium) text += ' · ' + (a.premiumUntil ? t('프리미엄 ~', 'Premium until ') + new Date(a.premiumUntil - 1).toLocaleDateString() : t('프리미엄', 'Premium'));
  else text += ' · ' + t('프리미엄 아님', 'No premium');
  $('acct').textContent = text;
}

function statusText(s) {
  const st = s.status;
  if (st.state === 'ONLINE' && st.onlineSinceMs) return t('접속 중 · ', 'Online · ') + duration(Date.now() - st.onlineSinceMs);
  if (st.state === 'WAITING_RECONNECT' && st.nextAttemptAtMs) {
    const left = Math.max(0, Math.round((st.nextAttemptAtMs - Date.now()) / 1000));
    return (st.message || t('재접속 대기', 'Waiting to reconnect')) + ' (' + left + t('초', 's') + ')';
  }
  return st.message || t('꺼짐', 'Off');
}

function duration(ms) {
  const m = Math.floor(ms / 60000), h = Math.floor(m / 60), d = Math.floor(h / 24);
  if (d > 0) return t(`${d}일 ${h % 24}시간`, `${d}d ${h % 24}h`);
  if (h > 0) return t(`${h}시간 ${m % 60}분`, `${h}h ${m % 60}m`);
  return t(`${m}분`, `${m}m`);
}

const dotClass = (s) => (s.status.state === 'ONLINE' ? 'online' : isActive(s) ? 'busy' : 'stopped');

/** 이 서버가 지금 어느 매체에서 돌고 있는지 */
function holders(s) {
  const key = addressKey(s.entry.address);
  return state.presence.filter((p) => p.key === key);
}
function holderText(p) {
  if (p.holder === 'cloud') return t('☁ 클라우드에서 실행 중', '☁ Running in the cloud');
  if (p.holder === 'app') return t(`📱 ${p.label}에서 실행 중`, `📱 Running on ${p.label}`);
  return t('🖥 다른 클라이언트(PC 게임 등)에서 접속해 끊겼습니다', '🖥 Disconnected: joined from another client (e.g. PC)');
}

function renderList() {
  const box = $('servers');
  box.replaceChildren();
  $('no-servers').hidden = state.sessions.size > 0;
  for (const [id, s] of state.sessions) {
    const row = el('div', 'server' + (id === state.selected ? ' on' : ''));
    row.append(el('span', 'dot ' + dotClass(s)));
    const body = el('div', 'grow');
    body.append(el('div', 'name', s.entry.name || s.entry.address), el('div', 'sub', s.entry.address + ' · ' + statusText(s)));
    for (const p of holders(s)) body.append(el('div', 'where ' + p.holder, holderText(p)));
    row.append(body);
    row.onclick = () => select(id);
    box.append(row);
  }
}

function select(id) {
  state.selected = id;
  document.body.classList.toggle('viewing', !!id);
  renderList();
  renderSession();
}

function renderSession() {
  const s = state.sessions.get(state.selected);
  $('session').hidden = !s;
  $('empty').hidden = !!s;
  if (!s) return;
  const box = $('chat');
  box.replaceChildren(...s.lines.map(lineNode));
  box.scrollTop = box.scrollHeight;
  $('suggest').hidden = true;
  renderSessionHead();
  renderTabs();
  renderMenuNotice();
  renderDeath();
  showTab(state.tab);
}

function renderSessionHead() {
  const s = state.sessions.get(state.selected);
  if (!s) return;
  $('s-name').textContent = s.entry.name || s.entry.address;
  $('s-status').textContent = statusText(s);
  $('s-dot').className = 'dot ' + dotClass(s);
  const b = $('btn-toggle');
  const active = isActive(s);
  b.textContent = active ? t('끊기', 'Disconnect') : t('시작', 'Start');
  b.className = (active ? 'plain' : 'primary') + ' small';
  const others = holders(s).filter((p) => p.holder !== 'cloud');
  $('s-presence').hidden = others.length === 0;
  $('s-presence').textContent = others.map(holderText).join(' · ');
}

function renderTabs() {
  const s = state.sessions.get(state.selected);
  const n = s ? s.players.players.length : 0;
  $('tab-players').textContent = t('접속자 ', 'Players ') + n;
}

function showTab(name) {
  state.tab = name;
  for (const b of document.querySelectorAll('.tab')) b.classList.toggle('on', b.dataset.tab === name);
  $('p-chat').hidden = name !== 'chat';
  $('p-players').hidden = name !== 'players';
  $('p-status').hidden = name !== 'status';
  if (name === 'players') renderPlayers();
  if (name === 'status') renderStatus();
  if (name === 'chat') { const c = $('chat'); c.scrollTop = c.scrollHeight; }
}

function renderPlayers() {
  const s = state.sessions.get(state.selected);
  const box = $('p-players');
  box.replaceChildren();
  if (!s) return;
  const pl = s.players;
  if (pl.header && pl.header.length) { const h = el('div', 'tabtext'); appendSpans(h, pl.header); box.append(h); }
  if (!pl.players.length) box.append(el('p', 'muted', isActive(s) ? t('접속자 목록을 아직 받지 못했습니다', 'No player list yet') : t('접속 중이 아닙니다', 'Not connected')));
  for (const p of [...pl.players].sort((a, b) => a.o - b.o)) {
    const row = el('div', 'player');
    const face = el('span', 'face');
    const skin = (p.s || '').replace(/^http:/, 'https:');
    if (/^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]+$/.test(skin)) face.style.backgroundImage = `url("${skin}")`;
    else face.textContent = (p.n || '?').slice(0, 1).toUpperCase();
    const name = el('span', 'grow');
    if (p.d && p.d.some((x) => x.t && x.t.trim())) appendSpans(name, p.d); else name.textContent = p.n;
    row.append(face, name, el('span', 'ping', p.p + 'ms'));
    box.append(row);
  }
  if (pl.footer && pl.footer.length) { const f = el('div', 'tabtext'); appendSpans(f, pl.footer); box.append(f); }
}

const bytes = (n) => (n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(0, Math.round(n / 1024)) + ' KB');

function renderStatus() {
  const s = state.sessions.get(state.selected);
  const box = $('p-status');
  box.replaceChildren();
  if (!s) return;
  const v = s.vitals;
  const bar = (label, value, max, cls) => {
    const w = el('div', 'stat');
    const l = el('div', 'label');
    l.append(el('span', null, label), el('span', null, value));
    const b = el('div', 'bar ' + (cls || ''));
    const fill = el('i');
    fill.style.width = Math.max(0, Math.min(100, max)) + '%';
    b.append(fill);
    w.append(l, b);
    return w;
  };
  if (v) {
    const hp = Math.round(v.h * 10) / 10;
    box.append(bar(t('체력', 'Health') + (v.a > 0 ? t(` (+흡수 ${v.a})`, ` (+${v.a} absorption)`) : ''), `${hp} / ${v.m}`, (v.h / (v.m || 20)) * 100));
    box.append(bar(t('허기', 'Hunger'), `${v.f} / 20`, (v.f / 20) * 100, 'food'));
  } else {
    box.append(el('p', 'muted', t('접속하면 체력·허기가 보입니다', 'Health and hunger appear once connected')));
  }
  const kv = el('div', 'kv');
  const add = (k, val) => { if (val != null && val !== '') kv.append(el('span', null, k), el('span', null, String(val))); };
  const st = s.status;
  add(t('상태', 'State'), statusText(s));
  add(t('서버 주소', 'Address'), s.entry.address);
  add(t('서버 버전', 'Server version'), st.serverVersion);
  add(t('재접속', 'Reconnects'), st.reconnectCount ? t(`${st.reconnectCount}회`, `${st.reconnectCount}`) : null);
  if (s.traffic) add(t('데이터 (서버 컴퓨터)', 'Data (server side)'), bytes(s.traffic.rx + s.traffic.tx));
  if (s.death) add(t('마지막 사망', 'Last death'), hhmm(s.death.at) + ' · ' + deathText(s.death));
  add(t('실행 위치', 'Running on'), isActive(s) ? t('클라우드 (폰을 꺼도 유지됩니다)', 'Cloud (stays on when your phone is off)') : null);
  box.append(kv);
}

function deathText(d) {
  const parts = [];
  if (d.killer) parts.push(t('누구: ', 'Killer: ') + d.killer);
  if (d.cause) parts.push(t('원인: ', 'Cause: ') + d.cause);
  if (d.weapon) parts.push(t('무기: ', 'Weapon: ') + d.weapon);
  return parts.length ? parts.join(' · ') : (d.message || t('사망했습니다', 'You died'));
}

function renderDeath() {
  const s = state.sessions.get(state.selected);
  const d = s && s.death;
  $('s-death').hidden = !d;
  if (d) $('s-death-text').textContent = t('사망 ', 'Died ') + hhmm(d.at) + ' · ' + deathText(d);
}

const plain = (spans) => (spans || []).map((x) => x.t || '').join('');

function renderMenuNotice() {
  const s = state.sessions.get(state.selected);
  const m = s && s.menu;
  $('s-menu').hidden = !m;
  if (m) $('s-menu-text').textContent = t('서버 메뉴: ', 'Server menu: ') + (plain(m.title) || t('(제목 없음)', '(untitled)'));
  // 열어 둔 메뉴 창이 있으면 내용도 맞춘다
  if (openMenu.sid === state.selected && !$('shade').hidden) { if (m) openMenu(); else closeDialog(); }
}

/** 서버가 띄운 메뉴 창(상자 화면)을 보여 주고 누를 수 있게 한다 */
function openMenu() {
  const sid = state.selected;
  const s = state.sessions.get(sid);
  if (!s || !s.menu) return;
  const m = s.menu;
  openMenu.sid = sid;
  dialog((d, close) => {
    dialog.onClose = () => { openMenu.sid = null; };
    const h = el('h2');
    appendSpans(h, m.title);
    if (!h.textContent) h.textContent = t('서버 메뉴', 'Server menu');
    d.append(h);
    const grid = el('div', 'items');
    const items = m.items.filter((i) => i.slot < m.slots);
    if (!items.length) d.append(el('p', 'muted', t('메뉴에 아이템이 없습니다', 'This menu has no items')));
    for (const it of items) {
      const b = el('button', 'item plain');
      b.type = 'button';
      b.append(el('span', 'slot', '#' + it.slot));
      const name = el('div');
      appendSpans(name, it.name);
      if (!name.textContent) name.textContent = it.id;
      if (it.count > 1) name.append(' ×' + it.count);
      b.append(name);
      if (it.lore && it.lore.length) {
        const lore = el('div', 'lore');
        it.lore.forEach((l, i) => { if (i) lore.append('\n'); appendSpans(lore, l); });
        b.append(lore);
      }
      b.onclick = () => act(() => api('POST', `servers/${sid}/menu/click`, { slot: it.slot }));
      grid.append(b);
    }
    d.append(grid);
    actions(d,
      [t('메뉴 닫기', 'Close menu'), 'ghost', () => { act(() => api('POST', `servers/${sid}/menu/close`, {})); closeDialog(); }],
      [t('창만 닫기', 'Hide'), 'plain', closeDialog]);
  });
}
openMenu.sid = null;

// ------------------------------------------------------------ 동작
async function toggle() {
  const sid = state.selected;
  const s = state.sessions.get(sid);
  if (!s) return;
  if (isActive(s)) {
    confirmBox(t('연결 끊기', 'Disconnect'), t(`${s.entry.name || s.entry.address} 잠수를 끊을까요?`, `Stop AFK on ${s.entry.name || s.entry.address}?`), t('끊기', 'Disconnect'),
      () => act(() => api('POST', `servers/${sid}/stop`, {})));
    return;
  }
  await start(sid, false);
}

async function start(sid, force) {
  try {
    await api('POST', `servers/${sid}/start`, { force });
  } catch (e) {
    if (e.status === 401) { showLogin(); return; }
    if (e.code === 'in_use') {
      // 다른 매체에서 이미 이 서버에 접속 중
      if (e.data.canForce) confirmBox(t('다른 곳에서 접속 중일 수 있습니다', 'May be connected elsewhere'), e.message + '\n' + t('계속할까요?', 'Continue?'), t('그래도 시작', 'Start anyway'), () => start(sid, true));
      else messageBox(t('이미 실행 중입니다', 'Already running'), e.message);
    } else {
      messageBox(t('시작할 수 없습니다', "Couldn't start"), e.message);
    }
  }
}

async function sendChat(text) {
  const sid = state.selected;
  const s = state.sessions.get(sid);
  const v = (text || '').trim();
  if (!s || !v) return false;
  const r = await act(() => api('POST', `servers/${sid}/chat`, { text: v }));
  if (!r) return false;
  s.history = s.history.filter((x) => x !== v);
  s.history.push(v);
  if (s.history.length > 50) s.history.shift();
  s.historyAt = -1;
  return true;
}

async function suggest() {
  const sid = state.selected;
  const input = $('input');
  const text = input.value;
  if (!sid || !text.startsWith('/')) return;
  const r = await act(() => api('POST', `servers/${sid}/suggest`, { text }));
  const box = $('suggest');
  box.replaceChildren();
  if (!r || !r.matches.length || input.value !== text) { box.hidden = true; return; }
  for (const m of r.matches.slice(0, 20)) {
    const b = el('button', 'plain', m);
    b.type = 'button';
    b.onclick = () => {
      const st = Math.min(Math.max(r.start, 0), text.length);
      const en = Math.min(Math.max(r.start + r.length, st), text.length);
      input.value = text.slice(0, st) + m + text.slice(en);
      box.hidden = true;
      input.focus();
    };
    box.append(b);
  }
  box.hidden = false;
}

function editServer(sid) {
  const s = sid ? state.sessions.get(sid) : null;
  const e = s ? s.entry : { name: '', address: '', joinCommands: [], joinDelaySec: 3, joinIntervalSec: 3, autoReconnect: true, reconnectOnKick: true, autoClickRules: [], walkOnJoin: true };
  dialog((d, close) => {
    d.append(el('h2', null, s ? t('서버 설정', 'Server settings') : t('서버 추가', 'Add server')));
    const field = (label, node) => { d.append(el('label', null, label), node); return node; };
    const input = (value, ph) => { const i = el('input'); i.value = value || ''; i.placeholder = ph || ''; i.autocomplete = 'off'; return i; };
    const area = (lines, ph) => { const a = el('textarea'); a.value = (lines || []).join('\n'); a.placeholder = ph || ''; return a; };
    const check = (label, on) => { const l = el('label', 'check'); const c = el('input'); c.type = 'checkbox'; c.checked = on; l.append(c, label); d.append(l); return c; };
    const name = field(t('이름 (목록에 보이는 이름)', 'Name (shown in the list)'), input(e.name));
    const address = field(t('주소', 'Address'), input(e.address, 'play.example.kr'));
    const version = field(t('버전 (비워 두면 자동 감지)', 'Version (blank = auto-detect)'), input(e.fixedVersion, '1.21.4'));
    const cmds = field(t('접속 후 자동 실행 (한 줄에 하나)', 'Run after joining (one per line)'), area(e.joinCommands, '/잠수'));
    const delay = field(t('첫 명령어까지 기다릴 시간(초)', 'Delay before the first command (s)'), input(String(e.joinDelaySec)));
    const interval = field(t('명령어 사이 간격(초)', 'Interval between commands (s)'), input(String(e.joinIntervalSec)));
    const rules = field(t('메뉴 자동 클릭 (메뉴 제목 > 아이템 이름)', 'Menu auto-click (menu title > item name)'), area(e.autoClickRules, t('리소스팩 > 적용', 'Resource pack > Apply')));
    delay.inputMode = 'numeric'; interval.inputMode = 'numeric';
    d.append(el('label', null, ''));
    const reconnect = check(t('끊기면 자동 재접속', 'Auto-reconnect when disconnected'), e.autoReconnect);
    const kick = check(t('킥당해도 재접속', 'Reconnect after being kicked'), e.reconnectOnKick);
    const walk = check(t('들어가면 한두 걸음 걷기', 'Take a step or two after joining'), e.walkOnJoin);
    if (s && isActive(s)) d.append(el('p', 'hint', t('바뀐 설정은 다음 접속부터 적용됩니다', 'Changes apply from the next connection')));
    const lines = (a) => a.value.split('\n').map((x) => x.trim()).filter(Boolean);
    const buttons = [[t('취소', 'Cancel'), 'ghost', close], [t('저장', 'Save'), 'primary', async () => {
      if (!address.value.trim()) { toast(t('서버 주소를 입력해 주세요', 'Please enter the server address')); return; }
      const id = sid || 'w' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
      const r = await act(() => api('PUT', 'servers/' + id, {
        name: name.value.trim(), address: address.value.trim(), fixedVersion: version.value.trim() || undefined,
        joinCommands: lines(cmds), joinDelaySec: parseInt(delay.value, 10) || 0, joinIntervalSec: parseInt(interval.value, 10) || 0,
        autoReconnect: reconnect.checked, reconnectOnKick: kick.checked, autoClickRules: lines(rules), walkOnJoin: walk.checked,
      }));
      if (r) { close(); if (!sid) setTimeout(() => select(id), 150); }
    }]];
    if (s) buttons.unshift([t('삭제', 'Delete'), 'danger', () => {
      confirmBox(t('서버 삭제', 'Delete server'), t(`${e.name || e.address} 을(를) 지울까요? 잠수 중이면 끊깁니다.`, `Delete ${e.name || e.address}? AFK will stop if running.`), t('삭제', 'Delete'),
        () => act(() => api('DELETE', 'servers/' + sid)), true);
    }]);
    actions(d, ...buttons);
  });
}

async function accountDialog() {
  const me = await act(() => api('GET', 'me'));
  if (!me) return;
  dialog((d, close) => {
    d.append(el('h2', null, t('계정', 'Account')));
    const a = me.account;
    const kv = el('div', 'kv');
    kv.append(el('span', null, t('닉네임', 'Nickname')), el('span', null, a.name));
    kv.append(el('span', null, 'UUID'), el('span', null, a.uuid));
    kv.append(el('span', null, t('프리미엄', 'Premium')), el('span', null, a.premium ? (a.premiumUntil ? t('사용 중 · ', 'Active · ') + new Date(a.premiumUntil - 1).toLocaleDateString() + t('까지', '') : t('사용 중', 'Active')) : t('아님', 'No')));
    kv.append(el('span', null, t('동시 잠수', 'Concurrent AFK')), el('span', null, t(`서버 ${me.limits.sessions}개까지`, `up to ${me.limits.sessions} server(s)`)));
    d.append(kv);
    d.append(el('label', null, t('로그인한 기기', 'Signed-in devices')));
    for (const dev of me.devices) {
      const row = el('div', 'device');
      row.append(el('span', 'grow', dev.label + (dev.current ? t(' (이 기기)', ' (this device)') : '')), el('span', 'muted', new Date(dev.lastSeenAt).toLocaleDateString()));
      if (!dev.current) {
        // 본인이 한 로그인이 아닌 기기가 있으면 여기서 로그아웃시킨다
        const out = el('button', 'ghost small', t('로그아웃', 'Sign out'));
        out.type = 'button';
        out.onclick = async () => { if (await act(() => api('POST', `devices/${dev.id}/revoke`, {}))) row.remove(); };
        row.append(out);
      }
      d.append(row);
    }
    d.append(el('p', 'hint', t('연결 해제를 누르면 모든 잠수를 끊고, 서버에 저장된 이 계정의 로그인 정보·서버 목록을 모두 지웁니다.', 'Unlink stops all AFK sessions and deletes this account’s sign-in data and server list from the server.')));
    actions(d,
      [t('연결 해제(삭제)', 'Unlink (delete)'), 'danger', () => confirmBox(t('연결 해제', 'Unlink'), t('모든 잠수를 끊고 서버에 저장된 정보를 지울까요?', 'Stop all AFK sessions and delete the stored data?'), t('해제', 'Unlink'), async () => { await act(() => api('DELETE', 'account')); showLogin(); }, true)],
      [t('로그아웃', 'Sign out'), 'plain', async () => { close(); await act(() => api('POST', 'logout', {})); showLogin(); }],
      [t('닫기', 'Close'), 'primary', close]);
  });
}

// ------------------------------------------------------------ 시작
function wire() {
  $('btn-ms').onclick = startMsLogin;
  $('btn-cancel').onclick = () => showLogin();
  $('btn-copy').onclick = () => copyText($('user-code').textContent);
  $('btn-dev').onclick = devLogin;
  $('btn-lang').onclick = () => {
    english = !english;
    try { localStorage.setItem('jamsu_lang', english ? 'en' : 'ko'); } catch (_) { /* 저장 못 해도 됨 */ }
    applyLang();
    renderAccount(); renderList(); renderSession();
  };
  $('btn-account').onclick = accountDialog;
  $('btn-add').onclick = () => {
    if (state.sessions.size >= state.limits.servers) { toast(t(`서버는 ${state.limits.servers}개까지 저장할 수 있습니다`, `You can save up to ${state.limits.servers} servers`)); return; }
    editServer(null);
  };
  $('btn-back').onclick = () => select(null);
  $('btn-edit').onclick = () => editServer(state.selected);
  $('btn-toggle').onclick = toggle;
  $('btn-menu').onclick = openMenu;
  $('btn-death').onclick = () => act(() => api('POST', `servers/${state.selected}/death/clear`, {}));
  for (const b of document.querySelectorAll('.tab')) b.onclick = () => showTab(b.dataset.tab);
  $('shade').onclick = (ev) => { if (ev.target === $('shade')) closeDialog(); };
  document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape' && !$('shade').hidden) closeDialog(); });

  const input = $('input');
  $('send').onsubmit = async (ev) => {
    ev.preventDefault();
    const v = input.value;
    if (!v.trim()) return;
    $('suggest').hidden = true;
    if (await sendChat(v)) input.value = '';
  };
  input.addEventListener('keydown', (ev) => {
    const s = state.sessions.get(state.selected);
    if (!s) return;
    if (ev.key === 'Tab') { ev.preventDefault(); suggest(); return; }
    if (ev.key !== 'ArrowUp' && ev.key !== 'ArrowDown') return;
    // ▲▼ 이전에 보낸 채팅·명령어
    if (!s.history.length) return;
    ev.preventDefault();
    if (s.historyAt < 0) s.historyAt = s.history.length;
    s.historyAt = Math.max(0, Math.min(s.history.length, s.historyAt + (ev.key === 'ArrowUp' ? -1 : 1)));
    input.value = s.history[s.historyAt] || '';
  });
  input.addEventListener('input', () => { $('suggest').hidden = true; });

  // 접속 시간·재접속 초 표시를 계속 맞춘다
  clockTimer = setInterval(() => {
    if ($('main').hidden) return;
    renderList();
    renderSessionHead();
  }, 5000);
  window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => renderSession());
}

async function boot() {
  applyLang();
  wire();
  try {
    const info = await api('GET', 'info');
    state.dev = !!info.dev;
    if (!info.msLogin && !info.dev) {
      showLogin(t('이 서버에는 아직 마이크로소프트 로그인이 설정되어 있지 않습니다 (운영자: cloud.properties 의 msClientId)', 'Microsoft sign-in is not set up on this server yet (operator: msClientId in cloud.properties)'));
      $('btn-ms').disabled = true;
      return;
    }
    $('btn-ms').hidden = !info.msLogin;
  } catch (e) {
    showLogin(e.message);
    return;
  }
  await enter();
}

boot();
