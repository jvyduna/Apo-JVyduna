# Outrun

Retro 80s fly-over: neon perspective grid and wireframe topography wrapped 360
degrees around the cube.

> Sidecar design doc convention: this file lives beside `Outrun.java` and is
> the source of truth for design decisions and curation history. Constants not
> yet visually verified carry inline `CURATE:` notes (grep for `CURATE:`).

## Original / inspiration

Not a screensaver this time — two 1980s sources:

1. **The aircraft T-shirts.** Classic 80s/early-90s black tees of the SR-71,
   B-2 and B-1B rendered as glowing wireframe/vector art flying over a
   perspective wireframe terrain grid (e.g. the 1986 B-1B-over-green-wireframe-
   mountains print). Signature: neon gridlines converging to a vanishing
   point, a hard horizon, monochrome phosphor green/amber on black, terrain as
   displaced wireframe rows. The feeling being preserved: *being a kid in the
   80s thinking about fast planes flying over computerized topography.*
2. **TitanicsEnd's Outrun shader** — `te-app/resources/shaders/outrun_grid.fs`
   in `titanicsend/LXStudio-TE`. The flat-ground math here is a direct Java
   port of its reverse-projection technique (see Rendering approach). Its FFT
   terrain and beat bolts are intentionally *not* ported (no audio in v1); the
   beat bolt survives as the manual Pulse trigger.

Interpretation notes: the TE shader scrolls the grid under a fixed camera on a
flat screen. Here the camera itself flies, because the render target is a
360-degree panorama — the four cube faces form one 200-column ring, and only
genuine camera motion keeps the leading/trailing faces coherent (terrain
rushes toward you ahead, recedes behind).

## Rendering approach

- **Base class:** `ApotheneumPattern` (not `ApotheneumRasterPattern`; the
  ring-wrapped `SurfaceCanvas` mapping is the natural fit and leaves the door
  open for a cylinder variant later).
- **Surfaces:** cube exterior ring only, 200×45 via one preallocated
  `SurfaceCanvas(200, 45)` → `copyTo(Apotheneum.cube.exterior)`. Interior
  faces mirror via `copyCubeExterior()`. Cylinder intentionally dark
  (`setColors(BLACK)` each frame; nothing else touches it).
- **Panoramic camera:** each ring column x is an azimuth ray
  `az = heading + 2π·x/200` from a camera at height `camY` over the ground
  plane. Rows map to elevation angles through the vertical FOV
  (`anglePerRow = vfov/45`); Pitch shifts the horizon row, a Bank tilts it
  sinusoidally around the ring (`tilt·sin(az − heading)` — a camera roll seen
  panoramically).
- **Flat ground (Relief ~0):** closed-form reverse projection per pixel below
  the horizon, the outrun_grid.fs technique: `d = camY / tan(−elevation)`,
  world point `cam + d·(sin az, cos az)`, gridline proximity from
  `fract(world/cell)`, distance-squared line glow widened with depth (tames
  aliasing), quadratic depth fade to a FAR=28 haze.
- **Terrain (Relief up):** per-column raymarch near→far over 64 exponentially
  spaced depths — the classic 80s wireframe terrain renderer. Heightfield is
  two-octave value noise on a shuffled lattice (Rough sets wavelength and
  octave mix, Relief sets amplitude 0..3 world units). Painter's occlusion
  per column; consecutive samples joined by vertical pixel runs so slopes
  have no holes; the top pixel of each newly exposed run is the silhouette
  ridge line (WIRE/FILLED modes). The camera floats: `camY` never dips below
  local terrain + 0.45.
- **Fill modes:** `WIRE` = gridlines + ridge only on black (the T-shirt look);
  `FILLED` = dim solid ground under bright lines (hidden-surface 80s CG);
  `GLOW` = continuous ground glow with grid ridges (synthwave poster).
- **Color:** 64-entry depth-gradient LUT, index 0 = nearest ground. Built from
  the project palette swatch (first color near, last far, non-wrapping) when
  it has ≥ 2 colors, else a phosphor green→dim-cyan fallback; Hue knob offsets
  either. Rebuilt only when the swatch or Hue actually changes (Satori idiom).
  CURATE: fallback endpoints (hsb 125→185, 95→80, 100→55) chosen blind.
- **Buffers:** canvas, march-distance table, noise permutation, LUT, cached
  swatch — all preallocated in the constructor; zero allocation in the render
  path. Per-frame derived values live in `f*` fields so the per-pixel helpers
  stay argument-light.
- **Door columns:** cube face columns always carry the full 45 points; the
  `SurfaceCanvas.copyTo` length guard covers any variance.
- **Cost:** flat path ≤ 9,000 closed-form pixels; terrain path 200×64 march
  samples ≈ 12.8k noise evaluations (≤ 4 lattice lookups each; ×2 during a
  Regen morph). Trivial at 60 fps. CURATE: check for depth-banding from
  STEPS=64 on near, steep terrain.

## Audio mapping

**None in v1 — by design.** The standard `CompoundParameter("Audio", 0)` knob
(key `audio`) is registered to keep the series' parameter-order contract and
so future wiring never has to rename keys, but nothing reads it yet; the
sidecar convention's "silence behavior" is therefore the *only* behavior, and
the pattern is built to look good as a pure screensaver at any knob position.
Documented candidate wiring for v2: bass → transient Boost-style speed lifts,
level → horizon glow, treble → gridline sparkle, bassHit → Pulse.

## Tempo mapping

- **Pulse** (event): at trigger, when Sync is on, the bolt's travel rate is
  scaled by `TempoLock.retime(2200 ms, TempoDiv)` with the default clamp
  (0.7..1.4), so the bolt reaches the horizon on a grid boundary. Life stays
  in 1.57..3.14 s — at or above the 1.5 s event floor.
- **Bank** (event): at trigger, when Sync is on, the turn duration is retimed
  with clamp (0.7..**1**) — slow-down only, so the 90-degree turn never beats
  the 5 s pace that keeps a full pan at 20 s/rev.
- `TempoLock.crossed()` is polled every frame (unconditionally, per its
  contract) though no per-frame event consumes it in v1.
- Sync off: both triggers run at their free base durations; nothing else
  changes.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Flight speed multiplier | 0.7× | 1.3× | lin |
| Line/ground intensity gain | 0.8 | 1.2 | lin |
| Horizon glow brightness | 0.18 | 0.5 | lin |
| Pulse bolt amplitude | 0.5 | 1.0 | lin |

Sustained motion respects the caps below even at e=1 (see compliance).

## Parameters

UI/registration order convention (do not deviate; existing keys/labels must
never be renamed — saved .lxp files reference them):

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `boost` | Boost | TriggerParameter | — | — | afterburner: speed ×(1+1.6·env), envelope decays exp τ=1.1 s |
| `bank` | Bank | TriggerParameter | — | — | banked 90° turn over ~5 s, horizon tilts up to 4 rows |
| `regen` | Regen | TriggerParameter | — | — | crossfade to fresh terrain over 5 s |
| `pulse` | Pulse | TriggerParameter | — | — | bright bolt races underfoot → horizon over ~2.2 s |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (see Energy mapping) |
| `speed` | Speed | CompoundParameter | 0.4 | 0..1 | flight speed, exp 0.15..2.5 units/s |
| `altitude` | Altitude | CompoundParameter | 0.5 | 0..1 | camera height, exp 0.7..4.5 units |
| `pitch` | Pitch | CompoundParameter | 0 | −0.5..0.5 | horizon row: fraction 0.35 + 0.8·pitch of 45 rows, clamped 0.03..0.85 |
| `fov` | FOV | CompoundParameter | 0.5 | 0..1 | vertical FOV, lin 38°..110° |
| `heading` | Heading | CompoundParameter | 0 | 0..1 | yaw offset 0..360°, wraps around the ring |
| `gridSize` | Grid | CompoundParameter | 0.5 | 0..1 | cell size, exp 2.4..0.45 units (knob up = denser) |
| `glow` | Glow | CompoundParameter | 0.4 | 0..1 | line half-width, lin 0.03..0.16 cell units (+depth widening) |
| `relief` | Relief | CompoundParameter | 0.35 | 0..1 | terrain amplitude ×3.0 units; ≤0.03 amp uses the flat fast path |
| `rough` | Rough | CompoundParameter | 0.4 | 0..1 | noise wavelength exp 14..4.5 units; 2nd octave weight 0.35·rough |
| `fill` | Fill | EnumParameter\<FillMode\> | WIRE | WIRE/FILLED/GLOW | topography fill mode |
| `hue` | Hue | CompoundParameter | 0 | 0..360 | hue offset (degrees) on the depth gradient |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | **reserved — unwired in v1** (see Audio mapping) |
| `sync` | Sync | BooleanParameter | true | — | lock Pulse/Bank event timing to the tempo grid |
| `tempoDiv` | TempoDiv | EnumParameter\<Tempo.Division\> | QUARTER | — | grid division for Sync |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

## Triggers

- `boost` — small: the world visibly accelerates ~2.6× for a breath and eases
  back over ~3 s; nothing else changes.
- `pulse` — mid: a single bright bolt sweeps every face from underfoot to the
  horizon in ~2.2 s (grid-timed with Sync).
- `bank` — mid/large: heading swings 90° (random direction) over ~5 s while
  the horizon rolls up to 4 rows and settles; the whole panorama slews a
  quarter turn.
- `regen` — large: the entire landscape morphs into fresh topography over 5 s
  (no cut; heights crossfade). CURATE: retriggering mid-morph snaps the old
  field to the previous target before starting the new morph — verify the pop
  is acceptable when Meta spams it.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `altitude` | full (0..1) | candidate | deck-skim ↔ map view is a strong scene change |
| `gridSize` | 0.15..0.85 | candidate | extremes avoided: near-solid lines / near-empty ground |
| `relief` | full (0..1) | candidate | flat laser grid ↔ mountains |
| `fov` | full (0..1) | candidate | perspective drama |
| `heading` | full (0..1) | candidate | instant yaw cut — CURATE: may read as a jarring hard cut; consider dropping or easing |

## Simulation-principles compliance

- **Panning (sustained):** only Bank rotates the view: 90° in ≥ 5 s (Sync can
  only stretch it) ⇒ ≥ 20 s per full 360° traversal of the ring. ✓
- **Forward flight (sustained):** speed = exp(knob, 0.15, 2.5) × lin(e, 0.7,
  1.3). Defaults (knob 0.4, e 0.35): 0.46 × 0.91 ≈ 0.42 units/s against the
  default 1.04-unit cell ⇒ ~2.5 s per cell at the screen bottom — well inside
  bold-motion territory. Worst sustained case (knob 1, e 1): 3.25 units/s ⇒
  0.32 s per nearest-row cell. CURATE: the knob's top end is intended for
  brief chase moments; verify it doesn't strobe on the sculpture and re-range
  SPEED_MAX down if it does. Boost adds ×2.6 but is an event envelope (τ 1.1 s,
  spent in ~3 s).
- **Pulse (event):** 2.2 s base, Sync clamp keeps 1.57..3.14 s ≥ the 1.5 s
  event floor. ✓
- **Bold posterized forms:** hard horizon line, quadratic depth fade to black
  (no shimmer at the far clip), gridline half-width 0.082 cells at default
  Glow *and* widened ×(1+1.5·d/FAR) with depth so far lines merge instead of
  alias; terrain features ≥ ~8.9 units wide at default Rough (≈ 8+ cells);
  silhouette ridge at 0.55 keeps the topography readable at distance. CURATE:
  visually verify no moiré in the last few rows above the fade at Glow ≤ 0.2
  and Grid ≥ 0.8.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-06 | Initial design & implementation | Plan: 80s aircraft-tee grid + TE outrun_grid.fs port, 360° panoram<br/>audio v1 |
