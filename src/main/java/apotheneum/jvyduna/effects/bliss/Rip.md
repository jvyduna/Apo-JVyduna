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
`LUMA_MIN`); surfaces above `MIN_LIT` (2%) are candidates and one is chosen
uniformly at random. If nothing is lit, the trigger is logged and skipped (a rip
on black would be invisible). The read is deferred from the trigger callback to
`render()` so it sees the live composite, not a stale between-frame buffer.

## Tear geometry (frozen at trigger)

Coordinates per surface: `u` along the source edge, `d` = depth inward from that
edge (`d = fromTop ? y : H-1-y`). The tear is a triangle whose **base sits on a
random top/bottom source edge** and whose **apex advances inward**:

- `startU` — random base center along the edge.
- `maxDepth` — random `[50%, 120%]` of surface height (may overshoot the far
  edge; rendering clamps to the surface).
- `halfWidthMax = 0.5 · RipWidt · W` — half-width the base reaches at completion.
- `slope` — random apex tilt in `[-Skew, +Skew]` (px drift per px depth);
  `Skew = 0` means always straight in.
- `curve` + `curveExp` — the grow speed curve (Accel/Linear/Decel + exponent),
  frozen so an in-flight rip keeps its easing.
- Two jag profiles (`jagLeft`, `jagRight`), 48 smoothed value-noise samples along
  depth, amplitude `Jag · JAG_MAX_PX` — the torn sides.
- `ripDurMs`, `fadeDurMs` sampled from `TempoLock.divisionMs(RipDiv/FadeDiv)`.

Everything is frozen here: live knob changes to width, jag, skew, or the
divisions only affect the *next* trigger, never an in-flight rip.

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
  slot is freed.

Subtraction is true per-channel: `channel − whiteLevel·enabled·255`, clamped at
0 — full black in the grow phase, content re-emerging as it fades.

`Smooth` feathers only the two ripped **side** boundaries (coverage-scaled
subtract over `Smooth·SMOOTH_MAX_PX`); the leading front stays hard white.
`Border` blends a 1px outline along the sides toward the **first palette color**
(read live from `lx.engine.palette.getColor()`) by the same `level` the region
subtracts by, so it fades out in lockstep with the rip zone.

## Concurrency / perf

A fixed pool of `MAX_RIPS` (12) preallocated slots; concurrent triggers stack and
the oldest slot is recycled when full. All per-rip arrays are preallocated; the
render loop allocates nothing and is bounded by `MAX_RIPS ×` surface area.

## Params

`Trigger`, `RipDiv` (default 1/4 = 1 beat), `Curve` (default Linear),
`Exp` (default 2), `FadeDiv` (default 1 = 1 bar), `RipWidt`, `Jag`, `Skew`,
`Smooth` (default 0), `Border` (default off; outline uses the first palette color).

## Possible future refinements

- Weight the surface pick toward the most-lit surface (currently uniform).
- Per-surface eligibility toggles (cube/cyl × ext/int), like BlitFeedback.
- A thicker hard "front band," or a `Level` cap for partial (non-black) scuffs.
