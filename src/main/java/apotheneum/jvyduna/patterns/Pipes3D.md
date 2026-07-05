# Pipes3D

NT 3D Pipes: a self-avoiding pipe lattice grows in one shared room volume,
projected orthographically onto all four cube walls.

> Sidecar design doc convention: this file lives beside `Pipes3D.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

The Windows NT 4.0 / 9x "3D Pipes" OpenGL screensaver (`sspipes.scr`): glossy
colored pipes growing through a dark room with right-angle turns, sphere joints
at the elbows, an occasional teleport, and a screen clear when the room fills
up. Signature elements preserved: saturated pipe colors on true black,
right-angle self-avoiding growth, ball joints at every elbow, teleporting when
boxed in (and on demand), and the fill-then-clear lifecycle. This is an
interpretation for LED-wall resolution, not a port: pipes are one cell thick,
shading is a depth cue + specular stripe rather than real lighting.

The conceit for Apotheneum: the cube's four walls are the four walls of the NT
*room*. There is exactly one 3D pipe lattice in a ~10×9×10-cell volume, and
each wall shows an orthographic projection of that SAME object along its inward
normal — walk around the cube and you see the same pipes from four sides, with
corner-continuous mappings so adjacent walls agree at their shared edges.

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
  so a density jump requires **no reallocation ever** (stricter than the planned
  event-rate realloc).
- **Projection**: per-wall orthographic along the inward normal, into 4
  persistent `int[50·45]` color buffers + 4 `float[50·45]` depth buffers
  (nearer wins). Corner-continuous mappings (u = horizontal pixel, d = depth in
  cells, v = y·ch for all walls):

  | Wall | u | depth d |
  |---|---|---|
  | front | `x·cw` | `z` |
  | right | `z·cw` | `gx − x` |
  | back | `(gx − x)·cw` | `gz − z` |
  | left | `(gz − z)·cw` | `x` |

  `right.columns[0]` physically adjoins `front.columns[49]` (ring order), and
  each mapping puts the shared corner at the shared edge, so the four views
  stitch into one object.
- **Incremental rasterization**: only the growing segment (an axis-aligned box
  from cell-center A toward cell-center B, truncated at the extrusion fraction)
  rasterizes per frame. Growth is monotonic, so redrawing into the persistent
  buffers is idempotent; completed geometry persists and is never re-rendered.
  Elbow balls and teleport/spawn cap balls are rasterized once, at event time.
  One depth-per-box simplification: a box perpendicular to a wall (seen end-on)
  takes its **near** depth across its whole footprint — visually correct for
  the small end-on square, cheap to compute.
- **Shading**: brightness factor `1 − 0.5·depthNorm` (far wall = 50%, so far
  pipes are clearly dimmer but not muddy), a 1 px desaturated highlight stripe
  along the pipe axis (skipped when seen end-on), and elbow balls drawn
  slightly larger (`+1 px` radius), brighter (`×1.15`), and less saturated —
  the glossy joint read.
- **Buffers / zero-alloc render**: everything preallocated in the constructor —
  4× color `int[2250]`, 4× depth `float[2250]`, occupancy
  `boolean[12][11][12]`, parallel pipe-state arrays (3 slots), direction
  scratch `int[6]`, elbow ring buffer (16). The render path allocates nothing.
  Event-rate exceptions (all noted): `Arrays.fill` room clears at drain end,
  `LX.log` strings at drain start/end and from `TriggerBag.fire()`.
- **Door columns**: buffers are computed for the full 50×45 grid; the blit
  iterates `column.points.length` so door-shortened columns just clip the
  bottom rows.

## Audio mapping

- **`level`** → growth rate: extrusion rate is multiplied by
  `1 + 0.3·level` (chosen over highlight brightness, because shading is baked
  into the persistent buffers — a level-driven highlight couldn't reach
  already-committed pixels). Modest by design; stays inside the motion cap.
- **`bassHit()`** → at energy > 0.6, new segment starts are gated: a completed
  pipe holds until the next `tempo.beat()` **or** bass transient (1.5 s safety
  timeout), so growth pulses land on the music.
- **`trebleHit()`** → sparkle: the 16 most recent elbows flash white (a 5 px
  plus, depth-tested against the wall buffers so occluded elbows don't flash),
  decaying over 500 ms.
- **Silence behavior**: level = 0 → base growth rate; hits never fire → no
  sparkle, and beat gating still opens on `tempo.beat()` (the internal tempo
  always runs). Pipes grow on timers and the pattern is fully presentable with
  zero audio.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Segment extrusion time (ms/cell) | 2000 | 1000 (then rounded **up** to a whole number of GrowDiv periods) | lin |
| Beat gating of segment starts | off | on (threshold e > 0.6) | step |

Sustained motion respects the ≥5 s full-traversal cap even at e=1 (math below).

## Parameters

UI order: triggers first, Energy, pattern parameters, Meta last (9 total).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `drain` | Drain | TriggerParameter | — | — | fade the room out over 3 s, clear, restart in the next palette color |
| `teleport` | Teleport | TriggerParameter | — | — | one random growing pipe caps and jumps to a random free cell (the classic) |
| `newPipe` | NewPipe | TriggerParameter | — | — | spawn another concurrent pipe (max 3) |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | 0–0.4 ambient crawl; 0.6–1.0 beat-gated high-energy growth |
| `thickness` | Thick | CompoundParameter | 3.5 | 3..5 | pipe thickness in px (applies to newly drawn segments; clamped to cell size) |
| `density` | Density | DiscreteParameter | 10 | 6..12 | room grid cells per axis; **takes effect at the next drain** |
| `hue` | Hue | CompoundParameter | 0 | 0..360 | hue offset (degrees) added to the palette-derived pipe color |
| `growthDiv` | GrowDiv | EnumParameter&lt;Growth&gt; | 1/4 | 1/8, 1/4, 1/2, 4/4 | beat division quantizing segment duration/starts at high energy |
| `meta` | Meta | TriggerParameter | — | — | randomly fire one trigger or jump one parameter (`TriggerBag`) |

Pipe color: hue of the palette swatch color at index `drainCount % swatch.size()`
(so each drain advances to the next palette color), plus the `hue` offset, plus
40° per concurrent pipe index so simultaneous pipes read as distinct.
Thickness/hue changes affect newly rasterized segments only — committed pixels
are baked (a deliberate consequence of the persistent-buffer design; old
geometry keeps its look, like real pipes already installed).

## Triggers

- `drain` — 3 s full-surface brightness fade (a fade, not motion), then the
  occupancy and buffers clear, the pending density applies, the palette color
  advances, and the same number of pipes respawn. Also fires automatically at
  >60% fill, or if a teleport/spawn can find no free cell.
- `teleport` — instant: a cap ball marks the disconnect point and the pipe
  continues from a random free cell. Reads immediately (the classic NT gag).
- `newPipe` — instant: a spawn ball appears at a free cell and a new pipe
  starts growing from it (max 3 concurrent; ignored when full or draining).

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `thickness` | 3..5 (full) | candidate | new segments only; visible drift in lattice weight |
| `density` | 6..12 (full) | candidate | deferred to next drain — jump reads as a room-scale change after the clear |
| `hue` | 0..360 (full) | candidate | new segments only; lattice becomes multicolored over time |
| `growthDiv` | all 4 divisions | candidate | only audible at energy > 0.6 (quantization granularity) |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

Fastest sustained motion is the extrusion tip crossing a wall:

- Cell size at default density 10: 50/10 = **5 px**. A wall crossing is 10 cells.
- Default energy 0.35: segment time = lin(0.35, 2000, 1000) = **1650 ms/cell**
  → 10 × 1.65 s = **16.5 s** per 50 px wall crossing.
- Energy 1: base 1000 ms/cell, rounded **up** to a whole number of GrowDiv
  periods (at 160 BPM, 1/4 = 375 ms → 3 × 375 = 1125 ms/cell); with the maximum
  audio-level boost ×1.3 → ≈ **865 ms/cell** worst case → ≈ **8.7 s** per wall
  crossing. ≥5 s cap satisfied with margin, and a pipe rarely runs straight
  across anyway (P_STRAIGHT = 0.55).
- Drain: a 3 s full-surface **brightness ramp** — a fade, not motion, so the
  traversal cap does not apply; nothing translates during it.
- Elbow sparkle: a **stationary** 500 ms brightness flash at fixed joints (like
  a beat pulse), not motion; no traversal involved.
- Balls/caps appear instantaneously but are single-cell events (~6 px) —
  event-like, and they persist indefinitely as lattice geometry.

Contrast/brightness: fully saturated pipes on true black; the only mid-tones
are the depth cue (floor at 50% brightness so far pipes stay readable, not
muddy) and the 1 px specular stripe. No fine texture; forms are ≥3 px thick by
parameter floor (thickness clamps to the cell size at density 12, ≈4.2 px, so
adjacent lattice cells stay distinct — `CURATE:` note in code).

Time-to-fill at defaults (rough): 900 cells × 60% = 540 cells; 1 pipe at 1.65
s/cell ≈ 15 min to auto-drain (ambient — intentionally patient); 3 pipes at
peak ≈ 4 min. The `drain` trigger and meta exist to short-circuit this live.
`CURATE:` if ambient fills too slowly to ever drain in a set, lower
`FILL_LIMIT` or raise ambient pipe count.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | First pass per approved plan; all CURATE: constants unverified on sculpture |
