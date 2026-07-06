# Unknown Pleasures

CP 1919 pulsar ridgeline waterfall: stacked, occluding spectrum silhouettes scrolling like the 1979 Joy Division "Unknown Pleasures" album cover.

## Original / inspiration

Peter Saville's cover for Joy Division's *Unknown Pleasures* (1979), itself a
stacked-ridgeline plot of successive radio pulses from pulsar CP 1919 (Harold
Craft's PhD data). The signature look: thin white ridgelines on true black,
each line's silhouette blacking out the lines behind it, peaks concentrated in
the center with the edges pinned flat to the baseline. Here the "pulses" are
born from the live FFT — the music literally draws each line — with
synthesized CP 1919-style pulses taking over in silence (and, by default,
whenever the Audio depth knob sits at 0).

## Rendering approach

- Base class: `ApotheneumPattern` (needs the cylinder, so no raster base).
- One 50×45 field (`int[] raster`) is computed per frame and shown everywhere:
  blitted to the cube's front exterior face, replicated to all 8 cube faces
  (exterior + interior) via `copyCubeFace(front)`, and sampled onto the
  cylinder with `x = cx * 50 / 120`; cylinder interior via
  `copyCylinderExterior()`. All four walls show the identical image. (The
  cylinder's 43 rows show the top 43 of the 45-row field.)
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
  Reseed pre-fills that bottom margin too when scrolling upward, so the reset
  never causes a one-frame contour pop-in.
- Door columns: bounded by `column.points.length` in both blit loops.

## Audio mapping

Audio depth knob: `CompoundParameter("Audio", 0)` (key `audio`), attached via
`audio.setDepth(audioDepth)`. **Default 0 = pure screensaver**: `level` reads
0, so every newborn line is a synthesized CP 1919 pulse, and `bassHit()` never
fires, so no boosts — the silence behavior below is exactly the default
behavior. Raising the knob restores, continuously:

- `level` crossfades newborn line shapes between the synthesized pulse and the
  live spectrum: fully FFT-driven at (depth-scaled) level ≥ 0.15
  (`FFT_CROSSFADE_LEVEL`, `CURATE:` knee vs typical music level). Because the
  `level` tap scales linearly with depth, partial depth effectively raises the
  crossfade knee: at depth d, full-FFT shapes require raw level ≥ 0.15/d, and
  below that the FFT contribution mixes in proportionally. The band resample
  itself is raw (not depth-attenuated) so FFT-drawn lines keep full stature;
  depth controls *how often/how much* the FFT wins the crossfade, not the
  height of the drawn spectrum. (`CURATE:` verify partial-depth crossfade
  feels continuous with real music.)
- Live shape: the 16 `GraphicMeter` bands lerp-resampled to 50 samples, then
  multiplied by a center-heavy Hann^1.5 window (`WINDOW_POWER`, `CURATE:`)
  so edges pin to the baseline like the cover.
- `bassHit()` arms a one-shot boost for the next-born line, scaled by depth:
  gain = 1 + 0.5×depth, i.e. the full 1.5× (`BASS_BOOST`) at depth 1 fading to
  no boost near 0. With Sync on, births land on the tempo grid, so boosted
  lines appear on division boundaries; the boosted line's *peak* still takes
  a few rows of scroll to clear the entry edge (`CURATE:` boost-to-visible
  latency, worst when scrolling up through the 25-row bottom margin).
- Silence (or depth 0): shapes are pure CP 1919-style synth (sums of 2–3
  random Gaussians near the center; `CURATE:` center spread and width ranges)
  and the waterfall keeps scrolling — the pattern is designed to look great
  with zero audio.

## Tempo mapping

Default division: `QUARTER`. With Sync on (default):

- **Line births land on the tempo grid.** Births are driven by scroll
  position (one per `spacing` rows), so at each birth the time to the next
  one (`spacing / nominalRate`) is passed to `TempoLock.retime()` and the
  returned scale is applied to the scroll rate until that next birth. The
  scale *replaces* the previous one (never compounds), so the rate always
  stays within the clamp of the nominal energy-derived rate. Each new
  ridgeline therefore enters on a division boundary; the visible effect is a
  gentle speed "breathing" of the waterfall, never a jump — the undulation
  stays continuous. (`CURATE:` at large Spacing the inter-birth interval is
  seconds long and the breathing may read as slow surging.)
- **Clamp override:** the upper retime clamp is
  `min(1.4, (45 rows / 5 s) / nominalRate)` — derived from the series 5 s
  traversal cap, so retiming can never make the scroll faster than a 5 s
  full-field traversal (at e=1 the effective clamp is 1.2). Lower clamp is
  the default 0.7.
- **Pulse launch is quantized.** While Sync is on, the Pulse trigger arms a
  pending pulse that fires on the next `TempoDiv` boundary
  (`TempoLock.crossed()`, evaluated every frame so the gate stays fresh).
- Sync off: the retime multiplier resets to 1 and Pulse fires immediately —
  bit-identical to the free-running pre-sync behavior at all energies.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve |
|---|---|---|---|
| Full-field scroll (45 rows) | 12 s | 6 s | lin |
| Ridge amplitude multiplier | ×0.85 | ×1.15 | lin (`CURATE:` swing may be too subtle) |

Line birth rate follows scroll speed automatically (one line per `spacing`
rows), so energy also raises the event rate without a separate knob. Sync
retiming modulates the scroll rate by at most [0.7, min(1.4, cap)] around
these nominal values.

## Parameters

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| reseed | Reseed | Trigger | — | — | Clear and refill the whole stack |
| flip | Flip | Trigger | — | — | Reverse scroll direction |
| pulse | Pulse | Trigger | — | — | Inject one oversized synth pulse line (grid-quantized when Sync on) |
| energy | Energy | Compound | 0.35 | 0–1 | Ambient ↔ 160 BPM master |
| spacing | Spacing | Compound | 2 | 2–8 | Rows between adjacent baselines |
| amplitude | Amp | Compound | 7 | 2–12 | Peak ridge height in rows |
| jaggedness | Jag | Compound | 0.15 | 0–1 | Noise baked into newborn profiles (`CURATE:` scale/smoothing) |
| tintHue | Tint | Compound | 0 | 0–360 | Stroke hue when TintAmt > 0 |
| tintAmount | TintAmt | Compound | 0 | 0–1 | 0 = classic white strokes, 1 = full tint |
| audio | Audio | Compound | 0 | 0–1 | Audio reactivity depth: 0 = pure screensaver, 1 = full FFT shapes + bass boosts |
| sync | Sync | Boolean | true | — | Lock line births / pulse launches to the tempo grid |
| tempoDiv | TempoDiv | Enum | QUARTER | Tempo.Division | Division that births and pulse launches land on |
| meta | Meta | Trigger | — | — | Random trigger or parameter jump |

## Triggers

Roster spans small → large: pulse (one new line), flip (behavior reversal),
reseed (full scene reset).

- `pulse` (small) — births one 1.8× amplitude (`PULSE_GAIN`) synthesized line
  at the entry edge, spacing-safe; a single dramatic "pulsar spike" that then
  rides the waterfall for its full traversal (≥6 s visible life). Launch is
  quantized to the next `TempoDiv` boundary while Sync is on. When scrolling
  *up*, the spike is born below the 25-row bottom margin and takes several
  seconds of scroll to enter view (`CURATE:` acceptable latency vs kill the
  margin line and insert closer?); scrolling down it appears within ~a second.
- `flip` (medium) — reverses scroll direction; existing lines retreat and new
  lines enter from the opposite edge. Direction change is instant, motion
  stays within the speed cap.
- `reseed` (large) — kills every line and refills the visible field (plus the
  bottom entry margin when scrolling up) at current spacing; reads as an
  instant scene reset (new shapes appear at once).

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
- Tempo retiming cannot break the floor: its upper clamp is computed per call
  as `min(1.4, 9 rows/s ÷ nominal)`, so the retimed rate never exceeds
  9 rows/s = exactly a 5.0 s traversal (at e=1: 7.5 × 1.2 = 9.0; at e=0:
  3.75 × 1.4 = 5.25 rows/s → 8.6 s).
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
| 2026-07-05 | Review + upgrade: added Audio depth knob (`audio`, default 0 = pure synth, gates FFT crossfade and depth-scales bass boost to 1+0.5d); added `sync`/`tempoDiv` — line births grid-locked via per-birth `TempoLock.retime()` with 5 s-cap-derived upper clamp, Pulse launch quantized via `crossed()`; fixed reseed-while-scrolling-up leaving the bottom entry margin empty (one-frame contour pop-in); documented pulse-visibility latency when scrolling up | Series-wide audio-depth/tempo-sync pass + bug hunt |
| 2026-07-05 | Adversarial review fix: `crossed()` now evaluated every frame regardless of Sync, not only on sync frames — a Sync-off interval left the cycle-count gate stale, so the first sync frame back returned a spurious boundary and could fire a just-armed Pulse off-grid | Match the documented "evaluated every frame" behavior |
