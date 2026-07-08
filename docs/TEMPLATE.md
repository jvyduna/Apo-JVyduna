# <PatternName>

<One-sentence description matching the @LXComponent.Description annotation.>

> Sidecar design doc convention: this file lives beside `<PatternName>.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

What screensaver this recreates, which version/platform, and what visual
signature we're preserving. If the design is an interpretation (original
unconfirmed), say so explicitly.

## Rendering approach

- Base class (`ApotheneumPattern` / `ApotheneumRasterPattern`) and why
- Surfaces covered: cube exterior/interior, cylinder — and how interior is
  handled (copied / intentionally dark)
- Geometry mapping (per-point, SurfaceCanvas 200x45 ring, 120x43 cylinder, ...)
- Buffers and their preallocation strategy (zero-alloc render rule)
- Door-column handling

## Audio mapping

Which `AudioReactive` taps drive what (bass/mid/treble/level, ratios,
bassHit/trebleHit), and the silence behavior (what the pattern does with all
taps at 0 — it must still look good).

Audio depth knob convention: every pattern exposes
`CompoundParameter("Audio", 0)` (key `audio`), attached via
`audio.setDepth(audioDepth)`. Default 0 = pure screensaver — all taps read
exactly their silence values and hits never fire, so the silence behavior
above is also the default behavior. Raising the knob continuously restores
the reactivity described here (magnitude taps scale linearly with depth;
hits fire normally once depth > 0.01). Document what the knob at 1 adds
visually over the knob at 0.

## Tempo

No tempo **gating**: patterns free-run. The old Sync/TempoDiv (`TempoLock`) and
Meta (`TriggerBag`) conventions were RETIRED 2026-07-08 — do not add them to
new patterns. Choreography (event timing, section changes) comes from the
arrange timeline, not from tempo-grid analysis or random meta jumps.

**Beat-relative speed for the grid-less songs (adopted 2026-07-08).** For the
grid-less songs — **Distance, Chrome, Temper** — prefer expressing motion
*speed* as a **continuous, beat-relative rate ("tempoDiv units")** rather than
absolute seconds: pick endpoints in units-per-beat (rows/beat, beats/cycle,
period-in-beats, …) and multiply by the live beat period
`lx.engine.tempo.period.getValue()` (ms/beat — the read Satori uses), so motion
follows the (often rubato) arrange tempo lane. This is **not** the retired
Sync/TempoDiv gate: the rate stays continuous, never snaps to a division, and
never jumps. Always clamp the resulting rate/period so the >=5 s full-traversal
cap holds at any tempo. Reference implementations: `Aumakua` (rows/beat rise),
`PulseOrb` (breathe/drift period in beats). **Chrome is a per-song outlier** —
parts are legitimately tempo-grid-driven (e.g. Cathedral's `TempoDiv` TearDown),
parts want continuous speeds; decide each Chrome speed param with Jeff and record
it in a "Speed units — DECISION NEEDED" section of that pattern's `.md`.

## Energy / Speed mapping

Name the motion-rate knob **`speed`** (label "Speed") when it *only* scales
motion; keep **`energy`** (label "Energy") only when the one knob also drives
non-motion quantities (brightness, density, a whole behavioral regime). Rename
key + label + field together (never rename a key a saved `.lxp` references).

| Quantity | Ambient (0) | Peak (1) | Curve (lin/exp) |
|---|---|---|---|
| ... | ... | ... | ... |

Sustained motion must respect the >=5 s full-traversal cap even at 1.

## Parameters

UI/registration order convention (do not deviate; existing keys must never be
renamed once a saved .lxp references them):

1. Triggers (>= 3; see Triggers section)
2. `speed` — Speed (or `energy` — Energy if it also drives non-motion; see above)
3. Pattern-specific parameters
4. `smooth` — Smooth (motion-blending + antialiasing knob; see Smooth section)
5. `audio` — Audio depth knob (`CompoundParameter("Audio", 0)`)

Naming: UI labels are max 7 characters (Chromatik knob width); the code field
name can and should be longer and descriptive (e.g. field `genSpacing`, key
`genY`, label `GenY`; field `interiorLevel`, key `interior`, label `Inner`).

## Smooth (motion-blending + antialiasing knob)

Every pattern exposes a `CompoundParameter("Smooth", 1.0)` (key `smooth`,
default 1.0) that controls **motion blending between moving pixels** (sub-pixel /
fractional-row crossfades vs. pixel-snapped stepping) and **antialiasing of drawn
forms** (edge coverage `clamp01(dist+0.5)` softened by `smooth`): 0 =
steppy/pixel-snapped/hard edges, 1 = smooth. Reuse the shared primitives —
`SurfaceCanvas.lineWu`/`plotWu` (Wu AA), `LXColor.lerp`, coverage-scaled color,
`SurfaceCanvas.line`/`lineMax` (hard) crossfaded against `lineWu` by `smooth`.
The name is standardized on **Smooth**; **"Blend" now means compositing mode
only** (`SurfaceCanvas.Blend` MAX/XOR/…). Register `smooth` after the
pattern-specific params, before `audio`. A pattern with genuinely nothing to
smooth (a static bench like `Lattice`) may omit it, but must document the
exemption in its `.md`.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| ... | ... | ... | ... | ... | ... |

## Triggers

Requirement: at least 3 triggers, spanning small -> large state
permutations (e.g. a subtle hue rotation, a mid-size behavior flip, and a
full scatter/reset). Trigger handlers run at event rate and may allocate
minimally.

- `name` — what suddenly changes, and how long the change takes to read on the
  sculpture

## Simulation-principles compliance

Show the math: fastest sustained motion at default Speed/Energy AND at 1, in
seconds per full sculpture traversal (>=5s required; event-like motion such as
a lightning stroke may be faster but must have >=1.5s total visual life). For
beat-relative speeds, show the seconds at the song's nominal BPM and confirm the
rate clamp holds the >=5s floor at fast tempo.
Contrast/brightness choices (bold forms, no fine texture, posterization etc.).

## Curation log

| Date | Change | Why |
|---|---|---|
| ... | ... | ... |
