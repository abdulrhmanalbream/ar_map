import { DEFAULT_GROUP_NAME } from './default-group.mjs';

/** One fixed public group's totals. No identifiers, names, messages or coordinates leave this query. */
export function publicDashboard(db, group, health, now = Date.now()) {
  const cutoff = new Date(now - 90_000).toISOString();
  const row = db.prepare(`SELECT COUNT(*) AS members,
    COALESCE(SUM(CASE WHEN last_seen_at>=? AND status='active' THEN 1 ELSE 0 END),0) AS active,
    COALESCE(SUM(CASE WHEN last_seen_at>=? AND status='paused' THEN 1 ELSE 0 END),0) AS paused,
    COALESCE(SUM(CASE WHEN last_seen_at>=? AND status='needs_help' THEN 1 ELSE 0 END),0) AS help,
    COALESCE(SUM(CASE WHEN last_seen_at IS NULL OR last_seen_at<? THEN 1 ELSE 0 END),0) AS offline,
    COALESCE(SUM(CASE WHEN sharing_enabled=1 THEN 1 ELSE 0 END),0) AS sharing,
    COALESCE(SUM(CASE WHEN last_seen_at>=? AND camera_connected=1 THEN 1 ELSE 0 END),0) AS eye,
    COALESCE(SUM(CASE WHEN last_seen_at>=? AND watch_connected=1 THEN 1 ELSE 0 END),0) AS watch
    FROM devices WHERE group_id=? AND revoked_at IS NULL`)
    .get(cutoff, cutoff, cutoff, cutoff, cutoff, cutoff, group.id);
  return {
    updatedAt: new Date(now).toISOString(),
    group: { name: DEFAULT_GROUP_NAME, memberCount: Number(row.members) },
    counts: { active: Number(row.active), offline: Number(row.offline), needsHelp: Number(row.help),
      paused: Number(row.paused), sharing: Number(row.sharing), eyeConnected: Number(row.eye), watchConnected: Number(row.watch) },
    health: { status: 'ok', aiConfigured: health.aiConfigured, aiProvider: health.aiProvider },
  };
}
