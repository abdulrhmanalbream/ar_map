import type { Device, Filter } from "./types";

export const OFFLINE_MS = 90_000;
export const LOCATION_STALE_MS = 60_000;
export const number = (value: number) =>
  new Intl.NumberFormat("ar-SA").format(value);
export function ageMs(
  timestamp: string | null | undefined,
  now = Date.now(),
): number {
  if (!timestamp) return Infinity;
  const time = Date.parse(timestamp);
  return Number.isFinite(time) ? Math.max(0, now - time) : Infinity;
}
export function isOffline(device: Device, now = Date.now()) {
  return (
    device.status === "offline" || ageMs(device.lastSeenAt, now) > OFFLINE_MS
  );
}
export function sharedLocation(device: Device) {
  const p = device.location;
  return device.sharingEnabled &&
    p &&
    Number.isFinite(p.lat) &&
    Math.abs(p.lat) <= 90 &&
    Number.isFinite(p.lng) &&
    Math.abs(p.lng) <= 180
    ? p
    : null;
}
export function matchesDevice(
  device: Device,
  filter: Filter,
  group: string,
  search: string,
  now = Date.now(),
) {
  if (group && device.groupId !== group) return false;
  if (
    search &&
    !`${device.name} ${device.groupName}`
      .toLocaleLowerCase()
      .includes(search.trim().toLocaleLowerCase())
  )
    return false;
  if (filter === "help") return needsHelp(device);
  if (filter === "offline") return isOffline(device, now);
  if (filter === "active")
    return !isOffline(device, now) && device.status === "active";
  return true;
}
export function relativeTime(
  timestamp: string | null | undefined,
  now = Date.now(),
) {
  const age = ageMs(timestamp, now);
  if (!Number.isFinite(age)) return "لم يصل تحديث بعد";
  if (age < 15_000) return "الآن";
  if (age < 60_000) return `قبل ${number(Math.floor(age / 1000))} ثانية`;
  if (age < 3600_000) return `قبل ${number(Math.floor(age / 60_000))} دقيقة`;
  if (age < 86_400_000) return `قبل ${number(Math.floor(age / 3600_000))} ساعة`;
  return `قبل ${number(Math.floor(age / 86_400_000))} يوم`;
}
export function dateTime(timestamp: string) {
  const date = new Date(timestamp);
  return Number.isNaN(date.valueOf())
    ? "وقت غير متاح"
    : new Intl.DateTimeFormat("ar-SA", {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(date);
}
export function initials(name: string) {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((x) => x[0])
    .join("");
}
export function statusLabel(device: Device, now = Date.now()) {
  if (needsHelp(device)) return "يطلب المساعدة";
  if (isOffline(device, now)) return "غير متصل";
  return device.status === "paused" ? "متوقف مؤقتًا" : "متصل";
}
export function needsHelp(device: Device) {
  return (
    device.status === "needs_help" || device.reportedStatus === "needs_help"
  );
}
