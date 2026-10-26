# Device and release validation — 3.3.0 demo companion

This is the validation checklist for `3.3.0-demo-companion` (phone code16), not evidence that an unrun check passed. The earlier Eye, religious companion and hologram reports retain their original build hashes and dates.

<!-- release-verification:start -->
**نتائج البناء المؤكدة:** 462 اختباراً للجوال و15 للساعة، دون فشل أو أخطاء أو تخطي. Lint:0 أخطاء، 147/3 تحذيرات للجوال/الساعة. تطابق222 ملف مصدر وإعدادات مع نسخة البناء.

APK: `3.3.0-demo-companion`، رقم16، 41,111,322 بايت. SHA256: `10E02E6B04FE6FB3F78172025D6C322B99C00A90CAC6101A80BD8369E2C2C49D`. شهادة التوقيع SHA256: `EEC7C6636F2E61C8ACB351C1CADFE0C55AEF3510C5AF8E4BB2A36E49D6D7186F`؛ تطابق شهادة التحديث السابق.

تقارير الفحص المرتبطة بهذا APK: `validation.json` (PASS، SHA256 `FE7A09BE22C8C2AF3F90CA6696E91C793CEC044F400426B4B1DEDAB3D4B78D70`)، `validation.json` (PASS، SHA256 `825C55EE6887125C333ECA2EA212FBF4947E4F2845BBF6E794493862EE54FE41`). هذا الربط لا يحول فحص المحاكي أو الصور الثابتة إلى تجربة كاميرا أو نظارة أو صوت فعلي.

تقارير الخادم المستقلة: `demo-companion-deploy-report.json` (PASS)، `demo-companion-live-report.json` (PASS).
<!-- release-verification:end -->

| Area | Meaningful check | Evidence boundary |
| --- | --- | --- |
| Build and identity | Parse app and wear JUnit XML, lint XML, APK metadata and signing certificate; compare repository inputs to the build mirror | Never reuse a hardcoded test total or a previous APK hash |
| First run | Fresh install and upgrade without a confirmed choice show all five languages before camera permissions; confirmation persists; settings can change language | Actual APK interaction required; source inspection alone is insufficient |
| No camera available | Choose an image before granting CAMERA; denial does not prevent gallery access | This verifies the fallback; it does not prove a camera stream |
| Image decoding | Wide/tall size bounds, all EXIF orientations and fit/letterbox geometry | Pure helper tests plus actual image import for platform decoding |
| Imported context | Select locally without consent, analyze only after consent, follow-up uses the same image; live frames do not replace it | Controller tests may use a fake transport; identify real provider requests separately |
| Lifecycle | Replace/return/source/language/background reject late replies; photo is retained locally for the current activity but does not upload automatically on resume | No claim of persistence across process death |
| Narration | Fresh landmark only, same-landmark cooldown, busy deferral, no stale/resume/unmute replay, curated text and qualification | Pure policy tests do not prove audible output |
| Mute | Persisted visible control stops playback; all read paths obey mute; microphone can still accept a question and leave the reply as text | A speech fixture must say whether Android STT/TTS or cloud PCM was actually invoked |
| Voice transport | Authenticated bounded PCM response, request cancellation, timeout/native fallback, no fallback on deliberate cancel | A server synthesis result is not a listening test on Fold7 or XREAL |
| Navigation voice | Shared mute stops immediate local guidance, onPause stops playback, no old cue replay; choose only installed offline Arabic, quality first and ar-SA tie preference | Pure selection tests exclude network/uninstalled/foreign voices; no audible device result is inferred. Time-sensitive cues never use Gemini speech |
| HUD | Photo fit with static rectangles, live hologram background, fixed microphone/back, text and keyboard fit in both orientations | Synthetic emulator pixels do not establish optical readability through glasses |
| Server deployment | Scoped project API update with pre/post hashes, health, actual provider probe if run, cleanup of temporary test identity | Build/test success is not deployment; record separate report |

No physical Fold7, XREAL One Pro/Eye or Galaxy Watch8 Classic is available for this validation until an explicit later report says otherwise. Actual USB capture, phone-camera behavior on Fold7, optical contrast, recognition in the Haram, real microphone accuracy and audible speech remain hardware checks. If the emulator camera is unavailable, report that limitation and use the image path; do not rename a still-image test as live camera validation.

`output/package-demo-companion-3.3.0.py` performs a read-only preflight by default. After the final build and actual QA reports, root supplies the expected APK SHA256 and report paths. `--package` copies only this release's filenames after all checks pass; existing older APKs must retain their hashes. The package records observed test/lint counts, APK metadata and certificate digest, exact report hashes and source/build-mirror agreement. It never invents a physical-device, server or voice result.
