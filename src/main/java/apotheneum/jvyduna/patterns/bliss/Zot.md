# Zot

After Dark "Zot!" lightning: bolts strike down the Apotheneum surfaces with a
stepped-leader draw-in (over StrkTime), a one-frame return-stroke flash, and a
glow decay (over FadeTime) — struck by hand, by a storm burst, or by an FFT
transient on a chosen bin-pair. Each strike is routed onto a load-balanced subset
of the 5 external + 5 internal surfaces; cylinder strikes wrap a random half-ring.
White bolts, or a random palette color per strike.

> Sidecar design doc convention: this file lives beside `Zot.java` and is the
> source of truth for design decisions and curation history. Constants or
> behaviors not visually verified are marked with inline `CURATE:` notes
> (grep for `CURATE:`).

## Original / inspiration

The "Zot!" module from Berkeley Systems' **After Dark** screensaver (Mac, ~1990):
sudden branching lightning bolts crackling down an otherwise black screen. This is
an interpretation, not a pixel recreation — the original was a single instant
flash; at sculpture scale each bolt here is given a full envelope (leader →
return stroke → glow) so the branching form has time to register.

## Surfaces & routing

Zot extends **`ApotheneumPattern`** (not the cube-only `ApotheneumRasterPattern`)
so it can reach the cylinder and route different strikes to different surfaces.

**The 10 surfaces** (flat ids in parentheses; `Zot.java` `allSurfaces`):

- **External (5)**: cube exterior front/right/back/left (0–3) + cylinder exterior (4).
- **Internal (5)**: cube interior front/right/back/left (5–8) + cylinder interior (9).

Each `Surface` owns a preallocated `SurfaceCanvas` (50×45 for a cube face, 120×43
for a cylinder), its `Apotheneum.Surface` target (bound lazily once the model is
loaded), and an `int activeCount` of strikes currently displayed on it.

**Per-strike routing** — a strike lights `ESurfs` (0–5) external + `ISurfs` (0–5)
internal surfaces. `pickSurfaces()` chooses, for each pool, the requested count
of surfaces with the **lowest `activeCount`**, ties broken uniformly at random
(reservoir sampling, zero-alloc). Example: internal counts `[3,2,1,2,0]` with
`ISurfs=3` picks the `0` and `1` surfaces, then one of the two `2`s at random.
On launch the chosen surfaces' counts are incremented; on expiry (or recycle)
they are decremented (`releaseSurfaces`).

**Cylinder placement** — a cylinder strike's canvas is the unrolled **half-ring**
(`CYL_CANVAS_W = 60`, i.e. 1/4 of the 120 columns each way from a random start).
The 60-wide rendered bolt is composited onto the 120-wide cylinder `SurfaceCanvas`
at a random `cylOffset` (0–119), wrapping across the seam via `setMax`'s floorMod.

**Doors / ground** — the model enforces full-length columns, so bolts run the full
height to the ground and whatever lands on the bottom door rows is simply lost
(no door-column handling needed). `SurfaceCanvas.copyTo` maps each surface's canvas
onto its LED points column-major.

## Rendering approach

- **Generator reuse**: Zot is a thin wrapper over the untouched
  `apotheneum.doved.lightning` library. Four algorithms (midpoint displacement,
  L-system, RRT, physically-based) are shared stateless instances in the `Algo`
  enum; per-strike `Parameters` objects are built by `buildParams(algo, w, h)` with
  Zot's `branch`/`jag`/random-startX knobs, sized to the target canvas (50×45 face
  or 60×43 cylinder). `MIDPOINT_START_SPREAD` stays at 0.1 (doved's 0.9 re-scattered
  starts to the clamp corners; Zot randomizes startX itself).
- **Color pipeline (keep doved look + post-tint)**: doved renders a blue-white,
  alpha-keyed bolt into a shared scratch `BufferedImage` (one 50×45 for faces, one
  60×43 for the cylinder) at the phase's fade. `getRGB` reads it into a scratch int[];
  `tint()` either flattens the blue-white pixel over black (White on) or scales the
  strike's palette color by the pixel's intensity (White off); the result is
  `setMax`-composited onto each assigned surface (cylinder at `cylOffset`, wrapped).
  One `render()` call per bolt per canvas-type, then composited to all its surfaces.
- **Buffers / zero-alloc rule**: 16 `Bolt` slots preallocated, each with reusable
  face/cyl segment lists + growing leader-prefix lists (`faceVisible`/`cylVisible`,
  extended in place). Surface canvases + scratch buffers + `pickScratch` are all
  preallocated. **Per-STRIKE allocation is accepted** (the doved generators allocate
  segments + a `Parameters` object per strike — event-rate). Known library exception:
  doved's `render()` allocates `Color`/`BasicStroke`/`Path2D` per segment per frame
  (bounded by ≤16 bolts × segments; reused unmodified by design).

## Timing (StrkTime + FadeTime)

No tempo gating (Sync/TempoDiv/Meta convention retired 2026-07-08) and no grid
quantization — strikes fire **immediately** on trigger; their durations are
tempo-derived from two `EnumParameter<Tempo.Division>` knobs via
`tempo.period / division.multiplier` (captured at launch):

- **StrkTime** (default `QUARTER`): the LEADER phase — the bolt draws top→bottom over
  `leaderMs`, a growing segment prefix, brightness ramping 0.25→0.55.
- At `leaderMs`: **FLASH** — one rendered frame of the whole bolt at 1.0, plus the
  optional rate-limited whole-surface Flash.
- **FadeTime** (default `HALF`): the GLOW phase — the whole bolt decays quadratically
  from 0.7 to 0 over `fadeMs`, then the bolt expires.

Total time any LED is visible = **StrkTime + FadeTime**, exactly. CURATE: verify the
draw-in reads as motion (not a flicker) at short StrkTime divisions / fast BPM.

## Audio mapping (FFT floor + EMA transient, no random strikes)

Random/ambient strikes were removed — strikes only come from the manual `Strike`
trigger, the `Storm` burst, or the audio detector. The detector reads the 16-band
`GraphicMeter` directly each frame (`updateAudio`):

- **AudFreq** (`DiscreteParameter` 0–14): watch neighbouring bins `[f, f+1]`;
  `avg = ½(band(f)+band(f+1))`. 0 = lowest two bins (kick), 14 = highest two.
- **Audio** (`CompoundParameter` 0–1): absolute FFT **floor**. **`Audio == 0`
  disables audio strikes entirely** (manual/storm only).
- **EMADur** (`DiscreteParameter` 0–16, beats): EMA/refractory window.
  `emaTauMs = EMADur × beatMs`; `ema += (avg − ema)·(1 − e^(−dt/tau))`.
- **Trigger** when `avg > Audio` **and** (`EMADur == 0` or `avg > ema × 1.5`) **and**
  the blackout timer has expired. On a fire: launch, **snap `ema = max(ema, avg)`**
  (so the condition self-disarms and only a genuinely larger transient re-fires
  until the EMA decays over ~EMADur beats), and set a short blackout
  (`max(60 ms, 0.15 × EMADur × beatMs)`) to kill double-fires on one transient.
  This is the "both combined" scheme: adaptive EMA refractory + short blackout.
- CURATE: `EMA_TRIGGER_MULT = 1.5` and `BLACKOUT_FRAC = 0.15` are first-guess.

## Color (White boolean)

- **White on** (default): bolts render as doved's blue-white (tint flattens the
  rendered pixel over black — identical to the pre-refactor look).
- **White off**: each strike stores a random palette color at launch
  (`lx.engine.palette.swatch.colors`, random index; fallback to a saturated hue if
  the swatch is empty). The whole-surface Flash is tinted to match. Colors currently
  on screen are not avoided (per spec).

## Parameters

UI order: triggers first, then algorithm, surface counts, pattern params,
timing, White, audio. (No inherited face toggles — the base-class switch
dropped them.)

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `strike` | Strike | TriggerParameter | — | — | strike one bolt now |
| `storm` | Storm | TriggerParameter | — | — | burst of 3–5 bolts over ~2 s |
| `nextAlgo` | NextAlgo | TriggerParameter | — | — | cycle the generator algorithm |
| `algorithm` | Algo | EnumParameter&lt;Algo&gt; | MIDPOINT | 4 values | Midpoint / L-System / RRT / Physical |
| `eSurfs` | ESurfs | DiscreteParameter | 2 | 0..5 | external surfaces per strike |
| `iSurfs` | ISurfs | DiscreteParameter | 1 | 0..5 | internal surfaces per strike |
| `thickness` | Thick | CompoundParameter | 2 | 1..3 | core stroke px (branches at half) |
| `branch` | Branch | CompoundParameter | 0.4 | 0..1 | branchiness (probability/angle of forks) |
| `jag` | Jag | CompoundParameter | 0.5 | 0..1 | jaggedness (displacement / angle variation) |
| `flash` | Flash | CompoundParameter | 0.15 | 0..0.5 | whole-surface flash brightness (0 = off) |
| `strkTime` | StrkTime | EnumParameter&lt;Division&gt; | QUARTER | divisions | strike draw-in duration |
| `fadeTime` | FadeTime | EnumParameter&lt;Division&gt; | HALF | divisions | fade (flash + glow) duration |
| `white` | White | BooleanParameter | true | on/off | white bolts vs. random palette color |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | FFT floor (0 = audio strikes off) |
| `audFreq` | AudFreq | DiscreteParameter | 0 | 0..14 | which FFT bin-pair to watch |
| `emaDur` | EMADur | DiscreteParameter | 3 | 0..16 | EMA/refractory window in beats |

Series RndTrig pass (2026-07-06): `meta` → `rndTrig` (label Meta → RndTrig).
Removed 2026-07-08: `rndTrig` (Sync/TempoDiv/Meta convention retired); saved
`.lxp` references to the old path are dropped on load.

### `branch`/`jag` mapping per algorithm

| Algorithm | `branch` drives | `jag` drives |
|---|---|---|
| Midpoint | branchProbability | displacement |
| L-System | branch angle 25°–70° (lin) | angleVariation |
| RRT | branchProbability | jaggedness |
| Physical | branchingProbability ×0.8 | stepAngleVariation ×π rad |

## Triggers

- `nextAlgo` (small) — cycles the algorithm enum (wraps); takes effect on the next
  strike (in-flight bolts keep their captured generator).
- `strike` (medium) — one bolt: leader draws over StrkTime, return-stroke flash for
  one frame, glow decays over FadeTime.
- `storm` (large) — 3–5 bolts, free-run spacing 300–700 ms. Late storm bolts recycle
  the oldest active slot if all 16 are busy.

## Simulation-principles compliance

40-foot sculpture, LEDs far brighter than a monitor. Lightning is the canonical
event-like pattern: the stroke may be fast, but its **life** (StrkTime + FadeTime)
is long.

- **Event life**: total visible = StrkTime + FadeTime (e.g. QUARTER + HALF at
  160 BPM ≈ 375 + 750 = 1125 ms). CURATE: pick defaults so life reads ≥ ~1.5 s at
  performance BPM; consider longer FadeTime if strikes feel clipped.
- **Whole-surface flashes**: ≤1 rendered frame, dim (default 0.15, max 0.5, drawn
  under the bolts via `setMax`), rate-limited to one per `FACE_FLASH_MIN_INTERVAL_MS`
  (1200 ms). For a cylinder strike the flash covers only its 60-column band. CURATE:
  interval + brightness unverified at scale.
- **Sustained motion**: none — bolts hold position for their whole life; only
  brightness animates. The leader draw-in is event motion.
- **Contrast/brightness**: bold high-contrast forms on black; 2 px default core,
  branches at half, bleed `BLEED = 1.0`. Leader ramps 0.25→0.55, return stroke 1.0,
  glow starts 0.7 and decays quadratically. CURATE: brightness constants unverified.
- CURATE: colored bolts (White off) tint via per-pixel intensity × `setMax`; verify
  they still read as lightning versus straight alpha compositing.
- CURATE: cylinder 60-wide canvas vs 50-wide face canvas — bolts read a touch wider
  on the cylinder; confirm acceptable.
- CURATE: `MAX_BOLTS = 16` vs worst-case audio + storm stacking.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | — |
| 2026-07-05 | Review pass: MIDPOINT_START_SPREAD 0.9→0.1; empty-generation retire; added Audio depth knob, Sync/TempoDiv (TempoLock). | Series-wide Audio/Sync/TempoDiv + bug hunt |
| 2026-07-06 | Major reconception: switched base to `ApotheneumPattern`; added cylinder int/ext surfaces and 10-surface per-strike routing via `ISurfs`/`ESurfs` (load-balanced, fewest-displayed first); cylinder strikes render a wrapped random half-ring. Replaced Sync/TempoDiv/Glow with `StrkTime`/`FadeTime` tempo-division durations (strikes fire immediately). New audio model: `Audio` = FFT floor, `AudFreq` = bin-pair, `EMADur` = EMA+blackout refractory (Audio=0 disables). Removed random/ambient strikes and Energy/Ambient/Thresh. Added `White` boolean with per-strike random palette color via doved post-tint. | User-directed reconception |
| 2026-07-06 | Series RndTrig ordering: `meta` → `rndTrig` (label RndTrig), moved from last to 4th, immediately after the triggers; `.lxp` values on the old `meta` path are dropped on load | Series convention: TriggerBag meta trigger sits right after the other trigger params |
| 2026-07-08 | Removed Sync/TempoDiv/Meta + TriggerBag/TempoLock (convention retired; free-run behavior = old Sync-off path): `rndTrig` param deleted with the TriggerBag + jump-candidate table; `TempoLock.divisionMs` inlined as `tempo.period / division.multiplier` — StrkTime/FadeTime durations behave identically | Jeff 2026-07-08: Sync/TempoDiv + Meta pattern-control convention retired project-wide |
