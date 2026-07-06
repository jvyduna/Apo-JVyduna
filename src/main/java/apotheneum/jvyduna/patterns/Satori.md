# Satori

After Dark-style psychedelic color cycling over a static phase field:
interference, moiré rings, angular sweeps or kaleidoscope folds.

> Sidecar design doc convention: this file lives beside `Satori.java` and is
> the source of truth for design decisions and curation history. Constants and
> behaviors that could not be visually verified are marked inline with
> `CURATE:` (grep for `CURATE:`).

## Original / inspiration

The classic palette-rotation ("color cycling") generators of the After Dark
screensaver family and demoscene plasma effects: a fixed grayscale/phase image
whose colors rotate through a lookup table, so rich motion appears with zero
per-frame geometry. This is an interpretation — no single After Dark module is
being cloned; the signature preserved is *bold psychedelic bands flowing
through a frozen pattern*, which is ideal at LED-sculpture resolution because
the motion is inherently smooth, slow, and full-field.

## Rendering approach

- **Base class**: `ApotheneumPattern` — per-point rendering over both
  components; no raster/Graphics2D needed since the render path is a single
  LUT lookup per point.
- **Surfaces**: cube exterior (200 columns × 45 rings, wrap-aware) and
  cylinder exterior (120 × 43) are computed; **interiors are verbatim copies**
  via `copyCubeExterior()` / `copyCylinderExterior()` each frame.
- **Phase field**: `float[model size]` indexed by `LXPoint.index`, filled per
  surface in 2D column/row coordinates (wrap-aware distances via min-image
  `dx`). After generation each surface is **normalized to span exactly
  [0,1]**, so `Spread` means exactly N color cycles across the surface and the
  traversal-cap math is exact for every variant.
- **Field variants** (`Field` enum, rebuilt only on trigger/enum change, one
  O(n) pass — never per frame):
  - `INTERFERENCE` — summed wrap-aware distances from 2–3 random centers:
    smooth ovoid interference lobes.
  - `RINGS` — moiré of two concentric ring sets: `d0/λ0 − d1/λ1` with two
    random centers and slightly different wavelengths
    (CURATE: λ0 ∈ 0.6–1.0×height, λ1 = 0.65–0.9×λ0 — beat-pattern density
    unverified on sculpture).
  - `ANGULAR` — 1–3 mirrored arms: triangle wave over `arms` copies of the
    azimuth (seam-free at the ring wrap for integer arms), plus a signed
    vertical pitch term → helical chevrons around the sculpture. The arm
    count must fold the azimuth rather than scale amplitude — the [0,1]
    normalization absorbs any constant scale (2026-07-05 fix; previously
    `arms` only nudged chevron tilt). (CURATE: pitch range ±0.5–1.5 cycles of
    tilt; CURATE: at arms=3 bands are 3× thinner locally — verify legibility
    at high `Spread`/`Bands`.)
  - `KALEIDO` — per-face mirror folds: x folded about the face center (fold
    width 50 on the cube = 4-fold symmetry, 40 on the cylinder = 3 mirrored
    sectors), y folded about mid-height; mixes a radial term with a
    folded-diagonal angular term. Mirror symmetry makes sector seams and face
    edges continuous by construction (CURATE: radial/angular mix seeded
    0.35–0.65).
- **Per-frame color**: `colors[i] = LUT[frac(field[i]·spread + phase +
  pulseDepth·pulseDist[i])]`, posterized to band centers. That's one
  multiply-add, a `frac`, a quantize and an array lookup per point — zero
  allocation, no trig, no per-frame geometry.
- **Color LUT**: 256-entry persistent `int[]`. Built from the project palette
  swatch (`lx.engine.palette.swatch`) when it has ≥ 2 colors — evenly spaced,
  lerped between neighbors, wrapping back to the first for cycle continuity —
  otherwise a `PerceptualHue` evenly-spaced rainbow. Rebuilt **only when the
  cached swatch colors actually change** (dynamic swatches trigger rebuilds
  automatically; 256 lerps, event-rate in practice).
- **Buffers**: `field`, `pulseDist` allocated on first build / model change
  only; `baseLut`, `frameLut`, swatch cache and all seed arrays preallocated.
  Field regeneration is flagged by triggers/parameter events and executed as
  one O(n) pass at the top of the next frame (thread-safe equivalent of
  regenerating inside the trigger handler).
- **Door columns**: all loops iterate `column.points.length`, never assuming
  full height; shortened columns simply sample fewer rows of the same field.

## Audio mapping

All reactivity is gated by the master `Audio` depth knob
(`CompoundParameter("Audio", 0)`, key `audio`), attached via
`audio.setDepth(audioDepth)`. **Depth 0 is the default**: every tap reads its
silence value and hits never fire, so the depth-0 baseline *is* the silence
behavior below. Magnitude taps scale linearly with depth; `bassHit()` fires
once depth > 0.01 and its pulse response is multiplied by `depth()` so a
barely-open knob pulses gently.

- `level` (extra-smoothed with a 1.2s time constant on top of AudioReactive's
  smoothing) modulates cycle speed ±50%: silence/depth-0 → 0.5×, average →
  1×, loud → 1.5×. Faster-than-base only when music is present; the cap math
  below budgets for the full 1.5×.
- `bassHit()` raises the pulse envelope to `depth()`; the envelope decays
  linearly over 2s. While active, each point's LUT phase is offset by
  `env · depth · pulseDist[i]` (precomputed normalized distance from a seeded
  center) — a radial wavefront that warps the bands outward from the center,
  then relaxes. No O(n) work on the hit itself; `pulseDist` is baked at field
  build time. (The same envelope is shared with the tempo-grid breath and the
  manual `Pulse` trigger — see Tempo mapping / Triggers.)
- `treble` lerps the whole LUT toward white by up to 30% — but only in the
  high-energy regime (fully gated off below energy 0.6). Applied as a 256-entry
  LUT pass, not per point. Depth-scaled like all magnitude taps.
- **Depth-0 / silence baseline**: a steady palette rotation at 0.5× the
  energy-set rate — the core look, fully presentable. No hits, no shimmer,
  no audio pulses (the tempo breath still runs if `Sync` is on — that is a
  tempo feature, not an audio one). Raising the knob to 1 restores the full
  speed modulation, bass pulses and treble shimmer described above.

## Tempo mapping

Default `TempoDiv` is `WHOLE` (one bar), fitting the meditative character.
Palette rotation has **no globally-simultaneous motion event to phase-align**
— posterized band flips are staggered across points by the field value — so
the lock has two parts:

- **Band-period quantization (sustained)**: the band-advance period (the
  visible rhythm — each point flips to its next posterized band once per
  `cyclePeriod / Bands`) is quantized to the nearest whole multiple, or unit
  fraction, of the `TempoDiv` period, implemented as a rate multiplier on the
  phase drift via `TempoLock.quantizePeriod()` (the shared helper extracted
  from this pattern's original hand-rolled latch). The chosen grid target is
  *latched* and only re-quantized when the required scale leaves the default
  `[0.7, 1.4]` window — hysteresis so the ±50% audio speed wobble near a
  rounding boundary cannot flip-flop the rate. `retime()` is deliberately not
  used: it targets a single phase-aligned future event, which a continuous
  cyclic drift does not have (per-frame reapplication would jitter). At the
  defaults (e=0.35, depth 0 → 25.1s effective cycle, 6 bands, 120 BPM) a band
  flip lands every 2 bars (~4s). CURATE: whether the latch hysteresis window
  feels stable under real music at high energy.
- **Grid breath (event)**: on every `TempoDiv` boundary
  (`TempoLock.crossed()`), the radial phase pulse fires at full strength —
  the same 2s-relax envelope as bass pulses, alive even at Audio depth 0.
  At 120 BPM / WHOLE this is a continuous gentle breath (2s period = 2s
  relax). CURATE: breath every bar may be too busy at fast tempos or small
  divisions — consider gating to HALF-or-slower if it strobes.

**Cap safety**: the quantization scale is clamped to
`min(1.4, cycleSec / (5 · speedMul))`, so `speedMul × syncScale ≤ cycleSec/5`
— worst-case traversal is exactly 5.0s (see compliance). `crossed()` is
called unconditionally each frame so its counter never goes stale while Sync
is off (re-enabling cannot fire a phantom pulse).

**Sync off** restores today's free-running behavior exactly: no rate
quantization (scale 1), no grid breath; bass pulses and the manual `Pulse`
trigger still work.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Color cycle period | 16 s | 8 s | exp (`Ranges.exp`) |
| Radial pulse depth (LUT cycles; bass/breath/trigger) | 0.15 | 0.35 | lin (`Ranges.lin`) |
| Treble shimmer gain | 0 (gated ≤ 0.6) | 0.30 lerp-to-white | lin above gate |

Energy scales rate/pulse/shimmer only; the ≥5s traversal cap holds at e=1
(math below). CURATE: pulse depths and shimmer gain/gate are unverified on
sculpture.

## Parameters

UI order: triggers first, Energy, pattern parameters, Audio, Sync, TempoDiv,
Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `newField` | NewField | TriggerParameter | — | — | reseed centers/seeds, keep the current variant |
| `reverse` | Reverse | TriggerParameter | — | — | flip the color-cycle direction |
| `pulse` | Pulse | TriggerParameter | — | — | launch the radial phase pulse manually (full strength) |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (cycle rate, pulse depth, shimmer) |
| `fieldMode` | Field | EnumParameter&lt;FieldMode&gt; | INTERFERENCE | 4 variants | static phase-field variant |
| `speed` | Speed | CompoundParameter | 1 | 0.25..1 | trim on the energy-set cycle rate; can only slow (cap-safe) |
| `spread` | Spread | CompoundParameter | 2 | 1..6 | color cycles across each surface |
| `posterize` | Bands | CompoundDiscreteParameter | 6 | 2..16 | quantize the LUT into N bold bands |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full |
| `sync` | Sync | BooleanParameter | true | on/off | lock band period + breath pulse to the tempo grid |
| `tempoDiv` | TempoDiv | EnumParameter&lt;Tempo.Division&gt; | WHOLE | all divisions | grid that band flips and the breath land on |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

## Triggers

Three non-meta triggers spanning small → large:

- `reverse` (small) — band motion direction flips. Takes a few seconds to
  read at ambient rates (bands visibly change travel direction);
  instantaneous state change, no discontinuity in the image itself.
- `pulse` (medium) — the radial phase pulse fires at full strength regardless
  of Audio depth: bands warp outward from the seeded center and relax over
  2s (≥ 1.5s event life). Same envelope as bass hits and the grid breath.
- `newField` (large) — the whole field snaps to a new random layout (new
  centers, wavelengths, arms, folds) in the current variant. Reads instantly
  as a scene change; the ongoing color rotation resumes over the new
  geometry, so it settles within one perceptual beat.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation. (`newField`, `reverse` and `pulse` are also
registered into the bag as triggers.)

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `fieldMode` | all 4 variants | candidate | variant swap without reseeding — A/B on one layout |
| `speed` | 0.4..1.0 | candidate | curated subrange; avoids the near-frozen 0.25 floor |
| `spread` | 1..6 (full) | candidate | CURATE: 6 with 16 bands may be too fine (see compliance) |
| `posterize` | 2..16 (full) | candidate | 2 = duotone slabs, 16 = near-smooth gradient |

## Simulation-principles compliance

**Sustained motion.** All sustained motion is phase drift. Because each
surface's field is normalized to exactly [0,1], an iso-color band crosses the
*entire* sculpture in `spread × cyclePeriod / (audioBoost × syncScale)`
seconds. Worst case is the fastest configuration: `spread = 1`, `speed = 1`,
max audio boost 1.5×, sync retiming at its cap:

- Cycle period `T(e) = Ranges.exp(e, 16, 8) = 16·(0.5)^e`
- The sync quantization scale is clamped to
  `capMax = min(1.4, T/(5·speedMul))`, so `speedMul × syncScale ≤ T/5` always.
- **Energy = 1**: `T = 8s`; at full boost (speedMul 1.5) the cap yields
  `capMax = 1.067` → combined 1.6× → worst-case traversal `8 / 1.6 = 5.0s ≥
  5s` ✓ (exactly at the cap by construction).
- **Default energy (0.35)**: `T ≈ 12.55s` → worst-case traversal `12.55 /
  min(1.5·1.4, 12.55/5) ≈ 12.55 / 2.1 ≈ 6.0s ≥ 5s` ✓
- At the actual defaults (`spread = 2`, Audio depth 0 → 0.5× floor, sync
  scale ≤ 1.4): traversal ≥ `2 × 12.55 / 0.7 ≈ 36s` — glacial, as intended.
- Ambient (e=0) worst-case drift period under max boost + sync is `16 / 2.1
  ≈ 7.6s` (pre-sync build: 10.7s). CURATE: verify ambient still reads
  meditative at that corner (loud music + Sync on + e=0).
- The `speed` trim only divides *below* 1× rate, so it can never violate the
  cap; the meta jump subrange (0.4–1.0) respects this too.

**Event motion.** The radial pulse (bass hit, grid breath, or manual `Pulse`
trigger) is event-like: an instantaneous radial warp relaxing over 2.0s —
above the 1.5s minimum visual life. It offsets phase by at most 0.35 cycles,
so it reads as a wave through the existing bands, not a strobe.

**Bold forms.** The posterize default of 6 bands over `spread = 2` gives ≈17
columns per band on the cube ring (200 / 12) — large, high-contrast slabs
with hard edges, legible through LED gaps. Smooth gradients are opt-in only at
high `Bands` values. CURATE: at the extremes (`spread = 6` × `Bands = 16`,
≈2 columns/band) bands approach fine texture; if illegible on sculpture,
re-range `spread` to 1..4 or cap `Bands`.

**Brightness.** Colors come straight from the palette swatch / PerceptualHue
at full saturation; shimmer adds ≤30% lerp-to-white only at high energy.
CURATE: whether full-brightness posterized fields need a global brightness
trim on the physical LEDs.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |
| 2026-07-05 | Review/upgrade session: fixed ANGULAR `arms` no-op (amplitude scale was absorbed by field normalization; now folds the azimuth into 1–3 real mirrored arms); added `Audio` depth knob (default 0) via `AudioReactive.setDepth` — depth-0 now the documented baseline, bass-pulse response scaled by `depth()`; added `Sync`/`TempoDiv` (default WHOLE) — band-period quantized to the grid with latch hysteresis and a cap-safe clamp (worst traversal pinned to 5.0s), grid breath pulse on each division boundary; added third non-meta trigger `Pulse` (manual radial pulse); doc corrections (compliance math incl. sync, removed unfounded "≥8s ambient requirement" phrasing) | Series-wide audio-depth/tempo-sync upgrade + bug hunt |
| 2026-07-05 | Integration pass: hand-rolled band-period latch (`syncTargetMs`/`syncDivMs`) extracted verbatim into shared `TempoLock.quantizePeriod(periodMs, division[, minScale, maxScale])`; Satori now calls the helper with the default `[0.7, 1.4]` window and keeps its pattern-local `capMax` traversal clamp on top (behavior-identical: helper output ≥ 0.7 and `capMax` ≥ 1.067 > 0.7, so `min(s, capMax)` reproduces the old three-way clamp) | Satori agent requested a shared period-quantization helper so other rotation/plasma-style patterns don't re-hand-roll the latch |
