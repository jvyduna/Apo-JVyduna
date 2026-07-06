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
  `M = V×A`, `V′ = V − (V·A)A`. Intensity =
  `min(1, peak⁴ · SPEC_GAIN · (2·Hglht + AUDIO_SPEC_BOOST·audioLevel))` —
  third pass: exponent lowered 8 → 4 (broader lobe so most orientations show
  a highlight, the fix for "I only see some highlights"), scaled by the
  Hglht knob (0.5 = nominal, 0 skips the stripe pass entirely) and boosted
  by loud music (`CURATE:` exponent/gains). The 1 px stripe (desaturating
  from the palette color's own saturation toward the 35 floor, boosted
  ×1.15, both scaled by intensity) draws in a second pass at the lobe's
  offset so the body sweep can't clobber it at equal depth.
  Because the sun does not rotate with the lattice, highlights slide around
  the pipes as the model turns. Balls keep their uniform glossy look
  (`CURATE:` follow-up — a per-ball offset sun dot).
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
  `SPEC_GAIN·(2·Hglht + AUDIO_SPEC_BOOST·level)` (AUDIO_SPEC_BOOST = 1.5) —
  louder music, hotter shine. `level` is depth-scaled and silence-safe, so
  Audio = 0 keeps highlights governed by the Hglht knob alone.

Removed 2026-07-06: the `level` → growth-rate boost and the `bassHit` →
grid-gate early release (growth is fully beat-planned; audio never changes
motion). `trebleHit` is unused. Fully presentable with zero audio.

## Beat planning

Replaces the old Energy/Sync/TempoDiv tempo mapping (2026-07-06). Two knobs:

- **`CapDiv`** (UI label; param key `onBeats`, renamed third pass) ∈
  {3/4, 1, 2, 4, 8} beats (custom `Beats` enum — `Tempo.Division`'s ordinal
  space interleaves triplet/dotted members, so no contiguous subrange
  expresses this set). Defines the **cap grid**: beats `epoch + m·CapDiv` on
  the engine's composite basis.
- **`Speed`**: growth speed in cells per beat, shared by all pipes.

Mechanics:

1. **Epoch**: set at pattern load, on activation, and at each drain
   conclusion, snapped to a whole engine beat (`rint(compositeBasis)`; a
   drain concludes on a beat by construction). Every cap (turn/elbow) lands
   on `epoch + m·CapDiv` for integer m. A backward clock jump (tap tempo /
   transport reset) rebases the epoch and all scheduled caps by the jump, so
   alignment survives relative to the new clock.
2. **Effective speed**: the Speed knob (third pass: a 16-step discrete
   geometric ladder, 0–64 cells/beat), applied raw — the ≥5 s traversal clamp
   was removed in the second pass (explicit user override). Applied live: the
   knob and BPM changes flow into every in-flight run immediately. Once runs
   complete within a single CapDiv interval (k = 1), pace saturates at
   roomSize/CapDiv — the top of the ladder mostly guarantees single-interval
   runs; CapDiv and density then set the visible speed.
   **Speed 0 pauses growth**: heads and cap checks freeze (rotation, sparkle
   and drain stay live); spawns/teleports while paused park with an infinite
   cap time. **Resume realigns** (`realignCaps`): every in-flight cap is
   re-scheduled on the CapDiv grid, landing 0.5–1.5 divisions after resume
   (same flavor as the drain rule), with the speed ratio re-trimmed from the
   remaining distance to the stored run target (`pGoal`).
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
   grid point and `T(k) = D0 + (k−1)·CapDiv`, pick
   `k = max(1, round((dist/Speed − D0)/CapDiv) + 1)`, then `++k` if
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
6. **CapDiv changes mid-run**: the in-flight run finishes at its scheduled
   cap; the next plan snaps to the new grid — one transitional run with a
   fractional-interval duration, accepted.
7. **Speed × CapDiv interaction**: at CapDiv = 8 with high Speed in a small
   room, an interval "wants" more cells than the room holds, so runs are
   physically forced slower (the pipe crawls at maxRun/8 cells per beat).
   Large CapDiv values read as *slower*, sparser caps — by design.

## Speed (replaces Energy)

`Speed` (third pass: EnumParameter over a discrete geometric ladder — 0,
0.25, 0.5, 1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64 cells/beat, default
1) is the single motion control; the pattern **departs from the series
master-Energy convention** (growth rate is musical, in cells per beat).
**Unclamped** since the second pass: the whole ladder is real at any BPM.
Above the point where runs complete within one CapDiv interval the pace
saturates at roomSize/CapDiv (see Beat planning); at the extreme a wall
crossing takes well under a second. **0 = pause**; resuming realigns every
cap to the CapDiv grid 0.5–1.5 divisions out. `CURATE:` where the usable
band sits live.

## Parameters

UI/registration order: triggers, Speed, pattern params, rotation (RstRot
directly after RotY), Audio, CapDiv, Meta last (14 total — over the ~12
series guideline; nothing was obviously droppable given the feature set).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `drain` | Drain | TriggerParameter | — | — | fade out concluding exactly on a beat (0.5–1.5 beats), clear, restart with the Pipes-knob count in the next palette color |
| `teleport` | Teleport | TriggerParameter | — | — | one random growing pipe caps and jumps to a random free cell (the classic) |
| `pipes` | Pipes | DiscreteParameter | 1 | 1..3 | concurrent pipe count; raising spawns a fresh pipe, lowering culls the oldest (capped where it stopped) |
| `sparkle` | Sparkle | TriggerParameter | — | — | flash ALL recent elbow joints white at full brightness (manual bass sparkle) |
| `speed` | Speed | EnumParameter&lt;Rate&gt; | 1 | 0, 0.25–64 (16-step geometric ladder) | growth speed in cells per beat, shared by all pipes; 0 pauses, resume realigns caps to the CapDiv grid |
| `thickness` | Thick | CompoundParameter | 3.5 | 1..6 | pipe thickness in px, whole model in realtime (unclamped; 6 px at high density merges cells) |
| `density` | Density | DiscreteParameter | 10 | 6..12 | room grid cells per axis; **takes effect at the next drain** |
| `hglht` | Hglht | CompoundParameter | 0.5 | 0..1 | amount of Phong sun-specular highlight (0.5 = nominal, 0 = none; audio level adds more on top) |
| `rotX` | RotX | CompoundParameter | 0 | 0..1 | lattice rotation speed about the horizontal x axis; 100% = 90°/beat |
| `rotY` | RotY | CompoundParameter | 0 | 0..1 | lattice rotation speed about the vertical y axis; 100% = 90°/beat |
| `rstRot` | RstRot | TriggerParameter | — | — | zero RotX/RotY and snap back to the orthogonal projection (jump-cut) |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full bass-sparkle + highlight response |
| `onBeats` | CapDiv | EnumParameter&lt;Beats&gt; | 1 | 3/4, 1, 2, 4, 8 | beat-grid division every cap lands on, counted from load or drain end (key `onBeats` kept for .lxp compat) |
| `meta` | Meta | TriggerParameter | — | — | randomly fire one trigger or jump one parameter (`TriggerBag`) |

Removed 2026-07-06: `energy`, `sync`, `tempoDiv` — superseded by the
beat-planned `speed`/`onBeats` pair (same precedent as the earlier `growthDiv`
removal) — and, in the second pass, `newPipe` (superseded by the `pipes`
knob). Third pass: `hue` removed (color is now purely palette-derived, series
house rules), and `speed` changed type (CompoundParameter →
EnumParameter&lt;Rate&gt;; an old `.lxp` speed value restores oddly or warns —
accepted). Old `.lxp` files referencing removed keys restore every other
parameter and log an unknown-parameter warning for each.

Pipe color (third pass — series house rules, no hue knob): full H/S/B
straight from the palette swatch. With ≥3 entries, pipe i takes slot
`(drainCount + i) % n` — each drain advances the rotation and concurrent
pipes get distinct entries. A shorter swatch keeps its defined colors and
fills the remainder with perceptually-even fully-saturated hues
(`PerceptualHue.fillCircle`, the Rubik `computeFaceColors` template); an
empty swatch degenerates to an even perceptual spread. The palette color's
own saturation and brightness are respected — pastel/dim swatch entries
render as such, with depth shading multiplied on top (`CURATE:` verify low-S/B
palettes read on the LEDs). Color is captured at run start, so retained
segments keep their color. Thickness is NOT captured: the Thick knob
rethickens the entire model in realtime (second pass).

## Triggers

Four non-meta triggers, small → large (`newPipe` became the `pipes` knob in
the 2026-07-06 second pass: raising it births a fresh pipe, lowering culls
the oldest, capped with a ball wherever its head stopped — its geometry
stays):

- `sparkle` — **small**: ALL of the ≤48 most recent elbow joints flash white
  and decay over 500 ms (bass hits flash a loudness-scaled subset); a
  stationary glitter accent, no state changes.
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
| `hglht` | 0..1 (full) | candidate | CURATE: highlight-amount drift as ambient variation |
| `speed` | 0.5..4 (ordinal subrange) | candidate | CURATE: musical mid-band; excludes PAUSE (meta must not silently freeze the pattern) and the blazing top of the ladder |
| `onBeats` | 1..4 (ordinal subrange) | candidate | CURATE: excludes 3/4 (a random polyrhythm jump reads as a timing bug) and 8 (caps stall for many seconds) |
| `rotX` | 0..0.25 | candidate | CURATE: slow ambient drift only (≤22.5°/beat); RstRot is in the trigger pool as the way back |
| `rotY` | 0..0.25 | candidate | CURATE: as rotX |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

Removed from the pool 2026-07-06: `tempoDiv` (parameter removed); second
pass: the `newPipe` trigger (parameter removed — `pipes` joins as a jumpable
instead); third pass: `hue` (parameter removed — `hglht` joins instead).

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
| 2026-07-06 (3rd pass) | Live-curation feedback round 3. **Params**: RstRot moved to sit right after RotX/RotY in the UI; `onBeats` relabeled **CapDiv** (key kept); `hue` REMOVED — color now follows the series palette house rules (full H/S/B from the swatch, `(drainCount + i)` slot rotation, `PerceptualHue.fillCircle` fill for short swatches per the Rubik template; retained geometry stores H/S/B, palette S/B respected under depth shading). **Hglht** knob added (0..1, default 0.5 = nominal): scales the Blinn-Phong gain, 0 skips the stripe pass; lobe exponent lowered 8 → 4 so most orientations show a highlight ("I only see some" fix). **Speed** → EnumParameter&lt;Rate&gt; over a 16-step geometric ladder 0–64 cells/beat: 0 PAUSES growth (heads + caps freeze; spawns park at infinite cap time); resume realigns every in-flight cap onto the CapDiv grid 0.5–1.5 divisions out via new `realignCaps` + stored `pGoal` run targets. **Audio**: bass hits flash a loudness-scaled number of recent caps (ring buffer 16 → 48, `1 + 47·min(1, 2·level)`); `audio.level` also boosts the specular gain (AUDIO_SPEC_BOOST = 1.5). Jump pool: −hue, +hglht, speed → ordinal 0.5..4 (PAUSE excluded) | User live-curation notes (3rd round); palette rules matched to Satori/Rubik/Terraform/Zot house idiom |
| 2026-07-06 (2nd pass) | Live-curation feedback round. **Elbow distribution**: run length now uniform over 1..maxRun (was max-of-two long-biased, which compounded with the length-weighted direction pick and pushed all corners to the walls) — every free candidate cell is now an equally likely elbow target. **Disc joints**: square stamps → discs (`stampDisc`, +0.25 px rounding margin) for balls and end-on cross-sections — a sphere projects as a circle from any angle, so joints stay correct under rotation. **Thick**: range 1–6 px, cell-size clamp removed, and thickness reads the live knob at raster time (whole-model realtime; `segHalfPx`/`ballHalfPx` capture arrays deleted). **Pipes knob**: `newPipe` trigger → `pipes` DiscreteParameter (1–3) reconciled by `syncPipeCount()` via `onParameterChanged` — raising births a fresh pipe, lowering culls the oldest (birth-stamped in `spawnPipe`; teleports keep their stamp), capped where it stopped; drain now respawns the knob count (knob never moves on its own — supersedes round-1 "one pipe"). **Speed**: clamp removed (`speedEff()`/`TRAVERSAL_MIN_MS` deleted), range 0.25–16 cells/beat — the ≥5 s growth floor is explicitly retired; pace saturates at roomSize/OnBeats once runs fit one interval. Meta pool: −newPipe, +pipes(1..3) | User live-curation notes (2nd round) + decisions: drain respawns knob count; thickness unclamped |
| 2026-07-06 | Curation rework per user notes. **Drain**: duration now measured at trigger to conclude exactly on a beat (0.5–1.5 beats, via new `TempoLock.msUntilNext`); respawns exactly ONE pipe; resets the cap-grid epoch. **Audio**: bassHit → sparkle is the only mapping; level→growth boost, bass gate-release removed; trebleHit unused. **Beat planning**: `energy`/`sync`/`tempoDiv` removed, replaced by `speed` (cells/beat, traversal-clamped) + `onBeats` (3/4–8, custom `Beats` enum); runs are integer-cell with bounded per-run speed trim so caps land exactly on the OnBeats grid AND lattice corners; boxed-in pipes now intersect (weighted, no reversal) instead of teleporting; speed/BPM changes self-heal via nearest-corner re-anchoring. **Rotation**: `rotX`/`rotY` speed knobs (100% = 90°/beat, tempo-locked accumulation) + `rstRot` jump-cut reset; rendering rebuilt from incremental persistent buffers to retained segment/ball lists with full per-frame re-raster through a rotate-then-project transform (zero-angle output verified identical to the old table). **Shading**: axis-aligned stripe replaced by a world-fixed sun-at-infinity Blinn-Phong specular (upper-corner light; highlights slide around pipes as the lattice turns). Jump pool: −tempoDiv, +speed[0.5..2], +onBeats[1..4], +rotX/rotY[0..0.25], +rstRot (registered trigger). All new CURATE: flags — sun direction/exponent, drain minimum at fast tempos, run-length bias, rotation-rate ceiling, fill pacing | User curation notes 2026-07-06 (+ AskUserQuestion decisions: any-multiple caps, spatial-lattice recovery, replace-all params, cells/beat speed, bounded trim, sun-specular request) |
