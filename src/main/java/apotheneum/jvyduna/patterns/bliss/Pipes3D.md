# Pipes3D

NT 3D Pipes: a beat-planned self-avoiding pipe lattice grows in one shared room
volume, projected (and optionally rotated) onto all four cube walls.

> Sidecar design doc convention: this file lives beside `Pipes3D.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

The Windows NT 4.0 / 9x "3D Pipes" OpenGL screensaver (`sspipes.scr`): glossy
colored pipes growing through a dark room with right-angle turns, sphere joints
at the elbows, an occasional teleport, and a screen clear when the room fills
up. Signature elements preserved: saturated pipe colors on true black,
right-angle self-avoiding growth, ball joints at every elbow, teleporting on
demand, and the fill-then-clear lifecycle. This is an interpretation for
LED-wall resolution, not a port: pipes are one cell thick, lit by a small
Phong rig (fourth pass) — a rotating sun plus corner fills — over a depth
cue.

The conceit for Apotheneum: the cube's four walls are the four walls of the NT
*room*. There is exactly one 3D pipe lattice in a ~10×9×10-cell volume, and
each wall shows an orthographic projection of that SAME object along its inward
normal — walk around the cube and you see the same pipes from four sides, with
corner-continuous mappings so adjacent walls agree at their shared edges.
Three distinct coordinate systems (fourth pass): the **walls/LEDs** are fixed;
the **lattice** rotates on RotX/RotY; and the **lighting rig** (sun + corner
fills) rotates autonomously about Y, one revolution per 32 beats.

**Cube-only in v1.** The cylinder stays dark; bringing a (radially projected?)
variant to the cylinder is a follow-up curation item.

## Rendering approach

- **Base class**: `ApotheneumPattern` — needs cube face geometry and
  `copyCubeExterior()`. No custom UI (default auto-generated panel).
- **Surfaces**: cube exterior rendered; **interior faces are copies** of the
  exterior via `copyCubeExterior()`. Cylinder intentionally dark
  (`setColors(BLACK)` each frame; nothing else touches it).
- **Room volume**: `gx × gy × gz` cells, default 10×9×10 (`gy = round(gx·45/50)`
  keeps cells ~square: 5×5 px at density 10). `boolean[][][]` occupancy is
  preallocated at max density 12×11×12; only the `[gx][gy][gz]` corner is used,
  so a density jump requires **no reallocation ever**.
- **Retained geometry**: the lattice is stored as segments (run polylines:
  endpoint pairs in continuous cell coordinates) and balls (elbow/cap/spawn
  joints), in preallocated parallel arrays (`MAX_SEGMENTS`/`MAX_BALLS` = 1600
  each, ~120 KB). Hue is **captured at creation** so committed geometry keeps
  its color; thickness reads the live Thick knob at raster time, so the whole
  model rethickens in realtime (2026-07-06 second pass). Normal growth is
  bounded far below the caps by occupancy (60% of 12·11·12 ≈ 950 cells, with
  straight runs merging many cells into one segment); overflow — reachable
  only via long best-effort-intersect episodes — just starts a drain.
- **Full re-raster each frame** (replaces the pre-2026-07-06 incremental
  persistent-buffer scheme, which could not rotate): the 4 color + 4 depth
  buffers are cleared and every segment/ball re-projected under the current
  rotation. Worst realistic case (density 12 near auto-drain, ~380 merged
  segments + ~170 balls, thickness 4 px) ≈ 115 K buffer writes/frame — well
  under a millisecond; the pathological all-1-cell-run fill is ~2.5× that.
- **Projection**: each point is rotated about the room center (RotX pitch,
  then RotY yaw — the model rotates, the room does not), then projected
  per-wall with the corner-continuous orthographic table (u = horizontal px,
  d = depth in cells, v = y·ch for all walls):

  | Wall | u | depth d |
  |---|---|---|
  | front | `x·cw` | `z` |
  | right | `z·cw` | `gx − x` |
  | back | `(gx − x)·cw` | `gz − z` |
  | left | `(gz − z)·cw` | `x` |

  At zero angles this is exactly the classic static axis-select projection
  (verified per-wall), so the un-rotated look is unchanged by the rewrite.
  `right.columns[0]` physically adjoins `front.columns[49]` (ring order), and
  each mapping puts the shared corner at the shared edge, so the four views
  stitch into one object. Rotated content can project outside the 50×45
  viewport or the [0, gz] depth range — pixels clip and depth clamps (the
  object "sticks out of the frame" at 45°, which reads fine).
- **Raster paths — gather, not scatter** (fifth pass): each segment projects
  to a 2D **capsule** per wall and iterates the destination pixels (= LEDs,
  1:1 with the wall buffer) inside its bounding box, evaluating the exact
  point-to-segment distance per pixel for coverage and the cross-pipe
  cylinder parameter for shading. Balls stamp sphere-shaded **discs**; both
  are depth-tested (nearer wins), and balls draw after segments so joints win
  ties. End-on segments collapse to a small disc. This replaced the earlier
  *scatter* (step along the centerline, floor-round a perpendicular span),
  which left off-pixel holes inside diagonal tubes — a gather evaluates every
  destination LED, so no interior pixel can be missed. Discs, not squares
  (second pass): joints are spheres, and a sphere's orthographic projection
  is a circle from any viewing angle — screen-aligned squares betrayed the
  projection as soon as the lattice rotated. Capsule end-caps are rounded
  (natural pipe tips; the joint ball covers the overlap).
- **Lighting** (fourth pass — full-surface Phong replaces the 1 px specular
  stripe): a rig of one directional **sun** (`SUN ∝ (0.35, −0.85, 0.35)` at
  rig angle 0; rows index top-down, so up is −y) plus **two fill point
  sources** at corner offsets `(−1,−1,+1)`/`(+1,−1,−1)` × room half-extents ×
  `FILL_DIST = 3.0` (direction only, no falloff), all rotating together about
  Y through the room center, one revolution per `RIG_ROT_BEATS = 32` beats.
  The rig is autonomous — RstRot does not touch it. Every pipe pixel gets a
  **cylinder normal** `N(t) = t·M̂ + √(1−t²)·V̂′` (t = cross-pipe offset);
  every joint/end-cap pixel gets a **sphere normal** from its screen offsets.
  Brightness = `paletteBri · depthCue · lerp(1, clamp(AMBIENT + diffuse),
  Shaded)` with `AMBIENT = 0.15`, `diffuse = 0.65·(N·sun)₊ +
  0.30·Σ(N·fillⱼ)₊` — tuned so pipes usually reach peak saturated
  brightness somewhere on their lit side in a dark scene (all `CURATE:`).
  The sun and specular half-vector are per-segment constants; the two fill
  directions are evaluated once at the segment **midpoint** (they sit far
  out, so their direction barely varies along a segment — fifth pass, avoids
  two normalizes per pixel; `CURATE:`).
  Specular stays Blinn-Phong exponent 4, now evaluated per pixel:
  `spec = min(1, (N·H_w)⁴ · SPEC_GAIN · (2·Shaded + 1.5·audioLevel))`,
  pushing brightness toward full and saturation toward the 35 floor. Because
  the rig rotates independently of the lattice, the bright crest slides
  around the tubes even when the lattice is static.
- **Antialiasing** (part of shading): the exact distance field drives a
  smooth coverage ramp `clamp((r + AA_WIDTH/2 − dist)/AA_WIDTH, 0, 1)`
  (`AA_WIDTH = 1.5` px, `CURATE:`) on every silhouette AND the rounded
  capsule/disc end-caps; coverage folds into brightness, and
  partial-coverage rim pixels blend via `lightest()` WITHOUT writing the
  depth buffer, so soft rims never occlude. Widened from the fourth pass's
  fixed 1 px ramp (fifth pass) for smoother edges.
- **Shaded = 0 fast path**: the knob (renamed from Hglht, fourth pass)
  disables the per-pixel lighting — the gather collapses to a hard binary
  coverage (`dist ≤ r + 0.5`) with flat depth-cued color, no lights and no
  AA. Still gap-free (it is the same gather), just cheap and hard-edged.
  Cost at Shaded > 0 is ~2–3× the flat path (`CURATE:` verify frame time at
  density 12 + rotation + Shaded 1; Shaded 0 is the escape hatch).
- **Shaded response curve** (sixth pass): the knob no longer drives the
  pipeline linearly. 0 = flat fast path (unchanged); **(0, 0.1] is a
  ramp-in zone** — `frameAABlend = knob/0.1` crossfades every pixel's
  coverage between the hard flat edge and the smooth AA ramp (and fades the
  specular gain in), so turning the knob (or a PlsShd pulse) up from 0
  eases in instead of snapping; **[0.1, 1] holds the classic phong range**,
  piecewise-remapped so 0.5 keeps its exact nominal meaning and 1 its full
  meaning (anchors 0.1→0, 0.5→0.5, 1→1).
- **CapDia** (sixth pass): joint/cap balls scale by the CapDia knob
  (`ballHalf = (thick/2 + 1px) · capDia`); at the **default 0 they are
  hidden entirely** (raster skipped) — the rounded capsule ends keep pipes
  looking continuous — and PlsCap flashes them in. `CURATE:` the capless
  resting look.
- **Rotation**: `RotX`/`RotY` are **speeds** — 100% = 90° per beat, angles
  accumulate on the tempo clock (`angle += rate·90°·dBeats`), so 25% at
  CapDiv=1 drifts a quarter-turn every 4 beats. `RstRot` zeros both speeds
  and snaps the angles to 0 (an instant jump-cut, per spec). Rotation
  continues during a drain fade.
- **Buffers / zero-alloc render**: everything preallocated in the constructor —
  4× color `int[2250]`, 4× depth `float[2250]`, occupancy
  `boolean[12][11][12]`, segment/ball parallel arrays, pipe-state arrays (3
  slots), direction scratch `int[6]`×2, elbow ring buffer (48). The render
  path allocates nothing; per-frame `Arrays.fill` buffer clears are writes,
  not allocations. Event-rate exceptions (all noted): occupancy clears at
  drain end, `LX.log` strings at drain start/end and from `TriggerBag.fire()`.
- **Door columns**: cube face columns always carry the full 45 points (the
  `Apotheneum.Cube.Face` constructor enforces exactly `GRID_HEIGHT` points per
  column — doors are exposed via `available()`, **not** shorter point arrays),
  so the blit writes every row of the 50×45 buffer directly.

## Audio mapping

**Audio depth knob**: `CompoundParameter("Audio", 0)` (key `audio`), attached
via `audio.setDepth(audioDepth)`. **Default 0 = pure screensaver.** As of the
third pass audio drives two things, neither of which is motion:

- **`bassHit()`** → sparkle with loudness-scaled breadth: a hit flashes the
  `1 + 47·min(1, SPARKLE_BREADTH·level)` most recent caps white
  (SPARKLE_BREADTH = 2, ring buffer of 48; a 5 px plus, depth-tested so
  occluded elbows don't flash), decaying over 500 ms. Quiet hits flash a few
  caps, loud hits flash up to all 48. Flash amplitude scales with
  `audio.depth()`; the manual `Sparkle` trigger fires ALL caps at full
  brightness regardless of the knob.
- **`level`** → Phong highlight boost: the specular gain is
  `SPEC_GAIN·(2·Shaded + AUDIO_SPEC_BOOST·level)` (AUDIO_SPEC_BOOST = 1.5),
  now applied across every shaded pixel (fourth pass) — louder music, hotter
  crest on every tube. `level` is depth-scaled and silence-safe, so Audio = 0
  keeps highlights governed by the Shaded knob alone.

Removed 2026-07-06: the `level` → growth-rate boost and the `bassHit` →
grid-gate early release (growth is fully beat-planned; audio never changes
motion). `trebleHit` is unused. Fully presentable with zero audio.

## Beat planning

Replaces the old Energy/Sync/TempoDiv tempo mapping (2026-07-06); rebuilt
Mystify-style in the fourth pass. Two knobs:

- **`CapDiv`** (UI label; param key `onBeats`, renamed third pass) ∈
  {3/4, 1, 2, 4, 8} beats (custom `Beats` enum — `Tempo.Division`'s ordinal
  space interleaves triplet/dotted members, so no contiguous subrange
  expresses this set). Defines the **cap grid**.
- **`Speed`**: target growth speed in cells per beat; steers planning only.

Mechanics (fourth pass):

1. **Global grid** (replaces the pattern-local epoch): cap times are
   absolute-beat multiples of CapDiv on the engine's composite basis,
   phase-aligned to the global tempo grid. Drains, re-activation, and reloads
   do NOT reset cap phase — every cap everywhere shares the transport's
   grid. A backward clock jump (tap tempo / transport reset) re-anchors all
   live caps via `scheduleCap`.
2. **Hard cap targets, derived velocity** (the Mystify idiom, cf.
   `TempoLock`'s beat-position doc): a run's cap time is a hard absolute-beat
   target; each frame the head covers the same fraction of its remaining
   distance as the frame covers of the remaining time — exact on-grid
   arrival, robust to live BPM changes. **The Speed knob does not move
   in-flight heads**; it steers the next plan. At max speed every cap lands
   every division, for all pipes.
   **Speed 0 pauses growth**: heads and cap checks freeze (rotation, sparkle
   and drain stay live); spawns/teleports while paused park with an infinite
   cap time. **Resume** re-schedules every in-flight cap on the global grid,
   landing 0.5–1.5 divisions out (the same `scheduleCap` rule as any
   off-grid start).
3. **Run planning** (`planRun`, at every cap/spawn/teleport): anchor at the
   nearest in-bounds cell to the head; scan the 6 directions for free-run and
   in-bounds-run lengths; prefer straight (p = 0.55), else weighted-random by
   free run length (space-filling bias). **No free direction → intersect**:
   weighted-random over in-bounds runs ignoring occupancy, excluding an
   immediate reversal when possible (replaces the old teleport-on-boxed-in;
   unavoidable intersections are drawn as-is). Run length n = 1 + rand(m),
   **uniform**: combined with the length-weighted direction pick, every free
   candidate cell is an equally likely elbow target, so elbows distribute
   through the room volume instead of hugging the walls (2026-07-06 second
   pass — the old max-of-two long bias compounded with the direction
   weighting and pushed corners to the extremities).
4. **Cap scheduling** (`scheduleCap`, one rule for caps, spawns, teleports,
   pause-resume, and clock jumps): with `tIdeal = dist/Speed`,
   `capBeat = max(firstGridPoint ≥ now + 0.5·CapDiv,
   rint((now + tIdeal)/CapDiv)·CapDiv)` — the global grid point nearest the
   ideal arrival, never sooner than half a division. Slow speeds pick higher
   multiples (long transits); fast speeds cap every division; **congestion
   (short available runs) degrades speed rather than grid alignment**.
   Escape hatch: when even one whole division is ≥2× too slow for the knob
   (`tIdeal ≤ 0.75·CapDiv`), the cap lands on a **half-division** — and the
   next plan's `rint` re-aligns to the whole grid automatically. Thresholds
   `CURATE:`. From off-grid starts the first cap lands 0.5–1.5 divisions
   out. Because arrival is exact (derived velocity), caps land on the grid
   AND on lattice corners with zero snapping; heads never sit off-lattice.
5. **Speed/BPM changes mid-run**: nothing moves — the cap target is hard and
   velocity is derived, so in-flight runs keep their schedule exactly; the
   knob shapes the next plan. (The round-1 "drift and self-heal" behavior is
   gone by design.)
6. **CapDiv changes mid-run**: the in-flight run finishes at its scheduled
   cap; the next plan snaps to the new grid — one transitional run, accepted.
7. **Speed × CapDiv interaction**: at CapDiv = 8 with high Speed in a small
   room, an interval "wants" more cells than the room holds, so runs are
   physically forced slower (down to maxRun per half-division with the
   escape hatch). Large CapDiv values read as *slower*, sparser caps — by
   design.

## Speed (replaces Energy)

`Speed` (fourth pass: back to a **continuous** CompoundParameter, 0–64
cells/beat, default 1, knob exponent 3 so the musical low end has
resolution — `CURATE:`) is the single motion control; the pattern **departs
from the series master-Energy convention** (growth rate is musical, in cells
per beat). It is a *target*: the planner picks each run's transit (whole
CapDiv multiples) so implied speeds sit near the knob, and in-flight runs
ignore knob moves entirely (hard grid schedules). Above the point where runs
complete within one division the pace saturates at roomSize/CapDiv; at the
extreme a wall crossing takes well under a second. **0 = pause**; resuming
re-schedules every cap onto the global grid 0.5–1.5 divisions out — and as
of the sixth pass this is **self-healing**: any pipe parked at an infinite
cap (scheduled while paused, no matter how — project load with Speed 0,
knob turns while inactive) re-schedules itself the first frame Speed is
positive, so the animation always kicks off. `CURATE:` where the usable
band sits live.

## Parameters

UI/registration order (sixth pass — gesture triggers first): Sparkle,
Teleport, the three pulses, Drain, RndTrig, then Pipes, Speed, CapDiv,
Thick, CapDia, Density, Shaded, Reverse, JmpPct, JumpTo, HoldBars, RotX,
RotY, RstRot, Audio
(21 total — well past the ~12 series guideline; this is the series'
flagship-complexity pattern).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `sparkle` | Sparkle | TriggerParameter | — | — | flash ALL recent elbow joints white at full brightness (manual bass sparkle) |
| `teleport` | Teleport | TriggerParameter | — | — | one random growing pipe caps and jumps to a random free cell (the classic) |
| `plsThk` | PlsThk | TriggerParameter | — | — | pulse Thick 50% of its range toward/past center (3.5): 32nd-note attack, one-beat decay |
| `plsShd` | PlsShd | TriggerParameter | — | — | pulse Shaded 50% of its range toward/past center (0.5), same envelope |
| `plsCap` | PlsCap | TriggerParameter | — | — | pulse CapDia 50% of its range toward/past center (1.0), same envelope |
| `drain` | Drain | TriggerParameter | — | — | fade out concluding exactly on a beat (0.5–1.5 beats), clear, restart with the Pipes-knob count in the first palette colors |
| `rndTrig` | RndTrig | TriggerParameter | — | — | randomly fire one of the gesture triggers or toggle Reverse (`TriggerBag`) |
| `pipes` | Pipes | DiscreteParameter | 1 | 1..3 | concurrent pipe count; raising spawns a fresh pipe, lowering culls the oldest (capped where it stopped) |
| `speed` | Speed | CompoundParameter | 1 | 0..64 (exp 3) | target growth speed in cells/beat, steers run-transit planning; 0 pauses, resume re-caps on-grid 0.5–1.5 divisions out |
| `onBeats` | CapDiv | EnumParameter&lt;Beats&gt; | 1 | 3/4, 1, 2, 4, 8 | beat division every cap lands on, phase-aligned to the global tempo grid (key `onBeats` kept for .lxp compat) |
| `thickness` | Thick | CompoundParameter | 3.5 | 1..6 | pipe thickness in px, whole model in realtime (unclamped; 6 px at high density merges cells) |
| `capDia` | CapDia | CompoundParameter | 0 | 0..2 | joint/cap ball size as a multiple of the classic cap; **0 (default) hides caps** — PlsCap pulses them in |
| `density` | Density | DiscreteParameter | 10 | 6..12 | room grid cells per axis; **takes effect at the next drain** |
| `shaded` | Shaded | CompoundParameter | 0.5 | 0..1 | Phong shading: 0 = flat fast render (all shader compute skipped); 0→0.1 fades shading/AA in; 0.5 = nominal |
| `reverse` | Reverse | BooleanParameter | off | — | play backward: unbuild the lattice newest-first on the CapDiv grid; auto-flips forward when empty |
| `jmpPct` | JmpPct | CompoundParameter | 1 | 0..1 | how far through the build JumpTo seeds: 0 = empty (instant drain/restart), 1 = the busy auto-drain fill level |
| `jumpTo` | JumpTo | TriggerParameter | — | — | jump the animation to JmpPct through the build; playback continues in the current direction (Reverse unbuilds it) |
| `holdBars` | HoldBars | BooleanParameter | off | — | progress-bar mode: instant clear, bars fill L-to-R (interior view, replicated projection), park in depth between bars (see HoldBars section) |
| `rotX` | RotX | CompoundParameter | 0 | 0..1 | lattice rotation speed about the horizontal x axis; 100% = 90°/beat |
| `rotY` | RotY | CompoundParameter | 0 | 0..1 | lattice rotation speed about the vertical y axis; 100% = 90°/beat |
| `rstRot` | RstRot | TriggerParameter | — | — | zero RotX/RotY and snap back to the orthogonal projection (jump-cut; the lighting rig keeps turning) |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full bass-sparkle + highlight response |

Series RndTrig pass (2026-07-06): `meta` → `rndTrig` (label Meta → RndTrig),
moved from last to immediately after the plain triggers; `.lxp` values on the
old `meta` path are dropped on load.
Removed 2026-07-06: `energy`, `sync`, `tempoDiv` — superseded by the
beat-planned `speed`/`onBeats` pair (same precedent as the earlier `growthDiv`
removal) — and, in the second pass, `newPipe` (superseded by the `pipes`
knob). Third pass: `hue` removed (color is now purely palette-derived, series
house rules), and `speed` changed type (CompoundParameter →
EnumParameter&lt;Rate&gt;). Fourth pass: `hglht` → `shaded` (repurposed for
full-surface shading) and `speed` back to a continuous CompoundParameter —
old `.lxp` `hglht`/enum-speed values drop or restore oddly, accepted. Sixth
pass: added `plsThk`/`plsShd`/`plsCap`/`capDia`/`reverse` (no removals —
old `.lxp` files restore cleanly); its short-lived `jmpEnd` became
`jumpTo` + `jmpPct` the next day (seventh pass; the day-old `jmpEnd` key
warns). Old `.lxp` files referencing removed keys restore every other
parameter and log an unknown-parameter warning for each.

Pipe color (third pass — series house rules, no hue knob): full H/S/B
straight from the palette swatch. Pipe i always wears slot `i % n` — so the
look is **consistent across drains** (fifth pass: the per-drain slot rotation
was retired by request; concurrent pipes still get distinct entries). A
shorter swatch keeps its defined colors and
fills the remainder with perceptually-even fully-saturated hues
(`PerceptualHue.fillCircle`, the Rubik `computeFaceColors` template); an
empty swatch degenerates to an even perceptual spread. The palette color's
own saturation and brightness are respected — pastel/dim swatch entries
render as such, with depth shading multiplied on top (`CURATE:` verify low-S/B
palettes read on the LEDs). Color is captured at run start, so retained
segments keep their color. Thickness is NOT captured: the Thick knob
rethickens the entire model in realtime (second pass).

## Pulses (sixth pass)

`PlsThk` / `PlsShd` / `PlsCap` each fire an in-pattern beat-synced AD
envelope: a **32nd-note (1/8-beat) linear attack** to 1, then a **one-beat
linear decay** to 0 (BPM-aware via dBeats; an LX AHDSR modulator cannot
express the direction rule, so the envelope lives in the pattern). At
trigger time the direction is latched **toward the param's range center**
(Thick 3.5, Shaded 0.5, CapDia 1.0) from wherever the base knob sits, and
the excursion is **50% of the total range** (Thick ±2.5, Shaded ±0.5,
CapDia ±1.0) — so the pulse usually crosses center. Pulses move the
EFFECTIVE per-frame value only (clamped to the range; the UI knob never
moves), so the whole retained model pulses (thickness is realtime).
Retriggering mid-decay re-latches direction and resumes the attack from the
current level. `CURATE:` amounts/times.

## Reverse / JumpTo (sixth-seventh pass)

`Reverse` (bool) plays the animation backward: growth stops (live pipes are
released — their partial segments become the first tails) and the retained
geometry unbuilds **newest-first** as a global LIFO (segments interleave
across pipes, so one tail cursor rather than per-pipe retraces). The tail
segment's B end retracts toward A with the same derived-velocity rule, so
**un-caps land on the global CapDiv grid at the Speed knob's pace** (0
pauses; the ∞-cap self-heal applies here too). At each un-cap the segment
pops, the balls created after it vanish (a `segBallCount` snapshot logged
at every segment's creation — elbows disappear with their segment), and its
swept cells free up (guarded `unoccupy`; shared joint/intersection cells
free exactly once — occupancy only steers planning, drift accepted). When
the last segment is consumed, Reverse **auto-flips off and fresh pipes
respawn** — the animation ping-pongs. Toggling forward mid-unbuild also
respawns immediately (old pipes are not resumed). A drain forces Reverse
off. `CURATE:` unbuild pacing, ping-pong feel.

`JumpTo` (seventh pass — generalizes the day-old JmpEnd) jumps the
animation to **`JmpPct`** of the way through the build: it synthesizes a
room filled to `JmpPct × 60%` (the auto-drain level) with the current
settings — density applies immediately, colors/thickness per the usual
rules — by running the same growth walk (`walkStep`) synchronously with
`Pipes`-many walkers. **Playback then continues in the current direction**
(JumpTo no longer touches Reverse): forward keeps the walkers alive and
growing seamlessly from the seeded state (`JmpPct = 0` reads as an instant
drain + restart), while Reverse leaves the geometry to the unbuild cursor
(`JmpPct = 1` + Reverse = the classic jump-to-end seed). Event-rate but
walks ~hundreds of cells in one call at high percentages (`CURATE:` measure
the frame hitch); bounded by the retained-list capacities and an iteration
guard. Not in the RndTrig bag.

## HoldBars (eighth pass) — progress bars that secretly build 3D

Conceived for the rafters→opus transition of *Communicating*: with `Shaded` at
0 (flat) and `HoldBars` on, the pattern impersonates an institutional loading
screen — **progress bars completing left-to-right that never go away** — while
secretly accruing a 3D lattice for a later reveal (`Shaded` + `RotX`/`RotY`
automation). "There is joy in waiting if you just use your imagination."

- **Replicated projection.** All four walls share ONE mapping
  (`u = (gx − x)·cw`, `v = y·ch`, `depth = z`) instead of the
  corner-continuous table — corner continuity intentionally breaks (CURATE).
  Mirrored on the exterior plane, so `+x` growth reads **left→right for a
  viewer INSIDE the cube** (interior faces are pixel copies of exterior
  faces), and **±z runs are invisible on every wall** (u,v independent of z).
  `HOLD_BARS_FLIP` (CURATE) swaps the read if the interior is backwards on
  hardware. Rotation still works — rotate-then-project is upstream — so the
  reveal needs no projection change.
- **Bar lifecycle.** On enable: **instant clear** (no drain fade; cancels any
  drain/reverse), then Pipes-many walkers spawn as bars. Spawn/advance picks
  a free cell in the **interior-left half** (`x < gx/2`) at a **Y not among
  the last `BAR_Y_HISTORY = 4` bar rows** (CURATE), random z. FILLING: runs
  forced **+x** (straight; random 1..maxRun lengths, so the bar advances in
  beat-planned chunks on the CapDiv grid — Speed/CapDiv pace it), intersecting
  occupied cells rather than turning. At the right edge → PARKED: runs forced
  **±z** (longest free side, bouncing; intersects when boxed) for
  `HOLD_WAIT_CAPS = 4` caps (CURATE — the invisible "wait"), then
  **auto-advance via the teleport mechanism** (cap ball hidden at CapDia 0)
  to a fresh bar. The manual `Teleport` trigger forces the advance
  immediately, so the wait can also be performed from the timeline.
- **Bars never go away**: the fill-based auto-drain is bypassed in bar
  planning; overflow/no-free-cell drains remain as safety valves (CURATE:
  long holds eventually exhaust rows/cells — findBarCell degrades to any
  free cell, and a truly full room drains). Manual `Drain` still works.
- **Disable** keeps all geometry: walkers replan as free pipes at their next
  caps and the projection snaps back corner-continuous (a jump-cut — mask it
  with rotation, or leave HoldBars on through the reveal).
- **Not adapted in v1** (doc'd, accepted): `JumpTo` synthesis uses the free
  walk; Reverse unbuilds bars right-to-left (fun — CURATE); pipe color rules
  unchanged (a walker's bars share its palette slot — CURATE: per-bar slot
  cycling as an option).

## Triggers

Gesture triggers, small → large:

- `sparkle` — **small**: ALL of the ≤48 most recent elbow joints flash white
  and decay over 500 ms (bass hits flash a loudness-scaled subset); a
  stationary glitter accent, no state changes.
- `plsThk` / `plsShd` / `plsCap` — **small/medium**: the beat-synced pulses
  (see Pulses). PlsCap flashes the (default-hidden) joint balls into
  existence for a beat.
- `rstRot` — **small/medium**: zeros the rotation speeds and jump-cuts the
  projection back to orthogonal; a no-op when already static. (Not in the
  bag as of the sixth pass.)
- `teleport` — **medium**: instant; a cap ball marks the disconnect point and
  the pipe continues from a random free cell. Reads immediately (the classic
  NT gag).
- `jumpTo` — **large**: instantly seed the room at JmpPct through the build,
  continuing in the current direction (see Reverse / JumpTo). Not in the bag.
- `drain` — **large**: a brightness fade (not motion) whose duration is
  measured at trigger time to conclude **exactly on a beat**, between 0.5 and
  1.5 beats out (if the next beat is closer than half a beat, it targets the
  one after; a mid-drain BPM change drifts the conclusion — accepted). Then
  the room clears, the pending density applies, and the **Pipes-knob count**
  of pipes respawns in the **first palette colors** (fifth pass — consistent
  every drain, no palette rotation; caps stay phase-aligned to the global
  tempo grid — a drain does not reset cap phase, fourth pass). Also fires
  automatically at >60% fill, if a teleport/spawn finds no free cell, or on
  retained-geometry overflow. Forces Reverse off.

Boxed-in pipes no longer teleport — they intersect (see Beat planning §3).

## RndTrig bag (sixth pass — triggers only)

The bag is strictly: **Sparkle, Teleport, PlsThk, PlsShd, PlsCap, Drain,
and a Reverse toggle** (a non-UI `RevTgl` entry). ALL parameter jumpables
were removed (thickness, density, pipes, shaded, speed, onBeats, rotX,
rotY) and `rstRot` left the pool — the pulse triggers took over the
param-motion role. Historical jump-candidate curation rows retired with
them.

## Simulation-principles compliance

Fastest sustained *growth* motion is the extrusion tip crossing a wall
(`gx` cells):

- **The growth-speed floor is RETIRED** (2026-07-06 second pass, explicit
  user override): Speed is unclamped up to 16 cells/beat — at 120 BPM that is
  ~32 cells/s, a full wall crossing in well under a second at any density.
  The old ≥5 s guarantee (round-1 `speedEff` clamp; before that, `minSegMs`)
  no longer holds for growth; the CapDiv grid is now the pacing mechanism,
  and run planning still never schedules a run faster than the knob (the
  `++k` rule in `planRun`). `CURATE:` the usable top of the knob on the
  sculpture.
- Drain: a beat-aligned (0.5–1.5 beat ≈ 0.25–1.5 s) full-surface **brightness
  ramp** — a fade, not motion, so the traversal cap does not apply; nothing
  translates during it. `CURATE:` at fast tempos this is a much snappier
  clear than the old fixed 3 s — verify it doesn't read as a flash cut; if
  so, minimum-duration could rise to e.g. 2 beats.
- Elbow sparkle: a **stationary** 500 ms brightness flash at fixed joints, not
  motion; no traversal involved.
- Balls/caps appear instantaneously but are single-cell events (~6 px) —
  event-like, and they persist indefinitely as lattice geometry.
- **Rotation is a separate motion class and can violate the 5 s translation
  floor**: at 100% (90°/beat, 120 BPM) outer geometry at r ≈ 25 px moves
  ~78 px/s tangentially — a wall sweep in well under a second. Implemented
  per spec (the knob goes there deliberately); the RndTrig pool only jumps to
  ≤25%. `CURATE:` decide live whether the knob range needs a cap (~25–50%)
  or whether full-rate spins are a wanted big-moment effect.

Contrast/brightness: fully saturated pipes on true black; the only mid-tones
are the depth cue (floor at 50% brightness so far pipes stay readable, not
muddy) and the 1 px sun-specular stripe. No fine texture. Forms are 1–6 px
by the Thick knob (unclamped and realtime on the whole model, 2026-07-06
second pass): at 1 px pipes are hairline and the specular stripe disables
(span < 2); at 6 px on density 12 adjacent lattice cells merge — both are
deliberate curation territory. `CURATE:` whether 1 px reads at all on the
sculpture.

Time-to-fill at defaults (rough): 900 cells × 60% = 540 cells; 1 pipe at
1 cell/beat @ 120 BPM ≈ 4.5 min to auto-drain; 3 pipes ≈ 1.5 min. Slower than
the old peak, faster than the old ambient — and high Speed now compresses
this dramatically (Speed 16 can fill in tens of seconds). The `drain` trigger
and RndTrig exist to short-circuit this live. `CURATE:` fill pacing at
performance tempos.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 (8th pass) | **HoldBars** boolean (registered after JumpTo): progress-bar mode for the rafters→opus transition. Replicated single-mapping projection on all four walls (`u=(gx−x)·cw`, depth=z; `HOLD_BARS_FLIP` CURATE) so bars read L-to-R from the cube interior and ±z runs are invisible everywhere; instant clear on enable; constrained spawn picks (interior-left half, un-recent Y via `BAR_Y_HISTORY=4` ring); `planBarRun` phase machine (FILLING forced +x → PARKED forced ±z for `HOLD_WAIT_CAPS=4` caps → auto-advance via the teleport mechanism; manual Teleport forces it); fill auto-drain bypassed in bar mode (overflow/no-free-cell drains kept); disable keeps geometry + snaps projection back. `forceDir` hook added to `walkStep` (reset after each use; JumpTo synthesis unaffected). CURATE: interior read direction, wait length, Y-history size, corner-continuity break, long-hold row exhaustion, Reverse-unbuild feel | Jeff's HoldBars concept: unshaded pipes as never-completing progress bars, depth-parking as the hidden wait, Shaded+rotation automation as the 3D reveal |
| 2026-07-04 | Initial implementation | First pass per approved plan; all CURATE: constants unverified on sculpture |
| 2026-07-05 | Adversarial review pass: `beginSegment` retime estimate now folds in the audio level boost — previously completions landed up to 23% early of the retimed boundary whenever the Audio knob was up; corrected the thickness-clamp figure | Verified improver's claims against HEAD |
| 2026-07-05 | Fixed sparkle overlay self-occlusion; enforced ≥5 s traversal cap at all densities via `minSegMs` floor; added `audio` depth knob (default 0) wired through `AudioReactive.setDepth`; migrated tempo handling to shared `TempoLock` with `sync`/`tempoDiv`; removed `growthDiv`; added `Sparkle` manual trigger; corrected false door-column claim | Review session (Fable): bug fixes + series-convention upgrade |
| 2026-07-05 | Integration pass: `TriggerBag.jumpable(DiscreteParameter, lo, hi)` subrange overload added; `tempoDiv` in the meta pool over SIXTEENTH..HALF; fixed a stale-gate nit (`crossed()` polled every frame) | Pipes3D agent's util request + series `crossed()` idiom |
| 2026-07-06 | Series RndTrig ordering: `meta` → `rndTrig` (label RndTrig), moved from last to immediately after the plain triggers; `.lxp` values on the old `meta` path are dropped on load | Series convention: TriggerBag meta trigger sits right after the other trigger params |
| 2026-07-07 (7th pass) | **JumpTo/JmpPct**: `jmpEnd` → `jumpTo` trigger + new `jmpPct` knob (0..1, default 1) scaling the synthesized fill to `pct × 60%`. JumpTo now respects the CURRENT direction instead of forcing Reverse on: forward keeps the synthesis walkers alive and growing from the seed (`planRun` each + `syncPipeCount`; pct 0 = instant drain + restart), Reverse hands the geometry to the unbuild cursor (pct 1 + Reverse = the old JmpEnd). Params ordered Reverse, JmpPct, JumpTo. `.lxp` fallout: the day-old `jmpEnd` key warns | User request: normalized jump percentage; JumpTo rename |
| 2026-07-07 (6th pass) | Performance-gesture round. **Pulses**: PlsThk/PlsShd/PlsCap triggers fire in-pattern AD envelopes (32nd-note attack, one-beat decay, BPM-aware) pushing the EFFECTIVE value 50% of the param's range toward/past its center (Thick→3.5, Shaded→0.5, CapDia→1), latched-direction, retrigger-safe; knobs never move. **CapDia** knob (0..2, default 0 = caps hidden; capsule ends keep pipes continuous; raster skipped below 0.3 px). **Shaded response curve**: (0, 0.1] crossfades hard-flat → AA'd pipeline (`frameAABlend`, spec fades in too); [0.1, 1] piecewise-remapped keeping the 0.5/1.0 anchors; 0 still skips all shader compute. **Reverse**: global newest-first LIFO unbuild on the CapDiv grid (derived velocity, `segBallCount` log pops elbows with their segments, guarded `unoccupy` frees cells); auto-flips forward + respawns when empty; drains force it off. **JmpEnd**: synchronous `walkStep` synthesis of a ~60%-filled room with current settings, then Reverse on (walkStep factored out of planRun; no scheduling/auto-drain during synthesis). **Speed-0 kickoff fix**: pipes parked at infinite caps self-heal in updatePipes/updateReverse the first frame Speed > 0. **Order**: Sparkle, Teleport, PlsThk, PlsShd, PlsCap, Drain, RndTrig first; CapDia by Thick; Reverse/JmpEnd before rotation. **Bag**: strictly the six gesture triggers + a RevTgl toggle — ALL param jumpables removed, rstRot out. CURATE: pulse amounts/times, capless resting look, unbuild pacing, ping-pong feel, JmpEnd hitch | User live-curation notes (6th round) + decisions: unbuild auto-ping-pongs; JmpEnd flips Reverse; bag strictly triggers |
| 2026-07-06 (5th pass) | Live-curation round 5, from a screenshot. **Gap bug fixed**: the shaded rasterizer was a *scatter* (step the projected centerline, floor-round perpendicular pixel spans), which missed interior pixels on rotated diagonals — rewrote `rasterSegment`'s per-wall emission as a **gather** over destination LEDs: per pixel in the projected capsule's bounding box, evaluate the exact 2D point-to-segment distance for coverage and the signed cross-pipe parameter for shading. Gap-free by construction, and per-LED (the user's question — was per-model-point scattered, now per projected LED). **More AA**: coverage is a `clamp((r + AA_WIDTH/2 − dist)/AA_WIDTH)` ramp (`AA_WIDTH = 1.5` px) on silhouettes and the rounded capsule/disc end-caps (was a fixed 1 px). `stampDisc` rim switched to the same ramp; segment caps now rounded (joint balls cover the overlap). Fills moved from per-step to per-segment-midpoint (far lights, negligible variation). **Drain color reset**: `drainCount` removed — pipe i always wears palette slot i, so pipes come back in the same first-N colors every drain (per-drain rotation retired by request). CURATE: `AA_WIDTH`, fill-midpoint approx, gather frame-time | User live-curation notes (5th round): fix interior gaps, smoother edges, consistent drain colors |
| 2026-07-06 (4th pass) | Live-curation round 4. **Lighting rig**: the Phong sun now rotates about Y (one revolution per 32 beats) together with two new corner fill point lights (`FILL_CORNERS`, `FILL_DIST = 3` half-extents, direction-only) — a third coordinate system beside the fixed walls and the RotX/RotY lattice; RstRot leaves the rig alone. **Full-surface Phong** replaces the 1 px stripe: per-pixel cylinder normals on pipe bodies and sphere normals on joints/end-caps, `AMBIENT = 0.15`, `KD_SUN = 0.65`, `KD_FILL = 0.30`, spec exponent 4 per pixel — tubes read as rounded, and coverage-based AA (widened spans/discs, lightest-blend rims without depth writes) removes projection jaggies. `Hglht` → **`Shaded`**: the flat↔phong mix and spec gain; **0 disables all shading processing** (flat fast path). **Planner** rebuilt Mystify-style: cap times are hard targets on the GLOBAL tempo grid (absolute-beat CapDiv multiples — the pattern-local epoch is gone; drains no longer reset cap phase); per-frame velocity is derived from remaining-distance/remaining-time (exact arrival, BPM-robust; the Speed knob no longer moves in-flight runs); one `scheduleCap` rule covers caps, spawns, teleports, pause-resume and clock jumps (nearest grid point to the ideal arrival, ≥0.5 divisions out, with a half-division escape when a whole division is ≥2× too slow — congestion degrades speed, never grid alignment). **Speed** back to continuous (0–64, exponent-3 knob); 0 still pauses. **CapDiv** ordered right after Speed. All lighting constants + escape thresholds `CURATE:` | User live-curation notes (4th round): rotate highlight source; phong-shade everything (AA + rounded pipes) with a corner-fill rig; CapDiv after Speed; Mystify-style grid-first planner |
| 2026-07-06 (3rd pass) | Live-curation feedback round 3. **Params**: RstRot moved to sit right after RotX/RotY in the UI; `onBeats` relabeled **CapDiv** (key kept); `hue` REMOVED — color now follows the series palette house rules (full H/S/B from the swatch, `(drainCount + i)` slot rotation, `PerceptualHue.fillCircle` fill for short swatches per the Rubik template; retained geometry stores H/S/B, palette S/B respected under depth shading). **Hglht** knob added (0..1, default 0.5 = nominal): scales the Blinn-Phong gain, 0 skips the stripe pass; lobe exponent lowered 8 → 4 so most orientations show a highlight ("I only see some" fix). **Speed** → EnumParameter&lt;Rate&gt; over a 16-step geometric ladder 0–64 cells/beat: 0 PAUSES growth (heads + caps freeze; spawns park at infinite cap time); resume realigns every in-flight cap onto the CapDiv grid 0.5–1.5 divisions out via new `realignCaps` + stored `pGoal` run targets. **Audio**: bass hits flash a loudness-scaled number of recent caps (ring buffer 16 → 48, `1 + 47·min(1, 2·level)`); `audio.level` also boosts the specular gain (AUDIO_SPEC_BOOST = 1.5). Jump pool: −hue, +hglht, speed → ordinal 0.5..4 (PAUSE excluded) | User live-curation notes (3rd round); palette rules matched to Satori/Rubik/Terraform/Zot house idiom |
| 2026-07-06 (2nd pass) | Live-curation feedback round. **Elbow distribution**: run length now uniform over 1..maxRun (was max-of-two long-biased, which compounded with the length-weighted direction pick and pushed all corners to the walls) — every free candidate cell is now an equally likely elbow target. **Disc joints**: square stamps → discs (`stampDisc`, +0.25 px rounding margin) for balls and end-on cross-sections — a sphere projects as a circle from any angle, so joints stay correct under rotation. **Thick**: range 1–6 px, cell-size clamp removed, and thickness reads the live knob at raster time (whole-model realtime; `segHalfPx`/`ballHalfPx` capture arrays deleted). **Pipes knob**: `newPipe` trigger → `pipes` DiscreteParameter (1–3) reconciled by `syncPipeCount()` via `onParameterChanged` — raising births a fresh pipe, lowering culls the oldest (birth-stamped in `spawnPipe`; teleports keep their stamp), capped where it stopped; drain now respawns the knob count (knob never moves on its own — supersedes round-1 "one pipe"). **Speed**: clamp removed (`speedEff()`/`TRAVERSAL_MIN_MS` deleted), range 0.25–16 cells/beat — the ≥5 s growth floor is explicitly retired; pace saturates at roomSize/OnBeats once runs fit one interval. Meta pool: −newPipe, +pipes(1..3) | User live-curation notes (2nd round) + decisions: drain respawns knob count; thickness unclamped |
| 2026-07-06 | Curation rework per user notes. **Drain**: duration now measured at trigger to conclude exactly on a beat (0.5–1.5 beats, via new `TempoLock.msUntilNext`); respawns exactly ONE pipe; resets the cap-grid epoch. **Audio**: bassHit → sparkle is the only mapping; level→growth boost, bass gate-release removed; trebleHit unused. **Beat planning**: `energy`/`sync`/`tempoDiv` removed, replaced by `speed` (cells/beat, traversal-clamped) + `onBeats` (3/4–8, custom `Beats` enum); runs are integer-cell with bounded per-run speed trim so caps land exactly on the OnBeats grid AND lattice corners; boxed-in pipes now intersect (weighted, no reversal) instead of teleporting; speed/BPM changes self-heal via nearest-corner re-anchoring. **Rotation**: `rotX`/`rotY` speed knobs (100% = 90°/beat, tempo-locked accumulation) + `rstRot` jump-cut reset; rendering rebuilt from incremental persistent buffers to retained segment/ball lists with full per-frame re-raster through a rotate-then-project transform (zero-angle output verified identical to the old table). **Shading**: axis-aligned stripe replaced by a world-fixed sun-at-infinity Blinn-Phong specular (upper-corner light; highlights slide around pipes as the lattice turns). Jump pool: −tempoDiv, +speed[0.5..2], +onBeats[1..4], +rotX/rotY[0..0.25], +rstRot (registered trigger). All new CURATE: flags — sun direction/exponent, drain minimum at fast tempos, run-length bias, rotation-rate ceiling, fill pacing | User curation notes 2026-07-06 (+ AskUserQuestion decisions: any-multiple caps, spatial-lattice recovery, replace-all params, cells/beat speed, bounded trim, sun-specular request) |
