#!/usr/bin/env bash
set -euo pipefail
root=/opt/sarab-platform
stage=/home/sysadmin/sarab-domain-stage-20260908
host_conf=/etc/nginx/conf.d/sm.hsaie.com.conf
test "$(realpath "$root")" = /opt/sarab-platform
test "$(realpath "$stage")" = /home/sysadmin/sarab-domain-stage-20260908
test ! -e "$host_conf"
test -f "$root/tls/domain/fullchain.pem"
test -f "$stage/nginx-before.sha256"
test -f "$root/compose.before-domain.yml"
test -f "$root/nginx.before-domain.conf"
if ss -ltn | awk '{print $4}' | grep -qE ':9087$'; then echo 'Loopback port9087 is already occupied' >&2; exit 1; fi
nginx -t
sha256sum --check --status "$stage/nginx-before.sha256"
compose=(docker compose -p sarab-platform -f "$root/compose.yml")

install -m 644 "$stage/compose.yml" "$root/compose.yml"
"${compose[@]}" up -d --no-deps api
ready=false
for attempt in $(seq 1 30); do
    if curl --fail --silent --max-time 2 http://127.0.0.1:9087/api/v1/health >/dev/null; then ready=true; break; fi
    sleep 1
done
if ! "$ready"; then
    install -m 644 "$root/compose.before-domain.yml" "$root/compose.yml"
    "${compose[@]}" up -d --no-deps api
    "${compose[@]}" exec -T edge nginx -s reload || true
    echo 'Sarab loopback API failed readiness; previous compose restored.' >&2
    exit 1
fi

# Preserve the inode of the bind-mounted edge configuration.
cat "$stage/nginx.conf" >"$root/nginx.conf"
if ! "${compose[@]}" exec -T edge nginx -t; then
    cat "$root/nginx.before-domain.conf" >"$root/nginx.conf"
    "${compose[@]}" exec -T edge nginx -s reload
    exit 1
fi
"${compose[@]}" exec -T edge nginx -s reload
install -m 644 "$stage/sm.hsaie.com.conf" "$host_conf"
if ! nginx -t; then rm -f -- "$host_conf"; exit 1; fi
sha256sum --check --status "$stage/nginx-before.sha256"
if ! systemctl reload nginx; then
    rm -f -- "$host_conf"
    nginx -t && systemctl reload nginx
    exit 1
fi
# Graceful reload returns before every new worker has begun accepting TLS handshakes.
domain_ready=false
for attempt in $(seq 1 10); do
    if curl --fail --silent --max-time 3 https://sm.hsaie.com/api/v1/health; then domain_ready=true; break; fi
    sleep 1
done
if ! "$domain_ready"; then echo 'Domain did not become healthy after reload.' >&2; exit 1; fi
printf '\n'
curl --fail --silent --show-error --max-time 15 https://167.86.106.163:9443/api/v1/health
printf '\n'
echo 'Dedicated Sarab domain enabled; existing nginx files match their baseline.'
