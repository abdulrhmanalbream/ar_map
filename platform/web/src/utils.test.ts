import { describe, expect, it } from 'vitest';
import { ageMs, isOffline, matchesDevice, sharedLocation } from './utils';
import type { Device } from './types';

const now = Date.parse('2026-09-08T12:00:00Z');
const device: Device = { id: 'one', name: 'أحمد', groupId: 'g1', groupName: 'المجموعة الأولى', language: 'ar', status: 'active', lastSeenAt: '2026-09-08T11:59:30Z', location: { lat: 21.4, lng: 39.8, accuracyM: 12, recordedAt: '2026-09-08T11:59:30Z' }, sharingEnabled: true, batteryPercent: 80, cameraConnected: true, imuTracking: true, watchConnected: false, destinationName: null, lap: null };

describe('consent and freshness', () => {
  it('never exposes a retained location when consent is disabled', () => {
    expect(sharedLocation({ ...device, sharingEnabled: false })).toBeNull();
    expect(sharedLocation(device)).toEqual(device.location);
  });
  it('rejects invalid or out-of-range coordinates before map rendering', () => {
    expect(sharedLocation({ ...device, location: { ...device.location!, lat: NaN } })).toBeNull();
    expect(sharedLocation({ ...device, location: { ...device.location!, lng: 181 } })).toBeNull();
    expect(sharedLocation({ ...device, location: { ...device.location!, lat: 0, lng: 0 } })).not.toBeNull();
  });
  it('treats absent timestamps and old devices as offline', () => {
    expect(isOffline(device, now)).toBe(false);
    expect(isOffline({ ...device, lastSeenAt: null }, now)).toBe(true);
    expect(isOffline(device, now + 90_000)).toBe(true);
    expect(ageMs('invalid', now)).toBe(Infinity);
  });
});
describe('operator filters', () => {
  it('keeps requests for help discoverable even from an offline device', () => {
    expect(matchesDevice({ ...device, status: 'needs_help', lastSeenAt: null }, 'help', '', '', now)).toBe(true);
    expect(matchesDevice({ ...device, status: 'needs_help', lastSeenAt: null }, 'active', '', '', now)).toBe(false);
  });
  it('combines status, group and member search without leaking other groups', () => {
    expect(matchesDevice(device, 'active', 'g1', 'أحمد', now)).toBe(true);
    expect(matchesDevice(device, 'all', 'g2', '', now)).toBe(false);
    expect(matchesDevice(device, 'all', '', 'الأولى', now)).toBe(true);
    expect(matchesDevice(device, 'offline', '', '', now)).toBe(false);
  });
});
