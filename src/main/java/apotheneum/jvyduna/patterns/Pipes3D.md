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
- **Door columns**: cube face columns always carry the full 45 points (the
  `Apotheneum.Cube.Face` constructor enforces exactly `GRID_HEIGHT` points per
  column — doors are exposed via `available()`, **not** shorter point arrays),
  so the blit writes every row of the 50×45 buffer directly. (Earlier revisions
  of this doc claimed door columns had fewer points; that was wrong.)

## Audio mapping

**Audio depth knob**: `CompoundParameter("Audio", 0)` (key `audio`), attached
via `audio.setDepth(audioDepth)`. **Default 0 = pure screensaver**: all taps
read exactly their silence values and hits never fire, so the pattern grows on
timers alone — the silence behavior below is the default behavior. Raising the
knob restores, continuously:

- **`level`** → growth rate: extrusion rate is multiplied by
  `1 + 0.3·level` (chosen over highlight brightness, because shading is baked
  into the persistent buffers — a level-driven highlight couldn't reach
  already-committed pixels). Scales linearly with depth; modest by design, and
  the traversal-cap floor budgets for the full ×1.3 boost.
- **`bassHit()`** → at Sync on + energy > 0.6, a completed pipe holding for the
  next TempoDiv grid boundary is released early by a bass transient, so growth
  pulses land on the music between grid points.
- **`trebleHit()`** → sparkle: the 16 most recent elbows flash white (a 5 px
  plus, depth-tested against the wall buffers so occluded elbows don't flash),
  decaying over 500 ms. The flash amplitude scales with `audio.depth()`; the
  manual `Sparkle` trigger fires it at full brightness regardless of the knob.
- **Silence / depth-0 behavior**: level = 0 → base growth rate; hits never
  fire → no audio sparkle (the `Sparkle` trigger still works), and with Sync on
  the high-energy gate still opens on TempoDiv grid crossings (the internal
  tempo always runs). Fully presentable with zero audio.

## Tempo mapping

Default `TempoDiv` = **1/16**; `Sync` on by default. Three lock points, all via
the shared `TempoLock`:

1. **Segment durations quantize** (`nextSegmentMs`): the energy-interpolated
   per-cell duration rounds **up** to a whole number of TempoDiv divisions
   whenever Sync is on (previously this only happened above energy 0.6, against
   the removed GrowDiv parameter, and was never phase-aligned).
2. **Completions phase-align** (`beginSegment`): on the frame a segment's
   growth actually starts (right after an advance, or when a gate-wait ends),
   `retime(msUntil, tempoDiv)` nudges the duration (rate scale clamped to the
   default [0.7, 1.4]) so the cell completes **exactly on a TempoDiv boundary
   of the real engine beat** (`Tempo.getBasis`), not just a period multiple.
   The arrival estimate folds in the audio level boost (`pSegMs / (1 +
   0.3·level)`, level sampled at segment start), so alignment holds with the
   Audio knob up too — exact for steady level, approximate while it changes.
   Each segment re-aligns, so frame-overshoot and residual audio-boost drift
   never accumulate. Because turns (elbow balls) happen at segment completions,
   **elbows inherently land on the grid** — no separate turn lock needed.
3. **High-energy start gate** (`updatePipes`): at Sync on + energy > 0.6, a
   completed pipe holds until `crossed(tempoDiv)` fires (or a bass transient,
   or the 1.5 s safety timeout), so growth pulses step on the grid. At 1/16
   this reads as a near-continuous tick; set TempoDiv to 1/4 for the classic
   beat-pulse feel. CURATE: whether 1/16 or 1/4 reads better as the default at
   high energy on the sculpture.

**Sync off** restores fully free-running timing: no duration quantization, no
retime, no start gating at any energy — pipes grow on pure interpolated timers
(note: the pre-2026-07-05 build always beat-gated above energy 0.6; that gating
now lives behind Sync).

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Segment extrusion time (ms/cell) | 2000 | 1000, floored by `minSegMs = 5000·1.3·1.4/gx` (910 ms at density 10, 1517 ms at density 6), then with Sync on rounded **up** to whole TempoDiv divisions | lin |
| Grid gating of segment starts (Sync on) | off | on (threshold e > 0.6) | step |

Sustained motion respects the ≥5 s full-traversal cap even at e=1 at **every
density** (math below).

## Parameters

UI/registration order: triggers, Energy, pattern params, Audio, Sync, TempoDiv,
Meta last (12 total).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `drain` | Drain | TriggerParameter | — | — | fade the room out over 3 s, clear, restart in the next palette color |
| `teleport` | Teleport | TriggerParameter | — | — | one random growing pipe caps and jumps to a random free cell (the classic) |
| `newPipe` | NewPipe | TriggerParameter | — | — | spawn another concurrent pipe (max 3) |
| `sparkle` | Sparkle | TriggerParameter | — | — | flash the recent elbow joints white at full brightness (manual treble sparkle) |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | 0–0.4 ambient crawl; 0.6–1.0 grid-gated high-energy growth |
| `thickness` | Thick | CompoundParameter | 3.5 | 3..5 | pipe thickness in px (applies to newly drawn segments; clamped to cell size) |
| `density` | Density | DiscreteParameter | 10 | 6..12 | room grid cells per axis; **takes effect at the next drain** |
| `hue` | Hue | CompoundParameter | 0 | 0..360 | hue offset (degrees) added to the palette-derived pipe color |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full reactivity |
| `sync` | Sync | BooleanParameter | true | — | lock growth to the tempo grid; off = free-running timers |
| `tempoDiv` | TempoDiv | EnumParameter&lt;Tempo.Division&gt; | 1/16 | all LX divisions | grid that durations quantize to and starts gate on when Sync is on |
| `meta` | Meta | TriggerParameter | — | — | randomly fire one trigger or jump one parameter (`TriggerBag`) |

Removed 2026-07-05: `growthDiv` (GrowDiv, enum 1/8..4/4) — superseded by the
standard `sync`/`tempoDiv` pair. Old `.lxp` files referencing it will restore
every other parameter and log an unknown-parameter warning for this one.

Pipe color: hue of the palette swatch color at index `drainCount % swatch.size()`
(so each drain advances to the next palette color), plus the `hue` offset, plus
40° per concurrent pipe index so simultaneous pipes read as distinct.
Thickness/hue changes affect newly rasterized segments only — committed pixels
are baked (a deliberate consequence of the persistent-buffer design; old
geometry keeps its look, like real pipes already installed).

## Triggers

Four non-meta triggers, small → large:

- `sparkle` — **small**: the ≤16 most recent elbow joints flash white and decay
  over 500 ms; a stationary glitter accent, no state changes.
- `teleport` — **medium**: instant; a cap ball marks the disconnect point and
  the pipe continues from a random free cell. Reads immediately (the classic
  NT gag).
- `newPipe` — **medium**: instant; a spawn ball appears at a free cell and a
  new pipe starts growing from it (max 3 concurrent; ignored when full or
  draining).
- `drain` — **large**: 3 s full-surface brightness fade (a fade, not motion),
  then the occupancy and buffers clear, the pending density applies, the
  palette color advances, and the same number of pipes respawn. Also fires
  automatically at >60% fill, or if a teleport/spawn can find no free cell.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `thickness` | 3..5 (full) | candidate | new segments only; visible drift in lattice weight |
| `density` | 6..12 (full) | candidate | deferred to next drain — jump reads as a room-scale change after the clear |
| `hue` | 0..360 (full) | candidate | new segments only; lattice becomes multicolored over time |
| `tempoDiv` | SIXTEENTH..HALF (curated subrange) | candidate | CURATE: unverified visually — a grid jump should read as a groove change, not a stall |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

`growthDiv` was jumpable and is removed. `tempoDiv` joins the pool only over
the curated `SIXTEENTH..HALF` ordinal subrange (via the
`TriggerBag.jumpable(param, lo, hi)` discrete overload): the full
`Tempo.Division` range spans 1/16 up to 16 bars, and a jump into the
multi-bar divisions would quantize a segment to tens of seconds.

## Simulation-principles compliance

Fastest sustained motion is the extrusion tip crossing a wall (`gx` cells):

- Cell size at default density 10: 50/10 = **5 px**; a wall crossing is 10 cells.
- Default energy 0.35 (density 10): segment time = lin(0.35, 2000, 1000) =
  1650 ms/cell, Sync-quantized up to whole 1/16s → ≥ 1650 ms → ≥ **16.5 s**
  per 50 px wall crossing.
- Energy 1, worst case at any density: `nextSegmentMs` floors the duration at
  `minSegMs = TRAVERSAL_MIN_MS · (1 + LEVEL_RATE_BOOST) · DEFAULT_MAX_SCALE / gx
  = 5000·1.3·1.4/gx`. The retime speed-up shrinks a duration by at most ×1.4
  and the audio level boost accelerates by at most ×1.3, so effective per-cell
  time ≥ 5000/gx ms → a full crossing of gx cells always takes ≥ **5.0 s**
  exactly at the cap. Concretely: density 10 ≥ 5.5 s (floor inactive at
  1000 ms/cell, retime+boost worst 549 ms/cell); density 6, floor 1517 ms/cell
  → worst 833 ms/cell × 6 = 5.0 s. (Pre-2026-07-05 this was violated: 4.6 s at
  density 6.) A pipe rarely runs straight across anyway (P_STRAIGHT = 0.55).
- Drain: a 3 s full-surface **brightness ramp** — a fade, not motion, so the
  traversal cap does not apply; nothing translates during it.
- Elbow sparkle: a **stationary** 500 ms brightness flash at fixed joints (like
  a beat pulse), not motion; no traversal involved.
- Balls/caps appear instantaneously but are single-cell events (~6 px) —
  event-like, and they persist indefinitely as lattice geometry.

Contrast/brightness: fully saturated pipes on true black; the only mid-tones
are the depth cue (floor at 50% brightness so far pipes stay readable, not
muddy) and the 1 px specular stripe. No fine texture; forms are ≥3 px thick by
parameter floor (thickness clamps to `min(cw, ch)` — ≈4.1 px at density 12,
where `ch` = 45/11 binds — so adjacent lattice cells stay distinct —
`CURATE:` note in code).

Time-to-fill at defaults (rough): 900 cells × 60% = 540 cells; 1 pipe at ~1.7
s/cell ≈ 15 min to auto-drain (ambient — intentionally patient); 3 pipes at
peak ≈ 4 min. The `drain` trigger and meta exist to short-circuit this live.
`CURATE:` if ambient fills too slowly to ever drain in a set, lower
`FILL_LIMIT` or raise ambient pipe count.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | First pass per approved plan; all CURATE: constants unverified on sculpture |
| 2026-07-05 | Adversarial review pass: `beginSegment` retime estimate now folds in the audio level boost (`pSegMs/(1+0.3·level)`) — previously completions landed up to 23% (more than a whole 1/16 at ~1000 ms segments) early of the retimed boundary whenever the Audio knob was up; traversal-cap math unaffected (same [0.7,1.4] clamp). Corrected the thickness-clamp figure (binding clamp at density 12 is `ch` ≈ 4.1 px, not 4.2) | Verified improver's claims: all four claimed pre-existing bugs confirmed against HEAD; cap/quantization arithmetic checked; param keys/labels unchanged; render path allocation-free |
| 2026-07-05 | Fixed sparkle overlay self-occlusion (visibility epsilon 0.001 was smaller than the elbow ball's own near-face depth offset ≈0.05 — sparkles never drew); enforced ≥5 s traversal cap at all densities via `minSegMs` floor (was 4.6 s at density 6/e=1/loud); added `audio` depth knob (default 0 = pure screensaver) wired through `AudioReactive.setDepth`, treble-sparkle response now scales with depth; migrated tempo handling to shared `TempoLock` with new `sync`/`tempoDiv` params — durations quantize AND phase-align to the engine grid via `retime` (old GrowDiv quantization was period-multiple only, and the start gate was hardcoded quarter-note `tempo.beat()`); removed `growthDiv`; added `Sparkle` manual trigger (4 non-meta triggers); corrected false door-column claim (cube columns are always 45 points) | Review session (Fable): bug fixes + series-convention upgrade |
| 2026-07-05 | Integration pass: util request granted — `TriggerBag.jumpable(DiscreteParameter, lo, hi)` subrange overload added; `tempoDiv` now in the meta pool over the curated `SIXTEENTH..HALF` ordinal window (the SIXTEENTH..HALF span includes the triplet/dotted divisions between them — all sub-bar, all musical). Also fixed a stale-gate nit: `crossed()` now polls every frame instead of `syncOn && crossed()`, so re-enabling Sync can't spuriously open the growth gate off-grid. CURATE: tempoDiv jump unverified visually | Pipes3D agent's util request + series `crossed()` idiom |
