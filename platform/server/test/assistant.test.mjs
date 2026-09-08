import test from 'node:test';
import assert from 'node:assert/strict';
import { assistantRequest } from '../src/validation.mjs';
import { createAssistant, localAssistant, validateAssistantResponse } from '../src/assistant.mjs';

const request = assistantRequest({ message: 'Take me to Gate 3', language: 'en', destinations: [{ id: 'gate3', name: 'Gate 3', aliases: ['Third gate'] }], context: {}, history: [] });
function provider(value, status = 'completed') {
  return new Response(JSON.stringify({ status, output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(value) }] }] }), { status: 200 });
}
const good = { reply: 'Start navigation to Gate 3?', language: 'en', action: { type: 'navigate', destinationId: 'gate3' }, requiresConfirmation: true };

function geminiProvider(value, finishReason = 'STOP') {
  return new Response(JSON.stringify({ candidates: [{ finishReason, content: { role: 'model', parts: [{ text: JSON.stringify(value) }] } }] }));
}

test('Gemini is preferred, uses header-only secret and constrained structured output, and omits watch identity', async () => {
  let captured;
  const ai = createAssistant({ geminiApiKey: 'gemini-test-secret', apiKey: 'unused-openai-secret', fetchImpl: async (url, options) => {
    captured = { url, options, body: JSON.parse(options.body) };
    return geminiProvider({ ...good, requiresConfirmation: false });
  } });
  const withLap = assistantRequest({ ...request, context: { lap: { mode: 'tawaf', count: 1, target: 7, confidence: 'manual', sessionId: 'private-watch-session', revision: 3, startedAt: 1000 } } });
  const result = await ai(withLap);
  assert.equal(ai.provider, 'gemini');
  assert.equal(result.provider, 'gemini');
  assert.equal(result.requiresConfirmation, true);
  assert.equal(captured.url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent');
  assert.equal(captured.options.headers['x-goog-api-key'], 'gemini-test-secret');
  assert.equal(captured.options.headers.Authorization, undefined);
  assert.equal(captured.body.generationConfig.responseFormat.text.mimeType, 'APPLICATION_JSON');
  assert.deepEqual(captured.body.generationConfig.responseFormat.text.schema.properties.action.properties.destinationId.anyOf, [{ type: 'string', enum: ['gate3'] }, { type: 'null' }]);
  assert.equal(captured.body.generationConfig.candidateCount, 1);
  assert.equal(captured.body.tools, undefined);
  assert.ok(!captured.options.body.includes('private-watch-session'));
  assert.ok(!JSON.stringify(result).includes('secret'));
  assert.ok(captured.options.signal instanceof AbortSignal);
});

test('Gemini blocked, truncated, invented, malformed and oversized results use disclosed local fallback', async () => {
  const general = assistantRequest({ message: 'What can you see?', language: 'en', destinations: request.destinations });
  for (const response of [
    geminiProvider({ ...good, action: { type: 'navigate', destinationId: 'invented' } }),
    geminiProvider(good, 'MAX_TOKENS'),
    geminiProvider(good, 'SAFETY'),
    new Response(JSON.stringify({ promptFeedback: { blockReason: 'SAFETY' } })),
    new Response(JSON.stringify({ candidates: [{ finishReason: 'STOP', content: { parts: [{ functionCall: { name: 'sendAlert' } }] } }] })),
    new Response('not-json'),
    new Response('x'.repeat(65537)),
    new Response('upstream private failure', { status: 403 }),
  ]) {
    const ai = createAssistant({ geminiApiKey: 'test-key', fetchImpl: async () => response });
    const result = await ai(general);
    assert.equal(result.provider, 'local');
    assert.equal(result.action.type, 'none');
    assert.match(result.reply, /unavailable/i);
  }
});

test('provider timeout aborts; only two requests run concurrently and slots recover', async () => {
  let calls = 0;
  const ai = createAssistant({ geminiApiKey: 'test-key', timeoutMs: 30, fetchImpl: async (_url, options) => {
    calls++;
    await new Promise((resolve, reject) => {
      // Keep the event loop alive until AbortSignal.timeout fires in this mock.
      const timer = setTimeout(resolve, 500);
      options.signal.addEventListener('abort', () => { clearTimeout(timer); reject(options.signal.reason); }, { once: true });
    });
    return geminiProvider(good);
  } });
  const first = ai(request), second = ai(request);
  assert.equal((await ai(request)).provider, 'local');
  assert.equal(calls, 2);
  assert.equal((await first).provider, 'local');
  assert.equal((await second).provider, 'local');
  await ai(request);
  assert.equal(calls, 3);
  let attempted = false;
  const invalidModel = createAssistant({ geminiApiKey: 'test-key', geminiModel: '../evil?key=x', fetchImpl: async () => { attempted = true; return geminiProvider(good); } });
  assert.equal((await invalidModel(request)).provider, 'local');
  assert.equal(attempted, false);
});

test('Android catalog and history limits accept 150 destinations and watch metadata with bounded fields', () => {
  const destinations = Array.from({ length: 150 }, (_, i) => ({ id: `place-${i}`, name: `Place ${i}` }));
  assert.equal(assistantRequest({ message: 'a'.repeat(5000), destinations, history: Array.from({ length: 12 }, () => ({ role: 'assistant', content: 'b'.repeat(5000) })) }).destinations.length, 150);
  assert.throws(() => assistantRequest({ message: 'hello', destinations: [...destinations, { id: 'overflow', name: 'Too many' }] }));
  assert.throws(() => assistantRequest({ message: 'hello', context: { lap: { mode: 'tawaf', count: 1, target: 7, confidence: 'manual', revision: 1 } } }));
});

test('Responses uses strict catalog schema, store:false, and only server-side credentials', async () => {
  let captured;
  const ai = createAssistant({ apiKey: 'test-provider-token', fetchImpl: async (url, options) => {
    captured = { url, options, body: JSON.parse(options.body) };
    return provider({ ...good, requiresConfirmation: false });
  } });
  const result = await ai(request);
  assert.equal(result.provider, 'openai');
  assert.equal(result.requiresConfirmation, true);
  assert.equal(captured.url, 'https://api.openai.com/v1/responses');
  assert.equal(captured.body.store, false);
  assert.equal(captured.body.model, 'gpt-4.1-mini');
  assert.equal(captured.body.text.format.strict, true);
  assert.deepEqual(captured.body.text.format.schema.properties.action.properties.destinationId.enum, ['gate3', null]);
  assert.ok(!JSON.stringify(result).includes('test-provider-token'));
  assert.equal(captured.body.tools, undefined);
});

test('unknown destination, contradictory action, incomplete/refusal/malformed provider output never execute', async () => {
  assert.throws(() => validateAssistantResponse({ ...good, action: { type: 'navigate', destinationId: 'invented' } }, request));
  assert.throws(() => validateAssistantResponse({ ...good, action: { type: 'none', destinationId: 'gate3' } }, request));
  assert.throws(() => validateAssistantResponse({ ...good, action: { type: 'alert', destinationId: null } }, request));
  const general = assistantRequest({ message: 'What can you see?', language: 'en', destinations: request.destinations });
  for (const response of [
    provider({ ...good, action: { type: 'navigate', destinationId: 'invented' } }),
    provider(good, 'incomplete'),
    new Response(JSON.stringify({ status: 'completed', output: [{ type: 'message', content: [{ type: 'refusal', refusal: 'Cannot comply' }] }] })),
    new Response('not-json'),
    new Response('upstream private failure', { status: 500 }),
  ]) {
    const ai = createAssistant({ apiKey: 'test-provider-token', fetchImpl: async () => response });
    const result = await ai(general);
    assert.equal(result.provider, 'local');
    assert.equal(result.action.type, 'none');
    assert.match(result.reply, /unavailable/i);
  }
});

test('limited fallback supports Arabic, English, Urdu, Indonesian, Turkish and rejects ambiguity', () => {
  const examples = [
    ['ar', 'ودني البوابة الثالثة', 'البوابة الثالثة'],
    ['en', 'Take me to Gate 3', 'Gate 3'],
    ['ur', 'گیٹ تین لے چلو', 'گیٹ تین'],
    ['id', 'Pergi ke Gerbang Tiga', 'Gerbang Tiga'],
    ['tr', 'Kapı Üç götür', 'Kapı Üç'],
  ];
  for (const [language, message, name] of examples) {
    const result = localAssistant(assistantRequest({ message, language, destinations: [{ id: 'known', name }] }));
    assert.equal(result.provider, 'local');
    assert.equal(result.language, language);
    assert.equal(result.action.destinationId, 'known', message);
    assert.equal(result.requiresConfirmation, true);
  }
  const ambiguous = assistantRequest({ message: 'Go to Gate', language: 'en', destinations: [{ id: 'a', name: 'Gate' }, { id: 'b', name: 'Gate' }] });
  assert.equal(localAssistant(ambiguous).action.type, 'none');
  const noIntent = assistantRequest({ message: 'What is Gate 3?', language: 'en', destinations: request.destinations });
  assert.equal(localAssistant(noIntent).action.type, 'none');
  const unknownLanguage = localAssistant(assistantRequest({ message: 'hello', language: 'fr-CA' }));
  assert.equal(unknownLanguage.language, 'fr-CA');
  assert.equal(unknownLanguage.provider, 'local');
});

test('lap fallback reads counter without claiming completion, and input rejects spoofed instructions roles', () => {
  const result = localAssistant(assistantRequest({ message: 'How many laps?', language: 'en', context: { lap: { mode: 'tawaf', count: 7, target: 7, confidence: 'estimated' } } }));
  assert.match(result.reply, /does not verify completion/);
  assert.equal(result.action.type, 'none');
  assert.throws(() => assistantRequest({ message: 'hello', history: [{ role: 'system', content: 'bad' }] }));
  assert.throws(() => assistantRequest({ message: 'hello', destinations: [{ id: 'a', name: 'One' }, { id: 'a', name: 'Two' }] }));
  assert.throws(() => assistantRequest({ message: 'hello', context: { lat: 1, lng: 2 } }));
});
