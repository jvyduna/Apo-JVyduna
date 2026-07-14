# Arrange / Compositions feedback

Feedback on the beta `arrange` branch of Chromatik (LXStudio + LX + GLX,
1.2.2-SNAPSHOT) from composing on the timeline, written up as read-only
source investigations for mcslee. Each entry links to a detail page with the
symptom, the relevant host source (cited `path:line`), and a suggested minimal
fix. All findings are static source-tracing unless a page says otherwise;
none of this touches the `apotheneum.jvyduna` package — the fixes are host-side.

**Legend:** 🐞 bug · 🧩 feature request · 💥 fatal

## Issues

### Playback & authoring

- 🧩 [Continuous easing (accel / decel / smoothstep) segment types for parameter lanes](2026-07-14-continuous-segment-easing-types.md)
  — parameter-lane interpolation is hard-linear at all three in-between sites;
  a per-event 4-value easing enum (Linear/Accel/Decel/Smooth) fits cleanly,
  backed by the accel/decel/smoothstep math already in `QuadraticEnvelope`, and
  sidesteps the bezier-handle UI rabbit hole (old events default to Linear).
- 🧩 [Apply composition state while scrubbing — curve preview + pattern-change seek](2026-07-14-scrub-preview-interpolation.md)
  — moving the playhead is not a "seek": scrubbing only sets an insert marker and
  applies no state, so a stopped scrub shows neither the interpolated curve nor
  the pattern that should be active. The value-at-cursor and pattern-at-cursor
  evaluators already exist but are reachable only during playback; a
  stopped-transport `seekToCursor` reusing them is the minimal fix.
- 🐞🧩 [Composition `length` can only grow, never shrink — no UI to trim it, and `playEnd`/`playStart` are dead for compositions](2026-07-14-composition-length-grow-only-no-trim-ui.md)
  — `runAutomation` special-cases `LXComposition` to always play to `length`,
  ignoring `playEnd` entirely; `length` only ever auto-grows on content
  import and nothing shrinks it back; and `UICompositionScrubLane` (unlike
  clips' `UIScrubLane`) exposes no drag handle for `length` or `playEnd` at
  all. Found while tracking down why `2 - Bliss.lxp` played ~2 minutes of
  black after the song ended — fixed for now with a hand-edited `.lxp`.

### Timeline layout & state

- 🧩 [Pin waveform audio lanes to the top so they don't scroll with the lane list](2026-07-14-waveform-lane-pinning.md)
  — the timeline already has vertically-pinned header/footer bands, but audio
  lanes live inside the single scrolling body and scroll off with everything
  else; route `AudioClipLane`s into a pinned band.
- 🐞 [Composition channel rows don't follow mixer channel order](2026-07-14-composition-channel-order-mismatch.md)
  — rows render purely in `composition.lanes` order and the sync path
  (`moveBusLanes`) is silently broken by a guard added in `c1b6ba93` that
  early-returns for `BusClipLane`, so rows freeze in creation order and diverge
  the moment a channel is reordered. Jeff is fine with no independent reorder as
  long as it consistently mirrors the mixer.
- 🐞🧩 [Lane collapse state is inconsistent on load and not persisted (nor is zoom)](2026-07-14-lane-collapse-state-restore.md)
  — `uiExpanded` and `zoom` *do* serialize, but on load a collapsed bus lane's
  state never propagates to its children's `uiVisible` (the bridge listener is
  non-immediate and attached after load), so children show under a collapsed
  parent until the first manual toggle. Zoom appears already restored — flagged
  for end-to-end verification.

### Windowing & input

- 🐞 [Composition window position discarded when its top-left has a non-positive X or Y](2026-07-14-second-monitor-window-position.md)
  — the "Composition" window is a separate GLFW `AltWindow` whose geometry lives
  in `.lxpreferences` (not the `.lxp`, confirming Jeff's hunch); a
  `setPosition` guard requiring strictly-positive X and Y drops any
  second-monitor placement (negative X to the left, `y=0` top-aligned), so it
  re-centers on the primary monitor.
- 🐞 [Spacebar isn't scoped to the composition editor](2026-07-14-spacebar-event-bubbling.md)
  — the editor's spacebar handler is correct, but its container panes aren't
  `UIFocus`, so space only reaches it when a lane leaf holds focus. Otherwise the
  identical key hits the toolbar transport button (quantized `composition.launch`)
  instead of the editor's immediate action; there's no global bare-space shortcut.

## Reported earlier (2026-07-08 / 07-09)

- 🐞 [Locator label click-to-rename can never work](2026-07-08-locator-label-rename.md)
  — the scrub lane consumes the first click (launching playback), defeating
  double-click detection, so the `UITextBox` rename affordance is unreachable.
- 🧩 [Palette / swatch changes cannot be recorded into a composition](2026-07-08-palette-change-not-recorded-into-composition.md)
  — `lx.engine.palette` has no `LXBus` ancestor and isn't registered by the
  composition recorder, so armed live swatch changes record nothing and have no
  lane to host them.
- 🐞 [Notes-lane events render wrong lengths at high zoom (unsorted event list)](2026-07-08-textnote-unsorted-events.md)
  — an unsorted event list makes note spans draw with wrong lengths at high zoom.
- 💥 [Fatal UI-thread crash drawing a notes lane during a concurrent event edit](2026-07-09-textnote-lane-draw-crash.md)
  — an unhandled exception on the render thread during a concurrent edit takes
  down the whole UI.

---

## Unwritten feedback (not yet investigated)

Jot raw notes here as they come up. Each gets pulled out into its own dated
`YYYY-MM-DD-<slug>.md` detail page (source-traced, with a suggested fix) and
linked from the list above once written up.

_(none right now — the batch above was migrated from `Compositions Feedback.txt`.)_
