# Terraform

Evolving terrain skyline: tempo-driven eruptions rise and fall on a division
grid, conservation-of-mass water couples to the land, and elevation color bands
render over a settable sea. Audio drives only the treble crest-flash
(2026-07-12 redesign).

> Sidecar design doc convention: this file lives beside `Terraform.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

**Designer's-choice interpretation — no confirmed original screensaver.** The name
evokes the terrain-generation / "terraforming" family of demos and screensavers,
but this design was not traced to a specific verified original. The visual
signature being built: a wrap-around mountain skyline in elevation color bands
over a sea that rises and falls as the land displaces it. **The base is always
clear of terrain** — there is no persistent landscape and none is seeded at
startup; every mountain is born by a **tempo-driven eruption** that rises then
fully erodes over a musical lifetime, then vanishes. The sea rests at a settable
`SeaLevel` and is pushed/pulled around it by the land (conservation of mass).
Choreography is via the `TrigDiv`/`Chance`/`Phase` eruption grid, `SeaLevel`
automation, and the `Erupt`/`Cataclysm`/`Reseed` triggers — all of which act
through the same transient overlay, so the world always returns to clear.
`Smooth` antialiases the forms and glides the ridgeline crest.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the pattern renders per-column stacks
  directly from a 1D heightfield; no 2D canvas needed (every pixel's color is a
  pure function of its column's height + its elevation, so there is nothing to
  persist or blit).
- **Surfaces**: cube exterior (200-column × 45-row wrap-around ring) and cylinder
  exterior (120 × 43) each carry an **independent heightfield**; the **sea level
  is one shared fraction** of surface height, so the ocean reads as a single
  world across both chambers. Interiors are copied from the exteriors via
  `copyCubeExterior()` / `copyCylinderExterior()`.
- **Heightfields**: per surface, the base `height[]` **stays flat (all zero)** —
  there is no persistent landscape and no `target[]`/rate-limited chase anymore
  (removed 2026-07-13). Every mountain rides on top as a transient additive
  `overlay[]` (Erupt / TrigDiv / Cataclysm / Reseed), so the base is always clear.
  `height[]` is retained only as the zero base the breach calc and renderer index
  uniformly. A slowly-drifting `roughField[]` and a motion-smoothed
  `smoothHeight[]` are also per-surface. All arrays, the mountain pools, and the
  `Random` are allocated in the constructor — **zero allocation in the render
  path**.
- **Mountain lifecycle overlay**: each eruption is a rise-then-fall envelope
  event (fixed pool of `MAX_UPLIFTS` = 16 per surface) rendered as an additive
  overlay on `height[]`. Total life = `MtnLife`; the envelope rises
  (smoothstep 0→1) over the `Duty` fraction, then falls (1→0) over the
  remainder, and at end of life the slot frees with **no commit** to the
  terrain — the mountain fully vanishes. Pool overflow steals the
  most-progressed slot and simply frees it (no terrain pop is possible, since
  nothing commits). A slot's `center` is a `double` and can **glide** to a new
  column over a `1/4` bar (`Reseed`, shortest wrap path); otherwise it rests.
- **Per-pixel rule** (bottom-up, `elev` = rows above the physical ground,
  computed as `fullHeight - 1 - yi`; see door-column handling below):
  - `elev ≤ sea` → water; the single top water row is the bright **waterline**
    accent. Water draws in front of submerged land, so the waterline is one
    continuous ring at the sea surface.
  - `sea < elev ≤ crest` → land, colored by **circular elevation band** (see
    Bands).
  - above the crest → sky (black).
  - **Edge smoothing** (`Smooth`): the band-to-band edges, the **land/sky
    silhouette** at the crest, and the sea surface are all antialiased with an
    `edge()`/smoothstep of half-width `hw = Smooth · SMOOTH_MAX_ROWS` (1.25
    rows). At `Smooth = 0` every edge collapses to a hard step. Zero allocation.
- **Door-column handling**: every cube/cylinder column carries the full point
  count (the `Apotheneum` orientation constructors enforce 45/43 points per
  column); door cutouts are masked globally by the core doors effect, not by
  shorter columns. `points[0]` is the **top** row, so elevation is computed as
  `fullHeight - 1 - yi`, which keeps the sea surface and band boundaries aligned
  across all columns.

## Bands — elevation color count + circular phase (2026-07-12)

`Bands` (0–5) is a **color count**: `M = max(1, Bands)` equal-height color bands
ground→peak. `Bands` 0/1 render monochrome (`bandColor[0]`); 2–5 subdivide the
wall into that many bands.

- **Colors** (`computeBandColors()`, recomputed once per frame, Rubik/Satori
  idiom): the first `min(M, swatch)` bands take colors straight from the project
  palette swatch (`lx.engine.palette.swatch.colors`), anchored at their
  perceptual hue positions (`PerceptualHue.toPerceptualPosition`); any remaining
  bands are generated by `PerceptualHue.fillCircle` (greedy bisect-the-largest
  hue gap) and colored via `PerceptualHue.color` (fully saturated).
  CURATE: generated fills are 100 %-saturation spectral hues — check terrain
  still reads as terrain on sparse swatches.
- **WhtCps** forces the **highest** band (index `M-1`) pure white (snow cap)
  **without** discarding a swatch color or shifting the others. (Different from
  the old behavior, which whitened band 0.) The treble flash then crackles the
  peaks dark for contrast (white-on-white would be invisible).
- **Render lookup**: for a pixel at elevation `elev`, `u = elev / fullHeight`
  ∈ [0,1]; `pos = frac(u + BndPhase)` (circular); band index `= floor(pos · M)`.
  `BndPhase` (0–1) rotates the gradient up the wall; because the bands are
  circular, **phase 0 = phase 1** and band `M-1` wraps into band 0, so the top
  and bottom of the wall can share a color across the wrap.
- **Band AA**: near a band boundary the color lerps toward the adjacent band
  (circularly) over ±`hw` rows. The blend is computed from the distance in rows
  to the nearest boundary and is symmetric/continuous across the seam (0.5
  weight exactly at the boundary). `M = 1` is monochrome (no band edges).

The old fixed `SAND_TOP/GRASS_TOP/ROCK_TOP` thresholds and the `bandShift` knob
are retired.

## Smooth — edge AA + crest motion (2026-07-12)

`Smooth` (0–1, default 0.3) does two things, both collapsing to hard/instant at
0:

1. **Edge antialiasing** — band-to-band edges, the land/sky silhouette at the
   crest, and the sea surface are smoothstepped over ±`hw = Smooth · 1.25` rows.
2. **Crest motion smoothing** — the displayed height per column
   (`base + eruption overlay + roughness + shake`) is low-passed into
   `smoothHeight[]` each frame with
   `alpha = clamp(1 − Smooth · CREST_MOTION_MAX, 0.02, 1)` (`CREST_MOTION_MAX`
   = 0.85). At `Smooth = 0`, `alpha = 1` (instant, hard-stepping crest); at
   `Smooth = 1`, `alpha ≈ 0.15` (a slow glide). `smoothHeight[]` starts at zero
   (the flat base), matching the cleared start, so there is no first-frame ramp.
   CURATE: a simple per-frame lerp (not frame-rate-normalized) — verify the
   glide reads well across frame rates and BPMs.

## Colors — palette-driven land bands, fixed sea

The land bands take their colors from the current palette swatch (above); the
sea and sky stay fixed (water must read as water for the flood/drown metaphor to
survive arbitrary palettes).

| Element | Source | Notes |
|---|---|---|
| Sky | black (fixed) | negative space; skyline silhouette |
| Deep water | 215°, 85 %, 40 % (fixed) | dim saturated blue, recedes |
| Waterline | 190°, 35 %, 100 % (fixed) | 1-row bright pale cyan accent |
| Land bands | palette swatch (perceptual fill) | `M = max(1, Bands)` circular bands; `WhtCps` whitens the top band |

## Water — conservation of mass (2026-07-12)

A "fish tank half full of mud": land rising **out** of the sea pulls water
**down**; land eroding **into** the sea lets water **rise**; at rest the sea
settles back to `SeaLevel`.

- Each frame compute `aboveVol` = normalized land volume above the **SeaLevel
  reference** (the knob, not the live water — avoiding a feedback loop):
  `Σ max(0, (height + overlay) − SeaLevel·H)` over both surfaces, divided by the
  total ring area `(cubeW·H_cube + cylW·H_cyl)`.
- Signed integrator `landWater` (0 at rest):
  - `dVol = clamp(aboveVol − prevAboveVol, ±LAND_WATER_DVOL_CLAMP)` (0.05 clamp
    so a cataclysm/reseed can't spike it),
  - `landWater −= LAND_WATER_GAIN · dVol` (rise ⇒ Δ>0 ⇒ water down; erosion ⇒
    Δ<0 ⇒ water up),
  - `landWater −= landWater · min(dt/LAND_WATER_TAU, 1)` (decay to 0 at rest).
  - CURATE: `LAND_WATER_GAIN` = 6, `LAND_WATER_TAU` = 8 s — chosen so a default
    eruption clearly dips then swells the sea, settling in a few seconds; verify
    magnitude and that it reads as a tide, not a lag.
- Final water fraction (shared by both surfaces):
  `waterFrac = constrain(SeaLevel + LndWtr · env(SeaLevel) · landWater, 0, 1)`
  with `env(s) = 4·s·(1−s)` (0 at s=0 and s=1, peak 1 at s=0.5). This
  guarantees: **SeaLevel 0 ⇒ no water ever**, **SeaLevel 1 ⇒ full**, strongest
  coupling at mid, always within [0,1]. At init `landWater = 0`, `prevAboveVol`
  is captured from the flat (empty) terrain (so it is 0), and
  `seaFrac = SeaLevel` — no start-up jump; the sea simply rests at `SeaLevel`
  over clear ground.

The old `silt` accumulator, `seaGoal()`, the one-beat sea chase, and the entire
`Flood` state machine are removed.

## Roughness (2026-07-12)

Without diffusion to clean up accumulated jitter, `Rough` is no longer injected
into `target[]`. Instead a preallocated per-column `roughField[]` (in [−1, 1])
**drifts slowly**: each frame `ROUGH_RETARGET_PER_FRAME` (1) random columns get a
fresh random target and the whole field eases toward its target by `ROUGH_EASE`
(0.04). `rough · ROUGH_AMP_FRAC · fullHeight · roughField[x]` (`ROUGH_AMP_FRAC`
= 0.06, ~±2.7 rows on the cube) is added to the **displayed** height before the
crest motion low-pass. **Land-gated (2026-07-13)**: the roughness contribution is
scaled by `roughGate = clamp(landRows / ROUGH_FADE_ROWS, 0, 1)` where
`landRows = height + overlay` and `ROUGH_FADE_ROWS = 3`, so cleared ground stays
perfectly flat (no craggy baseline) and eruptions fade in their texture over the
first few rows of rise instead of popping craggy at birth. Zero allocation.
CURATE: retarget rate / ease / amp / fade rows — does `Rough = 1` read as craggy,
not boiling or static, and does the fade-in read cleanly?

## Eruptions — TrigDiv × Chance × Phase (2026-07-12)

Ambient eruptions are the sole tempo-driven births (no audio- or timer-driven
births).

- A `TempoLock tempoLock` (field + ctor) is reintroduced for `divisionMs`.
- Each frame, `basis = tempo.getBasis(TrigDiv)` is polled **unconditionally**.
  A phase crossing fires when the 0..1 sawtooth passes `Phase` going from the
  previous basis to the current one (wrap-aware; see `crossedPhase`). The first
  frame (`prevBasis` NaN) and any frame where `TrigDiv` changed are resynced
  without firing.
- On a firing frame, an eruption spawns on **each** surface iff
  `random.nextDouble() < Chance`.
- Every eruption (ambient **and** the manual `Erupt`) is **breach-forced**:
  amplitude is raised to at least `seaRows + ERUPT_MARGIN_ROWS − height[center]`
  (`ERUPT_MARGIN_ROWS` = 3), so a new peak always clears the current sea and is
  reliably visible.

### Mountain lifecycle

Per pool slot: `center, ampRows, sigmaCols, elapsedMs, lifeMs (≤0 = free),
dutyFrac`. On spawn `lifeMs = max(50 ms, tempoLock.divisionMs(MtnLife))`,
`dutyFrac = clamp(Duty, 0.01, 0.99)`, `ampRows = Uplift · UPLIFT_AMP_FRAC · H`
(0.6·H) then breach-forced, `sigmaCols = max(2, UPLIFT_SIGMA_FRAC · W)` (0.03·W).
Progress `p = elapsedMs/lifeMs`; envelope `e = smoothstep(p/Duty)` while
`p < Duty`, else `smoothstep(1 − (p−Duty)/(1−Duty))`. At `p ≥ 1` the slot frees
with no commit. The eased overlay `e · ampRows` is added via the wrap-aware
Gaussian each frame (overlay rebuilt to 0 first).

## Audio mapping

All reactivity is gated by the **Audio depth knob** (`audio`, default **0**),
attached via `AudioReactive.setDepth(audioDepth)`. Audio now drives **only** the
treble crest-flash — no births, no sea motion, no regime steering.

| Tap | Drives |
|---|---|
| `trebleHit()` | `TrbSprk` white flash burst: the flash envelope snaps to `depth()` (the AudioReactive scaled-hit contract) and decays (τ = 45 ms); a fresh random ≤ 45 %·TrbSprk subset of the top `FLASH_CREST_ROWS` (4) rows of each mountain lerps toward white (toward black when `WhtCps` is on). |

**Depth-0 baseline (default)**: no flash; eruptions still fire on the `TrigDiv`
grid, the water still couples to the land, and the sea rests at `SeaLevel` — a
calm, tempo-locked, self-evolving archipelago that looks good with zero audio.
CURATE: flash decay 45 ms / coverage 0.45 / 4-row crest reach — must read as
glinting peaks, not noise, at distance.

## Tempo mapping

- **Eruption grid**: `TrigDiv` (an always-on `EnumParameter<Tempo.Division>`,
  default `WHOLE` = one bar) sets the division whose phase crossings offer an
  eruption; `Phase` offsets the fire point within the cycle; `Chance` gates it.
- **Mountain life**: `MtnLife` (default `FOUR` = four bars) sets each eruption's
  total rise+fall duration via `tempoLock.divisionMs`.
- The lone always-on division enum is the current idiom (Cathedral / Outrun /
  Mountains / Pipes3D); the retired `Sync` + `TempoDiv` pair is not used.

## Parameters

UI order: triggers first, then pattern parameters, Audio last. Chromatik's
default auto-generated control panel (no custom `UIDeviceControls`).

| Param (key) | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `cataclysm` | Cataclysm | TriggerParameter | — | — | transient 1/4-bar ridge + shake, then a 4-eruption burst; no lasting terrain |
| `reseed` | Reseed | TriggerParameter | — | — | glide all active mountains to new columns over 1/4 bar; no new terrain |
| `erupt` | Erupt | TriggerParameter | — | — | raise one new mountain now on each surface, breaching the sea |
| `triggerDiv` | TrigDiv | EnumParameter\<Tempo.Division\> | WHOLE | — | division whose phase crossings offer an eruption |
| `chance` | Chance | CompoundParameter | 0.5 | 0..1 | P(eruption) per TrigDiv opportunity |
| `mtnLife` | MtnLife | EnumParameter\<Tempo.Division\> | FOUR | — | total rise+fall lifetime of each eruption |
| `eruptDuty` | Duty | CompoundParameter | 0.3 | 0..1 | fraction of life spent rising |
| `trigPhase` | Phase | CompoundParameter | 0 | 0..1 | phase offset within the TrigDiv cycle |
| `upliftSize` | Uplift | CompoundParameter | 0.5 | 0..1 | eruption amplitude (before breach forcing) |
| `rough` | Rough | CompoundParameter | 0.25 | 0..1 | terrain ruggedness: slowly-drifting per-column noise |
| `seaLevel` | SeaLevel | CompoundParameter | 0.5 | **0..1** | resting sea level; 0 = dry, 1 = fully flooded |
| `lndWtr` | LndWtr | CompoundParameter | 0.5 | 0..1 | land→water coupling depth (strongest at mid SeaLevel) |
| `bands` | Bands | DiscreteParameter | 4 | 0..5 | count of equal elevation color bands |
| `bndPhase` | BndPhase | CompoundParameter | 0 | 0..1 | circular phase shift of the color bands |
| `smoothing` | Smooth | CompoundParameter | 0.3 | 0..1 | edge AA + crest motion smoothing; 0 = hard/instant |
| `whiteCaps` | WhtCps | BooleanParameter | false | — | force the highest band white (snow cap) |
| `trbSprk` | TrbSprk | CompoundParameter | 0.5 | 0..1 | treble-hit white flash coverage on the crests |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio depth: 0 = pure screensaver, 1 = full flash |

**Removed 2026-07-12**: `flood` (trigger), `erosion`, `water`, `bandShift`.
Saved `.lxp` references to those keys — and to any earlier removed keys — are
dropped silently on load (accepted; the pattern is in active curation).

## Triggers

- `erupt` (small) — books one breach-forced eruption on each surface (random
  center); the crest always emerges ≥ `ERUPT_MARGIN_ROWS` (3) rows above the sea
  and lives out a full `MtnLife` rise/fall like an ambient one.
- `cataclysm` (2026-07-13 redesign) — a **transient** event that leaves no
  lasting terrain. It books a mountain-range ridge (0.85 H central peak + two
  0.55 H shoulders, σ = 5 % of ring) as **overlay** mountains with
  `life = 1/4 bar` and `duty = 0.5`, so the ridge rises then falls within the
  quarter, plus the shake (a spatial sine ripple, ±2.5 rows, 7 Hz, decaying over
  the same `1/4` bar). When the ridge finishes, it **bursts 4 concurrent
  eruptions** on each surface (`spawnEruptions(4)` via a `cataclysmEruptMs`
  countdown), which then live out and erode as normal eruptions. Durations come
  from `tempoLock.divisionMs(QUARTER)` measured from the trigger instant — tempo-
  following, not grid-locked. CURATE: shake amp 2.5 rows / 7 Hz / 0.6
  rad-per-column and the 4-eruption burst count unverified — must read as a
  tremor + aftershock swarm.
- `reseed` (2026-07-13 redesign) — **no new or persistent terrain**. It glides
  every currently-active mountain from its present column to a fresh random one
  over a `1/4` bar (`reseedGlide`; per-slot `centerFrom→centerTo` over
  `centerAnimDur`, smoothstepped along the shortest wrap path), leaving each
  mountain's amplitude and life phase untouched. A no-op when nothing is active.
  Duration from `divisionMs(QUARTER)`, tempo-following, not grid-locked.

## Simulation-principles compliance

- **No persistent terrain**: the base is always flat; there is no whole-sculpture
  heightfield to rate-limit. All terrain is localized event-class motion (the
  eruption overlay), which is the documented exception below. ✓
- **Documented exception — eruption overlay**: every mountain (Erupt / TrigDiv /
  Cataclysm ridge / Reseed glide) is a localized additive overlay (σ ≤ ~6 of 200
  columns) with a bounded rise-then-fall lifetime, so a fast `MtnLife` or a
  `1/4`-bar ridge can move a local crest quickly. Localized event-class motion,
  same category as the cataclysm shake; nothing accumulates into a persistent
  landscape. ✓
- **Water motion**: the sea is a slow signed integrator (τ = 8 s, per-frame Δ
  clamped) around `SeaLevel`, always within [0,1]. Localized-in-time, bounded. ✓
- **Contrast/brightness**: bold color bands, black sky for silhouette, a dim sea
  vs. a single full-bright waterline row. Sub-band detail: roughness drift
  (±2.7 rows) and the treble crest-flash. `Smooth` softens as desired. ✓

Other unverified constants: CURATE: `SMOOTH_MAX_ROWS` 1.25, `CREST_MOTION_MAX`
0.85, `ERUPT_MARGIN_ROWS` 3, `UPLIFT_AMP_FRAC` 0.6, `UPLIFT_SIGMA_FRAC` 0.03,
`ROUGH_FADE_ROWS` 3, `MAX_UPLIFTS` 16 (enough overlap headroom for a long MtnLife
over a short TrigDiv, plus a 3-bump cataclysm ridge + 4-eruption burst?), the
cataclysm ridge amps (0.85 / 0.55 H) and the 1/4-bar event durations.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | as planned; no visual verification yet |
| 2026-07-05 | Review + upgrade session (audio depth knob; sync/tempoDiv; erupt trigger; shared uplift recipe; erosion re-range; door-column claim corrected) | audio-depth / tempo-sync conventions; doc/comment drift only |
| 2026-07-05 | Integration pass: `crossed()` polled unconditionally every frame | series idiom; no spurious re-enable birth |
| 2026-07-06 | Curation pass (Erosion re-ranged 0..10 + sub-stepping; Energy removed → `drive`; palette land bands + WhtCps; tempo-quantized uplift envelopes; per-beat sea + silt; `Rough`; Sparkle → TrbSprk) | Jeff's curation notes |
| 2026-07-06 | Post-implementation review fixes (envelope-before-spawn ordering; depth-relative bass gate; flash amp = depth(); WhtCps+TrbSprk; pool-overflow steal; unified Gaussian kernel; silt ordering) | multi-angle code review |
| 2026-07-06 | Series RndTrig/Sync cleanup (`meta` → `rndTrig`; Sync removed, grid-locking always-on) | Jeff 2026-07-06 |
| 2026-07-07 | Feedback revision (audio decoupled from the sea; `seaBias` → `SeaLevel`; `Water` reservoir; `Smooth` edge AA; `Erupt` guaranteed breach; `Flood` rewritten to a beat envelope) | Jeff's feedback |
| 2026-07-08 | Removed Sync/TempoDiv/Meta + TriggerBag/TempoLock (convention retired; free-run = old Sync-off path) | Jeff 2026-07-08 |
| 2026-07-12 | **Redesign (no visual verification yet)**: eruptions are now tempo-driven via `TrigDiv × Chance × Phase` (unconditional basis-crossing poll, breach-forced spawns on both surfaces); the old free Poisson timer and all bass-driven births removed. Mountains now live a `MtnLife`/`Duty` **rise-then-fall lifecycle** overlay that fully vanishes (no terrain commit), replacing the diffusion/subsidence erosion machinery (`advanceTerrain` is now just the rate-limited chase). Water is a **signed conservation integrator** (`LndWtr`, `env = 4s(1−s)`, decay τ = 8 s), replacing `silt`/`seaGoal`/the Flood state machine; `SeaLevel` re-ranged 0..1 (0 = dry, 1 = full). `Bands` is now a **color count 0–5** with a **circular `BndPhase`**; `WhtCps` whitens the **top** band. `Smooth` now also **AA's the silhouette and motion-smooths the crest** (per-surface `smoothHeight[]` low-pass). `Rough` reworked to a drifting per-column noise field added at render. Audio drives **only** the treble crest-flash. **Removed params** `flood`/`erosion`/`water`/`bandShift`; their `.lxp` keys drop on load. `MAX_UPLIFTS` 8 → 16. Reintroduced `TempoLock` for `divisionMs`. | Jeff's approved redesign plan: fewer moving parts, predictable tempo-locked behavior, reliable `SeaLevel` bounds, richer palette control |
| 2026-07-13 | **Clear-base + transient triggers (no visual verification yet)**: startup no longer seeds a landscape — the base `height[]` stays flat and the persistent-terrain machinery (`target[]`, `advanceTerrain`, `seedTerrain`, `addRidge`/`addBump`, `RISE_FULL_SEC`) is removed, so terrain exists **only** as the transient eruption overlay. `Cataclysm` is now a 1/4-bar **overlay** ridge (life = quarter, duty 0.5) + a shake for the same duration, then a **4-eruption burst** (`cataclysmEruptMs` countdown, `spawnEruptions(4)`); it leaves no lasting terrain. `Reseed` now **glides** all active mountain centers to new columns over a 1/4 bar (`MountainPool.center` → `double` + `centerFrom/To/AnimMs/AnimDur`, `wrapLerp`), creating no new terrain. Roughness is **land-gated** (`ROUGH_FADE_ROWS` = 3) so cleared ground is flat. Booking refactored into `allocSlot` + `bookAt`; `spawnEruptions()` → `spawnEruptions(int n)`. Event durations use `divisionMs(QUARTER)` from the trigger instant (tempo-following, not grid-locked). | Jeff: base state should start/stay clear; only triggers create terrain, and Cataclysm/Reseed should be tempo-timed transient events, not permanent |
