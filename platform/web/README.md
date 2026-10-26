# المطوف الذكي website, public dashboard and administrator workspace

React + TypeScript + Vite and Leaflet. Public `/` explains the religious companion through an illustrated, interactive local demo. `/dashboard` opens real anonymous general-group aggregates without sign-in, fetching only `/api/v1/public/dashboard`. Administrator sign-in and the original complete workspace live at `/dashboard/admin`. The three routes are lazy-loaded separately: the landing never mounts polling or requests `/api`, and neither public route reads/transmits administrator sessions or requests device permissions/Gemini. The backend serves `dist`; no public device/location listing is provided. Arabic fonts are bundled locally. Administrator OpenStreetMap tiles use `https://tile.openstreetmap.org` and visible attribution.

```powershell
npm ci
npm test
npm run build
npm run dev -- --port 5173
```

Development proxies `/api` to `http://127.0.0.1:8080`. Production must serve over HTTPS. Configure backend `WEB_ROOT` to the built `platform/web/dist` directory. Never place backend/provider secrets in Vite environment variables or web source.

Dashboard session token is sessionStorage-only and cleared on sign-out/401. Polling cancels on unmount and never overlaps. The phone can join the default public group by name, or a private group using an administrator-issued invitation code. Location sharing stays explicitly opt-in on that device. The dashboard contains only real records; the public demo is prominently labelled as an illustration, uses prepared answers and independent manual counters, and never creates server records.

Verification: unit tests cover consent, invalid coordinates, stale devices and combined filters. Physical device telemetry, alert vibration and acknowledgements need device validation. A configured AI indicator denotes server configuration, not proof that a remote model call succeeded.

## Public landing verification

`node scripts/landing-check.mjs` uses `LANDING_URL` (default `http://127.0.0.1:5173`) and optional `PLAYWRIGHT_MODULE`. It exercises all four demo steps, languages, prepared questions, separate 0–7 counters, keyboard controls, motion preferences and mobile/desktop widths. It blocks and fails on any API request, including with a sentinel saved dashboard session, and checks that camera/microphone/location are never requested. Screenshots and fieldnotes are written under ignored `playwright-results/landing/`. Start Vite directly if npm on Windows consumes forwarded options: `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173`.

`node scripts/guest-dashboard-check.mjs` uses `GUEST_DASHBOARD_URL` and `PLAYWRIGHT_MODULE` to verify anonymous aggregate loading, polling, retry, small screens, session isolation, the exact public response allowlist and protected administrator routes. Error and distinct-count fixtures are injected only in the browser, without server mutations. Reports and screenshots are saved under `playwright-results/guest-dashboard/`. Published behavior and deployment evidence: [public dashboard guide](../../docs/PUBLIC_DASHBOARD_AR.md).

## Browser integration smoke

`scripts/smoke.mjs` uses Playwright against a real isolated backend/SQLite database and creates records explicitly named as integration tests. Set `SARAB_ISOLATED_TEST=1`, `SARAB_TEST_CREDENTIALS` to a local JSON file containing test-only `username`/`password`, and optionally `SARAB_BASE_URL`. Set `PLAYWRIGHT_MODULE` to an installed Playwright `index.mjs` when it is not locally resolvable; `PLAYWRIGHT_CHANNEL=chrome` uses installed Chrome. Run `node scripts/smoke.mjs`. Never point this harness at production. Credentials are read from disk and never printed.

The smoke verifies login, group creation and enrollment, real API telemetry on the map, member detail, targeted sending, delivery and member acknowledgement, administrator help acknowledgement, consent withdrawal, mobile overflow and logout. Screenshots go to ignored `playwright-results/`.
