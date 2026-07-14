# Rubik

First pattern in the `apotheneum.jvyduna` package. A virtual Rubik's Cube that
continuously **scrambles and solves**, projected from the installation's center
onto the four walls of the outer cube. At rest each wall shows a flat,
undistorted 3×3 face. During a turn the moving slice's cubies rotate in true 3D,
so stickers foreshorten, bulge toward the corners, and tip over the top/bottom
edges. Motion is tempo-locked, and when solved the cube pulses to the beat.

## Logical state — cubie model

The cube is a list of 26 outer **cubies** (the core is omitted). Each cubie has:

- an integer cell position `p ∈ {-1,0,1}³`, and
- up to three **stickers**, each an outward unit normal `d ∈ {-1,0,1}³` plus a
  `colorId ∈ 0..5` (the face it belongs to when solved).

Face id from a direction: `+X=0(R) −X=1(L) +Y=2(U) −Y=3(D) +Z=4(B) −Z=5(F)`.
Opposite faces are adjacent ids, so palette assignment automatically pairs
opposites.

### Turns

A `Move` is `(axis, layer, sign)` — a 90° rotation of one outer layer
(`p[axis] == layer`, `layer = ±1`) about that axis. Committing a move rotates
each affected cubie's position *and* every sticker normal by the same integer
rotation `iRot`. The continuous render rotation `fRot` uses the same convention,
so at `θ = 90°` the animated geometry coincides exactly with the committed state
— the turn snaps seamlessly.

### Scramble / solve (no solver needed)

`computeBestScramble()` builds N random moves (N = `steps`) from solved, never
repeating the previous axis (avoids trivial cancellation). To avoid a bland
target, it generates `SCRAMBLE_TRIALS` (5) independent candidate scrambles,
simulates each from solved, and keeps the one with the highest **face entropy** —
the summed Shannon entropy of the sticker-color histogram on each of the 6 visible
faces (solved = 0; a well-mixed cube scores higher). The winning sequence is stored
in `lastScramble`, leaving the cube solved. Applying `lastScramble` in reverse with
inverted signs solves in exactly N moves. The scramble/reverse-solve identity was
verified over 20,000 random trials (every scramble+reverse returns to solved;
structure stays intact at 26 cubies / 54 stickers).

> The entropy metric scores color *mix* per face, not spatial arrangement, so it
> cheaply rewards shuffles that spread colors across faces rather than leaving
> large single-color patches. Trials run only at reset, not per frame.

### Direction — reversible travel along the path

The scramble defines a fixed path between two endpoints: **solved** (position 0)
and the high-entropy **scrambled** target (position `n = steps`). A position index
`pos` tracks how many forward moves have been committed from solved; each division
steps `pos` one move toward the endpoint selected by the `direction` parameter
(forward for Scrambling, backward for Solving), or holds at that endpoint.

```
SOLVING     jump to scrambled ─▶ solve (animated) ─▶ hold solved & pulse
SCRAMBLING  hold solved 1 division ─▶ scramble (animated) ─▶ hold scrambled
```

- **Solving** (default): `reset` sets the cube directly to the scrambled endpoint
  (`pos = n`), then travels backward to solved; it ends solved and pulses to the
  beat indefinitely.
- **Scrambling**: the cube starts solved and is **held solved for one tempo
  division** (`holdDivisions = 1`) — so observers register the solved start — then
  travels forward to the scrambled endpoint, where it rests statically (not solved,
  so no pulse).

**Direction may be flipped live**, mid-sequence. Because the move each division is
chosen from `pos` + the *current* `direction` (rather than a fixed one-way queue),
flipping simply reverses travel: the in-flight turn completes and snaps at the next
division boundary, then the path unwinds the other way. Held endpoints react too —
flipping at solved starts scrambling; flipping at scrambled starts solving — so no
`reset` is needed to change course. Flipping mid-run redoes/undoes the just-committed
turn, so **total travel time can exceed `steps`** (the cube can oscillate
indefinitely). `reset` re-rolls a fresh high-entropy scramble and jumps to the
endpoint for the current direction.

### Constrained motion (CNSTRN) — churn, don't solve

`constrained` (CNSTRN) turns off solving entirely and drives **pure motion**: a
`reset` jumps to the high-entropy scrambled state exactly as Solving does (any
`direction`), then each division picks a fresh random quarter turn via
`randomConstrainedMove()` instead of stepping along the solve path. The random
walk obeys two rules: **never the bottom face** (a `D` move — axis Y, layer −1;
other slices may still displace bottom cubies) and **no immediate axis repeat**
(so it never rocks back and forth). Every `Move` is already a single 90° quarter
turn, so a "double" simply reads as two consecutive quarters.

`pos` is frozen (`currentAdvance = 0`) but the cube still evolves via `applyMove`
each wrap, so motion stays coherent; because `currentMove` is always non-null the
solved test never trips and **the beat pulse never fires**. All timing controls
(`division`, `movingDuty`, `easing`, `phaseOffset`) behave exactly as in normal
mode — only move *selection* changes. Enter either order: toggle-then-`reset`
seeds the first constrained move in `reset`; `reset`-then-toggle lets the in-flight
move finish and picks up the walk at the next division.

Leaving CNSTRN does **not** restore a solvable path (`pos`/`lastScramble` no longer
describe the walked cube). Return to solving by toggling CNSTRN off **and** firing a
Solving `reset`, under cover of a fade — the transition is not handled visually.

## Tempo-locked motion

One `Tempo.Division` cycle = one turn.
`cyclePhase = frac(tempo.getBasis(division) + phaseOffset)`. A cycle wrap
(`cyclePhase` decreases) commits the current move (advancing `pos` by
`currentAdvance`) and selects the next via `selectMove()` — unless
`holdDivisions > 0`, in which case it decrements the hold and keeps the (solved)
state still for another division (used for the Scrambling solved intro). Within a
cycle, the sub-phase is `constrain(cyclePhase / movingDuty, 0, 1)`
— it ramps 0→1 during the moving portion and holds at 1 (paused at 90°) for the
rest. The rotation angle is `easing.apply(subPhase) · sign · 90°`.

`Easing` (nested enum): `DECELERATE` (default), `ACCELERATE`, `SMOOTHSTEP`,
`LINEAR`, each implementing `double apply(double t)`.

## Projection

Two projection modes, selected by the `beamMode` (Beam) toggle: the default
**gnomonic** raycast (perspective from the center), and an **orthographic**
collimated-emitter mode.

### Gnomonic (default) — central raycast

Everything is done in a normalized frame centered on the installation with the
walls at ±1: `d = ((p − center) / scale)`, using the horizontal half-extent for
X/Z and the vertical half-extent for Y (so a solved wall maps ±1 in both axes and
exactly fills with a 3×3). Center and scale are computed once from the cube
exterior point bounds.

Each frame, the 54 stickers are rebuilt (in place, no allocation) as 3D quads on
the unit cube: center `p·(2/3) + d·(1/3)`, two in-plane half-edge vectors of
length `1/3`, and the outward normal. Stickers in the moving layer are rotated by
the current angle.

For each wall LED, a ray is cast from the center along `d` and intersected with
all 54 quads (`projectLED`): nearest `t > 0` with an outward-facing normal
(`d·n > 0`) wins. This gives, for free:

- **flat 3×3 at rest** — a wall's rays only reach its own axis-aligned face
  (verified: each wall fills completely with the correct face color, 0 misses);
- **perspective bulge during a turn** — rotated cubies poke past ±1 and project
  larger, occluding via nearest-`t`;
- **tipping across edges** — a rotating slice's stickers cross onto neighboring
  walls exactly where the physical edges meet;
- **edges meeting** — adjacent faces transition at the shared physical corners.

The intersection also yields tile-local coords `a,b ∈ [-1,1]` for shading.

### Orthographic (Beam) — collimated emitters

With `beamMode` on, each sticker is instead a **collimated emitter**: a rectangular
beam fired straight out along its own surface normal with 0° divergence (a
searchlight). There is **no perspective bulge** — turns read as flat panels sweeping
rather than bulging cubies. An LED at normalized position `P` is lit by a sticker
when `P` projects inside that sticker's `u,v` rectangle **and** lies in the
sticker's outward hemisphere (`P·n ≥ 0`); the nearest sticker (smallest `|P−c|·n`)
wins (`projectLEDOrtho`). The `P·n ≥ 0` test is the *"cannot cross past center"*
clip: a beam only illuminates its own half of the installation, never wrapping to
the opposite wall.

Because central projection is invariant to uniform scale but an orthographic
emitter is not, here `scale` is a true resize of the emitter cube, applied by
sampling at `P/scale` against the unscaled sticker geometry:

- **100%** — the emitter cube exactly fills the installation: a giant flashlight,
  each wall showing its flat face.
- **< 100%** — a smaller cube casts smaller, **un-magnified** rectangles onto the
  walls (0° beam), black around them.
- **> 100%** — the emitter is larger than the installation; the beams
  reverse-project (magnify) but are still clipped at the center, so nothing crosses
  to the far wall.

### Scale and vertical recentering

`scale` and `yCenter` reshape the projected image (both modes). In **gnomonic**
mode, because central projection is invariant to a uniform 3D scale, the image is
scaled by dividing each LED ray's **transverse** component (the horizontal axis
perpendicular to that wall's outward normal — whichever of X/Z dominates for that
LED) by `scale`, magnifying about the wall center: `100%` = full-wall fit, `<100%`
shrinks (black surround), `>100%` (up to 400%) enlarges (clipped at wall edges). In
**Beam** mode `scale` is a literal emitter resize (see above). `yCenter` shifts the
shared vertical sample `vy = ((p.y−cy)/sy − yCenter)/scale`, sliding the image up
(+) / down (−); combined with `scale` it lifts the projection clear of the portals.

### YFloor — mute below a horizontal line

`yFloor` blacks out every wall LED whose vertical sample `vy` falls below a floor
line, measured in the **same frame as the sticker rows** (so the floor tracks the
image as `scale`/`yCenter` move it) and applied identically in both projection
modes. The 3×3 rows occupy `vy ∈ [-1,-⅓]` (bottom), `[-⅓,⅓]` (middle), `[⅓,1]`
(top). It is a **bipolar control spanning -100%..200%, default -100%**, linear
`floor = -1 + yFloor·2` (so each 100% step is one full image height, i.e. three
sticker rows):

- `-100%` (default) — floor at `-3`: three rows of headroom below the image; nothing
  can be muted.
- `0%` — floor at `-1`: just below the bottom row; the full image still shows.
- `100%` — floor at `+1`: just above the top row; the whole image is muted.
- `200%` — floor at `+3`: three rows above the image.

Besides framing, this is the tool for hiding the top/bottom-cap leak that gnomonic
projection produces at `scale < 100%` (the virtual cube's up/down faces reaching the
walls near the edges): raise the floor to clip the lower leak, or use it with
`yCenter`/`scale` to seat the projection above the portal openings.

## Face colors (palette)

The six face colors are taken from the active Chromatik palette swatch each frame:
face `i` uses swatch color `i` for the first five faces.

- **5 colors defined** (`swatch.size() >= 5`): faces 0–4 are the swatch colors and
  the sixth is **50% brightness white** (`hsb(0,0,50)`), a neutral complement.
- **Fewer than 5 defined**: the defined colors are kept, and the remaining faces are
  filled with fully-saturated hues placed to make all six hues as **perceptually
  even** as possible. The defined colors are anchored at their positions on a
  perceptually-uniform hue circle (see `util/PerceptualHue`, a FastLED-"rainbow"-style
  remap that gives orange/yellow room and compresses green/blue), then the gaps are
  filled by greedily bisecting the largest arc (wrap-aware), and each new position is
  mapped back to a spectral HSV hue for rendering. This yields more evenly-*named*
  jumps (red, orange, green, …) than raw 60° HSV steps, and it's allocation-free.

## Shading

Per tile, `tileMask(t, square, feather)` returns brightness in [0,1] from the
tile-local coordinate. The `gap` parameter is the **gap amount** (0 = tiles touch;
100% = stickers vanish); the colored square half-size is `square = 1 − gap`,
feathered by `feather = edgeFade · square` (0 = hard edge, 1 = only the exact center
is full bright). The final color is the face color scaled by `maskA · maskB · pulse`.

**Smooth** (the standardized antialiasing knob) is **edge antialiasing by sub-pixel
supersampling** — deliberately *not* a feather, so it never shrinks a tile or opens
negative space between stickers (that is `gap`/`Fade`'s job). When `smooth ≥ AA_EPS`,
each LED casts `AA_N = 4` sub-rays on a rotated grid (`AA_OFF`, RGSS → 5 coverage
levels on axis-aligned edges) spread across ~one LED — transverse pitch `2/GRID_WIDTH`,
vertical `2/GRID_HEIGHT`, each `/scale` so the AA width stays ~1 LED at any scale — and
averages the results **per RGB channel (linear)**. `smooth` scales the spread, so
`smooth = 0` collapses to a single hard sample. Interior pixels sample identical colors
→ unchanged full brightness; only boundary pixels blend, softening **all** projected
boundaries (color↔color grid/face seams *and* color↔black silhouette and the wedges the
rotating slice opens). There is no motion-blend term — the turn animation is already a
continuous function of `cyclePhase`. Works in both projection modes; the wall's
transverse (horizontal) axis and `vy` are perturbed, the near-normal axis is left alone.
Cost is `AA_N`× the ray casts only while `smooth > 0`; `AA_N` is the single quality dial.

## Solved beat pulse

Whenever the queue is empty **and** the cube is solved (the end of a Solving pass,
and also during the Scrambling solved-intro hold), rotation stops and a global
brightness multiplier `lerp(1.0, 0.5, tempo.getBasis(division))` snaps to full on
each division grid line and decays toward 50% — reusing the same `division`. A
finished Scrambling pass rests on the scrambled state instead (not solved, so no
pulse). The pulse continues indefinitely until `reset` is triggered.

## Surface targeting

Rendered into the exterior face buffers first (`setColors(BLACK)` clears
everything). `surfaces`: `OUTER` leaves it; `BOTH` mirrors via
`copyCubeExterior()`; `INNER` mirrors then blacks out the exterior faces.

## Parameters

Listed in UI order (`reset` first). The label shown in the UI is in parentheses
where it differs from the field name.

| Param (label) | Type | Default | Range | Meaning |
|---|---|---|---|---|
| `reset` (Reset) | TriggerParameter | — | — | restart the sequence in the current Direction |
| `direction` (Direction) | EnumParameter&lt;Direction&gt; | SOLVING | — | travel toward solved / scrambled; flippable live (reverses at next division) |
| `steps` (Steps) | CompoundDiscreteParameter | 16 | 1..64 | turns to scramble / solve |
| `constrained` (CNSTRN) | BooleanParameter | off | — | constrained motion: continuous D-free quarter turns on a scrambled cube; never solves, no pulse (enter via Reset, exit via fade + Reset) |
| `division` (TempoDiv) | EnumParameter&lt;Tempo.Division&gt; | QUARTER | — | one turn per cycle (and pulse rate) |
| `movingDuty` (MoveDuty) | CompoundParameter | 0.6 | 0.05..1 | moving vs. paused split of each cycle |
| `phaseOffset` (Phase) | CompoundParameter | 0 | 0..1 | shift the cycle vs. the tempo grid |
| `easing` (Ease) | EnumParameter&lt;Easing&gt; | DECELERATE | — | turn motion curve |
| `gap` (Gap) | CompoundParameter | 0.14 | 0..1 | gap between stickers (100% = stickers vanish) |
| `edgeFade` (Fade) | CompoundParameter | 0.25 | 0..1 | tile edge softness |
| `beamMode` (Beam) | BooleanParameter | off | — | off = gnomonic perspective; on = orthographic collimated emitters (no bulge) |
| `scale` (Scale) | CompoundParameter | 1.0 | 0..4 | projection scale (100% = full wall; Beam mode = literal emitter resize) |
| `yCenter` (YCenter) | CompoundParameter | 0 | -1..1 | vertical center of the image (+ = up) |
| `yFloor` (YFloor) | CompoundParameter | -1.0 | -1..2 | bipolar floor, default -100%; -100% = 3 rows below image, 0% = just below bottom row, 100% = just above top row, 200% = 3 rows above image |
| `surfaces` (Surface) | EnumParameter&lt;Surface&gt; | OUTER | — | OUTER / INNER / BOTH |
| `smooth` (Smooth) | CompoundParameter | 1.0 | 0..1 | edge antialiasing by ~1-LED sub-pixel supersampling (all boundaries); never shrinks tiles / adds negative space |

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).
