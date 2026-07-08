# Aumakua

Generative ochre cave-painting human figures rising in family trees up the exterior, dripping new figures below, blooming with the trombone; womb-dark interior.

> Sidecar design doc convention: this file lives beside `Aumakua.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

The hero centerpiece of **"Temper The Wound"** (Kalia Vandever), the opener of
"Communicating" — the genesis. Per the arc critique this, not the exterior words,
is *the* song: Vandever's stated meaning is her parents' separation and *how you
move through that pain*, threaded through her Hawaiian ancestry and **ʻaumākua**
(ancestral guardian spirits that appear in dreams). The visual is Jeff's Idea 2:
a generator that draws **Lascaux/Chauvet-style cave-painting human figures** —
stick/body forms with a head, torso, two arms and two legs — that slowly **rise**
up the walls. As a figure ascends, its limbs **drip** downward and seed a new
figure below it, so lineage strands branch upward into continuous **family trees
of humanity** climbing the surface (childbirth + ancestry).

This is an interpretation, not a recreation of any existing screensaver. The
"cave painting" signature we preserve: **bold ochre forms, no fine texture,**
hand-drawn stick anatomy, and the upward-climbing lineage as the central motion.
The critique's demand is honored directly: **something physically rises and
blooms at 2:32** (the G5 melodic peak, energy ~0.78) — the `Bloom` trigger (and
the trombone envelope automatically) raises every figure's arms into the
"orant" (raised-arm) pose and births a burst of new raised-arm ancestors,
brightest and fullest at that moment.

**Companion reuse (NOT built here):** the existing `Text` pattern spelling
**HEAL / BIRTH / HOPE** on the exterior is the accent layer that runs alongside
Aumakua per the plan; it is a separate pattern and out of scope for this class.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the piece needs the cube ring, the
  cylinder ring, and independent exterior/interior handling (womb split), none
  of which `ApotheneumRasterPattern` (cube-face-only) can reach.
- **Surfaces**: two independent generative systems (`Field`), one per surface,
  each with its own `Random` seed so the cube and cylinder grow different
  lineages from the same parameters:
  - cube ring — `SurfaceCanvas(200, 45)` copied to `Apotheneum.cube.exterior`.
  - cylinder — `SurfaceCanvas(120, 43)` copied to `Apotheneum.cylinder.exterior`.
- **Interior = womb echo**: the SAME canvas is copied to `cube.interior` /
  `cylinder.interior` through `copyTo(surface, colors, mult, false)` with
  `mult = Womb` (default 0.25) — a dimmer, softer echo of the exterior. Womb=0
  makes the interior pure black (a fully held, protected dark); the exterior
  always renders at full brightness. This is the thematic exterior/interior
  birth split.
- **Geometry mapping**: figures live in canvas pixel space. `y = 0` is the TOP
  row, so a figure **rising** means its feet-row `y` **decreases** over time.
  X wraps (both surfaces are continuous rings). Figure anatomy is drawn as
  fractions of the figure's pixel height (hip 0.38, shoulder 0.72, head at the
  top), with `lineMax` limbs (union brightness, never erase) and a small
  `setMax` disc head.
- **Buffers / zero-alloc**: each `Field` preallocates a fixed pool of
  `MAX_FIGURES = 64` `Figure` objects in its constructor. Births (root or drip)
  reuse an inactive pool slot via `freeSlot()`; nothing is allocated in the
  render loop. The palette color cache (`genColor[]`, `cachedSwatch[]`) is
  preallocated. `Random.nextInt/nextDouble` do not allocate.
- **Trails / smear**: instead of clearing, each `Field` calls
  `canvas.decay(decayMult)` per frame. A rising figure redrawn at full glow each
  frame therefore leaves a fading "comet" smear below it (the gauzy gossamer
  trombone-loop quality) and lineage strands persist as fading family-tree
  branches. `Smear` sets the trail half-life.
- **Door columns**: handled automatically by `SurfaceCanvas.copyTo`, which
  guards each column by `column.points.length` — figures over a door are
  clipped, no special-casing.

## Audio mapping

`AudioReactive`, ticked as the FIRST line of `render()`, gated by the standard
**Audio depth knob** (`audio.setDepth(audioDepth)`). The trombone is the only
instrument, so its envelope is read as `trom = 0.6·level + 0.4·mid` (the horn's
voice sits mostly in the mid bands):

- **`trom` → birth cadence**: shortens the root-spawn interval
  (`rootIntervalMs /= 1 + AUDIO_SPAWN·trom`, `AUDIO_SPAWN = 2.5` CURATE) — the
  horn swells birth new ancestors.
- **`trom` → glow**: lifts every figure's brightness above its resting value
  (`glowMult = 1 + AUDIO_GLOW·trom`, `AUDIO_GLOW = 0.6` CURATE) — the ochre
  "warms" with the horn.
- **`trom` → pose**: biases newborns toward the raised-arm (orant) bloom pose
  (`raiseBias = clamp(0.15 + trom)`), so the 2:32 climax swell auto-blooms the
  field without a manual trigger.

**Depth knob / silence behavior**: `Audio` defaults to 0 = pure screensaver. At
depth 0 (or true silence) `level`/`mid` read 0, so `trom = 0`: births run on the
Energy-driven idle timer alone, figures sit at their resting ochre glow, and
newborns use the 15% baseline raise probability. The full rise/drip/family-tree
lifecycle runs identically without audio — the default look is complete on its
own. Raising the knob toward 1 continuously restores the three couplings above;
at 1 a trombone swell visibly quickens births, brightens the ochre, and throws
arms up. No hit taps are used (rubato song, no drums — see the brief).

## Tempo mapping

The music is **rubato / near-beatless** — the brief is explicit that TempoLock
must never produce a foot-tappable click. So tempo locking here only **nudges
each due birth onto a slow, breathing grid line**, it does not drive motion:

- `Sync` on (default): a birth that has come due (idle timer elapsed, or a
  rising figure ready to drip) is held until the next `TempoDiv` boundary
  (`TempoLock.crossed`, polled once per frame and shared by both fields). With
  the default `TempoDiv = WHOLE` (one bar) births land on bar lines — a gentle
  breath, not a pulse. `Genesis`/`Bloom`/`Seed` triggers fire immediately
  (never gated).
- `Sync` off: `birthGateOpen` is always true, so births free-run on the idle
  timer — fully functional rubato timing, arguably the most on-theme mode.

No `retime()` is used — nothing about the continuous rise is quantized, only the
discrete birth events are grid-nudged. `crossed()` is called unconditionally
every frame (even with Sync off) so it never fires a stale boundary when Sync is
re-enabled.

## Energy mapping

| Quantity | Ambient (e=0.3) | Peak (e=1) | Curve |
|---|---|---|---|
| Rise speed | ~2.98 rows/s (45-row cube ≈ 15.1 s) | 6.43 rows/s (≈ 7.0 s) | lin |
| Root-spawn interval | ~11 s base (× Density, ÷ trom) | ~2.2 s base | exp |

`Energy` defaults to **0.3** (CURATE — below the series' usual 0.35, because
this is the calmest, most patient song in the piece). Density and the trombone
envelope further scale the spawn rate; figure `Scale`, `Drip` branching and
`Sway` are independent of Energy. Sustained rise respects the ≥5 s full-traversal
cap even at e=1 (7.0 s cube / 6.7 s cylinder — see compliance).

## Parameters

UI/registration order (do not deviate; keys/labels are referenced by saved
`.lxp` files): non-meta triggers, Energy, pattern params, Audio, Sync, TempoDiv,
Meta (last).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `seed` | Seed | TriggerParameter | — | — | birth one root ancestor at the base of both surfaces |
| `bloom` | Bloom | TriggerParameter | — | — | raise all arms + burst of raised-arm ancestors (the 2:32 climax) |
| `drift` | Drift | TriggerParameter | — | — | re-randomize every figure's sway phase/direction |
| `genesis` | Genesis | TriggerParameter | — | — | fade both fields to black, begin a fresh lineage |
| `energy` | Energy | CompoundParameter | 0.3 | 0..1 | rise speed + birth cadence (within the traversal cap) |
| `density` | Density | CompoundParameter | 0.5 | 0..1 | concurrent figure cap + ancestor birth rate |
| `figScale` | Scale | CompoundParameter | 0.5 | 0..1 | figure height (7..15 rows) |
| `warmth` | Warmth | CompoundParameter | 0.6 | 0..1 | ochre pull (hue → amber/sienna) + glow lift |
| `dripChance` | Drip | CompoundParameter | 0.6 | 0..1 | lineage branching probability per rising figure |
| `sway` | Sway | CompoundParameter | 0.4 | 0..1 | gentle organic horizontal sway amplitude |
| `smear` | Smear | CompoundParameter | 0.5 | 0..1 | feedback-smear trail length (0.2..6 s half-life) |
| `womb` | Womb | CompoundParameter | 0.25 | 0..1 | interior brightness (0 = black womb) |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure screensaver) |
| `sync` | Sync | BooleanParameter | true | — | nudge due births onto the tempo grid |
| `tempoDiv` | TempoDiv | EnumParameter | WHOLE | Tempo.Division | division births land on when Sync is on |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

## Triggers

Four non-meta triggers, small → large:

- `seed` (small) — one new root ancestor appears at the base of each surface; a
  single new stick figure begins climbing. Reads within a second or two as one
  new life at the bottom.
- `drift` (small–medium) — re-randomizes every living figure's sway phase and
  rate; the whole field's gentle organic wobble reshuffles. No births, no
  brightness change — a subtle breathing shift.
- `bloom` (large) — the birth bloom: every living figure throws its arms up
  (orant pose) and a burst of `3 + 3·Density` raised-arm ancestors is born
  brightest. This is the 2:32 climax as a manual cue; reads instantly, resolves
  over the ~2.2 s birth-in of the new figures.
- `genesis` (large) — both fields fade to black (canvas cleared, all figures
  retired) and a fresh lineage begins from empty. A full reset; reads
  immediately, repopulates over the next several seconds.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. All four
triggers are also registered in the bag.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `density` | [0.25, 0.9] | candidate | extremes excluded: 0 too sparse, 1 crowds the pool |
| `figScale` | [0.2, 0.9] | candidate | keep figures legible, avoid the full-height max |
| `warmth` | [0.3, 1.0] | candidate | never fully un-warm (0 could read cold vs the palette) |
| `dripChance` | [0.3, 1.0] | candidate | guarantee some branching on a jump |
| `sway` | [0.0, 0.8] | candidate | full-still to lively, reserve the top for manual use |
| `smear` | [0.2, 0.9] | candidate | avoid 0 (crisp, no gossamer) and 1 (near-permanent) |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

- **Rise (sustained motion)** — the only continuous motion. Cube: 45 rows per
  full traversal. Ambient (e=0.3): 2.98 rows/s → **15.1 s**. Peak (e=1):
  6.43 rows/s → **7.0 s**, inside the ≥5 s cap by design (`RISE_ROWS_PER_SEC_PEAK`
  is the named constant fixing this). Cylinder: 43 rows / 6.43 = **6.7 s** at
  e=1. Nothing rescales the rise but Energy; no grid retiming touches it.
- **Sway (bounded oscillation, not a traversal)** — horizontal wobble of a few
  rows, period ~6–16 s (`swayRate` 0.0004–0.001 rad/ms). Peak lateral speed at
  Sway=0.8, height 15 ≈ 4.2 rows/s over a small arc — not a full-width
  traversal, and slow. CURATE: confirm it reads as breathing, not jitter.
- **Births (event-like)** — each newborn fades IN over `BIRTH_IN_MS = 2.2 s`
  (≥ 1.5 s event minimum) and then lives many seconds as it climbs, so no birth
  reads as a strobe flash even at peak cadence. Drips inherit the same birth-in.
- **Bloom / Genesis (event-like)** — instant state changes (arms up / clear);
  the resulting figures birth-in over 2.2 s and persist. ≥ 1.5 s of visual life.
- **Bold forms / contrast** — hand-drawn stick anatomy in solid ochre with
  `lineMax` limbs and a filled disc head; no gradients, no fine texture. Depth
  haze is a single brightness step per lineage generation, not a gradient.
  CURATE: verify a gen-5 figure at silence (72% depth × resting palette
  brightness, further dimmed by the birth-in tail) still reads on hardware;
  raise the depth-haze floor in `warmColor`/`ochreFallback` if it vanishes.

## CURATE notes

Every value below is a first-pass guess for hardware curation (grep `CURATE:`
in the `.java` too):

- `RISE_ROWS_PER_SEC_PEAK = 6.43` — sets the 7.0 s cube traversal at e=1; pull
  down for an even more patient peak (never below 5 s cap).
- `ROOT_INTERVAL_MS_AMBIENT/PEAK = 11000 / 2200` — ancestor cadence; roots are
  meant to be rare (most figures are drips).
- `AUDIO_SPAWN = 2.5`, `AUDIO_GLOW = 0.6` — trombone coupling strengths.
- `DRIP_RISE_BODIES = 1.1` — how far a figure climbs before dripping a child;
  tune for family-tree density vs legibility.
- `STRAND_LEVEL = 0.35` — lineage-strand brightness; verify the branches read
  as connective tissue, not clutter.
- `OCHRE_HUE = 32`, `OCHRE_HUE_DEEP = 20`, empty-swatch sat 62 / bright ramp
  88→52 — the ochre fallback (only used with an empty project swatch); the real
  show should drive color from the Pastels swatch (Try Radish / Gareth), warmed
  toward ochre by `Warmth`.
- `Energy` default 0.3, `Womb` default 0.25 — the patient/dark defaults for the
  opener; confirm the womb interior reads as present-but-protected, not dead.
- `MAX_FIGURES = 64`, `MAX_GEN = 5`, figure geometry fractions (hip/shoulder/
  spreads/head radius) — the cave-painting anatomy; tune the silhouette on
  hardware (arm/leg spread especially at small `Scale`).
- `f.children < 2` drip cap and `raiseBias` 0.15 baseline — lineage branching
  factor and resting orant fraction.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass, Claude autonomous session | Aumakua hero for `temper`: generative ochre cave-painting family tree rising up the exterior, dripping new figures; trombone-warmed glow + births; 2:32 bloom (raised-arm burst); womb-dark interior echo. Compiles against lx 1.2.2-SNAPSHOT + apotheneum 2.0.0. All tuning values marked CURATE for hardware. |
