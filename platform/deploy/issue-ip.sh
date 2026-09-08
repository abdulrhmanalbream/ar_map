#!/usr/bin/env bash
set -euo pipefail
target=/opt/sarab-platform
test "$(realpath "$target")" = /opt/sarab-platform
test -f "$target/compose.yml"
install -d -m 755 /var/www/certbot/.well-known/acme-challenge
cd "$target"
docker compose --profile maintenance run --rm --no-deps acme certonly --non-interactive --agree-tos \
  --register-unsafely-without-email --preferred-profile shortlived --webroot \
  --webroot-path /var/www/certbot --ip-address 167.86.106.163 --cert-name sarab-ip
install -m 644 "$target/letsencrypt/live/sarab-ip/fullchain.pem" "$target/tls/fullchain.pem"
install -m 600 "$target/letsencrypt/live/sarab-ip/privkey.pem" "$target/tls/privkey.pem"
openssl x509 -in "$target/tls/fullchain.pem" -noout -dates -ext subjectAltName
