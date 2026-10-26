#!/bin/sh
# Static-only update. Preserve the bind-mounted directory inode and old asset URLs.
set -eu

stage=${1:?Pass the extracted web-only release directory}
target=/opt/sarab-platform/web/dist
stage=$(realpath "$stage")
case "$stage" in
  /home/sysadmin/sm-hedayah-web-*/dist) ;;
  *) echo 'Refusing a release outside the scoped staging directory' >&2; exit 1 ;;
esac
[ "$(realpath "$target")" = "$target" ] || { echo 'Unexpected web root' >&2; exit 1; }
[ -f "$stage/index.html" ] && [ -d "$stage/assets" ]
[ -f "$target/index.html" ]
if find "$stage" -type l | grep -q .; then
  echo 'Release must contain regular assets, not symlinks' >&2
  exit 1
fi

backup=/opt/sarab-platform/backups/web-hedaya-$(date -u +%Y%m%dT%H%M%SZ)
[ ! -e "$backup" ]
mkdir -p "$backup"
cp -a "$target/." "$backup/"

# Upload dependencies before publishing the HTML that references them.
(cd "$stage" && tar --exclude='./index.html' -cf - .) | (cd "$target" && tar -xf -)
install -m 0644 "$stage/index.html" "$target/index.html.hedaya-next"
mv -f "$target/index.html.hedaya-next" "$target/index.html"
echo "Web assets updated; rollback HTML and assets retained at $backup"
# No Docker, service, nginx, environment, certificate or database mutation.
