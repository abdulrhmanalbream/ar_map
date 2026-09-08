# Domain extension validation — 2026-09-08

Primary address: **https://sm.hsaie.com**. Compatibility addresses: `https://167.86.106.163:9443` and `https://sm.hsaie.com:9443`.

- Public Chrome authenticated login, dashboard polling, local font/assets loading, service view, mobile layout and logout passed. Certificate errors were never bypassed. Browser checks reported zero CSP violations, zero page errors, zero failed same-origin requests and no horizontal mobile overflow.
- All three HTTPS addresses returned200 for `/api/v1/health`, with `status=ok`, `aiConfigured=true`, `aiProvider=gemini`, `version=3.0`.
- Separate `sarab-domain` certificate: Let's Encrypt YE1, SAN `sm.hsaie.com`, validity2026-09-08 to2026-12-07. Existing `sarab-ip` certificate remained Let's Encrypt YE2, SAN `167.86.106.163`, validity2026-09-08 to2026-09-15.
- New host file: `/etc/nginx/conf.d/sm.hsaie.com.conf`. SHA-256 checks of every pre-existing `/etc/nginx` regular file passed against the saved baseline. The pre-existing protocol-options warnings were present before the change; syntax validation succeeded before the graceful reload.
- Host nginx master PID2959480 and service activation2026-09-02 remained unchanged. No host nginx restart occurred.
- Sarab API alone was recreated to publish127.0.0.1:9087. All15 other container IDs, including Sarab's edge, remained unchanged. The loopback listener was verified with `ss`.
- The real private Certbot renewal check completed with both copied certificates unchanged and no nginx reload. `sarab-tls-refresh.timer` remained active. The updated renewal script validates hostname/IP, expiration and matching private key; host nginx is reloaded only when the separate Sarab domain certificate changes.
- `ACCESS.private.txt` was updated by replacing only its URL bytes; username/password bytes were preserved and never printed. Browser verification created only temporary authentication sessions and logged them out; it did not create groups, members, telemetry or alerts.

Server baseline files are under `/home/sysadmin/sarab-domain-stage-20260908/`. Browser screenshots are ignored local QA artifacts under `platform/web/playwright-results/production/`; no credentials are stored in deployment source.
