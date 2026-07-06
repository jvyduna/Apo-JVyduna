# Mystify

Mystify-screensaver polylines leaving beat-spaced fading leftover lines,
bouncing on the tempo grid on the cube faces or wrapping around the cube ring
or cylinder.

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
continuously self-morph, **distinct spatially-separated leftover lines** (the
gapped moiré), and pure saturated color on black. The original stored N past
polygons and erased the oldest; here the same look comes from *stamping* the
shape into a decay buffer at tempo intervals (TimeGap) — leftover lines are
discrete like the original's, but fade smoothly to black instead of vanishing.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the sim needs the wrap-around cube ring
  and cylinder orientations plus exterior→interior copy utilities, which the
  raster base (cube-face-only) cannot reach.
- **SurfaceCanvas simulation**: vertex positions/velocities live in normalized
  `[0,1]²` coordinates in preallocated `double[]` arrays (`MAX_SHAPES=2` ×
  `MAX_VERTS=5` = 10 vertices, all simulated every frame; the `shapes`/`vertices`
  parameters only select how many are *drawn*, so count changes are seamless and
  allocation-free).
- **Trail / scratch split** (the gapped-moiré architecture): each geometry has
  TWO canvases. The **trail canvas** is persistent and holds only *stamped*
  leftover lines, decaying by one `decay(f)` per frame (never cleared during
  normal play). The **scratch canvas** is rebuilt every frame:
  `scratch.copyFrom(trail)`, then the live shape is drawn on top, then scratch
  blits to the LEDs. The live shape itself never persists — it is stamped into
  the trail only on crossings of the **TimeGap** tempo division
  (`TempoLock.crossed`, polled unconditionally every frame). Successive
  leftovers are therefore spatially separated by however far the shape moved in
  one division — the classic moiré gap, tempo-linked.
- **Line compositing is per-channel max (lighten)** — `lineMax` (Bresenham) or
  `lineWu` (Xiaolin Wu antialiasing, `Wu` toggle) — so a dim line or AA fringe
  never punches a dark notch through a brighter fading line beneath it.
  Crossings become the union of brightness, which is what keeps the layered
  moiré structure legible at LED resolution. The two styles share compositing,
  so toggling Wu changes only edge smoothness.
- **Geometry modes** (`Geometry` enum, all six canvases preallocated in the
  constructor; only the active pair is touched per frame):
  - `FACES` — 50×45 canvas; x **bounces** off left/right edges (the classic
    monitor-shaped Mystify). Blitted onto the front exterior face
    (door-guarded via `column.points.length`), then `copyCubeFace(front)`
    replicates it to all 4 exterior + all 4 interior faces.
  - `CUBE_RING` (default) — 200×45 canvas mapped onto `Apotheneum.cube.exterior`
    as one continuous strip; x **wraps** (the "Wraparound" variant is native to
    this closed topology). `copyCubeExterior()` mirrors to the interior.
  - `CYLINDER` — 120×43 canvas on `Apotheneum.cylinder.exterior`, wrapping;
    `copyCylinderExterior()` mirrors to the interior.
- **Inactive component stays dark** (cylinder in the cube modes, cube in
  CYLINDER mode). Rationale: the two surfaces have different widths (200 vs 120
  columns) and the bounce/wrap topologies differ, so mirroring one sim onto the
  other would stretch it non-uniformly and break wrap continuity; and a single
  lit surface against darkness matches the pattern's lines-on-black aesthetic.
  CURATE: confirm a dark cube around the lit cylinder reads as intentional on
  site; if not, a mirrored blit is a small change.
- Normalized coordinates carry across geometry switches (shapes persist); on a
  mode change all three trail canvases are cleared so stale trails from the
  previous topology never reappear (scratch needs no clear — fully rebuilt
  every frame).
- **Wrap drawing**: on wrapping topologies each edge takes the short way around
  the ring (endpoint shifted by ±width when |Δx| > width/2); `SurfaceCanvas`
  floorMods x, so off-canvas endpoints land correctly. In FACES (bounce) mode
  the flash cross and Wu fringes use the *clamped* setters
  (`setMaxClamped` / `lineWu(..., wrapX=false)`) — the wrapping setters would
  otherwise smear a face-edge pixel onto the opposite edge.
- **Zero-alloc render**: canvases, position/velocity/plan arrays, and the
  4-entry color array are constructed once; planning happens at event rate
  (bounces, knob moves, triggers). Per-frame work is ~10 vertex integrations,
  ≤10 live lines (+≤10 stamped lines on TimeGap crossings), one decay pass,
  one arraycopy.
- **Door columns**: `SurfaceCanvas.copyTo` guards by `column.points.length`; the
  FACES blit does the same.

## Audio mapping

All reactivity below is gated by the **Audio depth knob** (`audio`, default 0),
attached via `AudioReactive.setDepth(...)`. Audio does **not** affect speed —
the level speed-breath was removed in the 2026-07-06 curation pass (motion
timing belongs to the tempo grid, not the audio envelope).

- **Knob at 0 (default)**: every tap reads its silence value — this *is* the
  baseline look. No bass flashes ever fire, trails stay at their full knob-set
  length. A complete ambient screensaver.
- **Raising the knob** continuously restores, scaled by depth:
  - `bassHit()` — fires once depth > 0.01; arms the flash envelope to
    `audio.depth()` (not fixed 1), so the white vertex-cross pop brightness
    scales with the knob. The cross (vertex pixel + 4 neighbors, max-blended,
    clamped at face edges) fades linearly over `FLASH_DECAY_MS = 500` ms; hits
    can be gated as close as ~an eighth note, which simply re-arms the
    envelope. Stamped into the *trail* canvas, so the pop also leaves a
    decaying residue.
  - `trebleRatio` — subtly shortens trails: half-life divided by
    `1 + 0.15·clamp(trebleRatio − 1, 0, 2)` (up to ~1.3× faster decay on busy
    highs). Since the ratio scales with depth, this effect only emerges near
    full depth.

## Tempo mapping

Bounce timing is **hard-locked** to the transport beat grid — there is no Sync
toggle; this *is* the pattern's motion model. Two independent tempo hooks:

- **TimeGap** (`EnumParameter<Tempo.Division>`, default QUARTER) — on each
  crossing of the division the live shape is stamped into the trail canvas as
  a leftover line (see Rendering approach). Faster divisions → tighter line
  spacing; WHOLE → one leftover per bar, widely gapped.
- **BounceOnBeats** (`BounceGrid` enum: 1 / 2 / 4 / 8 quarter-note beats,
  default 1) — every wall bounce lands exactly on a beat satisfying
  `(beat − anchorBeat) % BounceOnBeats == 0`, where `anchorBeat` is the whole
  transport beat captured at pattern load and re-captured by **Scatter**
  (phase snaps to the real beat grid; load/scatter only chooses which
  multiples count).

**Planner mechanism** (replaces the old `TempoLock.retime` sync-scale nudging):

- Each vertex axis that can bounce (y always; x in FACES mode only) carries a
  planned arrival as an **absolute beat index** (`targetBeatX/Y`, NaN =
  unplanned). Planning (`planAxis`) computes the *natural* arrival time at the
  target rate (`|vel| · rateSpansPerSec`) and picks the allowed grid beat
  nearest it, with ≥100 ms headroom (an imminent beat is skipped to the next
  multiple — this also absorbs quick corner double-bounce geometry).
- Every frame, velocity is **derived**: `v = remainingDistance /
  msUntilBeat(target)`. Arrival is exact by construction, and live BPM changes
  bend the motion continuously with zero extra machinery. A target stranded
  >250 ms in the past with real distance remaining (transport restart, huge
  BPM jump, inactive gap) replans instead of teleporting.
- On arrival (±30 ms) the axis clamps to the wall, flips direction, and
  immediately replans its next arrival. X and Y advance independently, so a
  same-frame corner double-bounce needs no special casing.
- **Speed is a target, not a velocity** (user decision, 2026-07-06): the knob
  (0–5×) steers which grid multiple the planner picks; actual speed is
  quantized by the grid. At high Speed every bounce comes at the minimum
  allowed spacing; at high Speed with BounceOnBeats=8 the planner deliberately
  slows travel to hold the grid — that is spec, not a bug.
- **Speed changes slew**: a material rate change (>2%, Speed *or* Energy)
  replans every axis and blends the effective arrival time from old to new
  plan with a 250 ms smoothstep (`slewOldBeat*` holds the blend source; a
  retarget mid-slew freezes the currently-blended value so chained knob moves
  stay continuous). Speed < ~0 = full pause: plans are abandoned (NaN) and
  resume replans fresh from the frozen positions.
- **Wrap-topology x** (CUBE_RING/CYLINDER) free-runs at
  `velX · rateSpansPerSec` — a wrap has no visible event to align, and lap
  times span dozens of beats. Implication: on wrap topologies only y-bounces
  are beat-locked; FACES locks both axes. At Speed 0 the rate is 0, so pause
  is total in all modes.
- CURATE: `SLEW_MS = 250`, `ARRIVAL_EPS_MS = 30`, `MIN_HEADROOM_MS = 100`,
  `RATE_CHANGE_FRAC = 0.02`, `STALE_TARGET_MS = 250` — all chosen by
  reasoning, none visually verified.
- CURATE: with BounceOnBeats=1 and ~10 vertices × 2 axes, wall hits should
  read as a steady on-beat trickle; verify legibility, else try default 2.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Base traversal rate (fastest vertex, Speed=1) | 12 s / canvas width | 5 s / canvas width | lin |
| Bass-flash brightness | 60% | 100% | lin |

Speed multiplies the energy-set base rate 0–5×, so the ≥5 s traversal floor
holds only at Speed ≤ 1 (see compliance below). Energy deliberately does
**not** touch trail length — that stays a hands-on look control.

## Parameters

UI/registration order: triggers, Energy, pattern parameters, Audio, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `scatter` | Scatter | TriggerParameter | — | — | re-randomize every vertex position + velocity; re-anchor the bounce grid |
| `reverse` | Reverse | TriggerParameter | — | — | negate all velocities; shapes retrace their paths |
| `hueJump` | HueJump | TriggerParameter | — | — | rotate palette color assignment of the polylines |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (base traversal rate, flash intensity) |
| `geometry` | Geometry | EnumParameter&lt;Geometry&gt; | CUBE_RING | FACES / CUBE_RING / CYLINDER | sim topology / target surface |
| `shapes` | Shapes | DiscreteParameter | 2 | 1..2 | number of polylines |
| `vertices` | Vertices | DiscreteParameter | 4 | 3..5 | vertices per polyline |
| `speed` | Speed | CompoundParameter | 1 | 0..5 (exp 2) | target speed multiple of the energy rate; 0 = pause; planner quantizes arrivals to the bounce grid |
| `trails` | Trails | CompoundParameter | 0.5 | 0..1 | leftover-line decay (exp half-life 50..2500 ms) |
| `timeGap` | TimeGap | EnumParameter&lt;Tempo.Division&gt; | QUARTER | all divisions | division on whose crossings a leftover line is stamped |
| `wu` | Wu | BooleanParameter | false | — | Xiaolin Wu antialiased lines instead of Bresenham |
| `bounceOnBeats` | BounceOnBeats | EnumParameter&lt;BounceGrid&gt; | 1 | 1 / 2 / 4 / 8 | beat grid (quarter notes) every wall bounce lands on |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full reactivity |
| `meta` | Meta | TriggerParameter | — | — | randomly fire one trigger or jump one parameter |

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

## Triggers

All triggers fire **immediately** — never tempo-quantized (the bounce grid
supplies the musical alignment; triggers are performance gestures).

- `scatter` — every vertex jumps to a fresh random position with a fresh random
  velocity, and the bounce grid re-anchors to the current beat. The old shapes'
  leftover lines fade out at the trail rate while the new shapes draw in, so
  the cut reads within a second or two without a hard blackout. (Largest.)
- `reverse` — all velocities negate and every axis replans (its old target is
  now behind it); the shapes visibly fold back along their own fading
  leftovers. Grid anchor untouched. (Mid-size.)
- `hueJump` — the 4-slot palette color assignment rotates by one; edges change
  hue instantly, old-hue leftovers fade underneath (reads within one trail
  life). (Smallest.)

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `trails` | [0.25, 0.85] | candidate | CURATE: full range excluded — 0 looks bare, 1 can smear; verify subrange |
| `vertices` | 3..5 (full) | candidate | count changes are seamless (all 5 always simulated) |
| `geometry` | all 3 values | candidate | hard visual cut (canvases cleared); most dramatic jump |
| `speed` | [0.4, 1.5] | candidate | CURATE: re-ranged from [0.4, 1.0] when the knob grew to 0..5 — full-range jumps to 5× would jar; below 0.4 may read as stalled |
| `shapes` | 1..2 (full) | candidate | second-polyline on/off, exposed as a DiscreteParameter per series convention (TriggerBag has no boolean jump) |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

**Speed model.** Vertex velocity components are stored as canvas-spans/sec at
rate = 1 with magnitude in `[0.45, 1]`. Per frame:

```
capSpansPerSec  = 1 / lin(energy, 12 s, 5 s)
rateSpansPerSec = cap · Speed              (Speed ∈ [0, 5])
```

**Flagged deviation (user requirement, 2026-07-06 curation pass):** the ≥5 s
full-traversal floor holds only at **Speed ≤ 1** (the default). At Speed 5,
Energy 1 the *target* rate is 1 s/span. Two things soften the deviation in
practice: (a) on planned axes the grid quantizes arrivals — a bounce can never
come sooner than the next allowed beat with ≥100 ms headroom, so at high Speed
actual travel is bounded by the BounceOnBeats spacing per remaining distance;
(b) Speed above 1 is a deliberate performance move, not the resting look, and
the Meta jump range caps at 1.5. CURATE: verify Speed > ~2 on the full
sculpture doesn't strobe; consider capping the knob at 3 if it does.

**Grid interaction.** Planned-axis velocity is derived (`remainingDistance /
msUntilBeat(target)`), so it varies smoothly between bounces and is exact at
arrival; there is no clamp math to verify anymore. The slowest natural
component is `0.45 ·rate`; grid quantization can slow an axis well below that
(e.g. a vertex near a wall waiting for the next allowed beat) — CURATE:
confirm a wall-hugging vertex crawling to its beat never reads as stuck.

Fastest possible full-canvas-width traversal at default settings
(energy 0.35, Speed 1): `1 / lin(0.35, 12, 5) → 9.55 s` per span. In FACES
mode the canvas width is one 50-column wall (the full surface a viewer sees
from any side); in the wrap modes it is the entire 200/120-column
circumference, so wrap modes are strictly slower in ft/sec at equal settings.

**Event motion.** The bass flash is a stationary brightness pop (no traversal);
it fades over 0.5 s and its trail residue continues fading at the trail rate,
so total visual life exceeds the flash envelope. No fast-moving event exists in
this pattern.

**Contrast / brightness.** Pure palette (or saturated fallback) hue on true
black; 1-px lines are acceptable here per the series exception — they move
slowly at default settings and are maximum-contrast on black. Leftover lines
are strictly darker copies (multiplicative decay), preserving the bold
figure/ground split. Max-blend keeps crossings at the brighter line's level
(union, never sum — no white-out, no dark notches). Wu antialiasing spreads a
line across two pixel rows with coverage-scaled brightness; CURATE: at LED
resolution verify Wu reads as smooth rather than dim/fuzzy, and pick the
better default (`wu` currently ships false = crisp Bresenham).

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |
| 2026-07-05 | Review pass: added `audio` depth knob (default 0, gates all reactivity; flash now armed to depth); added `sync`/`tempoDiv` — per-vertex-axis retiming lands wall bounces on the tempo grid via `TempoLock.retime` with cap-safe clamp `min(1.4, 1/|vel|)`; fixed FACES-mode flash cross wrapping around face edges; corrected flash-decay code comment (envelope re-arms on fast hits, doesn't clear first) | Series audio/tempo conventions + bug hunt |
| 2026-07-06 | Curation rework: trail/scratch canvas split with TimeGap-stamped leftover lines (restores the gapped moiré — the old draw-every-frame smear belongs to a blur effect, not this pattern); Trails is now purely the leftover decay constant; removed the audio speed-breath; Speed re-ranged 0..5 (0 = pause) and redefined as a planner target; replaced Sync/TempoDiv retime nudging with the BounceOnBeats hard grid planner (absolute-beat targets, derived velocity, 250 ms slew on knob moves, Scatter re-anchors); added Wu antialiasing toggle; all line/flash drawing switched to per-channel max (lighten) compositing | Recreate the original's distinct-gap moiré look; tempo-exact bounces |

CURATE (unverified constants, gathered for the walk-through):
- `TRAIL_HALF_LIFE_MIN_MS/MAX_MS = 50/2500`, exp-mapped from Trails knob —
  default (0.5 → ~354 ms half-life) chosen by intuition; with stamped
  leftovers, longer values may now be the sweet spot (more lines visible).
- `TimeGap = QUARTER` default — line spacing vs. clutter at typical BPM.
- `BounceOnBeats = 1` default — see Tempo mapping CURATE note.
- `wu = false` default — pick Bresenham vs. Wu on the real LEDs.
- Planner constants `SLEW_MS/ARRIVAL_EPS_MS/MIN_HEADROOM_MS/RATE_CHANGE_FRAC/
  STALE_TARGET_MS` — see Tempo mapping CURATE note.
- Speed jump range upper bound 1.5 — see Jump candidates.
- `TREBLE_TRAIL_SHORTEN = 0.15` (≤ ~30% faster decay) — "subtle" is untested.
- `FLASH_DECAY_MS = 500` and the 5-pixel cross size — pop must read at 40 ft
  without whiting out the line.
- `VEL_MIN = 0.45` — spread between slowest and fastest vertex; also spreads
  natural arrival times so vertices land on different grid beats.
- Default `geometry = CUBE_RING` — chosen as the most site-specific mode; FACES
  is the more literal screensaver quote (and the only mode with x-bounces on
  the grid).
- Flash brightness energy range `lin(e, 0.6, 1.0)`.
- Two alternating hues per polyline (swatch slots 2s, 2s+1) — verify the
  alternation reads as intentional rather than as a rendering artifact.
