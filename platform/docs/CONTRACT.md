# Smart Mutawwif platform contract v1 — additive vision and curated devotional APIs

Root repository: C:/Users/TechTroniX/Documents/GitHub/ar_map. Platform root is platform/. Existing Android camera/navigation stays intact. Server is deployed at https://sm.hsaie.com (HTTPS IP 167.86.106.163:9443 remains available) in an isolated stack. A dedicated host virtual host routes the domain to a loopback-only API port; existing virtual-host files are preserved and nginx configuration is validated before a graceful reload. Existing containers are not recreated. Root owns deployment and app integration. JSON API prefix /api/v1. ISO timestamps in UTC, coordinates only when user explicitly enables sharing. Latest location only, no location history. AI provider secrets server-only.

## Authentication
- POST /api/v1/auth/login {username,password} -> {token,user:{id,name,role}}. Dashboard holds token in sessionStorage, Bearer auth. Admin bootstrap credentials server env. Rate limit login.
- POST /api/v1/auth/logout (Bearer) -> 204.
- GET /api/v1/me -> {user:{id,name,role}}.
- Dashboard admin creates groups and short-lived enrollment codes. A device enrolls with code/name/language and receives independent device bearer token. No public user list or open telemetry.

## Groups and devices
- GET /api/v1/groups -> {groups:[{id,name,memberCount}]}
- POST /api/v1/groups {name} -> {group:{id,name,memberCount}}
- POST /api/v1/groups/:id/enrollment -> {code,expiresAt}; code 8+ random characters, 24h TTL, permit group enrollment max20 uses.
- POST /api/v1/devices/enroll {code,name,language} -> {token,device:{id,name,groupId},group:{id,name}}
- POST /api/v1/devices/enroll-default {name,language} -> the same enrollment response, without an invite. Joins the stable `smart-mutawwif-public` group owned by the configured administrator, named `مجموعة المطوف الذكي` (a suffixed name only if a pre-existing private group owns that name). Bootstrap creates it idempotently without moving existing members or making private groups public. An ambiguous/missing administrator mapping returns503 `DEFAULT_GROUP_UNAVAILABLE`. Both enrollment routes share the30 requests/15min/IP limit and5000 active devices/owner cap. Each new device receives its own hashed session; location sharing remains off. General-group help/regroup notifications reach that group's members and administrator. Member/location lists remain administrator-only.
- GET /api/v1/groups additionally returns optional `isDefault:true` for the general group, so administrators can distinguish enrollment without a code.
- GET /api/v1/public/dashboard is anonymous and read-only: `{updatedAt,group:{name,memberCount},counts:{active,offline,needsHelp,paused,sharing,eyeConnected,watchConnected},health:{status,aiConfigured,aiProvider}}`. It includes only non-revoked members of the configured general group, never identities, individual telemetry, coordinates, private groups, alert text, enrollment codes or tokens. Current active/paused/needsHelp require a server last-seen timestamp within90seconds; all others are offline. Hardware-connected counts include online devices only; sharing counts the enabled flag independently. Query parameters are rejected; unresolved default-group ownership returns503. GET is limited to180 requests/min/IP and uses `Cache-Control:no-store`. Private endpoints and every mutation retain existing authentication. `/dashboard` consumes this projection without a session; `/dashboard/admin` is the full administrator workspace.
- GET /api/v1/devices -> {devices:[{id,name,groupId,groupName,language,status,lastSeenAt,location:{lat,lng,accuracyM,recordedAt}|null,batteryPercent,cameraConnected,imuTracking,watchConnected,destinationName,lap:{mode,count,target,confidence}|null,sharingEnabled}]}
- POST /api/v1/device/telemetry (deviceBearer) {sharingEnabled,location:{lat,lng,accuracyM,recordedAt}|null,batteryPercent,cameraConnected,imuTracking,watchConnected,destinationName,lap:{mode,count,target,confidence}|null,status:'active'|'needs_help'|'paused'} -> {ok:true}
- POST /api/v1/device/privacy {sharingEnabled:false} immediately clears location -> {ok:true}.
- GET /api/v1/device/profile -> {device:{id,name,groupId},group:{id,name}}

## Alerts
- GET /api/v1/alerts -> {alerts:[{id,groupId,sourceDeviceId,sourceName,kind:'help'|'regroup'|'message',message,createdAt,recipients:[{deviceId,name,deliveredAt,acknowledgedAt}]}]}
- POST /api/v1/alerts (admin) {groupId,deviceIds?:[],kind,message} -> {alert:...}. Empty omitted deviceIds means all group.
- POST /api/v1/device/alerts (device) {kind:'help'|'regroup',message,clientId} -> {alert:...}; server derives own group, idempotent clientId.
- GET /api/v1/device/alerts -> {alerts:[{id,kind,message,createdAt,sourceName}]}; returns pending unacknowledged fresh alerts only; explicit delivery semantics.
- POST /api/v1/device/alerts/:id/delivered -> {ok:true}
- POST /api/v1/device/alerts/:id/ack -> {ok:true}

## Assistant
- POST /api/v1/assistant (device) {message,language,context:{destinationId,destinationName,remainingMeters,lap},destinations:[{id,name,aliases?:[],distanceMeters?:number}],history:[{role:'user'|'assistant',content}]} -> {reply,language,action:{type:'navigate'|'none',destinationId:string|null},provider:'gemini'|'openai'|'local',requiresConfirmation:boolean}. Never invent destinations/routes. Server prefers Gemini structured output when GEMINI_API_KEY is configured; optional OpenAI Responses is also supported, validates action against provided catalog. No automatic alerts from assistant. Deterministic limited offline/provider-unavailable fallback disclosed via provider=local. Do not fake general AI success. Must handle Arabic/English/Urdu/Indonesian/Turkish and language tags generally.
- This text-only endpoint does not accept images or claim to see Eye. Its `history[].content` format and fallback remain unchanged. Religious image questions use the separate endpoints below and `history[].text`.
- GET /api/v1/health -> {status:'ok',aiConfigured:boolean,aiProvider:'gemini'|'openai'|'local',version:'3.0'} no secrets. The retained health version is a legacy platform value, not the Android release number or a guarantee that vision is available. Vision requires Gemini specifically.

## Religious image companion — added in 3.1

Both exact endpoints require `Authorization: Bearer <device token>` and `Content-Type: application/json`. The server authenticates and checks the device role before reading the larger body. Missing, expired or revoked credentials return 401; an administrator token returns 403. Location-sharing permission is independent. The client must obtain explicit runtime snapshot consent before either request; enrolling in a group alone is not consent. The server cannot infer a UI consent action from a bearer token.

Shared types (notation below describes the wire format; it is not an example request):

```typescript
type VisionLanguage = 'ar' | 'en' | 'ur' | 'id' | 'tr';
type VisionKind = 'landmark' | 'text' | 'object';
type VisionImage = { mimeType: 'image/jpeg'; data: string };
type VisionSource = { id: string; title: string; url: string };
type VisionLandmarkId = 'kaaba' | 'maqam_ibrahim' | 'black_stone' | 'safa_marwa';
type VisionDevotional = {
  id: string;
  title: string;
  arabicText: string;
  translation: string;
  note: string;
  sourceIds: string[];
};
```

### POST /api/v1/vision/analyze

```typescript
type AnalyzeRequest = {
  image: VisionImage;
  language: VisionLanguage;
  translate: boolean;
};
type AnalyzeResponse = {
  sceneId: string;
  summary: string;
  items: Array<{
    id: string;
    kind: VisionKind;
    label: string;
    description: string;
    box: [number, number, number, number];
    sourceIds: string[];
    landmarkId: VisionLandmarkId | null;
    devotionals: VisionDevotional[];
  }>;
  sources: VisionSource[];
  provider: 'gemini';
};
```

All request fields are required. A successful response contains zero to three items. `box` is integer `[ymin, xmin, ymax, xmax]`, normalized to 0–1000 over the entire uploaded image, with strict `ymin < ymax` and `xmin < xmax`. These are approximate 2D image bounds, not world anchors, ground planes, geographic coordinates or 6DoF tracking. Clients must map them through their image crop/scale and invalidate stale overlays. The server generates fresh random `sceneId` and item IDs; IDs do not establish continuity or identity across requests.

Unclear or person-related candidates are omitted; an empty `items` array is a valid uncertain result. Landmark candidates are restricted to `kaaba`, `maqam_ibrahim`, `black_stone` and the combined `safa_marwa` catalog entry. For a clear candidate, the server replaces proposed landmark labels/descriptions with the direct localized catalog name and fact, and returns the associated fixed source. It does not add a forced “possibly” prefix to a candidate already accepted as clear; uncertain candidates are still omitted. The additive `landmarkId` is the stable catalog key; random item IDs remain per-response. This separates a visual guess from a reference fact; a citation does not prove image identity. No floor or individual Safa-versus-Marwa classification is supported.

`devotionals` contains zero to two server-curated records per item, with `id` 1–80, `title` 1–120, `arabicText` 1–600, `translation` 1–900, and `note` 1–600 characters. Each has one to three fixed `sourceIds`. Arabic scripture/dhikr remains unchanged across languages; title, explanatory translation of meaning, and contextual note follow the request language. The note must be displayed with the text: it qualifies when the text applies and does not establish ritual stage or precise position. Maqam Ibrahim receives a Quran 2:125 reminder explicitly identified as an excerpt, not a special Maqam dua. Nonlandmarks receive `landmarkId:null` and `devotionals:[]`. Older clients can ignore the additive fields. Catalog content/provenance is documented in [Devotional catalog](../server/docs/DEVOTIONAL_CATALOG.md).

For `text`, `label` contains the model-read original text (at most 120 characters). With `translate:true`, the description is a proposed translation into the selected language; otherwise it is a localized machine-reading notice. Object descriptions are qualified visual observations. Model descriptions are limited to 400 characters before the server adds a localized prefix; landmark text and response summaries come from the bounded catalog. Analysis source entries are the union of included landmark-fact and devotional references, within 16 entries. Item `sourceIds` continue to cite the landmark fact; each devotional has its own references.

### POST /api/v1/vision/ask

```typescript
type AskRequest = {
  image: VisionImage;
  language: VisionLanguage;
  question: string;
  selectedItem?: {
    id: string;
    label: string;
    description: string;
    kind: VisionKind;
    landmarkId?: VisionLandmarkId | null;
  } | null;
  history?: Array<{ role: 'user' | 'assistant'; text: string }> | null;
};
type AskResponse = {
  reply: string;
  sources: VisionSource[];
  provider: 'gemini';
};
```

`question` is 1–2000 characters. If present, `selectedItem` requires the original four fields: `id` 1–120, `label` 1–160, `description` 0–600 characters, and the exact kind enum. It additionally accepts optional `landmarkId`, null or one of the four catalog IDs; a non-null ID requires `kind:'landmark'`. Do not send `devotionals` or source objects back: these are retrieved server-side, and extra fields still fail validation. `history` defaults to empty when omitted/null; it has at most six entries, each with a permitted role and `text` of 1–1000 characters. Input strings are trimmed; unsupported control characters and extra fields at every validated request object level are rejected. Character bounds follow JavaScript string length. Selected labels/IDs and history are untrusted context, not evidence that the object was identified correctly. No `sceneId` lookup or stored conversation exists on the server: the client resends the selected snapshot and bounded history with every follow-up.

The answer remains `{reply,sources,provider}`. Model prose is limited to 1600 characters. Answers are conversational and do not receive an automatic repeated image/snapshot disclaimer; the prompt still requires relevant uncertainty and forbids inferring the user’s physical presence from a depicted landmark. The provider can select up to two internal `devotionalIds`; the server inserts their exact Arabic, localized meaning and note into `reply`, bounded to 6000 characters total, with at most four sources across prose and devotional content. Unknown IDs, model-supplied devotional objects/URLs, and recognized free-text invocation/scriptural-quotation patterns are rejected. The model is instructed to select catalog IDs instead of composing or translating a prayer. This deterministic insertion protects the displayed devotional records; the pattern guard is not a semantic fact checker or a guarantee of every generated prose claim.

The fixed answer context includes the devotional catalog and three ritual notes: seven Tawaf circuits with the Kaaba on the left/start and finish aligned with the Black Stone; seven one-way Sa'i lengths from Safa, ending at Marwa; and each outward/return length counted separately. These are answer facts, not extra recognition categories or an automatic counter. The assistant may ask one contextual question or suggest one relevant next step, without claiming to execute navigation, notifications or other actions. Unsupported religious claims, fabricated scriptural quotations, mandatory per-round prayers, personal rulings and declarations of ritual completion are disallowed by the prompt. Model compliance is not a correctness guarantee.

### Validation, resource limits and failure behavior

| Resource | Limit/behavior |
| --- | --- |
| Decoded image | At most 400 KiB (409600 bytes); JPEG only. |
| Base64 | Canonical standard base64, at most 546136 characters; no data URL, whitespace, line breaks or URL-safe alphabet. |
| JPEG structure | Complete supported 8-bit JPEG container/segments and end marker; no trailing bytes. Maximum 2048 pixels per side and 4000000 pixels total. The server checks structure/dimensions without decoding pixels; Gemini performs image decoding. |
| Request body | At most 600 KiB (614400 UTF-8 bytes), only for these two exact POST routes. Other requests retain the 64 KiB limit. |
| Body read | 50-second image-upload timeout, including chunked requests; oversized declared or streamed bodies are rejected. The incoming HTTP request deadline is 90 seconds, allowing the maximum 8-second queue wait plus 50-second body read and header margin. Header timeout stays 15 seconds; other route body deadlines are unchanged. |
| Concurrency | One pending/active vision operation per device, four active across this process, and at most ten waiting up to eight seconds. Questions are admitted ahead of waiting background analysis. Admission happens before reading the image body; slots are released on success/failure. |
| Rate | Thirty analyze attempts/minute/device, thirty ask attempts/minute/device, and 240 combined vision attempts/minute/server process. Existing API rate limits also apply; rejected attempts can consume the window quota. These application limits do not increase Google’s quota. |
| Gemini | Default `gemini-3.8-flash`, configurable through server-only `GEMINI_MODEL`; one candidate, 3072 output tokens, 50-second provider timeout by default, 64 KiB response byte cap. |
| Output | Structured JSON is validated. Unknown source IDs, arbitrary model URLs/links, HTML, hidden direction overrides, malformed output, provider refusal and incomplete responses fail closed. |

The two endpoint stages allow up to 8 seconds in admission, 50 seconds receiving the image, and 50 seconds waiting for Gemini: a 108-second application budget. Clients and the reverse proxy should allow at least 120 seconds for this path. These are bounded allowances, not target response times. The normal `index.mjs` entry point supplies no `aiTimeoutMs` override and reads no timeout environment variable; its vision provider uses the 50-second default. Direct library/test callers may still supply the existing `aiTimeoutMs` option.

Application errors retain `{error:string,code:string}` and may add `retryable:boolean` and `retryAfterSeconds:integer`. Vision provider delays are bounded to 1–300 seconds; nonretryable provider failures omit the delay. The response `Retry-After` header carries the delay when present. Clients should respect this delay and apply bounded backoff, without automatically retrying nonretryable failures. The following statuses apply. A reverse proxy may reject an oversized request before the application and need not return this JSON shape.

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`, `INVALID_IMAGE`, `INVALID_JSON` | Invalid fields, image/base64/JPEG, or JSON. |
| 401 | `UNAUTHORIZED` | Missing, expired or revoked device session. |
| 403 | `FORBIDDEN` | Authenticated role cannot use the endpoint. |
| 408 | `VISION_UPLOAD_TIMEOUT` | Image upload did not finish within 50 seconds, before Gemini is called; retryable after 2 seconds. This is distinct from 504 `VISION_TIMEOUT`, which occurs while waiting for the provider. Other routes retain `BODY_TIMEOUT`. |
| 413 | `BODY_TOO_LARGE` | Body exceeds the route limit. An oversized image inside a smaller valid JSON body instead receives 400 `INVALID_IMAGE`. |
| 415 | `JSON_REQUIRED` | Content type is not `application/json`. |
| 429 | `RATE_LIMITED` | Application rate window reached; `Retry-After: 60`. |
| 429 | `VISION_BUSY` | This device already has work pending, the queue is full, or its wait expired; retryable with a 2-second delay. |
| 429 | `VISION_RATE_LIMITED` | Upstream 429; retryable with bounded provider delay or 60 seconds by default. This status alone does not identify daily, billing, token or request quota. |
| 504 | `VISION_TIMEOUT` | Local provider deadline or upstream 408/504; retryable after 12 seconds. |
| 503 | `VISION_UNAVAILABLE` | Upstream 5xx except 504, or transport failure; retryable with provider delay or 12 seconds by default. |
| 503 | `VISION_AUTHENTICATION` | Upstream 401/403: service credentials or access need attention; nonretryable. This is separate from the user’s device-session 401. |
| 503 | `VISION_MODEL_UNAVAILABLE` | Upstream 404 for the configured model endpoint; nonretryable. |
| 503 | `VISION_CONFIGURATION` | Missing/invalid local configuration or other upstream request rejection; nonretryable. HTTP 400 alone does not prove an invalid key. |
| 422 | `VISION_BLOCKED` | Prompt or generation safety/recitation refusal; nonretryable for this input. |
| 502 | `VISION_INVALID_RESPONSE` | Incomplete, oversized, malformed or unsupported provider output, including output that fails the source/content guards; retryable after 12 seconds. No fabricated local answer. |

The provider uses inline JPEG data through Gemini `generateContent` and the project's verified structured-output shape (`responseFormat.text.mimeType: 'APPLICATION_JSON'`). Reference: [Gemini image understanding](https://ai.google.dev/gemini-api/docs/generate-content/image-understanding?hl=en) and [GenerateContent REST](https://ai.google.dev/api/generate-content).

A clearly depicted catalog landmark is eligible whether supplied directly by a camera, shown inside a photograph or phone/tablet screen, or uploaded as an image. No new image-source metadata is required: all use the same bounded JPEG contract. Bounding boxes refer to the depicted object within the entire supplied image. The server does not turn recognition into a claim of physical presence, coordinates, distance, route, ritual stage or floor. Unclear depictions still produce no overlay. Curated facts and devotional text are unchanged. See [provider behavior and verification](../server/docs/VISION_PROVIDER.md).

### Sources, privacy and client behavior

`sources[].url` is populated only from `src/religious-landmarks.mjs`, never from a model-supplied URL. The original six IDs are `haramain-kaaba`, `haramain-maqam`, `haramain-black-stone`, `saudipedia-safa-marwa`, `haramain-ritual-guide`, and `nusuk-umrah-journey`; their official links and facts are listed in the [3.1 Arabic guide](../../docs/RELIGIOUS_COMPANION_AR.md). Five devotional source IDs are added: `quran-2-125`, `quran-2-158`, `quran-2-201`, `binbaz-tawaf-dhikr`, and `binbaz-safa-marwa-dhikr`, with provenance in the [catalog record](../server/docs/DEVOTIONAL_CATALOG.md). Model-read signs, questions, selected metadata and history cannot authorize navigation, alerts or other actions. The vision prompt does not identify people or infer personal religious identity.

Images are handled in memory by our application/server implementation; these routes do not write images, questions, conversation history or provider bodies to files, database or logs. Deploy vision proxy locations with request buffering disabled and the route-specific 600 KiB body limit so large snapshots are not spooled to proxy temporary files. This implementation statement does not promise zero retention by Google: the account's [Gemini API terms](https://ai.google.dev/gemini-api/terms) may apply.

The phone starts with vision sharing disabled, requests runtime consent, and provides manual capture and pause controls. The original 3.1 cadence was about 12 seconds. The current client uses a bounded adaptive schedule (6-second minimum for a new/lost scene, 18 seconds while tracking remains stable, and 12/24/48/60-second failure backoff), only in the foreground with the applicable stability checks and no overlapping request. Cloud analysis is distinct from the live local camera/marker display. Manual capture still requires consent and can be used while automatic capture is paused. Leaving the foreground or disabling sharing invalidates in-flight results and clears the temporary scene; data already delivered to the provider cannot be recalled. Selecting a scene item pins the retained snapshot for questions and pauses replacement until returning to the live camera. This does not change the existing location-consent or alert contract.

The 3.1 phone ritual counter is locally persisted and manual, with independent Tawaf/Sa'i values from 0 to 7, undo and confirmed reset. It works without enrollment or network. Choosing the watch source only displays the last received matching watch count, explicitly read-only and possibly delayed/disconnected. Switching sources does not transfer, sum or overwrite counts, and the phone-local counter does not modify watch telemetry. Neither source proves ritual completion.

## Speech playback

`POST /api/v1/vision/speak` accepts a device bearer session and JSON `{text:string, language:'ar'|'en'|'ur'|'id'|'tr'}`. Text must contain 1–1800 characters and is trimmed. Only these two keys are allowed; callers cannot select a model, voice, URL or credential. The route authenticates before checking/reading the body; administrator sessions receive 403. It keeps the 64 KiB JSON limit and a 5-second body-read timeout. It accepts no image or microphone audio.

Success returns raw mono signed 16-bit **little-endian** PCM without a WAV header:

```http
Content-Type: audio/pcm
X-Audio-Format: pcm_s16le
X-Sample-Rate: 24000
Content-Length: <actual PCM byte length>
Cache-Control: no-store
```

The adapter uses `gemini-3.1-flash-tts-preview`, `responseModalities:['AUDIO']` and the prebuilt `Sulafat` voice with the server's Gemini key. Its 30-second deadline and single provider call are independent of image analysis. Provider JSON is limited to 12 MiB; decoded PCM is nonempty, even-length and at most 8 MiB. Only a completed `STOP` result containing one audio part with the expected 24 kHz mono PCM format is accepted. See Google's [speech-generation reference](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation).

Speech has a separate admission queue: two active requests and six waiting up to four seconds, with one pending/active speech request per device. Limits are 24 requests/minute/device and 120/minute/process, independent of image analysis. The active slot is held through audio delivery; a stalled write is terminated after 15 seconds. Server factory options permit injected `speechFetchImpl`, `speechTimeoutMs`, `speechModel` and `speechAdmission` for tests; clients cannot set them through HTTP.

Failures use the JSON error envelope. `VISION_BUSY` (429, retryable after 2 seconds) covers speech queue capacity/duplicate requests/wait expiry; `RATE_LIMITED` (429, 60 seconds) covers application rate windows. `PROVIDER_RATE_LIMITED` (429) uses a bounded provider delay of 1–300 seconds, default 60. `SPEECH_CONFIGURATION` (503, nonretryable) covers missing/invalid configuration or upstream 400/401/403/404. `SPEECH_UNAVAILABLE` (503, retryable after 12 seconds) covers timeout, network, refusal, incomplete audio and other provider failures. Raw provider bodies and credentials never appear in the response. The route performs no automatic retry; clients may use a disclosed local speech fallback.

The prompt requests reading the text as written with warm, clear delivery, Modern Standard Arabic for Arabic text, and preserved Arabic passages in other languages. Transcript text is separated as untrusted data and cannot select a provider, execute an action or alter request configuration. This route stores/logs no transcript, audio or raw provider response. Audio fidelity and pronunciation remain model behavior rather than a verified word-for-word guarantee; the displayed sourced text remains the reference. Google's service data terms apply to text sent for cloud speech.

`test/speech.test.mjs` covers request/output/provider boundaries and single-call behavior. `test/speech-routes.test.mjs` covers authentication before body reading, PCM headers and bytes, unchanged member telemetry, independent queue/rate windows, safe error metadata and recovery. These use synthetic PCM and mocked Google responses; they do not verify physical speakers, voice quality or live service availability.

## Dashboard
Arabic RTL polished responsive application with real authenticated data, map of only shared last-known locations, explicit offline/stale/accuracy status, counts, group/enrollment management, alert compose and delivery/ack status. Demo is optional explicitly labeled opt-in and never sends data into real server. No invented real users. Poll every5s authenticated fetch; no SSE required v1.

## Wear OS phone data layer (same app id/signature on watch)
message path /sarab/watch/event payload JSON {id,type:'lap'|'help'|'ack'|'status', ...}; lap fields {mode:'tawaf'|'sai',count,target:7,confidence:'manual'|'estimated',sessionId}; ack {alertId}; status {batteryPercent}.
Phone -> watch /sarab/watch/alert {id,kind,message,sourceName,createdAt}; must dedupe persistent IDs before vibration. Watch sends ack on user tap, not merely delivery. Phone can reply /sarab/watch/state {groupName,connected,lastSyncedAt}. Watch event help uses id as backend clientId. Counts are locally persisted with manual increment/undo and confirmed reset. `estimated` remains a compatible protocol value, but the current app/watch do not implement automatic lap inference. Never claim religious completion or GPS precision.
