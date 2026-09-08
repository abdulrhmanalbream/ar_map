#!/usr/bin/env bash
set -euo pipefail
stage=/home/sysadmin/sarab-stage-20260908
target=/opt/sarab-platform
test ! -e "$target"
test -f "$stage/compose.yml"
test -f "$stage/.env"
if ss -ltn | awk '{print $4}' | grep -qE ':9443$'; then echo 'Port 9443 is already in use'; exit 1; fi
install -d -m 750 "$target"
install -d -m 700 "$target/tls" "$target/letsencrypt" "$target/acme-work" "$target/acme-logs"
install -d -o 1000 -g 1000 -m 700 "$target/data"
install -m 600 "$stage/.env" "$target/.env"
install -m 644 "$stage/compose.yml" "$stage/nginx.conf" "$target/"
install -m 750 "$stage/refresh-tls.sh" "$target/refresh-tls.sh"
# Existing host nginx already serves this ACME path. Only unique challenge files are created.
install -d -m 755 /var/www/certbot/.well-known/acme-challenge
cd "$target"
docker compose --profile maintenance run --rm --no-deps acme certonly --non-interactive --agree-tos \
  --register-unsafely-without-email --preferred-profile shortlived --webroot \
  --webroot-path /var/www/certbot --ip-address 167.86.106.163 --cert-name sarab-ip
install -m 644 "$target/letsencrypt/live/sarab-ip/fullchain.pem" "$target/tls/fullchain.pem"
install -m 600 "$target/letsencrypt/live/sarab-ip/privkey.pem" "$target/tls/privkey.pem"
openssl x509 -in "$target/tls/fullchain.pem" -noout -dates -ext subjectAltName
echo 'Isolated Sarab directory and IP TLS certificate are ready.'
