#!/bin/sh
# Scoped3.1 server release. Does not touch other projects, data, env or TLS.
set -eu
stage=$(realpath "${1:?Pass extracted vision release directory}")
case "$stage" in /home/sysadmin/sm-vision-*/release) ;; *) echo 'Invalid stage' >&2; exit 1 ;; esac
target=/opt/sarab-platform
[ "$(realpath "$target")" = "$target" ]
[ -f "$target/server/src/app.mjs" ] && [ -f "$target/compose.yml" ]
[ -f "$stage/server/src/vision.mjs" ] && [ -f "$stage/sm.hsaie.com.conf" ]
if find "$stage" -type l | grep -q .; then echo 'Symlinks refused' >&2; exit 1; fi
backup="$target/backups/vision-$(date -u +%Y%m%dT%H%M%SZ)"
[ ! -e "$backup" ]
mkdir -p "$backup"
cp -a "$target/server" "$backup/server"
cp -a "$target/nginx.conf" "$backup/nginx.conf"
cp -a /etc/nginx/conf.d/sm.hsaie.com.conf "$backup/sm.hsaie.com.conf"
docker ps --format '{{.ID}} {{.Names}}' | sort > "$backup/containers.before"
find /etc/nginx -type f ! -path /etc/nginx/conf.d/sm.hsaie.com.conf -exec sha256sum {} \; | sort > "$backup/nginx.before"
old_image=$(docker inspect --format '{{.Image}}' sarab-platform-api-1)
printf '%s\n' "$old_image" > "$backup/api-image"
cp -a "$stage/server/." "$target/server/"
docker compose -f "$target/compose.yml" build api
docker compose -f "$target/compose.yml" up -d --no-deps api
ready=false
for i in $(seq 1 30); do
    if curl --fail --silent http://127.0.0.1:9087/api/v1/health > /dev/null; then ready=true; break; fi
    sleep 1
done
[ "$ready" = true ] || { echo "Own API unhealthy; prior image recorded at $backup/api-image" >&2; exit 1; }
# Preserve the inode of Docker's bind-mounted edge config.
cat "$stage/nginx.conf" > "$target/nginx.conf"
install -m0644 "$stage/sm.hsaie.com.conf" /etc/nginx/conf.d/sm.hsaie.com.conf
if ! nginx -t || ! docker compose -f "$target/compose.yml" exec -T edge nginx -t; then
    cat "$backup/nginx.conf" > "$target/nginx.conf"
    cat "$backup/sm.hsaie.com.conf" > /etc/nginx/conf.d/sm.hsaie.com.conf
    echo "Proxy config restored after validation failure. Backup: $backup" >&2
    exit 1
fi
docker compose -f "$target/compose.yml" exec -T edge nginx -s reload
# The host reload is needed once for this domain's two bounded vision routes.
nginx -s reload
curl --fail --silent https://sm.hsaie.com/api/v1/health
printf '\n'
docker ps --format '{{.ID}} {{.Names}}' | sort > "$backup/containers.after"
find /etc/nginx -type f ! -path /etc/nginx/conf.d/sm.hsaie.com.conf -exec sha256sum {} \; | sort > "$backup/nginx.after"
grep -v ' sarab-platform-api-1$' "$backup/containers.before" > "$backup/others.before"
grep -v ' sarab-platform-api-1$' "$backup/containers.after" > "$backup/others.after"
cmp "$backup/others.before" "$backup/others.after"
cmp "$backup/nginx.before" "$backup/nginx.after"
echo "Scoped API release complete. Other container IDs and nginx files unchanged. Backup: $backup"
