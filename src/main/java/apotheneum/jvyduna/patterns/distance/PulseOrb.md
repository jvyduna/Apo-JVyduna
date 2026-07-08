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

Two **continuous rolling periods** (not scheduled events), so both use
`TempoLock.quantizePeriod` (the Satori idiom), each on its own `TempoLock`
instance so their harmonic latches are independent:

- **Orb breathe period** (`tempoOrb`) — snapped to `TempoDiv` (default `WHOLE`
  = one bar ≈ the pad swell) when Sync is on. Loose: the default stretch clamp
  `[0.7, 1.4]`, so the breathe only nudges toward the bar grid, matching the
  brief's "TempoLock in a *loose* mode."
- **Wall-drift period** (`tempoWall`) — snapped to the same `TempoDiv` but from a
  much longer base period, so the two systems stay decorrelated even when both
  are grid-locked.

Turning **Sync off** removes both `quantizePeriod` calls; the periods free-run at
their energy-derived values (`Ranges.lin`), which are already good-looking (the
`WHOLE`-bar default just aligns the breath to the ambient's ~1.4 s bar swell).
No state needs restoring — the scale factor is multiplicative and transient.

No `crossed()` gate is used (there are no discrete grid-fired events; the
triggers are operator/clip-driven).

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| Orb breathe period (full inhale+exhale) | 8 s | 5 s | lin |
| Wall-drift period | 24 s | 12 s (÷ Drift) | lin |
| Orb brightness / radius | via Size × breathe | (same, audio adds) | lin |

Default energy 0.35 sits deep in the ambient regime. Even at energy = 1 the
breathe cycle is 5 s and the wall drift is 12 s (before Drift) — both at/under
the ≥5 s sustained cap (see compliance). Energy scales *rate and intensity*,
never past the traversal cap.

## Parameters

Registration order (triggers → Energy → pattern params → Audio → Sync → TempoDiv
→ Meta):

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `pulse` | Pulse | TriggerParameter | — | — | manual breath swell (orb radius/brightness surge, settles ~2 s) |
| `ripple` | Ripple | TriggerParameter | — | — | one outward ring from the orb across the walls (~3 s) — the 4:04 snare bloom |
| `fold` | Fold | TriggerParameter | — | — | fold the wall topology inside-out to a new configuration (~2.5 s morph) |
| `energy` | Energy | CompoundParameter | 0.35 | 0..1 | master energy (breathe/drift rate + intensity) |
| `size` | Size | CompoundParameter | 0.4 | 0..1 | base orb radius (breathes around it); automate for 2:57 bloom / 5:07 contract |
| `warmth` | Warmth | CompoundParameter | 0.7 | 0..1 | palette warm/cool bias; low = the F→Fm cool ache |
| `morph` | Morph | CompoundParameter | 0.5 | 0..1 | wall-field presence: 0 = near-dark walls, 1 = full K-hole topology |
| `warp` | Warp | CompoundParameter | 0.5 | 0..1 | domain-warp fold intensity |
| `drift` | Drift | CompoundParameter | 1 | 0.5..2 | wall-drift speed multiplier (kept slower than the orb) |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure screensaver) |
| `sync` | Sync | BooleanParameter | true | — | loosely lock breathe/drift periods to the tempo grid |
| `tempoDiv` | TempoDiv | EnumParameter | WHOLE | Tempo.Division | division the periods snap to under Sync |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |

The 2:57 bloom / 4:04 re-escalation / 5:07 contract-to-point arc is delivered by
**timeline automation** of `size`/`morph` (and the `ripple` trigger at 4:04),
plus the audio envelope — the pattern is the instrument, the `.lxp` clip lanes
are the choreography. This matches the house convention (patterns expose the
knobs; the composition drives them).

## Triggers

Three non-meta triggers, small → large:

- `pulse` (small) — the orb radius/brightness swells from the current breathe
  state and settles back over ~2 s (`PULSE_DECAY_MS`). No spatial travel; reads
  as a single deeper breath. CURATE: verify the swell is visible but gentle.
- `ripple` (medium) — a Gaussian shell expands outward from each face-center
  orb across the walls over ~2.5 s and fades over its 3 s life
  (`RIPPLE_LIFE_MS`). The one decisive percussive gesture (4:04 snare peak).
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

Meta fires one of the above or jumps a parameter.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. All three
triggers are also registered in the bag.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `size` | [0.2, 0.9] | candidate | extremes excluded: 0 vanishes the orb, 1 blooms past the face |
| `warmth` | [0.3, 1.0] | candidate | full-cool floor avoided so the field never goes fully teal |
| `morph` | [0.15, 1.0] | candidate | floor keeps a hint of wall texture; 0 reserved for scripted breakdown |
| `warp` | [0.0, 1.0] | candidate | any warp is safe |
| `drift` | [0.6, 1.6] | candidate | extremes reserved for manual/scripted use |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

- **Orb breathe (sustained motion)** — a full inhale+exhale is one sine period:
  8 s ambient, **5 s at energy = 1** → exactly at the ≥5 s cap. Radius travels
  slowly (a soft radial falloff, no hard edge sweeping across the surface).
- **Wall-field drift (sustained motion)** — the domain-warp phase advances one
  full turn over 24 s ambient / **12 s at energy = 1** (before Drift). Drift ≤ 2
  can halve that to 6 s at peak energy — still ≥ 5 s. CURATE: confirm Drift = 2 +
  energy = 1 (6 s field turnover) still reads as drift, not motion; clamp Drift's
  jump upper bound (1.6) already keeps the Meta pool inside the cap at peak
  energy (24/1.6 = 15 s ambient; at energy 1, 12/1.6 = 7.5 s).
- **Ripple (event-like)** — ~2.5 s expansion, 3 s total life ≥ 1.5 s minimum;
  fired sparingly (once at 4:04).
- **Pulse (event-like)** — 2 s brightness/radius settle ≥ 1.5 s minimum, no
  spatial travel.
- **Fold (event-like)** — ~2.5 s eased topology morph ≥ 1.5 s minimum.
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
- `BREATHE_MS_*`, `WALL_MS_*` — the two coupled speeds.
- `RIPPLE_*`, `PULSE_DECAY_MS`, `FOLD_LERP_PER_MS`, `FOLD_ROT` — trigger envelopes
  / fold reconfiguration amount.
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
