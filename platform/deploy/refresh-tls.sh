#!/usr/bin/env bash
set -euo pipefail
# Sarab's ACME state/copies stay private. Host nginx is gracefully reloaded only for its domain cert.
root=/opt/sarab-platform
target_dir=$root/tls
test "$(realpath "$root")" = /opt/sarab-platform
test "$(realpath "$target_dir")" = /opt/sarab-platform/tls
test "$(realpath "$root/letsencrypt")" = /opt/sarab-platform/letsencrypt
compose=(docker compose -p sarab-platform -f "$root/compose.yml")
exec 9>"$root/.tls-maintenance.lock"
flock -w 300 9
case "${1:-}" in
  '') "${compose[@]}" --profile maintenance run --rm --no-deps acme renew --quiet ;;
  --copy-only) ;; # Used after issuance; does not request another renewal.
  *) echo 'Usage: refresh-tls.sh [--copy-only]' >&2; exit 2 ;;
esac

stage=$(mktemp -d "$target_dir/.refresh.XXXXXXXX")
cleanup() {
    case "$stage" in /opt/sarab-platform/tls/.refresh.*) ;; *) return 1 ;; esac
    rm -f -- "$stage"/*.pem
    rmdir -- "$stage"
}
trap cleanup EXIT
names=(sarab-ip)
if test -d "$root/letsencrypt/live/sarab-domain" || test -d "$target_dir/domain"; then names+=(sarab-domain); fi
changed=()
declare -A destinations existed

for name in "${names[@]}"; do
    source_dir=$root/letsencrypt/live/$name
    for file in fullchain.pem privkey.pem; do
        resolved=$(realpath "$source_dir/$file")
        case "$resolved" in "$root/letsencrypt/archive/$name/"*) ;; *) echo 'Unexpected certificate source path' >&2; exit 1 ;; esac
    done
    openssl x509 -in "$source_dir/fullchain.pem" -noout -checkend 86400 >/dev/null
    if test "$name" = sarab-ip; then
        openssl x509 -in "$source_dir/fullchain.pem" -noout -checkip 167.86.106.163 >/dev/null
        destination=$target_dir
    else
        openssl x509 -in "$source_dir/fullchain.pem" -noout -checkhost sm.hsaie.com >/dev/null
        destination=$target_dir/domain
        test ! -L "$destination"
        install -d -m 700 "$destination"
        test "$(realpath "$destination")" = /opt/sarab-platform/tls/domain
    fi
    cert_public=$(openssl x509 -in "$source_dir/fullchain.pem" -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256)
    key_public=$(openssl pkey -in "$source_dir/privkey.pem" -pubout -outform DER | openssl dgst -sha256)
    test "$cert_public" = "$key_public"
    destinations[$name]=$destination
    if cmp -s "$source_dir/fullchain.pem" "$destination/fullchain.pem" && cmp -s "$source_dir/privkey.pem" "$destination/privkey.pem"; then continue; fi
    changed+=("$name")
    for file in fullchain.pem privkey.pem; do
        test ! -L "$destination/$file"
        if test -f "$destination/$file"; then
            install -m 600 "$destination/$file" "$stage/$name.previous.$file"
            existed[$name.$file]=yes
        fi
        install -m 600 "$source_dir/$file" "$stage/$name.next.$file"
    done
done

if test "${#changed[@]}" = 0; then echo 'Sarab IP/domain TLS copies unchanged; no edge reload.'; exit 0; fi
rollback() {
    for name in "${changed[@]}"; do
        destination=${destinations[$name]}
        for file in fullchain.pem privkey.pem; do
            if test "${existed[$name.$file]:-}" = yes; then
                install -m 600 "$stage/$name.previous.$file" "$destination/$file"
            else
                rm -f -- "$destination/$file"
            fi
        done
        test ! -f "$destination/fullchain.pem" || chmod 644 "$destination/fullchain.pem"
    done
}
for name in "${changed[@]}"; do
    destination=${destinations[$name]}
    install -m 600 "$stage/$name.next.privkey.pem" "$destination/privkey.pem"
    install -m 644 "$stage/$name.next.fullchain.pem" "$destination/fullchain.pem"
done
host_domain_changed=false
for name in "${changed[@]}"; do
    if test "$name" = sarab-domain && test -f /etc/nginx/conf.d/sm.hsaie.com.conf; then host_domain_changed=true; fi
done
if ! "${compose[@]}" exec -T edge nginx -t; then rollback; exit 1; fi
if "$host_domain_changed" && ! nginx -t; then rollback; exit 1; fi
if ! "${compose[@]}" exec -T edge nginx -s reload; then
    rollback
    "${compose[@]}" exec -T edge nginx -s reload || true
    exit 1
fi
if "$host_domain_changed" && ! systemctl reload nginx; then
    rollback
    "${compose[@]}" exec -T edge nginx -s reload || true
    systemctl reload nginx || true
    exit 1
fi
printf 'Updated Sarab TLS copies and reloaded only its edge: %s\n' "${changed[*]}"
if "$host_domain_changed"; then echo 'Gracefully reloaded host nginx for the changed Sarab domain certificate.'; fi
