# Mountains

Fractal mountain ranges accumulate in layers around the cube and cylinder, After
Dark style.

> Sidecar design doc convention: this file lives beside `Mountains.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

After Dark 1.0 for DOS — the "Mountains" module: fractal mountain ranges drawn
one at a time, each new range in front of (and painted over) the previous ones,
until the screen fills and the sequence restarts. The visual signature preserved
here: 1D midpoint-displacement ridgelines, hard altitude-banded fills
(water/forest/rock/snow), front-over-back accumulation with the baseline stepping
downward each range, and a full-field restart when the screen is full.

The Apotheneum-specific move: a ridge is a **closed loop**. On the cube it wraps
continuously around all four faces (one 200-column strip); on the cylinder it
wraps its 120 columns. Endpoints of the heightfield are matched so there is no
seam — you can walk around the sculpture and the ridgeline never breaks.

## Rendering approach

- **Base class**: `ApotheneumPattern` — needs both cube and cylinder ring
  geometry plus exterior→interior copies; no 2D Graphics needed
  (`ApotheneumRasterPattern` is cube-face-only anyway).
- **Surfaces**: cube exterior + cylinder exterior, each with its own independent
  ridge sequence (same parameters, different random rolls). Interiors receive an
  exact copy via `copyCubeExterior()` / `copyCylinderExterior()`.
- **Geometry mapping**: two `SurfaceCanvas` buffers — 200×45 for the cube ring
  (4 × `GRID_WIDTH` columns), 120×43 for the cylinder — drawn in canvas space and
  copied column-major to the surfaces. Y = 0 is the top row.
- **Buffers** (all preallocated in the constructor, zero-alloc render):
  - `SurfaceCanvas` per surface — the committed picture. Ridges are painted
    *into* it permanently as the reveal wipe passes each column, so no second
    "compositing" buffer is needed.
  - `float[201]` / `float[121]` heightfield scratch per surface — regenerated in
    place at each spawn (spawn-time recursion only, never per-frame).
  - `int[4]` band colors per surface, baked at spawn.
- **Ridge generation**: recursive 1D midpoint displacement over `[0, width]`
  with `ridge[0] = ridge[width]` (wrap-matched endpoints → seamless loop; C0 at
  the seam, slope kink invisible at LED pitch). Amplitude falloff per octave =
  `0.40 + 0.35 × effectiveRoughness` — 0.40 reads as rolling hills, 0.75 as
  jagged alpine. The result is min-max normalized to `[0, reliefRows]` so every
  ridge uses its full band range (roughness controls *shape*, Relief controls
  *amplitude*).
- **Accumulation**: ridge *n* has `baseline(n) = baseline(0) + 6n` rows
  (`BASELINE_STEP_ROWS`); first baseline = `reliefRows + 2` so the first range's
  peaks just clear the top. Each ridge is filled from its crest down to the
  bottom row, occluding everything behind it. A depth-haze cue bakes farther
  ridges dimmer (`0.55 → 1.0` linear over the cycle) so the nearest range is
  boldest. CURATE: haze floor 0.55 — verify far ridges still read behind near ones.
- **Elevation bands** (hard edges, no gradients): color by rows above the
  ridge's own baseline. Thresholds at 6/12/18 rows (+ Bands offset): base
  water-blue, forest green, rock amber, snow near-white. 6-row spacing exceeds
  the 4-row LED legibility minimum. Snow is dropped entirely (threshold →
  ∞) if fewer than 4 rows of it would show, so it never appears as a sliver.
  CURATE: fixed band hues/sats/brightness (225/85/35, 130/75/48, 30/45/62,
  0/10/100) chosen blind, not from the project palette — swap to
  `lx.engine.palette.swatch` later if curation prefers project-palette shows.
- **Reveal**: a new ridge wipes in left-to-right around the ring from a random
  start column, painting columns permanently as the front passes (monotonic
  front ⇒ single canvas suffices). One ridge reveals at a time per surface.
- **Restart**: when even the tallest possible next crest would be below the
  bottom row (`baseline − reliefRows ≥ height`), the field holds one spawn
  interval, then fades to black over 2 s (`SurfaceCanvas.decay` per frame,
  targeting 0.4% residual) and the cycle restarts. Each surface restarts
  independently when *it* is full; the `Wipe` trigger restarts both together.
- **Invert (cave mode)**: flips the *entire render* vertically at copy time
  (canvas row `height−1−y`), chosen over per-ridge inversion so the whole scene
  reads coherently as stalactites; applies instantly on trigger and toggles back.
- **Door columns**: the canvas→surface copy is a local `copyLifted` that mirrors
  `SurfaceCanvas.copyTo`'s guard (`min(canvas.height, column.points.length)`);
  it exists only because `copyTo` has no brightness-scale or flip hook (see
  final-report note).

## Audio mapping

`AudioReactive`, ticked first line of `render()`:

- **treble** (smoothed) — added to base Rough at spawn time
  (`+0.6 × treble`, clamped to 1): bright/hissy passages spawn more jagged ridges.
- **bassHit()** — above Energy ≥ 0.55, a due spawn waits for the next bass
  transient (beat-locked feel), with a 3 s fallback so it never stalls.
- **level** — global brightness lift: pixels are baked at full band brightness
  and displayed at `80% + 20% × min(1, 1.25 × level)`. Gentle glow with the
  music, never clips.

**Silence behavior**: taps decay to 0 → spawns run purely on the idle timer
(the bass-hit gate falls back after 3 s even at high energy), ridges use the
Rough knob's value unmodified, and the field sits at a steady 80% brightness.
The full accumulate–fill–fade cycle runs identically without audio.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Reveal wipe duration (full ring) | 8 s | 5 s | lin |
| Idle gap between ridges | 14 s | 2.5 s | exp (÷ Spawn param) |
| Bass-hit spawn gating | off | on (from e ≥ 0.55) | threshold |

Sustained motion respects the ≥5 s full-traversal cap even at e=1 (see
compliance section).

## Parameters

UI order: triggers first, Energy, pattern parameters, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `wipe` | Wipe | TriggerParameter | — | — | fade both fields to black over 2 s, restart |
| `newRidge` | NewRidge | TriggerParameter | — | — | spawn a ridge now (idle fields only; full field fades instead) |
| `invert` | Invert | TriggerParameter | — | — | toggle cave mode (whole render flips vertically) |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (ambient ↔ 160 BPM regime) |
| `roughness` | Rough | CompoundParameter | 0.5 | 0..1 | base jaggedness of new ridges (treble adds on top) |
| `relief` | Relief | CompoundParameter | 0.55 | 0.2..0.75 | ridge peak height as fraction of surface height |
| `bandOffset` | Bands | CompoundParameter | 0 | −1..1 | shifts all band thresholds ±4 rows (+ raises snowline) |
| `hueShift` | Hue | CompoundParameter | 0 | 0..360 | rotates all band hues (snow stays near-white) |
| `spawnRate` | Spawn | CompoundParameter | 1 | 0.25..4 | multiplier on ridge spawn rate |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

New-ridge appearance (Rough/Bands/Hue/Relief changes) applies to *subsequently
spawned* ridges; already-painted ridges are committed pixels.

## Triggers

- `wipe` — both surfaces fade to black over 2 s, then the accumulation restarts
  from the first (farthest) ridge. Reads immediately, resolves in 2 s.
- `newRidge` — an idle surface spawns immediately (skipping the idle timer and
  bass gate); the ridge then takes its normal 5–8 s wipe to reveal. Ignored
  while that surface is revealing or fading (logged); on a full field it starts
  the restart fade instead.
- `invert` — instant whole-render vertical flip into/out of cave mode
  (mountains become stalactites hanging from the top). Event-like state change;
  the resulting image persists indefinitely.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation. All three triggers are also registered in the bag.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `roughness` | [0.2, 0.9] | candidate | extremes excluded: 0 too flat, 1 + treble saturates |
| `bandOffset` | [−0.75, 1.0] | candidate | full-negative floor avoided so base band survives |
| `hueShift` | [0, 360] (full) | candidate | any rotation is safe; snow anchors the look |
| `spawnRate` | [0.5, 2] | candidate | outer range reserved for manual performance use |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

- **Reveal wipe (sustained motion)** — 200 cube columns per full traversal.
  Ambient (e=0.35 → ~7 s; e=0 → 8 s): ≥ 25 col/s. Peak (e=1): 200 col / 5 s =
  40 col/s → **5.0 s full traversal, exactly at the ≥5 s cap**. The cylinder
  wipes its 120 columns in the same 5–8 s (24 col/s max), slower still.
  `REVEAL_SECONDS_PEAK` is a named constant; nothing else scales it.
- **Restart fade (event-like)** — 2 s from full field to black, auto-triggered
  roughly once per cycle (~3 min ambient, ~60 s at peak: 8 ridges ×
  (reveal + idle)). 2 s ≥ 1.5 s event minimum, and it is preceded by a full
  idle interval of hold so the completed scene rests before dissolving.
- **Everything else is static** — committed ridges do not move; the only other
  temporal change is the global brightness lift (80–100%, no spatial motion)
  and the ~once-per-cycle invert/wipe triggers.
- **Bold forms / contrast** — hard-edged elevation bands with 6-row threshold
  spacing (≥ 4-row minimum), no gradients, no fine texture; adjacent bands
  differ in both hue and brightness (35→48→62→100). Depth is a single baked
  brightness step per ridge, not a gradient. CURATE: verify the dimmest
  combination (farthest ridge base band at silence ≈ 35 × 0.55 × 0.8 ≈ 15%
  brightness) still reads on hardware; raise `FAR_BRIGHTNESS` if it vanishes.
- CURATE: `BASELINE_STEP_ROWS = 6` gives ~8 ridges/cycle at default Relief —
  confirm the layering density reads as distinct ranges, not stripes.
- CURATE: random wipe start column per ridge — confirm it reads as intentional
  variety rather than inconsistency.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |
