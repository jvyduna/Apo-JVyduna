# Lorre

A rotating Lorenz-attractor particle swarm with speed-lit fading trails on the
cube and cylinder.

> Sidecar design doc convention: this file lives beside `Lorre.java` and is the
> source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

Recreates **"Lorre" by Egil Stevens**, from "The Floating Point" collection — a
screensaver showing a swarm of points tracing the Lorenz strange attractor,
slowly rotating, with fading trails. Signature to preserve: the two-lobed
butterfly must read clearly as two orbiting lobes; the slow rotation reveals its
3D structure; kicked particles visibly re-converge onto the attractor (the
signature move).

## Rendering approach

- **Base class**: `ApotheneumPattern` — needs both cube and cylinder plus the
  exterior→interior copy helpers; per-point raster access is not required
  because everything is drawn into `SurfaceCanvas` buffers.
- **Surfaces**: cube exterior ring (200×45 canvas) + cylinder (120×43 canvas).
  Canvases are copied to the exteriors via `canvas.copyTo(orientation, colors)`,
  then interiors mirror via `copyCubeExterior()` / `copyCylinderExterior()`.
- **Simulation**: 300 particles in a preallocated `double[3][300]`, integrating
  the Lorenz ODE (`dx=σ(y−x)`, `dy=x(ρ−z)−y`, `dz=xy−βz`; σ=10, β=8/3, ρ from
  the `Rho` knob) with clamped-dt forward Euler: fixed substeps of ≤ 0.004 sim
  units, at most 12 per frame (longer frames dilate sim time rather than
  destabilizing). Escape guard: any non-finite particle or one beyond radius 250
  respawns near a fixed point C± = (±√(β(ρ−1)), ±√(β(ρ−1)), ρ−1). The
  constructor scatters the swarm and silently integrates 2 sim units so frame
  one already shows the butterfly.
- **Projection / views**: the swarm is rotated about the attractor's vertical z
  axis (slow Y rotation) and orthographically projected (screen-x =
  `x·cosθ − y·sinθ`, screen-y = z). *Interpretation vs. the plan:* rather than
  one image centered on the 200-wide ring (which would leave three cube faces
  black), the cube canvas holds **four views 90° apart, one centered per face**,
  and the cylinder **two views 180° apart**. Because the faces physically sit
  90° apart, this is equivalent to orthographically projecting a single 3D
  attractor at the center of the building onto each face — every viewer sees the
  butterfly, and rotation stays coherent between adjacent faces. Each view is
  still exactly the prescribed rotation + orthographic projection.
- **Scaling**: tracks ρ so regime hops change shape/tempo, not size. Vertical
  maps z ∈ [0, 2ρ−6] into the canvas height minus 2 margin rows — the 2ρ−6
  extent is a linear fit to measured long-run z maxima (49.9 at ρ=28, 74.0 at
  ρ=40; 300 particles × 900 sim units), so the full attractor fits with a couple
  rows of margin per the brief. Horizontal budget is 1.2ρ (measured max rotated
  radius: 33.0 at ρ=28, 45.4 at ρ=40), capped so a view fits its span of the
  ring (at ρ=28: cube ≈ 48 of 50 columns per face, cylinder ≈ 51 of 60).
- **Trails**: `decay()` once per frame per canvas instead of clearing; heads by
  particle speed (below). Equal-channel decay preserves hue and integer
  truncation extinguishes trails fully to black — no gray-mush floor.
- **Buffers**: everything (`pos`, `spd`, canvases, per-view cos/sin arrays) is
  preallocated in the constructor. Zero allocation in the render path
  (`LXColor.hsb`, `Random.nextDouble`, and `PerceptualHue` are all
  allocation-free). Trigger handlers allocate nothing either; `TriggerBag.fire`
  does event-rate logging allocation, which is accepted by convention.
- **Door columns**: only written via `SurfaceCanvas.copyTo`, which guards with
  `column.points.length`.

## Audio mapping

- `level` breathes the integration dt ±30% (`dt × (1 + 0.3·(2·level − 1))`) —
  the swarm surges with loudness.
- `bassHit()` fires a **mini-kick**: the same perturbation as the Kick trigger
  at 2–6 Lorenz units (energy-scaled) instead of 25 — a shimmer, not a scatter.
- `trebleRatio` shortens the trail half-life by up to ~3.5× when treble runs
  above its running average (`halfLife / (1 + 0.35·max(0, trebleRatio − 1))`) —
  busy hi-hats crisp the trails.
- **Silence behavior**: all taps at 0 → dt settles at 0.7× base (lobe orbit
  ≈ 6.4 s, still inside the 4–8 s target), no mini-kicks, trails at the knob's
  base half-life. The attractor keeps orbiting and rotating — fully presentable.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Sim-rate multiplier | 0.8× | 1.4× | lin |
| Mini-kick magnitude (Lorenz units) | 2 | 6 | lin |
| White-hot desaturation gain | 0.4 | 0.8 | lin |

Sustained motion respects the ≥5 s full-traversal cap even at e=1 (math below).

## Parameters

UI order: triggers first, Energy, pattern parameters, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `kick` | Kick | TriggerParameter | — | — | perturb every particle; swarm re-converges over ~5 s |
| `reseed` | Reseed | TriggerParameter | — | — | re-scatter particles in the attractor's bounding box |
| `energy` | Energy | CompoundParameter | 0.35 | 0–1 | master energy (ambient ↔ 160 BPM show) |
| `rho` | Rho | CompoundParameter | 28 | 20–45 | Lorenz ρ; regime control (near-stable spirals low, wild chaos high) |
| `speed` | Speed | CompoundParameter | 1 | 0.4–1.6 | integration-rate (dt) multiplier |
| `rotate` | Rotate | CompoundParameter | 0.75 | 0–1 | Y-rotation speed; 1 = one revolution / 30 s |
| `trails` | Trails | CompoundParameter | 0.5 | 0–1 | trail half-life, exp 150 ms – 1.2 s |
| `hue` | Hue | CompoundParameter | 0.58 | 0–1 | base hue (perceptual position; default classic Lorenz blue) |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

## Triggers

- `kick` — every particle is offset by a uniform random vector in ±25 units.
  The screen explodes into scattered dust, then the attractor's strong
  transverse contraction pulls the swarm back onto the butterfly — visibly
  re-formed within ~5 s (measured; table below). The signature move.
- `reseed` — particles re-scatter uniformly in the bounding box
  (x ∈ ±0.8ρ, y ∈ ±ρ, z ∈ [0, 0.75·(2ρ−6)]); a softer full reset with the same
  convergence read.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `rho` | [24, 40] | candidate | regime-hopping — best jump in the suite |
| `rotate` | [0.15, 1.0] | candidate | floor avoids a dead-stop rotation jump |
| `trails` | full [0, 1] | candidate | |
| `speed` | [0.6, 1.4] | candidate | capped below param extremes to keep orbit tempo musical and dt stable |
| `hue` | full [0, 1] | candidate | |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

**Lobe orbit tempo** (`CURATE:` the dt constant `BASE_SIM_RATE = 0.16`): one
orbit around a lobe takes ≈ 0.73 Lorenz time units at ρ=28 (measured: mean
z-maximum period 0.727 over 2,752 loops). Wall time = 0.73 / simRate:

- Default (e=0.35 → 1.01×, Speed=1, moderate music level≈0.5 → 1.0×):
  simRate = 0.16 → orbit ≈ **4.5 s**. Silence (0.7×): 0.113 → **6.4 s**.
  Both inside the 4–8 s target.
- Absolute max (Speed=1.6, e=1 → 1.4×, level=1 → 1.3×): simRate = 0.466 →
  orbit 1.6 s. This is *local orbiting*, not sculpture traversal; on canvas the
  head speed is ≈ (π × lobe diameter ≈ 40 px) / 1.6 s ≈ **25 px/s**, i.e. a
  200-px ring-equivalent traversal of **8 s ≥ 5 s** even at the un-jumpable
  extreme.

**Rotation** (the only true full-sculpture sustained motion): max
`ROTATION_MAX_REV_PER_SEC = 1/30` → one full revolution = full horizontal
traversal in **30 s ≥ 5 s** even at Rotate=1; default 0.75 → 40 s.

**Kick re-convergence ≈ 5 s** (measured off-line: 100 particles kicked ±25 off
a settled swarm at the silence-default rate 0.113 sim/s; distance to a dense
reference trajectory on the attractor):

| wall time | avg distance | worst |
|---|---|---|
| 0 s | 13.5 | 32.1 |
| 2 s | 3.8 | 20.6 |
| 5 s | 0.8 | 5.3 |

Average error is sub-pixel by ~5 s (1 Lorenz unit ≈ 0.7 px at cube scale); with
music the rate runs ~1.4× faster. `CURATE:` KICK_MAGNITUDE = 25 — confirm the
scatter-and-snap-back reads on the sculpture.

**Integration stability**: forward Euler at h ≤ 0.004 sim units with a
12-substep/frame cap and an escape-guard respawn. Verified off-line: zero
escapes over 300 particles × 900 sim units at both ρ=28 and ρ=40.

**Contrast / brightness**: bold point-swarm forms, no fine texture. Brightness
by particle speed with a 0.3 floor (slow lobe-center particles stay visible at
LED distance); fast heads desaturate toward white-hot (energy-scaled), slow
particles stay saturated; small perceptual hue spread (0.12) across the
attractor's height for depth cueing. Trails decay equal-channel (hue-preserving)
and truncate fully to black; max half-life is capped at 1.2 s so trails never
wash into gray mush.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | Per approved plan. Numerically verified off-line: lobe period 0.727, z/horizontal extents at ρ=28/40, Euler stability (0 escapes), kick re-convergence ~5 s. `CURATE:` constants pending on-sculpture review: orbit tempo feel, kick magnitudes, trail range, white-hot gain, hue spread |
