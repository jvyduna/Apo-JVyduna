# GlassRain

Window-rain streaking down the glass over a cold G-minor palette, with a single
warm bloom, 1/16 moiré at peaks, and pseudo-religious symbols condensing out of
the rain (rafters hero).

> Sidecar design doc convention: this file lives beside `GlassRain.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).
>
> **Status: BUILT.** All subsystems are implemented: the rain layer carries real
> droplet physics (per-drop TTL/lifetime, gentle horizontal wobble, variable head
> brightness, variable weight/width, ADD-blended head accumulation where beads
> merge); the symbol auto-condenses out of the rain on its own via an internal
> formation envelope (Form knob still overrides); and the moiré is a tempo-locked
> 2D interference shimmer with vertical structure (grating phases advanced on the
> 1/16 grid from the engine tempo's beat position). What remains are the numeric
> `CURATE:` values that can only be judged on the LEDs.

## Original / inspiration

Not a screensaver recreation — an original piece for song 3 of *Communicating*,
Lusine's **"Rafters"** (140 BPM / 70 half-time feel, G minor, 2:58). It is the
arc's **"traveling away from religion / first doubt"** beat: a young soul looking
up at the loft/church architecture it was raised inside, then boarding a train out
of it. The visual image is **rain running down a loft window** — cold, wet,
monochrome-leaning, anxious — the deliberate anti-*bliss* (song 2 is bright neon
screensaver-tech; this must feel organic and searching).

Per the arc critique (gap #6), GlassRain was **de-risked by splitting jobs**: this
hero owns **rain + all-arc dynamics + moiré**, and the morphing pseudo-religious
symbols ride the **shared `SymbolGlyphs` module** (built first, as thread-1
infrastructure) rather than being re-implemented here. The visual signature to
preserve: droplets streaking *down* (verticality = rafters/rain/train), one warm
bloom in an otherwise cold field, and abstracted symbols condensing out of the
rain and transmuting into one another.

## Rendering approach

- **Base class**: `ApotheneumPattern` — the piece needs both the cube ring and the
  cylinder plus exterior→interior copies; no 2D `Graphics` (so not
  `ApotheneumRasterPattern`, which is cube-face-only anyway).
- **Surfaces**: cube exterior ring (200×45) and cylinder exterior (120×43), each
  its own independent `RainField` (droplet pool + `SurfaceCanvas`). Interiors are
  an exact copy of the matching exterior via `copyCubeExterior()` /
  `copyCylinderExterior()` — rain looks the same from inside the loft.
- **Geometry mapping**: two `SurfaceCanvas` buffers, drawn in canvas space and
  `copyTo()`-copied column-major. Y = 0 is the TOP row, so droplets spawn at y = 0
  and fall toward increasing y — matching the physical top-down LED column order.
- **Buffers / zero-alloc**: every droplet field is a struct-of-arrays
  (`RainField.x/y/vy/active`) preallocated at `CUBE_DROPS = 160` / `CYL_DROPS =
  100`. The two `SurfaceCanvas` buffers, the `AudioReactive` and `Random` are all
  constructed once. Render allocates nothing; only the trigger callbacks (event
  rate) may allocate.
- **Door columns**: handled for free by `SurfaceCanvas.copyTo`, which clips each
  column to `column.points.length`, so droplet pixels below a door are simply not
  copied. No special-casing.

### The rain layer

Each `RainField` holds a pool of droplets. `step()` first `decay()`s the whole
canvas (the wet trail), then advances each active droplet down by `vy · dt` and
`lineMax`-paints the new segment, so trails stack (lighten-blend) where droplets
cross. A droplet retires when it falls off the bottom. Streak length is the
canvas decay half-life (Streak knob), *stretched* by the Bloom envelope for the
lush "dreamscape" smear.

Droplet **physics feel** is built: `spawnDrop()` gives each drop a random fall
speed (varying streak length), a **weight** (0..1 → a thin 1-column streak or a
fat 3-column bead with side beads past `FAT_WEIGHT`), a **head brightness**
(0.7..1.0), a gentle **horizontal wobble** (amplitude biased small via rand², so
only heavy beads visibly weave; slow rate), and a **TTL** (~one fall-time,
randomized: most drops fall off the bottom, a fraction evaporate mid-glass with
the head fading out over the last `1 - LIFE_FADE_START` of life). In `step()` the
head is **ADD-blended** so two beads crossing one pixel accumulate/brighten
(merge), while the trail is MAX-blended at `TRAIL_LEVEL` of the head color so the
head reads brighter than its wet trail. CURATE: tune wetness/weight
(`WOBBLE_MAX_COLS`, `FAT_WEIGHT`, `TRAIL_LEVEL`, `LIFE_FADE_START`) on hardware.

The **`Smooth`** knob (house motion-blur/AA convention) softens both the fall and
the edges. `paintStreak()` crossfades each streak by `smooth`: the wet trail
blends a pixel-snapped integer `lineMax` (weight `1-smooth`, steppy edges) against
a Xiaolin-Wu antialiased sub-pixel `lineWu` (weight `smooth`, soft sub-pixel
edges), and the ADD-blended head's fall position uses a **fractional-row
crossfade** gated by `smooth` — at `smooth = 0` the head snaps entirely onto the
nearest row (steppy vertical stepping), at `smooth = 1` it splits `(1-frac)/frac`
across the two straddled rows so the fall reads as continuous sub-pixel motion.
Both paths are the existing `SurfaceCanvas` primitives (`lineMax`, `lineWu`,
`setBlend`) with `scaleRGB` coverage weights, so it stays zero-alloc in `step()`.

### Symbol condensation (shared module)

The morphing symbols are **not** drawn here. `renderField()` calls
`SymbolGlyphs.morph(canvas, prevGlyph, curGlyph, morphT, cx, cy, argb, scale)`,
faded in by the `Form` knob and warmer than the rain (the "meaning" surfacing).
On the cube ring the glyph is stamped once per face (`cx = 25/75/125/175`); on the
cylinder three times around (`cx = 20/60/100`). A morph starts whenever the
`Glyph` knob changes or the `Condense` trigger advances to the next symbol, and
`morphT` eases 0→1 over `MORPH_SECONDS`.

Auto-formation is built: a glyph change (from `Condense` **or** the operator
moving `Glyph`) starts both the cross-dissolve and an internal formation
envelope (`autoForm`) that ramps 0→1 over `MORPH_SECONDS`, holds fully-formed for
`AUTO_FORM_HOLD_SECONDS`, then eases back to 0 — so a symbol condenses out of the
rain and re-dissolves on its own with no operator input. The effective form is
`max(Form knob, autoForm)`, so the `Form` knob / timeline still overrides and
scales it. CURATE: symbol scale (2 → 22 px), repeat positions/counts,
`MORPH_SECONDS`, and `AUTO_FORM_HOLD_SECONDS`.

### Moiré + sheet-wash overlays

- **Moiré** (`drawMoire`) — built: two nearby vertical gratings (`MOIRE_FREQ_A/B`)
  beat into horizontal interference fringes, and a third vertical grating
  (`MOIRE_FREQ_V`) adds **vertical structure** so the overlay reads as a
  shimmering 2D interference sheet, not static bars. Both grating phases
  (`moirePhaseH/V`) are advanced on the **1/16 tempo grid** — derived from the
  engine tempo's continuous beat position × 4 (a quarter beat = 4 sixteenths)
  times `MOIRE_PHASE_H/V_PER_16` — so the fringes sweep and the shimmer drifts in
  time. Max-blended over the rain, gated by the `Moire` knob and flickered by
  treble. Implemented
  separably (per-column fringe × per-row shimmer, precomputed into preallocated
  scratch arrays) so the inner loop is a multiply, not a trig call — zero-alloc.
- **Sheet-wash** (`drawWash`) — the `SheetWash` trigger sends a bright horizontal
  band sweeping down the glass over `WASH_SECONDS`, one gesture shared across both
  surfaces (rinse on a section change).

## Candidate alternative layer (NOT built)

A **cellular-automata "life" / blinkfade** field (Jeff's Idea 1, and the "fearful
of stagnation" thesis). Deferred: CA legibility on the LED pitch is unproven and
can read as noise (critique gap #6 flags the CA backup as legibility-risky).
Prototype large-cell, beat-locked, trail-decayed CA **separately** before adding
it as a layer or a sibling `Life` pattern. Not in this class.

## Audio mapping

`AudioReactive`, ticked first line of `render()`, gated by the **Audio depth knob**
(`audio.setDepth(audioDepth)`):

- **level** — scales droplet spawn density (`density · (1 + 0.6·level)`, clamped):
  louder passages rain harder. CURATE the 0.6 coefficient.
- **bassHit()** — a heavy-drop spatter (`BURST_DROPS · depth()` droplets on the
  cube), so kicks throw water. Scaled by `depth()` so it fades in with the knob.
- **trebleRatio** — flickers the moiré overlay intensity (`· (1 + treble excess)`),
  so hissy/bright material makes the interference shimmer.

**Depth knob / silence behavior**: `Audio` defaults to 0 = pure screensaver. At
depth 0 (or in true silence) the level term is 0, hits never fire, and the rain
runs purely on the Density/Energy knobs; the moiré runs on the
`Moire` knob alone. Nothing re-gates audio locally — the knob is the single master
gate. At knob = 1 the rain visibly breathes with the mix, kicks spatter, and the
moiré flickers on treble.

## Tempo mapping

No tempo gating — droplet timing free-runs (Sync/TempoDiv/Meta convention retired
2026-07-08; free-run behavior = the old Sync-off path: continuous density-driven
rain, no on-grid burst pulse). The **moiré** still derives its grating phases from
the engine tempo's continuous 1/16 beat position (see the Moiré section), gated
only by its own `Moire` knob.

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve |
|---|---|---|---|
| Droplet fall speed | 12 rows/s (~3.75 s down the cube) | 22 rows/s (~2.05 s) | lin |
| Base spawn rate | 2 drops/s | 14 drops/s | lin (× Density) |

Bloom (a **separate** timeline-automated envelope, not Energy) drives the cold→warm
palette lerp and the trail-half-life stretch — that is what carries the arc; Energy
is the moment-to-moment intensity knob. Sustained motion (droplet fall) stays
event-like and above the 1.5 s minimum even at e=1 (see compliance).

## Parameters

UI/registration order (do not deviate; keys/labels are referenced by saved .lxp):

1. Triggers (`dropBurst`, `condense`, `wash`)
2. `energy`
3. Pattern params (`density`, `streak`, `bloom`, `glyph`, `form`, `moire`, `smooth`)
4. `audio`

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `dropBurst` | DropBurst | TriggerParameter | — | — | spatter a heavy-drop cluster over both surfaces |
| `condense` | Condense | TriggerParameter | — | — | advance to the next religious symbol, starting a morph |
| `wash` | SheetWash | TriggerParameter | — | — | bright sheet of water washes down the glass |
| `energy` | Energy | CompoundParameter | 0.3 | 0..1 | droplet density + fall speed |
| `density` | Density | CompoundParameter | 0.4 | 0..1 | how many droplets are on the glass at once |
| `streak` | Streak | CompoundParameter | 0.5 | 0..1 | streak/trail length (canvas decay half-life) |
| `bloom` | Bloom | CompoundParameter | 0 | 0..1 | arc envelope: cold spine → warm maj7 bloom + trail smear |
| `glyph` | Glyph | EnumParameter | CROSS | 9 symbols | which symbol is condensing; changing it morphs |
| `form` | Form | CompoundParameter | 0 | 0..1 | how fully the symbol has condensed (0 = pure rain) |
| `moire` | Moire | CompoundParameter | 0 | 0..1 | 1/16-grid moiré shimmer over the rain (peaks) |
| `smooth` | Smooth | CompoundParameter | 1.0 | 0..1 | motion blending + antialiasing (0 = steppy/pixel-snapped, 1 = smooth sub-pixel + AA) |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure screensaver) |

Timeline usage: automate **Bloom** 0→1 at 1:22, hold across 1:56–2:20 (peak
2:03), back to 0 by 2:44; **Moire** up only on the 1:56–2:20 plateau; **Form**
pulsed on the symbol moments; **Energy** low in the "young" intro, up on the
plateau, thin in the coda, near-0 in the outro.

## Triggers

Three triggers, small → large:

- `dropBurst` (small) — a spatter of ~18 fast droplets appears at once; reads in
  a fraction of a second, resolves as they fall. Wired to kicks / bass hits.
- `condense` (medium) — advances the `Glyph` selection to the next symbol and
  starts a `MORPH_SECONDS` cross-dissolve from the old glyph; reads over ~3 s as
  one symbol transmutes into another. Wired to the 2:16 vocal cluster.
- `wash` (large) — a bright sheet sweeps the full height of both surfaces over
  `WASH_SECONDS`; a whole-room rinse gesture. Wired to section changes.

## Simulation-principles compliance

Show the math (fastest sustained motion at default energy AND at energy = 1):

- **Droplet fall (event-like, not sustained scroll)** — a droplet is a single
  transient streak, not a full-field traversal. Cube ring is 45 rows: at
  `FALL_MIN = 12` rows/s a drop lives ~3.75 s; at `FALL_MAX = 22` rows/s (energy
  1, ×1.2 random) ~1.7 s — above the **1.5 s event-life minimum**. There is
  deliberately **no continuous full-sculpture scroll**, so the ≥5 s traversal cap
  is not engaged by any sustained motion. CURATE: confirm the fastest drops at
  e=1 still read as discrete droplets, not a strobing sheet; raise `FALL_MAX` floor
  or the random spread if they blur.
- **Sheet-wash (event-like)** — 2.5 s from top to bottom, ≥ 1.5 s minimum,
  triggered (not sustained).
- **Symbol morph (event-like)** — 3 s cross-dissolve, ≥ 1.5 s minimum, no fast
  spatial travel (the glyph condenses in place).
- **Moiré** — a shimmer/flicker texture, low brightness, no bulk translation. Its
  phase advances on the 1/16 grid at `MOIRE_PHASE_H/V_PER_16` ≈ 0.3–0.5 rad per
  sixteenth (a full cycle over ~a bar), slow enough to read as shimmer, not
  travel. CURATE: confirm the drift rate reads as shimmer on hardware.
- **Contrast/brightness** — cold near-monochrome field with deep near-black
  negative space; the single warm bloom is the only saturation spend. Symbols are
  bold 22 px forms (no fine texture). CURATE: verify the cold droplet color at
  silence still reads on hardware, and that the warm bloom is clearly warmer.

## CURATE notes (grep `CURATE:` in the .java)

- Droplet physics (`FALL_MIN/MAX`, `RAIN_*_PER_SEC`, `BURST_DROPS`)
  — all first-pass numbers, untuned on hardware.
- Trail half-life range + `BLOOM_TRAIL_STRETCH` — the smear amount for the bloom.
- `SYMBOL_SCALE`, `MORPH_SECONDS`, `AUTO_FORM_HOLD_SECONDS`, symbol repeat
  positions/counts.
- Moiré tuning values: `MOIRE_FREQ_A/B/V`, `MOIRE_PHASE_H/V_PER_16`,
  `MOIRE_MAX_BRIGHT` — the interference math is built and tempo-locked; these are
  the pitch/brightness/drift numbers to eyeball on the LEDs.
- Droplet physics values: `WOBBLE_MAX_COLS`, `FAT_WEIGHT`, `TRAIL_LEVEL`,
  `LIFE_FADE_START` — the wetness/weight feel.
- `COLD_FALLBACK` / `WARM_FALLBACK` — picked blind; only used on an empty swatch.
- `warm = 3rd swatch color` — assumes the reserved warm accent sits at swatch
  index 2 (per the brief's Gareth `#33CCFF/#CC99FF/#FFB38A` triad).

## TODO notes

All round-1 build-out `TODO`s are resolved (droplet physics, auto-formation
envelope, tempo-locked 2D moiré). No open code TODOs remain — only the numeric
`CURATE:` values above, which need the physical LEDs to judge.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass, Claude autonomous session | Stub hero for rafters: rain layer + Bloom dynamics envelope + moiré + shared-`SymbolGlyphs` condensation, all params wired; physics/moiré/formation-timing left as TODO for hardware curation. Symbol drawing delegated to the shared module per critique gap #6 (split GlassRain's jobs); CA "life" layer explicitly deferred (legibility risk). |
| 2026-07-07 | Full build-out, Claude session | Replaced the three stubs: (1) droplet physics — per-drop TTL/lifetime with end-of-life head fade, small rand²-biased horizontal wobble, variable head brightness and weight/width (thin streak vs. fat 3-column bead), and ADD-blended heads so merging beads accumulate; (2) auto-formation envelope — a glyph change (Condense or knob) auto-ramps Form over `MORPH_SECONDS`, holds, then dissolves, with effective form = `max(knob, envelope)`; (3) moiré — tempo-locked 2D interference (horizontal fringe pair × vertical grating, both phases advanced on the 1/16 grid via `beatPosition()`), separable/zero-alloc via per-field scratch arrays. Also made DropBurst drops fast (energyPct 100, matching its stated intent). Params/labels unchanged. |
| 2026-07-08 | Removed Sync/TempoDiv/Meta + TriggerBag/TempoLock (convention retired; free-run behavior = old Sync-off path) | Project-wide retirement of the Sync/TempoDiv + Meta pattern-control convention. Droplet spawning now always free-runs (the on-grid `GRID_BURST` pulse is gone); the moiré's 1/16 phase lock is kept, reading the engine tempo's beat position directly. |
| 2026-07-08 | Added `Smooth` (house AA/interp convention); `energy` retained unchanged | Adopted the house motion-blur/antialiasing `Smooth` knob (default 1.0), registered after the pattern params and before `audio`. Wired into `paintStreak()`: `smooth` crossfades the wet trail between a pixel-snapped `lineMax` and a Wu-AA `lineWu`, and gates a fractional-row crossfade for the ADD-blended head's sub-pixel fall — 0 = steppy/pixel-snapped, 1 = smooth sub-pixel + antialiased. Reuses existing `SurfaceCanvas` primitives + `scaleRGB` coverage, zero-alloc in `step()`. `energy` deliberately kept as-is: rafters is a rigid 140 BPM song and `energy` drives BOTH droplet density AND fall speed (not speed-only), so it was not renamed or retimed. |
