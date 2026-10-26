import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { createPlatform } from '../src/app.mjs';
import { analyze, gemini } from './vision-fixture.mjs';

const path = '/api/v1/vision/speak';
const request = { text: 'هذا مقام إبراهيم.', language: 'ar' };
const pcm = Buffer.from([0x12, 0x34, 0xab, 0xcd, 0, 0, 0xff, 0x7f]);
const audio = () => new Response(JSON.stringify({ candidates: [{ finishReason: 'STOP', content: { parts: [{ inlineData: { mimeType: 'audio/l16;rate=24000;channels=1', data: pcm.toString('base64') } }] } }] }));

async function fixture(t, extra = {}, memberCount = 3) {
  const directory = await mkdtemp(join(tmpdir(), 'sarab-speech-api-test-'));
  let clock = Date.parse('2026-09-09T12:00:00Z');
  const platform = await createPlatform({ dataDir: directory, adminUsername: 'speech-test', adminPassword: 'Fixture-Only-Speech!92', geminiApiKey: 'mock-key', speechFetchImpl: async () => audio(), visionFetchImpl: async () => gemini({ items: [] }), now: () => clock, ...extra });
  platform.server.listen(0, '127.0.0.1');
  await once(platform.server, 'listening');
  const base = `http://127.0.0.1:${platform.server.address().port}`;
  const api = async (route, token, body) => {
    const response = await fetch(base + route, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const bytes = Buffer.from(await response.arrayBuffer());
    return { status: response.status, headers: response.headers, bytes, body: response.headers.get('content-type')?.startsWith('application/json') ? JSON.parse(bytes.toString()) : null };
  };
  t.after(async () => {
    await platform.close();
    const target = resolve(directory);
    assert.ok(target.startsWith(resolve(tmpdir()) + sep) && target.split(sep).at(-1).startsWith('sarab-speech-api-test-'));
    await rm(target, { recursive: true, force: true });
  });
  const admin = (await api('/api/v1/auth/login', null, { username: 'speech-test', password: 'Fixture-Only-Speech!92' })).body.token;
  const group = (await api('/api/v1/groups', admin, { name: 'Speech API test group' })).body.group;
  const code = (await api(`/api/v1/groups/${group.id}/enrollment`, admin)).body.code;
  const devices = [];
  for (let i = 0; i < memberCount; i++) devices.push((await api('/api/v1/devices/enroll', null, { code, name: `Local speech fixture ${i}`, language: 'ar' })).body);
  return { ...platform, base, api, admin, devices, advance: ms => { clock += ms; } };
}

function rawPost(base, headers, body) {
  return new Promise((resolveResponse, rejectResponse) => {
    const req = httpRequest(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers } }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => { clearTimeout(timer); resolveResponse({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString()) }); req.destroy(); });
    });
    const timer = setTimeout(() => { req.destroy(); rejectResponse(new Error('Speech route did not reject the bounded request')); }, 3000);
    req.on('error', error => { clearTimeout(timer); rejectResponse(error); });
    if (body === undefined) req.flushHeaders(); else req.end(body);
  });
}

test('speech authenticates a device before declared body checks and rejects malformed64KiB requests without provider calls', async t => {
  let calls = 0;
  const f = await fixture(t, { speechFetchImpl: async () => { calls++; return audio(); } });
  assert.equal((await rawPost(f.base, { 'Content-Length': '65537' })).status, 401);
  assert.equal((await rawPost(f.base, { 'Content-Length': '65537', Authorization: `Bearer ${f.admin}` })).status, 403);
  const auth = { Authorization: `Bearer ${f.devices[0].token}` };
  assert.equal((await rawPost(f.base, { ...auth, 'Content-Length': '65537' })).status, 413);
  assert.equal((await rawPost(f.base, auth, 'x'.repeat(65537))).status, 413);
  assert.equal((await rawPost(f.base, { ...auth, 'Content-Type': 'text/plain' }, 'hello')).status, 415);
  for (const body of [{}, { ...request, text: 'x'.repeat(1801) }, { ...request, language: 'ar-SA' }, { ...request, voice: 'injected' }, { ...request, providerUrl: 'https://evil.test' }]) assert.equal((await f.api(path, f.devices[0].token, body)).status, 400);
  assert.equal(calls, 0);
  assert.equal((await f.api(path, f.devices[0].token, request)).status, 200, 'rejected requests release their speech reservation');
});

test('speech returns exact raw16LE PCM headers without persisting transcript/audio or changing member telemetry', async t => {
  const f = await fixture(t);
  const device = f.devices[0];
  const before = f.db.prepare('SELECT * FROM devices WHERE id=?').get(device.device.id);
  const tables = f.db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all();
  const result = await f.api(path, device.token, request);
  assert.equal(result.status, 200);
  assert.equal(result.headers.get('content-type'), 'audio/pcm');
  assert.equal(result.headers.get('x-audio-format'), 'pcm_s16le');
  assert.equal(result.headers.get('x-sample-rate'), '24000');
  assert.equal(result.headers.get('content-length'), String(pcm.length));
  assert.equal(result.headers.get('cache-control'), 'no-store');
  assert.deepEqual(result.bytes, pcm);
  assert.deepEqual(f.db.prepare('SELECT * FROM devices WHERE id=?').get(device.device.id), before);
  assert.deepEqual(f.db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all(), tables);
  assert.equal(f.db.prepare('SELECT COUNT(*) n FROM alerts').get().n, 0);
});

test('speech has a separate two-active/six-waiting queue and duplicate devices cannot overlap', async t => {
  const releases = [], starts = [];
  let calls = 0;
  const f = await fixture(t, { speechFetchImpl: async () => {
    calls++;
    if (calls <= 2) await new Promise(resolveProvider => { releases.push(resolveProvider); starts.shift()?.(); });
    return audio();
  } }, 9);
  const started = () => new Promise(resolveStart => starts.push(resolveStart));
  const work = [];
  try {
    let ready = started(); work.push(f.api(path, f.devices[0].token, request)); await ready;
    ready = started(); work.push(f.api(path, f.devices[1].token, request)); await ready;
    const duplicate = await f.api(path, f.devices[0].token, request);
    assert.equal(duplicate.status, 429);
    assert.equal(duplicate.body.code, 'VISION_BUSY');
    assert.equal(duplicate.body.retryable, true);
    assert.equal(duplicate.headers.get('retry-after'), '2');
    assert.equal((await f.api('/api/v1/vision/analyze', f.devices[0].token, analyze)).status, 200, 'vision admission is independent');
    const queued = f.devices.slice(2).map(device => f.api(path, device.token, request));
    work.push(...queued);
    const firstRejection = await Promise.race(queued);
    assert.equal(firstRejection.status, 429);
    assert.equal(calls, 2, 'only two speech calls started');
    releases.splice(0).forEach(release => release());
    const results = await Promise.all(work);
    assert.equal(results.filter(result => result.status === 200).length, 8);
    assert.equal(results.filter(result => result.status === 429).length, 1);
    assert.equal(calls, 8);
  } finally { releases.splice(0).forEach(release => release()); await Promise.allSettled(work); }
});

test('speech has independent24/device120/global minute limits and recovers next window', async t => {
  const f = await fixture(t, {}, 6);
  for (let i = 0; i < 24; i++) assert.equal((await f.api(path, f.devices[0].token, request)).status, 200);
  const blocked = await f.api(path, f.devices[0].token, request);
  assert.equal(blocked.status, 429);
  assert.equal(blocked.body.code, 'RATE_LIMITED');
  assert.equal(blocked.headers.get('retry-after'), '60');
  assert.equal((await f.api('/api/v1/vision/analyze', f.devices[0].token, analyze)).status, 200);
  for (const device of f.devices.slice(1, 5)) for (let i = 0; i < 24; i++) assert.equal((await f.api(path, device.token, request)).status, 200);
  assert.equal((await f.api(path, f.devices[5].token, request)).status, 429);
  f.advance(60001);
  assert.equal((await f.api(path, f.devices[0].token, request)).status, 200);
  assert.equal((await f.api(path, f.devices[5].token, request)).status, 200);
});

test('speech provider failures serialize safe retry metadata and release admission for recovery', async t => {
  let status = 429;
  const f = await fixture(t, { speechFetchImpl: async () => status === 200 ? audio() : new Response('private upstream credential or transcript', { status, headers: { 'Retry-After': '7' } }) });
  let result = await f.api(path, f.devices[0].token, request);
  assert.equal(result.status, 429);
  assert.equal(result.body.code, 'PROVIDER_RATE_LIMITED');
  assert.equal(result.body.retryable, true);
  assert.equal(result.body.retryAfterSeconds, 7);
  assert.equal(result.headers.get('retry-after'), '7');
  status = 403;
  result = await f.api(path, f.devices[0].token, request);
  assert.equal(result.status, 503);
  assert.equal(result.body.code, 'SPEECH_CONFIGURATION');
  assert.equal(result.body.retryable, false);
  assert.equal(result.body.retryAfterSeconds, undefined);
  assert.ok(!JSON.stringify(result.body).includes('private'));
  status = 200;
  assert.deepEqual((await f.api(path, f.devices[0].token, request)).bytes, pcm);
});
