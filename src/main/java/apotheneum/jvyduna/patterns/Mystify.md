# Mystify

Mystify-screensaver polylines leaving beat-spaced XOR-moiré trails, bouncing
on random beat multiples, on the cube faces (shared or independent), the cube
ring, or the cylinder.

> Sidecar design doc convention: this file lives beside `Mystify.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

The Windows 95+ **"Mystify Your Mind"** screensaver (and its relatives — After
Dark's *Northern Lights* / *String Theory*, and the wraparound variants on cyclic
displays): 1–2 closed polylines whose vertices drift independently and bounce off
the screen edges, each edge leaving a trail of its past positions. The visual
signature preserved here: slow independent vertex drift, closed polygons that
continuously self-morph, **distinct spatially-separated leftover lines** with
**erased black crossings** where new lines cross old ones (the interference
moiré), and pure saturated color on black. The original stored N past polygons
and erased the oldest by redrawing in background color — cutting black paths
through the overlap; here the same look comes from *stamping* the shape into a
decay buffer at tempo intervals (TrailDiv) with a continuous-XOR blend
(TrailBlend), so crossings self-erase and everything fades smoothly to black.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the sim needs the wrap-around cube ring
  and cylinder orientations plus exterior→interior copy utilities, which the
  raster base (cube-face-only) cannot reach.
- **SurfaceCanvas simulation**: vertex positions/velocities live in normalized
  `[0,1]²` coordinates in preallocated `double[]` arrays: 4 face groups ×
  `MAX_SHAPES=2` × `MAX_VERTS=5` = 40 vertices, ALL simulated every frame
  (groups 1–3 exist for Faces4; the other modes draw group 0 only, so mode
  switches are seamless and allocation-free). The `shapes`/`vertices`
  parameters only select how many are *drawn* per group.
- **Trail / scratch split** (the moiré architecture): each surface has TWO
  canvases. The **trail canvas** is persistent and holds only *stamped*
  leftover lines, decaying by one `decay(f)` per frame (never cleared during
  normal play). The **scratch canvas** is rebuilt every frame:
  `scratch.copyFrom(trail)`, then the live shape is drawn on top, then scratch
  blits to the LEDs. The live shape itself never persists — it is stamped into
  the trail only on crossings of the **TrailDiv** tempo division
  (`TempoLock.crossed`, polled exactly once per frame — it consumes the
  crossing, so the Faces4 per-face loop reuses one boolean).
- **TrailBlend** governs how stamps composite against existing trail pixels
  (per-channel on [0,255]):
  - **Xor** (default): `a + c − 2ac/255` — bright over bright goes BLACK.
    Each stamped line erases the trail where it crosses older lines; the
    classic erased-path interference moiré develops in the decaying buffer.
  - **Max**: lighten (the pre-XOR behavior) — union of brightness.
  - **Diff**: `|a − c|` — softer inversion.
  - **Add**: clamped sum — bright buildup at crossings.
  The **live line is always MAX-blended** onto scratch — deliberate: a live
  line XOR-redrawn over its own fresh stamp would self-invert to black every
  stamp frame. **Half-open edges**: with any self-inverting blend (non-MAX),
  closed-polygon edges skip their final pixel so each shared vertex is
  plotted exactly once (the second write would XOR it back to black); the
  open-line (Vertices=2) single edge keeps its endpoint. Wu endpoint
  double-plots under XOR are accepted — CURATE: verify no visible vertex
  pinholes with Wu+Xor.
- **Line styles**: `lineBlend` (Bresenham) or `lineWu` (Xiaolin Wu
  antialiasing, `Wu` toggle); coverage scales the color BEFORE the blend op.
- **Geometry modes** (`Geometry` enum, carries a `wrapX` flag; canvases all
  preallocated in the constructor, only the active surface(s) touched per
  frame):
  - `FACES` — group 0 on faceTrail[0] (50×45); x **bounces**. Blitted onto the
    front exterior face (door-guarded), then `copyCubeFace(front)` replicates
    to all 4 exterior + 4 interior faces.
  - `FACES_4` — four independent sims (group f on faceTrail[f]), one per cube
    face; x **bounces**. Each scratch blits to its exterior face, then
    `copy(exterior.faces[f], interior.faces[f])` mirrors to the interior
    (null-guarded — `Apotheneum.cube.interior` can be absent).
  - `CUBE_RING` (default) — one 200×45 sim on `Apotheneum.cube.exterior` as a
    continuous strip; x **wraps**. `copyCubeExterior()` mirrors to interior.
  - `CYLINDER` — 120×43 on `Apotheneum.cylinder.exterior`, wrapping;
    `copyCylinderExterior()` mirrors to the interior.
- **Inactive component stays dark** (cylinder in the cube modes, cube in
  CYLINDER mode). Rationale: the two surfaces have different widths (200 vs 120
  columns) and the bounce/wrap topologies differ, so mirroring one sim onto the
  other would stretch it non-uniformly and break wrap continuity; and a single
  lit surface against darkness matches the pattern's lines-on-black aesthetic.
  CURATE: confirm a dark cube around the lit cylinder reads as intentional on
  site; if not, a mirrored blit is a small change.
- Normalized coordinates carry across geometry switches (shapes persist); on a
  mode change all six trail canvases are cleared so stale trails never
  reappear (scratches need no clear — fully rebuilt every frame).
- **Wrap drawing**: on wrapping topologies each edge takes the short way around
  the ring (endpoint shifted by ±width when |Δx| > width/2); `SurfaceCanvas`
  floorMods x. On the bounce (face) modes, flash balls and Wu fringes use the
  *clamped* setters so a face-edge pixel never smears onto the opposite edge.
- **Open-line mode** (`Vertices = 2`): a single open edge (v0-v1), not a
  closed 2-gon. Both endpoints still get flash balls.
- **Per-edge palette colors**: edge i of shape s cycles through ALL swatch
  colors (`swatch[(hueOffset + s*MAX_VERTS + i) % n]`) — with a 3-color
  palette and 5 vertices, edges use colors 0,1,2,0,1 (previously only 2 were
  ever used). Shared across the four Faces4 groups so faces stay
  color-coherent. Empty-swatch fallback: evenly-spaced perceptual hues.
- **Zero-alloc render**: canvases, position/velocity/plan arrays, and the
  10-entry edge-color array are constructed once; planning happens at event
  rate. Per-frame work is 40 vertex integrations, ≤10 live lines per active
  surface (+ stamps on TrailDiv crossings), decay pass(es), arraycopy(s).
- **Door columns**: `SurfaceCanvas.copyTo` and the face blit guard by
  `column.points.length`.

## Mirror

`Mirror` (default off) draws a same-color reflected copy of every drawn edge
and flash ball, computed in `mirrorPixelX`/`mirrorPixelXf`. The reflection
axis is geometry-dependent and was verified against the actual Apotheneum
fixture geometry (`generateApotheneumFixture.php`/`Apotheneum.lxf`):

- **FACES / FACES_4**: a local left-right flip across each face's own
  vertical centerline — `mirroredX = (width-1) - x`, width=50.
- **CUBE_RING**: mirrors across the physical world **X=Z diagonal** — the
  plane through the front-left and back-right corner posts, which reflects
  front↔left and right↔back. Verified per-face column sweep directions from
  the fixture generator (front +x, right +z, back −x, left −z) make this land
  on exactly the same formula: `mirroredX = (width-1) - x` (= `199 - x`) — a
  numeric coincidence, not the same reasoning (the two X=Z corner posts sit
  exactly at the ring's column-0 seam and column-~99.5 seam).
- **CYLINDER**: same X=Z diagonal, but the cylinder is angularly (not
  arc-length) parameterized (column 0 = front exactly, 30 = right, 60 = back,
  90 = left, from the fixture's `sin(instance*3)` expression):
  `mirroredX = floorMod(0.75·width - x, width)` (= `floorMod(90-x, 120)`).

Since mirroring lives inside `drawShapeLines`/`stampFlash`, both the stamped
trail draw and the live scratch draw get the mirrored copy automatically. The
short-way wrap adjustment is re-derived independently for the mirrored copy
(mirroring can flip which direction is short). Under Xor, mirrored copies
crossing originals erase at the crossings — intentional moiré.

## Audio mapping

All reactivity below is gated by the **Audio depth knob** (`audio`, default 0),
attached via `AudioReactive.setDepth(...)`. Audio does **not** affect speed.

- **Knob at 0 (default)**: every tap reads its silence value — this *is* the
  baseline look. No flash balls ever fire, trails stay at their full knob-set
  length. A complete ambient screensaver.
- **Raising the knob** continuously restores, scaled by depth:
  - `bassHit()` — fires once depth > 0.01; arms the flash envelope to
    `audio.depth()`. Each active vertex gets a **palette-colored ball** (its
    own edge color at `0.6 × envelope` brightness — no more white pops), a
    filled disc whose radius pulses with the envelope up to **3 px** at full
    depth (radius 1 = the original 5-pixel cross footprint, so low-depth
    continuity is preserved). Fades over `FLASH_DECAY_MS = 500` ms; hits can
    re-arm as fast as ~an eighth note. Stamped into the *trail* canvas
    (always max-blend — a flash should pop, not erase), so the pop leaves a
    decaying residue.
  - `trebleRatio` — subtly shortens trails: half-life divided by
    `1 + 0.15·clamp(trebleRatio − 1, 0, 2)` (up to ~1.3× faster decay on busy
    highs). Since the ratio scales with depth, this effect only emerges near
    full depth.

## Tempo mapping

Bounce timing is **hard-locked** to the transport beat grid; trail stamping
has its own independent division:

- **TrailDiv** (`EnumParameter<Tempo.Division>`, default QUARTER) — on each
  crossing the live shape is stamped into the trail canvas as a leftover
  line. Faster divisions → tighter line spacing; WHOLE → one leftover per
  bar, widely gapped.
- **BounceDiv** (`BounceGrid` enum: **3/4** / 1 / 2 / 4 / 8 quarter-note
  beats, default 1) — every wall bounce lands exactly on a beat satisfying
  `(beat − anchorBeat) % BounceDiv == 0` (fractional grids like 0.75 work —
  all beat math is double). `anchorBeat` is captured at pattern load and
  re-captured by **Scatter**.

**Random bounce planner** (replaces the natural-arrival targeting, which at
long BounceDiv locked vertices into periodic corner-to-corner paths):

- Each vertex axis that can bounce (y always; x in the face modes) plans its
  arrival as an **absolute beat index**: the next allowed grid beat (≥100 ms
  headroom) plus a **uniform random 0..kMax−1 additional grid steps**, where
  `kMax = clamp(round(8 / Speed), 1, 64)` — i.e. the next bounce is 1..kMax
  BounceDiv steps from now. Drawn independently per vertex per axis per
  bounce: x and y arrival times decorrelate, so which edge is hit and where
  on it varies every ricochet — no static periodic motion. At Speed 1 this is
  exactly "BounceDiv × rand(8) from now"; Speed scales the window (5 → 1..2,
  frenetic; 0.5 → 1..16, languid). Speed 0 = pause.
- **Dart guard**: a random draw could otherwise imply a full-canvas traversal
  in one short grid step; targets are bumped to later multiples until the
  implied speed is no faster than `TRAVERSE_SEC/5` = 2.4 s per span (the old
  Speed knob's own ceiling). CURATE: verify 2.4 s worst-case darts read OK on
  the full sculpture.
- Every frame, velocity is **derived**: `v = remainingDistance /
  msUntilBeat(target)` — arrival is exact by construction and live BPM
  changes bend the motion continuously. A target stranded >250 ms in the past
  with real distance remaining (transport restart, BPM jump, inactive gap)
  replans instead of teleporting.
- On arrival (±30 ms) the axis clamps to the wall, flips direction, and
  immediately replans — re-rolling the random window (fresh ricochet).
- **Speed changes slew**: a material rate change (>2%) replans every axis and
  blends the effective arrival time from old to new plan with a 250 ms
  smoothstep; a retarget mid-slew freezes the currently-blended value so
  chained knob moves stay continuous. Speed < ~0 = full pause; resume replans
  fresh.
- **Wrap-topology x** (CUBE_RING/CYLINDER) free-runs at
  `velX · Speed / TRAVERSE_SEC(12 s)` — a wrap has no visible event to align.
  On wrap topologies only y-bounces are beat-locked; the face modes lock both
  axes. At Speed 0 the rate is 0, so pause is total in all modes.
- CURATE: `SLEW_MS = 250`, `ARRIVAL_EPS_MS = 30`, `MIN_HEADROOM_MS = 100`,
  `RATE_CHANGE_FRAC = 0.02`, `STALE_TARGET_MS = 250`, `RAND_WINDOW_MAX = 64` —
  chosen by reasoning, none visually verified.
- CURATE: `BounceDiv = 1` default with the random window — verify the on-beat
  wall hits still read as tempo-locked when arrivals are spread over 1..8
  beats.

## Parameters

UI/registration order: triggers, pattern parameters, Audio, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `scatter` | Scatter | TriggerParameter | — | — | re-randomize every vertex position + velocity; re-anchor the bounce grid |
| `reverse` | Reverse | TriggerParameter | — | — | negate all velocities; shapes retrace their paths |
| `hueJump` | HueJump | TriggerParameter | — | — | rotate the palette color assignment of the edges |
| `geometry` | Geometry | EnumParameter&lt;Geometry&gt; | CUBE_RING | FACES / FACES_4 / CUBE_RING / CYLINDER | sim topology / target surface |
| `shapes` | Shapes | DiscreteParameter | 2 | 1..2 | number of polylines (per face group) |
| `vertices` | Vertices | DiscreteParameter | 4 | 2..5 | vertices per polyline (2 = a single open line) |
| `speed` | Speed | CompoundParameter | 1 | 0..5 (exp 2) | bounce cadence: random window is 1..(8/Speed) BounceDiv steps; 0 = pause |
| `trails` | Trails | CompoundParameter | 0.5 | 0..1 | leftover-line decay (exp half-life 50..5000 ms) |
| `trailDiv` | TrailDiv | EnumParameter&lt;Tempo.Division&gt; | QUARTER | all divisions | division on whose crossings a leftover line is stamped |
| `trailBlend` | TrailBlend | EnumParameter&lt;SurfaceCanvas.Blend&gt; | Xor | Max / Xor / Diff / Add | how stamps composite against existing trail pixels |
| `wu` | Wu | BooleanParameter | false | — | Xiaolin Wu antialiased lines instead of Bresenham |
| `mirror` | Mirror | BooleanParameter | false | — | same-color mirrored copy of every line/ball across the geometry's reflection axis |
| `bounceDiv` | BounceDiv | EnumParameter&lt;BounceGrid&gt; | 1 | 3/4 / 1 / 2 / 4 / 8 | beat grid (quarter notes) every wall bounce lands on |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full reactivity |
| `meta` | Meta | TriggerParameter | — | — | randomly fire one trigger or jump one parameter |

The former **Energy** knob is removed; its 0 (default) behavior is baked in:
nominal traversal `TRAVERSE_SEC = 12 s`, flash brightness factor 0.6.

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

## Triggers

All triggers fire **immediately** — never tempo-quantized (the bounce grid
supplies the musical alignment; triggers are performance gestures).

- `scatter` — every vertex (all four groups) jumps to a fresh random position
  with a fresh random velocity, and the bounce grid re-anchors to the current
  beat. Old leftover lines fade at the trail rate while the new shapes draw
  in. (Largest.)
- `reverse` — all velocities negate and every axis replans (its old target is
  now behind it); the shapes visibly fold back along their own fading
  leftovers. Grid anchor untouched. (Mid-size.)
- `hueJump` — the edge color assignment rotates by one swatch slot (hueOffset
  is a growing int; the swatch-size modulus applies at lookup, so it works
  with any palette size). (Smallest.)

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `trails` | [0.25, 0.85] | candidate | CURATE: full range excluded — 0 looks bare, 1 can smear; verify subrange with the new 5000 ms max |
| `vertices` | 2..5 (full) | candidate | count changes are seamless (all always simulated); 2 is the open-line mode |
| `geometry` | all 4 values | candidate | hard visual cut (canvases cleared); most dramatic jump; now includes Faces4 |
| `speed` | [0.4, 1.5] | candidate | CURATE: re-ranged when the knob grew to 0..5 — full-range jumps to 5× would jar; below 0.4 may read as stalled |
| `shapes` | 1..2 (full) | candidate | second-polyline on/off, exposed as a DiscreteParameter per series convention (TriggerBag has no boolean jump) |

`trailBlend` is deliberately NOT jumpable — a blend flip mid-set is jarring
(CURATE: revisit if Xor↔Max reads as a good hit).

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

**Speed model.** Planned-axis velocity is fully emergent:
`v = remainingDistance / msUntilBeat(target)`, with targets drawn randomly
from the BounceDiv grid (window 1..8/Speed steps). There is no fixed
traversal cap anymore — **flagged deviation** (user requirement, 2026-07-06):
the old ≥5 s floor is replaced by the **dart guard**, which bumps any target
that would imply traversal faster than `TRAVERSE_SEC/5 = 2.4 s` per span.
Slow side: a random long draw (up to 64 grid steps at low Speed) can make a
vertex crawl — CURATE: confirm a wall-hugging vertex crawling toward a
distant beat never reads as stuck.

Wrap-x drift runs at `Speed / 12 s` per circumference — at Speed 5 that is
2.4 s per 200/120-column lap, matching the dart cap. In the face modes the
canvas width is one 50-column wall; in the wrap modes it is the entire
circumference, so wrap modes are slower in ft/sec at equal settings.

**Event motion.** The flash ball is a stationary brightness pop (no
traversal); it fades over 0.5 s and its trail residue continues fading at the
trail rate. No fast-moving event exists in this pattern.

**Contrast / brightness.** Pure palette (or perceptual-hue fallback) color on
true black; 1-px lines are acceptable here per the series exception. Leftover
lines are strictly darker copies (multiplicative decay). Under the default
Xor blend, crossings between stamped lines go black — high-contrast
interference structure rather than brightness buildup; Max remains available
when a purely additive look is wanted. Wu antialiasing spreads a line across
two pixel rows with coverage-scaled brightness; CURATE: at LED resolution
verify Wu+Xor reads as smooth interference rather than noise, and pick the
better default (`wu` currently ships false = crisp Bresenham).

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |
| 2026-07-05 | Review pass: added `audio` depth knob (default 0, gates all reactivity; flash armed to depth); added `sync`/`tempoDiv` retiming via `TempoLock.retime`; fixed FACES-mode flash cross wrapping around face edges | Series audio/tempo conventions + bug hunt |
| 2026-07-06 | Curation rework: trail/scratch canvas split with TimeGap-stamped leftover lines; Trails = pure decay constant; removed the audio speed-breath; Speed re-ranged 0..5 (0 = pause); replaced Sync/TempoDiv retime nudging with the BounceOnBeats hard grid planner; added Wu antialiasing toggle; max (lighten) line compositing | Recreate the original's distinct-gap moiré look; tempo-exact bounces |
| 2026-07-06 | Added `Vertices = 2` open-line mode; added `Mirror` toggle (axis verified against the fixture geometry: local centerline on faces, X=Z world diagonal on CubeRing/Cylinder) | Round out the shape vocabulary; kaleidoscope-style symmetry |
| 2026-07-06 | Batch 3: split TimeGap into `TrailDiv` + `BounceDiv` (renamed from BounceOnBeats, added 3/4); **random bounce planner** (next arrival = 1..8/Speed random BounceDiv steps, per vertex per axis — kills periodic corner-to-corner locking; dart guard at 2.4 s/span); `TrailBlend` (default **Xor** — stamps erase trail crossings, the true interference moiré; live line stays Max; half-open closed-polygon edges prevent vertex self-erase); Trails max half-life 2500→5000 ms; new `Faces4` geometry (4 independent sims, state ×4 = 40 vertices); per-edge palette cycling (3-color palette + 5 vertices now uses all 3, was 2); removed `Energy` (baked at its 0 value: 12 s nominal, 0.6 flash); flash pops are now palette-colored pulsing balls (radius ≤3, was a white 1-px cross) | Live-viewing feedback: periodic paths, weak moiré, unused palette colors, dead Energy knob, clashing white flashes |

CURATE (unverified constants, gathered for the walk-through):
- `TRAIL_HALF_LIFE_MIN_MS/MAX_MS = 50/5000`, exp-mapped from Trails knob —
  default (0.5 → ~500 ms half-life) chosen by intuition.
- `TrailBlend = Xor` default — the moiré goal, but confirm it doesn't read as
  broken/flickery on real LEDs; Max is one click away.
- `TrailDiv = QUARTER` default — line spacing vs. clutter at typical BPM.
- `BounceDiv = 1` default and the random-window legibility — see Tempo notes.
- Dart guard ceiling `TRAVERSE_SEC/5 = 2.4 s/span` — fastest allowed dart.
- `wu = false` default — pick Bresenham vs. Wu on the real LEDs; check
  Wu+Xor vertex pinholes (endpoint double-plot accepted in code).
- Planner constants `SLEW_MS/ARRIVAL_EPS_MS/MIN_HEADROOM_MS/RATE_CHANGE_FRAC/
  STALE_TARGET_MS/RAND_WINDOW_MAX` — see Tempo mapping CURATE note.
- Speed jump range upper bound 1.5 — see Jump candidates.
- `TREBLE_TRAIL_SHORTEN = 0.15` (≤ ~30% faster decay) — "subtle" is untested.
- `FLASH_DECAY_MS = 500`, `FLASH_LEVEL = 0.6`, `FLASH_MAX_RADIUS = 3` — the
  palette ball must read at 40 ft without whiting out the line.
- `VEL_MIN = 0.45` — wrap-x drift spread between slowest and fastest vertex.
- Default `geometry = CUBE_RING`; Faces4 color-coherence across faces (all
  four groups share one edge-color set) — verify it reads as intentional.
- Per-edge color cycling offset (`s*MAX_VERTS`) — shape 2 starts 5 slots into
  the palette; verify the two shapes read as related-but-distinct.
