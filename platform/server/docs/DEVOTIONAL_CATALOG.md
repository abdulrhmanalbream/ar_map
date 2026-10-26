# Curated landmark reminders and devotional text

Checked against the primary publications below on 2026-09-08. The catalog is maintained in `src/religious-landmarks.mjs`. It adds content to a possible visual landmark match; it does not verify the user's place, ritual stage, count or alignment with a corner.

| Landmark ID | Devotional IDs | Verified content and scope |
| --- | --- | --- |
| `maqam_ibrahim` | `maqam-quran-2-125` | The Quran 2:125 excerpt about taking the Station of Ibrahim as a place of prayer. It is explicitly a Quran reminder, not a special dua prescribed for seeing the Maqam. |
| `kaaba` | `tawaf-dua-2-201` | Quran 2:201's supplication; the official Ibn Baz publication associates its recitation in Tawaf with the stretch between the Yemeni Corner and Black Stone. Seeing the Kaaba does not establish that position. |
| `black_stone` | `tawaf-dua-2-201`, `tawaf-takbir` | The same qualified between-corners reminder, plus takbir when level with the Black Stone in Tawaf. The app does not tell a user to approach the stone, infer alignment or advance a counter. |
| `safa_marwa` | `safa-marwa-quran-2-158`, `safa-marwa-dhikr` | Quran 2:158's opening excerpt in the context of beginning at Safa, and the dhikr transmitted in Jabir's account for Safa/Marwa, with supplication between repetitions. The combined visual category cannot distinguish the two ends or establish the beginning of Sai. |

Primary references:

- `quran-2-125`: [Quran 2:125, King Saud University's Quran publication](https://quran.ksu.edu.sa/tafseer/katheer/sura2-aya125.html). The excerpt is displayed with the verse reference and a contextual note.
- `quran-2-158`: [Quran 2:158, King Saud University's Quran publication](https://quran.ksu.edu.sa/tafseer/katheer/sura2-aya158.html).
- `quran-2-201`: [Quran 2:201, King Saud University's Quran publication](https://quran.ksu.edu.sa/tafseer/saadi/sura2-aya201.html).
- `binbaz-tawaf-dhikr`: [Official Ibn Baz publication: supplications and remembrance in Tawaf and Sai](https://binbaz.org.sa/fatwas/10450/الادعية-والاذكار-المشروعة-في-الطواف-والسعي). Supports the between-corners association, takbir aligned with the Black Stone, and absence of a compulsory separate prayer for each circuit.
- `binbaz-safa-marwa-dhikr`: [Official Ibn Baz explanation of Jabir's narration](https://binbaz.org.sa/audios/51/7--من-حديث-ابدا-بما-بدا-الله-به). Supports the opening verse at Safa and the fixed dhikr at Safa/Marwa. The Arabic dhikr is a classical transmitted text; the app does not fabricate an attribution or an additional ritual prescription.

Arabic scripture/dhikr is fixed in source code. English, Urdu, Indonesian and Turkish translations, plus Arabic explanations, are project-maintained renderings of meaning for accessibility; they are not presented as the original scripture or as officially approved translations. Titles and context notes are localized. The contextual note is displayed with each devotional. Conversational answers include the note when the server inserts a devotional, and reading that answer includes the note. The explicit “read Arabic” control reads only the fixed Arabic text while its context remains visible. The four-landmark visual catalog and three earlier ritual FAQ facts remain unchanged.

## Data contract and trust boundary

`/vision/analyze` adds `landmarkId` and up to two `devotionals` to each item. Nonlandmarks have null/empty values. The model still supplies only the original recognition fields; attempts to supply devotional objects, additional source fields, URLs or unknown landmark IDs fail validation. The server overwrites landmark prose and retrieves its devotional associations from this catalog. Every devotional source ID is resolved into a fixed top-level source record; generated text cannot define links.

`/vision/ask` optionally accepts a known `selectedItem.landmarkId` as untrusted context. The original four-field selected payload remains accepted; client-supplied devotionals and source objects remain rejected. The server retrieves reference facts for that hint, but the prompt still requires image evidence before identifying the place. No client-selected label or ID can change the catalog.

The provider's optional internal `devotionalIds` field selects up to two fixed records. The server, rather than the model, inserts their Arabic text, localized meaning and note into the public `reply`. Final text is at most 6000 characters, and all references together remain at most four. The provider must not write prayers or scriptural quotations directly in free prose; a further pattern guard rejects common invocation and attribution forms. This guard is not a semantic proof of every free-text answer. It does not make the model's identification or religious prose infallible. Personal rulings, guaranteed ritual completion, invented required prayers and claimed execution of navigation/alerts remain disallowed.

## Provider cost and latency

Enrichment adds no network call: each analyze/ask operation still makes one Gemini `generateContent` request with one inline JPEG. The current defaults are `gemini-3.8-flash`, LOW thinking, one candidate, a 3072-token output ceiling, a 50-second provider timeout and a 64 KiB provider-response ceiling. These are code defaults, not a measured live latency promise or inspection of production secrets. The provider documentation describes [thinking controls](https://ai.google.dev/gemini-api/docs/thinking); model-specific support must be verified before changing configuration. No provider configuration was changed for this catalog.

The selected-reference and devotional context makes follow-ups more useful, but adds prompt text and may affect latency. Analysis receives no devotional text in its provider prompt because enrichment happens afterwards. No image, prompt, history, provider body or secret is written by these routes to application logs or persistent storage. Google's service data terms remain applicable; the code makes no provider-retention promise.

The coordinated route limit is thirty analyze attempts per minute per device, thirty asks and 240 combined requests per minute per process. There is at most one pending/active operation per device, four active operations per process and ten waiting for up to eight seconds; questions have priority over waiting background analysis. The client's 6-second acquisition floor and 18-second stable-scene cadence leave bounded demand; its 12/24/48/60-second failure backoff reduces retry pressure. Local preview/tracking can update between requests. A rapid camera feed is not continuous cloud video analysis, and failures do not create a local fabricated identification.

## Verification

`test/vision-devotionals.test.mjs` covers every landmark/language association, text bounds, source completeness, the Maqam qualification, nonlandmark/uncertain output, defensive copies, selected-input compatibility, injection rejection, fixed-text answer insertion, conversational prompt boundaries and one provider call per operation. Route tests additionally verify enriched payload transport, image/transcript non-persistence, authentication before body read and the coordinated application rate windows. Mocked provider tests do not validate physical landmark recognition or the live Gemini service.

A clear landmark depicted in a photograph or screen is eligible for the same curated enrichment. Recognition never establishes that the user is physically present at the landmark. Provider failures now have safe categories and retry metadata; see [provider behavior](VISION_PROVIDER.md). No devotional Arabic text, translation, qualification, association or source record changed in this provider update.
