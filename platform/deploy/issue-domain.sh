#!/usr/bin/env bash
set -euo pipefail
root=/opt/sarab-platform
challenge=/var/www/certbot/.well-known/acme-challenge
test "$(realpath "$root")" = /opt/sarab-platform
test "$(realpath "$root/letsencrypt")" = /opt/sarab-platform/letsencrypt
test "$(realpath "$challenge")" = /var/www/certbot/.well-known/acme-challenge
test -f "$root/compose.yml"
test -x "$root/refresh-tls.sh"
exec 9>"$root/.tls-maintenance.lock"
flock -w 300 9

# Check the existing ACME route with one unique temporary file; no host config changes.
probe=$(mktemp "$challenge/sarab-domain-probe.XXXXXXXX")
cleanup() { test -z "${probe:-}" || rm -f -- "$probe"; }
trap cleanup EXIT
nonce=$(openssl rand -hex 16)
printf '%s' "$nonce" >"$probe"
chmod 644 "$probe"
received=$(curl --fail --silent --show-error --max-time 20 "http://sm.hsaie.com/.well-known/acme-challenge/$(basename "$probe")")
test "$received" = "$nonce"
cleanup
probe=

docker compose -p sarab-platform -f "$root/compose.yml" --profile maintenance run --rm --no-deps acme \
    certonly --non-interactive --agree-tos --register-unsafely-without-email \
    --webroot --webroot-path /var/www/certbot --domain sm.hsaie.com --cert-name sarab-domain \
    --keep-until-expiring
openssl x509 -in "$root/letsencrypt/live/sarab-domain/fullchain.pem" -noout -checkhost sm.hsaie.com
flock -u 9
"$root/refresh-tls.sh" --copy-only
openssl x509 -in "$root/tls/domain/fullchain.pem" -noout -dates -ext subjectAltName
echo 'Sarab domain certificate is ready; validate/reload only the isolated edge after installing its SNI config.'
