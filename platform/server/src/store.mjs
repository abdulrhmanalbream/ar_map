import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, chmodSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { hashPassword } from './security.mjs';

export async function openStore({ dataDir, adminUsername, adminPassword, now = Date.now }) {
  const directory = resolve(dataDir);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const path = join(directory, 'sarab.sqlite');
  const db = new DatabaseSync(path);
  try {
    if (db.prepare('PRAGMA user_version').get().user_version > 1) throw new Error('Database schema is newer than this server');
    // Do not retain obsolete coordinates in WAL/checkpoint history or free pages.
    // A single server process and brief transactions keep DELETE journaling cheap.
    db.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=DELETE; PRAGMA secure_delete=ON;
      PRAGMA busy_timeout=3000; PRAGMA synchronous=FULL; PRAGMA cache_size=-4096;
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
        name TEXT NOT NULL, role TEXT NOT NULL CHECK(role='admin'), password_hash TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS groups (
        id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL,
        created_at TEXT NOT NULL, UNIQUE(owner_id,name)
      );
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES groups(id), name TEXT NOT NULL,
        language TEXT NOT NULL, created_at TEXT NOT NULL, revoked_at TEXT,
        last_seen_at TEXT, status TEXT NOT NULL DEFAULT 'paused', sharing_enabled INTEGER NOT NULL DEFAULT 0,
        location_json TEXT, location_time TEXT, battery_percent REAL,
        camera_connected INTEGER NOT NULL DEFAULT 0, imu_tracking INTEGER NOT NULL DEFAULT 0,
        watch_connected INTEGER NOT NULL DEFAULT 0, destination_name TEXT, lap_json TEXT
      );
      CREATE TABLE IF NOT EXISTS sessions (
        token_hash TEXT PRIMARY KEY, role TEXT NOT NULL CHECK(role IN ('admin','device')),
        user_id TEXT REFERENCES users(id), device_id TEXT REFERENCES devices(id),
        created_at TEXT NOT NULL, expires_at TEXT NOT NULL,
        CHECK((role='admin' AND user_id IS NOT NULL AND device_id IS NULL) OR
          (role='device' AND device_id IS NOT NULL AND user_id IS NULL))
      );
      CREATE TABLE IF NOT EXISTS enrollment_codes (
        code_hash TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES groups(id),
        expires_at TEXT NOT NULL, uses INTEGER NOT NULL DEFAULT 0, max_uses INTEGER NOT NULL DEFAULT 20
      );
      CREATE TABLE IF NOT EXISTS alerts (
        id TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES groups(id),
        source_device_id TEXT REFERENCES devices(id), source_user_id TEXT REFERENCES users(id),
        kind TEXT NOT NULL CHECK(kind IN ('help','regroup','message')), message TEXT NOT NULL,
        created_at TEXT NOT NULL, expires_at TEXT NOT NULL, client_scope TEXT, client_id TEXT,
        request_hash TEXT, admin_acknowledged_at TEXT, admin_acknowledged_by TEXT REFERENCES users(id),
        UNIQUE(client_scope,client_id)
      );
      CREATE TABLE IF NOT EXISTS alert_recipients (
        alert_id TEXT NOT NULL REFERENCES alerts(id), device_id TEXT NOT NULL REFERENCES devices(id),
        delivered_at TEXT, acknowledged_at TEXT, PRIMARY KEY(alert_id,device_id)
      );
      CREATE INDEX IF NOT EXISTS devices_group ON devices(group_id);
      CREATE INDEX IF NOT EXISTS alerts_group_created ON alerts(group_id,created_at);
      CREATE INDEX IF NOT EXISTS recipients_pending ON alert_recipients(device_id,acknowledged_at);
      CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
      PRAGMA user_version=1;`);
    chmodSync(path, 0o600);
    const admins = db.prepare('SELECT COUNT(*) AS count FROM users').get().count;
    if (!admins) {
      if (typeof adminUsername !== 'string' || !/^[a-zA-Z0-9_.@-]{3,80}$/.test(adminUsername)) {
        throw new Error('First bootstrap requires ADMIN_USERNAME (3–80 letters, numbers, or ._@-).');
      }
      if (typeof adminPassword !== 'string' || adminPassword.length < 12 || adminPassword.length > 256 ||
          /^(password|admin|sarab|1234567890|changeme)/i.test(adminPassword) || adminPassword.toLowerCase() === adminUsername.toLowerCase()) {
        throw new Error('First bootstrap requires a strong ADMIN_PASSWORD of 12–256 characters; no default password is provided.');
      }
      const encoded = await hashPassword(adminPassword);
      db.prepare('INSERT INTO users(id,username,name,role,password_hash,created_at) VALUES(?,?,?,?,?,?)')
        .run(randomUUID(), adminUsername, adminUsername, 'admin', encoded, new Date(now()).toISOString());
    }
    return db;
  } catch (error) { db.close(); throw error; }
}

export function transaction(db, operation) {
  db.exec('BEGIN IMMEDIATE');
  try { const result = operation(); db.exec('COMMIT'); return result; }
  catch (error) { db.exec('ROLLBACK'); throw error; }
}
