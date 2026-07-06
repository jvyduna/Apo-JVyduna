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
- **Simulation**: up to 300 particles in a preallocated `double[3][300]`,
  integrating the Lorenz ODE (`dx=σ(y−x)`, `dy=x(ρ−z)−y`, `dz=xy−βz`; σ=10,
  β=8/3, ρ from the `Rho` knob) with clamped-dt forward Euler: fixed substeps
  of ≤ 0.004 sim units, at most 12 per frame (longer frames dilate sim time
  rather than destabilizing). Escape guard: any non-finite particle or one
  beyond radius 250 respawns near a fixed point C± = (±√(β(ρ−1)), ±√(β(ρ−1)),
  ρ−1). The constructor scatters the swarm and silently integrates 2 sim units
  so frame one already shows the butterfly.
- **Particle lifecycle (Count / ring window)**: live particles occupy a ring
  window `(head + k) % 300, k < activeCount` over the preallocated arrays,
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
- **Density reveal (Vis)**: only the first
  `1 + round(vis × (activeCount − 1))` window slots are plotted; the rest
  **keep simulating invisibly** (constant compute), so raising Vis is an
  instant reveal. Vis 0 shows exactly one particle and its trail. Ring order is
  uncorrelated with spatial position (chaotic mixing), so lowering Vis thins
  density uniformly across the attractor rather than blanking a region.
  `CURATE:` mapping is linear; consider vis² if the low end needs finer
  control.
- **Buffers**: everything (`pos`, `spd`, canvases, per-view cos/sin arrays) is
  preallocated in the constructor. Zero allocation in the render path
  (`LXColor.hsb`, `Random.nextDouble`, and `PerceptualHue` are all
  allocation-free). Trigger handlers allocate nothing either; `TriggerBag.fire`
  does event-rate logging allocation, which is accepted by convention.
- **Door columns**: only written via `SurfaceCanvas.copyTo`, which guards with
  `column.points.length`.

## Audio mapping

All reactivity **except BassSpd** is gated by the **Audio depth knob** (`audio`,
default 0), attached via `AudioReactive.setDepth(audioDepth)`. No per-site
gating exists in the pattern — the taps themselves scale with depth. BassSpd is
deliberately **independent of Audio** (2026-07-06, Jeff's request): it is its
own depth control for the bass→speed coupling, reading the depth-independent
`bassPulseRaw` tap, so the two knobs act independently on the same FFT data.
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
  turns bass **transients** into a momentary speed surge relative to the
  current Speed: `simRate = 0.045 × (Speed + bassSpd·pulse·max(Speed, 1))`
  with `pulse = AudioReactive.bassPulseRaw` — the depth-independent,
  trough-referenced rise of the bass band, 0..1, saturating at
  `PULSE_FULL_RISE_DB = 6` dB above the recent trough
  (`BASS_SPEED_MIN_BASE = 1`, `CURATE:`). Trough-referenced for the same
  reason as hit detection (2026-07-06 `AudioReactive` tuning): at eighth-note
  density the running average parks within ~4 dB of the peaks, so
  ratio-vs-average signals starve — the trough reference keeps full margin at
  any playback level, saturates on a real kick, and decays with the raw meter
  (release 100 ms) well before the next beat at 160 BPM. At BassSpd 1 each
  kick reads as a ~2× forward surge **at any orbit tempo** (the pre-2026-07-06
  formula added absolute speed units of depth-gated smoothed bass:
  imperceptible at high Speed, a steady offset rather than a pulse on
  sustained basslines, and zeroed by Audio 0 — the 1–2 dB wobble of sustained
  bass reads near pulse 0). The `max(Speed, 1)` floor keeps the Speed-0
  behavior: a frozen swarm lurches forward only when the bass hits.
  Silence-safe: the pulse reads ~0 with no audio input.
- Removed 2026-07-05: the ±30% integration-dt level breathing. Its silence
  factor (0.7×) is folded into the Speed rebaseline calibration below, so
  depth-0 behavior is unchanged from the previous build.

## Tempo mapping

The continuous motion (lobe orbit, Y rotation) is chaotic/smooth by design and
is **never retimed** — only discrete regime-change instants lock to the grid,
so tempo lock cannot interact with the ≥5 s traversal cap.

- With **Sync on** (default), the three triggers — Kick, Reseed, Tint,
  including RndTrig-fired ones (RndTrig routes through the same trigger
  callbacks) — are **deferred to the next `TempoDiv` boundary** (default
  **HALF**): the scatter/recolor lands on the grid like a musical hit. Deferral
  latency is at most one division (1 s at 120 BPM HALF). Repeat fires within
  one window coalesce into a single action.
- `TempoLock.crossed(division)` is called once per frame unconditionally (even
  with Sync off, where its result is unused) so the gate never goes stale — a
  lapsed gate would treat any old boundary as "just crossed" and fire a
  deferred trigger off-grid.
- bassHit mini-kicks stay **audio-timed**, not grid-quantized — transients are
  already musically placed.
- RndTrig parameter jumps (rho/yRotSpd/trails/speed/hue) get the same deferral:
  the bag routes jump actions through `TriggerBag.setJumpScheduler`, so with
  Sync on a RndTrig-fired rho regime hop also lands on the grid. Repeat RndTrig
  fires within one window coalesce (latest jump wins; the jump value is
  chosen, and logged, at the boundary).
- **Sync off**: triggers and RndTrig jumps fire immediately (the pattern's
  original behavior, fully functional); anything pending when Sync turns off
  is released on the next frame.
- No `retime()` calls: there is no scheduled future arrival to nudge (particle
  motion is chaotic), so only the `crossed()` gate is used.

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

UI order: triggers first, pattern parameters, audio pair, Sync, TempoDiv,
RndTrig last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `kick` | Kick | TriggerParameter | — | — | perturb every live particle; swarm re-converges over ~5 s |
| `reseed` | Reseed | TriggerParameter | — | — | re-scatter live particles in the attractor's bounding box |
| `tint` | Tint | TriggerParameter | — | — | rotate base hue by a golden-ratio step (0.382) of the perceptual wheel |
| `desat` | Desat | CompoundParameter | 0.55 | 0–1 | white-hot desaturation of fast heads (0 = saturated, 1 = pastel) |
| `rho` | Rho | CompoundParameter | 28 | 20–45 | Lorenz ρ; regime control (near-stable spirals low, wild chaos high) |
| `speed` | Speed | CompoundParameter | 2.5 | 0–8, exp 2 | orbit tempo: 0 = frozen moment, 1 = 100% (old slowest), 2.5 = old default, 4 = old fastest, 8 = 800% (2× old fastest) |
| `count` | Count | DiscreteParameter | 300 | 1–300 | simulated particles; kills oldest first, births emerge from the swarm; compute scales with it |
| `vis` | Vis | CompoundParameter | 1 | 0–1 | density reveal ("#vis"): 0 = one particle + trail, 1 = whole swarm; hidden particles keep simulating |
| `yPos` | YPos | CompoundParameter | 0 | −0.5–0.5 | attractor vertical position: ±50% of the sculpture height below/above |
| `yRotSpd` | YRotSpd | CompoundParameter | 0.75 | 0–1 | Y-rotation speed; 1 = one revolution / 30 s |
| `trails` | Trails | CompoundParameter | 0.5 | 0–1 | trail half-life, exp 150 ms – 1.2 s |
| `hue` | Hue | CompoundParameter | 0.58 | 0–1 | base hue (perceptual position; default classic Lorenz blue) |
| `audio` | Audio | CompoundParameter | 0 | 0–1 | audio reactivity depth; 0 = pure screensaver |
| `bassSpd` | BassSpd | CompoundParameter | 0 | 0–1 | bass transient → speed pulse, independent of Audio: a kick momentarily multiplies orbit tempo, up to 2× at full |
| `sync` | Sync | BooleanParameter | true | — | defer triggers and RndTrig jumps to the tempo grid; off = fire immediately |
| `tempoDiv` | TempoDiv | EnumParameter&lt;Tempo.Division&gt; | HALF | all divisions | grid that deferred triggers and RndTrig jumps land on |
| `rndTrig` | RndTrig | TriggerParameter | — | — | randomly fire a trigger or jump a parameter (formerly Meta) |

Renames 2026-07-05 (labels **and** paths, per curation): Energy→Desat,
Rotate→YRotSpd, Meta→RndTrig. Saved Lorre instances from earlier project files
reset those knobs (and Speed, whose scale changed) to defaults on load.

UI is Chromatik's default auto-generated control panel (no custom
`UIDeviceControls`).

## Triggers

Three non-meta triggers spanning small → large. With Sync on, all three defer
to the next TempoDiv boundary (see Tempo mapping); repeat fires within one
window coalesce. All operate on the live (Count) window only.

- `tint` (small) — the base hue steps by 0.382 (golden-ratio conjugate) around
  the perceptual wheel, wrapping in [0, 1). Instant recolor of heads; trails
  cross-fade to the new hue over one trail half-life. Repeated tints visit the
  whole wheel without early repeats. `CURATE:` step size should read as a clear
  but non-jarring recolor.
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
| `yRotSpd` | [0.15, 1.0] | candidate | floor avoids a dead-stop rotation jump |
| `trails` | full [0, 1] | candidate | |
| `speed` | [1.5, 3.5] | candidate | re-ranged 2026-07-05 from [0.6, 1.4] into the rebaselined units — same musical window, keeps dt stable and avoids a frozen jump |
| `hue` | full [0, 1] | candidate | |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

Count/Vis/YPos are deliberately **not** jumpable: a random jump there reads as
a glitch (mass die-off, blackout, attractor teleport), not a musical hit.

## Simulation-principles compliance

**Lobe orbit tempo** (`CURATE:` the rebaselined `SIM_RATE_AT_SPEED_1 = 0.045`):
one orbit around a lobe takes ≈ 0.73 Lorenz time units at ρ=28 (measured: mean
z-maximum period 0.727 over 2,752 loops). `simRate = 0.045 × (Speed +
bassSpd·pulse·max(Speed, 1))` with `pulse = bassPulseRaw` (0..1, trough-
referenced bass rise); wall time = 0.73 / simRate:

- Default (Speed 2.5, silence or depth 0): simRate = 0.1125 → orbit ≈ **6.5 s**
  — numerically the old default (0.113), inside the 4–8 s target. Speed 1
  (100%) = old knob minimum → 16.2 s. Speed 0 = frozen moment (integration
  skipped entirely; rotation, trails, triggers and mini-kicks still run).
- Speed knob max (8 = 2× old fastest): simRate = 0.36 → orbit 2.0 s, heads
  ≈ 58 px/s at ρ=28 — *below* the previously analyzed extreme (0.466 / 75
  px/s), so the prior analysis holds: this is local orbiting confined within
  one ≈48-column view, texture rather than sculpture traversal.
- Absolute max (Speed 8 + BassSpd 1 + saturated pulse): simRate =
  0.045 × 16 = 0.72 → orbit ≈ 1.0 s, heads ≈ 115 px/s — above the previously
  analyzed sustained extreme (75 px/s), but only for the duration of a kick
  transient (the pulse decays with the raw meter, ≲100 ms), still confined
  within one ≈48-column view, and the baseline (between kicks) is the plain
  Speed rate. `CURATE:` confirm the bass surge reads as pulsing-forward, not
  strobing, at high Speed.

**Rotation** (the only true full-sculpture sustained motion): max
`ROTATION_MAX_REV_PER_SEC = 1/30` → one full revolution = full horizontal
traversal in **30 s ≥ 5 s** even at YRotSpd=1; default 0.75 → 40 s.

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
12-substep/frame cap and an escape-guard respawn. Verified off-line: zero
escapes over 300 particles × 900 sim units at both ρ=28 and ρ=40. The absolute
max simRate (0.72) stays well inside the substep cap (0.004 × 12 = 0.048 sim
units/frame supports simRate ≤ ~2.9 at 60 FPS before time dilation).

**Contrast / brightness**: bold point-swarm forms, no fine texture. Brightness
by particle speed with a 0.3 floor (slow lobe-center particles stay visible at
LED distance); fast heads desaturate toward white-hot (Desat knob), slow
particles stay saturated; small perceptual hue spread (0.12) across the
attractor's height for depth cueing. Trails decay equal-channel (hue-preserving)
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
