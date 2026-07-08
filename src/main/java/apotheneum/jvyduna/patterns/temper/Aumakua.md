# Aumakua

Generative ochre Kolo-style cave-painting figures rising in family trees up the
exterior, dripping new figures below with genealogy-chart connectors; dark
interior echo.

> Sidecar design doc convention: this file lives beside `Aumakua.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

The hero centerpiece of **"Temper The Wound"** (Kalia Vandever), the opener of
"Communicating" — the genesis. Vandever's stated meaning is her parents'
separation and *how you move through that pain*, threaded through her Hawaiian
ancestry and **ʻaumākua** (ancestral guardian spirits that appear in dreams).
The visual is Jeff's Idea 2: a generator that draws cave-painting human figures
that slowly **rise** up the walls; as a figure ascends it **drips** children
below it, so lineage strands branch upward into continuous **family trees of
humanity** climbing the surface (childbirth + ancestry).

**Figure style (2026-07-08 restyle): Kolo/Kondoa rock art** (Tanzania), from
reference photos Jeff supplied. The signatures we draw: elongated proportions
with legs dominating (high hips), short forward-leaning torsos, LARGE oval
heads, bent two-segment limbs in a scissored walking stride, slight per-figure
forward pitch, a torso slightly thicker than the limbs. Bold ochre strokes, no
fine texture. This replaced the original symmetric stick-figure anatomy, which
read too much like a glyph and not enough like a painting.

The 2:32 G5 melodic-peak climax is honored by the `Bloom` trigger: every
figure's arms rise into the "orant" (raised-arm) pose and a burst of raised-arm
ancestors is born.

**Companion reuse (NOT built here):** the existing `Text` pattern spelling
**HEAL / BIRTH / HOPE** on the exterior is the accent layer that runs alongside
Aumakua per the plan; it is a separate pattern and out of scope for this class.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the piece needs the cube ring, the
  cylinder ring, and independent exterior/interior handling, none of which
  `ApotheneumRasterPattern` (cube-face-only) can reach.
- **Surfaces**: two independent generative systems (`Field`), one per surface,
  each with its own `Random` seed so the cube and cylinder grow different
  lineages from the same parameters:
  - cube ring — `SurfaceCanvas(200, 45)` copied to `Apotheneum.cube.exterior`.
  - cylinder — `SurfaceCanvas(120, 43)` copied to `Apotheneum.cylinder.exterior`,
    scaled by the **Cyl** knob (whole-field cylinder brightness).
- **Interior echo**: the SAME canvas is copied to `cube.interior` /
  `cylinder.interior` through `copyTo(surface, colors, mult, false)` with
  `mult = Inner` (default 0.25; cylinder interior additionally × Cyl) — a
  dimmer, softer echo of the exterior. Inner=0 makes the interior pure black (a
  fully held, protected dark); the exterior always renders at full brightness.
  This is the thematic exterior/interior birth split.
- **Geometry mapping**: figures live in canvas pixel space. `y = 0` is the TOP
  row, so a figure **rising** means its feet-row `y` **decreases** over time.
  X wraps (both surfaces are continuous rings). Figure anatomy is drawn as
  fractions of the figure's pixel height (see the Kolo geometry constants),
  with antialiased `lineWu` strokes (MAX blend — union brightness, never
  erase) and a filled-ellipse head.
- **Family-tree attachment**: an attached child's `y` is not integrated — it is
  **derived every frame** from its living parent
  (`y = parent.y + gapBase(parent) + height + sibJitter`), resolved generation
  by generation so parents are always positioned before children. That is what
  lets the **GenY** knob re-space every living tree instantly. When a parent
  retires off the top, its children detach in place (their last derived y is
  continuous) and self-integrate from then on. A `birthId` stamp guards
  against pool-slot reuse masquerading as a still-living parent.
- **Genealogy connectors**: right-angle chart connectors drawn EVERY frame
  between living parent-child pairs — vertical stub down from the parent's
  feet to a shared sibling rail (`railY = parent.y + gapBase/2`, deterministic
  per parent so siblings share one rank line), horizontal rail the short way
  around the ring, vertical drop to the child's head. Faint
  (`CONNECTOR_LEVEL = 0.30 of the child color`) and scaled by the child's glow
  envelope, so connectors fade in with each birth and out near the top. On
  detach they stop being drawn and the smear decay fades the residue.
- **Buffers / zero-alloc**: each `Field` preallocates a fixed pool of
  `MAX_FIGURES = 64` `Figure` objects in its constructor. Births (root or drip)
  reuse an inactive pool slot via `freeSlot()`; nothing is allocated in the
  render loop. The palette color cache (`genColor[]`, `cachedSwatch[]`) is
  preallocated.
- **Trails / smear**: instead of clearing, each `Field` calls
  `canvas.decay(decayMult)` per frame. A rising figure redrawn at full glow each
  frame leaves a fading "comet" smear below it (the gauzy gossamer quality).
  `Smear` sets the trail half-life.
- **Doors**: door openings do NOT shorten columns — every column carries the
  full point array; `Orientation.available(col)` is the only door signal
  (returns the count of non-occluded rows; the door occupies the BOTTOM 11
  rows of a 10-column opening). Figures crossing a door region mid-wall are
  simply invisible over the opening, which is acceptable. The **Genesis**
  entrance uses `available()` to pick a door-free column (see Triggers).

## No audio, no tempo (Temper convention)

This pattern is deliberately **free-running and silent**: no `AudioReactive`,
no `TempoLock`, no Sync/TempoDiv gating, no Meta trigger. The recording is
rubato with no fixed tempo grid, and every Temper pattern stays super chill
with **no sudden jumps** — births free-run on their idle timers, and all
choreography (Bloom at 2:32 etc.) is driven from the arrange timeline, not
from audio or tempo analysis. See `docs/composition/temper-brief.md`.

## Speed mapping

| Quantity | Ambient (Speed=0.3) | Peak (Speed=1) | Curve |
|---|---|---|---|
| Rise speed | ~2.98 rows/s (45-row cube ≈ 15.1 s) | 6.43 rows/s (≈ 7.0 s) | lin |

`Speed` drives **rise speed only** and defaults to **0.3** (CURATE — the
calmest, most patient song in the piece). Birth cadence is Density's job:
root-spawn interval `Ranges.exp(Density, 16 s, 2.5 s)` (CURATE). Sustained
rise respects the ≥5 s full-traversal cap even at Speed=1 (7.0 s cube / 6.7 s
cylinder — see compliance).

## Parameters

UI/registration order (do not deviate; keys are referenced by saved `.lxp`
files). Labels are the ≤7-char UI names; code field names are longer and
descriptive (e.g. `genSpacing`, `interiorLevel`, `cylinderLevel`).

| Param key | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `seed` | Seed | TriggerParameter | — | — | birth one root ancestor at the base of both surfaces |
| `bloom` | Bloom | TriggerParameter | — | — | raise all arms + burst of raised-arm ancestors (the 2:32 climax) |
| `genesis` | Genesis | TriggerParameter | — | — | fade to black, then a new figure's head immediately emerges from the bottom edge at a door-free column |
| `speed` | Speed | CompoundParameter | 0.3 | 0..1 | rise speed only (within the traversal cap) |
| `density` | Density | CompoundParameter | 0.5 | 0..1 | concurrent figure cap + root ancestor birth cadence |
| `figScale` | Scale | CompoundParameter | 0.5 | 0..1 | figure height (10..26 rows) |
| `ochre` | Ochre | CompoundParameter | 0.6 | 0..1 | 0 = palette colors per generation; 1 = ALL figures converge to one deep red-brown |
| `fertility` | Fertl | CompoundParameter | 0.6 | 0..1 | lineage branching probability per rising figure |
| `genY` | GenY | CompoundParameter | 0.4 | 0..1 | generational Y spacing; re-spaces ALL living figures live |
| `sway` | Sway | CompoundParameter | 0.4 | 0..1 | per-figure independent horizontal sway amplitude |
| `smear` | Smear | CompoundParameter | 0.5 | 0..1 | feedback-smear trail length (0.2..6 s half-life) |
| `interior` | Inner | CompoundParameter | 0.25 | 0..1 | interior brightness (0 = black interior) |
| `cylinder` | Cyl | CompoundParameter | 1.0 | 0..1 | brightness of all cylinder figures (whole cylinder field) |

Removed in the 2026-07-08 round (breaking for any earlier `.lxp`): `drift`
trigger, `energy` (→ `speed`), `warmth` (→ `ochre`), `dripChance` (→
`fertility`), `womb` (→ `interior`), `audio`, `sync`, `tempoDiv`, `meta`.

## Triggers

- `seed` (small) — one new root ancestor appears at the base of each surface; a
  single new figure begins climbing. Reads within a second or two as one new
  life at the bottom.
- `bloom` (large) — the birth bloom: every living figure throws its arms up
  (orant pose) and a burst of `3 + 3·Density` raised-arm ancestors is born.
  This is the 2:32 climax as a manual cue; reads instantly, resolves over the
  ~2.2 s birth-in of the new figures.
- `genesis` (large) — both fields fade to black (canvas cleared, all figures
  retired) and the new lineage begins IMMEDIATELY: one fresh ancestor per
  surface whose **head emerges from the bottom edge** (feet start below the
  wall, `y = H−1+height`, so only the head shows and the body reveals as it
  rises), at a column chosen clear of door openings (`available()` check,
  ±0.25·height margin, ≤16 bounded rng tries with fallback).

## GenY / family-tree geometry

- Gap from parent's feet to child's head:
  `gapBase = GAP_MIN(2) + GenY·GAP_RANGE(12) + GAP_H_FRAC(0.25)·parentHeight`
  rows (all CURATE). Deterministic per parent → siblings share a rank line;
  each child adds only a ±0.75-row `sibJitter` (was: full-height scatter).
- Generation pitch ≈ childHeight + gap ≈ 29 rows at defaults → the 45-row cube
  wall shows a parent + child with the grandchild's head entering from the
  bottom; deeper generations unroll as the tree rises (a drip blocked by the
  bottom edge retries on later frames instead of being consumed).
- CURATE: at figScale ≥ 0.9 with GenY ≥ ~0.6 barely one generation fits the
  wall at a time — keep Scale ≤ ~0.9 / GenY ≤ ~0.6 when a chart-like read
  matters.
- `MAX_GEN = 5` is a color-depth cap only (colors cycle over it); lineage
  branching stops at gen 5, `children < 2` per figure.

## Kolo figure geometry (fractions of height)

Hip 0.55 (elongated legs), shoulder 0.82 (short torso), head = filled ellipse
rx 0.10 / ry 0.08 (large, wider than tall), knee 0.26. Whole-figure forward
lean ∈ [−0.05, 0.14] of height (biased forward of the facing direction),
scissored two-segment legs (front knee/foot +0.10/+0.20, back −0.06/−0.22 of
the per-figure stride spread), two-segment arms lerped between down-forward
and orant by `armRaise`, ±0.03h arm asymmetry. Torso double-stroked (+0.7px)
at height ≥ 14 for painted body mass. Below height 12 limbs degrade to single
segments (the silhouette survives on proportions). All strokes are `lineWu`
antialiased; fractional joints let lean/stride/sway read sub-pixel.
CURATE: all fractions above are first-pass — tune the silhouette on hardware
against the Kolo reference photos, especially at small Scale.

## Simulation-principles compliance

- **Rise (sustained motion)** — the only continuous motion. Cube: 45 rows per
  full traversal. Ambient (Speed=0.3): 2.98 rows/s → **15.1 s**. Peak
  (Speed=1): 6.43 rows/s → **7.0 s**, inside the ≥5 s cap by design
  (`RISE_ROWS_PER_SEC_PEAK` is the named constant fixing this). Cylinder:
  43 rows / 6.43 = **6.7 s** at Speed=1. Nothing rescales the rise but Speed.
- **Sway (bounded oscillation, not a traversal)** — horizontal wobble, period
  ~6–16 s (`swayRate` 0.0004–0.001 rad/ms), fully independent per figure
  (per-figure phase AND rate). CURATE: confirm it reads as breathing, not
  jitter, at the new larger figure sizes.
- **Births (event-like)** — each newborn fades IN over `BIRTH_IN_MS = 2.2 s`
  (≥ 1.5 s event minimum) and then lives many seconds as it climbs, so no birth
  reads as a strobe flash even at peak cadence. Drips inherit the same birth-in.
- **Bloom / Genesis (event-like)** — instant state changes (arms up / clear);
  the resulting figures birth-in over 2.2 s and persist. ≥ 1.5 s of visual life.
- **Bold forms / contrast** — Kolo anatomy in solid ochre `lineWu` strokes and
  a filled ellipse head; no gradients, no fine texture. Depth haze is a single
  brightness step per lineage generation, not a gradient. CURATE: verify a
  gen-5 figure (72% depth × palette brightness) still reads on hardware at
  Ochre mid-values; at Ochre=1 all generations share one brightness by design.

## CURATE notes

Every value below is a first-pass guess for hardware curation (grep `CURATE:`
in the `.java` too):

- `RISE_ROWS_PER_SEC_PEAK = 6.43` — sets the 7.0 s cube traversal at Speed=1.
- `ROOT_INTERVAL_MS_SPARSE/DENSE = 16000 / 2500` — Density-driven ancestor
  cadence; roots are meant to be rare (most figures are drips).
- `GAP_MIN/GAP_RANGE/GAP_H_FRAC = 2 / 12 / 0.25` — the GenY spacing formula.
- `CONNECTOR_LEVEL = 0.30` — genealogy connector brightness; verify the chart
  lines read as connective tissue, not clutter.
- `OCHRE_TARGET = hsb(22, 72, 45)` — the deep red-brown every figure converges
  to at Ochre=1; tune hue/depth on hardware ("pure ochre — deeper, less
  bright").
- `OCHRE_HUE = 32`, `OCHRE_HUE_DEEP = 20`, empty-swatch sat 62 / bright ramp
  88→52 — the ochre fallback (only used with an empty project swatch); the real
  show should drive color from the Pastels swatch (Try Radish / Gareth).
- `Speed` default 0.3, `Inner` default 0.25 — the patient/dark defaults for the
  opener; confirm the interior reads as present-but-protected, not dead.
- Kolo geometry fractions (hip/shoulder/knee/head/lean/stride/spreads) and the
  degrade thresholds (12 / 14 rows) — tune the silhouette on hardware.
- `DRIP_RISE_BODIES = 1.1`, `f.children < 2`, `RAISE_BIAS = 0.15` — branching
  cadence and resting orant fraction.
- `FIG_HEIGHT_MIN/MAX = 10 / 26` — bigger than round 1 per the Kolo restyle;
  check legibility at both ends on the 43-row cylinder.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass, Claude autonomous session | Aumakua hero for `temper`: generative ochre cave-painting family tree rising up the exterior, dripping new figures; trombone-warmed glow + births; 2:32 bloom (raised-arm burst); womb-dark interior echo. Compiles against lx 1.2.2-SNAPSHOT + apotheneum 2.0.0. All tuning values marked CURATE for hardware. |
| 2026-07-08 | Jeff feedback round 2 | Kolo/Kondoa figure restyle (elongated legs, big oval heads, bent limbs, forward lean; height 10→26); GenY generational spacing (derived child y, live re-spacing, sibling rank lines) + genealogy-chart connectors replacing the one-shot strand; Warmth→Ochre with full convergence to deep red-brown at 1; Energy→Speed (rise only; cadence → Density); Drip→Fertl; Womb→Inner (key `interior`); new Cyl cylinder-brightness knob; Genesis now immediately emerges a head from the bottom at a door-free column; REMOVED: Drift trigger, all audio reactivity, Sync/TempoDiv/Meta (Temper is rubato — no tempo grid, no sudden jumps). |
