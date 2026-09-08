/**
 * One narrowly scoped QA cleanup for the September 8, 2026 verification group.
 * Requires Node 24.14+, the existing DATA_DIR, an explicit group UUID, and the
 * exact expected name. Defaults to a read-only dry run; --execute is required
 * to delete. Never imports server bootstrap, creates a database, reads secrets,
 * drops tables, vacuums, or touches admin users or another group's records.
 *
 * Dry run:
 * node cleanup-qa.mjs --group-id <UUID> --expected-name 'فحص سراب 20260908'
 * Execute the same reviewed target by adding --execute.
 * This file can also be piped to node --input-type=module - with those arguments.
 */
import { DatabaseSync } from 'node:sqlite';
import { lstatSync, realpathSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';

const EXPECTED_NAME = 'فحص سراب 20260908';
const ALLOWED_MEMBERS = new Set(['QA-Phone', 'QA-Protocol']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function requireSafe(condition, code) {
  if (!condition) throw new Error(code);
}

function argumentsForCleanup(args) {
  const result = { execute: false };
  for (let index = 0; index < args.length; index++) {
    const flag = args[index];
    if (flag === '--execute') {
      requireSafe(!result.execute, 'DUPLICATE_EXECUTE_FLAG');
      result.execute = true;
    } else if (flag === '--group-id' || flag === '--expected-name') {
      const key = flag === '--group-id' ? 'groupId' : 'expectedName';
      requireSafe(result[key] === undefined && index + 1 < args.length, 'INVALID_ARGUMENTS');
      result[key] = args[++index];
    } else throw new Error('UNSUPPORTED_ARGUMENT');
  }
  requireSafe(typeof result.groupId === 'string' && UUID.test(result.groupId), 'EXPLICIT_GROUP_UUID_REQUIRED');
  requireSafe(result.expectedName === EXPECTED_NAME, 'EXACT_QA_NAME_REQUIRED');
  return result;
}

let db;
let transactionOpen = false;
try {
  const { groupId, expectedName, execute } = argumentsForCleanup(process.argv.slice(2));
  requireSafe(typeof process.env.DATA_DIR === 'string' && isAbsolute(process.env.DATA_DIR), 'EXPLICIT_ABSOLUTE_DATA_DIR_REQUIRED');
  const directory = realpathSync(process.env.DATA_DIR);
  const path = join(directory, 'sarab.sqlite');
  // Refuse a missing file and symlinks before SQLite could create/open a target.
  const file = lstatSync(path);
  requireSafe(file.isFile() && !file.isSymbolicLink(), 'EXISTING_REGULAR_DATABASE_REQUIRED');
  db = new DatabaseSync(path, { readOnly: !execute });
  db.exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=3000;');
  requireSafe(db.prepare('PRAGMA foreign_keys').get().foreign_keys === 1, 'FOREIGN_KEYS_REQUIRED');
  requireSafe(db.prepare('PRAGMA user_version').get().user_version === 1, 'UNEXPECTED_SCHEMA_VERSION');
  requireSafe(db.prepare("SELECT COUNT(*) AS count FROM sqlite_master WHERE type='trigger'").get().count === 0, 'UNEXPECTED_DATABASE_TRIGGERS');
  if (execute) db.exec('PRAGMA secure_delete=ON;');
  // A write reservation prevents enrollment or new alerts changing the target
  // between its safety checks and deletion. Dry run opens no write transaction.
  db.exec(execute ? 'BEGIN IMMEDIATE' : 'BEGIN');
  transactionOpen = true;

  const group = db.prepare('SELECT name FROM groups WHERE id=?').get(groupId);
  requireSafe(group && group.name === expectedName, 'GROUP_MISSING_OR_NAME_MISMATCH');
  // Include revoked members: they still have records and must also be QA-only.
  const members = db.prepare('SELECT name FROM devices WHERE group_id=?').all(groupId);
  requireSafe(members.length <= 2 && members.every(member => ALLOWED_MEMBERS.has(member.name)), 'UNEXPECTED_MEMBER_OR_MEMBER_COUNT');
  requireSafe(!db.prepare('PRAGMA foreign_key_check').get(), 'EXISTING_FOREIGN_KEY_VIOLATION');

  // Cross-group references are not created by the API, but refuse cleanup if
  // any exist rather than deleting another group's delivery evidence.
  const crossReferences = [
    ['SELECT COUNT(*) AS count FROM alerts a JOIN devices d ON d.id=a.source_device_id WHERE a.group_id=? AND d.group_id<>?', groupId, groupId],
    ['SELECT COUNT(*) AS count FROM alerts a JOIN devices d ON d.id=a.source_device_id WHERE d.group_id=? AND a.group_id<>?', groupId, groupId],
    ['SELECT COUNT(*) AS count FROM alert_recipients r JOIN alerts a ON a.id=r.alert_id JOIN devices d ON d.id=r.device_id WHERE a.group_id=? AND d.group_id<>?', groupId, groupId],
    ['SELECT COUNT(*) AS count FROM alert_recipients r JOIN alerts a ON a.id=r.alert_id JOIN devices d ON d.id=r.device_id WHERE d.group_id=? AND a.group_id<>?', groupId, groupId],
  ];
  for (const [sql, ...values] of crossReferences) {
    requireSafe(db.prepare(sql).get(...values).count === 0, 'CROSS_GROUP_REFERENCE_FOUND');
  }

  const operations = [
    ['recipients', 'alert_recipients', 'alert_id IN (SELECT id FROM alerts WHERE group_id=?)'],
    ['alerts', 'alerts', 'group_id=?'],
    ['deviceSessions', 'sessions', "role='device' AND device_id IN (SELECT id FROM devices WHERE group_id=?)"],
    ['enrollmentCodes', 'enrollment_codes', 'group_id=?'],
    ['devices', 'devices', 'group_id=?'],
    ['groups', 'groups', 'id=?'],
  ];
  const counts = Object.fromEntries(operations.map(([name, table, where]) => [
    name, Number(db.prepare(`SELECT COUNT(*) AS count FROM ${table} WHERE ${where}`).get(groupId).count),
  ]));
  const otherGroups = JSON.stringify(db.prepare('SELECT id,owner_id,name,created_at FROM groups WHERE id<>? ORDER BY id').all(groupId));
  const adminCount = db.prepare('SELECT COUNT(*) AS count FROM users').get().count;

  if (execute) {
    for (const [name, table, where] of operations) {
      const result = db.prepare(`DELETE FROM ${table} WHERE ${where}`).run(groupId);
      requireSafe(Number(result.changes) === counts[name], 'DELETE_COUNT_MISMATCH');
    }
    requireSafe(!db.prepare('SELECT 1 FROM groups WHERE id=?').get(groupId), 'TARGET_GROUP_REMAINS');
    requireSafe(JSON.stringify(db.prepare('SELECT id,owner_id,name,created_at FROM groups WHERE id<>? ORDER BY id').all(groupId)) === otherGroups, 'OTHER_GROUP_CHANGED');
    requireSafe(db.prepare('SELECT COUNT(*) AS count FROM users').get().count === adminCount, 'ADMIN_COUNT_CHANGED');
    requireSafe(!db.prepare('PRAGMA foreign_key_check').get(), 'RESULTING_FOREIGN_KEY_VIOLATION');
    db.exec('COMMIT');
  } else db.exec('ROLLBACK');
  transactionOpen = false;
  console.log(JSON.stringify({ mode: execute ? 'deleted' : 'dry-run', counts, otherGroupsUnchanged: true }));
} catch (error) {
  if (transactionOpen) {
    try { db.exec('ROLLBACK'); } catch { /* Preserve the original refusal. */ }
  }
  // No raw SQLite errors, database paths, record IDs, names, tokens, or keys.
  const code = typeof error.message === 'string' && /^[A-Z_]+$/.test(error.message) ? error.message : 'DATABASE_OR_RUNTIME_FAILURE';
  console.error(JSON.stringify({ status: 'refused', code }));
  process.exitCode = 1;
} finally { db?.close(); }
