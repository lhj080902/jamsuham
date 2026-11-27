// 클라우드 웹 화면을 폰·태블릿(터치)으로 썼을 때의 시험 (Playwright 기기 흉내 + 터치 입력).
// 먼저 띄울 것: 시험 MC 서버 `node server.js 1.21.4 25570 stay` 와 `node server.js 1.21.4 <walk포트> walk`,
// 클라우드 서버 (포트 18787, JAVA_OPTS=-Djamsu.devLogin=true, premiumMode=open, allowPrivateTargets=true)
// 실행: node web-touch-test.js <스크린샷 폴더> <walk포트>
const { chromium, devices } = require('playwright');
const S = process.argv[2];
const WALK_PORT = process.argv[3];

async function run(browser, label, device, results) {
  const check = (name, pass) => { results.push([label + ' ' + name, pass]); console.log((pass ? '  통과  ' : '  실패  ') + label + ': ' + name); };
  const ctx = await browser.newContext({ ...device, locale: 'ko-KR' });
  const page = await ctx.newPage();
  const problems = [];
  page.on('pageerror', (e) => problems.push(e.message));
  const cdp = await ctx.newCDPSession(page);
  const touch = (type, points) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points.map((p, i) => ({ x: p[0], y: p[1], id: p[2] ?? i })) });

  await page.goto('http://127.0.0.1:18787/');
  await page.waitForSelector('#login:not([hidden])');
  // 시험 로그인 닉네임은 영문·숫자만 된다
  await page.fill('#dev-name', 'Touch_' + results.length);
  await page.tap('#btn-dev');
  await page.waitForSelector('#main:not([hidden])');

  // 가로로 넘치는 것이 없는지 (폰에서 옆으로 스크롤되면 쓰기 불편하다)
  const fits = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
  check('본 화면이 화면 폭 안에 들어감', await fits());

  // 서버 두 개 추가 (채팅용, 걷기용)
  const add = async (name, port, noWalk) => {
    await page.tap('#btn-add');
    await page.waitForSelector('.dialog input');
    const f = page.locator('.dialog input:not([type=checkbox])');
    await f.nth(0).fill(name);
    await f.nth(1).fill('127.0.0.1:' + port);
    if (noWalk) await page.locator('.dialog input[type=checkbox]').nth(2).uncheck();
    const dialogFits = await page.evaluate(() => { const r = document.getElementById('dialog').getBoundingClientRect(); return r.left >= 0 && r.right <= window.innerWidth + 1; });
    await page.locator('.dialog .actions .primary').scrollIntoViewIfNeeded();
    await page.tap('.dialog .actions .primary');
    await page.waitForFunction((n) => document.getElementById('s-name').textContent === n, name, { timeout: 8000 });
    return dialogFits;
  };
  check('설정 창이 화면 안에 들어감', await add('채팅 서버', 25570, false));
  const phone = await page.evaluate(() => getComputedStyle(document.getElementById('list-pane')).display === 'none');
  console.log('        화면 모양:', phone ? '한 칸(폰)' : '두 칸(태블릿·PC)', await page.evaluate(() => window.innerWidth + '×' + window.innerHeight));

  await page.tap('#btn-toggle');
  await page.waitForFunction(() => document.getElementById('s-status').textContent.startsWith('접속 중 ·'), null, { timeout: 30000 });
  await page.waitForFunction(() => [...document.querySelectorAll('#chat .line')].some((e) => e.textContent.includes('반가워요')));
  check('시작 → 채팅 수신', true);

  // 입력창과 전송 버튼이 화면 안(아래쪽)에 보이는지
  const sendBox = await page.locator('#send').boundingBox();
  const vh = await page.evaluate(() => window.innerHeight);
  check('입력창이 화면 안에 보임', !!sendBox && sendBox.y + sendBox.height <= vh + 1 && sendBox.y > vh * 0.5);
  await page.tap('#input');
  await page.keyboard.type('폰에서 보낸 채팅');
  await page.tap('#send button');
  await page.waitForTimeout(900);
  check('채팅 보내기', (await page.inputValue('#input')) === '');

  // 서버 메뉴: 열기 → 아이템 누르기
  await page.waitForSelector('#s-menu:not([hidden])');
  await page.tap('#btn-menu');
  await page.waitForSelector('.dialog .item');
  await page.screenshot({ path: `${S}/shots/touch-${label}-menu.png` });
  check('메뉴 창이 화면 안에 들어감', await page.evaluate(() => { const r = document.getElementById('dialog').getBoundingClientRect(); return r.left >= 0 && r.right <= window.innerWidth + 1 && r.bottom <= window.innerHeight + 1; }));
  await page.locator('.dialog .item', { hasText: '#13' }).first().tap();
  await page.waitForFunction(() => document.getElementById('shade').hidden && document.getElementById('s-menu').hidden, null, { timeout: 8000 });
  check('메뉴 아이템을 눌러 클릭', true);
  await page.screenshot({ path: `${S}/shots/touch-${label}-chat.png` });

  // 폰이면 목록으로 돌아가는 ← 버튼
  if (phone) {
    await page.tap('#btn-back');
    check('← 로 서버 목록으로', await page.isVisible('#servers') && !(await page.isVisible('#session')));
    await page.screenshot({ path: `${S}/shots/touch-${label}-list.png` });
  }

  // 이동: 조이스틱을 손가락으로 끌기 + 동시에 점프 누르기
  await add('걷기 서버', WALK_PORT, true);
  await page.tap('#btn-toggle');
  await page.tap('[data-tab=move]');
  await page.waitForFunction(() => document.getElementById('pos').textContent.startsWith('X '), null, { timeout: 30000 });
  check('이동 탭이 화면 폭 안에 들어감', await fits());
  const coord = async () => { const t = (await page.textContent('#pos')).split('·'); return { x: parseFloat(t[0].replace('X', '')), z: parseFloat(t[2].replace('Z', '')) }; };
  const p0 = await coord();
  const box = await page.locator('#stick').boundingBox();
  const cx = box.x + box.width / 2, cy = box.y + box.height / 2;
  await touch('touchStart', [[cx, cy, 1]]);
  await touch('touchMove', [[cx + box.width / 2 - 6, cy, 1]]); // 오른쪽(동쪽)으로 끝까지
  await page.waitForTimeout(700);
  // 조이스틱을 누른 채 다른 손가락으로 점프
  const jb = await page.locator('#btn-jump').boundingBox();
  await touch('touchStart', [[cx + box.width / 2 - 6, cy, 1], [jb.x + jb.width / 2, jb.y + jb.height / 2, 2]]);
  await page.waitForTimeout(250);
  const jumpOn = await page.evaluate(() => document.getElementById('btn-jump').classList.contains('on'));
  await page.screenshot({ path: `${S}/shots/touch-${label}-move.png` });
  await touch('touchEnd', [[cx + box.width / 2 - 6, cy, 1]]); // 점프 손가락만 뗌
  await page.waitForTimeout(500);
  await touch('touchEnd', []);
  await page.waitForTimeout(900);
  const p1 = await coord();
  await page.waitForTimeout(1200);
  const p2 = await coord();
  console.log('        x', p0.x, '→', p1.x, '→', p2.x);
  check('조이스틱을 손가락으로 끌어 걷기', p1.x - p0.x > 3);
  check('조이스틱을 누른 채 점프 버튼도 눌림', jumpOn);
  check('손을 떼면 멈춤', p1.x === p2.x);
  check('조이스틱을 끌어도 화면이 스크롤되지 않음', await page.evaluate(() => window.scrollY === 0 && document.getElementById('p-move').scrollTop === 0));

  check('브라우저 오류 없음', problems.length === 0);
  if (problems.length) console.log(problems);
  await page.tap('#btn-toggle');
  await page.tap('.dialog .actions .primary');
  // 계정을 지워 둔다 (다음 기기 시험이 서버별 계정 수 제한에 걸리지 않게)
  await page.evaluate(() => fetch('/api/account', { method: 'DELETE', headers: { 'X-Jamsu': '1' } }));
  await ctx.close();
}

(async () => {
  const browser = await chromium.launch();
  const results = [];
  await run(browser, '폰', devices['Galaxy S9+'], results);
  await run(browser, '태블릿', devices['Galaxy Tab S4'], results);
  const failed = results.filter((r) => !r[1]);
  console.log('RESULT touch ' + (results.length - failed.length) + '/' + results.length + (failed.length ? ' 실패: ' + failed.map((f) => f[0]).join(', ') : ''));
  await browser.close();
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('FAIL', e.message.split('\n')[0]); process.exit(1); });
