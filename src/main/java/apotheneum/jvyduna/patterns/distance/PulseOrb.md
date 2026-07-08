# PulseOrb

Breathing warm sanctuary orb (interior-first) coupled with a slower morphing domain-warp wall topology — the *Our Distance* exhale, source field for BlitFeedback.

> Sidecar design doc convention: this file lives beside `PulseOrb.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

Not a screensaver recreation — a **bespoke hero pattern** for song 6 of 6,
Helios' "Our Distance" (`distance`), the arc closer. It marries Jeff's two
setlist seeds instead of choosing between them (per `distance-brief.md` §5):

1. **The pulsing orb** (seed 1, the "Bethlehem shader" breathing star) → the
   **home / heart**: a warm volumetric orb read *interior-first*, breathing with
   the ~1.4 s pad swell.
2. **The ketamine K-hole morphing worlds** (seed 2, reds/purples/greens folding
   inside-out like *Inception* / *Interstellar*) → the **world outside the
   window**: a slow domain-warp topology drifting across the four outer walls,
   Kenniff's own "a window's light or a tree's shadow moving across the wall."

The two systems are **coupled at deliberately different speeds** (orb ~5–8 s,
walls ~12–24 s). That single decision is the anti-monotony insurance the brief
and the adversarial critique both flag as **non-optional** for a 5+ minute,
harmonically-static ambient finale: the eye always has slow evolution to follow
without the frame ever getting busy. Palette pulls toward candlelight
amber/rose/deep-teal (resisting the neon read of the raw K-hole seed); the
plagal **F→Fm** ache is expressed by cooling the whole field via the Warmth
knob.

This pattern is the intended **source field for `effects/BlitFeedback`** (reuse
#2 in the brief's plan): layer BlitFeedback over PulseOrb with a slow warm
feedback zoom/rotate to get the Inception/Interstellar "topology folding into
itself" melt on top of the wall field.

## Rendering approach

- **Base class**: `ApotheneumPattern`. The piece needs the cube exterior AND
  interior rendered *independently* (interior-first is the whole thesis) plus
  the cylinder — not cube-face-only, so not `ApotheneumRasterPattern`.
- **Surfaces covered**:
  - **Cube exterior** ("the world"): wall field dominant, orb a faint bleed.
  - **Cube interior** ("the home"): orb dominant, wall field recessive. Rendered
    into its **own** `SurfaceCanvas` (`ringInt`) in the same per-pixel pass as
    the exterior (`ringExt`) — the field/orb math is computed once and weighted
    differently into each, so interior-first costs almost nothing.
  - **Cylinder**: the wall field plus a faint horizontal orb *belt* (the
    cylinder has no cube interior to anchor a point-orb). Interior is a straight
    copy of the exterior via `copyCylinderExterior()`.
- **Geometry mapping**: two 200×45 `SurfaceCanvas` for the cube ring
  (`Cube.Ring.LENGTH` × `GRID_HEIGHT`, all four faces as one wrap strip), one
  120×43 for the cylinder (`RING_LENGTH` × `CYLINDER_HEIGHT`). Y = 0 is the top
  row. The orb glow uses a **per-face radial metric**: each of the 4 cube faces
  (50 cols) has its own center (columns 25/75/125/175, row 22), so the orb reads
  as one central light seen on every wall and can **contract toward a point** at
  each face center for the fade.
- **Wall field wrap**: the domain-warp uses INTEGER x-harmonics of the ring
  angle and warp offsets that depend only on the orthogonal coordinate, so the
  field wraps seamlessly around the 200/120-column strip with no seam.
- **Wall field timebase (bidirectional)**: the drift phase fed to `wallField` is
  `WALL_PHASE_AMP · sin(wallOsc)` — a **bounded, continuous** value. The old
  monotonic phase wrapped at 2π and the field's fractional-phase warp terms
  (`phase·0.4/0.3/0.5`) jumped at each wrap, causing a visible discontinuity
  (worst at high drift). The sin() timebase never jumps; the field now drifts
  **bidirectionally** (forward then reverses) — the accepted tradeoff for a
  guaranteed seamless timebase.
- **Field density**: `Density` scales only the vertical / inner warp frequencies
  (`densMul`, up to `DENSITY_MAX_MUL`), leaving the integer angle harmonics
  untouched so the seam still holds. At high density the field is pushed past the
  display's resolvable resolution → intentional sparkle/moiré. Density sits
  BEFORE the Smooth posterize (a brightness quantizer, not a spatial filter), so
  the aliasing survives even at Smooth = 1.
- **Buffers**: `ringExt`, `ringInt`, `cyl` `SurfaceCanvas` all preallocated in
  the constructor; the palette role colors + swatch cache arrays preallocated;
  all envelope/phase state is primitive. Zero allocation in `render()`.
- **Door columns**: handled for free by `SurfaceCanvas.copyTo`, which guards
  each column by `column.points.length` (shortened door columns just receive
  fewer rows). No special-casing.

## Audio mapping

`AudioReactive`, ticked as the first line of `render()`, gated by the **Audio
depth knob** (`audio.setDepth(audioDepth)`, default 0):

- **bass** (smoothed, depth-scaled) — adds up to `ORB_AUDIO_R` (8 px, CURATE) of
  orb radius and up to 0.4 of orb brightness: the heart swells with the low end
  as the bassline locks in (1:49) and climbs.
- **level** (depth-scaled) — a gentle global brightness lift on both the orb and
  the wall field (`levelLift = 1 + 0.3·level`): the whole sanctuary glows a
  little brighter with the mix, never clipping (channels clamp).

Hits (`bassHit`/`trebleHit`) are intentionally **not** consumed here — the
decisive percussive gesture (4:04 snare) is the operator-fired / clip-automated
**Ripple** trigger, which reads as one deliberate bloom rather than per-onset
flicker (this is the calm finale, not a rimshot piece).

**Depth knob / silence behavior**: `Audio` defaults to 0 = pure screensaver. At
depth 0 (or true silence) the orb breathes purely on its LFO, the wall field
drifts on its own clock, and brightness sits at its base — the full breathing
sanctuary is intact with no audio. Raising the knob continuously restores the
bass-swell and level-glow above. The knob at 1 adds: an audible heartbeat in the
orb radius/brightness on the low end, plus a subtle whole-field glow riding the
mix envelope. Default gentle per the brief ("`Audio` depth knob default gentle").

## Tempo mapping

No Sync/TempoDiv **grid gate** (that convention was retired 2026-07-08). Distance
is grid-less, so both rolling periods (orb breathe, field drift) are **beat-
relative and continuous**: their beat counts are multiplied by the live
`lx.engine.tempo.period`. Motion follows the arrange tempo lane without ever
snapping to a division.

**≥5 s cap dropped (2026-07-08 feedback pass).** The old `MIN_PERIOD_MS = 5 s`
clamp is removed for both speeds: Jeff asked for a functional, wide range whose
fast end is one period **per beat** (500 ms @ 120 BPM). This intentionally
overrides the earlier "all sustained motion ≥ 5 s" compliance note below — the
knobs now reach event-rate motion at the top of their travel.

## Speed mapping

The single Speed knob was split into **two independent controls** — orb pulse
(`speed`) and field drift (`drift`, repurposed from its old 0.5–2× multiplier).
Both are speed-only (they scale rate, nothing else).

| Quantity | Slow (knob=0) @ 120 BPM | Fast (knob=1) @ 120 BPM | Curve |
|---|---|---|---|
| Orb breathe period (`speed`) | 16 beats = 4 bars ≈ 8 s | 1 beat ≈ 0.5 s | lin (beats) |
| Field-drift cycle (`drift`) | 32 beats = 8 bars ≈ 16 s | 4 beats = 1 bar ≈ 2 s | lin (beats) |

Defaults: Speed 0.35, Drift 0.3 — both deep in the ambient regime, field slower
than the orb (the anti-monotony decorrelation). The orb speed also scales the
Pulse/Ripple/Fold event durations (`trigScale = lin(speed, 2.0, 0.25)`): a faster
orb gives snappier triggers.

## Parameters

Registration order (triggers → Speed → pattern params → Smooth → Audio):

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `pulse` | Pulse | TriggerParameter | — | — | manual breath swell (attack 2× the release; scales with orb speed) |
| `ripple` | Ripple | TriggerParameter | — | — | one outward ring from the orb across the walls — the 4:04 snare bloom |
| `fold` | Fold | TriggerParameter | — | — | fold the wall topology inside-out to a new configuration |
| `speed` | Speed | CompoundParameter | 0.35 | 0..1 | **orb** pulse speed: 0 = one breath / 4 bars, 1 = one / beat |
| `size` | Size | CompoundParameter | 0.4 | 0..1 | base orb radius (breathes around it); automate for 2:57 bloom / 5:07 contract |
| `warmth` | Warmth | CompoundParameter | 0.7 | 0..1 | palette warm/cool bias; low = the F→Fm cool ache |
| `field` | Field | CompoundParameter | 0.5 | 0..1 | wall-field presence: 0 = near-dark walls, 1 = full K-hole topology (renamed from `morph`) |
| `density` | Density | CompoundParameter | 0 | 0..1 | wall-field spatial density: 0 = current soft field, 1 = ~2× past resolvable → intentional sparkle/aliasing (moirés even at Smooth 1) |
| `warp` | Warp | CompoundParameter | 0.5 | 0..1 | domain-warp fold intensity |
| `drift` | Drift | CompoundParameter | 0.3 | 0..1 | **field** drift speed: 0 = one cycle / 8 bars, 1 = one / bar (repurposed from the old 0.5–2× multiplier) |
| `smooth` | Smooth | CompoundParameter | 1.0 | 0..1 | gradient banding, input remapped `pow(in, SMOOTH_EXP=16)` so the interesting 0–0.1 region spreads across the knob: 1 = smooth (default), 0 = banded |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure screensaver) |

**Note (`.lxp` breakage):** the `morph` param key is now `field`. Any clip lane or
modulation targeting `…/morph` (e.g. the arc automation) must retarget `…/field`.

The 2:57 bloom / 4:04 re-escalation / 5:07 contract-to-point arc is delivered by
**timeline automation** of `size`/`field` (and the `ripple` trigger at 4:04),
plus the audio envelope — the pattern is the instrument, the `.lxp` clip lanes
are the choreography. This matches the house convention (patterns expose the
knobs; the composition drives them).

## Triggers

Three triggers, small → large. All three durations **scale with the orb speed**
via `trigScale = lin(speed, 2.0, 0.25)` (slow orb → up to 2× longer; fast orb →
up to 4× shorter). Times below are at the default Speed 0.35.

- `pulse` (small) — the orb radius/brightness swells then settles as an
  **attack→release envelope whose onset is 2× the fadeout** (`PULSE_ATTACK_MS =
  3000`, `PULSE_RELEASE_MS = 1500`). No spatial travel; reads as a single slow
  inhale with a quicker settle. CURATE: verify the swell is visible but gentle.
- `ripple` (medium) — a Gaussian shell expands outward from each face-center
  orb across the walls and fades over its life (`RIPPLE_LIFE_MS`, `RIPPLE_SPEED`
  both trig-scaled so the shell crosses the same distance). The one decisive
  percussive gesture (4:04 snare peak).
  CURATE: `RIPPLE_SPEED_PX_PER_MS`, `RIPPLE_SIGMA`, and whether it reads as one
  clean ring on hardware.
- `fold` (large) — advances the fold target by one fold-unit; `foldPhase` eases
  toward it over ~2.5 s (`FOLD_LERP_PER_MS`) and the whole wall field
  reconfigures inside-out. Inside `wallField` that unit drives a true coordinate
  transform — a smooth 0..1..0 inversion of the vertical axis (wall center
  ↔ edges), a rigid seamless ring rotation (`FOLD_ROT`) so successive folds
  never land on the same topology, and a re-texturing of the domain warp — all
  blended on the eased `foldPhase`, so the render crossfades the pre- and
  post-fold coordinate mapping (a topological morph, not a bare phase add). The
  K-hole "world reconfigures inside-out" gesture.

## Simulation-principles compliance

> **≥5 s cap intentionally relaxed (2026-07-08 feedback).** Jeff asked for
> functional, wide speed ranges reaching one period per beat, so the top of the
> Speed/Drift travel is now event-rate by design. Keep the knobs in their lower
> regime (defaults Speed 0.35 / Drift 0.3) for the compliant sustained-motion
> look described below; the fast ends are a deliberate performance option.

- **Orb breathe (sustained motion)** — a full inhale+exhale is one sine period:
  ~8 s at the default Speed; the fast end reaches one breath per beat (~0.5 s @
  120 BPM). Radius travels slowly (a soft radial falloff, no hard edge sweeping).
- **Wall-field drift (sustained motion)** — one bidirectional cycle over ~16 s at
  the default Drift; the fast end reaches one cycle per bar (~2 s @ 120 BPM).
- **Ripple (event-like)** — expansion + life ≥ 1.5 s at default speed (scales
  with the orb); fired sparingly (once at 4:04).
- **Pulse (event-like)** — attack (2×) + release ≥ 1.5 s at default speed, no
  spatial travel.
- **Fold (event-like)** — eased topology morph ≥ 1.5 s at default speed.
- **Bold forms / contrast** — the orb is a broad radial volume, the wall field a
  low-frequency domain warp (no fine texture); both are soft-focus by design,
  which suits the "warm, soft-focus, interior, resolving" mood. CURATE: confirm
  the dim end (silence, low Size/Morph, farthest breathe floor) still reads on
  hardware without vanishing.

## CURATE notes (grep `CURATE:` in the source)

- `ORB_MAX_R = 30`, `ORB_MIN_R`, `BREATHE_FLOOR = 0.35` — orb size envelope.
- `ORB_AUDIO_R`, `ORB_PULSE_R` — audio/pulse radius contributions.
- Interior/exterior/cylinder weights (`ORB_*_WEIGHT`, `WALL_*_WEIGHT`) — the
  interior-first balance; the single biggest thing to tune on hardware.
- `ORB_BEATS_*`, `FIELD_BEATS_*` — the two independent coupled speeds.
- `DENSITY_MAX_MUL` — how far Density pushes past resolvable resolution (sparkle).
- `WALL_PHASE_AMP` — field morph amount per bidirectional half-cycle.
- `SMOOTH_EXP` — Smooth input remap (16 spreads the 0–0.1 region; flip to 1.0/16).
- `RIPPLE_*`, `PULSE_ATTACK_MS`/`PULSE_RELEASE_MS`, `FOLD_LERP_PER_MS`, `FOLD_ROT`
  — trigger envelopes (all trig-scaled by orb speed) / fold reconfiguration.
- Fallback palette constants `FB_*` — picked blind to the brief's amber/rose/teal.
- `wallField(...)` domain-warp frequencies/gains (`amp` range, the harmonic
  weights, the `0.24` output shoulder) — the field structure is built and seam-
  safe; the exact softness/contrast is a tune-against-the-cube value.

## TODO (hardware / next pass)

- **Interior-first weighting** — the exterior/interior weights are a plausible
  first split; tune the actual home-vs-world balance against the cube on site.
- **BlitFeedback pairing** — verify PulseOrb reads well as BlitFeedback's source
  (slow warm feedback zoom/rotate) and set feedback gain low so it drifts, not
  glitches.

(The domain-warp math and the topology-fold transition are now fully built —
see the Curation log — leaving only these two hardware-balance items.)

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass, Claude autonomous session | Design doc + compiling stub: orb + coupled domain-warp wall field + palette morph wired to params/AudioReactive/TempoLock; domain-warp math and topology-fold transition left as marked TODOs for hardware tuning |
| 2026-07-07 | Full build-out, Claude session | Replaced the two-octave-sine placeholder with a real inside-out-fold iterative domain warp (Quilez-style two-pass fbm-of-fbm, seam-safe via integer angle harmonics + vertical-only angle displacements, verified invariant under xn→xn+1); implemented the `Fold` trigger as a true inside-out coordinate reconfiguration (eased vertical center↔edge inversion + rigid seamless ring rotation `FOLD_ROT` + warp re-texture, crossfaded on the eased `foldPhase` in fold-units) |
| 2026-07-08 | Removed Sync/TempoDiv/Meta + TriggerBag/TempoLock (convention retired; free-run behavior = old Sync-off path) | Project-wide retirement of the Sync/TempoDiv + Meta pattern-control convention. The breathe and wall-drift periods now always free-run at their energy-derived values (the `quantizePeriod` snapping is gone). |
| 2026-07-08 | Convention pass (non-bliss) | `Energy`→`speed` (it only ever drove the breathe/drift *rate* — the render never scaled brightness by it, so it was already speed-only). Breathe + drift periods made **beat-relative**: Speed-mapped beat counts × live `lx.engine.tempo.period`, clamped ≥ 5 s (`MIN_PERIOD_MS`) — continuous, tempo-following, never a grid snap. Added house **Smooth** knob (default 1.0): the field is continuous, so Smooth controls gradient banding (1 = smooth gradients, 0 = posterized/steppy) — CURATE, Jeff may prefer a different mapping. |
| 2026-07-08 | Feedback pass (live viewing) | Six changes per Jeff: (1) new **Density** knob scales the field's vertical/inner warp frequencies past resolvable resolution for intentional sparkle/moiré, seam-safe (integer angle harmonics untouched) and before the Smooth posterize so it aliases even at Smooth 1. (2) **Discontinuity fixed**: the field drift phase is now a bounded `WALL_PHASE_AMP·sin(wallOsc)` (no 2π-wrap jump in the fractional-phase warp terms); the field drifts bidirectionally as the tradeoff. (3) **Speed split** into orb (`speed`, 4 bars→1 beat) and field (`drift`, repurposed, 8 bars→1 bar); **≥5 s cap dropped** (`MIN_PERIOD_MS` removed) for a functional wide range; orb speed also scales the trigger event durations (`trigScale`). (4) **Pulse** now an attack→release envelope with the onset 2× the fadeout. (5) `Morph`→**`Field`** (key + label). (6) **Smooth input remapped** `pow(in, SMOOTH_EXP=16)` so the interesting 0–0.1 region spreads across the knob (0.1→knob 0.87); flippable to 1.0/16. |
