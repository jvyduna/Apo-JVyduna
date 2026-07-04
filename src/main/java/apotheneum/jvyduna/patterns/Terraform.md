# Terraform

Evolving terrain skyline: uplift raises mountains, erosion ages them, and the sea
breathes with the music.

> Sidecar design doc convention: this file lives beside `Terraform.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

**Designer's-choice interpretation — no confirmed original screensaver.** The name
evokes the terrain-generation / "terraforming" family of demos and screensavers,
but this design was not traced to a specific verified original. The visual
signature being built: a wrap-around mountain skyline in hard elevation bands
(water / sand / grass / rock / snow) that geologically evolves — mountains rise,
erode, and drown or emerge as the sea level breathes with the music. The
emotional core is the sea: quiet music floods the land into a calm ocean; loud
music drains it and the full mountain range emerges — spanning ambient↔peak
with zero mode switches.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the pattern renders per-column stacks
  directly from a 1D heightfield; no 2D canvas needed (`SurfaceCanvas` was
  considered and skipped: every pixel's color is a pure function of its column's
  height + its elevation, so there is nothing to persist or blit).
- **Surfaces**: cube exterior (200-column × 45-row wrap-around ring) and cylinder
  exterior (120 × 43) each carry an **independent heightfield**; the **sea level
  is one shared fraction** of surface height, so the ocean reads as a single
  world across both chambers. Interiors are copied from the exteriors via
  `copyCubeExterior()` / `copyCylinderExterior()`.
- **Heightfields**: per surface, `target[]` (receives uplift, diffusion,
  subsidence) and `height[]` (displayed; chases `target[]` under the per-point
  rate limit of full height in ≥ `RISE_FULL_SEC` = 5 s), plus a `scratch[]` for
  the diffusion stencil. All arrays, plus the `Random`, are allocated in the
  constructor — zero allocation in the render path.
- **Per-pixel rule** (bottom-up, `elev` = rows above the physical ground,
  computed as `fullHeight - 1 - yi` so door-shortened columns stay aligned):
  - `elev ≤ sea` → water; the single top water row is the bright **waterline**
    accent (specular highlight). Water draws in front of submerged land, so the
    waterline is one continuous ring at the sea surface.
  - `sea < elev ≤ terrain` → land, banded by **absolute elevation**: sand /
    grass / rock / snow. Hard edges, no gradients.
  - above both → sky (black).
- **Door-column handling**: door columns physically lack their lowest 11 rows;
  since elevation is indexed from the column top, the sea surface and band
  thresholds line up across doors, and rows below the door top are simply not
  drawn (guarded by `column.points.length`).

### Colors — fixed natural hues (decision)

Fixed semantic hues rather than the project palette: terrain legibility depends
on water reading blue, grass green, snow white — an arbitrary swatch breaks the
metaphor. `BandShift` provides the "palette/band offset" variation instead.

| Band | HSB | Intent |
|---|---|---|
| Sky | black | negative space; skyline silhouette |
| Deep water | 215°, 85%, 40% | dim saturated blue, recedes |
| Waterline | 190°, 35%, 100% | 1-row bright pale cyan accent |
| Sand | 45°, 60%, 85% | warm bright beach |
| Grass | 115°, 85%, 55% | mid green |
| Rock | 25°, 30%, 45% | dim gray-brown |
| Snow | 210°, 4%, 100% | near-white, slightly cool |

CURATE: all seven band colors are unverified on hardware — check water-vs-sky
separation at distance, waterline pop, and rock-vs-sand contrast.

Band thresholds (fractions of full height, before `BandShift`): sand < 0.30,
grass < 0.55, rock < 0.80, snow ≥ 0.80. Band heights at defaults: cube (45 rows)
13.5 / 11.25 / 11.25 / 9; cylinder (43 rows) 12.9 / 10.75 / 10.75 / 8.6 — all
≥ 4 rows (the 1-row waterline is a deliberate accent, not a band).
CURATE: threshold fractions 0.30/0.55/0.80 unverified.

## Audio mapping

| Tap | Drives |
|---|---|
| `level` (slow-smoothed, τ = 4 s) | Sea level goal: `seaBias − 0.45 · slowLevel`, clamped to [0.05, 0.9]. Quiet → sea rises and drowns the land; loud → sea drains, mountains emerge. |
| `bassHit()` + `bassRatio` | At energy ≥ 0.55: each bass transient raises a new mountain (uplift bump); amplitude scales with `bassRatio`. |
| `treble` | Snowcap sparkle depth (with the `Sparkle` param), gated to energy > 0.5. |

**Silence behavior**: all taps decay to 0 → the sea settles at `SeaBias`
(default 0.55 ≈ mid-level), the Poisson uplift timer keeps slowly raising new
mountains, and erosion keeps aging them — a calm, self-evolving archipelago.
Designed to look good with zero audio.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Spontaneous uplift rate (per surface) | 0.06 /s (~1 per 17 s) | 0.5 /s (~1 per 2 s) | exp |
| Uplift amplitude factor (× `Uplift` param × height) | 0.40 | 0.90 | lin |
| Uplift bump sigma (fraction of ring) | 0.02 (4 cube cols) | 0.045 (9 cube cols) | lin |
| Diffusion coefficient (× `Erosion` param) | 0.4 /s | 2.0 /s | lin |
| Subsidence time constant | 120 s | 30 s | exp |
| Bass-hit uplifts | off (< 0.55) | on | gate |
| Snow sparkle gate | 0 (≤ 0.5) | 1 (≥ 0.8) | lin ramp |

The terrain chase rate (height/5 s) and sea rates are **not** energy-scaled —
the ≥ 5 s full-traversal cap holds at every energy.

## Parameters

UI order: triggers first, Energy, pattern parameters, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `cataclysm` | Cataclysm | TriggerParameter | — | — | huge ridge + ≤ 0.5 s whole-ring shake, then settles |
| `flood` | Flood | TriggerParameter | — | — | sea ramps to max, holds 2 s, drains back |
| `reseed` | Reseed | TriggerParameter | — | — | morph to a fresh random terrain over ~5 s |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (0–0.4 ambient, 0.6–1.0 = 160 BPM regime) |
| `upliftSize` | Uplift | CompoundParameter | 0.5 | 0..1 | amplitude of new mountain uplifts |
| `erosion` | Erosion | CompoundParameter | 0.4 | 0..1 | diffusion + subsidence rate |
| `seaBias` | SeaBias | CompoundParameter | 0.55 | 0.15..0.85 | sea level in silence (fraction of height); music lowers it |
| `bandShift` | Bands | CompoundParameter | 0 | -0.2..0.2 | shift all band thresholds up/down (fraction of height) |
| `sparkle` | Sparkle | CompoundParameter | 0.5 | 0..1 | treble snowcap shimmer (high energy only) |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

## Triggers

- `cataclysm` — adds a mountain-range ridge (0.85 H central peak + two 0.55 H
  shoulders, σ = 5% of ring) to both surfaces' targets and starts the shake: a
  spatial sine ripple (±2.5 rows, 7 Hz, decaying) across every column for
  0.45 s. The ridge then *grows* under the rate limit (~4.3 s to full height)
  and erodes over the following tens of seconds. This is the pattern's one
  event-like exception: shake ≤ 0.5 s, total visual life (shake + ridge rise +
  settle) ≫ 1.5 s. CURATE: shake amp 2.5 rows / 7 Hz / 0.6 rad-per-column
  wavelength unverified — must read as a tremor, not flicker.
- `flood` — sea goal overridden to 0.97 H at the flood rate (full sweep in 5 s;
  ~4 s from a typical drained sea), holds 2 s at max (near-total drowning, one
  bright waterline near the crown), then drains at the normal 8 s/full-sweep
  rate back to the audio-driven level (~4 s for a typical drop).
- `reseed` — re-rolls both targets (random base 0.05–0.15 H plus 8 cube / 6
  cylinder random bumps, amp 0.25–0.8 H); displayed heights morph there under
  the rate limit, so the world transforms over ≤ 5 s.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `erosion` | 0..1 (full) | candidate | erosion rate |
| `upliftSize` | 0..1 (full) | candidate | uplift size |
| `bandShift` | -0.12..0.12 | candidate | band offset; sub-range keeps snow ≥ ~4 rows |
| `seaBias` | 0.3..0.75 | candidate | sea level bias; sub-range avoids near-empty/near-full seas |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

- **Per-point terrain rate limit**: `height[]` chases `target[]` at
  `fullHeight / RISE_FULL_SEC` = 45/5 = **9 rows/s** (cube; 8.6 rows/s
  cylinder), independent of energy. A full-height (45-row) change therefore
  takes exactly **5 s** at default energy *and* at energy = 1. ✓
- **Sea level**: normal rate = full sweep / 8 s; flood rate = full sweep / 5 s.
  Both ≥ 5 s per full sculpture traversal at all energies. ✓
- **Uplift events**: a default-size bump (0.5 × 0.575 ≈ 0.29 H ≈ 13 rows) grows
  in ~1.4 s; worst case (Uplift = 1, e = 1: 0.9 H) in 4.5 s. These are localized
  event-like births (σ ≤ 9 of 200 columns) whose visual life continues for tens
  of seconds of erosion — well past the 1.5 s event minimum; whole-sculpture
  change remains capped by the chase rate above. ✓
- **Erosion spread**: diffusion is local smoothing, not a traveling front —
  worst-case k = 2 /s gives an RMS spread of √(2kt) columns (≈ 2 columns/s
  initially, decelerating); crossing even half the cube ring (100 columns)
  would take ~2500 s. ✓
- **The one exception**: the cataclysm shake — 0.45 s of ±2.5-row ripple —
  documented above; total cataclysm visual life ≥ 4.3 s (ridge rise) plus
  settle. ✓
- **Contrast/brightness**: bold solid bands ≥ 4 rows tall with hard edges, a
  black sky for silhouette, a dim sea vs. a single full-bright waterline row,
  no gradients, no fine texture. Snow sparkle is the only sub-band detail:
  ≤ 45% brightness modulation on an already-white ≥ 8.6-row cap, gated to high
  energy. CURATE: sparkle depth 0.45 and treble scaling (×1.6) unverified —
  reduce if it reads as noise at distance.

Other unverified constants: CURATE: `SEA_SWING` 0.45 and `SLOW_LEVEL_TAU_SEC`
4 s (does the sea audibly "breathe" with song dynamics without pumping?).
CURATE: Poisson rates 0.06→0.5 /s (is ambient lively enough / peak not
cluttered?). CURATE: subsidence taus 120→30 s (do old ranges linger too long?).
CURATE: seed bump counts (8 cube / 6 cylinder) and base height 0.05–0.15 H.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | as planned; no visual verification yet |
