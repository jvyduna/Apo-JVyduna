# Unknown Pleasures

CP 1919 pulsar ridgeline waterfall: stacked, occluding spectrum silhouettes scrolling like the 1979 Joy Division "Unknown Pleasures" album cover.

## Original / inspiration

Peter Saville's cover for Joy Division's *Unknown Pleasures* (1979), itself a
stacked-ridgeline plot of successive radio pulses from pulsar CP 1919 (Harold
Craft's PhD data). The signature look: thin white ridgelines on true black,
each line's silhouette blacking out the lines behind it, peaks concentrated in
the center with the edges pinned flat to the baseline. Here the "pulses" are
born from the live FFT — the music literally draws each line — with
synthesized CP 1919-style pulses taking over in silence.

## Rendering approach

- Base class: `ApotheneumPattern` (needs the cylinder, so no raster base).
- One 50×45 field (`int[] raster`) is computed per frame and shown everywhere:
  blitted to the cube's front exterior face, replicated to all 8 cube faces
  (exterior + interior) via `copyCubeFace(front)`, and sampled onto the
  cylinder with `x = cx * 50 / 120`; cylinder interior via
  `copyCylinderExterior()`. All four walls show the identical image.
- Line pool: `MAX_LINES = 48` preallocated `Line` slots (baseline, gain,
  `float[50]` shape), recycled — zero allocation in the render path. Shape
  generation is event-rate (at line birth) and uses preallocated scratch
  arrays only.
- Painter's algorithm: lines sorted back-to-front by baseline row (insertion
  sort into a preallocated `int[] order`); each line black-fills from just
  under its contour down to its baseline (the occlusion), then strokes the
  contour anti-aliased across two rows by its fractional height, so sub-pixel
  scrolling stays smooth at ambient speed.
- Scroll: continuous fractional row accumulator; lines are born just off the
  entry edge (a full `MAX_RIDGE_ROWS = 25` margin on the bottom-entry side so
  even the tallest ridge never pops in) and killed only once fully off-field.
- Door columns: bounded by `column.points.length` in both blit loops.

## Audio mapping

- `level` crossfades newborn line shapes between the synthesized pulse and the
  live spectrum: fully FFT-driven at level ≥ 0.15 (`FFT_CROSSFADE_LEVEL`,
  `CURATE:` knee vs typical music level).
- Live shape: the 16 `GraphicMeter` bands lerp-resampled to 50 samples, then
  multiplied by a center-heavy Hann^1.5 window (`WINDOW_POWER`, `CURATE:`)
  so edges pin to the baseline like the cover.
- `bassHit()` arms a one-shot boost: the next-born line gets 1.5× amplitude
  (`BASS_BOOST`) — at high energy line births are frequent, so boosts land
  close to the beat.
- Silence: shapes become pure CP 1919-style synth (sums of 2–3 random
  Gaussians near the center; `CURATE:` center spread and width ranges) and the
  waterfall keeps scrolling — the pattern is designed to look great with zero
  audio.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve |
|---|---|---|---|
| Full-field scroll (45 rows) | 12 s | 6 s | lin |
| Ridge amplitude multiplier | ×0.85 | ×1.15 | lin (`CURATE:` swing may be too subtle) |

Line birth rate follows scroll speed automatically (one line per `spacing`
rows), so energy also raises the event rate without a separate knob.

## Parameters

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| reseed | Reseed | Trigger | — | — | Clear and refill the whole stack |
| flip | Flip | Trigger | — | — | Reverse scroll direction |
| pulse | Pulse | Trigger | — | — | Inject one oversized synth pulse line |
| energy | Energy | Compound | 0.35 | 0–1 | Ambient ↔ 160 BPM master |
| spacing | Spacing | Compound | 2 | 2–8 | Rows between adjacent baselines |
| amplitude | Amp | Compound | 7 | 2–12 | Peak ridge height in rows |
| jaggedness | Jag | Compound | 0.15 | 0–1 | Noise baked into newborn profiles (`CURATE:` scale/smoothing) |
| tintHue | Tint | Compound | 0 | 0–360 | Stroke hue when TintAmt > 0 |
| tintAmount | TintAmt | Compound | 0 | 0–1 | 0 = classic white strokes, 1 = full tint |
| meta | Meta | Trigger | — | — | Random trigger or parameter jump |

## Triggers

- `reseed` — kills every line and refills the visible field at current
  spacing; reads as an instant scene reset (new shapes appear at once).
- `flip` — reverses scroll direction; existing lines retreat and new lines
  enter from the opposite edge. Direction change is instant, motion stays
  within the speed cap.
- `pulse` — births one 1.8× amplitude (`PULSE_GAIN`) synthesized line at the
  entry edge, spacing-safe; a single dramatic "pulsar spike" that then rides
  the waterfall for its full traversal (≥6 s visible life).

## Jump candidates

| Param | Jump range | Status | Notes |
|---|---|---|---|
| spacing | [2, 6] | candidate | Density jump; 2 = dense classic stack |
| amplitude | [4, 12] | candidate | Tall spikes vs calm ripples |
| tintHue | full (0–360) | candidate | Only visible when TintAmt > 0 |
| jaggedness | [0, 0.5] | candidate | Above ~0.5 lines may read as static (`CURATE:`) |

## Simulation-principles compliance

- Scroll traversal of the 45-row field: 12 s at e=0; at default e=0.35,
  rate = lin(0.35, 3.75, 7.5) ≈ 5.06 rows/s → **8.9 s**; at e=1 exactly
  **6 s** — always above the 5 s floor.
- Contours render at full brightness on true black (maximum contrast); the
  two-row anti-aliased stroke is the only gradient, used for motion
  smoothness, not texture.
- Minimum spacing of 2 rows keeps adjacent ridgelines separate at LED-gap
  scale; `MAX_RIDGE_ROWS` caps silhouettes so a single line can never fill
  the field.
- Event motion (pulse/reseed) changes content instantly but every line then
  develops over its full ≥6 s traversal.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | Wave 1 build (sidecar completed by coordinator; worker session ended at spend limit) |
