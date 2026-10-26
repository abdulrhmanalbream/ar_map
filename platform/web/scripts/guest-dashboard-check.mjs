// Anonymous, read-only guest dashboard verification. Never accepts admin credentials.
// Set GUEST_DASHBOARD_URL and PLAYWRIGHT_MODULE. Optional GUEST_DASHBOARD_OUTPUT.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const base = new URL(process.env.GUEST_DASHBOARD_URL || 'http://127.0.0.1:5173');
assert.ok(['http:', 'https:'].includes(base.protocol) && !base.username && !base.password && !base.search, 'Use an HTTP(S) origin without credentials or query tokens.');
assert.ok(process.env.PLAYWRIGHT_MODULE, 'Set PLAYWRIGHT_MODULE.');
const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.resolve(process.env.GUEST_DASHBOARD_OUTPUT || path.join(webRoot, 'playwright-results', 'guest-dashboard', ['localhost', '127.0.0.1'].includes(base.hostname) ? 'local' : 'production'));
assert.ok(output !== path.join(webRoot, 'public') && !output.startsWith(path.join(webRoot, 'public') + path.sep), 'Keep verification artifacts outside public assets.');
await fs.mkdir(output, { recursive: true });
const dashboardUrl = new URL('/dashboard', base).href;
const apiPath = '/api/v1/public/dashboard';
const apiUrl = new URL(apiPath, base).href;
const countNames = ['active', 'offline', 'needsHelp', 'paused', 'sharing', 'eyeConnected', 'watchConnected'];
const report = {
  result: 'RUNNING', startedAt: new Date().toISOString(), url: dashboardUrl,
  checks: [], requests: [], responses: [], privateApiAttempts: [], unexpectedDataRequests: [],
  pageErrors: [], failedAssets: [], responseErrors: [], cspViolations: [], browserInstrumentationOrigins: [],
  screenshots: [], protectionProbes: [], sourceAudit: [],
  limitations: ['Only anonymous aggregate data is read; no login or data mutation is performed.', 'Error and explicitly labeled synthetic mapping responses are injected only in this browser context. Source audit inspects the local working tree.'],
};
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome' });
const contexts = [];
const pendingResponses = [];
let currentPage;
const check = (name, extra = {}) => report.checks.push({ name, result: 'PASS', ...extra });
const isPublic = url => url.origin === base.origin && url.pathname === apiPath;
const sameKeys = (value, keys, label) => {
  assert.ok(value && typeof value === 'object' && !Array.isArray(value), `${label} must be an object.`);
  assert.deepEqual(Object.keys(value).sort(), [...keys].sort(), `${label} contains unexpected fields.`);
};
function assertPublicPayload(data) {
  sameKeys(data, ['updatedAt', 'group', 'counts', 'health'], 'Public response');
  sameKeys(data.group, ['name', 'memberCount'], 'Public group');
  sameKeys(data.counts, countNames, 'Public counts');
  sameKeys(data.health, ['status', 'aiConfigured', 'aiProvider'], 'Public health');
  assert.ok(typeof data.updatedAt === 'string' && Number.isFinite(Date.parse(data.updatedAt)), 'Public timestamp must be valid.');
  assert.ok(typeof data.group.name === 'string' && data.group.name.trim().length > 0 && data.group.name.length <= 200);
  assert.ok(Number.isSafeInteger(data.group.memberCount) && data.group.memberCount >= 0);
  for (const name of countNames) assert.ok(Number.isSafeInteger(data.counts[name]) && data.counts[name] >= 0 && data.counts[name] <= data.group.memberCount, `Invalid aggregate count: ${name}.`);
  assert.equal(data.health.status, 'ok');
  assert.equal(typeof data.health.aiConfigured, 'boolean');
  assert.ok(['gemini', 'openai', 'local'].includes(data.health.aiProvider), 'Only public AI provider identity may be exposed.');
  return data;
}
async function snapshot(page, name) {
  const filename = `${name}.png`;
  await page.screenshot({ path: path.join(output, filename), fullPage: true, animations: 'disabled' });
  report.screenshots.push(filename);
}
async function nativeGet(route) {
  const response = await fetch(new URL(route, base), { method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(20_000) });
  return response;
}
async function assertNativeBoundaries() {
  const response = await nativeGet(apiPath);
  assert.equal(response.status, 200, 'Public aggregates must not require login.');
  assert.equal(response.headers.get('set-cookie'), null, 'Public aggregate reads must not establish a session.');
  assert.match(response.headers.get('cache-control') || '', /no-store/, 'Public aggregates must remain fresh.');
  report.publicSummary = assertPublicPayload(await response.json());
  check('strict aggregate response allowlist; no member records or coordinates');
  for (const route of ['/api/v1/groups', '/api/v1/devices']) {
    const protectedResponse = await nativeGet(route);
    report.protectionProbes.push({ method: 'GET', path: route, status: protectedResponse.status });
    assert.equal(protectedResponse.status, 401, `${route} must remain authenticated.`);
    await protectedResponse.body?.cancel();
  }
  check('anonymous private group/device GETs remain protected');
}

async function createGuest(width, label, { savedSession = false, failPublic = false, mockPayload = null } = {}) {
  const context = await browser.newContext({ viewport: { width, height: 900 }, locale: 'ar-SA', serviceWorkers: 'block' });
  contexts.push(context);
  const controller = { fail: failPublic, mockPayload };
  if (savedSession) {
    await context.addCookies([{ name: 'guest-read-only-probe', value: 'sentinel', domain: base.hostname, path: apiPath, httpOnly: true, secure: base.protocol === 'https:', sameSite: 'Lax' }]);
  }
  await context.addInitScript(({ savedSession }) => {
    if (savedSession) sessionStorage.setItem('sarab.admin.session', 'guest-read-only-sentinel');
    window.__guestCspViolations = [];
    document.addEventListener('securitypolicyviolation', event => {
      let blocked = event.blockedURI;
      try {
        const url = new URL(blocked);
        if (url.hostname === 'gc.kis.v2.scr.kaspersky-labs.com') return;
        blocked = url.origin === location.origin ? url.pathname : url.origin;
      } catch { /* fixed CSP tokens */ }
      window.__guestCspViolations.push({ directive: event.effectiveDirective, blocked });
    });
  }, { savedSession });
  await context.route('**/*', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.hostname === 'gc.kis.v2.scr.kaspersky-labs.com' && !url.pathname.startsWith('/api/')) {
      if (!report.browserInstrumentationOrigins.includes(url.origin)) report.browserInstrumentationOrigins.push(url.origin);
      await route.continue();
      return;
    }
    if (isPublic(url)) {
      const headers = await request.allHeaders();
      const observation = { page: label, method: request.method(), path: url.pathname, at: Date.now(), hasAuthorization: Boolean(headers.authorization), hasCookie: Boolean(headers.cookie), hasQuery: Boolean(url.search), injectedError: controller.fail, syntheticMapping: Boolean(controller.mockPayload) };
      report.requests.push(observation);
      if (observation.method !== 'GET' || observation.hasAuthorization || observation.hasCookie || observation.hasQuery) {
        report.privateApiAttempts.push(observation);
        await route.abort('blockedbyclient');
      } else if (controller.fail) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'LOCAL_TEST_UNAVAILABLE', message: 'تعذر التحديث في اختبار المتصفح.' }) });
      } else if (controller.mockPayload) {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(controller.mockPayload) });
      } else await route.continue();
      return;
    }
    if (url.pathname.startsWith('/api/') || !['GET', 'HEAD'].includes(request.method()) || ['fetch', 'xhr'].includes(request.resourceType())) {
      const observation = { page: label, path: url.origin === base.origin ? url.pathname : url.origin, method: request.method(), type: request.resourceType() };
      if (url.pathname.startsWith('/api/')) report.privateApiAttempts.push(observation);
      else report.unexpectedDataRequests.push(observation);
      await route.abort('blockedbyclient');
    } else await route.continue();
  });
  const page = await context.newPage();
  currentPage = page;
  page.setDefaultTimeout(12_000);
  page.on('pageerror', error => report.pageErrors.push({ page: label, message: error.message }));
  page.on('response', response => {
    const url = new URL(response.url());
    if (isPublic(url) && response.status() === 200) {
      pendingResponses.push(response.json().then(data => {
        const payload = assertPublicPayload(data);
        report.responses.push({ page: label, status: 200, at: Date.now(), data: payload });
      }).catch(error => report.responseErrors.push({ page: label, message: error.message })));
    } else if (url.origin === base.origin && !isPublic(url) && response.status() >= 400) report.failedAssets.push({ page: label, path: url.pathname, status: response.status() });
  });
  const response = await page.goto(dashboardUrl, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  assert.equal(response?.status(), 200);
  await page.getByTestId('guest-dashboard').waitFor();
  await page.evaluate(() => document.fonts.ready);
  assert.match(await page.title(), /المطوف الذكي/);
  assert.equal(new URL(page.url()).pathname, '/dashboard');
  assert.equal(await page.getByTestId('guest-admin-link').getAttribute('href'), '/dashboard/admin');
  assert.equal(await page.getByTestId('guest-home-link').getAttribute('href'), '/');
  assert.equal(await page.locator('input[type="password"], form, textarea, .leaflet-container, .member-list').count(), 0, 'Guest dashboard must not expose login, mutation forms, member rows or map coordinates.');
  const unexpectedButtons = await page.getByTestId('guest-dashboard').getByRole('button').evaluateAll(buttons => buttons.filter(button => !['guest-refresh', 'guest-retry'].includes(button.getAttribute('data-testid'))).map(button => button.textContent?.trim()));
  assert.deepEqual(unexpectedButtons, [], 'Guest view must expose only refresh/retry controls.');
  return { page, context, controller, label };
}

async function waitForData(test) {
  const { page, label } = test;
  await page.locator('[data-testid="guest-member-count"][data-count]').waitFor();
  await page.getByTestId('guest-updated-at').waitFor();
  await Promise.all(pendingResponses);
  const visible = await page.evaluate(names => ({
    updatedAt: document.querySelector('[data-testid="guest-updated-at"]')?.getAttribute('datetime'),
    members: Number(document.querySelector('[data-testid="guest-member-count"]')?.getAttribute('data-count')),
    counts: Object.fromEntries(names.map(name => [name, Number(document.querySelector(`[data-testid="guest-count-${name}"]`)?.getAttribute('data-count'))])),
  }), countNames);
  const response = [...report.responses].reverse().find(item => item.page === label && item.data.updatedAt === visible.updatedAt);
  assert.ok(response, 'Displayed aggregates must match a validated real public response.');
  assert.equal(visible.members, response.data.group.memberCount);
  assert.deepEqual(visible.counts, response.data.counts);
  return visible;
}

async function assertLayout(test, label) {
  const { page } = test;
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1 || document.body.scrollWidth > innerWidth + 1);
  assert.equal(overflow, false, `${label} must not overflow horizontally.`);
  const targets = await page.getByTestId('guest-dashboard').locator('button, a').evaluateAll(elements => elements.filter(element => {
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && (rect.width < 43 || rect.height < 43);
  }).map(element => ({ name: element.textContent?.trim(), width: element.getBoundingClientRect().width, height: element.getBoundingClientRect().height })));
  assert.deepEqual(targets, [], `${label} has small interactive targets.`);
  check(`responsive:${label}`, { width: page.viewportSize().width, horizontalOverflow: false });
}

async function assertIsolation(test) {
  await Promise.all(pendingResponses);
  report.cspViolations.push(...await test.page.evaluate(() => window.__guestCspViolations || []));
  assert.deepEqual(report.privateApiAttempts, [], 'Guest UI attempted private API access or sent credentials.');
  assert.deepEqual(report.unexpectedDataRequests, [], 'Guest UI attempted a mutation or unexpected data request.');
  assert.deepEqual(report.responseErrors, [], 'Public response escaped the aggregate allowlist.');
  assert.deepEqual(report.pageErrors, []);
  assert.deepEqual(report.failedAssets, []);
  assert.deepEqual(report.cspViolations, []);
}

async function sourceAudit() {
  const candidates = ['src/GuestDashboard.tsx', 'src/guest/GuestDashboard.tsx'];
  const files = [];
  for (const file of candidates) {
    try { files.push({ file, source: await fs.readFile(path.join(webRoot, file), 'utf8') }); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  assert.ok(files.length > 0, 'Guest dashboard source must exist for static isolation audit.');
  for (const { file, source } of files) {
    const forbidden = /\b(?:tokenStore|sessionStorage|localStorage|GEMINI_API_KEY|ADMIN_PASSWORD|getUserMedia|getCurrentPosition)\b|from\s+["'][^"']*\/api["']/;
    assert.equal(forbidden.test(source), false, `${file} must not read sessions, secrets, private API helpers or device capabilities.`);
    assert.match(source, /credentials\s*:\s*["']omit["']/);
    assert.ok(source.includes(apiPath));
    report.sourceAudit.push({ file, result: 'PASS' });
  }
  check('guest source keeps anonymous requests separate from admin session');
}

try {
  await sourceAudit();
  await assertNativeBoundaries();
  for (const width of [360, 390, 1440]) {
    const test = await createGuest(width, `normal-${width}`, { savedSession: width === 390 });
    await waitForData(test);
    await assertLayout(test, String(width));
    await snapshot(test.page, `guest-${width}`);
    if (width === 390) {
      assert.equal(await test.page.evaluate(() => sessionStorage.getItem('sarab.admin.session')), 'guest-read-only-sentinel');
      check('saved session and API-scoped cookie are never sent to public aggregate endpoint');
    }
    if (width === 1440) {
      const beforePoll = await waitForData(test);
      const previous = report.requests.filter(request => request.page === test.label).at(-1);
      const nextResponse = test.page.waitForResponse(response => response.url() === apiUrl && response.status() === 200);
      await test.page.waitForRequest(request => request.url() === apiUrl && request.method() === 'GET', { timeout: 8_000 });
      const intervalMs = Date.now() - previous.at;
      assert.ok(intervalMs >= 3_500 && intervalMs <= 7_500, `Expected a roughly five-second refresh, received ${intervalMs}ms.`);
      await nextResponse;
      await test.page.waitForFunction(previousTime => document.querySelector('[data-testid="guest-updated-at"]')?.getAttribute('datetime') !== previousTime, beforePoll.updatedAt);
      await waitForData(test);
      check('automatic five-second refresh', { intervalMs });
      const beforeRefresh = report.requests.filter(request => request.page === test.label).length;
      const refreshResponse = test.page.waitForResponse(response => response.url() === apiUrl && response.status() === 200);
      await test.page.getByTestId('guest-refresh').click();
      await refreshResponse;
      await waitForData(test);
      assert.ok(report.requests.filter(request => request.page === test.label).length > beforeRefresh);
      check('manual refresh remains read-only');
    }
    await assertIsolation(test);
    await test.context.close();
  }
  const errorTest = await createGuest(390, 'error-retry', { savedSession: true, failPublic: true });
  await errorTest.page.getByTestId('guest-error').waitFor();
  assert.equal(await errorTest.page.locator('[data-testid^="guest-count-"][data-count], [data-testid="guest-member-count"][data-count]').count(), 0, 'Initial failure must not fabricate zero counts.');
  await assertLayout(errorTest, '390-error');
  await snapshot(errorTest.page, 'guest-error-390');
  errorTest.controller.fail = false;
  const recovery = errorTest.page.waitForResponse(response => response.url() === apiUrl && response.status() === 200);
  await errorTest.page.getByTestId('guest-retry').click();
  await recovery;
  await waitForData(errorTest);
  await errorTest.page.getByTestId('guest-error').waitFor({ state: 'hidden' });
  await snapshot(errorTest.page, 'guest-recovered-390');
  await assertIsolation(errorTest);
  check('initial error is honest and retry restores real aggregates');
  await errorTest.context.close();

  // Distinct values catch swapped labels even when the real deployment is empty.
  // This response exists only in the isolated browser and is never sent to a server.
  const syntheticTest = await createGuest(390, 'synthetic-mapping', { mockPayload: {
    updatedAt: new Date().toISOString(), group: { name: 'اختبار متصفح — أرقام اصطناعية', memberCount: 10 },
    counts: { active: 1, offline: 2, needsHelp: 3, paused: 4, sharing: 5, eyeConnected: 6, watchConnected: 7 },
    health: { status: 'ok', aiConfigured: true, aiProvider: 'gemini' },
  } });
  await waitForData(syntheticTest);
  await assertLayout(syntheticTest, '390-synthetic-mapping');
  await assertIsolation(syntheticTest);
  check('seven distinct synthetic values map correctly; isolated browser response only');
  await syntheticTest.context.close();

  // A fresh, token-free browser may view the login form, but never submits it.
  const adminContext = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'ar-SA' });
  contexts.push(adminContext);
  await adminContext.route('**/api/**', route => route.abort('blockedbyclient'));
  const adminPage = await adminContext.newPage();
  currentPage = adminPage;
  await adminPage.goto(new URL('/dashboard/admin', base).href, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await adminPage.getByRole('heading', { name: 'أهلًا بعودتك', exact: true }).waitFor();
  assert.equal(await adminPage.locator('input[type="password"]').count(), 1);
  await snapshot(adminPage, 'protected-admin-login-390');
  check('administration route still requires login');
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error?.message || String(error);
  if (currentPage && !currentPage.isClosed()) await snapshot(currentPage, 'failure').catch(() => {});
  process.exitCode = 1;
} finally {
  await Promise.allSettled(contexts.map(context => context.close()));
  await Promise.allSettled(pendingResponses);
  await browser.close();
  report.finishedAt = new Date().toISOString();
  await fs.writeFile(path.join(output, 'fieldnotes.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ result: report.result, url: dashboardUrl, checks: report.checks.length, anonymousPublicRequests: report.requests.length, privateApiAttempts: report.privateApiAttempts.length, unexpectedDataRequests: report.unexpectedDataRequests.length, pageErrors: report.pageErrors.length, responseErrors: report.responseErrors.length, cspViolations: report.cspViolations.length, protectionProbes: report.protectionProbes, error: report.error, output }, null, 2));
}
