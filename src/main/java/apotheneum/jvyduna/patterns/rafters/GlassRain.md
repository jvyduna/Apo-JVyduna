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
> merge); the symbols are **true vector glyphs** (shared `SymbolGlyphs`,
> rasterized) that **condense out of the rain as liquid** — a per-cell fill sim
> with gravity, surface-tension spread and bottom-lip overflow, gated by a
> `Symbols` boolean; and the moiré is a tempo-locked 2D interference shimmer
> (grating phases advanced on the 1/16 grid from the engine tempo's beat
> position). Glyphs read correctly inside and out via the reverse-normal mirror.
> What remains are the numeric `CURATE:` values that can only be judged on the LEDs.

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
  its own independent `RainField` (droplet pool + liquid grids + `SurfaceCanvas`).
  Interiors are the **reverse-normal mirror** of the matching exterior:
  `canvas.copyTo(exterior)` then `canvas.copyToMirrored(interior)`, so the glyphs
  read un-reversed from inside (see *Reverse-normal legibility* below). Rain is
  mirrored too — statistically invisible.
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

### Symbol condensation — the liquid model (shared vector glyphs)

The glyph shapes are **not** drawn here — they ride the shared `SymbolGlyphs`
module, now **true vector art rasterized to a coverage mask** (see
`SymbolGlyphs.md`). GlassRain does not stamp the glyph as pixels; it uses the
mask as the *container for a liquid sim* so the symbol literally **condenses out
of the rain**.

Per surface, `RainField` carries three canvas-sized grids (all preallocated):
`mask` (glyph coverage 0..1), `fill` (liquid depth 0..1+), and `overflow` (the
bottom-lip cells). The lifecycle, driven by the **`Symbols`** boolean and the
continuous **`Glyph`** selector:

1. **Build the mask.** When `Symbols` turns on, or `Glyph` floors to a new
   index, or `Smooth` changes, `rebuildMask()` rasters the vector glyph via
   `SymbolGlyphs.coverage(sym, GLYPH_PX, GLYPH_PX, smooth)` and stamps it at each
   instance center (`CUBE_CENTERS = {25,75,125,175}`, `CYL_CENTERS = {20,60,99}`),
   then precomputes the lip cells (a masked cell with no masked cell directly
   below — a slope-0 / concave-up discontinuity).
2. **Inflow.** In `RainField.step`, a rain drop whose head crosses a masked cell
   is **absorbed** into that cell's `fill` and retired — drops "flow into" the
   form. (Liquid drips are exempt so released water isn't re-absorbed.)
3. **Flow (`stepLiquid`).** A gravity sweep (bottom-up) sinks liquid where it
   can; a lateral-leveling sweep spreads what can't sink into neighboring glyph
   cells (surface tension) — so a glyph fills from its low points up, "getting
   stuck until saturated, then flowing to neighboring regions until full."
4. **Overflow.** Once a bottom-lip cell passes `SPILL_LEVEL`, the excess spills
   as a new liquid-colored **drip** just below the lip (rate-limited to
   `MAX_SPILL_PER_FRAME`), which runs on down the glass.
5. **Color.** The liquid color eases from the cold droplet color toward the warm
   **3rd swatch** (`warm`) over `TINT_SECONDS` — "slowly change their liquid color
   to the palette 3 swatch."
6. **Release.** When `Glyph` changes or `Symbols` turns off, `releaseLiquid()`
   converts every filled column into a downward drip and zeroes `fill` — the held
   liquid is "instantly released to continue to flow down," and a fresh glyph
   starts empty.

`renderLiquid()` lights each glyph cell at `fill × mask` in the liquid color
(MAX over the rain), so the symbol appears exactly as it fills. Antialiasing of
the glyph edge is the `Smooth` knob (it dials the mask's coverage sharpness in
`SymbolGlyphs`). CURATE: `GLYPH_PX`, instance positions/counts, `INFLOW_PER_DROP`,
`FLOW_DOWN`/`FLOW_SIDE` (viscosity/surface-tension), `SPILL_LEVEL`, `TINT_SECONDS`.

### Reverse-normal legibility

Interior and exterior LED layers face opposite ways, so identical content reads
mirror-reversed from the far side. The glyphs follow the reverse-normal rule
worked out for the Text pattern: draw the exterior straight
(`canvas.copyTo(exterior)`) and the interior horizontally mirrored
(`canvas.copyToMirrored(interior)`). Because `copyToMirrored` mirrors about the
**canvas center**, the glyph instances are placed **symmetric about the surface's
horizontal center** (`{25,75,125,175}` sum in pairs to ~199; `{20,60,99}` pair to
119) so each identical glyph maps onto its mirror partner and ends up individually
un-reversed and in place (a ±0.5px even-width offset is intentional and
invisible). Rain is mirrored too — statistically invisible; keeping rain
identical inside/out would need a second buffer and was deliberately not built.

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

UI/registration order (do not deviate; keys/labels are referenced by saved .lxp).
**Breaking change (2026-07-08):** `condense` (trigger) → `symbols` (boolean),
`glyph` (enum) → `glyph` (continuous), and `form` was **removed** — old .lxp
projects lose those three values on load.

1. Triggers (`dropBurst`, `wash`)
2. `energy`
3. Pattern params (`density`, `streak`, `bloom`, `symbols`, `glyph`, `moire`, `smooth`)
4. `audio`

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `dropBurst` | DropBurst | TriggerParameter | — | — | spatter a heavy-drop cluster over both surfaces |
| `wash` | SheetWash | TriggerParameter | — | — | bright sheet of water washes down the glass |
| `energy` | Energy | CompoundParameter | 0.3 | 0..1 | droplet density + fall speed |
| `density` | Density | CompoundParameter | 0.4 | 0..1 | how many droplets are on the glass at once |
| `streak` | Streak | CompoundParameter | 0.5 | 0..1 | streak/trail length (canvas decay half-life) |
| `bloom` | Bloom | CompoundParameter | 0 | 0..1 | arc envelope: cold spine → warm maj7 bloom + trail smear |
| `symbols` | Symbols | BooleanParameter | off | on/off | condense the glyph as liquid; off releases the held liquid |
| `glyph` | Glyph | CompoundParameter | 1 | 1..9 | which symbol condenses (continuous, floored to a glyph) |
| `moire` | Moire | CompoundParameter | 0 | 0..1 | 1/16-grid moiré shimmer over the rain (peaks) |
| `smooth` | Smooth | CompoundParameter | 1.0 | 0..1 | motion blending + antialiasing incl. the glyph mask edge |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure screensaver) |

Timeline usage: automate **Bloom** 0→1 at 1:22, hold across 1:56–2:20 (peak
2:03), back to 0 by 2:44; **Moire** up only on the 1:56–2:20 plateau; toggle
**Symbols** on for the symbol moments (and step **Glyph** across its range to
transmute one symbol into the next — each step releases the old liquid and pools
the new); **Energy** low in the "young" intro, up on the plateau, thin in the
coda, near-0 in the outro.

## Triggers + Symbols

Two triggers plus the `Symbols` gesture, small → large:

- `dropBurst` (small) — a spatter of ~18 fast droplets appears at once; reads in
  a fraction of a second, resolves as they fall. Wired to kicks / bass hits.
- `symbols` (medium, a **boolean** not a trigger) — turning it on begins
  condensing the selected glyph out of the rain as liquid (fills over seconds);
  stepping `Glyph` transmutes to the next symbol (releases the old liquid, pools
  the new); turning it off releases the held liquid to run down. Wired to the
  symbol section (e.g. on at the 2:16 vocal cluster, glyph steps across it).
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
- **Symbol condensation (event-like)** — the glyph fills with liquid over several
  seconds and holds; no fast spatial travel (it forms in place). Release converts
  it to drips that fall event-like (single streaks), above the 1.5 s minimum.
- **Moiré** — a shimmer/flicker texture, low brightness, no bulk translation. Its
  phase advances on the 1/16 grid at `MOIRE_PHASE_H/V_PER_16` ≈ 0.3–0.5 rad per
  sixteenth (a full cycle over ~a bar), slow enough to read as shimmer, not
  travel. CURATE: confirm the drift rate reads as shimmer on hardware.
- **Contrast/brightness** — cold near-monochrome field with deep near-black
  negative space; the single warm bloom / warm liquid symbol is the only
  saturation spend. Glyphs are bold ~24 px forms (no fine texture). CURATE: verify
  the cold droplet color at silence still reads on hardware, and that the warm
  liquid is clearly warmer.

## CURATE notes (grep `CURATE:` in the .java)

- Droplet physics (`FALL_MIN/MAX`, `RAIN_*_PER_SEC`, `BURST_DROPS`)
  — all first-pass numbers, untuned on hardware.
- Trail half-life range + `BLOOM_TRAIL_STRETCH` — the smear amount for the bloom.
- **Liquid-symbol sim**: `GLYPH_PX` (glyph size), `CUBE_CENTERS`/`CYL_CENTERS`
  (positions/counts, kept symmetric for the reverse-normal mirror),
  `INFLOW_PER_DROP` (fill rate), `FLOW_DOWN`/`FLOW_SIDE` (viscosity / surface
  tension), `SPILL_LEVEL` + `MAX_SPILL_PER_FRAME` (overflow feel), `TINT_SECONDS`
  (how fast the liquid warms to swatch-3), `MASK_MIN` (glyph membership
  threshold). All read by eye on the LEDs — the whole sim is new this pass.
- Moiré tuning values: `MOIRE_FREQ_A/B/V`, `MOIRE_PHASE_H/V_PER_16`,
  `MOIRE_MAX_BRIGHT` — the interference math is built and tempo-locked; these are
  the pitch/brightness/drift numbers to eyeball on the LEDs.
- Droplet physics values: `WOBBLE_MAX_COLS`, `FAT_WEIGHT`, `TRAIL_LEVEL`,
  `LIFE_FADE_START` — the wetness/weight feel.
- `COLD_FALLBACK` / `WARM_FALLBACK` — picked blind; only used on an empty swatch.
- `warm = 3rd swatch color` — assumes the reserved warm accent sits at swatch
  index 2 (per the brief's Gareth `#33CCFF/#CC99FF/#FFB38A` triad); this is also
  the liquid-symbol target color.
- Vector glyph geometry lives in `SymbolGlyphs` (proportions of the cross pommée
  bulbs, the Star/Om/Crescent rotations, the abstract Om) — see `SymbolGlyphs.md`.

## TODO notes

No open code TODOs. The liquid-symbol sim, vector glyphs and reverse-normal
mirror are all built; what remains are the numeric `CURATE:` values above, which
need the physical LEDs to judge. Possible future refinement (not built): a
two-buffer path so interior **rain** stays identical to exterior while only the
glyph mirrors (current path mirrors the whole surface — invisible for rain).

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass, Claude autonomous session | Stub hero for rafters: rain layer + Bloom dynamics envelope + moiré + shared-`SymbolGlyphs` condensation, all params wired; physics/moiré/formation-timing left as TODO for hardware curation. Symbol drawing delegated to the shared module per critique gap #6 (split GlassRain's jobs); CA "life" layer explicitly deferred (legibility risk). |
| 2026-07-07 | Full build-out, Claude session | Replaced the three stubs: (1) droplet physics — per-drop TTL/lifetime with end-of-life head fade, small rand²-biased horizontal wobble, variable head brightness and weight/width (thin streak vs. fat 3-column bead), and ADD-blended heads so merging beads accumulate; (2) auto-formation envelope — a glyph change (Condense or knob) auto-ramps Form over `MORPH_SECONDS`, holds, then dissolves, with effective form = `max(knob, envelope)`; (3) moiré — tempo-locked 2D interference (horizontal fringe pair × vertical grating, both phases advanced on the 1/16 grid via `beatPosition()`), separable/zero-alloc via per-field scratch arrays. Also made DropBurst drops fast (energyPct 100, matching its stated intent). Params/labels unchanged. |
| 2026-07-08 | Removed Sync/TempoDiv/Meta + TriggerBag/TempoLock (convention retired; free-run behavior = old Sync-off path) | Project-wide retirement of the Sync/TempoDiv + Meta pattern-control convention. Droplet spawning now always free-runs (the on-grid `GRID_BURST` pulse is gone); the moiré's 1/16 phase lock is kept, reading the engine tempo's beat position directly. |
| 2026-07-08 | Added `Smooth` (house AA/interp convention); `energy` retained unchanged | Adopted the house motion-blur/antialiasing `Smooth` knob (default 1.0), registered after the pattern params and before `audio`. Wired into `paintStreak()`: `smooth` crossfades the wet trail between a pixel-snapped `lineMax` and a Wu-AA `lineWu`, and gates a fractional-row crossfade for the ADD-blended head's sub-pixel fall — 0 = steppy/pixel-snapped, 1 = smooth sub-pixel + antialiased. Reuses existing `SurfaceCanvas` primitives + `scaleRGB` coverage, zero-alloc in `step()`. `energy` deliberately kept as-is: rafters is a rigid 140 BPM song and `energy` drives BOTH droplet density AND fall speed (not speed-only), so it was not renamed or retimed. |
| 2026-07-08 | GlassRain feedback pass (Jeff): vector glyphs + liquid condensation + reverse-normal + param changes | Rewrote `SymbolGlyphs` from 11px bitmap masks to **true vector geometry** rasterized to a cached, `Smooth`-antialiased coverage grid (headless AWT, à la `OtfTypeface`); changed Cross to a **cross pommée** and rotated Star −90°, Om −90°, Crescent +45°; dropped the bitmap `morph`/`pixel`/`rows` API (only GlassRain used it). In GlassRain: `Condense` trigger → **`Symbols`** boolean; `Glyph` enum → **continuous** `1..count` floored to an index; **removed `Form`** (+ the auto-formation envelope). Replaced the stamp-and-fade symbol with a **per-cell liquid sim** — rain absorbed into the glyph mask, gravity + lateral surface-tension leveling, bottom-lip overflow drips, color easing to the warm 3rd swatch, instant release on glyph-change / Symbols-off. Glyphs now follow the **reverse-normal rule**: `copyTo(exterior)` + `copyToMirrored(interior)` with instances placed symmetric about each surface center (interior rain mirrors too — invisible). `SurfaceCanvas` untouched; `SymbolGlyphs` is GlassRain-only so its API break is contained. |
