# المطوف الذكي website and dashboard product
<!-- impeccable:product-schema 1 -->

## Platform
web

## Confirmed scope
The public homepage is an Arabic Hedayah field guide to the religious visual companion: an animated, explicitly illustrative local demo; actual usage steps; supported landmark references and languages; secondary watch/group features; and honest prototype disclosures. It performs no API or device-permission requests. `/dashboard` opens an anonymous, read-only live aggregate for the general group. The authenticated administrator workspace is at `/dashboard/admin`.

The administrator workspace retains latest explicitly shared device locations, groups and enrollment, assistance alerts and delivery acknowledgements, and server/assistant readiness. The user requested viewing the dashboard without sign-in; the public view shows real general-group totals and current device readiness without individual identities, coordinates, messages or private groups. Viewing cannot grant send/manage privileges. The supplied Hedayathon identity is preserved across both surfaces. No invented users, location history or public tracking. Administrator bearer tokens remain in sessionStorage; the anonymous route never reads or transmits them. Provider credentials stay server-side.

## Operating context
An operator needs to identify a member needing help, understand recency and location accuracy, and coordinate a group. Desktop and mobile browsers are supported. The API contract is ../docs/CONTRACT.md. Device sharing consent controls whether a location may be rendered.

## Principles
Show stale information honestly. Keep errors actionable. Never treat alert delivery as user acknowledgement. Empty dashboards must teach joining the default public group by name or private invitation enrollment without synthetic member data. Only the labelled public demonstration uses authored sample scene/text/counters, all local to the browser.

## Open decisions
The product's user-confirmed display name is exactly **المطوف الذكي**, with **Smart Mutawwif** for the English wordmark. Internal package, API and session-storage identifiers remain compatible. Hedayathon is secondary event attribution with **فريق سعي**, using the supplied original transparent PNG. Forest, ivory, restrained gold and IBM Plex Sans Arabic form the shared identity. The map's initial geographic extent is a navigation default, never represented as a member location.
