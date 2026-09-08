# Isolated deployment

Production: **https://sm.hsaie.com** (standard HTTPS port443). Trusted IP compatibility remains at `https://167.86.106.163:9443`; the domain also works on9443. Docker Compose project `sarab-platform`, files under `/opt/sarab-platform`.

The `api` container is limited to 256 MiB/0.5 CPU; `edge` to 96 MiB/0.25 CPU. Neither joins existing service networks or mounts existing application data. The API publishes **127.0.0.1:9087 only**, for a dedicated host nginx virtual host; edge publishes9443 for compatibility. Private `.env` and SQLite files are not included in deployment archives or Git. Server and web assets are copied from `platform/server/{src,package.json,Dockerfile}` and `platform/web/dist`.

`prepare.sh` records the original IP deployment preparation and refuses an existing target or occupied port. `issue-ip.sh` resumes IP certificate issuance. The domain extension is handled by `issue-domain.sh` and `enable-domain.sh`; use these scoped scripts only on the configured server. Both certificates use the existing ACME route at `/var/www/certbot/.well-known/acme-challenge`. Certificate files, ACME account/config/logs, renewal service and timer are private to Sarab.

Certbot and nginx images are pinned to the digests verified during deployment. The maintenance profile never starts with the website's ordinary `up`; the dedicated timer explicitly invokes renewal. The timer checks every12h because public IP certificates have a short lifetime. `refresh-tls.sh` uses only Sarab's private Certbot configuration and validates lifetime, hostname/IP and certificate/key correspondence. It copies changed `sarab-ip` and `sarab-domain` certificates, validates nginx configuration, then reloads the isolated edge. **Host nginx receives a graceful reload only when Sarab's domain certificate changed** and its dedicated vhost exists. Unchanged certificates cause no nginx reload. Validation/reload failure restores previous copies. The host Certbot binary and other certificate directories are never used.

## Standard-port domain routing

The only new host configuration is `/etc/nginx/conf.d/sm.hsaie.com.conf`, provided by `sm.hsaie.com.conf`. It answers this exact domain on80/443, retains ACME HTTP validation, redirects other HTTP requests to HTTPS, and proxies to `http://127.0.0.1:9087`. It sets `X-Forwarded-For` to the real remote address and `X-Forwarded-Proto` to HTTPS. Client-supplied forwarding headers cannot override those values. Other virtual-host files remain byte-identical; nginx is validated and gracefully reloaded, never restarted.

The isolated edge also has separate SNI blocks on its original9443 port. Its default certificate remains `tls/{fullchain,privkey}.pem` for `167.86.106.163`; `sm.hsaie.com` uses `tls/domain/` with a separate `sarab-domain` certificate. Both serve the same private API. Domain clients require no custom port or certificate exception.

`issue-domain.sh` checks the existing HTTP ACME route with one uniquely named temporary file, requests `sarab-domain` via Sarab's maintenance container and calls `refresh-tls.sh --copy-only`. `enable-domain.sh` is the guarded one-time extension used for this deployment: it requires saved baseline/rollback files, refuses an occupied9087 or an existing domain vhost, recreates only the Sarab API for loopback publication, and validates both configurations before graceful reload. The bind-mounted edge config is updated in place to preserve its inode. The existing `sarab-tls-refresh.timer` invokes the updated refresh script for both certificates.

For a scoped code update, copy the changed server or web bundle into this directory, then:

```bash
sudo docker compose -f /opt/sarab-platform/compose.yml up -d --build --no-deps api
sudo docker compose -f /opt/sarab-platform/compose.yml exec -T edge nginx -s reload
curl --fail https://167.86.106.163:9443/api/v1/health
curl --fail https://sm.hsaie.com/api/v1/health
```

Reloading only Sarab's edge refreshes its upstream if recreating the API changed its Docker address. The domain's host proxy keeps its stable loopback destination; ordinary application updates do not require host nginx reload. A rollback should restore only a known compatible Sarab source/image and its own data snapshot. Never restart Docker/host nginx, run global prune, delete volumes, or run compose commands from another project.

`probe-gemini.mjs` makes one synthetic navigation request using a provided private environment file without logging key values. Actual live proof was also performed inside the deployed API container. `GEMINI_API_KEY` takes precedence over optional `OPENAI_API_KEY`; see the server README. Provider health means configured; actual generation was separately verified.

Sources: [Let's Encrypt IP certificate instructions](https://letsencrypt.org/2026/03/11/shorter-certs-certbot), [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output), [Wear Data Layer](https://developer.android.com/training/wearables/data/overview), [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

TLS references: [NGINX SNI and HTTPS servers](https://nginx.org/en/docs/http/configuring_https_servers.html), [Certbot certificate names and renewal](https://eff-certbot.readthedocs.io/en/stable/using.html).
