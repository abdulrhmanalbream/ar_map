---
name: المطوف الذكي — هوية هداية ثون
description: A shared Hedayathon identity for the phone, watch and supervisor dashboard.
colors:
  forest: "#133F35"
  paper: "#F5F3EB"
  surface: "#FFFEF9"
  ink: "#173F35"
  gold: "#B49A5B"
  gold-ink: "#806B38"
  soft: "#E9EEE3"
---

# Shared Hedayathon design system

## Overview
The supplied Hedayathon presentation identity is the visual authority. The original logo sits on a forest field, where its pale lettering stays legible. The product remains المطوف الذكي with secondary هداية ثون / فريق سعي attribution. These are operational interfaces: camera guidance, a watch counter and group supervision each keep their native interaction model.

## Colors
Forest carries branded navigation and primary actions; ivory and warm white carry ordinary reading surfaces. Gold identifies selected elements and small brand details. Gold is not used for small text on ivory: use gold-ink or forest. Status errors, warnings and success retain semantic colors plus labels. The user's3.2.2 camera-panel direction is translucent hologram styling: a low-alpha forest tint preserves the video behind the panel, pale ivory/mint text uses a dark shadow, and thin mint outlines/corner brackets define its boundary. This supersedes the opaque ivory camera panels documented in3.2.1; those colors and contrast measurements remain historical. Contrast on a translucent surface depends on the current camera image and must not be claimed as a fixed ratio or as verified physical-XREAL readability. Ordinary web/watch surfaces retain their established identity.

The frozen3.2.2 panel is one `#102C25` gradient layer at alpha.34/.18/.26 from top to bottom. Text uses `#F3FFF9`, secondary text `#D8F4E5`, and mint `#B7FFE0` with a dark shadow. The small microphone fill uses alpha.50. Do not stack an opaque Material surface beneath the gradient or describe these alpha values as physical lens transmission.

## Typography
Use the locally bundled IBM Plex Sans Arabic family, with its OFL license included. Native Android uses sp and Material typography roles, with system font fallback for unsupported scripts. Web uses a fixed rem scale. Arabic is RTL; URLs and codes retain readable LTR direction. Do not stretch or redraw the logo or turn its lettering into the app's body font.

`HedayahArPanelTheme` retains primary body text at17–19sp with27–30sp line heights. The hologram text shadow supports separation from the image without dimming the whole camera. Context and source labels accompany the content; this scoped camera treatment does not redefine dashboard or watch typography.

## Layout
Phone screens keep system insets and reachable navigation. Camera chrome leaves the center view clear. Watch content respects the circular safe area, scrolls vertically, and makes the selected ritual and manual increment action immediately understandable. The dashboard keeps the map and member state central, with an adaptive navigation rail/header and stacked compact layouts.

The3.2.2 skin preserves the in-activity panel layout introduced in3.2.1, with explicit compact/reading controls and no full-scene modal. Use a bounded side panel in landscape and bounded-height panel in portrait, leaving the camera visible around and through it. Keep microphone actions outside the scrollable text and identify the information as belonging to a selected snapshot. Compact reduces detail but may share reading height when space is constrained. View changes are manual; no walking, stopped-state or hazard detection is implied. Real glasses readability remains a physical acceptance check.

## Elevation & Depth
Use tonal layers and restrained borders. Native dialogs follow the platform's normal focus and dismissal behavior. The hologram skin uses a static thin glowing frame and dark text shadow; do not blur the camera or add continuously animated decoration. Do not use the hologram appearance to imply depth, world anchoring or optical light occlusion.

## Shapes
Use a consistent restrained corner family for panels and standard rounded native controls. Minimum Android touch targets are 48dp; compact browser controls remain usable by touch and keyboard.

## Components
Brand components reuse the exact provided logo asset. Buttons distinguish primary actions from secondary navigation and destructive/reset actions. Form labels remain visible; focus, loading, disabled, empty, error and selected states remain explicit. Watch lap marks communicate the actual manual count only. Guidance arrows retain their tested placement and sensor interpretation.

## Do's and Don'ts
- Keep original logo proportions and clear surrounding space.
- Keep contrast, native back behavior, accessibility scaling, reduced motion and consent semantics.
- Keep event identity secondary to the user's current task.
- Do not add synthetic operational data, automatically count laps, or claim floor awareness as part of this design update.
- See `platform/web/DESIGN.md` for the web surface's exact component tokens and responsive rules.
