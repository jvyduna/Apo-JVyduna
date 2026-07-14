# Rip — design note

Triggered "torn-fabric" subtract effect for the Bliss section. On each
`Trigger`, Rip tears a jagged, growing white triangle out of one surface that
currently has content, darkening (subtracting) whatever is beneath it, then
fades that white back to black.

## Why an effect, not a pattern

The spec requires picking *a surface that currently has non-black pixel data
from other patterns*. In LX a pattern's `colors[]` is only its own channel's
working buffer — it cannot see other channels. An `ApotheneumEffect` receives in
`this.colors[]` the **composited output of the channel/group it sits on**, which
is exactly "the other patterns' content" (this is how `effects/BlitFeedback`
reads live pixels). So Rip is an effect, and "subtract white → black" is just the
effect darkening `colors[]` in place — no mixer blend-mode wiring needed.

**Placement is load-bearing:** Rip only tears what is *below it on its own bus*.
Put it on a group or master bus above the content channels — not on an isolated
channel, where it would have nothing to rip.

## The ten surfaces

Candidates are the four cube exterior faces, the four cube interior faces, the
cylinder exterior, and the cylinder interior — each an `Apotheneum.Surface`
(cube `Face` = 50w×45h, no horizontal wrap; cylinder `Orientation` = 120w×43h,
wraps). Interiors may be null on some models and are skipped. Surfaces are stored
as descriptors (`cube`/`exterior`/`faceIndex`) and re-resolved every frame, so a
model rebuild never leaves a stale reference.

At trigger, each surface's composite is scanned for lit pixels (integer luma >
`LUMA_MIN`); surfaces above `MIN_LIT` (2%) are candidates. Each rip records its
`surfaceId` (0-9), and the trigger picks, among the lit candidates, one carrying
the **fewest existing active rips** (ties broken at random) — so repeated
triggers spread across surfaces instead of piling onto one. The per-surface tally
counts a rip against its home surface; with `Through` on, a rip occupies both
sides of its wall, so it also counts against its counterpart surface (cube ext
0-3 ↔ int 4-7, cylinder 8 ↔ 9) — new rips then favor an entirely fresh wall. If
nothing is lit, the trigger
is logged and skipped (a rip on black would be invisible). The read is deferred
from the trigger callback to `render()` so it sees the live composite, not a
stale between-frame buffer.

## Tear geometry (frozen at trigger)

Coordinates per surface: `u` along the source edge, `d` = depth inward from that
edge (`d = fromTop ? y : H-1-y`). The tear is a triangle whose **base sits on a
random top/bottom source edge** and whose **apex advances inward**:

- `fromTop` + `startU` — the origin edge and base center. With no other rips on
  this surface, uniform-random. Otherwise **biased away from existing origins**:
  - **Cube face:** the two valid edges (rips can't start on the vertical sides)
    are concatenated into one **wrapped 1D loop** `t ∈ [0,1)` — top-left `0`,
    top-right `0.5`, bottom-right `0.5+`, bottom-left `1 ≡ 0` (the sides are the
    zero-length seams closing the loop). The new origin aims at the **midpoint of
    the largest gap** between existing origins on this loop (uniform-random among
    tied gaps), jittered by a Gaussian of width `ORIGIN_SIGMA_FRAC` (0.4) × the
    half-gap. So one rip at 10% pushes the next toward 60%; two antipodal ones
    push the third to 35% or 85% (50/50); the fourth takes the remaining
    midpoint. This replaced an earlier scheme that reflected X and edge
    *independently* and collapsed toward mid-edge for 2+ rips.
  - **Cylinder:** no seams (each edge is its own ring), so azimuth uses the
    circular-mean **antipode** (jitter `ORIGIN_SIGMA_X` 0.18) and the edge flees
    the crowded ring (`ORIGIN_SIGMA_EDGE` 0.28), handled independently.
  - With `Through` on, the counterpart surface's rips share these origins (same
    column index) and join the avoidance set.
- `maxDepth` — random `[50%, 120%]` of surface height (may overshoot the far
  edge; rendering clamps to the surface).
- `halfWidthMax = 0.5 · RipWidt · W` — half-width the base reaches at completion.
- `slope` — apex tilt (px drift per depth px), drawn from a `Skew·90°`-wide
  angle range (`Skew = 0` ⇒ straight up/down). On a **cube face** the range is
  one-sided, `[0, Skew·90°]`, always leaning the apex **toward the face center**
  (origin left of center → lean right, and vice-versa). On the **cylinder**
  (continuous, no left/right center) the range is **symmetric about straight**,
  `±Skew·45°`, random direction. The resulting `tan(θ)` slope is capped at
  `SLOPE_CAP` (7) so near-90° angles stay finite.
- `curve` + `curveExp` — the grow speed curve (Accel/Linear/Decel + exponent),
  frozen so an in-flight rip keeps its easing.
- Two jag profiles (`jagLeft`, `jagRight`), 48 smoothed value-noise samples along
  depth, amplitude `Jag · JAG_MAX_PX` — the torn sides.
- `ripDurMs` from `TempoLock.divisionMs(RipDiv)` (frozen). `FadeDiv` is **not**
  frozen — see below.

Everything here is frozen: live changes to width, jag, skew, or `RipDiv` only
affect the *next* trigger, never an in-flight rip. **`FadeDiv` is the exception**
— see Animation.

## Animation

- **Grow** (`age < RipDiv`): linear clock time `t = age/RipDiv` is reshaped by
  the frozen speed curve into tear progress `p`: `Accel` = `t^Exp` (ease-in),
  `Decel` = `1−(1−t)^Exp` (ease-out), `Linear` = `t`. The triangle scales up from
  the edge — `currentDepth = p·maxDepth`, `currentHalfWidth = p·halfWidthMax`.
  Zone is full white. Half-width narrows linearly from base (`d=0`) to apex
  (`d=currentDepth`): `hw(d) = currentHalfWidth·(1 − d/currentDepth)`, perturbed
  by the jag profiles. A pixel is in the zone iff `d ≤ currentDepth` and `du`
  falls within `[−(hw+jagLeft), +(hw+jagRight)]` (`du` wraps for the cylinder).
- **Fade** (`age ≥ RipDiv`): full shape held; white decays exponentially,
  `whiteLevel = (1/255)^fadeT` (BlitFeedback's fade curve). At `fadeT ≥ 1` the
  slot is freed. `fadeT` is advanced **incrementally** each frame by `deltaMs /
  fadeDurMs`, where `fadeDurMs = FadeDiv.bars · divisionMs(WHOLE)` is read
  **live** and is the same for every rip — so `FadeDiv` is a shared "healing
  rate": turning the knob retimes all in-flight fades at once, smoothly (no jump,
  since progress accumulates rather than being recomputed from elapsed time).
  `FadeDiv` is a custom bar-span enum (1/4 → **64 bars**) because
  `Tempo.Division` tops out at 2 bars and the fade tail needs longer.

Subtraction is true per-channel: `channel − whiteLevel·enabled·255`, clamped at
0 — full black in the grow phase, content re-emerging as it fades.

`Smooth` feathers only the two ripped **side** boundaries (coverage-scaled
subtract over `Smooth·SMOOTH_MAX_PX`); the leading front stays hard white.
`Border` blends a 1px outline along the sides toward a **palette swatch color**
by the same `level` the region subtracts by, so it fades out in lockstep with the
rip zone. `BColor` (1-5) selects which swatch slot supplies that color, read live
each frame from `lx.engine.palette.swatch.colors`; a slot index beyond the number
of defined swatch slots falls back to the last defined slot (and an empty swatch
falls back to `lx.engine.palette.getColor()`).

`Through` tears the *opposite* (interior/exterior) side of the same wall as well,
as if a physical sheet were torn so the hole passes through the material. Per
`Apotheneum.lxf`, interior faces carry the same yaw as their exterior twins
(differing only by a small depth inset), so **exterior column `i` and interior
column `i` share a world (x, y)** — front and back of the wall at the same spot.
The rip is therefore redrawn on the counterpart at the **same column index** (no
reversal); the mirror-image look to an inside viewer falls out of the geometry.
Both cube faces and the cylinder pair exterior↔interior; a null counterpart
(no-interior model) is skipped.

### `Smooth` default exemption

The repo convention is `Smooth` = 1.0 default. Rip intentionally ships `Smooth`
= **0**: a torn edge should be hard/pixel-crisp by default, and any softening is
an opt-in on the ripped sides only (the leading front is always hard white).

## Concurrency / perf

A fixed pool of `MAX_RIPS` (72) preallocated slots; concurrent triggers stack and
the oldest slot is recycled when full. All per-rip arrays are preallocated; the
render loop allocates nothing and is bounded by `MAX_RIPS ×` surface area.

## Params

`Trigger`, `Rips` (1-3, default 1; new rips started per trigger — a `Through`
double-sided pair counts as one), `RipDiv` (default 1/4 = 1 beat), `Curve` (default Linear),
`Exp` (default 2, max 6), `FadeDiv` (bars, default 1; 1/4 → 64), `RipWidt`,
`Jag`, `Skew`, `Smooth` (default 0), `Border` (default off), `BColor`
(swatch slot 1-5, default 1; too-high indices clamp to the last defined slot),
`Through` (default off; tear the same hole through both sides of the wall).

## Possible future refinements

- Weight the surface pick toward the most-lit surface (currently uniform).
- Per-surface eligibility toggles (cube/cyl × ext/int), like BlitFeedback.
- A thicker hard "front band," or a `Level` cap for partial (non-black) scuffs.
