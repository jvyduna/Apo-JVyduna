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
- **Surfaces:** cube exterior ring, 200×45 via one preallocated
  `SurfaceCanvas(200, 45)` → `copyTo(Apotheneum.cube.exterior)`; interior
  faces mirror via `copyCubeExterior()`. The center cylinder (120×43, its own
  `SurfaceCanvas`) is dark except **Pulse bolts, which render there and only
  there** (2026-07-11; `copyCylinderExterior()` mirrors the interior).
- **Panoramic camera:** each ring column x is an azimuth ray
  `az = heading + 2π·x/200` from a camera at height `camY`. Rows map to
  elevation angles through the vertical FOV (`anglePerRow = vfov/45`); TopY
  places the horizon row, a Bank tilts it sinusoidally around the ring
  (`tilt·sin(az − heading)` — a camera roll seen panoramically).
- **Soft-knee row→elevation mapping (2026-07-11):** below the horizon the
  angle is linear (`rowOff·anglePerRow`) up to `PHI_KNEE = 60°`, then
  tanh-compressed to asymptote at `PHI_CAP = 88°`
  (`phi = knee + span·tanh((lin−knee)/span)`, span = cap−knee; inverse via
  artanh for the terrain march). No screen row can look past straight down,
  so the old **nadir clip** — a hard black band at
  `hRow + 90°/anglePerRow`, on-wall at high FOV with the horizon high — is
  unreachable at any knob combination; the flat path's `d ≤ 0` guard and the
  terrain path's nadir `minRow` clip are deleted. Exactly linear (current
  look) over most of the image; only the steep near field compresses.
  CURATE: the AA row-step term `fDdrowK` still assumes the linear mapping, so
  bottom rows in the compressed zone get slightly over-wide (softer) lines —
  multiply by the mapping derivative (sech²) if they read mushy.
- **Flat ground (Relief ~0):** closed-form reverse projection per pixel below
  the horizon, the outrun_grid.fs technique: `d = camY / tan(phiDown)`,
  world point `cam + d·(sin az, cos az)`, gridline proximity from the
  camera-anchored phase (below), quadratic depth fade to a FAR=28 haze. The
  capped mapping keeps `d` positive down to the bottom row.
- **Gridline analytic AA (2026-07-10):** the line test is fwidth-filtered, per
  world axis. A pixel's footprint on each axis is
  `|∂w/∂column| + |∂w/∂row|` in cell units (column step = `d·2π/200`; row step
  = `ddrow = (d²+camY²)/camY·anglePerRow`, flat-ground derivative also used as
  the terrain estimate). Effective half-width `effW = max(w, footprint/2)` so
  thin lines never fall between samples (stay **connected**); coverage
  `(1−dist/effW)²` with peak attenuated by `√(w/effW)` when sub-pixel (full
  energy conservation reads too dim on top of the fade² already applied);
  final `line = max(lineX, lineZ)`. Near field footprint < w ⇒ degenerates to
  the exact crisp test. This replaced the ad-hoc `×(1+1.5·d/FAR)` depth
  widening, which couldn't track the real (FOV/altitude/azimuth-dependent)
  footprint — the vertical footprint is ~1.6 cells/row at d=10 at mid FOV,
  ~17× the default half-width, which is why lines were invisible except near
  Glow max. Known artifact (accepted): beyond d≈7.5 where the z-footprint
  exceeds half a cell, a faint ground wash appears even at Fill 0 (~3:1 line
  contrast, bounded by fade² and the 0.015 cutoff). CURATE: if far lines still
  fade too early, floor the √ attenuation at ~0.3.
- **Terrain (Relief up):** per-column raymarch near→far over 64 exponentially
  spaced depths — the classic 80s wireframe terrain renderer. Heightfield is
  two-octave value noise on a shuffled lattice (Rough sets wavelength and
  octave mix, Relief sets **peak-to-peak** amplitude 0..3 world units,
  **centered**: `height = amp·(noise − 0.5)`, so Relief scales peaks up AND
  valleys down without raising the mean ground — the old [0,1] noise lifted
  the land by amp/2 as Relief rose). Painter's occlusion per column;
  consecutive samples joined by vertical pixel runs so slopes have no holes.
  A run top is lit as silhouette ridge **only at a true crest** — where the
  surface behind it dips ≥ `RIDGE_DIP_ROWS` (2) screen rows, or at the
  terrain's far edge against the sky. (Marking *every* run top — the original
  behavior — read as solid fill, not a silhouette: with 64 exponential
  samples over ~30 visible rows, mid/far runs are ~1 px tall, so nearly every
  terrain pixel was a "top" and lit at 0.55 the moment Relief > 0, regardless
  of Fill/Rough. Its lower boundary — where runs grow taller than 1 px —
  moved with pitch/altitude/amplitude and moiréd with the sample↔row
  quantization.)
- **Per-frame march start (2026-07-11):** `marchD` is rebuilt each terrain
  frame from `dStart = clamp(0.9·clearance/(tan(phiBottom) + 0.7·amp),
  0.008, 0.45)` — clearance = camY − terrain underfoot, phiBottom = the
  bottom row's mapped angle, and the `0.7·amp` slope term bounds the steepest
  possible noise hillside — so the nearest sample's **surface** always
  projects at/below the bottom LED row (verified by an adversarial sweep:
  worst case lands 1.9 rows below it). Previously the march started at a
  fixed NEAR=0.45 and every row below the nearest sample's row was painted
  from that single sample — a **frozen-vertical-stripe smear band** whose top
  edge (Jeff's "break 1") moved with Altitude/Relief/TopY/FOV. With the
  nadir clip ("break 2") also gone, neither artifact can appear above the
  bottom row at any knob combination. STEPS raised 64 → 96 for the wider
  exponential range. CURATE: check mid-field depth banding at low camY.
- **Upward row cap (2026-07-11):** a terrain sample's row offset above the
  horizon is soft-capped (linear to 12 rows, tanh-compressed to 24): the
  whole image occupies a fixed row band around hRow at every FOV. Uncapped,
  slope-limited peaks reach ~75 rows above the horizon at low FOV, which
  would make a guaranteed TopY-0 hide need an absurd dead zone. At the
  default horizon peaks can still overtop the panel; only towering
  close-canyon walls compress. CURATE: if big terrain reads flattened, raise
  UP_ROWS_KNEE/MAX and accept the wider TopY reveal zone.
- **Camera height (2026-07-11):** Altitude is measured above the **local
  terrain** (`camY = terrainHeight(cam) + altWorld`, exp 0.45..9.0 — ALT_MIN
  absorbs the retired CAM_CLEARANCE), low-passed at 0.4 s with a hard floor
  0.3 above the surface underfoot. The old `max(altWorld, terrain + 0.45)`
  made Altitude < ~0.4 a no-op whenever Relief was high; now the knob acts
  across its whole range at any Relief. In a deep valley the camera can sit
  below the plane — the AA row-step estimate keys off `|camY|` with a 0.25
  floor for that case.
- **Fill blend:** one continuous `Fill` knob (0..1) blends the three T-shirt/
  synthwave looks. Two derived quantities: the ground floor
  `fFloor = 0.55·Fill` lifts the whole ground from wireframe-on-black (0)
  through dim-filled 80s-CG ground (~0.5) to a continuous synthwave glow (1);
  the silhouette ridge weight `fRidge = 0.55` is **constant**. At Fill 1 the
  floor reaches the ridge level, so the ground catches up to the silhouette and
  it dissolves into an even glow — the ridge disappears by the ground
  *brightening to meet it*, not by being deleted. The earlier mapping faded the
  ridge to 0 with a 0.25 floor, which made raising Fill read as *less* fill:
  the bright 0.55 silhouette dominates the terrain (its pixel count is set by
  Rough, not Fill), so removing it darkened the image while the dim floor
  filled in. Holding the ridge fixed and letting the floor climb to it makes
  Fill monotonic and decouples the filled-ground look from terrain roughness.
- **Camera-anchored grid phase (2026-07-11):** the gridline test runs on
  camera-relative coordinates plus an **integrated** phase
  (`phase += Δcam/cell` each frame, wrapped to [0,1)), instead of
  `fract(worldPos/cell)`. Scrolling is identical at constant cell, but
  changing the Grid knob now rescales the lattice about the camera's ground
  point: the view center holds and lines enter/leave at the periphery,
  instead of every line sweeping past (origin-anchored rescale) — Grid is
  now automatable mid-render. Heading-independent, so it works on all four
  panorama faces at once.
- **TopY (2026-07-11, was Pitch):** direct horizon-height knob, 0..1 linear
  across `hRow = lin(topY, ROWS+24+4+2.5, −0.6)` (= 75.5..−0.6; the low
  endpoint clears the 24-row upward terrain cap, 4 rows of Bank tilt, and
  the 2.2-row glow band). At 0 the **whole** landscape — terrain peaks
  included — is guaranteed hidden below the bottom row and can be animated
  up from below ground; at 1 the band has just escaped above the top row.
  Knob ≲0.38 is the below-ground reveal zone (peaks emerge first), the
  yellow line itself crosses the panel over ~0.38..0.99. Default 0.785 =
  the old default row 15.75 (to 0.01 rows). No clamp, no dead zone (the old
  `0.35 + 0.8·pitch` mapping exited its 0.03..0.85 clamp below pitch ≈
  −0.44). CURATE: verify "barely gone" at both ends on hardware.
- **Glow / laser core (2026-07-11):** Glow 0..0.5 sweeps the full former
  width range (old Glow 1.0 look now at 0.5; default halved 0.4 → 0.2 so the
  default appearance is unchanged); 0.5..1 holds max width and raises
  `coreAmt` 0..1. The core is a second, narrower coverage test
  (half-width `lin(Core, 0.5, 0.15)·lineW`, falloff exponent
  `lin(Core, 1.5, 4)` — the **Core** knob makes it thinner and sharper as it
  rises) with the same footprint-AA/√-attenuation structure, which **lerps
  the pixel toward `lutCore`** — the LUT hue at full saturation/brightness —
  by `coreAmt · coverage · √fade`. √fade (vs the base fade²) lets the core
  outlive the base glow into the distance. CURATE: core width/exponent
  endpoints and the √fade choice are blind picks; verify the far field
  doesn't read as bright dots.
- **Color (2026-07-11, height-primary):** 64-entry gradient LUT built from
  **ALL colors of the active swatch** (up to MAX_SWATCH=16, non-wrapping; the
  earlier 3-slot reservation is retired for fine-grained palette control)
  when it has ≥ 2 colors, else a phosphor green→dim-cyan fallback; Hue knob
  offsets either. Rebuilt only when the swatch or Hue actually changes
  (Satori idiom). LUT position on terrain =
  `hw·heightPos + (1−hw)·depthPos` with `heightPos = 0.5 − h/RELIEF_AMP`
  (**absolute** world height: slot 1 = peaks, last slot = valleys, full
  palette span only at Relief 1), `depthPos = clamp(d/(0.6·FAR), 0, 1)`
  (GRADIENT_DEPTH compression lives in the index math, not inside the LUT),
  and height weight `hw = 0.7·min(1, amp/1.0)` **ramping in with
  amplitude**. Height is **camera-invariant**: pure depth indexing made the
  palette slide out from under the terrain as Altitude rose (nearby
  first-color ground vanished, the landscape recolored with camera height);
  now a given topographical height keeps a consistent color, while the
  residual depth share still pushes distant terrain deeper into the LUT —
  the dimmer horizon survives, and the horizon glow still reads the last
  color (`lut[63]`). The amplitude ramp (2026-07-11, round 2) is what makes
  Relief 0 → 0.01 seamless: the first cut normalized by h/amp (relative), so
  microscopic bumps spanned the whole palette and the flat→terrain switch
  recolored dramatically; with absolute height + ramped weight the formula
  degenerates to the flat pure-depth mapping as amp → 0. Flat ground stays
  pure depth (identical to the old flat behavior); the silhouette ridge is
  colored by its crest height.
  CURATE: LUT_DEPTH_MIX=0.3 and LUT_HEIGHT_RAMP_AMP=1.0 (full height weight
  from Relief ~0.33) blind picks; fallback endpoints (hsb 125→185, 95→80,
  100→55) chosen blind; GRADIENT_DEPTH=0.6 from the default-column sim,
  retune on hardware.
- **Buffers:** canvas, march-distance table, noise permutation, LUT, cached
  swatch — all preallocated in the constructor; zero allocation in the render
  path. Per-frame derived values live in `f*` fields so the per-pixel helpers
  stay argument-light.
- **Door columns:** cube face columns always carry the full 45 points; the
  `SurfaceCanvas.copyTo` length guard covers any variance.
- **Cost:** flat path ≤ 9,000 closed-form pixels; terrain path 200×96 march
  samples ≈ 19.2k noise evaluations (≤ 4 lattice lookups each; ×2 during a
  Regen morph), plus 96 pow for the per-frame marchD rebuild. Pulse bolts
  add ≤ 120×43 closed-form cylinder pixels × ≤8 exp each, only while bolts
  are in flight. Trivial at 60 fps. CURATE: check for depth-banding from
  STEPS=96 on near, steep terrain.

## Audio mapping

**None — the reserved `audio` knob was removed 2026-07-10** before any wiring
existed (verified: no .lxp references Outrun). If audio ever returns, the v2
candidates were: bass → transient Boost-style speed lifts, level → horizon
glow, treble → gridline sparkle, bassHit → Pulse.

## Tempo mapping

Every emphasis event plays out over **exactly one `TempoDiv` period**, captured
at trigger time via `TempoLock.divisionMs(TempoDiv)` (floored at
`MIN_EVENT_SEC = 0.15 s`) and applied as the event's progress rate. There is no
Sync toggle and no free-running mode — the tempo grid is always the clock.

- **Pulse**: bolt travels underfoot → horizon in one Div period. Up to 8
  bolts fly simultaneously; each bakes its rate from the TempoDiv period **at
  its own launch** (later TempoDiv/tempo changes don't affect bolts already
  in flight). With all 8 in flight, a new trigger steals the bolt nearest the
  horizon.
- **Bank**: the 90° yaw smooth-step and sinusoidal horizon roll complete in one
  Div period. CURATE: at the QUARTER default (~0.5 s @120 BPM) this is a fast
  snap, not a sustained pan; raise TempoDiv for a slow banked turn.
- **Boost**: the speed surge envelope `boostEnv = (1−t)²` (instant attack,
  quadratic decay) runs its `t: 0→1` over one Div period.
- **Regen**: the terrain height crossfade completes in one Div period.

## Energy mapping

**The `energy` knob was removed 2026-07-10.** Its speed multiplier (0.7..1.3×)
is folded into Speed's own range; everything else it drove was effectively
brightness, which the channel fader already covers. Fixed values chosen:
line/ground intensity gain = 1.0 (removed), pulse bolt amplitude = 1.0,
horizon glow = 0.35 (CURATE: between the old ambient 0.18 / peak 0.5 — retune
on hardware).

## Parameters

UI/registration order convention (do not deviate; existing keys/labels must
never be renamed — saved .lxp files reference them). Three sanctioned
exceptions: 2026-07-07 dropped `sync`/`meta` and changed `fill` from an enum
to a 0..1 CompoundParameter; 2026-07-10 dropped `energy` and `audio`
(verified: no .lxp anywhere referenced Outrun at the time); 2026-07-11
renamed `pitch` → `topY` with a new 0..1 mapping and added `core`
(Jeff-approved; **Bliss.lxp's saved `pitch` value / any clip lanes targeting
it drop on next load — re-set TopY and re-save**). Treat the keys below as
frozen from here on.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `boost` | Boost | TriggerParameter | — | — | afterburner: speed ×(1+1.6·env), env=(1−t)² over one TempoDiv |
| `bank` | Bank | TriggerParameter | — | — | banked 90° turn over one TempoDiv, horizon tilts up to 4 rows |
| `regen` | Regen | TriggerParameter | — | — | crossfade to fresh terrain over one TempoDiv |
| `pulse` | Pulse | TriggerParameter | — | — | cylinder-only bolt races underfoot → horizon over one TempoDiv; ≤8 in flight, speeds baked at launch, overlaps ADD |
| `speed` | Speed | CompoundParameter | 0.4 | 0..1 | flight speed, exp 0.1..3.25 **cells**/s (world = knob × cell size; Grid no longer changes perceived speed) |
| `altitude` | Altitude | CompoundParameter | 0.5 | 0..1 | camera height above LOCAL terrain, exp 0.45..9.0 units (low-passed 0.4 s) |
| `topY` | TopY | CompoundParameter | 0.785 | 0..1 | horizon row: lin 75.5..−0.6 — full hide below ground (0) to band-off-the-top (1); default = old row 15.75 |
| `fov` | FOV | CompoundParameter | 0.5 | 0..1 | vertical FOV, lin 38°..110° |
| `heading` | Heading | CompoundParameter | 0 | 0..1 | yaw offset 0..360°, wraps around the ring |
| `gridSize` | Grid | CompoundParameter | 0.5 | 0..1 | cell size, exp 2.4..0.45 units (knob up = denser; rescales about the camera) |
| `glow` | Glow | CompoundParameter | 0.2 | 0..1 | 0..0.5: line half-width lin 0.05..0.16 cells (old full range, so default 0.2 ≡ old 0.4); 0.5..1: laser core amount |
| `core` | Core | CompoundParameter | 0.5 | 0..1 | laser core shape: width frac 0.5→0.15 of lineW, falloff exponent 1.5→4 (thinner+sharper up) |
| `relief` | Relief | CompoundParameter | 0.35 | 0..1 | terrain pk-pk amplitude ×3.0 units, centered ±half; ≤0.03 amp uses the flat fast path |
| `rough` | Rough | CompoundParameter | 0.4 | 0..1 | noise wavelength exp 14..4.5 units; 2nd octave weight 0.35·rough |
| `fill` | Fill | CompoundParameter | 0 | 0..1 | topography fill blend: wireframe (0) → dim-filled (0.5) → glow (1) |
| `hue` | Hue | CompoundParameter | 0 | 0..360 | hue offset (degrees) on the depth gradient |
| `tempoDiv` | TempoDiv | EnumParameter\<Tempo.Division\> | QUARTER | — | period each emphasis event (Boost/Bank/Pulse/Regen) plays out over |

## Triggers

All four play out over one `TempoDiv` period (see Tempo mapping).

- `boost` — small: the world accelerates for a breath (env=(1−t)², instant
  attack) and eases back; nothing else changes.
- `pulse` — mid (2026-07-11: moved to the **center cylinder only**; the cube
  no longer flashes): a bright gaussian band of ground races from underfoot
  to the horizon around the full cylinder. Up to **8 bolts** fly at once,
  each at the speed baked from the TempoDiv period at its own launch; a 9th
  trigger steals the bolt nearest the horizon. Overlapping bolts **ADD**
  (gaussians summed per pixel before the clamp — exact additive blending
  since they share the pixel's LUT color). Bolts are projected with the same
  camera (camY/TopY/FOV/Bank tilt/heading) and depth LUT/fade as the cube,
  but on the flat ground plane — no gridlines, fill, or terrain occlusion
  (a bolt is a fast transient, not topography). Cylinder rows map 1:1 to
  cube rows (43 vs 45, row 0 = top). CURATE: verify the 1:1 row mapping
  reads as aligned with the cube horizon on hardware, and that a stolen
  bolt's pop isn't visible under rapid retriggers.
- `bank` — mid/large: heading swings 90° (random direction) while the horizon
  rolls up to 4 rows and settles; the whole panorama slews a quarter turn.
  One-turn-at-a-time (retrigger ignored until it settles).
- `regen` — large: the entire landscape morphs into fresh topography (no cut;
  heights crossfade). CURATE: retriggering mid-morph snaps the old field to the
  previous target before starting the new morph — verify the pop is acceptable
  under rapid retriggers.

## Simulation-principles compliance

- **Panning:** Bank is the only view rotation and is now an **event**, not a
  sustained pan — its 90° swing completes in one `TempoDiv` period. At the
  QUARTER default this is a deliberate fast slew (per the tempo-lock design),
  so the ≥20 s/rev sustained-pan cap no longer applies. CURATE: at fast BPM /
  small divisions the snap can read as a hard cut; raise TempoDiv (HALF/WHOLE/
  bars) for a slow banked turn, and verify the snap isn't jarring on hardware.
- **Forward flight (sustained):** speed = exp(knob, 0.1, 3.25) **cells/s**,
  cell-relative (world speed = knob value × cell size), so the Grid knob
  changes density without changing the perceived line-crossing rate. Default
  knob 0.4 ⇒ 0.40 cells/s ⇒ ~2.5 s per cell at the screen bottom — matches
  the old default feel (0.404 cells/s) with the retired Energy multiplier
  folded into the range. Worst sustained case (knob 1): 3.25 cells/s ⇒ 0.31 s
  per cell — and unlike the old world-units mapping, this cap now holds at
  every grid density (old worst case at the densest grid was ~7.2 cells/s).
  CURATE: the knob's top end is intended for brief chase moments; verify it
  doesn't strobe on the sculpture and re-range SPEED_MAX down if it does.
  Boost adds ×2.6 but is an event envelope spent in one TempoDiv period.
- **Pulse (event):** one TempoDiv period, floored at MIN_EVENT_SEC=0.15 s.
  CURATE: at small divisions the bolt is brief — verify it still reads.
- **Bold posterized forms:** hard horizon line, quadratic depth fade to black
  (no shimmer at the far clip), gridline half-width 0.094 cells at default
  Glow with analytic per-axis AA (footprint-widened coverage, √ peak
  attenuation) so thin lines stay connected at every depth; terrain features
  ≥ ~8.9 units wide at default Rough (≈ 8+ cells); silhouette ridge at 0.55
  keeps the topography readable at distance. The old horizon-moiré CURATE
  item is resolved by the AA (footprint-widened lines merge smoothly).
  CURATE (new, all on-hardware checks): faint mid/far ground wash at Fill 0
  from footprint-widened cross-lines; optional √-attenuation floor ~0.3 if
  far lines fade too early; Alt 1 map view dimness against the fixed FAR=28
  fade (consider scaling FAR with camY); VFOV_MAX 110° bottom-half sparsity
  (revisit 90° if it still reads too empty); HORIZON_GLOW fixed 0.35.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-06 | Initial design & implementation | Plan: 80s aircraft-tee grid + TE outrun_grid.fs port, 360° panorama, cube only, palette-driven, no audio v1 |
| 2026-07-07 | Fill → continuous blend; remove Sync + Meta; all emphasis events run one TempoDiv period; compress palette into visible depth band | Feedback: WIRE≈FILLED looked identical (dim 0.1 floor crushed by fade); wanted one blend knob, everything beat-locked, fewer controls, and the far swatch colors were unseen |
| 2026-07-10 | Fill made monotonic: floor ramps 0→0.55 (was 0→0.25), ridge held constant at 0.55 (was faded to 0 at Fill 1) | Feedback: raising Fill *reduced* the green fill and Fill 0/0.5 looked alike; the bright silhouette ridge (count driven by Rough, not Fill) dominated, so fading it darkened the image. Floor now climbs to meet the ridge so glow dissolves it by brightening, not deleting — and Fill, not Rough, drives the filled look |
| 2026-07-10 | Ridge = crest detection (dip ≥ 2 rows or sky edge), not every run top; terrain runs clipped at nadir; flat path guards d ≤ 0 | Feedback: terrain filled solid at any Fill/Rough the moment Relief > 0 (~every mid/far march-run top is 1 px, so "ridge" was a fill — root cause behind both earlier Fill complaints); near-field run smear "turned straight down the wall" past the nadir; lut[-1] AIOOBE at high FOV + pitch down from negative flat-path depth |
| 2026-07-10 | Analytic-AA gridlines (per-axis fwidth footprint, effW = max(w, fp/2), √ peak attenuation); LINE_W_MIN 0.05; dropped the ×(1+1.5·d/FAR) depth widening | Feedback: gridlines invisible except near Glow max — point-sampling aliasing (vertical footprint ≈1.6 cells/row at d=10 vs 0.03..0.09-cell half-widths); also explains the FOV>0.75 empty bottom (wide-angle magnification + invisible sparse lines; geometry itself is unavoidable, VFOV_MAX kept at 110 per Jeff) |
| 2026-07-10 | Removed Energy + Audio params; Speed cell-relative exp 0.1..3.25 cells/s; ALT_MAX 4.5→9.0; horizon glow fixed 0.35; palette scoped to first 3 swatch slots | Feedback: Grid density changed perceived speed (now decoupled — Speed is cells/s); Energy minus its speed component was just brightness (channel fader covers that), speed range folded into Speed; Audio never wired; more dark sky above the terrain at high altitude; swatch slots 4+ reserved for other composition elements |
| 2026-07-11 | Param-math rework: soft-knee row→elevation mapping (nadir clip unreachable); per-frame slope-aware march start + STEPS 96 (near-smear band gone); 24-row upward terrain cap; Altitude above local terrain (exp 0.45..9, low-passed 0.4 s); centered terrain noise; camera-anchored grid phase; `pitch` → `topY` 0..1 direct horizon height (75.5..−0.6, default 0.785); Glow remap (old max at 0.5, default 0.4→0.2, 0.5..1 = laser core) + new `core` knob + full-sat/bright `lutCore`. All projection invariants verified by an adversarial parameter-sweep harness | Feedback: two projection breaks (nadir black band + frozen-stripe near smear) could ride up the walls with Altitude/Pitch/FOV/Relief — both now pinned below the bottom LED row at every combo; Altitude was a no-op below ~0.4 at high Relief; Relief raised the whole landscape instead of just peak-to-valley; small Grid changes swept every line past (unautomatable); wanted horizon as a direct Y knob that can hide the pattern below ground / push the band off the top (the upward cap is what makes the full hide guaranteeable); wanted Glow's top half to add a laser-bright core beyond the LUT |
| 2026-07-11 | Pulse → cylinder-only multi-bolt: up to 8 simultaneous bolts, per-bolt rate baked from the TempoDiv period at launch, overlaps ADD (summed gaussians), slot steal = bolt nearest the horizon; cube no longer renders the pulse | Feedback: wanted up to 8 simultaneous pulses with launch-baked speeds, following the projection/palette settings, rendered only on the center cylinder, ADD on overlap |
| 2026-07-11 | Height-primary LUT: terrain palette position = 0.7·(0.5 − h/amp) + 0.3·depth (flat stays pure depth); full swatch used (3-slot reservation retired, cap 16); GRADIENT_DEPTH compression moved from LUT construction into the index math; ridge colored by crest height | Feedback: climbing made the terrain "fall out" of the LUT (first palette color lost entirely, landscape recoloring with Altitude) — height is camera-invariant so a given topographical height keeps its color; keep some depth push for the dimmer horizon; wanted all swatch colors for fine-grained control |
| 2026-07-11 | LUT height term made absolute (h/RELIEF_AMP, not h/amp) with the height weight ramping in with amplitude (hw = 0.7·min(1, amp/1.0)) | Feedback: Relief 0 → 0.01 caused a dramatic sudden recolor — relative normalization let microscopic bumps span the whole palette, and the flat→terrain path switch jumped mappings; now the formula degenerates smoothly to the flat pure-depth mapping as amplitude → 0 |
