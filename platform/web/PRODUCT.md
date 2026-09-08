# المطوف الذكي dashboard product
<!-- impeccable:product-schema 1 -->

## Platform
web

## Confirmed scope
The supplied platform contract defines an authenticated Arabic RTL dashboard for administrators: latest explicitly shared device locations, groups and enrollment, assistance alerts and delivery acknowledgements, and server/assistant readiness. The parent task explicitly pinned an ivory/teal operational dashboard. Device camera, motion, battery, watch and lap states are genuine telemetry. No invented users, location history or public tracking. Bearer tokens remain in sessionStorage; provider credentials stay server-side.

## Operating context
An operator needs to identify a member needing help, understand recency and location accuracy, and coordinate a group. Desktop and mobile browsers are supported. The API contract is ../docs/CONTRACT.md. Device sharing consent controls whether a location may be rendered.

## Principles
Show stale information honestly. Keep errors actionable. Never treat alert delivery as user acknowledgement. Empty deployments must teach group enrollment without synthetic data.

## Open decisions
The product's user-confirmed display name is exactly **المطوف الذكي**, with **Smart Mutawwif** for the English wordmark. Internal package, API and session-storage identifiers are compatibility details and are not renamed. No separate branding asset was provided; the dashboard uses the product name in typography. The map's initial geographic extent is a navigation default, never represented as a member location.
