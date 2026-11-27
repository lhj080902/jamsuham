// 클라우드 서버의 웹 화면 시험 (Playwright). 먼저 띄울 것: 시험 MC 서버 `node server.js 1.21.4 25570 stay`,
// 클라우드 서버 (포트 18787, JAVA_OPTS=-Djamsu.devLogin=true, premiumMode=open, allowPrivateTargets=true)
// 실행: node web-test.js <스크린샷 폴더> [walk 시나리오 MC 서버 포트 — 주면 이동도 시험]
const { chromium } = require('playwright');
const S = process.argv[2];
const WALK_PORT = process.argv[3];
(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'ko-KR', deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const problems = [];
  const results = [];
  const check = (name, pass) => { results.push([name, pass]); console.log((pass ? '  통과  ' : '  실패  ') + name); };
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') problems.push(m.type() + ': ' + m.text()); });
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  await page.goto('http://127.0.0.1:18787/');
  await page.waitForSelector('#login:not([hidden])');
  await page.fill('#dev-name', 'WebTester');
  await page.click('#btn-dev');
  await page.waitForSelector('#main:not([hidden])');
  check('로그인 → 본 화면', await page.isVisible('#btn-add'));

  // 서버 추가
  await page.click('#btn-add');
  await page.waitForSelector('.dialog input');
  const inputs = page.locator('.dialog input:not([type=checkbox])');
  await inputs.nth(0).fill('웹 시험 서버');
  await inputs.nth(1).fill('127.0.0.1:25570');
  await page.click('.dialog .actions .primary');
  await page.waitForSelector('.server');
  check('서버 추가', (await page.locator('.server .name').allInnerTexts()).includes('웹 시험 서버'));
  await page.waitForSelector('#session:not([hidden])');

  // 시작
  await page.click('#btn-toggle');
  await page.waitForFunction(() => document.getElementById('s-status').textContent.startsWith('접속 중 ·'), null, { timeout: 30000 });
  check('시작 → 접속 중', true);
  await page.waitForFunction(() => [...document.querySelectorAll('#chat .line')].some((e) => e.textContent.includes('반가워요')));
  check('채팅 수신', true);
  check('목록에 "클라우드에서 실행 중"', (await page.locator('.server .where.cloud').count()) === 1);

  await page.fill('#input', '웹에서 보낸 채팅');
  await page.press('#input', 'Enter');
  await page.waitForTimeout(1000);
  check('채팅 보내면 입력창이 비워짐', (await page.inputValue('#input')) === '');

  await page.waitForSelector('#s-menu:not([hidden])');
  await page.click('#btn-menu');
  await page.waitForSelector('.dialog .item');
  const items = await page.locator('.dialog .item').allInnerTexts();
  check('서버 메뉴 창에 아이템', items.length === 2 && items[1].includes('리소스팩 적용하기'));
  // 메뉴 창에서 바로 자동 클릭 규칙 추가
  await page.locator('.dialog .itemwrap', { hasText: '#13' }).locator('.auto').click();
  await page.waitForSelector('.dialog h2:has-text("자동 클릭 규칙 추가")');
  const ruleText = await page.innerText('.dialog p');
  await page.click('.dialog .actions .primary');
  await page.waitForFunction(() => !document.getElementById('toast').hidden && document.getElementById('toast').textContent.includes('추가했습니다'), null, { timeout: 8000 });
  check('메뉴 창에서 자동 클릭 규칙 추가', ruleText.includes('리소스팩 적용 > 리소스팩 적용하기'));
  await page.waitForSelector('#s-menu:not([hidden])');
  await page.click('#btn-menu');
  await page.waitForSelector('.dialog .item');
  await page.locator('.dialog .item', { hasText: '#13' }).first().click();
  await page.waitForFunction(() => document.getElementById('shade').hidden && document.getElementById('s-menu').hidden, null, { timeout: 8000 });
  check('메뉴 클릭 → 메뉴 닫힘', true);

  await page.fill('#input', '/s');
  await page.press('#input', 'Tab');
  await page.waitForSelector('#suggest button');
  check('명령어 자동완성', (await page.locator('#suggest button').allInnerTexts()).join(',') === 'spawn,server');
  await page.fill('#input', '');

  await page.click('[data-tab=players]');
  await page.waitForSelector('.player');
  check('접속자 목록', (await page.locator('.player').count()) === 2);
  await page.click('[data-tab=status]');
  const statusText = await page.innerText('#p-status');
  check('상태 탭', statusText.includes('클라우드 (폰을 꺼도 유지됩니다)'));
  check('상태 탭에 자동 재접속·자동 클릭 표시', statusText.includes('켜짐 (킥당해도 재접속)') && statusText.includes('규칙 1개'));

  // 계정 창: 다른 기기 로그아웃 버튼
  const ctx2 = await browser.newContext({ locale: 'ko-KR' });
  const page2 = await ctx2.newPage();
  await page2.goto('http://127.0.0.1:18787/');
  await page2.fill('#dev-name', 'WebTester');
  await page2.click('#btn-dev');
  await page2.waitForSelector('#main:not([hidden])');
  await page.waitForFunction(() => !document.getElementById('toast').hidden && document.getElementById('toast').textContent.includes('새 기기가 로그인'), null, { timeout: 8000 });
  check('다른 브라우저가 로그인하면 알림', true);
  await page.click('#btn-account');
  await page.waitForSelector('.dialog .device');
  await page.screenshot({ path: S + '/shots/21-account.png' });
  const revoke = page.locator('.dialog .device button');
  check('다른 기기에 로그아웃 버튼', (await revoke.count()) === 1);
  await revoke.first().click();
  await page2.waitForSelector('#login:not([hidden])', { timeout: 10000 });
  check('로그아웃시킨 기기는 로그인 화면으로', true);
  await ctx2.close();
  await page.keyboard.press('Escape');

  // 이동: 평평한 땅 시험 서버에서 키보드 D(동쪽)로 걷고, 떼면 멈추는지
  if (WALK_PORT) {
    await page.click('#btn-add');
    await page.waitForSelector('.dialog input');
    const f = page.locator('.dialog input:not([type=checkbox])');
    await f.nth(0).fill('걷기 서버');
    await f.nth(1).fill('127.0.0.1:' + WALK_PORT);
    await page.locator('.dialog input[type=checkbox]').nth(2).uncheck(); // 들어가면 한두 걸음 걷기 끔
    await page.click('.dialog .actions .primary');
    await page.waitForFunction(() => document.getElementById('s-name').textContent === '걷기 서버', null, { timeout: 8000 });
    await page.click('#btn-toggle');
    await page.click('[data-tab=move]');
    await page.waitForFunction(() => document.getElementById('pos').textContent.startsWith('X '), null, { timeout: 30000 });
    const xOf = async () => parseFloat((await page.textContent('#pos')).split('·')[0].replace('X', ''));
    const x0 = await xOf();
    await page.keyboard.down('d');
    await page.waitForTimeout(1300);
    await page.screenshot({ path: S + '/shots/22-move.png' });
    await page.keyboard.up('d');
    await page.waitForTimeout(900);
    const x1 = await xOf();
    await page.waitForTimeout(1200);
    const x2 = await xOf();
    console.log('        x', x0, '→', x1, '→', x2);
    check('키보드 D 로 동쪽으로 걸음', x1 - x0 > 3);
    check('키를 떼면 멈춤', x1 === x2);
    // 조이스틱: 위쪽(북)으로 끌기
    const z0 = parseFloat((await page.textContent('#pos')).split('·')[2].replace('Z', ''));
    const box = await page.locator('#stick').boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, box.y + 8, { steps: 4 });
    await page.waitForTimeout(1200);
    await page.mouse.up();
    await page.waitForTimeout(900);
    const z1 = parseFloat((await page.textContent('#pos')).split('·')[2].replace('Z', ''));
    console.log('        z', z0, '→', z1);
    check('조이스틱 위로 끌면 북쪽으로 걸음', z0 - z1 > 3);
    await page.click('#btn-toggle');
    await page.click('.dialog .actions .primary');
    await page.waitForFunction(() => document.getElementById('btn-toggle').textContent === '시작', null, { timeout: 10000 });
    check('끊은 뒤 이동 탭은 안내만', (await page.textContent('#pos')).includes('접속하면'));
    await page.click('#btn-edit');
    await page.click('.dialog .actions .danger');
    await page.click('.dialog .actions .danger');
    await page.waitForFunction(() => document.querySelectorAll('.server').length === 1);
    await page.click('.server');
    await page.waitForSelector('#session:not([hidden])');
  }

  // 끊기 → 삭제
  await page.click('#btn-toggle');
  await page.click('.dialog .actions .primary');
  await page.waitForFunction(() => document.getElementById('btn-toggle').textContent === '시작', null, { timeout: 10000 });
  check('끊기', true);
  await page.click('#btn-edit');
  await page.click('.dialog .actions .danger');
  await page.click('.dialog .actions .danger');
  await page.waitForFunction(() => document.querySelectorAll('.server').length === 0);
  check('서버 삭제', true);

  const real = problems.filter((p) => !p.includes('401') && !p.includes('ERR_TUNNEL') && !p.includes('ERR_PROXY'));
  check('브라우저 오류 없음', real.length === 0);
  if (real.length) console.log(JSON.stringify(real, null, 1));
  const failed = results.filter((r) => !r[1]);
  console.log('RESULT web ' + (results.length - failed.length) + '/' + results.length);
  await browser.close();
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('FAIL', e.message.split('\n')[0]); process.exit(1); });
