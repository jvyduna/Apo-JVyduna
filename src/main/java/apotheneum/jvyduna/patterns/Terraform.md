# Terraform

Evolving terrain skyline: tempo-locked uplifts raise mountains, erosion silts
the sea, and the sea pumps with the music.

> Sidecar design doc convention: this file lives beside `Terraform.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

**Designer's-choice interpretation — no confirmed original screensaver.** The name
evokes the terrain-generation / "terraforming" family of demos and screensavers,
but this design was not traced to a specific verified original. The visual
signature being built: a wrap-around mountain skyline in hard elevation bands
(water / sand / grass / rock / peaks) that geologically evolves — mountains rise,
erode, and drown or emerge as the sea level pumps with the music. The
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
- **Heightfields**: per surface, `target[]` (receives uplift commits, roughness
  jitter, diffusion, subsidence) and `height[]` (displayed; chases `target[]`
  under the per-point rate limit of full height in ≥ `RISE_FULL_SEC` = 5 s),
  plus a `scratch[]` for the diffusion stencil and an uplift `overlay[]` (see
  next bullet). All arrays, the uplift pools, and the `Random` are allocated in
  the constructor — zero allocation in the render path.
- **Uplift envelopes**: rising mountains are eased envelope events (fixed pool
  of `MAX_UPLIFTS` = 8 per surface) rendered as an **additive overlay** on
  `height[]`; each event's smoothstep progress reaches exactly 1 at its
  deadline, at which point the full Gaussian is committed into both `target[]`
  and `height[]` and the slot frees. The overlay bypasses the background chase
  so a rise can complete in a fraction of a second and land on the tempo grid.
  Pool overflow steals the most-progressed rise without a pop: height keeps
  exactly the eased fraction the overlay was showing (its overlay contribution
  is cancelled), target gets the full bump, and the background chase raises
  the remainder. Deadlines are fixed at booking (retime-once, the flood
  idiom): a tempo change mid-rise is not re-tracked, so a BPM ramp landing
  inside a sub-second rise can peak slightly off-grid — accepted.
- **Per-pixel rule** (bottom-up, `elev` = rows above the physical ground,
  computed as `fullHeight - 1 - yi`; see door-column handling below):
  - `elev ≤ sea` → water; the single top water row is the bright **waterline**
    accent (specular highlight). Water draws in front of submerged land, so the
    waterline is one continuous ring at the sea surface.
  - `sea < elev ≤ terrain` → land, banded by **absolute elevation**: sand /
    grass / rock / peaks. Hard edges, no gradients.
  - above both → sky (black).
- **Door-column handling**: every cube/cylinder column carries the full point
  count (the `Apotheneum` orientation constructors enforce 45/43 points per
  column); door cutouts are masked globally by the core doors effect, not by
  shorter columns. `points[0]` is the **top** row, so elevation is computed as
  `fullHeight - 1 - yi`, which keeps the sea surface and band thresholds
  aligned across all columns (and would remain correct even if door columns
  were ever modeled short).

### Colors — palette-driven land bands, fixed sea (2026-07-06 decision)

The four land bands take their colors from the current project palette swatch,
Rubik-style; the sea and sky stay fixed (water must read as water for the
flood/drown metaphor to survive arbitrary palettes).

| Band | Source | Notes |
|---|---|---|
| Sky | black (fixed) | negative space; skyline silhouette |
| Deep water | 215°, 85%, 40% (fixed) | dim saturated blue, recedes |
| Waterline | 190°, 35%, 100% (fixed) | 1-row bright pale cyan accent |
| Sand | swatch color 3 | |
| Grass | swatch color 2 | |
| Rock | swatch color 1 | |
| Peaks | swatch color 0 | `WhtCps` on: pure white instead; swatch 0 discarded, and TrbSprk flashes crackle the peaks *dark* (white-on-white would be invisible; the rock spill still flashes white) |

Short-swatch fill rule (`computeBandColors()`, recomputed once per frame,
modeled on `Rubik.computeFaceColors`): defined swatch colors are anchored at
their perceptual hue positions (`PerceptualHue.toPerceptualPosition`) and the
missing bands are generated with `PerceptualHue.fillCircle` — greedy
bisect-the-largest-hue-gap — then colored via `PerceptualHue.color` (fully
saturated, full brightness). `WhtCps` never anchors white as a hue; with a
1-color swatch + WhtCps the three remaining bands spread evenly on the hue
circle. CURATE: generated fills are 100%-saturation spectral hues — check that
terrain still reads as terrain on sparse swatches, and that typical swatches
keep water-vs-land separation at distance.

Band thresholds (fractions of full height, before `BandShift`): sand < 0.30,
grass < 0.55, rock < 0.80, peaks ≥ 0.80. Band heights at defaults: cube (45 rows)
13.5 / 11.25 / 11.25 / 9; cylinder (43 rows) 12.9 / 10.75 / 10.75 / 8.6 — all
≥ 4 rows (the 1-row waterline is a deliberate accent, not a band).
CURATE: threshold fractions 0.30/0.55/0.80 unverified.

## Audio mapping

All reactivity is gated by the **Audio depth knob** (`audio`, default **0**),
attached via `AudioReactive.setDepth(audioDepth)`. At depth 0 every tap reads
its silence value and hits never fire, so the depth-0 baseline *is* the
silence behavior below. There is no per-site gating in the pattern — the
depth scaling happens once inside `AudioReactive`.

| Tap | Drives |
|---|---|
| `level` → `seaLevel` (fast smoother, τ = 0.35 s) | Sea level goal: `seaBias − 0.45 · seaLevel + silt`, clamped to [0.05, 0.9]. Quiet → sea rises and drowns the land; loud → sea drains, mountains emerge — within a beat. |
| `level` → `drive` (slow smoother, τ = 2 s) | Everything the old Energy knob drove: uplift rate/amplitude/sigma, diffusion blend, subsidence speed, bass-uplift gate. See "Drive mapping". |
| `bassHit()` + `bassRatio` | At smoothed level ≥ 0.35 (gate = 0.35 × depth compared against `drive`, so it reads the depth-independent level — a fixed gate would silently disable bass uplifts for any Audio knob below it): each bass transient raises a new mountain (tempo-quantized uplift); amplitude scales with `bassRatio`. |
| `trebleHit()` | TrbSprk white flash burst: flash envelope snaps to `depth()` (the AudioReactive scaled-hit-response contract — a barely-on knob must not strobe at full brightness) and decays (τ = 45 ms); a fresh random ~45%·TrbSprk subset of peak-band pixels (plus a 2-row spill into rock) lerps toward white by the envelope (toward black on the peaks when `WhtCps` is on). |

**Depth-0 baseline (default, = silence behavior)**: the sea settles at
`SeaBias` (default 0.55 ≈ mid-level) plus any silt from ongoing erosion, the
spontaneous uplift timer keeps slowly raising new mountains, and erosion keeps
aging them — a calm, self-evolving archipelago. Designed to look good with
zero audio. (Since the Energy-knob removal this baseline sits at the pure
ambient endpoints; the old default was a fixed e = 0.35 blend, so silence is
now slightly calmer.)

**Raising the knob restores**: the sea pumping (draining as the fast-smoothed
music level rises — the emotional core), bass-hit mountain births under
sustained loud music, and treble flash bursts. Magnitude effects (sea swing,
bass-uplift amplitude via `bassRatio`) scale linearly with depth; bass-hit
births start firing once depth > 0.01 *and* drive ≥ 0.35 at a floor amplitude
of 0.6× (the `bassRatio` term supplies the remaining 0.4× as depth rises).

## Tempo mapping

Discrete formation events lock to the grid; continuous morphing (erosion,
height chase) stays smooth and is never tempo-quantized. Default division:
`QUARTER`.

- **Uplift rises complete on the grid** — every uplift (timer, bass, Erupt) is
  an eased envelope whose peak lands exactly on a `tempoDiv` boundary. Duration
  rule (grid-strict): finish on the next boundary, unless it is less than half
  a division away — then target the boundary after. Durations land in
  [0.5, 1.5) divisions; timer births fire *on* crossings, so they get exactly
  1.0 division. `Sync` off: fixed free rise of 1.4 s (≈ the old default feel),
  same envelope code path.
- **Spontaneous mountain births initiate on the grid** — with `Sync` on, the
  free-running Poisson timer is replaced by a `TempoLock.crossed(tempoDiv)`
  per-frame gate: on each grid crossing a birth fires with probability
  `upliftRate × divisionMs / 1000`, preserving the same expected rate as the
  Poisson timer (capped at one birth per division cycle — at peak drive with
  divisions longer than ~2 s the effective rate saturates at one per
  division). Births land exactly on the beat; drive controls density.
- **Sea rate** — the ambient sea chase runs at one full sweep per beat
  (`1000 / tempo.period` fraction/s), so the sea achieves any new goal within
  one beat at the current tempo. Not phase-locked, just rate-matched.
- **Flood ramp landing** — at the moment the `Flood` trigger fires, the sea
  rise rate is scaled by `retime(msUntilFull, tempoDiv, 0.7, 1.0)` so the sea
  tops out on a grid boundary. **Clamp override**: max scale 1.0 (slow-down
  only) because `FLOOD_RAMP_SEC` = 5 s is the designed dramatic ramp — it may
  stretch to ~7.1 s but never quicken. Retimed once, at trigger time
  (event-rate), not per frame.
- **Not locked**: cataclysm/reseed triggers (fire when pressed; their terrain
  writes ride the background chase), erosion, subsidence, roughness jitter.

`Sync` off restores free-running behavior: Poisson birth timer, 1.4 s rises,
and the fixed 5 s flood ramp (`retime()` is not called; `crossed()` is
still polled every frame with its result unused, so the gate stays fresh and
re-enabling Sync cannot report a stale boundary and fire one spurious birth).
CURATE: quarter-note default division for birth quantization
unverified — at high BPM births may want `HALF`/`WHOLE` to stay stately.
CURATE: sub-second envelope rises (e.g. 0.5 div at 160 BPM QUARTER ≈ 190 ms
minimum) unverified — must read as an eruption, not a glitch.

## Drive mapping (smoothed audio level, τ = 2 s)

The Energy knob was removed 2026-07-06; `drive` — the τ = 2 s smoothing of the
depth-gated `audio.level` — substitutes at every former site. Ambient column =
silence or Audio depth 0; peak = sustained loud music at depth 1.

| Quantity | Ambient (drive=0) | Peak (drive=1) | Curve (lin/exp) |
|---|---|---|---|
| Spontaneous uplift rate (one shared timer; each birth raises one bump on *both* surfaces at independent random columns) | 0.06 /s (~1 per 17 s) | 0.5 /s (~1 per 2 s) | exp |
| Uplift amplitude factor (× `Uplift` param × height) | 0.40 | 0.90 | lin |
| Uplift bump sigma (fraction of ring) | 0.02 (4 cube cols) | 0.045 (9 cube cols) | lin |
| Diffusion coefficient (× `Erosion` param) | 0.4 /s | 2.0 /s | lin |
| Subsidence time constant | 120 s | 30 s | exp |
| Bass-hit uplifts | off (smoothed level < 0.35) | on | gate (depth-relative: drive ≥ 0.35 × depth) |

The terrain chase rate (height/5 s) is **not** drive-scaled.

## Parameters

UI order: triggers first, pattern parameters, Audio, Sync, TempoDiv, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `cataclysm` | Cataclysm | TriggerParameter | — | — | huge ridge + ≤ 0.5 s whole-ring shake, then settles |
| `flood` | Flood | TriggerParameter | — | — | sea ramps to max, holds 2 s, drains back |
| `reseed` | Reseed | TriggerParameter | — | — | morph to a fresh random terrain over ~5 s |
| `erupt` | Erupt | TriggerParameter | — | — | raise one new mountain now, exactly as a spontaneous uplift would |
| `upliftSize` | Uplift | CompoundParameter | 0.5 | 0..1 | amplitude of new mountain uplifts |
| `erosion` | Erosion | CompoundParameter | 0.4 | 0..10, exponent 2 | diffusion + subsidence rate; quadratic knob (value = 10·knob²) gives fine low-end resolution, top is 10× the old max |
| `rough` | Rough | CompoundParameter | 0.25 | 0..1 | terrain ruggedness: continuous ±2.7-row jitter bumps/divots, up to 25 /s per surface |
| `seaBias` | SeaBias | CompoundParameter | 0.55 | 0.15..0.85 | sea level in silence (fraction of height); music lowers it |
| `bandShift` | Bands | CompoundParameter | 0 | -0.2..0.2 | shift all band thresholds up/down (fraction of height) |
| `whiteCaps` | WhtCps | BooleanParameter | false | — | peaks band pure white; swatch color 0 discarded (no band shifting); TrbSprk crackles the peaks dark |
| `trbSprk` | TrbSprk | CompoundParameter | 0.5 | 0..1 | treble-hit white flash burst coverage on the peaks |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full reactivity |
| `sync` | Sync | BooleanParameter | true | — | lock uplift rises + births + flood landing to the tempo grid |
| `tempoDiv` | TempoDiv | EnumParameter\<Tempo.Division\> | QUARTER | divisions | grid that synced events land on |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

Param-key note: the 2026-07-06 renames (`sparkle` → `trbSprk`, removed
`energy`, added `rough`/`whiteCaps`) change the saved-parameter keys — older
.lxp projects will not restore the old Sparkle/Energy values. Accepted during
curation.

## Triggers

Four non-meta triggers spanning small → large, all registered through the
`TriggerBag` (so Meta can fire any of them):

- `erupt` (small) — books a single new mountain on each surface, exactly the
  spontaneous-uplift recipe (drive-scaled amplitude and sigma, random
  columns). With Sync on the rise completes on the next tempoDiv boundary
  (≥ 0.5 div away); Sync off, 1.4 s. The subtlest permutation.
- `cataclysm` — adds a mountain-range ridge (0.85 H central peak + two 0.55 H
  shoulders, σ = 5% of ring) to both surfaces' targets and starts the shake: a
  spatial sine ripple (±2.5 rows, 7 Hz, decaying) across every column for
  0.45 s. The ridge then *grows* under the rate limit (~4.3 s to full height)
  and erodes over the following tens of seconds. Deliberately unquantized.
  CURATE: shake amp 2.5 rows / 7 Hz / 0.6 rad-per-column wavelength
  unverified — must read as a tremor, not flicker.
- `flood` — sea goal overridden to 0.97 H at the flood rate (full sweep in 5 s;
  ~4 s from a typical drained sea), holds 2 s at max (near-total drowning, one
  bright waterline near the crown), then drains at the normal sea rate back to
  the audio-driven level. Since the sea-rate change that drain is one full
  sweep per beat — a dramatic whoosh rather than the old 8 s ebb (accepted;
  if curation dislikes it, give `floodPhase` exit a dedicated slow drain
  rate). With `Sync` on, the ramp rate is retimed (slow-down only, see Tempo
  mapping) so the sea tops out on a `TempoDiv` boundary.
- `reseed` — re-rolls both targets (random base 0.05–0.15 H plus 8 cube / 6
  cylinder random bumps, amp 0.25–0.8 H); displayed heights morph there under
  the rate limit, so the world transforms over ≤ 5 s. Deliberately unquantized.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `erosion` | 0.15..3 | re-ranged to [0.15, 3] | 2026-07-05: floor raised from 0 — a jump landing near erosion = 0 freezes aging while uplifts keep firing, and the ring saturates into a full-height snow plateau within minutes. 2026-07-06: ceiling set to 3 on the new 0..10 scale — a meta jump to 10 plus silt coupling would pin the sea high |
| `upliftSize` | 0..1 (full) | candidate | uplift size |
| `rough` | 0..0.8 | candidate | ruggedness; sub-range avoids full-rate jitter storms from a meta jump |
| `bandShift` | -0.12..0.12 | candidate | band offset; sub-range keeps peaks ≥ ~4 rows |
| `seaBias` | 0.3..0.75 | candidate | sea level bias; sub-range avoids near-empty/near-full seas |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Silt — erosion-coupled sea level

Visible mountain volume lost each frame (the decrease in Σ max(0, target −
seaRows) across `advanceTerrain`, normalized by ring-area) feeds a `silt`
accumulator: `silt += 8 × loss`, decaying with τ = 25 s, capped at 0.3. Silt
adds directly to the sea goal, so watching a range erode visibly raises the
ocean over it — conservation-of-mass as theater. The measurement window is
strictly inside `advanceTerrain` (diffusion + subsidence only): uplift commits
and roughness jitter run *before* it in `render()` and are invisible to it —
an ordering contract pinned by a comment at the call site (reordering
`roughen()` after the erosion pass would count every downward jitter divot as
eroded volume and pump silt to its cap). CURATE: SILT_GAIN 8 / decay 25 s /
cap 0.3 unverified —
one default bump eroding contributes ≈ +0.14 of sea, sized to be clearly
visible; check it doesn't read as the sea "breathing wrong" against music.

## Simulation-principles compliance

- **Per-point terrain rate limit**: `height[]` chases `target[]` at
  `fullHeight / RISE_FULL_SEC` = 45/5 = **9 rows/s** (cube; 8.6 rows/s
  cylinder), independent of drive. This governs erosion, reseed, cataclysm and
  uplift-commit reconciliation. ✓
- **Documented exception — quantized uplift rises** (2026-07-06 curation
  decision): uplift envelopes bypass the chase so the peak lands on the tempo
  grid; a 13-row bump rising in 0.25 s at 160 BPM QUARTER moves ~52 rows/s
  *locally* (σ ≤ 9 of 200 columns). Localized event-class motion, same
  category as the cataclysm shake; whole-sculpture change remains chase-capped.
- **Documented exception — one-beat sea rate** (2026-07-06 curation decision):
  the previous "sea ≥ 5 s per full traversal" claim is **revoked**. The sea now
  sweeps its full range in one beat (≈ 0.37 s at 160 BPM) so it fully reacts
  to the music within a beat; the fast τ = 0.35 s `seaLevel` smoother plus the
  0.45 swing keep it musical rather than strobing. The flood ramp keeps its
  5 s character (retime slow-down only), but the post-flood drain rides the
  fast rate. CURATE: verify the pumping sea reads as surf, not flicker, at
  high BPM.
- **Diffusion stability**: the Laplacian pass is sub-stepped — per frame
  `kTotal = erosion × diffusion × dt` is split into `⌈kTotal / 0.24⌉` Jacobi
  sub-steps (≤ 10), each k ≤ 0.24 < 0.5, so the stencil is unconditionally
  stable at erosion = 10. Subsidence uses the exact exponential
  `1 − exp(−erosion·dt/τ)` — stable at any knob value and frame rate. ✓
- **Erosion spread**: diffusion is local smoothing, not a traveling front —
  worst-case k = 20 /s (knob 10, peak drive) gives an RMS spread of √(2kt)
  columns ≈ 6 columns/s initially, decelerating; crossing half the cube ring
  would still take ~250 s. ✓
- **The shake exception**: the cataclysm shake — 0.45 s of ±2.5-row ripple —
  documented above; total cataclysm visual life ≥ 4.3 s (ridge rise) plus
  settle. ✓
- **Contrast/brightness**: bold solid bands ≥ 4 rows tall with hard edges, a
  black sky for silhouette, a dim sea vs. a single full-bright waterline row,
  no gradients. Sub-band detail: roughness jitter (±2.7 rows of skyline
  texture) and the treble flash (≤ 45%·TrbSprk of peak pixels lerped to white
  for ~100 ms). CURATE: flash decay 45 ms / coverage 0.45 / 2-row rock spill
  unverified — must read as glinting peaks, not noise, at distance.

Other unverified constants: CURATE: `SEA_SWING` 0.45, `SEA_TAU_SEC` 0.35 and
`DRIVE_TAU_SEC` 2 (sea pumps with dynamics without strobing; drive swings the
uplift/erosion regime over phrases, not bars?). CURATE: spontaneous birth
rates 0.06→0.5 /s, applied both as Poisson rate (Sync off) and per-crossing
probability (Sync on) — is ambient lively enough / peak not cluttered?
CURATE: subsidence taus 120→30 s (do old ranges linger too long?).
CURATE: `ROUGH_RATE_MAX_HZ` 25 / `ROUGH_AMP_FRAC` 0.06 (does Rough = 1 read
as craggy or as boiling?). CURATE: bass gate drive ≥ 0.35 (old energy gate
was a manual 0.55; the smoothed-level scale sits lower).
CURATE: seed bump counts (8 cube / 6 cylinder) and base height 0.05–0.15 H.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | as planned; no visual verification yet |
| 2026-07-05 | Review + upgrade session (code review, no visual verification): added `audio` depth knob (default 0) wired via `AudioReactive.setDepth` — no per-site gating needed, depth 0 reproduces silence behavior exactly; added `sync`/`tempoDiv` — spontaneous mountain births grid-gated via `crossed()` at rate-preserving probability, flood ramp retimed slow-down-only ([0.7, 1.0]) to land on the grid; added `erupt` small trigger (4th non-meta, registered in TriggerBag); factored shared uplift recipe into `spawnUplift(ampScale)` with named amp/sigma constants; re-ranged `erosion` meta-jump to [0.15, 1] (plateau-saturation guard); corrected door-column claim in doc + code comment (columns are always full-length; doors are a global mask, `points[0]` = top row) | audio-depth / tempo-sync series conventions; bug hunt found no functional render/indexing bugs — doc/comment drift only |
| 2026-07-05 | Integration pass: fixed the Sync-re-enable stale-gate nit reported to the util queue — `crossed()` is now polled unconditionally every frame (was inside the Sync-on ternary branch), matching the series idiom, so re-enabling Sync no longer fires one spurious off-grid birth; `TempoLock.crossed()` javadoc now states the unconditional-poll contract | Terraform agent's util request; fixed at the call site rather than with a heuristic in-util guard |
| 2026-07-06 | Curation pass (7 changes, no visual verification yet): Erosion re-ranged 0..10 with `setExponent(2)` + diffusion sub-stepping and exact-exponential subsidence for stability; **Energy knob removed** — `drive` (τ = 2 s smoothed audio level) substitutes at every site; land band colors now palette-driven (peaks/rock/grass/sand ← swatch 0–3, Rubik fill rules) + `WhtCps` white-peaks toggle; uplifts became tempo-quantized eased envelopes (overlay + commit, grid-strict [0.5, 1.5) div, Sync-off 1.4 s); sea re-rated to one full sweep per beat with a fast τ = 0.35 s smoother + erosion-coupled `silt` raising the sea; added `Rough` jitter-bump knob; Sparkle → `TrbSprk` treble-hit white flash bursts (the old ≤ 45% snow *dimming* was invisible over bright snow and gated behind the removed energy > 0.5) | Jeff's curation notes: better low-end Erosion resolution and a real 10x top; Energy redundant; palette integration; beat-locked geology; the sea was imperceptible; terrain needed a ruggedness axis; sparkle was never visible with audio playing |
| 2026-07-06 | Post-implementation review fixes (multi-angle code review, 8 findings): uplift envelopes now advance *before* the spawn decision (a just-booked rise was credited the pre-booking frame's deltaMs and peaked one frame ahead of the grid); bass-uplift gate made depth-relative (`drive ≥ 0.35 × depth()` — a fixed 0.35 gate silently disabled bass uplifts for any Audio knob < 0.35, since drive ≤ depth); flash amplitude now `depth()` instead of 1 (AudioReactive's scaled-hit-response contract — Audio = 0.05 must not strobe full white); WhtCps + TrbSprk interaction resolved (peaks crackle dark under white caps; white-on-white was a no-op); pool-overflow steal hands the eased fraction to `height[]` + remainder to the chase instead of committing full amplitude in one frame (terrain pop); `addBump`/`addOverlayBump` merged into one ±4σ-windowed `addGaussian` kernel (shape-divergence risk + ~2560 wasted Math.exp/frame at a full pool); silt ordering contract documented in code + doc; dead 1.02·H clamp removed from renderSurface, `hueWork` sized to its real capacity | review found the envelope/gate/flash regressions before hardware time; tempo-change-mid-rise un-quantizing noted and accepted (retime-once, flood idiom) |
