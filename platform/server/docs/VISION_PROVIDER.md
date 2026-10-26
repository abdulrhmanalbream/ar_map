# Vision provider behavior

The vision endpoints use the configured `gemini-3.8-flash` model through the `v1beta/models/{model}:generateContent` REST endpoint. The model remains unchanged. Google’s current [Gemini 3.8 Flash page](https://ai.google.dev/gemini-api/docs/latest-model) lists this model; the existing request format is supported by the [GenerateContent reference](https://ai.google.dev/api/generate-content): inline JPEG bytes in `Part.inlineData`, a header-only API key, LOW thinking and `generationConfig.responseFormat.text` with `mimeType:'APPLICATION_JSON'` and a JSON schema.

There is exactly one generation call per accepted operation. The provider deadline is 50 seconds, with one candidate, 3072 output tokens and a 64 KiB response-body ceiling. Route admission limits the number of active operations before reading the larger image body. There is no server-side generation retry or invented local visual fallback. The Android/provider time budgets and public error schema are described in the [API contract](../../docs/CONTRACT.md).

## Failure classification

Only server-authored messages and codes leave the provider adapter. It does not retain or log image bytes, questions, history, raw provider responses, credentials or exception causes. HTTP error bodies are cancelled without being parsed. Bounded numeric/date `Retry-After` headers can supply an integer delay of 1–300 seconds; invalid values use a fixed fallback. An upstream 429 is reported as a request-limit failure without guessing whether it represents a daily allowance, billing issue or per-minute limit.

HTTP 401/403 identifies service credential/access rejection; 404 identifies an unavailable configured model endpoint. Other request rejections and missing configuration are nonretryable configuration failures. These statuses do not revoke or invalidate the user’s app session. Timeouts, network/5xx failures, rate limits, safety refusals and incomplete/unsupported responses have distinct codes. Invalid model output remains a provider-response error even when an internal validator raises a request-validation exception.

The client can use `retryable` and `retryAfterSeconds` to avoid repeatedly issuing an operation that requires changed service settings or changed input. Google’s [troubleshooting guide](https://ai.google.dev/gemini-api/docs/troubleshooting) recommends bounded backoff for transient failures and avoiding automatic retries for malformed requests or permission failures. A retryable flag is not a promise that a later request will succeed.

## Photographs and screens

The same validated JPEG request supports direct camera imagery, an imported photograph, and a camera view of a phone/tablet screen. A screen or photograph does not automatically make an otherwise clear catalog landmark uncertain. The prompt requires visible identifying evidence and a bounding box around the depicted landmark inside the full input image. A generic black object, gold enclosure or corridor remains insufficient evidence.

The explanation concerns the depicted landmark. The prompt prohibits inferring physical presence, location, distance, direction, ritual progress or floor from recognition. Clear accepted landmarks use their direct localized catalog name/fact and a concise selection summary, without a forced “possibly” prefix. Unclear candidates are still excluded. Questions receive concise natural answers without an automatic repeated snapshot disclaimer; uncertainty is still required when relevant. Image text, user questions, selected metadata and conversation history remain untrusted data and cannot authorize actions or override instructions. Photo/screen recognition is a model behavior, not a verified physical-localization capability.

Landmark facts, five-language devotional records, quotations, qualifications and official source URLs remain server-owned and unchanged. The model selects from the existing catalog and cannot add a devotional record or source URL. See [devotional provenance and limits](DEVOTIONAL_CATALOG.md).

## Verification scope

`test/vision-provider.test.mjs` covers distinct failure codes, sanitized metadata, delay bounds, configuration checks, transport failures, safety refusals, malformed/incomplete output, one-call behavior, photo/screen prompt eligibility and natural sourced answers. Existing vision and devotional tests retain request/image/privacy/injection/source constraints and all five languages. These mocked checks verify the contract and safeguards; they do not measure the accuracy of identifying real landmark photographs or establish live service availability.
