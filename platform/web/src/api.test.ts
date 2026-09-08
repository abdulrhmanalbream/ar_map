import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, tokenStore } from './api';

beforeEach(() => {
  const storage = new Map<string, string>();
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => storage.get(key) ?? null, setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) });
});
afterEach(() => vi.unstubAllGlobals());

describe('authenticated API', () => {
  it('uses same-origin paths, bearer token and no ambient cookies', async () => {
    tokenStore.set('test-session');
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"groups":[]}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await api('/groups');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/groups');
    expect(fetchMock.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer test-session');
    expect(fetchMock.mock.calls[0][1].credentials).toBe('omit');
    expect(fetchMock.mock.calls[0][1].cache).toBe('no-store');
  });
  it('never sends an existing bearer token with login', async () => {
    tokenStore.set('old-session');
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"token":"new"}', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await api('/auth/login', { method: 'POST', body: '{}' }, null);
    expect(fetchMock.mock.calls[0][1].headers.has('Authorization')).toBe(false);
  });
  it('preserves unauthorized status so the UI can clear all protected data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 401 })));
    await expect(api('/devices')).rejects.toMatchObject({ status: 401 });
  });
  it('does not expose an untrusted server stack trace in the interface', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"error":"private server stack trace"}', { status: 500 })));
    await expect(api('/groups')).rejects.toThrow('تعذّر إتمام الطلب');
  });
  it('accepts a successful empty logout response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(api('/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
    tokenStore.set('value'); tokenStore.clear(); expect(tokenStore.get()).toBeNull();
  });
});
