# HoldClock

Deadpan corporate hold-screen clock ticking on the beat, morphing decimal →
binary → hex → mystical dial, with a crystal-staircase shimmer and a held "GOD
PUT ME ON HOLD" button.

> Sidecar design doc convention: this file lives beside `HoldClock.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

Not a screensaver recreation — the hero pattern for song 4, "Opus No 1"
(the Cisco default hold music, slug `opus`), the comic-relief / troll beat of "Communicating."
See `docs/composition/opus-brief.md`. The design realizes Jeff's three setlist
ideas literally: (1) a **cold open** in near-black long enough that the room
wonders *WTF is happening* and the familiar tune does the work; (2) a **boring
institutional clock** that just reiterates the hold — visualized patience,
ticking on the beat; (3) the boring clock **turns esoteric** — a nerdy
computer-nerd counter (binary, hex, then something weirder), leaning into the
"16-year-old computer nerd" origin of the tune.

The visual language synthesizes the three research lenses from the brief into
one: liminal corporate-futurism / vaporwave ("a training video for a software
company that never launched") + the on-hold clock + the "God put me on hold"
divine punchline — a hold screen for the afterlife's customer-service line.

Two committed beats come straight from the adversarial critique
(`docs/composition/00-overview-critique.md`, gaps #4 and #9):

- **Gap #4 (the sag's rescue):** the 0:40–0:50 shimmer must be a genuine,
  brief, *fluid* rise — "a prince dancing up a crystal staircase, head flung
  back in bliss" — allowed to be the brightest thing in the whole song for ~10 s,
  then snap back to deadpan. Wired to the `shimmer` knob, not a light-organ.
- **Gap #9 (the punchline):** "GOD PUT ME ON HOLD" — the doorway into `chrome` —
  must be a single HELD hard-cut frame (not a scrolling marquee), staged by
  timing, held a beat longer than comfortable, then black. Wired to `freeze`.

This is **fully built**: the phase state machine, every parameter, the
tick/beat engine, the digit rasterizer, the bezel/tick/AA-hand analog face, the
split-flap flip face, the binary dot-grid, the base-N occult dial, the
multi-strand crystal-staircase shimmer cascade, the word-wrapped held message,
the never-resolving "estimated wait" line and the VHS button-glitch melt are all
implemented and compiling. Only the blind-picked CURATE constants (timings,
brightness, mystic base, fallback phosphor) await a hardware tuning pass.

## Rendering approach

- **Base class**: `ApotheneumPattern` — draws the hold screen on the cube
  faces AND the cylinder ring (both, via `render()`), so it needs full
  cube+cylinder access, not `ApotheneumRasterPattern`'s cube-face-only reach.
- **Surfaces**: the same centered clock is drawn onto a 50×45 face canvas and a
  120×43 cylinder canvas. The face canvas is copied to `cube.exterior.front`,
  then `copyCubeFace(front)` replicates it to all 8 cube faces (exterior +
  interior). The cylinder canvas is copied to `cylinder.exterior`, then
  `copyCylinderExterior()` mirrors it inside. So the whole building reads as
  one "waiting room on hold." (CURATE: consider whether only the front face
  should carry the clock with `left/right/back` dark for more negative space —
  the brief's "front, with left/right/back optionally mirroring" — swap the
  `copyCubeFace` for a single-face paint if curation prefers.)
- **Geometry mapping**: two preallocated `SurfaceCanvas` buffers drawn in
  canvas space (Y=0 top) and copied column-major via `copyTo(surface, colors,
  mult, false)`, where `mult` is the phase/brightness multiplier so drawing
  stays full-color and dimming happens once at copy time.
- **Buffers / zero-alloc**: both canvases, plus a reusable `char[32]` glyph
  assembly buffer, are allocated in the constructor. Digit strings are written
  into the char buffer by hand (`writeTime`/`writeBinary`/`writeHex`) with no
  `String` allocation, and glyphs are blitted straight from `PixelFont5`. The
  only per-frame allocation is none; trigger callbacks (event rate) may allocate
  minimally (enum `setValue`).
- **Door columns**: `SurfaceCanvas.copyTo` already guards shortened columns via
  `column.points.length`; the centered clock sits mid-face away from the door
  cut, so no special handling.

## Audio mapping

`AudioReactive`, ticked as the FIRST line of `render()`, gated by the **Audio
depth knob** (`audio.setDepth(audioDepth)`):

- **bassPulseRaw × depth** — a subtle horizontal **jitter** (up to `JITTER_PX`
  = 1 px, CURATE) of the whole clock on each kick. That's the only audio
  coupling — deliberately minimal, honoring the deadpan restraint.

**Depth knob / silence behavior**: `Audio` defaults to 0 = pure clock. At depth
0 (or in true silence) `bassPulseRaw` contributes nothing, jitter is 0, and the
clock is rock-steady — a clean institutional hold screen. Raising the knob adds
the per-kick jitter continuously. Nothing else in the pattern reads audio, so
the knob is the single master gate. (The brief's "higher = subtle jitter on
kicks" is the whole story.)

## Tempo mapping

The clock **ticks on the grid**. Each frame `TempoLock.crossed(tempoDiv)` is
polled unconditionally (even when Sync is off, per the stale-cycle-count
gotcha). With `Sync` on, `elapsedTicks` advances and the colon toggles on every
`TempoDiv` crossing (default **QUARTER** — one tick per beat at 85 BPM, "on the
beat"). With `Sync` off, the clock free-runs on a BPM-period accumulator
(`beatPeriodMs`), so it still ticks at the musical rate but drifts off the grid
phase — fully functional and good-looking standalone.

The 85 BPM `opus` grid is rigid (fit residual ~10.7 ms), so `TempoLock` locks
cleanly; everything snaps. No `retime` is used — the clock is a discrete
grid-gated event, not a continuous motion that needs phase-nudging. The shimmer
sweep is time-based (`SHIMMER_SWEEP_MS`), not grid-locked, since it's a brief
choreographed bloom driven by a clip lane over a fixed 10 s window.

## Energy mapping

No `energy` parameter. This song is choreographed by the composition (clip
lanes on `phase`, `clockMode`, `shimmer`, `freeze`) rather than by a single
ambient↔peak energy dial, and the brief's whole point is *deliberate flatness* —
an energy knob would invite exactly the dynamic swell the deadpan bit forbids.
The one permitted brightness excursion (the shimmer) has its own dedicated knob.

Sustained motion still respects the ≥5 s cap — see the compliance section.

## Parameters

UI/registration order (house style; keys/labels are frozen once `.lxp` files
reference them): triggers (Meta last of them, via the bag), pattern parameters,
Audio, Sync, TempoDiv. No Energy param (see above).

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| `tick` | Tick | TriggerParameter | — | — | advance the clock one unit now (manual on-beat tick) |
| `boot` | Boot | TriggerParameter | — | — | leave the cold open, boot the clock (the 0:19 reveal) |
| `baseFlip` | BaseFlip | TriggerParameter | — | — | step to the next (more esoteric) clock mode |
| `glitch` | Glitch | TriggerParameter | — | — | arm a one-frame loop-glitch / VHS melt (button flourish) |
| `meta` | Meta | TriggerParameter | — | — | randomly fire a trigger or jump a parameter |
| `phase` | Phase | EnumParameter | Running | ColdOpen/Running/Blackout | top-level screen state (composition-driven; Running default for standalone) |
| `clockMode` | Mode | EnumParameter | Decimal | Analog/Flip/Decimal/Binary/Hex/Mystic | clock face style; morphs across the outro |
| `brightness` | Bright | CompoundParameter | 0.35 | 0..1 | deadpan resting display brightness |
| `hue` | Hue | CompoundParameter | 0 | 0..360 | rotates the palette-sampled phosphor hue (0 = pure palette) |
| `shimmer` | Shimmer | CompoundParameter | 0 | 0..1 | crystal-staircase cascade intensity (committed beat 0:40–0:50) |
| `freeze` | Freeze | BooleanParameter | false | — | latch the held "GOD PUT ME ON HOLD" punchline frame (committed beat ~1:25) |
| `message` | Msg | StringParameter | "" | — | held-frame / cold-open text, driven by clip-lane string events |
| `audio` | Audio | CompoundParameter | 0 | 0..1 | audio reactivity depth (0 = pure clock) |
| `sync` | Sync | BooleanParameter | true | — | tick on the tempo grid; off free-runs at the BPM period |
| `tempoDiv` | TempoDiv | EnumParameter | QUARTER | Tempo.Division | division the clock ticks / colon blinks on |

### Composition-driven timeline (how the .lxp drives it)

| mm:ss | Anchor | Clip-lane action |
|---|---|---|
| 0:00 | cold open | `phase` = ColdOpen, `message` = "" (blinking colon), `brightness` low |
| ~0:10 | recognition dawns | (automatic — the `DAWN_MS` ramp brings the colon in) |
| 0:19 | first reveal (bass in) | `phase` = Running, `clockMode` = Decimal (or Analog) |
| 0:40–0:50 | crystal staircase | ramp `shimmer` 0→1→0 over the 10 s window |
| 1:04 | go esoteric | `baseFlip` (Decimal→Binary), later →Hex, →Mystic |
| ~1:25 | the button | `message` = "GOD PUT ME ON HOLD", `freeze` = on |
| 1:29 | hard cut | `freeze` = off, `phase` = Blackout |

## Triggers

Four non-Meta triggers, small → large state change:

- `tick` (small) — advances the clock one unit and toggles the colon. Reads as
  a single quiet increment; instant.
- `boot` (medium) — flips `phase` to Running, so the clock materializes out of
  the cold open. Reads as "the system came online"; instant, persists.
- `baseFlip` (medium-large) — steps `clockMode` to the next style
  (analog→flip→decimal→binary→hex→mystic, wrapping). The whole clock's
  representation changes; instant, persists. This is the mutate-to-esoteric gag.
- `glitch` (large) — arms the `GLITCH_MS` loop-glitch/melt envelope (the button
  flourish before blackout). Event-like; ≥ 1.5 s life. Built: the frame is
  copied to a scratch canvas and rewritten per-pixel through a horizontal
  phase-wobble + occasional torn band + a per-column vertical drip that grows as
  the envelope fades (the VHS melt into near-black). CURATE: the wobble/tear/
  melt magnitudes are blind-picked — tune on hardware.

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. All four
non-Meta triggers are also registered in the bag.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| `clockMode` | all (Analog..Mystic) | candidate | full morph range; logged by label |
| `brightness` | [0.2, 0.5] | candidate | stay in the deadpan band — never lush |
| `hue` | [0, 360] (full) | candidate | any rotation is safe |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

- **The clock is essentially static** — it changes at the tick rate (one step
  per quarter note ≈ 0.7 s at 85 BPM). A digit flip / colon blink is an
  event-like discrete change, not sustained motion, so the ≥5 s traversal cap
  does not apply; the frame is otherwise motionless between ticks. Bold forms:
  5px-tall high-contrast phosphor glyphs, no gradients, no fine texture.
- **Shimmer cascade (the one sustained motion)** — a seamless repeating
  staircase of `SHIMMER_STEPS` treads (with `SHIMMER_STRANDS` parallel offset
  strands) scrolls up exactly one full canvas height once per `SHIMMER_SWEEP_MS`
  = 5 s (CURATE). Because the flight is periodic (period = full height), any
  point of light rises one full traversal per sweep: Cube 45 rows / 5 s →
  **5.0 s full traversal, exactly at the ≥5 s cap**; Cylinder 43 rows / 5 s,
  slower still. A highlight band rides up the flight (the "prince dancing up a
  crystal staircase") but travels at the same one-height-per-5-s rate, so it too
  honors the cap. The shimmer only runs during the ~10 s clip-lane window, then
  `shimmer` returns to 0.
- **Cold-open dawn (event-like)** — a 10 s brightness ramp of the colon from
  black to ~0.35; no spatial motion, ≫ 1.5 s. It's a slow fade-in, the joke's
  timing.
- **Button glitch (event-like)** — `GLITCH_MS` = 1.6 s ≥ 1.5 s minimum. The
  melt is a per-frame distortion of the freshly drawn frame (no sustained
  traversal), fading with its envelope.
- **Held punchline frame** — deliberately *frozen*: no motion at all while
  `freeze` is on, held "a beat longer than comfortable," then a hard cut to
  black. Timing delivers the turn, per the critique.
- **Audio jitter** — ≤ 1 px, per-kick, sub-frame; not a traversal.
- CURATE: at scale 1 an 8-char `HH:MM:SS` is ~47 px wide on a 50 px face —
  legible but small. Confirm the clock reads at hardware pitch; consider a
  shorter `MM:SS` format or a larger integer scale if it's too tiny.
- CURATE: verify the cold-open colon at `brightness × dawn` (≈ 0 → 0.35 over
  10 s) is genuinely near-invisible early and clearly readable by ~0:10.
- CURATE: confirm the shimmer at `SHIMMER_PEAK_MULT` = 1.0 actually reads as the
  brightest moment of the song against `brightness` 0.35 elsewhere.

## CURATE notes (open items for the hardware pass)

- `DAWN_MS` (10 s), `BRIGHT_DEFAULT` (0.35), `SHIMMER_PEAK_MULT` (1.0),
  `PUNCH_MULT` (1.0), `SHIMMER_SWEEP_MS` (5 s), `SHIMMER_STEPS` (5),
  `SHIMMER_STRANDS` (3), `FLIP_MS` (220 ms), `GLITCH_MS` (1.6 s), `JITTER_PX`
  (1), `MYSTIC_BASE` (12), `FALLBACK_PHOSPHOR` (Gareth cyan #33CCFF) — all
  picked blind; tune on hardware.
- Confirm the built faces read at hardware pitch: the analog bezel/ticks/hands
  and the base-N occult dial live on a ~min(width,height) circle (≈43–45 px),
  the binary dot-grid packs 6×4 dots, and the split-flap seam is a 1 px line —
  all authored blind; verify legibility and dot/hand thickness on the LEDs.
- Glitch melt magnitudes (wobble 0.16·w, tear 1-in-8 bands, drip 0.55·h) are
  blind-picked — confirm the VHS flourish reads as intended before the cut.
- Ticks are displayed as seconds 1:1 (`writeTime`); decide whether the clock
  should show real wall-clock elapsed instead. Same for the "estimated wait"
  line, which counts up in real elapsed ms since the clock started running.
- Single-face vs. all-faces question (see Rendering approach).

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-07 | Initial first-pass, Claude autonomous session | Design doc + compiling stub: phase/mode state machine, tick engine, digit rasterizer, and the two committed beats (shimmer knob, freeze button) all wired and registered; analog/binary/mystic/flip/glitch/cascade/multi-line left as TODOs for the hardware pass |
| 2026-07-07 | Full build-out, Claude session | Built every stubbed subsystem: multi-strand crystal-staircase shimmer cascade (seamless periodic staircase + ascending highlight, ≥5 s traversal); multi-line word-wrapped held message with auto-scale + never-resolving "EST WAIT" counter; analog face (bezel ring, 12 ticks, AA lineWu hands, smooth sub-tick sweep); split-flap FLIP styling with per-tick flap fall; binary 6×4 BCD dot-grid; base-N occult MYSTIC dial (esoteric glyph marks, counter-rotating inner ring, smooth pointer); and the VHS button-glitch melt (scratch-buffer phase-wobble + torn bands + per-column drip). Zero-alloc preserved (added bcd[6], line-range arrays, two scratch canvases, all preallocated); all parameters/keys unchanged |
