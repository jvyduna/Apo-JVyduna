# Zot

After Dark "Zot!" lightning: bolts strike down the cube faces with a stepped-leader
draw-in, a one-frame return-stroke flash, and a long afterglow — struck by hand,
by bass transients, or by a silence-safe ambient storm.

> Sidecar design doc convention: this file lives beside `Zot.java` and is the
> source of truth for design decisions and curation history. Constants or
> behaviors not visually verified are marked with inline `CURATE:` notes
> (grep for `CURATE:`).

## Original / inspiration

The "Zot!" module from Berkeley Systems' **After Dark** screensaver (Mac, ~1990):
sudden branching lightning bolts crackling down an otherwise black screen. This is
an interpretation, not a pixel recreation — the original was a single instant
flash; at sculpture scale each bolt here is given a full envelope (leader →
return stroke → afterglow) so the branching form has time to register.

## Rendering approach

- **Base class**: `ApotheneumRasterPattern` — bolts are drawn with `Graphics2D`
  into the shared 50×45 raster, exactly as doved's `Lightning.java` does, then the
  base class maps the raster onto the eight cube faces.
- **Surfaces covered**: **cube faces only.** The raster base class has no cylinder
  path; cylinder support is a documented **follow-up** (would need a
  `SurfaceCanvas`-style port or a cylinder raster in the base class). The base
  class auto-registers 8 face BooleanParameters (`exteriorFront` … `interiorLeft`,
  exterior on / interior off by default); per the jvyduna no-custom-UI convention
  `buildFaceControls` is **not** called — the plain auto-generated toggles are the
  face UI, and these 8 params don't count against the ~14-param budget.
- **Generator reuse**: Zot is a thin wrapper over the untouched
  `apotheneum.doved.lightning` library. The four algorithms (midpoint
  displacement, L-system, RRT, physically-based) are held as shared stateless
  instances inside the `Algo` enum; selection mirrors doved's `Lightning.java`
  (`EnumParameter` instead of its `DiscreteParameter` + display names). Per-strike
  `Parameters` objects are built with Zot's `branch`/`jag`/random-startX knobs
  mapped onto each algorithm's inputs; the remaining generator inputs are fixed
  named constants taken from doved's defaults.
- **Buffers / zero-alloc rule**: 3 `Bolt` slots are preallocated
  (`MAX_BOLTS = 3`), each with two reusable `ArrayList<LightningSegment>`s.
  **Per-STRIKE allocation is accepted**: the doved generators allocate segments
  and a `Parameters` object per strike, which is event-rate, not per-frame —
  explicitly allowed. The leader-phase progressive reveal renders a growing
  *prefix list* (`visible`) extended in place from the full segment list, no
  per-frame allocation in Zot's own code. Known exception outside our control:
  the doved `render()` methods allocate `Color`/`BasicStroke`/`Path2D` per
  segment per frame internally; the library is reused unmodified by design, so
  this is documented rather than fixed (bounded by ≤3 bolts × segment count;
  a follow-up could add pooled rendering upstream).
- **Door-column handling**: none needed — the raster base class maps the full
  50×45 grid per face; door cutouts are handled by the model.

## Audio mapping

- `bassHit()` **and** `bassRatio >= threshold` (param, default 1.5) triggers a
  strike, gated by an energy-scaled minimum interval (4 s at e=0 → 375 ms at
  e=1, exp). `AudioReactive.tick(deltaMs)` is the first line of `render()`.
- **Silence behavior**: with no audio all taps decay to 0 and `bassHit()` never
  fires, but the ambient Poisson storm keeps striking — the pattern is fully
  presentable in silence (that *is* its ambient mode).

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Ambient Poisson mean strike interval | 30 s | 375 ms (160 BPM beat) | exp |
| Audio strike gate (min interval) | 4 s | 375 ms | exp |
| Afterglow scale on the Glow knob | ×1.4 | ×0.8 | lin |

Strikes are events, not sustained motion, so the ≥5 s traversal cap does not
bind; the event-life floor does (see compliance section).

## Parameters

UI order: triggers first, Energy, pattern parameters, Meta last.
(8 face toggles from the raster base class are auto-registered and not listed.)

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `strike` | Strike | TriggerParameter | — | — | strike one bolt now |
| `storm` | Storm | TriggerParameter | — | — | burst of 3–5 bolts over ~2 s |
| `nextAlgo` | NextAlgo | TriggerParameter | — | — | cycle the generator algorithm |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (series convention) |
| `algorithm` | Algo | EnumParameter&lt;Algo&gt; | MIDPOINT | 4 values | Midpoint / L-System / RRT / Physical |
| `threshold` | Thresh | CompoundParameter | 1.5 | 1..4 | bass ratio needed for an audio strike |
| `thickness` | Thick | CompoundParameter | 2 | 1..3 | core stroke px (branches at half) |
| `branch` | Branch | CompoundParameter | 0.4 | 0..1 | branchiness (probability/angle of forks) |
| `jag` | Jag | CompoundParameter | 0.5 | 0..1 | jaggedness (displacement / angle variation) |
| `glow` | Glow | CompoundParameter | 2.5 | 1..6 s | afterglow fade time (energy-scaled) |
| `ambient` | Ambient | CompoundParameter | 1 | 0..4 | ambient strike rate multiplier (0 = off) |
| `flash` | Flash | CompoundParameter | 0.15 | 0..0.5 | whole-face flash brightness (0 = off) |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

Colors are doved's built-in blue-white lightning on black (no tint param; a
palette tint would require modifying or post-processing the doved renderers —
possible follow-up).

### `branch`/`jag` mapping per algorithm

| Algorithm | `branch` drives | `jag` drives |
|---|---|---|
| Midpoint | branchProbability | displacement |
| L-System | branch angle 25°–70° (lin) | angleVariation |
| RRT | branchProbability | jaggedness |
| Physical | branchingProbability ×0.8 | stepAngleVariation ×π rad |

## Triggers

- `strike` — one bolt: leader draws top→bottom over 300–600 ms, return-stroke
  flash for exactly 1 frame, then ≥1 s afterglow. Total read time ≥1.5 s.
- `storm` — 3–5 bolts spread over ~2 s (spacing 300–700 ms). With 3 slots and
  the 1.5 s min-life recycling gate, late storm bolts may be dropped if all
  slots are still young — dense but never truncating.
- `nextAlgo` — cycles the algorithm enum (wraps); takes effect on the next
  strike; in-flight bolts keep their own generator reference.
- `meta` — one random action from the bag (3 triggers + 6 jumps below).

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `algorithm` | all 4 values | candidate | discrete jump |
| `thickness` | [1, 3] | candidate | full range |
| `branch` | [0.1, 0.8] | candidate | CURATE: avoid 0 (bare bolt) and 1 (thicket) — unverified |
| `jag` | [0.15, 0.9] | candidate | CURATE: subrange unverified |
| `glow` | [1.5, 5] s | candidate | CURATE: subrange unverified |
| `ambient` | [0.25, 2] | candidate | CURATE: keeps storms present but not relentless — unverified |

## Simulation-principles compliance

40-foot sculpture, LEDs far brighter than a monitor. Lightning is the canonical
event-like pattern: the stroke may be fast, but its **life** is long.

- **Event life floor (the math)**: leader 300–600 ms + return stroke (1 frame)
  + afterglow. Afterglow = `Glow × 1000 × lin(e, 1.4, 0.8)` ms, then clamped:
  `glowMs = max(glowMs, MIN_VISUAL_LIFE_MS − leaderMs)`. Worst case (Glow = 1 s,
  e = 1 → 800 ms, leader = 300 ms → clamped to 1200 ms): total = 1500 ms exactly.
  Defaults (Glow 2.5 s, e = 0.35 → ×1.19 ≈ 2.97 s + leader ≈ 0.45 s): **≈3.4 s
  total life**. At e=1: 2.5 × 0.8 = 2.0 s + leader ≈ **2.4 s**. Always ≥1.5 s. ✓
- **No truncation**: slot recycling refuses to kill a bolt younger than 1.5 s
  (the strike is dropped instead), so the floor holds even at beat-rate strike
  demand (3 slots ÷ 1.5 s ≈ 2 bolts/s sustainable max).
- **Whole-face flashes**: ≤1 rendered frame, dim (default alpha 0.15, max 0.5,
  drawn *under* the bolts), and rate-limited to **one per 8 s**
  (`FACE_FLASH_MIN_INTERVAL_MS`). CURATE: 8 s interval and 0.15 alpha unverified.
- **Sustained motion**: none — bolts hold position for their whole life; only
  brightness animates. The leader draw-in (a full-face 45-row sweep in
  300–600 ms) is event motion, permitted under the event-life rule.
- **Contrast/brightness**: bold high-contrast blue-white forms on black; 2 px
  default core (≥1 px allowed at this contrast), branches at half thickness,
  bleed halo constant `BLEED = 1.0`. Leader ramps 0.25→0.55, return stroke 1.0,
  afterglow starts 0.7 and decays quadratically — the 0.55→1.0 return-stroke pop
  is the "Zot!" moment. CURATE: all four brightness constants unverified.
- CURATE: fixed generator constants (`MIDPOINT_*`, `LS_*`, `RRT_*`, `PHYS_*`,
  `START_X_MIN/MAX`) copied/adapted from doved defaults, unverified on the raster.
- CURATE: leader duration range 300–600 ms — verify the top→bottom draw-in reads
  as motion (not a flicker) at sculpture scale.
- CURATE: segment-list prefix order as "growth order" is true by construction for
  midpoint/RRT/physical; for L-system it follows the rule-string interpretation
  order — verify the draw-in doesn't jump around visually on L-System.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |
