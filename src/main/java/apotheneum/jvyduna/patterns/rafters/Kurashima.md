# Kurashima

A 20-mode grand tour of moiré illusions in five acts: **FieldA** and **FieldB**
enter from opposite wings of each cube wall and bloom into surprising motion where
they cross; inside the cube, only the emergent ghost renders — after Takahiro
Kurashima's *Moirémotion* / *Poemotion* books.

> Sidecar design doc. Source of truth for design decisions + curation history.
> Anything not visually verifiable off-hardware carries an inline `CURATE:` note
> in the `.java` (grep for `CURATE:`).

> **Feedback pass (2026-07-13)** reworked color, motion, geometry, and UI — see
> the *Feedback pass* section and the curation log. The A/B fields are now named
> **FieldA/FieldB** (edge-relative, not wall-relative), alternate cube walls are
> mirrored, motion is tempo-locked, color is two palette swatches + a BlendMode,
> every mode responds to Warp, and the pattern ships a bespoke device UI.

## Feedback pass (2026-07-13)

Jeff's review turned the single-hue B&W engine into a two-color, tempo-locked one
and added a bespoke panel. Seven changes:

1. **Full greyscale at Sat=0, every mode.** The old `satPctColor` floor (min 70%
   sat so ColorBeat stayed colorful) is gone. `Sat` is now a **master
   desaturation** applied to both chosen colors (`applySat`), so `Sat=0` is true
   greyscale for *every* mode, including ColorBeat.
2. **`Swap`** (continuous, default 0) crossfades solo-carrier visibility between
   the two surfaces: 0 = exterior mechanism / interior ghost (the original), 1 =
   reversed, in between both show partial carriers. Implemented as
   `extSolo = CARRIER_GROUND·(1−Swap)`, `intSolo = CARRIER_GROUND·Swap` feeding the
   existing `soloScale`. Inert on fullField/Glass modes (no solo carriers) —
   CURATE if those want a distinct swapped interior pass.
3. **Mirrored corners.** Alternate cube walls are blitted mirrored (`copyTo` on
   even faces, `copyToMirrored` on odd — interior does the complementary parity),
   so FieldA/FieldB emanate outward from the shared vertical corner as `Meet`
   changes. CURATE the parity on hardware.
4. **Two palette colors + BlendMode.** `Hue` is replaced by two
   `LinkedColorParameter` (**ColorA/ColorB**, link each to a swatch). The overlap
   color is `blendMode(cA,cB)` (LERP/ADD/SCREEN/MULTIPLY/MAX, default ADD) — a
   *color* blend distinct from the `Op` *brightness* blend. The grayscale and the
   old two-hue ColorBeat paths are unified: solo regions wear their own color, the
   overlap wears the blended color at full-bright moiré.
5. **Tempo-locked Speed.** Motion is no longer free-run. `Speed` is a linear 0–2
   scaler (default **1.0** center): at 1 the mode's expected moiré beat lands once
   per **`TempoDiv`** period (`BASE_CYCLE_UNITS·SPEED_TRIM` slide units per
   division), 0.5 = half speed (2× the period), 0 = paused, 2 = double. The
   ≥5 s/face governor survives only as a **safety clamp** (it can pull a mode
   slightly off-grid at fast div / high magnification). `SPEED_TRIM` is repurposed
   as the per-mode "slide units per beat" trim.
6. **Every mode uses Warp.** A universal second geometry rotates **FieldB's frame**
   by `Warp·MAX_WARP_RAD` (~15° at 1) about the face center — except the four modes
   that already consume Warp (Wave/Hyper/Lens/Rosette), which keep their own term.
   Wagon (fullField, FieldB≡1, so the B-rotation can't reach it) gets a
   radius-dependent spoke spiral instead. `main()` now asserts a non-zero
   warp-delta per mode.
7. **Center-anchored Pitch + more modes.** Linear gratings now phase off the
   overlap **midline** (center column fixed as Pitch changes) instead of the left
   edge. Pitch also folds into **Pinwheel/Wagon** (spoke density) and **Lens**
   (ring rate), calibrated so the Pitch default (**0.33**) reproduces the prior
   look; Walk/Spin (lattice barrier) and the Glass trio keep no grating pitch.
8. **Bespoke device UI** (`buildDeviceControls`, ≤3 controls/column) — the one
   pattern here that overrides the prefer-auto-panel rule (Jeff asked for it).
   Controls a mode can't use grey out live (cosmetic only): **Pitch** on
   Walk/Spin/Glass, **Op** on Glass, **Reseed** enabled only on Glass.

## Original / inspiration

Kurashima (b. 1970, Tokyo) makes interactive book-objects: black-on-white dithered
printed pages plus a transparent striped/dot foil. Sliding or rotating the foil
animates the static print — rotation, waves, expansion/contraction, hidden figures
that emerge and dissolve, curved/orbital motion from a straight slide. *Poemotion 2*
adds color. This pattern is an interpretation: the **emissive negative** of the
book — a black page on which razor-crisp light-printed gratings enter, cross, and
bloom. It tours the canonical moiré illusions catalogued by Oster & Nishijima
(SciAm 1963), Amidror (*Theory of the Moiré Phenomenon*), Hersch/Chosson (band
moiré), Hutley (moiré magnifier), Leon Glass (Glass patterns), and the
barrier-grid "Scanimation" family.

**Design provenance:** initial implementation (2026-07-11) was then put through a
five-lens adversarial review arena (Kurashima purist, architectural light artist,
op-art kineticist, show dramaturg, engineer-aesthete — each vision attacked by an
adversarial critic, survivors merged by a judge). All ten accepted enhancements
are in; the kill list (per-frame gamma LUT, interior polarity inversion, fixed RNG
seed, autonomous rate tides, Muybridge walker bitmap, SCREEN-as-default, wagon
virtual shutter) is documented in the curation log for posterity.

## Rendering approach

- Base class **`ApotheneumPattern`**, per-pixel field math on `SurfaceCanvas`
  (not `Graphics2D`): moiré is an analytic field sampled on the LED grid — and
  that sampling *is* the third layer (below).
- **Exterior:** one 50×45 canvas rendered once (the *mechanism*: carriers at
  `CARRIER_GROUND` 0.6 + full-bright emergent moiré), copied to all four cube
  walls.
- **Interior is the ghost:** windowed modes render a second 50×45 pass with
  solo-carrier brightness 0 — only the emergent interference shows, mirrored
  (`copyToMirrored`) at `INTERIOR_LEVEL` 0.85, so a person walking through the
  door sees the apparition floating on darkness with no visible cause. fullField
  and Glass modes are already pure moiré: they reuse the exterior canvas dimmed
  (provably identical output, no second pass). CURATE: 0.85 vs 0.7 at 5 m.
- **Cylinder** (`Cyl`, default off): same field math wrapped on 120×43; its
  interior shows the mechanism dimmed, not the ghost (a third field pass for an
  off-by-default surface was judged not worth it — CURATE if the cylinder joins
  the composition).
- **Buffers:** three `SurfaceCanvas` (face, faceIn, cyl) + two `double[GLASS_N]`
  dot arrays, preallocated. Zero allocation in `render()`.
- **Door columns:** `copyTo`/`copyToMirrored` guard on `column.points.length`;
  the Walk figure's feet are kept above the door rows (bob amplitude clamped so
  fcy + bob + r ≤ 33 < 34).

## The engine

Two fields **FieldA** and **FieldB** in `[0,1]` per pixel, entering from opposite
wings (alternate cube walls mirrored so they emanate from the shared corner).
Overlap combines in **brightness** via the `Op` (**AND** `a·b` default for most —
the Kurashima "both-black" logical AND — plus MIN/MAX/**XOR**/DIFF/ADD/**SCREEN**
`a+b−ab`, the De Morgan dual); the two **colors** (ColorA/ColorB) mix there by the
separate `BlendMode`. Solo carriers wear their own color at `CARRIER_GROUND`
(0.6, CURATE 0.5–0.7) scaled by `Swap`: **the emergent fringe alone reaches full
brightness** — the book's figure-darker-than-ground hierarchy, inverted honestly
for light.

**Three convergence mechanics**, so all 20 modes answer `Meet`/`Pulse`:
- *Windowed modes* — sliding sheets with crisp ~1.5 px feathered edges (a foil
  edge, not a fog bank).
- *`sepCenters` modes* (Rings, Lens, Pinwheel, Rosette, Wagon) — two Oster
  centers rest off-wall at −0.15 W / 1.15 W (`SEP_FRAC` 0.65) and land exactly on
  face center at Meet 1 (pixel-identical to the pre-arena look). Two approaching
  sources weave literal double-slit hyperbolic fringes that degenerate
  continuously into the concentric bloom.
- *Glass trio* — per-dot windowing: base cloud enters left, transformed cloud
  right; the pair-correlation (the percept itself) pops into existence only in
  the overlap — Leon Glass's discovery, staged. CURATE: may thin pairs below the
  correlation threshold at 30 m; raise `GLASS_N` if so.
- *Shimmer* — fieldB is now a second detuned near-lattice grating entering from
  the right: the overlap is a three-layer AND (grating × grating × lattice), the
  pattern's thesis. CURATE: the pure foil-vs-lattice look survives only in the
  wings; retune `pb` if the rest state should stay two-layer.

**The third multiply.** The LED lattice samples the product. `Smooth` dials it:
0 = hard threshold (aliased, steppy — the deliberate look for Wagon/Shimmer at
one end), 1 = **flat-top 50%-duty stripes with ~1 px analytic-AA edges**
(`AA_PX`, CURATE 0.7–1.5) + sub-pixel glide. The old sine crossfade is gone — it
algebraically pinned XOR modes to 0.27–0.77 gray mush at the default Smooth = 1
(measured); the razor-foil duty restores true 0→1 contrast (verified in `main()`:
every mode now spans 0.00–1.00). Angular/chirp gratings (Pinwheel, Wagon, Lens)
are linearized to local px pitch so AA spans real pixels at any radius. In the
Glass trio, Smooth instead maps the phosphor-trail half-life (0.03 s → 2.5 s).

### The tour (enum order = .lxp serialization — FINAL once a project saves)

| Act | Mode | A × B | Op | Question |
|---|---|---|---|---|
| I — the mechanism | Bars | still page × sliding detuned foil | Xor | stillness → motion, `v=p/Δp` |
| | Breathe | grating × slowly rotating grating | And | static → dynamic |
| | Wave | sine-bent grating × straight foil | Xor | straight → curved flow |
| | Zoom | concentric × linear | And | straight slide → radial zoom |
| II — line becomes curve | Rings | two ring systems, converging centers | And | two sources → hyperbolic → bloom |
| | Lens | two r² zone plates, converging + drift | And | small shift → huge magnification |
| | Pinwhl | counter-rotating fans, converging hubs | Xor | linear → rotation |
| | Hyprbl | fixed-angle gratings, morphing ratio | Xor | hyperbolic fringe families |
| | DotScr | two 2-D dot rasters, Δpitch | And | Poemotion dot-foil rosette |
| III — the hidden figure | Reveal | compressed glyph × slit comb | And | what hides in the regular |
| | Magnif | micro-glyph colonnade × line slits (1-D band moiré) | And | small → large (Fourier) |
| | Walk | interlaced hopping disc × lattice-locked barrier | And | static → animate |
| | Spin | interlaced 45°-step bar × barrier | Xor→And | straight slide → rotation |
| IV — order from randomness | Swirl | dots × rotated copy | Max | flow from no grating |
| | Burst | dots × scaled copy | Max | radial streamlines |
| | Vortex | dots × rotate+scale | Max | living spiral |
| V — the medium itself | Wagon | judder-capped spokes (fieldB ≡ 1) | And | temporal quantization made visible |
| | Shimmr | near-lattice grating × second detuned one | And | the extra AND made visible |
| Coda | ColorB | still page × sliding foil, two hues | Add | Poemotion 2 color beat |
| | Rosett | concentric × petal-warped concentric | And | petals bloom/close |

Next wraps Rosette → Bars (da capo). Act seams: Zoom→Rings is a bridge, not a
surprise — CURATE the act assignments on hardware.

## The convergence ritual

- `Meet` rests at **0.3** — two dim carrier wings touching softly at center (the
  piece no longer rests at its own climax).
- **`Pulse` = an 8 s phrase** (`PULSE_SEC`): ease apart (20%), rise (35%),
  **hold the bloom ~2 s** (25%), settle to the live Meet (20%), all smoothstepped.
  Fire captures `pulseFrom = lastMeetEff`, so fire-from-rest, retrigger
  mid-phrase, and phrase end are all continuous by construction (no snaps).
- **Page-turn dip** (`DIP_SEC` 0.6, nadir at 0.25): any Mode change dims through
  true black; the new mode (and Random's staged pitch/detune/warp re-rolls +
  reseed) land hidden at the ~0.15 s nadir; the 0.45 s rise reads as the page
  settling. Glass canvases are cleared at the latch (no cross-mode ghosts).
  CURATE: the 0.15/0.45 split.

## Fringe-velocity governor

The emergent fringe moves at `v_carrier × p/Δp` — magnification that at low
Detune outran the ≥5 s floor in the original build (Bars hit ~28 px/s). Now
enforced analytically: `fringePxPerSlide(mode, det, wrp, P)` models each mode's
magnification; the slide rate is clamped so governed fringe velocity ≤
`FACE_H / 5 = 9 px/s`. Verified in `main()` at Speed 1: every mode prints
`governor ... OK` (Bars 8.43, Rings/Lens/Pinwheel/Reveal/Magnify/Shimmer clamped
at 9.00, Walk/Spin 3.60). Physically honest side-effect: at high magnification
the carriers slow — Detune/Pitch now modulate effective carrier rate (documented
in the Speed/Detune param descriptions; will surprise mid-arrange otherwise).
Transients are exempt (Breathe's blowup near θ=0); Wagon is capped by
`SPEED_TRIM` to ~5–6 spoke passages/s — deliberate frame-quantized judder.
**True wagon-wheel reversal is unreachable at 60 fps** without flicker in the
photosensitive band; deliberately forgone. `SPEED_TRIM` values are all CURATE.

## Audio mapping

`Audio` depth (`audio.setDepth(audioDepth)`), default **0** = pure screensaver
(taps read silence values; hits never fire). Above 0: `audio.level` breathes
`Meet` (+0.5·level·depth, clamped) and each `bassHit()` fires the Pulse phrase —
which now re-parts gracefully from wherever the envelope sits. CURATE against
the Rafters stems.

## Tempo

**Tempo-locked** (feedback pass). The slide clock advances
`BASE_CYCLE_UNITS·SPEED_TRIM[mode] / divSec · Speed` units/sec, where
`divSec = tempo.period / TempoDiv.multiplier` — so at `Speed=1` the mode's
expected emergent beat lands once per `TempoDiv` period. This follows the live
`lx.engine.tempo.period` (rubato-friendly, continuous), not a Sync/grid snap. The
≥5 s/face governor is now a **safety clamp** only. CURATE: `BASE_CYCLE_UNITS`
(0.2, the global tempo-feel knob), every `SPEED_TRIM`, and the `TempoDiv` default
(WHOLE / one bar — chosen so typical tempos stay under the governor and lock).

## Timeline vocabulary (for the arrange session)

- **Mode lane** — page turns; the reveal lands at the ~0.15 s nadir, so place
  events a hair ahead of the beat (or fire during a black to get a free hard cut).
- **Pulse** — the 8 s phrase (part→bloom→settle); the held apex is the musical
  landmark.
- **Next** — tour step (same dip semantics).
- **Random** — rehearsal/improv only; never in the final arrangement (its
  re-rolls are staged and land at the nadir, so it is safe to fire live).
- **Reseed** — Glass-cloud re-roll; clouds also re-roll per app run (RNG is
  unseeded by design — the arena killed the fixed seed as protecting nothing).
- **Meet lane** — the master dramaturgy dial; automate directly for slow
  approaches the Pulse phrase doesn't cover.

## Energy / Speed mapping

`speed` (motion-only), a **linear 0–2 scaler, default 1.0** (center). The tempo
lock sets the base rate (one expected moiré beat per `TempoDiv`); Speed multiplies
it: 0 = paused, 0.5 = half speed (2× the period), 1 = on-beat, 2 = double. The
governor still caps the governed fringe velocity at 9 px/s (5 s/face) as a
legibility floor — so at fast `TempoDiv`/low `Detune` the lock yields to the clamp
and the beat lands late. All `SPEED_TRIM`/`BASE_CYCLE_UNITS` values are CURATE.

## Parameters

Order: triggers → speed/tempoDiv → mode → geometry → swap → warp → op → colors →
sat → cyl → smooth → audio. Keys are frozen. A **bespoke UI**
(`buildDeviceControls`) lays these into ≤3-per-column groups and greys per-mode
invalid controls (Pitch on Walk/Spin/Glass, Op on Glass, Reseed off unless Glass).

| Param | Label | Type | Default | Meaning |
|---|---|---|---|---|
| nextMode | Next | Trigger | — | next mode (page-turn dip) |
| randomize | Random | Trigger | — | random mode + staged re-rolls at the nadir |
| pulse | Pulse | Trigger | — | 8 s convergence phrase with held bloom |
| reseed | Reseed | Trigger | — | re-roll Glass dot cloud (Glass modes only) |
| speed | Speed | Compound | **1.0** | tempo-rate scaler 0–2 (0 pause · 0.5 half · 1 on-beat · 2 double) |
| tempoDiv | Div | Enum | Whole | musical period the expected moiré beat lands on |
| mode | Mode | Enum | Bars | which illusion (act order above) |
| pitch | Pitch | Compound | **0.33** | grating pitch 2.5–14 px, rescaled about the overlap midline |
| detune | Detune | Compound | 0.35 | A/B difference → magnification (governor interacts) |
| meet | Meet | Compound | **0.3** | convergence: 0 apart · 0.3 wings touch · 1 bloom |
| swap | Swap | Compound | 0 | crossfade carriers↔ghost between exterior/interior |
| warp | Warp | Compound | 0.3 | 2nd geometry: rotates FieldB's frame (all modes) + each mode's own term |
| op | Op | Enum | Auto | brightness combine op override (incl. Screen) |
| colorA | Color A | LinkedColor | blue | FieldA color (link to a palette swatch) |
| colorB | Color B | LinkedColor | warm | FieldB color (link to a palette swatch) |
| blendMode | Blend | Enum | Add | color blend of A/B in the overlap (Lerp/Add/Screen/Mult/Max) |
| sat | Sat | Compound | 0 | master saturation (0 = greyscale, the B&W book) |
| cylinder | Cyl | Boolean | off | wrap onto the cylinder too |
| smooth | Smooth | Compound | 1.0 | lattice alias ↔ razor AA; Glass: trail length |
| audio | Audio | Compound | 0 | reactivity depth |

## Smooth

0 = hard `sign` threshold (aliased/steppy — the deliberate lattice-beat look);
1 = **flat-top square stripes, ~1 px analytic-AA edges, sub-pixel glide** — the
foil's razor line, never a blurry sinusoid. Implemented as signed px distance to
the nearest stripe edge over `smooth·AA_PX` (clamped to pitch/4, which
gracefully collapses Shimmer's ~2 px grating toward a triangle). `edge()` gives
the barrier/figure family the same AA. Glass trio: Smooth maps trail half-life
instead (0.03–2.5 s, CURATE). Registered after pattern params, before `audio`.

## Triggers

- **Next** — tour step through the acts (page-turn dip; ~0.6 s ritual).
- **Random** — full scatter, staged: mode + pitch/detune/warp + reseed, all
  landing hidden at the dip nadir.
- **Pulse** — the 8 s phrase; the held apex is the bloom (auto-fired by bass
  hits when Audio > 0).
- **Reseed** — subtle: re-rolls the Glass cloud (visible in Act IV only).

## Simulation-principles compliance

Dark-dominant: carriers at 0.6, true blacks between stripes, fringe alone at
full brightness; interior at 0.85 showing overlap only (also fixes the original
glare defect — a 50%-duty field at 1.0 straight into the eyes of anyone inside).
Motion floor is enforced analytically by the governor (see above), not hoped
for: `main()` prints per-mode governed velocities, all ≤ 9 px/s at Speed 1.
Event-like transients (page-turn dip 0.6 s, ≥1.5 s visual life via the rise) are
within policy. No fine texture except where the texture is the thesis
(Shimmer/Wagon, capped and judder-limited).

## Headless verification

`main()` renders every non-glass mode at meet ∈ {0.1, 0.5, 1.0} (ASCII maps for
entrance + bloom, full-row stats for all three), flags NaN/flat fields, prints
each mode's governed fringe velocity vs. the 9 px/s cap, and now asserts a
non-zero **warp-delta** per mode (field at Warp 0 vs 0.8) so "every mode uses
Warp" is proven, not assumed. Current output: **ALL FIELD MODES OK**, zero
governor violations, zero WARP INERT, every mode spanning 0.00–1.00.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-11 | Initial 20-mode implementation | grand-tour brief; research → phase-field engine |
| 2026-07-11 | Wagon default op MAX→AND | headless preview caught flat-white field (max(a,1)=1) |
| 2026-07-11 | **Arena pass** (5 adversarial lenses → judge; 10 items) | pre-first-review beautification, all items below |
| 2026-07-11 | duty() → flat-top + 1px analytic AA; angular/chirp linearization | sine crossfade pinned XOR modes to 0.27–0.77 gray at default Smooth (measured); razor-foil restores 0→1 |
| 2026-07-11 | Meet default 0.3; Pulse → 8s eased phrase with held apex; crisp 1.5px window feather; CARRIER_GROUND 0.6 | stop resting at the climax; kill fire/end snaps; fringe = hero, carriers = ground |
| 2026-07-11 | Oster sepCenters (Rings/Lens/Pinwhl/Rosett/Wagon); Shimmer 2nd grating; Glass per-dot windows | 9 modes ignored Meet/Pulse; now all 20 perform enter→converge→bloom |
| 2026-07-11 | Enum reordered into five acts + coda | ordinal = .lxp serialization; only cheap before first save |
| 2026-07-11 | Fringe-velocity governor + SPEED_TRIM + Lens Nyquist chirp (kA 0.002+0.004w) | original Bars hit ~28px/s at det→0 (floor violation); now analytic invariant |
| 2026-07-11 | Hidden figures: lattice-locked bp=4/n=4/w=1 barriers ×6 rate; Walk disc r9 det-bob (door-safe); Reveal HT-solved (heart finally fits — was 66px on a 45px face); Magnify → 1-D band-moiré colonnade | three near-black walls → legible payloads |
| 2026-07-11 | Page-turn dip (0.6s, nadir 0.25) + staged Random | mode changes were hard cuts; re-rolls visibly scrambled the old page |
| 2026-07-11 | Interior = emergent ghost (solo 0, INTERIOR_LEVEL 0.85) | walking through the door is the reveal; fixes interior glare |
| 2026-07-11 | Glass churn (0.15/s) + Smooth-mapped trails (0.03–2.5s half-life) | frozen constellation → living Glass demo; Smooth was dead in Act IV |
| 2026-07-11 | Bars/ColorB still page (Pb=0.68P, foil ×2 rate); ColorB hue+lerp(40,150,warp), B×0.8; SCREEN op added | stillness-becomes-motion; kill warm crimson vs G-minor ice; Warp was dead in ColorB |
| 2026-07-11 | Killed by arena (recorded): gamma LUT, interior inversion, fixed seed, rate tides, walker bitmap, SCREEN defaults, wagon shutter | see .java history / arena verdict; each with measured refutation |
| 2026-07-13 | **Feedback pass** (Jeff): items below | single-hue B&W → two-color tempo-locked engine + bespoke UI |
| 2026-07-13 | Removed `satPctColor` floor; `Sat` = master desaturation | ColorBeat must reach true greyscale at Sat=0 like every other mode |
| 2026-07-13 | `Swap` crossfade of carriers↔ghost between exterior/interior | reversible, continuous version of the fixed interior-ghost rule |
| 2026-07-13 | Alternate walls mirrored (copyTo/copyToMirrored parity) | FieldA/FieldB emanate from the shared vertical corner as Meet changes |
| 2026-07-13 | Hue → ColorA/ColorB `LinkedColorParameter` + `BlendMode`; unified color path | per-half palette color; color blend distinct from the Op brightness blend |
| 2026-07-13 | Tempo lock: Speed → linear 0–2 scaler, `TempoDiv`; governor demoted to safety clamp | expected moiré beat lands on the grid; free-run retired |
| 2026-07-13 | Universal Warp = FieldB-frame rotation (non-internal modes) + Wagon spoke spiral | every mode must respond to Warp (main() asserts warp-delta) |
| 2026-07-13 | Center-anchored Pitch; Pitch folded into Pinwheel/Wagon/Lens; default 0.4→0.33 | midline-invariant pitch; more modes respond, prior look at the new default |
| 2026-07-13 | Bespoke `buildDeviceControls`; per-mode grey-out (Pitch/Op/Reseed) | Jeff requested a custom panel; invalid controls disable, never hide |
