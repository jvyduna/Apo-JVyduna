# Mystify

Mystify-screensaver polylines with fading trails, bouncing on the cube faces or
wrapping around the cube ring or cylinder.

> Sidecar design doc convention: this file lives beside `Mystify.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

The Windows 95+ **"Mystify Your Mind"** screensaver (and its relatives — After
Dark's *Northern Lights* / *String Theory*, and the wraparound variants on cyclic
displays): 1–2 closed polylines whose vertices drift independently and bounce off
the screen edges, each edge leaving a fading trail of its past positions. The
visual signature preserved here: slow independent vertex drift, closed polygons
that continuously self-morph, long ghost trails, and pure saturated color on
black. This is an interpretation — the trail here is a per-pixel exponential
decay buffer rather than Mystify's N stored past polygons; at LED resolution the
decay buffer reads the same and is far cheaper.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the sim needs the wrap-around cube ring
  and cylinder orientations plus exterior→interior copy utilities, which the
  raster base (cube-face-only) cannot reach.
- **SurfaceCanvas simulation**: vertex positions/velocities live in normalized
  `[0,1]²` coordinates in preallocated `double[]` arrays (`MAX_SHAPES=2` ×
  `MAX_VERTS=5` = 10 vertices, all simulated every frame; the `shapes`/`vertices`
  parameters only select how many are *drawn*, so count changes are seamless and
  allocation-free). Edges are Bresenham `canvas.line(...)`; trails come from one
  `canvas.decay(f)` per frame — the canvas is never cleared during normal play.
- **Geometry modes** (`Geometry` enum, all three canvases preallocated in the
  constructor; only the active one is touched per frame):
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
  mode change all three canvases are cleared so stale trails from the previous
  topology never reappear.
- **Wrap drawing**: on wrapping topologies each edge takes the short way around
  the ring (endpoint shifted by ±width when |Δx| > width/2); `SurfaceCanvas.set`
  floorMods x, so off-canvas endpoints land correctly.
- **Zero-alloc render**: canvases, position/velocity arrays, and the 4-entry
  color array are constructed once; regeneration happens only in trigger
  handlers. Per-frame work is ~10 vertex integrations, ≤10 lines, one decay pass.
- **Door columns**: `SurfaceCanvas.copyTo` guards by `column.points.length`; the
  FACES blit does the same.

## Audio mapping

- `level` (smoothed 0..1) — breathes speed ±30% around a nominal that is
  cap/1.3, so full level lands exactly on the traversal cap, never above.
- `bassHit()` — sets a flash envelope to 1; a bright white cross (vertex pixel +
  4 neighbors) is stamped at every active vertex, fading linearly over
  `FLASH_DECAY_MS = 500` ms. Stamped onto the canvas, so the pop also leaves a
  decaying trail residue.
- `trebleRatio` — subtly shortens trails: the trail half-life is divided by
  `1 + 0.15·clamp(trebleRatio − 1, 0, 2)` (up to ~1.3× faster decay on busy
  highs).
- **Silence behavior**: `level = 0` → speed settles at 0.7× nominal — steady,
  graceful drift; `bassHit` never fires (no flashes); `trebleRatio → 0` → clamp
  gives 0 → trails at their full knob-set length. The pattern is a complete
  ambient screensaver with no audio at all.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Width-traversal cap (fastest vertex) | 12 s / canvas width | 5 s / canvas width | lin |
| Bass-flash brightness | 60% | 100% | lin |

Sustained motion respects the ≥5 s full-traversal cap even at e=1 (see
compliance math below). Energy deliberately does **not** touch trail length —
that stays a hands-on look control.

## Parameters

UI order: triggers first, Energy, pattern parameters, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `scatter` | Scatter | TriggerParameter | — | — | re-randomize every vertex position + velocity |
| `reverse` | Reverse | TriggerParameter | — | — | negate all velocities; shapes retrace their paths |
| `hueJump` | HueJump | TriggerParameter | — | — | rotate palette color assignment of the polylines |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (speed cap, flash intensity) |
| `geometry` | Geometry | EnumParameter&lt;Geometry&gt; | CUBE_RING | FACES / CUBE_RING / CYLINDER | sim topology / target surface |
| `shapes` | Shapes | DiscreteParameter | 2 | 1..2 | number of polylines |
| `vertices` | Vertices | DiscreteParameter | 4 | 3..5 | vertices per polyline |
| `speed` | Speed | CompoundParameter | 1 | 0.2..1 | fraction of the energy-set traversal cap |
| `trails` | Trails | CompoundParameter | 0.5 | 0..1 | trail length (exp half-life 50..2500 ms) |
| `meta` | Meta | TriggerParameter | — | — | randomly fire one trigger or jump one parameter |

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

## Triggers

- `scatter` — every vertex jumps to a fresh random position with a fresh random
  velocity. The old shapes' trails fade out at the trail rate (~0.3–2 s at
  typical knob settings) while the new shapes draw in, so the cut reads within
  a second or two without a hard blackout.
- `reverse` — all velocities negate; the shapes visibly fold back along their
  own fading trails. Reads immediately because each line re-enters its own ghost.
- `hueJump` — the 4-slot palette color assignment rotates by one; edges change
  hue instantly, old-hue trails fade underneath (reads within one trail life).

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `trails` | [0.25, 0.85] | candidate | CURATE: full range excluded — 0 looks bare, 1 can smear; verify subrange |
| `vertices` | 3..5 (full) | candidate | count changes are seamless (all 5 always simulated) |
| `geometry` | all 3 values | candidate | hard visual cut (canvases cleared); most dramatic jump |
| `speed` | [0.4, 1.0] | candidate | CURATE: below 0.4 may read as stalled |
| `shapes` | 1..2 (full) | candidate | second-polyline on/off, exposed as a DiscreteParameter per series convention (TriggerBag has no boolean jump) |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

**Speed cap math.** Vertex velocity components are stored as canvas-spans/sec at
cap = 1 with magnitude in `[0.45, 1]`. Per frame:

```
capSpansPerSec = 1 / lin(energy, 12 s, 5 s)
audioBreath    = 1 + 0.3·(2·level − 1)            ∈ [0.7, 1.3]
rate           = cap · Speed · audioBreath / 1.3   (Speed ≤ 1)
⇒ max |dx/dt|  = cap · 1 · 1.3 / 1.3 = cap         — never exceeded
```

Fastest possible full-canvas-width traversal:

- **Default energy (0.35)**: cap = 1 / lin(0.35, 12, 5) = 1/9.55 → **9.55 s**
  minimum (loud audio, Speed = 1). Nominal (quiet room): 9.55 × 1.3/1.0 ≈
  12.4 s; silence floor ≈ 17.7 s.
- **Energy = 1**: cap = **5.0 s** minimum — exactly the ≥5 s floor, only
  reachable with Speed = 1 *and* sustained full audio level.

Vertical motion uses the same normalized rate on canvas heights, so a full
top-to-bottom crossing also takes ≥ the same seconds — comfortably above the
floor. In FACES mode the canvas width is one 50-column wall (the full surface a
viewer sees from any side); in the wrap modes it is the entire 200/120-column
circumference, so wrap modes are strictly slower in ft/sec.

**Event motion.** The bass flash is a stationary brightness pop (no traversal);
it fades over 0.5 s and its canvas residue continues fading at the trail rate,
so total visual life exceeds the flash envelope. No fast-moving event exists in
this pattern.

**Contrast / brightness.** Pure palette (or saturated fallback) hue on true
black; 1-px lines are acceptable here per the series exception — they move
slowly (≥5 s/span) and are maximum-contrast on black. Trails are strictly
darker copies of the line (multiplicative decay), preserving the bold
figure/ground split. No fine texture, no gradients wider than the trail falloff.
CURATE: verify 1-px line visibility across the space at 60 fps decay rates; if
too thin at distance, a 2-px double-strike line is the fallback.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |

CURATE (unverified constants, gathered for the walk-through):
- `TRAIL_HALF_LIFE_MIN_MS/MAX_MS = 50/2500`, exp-mapped from Trails knob —
  default (0.5 → ~354 ms half-life) chosen by intuition.
- `TREBLE_TRAIL_SHORTEN = 0.15` (≤ ~30% faster decay) — "subtle" is untested.
- `FLASH_DECAY_MS = 500` and the 5-pixel cross size — pop must read at 40 ft
  without whiting out the line.
- `VEL_MIN = 0.45` — spread between slowest and fastest vertex; too low may look
  like a stuck vertex.
- Default `geometry = CUBE_RING` — chosen as the most site-specific mode; FACES
  is the more literal screensaver quote.
- Flash brightness energy range `lin(e, 0.6, 1.0)`.
- Silence speed floor of 0.7× nominal (from the ±30% breath at level = 0) —
  confirm ambient pacing still feels alive in a quiet room.
- Two alternating hues per polyline (swatch slots 2s, 2s+1) — verify the
  alternation reads as intentional rather than as a rendering artifact.
