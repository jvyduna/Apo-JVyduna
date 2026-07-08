# Terraform

Evolving terrain skyline: tempo-locked uplifts raise mountains, erosion silts
the sea, and a settable sea level (with Water and silt) drives flood/eruption
triggers. The sea is independent of the music (2026-07-07 revision).

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
erode, and drown or emerge as the sea level moves. The sea rests at a settable
`SeaLevel` (plus `Water` and erosion silt) and is choreographed by the artist
and the Flood/Erupt triggers rather than pumped by the music (2026-07-07
revision — audio no longer moves the sea). An optional `Smooth` knob
anti-aliases the band and sea-surface edges.

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
    grass / rock / peaks.
  - above both → sky (black).
  - **Edge smoothing** (`Smooth`, 2026-07-07): the band-to-band edges and the
    sea surface top are anti-aliased. Each pixel's color is a chain of
    `LXColor.lerp`s across the band tops and then across the sea surface, using
    an `edge(elev, boundary, halfWidth)` smoothstep with
    `halfWidth = Smooth · SMOOTH_MAX_ROWS` (1.25 rows). At `Smooth = 0` the
    smoothstep collapses to the original hard integer thresholds. The **land/sky
    silhouette** (terrain top at `h`) is deliberately left hard — only bands and
    the sea top smooth. Zero allocation (scalar math + `LXColor.lerp`).
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

Note (2026-07-07): **audio no longer moves the sea.** The former
`level → seaLevel` sea-pumping tap and its `SEA_SWING`/`SEA_TAU_SEC` constants
were removed. Audio now drives only uplifts, the erosion regime, and the treble
flash — never the sea height.

| Tap | Drives |
|---|---|
| `level` → `drive` (slow smoother, τ = 2 s) | Everything the old Energy knob drove: uplift rate/amplitude/sigma, diffusion blend, subsidence speed, bass-uplift gate. See "Drive mapping". |
| `bassHit()` + `bassRatio` | At smoothed level ≥ 0.35 (gate = 0.35 × depth compared against `drive`, so it reads the depth-independent level — a fixed gate would silently disable bass uplifts for any Audio knob below it): each bass transient raises a new mountain (tempo-quantized uplift); amplitude scales with `bassRatio`. |
| `trebleHit()` | TrbSprk white flash burst: flash envelope snaps to `depth()` (the AudioReactive scaled-hit-response contract — a barely-on knob must not strobe at full brightness) and decays (τ = 45 ms); a fresh random ~45%·TrbSprk subset of peak-band pixels (plus a 2-row spill into rock) lerps toward white by the envelope (toward black on the peaks when `WhtCps` is on). |

**Depth-0 baseline (default, = silence behavior)**: the sea settles at
`SeaLevel` (default 0.5 ≈ mid-level) plus any `Water` and any silt from ongoing
erosion, the spontaneous uplift timer keeps slowly raising new mountains, and
erosion keeps aging them — a calm, self-evolving archipelago. Designed to look
good with zero audio.

**Raising the knob restores**: bass-hit mountain births under sustained loud
music, and treble flash bursts (the sea is unaffected). Magnitude effects
(bass-uplift amplitude via `bassRatio`) scale linearly with depth; bass-hit
births start firing once depth > 0.01 *and* drive ≥ 0.35 at a floor amplitude
of 0.6× (the `bassRatio` term supplies the remaining 0.4× as depth rises).

## Tempo mapping

Discrete formation events lock to the grid; continuous morphing (erosion,
height chase) stays smooth and is never tempo-quantized. Default division:
`QUARTER`.

- **Uplift rises complete on the grid** — every uplift (timer, bass, Erupt —
  including the guaranteed-breach eruption) is an eased envelope whose peak
  lands exactly on a `tempoDiv` boundary. Duration rule (grid-strict): finish
  on the next boundary, unless it is less than half a division away — then
  target the boundary after. Durations land in [0.5, 1.5) divisions; timer
  births fire *on* crossings, so they get exactly 1.0 division.
- **Spontaneous mountain births initiate on the grid** — a
  `TempoLock.crossed(tempoDiv)`
  per-frame gate: on each grid crossing a birth fires with probability
  `upliftRate × divisionMs / 1000`, preserving the same expected rate as the
  Poisson timer (capped at one birth per division cycle — at peak drive with
  divisions longer than ~2 s the effective rate saturates at one per
  division). Births land exactly on the beat; drive controls density.
- **Sea rate** — outside a flood the sea chases its nominal goal (SeaLevel +
  Water + silt) at one full sweep per beat (`1000 / tempo.period` fraction/s),
  so a parameter move eases in over a beat rather than jumping. Not
  phase-locked, just rate-matched.
- **Flood timing** (2026-07-07) — the `Flood` trigger drives the sea over a
  fixed beat-count envelope, not a seconds ramp: **1 beat** smoothstep rise
  from the current level to the top (`SEA_FLOOD` = 1.0), then **8 beats**
  smoothstep settle back to the live nominal sea. Beat length is captured at
  trigger from `tempo.period`, so the whole gesture scales with BPM. The old
  `retime`-to-grid landing was dropped — the durations are exact beat multiples
  from the press.
- **Not locked**: cataclysm/reseed triggers (fire when pressed; their terrain
  writes ride the background chase), erosion, subsidence, roughness jitter.

The `Sync` toggle was removed 2026-07-06 — grid-locking of these ambient
events is always on (triggers themselves fire immediately; the grid only
shapes ambient timing and completion landing).
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

UI order (2026-07-06 series convention): triggers first with RndTrig
immediately after them, then pattern parameters, Audio, TempoDiv.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `cataclysm` | Cataclysm | TriggerParameter | — | — | huge ridge + ≤ 0.5 s whole-ring shake, then settles |
| `flood` | Flood | TriggerParameter | — | — | 1-beat smoothstep rise to the top, then 8-beat smoothstep settle back |
| `reseed` | Reseed | TriggerParameter | — | — | morph to a fresh random terrain over ~5 s |
| `erupt` | Erupt | TriggerParameter | — | — | raise one new mountain now, always breaching the sea by ≥ 3 rows |
| `rndTrig` | RndTrig | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |
| `upliftSize` | Uplift | CompoundParameter | 0.5 | 0..1 | amplitude of new mountain uplifts |
| `erosion` | Erosion | CompoundParameter | 0.4 | 0..10, exponent 2 | diffusion + subsidence rate; quadratic knob (value = 10·knob²) gives fine low-end resolution, top is 10× the old max |
| `rough` | Rough | CompoundParameter | 0.25 | 0..1 | terrain ruggedness: continuous ±2.7-row jitter bumps/divots, up to 25 /s per surface |
| `seaLevel` | SeaLevel | CompoundParameter | 0.5 | 0..0.9 | resting sea level (fraction of height); bottom = sea at the base. Independent of the music; silt/eruptions fluctuate it |
| `water` | Water | CompoundParameter | 0 | 0..0.5 | extra water volume raising the sea above SeaLevel (adds upward only) |
| `bandShift` | Bands | CompoundParameter | 0 | -0.2..0.2 | shift all band thresholds up/down (fraction of height) |
| `smoothing` | Smooth | CompoundParameter | 0 | 0..1 | anti-alias band-to-band edges and the sea surface top; 0 = hard edges |
| `whiteCaps` | WhtCps | BooleanParameter | false | — | peaks band pure white; swatch color 0 discarded (no band shifting); TrbSprk crackles the peaks dark |
| `trbSprk` | TrbSprk | CompoundParameter | 0.5 | 0..1 | treble-hit white flash burst coverage on the peaks |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth: 0 = pure screensaver, 1 = full reactivity |
| `tempoDiv` | TempoDiv | EnumParameter\<Tempo.Division\> | QUARTER | divisions | grid that uplift rises, births and flood landings land on |

Removed/renamed 2026-07-06: `sync` path dropped (grid-locking is baked on;
`UPLIFT_FREE_RISE_MS` free path deleted); `meta` → `rndTrig` (label Meta →
RndTrig) and moved up next to the triggers. Saved `.lxp` references to the
old paths are silently dropped on load.

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

Param-key note: the 2026-07-06 renames (`sparkle` → `trbSprk`, removed
`energy`, added `rough`/`whiteCaps`) change the saved-parameter keys — older
.lxp projects will not restore the old Sparkle/Energy values. Accepted during
curation. **2026-07-07**: `seaBias` (key `seaBias`) → `seaLevel` (key
`seaLevel`), and new `water`/`smoothing` keys added — old `.lxp` `seaBias`
references are dropped on load. Accepted (the pattern is new).

## Triggers

Four non-meta triggers spanning small → large, all registered through the
`TriggerBag` (so RndTrig can fire any of them):

- `erupt` (small) — books a single new mountain on each surface (drive-scaled
  sigma, random column, rise completes on the next tempoDiv boundary). Unlike a
  spontaneous uplift, the amplitude is forced to at least
  `seaFrac·H + ERUPT_MARGIN_ROWS − height[center]`, so the crest **always**
  emerges ≥ 3 rows above the current sea surface, at any sea level or local
  terrain height (2026-07-07). Only a fully-flooded installation (sea at the
  very top) leaves no headroom.
- `cataclysm` — adds a mountain-range ridge (0.85 H central peak + two 0.55 H
  shoulders, σ = 5% of ring) to both surfaces' targets and starts the shake: a
  spatial sine ripple (±2.5 rows, 7 Hz, decaying) across every column for
  0.45 s. The ridge then *grows* under the rate limit (~4.3 s to full height)
  and erodes over the following tens of seconds. Deliberately unquantized.
  CURATE: shake amp 2.5 rows / 7 Hz / 0.6 rad-per-column wavelength
  unverified — must read as a tremor, not flicker.
- `flood` (2026-07-07) — a two-phase beat-count envelope that drives `seaFrac`
  directly (bypassing the chase): **rise** over 1 beat, smoothstepping from the
  level at the press to the top (`SEA_FLOOD` = 1.0, total drowning), then
  **settle** over 8 beats, smoothstepping from the top back to the live nominal
  sea (SeaLevel + Water + silt at that moment). Beat length is captured at the
  trigger from `tempo.period`, so both phases scale with BPM. Replaces the old
  5 s ramp + 2 s hold + fast drain.
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
| `seaLevel` | 0.2..0.7 | candidate | resting sea level; sub-range avoids near-empty/near-full seas |

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
- **Documented exception — one-beat sea rate** (2026-07-06; sea/audio decoupled
  2026-07-07): the previous "sea ≥ 5 s per full traversal" claim is **revoked**.
  Outside a flood the sea chases its nominal goal (SeaLevel + Water + silt) at
  one full sweep per beat, so a parameter move eases in within a beat rather
  than jumping. The Flood trigger drives the sea directly over a 1-beat rise /
  8-beat settle smoothstep envelope (≈ 52 rows over 1 beat at 160 BPM on the
  cube — event-class localized-in-time motion, same category as the cataclysm
  shake). CURATE: verify the flood rise/settle reads as a tide, not a jump, at
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

Other unverified constants: CURATE: `DRIVE_TAU_SEC` 2 (drive swings the
uplift/erosion regime over phrases, not bars?). CURATE: `SMOOTH_MAX_ROWS` 1.25
and `ERUPT_MARGIN_ROWS` 3 (does Smooth = 1 read as anti-aliased, not blurry;
does an eruption clear the water by a satisfying margin?). CURATE: spontaneous
birth
rates 0.06→0.5 /s, applied as per-crossing probability on the tempo grid —
is ambient lively enough / peak not cluttered?
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
| 2026-07-06 | Series RndTrig/Sync cleanup: `meta` → `rndTrig` (label RndTrig) and moved to 5th, right after the triggers; `Sync` removed with grid-locking baked always-on (uplift rises, grid-gated births, flood-ramp retime); the Sync-off free paths (`UPLIFT_FREE_RISE_MS` 1.4 s rise, Poisson birth timer, unretimed flood ramp) deleted. `.lxp` values on the old `sync`/`meta` paths are dropped on load | Jeff 2026-07-06: RndTrig placement convention; remove Sync booleans — triggers are aligned in the composition timeline, ambient events stay tempo-locked |
| 2026-07-07 | Feedback revision (6 changes, no visual verification yet): **audio decoupled from the sea** — removed the `level → seaLevel` pump, `SEA_SWING`, `SEA_TAU_SEC`; sea goal is now `SeaLevel + Water + silt`. `seaBias` → **`SeaLevel`** (range 0..0.9, default 0.5; bottom = sea at the base) and **settled at init** (`seaFrac = seaGoal()` — no start-up motion). New **`Water`** reservoir param (0..0.5, adds upward only). New **`Smooth`** knob (0..1) anti-aliasing band and sea-surface edges via an `edge()` smoothstep (land/sky silhouette left hard). **`Erupt`** now guarantees a breach: amplitude forced to clear the sea by `ERUPT_MARGIN_ROWS` (3). **`Flood`** rewritten to a 1-beat smoothstep rise to the top + 8-beat smoothstep settle to the live nominal (beat length captured at trigger; dropped the seconds ramp/hold/retime). `SEA_MIN` 0.05→0, `SEA_MAX` 0.9→0.98, `SEA_FLOOD` 0.97→1.0 | Jeff's feedback: sea level should be a reliable, settable, music-independent quantity that init rests at; eruptions must always emerge; flood should be a fast tide (1 beat up, 8 down); smoother edges available on demand |
