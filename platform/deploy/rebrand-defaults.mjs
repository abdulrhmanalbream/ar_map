/**
 * Rename exactly the reviewed default group's and administrator's display names.
 * Existing DATA_DIR and Node 24.14+ are required. This script is read-only by
 * default; --execute applies the two guarded updates in one transaction.
 * --dry-run is an optional explicit spelling of the default mode.
 *
 * node rebrand-defaults.mjs --dry-run
 * node rebrand-defaults.mjs --execute
 * Also supports piping to node --input-type=module - --execute.
 * No credential, identifier, location, or fingerprint is printed.
 */
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { lstatSync, realpathSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const GROUP_ID = '643a3405-9936-47c7-86a6-726bb197a241';
const ADMIN_ID = '4b7c73dd-ad24-44f8-a674-9703360d059f';
const OLD_GROUP_NAME = 'مجموعة سراب';
const NEW_GROUP_NAME = 'مجموعة المطوف الذكي';
const OLD_ADMIN_NAME = 'sarab-admin';
const NEW_ADMIN_NAME = 'مشرف المطوف الذكي';
const EXPECTED_USERNAME = 'sarab-admin';

function requireSafe(condition, code) {
  if (!condition) throw new Error(code);
}

/** Hash rows incrementally, retaining neither copies nor printed digests. */
function protectedFingerprints(db) {
  const queries = {
    users: 'SELECT * FROM users ORDER BY id',
    groups: 'SELECT * FROM groups ORDER BY id',
    sessions: 'SELECT * FROM sessions ORDER BY token_hash',
    enrollmentCodes: 'SELECT * FROM enrollment_codes ORDER BY code_hash',
    devices: 'SELECT * FROM devices ORDER BY id',
    alerts: 'SELECT * FROM alerts ORDER BY id',
    recipients: 'SELECT * FROM alert_recipients ORDER BY alert_id,device_id',
    schema: 'SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name',
  };
  const fingerprints = {};
  for (const [table, sql] of Object.entries(queries)) {
    const hash = createHash('sha256');
    for (const row of db.prepare(sql).iterate()) {
      // These are the only two permitted field changes. All other fields and
      // all other rows, including login/password hashes, stay fingerprinted.
      if ((table === 'users' && row.id === ADMIN_ID) || (table === 'groups' && row.id === GROUP_ID)) {
        delete row.name;
      }
      hash.update(JSON.stringify(row));
      hash.update('\n');
    }
    fingerprints[table] = hash.digest('hex');
  }
  return JSON.stringify(fingerprints);
}

let db;
let transactionOpen = false;
try {
  const args = process.argv.slice(2);
  requireSafe(args.length <= 1 && (args.length === 0 || ['--execute', '--dry-run'].includes(args[0])), 'INVALID_ARGUMENTS');
  const execute = args[0] === '--execute';
  requireSafe(typeof process.env.DATA_DIR === 'string' && isAbsolute(process.env.DATA_DIR), 'EXPLICIT_ABSOLUTE_DATA_DIR_REQUIRED');
  const path = join(realpathSync(process.env.DATA_DIR), 'sarab.sqlite');
  const file = lstatSync(path);
  requireSafe(file.isFile() && !file.isSymbolicLink(), 'EXISTING_REGULAR_DATABASE_REQUIRED');
  db = new DatabaseSync(path, { readOnly: !execute });
  db.exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=3000;');
  if (execute) db.exec('PRAGMA secure_delete=ON;');
  db.exec(execute ? 'BEGIN IMMEDIATE' : 'BEGIN');
  transactionOpen = true;
  requireSafe(db.prepare('PRAGMA foreign_keys').get().foreign_keys === 1, 'FOREIGN_KEYS_REQUIRED');
  requireSafe(db.prepare('PRAGMA user_version').get().user_version === 1, 'UNEXPECTED_SCHEMA_VERSION');
  requireSafe(db.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type='trigger'").get().count === 0, 'UNEXPECTED_DATABASE_TRIGGERS');
  requireSafe(!db.prepare('PRAGMA foreign_key_check').get(), 'EXISTING_FOREIGN_KEY_VIOLATION');

  const group = db.prepare('SELECT owner_id,name FROM groups WHERE id=?').get(GROUP_ID);
  requireSafe(group?.owner_id === ADMIN_ID && group.name === OLD_GROUP_NAME, 'DEFAULT_GROUP_GUARD_FAILED');
  const admin = db.prepare('SELECT username,name,role FROM users WHERE id=?').get(ADMIN_ID);
  requireSafe(admin?.username === EXPECTED_USERNAME && admin.name === OLD_ADMIN_NAME && admin.role === 'admin', 'DEFAULT_ADMIN_GUARD_FAILED');
  requireSafe(!db.prepare('SELECT 1 FROM groups WHERE owner_id=? AND name=? AND id<>?').get(ADMIN_ID, NEW_GROUP_NAME, GROUP_ID), 'GROUP_NAME_CONFLICT');
  const before = protectedFingerprints(db);

  if (execute) {
    const renamedGroup = db.prepare('UPDATE groups SET name=? WHERE id=? AND owner_id=? AND name=?')
      .run(NEW_GROUP_NAME, GROUP_ID, ADMIN_ID, OLD_GROUP_NAME);
    requireSafe(Number(renamedGroup.changes) === 1, 'GROUP_UPDATE_COUNT_MISMATCH');
    const renamedAdmin = db.prepare("UPDATE users SET name=? WHERE id=? AND username=? AND name=? AND role='admin'")
      .run(NEW_ADMIN_NAME, ADMIN_ID, EXPECTED_USERNAME, OLD_ADMIN_NAME);
    requireSafe(Number(renamedAdmin.changes) === 1, 'ADMIN_UPDATE_COUNT_MISMATCH');
    requireSafe(db.prepare('SELECT name FROM groups WHERE id=?').get(GROUP_ID)?.name === NEW_GROUP_NAME, 'GROUP_RENAME_VERIFICATION_FAILED');
    requireSafe(db.prepare('SELECT name FROM users WHERE id=?').get(ADMIN_ID)?.name === NEW_ADMIN_NAME, 'ADMIN_RENAME_VERIFICATION_FAILED');
  }

  requireSafe(protectedFingerprints(db) === before, 'PROTECTED_RECORD_CHANGED');
  requireSafe(!db.prepare('PRAGMA foreign_key_check').get(), 'RESULTING_FOREIGN_KEY_VIOLATION');
  db.exec(execute ? 'COMMIT' : 'ROLLBACK');
  transactionOpen = false;
  console.log(JSON.stringify({ mode: execute ? 'renamed' : 'dry-run', displayNameChanges: execute ? 2 : 0, plannedDisplayNameChanges: 2, protectedRecordsUnchanged: true }));
} catch (error) {
  if (transactionOpen) {
    try { db.exec('ROLLBACK'); } catch { /* Retain the original refusal. */ }
  }
  const code = typeof error.message === 'string' && /^[A-Z_]+$/.test(error.message) ? error.message : 'DATABASE_OR_RUNTIME_FAILURE';
  console.error(JSON.stringify({ status: 'refused', code }));
  process.exitCode = 1;
} finally { db?.close(); }
