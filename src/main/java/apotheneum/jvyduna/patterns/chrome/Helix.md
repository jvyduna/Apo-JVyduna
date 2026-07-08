# Helix

Chrome finale: a lightning strike to earth opening into a white double-helix
ascension up the cylinder, ringed by a counter-rotating parallax starfield, with
pulses riding the strands, unstranding separation, tightening twist and an XOR moire.

> Sidecar design doc convention: this file lives beside `Helix.java` and is the
> source of truth for design decisions and curation history. Mark any constant or
> behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

Not a screensaver recreation — this is the storyboarded payoff for the **4:05
finale of "Chrome Country"** (Oneohtrix Point Never, closing track of *R Plus
Seven*), the single most important cue in the whole 21-minute "Communicating"
piece. From Jeff's finale storyboard (chrome-brief.md §4, §6 pattern #3): *"first
hit of series (4:05) = lightning strike to earth; white helical ascension, pulses
ride it, CMO unstrands, DNA twists tighter, XOR-blend moiré, outer stars in a
counter-rotating path just fast enough against the main helix twist."*

The lightning vocabulary deliberately echoes the existing **Zot** pattern (After
Dark "Zot!" — a bolt striking down the surfaces) so the finale ignition rhymes
with the strike motif used elsewhere in the set; here it is a self-contained,
lightweight bolt rather than a reuse of the `doved.lightning` library (kept simple
for the first pass — CURATE: compare against a real Zot-style bolt on hardware and
consider promoting to the shared generator if the look is thin).

The visual signature to preserve: (1) an **event** — a white bolt cracks to earth;
(2) a **long sustained ascension** — a DNA-like double helix spiraling up the
cylinder, growing tighter and separating as the organ/choir note holds; (3) the
**uncanny** hinge required by the brief — the strands read as almost-clean but
develop an XOR interference moire, "beauty that is just slightly wrong."

## Rendering approach

- **Base class**: `ApotheneumPattern` — the finale is cylinder-primary with an
  optional cube-ring echo, needs full ring geometry plus exterior→interior mirror
  copies, and does its own canvas compositing (not cube-face-only, so
  `ApotheneumRasterPattern` doesn't fit). Same shape as `Mystify` / `Zot`.
- **Surfaces**: the **cylinder exterior** is the primary surface (SurfaceCanvas
  120×43). The **cube ring** (SurfaceCanvas 200×45) is drawn only when the `Cube`
  toggle is on. Interiors are an exact mirror of their exteriors via
  `copyCylinderExterior()` / `copyCubeExterior()` — the audience standing inside is
  enclosed by the ascension (brief: "interior-face use so the audience feels
  enclosed by a nave").
- **Geometry mapping**: the double helix is drawn directly in cylindrical-unwrap
  space, where **a canvas column IS the angular position around the ring**. A
  strand that advances `Twist` whole turns from base to top is therefore a
  physically correct spiral: `col = (angle / 2π) · width`, `angle = spin +
  Twist·2π·t + phase`. Two strands a half-turn (`π`) apart are the double helix; on
  the real cylinder they are parallel spirals a half-ring apart (the sinusoidal
  "crossing" look is only a 2D-projection artifact — the spiral is the true form).
  `y = 0` is the top row (`SurfaceCanvas` convention); the strand parameter `t`
  runs 0 at the base to 1 at the top.
- **Ascension** is the whole winding slowly rotating around the ring over time
  (`spin`, in turns), NOT a vertical scroll — so the strands appear to twist
  upward continuously without any seam. Pulses add the literal upward travel.
- **Buffers** (all preallocated in the constructor, zero-alloc render): two
  SurfaceCanvas per surface (main + scratch for the moire copy); parallel pulse
  arrays (`pulseActive/Strand/T`, MAX_PULSES = 24); parallel star arrays
  (`starU/V/Depth`, MAX_STARS = 160, seeded once in the constructor); the bolt
  column profile (`boltCol`, BOLT_SAMPLES = 64). Palette samples are cached with a
  Satori-style change detector. Trigger callbacks (pulse spawn, bolt regen) run at
  event rate and touch only preallocated state.
- **Door-column handling**: none special — `SurfaceCanvas.copyTo` already guards
  short (door) columns via `column.points.length`; pixels landing on door rows are
  simply not written. The bolt/helix over the doors are lost, acceptable for a
  full-height ascension.

## Audio mapping

`AudioReactive`, ticked as the first line of `render()`, gated by the **Audio
depth knob** (`audio.setDepth(audioDepth)`):

- **level** — exterior brightness **bloom**: the copy multiplier is
  `1 + 0.30 · level`, so the whole ascension swells brighter as the organ/choir
  finale surges (`LEVEL_BLOOM`). At silence (level 0) the multiplier is exactly 1.
  Level also thickens the free-running auto-pulse stream (interval `/= 1 + level`).
- **bassHit()** — emits one pulse up each strand on each detected bass transient
  (the sparse rubato bass in the finale rides the helix).
- **treble** — star **twinkle**: star brightness is lifted by `1 + 0.5 · treble`
  (`TREBLE_TWINKLE`), so the surrounding field shimmers with the high-frequency
  wash.

**Depth knob / silence behavior**: `Audio` defaults to 0 = pure screensaver. At
depth 0 (or in true silence), level/treble read 0 and hits never fire: the pattern
is a steadily-ascending red double helix with a slow drifting parallax starfield
and auto-emitted pulses on the `Pulses` timer, at full (unbloomed) brightness. That
is the pattern's native look — this is a **beatless, envelope-driven** song, so the
Audio knob (raised via a clip/modulator across 4:05–4:40) is the primary engine,
not a tempo grid. Raising the knob to 1 continuously adds the bloom, hit-driven
pulses and star twinkle over the depth-0 baseline.

## Tempo mapping

**This song is beatless** (chrome-brief.md §1: grid mode `none`, "drive
choreography from envelopes/sections, no 16th grid"). The `Sync`/`TempoDiv` pair is
present for series-convention parity but is **intended OFF** for this song, and the
default composition leaves it off. The pattern's native timing is fully
free-running:

- **Free-running (Sync off, the intended mode)**: pulses auto-emit on a timer whose
  interval interpolates from `PULSE_INTERVAL_SPARSE_MS` (2000 ms at Pulses = 0) to
  `PULSE_INTERVAL_DENSE_MS` (450 ms at Pulses = 1), shortened by audio level. Spin,
  pulse travel and star drift are all continuous, energy-scaled rates.
- **Sync on (optional)**: auto-pulse emission is instead gated to
  `TempoLock.crossed(TempoDiv)` (default `HALF`), for the rare case someone wants
  the finale pulses to land on a transport grid. `crossed()` is polled
  unconditionally every frame (per the TempoLock javadoc) so toggling Sync never
  fires a stale crossing.

No `retime()` clamp overrides are used — nothing here schedules a single future
event onto the grid; only discrete pulse emission is optionally grid-gated.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Helix spin (ascension twist) | 0.03 turns/s | 0.12 turns/s (×Climb, ≤0.18) | lin |
| Pulse travel (full height) | 0.11 /s → 9.1 s | 0.18 /s → 5.5 s | lin |
| Star counter-rotation (nearest layer) | 0.06 turns/s | 0.13 turns/s → 7.7 s/turn | lin |

`Energy` defaults to 0.35 (the soothing-ambient regime). Every sustained rate above
respects the ≥5 s full-traversal cap even at energy 1 — see the compliance section
for the arithmetic, including the `Climb` multiplier headroom.

## Parameters

UI/registration order convention (do not deviate; keys/labels must never be
renamed once saved in a `.lxp`): triggers (non-meta), Energy, pattern-specific,
Audio, Sync, TempoDiv, Meta (always last).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `pulse` | Pulse | TriggerParameter | — | — | release one bright pulse up each strand |
| `reverse` | Reverse | TriggerParameter | — | — | flip the ascension twist direction |
| `strike` | Strike | TriggerParameter | — | — | fire the lightning bolt to earth (finale ignition) |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (ambient ↔ peak rates, capped) |
| `climb` | Climb | CompoundParameter | 1.0 | 0..1.5 | ascension twist-rate multiplier |
| `twist` | Twist | CompoundParameter | 3.0 | 0.5..8 | whole turns each strand winds over the full height |
| `unstrand` | Unstrnd | CompoundParameter | 0 | 0..1 | strand vertical separation (also tightens the twist) |
| `moire` | Moire | CompoundParameter | 0 | 0..1 | XOR strand-copy strength (interference moire); 0 = off |
| `stars` | Stars | CompoundParameter | 0.4 | 0..1 | parallax starfield density/brightness |
| `pulses` | Pulses | CompoundParameter | 0.5 | 0..1 | auto-pulse density (free-run) |
| `thick` | Thick | CompoundParameter | 1.5 | 1..3 | strand stroke thickness (px) |
| `cube` | Cube | BooleanParameter | false | — | also render the helix on the cube ring |
| `hue` | Hue | CompoundParameter | 0 | 0..360 | rotate the palette-sampled colors (0 = pure palette) |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure screensaver) |
| `sync` | Sync | BooleanParameter | true | — | grid-gate pulses (leave OFF — beatless song) |
| `tempoDiv` | TempoDiv | EnumParameter | HALF | Tempo.Division | division pulses emit on when Sync is on |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

CURATE: `Twist` default 3.0 turns, `Stars` 0.4, `Pulses` 0.5, `Thick` 1.5 — all
first-guess screensaver values, tune on hardware. CURATE: `TempoDiv` default HALF
is moot while Sync is off; picked blind.

## Triggers

Three non-meta triggers, small → large, plus Meta:

- `pulse` (small) — one bright pulse leaves the base of each strand and rides up
  over ~5–9 s. A local highlight; reads immediately, resolves over the pulse life.
- `reverse` (medium) — the helix ascension flips direction (twists the other way
  around the ring). A continuous, few-seconds-to-read change of motion.
- `strike` (large) — a white lightning bolt regenerates and cracks to earth with a
  ~140 ms flash then a ~1.6 s glow decay. The full-field finale-ignition event.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. The three
triggers are also registered in the bag.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `twist` | [1, 6] | candidate | extremes excluded: <1 barely winds, >6 reads as vertical stripes at LED pitch |
| `unstrand` | [0, 1] | candidate | full range safe (paired ↔ fully separated) |
| `moire` | [0, 0.7] | candidate | upper reserved: full-strength XOR can strobe the strands to black |
| `stars` | [0.1, 0.8] | candidate | floor keeps a few stars; ceiling avoids a solid red wash |
| `climb` | [0.4, 1.3] | candidate | outer range reserved for manual performance use |
| `pulses` | [0.2, 0.9] | candidate | avoids the fully-sparse and machine-gun extremes |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

- **Helix spin (sustained motion)** — one full turn around the ring = `1 /
  turnsPerSec`. Peak `turnsPerSec = Climb · 0.12`; at Climb = 1.0 (default) that is
  0.12 → **8.3 s/turn**, at Climb = 1.5 (max) it is 0.18 → **5.5 s/turn**, still
  ≥ 5 s. Ambient (e=0) is 0.03 → 33 s/turn. The Climb range top (1.5) was chosen so
  `1.5 · 0.12 = 0.18` sits exactly at the cap margin, never over.
- **Pulse travel (sustained motion)** — full-height traversal = `1 / pulseSpeed`.
  Peak 0.18 → **5.5 s**; ambient 0.11 → 9.1 s. Both ≥ 5 s. Pulses are the fastest
  vertical motion and sit at the cap by construction (peak endpoint chosen = 0.18).
- **Star counter-rotation (sustained motion)** — nearest layer (depth 1) sweeps the
  ring in `1 / STAR_TURNS_PEAK = 7.7 s` at e=1; farther layers are slower
  (`× depth`). ≥ 5 s. "Just fast enough against the main helix twist": at e=1 the
  stars (0.13 turns/s) counter-rotate slightly faster than the helix (0.12 turns/s),
  giving the parallax the brief calls for. CURATE: confirm the differential reads.
- **Lightning strike (event-like)** — total life `BOLT_LIFE_MS = 1600` (≥ 1.5 s
  event minimum), with a 140 ms full-bright flash then a quadratic glow decay. Fast,
  but event-like motion is exempt from the 5 s cap and this clears the 1.5 s life
  floor.
- **Contrast / bold forms** — the strands are hard MAX-blended strokes (no
  gradients); pulses are discrete bright discs; stars are single dim points. The
  only fine texture is the intentional XOR moire, whose strength is a knob (default
  0). Brightness is full at silence, blooming ≤ 1.30× on the audio swell.
- CURATE: cube-ring render cost — at Twist 8 + Unstrnd (effective ~10 turns) on the
  200-wide cube canvas the per-strand sub-step count approaches `MAX_HELIX_STEPS`;
  confirm frame time is acceptable with `Cube` on, or lower the step density.
- CURATE: strand thickness vs. LED pitch — verify a 1.5 px strand reads as a clean
  line rather than a dotted spiral at high Twist, where adjacent windings crowd.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass, Claude autonomous session — implemented cylinder-primary double-helix ascension (spin-based), riding pulses, unstranding vertical separation + tightening twist, XOR-scratch moire composite, counter-rotating parallax starfield, self-contained lightning-strike event, palette-driven deep-red colors with Satori change-cache; Sync/TempoDiv present but intended off (beatless song); compiles clean against the arrange API | Chrome 4:05 finale centerpiece per chrome-brief.md §6 pattern #3 + Jeff's storyboard |
