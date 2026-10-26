import test from 'node:test';
import assert from 'node:assert/strict';
import { createVision } from '../src/vision.mjs';
import { VISION_COPY, VISION_LANGUAGES } from '../src/religious-landmarks.mjs';
import { analyze, ask, landmark, gemini } from './vision-fixture.mjs';

const privateText = 'private-provider-key-image-question-never-return';

test('the default provider deadline is50seconds and expiry remains a retryable timeout with one call', async t => {
  const realTimeout = AbortSignal.timeout.bind(AbortSignal);
  const deadlines = [];
  t.mock.method(AbortSignal, 'timeout', milliseconds => {
    deadlines.push(milliseconds);
    return realTimeout(20);
  });
  let calls = 0;
  const ai = createVision({ geminiApiKey: 'test-key', fetchImpl: async (_url, options) => {
    calls++;
    await new Promise((resolve, reject) => {
      const timer = setTimeout(resolve, 1000);
      options.signal.addEventListener('abort', () => { clearTimeout(timer); reject(options.signal.reason); }, { once: true });
    });
    return gemini({ items: [] });
  } });
  await assert.rejects(ai(analyze, 'analyze'), error => error.status === 504 && error.code === 'VISION_TIMEOUT' && error.retryable === true && error.retryAfterSeconds === 12);
  assert.deepEqual(deadlines, [50000]);
  assert.equal(calls, 1, 'deadline expiry must not trigger a hidden provider retry');
});

async function failure(response, expected, options = {}) {
  let calls = 0;
  const ai = createVision({ geminiApiKey: privateText, fetchImpl: async () => { calls++; return response; }, ...options });
  await assert.rejects(ai(analyze, 'analyze'), error => {
    assert.equal(error.code, expected.code);
    assert.equal(error.status, expected.status);
    assert.equal(error.retryable, expected.retryable);
    assert.equal(error.retryAfterSeconds, expected.delay);
    assert.ok(!JSON.stringify(error).includes(privateText));
    assert.ok(!error.message.includes(privateText));
    assert.equal(error.cause, undefined);
    return true;
  });
  assert.equal(calls, 1, 'no hidden generation retry');
}

test('provider HTTP failures have distinct safe codes without exposing upstream bodies or credentials', async () => {
  const cases = [
    [400, 'VISION_CONFIGURATION', 503, false],
    [401, 'VISION_AUTHENTICATION', 503, false],
    [403, 'VISION_AUTHENTICATION', 503, false],
    [404, 'VISION_MODEL_UNAVAILABLE', 503, false],
    [408, 'VISION_TIMEOUT', 504, true, 12],
    [429, 'VISION_RATE_LIMITED', 429, true, 60],
    [500, 'VISION_UNAVAILABLE', 503, true, 12],
    [503, 'VISION_UNAVAILABLE', 503, true, 12],
    [504, 'VISION_TIMEOUT', 504, true, 12],
  ];
  for (const [http, code, status, retryable, delay] of cases) {
    const response = new Response(JSON.stringify({ error: { message: privateText } }), { status: http });
    await failure(response, { code, status, retryable, delay });
    assert.ok(response.bodyUsed, 'upstream error body is cancelled');
  }
});

test('retry delays use only bounded safe numeric/date headers, not provider body instructions', async () => {
  for (const [header, delay] of [['17', 17], ['1.1', 2], ['0', 1], ['999999', 300], ['invalid ' + privateText, 60], ['x'.repeat(129), 60], ['Thu, 01 Jan 1970 00:00:00 GMT', 1]]) {
    await failure(new Response(privateText, { status: 429, headers: { 'Retry-After': header } }), { code: 'VISION_RATE_LIMITED', status: 429, retryable: true, delay });
  }
  await failure(new Response(privateText, { status: 503, headers: { 'Retry-After': '28' } }), { code: 'VISION_UNAVAILABLE', status: 503, retryable: true, delay: 28 });
});

test('provider safety blocks are distinct from malformed, incomplete or unsafe output', async () => {
  for (const response of [
    gemini({ items: [landmark] }, 'SAFETY'), gemini({ items: [landmark] }, 'RECITATION'),
    new Response(JSON.stringify({ promptFeedback: { blockReason: 'SAFETY', privateText } })),
  ]) await failure(response, { code: 'VISION_BLOCKED', status: 422, retryable: false });
  for (const response of [
    gemini({ items: [landmark] }, 'MAX_TOKENS'), gemini({ items: [landmark] }, 'UNKNOWN_REASON'),
    new Response(JSON.stringify({ candidates: [] })), new Response('not json'), new Response('x'.repeat(65537)),
    new Response(JSON.stringify({ candidates: [{ finishReason: 'STOP', content: { parts: [{ functionCall: { name: 'sendAlert' } }] } }] })),
    gemini({ items: [{ ...landmark, box: [0, 0, 30, 5001] }] }),
    gemini({ items: [landmark], sourceUrl: 'https://evil.test' }),
  ]) await failure(response, { code: 'VISION_INVALID_RESPONSE', status: 502, retryable: true, delay: 12 });
});

test('missing configuration fails before fetching; network failure has no raw exception cause', async () => {
  for (const options of [{ geminiApiKey: '' }, { geminiApiKey: 'key', geminiModel: '../escape?key=bad' }]) {
    let calls = 0;
    const ai = createVision({ ...options, fetchImpl: async () => { calls++; } });
    await assert.rejects(ai(analyze, 'analyze'), error => error.code === 'VISION_CONFIGURATION' && error.retryable === false);
    assert.equal(calls, 0);
  }
  let calls = 0;
  const ai = createVision({ geminiApiKey: 'key', fetchImpl: async () => { calls++; throw new TypeError(privateText); } });
  await assert.rejects(ai(analyze, 'analyze'), error => error.code === 'VISION_UNAVAILABLE' && error.retryable === true && error.cause === undefined && !error.message.includes(privateText));
  assert.equal(calls, 1);
});

test('clear depicted landmarks are allowed for camera and upload; no physical-presence or prompt-injection shortcut', async () => {
  for (const language of VISION_LANGUAGES) {
    let prompt;
    const ai = createVision({ geminiApiKey: 'test-key', fetchImpl: async (_url, options) => {
      const payload = JSON.parse(options.body);
      prompt = payload.systemInstruction.parts[0].text;
      return gemini({ items: [landmark] });
    } });
    const result = await ai({ ...analyze, language }, 'analyze');
    assert.equal(result.items[0].landmarkId, 'kaaba');
    assert.deepEqual(result.items[0].sourceIds, ['haramain-kaaba']);
    assert.ok(prompt.includes('A clear landmark depicted in a photo or screen is eligible'));
    assert.ok(prompt.includes('Never infer that the user is physically present'));
    assert.ok(prompt.includes('UNTRUSTED DATA'));
    assert.ok(prompt.includes('doubtful, blurred or obscured'));
    assert.ok(!prompt.includes('or a photograph/screen depicting a place'));
    assert.ok(!prompt.includes('drawing or photograph as the physical landmark'));
    assert.ok(!/glasses|النظارة|چشمہ|kacamata|Gözlüğü/u.test(VISION_COPY[language].uncertain));
  }
});

test('a conversational sourced answer is not wrapped in a repetitive snapshot disclaimer', async () => {
  const reply = 'The Kaaba is in the Grand Mosque.';
  const ai = createVision({ geminiApiKey: 'test-key', fetchImpl: async () => gemini({ reply, sourceIds: ['haramain-kaaba'] }) });
  const result = await ai(ask, 'ask');
  assert.equal(result.reply, reply);
  assert.equal(result.sources[0].id, 'haramain-kaaba');
});
