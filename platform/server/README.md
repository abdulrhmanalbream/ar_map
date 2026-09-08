# المطوف الذكي server

The server implements `../docs/CONTRACT.md` using Node HTTP, `node:sqlite`, and built-in cryptography. There are **no runtime npm dependencies**. Production runtime: Node 24.14 or later. Local tests also run on Node 22.17, which prints an experimental SQLite warning.

## Run

Set these environment variables through the deployment secret mechanism, then run `node src/index.mjs` from this directory:

| Variable | Meaning |
| --- | --- |
| `PORT` | Listener port; default `8080`. Bind an isolated new host port when deploying. |
| `DATA_DIR` | Private persistent SQLite directory; default `/data`. Must be writable by the Node user. |
| `ADMIN_USERNAME` | Required on an empty database; 3–80 letters, digits or `._@-`. |
| `ADMIN_PASSWORD` | Required on an empty database; 12–256 characters. Common defaults are refused. Supply a generated secret. |
| `GEMINI_API_KEY` | Preferred server-only provider secret. Missing or failed provider uses a disclosed limited local fallback. |
| `GEMINI_MODEL` | Default `gemini-3.8-flash`, stable as of September 2026. |
| `OPENAI_API_KEY` | Optional alternative when no Gemini key is configured. |
| `OPENAI_MODEL` | Default `gpt-4.1-mini`; do not expose this setting as a client request field. |
| `WEB_ROOT` | Absolute directory containing the built dashboard's `index.html` and assets. |
| `TRUST_PROXY` | Default false. Enable only behind a controlled proxy that overwrites forwarded headers and blocks direct listener access. |

Credentials are never generated with a common default or printed in startup logs. Bootstrap creates one admin with scrypt (`N=32768,r=8,p=1`, random salt). Existing databases are not reset and existing admin passwords are **not changed by restarting with different bootstrap variables**. Preserve `DATA_DIR` across upgrades. Database schema creation is additive; a newer schema version is rejected rather than downgraded.

The Dockerfile uses this directory as build context. Mount the dashboard build read-only at `/app/web` and persistent data at `/data` (container user UID 1000), provide secrets at runtime, and publish only the root agent's chosen isolated port. A typical resource ceiling is 256 MiB RAM / one CPU; there are at most two concurrent password derivations and two concurrent AI calls. No existing service needs to be stopped. The health check is `GET /api/v1/health` and contains no secrets. `aiConfigured` reports whether either key is supplied, **not whether a live provider request has succeeded**. `aiProvider` reports `gemini`, `openai`, or `local`.

## API behavior

All API errors are `{ "error": "Readable message", "code": "STABLE_CODE" }`. Successful shapes and routes match the contract. Protected responses use `Cache-Control: no-store`. Bearer tokens contain 256 random bits and only SHA-256 digests are stored. Admin sessions expire after 12 hours, device sessions after 90 days; re-enroll an expired/revoked device. Logout revokes its session and also clears shared location for device callers.

Admin groups are scoped to their creator; another admin cannot access them. Devices can access only their own profile, telemetry, assistant, group alerts, and assigned delivery rows. Device clients never receive another device's coordinates or member list. Enrollment codes contain 10 random unambiguous characters, expire after 24 hours, and allow at most 20 enrollments. Device names need not be unique. Enrollment is transactional so concurrent requests cannot exceed its use limit.

Additions to the v1 contract:

- `GET /devices` includes `stale`, `locationStale`, and `reportedStatus`. `status` becomes `offline` after 90 seconds without telemetry; `reportedStatus` retains the submitted `active`, `paused`, or `needs_help` value. A shared location becomes stale after 120 seconds. All coordinates include capture time and reported accuracy; none is treated as an exact live fix.
- `DELETE /devices/:id` is admin-only, revokes that device's tokens and immediately clears its location. Existing alert delivery evidence remains available.
- Alerts include `expiresAt`, `adminAcknowledgedAt`, and `adminAcknowledgedBy`. `POST /alerts/:id/ack` records the guide's receipt of a help request idempotently. It does not impersonate a device acknowledgment or clear the device's help state.
- An admin can provide `Idempotency-Key` on alert creation. Device alerts require `clientId`. Repeating the same request returns the original ID; reusing a key for different content returns 409.
- Admin alert listing returns newest 250 by default (`?limit=1..500`). It includes expired alerts for audit. The database retains alerts and recipient receipts across restarts.
- Lap counters accept the watch bridge's optional `sessionId`, `revision`, and `startedAt` fields together. A lower/equal revision from the same session or an older session cannot overwrite a newer counter; an explicit undo with a higher revision is accepted. Legacy counters without these metadata fields are accepted.

Device-origin help/regroup alerts go to active enrolled devices in that same group except the source, and always appear in the group's admin dashboard. Admin-created alerts target the supplied devices or all active devices in the group. A single-device group still produces an admin-visible help alert. Device GET polls return only assigned, unacknowledged alerts created within the last 24 hours, at most 100 per poll, oldest first. Polling does **not** mark delivery. Clients explicitly call `/delivered` after receiving/forwarding and `/ack` only after a person's action. An acknowledgment also implies delivery. Repeated delivery/ack calls preserve the original timestamp. Existing receipts can be acknowledged after expiry, but expired alerts never reappear in polling.

## Privacy and validation

Location sharing starts disabled. Only telemetry carrying an explicit `sharingEnabled:true` can store coordinates. Disabled sharing discards even an accidentally included location. `/device/privacy` with `sharingEnabled:false` immediately clears the only stored location. Explicit `location:null` clears a previously shared fix. Out-of-order valid fixes cannot replace a newer fix, and fixes more than 60 seconds in the future are rejected. The latest location is purged after 24 hours, including while the device is offline. A one-minute maintenance sweep removes expired sessions/codes and obsolete fixes; API reads hide expired fixes immediately.

There is no location history table, request-body log, camera/audio ingestion, AI transcript storage, or plaintext token storage. SQLite uses `secure_delete=ON` and DELETE journaling instead of persistent WAL history. Backups outside this application must honor the same location retention and access rules. The dashboard is same-origin; no permissive CORS is enabled. Security headers restrict scripts and frames, while allowing the dashboard's OpenStreetMap tiles and bundled fonts. Static file paths, extensions, and resolved symlinks are bounded to `WEB_ROOT`.

JSON requests are capped at 64 KiB, URLs at 2048 characters. Schemas reject unknown identity fields, invalid coordinate ranges, unsupported alert types, non-finite numeric values, and extra assistant roles. Login is limited per source and username; enrollment per source; authenticated API, alerts, and assistant per identity. Forwarded client IPs are ignored unless explicitly configured.

Assistant input allows 150 destinations, each with an ID up to 120 characters, name up to 160 characters, and up to ten aliases of 120 characters each. Message and each of up to twelve history entries allow 5000 characters. These are individual limits; the total UTF-8 JSON must still fit 64 KiB, so clients should trim older history and low-priority aliases to fit.

## Assistant

Gemini is selected when its key is configured. The request uses `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, a server-only `x-goog-api-key` header, low thinking, a bounded output, a 12-second timeout, and JSON `responseFormat.text.schema`. Only a complete, unblocked result is accepted. No tools, provider-side conversation IDs, or explicit caching are used. If only OpenAI is configured, the request uses `POST https://api.openai.com/v1/responses`, `store:false`, and strict `text.format` JSON schema with the same timeout and validation. A selected provider failure goes to the disclosed local fallback, without silently forwarding the request to another provider.

The allowed destination IDs are enumerated in the schema and validated again after parsing. Every navigation result requires user confirmation; the server never executes navigation or sends an alert from model output. It does not invent route geometry or declare ritual completion. Neither provider key is returned to the client or written to logs.

When no key is configured, the provider fails/refuses/times out, or output is invalid, `provider:"local"` is returned with explicit availability disclosure. This limited fallback matches an unambiguous listed destination only for navigation intent, or reads the supplied manual/estimated lap counter. It supports Arabic, English, Urdu, Indonesian, and Turkish wording. Other valid language tags are accepted; fallback wording is English when not translated. Provider mode can respond in any requested language tag. It is not represented as a successful general AI response. The server does not add device coordinates, device identity, or watch session metadata to provider requests; only the bounded message/history, destination names/IDs, optional distances, and supplied counter/context are sent. Users' own message text is forwarded as supplied.

Official references checked for implementation: [Gemini structured outputs](https://ai.google.dev/gemini-api/docs/generate-content/structured-output), [Gemini REST format enum](https://ai.google.dev/api/generate-content#TextResponseFormat), [stable Gemini 3.8 Flash](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash), [Responses structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs), and [GPT-4.1 mini support](https://developers.openai.com/api/docs/models/gpt-4.1-mini). REST uses the `APPLICATION_JSON` enum, not the `application/json` string shown in the high-level guide. Unit tests mock provider responses. A live synthetic request from the isolated deployment container on September 8, 2026 returned HTTP 200 and a validated `provider:"gemini"` navigation proposal in approximately 2.4 seconds; no participant data was used.

## Tests and local visual verification

Run `npm test`. The tests cover credential bootstrap, role and owner boundaries, enrollment expiry/use count, privacy clearing/stale positions, idempotent alerts, explicit delivery vs acknowledgment, revocation, input/body limits, static path confinement, restart persistence, multilingual limited fallback, strict action validation, and malformed/refused/incomplete AI results.

For an explicitly temporary local dashboard integration test only, set `SARAB_UI_TEST=1` then run `node test/ui-server.mjs`. It binds `127.0.0.1:8080`, uses a new temporary database, and prints only the path to generated test credentials. The password is never printed. It loads the sibling `web/dist` build. SIGINT/SIGTERM closes and removes that temporary database. This helper lives under `test/` and is absent from the production Docker image.
