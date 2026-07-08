# Shared source context — "Communicating" (Jeff Vyduna, Apotheneum, ~21 min)

> Machine-generated shared context for the composition workflow agents. Captures
> the Google Sheet (Setlist / Palettes / Project Chrome Country), the arc theme,
> and pointers to MIR data. Read this + the relevant `<slug>.mir.json` before
> writing any deliverable.

## The piece

- **Working titles:** KERIOT · Konsciiĝo ("becoming conscious / realization",
  constructed Esperanto) · Cymbals.
- **Theme — "Communicating":** a spiritual journey across a lifespan — *being
  birthed in, traveling away from, accepting, then reconstructing a personal
  religion.* Incorporates many religious symbols.
- **Structure:** each song is a separate `.lxp` project using Chromatik's new
  **Compositions** (arrange timeline) feature to choreograph channels/patterns
  to the music. Jeff is also dogfooding the beta `arrange` branch of LX/GLX.

## Setlist (order = narrative arc)

| # | Slug | Artist | Title | Dur (MIR) | Key | BPM/grid | Palette dir | Feel |
|---|------|--------|-------|-----------|-----|----------|-------------|------|
| 1 | temper | Kalia Vandever | Temper The Wound | 3:45 | G major | rubato (tracked) | Pastels | Hopeful, childbirth, mournful, healing through a difficult period |
| 2 | bliss | Ducky | Bliss | 4:06 | G# major | 160 rigid | Mint/magenta/yellow + CMY + Rubik colors | So tech; 80s screensaver topograph (ALREADY BUILT) |
| 3 | rafters | Lusine | Rafters | 2:59 | G minor | 70 rigid | — | Seems young, turns brooding, ends lush |
| 4 | opus | — | Opus No 1 | 1:29 | D# major | 85 rigid | — | Troll: classic, people know it but it dawns on them what it is |
| 5 | chrome | Oneohtrix Point Never | Chrome Country | 5:05 | D# major | beatless (none) | Wild | Surprising dissonance & the healing of resolutions; "Beauty in the Occult" |
| 6 | distance | Helios | Our Distance | 5:24 | C major | ambient (tracked) | — | Pulsing, textured, morphing worlds |

Total selected ≈ 21:13. MIR structure per song lives in
`~/Code/jvyduna/Claude-MIR-pipeline/out/<slug>/<slug>.mir.json`
(sections, key, chords, grid, per-stem envelopes, events) with a companion
`<title> - MIR-verify.lxp`.

## Per-song idea columns (verbatim from the Setlist sheet)

**temper — Temper The Wound (Kalia Vandever):** Pastels. Hopeful, childbirth,
mournful, healing through a difficult period.
- Idea 1: Fading text on the *outside* surface but not inside: HEAL, BIRTH, HOPE.
- Idea 2: Generator for a *cave painting* of human forms; these figures slowly
  scroll upward, limbs drip down into new human forms forming family trees.

**bliss — Bliss (Ducky, 160 BPM) [REFERENCE / already built]:** Mint, magenta,
yellow with CMY moments and the Rubik speed-cube color scheme. So tech; 80s
screensaver topograph.
- Rimshots create negative-space rips (FFT detector / trigger track) → the `Rip` effect.
- Classic Windows/After Dark screensavers reimagined: Mountains, Mystify,
  Pipes3D, Satori. Second rimshot series → decaying splotches (paint thrown at a
  wall, dripping down while fading).
- Juvenile: Rubik's cube solving on-beat.
- Moving through grid fields like the late-80s SR-71 Blackbird t-shirts → Outrun.

**rafters — Rafters (Lusine, 70 BPM):** Seems young, turns brooding, ends lush.
- Idea 1: Blinkfade, life, cellular automata (CA), fast-paced images like the
  Kendrick [Lamar video aesthetic].
- Idea 2: Window rain; pseudo-religious symbols appear and morph.
- Idea 3: Stop-motion of mirror shadows; (1)e&a [beat placement].
- Idea 4: Moiré on 1/16 notes.

**opus — Opus No 1 (85 BPM):** Classic, people know it but it dawns on
them what it is.
- Idea 1: Start black for long enough that they wonder WTF.
- Idea 2: Some boring clock reiterating the hold / the patience.
- Idea 3: Boring clock becomes some nerdy esoteric clock like binary or whatever.

**chrome — Chrome Country (Oneohtrix Point Never):** Wild.
- Cathedral arches grow; flying buttress. See full marker sheet below.

**distance — Our Distance (Helios):**
- Idea 1: Pulsing orb and my Bethlehem shader (from Pixelblaze).
- Idea 2: Ketamine K-hole moments — textured worlds of reds, purples, and greens
  morphing their topology like the worlds in Inception and Interstellar.

## Palettes (named hex triads, from the Palettes sheet)

Color 1 / Color 2 / Color 3:

- **Try Radish:** `#00FF99` / `#66CCFF` / `#FFE699`  (mint · sky · cream)
- **CMG — Clover Magenta Goldenrod:** `#33FF99` / `#FF66CC` / `#FFCC33`
- **Gareth:** `#33CCFF` / `#CC99FF` / `#FFB38A`
- **Limeaid:** `#CCFF33` / `#FF9966` / `#6699FF`
- **Not RGB:** `#FF6633` / `#66FF66` / `#33B5FF`

Loose notes on the sheet: "Twinkle starfield bliss"; "2 of 4 walls image".

## Project Chrome Country — per-marker choreography sheet (verbatim ideas)

Personal meaning: *Accepting my father's strict religion for its merits.*
Message: *Beauty in the Occult.* Feel: *Surprising dissonance and the healing of
the resolutions.* (Times converted from the sheet's fraction-of-day to mm:ss;
MIR chrome duration = 305 s.)

| ~Time | Marker | Idea |
|-------|--------|------|
| 0:30 | Big surprise | Buffer accumulator long fade |
| 0:39 | "Fucking pan flute? OK" | — |
| 1:18 | Children's choir | Build a green horizon out of each attack; rotate each square while shaking the canvas |
| 1:36 | Wtf Children's choir | Hue-scroll leaves; then turn the world upside down; rabbit vs old woman; bump it each way; inception wrap; tree is upside down; brown (!) soils falling apart in clumps |
| 1:43 | Big chord | Heal |
| 1:48 | First dissonance peak; false string beauty; big timpani revisit | Hue-shift glitch? |
| 2:05 | Holly sings, second dissonance peak | — |
| 2:31 | Super-defined synth lead arpeggio | Cut in but pretty |
| 2:57 | Distance on children | Frame-buffer overflow glitch |
| 3:13 | Build dissonance to stutter repeater | — |
| 3:23 | True chill after timpani hit | Waves wash away the pixels, melt down |
| 3:50 | Center empty | Red-shifted parallax stars in a black field appear; field coin-wobbles |
| 4:05 | First hit of series | Lightning strike to earth. White helical ascension. Pulses ride it. CMO unstrands, separates more with the hold of the note. DNA twists tighter, mix in xor version of blend to moiré the shit out of it. Outer stars merged into counter-rotating path just fast enough vs the main helix twist |
| 4:37 | Angels and fade | — |
| — | (loose) | Deep red? Intensity — strobe inner quadrants away from darkness so you see the humans in the space. Hue shifts on dissonance. Wolfram cathedrals as landscapes — tear them down by rotating slices 90° as if a codex. |

## Reusable inventory (build cohesion by varying these)

- **Patterns** (`patterns/bliss/`): Mountains, Mystify, Outrun, Pipes3D, Lorre
  (Lorenz swarm), Rubik, Satori, Terraform, UnknownPleasures, Zot (lightning),
  Text (5px bitmap font), MirPitchGrid.
- **Effects:** `effects/bliss/Rip` (FFT/trigger negative-space rips),
  `effects/BlitFeedback` (frame-buffer feedback — glitch/K-hole/melt).
- **Utils** (`util/`): AudioReactive, SurfaceCanvas, PixelFont5, PerceptualHue,
  Ranges. (TempoLock and TriggerBag are legacy — the Sync/TempoDiv/Meta
  convention was retired 2026-07-08; do not use them in new patterns.)
- **Conventions:** `docs/TEMPLATE.md` (design-doc template) and
  `patterns/bliss/Mountains.md` (exemplar). Palette-driven color; `Audio` depth
  knob (default 0 = screensaver); ≥3 triggers; zero-alloc render; ≥5 s
  full-traversal sustained-motion cap; `CURATE:` inline notes on unverifiable
  values; curation log. No tempo-grid gating and no Meta/random-jump triggers —
  timing free-runs and choreography comes from the arrange timeline. **Every
  pattern exposes a `Smooth` knob** (motion blending + antialiasing; "Blend" now
  = compositing mode only). **`energy`→`speed`** when the knob only scales
  motion. **Grid-less songs (Distance/Chrome/Temper) express speed as continuous
  beat-relative "tempoDiv units"** (units-per-beat × `lx.engine.tempo.period`,
  clamped ≥5 s; never a grid snap); Chrome is a per-param outlier. The
  `te-patterns` skill is the authoring reference.
