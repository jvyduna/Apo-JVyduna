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
LED-wall resolution, not a port: pipes are one cell thick, shading is a depth
cue plus a sun-specular stripe rather than full lighting.

The conceit for Apotheneum: the cube's four walls are the four walls of the NT
*room*. There is exactly one 3D pipe lattice in a ~10×9×10-cell volume, and
each wall shows an orthographic projection of that SAME object along its inward
normal — walk around the cube and you see the same pipes from four sides, with
corner-continuous mappings so adjacent walls agree at their shared edges. The
lattice can additionally rotate inside the room (RotX/RotY) while the walls,
the viewers, and the sun stay fixed.

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
- **Raster paths**: segments draw as thick 2D lines (~1 px steps along the
  screen length, a perpendicular span of ~thickness px, depth lerped per
  step); balls stamp slightly-oversized **discs**; both are depth-tested
  (nearer wins), and balls draw after segments so joints win ties. End-on
  segments collapse to a small disc (a circular pipe cross-section). Discs,
  not squares (2026-07-06 second pass): joints are spheres, and a sphere's
  orthographic projection is a circle from any viewing angle — screen-aligned
  squares betrayed the projection as soon as the lattice rotated.
- **Shading**: brightness factor `1 − 0.5·depthNorm` (far wall = 50%), elbow
  balls slightly larger (`+1 px` radius), brighter (`×1.15`), less saturated —
  the glossy joint read — and a **sun-specular stripe** (below).
- **Sun specular** (replaced the old axis-aligned stripe 2026-07-06): a
  world-fixed directional light at infinity toward an upper corner
  (`SUN ∝ (0.35, −0.85, 0.35)`; rows index top-down, so up is −y —
  `CURATE:` corner choice, flip signs if it reads lit-from-below). Per wall
  the Blinn-Phong half vector `H_w = normalize(SUN + V_w)` is a compile-time
  constant (`V_w` = the wall's view direction). Per segment per wall, with
  rotated pipe axis `A`: the cylinder lobe peaks at cross-pipe offset
  `t* = a/√(a²+b²)` with height `√(a²+b²)`, where `a = M̂·H`, `b = V̂′·H`,
  `M = V×A`, `V′ = V − (V·A)A`. Intensity = `min(1, peak⁸ · 1.4)`
  (`CURATE:` exponent/gain — vertical pipes get almost no stripe under a
  high sun, which is physical but worth a look). The 1 px stripe (desaturated
  toward 35, boosted ×1.15, both scaled by intensity) draws in a second pass
  at the lobe's offset so the body sweep can't clobber it at equal depth.
  Because the sun does not rotate with the lattice, highlights slide around
  the pipes as the model turns. Balls keep their uniform glossy look
  (`CURATE:` follow-up — a per-ball offset sun dot).
- **Rotation**: `RotX`/`RotY` are **speeds** — 100% = 90° per beat, angles
  accumulate on the tempo clock (`angle += rate·90°·dBeats`), so 25% at
  OnBeats=1 drifts a quarter-turn every 4 beats. `RstRot` zeros both speeds
  and snaps the angles to 0 (an instant jump-cut, per spec). Rotation
  continues during a drain fade.
- **Buffers / zero-alloc render**: everything preallocated in the constructor —
  4× color `int[2250]`, 4× depth `float[2250]`, occupancy
  `boolean[12][11][12]`, segment/ball parallel arrays, pipe-state arrays (3
  slots), direction scratch `int[6]`×2, elbow ring buffer (16). The render
  path allocates nothing; per-frame `Arrays.fill` buffer clears are writes,
  not allocations. Event-rate exceptions (all noted): occupancy clears at
  drain end, `LX.log` strings at drain start/end and from `TriggerBag.fire()`.
- **Door columns**: cube face columns always carry the full 45 points (the
  `Apotheneum.Cube.Face` constructor enforces exactly `GRID_HEIGHT` points per
  column — doors are exposed via `available()`, **not** shorter point arrays),
  so the blit writes every row of the 50×45 buffer directly.

## Audio mapping

**Audio depth knob**: `CompoundParameter("Audio", 0)` (key `audio`), attached
via `audio.setDepth(audioDepth)`. **Default 0 = pure screensaver.** As of
2026-07-06 audio drives exactly one thing:

- **`bassHit()`** → sparkle: the 16 most recent elbows flash white (a 5 px
  plus, depth-tested against the wall buffers so occluded elbows don't flash),
  decaying over 500 ms. The flash amplitude scales with `audio.depth()`; the
  manual `Sparkle` trigger fires it at full brightness regardless of the knob.

Removed 2026-07-06: the `level` → growth-rate boost and the `bassHit` →
grid-gate early release (both died with the Energy/Sync growth machinery —
growth is now fully beat-planned and audio never changes motion). `trebleHit`
is unused. Silence / depth-0 behavior: no audio sparkle (the `Sparkle` trigger
still works); growth is untouched by audio at any depth. Fully presentable
with zero audio.

## Beat planning

Replaces the old Energy/Sync/TempoDiv tempo mapping (2026-07-06). Two knobs:

- **`OnBeats`** ∈ {3/4, 1, 2, 4, 8} beats (custom `Beats` enum —
  `Tempo.Division`'s ordinal space interleaves triplet/dotted members, so no
  contiguous subrange expresses this set). Defines the **cap grid**: beats
  `epoch + m·OnBeats` on the engine's composite basis.
- **`Speed`**: growth speed in cells per beat, shared by all pipes.

Mechanics:

1. **Epoch**: set at pattern load, on activation, and at each drain
   conclusion, snapped to a whole engine beat (`rint(compositeBasis)`; a
   drain concludes on a beat by construction). Every cap (turn/elbow) lands
   on `epoch + m·OnBeats` for integer m. A backward clock jump (tap tempo /
   transport reset) rebases the epoch and all scheduled caps by the jump, so
   alignment survives relative to the new clock.
2. **Effective speed**: the Speed knob, applied raw — the ≥5 s traversal
   clamp was removed 2026-07-06 (second pass, explicit user override).
   Applied live: the Speed knob and BPM changes flow into every in-flight run
   immediately. Once runs complete within a single OnBeats interval (k = 1),
   pace saturates at roomSize/OnBeats — the top of the knob mostly guarantees
   single-interval runs; OnBeats and density then set the visible speed.
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
4. **Cap scheduling / trim** (the reconciliation of integer cells with the
   beat grid): the run covers `dist ≈ n` cells (the exact distance from the
   continuous head to the target corner). With `D0` = beats until the next
   grid point and `T(k) = D0 + (k−1)·OnBeats`, pick
   `k = max(1, round((dist/Speed − D0)/OnBeats) + 1)`, then `++k` if
   `dist/T(k) > Speed` — **a run never moves faster than the knob**. The
   trim is stored as a ratio (`run speed / Speed`), so per-run speeds are
   ≈Speed within a bounded factor and exactly equal in steady state; caps
   land exactly on the grid AND on lattice corners with zero snapping. (The
   alternative — bit-identical shared speed with fractional-cell runs — was
   rejected: permanent off-lattice drift lets near-parallel pipes visually
   merge at 3–5 px thickness.)
5. **Speed changes mid-run**: the head immediately moves at the new rate, so
   the beat-aligned cap fires wherever the head is — possibly off a lattice
   corner ("outside the grid"); a head that outruns the room clamps at the
   wall until its cap beat (caps are never early). The next plan re-anchors
   to the nearest corner: the along-axis residual (≤ 0.5 cell) is absorbed
   into the next run's trim; off-axis residuals heal when a later run
   travels that axis. Self-healing, no pops. The same path absorbs BPM
   changes.
6. **OnBeats changes mid-run**: the in-flight run finishes at its scheduled
   cap; the next plan snaps to the new grid — one transitional run with a
   fractional-interval duration, accepted.
7. **Speed × OnBeats interaction**: at OnBeats = 8 with high Speed in a small
   room, an interval "wants" more cells than the room holds, so runs are
   physically forced slower (the pipe crawls at maxRun/8 cells per beat).
   Large OnBeats values read as *slower*, sparser caps — by design.

## Speed (replaces Energy)

`Speed` (0.25–16 cells/beat, default 1.0) is the single motion control; the
pattern **departs from the series master-Energy convention** (no `energy`
parameter — growth rate is musical, in cells per beat, rather than an
abstract 0–1). **Unclamped** as of 2026-07-06 (second pass, explicit user
override): the old `gx·period/5000` traversal clamp is gone, so the whole
range is real at any BPM. Above the point where runs complete within one
OnBeats interval the pace saturates at roomSize/OnBeats (see Beat planning);
at the extreme (Speed 16, OnBeats 3/4, 120 BPM) a wall crossing takes well
under a second. `CURATE:` where the usable band sits live.

## Parameters

UI/registration order: triggers, Speed, pattern params, rotation, Audio,
OnBeats, Meta last (14 total — over the ~12 series guideline; nothing was
obviously droppable given the rotation feature).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `drain` | Drain | TriggerParameter | — | — | fade out concluding exactly on a beat (0.5–1.5 beats), clear, restart with the Pipes-knob count in the next palette color |
| `teleport` | Teleport | TriggerParameter | — | — | one random growing pipe caps and jumps to a random free cell (the classic) |
| `pipes` | Pipes | DiscreteParameter | 1 | 1..3 | concurrent pipe count; raising spawns a fresh pipe, lowering culls the oldest (capped where it stopped) |
| `sparkle` | Sparkle | TriggerParameter | — | — | flash the recent elbow joints white at full brightness (manual bass sparkle) |
| `rstRot` | RstRot | TriggerParameter | — | — | zero RotX/RotY and snap back to the orthogonal projection (jump-cut) |
| `speed` | Speed | CompoundParameter | 1.0 | 0.25..16 | growth speed in cells per beat, shared by all pipes; unclamped |
| `thickness` | Thick | CompoundParameter | 3.5 | 1..6 | pipe thickness in px, whole model in realtime (unclamped; 6 px at high density merges cells) |
| `density` | Density | DiscreteParameter | 10 | 6..12 | room grid cells per axis; **takes effect at the next drain** |
| `hue` | Hue | CompoundParameter | 0 | 0..360 | hue offset (degrees) added to the palette-derived pipe color |
| `rotX` | RotX | CompoundParameter | 0 | 0..1 | lattice rotation speed about the horizontal x axis; 100% = 90°/beat |
| `rotY` | RotY | CompoundParameter | 0 | 0..1 | lattice rotation speed about the vertical y axis; 100% = 90°/beat |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full bass-sparkle response |
| `onBeats` | OnBeats | EnumParameter&lt;Beats&gt; | 1 | 3/4, 1, 2, 4, 8 | beat-grid interval every cap lands on, counted from load or drain end |
| `meta` | Meta | TriggerParameter | — | — | randomly fire one trigger or jump one parameter (`TriggerBag`) |

Removed 2026-07-06: `energy`, `sync`, `tempoDiv` — superseded by the
beat-planned `speed`/`onBeats` pair (same precedent as the earlier `growthDiv`
removal) — and, in the second pass, `newPipe` (superseded by the `pipes`
knob). Old `.lxp` files referencing them restore every other parameter and
log an unknown-parameter warning for each.

Pipe color: hue of the palette swatch color at index `drainCount % swatch.size()`
(so each drain advances to the next palette color), plus the `hue` offset, plus
40° per concurrent pipe index so simultaneous pipes read as distinct.
Hue changes affect newly started runs only — retained segments capture their
color at creation. Thickness is NOT captured: the Thick knob rethickens the
entire model in realtime (2026-07-06 second pass).

## Triggers

Four non-meta triggers, small → large (`newPipe` became the `pipes` knob in
the 2026-07-06 second pass: raising it births a fresh pipe, lowering culls
the oldest, capped with a ball wherever its head stopped — its geometry
stays):

- `sparkle` — **small**: the ≤16 most recent elbow joints flash white and decay
  over 500 ms; a stationary glitter accent, no state changes.
- `rstRot` — **small/medium**: zeros the rotation speeds and jump-cuts the
  projection back to orthogonal; a no-op when already static.
- `teleport` — **medium**: instant; a cap ball marks the disconnect point and
  the pipe continues from a random free cell. Reads immediately (the classic
  NT gag).
- `drain` — **large**: a brightness fade (not motion) whose duration is
  measured at trigger time to conclude **exactly on a beat**, between 0.5 and
  1.5 beats out (if the next beat is closer than half a beat, it targets the
  one after; a mid-drain BPM change drifts the conclusion — accepted). Then
  the room clears, the pending density applies, the palette color advances,
  the cap-grid **epoch resets to that beat**, and the **Pipes-knob count** of
  pipes respawns (the knob is the single source of truth and never moves on
  its own — supersedes the round-1 "one pipe" behavior). Also fires
  automatically at >60% fill, if a teleport/spawn finds no free cell, or on
  retained-geometry overflow.

Boxed-in pipes no longer teleport — they intersect (see Beat planning §3).

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `thickness` | 1..6 (full) | candidate | whole model in realtime — a visible weight change on jump |
| `density` | 6..12 (full) | candidate | deferred to next drain — jump reads as a room-scale change after the clear |
| `pipes` | 1..3 (full) | candidate | CURATE: spawn/cull as an ambient event — do uncommanded culls read well? |
| `hue` | 0..360 (full) | candidate | new runs only; lattice becomes multicolored over time |
| `speed` | 0.5..2.0 | candidate | CURATE: musical mid-band; the knob itself now reaches 16, far past what a random jump should do |
| `onBeats` | 1..4 (ordinal subrange) | candidate | CURATE: excludes 3/4 (a random polyrhythm jump reads as a timing bug) and 8 (caps stall for many seconds) |
| `rotX` | 0..0.25 | candidate | CURATE: slow ambient drift only (≤22.5°/beat); RstRot is in the trigger pool as the way back |
| `rotY` | 0..0.25 | candidate | CURATE: as rotX |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

Removed from the pool 2026-07-06: `tempoDiv` (parameter removed); second
pass: the `newPipe` trigger (parameter removed — `pipes` joins as a jumpable
instead).

## Simulation-principles compliance

Fastest sustained *growth* motion is the extrusion tip crossing a wall
(`gx` cells):

- **The growth-speed floor is RETIRED** (2026-07-06 second pass, explicit
  user override): Speed is unclamped up to 16 cells/beat — at 120 BPM that is
  ~32 cells/s, a full wall crossing in well under a second at any density.
  The old ≥5 s guarantee (round-1 `speedEff` clamp; before that, `minSegMs`)
  no longer holds for growth; the OnBeats grid is now the pacing mechanism,
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
  per spec (the knob goes there deliberately); the meta pool only jumps to
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
and meta exist to short-circuit this live. `CURATE:` fill pacing at
performance tempos.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | First pass per approved plan; all CURATE: constants unverified on sculpture |
| 2026-07-05 | Adversarial review pass: `beginSegment` retime estimate now folds in the audio level boost — previously completions landed up to 23% early of the retimed boundary whenever the Audio knob was up; corrected the thickness-clamp figure | Verified improver's claims against HEAD |
| 2026-07-05 | Fixed sparkle overlay self-occlusion; enforced ≥5 s traversal cap at all densities via `minSegMs` floor; added `audio` depth knob (default 0) wired through `AudioReactive.setDepth`; migrated tempo handling to shared `TempoLock` with `sync`/`tempoDiv`; removed `growthDiv`; added `Sparkle` manual trigger; corrected false door-column claim | Review session (Fable): bug fixes + series-convention upgrade |
| 2026-07-05 | Integration pass: `TriggerBag.jumpable(DiscreteParameter, lo, hi)` subrange overload added; `tempoDiv` in the meta pool over SIXTEENTH..HALF; fixed a stale-gate nit (`crossed()` polled every frame) | Pipes3D agent's util request + series `crossed()` idiom |
| 2026-07-06 (2nd pass) | Live-curation feedback round. **Elbow distribution**: run length now uniform over 1..maxRun (was max-of-two long-biased, which compounded with the length-weighted direction pick and pushed all corners to the walls) — every free candidate cell is now an equally likely elbow target. **Disc joints**: square stamps → discs (`stampDisc`, +0.25 px rounding margin) for balls and end-on cross-sections — a sphere projects as a circle from any angle, so joints stay correct under rotation. **Thick**: range 1–6 px, cell-size clamp removed, and thickness reads the live knob at raster time (whole-model realtime; `segHalfPx`/`ballHalfPx` capture arrays deleted). **Pipes knob**: `newPipe` trigger → `pipes` DiscreteParameter (1–3) reconciled by `syncPipeCount()` via `onParameterChanged` — raising births a fresh pipe, lowering culls the oldest (birth-stamped in `spawnPipe`; teleports keep their stamp), capped where it stopped; drain now respawns the knob count (knob never moves on its own — supersedes round-1 "one pipe"). **Speed**: clamp removed (`speedEff()`/`TRAVERSAL_MIN_MS` deleted), range 0.25–16 cells/beat — the ≥5 s growth floor is explicitly retired; pace saturates at roomSize/OnBeats once runs fit one interval. Meta pool: −newPipe, +pipes(1..3) | User live-curation notes (2nd round) + decisions: drain respawns knob count; thickness unclamped |
| 2026-07-06 | Curation rework per user notes. **Drain**: duration now measured at trigger to conclude exactly on a beat (0.5–1.5 beats, via new `TempoLock.msUntilNext`); respawns exactly ONE pipe; resets the cap-grid epoch. **Audio**: bassHit → sparkle is the only mapping; level→growth boost, bass gate-release removed; trebleHit unused. **Beat planning**: `energy`/`sync`/`tempoDiv` removed, replaced by `speed` (cells/beat, traversal-clamped) + `onBeats` (3/4–8, custom `Beats` enum); runs are integer-cell with bounded per-run speed trim so caps land exactly on the OnBeats grid AND lattice corners; boxed-in pipes now intersect (weighted, no reversal) instead of teleporting; speed/BPM changes self-heal via nearest-corner re-anchoring. **Rotation**: `rotX`/`rotY` speed knobs (100% = 90°/beat, tempo-locked accumulation) + `rstRot` jump-cut reset; rendering rebuilt from incremental persistent buffers to retained segment/ball lists with full per-frame re-raster through a rotate-then-project transform (zero-angle output verified identical to the old table). **Shading**: axis-aligned stripe replaced by a world-fixed sun-at-infinity Blinn-Phong specular (upper-corner light; highlights slide around pipes as the lattice turns). Jump pool: −tempoDiv, +speed[0.5..2], +onBeats[1..4], +rotX/rotY[0..0.25], +rstRot (registered trigger). All new CURATE: flags — sun direction/exponent, drain minimum at fast tempos, run-length bias, rotation-rate ceiling, fill pacing | User curation notes 2026-07-06 (+ AskUserQuestion decisions: any-multiple caps, spatial-lattice recovery, replace-all params, cells/beat speed, bounded trim, sun-specular request) |
