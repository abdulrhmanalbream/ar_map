import test from 'node:test';
import assert from 'node:assert/strict';
import { VisionAdmission } from '../src/vision-admission.mjs';

test('questions overtake queued analysis without interrupting the active request', async () => {
  const queue = new VisionAdmission({ concurrency: 1 });
  const first = await queue.acquire('a');
  let analysisStarted = false;
  const analysis = queue.acquire('b').then(release => { analysisStarted = true; return release; });
  const question = queue.acquire('c', true);
  first();
  const finishQuestion = await question;
  assert.equal(analysisStarted, false);
  finishQuestion();
  (await analysis)();
  assert.equal(queue.active, 0);
  assert.equal(queue.actors.size, 0);
});

test('duplicate users and full queues reject with a short retry, cancellation frees waiting capacity', async () => {
  const queue = new VisionAdmission({ concurrency: 1, maxWaiting: 1 });
  const first = await queue.acquire('a');
  await assert.rejects(queue.acquire('a'), error => error.code === 'VISION_BUSY' && error.retryAfterSeconds === 2);
  const controller = new AbortController();
  const waiting = queue.acquire('b', false, controller.signal);
  await assert.rejects(queue.acquire('c'), error => error.code === 'VISION_BUSY');
  controller.abort();
  await assert.rejects(waiting, error => error.code === 'VISION_BUSY');
  const replacement = queue.acquire('b');
  first(); first();
  (await replacement)();
  assert.equal(queue.active, 0);
  assert.equal(queue.actors.size, 0);
});

test('bounded waiting expires without leaking an actor reservation', async () => {
  const queue = new VisionAdmission({ concurrency: 1, waitMs: 15 });
  const finish = await queue.acquire('a');
  await assert.rejects(queue.acquire('b'), error => error.retryable === true);
  assert.equal(queue.actors.has('b'), false);
  finish();
  (await queue.acquire('b'))();
});
