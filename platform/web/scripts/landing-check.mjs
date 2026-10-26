// Public-page verification only: no credentials, authenticated requests, or API writes.
// Run from platform/web with PLAYWRIGHT_MODULE and optionally LANDING_URL/LANDING_OUTPUT.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const base = new URL(process.env.LANDING_URL || 'http://127.0.0.1:5173');
assert.ok(['http:', 'https:'].includes(base.protocol), 'LANDING_URL must be HTTP(S).');
assert.ok(!base.username && !base.password && !base.search, 'Do not supply credentials or query tokens in LANDING_URL.');
assert.ok(process.env.PLAYWRIGHT_MODULE, 'Set PLAYWRIGHT_MODULE to the installed Playwright module.');
const publicUrl = new URL('/', base).href;
const requireCsp = process.env.LANDING_REQUIRE_CSP === '1' || base.protocol === 'https:';
const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.resolve(process.env.LANDING_OUTPUT || path.join(webRoot, 'playwright-results', 'landing', base.hostname === '127.0.0.1' || base.hostname === 'localhost' ? 'local' : 'production'));
assert.ok(!output.startsWith(path.join(webRoot, 'public') + path.sep), 'Verification artifacts must not be public website assets.');
await fs.mkdir(output, { recursive: true });
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE).href);
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome' });
const report = {
  result: 'RUNNING', verifiedAt: new Date().toISOString(), url: publicUrl,
  scope: 'Public landing and local simulated demo; no private API calls or device capabilities permitted.',
  checks: [], viewports: [], privateApiAttempts: [], deviceCapabilityAttempts: [], pageErrors: [], failedResponses: [], failedRequests: [],
  browserInstrumentationOrigins: [],
  contentSecurityPolicies: [], cspViolations: [],
  nativeContentSecurityPolicy: null,
  sourceAudit: [], screenshots: [],
  limitations: ['This smoke test checks the public route only; it does not authenticate or verify real device, vision, navigation, or lap sensing.', 'Static source checks inspect the local working tree; runtime assertions and screenshots inspect LANDING_URL.'],
};
let currentPage;
const contexts = [];
const cspDirectives = csp => Object.fromEntries(csp.split(';').map(value => value.trim().split(/\s+/)).filter(([name]) => name).map(([name, ...values]) => [name, values]));
function assertStrictCsp(csp, browserResponse = false) {
  assert.ok(csp, 'Built public landing must be served with Content-Security-Policy.');
  const directives = cspDirectives(csp);
  if (browserResponse) {
    // The installed browser security product adds its verified own origins to
    // Chrome's response header. Native HTTP below must still be strictly self.
    directives['script-src'] = directives['script-src']?.filter(source => !/^(?:https?|wss?):\/\/gc\.kis\.v2\.scr\.kaspersky-labs\.com$/.test(source));
  }
  assert.deepEqual(directives['default-src'], ["'self'"]);
  assert.deepEqual(directives['script-src'], ["'self'"], 'Public bundle must run under same-origin scripts without inline/eval exceptions.');
  assert.deepEqual(directives['object-src'], ["'none'"]);
  assert.deepEqual(directives['frame-ancestors'], ["'none'"]);
  assert.deepEqual(directives['base-uri'], ["'self'"]);
}
const record = (name, details = {}) => report.checks.push({ name, result: 'PASS', ...details });
const screenshot = async (page, name, fullPage = false) => {
  const filename = `${name}.png`;
  await page.screenshot({ path: path.join(output, filename), fullPage, animations: 'disabled' });
  report.screenshots.push(filename);
};
const demoScreenshot = async (page, name) => {
  const filename = `${name}.png`;
  await page.getByTestId('project-demo').screenshot({ path: path.join(output, filename), animations: 'disabled' });
  report.screenshots.push(filename);
};

async function createPage({ width = 1440, reducedMotion = 'no-preference', savedSession = false } = {}) {
  const context = await browser.newContext({ viewport: { width, height: 800 }, locale: 'ar-SA', reducedMotion, serviceWorkers: 'block' });
  contexts.push(context);
  await context.route('**/*', async route => {
    const request = route.request();
    const url = new URL(request.url());
    // This Windows host injects Kaspersky browser instrumentation. Keep its own
    // traffic out of application API counts without recording its session paths.
    if (url.hostname === 'gc.kis.v2.scr.kaspersky-labs.com' && !/^\/api(?:\/|$)/.test(url.pathname)) {
      if (!report.browserInstrumentationOrigins.includes(url.origin)) report.browserInstrumentationOrigins.push(url.origin);
      await route.continue();
      return;
    }
    if (/^\/api(?:\/|$)/.test(url.pathname) || ['fetch', 'xhr'].includes(request.resourceType()) || !['GET', 'HEAD'].includes(request.method()) || request.headers().authorization) {
      report.privateApiAttempts.push({ path: url.pathname, method: request.method(), type: request.resourceType(), savedSession });
      await route.abort('blockedbyclient');
    } else {
      await route.continue();
    }
  });
  await context.addInitScript(({ savedSession }) => {
    // A deliberately invalid sentinel exercises route isolation without reading a real session.
    if (savedSession) sessionStorage.setItem('sarab.admin.session', 'landing-read-only-sentinel');
    window.__landingCapabilityAttempts = [];
    window.__landingCspViolations = [];
    document.addEventListener('securitypolicyviolation', event => {
      let blocked = event.blockedURI;
      try {
        const url = new URL(blocked);
        if (url.hostname === 'gc.kis.v2.scr.kaspersky-labs.com') return;
        blocked = url.origin === location.origin ? url.pathname : url.origin;
      } catch { /* CSP also uses fixed values such as inline and eval. */ }
      window.__landingCspViolations.push({ directive: event.effectiveDirective, blocked });
    });
    const blocked = name => (..._args) => {
      window.__landingCapabilityAttempts.push(name);
      return Promise.reject(new DOMException('Public demo must remain simulated.', 'NotAllowedError'));
    };
    if (navigator.mediaDevices) {
      for (const method of ['getUserMedia', 'getDisplayMedia', 'enumerateDevices']) {
        if (method in navigator.mediaDevices) Object.defineProperty(navigator.mediaDevices, method, { configurable: true, value: blocked(method) });
      }
    }
    if (navigator.geolocation) {
      for (const method of ['getCurrentPosition', 'watchPosition']) {
        Object.defineProperty(navigator.geolocation, method, { configurable: true, value: () => window.__landingCapabilityAttempts.push(method) });
      }
    }
    for (const name of ['SpeechRecognition', 'webkitSpeechRecognition']) {
      if (name in window) Object.defineProperty(window, name, { configurable: true, value: function () { window.__landingCapabilityAttempts.push(name); throw new Error('Public demo must remain simulated.'); } });
    }
  }, { savedSession });
  const page = await context.newPage();
  currentPage = page;
  page.setDefaultTimeout(12_000);
  page.on('pageerror', error => report.pageErrors.push(error.message));
  page.on('requestfailed', request => {
    const url = new URL(request.url());
    if (url.origin === base.origin) report.failedRequests.push({ path: url.pathname, type: request.resourceType(), error: request.failure()?.errorText });
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === base.origin && response.status() >= 400) report.failedResponses.push({ path: url.pathname, status: response.status() });
  });
  const response = await page.goto(publicUrl, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  assert.equal(response?.status(), 200, 'Public root must return HTTP 200.');
  const csp = response.headers()['content-security-policy'];
  if (csp && !report.contentSecurityPolicies.includes(csp)) report.contentSecurityPolicies.push(csp);
  if (requireCsp) assertStrictCsp(csp, true);
  await page.locator('#lp-main').waitFor();
  await page.getByTestId('project-demo').waitFor();
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(() => {
    const logo = document.querySelector('.lp-event-logo');
    return logo?.complete && logo.naturalWidth > 0;
  });
  assert.match(await page.title(), /المطوف الذكي/, 'Public title must identify the Arabic project.');
  assert.equal(new URL(page.url()).pathname, '/', 'Public root must not redirect to an admin login.');
  assert.equal(await page.locator('.lp-admin').getAttribute('href'), '/dashboard');
  assert.equal(await page.locator('.lp-primary').getAttribute('href'), '#experience');
  assert.equal(await page.locator('input[type="password"]').count(), 0, 'Public page must not contain the admin login form.');
  assert.equal(await page.getByRole('tab').count(), 4, 'Four demo stages must expose accessible tab semantics.');
  const logo = await page.locator('.lp-event-logo').evaluate(image => ({ src: image.getAttribute('src'), width: image.naturalWidth, height: image.naturalHeight, alt: image.alt }));
  assert.deepEqual(logo, { src: '/brand/hedayathon-logo.png', width: 801, height: 494, alt: 'هداية ثون' }, 'Original event logo must load without replacing its aspect ratio.');
  const unsafeExternalLinks = await page.locator('a[target="_blank"]').evaluateAll(links => links.filter(link => !link.relList.contains('noopener') || !link.relList.contains('noreferrer')).map(link => link.textContent?.trim()));
  assert.deepEqual(unsafeExternalLinks, [], 'External reference links need safe new-window attributes.');
  return page;
}

async function assertPublicIsolation(page) {
  const attempts = await page.evaluate(() => window.__landingCapabilityAttempts || []);
  report.cspViolations.push(...await page.evaluate(() => window.__landingCspViolations || []));
  report.deviceCapabilityAttempts.push(...attempts);
  assert.deepEqual(report.privateApiAttempts, [], 'Public route attempted private API requests.');
  assert.deepEqual(report.deviceCapabilityAttempts, [], 'Public demo attempted a real device capability.');
  assert.deepEqual(report.pageErrors, [], 'Public page has uncaught JavaScript errors.');
  assert.deepEqual(report.failedResponses, [], 'Public page has failed asset responses.');
  assert.deepEqual(report.failedRequests, [], 'Public page has failed resource requests.');
  assert.deepEqual(report.cspViolations, [], 'Public application violates its Content-Security-Policy.');
}

async function assertLayout(page, label) {
  const dimensions = await page.evaluate(() => ({
    viewport: innerWidth, document: document.documentElement.scrollWidth, body: document.body.scrollWidth,
    overflow: [...document.querySelectorAll('#lp-main *')].filter(element => {
      if (element instanceof SVGElement) return false;
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      return style.position !== 'fixed' && style.display !== 'none' && rect.width > 0 && (rect.right > innerWidth + 1 || rect.left < -1);
    }).slice(0, 8).map(element => ({ tag: element.tagName, className: element.getAttribute('class'), testId: element.getAttribute('data-testid') })),
  }));
  assert.ok(dimensions.document <= dimensions.viewport + 1 && dimensions.body <= dimensions.viewport + 1, `${label}: horizontal page overflow ${JSON.stringify(dimensions)}`);
  // Visible interactive controls must retain usable targets; decorative offscreen artwork is not an overflow failure.
  const tooSmall = await page.locator('#lp-main button, #lp-main select, .lp-primary, .lp-admin').evaluateAll(elements => elements.filter(element => {
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && (rect.width < 43 || rect.height < 43);
  }).map(element => ({ name: element.getAttribute('aria-label') || element.textContent?.trim(), width: element.getBoundingClientRect().width, height: element.getBoundingClientRect().height })));
  assert.deepEqual(tooSmall, [], `${label}: interactive targets below 44px.`);
  record(`layout:${label}`, { width: dimensions.viewport, horizontalOverflow: false });
}

async function assertKeyboard(page) {
  await page.goto(publicUrl, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('project-demo').waitFor();
  await page.keyboard.press('Tab');
  const firstFocus = await page.evaluate(() => {
    const element = document.activeElement;
    const style = getComputedStyle(element);
    return { name: element.textContent?.trim(), href: element.getAttribute('href'), visible: element.matches(':focus-visible'), outlineStyle: style.outlineStyle, outlineWidth: parseFloat(style.outlineWidth) };
  });
  assert.ok(firstFocus.visible, 'Keyboard focus must be visible.');
  assert.ok(firstFocus.outlineStyle !== 'none' && firstFocus.outlineWidth >= 1, 'Keyboard focus needs a visible outline.');
  // Native buttons must support keyboard operation, independent of optional arrow-key tab navigation.
  await page.getByTestId('pd-step-3').focus();
  await page.keyboard.press('Enter');
  assert.equal(await page.getByTestId('project-demo').getAttribute('data-step'), '3');
  await page.getByTestId('pd-increment').focus();
  await page.keyboard.press('Space');
  assert.equal(await page.getByTestId('pd-count').getAttribute('data-count'), '1');
  await page.getByTestId('pd-undo').focus();
  await page.keyboard.press('Enter');
  assert.equal(await page.getByTestId('pd-count').getAttribute('data-count'), '0');
  const question = page.locator('.lp-question').first();
  await question.locator('summary').focus();
  await page.keyboard.press('Enter');
  assert.equal(await question.evaluate(element => element.open), true, 'FAQ must open from the keyboard.');
  await page.keyboard.press('Enter');
  assert.equal(await question.evaluate(element => element.open), false, 'FAQ must close from the keyboard.');
  record('keyboard focus and native manual controls', { firstFocus: firstFocus.name });
}

async function assertDemo(page) {
  const demo = page.getByTestId('project-demo');
  assert.equal(await demo.getAttribute('data-step'), '0');
  assert.equal(await demo.getAttribute('data-playing'), 'false', 'Demo must wait for an intentional start.');
  for (let step = 0; step < 4; step++) {
    await page.getByTestId(`pd-step-${step}`).click();
    assert.equal(await demo.getAttribute('data-step'), String(step));
    assert.equal(await demo.getAttribute('data-playing'), 'false', 'Manual stage changes must pause playback.');
    await assertLayout(page, `desktop-demo-${step}`);
    await demoScreenshot(page, `demo-step-${step}`);
  }
  await page.getByTestId('pd-reset-demo').click();
  assert.equal(await demo.getAttribute('data-step'), '0');
  await page.getByTestId('pd-next').click();
  assert.equal(await demo.getAttribute('data-step'), '1');
  await page.getByTestId('pd-prev').click();
  assert.equal(await demo.getAttribute('data-step'), '0');
  await page.getByTestId('pd-play-toggle').click();
  assert.equal(await demo.getAttribute('data-playing'), 'true');
  await page.waitForFunction(() => document.querySelector('[data-testid="project-demo"]')?.getAttribute('data-step') !== '0', undefined, { timeout: 10_000 });
  await page.getByTestId('pd-play-toggle').click();
  assert.equal(await demo.getAttribute('data-playing'), 'false');
  const pausedStep = await demo.getAttribute('data-step');
  await page.waitForTimeout(6_500);
  assert.equal(await demo.getAttribute('data-step'), pausedStep, 'Paused demo must not advance.');
  await page.getByTestId('pd-step-1').click();
  await page.getByTestId('pd-landmark').click();
  await page.getByTestId('pd-landmark-info').waitFor();
  assert.equal(await page.getByTestId('pd-landmark').getAttribute('aria-expanded'), 'true');
  assert.match(await page.getByTestId('pd-landmark-info').innerText(), /مثال لبطاقة معلم/);
  await page.getByTestId('pd-step-2').click();
  const answers = {};
  for (const language of ['ar', 'en', 'ur', 'id', 'tr']) {
    await page.getByTestId('pd-language').selectOption(language);
    await page.getByTestId('pd-question').click();
    const answer = page.getByTestId('pd-answer');
    await answer.waitFor();
    answers[language] = (await answer.innerText()).trim();
    assert.ok(answers[language].length > 10, `Local ${language} answer must be present.`);
  }
  assert.equal(new Set(Object.values(answers)).size, 5, 'Each supported language needs a localized local answer.');
  await page.getByTestId('pd-step-3').click();
  const count = async () => Number(await page.getByTestId('pd-count').getAttribute('data-count'));
  await page.getByTestId('pd-mode-tawaf').click();
  if (await page.getByTestId('pd-counter-reset').isEnabled()) await page.getByTestId('pd-counter-reset').click();
  for (let index = 0; index < 10; index++) {
    if (await page.getByTestId('pd-increment').isEnabled()) await page.getByTestId('pd-increment').click();
    assert.ok(await count() >= 0 && await count() <= 7);
  }
  assert.equal(await count(), 7, 'Manual count must cap at seven.');
  await page.getByTestId('pd-mode-sai').click();
  assert.equal(await count(), 0, 'Tawaf and Sai need independent local counts.');
  await page.getByTestId('pd-increment').click();
  assert.equal(await count(), 1);
  await page.getByTestId('pd-mode-tawaf').click();
  assert.equal(await count(), 7);
  for (let index = 0; index < 10; index++) {
    if (await page.getByTestId('pd-undo').isEnabled()) await page.getByTestId('pd-undo').click();
    assert.ok(await count() >= 0 && await count() <= 7);
  }
  assert.equal(await count(), 0, 'Undo must not make the count negative.');
  await page.getByTestId('pd-mode-sai').click();
  assert.equal(await count(), 1, 'Undo in Tawaf must not overwrite Sai.');
  await page.getByTestId('pd-counter-reset').click();
  assert.equal(await count(), 0);
  record('four stages, playback pause, landmark detail, five local languages, independent bounded manual counts');
}

async function sourceAudit() {
  const forbidden = [
    { name: 'network request', pattern: /\b(?:fetch|XMLHttpRequest|WebSocket|EventSource)\s*\(/ },
    { name: 'private API import', pattern: /from\s+["'][^"']*\/api["']/ },
    { name: 'credentials or admin session access', pattern: /\b(?:tokenStore|sessionStorage|localStorage|GEMINI_API_KEY|ADMIN_PASSWORD)\b/ },
    { name: 'camera/microphone/location capability', pattern: /\b(?:getUserMedia|getDisplayMedia|getCurrentPosition|watchPosition|SpeechRecognition|webkitSpeechRecognition)\s*\(/ },
  ];
  for (const name of ['landing/LandingPage.tsx', 'landing/ProjectDemo.tsx', 'landing/demoState.ts']) {
    const source = await fs.readFile(path.join(webRoot, 'src', name), 'utf8');
    const findings = forbidden.filter(rule => rule.pattern.test(source)).map(rule => rule.name);
    report.sourceAudit.push({ file: `src/${name}`, findings });
    assert.deepEqual(findings, [], `${name} must remain a public, local simulation.`);
  }
  record('public component source isolation');
}

try {
  await sourceAudit();
  if (requireCsp) {
    const response = await fetch(publicUrl, { redirect: 'manual', signal: AbortSignal.timeout(20_000) });
    assert.equal(response.status, 200);
    report.nativeContentSecurityPolicy = response.headers.get('content-security-policy');
    assertStrictCsp(report.nativeContentSecurityPolicy);
    await response.body?.cancel();
    record('strict server CSP verified independently of browser instrumentation');
  }
  for (const width of [360, 390, 768, 1440, 1920]) {
    const page = await createPage({ width });
    const cta = await page.locator('.lp-primary').boundingBox();
    assert.ok(cta && cta.y >= 0 && cta.y + cta.height <= 800, `${width}px: primary CTA must be visible in the first viewport.`);
    await assertLayout(page, `${width}-hero`);
    await screenshot(page, `hero-${width}`);
    await screenshot(page, `full-${width}`, true);
    for (let step = 0; step < 4; step++) {
      await page.getByTestId(`pd-step-${step}`).click();
      await assertLayout(page, `${width}-step-${step}`);
    }
    if (width === 360 || width === 390) await demoScreenshot(page, `manual-counter-${width}`);
    if (width === 360) {
      await page.getByTestId('pd-step-2').click();
      for (const language of ['ar', 'en', 'ur', 'id', 'tr']) {
        await page.getByTestId('pd-language').selectOption(language);
        await page.getByTestId('pd-question').click();
        await assertLayout(page, `360-answer-${language}`);
      }
      await demoScreenshot(page, 'translated-answer-360');
      for (const question of await page.locator('.lp-question').all()) await question.locator('summary').click();
      await assertLayout(page, '360-faq-expanded');
      await screenshot(page, 'faq-expanded-360', true);
    }
    for (const anchor of ['experience', 'journey', 'features', 'questions']) {
      assert.equal(await page.locator(`#${anchor}`).count(), 1, `Missing public anchor #${anchor}.`);
      assert.ok(await page.locator(`a[href="#${anchor}"]`).count() > 0, `No navigation to #${anchor}.`);
    }
    await page.locator('.lp-primary').click();
    assert.equal(new URL(page.url()).hash, '#experience');
    report.viewports.push({ width, height: 800, firstViewportCta: true, allFourStages: true });
    await assertPublicIsolation(page);
    await page.context().close();
  }
  const interactivePage = await createPage();
  await assertDemo(interactivePage);
  await assertKeyboard(interactivePage);
  await assertPublicIsolation(interactivePage);
  await interactivePage.context().close();
  const reducedPage = await createPage({ width: 390, reducedMotion: 'reduce', savedSession: true });
  const reducedDemo = reducedPage.getByTestId('project-demo');
  assert.equal(await reducedDemo.getAttribute('data-playing'), 'false');
  assert.ok(await reducedPage.getByTestId('pd-play-toggle').isDisabled(), 'Reduced-motion preference must disable automatic playback.');
  await reducedPage.waitForTimeout(6_500);
  assert.equal(await reducedDemo.getAttribute('data-step'), '0');
  await reducedPage.getByTestId('pd-next').click();
  assert.equal(await reducedDemo.getAttribute('data-step'), '1', 'Reduced-motion users retain manual navigation.');
  assert.equal(await reducedDemo.getAttribute('data-playing'), 'false');
  assert.equal(await reducedPage.evaluate(() => sessionStorage.getItem('sarab.admin.session')), 'landing-read-only-sentinel', 'Public route must not consume or clear an existing admin token.');
  await demoScreenshot(reducedPage, 'reduced-motion-saved-session-390');
  await assertPublicIsolation(reducedPage);
  record('reduced motion stays paused; existing session remains isolated');
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error?.message || String(error);
  if (currentPage && !currentPage.isClosed()) await screenshot(currentPage, 'failure', true).catch(() => {});
  process.exitCode = 1;
} finally {
  await Promise.allSettled(contexts.map(context => context.close()));
  await browser.close();
  report.finishedAt = new Date().toISOString();
  await fs.writeFile(path.join(output, 'fieldnotes.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ result: report.result, url: publicUrl, checks: report.checks.length, viewports: report.viewports, privateApiAttempts: report.privateApiAttempts.length, deviceCapabilityAttempts: report.deviceCapabilityAttempts.length, pageErrors: report.pageErrors.length, failedResponses: report.failedResponses.length, failedRequests: report.failedRequests.length, error: report.error, output }, null, 2));
}
