// Run only against an isolated test database. Creates explicitly named integration test records.
import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import assert from "node:assert/strict";

const credentialFile = process.env.SARAB_TEST_CREDENTIALS;
if (!credentialFile || process.env.SARAB_ISOLATED_TEST !== "1")
  throw new Error(
    "Set SARAB_TEST_CREDENTIALS and SARAB_ISOLATED_TEST=1 for an isolated test server.",
  );
const credentials = JSON.parse(await fs.readFile(credentialFile, "utf8"));
const { chromium } = await import(
  process.env.PLAYWRIGHT_MODULE
    ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href
    : "playwright"
);
const browser = await chromium.launch({
  headless: true,
  ...(process.env.PLAYWRIGHT_CHANNEL
    ? { channel: process.env.PLAYWRIGHT_CHANNEL }
    : {}),
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  locale: "ar-SA",
});
const page = await context.newPage();
const base = process.env.SARAB_BASE_URL || "http://127.0.0.1:8080";
const screenshots = path.resolve("playwright-results");
await fs.mkdir(screenshots, { recursive: true });
const errors = [];
page.on("pageerror", (error) => errors.push(error.stack || error.message));
try {
  await page.goto(base);
  await page.getByRole("heading", { name: "أهلًا بعودتك" }).waitFor();
  await page.screenshot({
    path: path.join(screenshots, "login-desktop.png"),
    fullPage: true,
  });
  await page
    .getByLabel("اسم المستخدم", { exact: true })
    .fill(credentials.username);
  await page
    .getByLabel("كلمة المرور", { exact: true })
    .fill(credentials.password);
  await page.getByRole("button", { name: "تسجيل الدخول", exact: true }).click();
  await page
    .getByRole("heading", { name: "مركز المتابعة", exact: true })
    .waitFor();
  await page.getByText("تحديث كل ٥ ثوانٍ", { exact: true }).waitFor();
  await page.screenshot({
    path: path.join(screenshots, "dashboard-empty-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "المجموعات", exact: true }).click();
  const groupName = `مجموعة اختبار التكامل ${Date.now()}`;
  await page.getByLabel("اسم المجموعة", { exact: true }).fill(groupName);
  await page
    .getByRole("button", { name: "إنشاء المجموعة", exact: true })
    .click();
  await page.getByRole("heading", { name: groupName }).waitFor();
  const groupRow = page
    .locator("article")
    .filter({ has: page.getByRole("heading", { name: groupName }) });
  await groupRow
    .getByRole("button", { name: "إصدار رمز انضمام", exact: true })
    .click();
  await groupRow.locator(".enrollment code").waitFor();
  const code = await groupRow.locator(".enrollment code").textContent();
  const memberName = `عضو اختبار ${Date.now()}`;
  const enrolledResponse = await context.request.post(
    `${base}/api/v1/devices/enroll`,
    { data: { code, name: memberName, language: "ar" } },
  );
  assert.equal(enrolledResponse.status(), 201);
  const enrolled = await enrolledResponse.json();
  const deviceHeaders = { Authorization: `Bearer ${enrolled.token}` };
  const telemetry = await context.request.post(
    `${base}/api/v1/device/telemetry`,
    {
      headers: deviceHeaders,
      data: {
        status: "active",
        sharingEnabled: true,
        location: {
          lat: 21.423,
          lng: 39.8265,
          accuracyM: 14,
          recordedAt: new Date().toISOString(),
        },
        batteryPercent: 75,
        cameraConnected: true,
        imuTracking: true,
        watchConnected: true,
        destinationName: "وجهة اختبار",
        lap: { mode: "tawaf", count: 2, target: 7, confidence: "manual" },
      },
    },
  );
  assert.equal(telemetry.status(), 200);
  await page
    .getByRole("button", { name: "المتابعة المباشرة", exact: true })
    .click();
  await page
    .getByRole("button")
    .filter({ hasText: memberName })
    .first()
    .click({ timeout: 15_000 });
  await page.getByRole("heading", { name: memberName }).waitFor();
  await page.screenshot({
    path: path.join(screenshots, "dashboard-device-desktop.png"),
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "إرسال رسالة للعضو", exact: true })
    .click();
  await page
    .getByLabel("نص الرسالة", { exact: true })
    .fill("رسالة اختبار التكامل — يرجى تأكيد الاستلام.");
  await page
    .locator("form")
    .getByRole("button", { name: "إرسال التنبيه", exact: true })
    .click();
  await page
    .getByText("أُرسل التنبيه. تابع حالة الوصول والتأكيد في سجل التنبيهات.", {
      exact: true,
    })
    .waitFor();
  const pending = await (
    await context.request.get(`${base}/api/v1/device/alerts`, {
      headers: deviceHeaders,
    })
  ).json();
  assert.ok(pending.alerts.length > 0);
  const alert = pending.alerts.find((item) =>
    item.message.includes("رسالة اختبار التكامل"),
  );
  assert.ok(alert);
  await context.request.post(
    `${base}/api/v1/device/alerts/${alert.id}/delivered`,
    { headers: deviceHeaders },
  );
  await context.request.post(`${base}/api/v1/device/alerts/${alert.id}/ack`, {
    headers: deviceHeaders,
  });
  await page
    .getByText("أكّد ١", { exact: true })
    .first()
    .waitFor({ timeout: 15_000 });
  await page.screenshot({
    path: path.join(screenshots, "alerts-desktop.png"),
    fullPage: true,
  });
  const helpMessage = `طلب مساعدة لاختبار ${memberName}`;
  await context.request.post(`${base}/api/v1/device/alerts`, {
    headers: deviceHeaders,
    data: {
      kind: "help",
      message: helpMessage,
      clientId: `ui-smoke-${Date.now()}`,
    },
  });
  const helpRow = page.locator("article").filter({ hasText: helpMessage });
  await helpRow
    .getByRole("button", {
      name: "تأكيد الاطلاع على طلب المساعدة",
      exact: true,
    })
    .click({ timeout: 15_000 });
  await helpRow
    .getByText("اطّلع المشرف على الطلب", { exact: false })
    .waitFor({ timeout: 15_000 });
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "المتابعة المباشرة", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "تصفية حسب المجموعة" })
    .selectOption(enrolled.group.id);
  await page.screenshot({
    path: path.join(screenshots, "dashboard-mobile.png"),
    fullPage: true,
  });
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > innerWidth,
  );
  assert.equal(
    overflow,
    false,
    "mobile viewport must not overflow horizontally",
  );
  await context.request.post(`${base}/api/v1/device/privacy`, {
    headers: deviceHeaders,
    data: { sharingEnabled: false },
  });
  await page
    .getByText("بانتظار المواقع المشتركة", { exact: true })
    .waitFor({ timeout: 15_000 });
  await page.getByRole("button", { name: "تسجيل الخروج", exact: true }).click();
  await page.getByRole("heading", { name: "أهلًا بعودتك" }).waitFor();
  assert.equal(
    await page.evaluate(() => sessionStorage.getItem("sarab.admin.session")),
    null,
  );
  assert.deepEqual(errors, []);
  console.log(
    "PASS: authenticated login, empty state, group/enrollment, consented map + device detail, member alert, delivery/ack, admin help ack, mobile overflow, privacy clearing and logout.",
  );
  console.log(`Screenshots: ${screenshots}`);
} finally {
  await browser.close();
}
