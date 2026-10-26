import { transaction } from './store.mjs';

export const DEFAULT_GROUP_ID = 'smart-mutawwif-public';
export const DEFAULT_GROUP_NAME = 'مجموعة المطوف الذكي';

// A stable, server-owned group: opening enrollment never moves existing members.
// An ambiguous administrator configuration must not attach strangers to a tenant.
export function ensureDefaultGroup(db, adminUsername, now = Date.now) {
  const owners = typeof adminUsername === 'string' && adminUsername.trim()
    ? db.prepare('SELECT id FROM users WHERE username=? COLLATE NOCASE').all(adminUsername)
    : db.prepare('SELECT id FROM users ORDER BY created_at,id LIMIT 2').all();
  if (owners.length !== 1) return null;
  return transaction(db, () => {
    const existing = db.prepare('SELECT * FROM groups WHERE id=?').get(DEFAULT_GROUP_ID);
    if (existing) return existing.owner_id === owners[0].id ? existing : null;
    let name = DEFAULT_GROUP_NAME;
    // Preserve any manually created group that already uses the display name.
    let suffix = 1;
    while (db.prepare('SELECT id FROM groups WHERE owner_id=? AND name=?').get(owners[0].id, name)) name = `${DEFAULT_GROUP_NAME} · عامة ${suffix++}`;
    db.prepare('INSERT INTO groups(id,owner_id,name,created_at) VALUES(?,?,?,?)')
      .run(DEFAULT_GROUP_ID, owners[0].id, name, new Date(now()).toISOString());
    return db.prepare('SELECT * FROM groups WHERE id=?').get(DEFAULT_GROUP_ID);
  });
}
