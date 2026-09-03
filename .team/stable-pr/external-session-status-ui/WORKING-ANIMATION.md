# Codex working marker source freeze

The source of truth for the ordinary session-list marker is the desktop
implementation consumed by `Ctrl+W+B`: `src/components/sidebar/AgentsList.jsx`
renders `.agents-dot`, and `src/components/sidebar/sidebar.css` defines its
working state. The same marker is present in the authoritative prototype at
`design-handoff/cross-platform-desktop-ui-mockups/project/Agent App prototype.dc.html`
as `statusDot(running, label)` and `@keyframes pulse`.

## Frozen render contract

- Slot: 8 CSS px square, pill radius; Android maps this to the existing 8 dp
  left status slot immediately before the session name. Its center is the row
  centerline, matching the source marker's row placement. It is not a provider
  icon and owns no gesture.
- Working frame set/order: one solid center dot plus an outward box-shadow ring.
  At 0% the ring is radius 0 with `rgba(52,199,89,.55)`; at 70% it is radius
  5 px with transparent green; at 100% it returns to radius 0 transparent.
  The keyframes run in this order and repeat from 0%.
- Cadence/interpolation: 1.8 s, `ease-out`, infinite. There is no alpha
  cross-fade and no two-frame busy-dot substitute.
- Color: center `#34c759`; ring starts `rgba(52,199,89,.55)` and ends
  `rgba(52,199,89,0)`.
- Idle: the same 8 px slot is static, transparent fill, 1.5 px
  `#b8b4ab` hollow border; no animation.
- Unknown/abnormal/offline: no working animation. The row retains its
  fail-closed empty/offline slot behavior.

This freeze precedes implementation. The Android implementation must preserve
the source geometry and timing above for `activity=working` + `health=normal`.
