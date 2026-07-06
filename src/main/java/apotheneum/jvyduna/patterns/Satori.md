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
  [0,1]**, so `Width` maps to exactly N color cycles across the surface
  (inverted: `cycles = 7 − width`) for every variant.
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
    at low `Width` / high `Bands`.)
  - `KALEIDO` — per-face mirror folds: x folded about the face center (fold
    width 50 on the cube = 4-fold symmetry, 40 on the cylinder = 3 mirrored
    sectors), y folded about mid-height; mixes a radial term with a
    folded-diagonal angular term. Mirror symmetry makes sector seams and face
    edges continuous by construction (CURATE: radial/angular mix seeded
    0.35–0.65).
- **Per-frame color**: `colors[i] = LUT[frac(field[i]·cycles + phase +
  pulseDepth·pulseDist[i])]`, posterized to band centers. That's one
  multiply-add, a `frac`, a quantize and an array lookup per point — zero
  allocation, no trig, no per-frame geometry. `Smooth` widens each band edge
  into a smoothstep ramp over the trailing `smooth` fraction of the band
  (0 = hard edges as above; 1 = every pixel smoothsteps continuously from one
  band's center color to the next; the cyclic LUT index mask handles the
  last-band wrap). One branch + a few multiplies per point when engaged.
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

All three taps were amplified ~6× in the 2026-07-06 curation pass — the
knob's effect was previously imperceptible on sculpture (CURATE: all three
gains below are unverified at the new levels):

- `level` (extra-smoothed with a 1.2s time constant on top of AudioReactive's
  smoothing) boosts cycle speed, boost-only: `speedMul = 1 + 3·level`.
  Silence/depth-0 → exactly 1× (the `Speed` knob alone owns the base rate;
  the old bipolar ±50% mapping with its 0.5× silence floor is gone), loud
  music at full depth → up to 4×.
- `bassHit()` raises the pulse envelope to `depth()`; the envelope decays
  linearly over 2s. While active, each point's LUT phase is offset by
  `env · PULSE_DEPTH · pulseDist[i]` (precomputed normalized distance from a
  seeded center) — a radial wavefront that warps the bands outward from the
  center, then relaxes. `PULSE_DEPTH = 1.3` LUT cycles (was 0.15–0.35
  energy-scaled). No O(n) work on the hit itself; `pulseDist` is baked at
  field build time. (The same envelope is shared with the manual `Pulse`
  trigger — see Triggers.)
- `treble` lerps the whole LUT toward white by up to 100% (was 30%, and
  gated off below energy 0.6 — the gate went away with the Energy knob).
  Applied as a 256-entry LUT pass, not per point. Depth-scaled like all
  magnitude taps.
- **Depth-0 / silence baseline**: a steady palette rotation at the
  `Speed`-set rate — the core look, fully presentable. No hits, no shimmer,
  no audio pulses. Raising the knob to 1 restores the full speed boost, bass
  pulses and treble shimmer described above.

## Tempo mapping

None (as of 2026-07-06). The original build quantized the band-advance
period to the tempo grid via `TempoLock.quantizePeriod()` and fired a
grid-breath pulse on each `TempoDiv` boundary; both were removed along with
the `Sync`/`TempoDiv` parameters during curation — free-running timing reads
better for this pattern and the extra controls weren't earning their space.
(`TempoLock` itself remains in the util package; other patterns use it.)

## Energy mapping

The `Energy` master knob was removed in the 2026-07-06 curation pass; its
three targets are now fixed constants in `Satori.java`: cycle period
`CYCLE_SEC = 8s` (scaled by `Speed`), radial pulse depth `PULSE_DEPTH = 1.3`
LUT cycles, treble shimmer max `SHIMMER_MAX = 1.0` (all marked CURATE
inline).

## Parameters

UI order (2026-07-06 series convention): triggers first with RndTrig
immediately after them, then pattern parameters, Audio last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `newField` | NewField | TriggerParameter | — | — | reseed centers/seeds, keep the current variant |
| `reverse` | Reverse | TriggerParameter | — | — | flip the color-cycle direction |
| `pulse` | Pulse | TriggerParameter | — | — | launch the radial phase pulse manually (full strength) |
| `rndTrig` | RndTrig | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |
| `fieldMode` | Field | EnumParameter&lt;FieldMode&gt; | INTERFERENCE | 4 variants | static phase-field variant |
| `speed` | Speed | CompoundParameter (%) | 1 (100%) | 0..2 | cycle rate: 0% pauses, 100% = 8s/cycle, 200% = 4s |
| `width` | Width | CompoundParameter | 5 | 1..6 | band width: higher = wider bands, fewer repeats (`cycles = 7 − width`) |
| `posterize` | Bands | CompoundDiscreteParameter | 6 | 2..16 | quantize the LUT into N bold bands |
| `smooth` | Smooth | CompoundParameter | 0 | 0..1 | band-edge interpolation: 0 = hard edges, 1 = full smoothstep gradient |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full |

Renames/removals (2026-07-06): `energy`, `sync`, `tempoDiv` removed;
`spread` → `width` (action reversed); `meta` → `rndTrig`. Any `.lxp`
modulation/automation bound to the old keys must be re-bound.

## Triggers

Three non-RndTrig triggers spanning small → large:

- `reverse` (small) — band motion direction flips. Takes a few seconds to
  read at ambient rates (bands visibly change travel direction);
  instantaneous state change, no discontinuity in the image itself.
- `pulse` (medium) — the radial phase pulse fires at full strength regardless
  of Audio depth: bands warp outward from the seeded center and relax over
  2s (≥ 1.5s event life). Same envelope as bass hits. CURATE: at the new
  `PULSE_DEPTH = 1.3` the warp is ~4× deeper than before — verify it reads
  as a wave, not a scene change.
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
| `speed` | 0.5..1.5 | candidate | curated subrange; avoids jumping to a frozen pattern |
| `width` | 1..6 (full) | candidate | CURATE: width 1 (6 cycles) with 16 bands may be too fine (see compliance) |
| `posterize` | 2..16 (full) | candidate | 2 = duotone slabs, 16 = near-smooth gradient |

## Simulation-principles compliance

**Sustained motion.** All sustained motion is phase drift. Because each
surface's field is normalized to exactly [0,1], an iso-color band crosses the
*entire* sculpture in `cycles × CYCLE_SEC / (speed × speedMul)` seconds,
where `cycles = 7 − width`.

- At the defaults (`width = 5` → 2 cycles, `speed = 1`, Audio depth 0 →
  speedMul 1): traversal `2 × 8 = 16s` — slow, as intended.
- Worst case (`width = 6` → 1 cycle, `speed = 2`, full audio boost 4×):
  traversal `8 / 8 = 1s`. This **knowingly breaks the old ≥5s series
  traversal cap** — user-directed curation (2026-07-06): Speed's 200%
  ceiling and the 6× audio boost take priority; the fast corner is opt-in
  via two knobs, and the defaults remain glacial. The old sync-cap budget
  machinery went away with tempo locking.

**Event motion.** The radial pulse (bass hit or manual `Pulse` trigger) is
event-like: an instantaneous radial warp relaxing over 2.0s — above the 1.5s
minimum visual life. At `PULSE_DEPTH = 1.3` it offsets phase by up to 1.3
cycles (CURATE: verify it still reads as a wave through the bands, not a
strobe — see Triggers).

**Bold forms.** The posterize default of 6 bands over 2 cycles (`width = 5`)
gives ≈17 columns per band on the cube ring (200 / 12) — large,
high-contrast slabs with hard edges, legible through LED gaps. Smooth
gradients are opt-in via high `Bands` values or the `Smooth` knob. CURATE:
at the extremes (`width = 1` → 6 cycles × `Bands = 16`, ≈2 columns/band)
bands approach fine texture; if illegible on sculpture, re-range or cap.

**Brightness.** Colors come straight from the palette swatch / PerceptualHue
at full saturation; shimmer lerps toward white by up to 100% on loud treble
at full Audio depth (CURATE: verify the white-out reads as shimmer, not a
flash). Whether full-brightness posterized fields need a global brightness
trim on the physical LEDs is still unverified.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |
| 2026-07-05 | Review/upgrade session: fixed ANGULAR `arms` no-op (amplitude scale was absorbed by field normalization; now folds the azimuth into 1–3 real mirrored arms); added `Audio` depth knob (default 0) via `AudioReactive.setDepth` — depth-0 now the documented baseline, bass-pulse response scaled by `depth()`; added `Sync`/`TempoDiv` (default WHOLE) — band-period quantized to the grid with latch hysteresis and a cap-safe clamp (worst traversal pinned to 5.0s), grid breath pulse on each division boundary; added third non-meta trigger `Pulse` (manual radial pulse); doc corrections (compliance math incl. sync, removed unfounded "≥8s ambient requirement" phrasing) | Series-wide audio-depth/tempo-sync upgrade + bug hunt |
| 2026-07-05 | Integration pass: hand-rolled band-period latch (`syncTargetMs`/`syncDivMs`) extracted verbatim into shared `TempoLock.quantizePeriod(periodMs, division[, minScale, maxScale])`; Satori now calls the helper with the default `[0.7, 1.4]` window and keeps its pattern-local `capMax` traversal clamp on top (behavior-identical: helper output ≥ 0.7 and `capMax` ≥ 1.067 > 0.7, so `min(s, capMax)` reproduces the old three-way clamp) | Satori agent requested a shared period-quantization helper so other rotation/plasma-style patterns don't re-hand-roll the latch |
| 2026-07-06 | Curation pass from sculpture review: removed `Energy` (targets now fixed constants: `CYCLE_SEC = 8`, `PULSE_DEPTH = 1.3`, `SHIMMER_MAX = 1.0`, shimmer energy-gate deleted); removed tempo locking entirely (`Sync`, `TempoDiv`, `TempoLock` usage, grid breath, traversal-cap budget); `Speed` re-ranged 0..2 (%, multiplies rate: 0% pauses, 100% = old max 8s/cycle, 200% = 4s); `Spread` → `Width` with action reversed (`cycles = 7 − width`); added `Smooth` (smoothstep band-edge interpolation, 0 = old hard bands); Audio max effect ×6 (speed boost now boost-only `1 + 3·level`, old 0.5× silence floor removed); `Meta` → `RndTrig`; speed jump range 0.5..1.5 | Jeff's curation notes: Energy/Sync not earning their space, Audio imperceptible, Spread direction backwards, wanted pause + faster ceiling on Speed and optional smooth color interpolation |
| 2026-07-06 | Series RndTrig ordering: `rndTrig` moved from last to 4th, immediately after the three triggers (no rename needed — already RndTrig) | Jeff 2026-07-06: TriggerBag meta trigger sits right after the other trigger params in every pattern |
| 2026-07-06 | Series RndTrig ordering: `rndTrig` moved from last to 4th, immediately after the three triggers (already named RndTrig) | Series convention: TriggerBag meta trigger sits right after the other trigger params |
