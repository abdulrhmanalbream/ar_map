import { ApiError } from './validation.mjs';

function busy() {
  const error = new ApiError(429, 'VISION_BUSY', 'Your request is waiting for capacity. Please try again shortly.');
  error.retryable = true;
  error.retryAfterSeconds = 2;
  return error;
}

/** Bounded admission before image upload. Questions go before background analysis. */
export class VisionAdmission {
  constructor({ concurrency = 4, maxWaiting = 10, waitMs = 8000 } = {}) {
    this.concurrency = concurrency;
    this.maxWaiting = maxWaiting;
    this.waitMs = waitMs;
    this.active = 0;
    this.actors = new Set();
    this.waiting = [];
  }
  acquire(actor, question = false, signal) {
    if (signal?.aborted || this.actors.has(actor) || (this.active >= this.concurrency && this.waiting.length >= this.maxWaiting))
      return Promise.reject(busy());
    this.actors.add(actor);
    if (this.active < this.concurrency) return Promise.resolve(this.start(actor));
    return new Promise((resolve, reject) => {
      const entry = { actor, question, resolve, reject, timer: null, cleanup: null };
      const cancel = () => {
        const index = this.waiting.indexOf(entry);
        if (index < 0) return;
        this.waiting.splice(index, 1);
        this.actors.delete(actor);
        entry.cleanup();
        reject(busy());
      };
      entry.cleanup = () => { clearTimeout(entry.timer); signal?.removeEventListener('abort', cancel); };
      entry.timer = setTimeout(cancel, this.waitMs);
      signal?.addEventListener('abort', cancel, { once: true });
      this.waiting.push(entry);
    });
  }
  start(actor) {
    this.active++;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.active--;
      this.actors.delete(actor);
      const question = this.waiting.findIndex(entry => entry.question);
      const next = this.waiting.splice(question < 0 ? 0 : question, 1)[0];
      if (next) { next.cleanup(); next.resolve(this.start(next.actor)); }
    };
  }
}
