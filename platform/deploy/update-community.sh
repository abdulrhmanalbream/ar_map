#!/bin/sh
# Scoped default-group release; preserves every other project's runtime/config.
set -eu
stage=$(realpath "${1:?Pass extracted community release directory}")
case "$stage" in /home/sysadmin/sm-community-*/release) ;; *) echo 'Invalid stage' >&2; exit 1 ;; esac
target=/opt/sarab-platform
[ "$(realpath "$target")" = "$target" ]
[ -f "$target/server/src/app.mjs" ] && [ -f "$target/compose.yml" ]
[ -f "$stage/app.mjs" ] && [ -f "$stage/default-group.mjs" ]
if find "$stage" -type l | grep -q .; then echo 'Symlinks refused' >&2; exit 1; fi
umask 077
backup="$target/backups/community-$(date -u +%Y%m%dT%H%M%SZ)"
[ ! -e "$backup" ]
mkdir -p "$backup"
cp -a "$target/server/src/app.mjs" "$backup/app.mjs"
if [ -f "$target/server/src/default-group.mjs" ]; then cp -a "$target/server/src/default-group.mjs" "$backup/default-group.mjs"; fi
docker ps --format '{{.ID}} {{.Names}}' | sort > "$backup/containers.before"
find /etc/nginx -type f -exec sha256sum {} \; | sort > "$backup/nginx.before"
sha256sum "$target/.env" "$target/compose.yml" "$target/nginx.conf" > "$backup/runtime.before"
old_image=$(docker inspect --format '{{.Image}}' sarab-platform-api-1)
printf '%s\n' "$old_image" > "$backup/api-image"
old_tag=$(docker inspect --format '{{.Config.Image}}' sarab-platform-api-1)
# Online consistent snapshot of this project's SQLite only; credentials stay local.
python3 - "$target/data/sarab.sqlite" "$backup/sarab.sqlite" <<'PY'
import sqlite3, sys
source = sqlite3.connect('file:' + sys.argv[1] + '?mode=ro', uri=True)
destination = sqlite3.connect(sys.argv[2])
with destination:
    source.backup(destination)
destination.close()
source.close()
PY
rollback() {
  rollback_needed=false
  cp -a "$backup/app.mjs" "$target/server/src/app.mjs"
  if [ -f "$backup/default-group.mjs" ]; then cp -a "$backup/default-group.mjs" "$target/server/src/default-group.mjs"; fi
  docker tag "$old_image" "$old_tag"
  docker compose -f "$target/compose.yml" up -d --no-deps --no-build api
  docker compose -f "$target/compose.yml" exec -T edge nginx -s reload
  echo "Restored own API image/source. Backup: $backup" >&2
}
rollback_needed=true
trap 'result=$?; if [ "$result" -ne 0 ] && [ "$rollback_needed" = true ]; then trap - 0; rollback || true; fi' 0
install -m0644 "$stage/app.mjs" "$target/server/src/app.mjs"
install -m0644 "$stage/default-group.mjs" "$target/server/src/default-group.mjs"
if ! docker compose -f "$target/compose.yml" build api; then rollback; exit 1; fi
if ! docker compose -f "$target/compose.yml" up -d --no-deps api; then rollback; exit 1; fi
ready=false
for i in $(seq 1 30); do
  if curl --fail --silent --connect-timeout 2 --max-time 4 http://127.0.0.1:9087/api/v1/health > /dev/null; then ready=true; break; fi
  sleep 1
done
if [ "$ready" != true ]; then rollback; exit 1; fi
# Only own edge may cache an old container address. Host nginx never reloads.
docker compose -f "$target/compose.yml" exec -T edge nginx -t
docker compose -f "$target/compose.yml" exec -T edge nginx -s reload
curl --fail --silent --connect-timeout 3 --max-time 8 --retry 2 https://sm.hsaie.com/api/v1/health > /dev/null
curl --fail --silent --connect-timeout 3 --max-time 8 --retry 2 https://167.86.106.163:9443/api/v1/health > /dev/null
docker ps --format '{{.ID}} {{.Names}}' | sort > "$backup/containers.after"
find /etc/nginx -type f -exec sha256sum {} \; | sort > "$backup/nginx.after"
sha256sum "$target/.env" "$target/compose.yml" "$target/nginx.conf" > "$backup/runtime.after"
grep -v ' sarab-platform-api-1$' "$backup/containers.before" > "$backup/others.before"
grep -v ' sarab-platform-api-1$' "$backup/containers.after" > "$backup/others.after"
cmp "$backup/others.before" "$backup/others.after"
cmp "$backup/nginx.before" "$backup/nginx.after"
cmp "$backup/runtime.before" "$backup/runtime.after"
rollback_needed=false
trap - 0
echo "Default-group release complete. Other containers and all nginx/runtime configuration unchanged. Backup: $backup"
