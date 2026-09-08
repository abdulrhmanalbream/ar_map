import { createServer } from 'node:http';
import { randomInt, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { realpath, stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { ApiError, object, string, language, array, choice, boolean, telemetry, assistantRequest, fail } from './validation.mjs';
import { digest, token, hashPassword, verifyPassword, RateLimiter } from './security.mjs';
import { openStore, transaction } from './store.mjs';
import { createAssistant } from './assistant.mjs';

const PREFIX = '/api/v1';
const DAY = 86400000;
const BODY_LIMIT = 65536;
const OFFLINE_AFTER_MS = 90000;
const LOCATION_STALE_MS = 120000;
const ALERT_TTL_MS = DAY;
const LOCATION_RETENTION_MS = DAY;

function iso(time) { return new Date(time).toISOString(); }
function groupSummary(row) { return { id: row.id, name: row.name, memberCount: Number(row.member_count ?? 0) }; }
function deviceIdentity(row) { return { id: row.id, name: row.name, groupId: row.group_id }; }
function userIdentity(row) { return { id: row.id, name: row.name, role: row.role }; }

function headers(res) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://tile.openstreetmap.org https://*.tile.openstreetmap.org; connect-src 'self' https://tile.openstreetmap.org https://*.tile.openstreetmap.org; font-src 'self' data:; worker-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
}

function json(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(status === 204 ? undefined : JSON.stringify(data));
}

async function readJson(req) {
  const size = Number(req.headers['content-length']);
  if (Number.isFinite(size) && size > BODY_LIMIT) {
    req.resume();
    throw new ApiError(413, 'BODY_TOO_LARGE', 'Request exceeds 64 KiB');
  }
  const type = req.headers['content-type']?.split(';')[0].trim().toLowerCase();
  if (type !== 'application/json') throw new ApiError(415, 'JSON_REQUIRED', 'Content-Type must be application/json');
  let length = 0;
  const chunks = [];
  for await (const chunk of req) {
    length += chunk.length;
    if (length > BODY_LIMIT) throw new ApiError(413, 'BODY_TOO_LARGE', 'Request exceeds 64 KiB');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new ApiError(400, 'INVALID_JSON', 'Request must contain valid JSON'); }
}

function limitParameter(url, fallback = 250, maximum = 500) {
  const value = url.searchParams.get('limit');
  if (value == null) return fallback;
  if (!/^\d{1,3}$/.test(value) || Number(value) < 1 || Number(value) > maximum) fail('Invalid limit');
  return Number(value);
}

export async function createPlatform(options = {}) {
  const now = options.now ?? Date.now;
  const db = await openStore({
    dataDir: options.dataDir ?? './data', adminUsername: options.adminUsername,
    adminPassword: options.adminPassword, now,
  });
  const rate = new RateLimiter(now);
  const ai = createAssistant({ apiKey: options.apiKey, model: options.model, geminiApiKey: options.geminiApiKey, geminiModel: options.geminiModel, fetchImpl: options.fetchImpl, timeoutMs: options.aiTimeoutMs });
  const dummyPassword = await hashPassword(token());
  let hashing = 0;
  let closed = false;

  const getGroup = db.prepare('SELECT * FROM groups WHERE id=?');
  const getDevice = db.prepare('SELECT * FROM devices WHERE id=?');
  function groupOwned(id, actor) {
    const row = getGroup.get(id);
    if (!row || row.owner_id !== actor.id) throw new ApiError(404, 'NOT_FOUND', 'Group not found');
    return row;
  }

  function authenticate(req, requiredRole) {
    const authorization = req.headers.authorization;
    if (typeof authorization !== 'string' || !/^Bearer sarab_[A-Za-z0-9_-]{43}$/.test(authorization)) {
      throw new ApiError(401, 'UNAUTHORIZED', 'Sign in or enroll this device');
    }
    const hash = digest(authorization.slice(7));
    const session = db.prepare('SELECT * FROM sessions WHERE token_hash=? AND expires_at>?').get(hash, iso(now()));
    if (!session) throw new ApiError(401, 'UNAUTHORIZED', 'Session expired or revoked');
    if (requiredRole && session.role !== requiredRole) throw new ApiError(403, 'FORBIDDEN', 'This operation is not allowed for this role');
    const row = session.role === 'admin'
      ? db.prepare('SELECT id,name,role FROM users WHERE id=?').get(session.user_id)
      : getDevice.get(session.device_id);
    if (!row || row.revoked_at) throw new ApiError(401, 'UNAUTHORIZED', 'Session expired or revoked');
    return { ...row, role: session.role, tokenHash: hash };
  }

  function createSession(role, id) {
    const bearer = token();
    const created = now();
    db.prepare('INSERT INTO sessions(token_hash,role,user_id,device_id,created_at,expires_at) VALUES(?,?,?,?,?,?)')
      .run(digest(bearer), role, role === 'admin' ? id : null, role === 'device' ? id : null,
        iso(created), iso(created + (role === 'admin' ? DAY / 2 : DAY * 90)));
    return bearer;
  }

  function deviceView(row) {
    const location = row.sharing_enabled && row.location_json && row.location_time > iso(now() - LOCATION_RETENTION_MS)
      ? JSON.parse(row.location_json) : null;
    const stale = !row.last_seen_at || Date.parse(row.last_seen_at) < now() - OFFLINE_AFTER_MS;
    return {
      ...deviceIdentity(row), groupName: row.group_name, language: row.language,
      status: stale ? 'offline' : row.status, reportedStatus: row.status, lastSeenAt: row.last_seen_at,
      location, batteryPercent: row.battery_percent,
      cameraConnected: !!row.camera_connected, imuTracking: !!row.imu_tracking, watchConnected: !!row.watch_connected,
      destinationName: row.destination_name, lap: row.lap_json ? JSON.parse(row.lap_json) : null,
      sharingEnabled: !!row.sharing_enabled, stale,
      locationStale: location != null && Date.parse(location.recordedAt) < now() - LOCATION_STALE_MS,
    };
  }

  function fullAlert(id) {
    const row = db.prepare(`SELECT a.*, COALESCE(d.name,u.name,'Group guide') source_name,
      acknowledged.name acknowledged_name FROM alerts a
      LEFT JOIN devices d ON d.id=a.source_device_id LEFT JOIN users u ON u.id=a.source_user_id
      LEFT JOIN users acknowledged ON acknowledged.id=a.admin_acknowledged_by WHERE a.id=?`).get(id);
    if (!row) return null;
    return {
      id: row.id, groupId: row.group_id, sourceDeviceId: row.source_device_id,
      sourceName: row.source_name, kind: row.kind, message: row.message, createdAt: row.created_at,
      expiresAt: row.expires_at, adminAcknowledgedAt: row.admin_acknowledged_at,
      adminAcknowledgedBy: row.acknowledged_name,
      recipients: db.prepare(`SELECT r.*,d.name FROM alert_recipients r JOIN devices d ON d.id=r.device_id
        WHERE r.alert_id=? ORDER BY d.name`).all(id).map(item => ({
        deviceId: item.device_id, name: item.name, deliveredAt: item.delivered_at,
        acknowledgedAt: item.acknowledged_at,
      })),
    };
  }

  function publicDeviceAlert(alert) {
    return { id: alert.id, kind: alert.kind, message: alert.message, createdAt: alert.createdAt, sourceName: alert.sourceName };
  }

  function createAlert({ groupId, sourceDeviceId = null, sourceUserId = null, kind, message, deviceIds, scope = null, clientId = null }) {
    const requestHash = digest(JSON.stringify({ groupId, kind, message, deviceIds: deviceIds?.slice().sort() ?? null }));
    return transaction(db, () => {
      if (clientId) {
        const existing = db.prepare('SELECT id,request_hash FROM alerts WHERE client_scope=? AND client_id=?').get(scope, clientId);
        if (existing) {
          if (existing.request_hash !== requestHash) throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'This request id was already used with different content');
          return { id: existing.id, created: false };
        }
      }
      const recipients = db.prepare('SELECT id FROM devices WHERE group_id=? AND revoked_at IS NULL').all(groupId)
        .map(row => row.id).filter(id => id !== sourceDeviceId);
      if (deviceIds?.some(id => !recipients.includes(id))) throw new ApiError(400, 'INVALID_RECIPIENT', 'Recipients must be active devices in this group');
      const targets = deviceIds?.length ? deviceIds : recipients;
      const id = randomUUID();
      const created = now();
      db.prepare(`INSERT INTO alerts(id,group_id,source_device_id,source_user_id,kind,message,created_at,expires_at,client_scope,client_id,request_hash)
        VALUES(?,?,?,?,?,?,?,?,?,?,?)`).run(id, groupId, sourceDeviceId, sourceUserId, kind, message, iso(created), iso(created + ALERT_TTL_MS), scope, clientId, requestHash);
      const insert = db.prepare('INSERT INTO alert_recipients(alert_id,device_id) VALUES(?,?)');
      for (const recipient of targets) insert.run(id, recipient);
      if (sourceDeviceId) db.prepare('UPDATE devices SET status=?,last_seen_at=? WHERE id=?')
        .run(kind === 'help' ? 'needs_help' : 'active', iso(now()), sourceDeviceId);
      return { id, created: true };
    });
  }

  function maintenance() {
    if (closed) return;
    const stamp = iso(now());
    // No history table exists. Purge even the latest location after 24 hours.
    db.prepare('UPDATE devices SET location_json=NULL,location_time=NULL WHERE location_time<? OR sharing_enabled=0')
      .run(iso(now() - LOCATION_RETENTION_MS));
    db.prepare('DELETE FROM sessions WHERE expires_at<=?').run(stamp);
    db.prepare('DELETE FROM enrollment_codes WHERE expires_at<=?').run(stamp);
  }
  maintenance();
  const maintenanceTimer = setInterval(maintenance, 60_000);
  maintenanceTimer.unref();

  async function routes(req, res, url) {
    const path = url.pathname;
    const method = req.method;
    // Do not trust arbitrary X-Forwarded-For headers. A reverse proxy may set
    // TRUST_PROXY only when direct access to this listener is blocked.
    const remote = options.trustProxy && typeof req.headers['x-forwarded-for'] === 'string'
      ? req.headers['x-forwarded-for'].split(',').at(-1).trim().slice(0,100) : (req.socket.remoteAddress ?? 'unknown');
    if (method === 'GET' && path === `${PREFIX}/health`) {
      return json(res, 200, { status: 'ok', aiConfigured: ai.provider !== 'local', aiProvider: ai.provider, version: '3.0' });
    }
    if (method === 'POST' && path === `${PREFIX}/auth/login`) {
      rate.take(`login-ip:${remote}`, 20, 15 * 60_000);
      const body = object(await readJson(req), ['username', 'password']);
      const username = string(body.username, 'username', 3, 80);
      string(body.password, 'password', 1, 256);
      const password = body.password; // Passwords are opaque; preserve intentional spaces.
      rate.take(`login-name:${digest(username.toLowerCase())}`, 10, 15 * 60_000);
      if (hashing >= 2) throw new ApiError(429, 'RATE_LIMITED', 'Authentication is busy. Try again shortly.');
      hashing++;
      const user = db.prepare('SELECT * FROM users WHERE username=? COLLATE NOCASE').get(username);
      let valid;
      try { valid = await verifyPassword(password, user?.password_hash ?? dummyPassword); }
      finally { hashing--; }
      if (!valid || !user) throw new ApiError(401, 'INVALID_CREDENTIALS', 'Username or password is incorrect');
      return json(res, 200, { token: createSession('admin', user.id), user: userIdentity(user) });
    }
    if (method === 'POST' && path === `${PREFIX}/devices/enroll`) {
      rate.take(`enroll:${remote}`, 30, 15 * 60_000);
      const body = object(await readJson(req), ['code', 'name', 'language']);
      const code = string(body.code, 'code', 8, 32).toUpperCase();
      if (!/^[A-Z0-9]{8,32}$/.test(code)) fail('Invalid enrollment code');
      const name = string(body.name, 'name', 1, 80);
      const locale = language(body.language);
      const result = transaction(db, () => {
        const enrollment = db.prepare('SELECT * FROM enrollment_codes WHERE code_hash=? AND expires_at>? AND uses<max_uses').get(digest(code), iso(now()));
        if (!enrollment) throw new ApiError(400, 'INVALID_ENROLLMENT', 'Enrollment code is invalid, expired, or fully used');
        const group = getGroup.get(enrollment.group_id);
        const count = db.prepare('SELECT COUNT(*) count FROM devices d JOIN groups g ON g.id=d.group_id WHERE g.owner_id=? AND d.revoked_at IS NULL').get(group.owner_id).count;
        if (count >= 5000) throw new ApiError(409, 'CAPACITY_REACHED', 'Device capacity reached');
        const id = randomUUID();
        db.prepare('INSERT INTO devices(id,group_id,name,language,created_at) VALUES(?,?,?,?,?)').run(id, group.id, name, locale, iso(now()));
        db.prepare('UPDATE enrollment_codes SET uses=uses+1 WHERE code_hash=?').run(digest(code));
        return { token: createSession('device', id), device: { id, name, groupId: group.id }, group: { id: group.id, name: group.name } };
      });
      return json(res, 201, result);
    }

    const actor = authenticate(req);
    rate.take(`api:${actor.role}:${actor.id}`, actor.role === 'admin' ? 600 : 240);
    const admin = () => { if (actor.role !== 'admin') throw new ApiError(403, 'FORBIDDEN', 'Administrator access required'); };
    const device = () => { if (actor.role !== 'device') throw new ApiError(403, 'FORBIDDEN', 'Device access required'); };

    if (method === 'GET' && path === `${PREFIX}/me`) return json(res, 200, { user: userIdentity(actor) });
    if (method === 'POST' && path === `${PREFIX}/auth/logout`) {
      transaction(db, () => {
        db.prepare('DELETE FROM sessions WHERE token_hash=?').run(actor.tokenHash);
        if (actor.role === 'device') db.prepare('UPDATE devices SET sharing_enabled=0,location_json=NULL,location_time=NULL,status=? WHERE id=?').run('paused', actor.id);
      });
      return json(res, 204);
    }
    if (path === `${PREFIX}/groups`) {
      admin();
      if (method === 'GET') {
        const rows = db.prepare(`SELECT g.id,g.name,COUNT(d.id) member_count FROM groups g
          LEFT JOIN devices d ON d.group_id=g.id AND d.revoked_at IS NULL WHERE g.owner_id=? GROUP BY g.id ORDER BY g.created_at DESC`).all(actor.id);
        return json(res, 200, { groups: rows.map(groupSummary) });
      }
      if (method === 'POST') {
        const body = object(await readJson(req), ['name']);
        const name = string(body.name, 'name', 1, 100);
        if (db.prepare('SELECT COUNT(*) count FROM groups WHERE owner_id=?').get(actor.id).count >= 500) throw new ApiError(409, 'CAPACITY_REACHED', 'Group capacity reached');
        if (db.prepare('SELECT id FROM groups WHERE owner_id=? AND name=?').get(actor.id, name)) throw new ApiError(409, 'ALREADY_EXISTS', 'A group with this name already exists');
        const id = randomUUID();
        db.prepare('INSERT INTO groups(id,owner_id,name,created_at) VALUES(?,?,?,?)').run(id, actor.id, name, iso(now()));
        return json(res, 201, { group: { id, name, memberCount: 0 } });
      }
    }
    let match = path.match(/^\/api\/v1\/groups\/([^/]+)\/enrollment$/);
    if (method === 'POST' && match) {
      admin();
      const group = groupOwned(match[1], actor);
      rate.take(`code:${actor.id}`, 30);
      const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
      const code = Array.from({ length: 10 }, () => alphabet[randomInt(alphabet.length)]).join('');
      const expiresAt = iso(now() + DAY);
      db.prepare('INSERT INTO enrollment_codes(code_hash,group_id,expires_at) VALUES(?,?,?)').run(digest(code), group.id, expiresAt);
      return json(res, 201, { code, expiresAt });
    }
    if (method === 'GET' && path === `${PREFIX}/devices`) {
      admin();
      const rows = db.prepare(`SELECT d.*,g.name group_name FROM devices d JOIN groups g ON g.id=d.group_id
        WHERE g.owner_id=? AND d.revoked_at IS NULL ORDER BY d.created_at DESC`).all(actor.id);
      return json(res, 200, { devices: rows.map(deviceView) });
    }
    match = path.match(/^\/api\/v1\/devices\/([^/]+)$/);
    if (method === 'DELETE' && match) {
      admin();
      const row = getDevice.get(match[1]);
      if (!row) throw new ApiError(404, 'NOT_FOUND', 'Device not found');
      groupOwned(row.group_id, actor);
      transaction(db, () => {
        db.prepare('UPDATE devices SET revoked_at=?,sharing_enabled=0,location_json=NULL,location_time=NULL WHERE id=?').run(iso(now()), row.id);
        db.prepare('DELETE FROM sessions WHERE device_id=?').run(row.id);
      });
      return json(res, 204);
    }
    if (method === 'GET' && path === `${PREFIX}/device/profile`) {
      device();
      const group = getGroup.get(actor.group_id);
      return json(res, 200, { device: deviceIdentity(actor), group: { id: group.id, name: group.name } });
    }
    if (method === 'POST' && path === `${PREFIX}/device/telemetry`) {
      device();
      const payload = telemetry(await readJson(req), now());
      transaction(db, () => {
        const previous = getDevice.get(actor.id);
        let location = payload.location;
        // Out-of-order position uploads cannot move the latest position backwards.
        if (payload.sharingEnabled && location && previous.sharing_enabled && previous.location_time && location.recordedAt < previous.location_time) {
          location = previous.location_json ? JSON.parse(previous.location_json) : null;
        }
        if (location && Date.parse(location.recordedAt) < now() - LOCATION_RETENTION_MS) location = null;
        let lap = payload.lap;
        const previousLap = previous.lap_json ? JSON.parse(previous.lap_json) : null;
        if (lap?.sessionId && previousLap?.sessionId && (
          (lap.sessionId === previousLap.sessionId && lap.revision <= previousLap.revision) ||
          (lap.sessionId !== previousLap.sessionId && lap.startedAt < previousLap.startedAt)
        )) lap = previousLap;
        db.prepare(`UPDATE devices SET last_seen_at=?,status=?,sharing_enabled=?,location_json=?,location_time=?,
          battery_percent=?,camera_connected=?,imu_tracking=?,watch_connected=?,destination_name=?,lap_json=? WHERE id=?`)
          .run(iso(now()), payload.status, Number(payload.sharingEnabled), location ? JSON.stringify(location) : null, location?.recordedAt ?? null,
            payload.batteryPercent, Number(payload.cameraConnected), Number(payload.imuTracking), Number(payload.watchConnected),
            payload.destinationName, lap ? JSON.stringify(lap) : null, actor.id);
      });
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && path === `${PREFIX}/device/privacy`) {
      device();
      const body = object(await readJson(req), ['sharingEnabled']);
      if (boolean(body.sharingEnabled, 'sharingEnabled') !== false) fail('Use telemetry only after explicitly enabling location sharing');
      db.prepare('UPDATE devices SET sharing_enabled=0,location_json=NULL,location_time=NULL WHERE id=?').run(actor.id);
      return json(res, 200, { ok: true });
    }
    if (path === `${PREFIX}/alerts`) {
      admin();
      if (method === 'GET') {
        const rows = db.prepare(`SELECT a.id FROM alerts a JOIN groups g ON g.id=a.group_id
          WHERE g.owner_id=? ORDER BY a.created_at DESC,a.id DESC LIMIT ?`).all(actor.id, limitParameter(url));
        return json(res, 200, { alerts: rows.map(row => fullAlert(row.id)) });
      }
      if (method === 'POST') {
        rate.take(`alerts-admin:${actor.id}`, 30);
        const body = object(await readJson(req), ['groupId', 'deviceIds', 'kind', 'message']);
        const group = groupOwned(string(body.groupId, 'groupId', 1, 80), actor);
        const ids = body.deviceIds == null ? undefined : array(body.deviceIds, 'deviceIds', 5000).map(id => string(id, 'device id', 1, 80));
        if (ids && new Set(ids).size !== ids.length) fail('Recipient ids must be unique');
        const key = req.headers['idempotency-key'] == null ? null : string(req.headers['idempotency-key'], 'Idempotency-Key', 8, 120);
        const result = createAlert({ groupId: group.id, sourceUserId: actor.id,
          kind: choice(body.kind, ['help', 'regroup', 'message'], 'kind'), message: string(body.message, 'message', 1, 1000),
          deviceIds: ids, scope: `admin:${actor.id}`, clientId: key });
        return json(res, result.created ? 201 : 200, { alert: fullAlert(result.id) });
      }
    }
    match = path.match(/^\/api\/v1\/alerts\/([^/]+)\/ack$/);
    if (method === 'POST' && match) {
      admin();
      const row = db.prepare('SELECT group_id FROM alerts WHERE id=?').get(match[1]);
      if (!row) throw new ApiError(404, 'NOT_FOUND', 'Alert not found');
      groupOwned(row.group_id, actor);
      db.prepare('UPDATE alerts SET admin_acknowledged_at=COALESCE(admin_acknowledged_at,?),admin_acknowledged_by=COALESCE(admin_acknowledged_by,?) WHERE id=?')
        .run(iso(now()), actor.id, match[1]);
      return json(res, 200, { ok: true });
    }
    if (path === `${PREFIX}/device/alerts`) {
      device();
      if (method === 'GET') {
        const rows = db.prepare(`SELECT a.id FROM alerts a JOIN alert_recipients r ON r.alert_id=a.id
          WHERE r.device_id=? AND a.group_id=? AND r.acknowledged_at IS NULL AND a.expires_at>?
          ORDER BY a.created_at,a.id LIMIT 100`).all(actor.id, actor.group_id, iso(now()));
        return json(res, 200, { alerts: rows.map(row => publicDeviceAlert(fullAlert(row.id))) });
      }
      if (method === 'POST') {
        rate.take(`alerts-device:${actor.id}`, 12);
        const body = object(await readJson(req), ['kind', 'message', 'clientId']);
        const result = createAlert({ groupId: actor.group_id, sourceDeviceId: actor.id,
          kind: choice(body.kind, ['help', 'regroup'], 'kind'), message: string(body.message, 'message', 1, 1000),
          scope: `device:${actor.id}`, clientId: string(body.clientId, 'clientId', 1, 120) });
        return json(res, result.created ? 201 : 200, { alert: publicDeviceAlert(fullAlert(result.id)) });
      }
    }
    match = path.match(/^\/api\/v1\/device\/alerts\/([^/]+)\/(delivered|ack)$/);
    if (method === 'POST' && match) {
      device();
      const row = db.prepare(`SELECT r.alert_id FROM alert_recipients r JOIN alerts a ON a.id=r.alert_id
        WHERE r.alert_id=? AND r.device_id=? AND a.group_id=?`).get(match[1], actor.id, actor.group_id);
      if (!row) throw new ApiError(404, 'NOT_FOUND', 'Alert not found for this device');
      if (match[2] === 'ack') {
        db.prepare(`UPDATE alert_recipients SET delivered_at=COALESCE(delivered_at,?),
          acknowledged_at=COALESCE(acknowledged_at,?) WHERE alert_id=? AND device_id=?`).run(iso(now()), iso(now()), match[1], actor.id);
      } else {
        db.prepare('UPDATE alert_recipients SET delivered_at=COALESCE(delivered_at,?) WHERE alert_id=? AND device_id=?').run(iso(now()), match[1], actor.id);
      }
      return json(res, 200, { ok: true });
    }
    if (method === 'POST' && path === `${PREFIX}/assistant`) {
      device();
      rate.take(`assistant:${actor.id}`, 10);
      rate.take('assistant-global', 120);
      const request = assistantRequest(await readJson(req));
      return json(res, 200, await ai(request));
    }
    throw new ApiError(404, 'NOT_FOUND', 'API endpoint not found');
  }

  async function staticFile(req, res, url) {
    if (!['GET', 'HEAD'].includes(req.method)) throw new ApiError(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    if (!options.webRoot) throw new ApiError(503, 'DASHBOARD_NOT_BUILT', 'Dashboard build is not configured');
    let decoded;
    try { decoded = decodeURIComponent(url.pathname); } catch { fail('Invalid path'); }
    if (decoded.includes('\0') || decoded.includes('\\') || decoded.split('/').some(part => part.startsWith('.'))) throw new ApiError(404, 'NOT_FOUND', 'File not found');
    const root = await realpath(resolve(options.webRoot)).catch(() => null);
    if (!root) throw new ApiError(503, 'DASHBOARD_NOT_BUILT', 'Dashboard build is not available');
    let file = resolve(root, `.${decoded === '/' ? '/index.html' : decoded}`);
    if (!file.startsWith(root + sep)) throw new ApiError(404, 'NOT_FOUND', 'File not found');
    let info = await stat(file).catch(() => null);
    if ((!info || !info.isFile()) && !extname(decoded)) {
      file = resolve(root, 'index.html');
      info = await stat(file).catch(() => null);
    }
    const real = await realpath(file).catch(() => null);
    const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.json': 'application/json; charset=utf-8' };
    const type = types[extname(file).toLowerCase()];
    if (!info?.isFile() || !real?.startsWith(root + sep) || !type) throw new ApiError(404, 'NOT_FOUND', 'File not found');
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': info.size,
      'Cache-Control': extname(file) === '.html' ? 'no-cache' : 'public, max-age=3600' });
    if (req.method === 'HEAD') return res.end();
    await pipeline(createReadStream(real), res);
  }

  const server = createServer(async (req, res) => {
    headers(res);
    try {
      if (!req.url || req.url.length > 2048) throw new ApiError(414, 'URI_TOO_LONG', 'Request URL is too long');
      if (Number(req.headers['content-length']) > BODY_LIMIT) {
        req.resume();
        throw new ApiError(413, 'BODY_TOO_LARGE', 'Request exceeds 64 KiB');
      }
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname.startsWith('/api/')) await routes(req, res, url);
      else await staticFile(req, res, url);
    } catch (error) {
      if (res.headersSent || res.destroyed) { res.destroy(); return; }
      if (error instanceof ApiError) {
        if (error.status === 429) res.setHeader('Retry-After', '60');
        if (error.status === 413) res.setHeader('Connection', 'close');
        json(res, error.status, { error: error.message, code: error.code });
      } else {
        // Avoid raw exceptions: SQLite/provider errors can contain sensitive data.
        json(res, 500, { error: 'The request could not be completed', code: 'INTERNAL_ERROR' });
      }
    }
  });
  server.requestTimeout = 20000;
  server.headersTimeout = 15000;
  server.keepAliveTimeout = 5000;
  server.maxHeadersCount = 64;
  server.maxRequestsPerSocket = 1000;
  server.on('clientError', (_error, socket) => { if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); });
  return {
    server, db, maintenance,
    async close() {
      if (closed) return;
      closed = true;
      clearInterval(maintenanceTimer);
      if (server.listening) await new Promise((resolveClose, rejectClose) => {
        server.close(error => error ? rejectClose(error) : resolveClose());
        server.closeIdleConnections();
      });
      db.close();
    },
  };
}
