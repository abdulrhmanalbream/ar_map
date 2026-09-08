export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function fail(message, code = 'INVALID_REQUEST', status = 400) {
  throw new ApiError(status, code, message);
}

export function object(value, keys, label = 'request') {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`${label} must be an object`);
  if (Object.keys(value).some(key => !keys.includes(key))) fail(`${label} contains unsupported fields`);
  return value;
}

export function string(value, label, min = 1, max = 200) {
  if (typeof value !== 'string' || value.trim().length < min || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value)) {
    fail(`${label} must be text of ${min}–${max} characters`);
  }
  return value.trim();
}

export function nullableString(value, label, max = 200) {
  return value == null ? null : string(value, label, 0, max);
}

export function boolean(value, label) {
  if (typeof value !== 'boolean') fail(`${label} must be a boolean`);
  return value;
}

export function number(value, label, min, max, integer = false) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max || (integer && !Number.isInteger(value))) {
    fail(`${label} must be ${integer ? 'an integer' : 'a number'} between ${min} and ${max}`);
  }
  return value;
}

export function choice(value, values, label) {
  if (!values.includes(value)) fail(`${label} is invalid`);
  return value;
}

export function array(value, label, max) {
  if (!Array.isArray(value) || value.length > max) fail(`${label} must be an array with at most ${max} entries`);
  return value;
}

export function language(value = 'ar') {
  const text = string(value, 'language', 2, 35);
  if (!/^[a-zA-Z]{2,8}(?:-[a-zA-Z0-9]{1,8})*$/u.test(text)) fail('language must be a language tag');
  try { return Intl.getCanonicalLocales(text)[0]; } catch { fail('language must be a language tag'); }
}

export function lap(value) {
  if (value == null) return null;
  object(value, ['mode', 'count', 'target', 'confidence'], 'lap');
  return {
    mode: choice(value.mode, ['tawaf', 'sai'], 'lap.mode'),
    count: number(value.count, 'lap.count', 0, 1000, true),
    target: number(value.target, 'lap.target', 7, 7, true),
    confidence: choice(value.confidence, ['manual', 'estimated'], 'lap.confidence'),
  };
}

export function telemetry(body, now) {
  object(body, ['sharingEnabled', 'location', 'batteryPercent', 'cameraConnected', 'imuTracking', 'watchConnected', 'destinationName', 'lap', 'status']);
  const sharingEnabled = boolean(body.sharingEnabled, 'sharingEnabled');
  let location = null;
  // Coordinates sent while sharing is disabled are intentionally not inspected,
  // retained, or echoed, including malformed accidental coordinates.
  if (sharingEnabled && body.location != null) {
    object(body.location, ['lat', 'lng', 'accuracyM', 'recordedAt'], 'location');
    const recordedAt = string(body.location.recordedAt, 'location.recordedAt', 20, 35);
    const time = Date.parse(recordedAt);
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/u.test(recordedAt) || !Number.isFinite(time)) fail('location.recordedAt must be a UTC ISO timestamp');
    if (time > now + 60_000) fail('location.recordedAt is in the future');
    location = {
      lat: number(body.location.lat, 'location.lat', -90, 90),
      lng: number(body.location.lng, 'location.lng', -180, 180),
      accuracyM: number(body.location.accuracyM, 'location.accuracyM', 0, 10000),
      recordedAt: new Date(time).toISOString(),
    };
  }
  return {
    sharingEnabled,
    location,
    batteryPercent: body.batteryPercent == null ? null : number(body.batteryPercent, 'batteryPercent', 0, 100),
    cameraConnected: boolean(body.cameraConnected, 'cameraConnected'),
    imuTracking: boolean(body.imuTracking, 'imuTracking'),
    watchConnected: boolean(body.watchConnected, 'watchConnected'),
    destinationName: nullableString(body.destinationName, 'destinationName', 160),
    lap: lap(body.lap),
    status: choice(body.status, ['active', 'needs_help', 'paused'], 'status'),
  };
}

export function assistantRequest(body) {
  object(body, ['message', 'language', 'context', 'destinations', 'history']);
  const destinations = array(body.destinations ?? [], 'destinations', 100).map(item => {
    object(item, ['id', 'name', 'aliases', 'distanceMeters'], 'destination');
    return {
      id: string(item.id, 'destination.id', 1, 120),
      name: string(item.name, 'destination.name', 1, 160),
      aliases: array(item.aliases ?? [], 'destination.aliases', 10).map(alias => string(alias, 'alias', 1, 120)),
      ...(item.distanceMeters == null ? {} : { distanceMeters: number(item.distanceMeters, 'destination.distanceMeters', 0, 10000000) }),
    };
  });
  if (new Set(destinations.map(item => item.id)).size !== destinations.length) fail('destination ids must be unique');
  const context = object(body.context ?? {}, ['destinationId', 'destinationName', 'remainingMeters', 'lap'], 'context');
  return {
    message: string(body.message, 'message', 1, 2000),
    language: language(body.language),
    context: {
      destinationId: nullableString(context.destinationId, 'context.destinationId', 120),
      destinationName: nullableString(context.destinationName, 'context.destinationName', 160),
      remainingMeters: context.remainingMeters == null ? null : number(context.remainingMeters, 'context.remainingMeters', 0, 10000000),
      lap: lap(context.lap),
    },
    destinations,
    history: array(body.history ?? [], 'history', 12).map(item => {
      object(item, ['role', 'content'], 'history item');
      return { role: choice(item.role, ['user', 'assistant'], 'history.role'), content: string(item.content, 'history.content', 1, 1500) };
    }),
  };
}
