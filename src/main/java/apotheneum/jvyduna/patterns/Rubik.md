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

## Projection — central (gnomonic) raycast

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

### Scale and vertical recentering

Two controls reshape the projected image on the walls. Because central projection
is invariant to a uniform 3D scale of the model about the center, the image is
scaled instead by dividing each LED ray's **transverse** components (the two axes
perpendicular to that wall's outward normal) by the `scale` factor — magnifying the
stickers about the wall center. `scale = 100%` is the untouched full-wall fit;
`< 100%` shrinks the cube (black surround as rays miss it); `> 100%` (up to 400%)
enlarges it (clipped at the wall edges). Each wall's outward normal is simply
whichever of X/Z dominates for that LED (it physically sits on that wall, so its
normal component is ≈ ±1).

`yCenter` shifts the vertical origin of the sample (`dy → (dy − yCenter)/scale`),
sliding the image up (+) or down (−) the wall by up to a full half-height.
Combined with a reduced `scale`, the whole projection can be lifted clear of the
portal openings.

### YFloor — mute below a horizontal line

`yFloor` blacks out every wall LED whose (scaled/recentered) vertical `dy` falls
below a floor line, measured in the **same frame as the sticker rows** — so the
floor tracks the image as `scale`/`yCenter` move it. The 3×3 rows occupy
`dy ∈ [-1,-⅓]` (bottom), `[-⅓,⅓]` (middle), `[⅓,1]` (top), and the floor is linear:
`floor = -1 + yFloor·(2/3)`.

- `0` — off (no muting).
- `100%` — floor at `-⅓`, right below the bottom row: the bottom row is muted,
  middle and top remain.
- `200%` — floor at `+⅓`: only the top row remains.

Besides framing, this is the tool for hiding the top/bottom-cap leak that central
projection produces at `scale < 100%` (the virtual cube's up/down faces reaching the
walls near the edges): raise the floor to clip the lower leak, or use it with
`yCenter`/`scale` to seat the projection above the portal openings.

> Note vs. the plan: an explicit ortho⇄gnomonic `k` blend proved unnecessary.
> Pure central projection is already flat at rest (planar faces project
> uniformly) and only distorts where 3D geometry actually moves, so the "bulge
> tracks motion" behavior is inherent. Consequently there are **no black side
> bars** — each wall is filled edge-to-edge and meets its neighbors like a real
> cube. Tiles are the wall size / 3 (≈ slightly wider than tall on the 50×45
> grid). A letterbox/"square-tile" mode could be layered on later if desired.

## Face colors (palette)

The six face colors are taken from the active Chromatik palette swatch each frame:
face `i` uses swatch color `i` for the first five faces. For the sixth face:

- if the swatch defines **all five** colors (`swatch.size() >= 5`), the sixth face
  is **50% brightness white** (`hsb(0,0,50)`) — a neutral complement to a full
  five-color palette;
- otherwise the remaining faces fall back to an **auto picker** (evenly spaced,
  fully saturated hues) so the cube always shows six distinct colors even with a
  sparse swatch.

## Shading

Per tile, `tileMask(t, square, feather)` returns brightness in [0,1] from the
tile-local coordinate. The `gap` parameter is the **gap amount** (0 = tiles touch;
100% = stickers vanish); the colored square half-size is `square = 1 − gap`,
feathered by `feather = edgeFade · square` (0 = hard edge, 1 = only the exact
center is full bright). The final color is the face color scaled by
`maskA · maskB · pulse`.

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
| `division` (TempoDiv) | EnumParameter&lt;Tempo.Division&gt; | QUARTER | — | one turn per cycle (and pulse rate) |
| `movingDuty` (MoveDuty) | CompoundParameter | 0.6 | 0.05..1 | moving vs. paused split of each cycle |
| `phaseOffset` (Phase) | CompoundParameter | 0 | 0..1 | shift the cycle vs. the tempo grid |
| `easing` (Ease) | EnumParameter&lt;Easing&gt; | DECELERATE | — | turn motion curve |
| `gap` (Gap) | CompoundParameter | 0.14 | 0..1 | gap between stickers (100% = stickers vanish) |
| `edgeFade` (Fade) | CompoundParameter | 0.25 | 0..1 | tile edge softness |
| `scale` (Scale) | CompoundParameter | 1.0 | 0..4 | overall scale of the projection (100% = full wall) |
| `yCenter` (YCenter) | CompoundParameter | 0 | -1..1 | vertical center of the image (+ = up) |
| `yFloor` (YFloor) | CompoundParameter | 0 | 0..2 | mute LEDs below a floor (0 = off; 100% = below bottom row; 200% = only top row) |
| `surfaces` (Surface) | EnumParameter&lt;Surface&gt; | OUTER | — | OUTER / INNER / BOTH |

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).
