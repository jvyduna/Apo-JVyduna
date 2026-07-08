# Lorre

A rotating Lorenz-attractor particle swarm with speed-lit fading trails on the
cube and cylinder.

> Sidecar design doc convention: this file lives beside `Lorre.java` and is the
> source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

Recreates **"Lorre" by Egil Stevens**, from "The Floating Point" collection — a
screensaver showing a swarm of points tracing the Lorenz strange attractor,
slowly rotating, with fading trails. Signature to preserve: the two-lobed
butterfly must read clearly as two orbiting lobes; the slow rotation reveals its
3D structure; kicked particles visibly re-converge onto the attractor (the
signature move).

## Rendering approach

- **Base class**: `ApotheneumPattern` — needs both cube and cylinder plus the
  exterior→interior copy helpers; per-point raster access is not required
  because everything is drawn into `SurfaceCanvas` buffers.
- **Surfaces**: cube exterior ring (200×45 canvas) + cylinder (120×43 canvas).
  Canvases are copied to the exteriors via `canvas.copyTo(orientation, colors)`
  (or the brightness-multiplier overload when the bass pump is active), then
  interiors mirror via `copyCubeExterior()` / `copyCylinderExterior()`.
- **Simulation**: up to 150 particles in a preallocated `double[3][150]`,
  integrating the Lorenz ODE (`dx=σ(y−x)`, `dy=x(ρ−z)−y`, `dz=xy−βz`; σ=10,
  β=8/3, ρ from the `Rho` knob) with clamped-dt forward Euler: fixed substeps
  of ≤ 0.004 sim units, at most 32 per frame (longer frames dilate sim time
  rather than destabilizing; raised from 12 on 2026-07-06 so the full BassSpd
  surge clears the clamp). Escape guard: any non-finite particle or one
  beyond radius 250 respawns near a fixed point C± = (±√(β(ρ−1)), ±√(β(ρ−1)),
  ρ−1). The constructor scatters the swarm and silently integrates 2 sim units
  so frame one already shows the butterfly.
- **Particle lifecycle (Count / ring window)**: live particles occupy a ring
  window `(head + k) % 150, k < activeCount` over the preallocated arrays,
  reconciled to the `Count` knob once per frame. Decreases advance `head` —
  the **oldest particles are killed first** and their trails fade out via
  canvas decay; increases birth at the tail by **copying a random live
  particle plus ±1 unit of jitter** (`CURATE:` birth jitter), so newborns
  visibly emerge from the swarm and diverge chaotically. A sudden knob change
  reads as births/deaths, never a discontinuity. Sim/kick/reseed loops iterate
  only the window, so **compute per frame scales with Count**.
- **Projection / views**: the swarm is rotated about the attractor's vertical z
  axis (slow Y rotation) and orthographically projected (screen-x =
  `x·cosθ − y·sinθ`, screen-y = z). *Interpretation vs. the plan:* rather than
  one image centered on the 200-wide ring (which would leave three cube faces
  black), the cube canvas holds **four views 90° apart, one centered per face**,
  and the cylinder **two views 180° apart**. Because the faces physically sit
  90° apart, this is equivalent to orthographically projecting a single 3D
  attractor at the center of the building onto each face — every viewer sees the
  butterfly, and rotation stays coherent between adjacent faces. Each view is
  still exactly the prescribed rotation + orthographic projection. `CURATE:`
  verify on the sculpture that the four cube views rotate coherently (the
  screen-x sign convention vs. the physical column winding direction was not
  verifiable off-line; if adjacent faces appear to counter-rotate, negate the
  per-view heading offset or the u sign).
- **Vertical position (YPos)**: a per-frame row offset of `−yPos × height`
  (positive knob = attractor up; canvas y is top-down) slides the whole
  projection by up to half the surface height in either direction. Off-canvas
  rows are silently dropped by `SurfaceCanvas.set()`, so no clipping code.
- **Scaling**: tracks ρ so regime hops change shape/tempo, not size. Vertical
  maps z ∈ [0, 2ρ−6] into the canvas height minus 2 margin rows — the 2ρ−6
  extent is a linear fit to measured long-run z maxima (49.9 at ρ=28, 74.0 at
  ρ=40; 300 particles × 900 sim units), so the full attractor fits with a couple
  rows of margin per the brief. Horizontal budget is 1.2ρ (measured max rotated
  radius: 33.0 at ρ=28, 45.4 at ρ=40), capped so a view fits its span of the
  ring (at ρ=28: cube ≈ 48 of 50 columns per face, cylinder ≈ 51 of 60).
- **Trails**: `decay()` once per frame per canvas instead of clearing; heads by
  particle speed (below). Equal-channel decay preserves hue and integer
  truncation extinguishes trails fully to black — no gray-mush floor.
- **Density reveal (Vis%)**: only the first
  `1 + round(vis/100 × (activeCount − 1))` window slots are plotted; the rest
  **keep simulating invisibly** (constant compute), so raising Vis% is an
  instant reveal. Vis% 0 shows exactly one particle and its trail. Ring order
  is uncorrelated with spatial position (chaotic mixing), so lowering Vis%
  thins density uniformly across the attractor rather than blanking a region.
  `CURATE:` mapping is linear; consider vis² if the low end needs finer
  control.
- **Color (palette)**: each particle is dealt a random color index at birth
  (also on reseed and escape-respawn) in `[0, 60)` — 60 = LCM(1..5), reduced
  mod the active swatch size at draw time, so the deal stays uniform when the
  swatch grows or shrinks live, with no listener. The active swatch's ≤5
  colors are read and h/s/b-decomposed **once per frame** (tint-blended when a
  Tint crossfade is in flight); the per-particle path is flat array reads.
- **Buffers**: everything (`pos`, `spd`, `colorIndex`, palette caches,
  canvases, per-view cos/sin arrays) is preallocated in the constructor. Zero
  allocation in the render path (`LXColor.hsb/.lerp/.h/.s/.b` and
  `Random.nextDouble` are all allocation-free). Trigger handlers allocate
  nothing either; `TriggerBag.fire` does event-rate logging allocation, which
  is accepted by convention.
- **Door columns**: only written via `SurfaceCanvas.copyTo`, which guards with
  `column.points.length`.

## Audio mapping

All reactivity **except BassSpd** is gated by the **Audio depth knob** (`audio`,
default 0), attached via `AudioReactive.setDepth(audioDepth)`. No per-site
gating exists in the pattern — the taps themselves scale with depth. BassSpd is
deliberately **independent of Audio** (2026-07-06, Jeff's request): it is its
own depth control for the bass→speed coupling, reading the depth-independent
`bassFast` tap, so the two knobs act independently on the same FFT data.
The 2026-07-05 curation pass replaced the subtle ±30% dt level-breathing with
two overt mechanisms (brightness pump, BassSpd) after review feedback that max
reactivity was not visible enough.

- **Depth 0 (default)**: every Audio-gated tap reads its silence value — the
  pump multiplier is exactly 1 (the plain `copyTo` path is taken, bit-identical
  output), no mini-kicks fire, trails sit at the knob's base half-life. BassSpd
  also defaults to 0, so default knobs remain a pure screensaver.
- **Raising the knob** restores, linearly with depth:
  - **Brightness pump** — output copies scale by `1 + 0.6·bass`
    (`BASS_PUMP = 0.6`, `CURATE:`): the whole swarm and its trails visibly
    flash up to ~1.6× with the low end at depth 1. The unmistakable
    max-reactivity tell.
  - `bassHit()` fires a **mini-kick**: the same perturbation as the Kick
    trigger at a fixed `MINI_KICK_MAGNITUDE = 10` Lorenz units × `depth()`
    (was 2–6 energy-scaled; raised ~2–3× for visibility, `CURATE:` obvious
    punch but still short of Kick's 25).
  - `trebleRatio` shortens the trail half-life by up to ~3.5× when treble runs
    above its running average (`halfLife / (1 + 0.35·max(0, trebleRatio − 1))`)
    — busy hi-hats crisp the trails. (Unchanged.)
- **BassSpd** (`bassSpd` knob, 0–1, default 0, **independent of Audio**) —
  turns the bass **level** into a speed surge relative to the current Speed:
  `simRate = 0.045 × (Speed + 16·bassSpd·drive·max(Speed, 1))` with
  `drive = AudioReactive.bassFast` — the depth-independent, **level-based**
  smoothed bass band (attack 15 ms, release 85 ms, both under the 94 ms 16th
  note at 160 BPM). At BassSpd 1, saturated bass runs the sim at up to
  **17× the current Speed** (`BASS_SPEED_GAIN = 16` `CURATE:` — 16× the
  forward progress of the previous 2× ceiling, per Jeff: the pulse version
  was still way too subtle). Level-based rather than trough-referenced
  (2026-07-06): the previous `bassPulseRaw` drive decayed to ~0 within a
  couple seconds of **sustained** bass as its trough tracker relaxed upward —
  now a sustained high bass FFT level *holds* the speed elevated, while the
  sub-16th-note attack/release still resolves individual 160 BPM 16th-note
  hits as distinct hit-and-decay surges. The `max(Speed, 1)` floor keeps the
  Speed-0 behavior: a frozen swarm lurches forward only when the bass does.
  Silence-safe: the drive decays to ~0 with no audio input.
- Removed 2026-07-05: the ±30% integration-dt level breathing. Its silence
  factor (0.7×) is folded into the Speed rebaseline calibration below, so
  depth-0 behavior is unchanged from the previous build.

## Tempo mapping

Sync/TempoDiv and the whole trigger-deferral mechanism were **removed
2026-07-06** (Jeff: triggers will be properly aligned in the composition
timeline, so the pattern should not quantize them). Kick, Reseed, Tint and
RndTrig-fired actions — including RndTrig parameter jumps — all execute
immediately; `TempoLock` is no longer used by this pattern.

Tempo still drives two things, read directly from `lx.engine.tempo`:

- **Y rotation (YRotDiv)**: one quarter turn per selected division (pattern-
  local `RotDiv` enum: Off, 1/4, 1/2, 1 bar, 2, 4, 8, 16 bars; default
  **4 bars** ≈ the old default rate at 120 BPM). Phase is accumulated
  incrementally per frame (`Δangle = deltaMs / divisionMs × π/2`), so a live
  BPM or division change alters the *rate* without snapping the angle. Off
  freezes rotation.
- **Tint crossfade duration**: one beat (`tempo.period`), captured at trigger
  time (a BPM change mid-fade drifts the ending — accepted).

bassHit mini-kicks stay **audio-timed** — transients are already musically
placed.

## Desat mapping

The former `Energy` knob is now **Desat** and does exactly one job: the
white-hot desaturation gain of fast particle heads, mapped directly 0–1
(`sat = 100·(1 − desat·b²)`). Default 0.55 matches the old look (Energy 0.35 →
gain 0.54). `CURATE:` default and feel of the extremes.

Energy's two other former couplings were removed on 2026-07-05:
- the sim-rate multiplier (0.8–1.4×) — tempo is now entirely the rebaselined
  Speed knob (+ BassSpd);
- the mini-kick magnitude scaling (2–6) — now the fixed constant 10 × depth.

## Parameters

UI order (2026-07-06 series convention): triggers first with RndTrig
immediately after them, then pattern parameters, audio pair last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `kick` | Kick | TriggerParameter | — | — | perturb every live particle; swarm re-converges over ~5 s |
| `reseed` | Reseed | TriggerParameter | — | — | re-scatter live particles in the attractor's bounding box |
| `tint` | Tint | TriggerParameter | — | — | one-beat crossfade of every particle to its next palette color (mod swatch size) |
| `rndTrig` | RndTrig | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |
| `desat` | Desat | CompoundParameter | 0.55 | 0–1 | white-hot desaturation of fast heads (0 = saturated, 1 = pastel) |
| `rho` | Rho | CompoundParameter | 28 | 20–45 | Lorenz ρ; regime control (near-stable spirals low, wild chaos high) |
| `speed` | Speed | CompoundParameter | 2.5 | 0–8, exp 2 | orbit tempo: 0 = frozen moment, 1 = 100% (old slowest), 2.5 = old default, 4 = old fastest, 8 = 800% (2× old fastest) |
| `count` | Count | DiscreteParameter | 40 | 1–150 | simulated particles; kills oldest first, births emerge from the swarm; compute scales with it |
| `vis` | Vis% | CompoundParameter | 50 | 0–100 (%) | density reveal: 0 = one particle + trail, 100 = whole swarm; hidden particles keep simulating |
| `yPos` | YPos | CompoundParameter | 0 | −0.5–0.5 | attractor vertical position: ±50% of the sculpture height below/above |
| `yRotDiv` | YRotDiv | EnumParameter&lt;RotDiv&gt; | 4 bars | Off, 1/4 – 16 bars | one quarter turn per division; Off freezes rotation |
| `trails` | Trails | CompoundParameter | 0.5 | 0–1 | trail half-life, exp 150 ms – 1.2 s |
| `audio` | Audio | CompoundParameter | 0 | 0–1 | audio depth: bass hits mini-kick the swarm, bass pumps brightness, treble shortens trails |
| `bassSpd` | BassSpd | CompoundParameter | 0 | 0–1 | bass level → speed, independent of Audio: up to ~17× current Speed at full, holds under sustained bass |

Renames 2026-07-05 (labels **and** paths, per curation): Energy→Desat,
Rotate→YRotSpd, Meta→RndTrig. Removed/re-ranged 2026-07-06: `hue`, `sync`,
`tempoDiv`, `yRotSpd` paths dropped (saved values silently ignored on load);
`yRotDiv` is a new path; `vis` re-ranged 0–1 → 0–100 in place, so an old saved
1.0 loads as 1% (near-single-particle — reset it); old `count` values clamp to
150 and the default dropped 300 → 40.

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

## Triggers

Three non-meta triggers spanning small → large, all executing immediately
(alignment is the composition timeline's job). All operate on the live (Count)
window only.

- `tint` (small) — a **one-beat crossfade** of every particle from its palette
  color to the next swatch color (`+1 mod` swatch size), implemented as a
  global committed offset plus a blend phase: the ≤5 cached swatch colors are
  RGB-lerped toward their successors once per frame, and on completion the
  offset advances. Retriggering mid-fade commits the in-flight target and
  restarts toward the next color (hammering Tint walks the swatch, never
  snaps back). With a 1-color swatch it is a no-op. `CURATE:` confirm the
  one-beat fade reads as a musical recolor.
- `kick` (large) — every particle is offset by a uniform random vector in ±25
  units. The screen explodes into scattered dust, then the attractor's strong
  transverse contraction pulls the swarm back onto the butterfly — visibly
  re-formed within ~5 s (measured; table below). The signature move.
- `reseed` (large) — particles re-scatter uniformly in the bounding box
  (x ∈ ±0.8ρ, y ∈ ±ρ, z ∈ [0, 0.75·(2ρ−6)]); a softer full reset with the same
  convergence read.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `rho` | [24, 40] | candidate | regime-hopping — best jump in the suite |
| `yRotDiv` | [4 bars, 16 bars] | candidate | ordinal window over the slow divisions; never jumps to Off or the fast end (replaces the old yRotSpd [0.15, 1.0]) |
| `trails` | full [0, 1] | candidate | |
| `speed` | [1.5, 3.5] | candidate | re-ranged 2026-07-05 from [0.6, 1.4] into the rebaselined units — same musical window, keeps dt stable and avoids a frozen jump |

(`hue` was a jump candidate until 2026-07-06, removed with the knob — color now
comes from the palette.)

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

Count/Vis/YPos are deliberately **not** jumpable: a random jump there reads as
a glitch (mass die-off, blackout, attractor teleport), not a musical hit.

## Simulation-principles compliance

**Lobe orbit tempo** (`CURATE:` the rebaselined `SIM_RATE_AT_SPEED_1 = 0.045`):
one orbit around a lobe takes ≈ 0.73 Lorenz time units at ρ=28 (measured: mean
z-maximum period 0.727 over 2,752 loops). `simRate = 0.045 × (Speed +
16·bassSpd·drive·max(Speed, 1))` with `drive = bassFast` (0..1, level-based
smoothed bass); wall time = 0.73 / simRate:

- Default (Speed 2.5, silence or BassSpd 0): simRate = 0.1125 → orbit ≈
  **6.5 s** — numerically the old default (0.113), inside the 4–8 s target.
  Speed 1 (100%) = old knob minimum → 16.2 s. Speed 0 = frozen moment
  (integration skipped entirely; rotation, trails, triggers and mini-kicks
  still run).
- Speed knob max (8 = 2× old fastest): simRate = 0.36 → orbit 2.0 s, heads
  ≈ 58 px/s at ρ=28 — *below* the previously analyzed extreme (0.466 / 75
  px/s), so the prior analysis holds: this is local orbiting confined within
  one ≈48-column view, texture rather than sculpture traversal.
- Absolute max (Speed 8 + BassSpd 1 + saturated bass): simRate =
  0.045 × (8 + 16×8) = 6.12 → the swarm blurs into streaks for the duration
  of the bass (the 85 ms release returns it to the plain Speed rate between
  hits). This is the deliberate 16× "bass slams the swarm forward" read Jeff
  asked for; still confined within one ≈48-column view. Default Speed 2.5 at
  full BassSpd + saturated bass: simRate 1.91, orbit ≈ 0.4 s. `CURATE:`
  confirm the surge reads as slamming-forward, not strobing, and that
  moderate BassSpd values (~0.2–0.5) give the musical middle ground.

**Rotation** (the only true full-sculpture sustained motion): tempo-relative
via YRotDiv, one quarter turn per division — a full revolution takes 4
divisions: at 120 BPM, 1/4 → 8 s/rev (the fastest choice; still ≥ 5 s), the
4-bar default → 32 s/rev (≈ the old default 40 s), 16 bars → 128 s/rev. At
extreme BPM the 1/4 choice can dip below 5 s/rev (e.g. 180 BPM → 5.3 s/rev is
still fine; `CURATE:` avoid 1/4 at very high BPM if it strobes).

**Kick re-convergence ≈ 5 s** (measured off-line: 100 particles kicked ±25 off
a settled swarm at rate 0.113 sim/s — numerically the current default;
distance to a dense reference trajectory on the attractor):

| wall time | avg distance | worst |
|---|---|---|
| 0 s | 13.5 | 32.1 |
| 2 s | 3.8 | 20.6 |
| 5 s | 0.8 | 5.3 |

Average error is sub-pixel by ~5 s (1 Lorenz unit ≈ 0.7 px at cube scale).
`CURATE:` KICK_MAGNITUDE = 25 — confirm the scatter-and-snap-back reads on the
sculpture.

**Integration stability**: forward Euler at h ≤ 0.004 sim units with a
32-substep/frame cap and an escape-guard respawn. Verified off-line: zero
escapes over 300 particles × 900 sim units at both ρ=28 and ρ=40 (the original
stability run; per-substep h is unchanged). The absolute max simRate (6.12)
stays inside the raised substep cap (0.004 × 32 = 0.128 sim units/frame
supports simRate ≤ ~7.7 at 60 FPS before time dilation); worst-case cost is
26 substeps × 150 particles ≈ the old 12 × 300, so CPU is unchanged.

**Contrast / brightness**: bold point-swarm forms, no fine texture. Brightness
by particle speed with a 0.3 floor (slow lobe-center particles stay visible at
LED distance); fast heads desaturate toward white-hot (Desat knob) relative to
their palette color's own saturation, slow particles stay saturated; a small
hue spread (±12°, `HUE_SPREAD_DEG = 24` `CURATE:`) centered on each particle's
palette hue across the attractor's height for depth cueing. Trails decay
equal-channel (hue-preserving)
and truncate fully to black; max half-life is capped at 1.2 s so trails never
wash into gray mush. The bass brightness pump multiplies output up to 1.6×
(clamped at 255 per channel in `copyTo`).

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | Per approved plan. Numerically verified off-line: lobe period 0.727, z/horizontal extents at ρ=28/40, Euler stability (0 escapes), kick re-convergence ~5 s. `CURATE:` constants pending on-sculpture review: orbit tempo feel, kick magnitudes, trail range, white-hot gain, hue spread |
| 2026-07-05 | Review + series upgrade (Fable): added `audio` depth knob (default 0 = pure screensaver, wired via `AudioReactive.setDepth`; mini-kick response scaled by `depth()`); added `sync`/`tempoDiv` (HALF) — Kick/Reseed/Tint defer to the next grid boundary when Sync is on, `crossed()` polled every frame to avoid a stale gate; added third trigger `tint` (golden-ratio hue step); fixed the compliance-math error (head speed at absolute max is ≈75 px/s confined within one 48-column view, not the previously claimed 25 px/s — full-sculpture motion remains rotation at ≥30 s/rev); added CURATE notes for cube-view rotation coherence and max-rate swirl. Meta parameter jumps still apply off-grid (TriggerBag has no deferral hook — noted as a util request) | Series conventions (TEMPLATE.md 2026-07-05) + bug hunt |
| 2026-07-05 | Integration pass: util request granted — `TriggerBag.setJumpScheduler` added, and Meta parameter jumps now defer to the TempoDiv grid via the same pending mechanism as the triggers (`requestJump`/`pendingJump`, latest-wins coalescing, released immediately on Sync off). CURATE: unverified visually — confirm a deferred rho hop landing on a HALF boundary reads as a musical hit | Lorre agent + reviewer both requested the TriggerBag deferral hook; wired it end-to-end |
| 2026-07-05 | Curation pass (Jeff's review): **Speed rebaselined** to 0–8 with exponent 2 (`SIM_RATE_AT_SPEED_1 = 0.045` absorbs the removed energy/silence factors; 0 = frozen moment, 1 = old slowest, 2.5 = default = old look, 8 = 2× old fastest; RndTrig jump range re-ranged to [1.5, 3.5]). **Renames** (labels + paths): Energy→`Desat` (now pure desaturation 0–1, default 0.55 ≈ old look; sim-rate and mini-kick couplings removed), Rotate→`YRotSpd`, Meta→`RndTrig`. **New params**: `Count` (1–300 ring window, kills oldest first, births copy a live particle + ±1 jitter — `CURATE:` jitter size; compute scales with count), `Vis` (density reveal, ring-order gating, hidden particles keep simulating — `CURATE:` linear vs vis² low end), `YPos` (±50% sculpture-height vertical offset), `BassSpd` (bass→timebase accumulation, `BASS_SPEED_GAIN = 4` `CURATE:`). **Audio punch**: dt level-breathing removed; added bass brightness pump (`BASS_PUMP = 0.6` `CURATE:`) and fixed mini-kick 10 × depth (`CURATE:`). All `CURATE:` items unverified on sculpture; saved project knobs for renamed/re-scaled params reset on load (accepted) | Jeff's curation review notes 2026-07-05 |
| 2026-07-06 | **BassSpd rework**: additive-absolute smoothed-bass term (`0.045 × (Speed + 4·bassSpd·bass)`) → Speed-relative transient pulse (`0.045 × (Speed + bassSpd·pulse·max(Speed, 1))`, `pulse = bassPulseRaw`; `BASS_SPEED_MIN_BASE = 1` `CURATE:`). Signal switched from depth-gated `bass` (250 ms-release EMA — near-constant on sustained bass, and imperceptible as an absolute-units boost at high Speed) to the new **`bassPulseRaw`** tap in `AudioReactive`: depth-independent, trough-referenced bass rise saturating at `PULSE_FULL_RISE_DB = 6` dB — same trough reference as the 160 BPM hit-detection tuning, since ratio-vs-average signals starve at eighth-note density; attacks with the meter on a kick, decays (release 100 ms) before the next beat, sustained bass wobble (1–2 dB) reads ~0. No new decay knob needed. **Decoupled from Audio** (Jeff's request): BassSpd is its own depth control; default dropped 0.5 → 0 so default knobs remain a pure screensaver. `max(Speed, 1)` floor preserves the Speed-0 lurch. New absolute max simRate 0.72 (transient-only), still inside the substep cap | Jeff: BassSpd imperceptible on bass-heavy 160 BPM music — must scale relative to current Speed (~2× at full), visibly jump on each kick settling before the next beat, and act independently of the Audio knob |
| 2026-07-06 | Curation pass 2 (Jeff's review): **YRotSpd → `YRotDiv`** (pattern-local RotDiv enum: Off + 1/4…16 bars; one quarter turn per division, incremental phase accumulation so BPM/division changes never snap the angle; default 4 bars ≈ old rate; jump window [4 bars, 16 bars]). **Hue removed — palette color**: per-particle random swatch index dealt at birth/reseed/respawn (`COLOR_INDEX_WRAP = 60` = LCM(1..5), mod swatch size at draw); swatch cached + h/s/b-decomposed once per frame; `HUE_SPREAD_DEG = 24` `CURATE:`. **Tint** is now a one-beat crossfade advancing every particle +1 swatch color (retrigger commits + restarts). **Count** max 300 → 150, default 40. **Vis → Vis%** (0–100, `Units.PERCENT`, default 50). **BassSpd ×16** (`BASS_SPEED_GAIN = 16` `CURATE:`): drive switched `bassPulseRaw` → new level-based `AudioReactive.bassFast` (attack 15 ms / release 85 ms, both < a 160 BPM 16th note) so sustained bass holds the speed-up instead of decaying with the trough tracker; `MAX_SUBSTEPS` 12 → 32 so the surge clears the dt clamp (max simRate 6.12 < cap 7.7). **Sync/TempoDiv removed** with the whole deferral mechanism — triggers and RndTrig jumps fire immediately (composition timeline aligns them). **Param order**: RndTrig moved up to 4th, right after the triggers (series convention). .lxp fallout accepted: hue/sync/tempoDiv/yRotSpd paths dropped, vis re-ranged in place (old 1.0 → 1%) | Jeff's curation review notes 2026-07-06: BassSpd still way too subtle (16× forward progress, sustained bass must hold); palette-driven color; triggers aligned in composition |
