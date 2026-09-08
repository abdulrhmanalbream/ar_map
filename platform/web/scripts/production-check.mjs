// Read-only production UI verification; only authentication login/logout mutate session state.
import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const base = process.env.SARAB_PRODUCTION_URL;
const envFile = process.env.SARAB_ADMIN_ENV;
if (!base?.startsWith('https://') || !envFile || !process.env.PLAYWRIGHT_MODULE) {
  throw new Error('Set HTTPS SARAB_PRODUCTION_URL, SARAB_ADMIN_ENV and PLAYWRIGHT_MODULE.');
}
const text = await fs.readFile(envFile, 'utf8');
function adminValue(key) {
  const match = text.match(new RegExp(`^\\s*(?:export\\s+)?${key}\\s*=\\s*(.*)$`, 'm'));
  let value = match?.[1]?.trim();
  if (!value) throw new Error(`Missing required ${key} configuration.`);
  if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
  return value;
}
// No other environment field is parsed, transmitted or reported.
const username = adminValue('ADMIN_USERNAME');
const password = adminValue('ADMIN_PASSWORD');
const redact = value => String(value).split(password).join('[redacted]').split(username).join('[admin]');
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome' });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'ar-SA' });
const page = await context.newPage();
const errors = [];
const responses = [];
page.on('pageerror', error => errors.push(redact(error.message)));
page.on('response', response => {
  const url = new URL(response.url());
  if (url.origin === new URL(base).origin) responses.push({ path: url.pathname, status: response.status() });
});
await page.addInitScript(() => {
  window.__sarabCspViolations = [];
  document.addEventListener('securitypolicyviolation', event => {
    window.__sarabCspViolations.push({ directive: event.effectiveDirective, blockedURI: event.blockedURI });
  });
});
const screenshots = path.resolve('playwright-results/production');
await fs.mkdir(screenshots, { recursive: true });
let loggedIn = false;
try {
  const mainResponse = await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  assert.equal(mainResponse.status(), 200);
  assert.ok(mainResponse.headers()['content-security-policy']);
  await page.getByRole('heading', { name: 'أهلًا بعودتك' }).waitFor();
  assert.match(await page.title(), /المطوف الذكي/);
  assert.equal(await page.locator('.brand strong').innerText(), 'المطوف الذكي');
  await page.screenshot({ path: path.join(screenshots, 'login-desktop.png'), fullPage: true, animations: 'disabled' });
  await page.getByLabel('اسم المستخدم', { exact: true }).fill(username);
  await page.getByLabel('كلمة المرور', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'تسجيل الدخول', exact: true }).click();
  await page.getByRole('heading', { name: 'مركز المتابعة', exact: true }).waitFor();
  loggedIn = true;
  await page.getByText('تحديث كل ٥ ثوانٍ', { exact: true }).waitFor();
  await page.evaluate(() => document.fonts.ready);
  await page.locator('.leaflet-tile-loaded').first().waitFor({ timeout: 15_000 });
  await page.waitForFunction(() => [...document.querySelectorAll('.leaflet-tile')].every(tile => tile.complete && tile.naturalWidth > 0), undefined, { timeout: 15_000 });
  assert.equal(await page.locator('.brand strong').innerText(), 'المطوف الذكي');
  assert.match(await page.locator('.account strong').innerText(), /المطوف الذكي/);
  await page.screenshot({ path: path.join(screenshots, 'dashboard-desktop.png'), fullPage: true, animations: 'disabled' });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.getByRole('button', { name: 'المجموعات', exact: true }).click();
  await page.getByRole('heading', { name: /المطوف الذكي/ }).first().waitFor();
  await page.screenshot({ path: path.join(screenshots, 'groups-desktop.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: 'حالة المنصة', exact: true }).click();
  await page.getByRole('heading', { name: 'اتصال الخدمات', exact: true }).waitFor();
  await page.screenshot({ path: path.join(screenshots, 'health-desktop.png'), fullPage: true, animations: 'disabled' });
  const healthResponse = await context.request.get(`${base}/api/v1/health`);
  assert.equal(healthResponse.status(), 200);
  const health = await healthResponse.json();
  await page.getByRole('button', { name: 'المتابعة المباشرة', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.leaflet-tile-loaded').first().waitFor({ timeout: 15_000 });
  await page.screenshot({ path: path.join(screenshots, 'dashboard-mobile.png'), fullPage: true, animations: 'disabled' });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  const csp = await page.evaluate(() => window.__sarabCspViolations);
  assert.deepEqual(csp, []);
  assert.deepEqual(errors, []);
  assert.deepEqual(responses.filter(response => response.status >= 400), []);
  await page.getByRole('button', { name: 'تسجيل الخروج', exact: true }).click();
  await page.getByRole('heading', { name: 'أهلًا بعودتك' }).waitFor();
  loggedIn = false;
  assert.equal(await page.evaluate(() => sessionStorage.getItem('sarab.admin.session')), null);
  console.log(JSON.stringify({ result: 'PASS', base, brand: 'المطوف الذكي', renamedAdminAndGroupVerified: true, trustedTls: true, cspViolations: 0, pageErrors: 0, failedSameOriginResponses: 0, mobileOverflow: false, health: { status: health.status, aiConfigured: health.aiConfigured, aiProvider: health.aiProvider, version: health.version }, screenshots }, null, 2));
} catch (error) {
  await page.screenshot({ path: path.join(screenshots, 'verification-failure.png'), fullPage: true, animations: 'disabled' }).catch(() => {});
  console.error(JSON.stringify({ currentUrl: page.url(), observedResponses: responses, pageErrors: errors }));
  console.error(redact(error?.message || error));
  process.exitCode = 1;
} finally {
  if (loggedIn) {
    await page.evaluate(async () => {
      const token = sessionStorage.getItem('sarab.admin.session');
      try { if (token) await fetch('/api/v1/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${token}` } }); }
      finally { sessionStorage.removeItem('sarab.admin.session'); }
    }).catch(() => {});
  }
  await browser.close();
}
