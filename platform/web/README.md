# Sarab authenticated dashboard

React + TypeScript + Vite and Leaflet. Production API requests use same-origin `/api/v1`. The backend serves `dist`; no public device/location endpoints are used. Arabic fonts are bundled locally. OpenStreetMap tiles use `https://tile.openstreetmap.org` and visible attribution.

```powershell
npm ci
npm test
npm run build
npm run dev -- --port 5173
```

Development proxies `/api` to `http://127.0.0.1:8080`. Production must serve over HTTPS. Configure backend `WEB_ROOT` to the built `platform/web/dist` directory. Never place backend/provider secrets in Vite environment variables or web source.

Dashboard session token is sessionStorage-only and cleared on sign-out/401. Polling cancels on unmount and never overlaps. Initial setup: log in with server-provisioned administrator credentials, create a group, generate an enrollment code, enroll the real phone app, and enable location sharing explicitly on that device. No demo data is shipped.

Verification: unit tests cover consent, invalid coordinates, stale devices and combined filters. Physical device telemetry, alert vibration and acknowledgements need device validation. A configured AI indicator denotes server configuration, not proof that a remote model call succeeded.
