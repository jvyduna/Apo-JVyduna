# Bliss — Composition Brief

> Song 2 of 6 in *Communicating* (Jeff Vyduna, Apotheneum, ~21 min).
> Synthesizes MIR structure + three research lenses into a choreography plan.
> Status: **already built** as a reference `.lxp` (`2 - Bliss.lxp`, 160 BPM TEMPO
> base); this brief is the design rationale and the next-pass choreography spec.

## 1. Snapshot

- **Artist / title:** DUCKY (Morgan Neiman) — *Bliss*. Ducky (Morgan Neiman)
  uses he/they pronouns.
- **Release:** opening track of the EP *Don't Give Up Yet*, Secret Songs (Ryan
  Hemsworth), Aug 12, 2016. Official video premiered on Pitchfork ~Jul 29, 2016.
- **Duration:** 4:06 (MIR `duration_s` = 246.014 s)
- **Key:** F Minor (= Ab major; keyfinder + librosa agree). Loop harmony is a
  bright diatonic cycle: **Fm7 (vi) → C#maj7 (IV) → Eb (V) → Ab/Bb (I over II)**.
- **Tempo / grid:** **160 BPM, rigid**, 4/4, 16th subdivision, 165 bars.
  `known_bpm_match: true`; beat_this measured 157.9; the existing composition
  already runs a 160 TEMPO time base. (Research flagged a secondary "150 BPM"
  characterization from Insomniac — note it, but for our purposes **160 rigid is
  confirmed** by the MIR fit and the existing project.)
- **Arc position:** the piece's second beat — *being birthed into a religion*.
  After the rubato, mournful-hopeful childbirth of **temper**, Bliss is the
  euphoric, uncritical rush of a child fully inside a belief system: everything
  is bright, fast, patterned, and *certain*. This is the joy **before** doubt.
  It should read as the most naive, high-gloss, "so tech" moment in the whole
  arc — pure serotonin, no shadow yet.

## 2. Song structure (from MIR)

Sections are `allin1` machine labels (intro/solo/inst/end); the read below is
the per-stem RMS envelope reality, which is far more informative than the labels.
Energy peaks/valleys are from mean stem RMS per section; hit times are from
crash/snare onset peaks.

| Time | Section (envelope reality) | Notes |
|------|---------------------------|-------|
| **0:00–0:24** | **Intro / pad-only** | `other` (pads/lead) 0.31, **no bass, no snare, no crash**, kick barely 0.04. Atmospheric wash, harmony floating. The calm before ignition. |
| **0:24–0:48** | **Drop 1 (first full lead)** | Everything ignites at once: bass in (0.40), kick, snare backbeat (0.15), **crash burst 0:25 (0.86), 0:31, 0:37, 0:40**. Dense snare fills 0:34–0:47. This is the euphoric first payoff. |
| **0:48–1:12** | **Breakdown A (kick-out)** | **Kick drops to 0**, snare gone, but **bass stays heavy (0.44)** + lead. Half-time / filtered float — first "hold your breath." |
| **1:12–1:47** | **Main body (four-on-floor)** | **Kick 0.31 (peak)**, bass 0 (sub folded into kick region), **lead `other` 0.43 (peak)**. The driving anthem core — relentless pulse under the hook. |
| **1:47–2:00** | **Main body cont.** | Same drive; crash accents 1:58; vocals tick up (0.02) hinting at chops to come. |
| **2:00–2:29** | **Breakdown B (rolling)** | Kick out again (0.02), **bass back (0.30)**, snare rising (0.09), crash stabs 2:03–2:16. Tension re-loading. |
| **2:29–2:48** | **Snare-roll BUILD** | **Snare peaks 0.20 mean, hits of 0.70/0.82/0.86 at 2:29, 2:35, 2:41.** Classic accelerating snare-roll riser — the single most kinetic build in the track. |
| **2:48–3:12** | **Drop 2 (four-on-floor return)** | **Kick 0.31**, snare cut to ~0 — the build pays off into the hardest four-on-floor. |
| **3:12–3:35** | **CLIMAX (loudest section)** | **mix RMS 0.566 — highest in the track.** Kick 0.29 + bass 0.41 both driving. Peak collective energy; the emotional summit. |
| **3:35–3:59** | **Outro / vocal chops** | **Kick out**, bass sustained (0.43), and **vocals peak (0.16) — highest by far.** Melodic vocal chops surface as the beat dissolves; the bliss softens into afterglow. |
| **3:59–4:06** | **End** | Everything decays to near-silence. Clean fade. |

**Choreography anchors (the load-bearing moments):**
- **0:24** — Drop 1 ignition (first crash 0.86). The lights must *snap on*.
- **0:48** — kick-out breakdown; motion should float / decelerate.
- **1:12** — main four-on-floor engages; lock the primary motion to the kick.
- **2:29–2:41** — the snare-roll build; escalating strobe/accumulation, resolve at 2:48.
- **2:48** — Drop 2; hardest visual hit.
- **3:12** — climax, loudest; the widest, brightest full-surface moment.
- **3:35** — vocal chops emerge; shift from grid/geometry toward softer melodic
  color, begin the wind-down.

## 3. Reception & meaning

**Emotional consensus — bright, buoyant, joyful, high-energy — is unambiguous
and consistent across every source found.** The one substantive review
(Enlightenment For Your Ears) calls Bliss "a peppy, high-spirited track with
supersonic synth strides, centered around punchy percussion pops and rising bass
bumps," and "the ultimate opening track... a joyous journey through sound," with
the EP as a whole "buoyant, bouncy and fantastically freeing"
(https://enlightenmentforyourears.blogspot.com/2016/08/ducky-dont-give-up-yet-ep.html).
No dark/melancholic language appears anywhere. **Confidence: solid on mood,
thin on volume** — Bandcamp shows only ~6 supporters and no fan comments
(https://shhsecretsongs.bandcamp.com/track/bliss); Reddit/RYM/last.fm/YouTube
surfaced no track-specific reaction threads or standout timestamps
(https://www.last.fm/music/Ducky).

**Artist vision (scene-level, not track-specific).** No primary statement from
Ducky about *Bliss* itself exists. Their documented ethos is fast, melodic,
un-cynical "rave" music carrying genuine emotion at high tempo, with an explicit
anti-genre-purism stance ("don't feel limited or defined by genres"); they keep
a 100–216 BPM playlist (https://dmy.co/features/ducky-nightcore-interview).
Insomniac ties their "carefree adventurousness at 150 BPM" to Bliss
(https://www.insomniac.com/magazine/my-charming-ducky-youre-for-me/) — hence the
tempo caveat. **Confidence: solid on aesthetic, no track-level intent.**

**Critics.** No tier-1 outlet (Pitchfork, RA, Quietus, AllMusic, PopMatters)
reviewed it; usable criticism is Insomniac + Vice/Noisey + one enthusiast blog.
Vice frames their sound as "super-chill, weird-wonderful pop, full of unexpected
grooves, interstellar bleeps," capturing "the freshness of falling in love"
(https://www.vice.com/en/article/rgpyzb/premiere-ducky-you-on-top-ep). The EP's
Bandcamp framing — "finally finding hope after a lifelong battle with undiagnosed
bipolar and trauma" (https://shhsecretsongs.bandcamp.com/album/dont-give-up-yet)
— gives *Bliss*, as track 1, a **hope/resilience "beginning"** valence that maps
beautifully onto our arc's "birthed into" beat. **Confidence: solid facts, thin
criticism (mostly one blog + dance press).**

**Disambiguation (do not attribute):** not Tyla's "Bliss," not a tattoo-artist
"Ducky," not Ducky's own "Bliss Pack" (dubstep samples) or "BLISS TAPE 01" (DJ mix).

## 4. Jeff's existing ideas (from the source sheet)

**Palette:** Mint / magenta / yellow, with CMY moments and the **Rubik speed-cube
color scheme**. Named triads that fit: *Try Radish* (`#00FF99`/`#66CCFF`/`#FFE699`),
*CMG — Clover Magenta Goldenrod* (`#33FF99`/`#FF66CC`/`#FFCC33`). Feel: "so tech;
80s screensaver topograph." Sheet note: "Twinkle starfield bliss."

- **Rimshot rips:** rimshots create negative-space *rips* via an FFT
  detector / trigger track → the **`Rip` effect**.
- **Reimagined 90s/After Dark screensavers:** Mountains, Mystify, Pipes3D, Satori.
  A second rimshot series → **decaying splotches** (paint thrown at a wall,
  dripping down while fading).
- **Juvenile:** a **Rubik's cube solving on-beat**.
- **Grid fly-over:** moving through grid fields like the late-80s SR-71 Blackbird
  t-shirts → **Outrun**.

## 5. Artistic feedback & direction

**The thesis: naive maximalist gloss.** Bliss is the arc's "true believer as a
child" moment — no irony, no doubt. Every neighbor is more complicated: **temper**
before it is rubato and mournful; **rafters** after it "turns brooding." So Bliss
should be the one song that is *unashamedly bright, fast, geometric, and clean* —
the visual equivalent of a kid who is certain the world is exactly as they were
told. Lean **hard** into the 80s/90s tech-nostalgia and CMY/Rubik candy. Avoid
anything atmospheric, painterly, or ambiguous — save that for chrome and distance.

**Structure the visuals as a two-drop EDM form, not a slow evolution.** The MIR
gives you a textbook build/drop skeleton — use it literally:
- **0:00–0:24 intro** is a *dark stage*. Almost nothing: a single floating
  gradient or a sparse "twinkle starfield" on the cylinder/interior, harmony-tinted.
  Resist the urge to fill it. The payoff at 0:24 only lands if you hold back here.
- **0:24 Drop 1** — hard cut to the full hero visual. The 0:25 crash (0.86) is
  your biggest single-frame accent in the first half: full-surface white/CMY flash
  → settle into the grid.
- **0:48 Breakdown A** — kick is *gone* but bass stays. Motion should visibly
  *decelerate and float* (screensaver mode: Mystify or Pipes3D drifting slowly),
  color cooling toward mint/sky. This is the "hold your breath."
- **1:12 Main body** — the hero grid re-engages and **locks to the kick** (TempoLock,
  four-on-floor). This is the longest single-look stretch (35 s) — it must sustain
  without getting boring, so this is where a *travelling* pattern (Outrun fly-over)
  earns its keep over a stationary one.
- **2:00–2:29 Breakdown B** — second float, but now *reloading tension*: introduce
  the rolling snare/crash stabs as **Rip** negative-space tears (2:03–2:16).
- **2:29–2:48 snare-roll BUILD** — this is the marquee choreography moment.
  Accelerating snare hits (0.70→0.82→0.86 at 2:29/2:35/2:41) should drive an
  **accumulating riser**: strobing quadrants, a Rubik cube scrambling faster, or a
  BlitFeedback accumulator whiting-out toward the drop. Everything tightens.
- **2:48 Drop 2** — hardest hit; the grid slams back, ideally *inverted or
  re-colored* (swap to the Rubik six-color scheme here for a "level up" feel).
- **3:12 CLIMAX (loudest)** — go widest and brightest: all four cube faces +
  interior + cylinder ring active simultaneously, full palette. This is the one
  moment to blow the whole surface out.
- **3:35 vocal chops** — the melodic turn. Retire the hard grid; the vocals
  (peak RMS 0.16) should *paint*: this is the natural home for the "paint thrown
  at a wall, dripping while fading" splotch idea, in magenta/yellow, over a
  darkening field. End on afterglow, fading to black by 4:06.

**What to avoid:** a single pattern running the whole 4 minutes at constant
intensity — the MIR is explicitly two-drop, and flatlining wastes the two
breakdowns and the build. Also avoid muddy blends; Bliss wants **saturated, hard-
edged, high-contrast** color (CMY primaries), not gradients between neighbors.

**How it differs from neighbors:** temper is soft/rubato/pastel; Bliss is
**rigid-grid, saturated, fast, geometric**. rafters (next) is 70 BPM and "turns
brooding" — so Bliss is the last purely happy song for a while. Make the
brightness *conspicuous* so the coming turn toward doubt lands harder.

## 6. Pattern & effect plan (ranked)

All hero-tier patterns already exist in `patterns/bliss/`; "first-pass
implementation" here means **choreographing them to the MIR anchors** in the
composition, plus tuning. Ranked by centrality to the track.

1. **★ HERO — `Outrun` (neon SR-71 grid fly-over).** *Reuse.* The definitive
   "so tech / 80s / moving through grid fields" image and Jeff's own headline
   idea. It is a *travelling* look, so it sustains the 35 s main body (1:12–1:47)
   and the drops (2:48, 3:12) where stationary screensavers would stall. Wire its
   forward speed to a TempoLock-locked kick pulse; hue-cycle the horizon across
   the CMG palette; on Drop 2 (2:48) swap to the Rubik six-color scheme. First
   pass = get Outrun running the four "on" sections and hard-cutting at 0:24 /
   2:48. Honors the ≥5 s traversal cap (continuous fly-over) and Audio depth knob.

2. **`Rip` effect (rimshot negative-space tears).** *Reuse effect.* Jeff's
   signature idea for this track. Drive it from the snare/crash trigger track —
   fire on the crash burst (0:25–0:40), the reloading stabs (2:03–2:16), and let
   it go wild through the snare-roll build (2:29–2:43, hits up to 0.86). This is
   the layer that makes the percussion *visible*. ≥3 triggers trivially satisfied.

3. **`Mystify` / `Pipes3D` (After Dark screensavers) — breakdown float looks.**
   *Reuse.* Deploy specifically in the kick-out breakdowns (0:48–1:12 and
   2:00–2:29): drifting, decelerated motion, cooled mint/sky palette, to contrast
   the driving grid. Mystify's slow polyline drift reads as "float"; Pipes3D as
   "toy tech." Pick one per breakdown for variety.

4. **`Rubik` (speed-cube, solving on-beat).** *Reuse.* The "juvenile" motif and
   the source of the six-color palette. Best used as the **snare-roll build →
   Drop 2 payoff** (2:29 scramble accelerating → 2:48 hard face-turn slam), and/or
   as a cube-face accent during the main body. On-beat solves via TempoLock.

5. **`BlitFeedback` effect (accumulator riser).** *Reuse effect.* On the snare-
   roll build (2:29–2:48), feed the accumulator to white-out toward Drop 2 — the
   classic EDM riser bloom. Also usable at the 3:12 climax to fatten the full-
   surface blowout. Keep it OFF during the clean grid sections.

6. **Splotch / drip look for the vocal-chop outro (3:35–3:59).** *Likely a small
   new class or a BlitFeedback + palette-splat combo.* Jeff's "paint thrown at a
   wall, dripping down while fading" idea, triggered by the emerging vocal chops
   (RMS peak 0.16). Magenta/yellow blobs that bleed downward and fade to black by
   4:06. If BlitFeedback with a downward-biased decay + trigger-spawned splats can
   do it, prefer that over a new class; otherwise a compact `Drip` pattern.

7. **`Mountains` (topograph) — optional intro texture.** *Reuse.* The "80s
   screensaver topograph" line points here; a slow, near-static topographic wash
   could carry the 0:00–0:24 pad intro at low brightness before the Drop-1 cut.
   Secondary — the intro can also just be the "twinkle starfield," so this is a
   nice-to-have alternate.

8. **`Text` — very sparse, optional.** *Reuse.* Not core to Bliss's mood, but a
   single hard-cut word on Drop 1 or the climax (e.g. "BLISS") on the cube
   exterior could suit the naive/on-the-nose register — only if it doesn't
   clutter. Low priority; easy to cut.

**Geometry notes:** the travelling grid (Outrun) and rips read best on the four
cube faces; use the cylinder ring for the twinkle-starfield intro and for a
horizon/perimeter accent; reserve the **interior** for the 3:12 climax full-
surface blowout so it has somewhere left to escalate.
