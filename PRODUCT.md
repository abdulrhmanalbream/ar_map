# المطوف الذكي
<!-- impeccable:product-schema 1 -->

## Platform
adaptive — Android phone, Wear OS watch, and authenticated web dashboard.

## Product and audience
Arabic-first ritual and navigation companion for a pilgrim and their group supervisor. The phone's live XREAL Eye view is the main workspace for guidance and a manual Tawaf/Sa'i lap counter, supported by a multilingual assistant and explicitly opted-in group telemetry. The phone retains separate local Tawaf and Sa'i sessions; the user can explicitly choose a read-only view of the last count received from a linked watch. The watch is an optional additional counter and help/alert device, with independent sessions. The dashboard lets an authenticated supervisor understand current member status and coordinate assistance.

The religious companion defaults to Eye and also offers an explicit back-camera phone mode without glasses or ARCore. Both sources use the same recognition, translation, questions and counter HUD. The choice is saved; switching sources clears the previous scene and turns snapshot sharing off until fresh consent. Phone rotation sensors provide only scene-motion invalidation, never glasses IMU telemetry or positional tracking. The phone AR navigation feature stays separate.

The primary interaction is now the live religious camera: tap a recognized landmark box for its reference facts and sourced contextual duas/reminders, or tap the visible microphone to ask about the current view without waiting for prior recognition. Speech is submitted automatically and the reply is read in the chosen language; further taps continue the conversation. The camera stays visible behind a compact conversation panel, and item details keep the microphone reachable above scrolling content. Image matching tracks boxes between cloud detections and hides lost targets. This is bounded2D image tracking with user-triggered voice turns, not continuous cloud video/audio streaming or world-position tracking.

The user's clarified3.2.2 preference is to see the camera through transparent hologram panels. A low-alpha forest tint, pale ivory/mint lettering with dark text shadow, and a thin mint frame/corner brackets replace3.2.1's opaque camera reading skin. The existing in-activity layout, selected-snapshot caption, fixed microphone/close controls and manual compact/reading choices remain. Compact reduces detail but may retain reading height in constrained space. No blur or continuous decorative motion is added. The app does not infer walking or a safe stopping place; changing camera content prevents a fixed contrast guarantee, and physical glasses readability remains unverified. This is a UI-only change with no new image consent or server action. Current validation is tracked in `docs/HOLOGRAM_PANELS_3.2.2_AR.md`; the opaque3.2.1 guide and results remain historical.

## Confirmed design brief
The user explicitly requested all interfaces to adopt the supplied Hedayathon identity and include its original logo. The product name remains **المطوف الذكي**. **هداية ثون** and **فريق سعي** identify the event and participating team; they do not replace the product name or imply a new endorsement. Supplied slide references establish forest green, ivory, gold and Arabic typography.

## Operating constraints
Phone controls must be readable outdoors and not obscure the live camera. Watch controls must fit a round screen and remain reachable while walking. Dashboard status must distinguish missing consent, stale information, alert delivery and explicit acknowledgement. Rebranding must preserve package IDs, signing, stored data, API contracts and existing navigation/camera behavior.

## Scope limits
Counters remain manual and preserve the user's recorded tally rather than deciding religious completion. Choosing the phone or watch display never merges, transfers, or overwrites their counts; unavailable watch data stays unavailable and is never replaced silently by the phone count. Physical Fold7 + One Pro + Eye + Watch8 Classic acceptance is outstanding. Floor identification is a separate proposal and demonstration, not a shipping feature. No invented members, telemetry, route maps or claimed accuracy may be added for visual effect.
