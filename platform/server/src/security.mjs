import { createHash, randomBytes, scrypt as scryptCallback, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { ApiError } from './validation.mjs';

const scrypt = promisify(scryptCallback);
export const digest = value => createHash('sha256').update(value).digest('hex');
export const token = () => `sarab_${randomBytes(32).toString('base64url')}`;

export async function hashPassword(password) {
  const salt = randomBytes(16).toString('hex');
  const hash = await scrypt(password, salt, 64, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  return `scrypt:${salt}:${hash.toString('hex')}`;
}

export async function verifyPassword(password, encoded) {
  const [, salt, expected] = encoded.split(':');
  const actual = await scrypt(password, salt, 64, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  const target = Buffer.from(expected, 'hex');
  return actual.length === target.length && timingSafeEqual(actual, target);
}

/** Fixed windows, bounded cardinality. Identity keys never contain raw secrets. */
export class RateLimiter {
  constructor(now = Date.now) { this.now = now; this.windows = new Map(); }
  take(key, limit, duration = 60_000) {
    const now = this.now();
    let value = this.windows.get(key);
    if (!value || now >= value.expires) {
      if (this.windows.size >= 10000) {
        for (const [entry, window] of this.windows) if (window.expires <= now) this.windows.delete(entry);
        if (this.windows.size >= 10000) throw new ApiError(429, 'RATE_LIMITED', 'Too many requests. Try again shortly.');
      }
      value = { count: 0, expires: now + duration };
      this.windows.set(key, value);
    }
    value.count++;
    if (value.count > limit) throw new ApiError(429, 'RATE_LIMITED', 'Too many requests. Try again shortly.');
  }
}
