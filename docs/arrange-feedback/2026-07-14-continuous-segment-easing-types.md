# Feature request: continuous easing (accel / decel / smoothstep) segment types for parameter lanes

**Branch:** `arrange` (LX + LXStudio, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## What Jeff wants

> "Could really use accel, decel, and smoothstep continuous segment types
> (instead of recording a bunch of points or assigning a trigger modulator for
> the transition instead). Setting the exponent and/or endpoint slopes a
> nice-to-have but annoying to make a UI for something that eventually drifts
> towards a full bezier UI (show/hide handles, decide what to do when a handle
> control needs to live outside a lane for its magnitude)."

Today a Normalized parameter lane can only draw a **straight ramp** between two
automation points. Any ease (a soft start, a soft landing, an S-curve) has to be
faked by hand-dotting a dozen intermediate points or by farming the transition
out to a modulator. The ask is a small, fixed menu of curve shapes on a segment
— accel (ease-in), decel (ease-out), smoothstep (ease-in-out) — with linear as
the default, explicitly stopping short of a bezier-handle editor.

## How interpolation works today

Only **`ParameterClipLane.Normalized`** interpolates; Boolean/Discrete lanes
step and Trigger lanes fire. The gate:

```java
// LX: heronarts/lx/clip/ParameterClipLane.java:138-140
public boolean hasInterpolation() {
  return (this instanceof Normalized);
}
```

Playback value between two surrounding events is a **plain linear lerp** in
`playCursor`:

```java
// LX: heronarts/lx/clip/ParameterClipLane.java:468-474
} else if (hasInterpolation()) {
  // Interpolate value between the two events surrounding us
  this.parameter.setNormalized(LXUtils.lerp(
    prior.getNormalized(),
    next.getNormalized(),
    CursorOp().getLerpFactor(to, prior.cursor, next.cursor)
  ));
}
```

`getLerpFactor` returns the 0..1 time fraction of `to` between the two event
cursors (`Cursor.java:229` interface, impls at `:471` and `:611`); `LXUtils.lerp`
is the bare `v1 + (v2-v1)*amt` (`LXUtils.java:192-194`). There is **no shape term
anywhere** — the fraction feeds straight into the lerp. The identical linear form
recurs in the two other places a lane needs an in-between value: the editor/stitch
path

```java
// LX: heronarts/lx/clip/ParameterClipLane.java:182-188
if (hasInterpolation()) {
  return new ParameterClipEvent(this, cursor, LXUtils.lerp(
    prior.getNormalized(),
    next.getNormalized(),
    CursorOp().getLerpFactor(cursor, prior.cursor, next.cursor)
  ));
}
```

and the record-time "envelope value at this instant" rescue
(`ParameterClipLane.java:405-409`). All three would need to route through one
shared shaping function.

The **UI draws matching straight segments** — for an interpolated lane the
segment loop just emits `lineTo` from point to point (no curve sampling); only
stepped lanes get an extra horizontal `lineTo` to make the stair:

```java
// LXStudio: heronarts/lx/studio/ui/clip/UIParameterClipEnvelope.java:140-145
if (!isInterpolated) {
  // Create a "stair-step" by first moving horizontally to this x-position
  // at the previous y-value
  vg.lineTo(toX, fromY);
}
vg.lineTo(jumpX = toX, jumpY = toY);
```

So "the segment is linear" is true in both the engine and the renderer, and both
sides are small.

## Where an easing enum would live

**Event data shape (unchanged surface).** A `ParameterClipEvent` serializes only
its normalized value; the cursor comes from the base class:

```java
// LX: heronarts/lx/clip/ParameterClipEvent.java:86-89
public void save(LX lx, JsonObject obj) {
  super.save(lx, obj);
  obj.addProperty(KEY_NORMALIZED, this.normalized);   // "normalized"
}
```

```java
// LX: heronarts/lx/clip/LXClipEvent.java:87-89
public void save(LX lx, JsonObject obj) {
  obj.add(KEY_CURSOR, LXSerializable.Utils.toObject(lx, this.cursor));  // "cursor"
}
```

i.e. today's `{"cursor": {millis, beatCount, beatBasis}, "normalized": <0-1>}`
(matches the CLAUDE.md clip-lane note). An easing selector is a **third scalar
on the event** — the natural home, because the shape applies to the segment
*leaving* that event toward the next one, and a segment is fully identified by
its left endpoint. Add a `KEY_EASING` property alongside `KEY_NORMALIZED`, read
it in `ParameterClipEvent.load` (`:77-83`), write it in `save`.

**There is already a precedent in-tree for exactly this data model.**
`MultiStageEnvelope.Stage` carries a per-node `shape` scalar, serializes it as
`"shape"`, and shapes its inter-node lerp with a power curve:

```java
// LX: heronarts/lx/modulator/MultiStageEnvelope.java:206-220
public double compute(double basis) {
  ...
  if (basis < stage.basis) {
    double relativeBasis = (basis - prevBasis) / (stage.basis - prevBasis);
    return LXUtils.lerp(prevValue, stage.value, Math.pow(relativeBasis, stage.shape));
  }
  ...
}
```

```java
// LX: heronarts/lx/modulator/MultiStageEnvelope.java:104-126
private static final String KEY_SHAPE = "shape";
// Stage.save/load persist basis, value, shape
```

That is the same node-plus-per-segment-shape structure a parameter lane wants,
and it already has a working draggable-shape UI (`UIMultiStageEnvelope`) — useful
as a reference, though Jeff explicitly does *not* want the continuous drag-handle
UI here.

**Back-compat / serialization story.** `load` is additive: `ParameterClipEvent.load`
already guards every key with `obj.has(...)`. An old `.lxp` has no `easing`
property, so a missing key defaults to `Linear` and every existing composition
plays back bit-identically. The enum should serialize as a **string name**
(`"LINEAR"`/`"ACCEL"`/`"DECEL"`/`"SMOOTH"`), not an ordinal, so reordering the
enum later can't silently repaint saved curves. Non-Normalized lanes ignore the
field entirely (Boolean/Discrete/Trigger never call the interpolation path), so
it is inert on them.

## Suggested minimal design

A **per-event `EasingType` enum with four values**, describing how the segment
from this event to the next is shaped:

```
LINEAR   f(t) = t                                    (default; today's behavior)
ACCEL    f(t) = t^k               ease-in            (slow start, fast finish)
DECEL    f(t) = 1 - (1-t)^k       ease-out           (fast start, soft landing)
SMOOTH   piecewise / smoothstep   ease-in-out        (soft both ends, S-curve)
```

The math already exists in-tree and can be lifted verbatim — `QuadraticEnvelope`
implements precisely accel/decel/smoothstep as `IN`/`OUT`/`BOTH`:

```java
// LX: heronarts/lx/modulator/QuadraticEnvelope.java:128-144
case IN:   return Math.pow(basis, exponent);
case OUT:  return 1 - Math.pow(1 - basis, exponent);
case BOTH: // symmetric ease-in-out, exponent 2 = classic smoothstep
  if (basis < 0.5) return .5 * Math.pow(2*basis, exponent);
  else             return .5 + .5 * (1 - Math.pow(1 - 2*(basis-0.5), exponent));
```

Fixing `exponent = 2` (or `3`) collapses this to a zero-parameter shape function
`shape(EasingType, t) -> t'` with no knob, no handle, no magnitude that has to
escape the lane — which is exactly the rabbit hole Jeff wants to avoid.

**Engine wiring** (three call sites, one helper): replace each
`LXUtils.lerp(a, b, factor)` at `ParameterClipLane.java:470-474`, `:182-188`,
and `:405-409` with `LXUtils.lerp(a, b, shape(priorEvent.easing, factor))`. The
shape is read from the **left/prior** event in every case. That's the entire
playback + record + stitch change; the lerp endpoints and `getLerpFactor` are
untouched.

**Lane rendering** (`UIParameterClipEnvelope.onDraw`): the segment loop at
`:120-171` currently emits a single `lineTo` per event. For a non-`LINEAR` prior
event, subdivide that one segment into ~8-16 `lineTo` samples of
`shape(easing, t)` across the segment's pixel span (cheap, only the visible lens
is walked). `LINEAR` keeps the single `lineTo`, so nothing changes for existing
lanes and the common case stays fast.

**Choosing the shape (no new custom UI):** the per-event easing is a discrete
4-value pick, so it fits the existing right-click/context affordance on a dot, or
a cycle-on-modifier-click — no drag handle, no inspector column, no value that
lives outside the lane. This is the crux of why the enum sidesteps bezier: a
segment has exactly one categorical attribute, chosen from a fixed menu, with a
safe default.

## Future

If per-segment intensity is ever wanted, the same field generalizes without a
format break:

- **Exponent knob** — promote the fixed `k=2` to a per-event `double` (mirrors
  `MultiStageEnvelope.Stage.shape` one-for-one, including its existing shape-drag
  UI as prior art). The enum stays as the coarse selector; the exponent is an
  optional refinement.
- **Endpoint slopes / full bezier** — two control-magnitude handles per segment.
  This is the case Jeff flags as annoying (handles whose magnitude wants to live
  outside the lane bounds). The enum design deliberately defers it; nothing here
  forecloses it, since a bezier would just be a fifth `EasingType` plus extra
  per-event handle data, still keyed off the left event.

The recommendation is to ship LINEAR/ACCEL/DECEL/SMOOTH with fixed curves first —
it covers the stated need (soft starts, soft landings, S-transitions) with a
zero-parameter UI and a purely additive serialization change.

Key files: `heronarts/lx/clip/ParameterClipLane.java` (the three lerp sites +
`hasInterpolation`), `heronarts/lx/clip/ParameterClipEvent.java` (event
save/load, where an `easing` key lands), `heronarts/lx/clip/LXClipEvent.java`
(cursor serialization), `heronarts/lx/modulator/QuadraticEnvelope.java` (the
ready-made IN/OUT/BOTH ease math), `heronarts/lx/modulator/MultiStageEnvelope.java`
(per-node `shape` precedent + its UI),
`heronarts/lx/studio/ui/clip/UIParameterClipEnvelope.java` (the straight-line
segment renderer to subdivide), `heronarts/lx/utils/LXUtils.java` (`lerp`).
</content>
</invoke>
