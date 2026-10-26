import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { createPlatform } from '../src/app.mjs';
import { VISION_BODY_LIMIT } from '../src/vision.mjs';
import { analyze, ask, landmark, gemini } from './vision-fixture.mjs';

async function fixture(t, extra = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'sarab-vision-api-test-'));
  let clock = Date.parse('2026-09-08T12:00:00Z');
  const platform = await createPlatform({ dataDir: directory, adminUsername: 'vision-test', adminPassword: 'Fixture-Only-Vision!92', geminiApiKey: 'mock-key', visionFetchImpl: async (_url, options) => gemini(JSON.parse(options.body).generationConfig.responseFormat.text.schema.properties.items ? { items: [landmark] } : { reply: 'The object is visible.', sourceIds: [] }), now: () => clock, ...extra });
  platform.server.listen(0, '127.0.0.1');
  await once(platform.server, 'listening');
  const base = `http://127.0.0.1:${platform.server.address().port}`;
  const api = async (path, token, body) => {
    const response = await fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, headers: response.headers, body: await response.json() };
  };
  t.after(async () => {
    await platform.close();
    const resolved = resolve(directory), parent = resolve(tmpdir());
    assert.ok(resolved.startsWith(parent + sep) && resolved.split(sep).at(-1).startsWith('sarab-vision-api-test-'));
    await rm(resolved, { recursive: true, force: true });
  });
  const login = await api('/api/v1/auth/login', null, { username: 'vision-test', password: 'Fixture-Only-Vision!92' });
  const admin = login.body.token;
  const group = (await api('/api/v1/groups', admin, { name: 'Vision API test group' })).body.group;
  const enrollment = (await api(`/api/v1/groups/${group.id}/enrollment`, admin)).body.code;
  const devices = [];
  for (let i = 0; i < 3; i++) devices.push((await api('/api/v1/devices/enroll', null, { code: enrollment, name: `Local vision fixture ${i}`, language: 'ar' })).body);
  return { ...platform, base, api, admin, devices, advance: ms => { clock += ms; } };
}

function rawPost(base, path, headers, body, timeout = 3000) {
  return new Promise((resolveResponse, rejectResponse) => {
    const req = httpRequest(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers } }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => { clearTimeout(timer); resolveResponse({ status: res.statusCode, headers: res.headers, body: JSON.parse(Buffer.concat(chunks).toString()) }); req.destroy(); });
    });
    const timer = setTimeout(() => { req.destroy(); rejectResponse(new Error('Timed out waiting for bounded request rejection')); }, timeout);
    req.on('error', error => { clearTimeout(timer); rejectResponse(error); });
    if (body === undefined) req.flushHeaders(); else req.end(body);
  });
}

test('vision requires a device bearer before reading a declared large body', async t => {
  let called = false;
  const f = await fixture(t, { visionFetchImpl: async () => { called = true; return gemini({ items: [] }); } });
  for (const path of ['/api/v1/vision/analyze', '/api/v1/vision/ask']) {
    assert.equal((await rawPost(f.base, path, { 'Content-Length': String(VISION_BODY_LIMIT + 1) })).status, 401);
    assert.equal((await rawPost(f.base, path, { 'Content-Length': String(VISION_BODY_LIMIT + 1), Authorization: `Bearer ${f.admin}` })).status, 403);
    assert.equal((await f.api(path, f.devices[0].token, {})).status, 400);
  }
  assert.equal(called, false);
});

test('only vision POST routes accept larger JSON; both declared and chunked overflow return 413', async t => {
  const f = await fixture(t);
  const authorization = { Authorization: `Bearer ${f.devices[0].token}` };
  const large = await rawPost(f.base, '/api/v1/vision/analyze', { ...authorization, 'Content-Length': String(VISION_BODY_LIMIT + 1) });
  assert.equal(large.status, 413);
  assert.equal(large.headers.connection, 'close');
  const chunked = await rawPost(f.base, '/api/v1/vision/ask', authorization, '{"question":"' + 'x'.repeat(VISION_BODY_LIMIT) + '"}');
  assert.equal(chunked.status, 413);
  assert.equal(chunked.body.code, 'BODY_TOO_LARGE');
  assert.equal((await rawPost(f.base, '/api/v1/assistant', { ...authorization, 'Content-Length': '65537' })).status, 413);
  const ok = await f.api('/api/v1/vision/analyze', f.devices[0].token, analyze);
  assert.equal(ok.status, 200);
  assert.equal(ok.headers.get('cache-control'), 'no-store');
  assert.ok(!JSON.stringify(ok.body).includes(analyze.image.data));
});

test('analyze and selected-item ask map correctly without persisting images, transcripts or device telemetry', async t => {
  const f = await fixture(t);
  const device = f.devices[0];
  const before = f.db.prepare('SELECT * FROM devices WHERE id=?').get(device.device.id);
  const tables = f.db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all();
  const analyzed = await f.api('/api/v1/vision/analyze', device.token, analyze);
  assert.equal(analyzed.status, 200);
  assert.equal(analyzed.body.items[0].kind, 'landmark');
  const item = analyzed.body.items[0];
  assert.equal(item.landmarkId, 'kaaba');
  assert.equal(item.devotionals[0].id, 'tawaf-dua-2-201');
  assert.ok(analyzed.body.sources.some(source => source.id === 'quran-2-201'));
  const answer = await f.api('/api/v1/vision/ask', device.token, { ...ask, selectedItem: { id: item.id, kind: item.kind, label: item.label, description: item.description, landmarkId: item.landmarkId }, history: [{ role: 'user', text: 'What about this selected object?' }] });
  assert.equal(answer.status, 200);
  assert.equal(answer.body.provider, 'gemini');
  assert.deepEqual(answer.body.sources, []);
  assert.deepEqual(f.db.prepare('SELECT * FROM devices WHERE id=?').get(device.device.id), before);
  assert.deepEqual(f.db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all(), tables);
  assert.equal(f.db.prepare('SELECT COUNT(*) n FROM alerts').get().n, 0);
});

test('vision rate limit is per operation/device, supplies Retry-After and recovers next window', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 30; i++) assert.equal((await f.api('/api/v1/vision/analyze', f.devices[0].token, analyze)).status, 200);
  const blocked = await f.api('/api/v1/vision/analyze', f.devices[0].token, analyze);
  assert.equal(blocked.status, 429);
  assert.equal(blocked.headers.get('retry-after'), '60');
  for (let i = 0; i < 30; i++) assert.equal((await f.api('/api/v1/vision/ask', f.devices[0].token, ask)).status, 200);
  assert.equal((await f.api('/api/v1/vision/ask', f.devices[0].token, ask)).status, 429);
  assert.equal((await f.api('/api/v1/vision/analyze', f.devices[1].token, analyze)).status, 200);
  f.advance(60001);
  assert.equal((await f.api('/api/v1/vision/analyze', f.devices[0].token, analyze)).status, 200);
});

test('one in-flight request per device is retained while three different devices can proceed', async t => {
  const releases = [];
  const starts = [];
  const f = await fixture(t, { visionFetchImpl: async () => {
    await new Promise(resolveProvider => { releases.push(resolveProvider); starts.shift()?.(); });
    return gemini({ items: [] });
  } });
  const launched = () => new Promise(resolveStart => starts.push(resolveStart));
  let ready = launched();
  const first = f.api('/api/v1/vision/analyze', f.devices[0].token, analyze); await ready;
  const sameDevice = await rawPost(f.base, '/api/v1/vision/ask', { Authorization: `Bearer ${f.devices[0].token}`, 'Content-Length': '100' });
  assert.equal(sameDevice.status, 429);
  assert.equal(sameDevice.body.code, 'VISION_BUSY');
  ready = launched();
  const second = f.api('/api/v1/vision/analyze', f.devices[1].token, analyze); await ready;
  ready = launched();
  const third = f.api('/api/v1/vision/analyze', f.devices[2].token, analyze); await ready;
  releases.splice(0).forEach(release => release());
  assert.equal((await first).status, 200);
  assert.equal((await second).status, 200);
  assert.equal((await third).status, 200);
  ready = launched();
  const recovered = f.api('/api/v1/vision/analyze', f.devices[2].token, analyze); await ready;
  releases.splice(0).forEach(release => release());
  assert.equal((await recovered).status, 200);
});

test('an incomplete image upload expires after50seconds before Gemini and releases its reserved slot', async t => {
  let calls = 0;
  const f = await fixture(t, { visionFetchImpl: async () => { calls++; return gemini({ items: [] }); } });
  const started = Date.now();
  const timedOut = await rawPost(f.base, '/api/v1/vision/analyze', { Authorization: `Bearer ${f.devices[0].token}`, 'Content-Length': '100' }, undefined, 54000);
  assert.equal(timedOut.status, 408);
  assert.equal(timedOut.body.code, 'VISION_UPLOAD_TIMEOUT');
  assert.equal(timedOut.body.retryable, true);
  assert.equal(timedOut.body.retryAfterSeconds, 2);
  assert.equal(timedOut.headers['retry-after'], '2');
  assert.ok(Date.now() - started >= 49500, 'upload gets its50-second window');
  assert.equal(calls, 0, 'provider must not run on an incomplete image');
  assert.equal(f.server.requestTimeout, 90000, 'incoming deadline allows queue8s plus upload50s and header margin');
  assert.equal(f.server.headersTimeout, 15000, 'header deadline remains bounded separately');
  assert.equal((await f.api('/api/v1/vision/analyze', f.devices[0].token, analyze)).status, 200);
  assert.equal(calls, 1);
});

test('thirty-two-second streamed images reach Gemini successfully for both analyze and ask', async t => {
  let calls = 0;
  const f = await fixture(t, { visionFetchImpl: async (_url, options) => {
    calls++;
    const analyzing = JSON.parse(options.body).generationConfig.responseFormat.text.schema.properties.items;
    return gemini(analyzing ? { items: [] } : { reply: 'The Kaaba is in the Grand Mosque.', sourceIds: ['haramain-kaaba'] });
  } });
  const stream = (path, token, value) => new Promise((resolveResponse, rejectResponse) => {
    const data = Buffer.from(JSON.stringify(value));
    const length = Math.ceil(data.length / 33);
    const started = Date.now();
    let sent = 0, sending;
    const req = httpRequest(f.base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Content-Length': data.length } }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        clearInterval(sending); clearTimeout(timer);
        resolveResponse({ status: res.statusCode, elapsedMs: Date.now() - started, body: JSON.parse(Buffer.concat(chunks).toString()) });
      });
    });
    const timer = setTimeout(() => { clearInterval(sending); req.destroy(); rejectResponse(new Error('Slow image regression timed out')); }, 40000);
    req.on('error', error => { clearInterval(sending); clearTimeout(timer); rejectResponse(error); });
    const send = () => {
      const next = Math.min(data.length, sent + length);
      req.write(data.subarray(sent, next));
      sent = next;
      if (sent === data.length) { clearInterval(sending); req.end(); }
    };
    send();
    sending = setInterval(send, 1000);
  });
  const results = await Promise.all([
    stream('/api/v1/vision/analyze', f.devices[0].token, analyze),
    stream('/api/v1/vision/ask', f.devices[1].token, ask),
  ]);
  for (const result of results) {
    assert.equal(result.status, 200);
    assert.equal(result.body.provider, 'gemini');
    assert.ok(result.elapsedMs >= 31900, 'the regression must actually cross the intermediate30-second limit');
  }
  assert.equal(calls, 2);
});
