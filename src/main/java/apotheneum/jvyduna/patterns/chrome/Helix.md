# Helix

Chrome: a triple-helix ascension up the cylinder, ringed by a counter-rotating
parallax starfield, with pulses riding the strands, an electron swarm spiralling
up them, unstranding separation, tightening twist and an XOR moire.

> Sidecar design doc convention: this file lives beside `Helix.java` and is the
> source of truth for design decisions and curation history. Mark any constant or
> behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

The storyboarded payoff for the **4:05 finale of "Chrome Country"** (Oneohtrix
Point Never, closing track of *R Plus Seven*). From Jeff's finale storyboard
(chrome-brief.md §4, §6 pattern #3): *"white helical ascension, pulses ride it,
CMO unstrands, DNA twists tighter, XOR-blend moiré, outer stars in a
counter-rotating path just fast enough against the main helix twist."*

The visual signature to preserve: a **long sustained ascension** — a DNA-like
helix spiraling up the cylinder, growing tighter and separating as the
organ/choir note holds; the **uncanny** hinge required by the brief — the strands
read as almost-clean but develop an XOR interference moire, "beauty that is just
slightly wrong." The electron swarm adds a living, organic sparkle climbing the
strands.

## Rendering approach

- **Base class**: `ApotheneumPattern` — cylinder-primary, needs full ring
  geometry plus the exterior→interior mirror copy, and does its own canvas
  compositing. Same shape as `Mystify`.
- **Surface**: the **cylinder exterior** (SurfaceCanvas 120×43) is the only drawn
  surface. The interior is an exact mirror via `copyCylinderExterior()` — the
  audience standing inside is enclosed by the ascension. **Cylinder-only** (the
  old cube-ring echo was removed per Jeff's feedback).
- **Geometry mapping**: the helix is drawn directly in cylindrical-unwrap space,
  where **a canvas column IS the angular position around the ring**: `col =
  (angle / 2π) · width`, `angle = spin + Twist·2π·t + phase_s`. `y = 0` is the top
  row (`SurfaceCanvas` convention); the strand parameter `t` runs 0 at the base to
  1 at the top.
- **Three converging strands**: `STRAND_COUNT = 3`. The per-strand angular phase
  and vertical offset are both scaled by **Unstrnd**, chosen so **all three
  coincide exactly at Unstrnd = 0** and fan apart as it rises:
  - `phase_s = Unstrnd · s · (2π/3)` → strands 0,1,2 at 0, 120°, 240° at Unstrnd 1.
  - `yOff_s  = Unstrnd · (s−1) · MAX_SEP_V` → −SEP, 0, +SEP at Unstrnd 1.
  At Unstrnd 0 every strand has phase 0 and yOff 0, so the three (and their pulses
  and electrons) are stacked on one path. This is the math guaranteeing "when
  Unstrnd is back at 0 they have always converged."
- **Ascension** is the whole winding slowly rotating around the ring over time
  (`spin`, in turns), NOT a vertical scroll. **Signed Climb** sets both the rate
  and the direction (negative = the other way around the ring). Pulses and
  electrons add the literal upward travel.
- **Buffers** (preallocated, zero-alloc render): two SurfaceCanvas (main + scratch
  for the moire copy); parallel pulse arrays (`pulseActive/Strand/T`,
  MAX_PULSES = 24); parallel star arrays (`starU/V/Depth`, MAX_STARS = 160, seeded
  once in the constructor). Palette samples are cached with a Satori-style change
  detector.
- **Electron swarm** uses the **LXLayer-per-entity idiom** (the `Raindrops.Drop`
  model documented in the apotheneum-patterns skill): one `Electron extends
  LXLayer` per particle, spawned with `addLayer(new Electron(lx))`, self-destruct
  via `remove()`. The layers draw into the shared `cylCanvas` during the layer
  loop; the cylinder **blit + interior mirror happen in `afterLayers()`** (not
  `render()`) so the swarm is included and mirrored. This is a **mixed
  architecture**: pulses/stars keep their preallocated arrays while the new swarm
  uses layers — deliberate, since the layer idiom is the skill's stated preference
  for entity systems and the array entities predate it.
- **Door-column handling**: none special — `SurfaceCanvas.copyTo` guards short
  (door) columns via `column.points.length`; pixels landing on door rows are
  simply not written.

## Electron swarm

Palette **swatch 3** (`swatch.get(2)`, cyan-ish fallback when absent), climbing
the strand paths while circling around them. Each electron holds its own state
(strand, height, orbit phase/speed, climb jitter, fade), seeded from `random` at
birth. `Electro` (0..1) drives the whole population:

- **Births** (`render()`): a free-running timer emits at
  `lin(Electro, 0, ELECTRON_BIRTH_PER_SEC_MAX)` per second — **0 at Electro 0**, so
  no new electrons are born — capped at `MAX_ELECTRONS` via `getLayers().size()`.
- **Climb**: `lin(Electro, ELECTRON_CLIMB_MIN, ELECTRON_CLIMB_MAX)` norm-height/s
  (× per-particle jitter) — faster at Electro 1.
- **Circling**: `orbitPhase += orbitSpeed·dt`; drawn column offset
  `orbitAmp·cos(orbitPhase)`, with a small vertical bob `orbitBob·sin(orbitPhase)`
  — an organic loop around the strand path as it ascends.
- **Life / death within 1 beat**: `fade` eases toward `Electro` with time-constant
  `ELECTRON_FADE_BEATS · beatMs` (`beatMs = lx.engine.tempo.period`, live). At
  Electro 0 the target is 0, so every electron fades to < `ELECTRON_DEATH_EPS`
  within ~1 beat and `remove()`s — satisfying "when Electro is 0 they all die off
  within 1 beat." `fade` also sets brightness, so Electro 1 is birth-dense, fast
  and bright.

CURATE: `MAX_ELECTRONS 48`, `BIRTH_PER_SEC_MAX 12`, `CLIMB_MIN 0.08`/`MAX 0.35`,
`ORBIT_MIN 1.5`/`MAX 4.0` rad/s, `ORBIT_AMP 3.0` cols, `ORBIT_BOB 0.02`,
`RADIUS 1.0`, `FADE_BEATS 0.4`, `DEATH_EPS 0.03`, `FALLBACK_ELECTRON` cyan — all
first-guess, tune on hardware.

## Color

Palette-driven with a Satori-style change cache (rebuilt only when a swatch color
or `Desat` changes). Roles:

- **Strand + stars** ← palette **swatch 1** (`swatch.get(0)`).
- **Pulses** ← palette **swatch 2** (`swatch.get(1)`), or **white** if the swatch
  has no second color (per Jeff's feedback).
- **Electrons** ← palette **swatch 3** (`swatch.get(2)`), or a cyan fallback.

**Desat** (replaces the old Hue rotation) pulls every role color's saturation down
toward white: `sat' = sat · (1 − Desat)` in HSB (the `Lorre.java` idiom). At
Desat 0 the pure palette shows; at 1 the strands/pulses/electrons wash white.

## Audio mapping

`AudioReactive`, ticked first in `render()`, gated by the **Audio depth knob**
(`audio.setDepth(audioDepth)`, default 0 = pure screensaver):

- **level** — exterior brightness **bloom**: the blit multiplier is
  `1 + 0.30 · level` (`LEVEL_BLOOM`). At silence the multiplier is exactly 1.
- **bassHit()** — emits one pulse up each strand on each detected bass transient.
- **treble** — star **twinkle**: star brightness is lifted by `1 + 0.5 · treble`
  (`TREBLE_TWINKLE`).

At depth 0 (or true silence) the pattern is a steadily-ascending triple helix with
a drifting parallax starfield, timer-emitted pulses, and the electron swarm, all
at full (unbloomed) brightness — the native look.

## Tempo mapping

Beatless song — timing free-runs. The only live-tempo read is the electron death
timer (`lx.engine.tempo.period` = "one beat"), used so the "die within 1 beat"
behavior tracks the project BPM automation. Pulses auto-emit on a density timer
(`Pulses`), and spin / pulse-travel / star-drift are continuous, knob-scaled
rates.

## Speed / rate mapping

Chrome is a per-song **outlier** (parts grid-driven, parts continuous), so the
2026-07-08 blanket beat-relative conversion was skipped here and the rate knobs
were decided with Jeff individually. Resolved 2026-07-08:

| Quantity | Knob(s) | Basis |
|---|---|---|
| Helix ascension / spin | `Speed` × signed `Climb` | absolute turns/s; `lin(Speed, 0.03, 0.12)` × Climb. Climb −5..5 intentionally **relaxes** the ≥5 s/turn cap (Jeff's call — this is the outlier) |
| Pulse travel | `PlsSpd` | absolute norm-height/s, `lin(PlsSpd, 0.05, 0.50)` — dedicated knob ("lose tempo, replace with PlsSpd") |
| Pulse emission spacing | `Pulses` | pure density: interval `lin(Pulses, 2000, 450)` ms, free-run |
| Star counter-rotation | `Speed` | absolute turns/s, `lin(Speed, 0.06, 0.13)` × depth |
| Electron climb / birth / brightness | `Electro` | see Electron swarm; death timer is live-tempo-relative |

## Parameters

Registration/UI order (do not deviate; keys/labels must never be renamed once
saved in a `.lxp`). **No triggers** — the old `pulse`/`reverse`/`strike` triggers
were all removed per Jeff's feedback; the ≥3-trigger template guideline is
**waived** for this pattern (signed Climb subsumes Reverse; there is no
strike/manual-pulse concept anymore).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `speed` | Speed | CompoundParameter | 0.35 | 0..1 | master rate for spin + star drift |
| `climb` | Climb | CompoundParameter | 1.0 | −5..5 | signed ascension twist-rate (sign = direction; wide, cap relaxed) |
| `twist` | Twist | CompoundParameter | 3.0 | 0..8 | whole turns each strand winds (0 = vertical) |
| `unstrand` | Unstrnd | CompoundParameter | 0 | 0..1 | strand fan + vertical separation (all converged at 0) |
| `moire` | Moire | CompoundParameter | 0 | 0..1 | XOR strand-copy strength; 0 = off |
| `stars` | Stars | CompoundParameter | 0.4 | 0..1 | parallax starfield density/brightness |
| `pulses` | Pulses | CompoundParameter | 0.5 | 0..1 | auto-pulse density / inverse spacing |
| `plsSpd` | PlsSpd | CompoundParameter | 0.4 | 0..1 | pulse travel speed up the strands |
| `electro` | Electro | CompoundParameter | 0.3 | 0..1 | electron swarm (0 = die within a beat, 1 = more/faster/brighter) |
| `thick` | Thick | CompoundParameter | 1.5 | 1..3 | strand stroke thickness (px) |
| `desat` | Desat | CompoundParameter | 0 | 0..1 | desaturate palette colors toward white |
| `smooth` | Smooth | CompoundParameter | 1.0 | 0..1 | motion blending + antialiasing (now continuous — see below) |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure screensaver) |

CURATE: `Twist` 3.0, `Stars` 0.4, `Pulses` 0.5, `PlsSpd` 0.4, `Electro` 0.3,
`Thick` 1.5, `Climb` range ±5 — tune on hardware.

## Smooth (continuous)

`Smooth` (default 1.0) blends motion + antialiasing: 0 = steppy/pixel-snapped/hard
edges, 1 = smooth sub-pixel motion + antialiased forms. It is applied in exactly
two places, each a **single continuous formulation** (no hidden rasterizer switch,
so there is no jump the instant Smooth leaves 0 — the original bug):

- `plotDisc` — the center is snapped at Smooth 0 and interpolated toward the true
  sub-pixel position as Smooth rises; the radius is a **constant float `r` for all
  Smooth** (the old integer radius `round(r)` at Smooth 0 was what made the stroke
  jump thinner); the edge half-width grows continuously `hw = 0.5 + 0.5·Smooth`,
  coverage-scaled. CURATE: the 0.5..1 px half-width endpoints.
- `drawStars` — always the bilinear 2×2 sub-pixel splat with weights gated by
  Smooth (`tx = (fx−ix)·Smooth`); at Smooth 0 it collapses to the floor pixel.

## Simulation-principles compliance

- **Helix spin** — `Climb · lin(Speed, 0.03, 0.12)` turns/s. Climb's wide/signed
  range intentionally lets spin exceed the ≥5 s/turn cap; this is a deliberate
  exception for the Chrome outlier (Jeff wanted a much wider Climb).
- **Pulse travel** — `lin(PlsSpd, 0.05, 0.50)` norm-height/s → full-height
  traversal 20 s (PlsSpd 0) down to 2 s (PlsSpd 1). Also user-owned via PlsSpd.
- **Star counter-rotation** — nearest layer sweeps the ring in `1 / 0.13 = 7.7 s`
  at Speed 1; ≥ 5 s. "Just fast enough against the main helix twist" per the brief.
  CURATE: confirm the differential reads.
- **Electron swarm (event-like)** — births/deaths are event-rate; individual climb
  (≤ 0.35 /s → ≥ ~3 s per full height) reads as organic sparkle, exempt from the
  sustained-motion cap. CURATE: confirm density/brightness at Electro 1.
- **Contrast / bold forms** — strands are hard MAX-blended strokes; pulses discrete
  bright discs; stars single dim points; electrons small bright discs. The only
  fine texture is the intentional XOR moire (default 0).
- CURATE: strand thickness vs. LED pitch — verify a 1.5 px strand reads as a clean
  line at high Twist, where adjacent windings crowd.
- CURATE: swarm frame cost — confirm frame time is fine at Twist 8 + full swarm.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass — cylinder-primary double-helix ascension, riding pulses, unstranding separation + tightening twist, XOR moire, counter-rotating starfield, lightning-strike event, palette deep-red | Chrome 4:05 finale per chrome-brief.md §6 pattern #3 |
| 2026-07-08 | Removed Sync/TempoDiv/Meta + TriggerBag/TempoLock (convention retired) | Project-wide retirement of the Sync/TempoDiv + Meta control convention |
| 2026-07-08 | Conventions pass: `energy`→`speed`; added `Smooth` (default 1.0) with a hard/smooth branch in `plotDisc`/`drawStars`; flagged Chrome's per-song speed units for a per-param decision | Package-wide param-naming + Smooth AA conventions |
| 2026-07-08 | **Feedback revision** (this pass): (1) **Smooth made continuous** — removed the `sm≤0.001` rasterizer swap in `plotDisc`/`drawStars` (constant float radius + continuously-growing edge half-width + sub-pixel center); fixes the jump leaving 0. (2) **Twist** min 0.5→**0** (vertical). (3) **Climb** widened to signed **−5..5** (sign = direction), **Reverse trigger dropped**; cap intentionally relaxed. (4) **Pulses** = pure density; new **PlsSpd** knob owns pulse travel (dropped the Speed/audio coupling). (5) **Three strands** with Unstrnd-scaled phase+offset so all converge at Unstrnd 0. (6) **Electron swarm** added (LXLayer-per-entity, swatch 3, Electro-driven, death within 1 beat via live `tempo.period`); blit+mirror moved to `afterLayers()`. (7) **Desat** replaces **Hue**; **pulses** now from swatch 2 / white. (8) Removed **Strike**, **Pulse** and **Reverse** triggers, the **Cube** toggle + all lightning-bolt/cube machinery — cylinder-only, zero triggers. Resolved the "Speed units — DECISION NEEDED" section. | Jeff's Helix hardware/in-app feedback pass |
