# Composition brief — "Opus No 1" (slug `opus`)

> Song 4 of 6 in "Communicating." This is the comic-relief / troll beat and the
> shortest track in the set — a palate cleanser and a tonal trapdoor between
> `rafters` (brooding) and `chrome` (the occult-cathedral centerpiece).

## 1. Snapshot

- **Artist / title:** Tim Carleton & Darrick Deel — "Opus No. 1," universally
  known as the **Cisco default hold music**. (Credit it honestly; it is not
  anonymous — see meaning section.)
- **Duration:** 1:29 (89.4 s) — by far the shortest song in the piece.
- **Key:** D# / Eb major (keyfinder + librosa agree). Progression actually
  *cadences to C* at the end, not Eb — a deliberately unresolved, loop-forever
  tonality that is the whole point of hold music.
- **Tempo / grid:** 85 BPM, **rigid** grid, 4/4, 16th subdivision, 32 bars,
  downbeat offset 1.81 s. Fit residual mean 10.7 ms — tight, quantized, machine
  music. `TempoLock` will lock cleanly; everything can and should snap to grid.
- **Arc position & emotional beat:** the "**traveling away from**" phase of the
  religion-across-a-lifespan arc, at its most deadpan. This is the **spiritual
  waiting room** — purgatory, limbo, the on-hold silence between abandoning the
  inherited faith and the reckoning that follows. The famous listener line
  **"It feels like God put me on hold"** is the literal thesis of this cue. The
  intended audience experience: *recognition dawning* — "wait… is this the
  Cisco hold music?" — comfort curdling into the absurd realization that they've
  been placed on hold by the divine. Comic on the surface, quietly existential
  underneath.

## 2. Song structure (from MIR)

MIR sections (allin1), remapped labels, with the mix-envelope and per-stem
reads. Frame rate 25 Hz; RMS normalized 0–1, peak = 1.000 at 21.6 s.

| mm:ss | Section | Bars / chords | Energy & stems | Notable |
|-------|---------|---------------|----------------|---------|
| 0:00–0:19 | **intro** (pass 1) | bars 1–8: C · Fm · Fm · Bb · Ab · Bb · Bb/Ab · Bb6 | Low/uneven: RMS ~0.13–0.28, **bass thin** (0.05–0.10), treble-forward hats. Drums actually enter at **1.9 s** (kick/snare/hat all together). | The "WTF, why is it near-black" window. Melody ("other") sparse until ~0:15. |
| 0:19–0:36 | **up** (intro pass 2) | bars 9–13: Abmaj7 · Eb/Bb · Ab7 · Gm · Cm7 | **Bass slams in** (0.22) and RMS jumps to ~0.38; **global RMS peak at 21.6 s**. First real "arrival." | The reveal moment — full band locks. Choreography anchor #1. |
| 0:36–1:04 | **down** (solo) | bars 14–22: Ab · Bb · Ab · Bb · Ab · Ab6 · Eb · Ab · Eb | **Melodic high point:** mid peaks 0.33 at 0:35–0:40, "other" event density peaks **55 events at 0:40–0:45**, 46 at 0:45. This is the "smooth-jazz lead" / the *"prince dancing up a crystal staircase"* shimmer. Bass eases back. | The funky/danceable read lives here. Choreography anchor #2 (0:40–0:50). |
| 1:04–1:29 | **outro** | bars 23–32: Eb ×3 · Gm · Fm · C7 · **C · C · C · C** | **Bass drops out** (~0.06 through 1:00–1:20), texture thins; mid re-blooms 1:05–1:15 (0.38) then recedes. **Final cadence chord ~1:25** (bass+treble spike, RMS 0.38 across 1:25–1:30), then **hard cut to silence at 89.0 s** (RMS → 0.000). | Ends on **C, not Eb** — unresolved, wants to loop. The "you're still on hold" tail. Choreography anchor #3 (the 1:25 button and the 1:29 cut). |

Structural read: it's a two-lap loop (intro pass 1 ≈ pass 2 "up") with a
melodic solo swell in the middle third and a thinning outro that never truly
resolves. Drums run near-continuously from 1.9 s to 88 s — there is no "drop,"
so dynamics come from the **bass on/off** (in at 0:19, out at 1:04) and the
**melody density bulge at 0:40–0:50**.

## 3. Reception & meaning

Confidence is **high** on the cultural read, **low/none** on any authorial
intent (there isn't one).

- **How listeners feel it — warm, surreal, secretly funky.** Reactions are
  wildly affectionate for utilitarian music, clustering on: nostalgic
  **corporate-futurism** people explicitly call *vaporwave* ("what vaporwave is
  nostalgic for," "the soundtrack to a training video for a futuristic software
  company that never actually launched")
  (https://locotheme.com/why-the-cisco-systems-hold-music-is-unironically-a-masterpiece-2511);
  quasi-religious awe (**"It feels like God put me on hold"**)
  (https://roadtonowhere.forumotion.org/t273-tim-carleton-darrick-deel-opus-no-1-a-k-a-cisco-hold-music);
  cozy comfort ("a warm shirt out of the dryer"); and a shimmer moment read as
  "a prince dancing up a crystal staircase… head flung back in a moment of
  bliss"
  (https://holdmusicreviews.wordpress.com/2021/01/08/opus-no-1-cisco-default-hold-music/).
  Many say they *want* to stay on hold; *This American Life* documented
  near-obsessive devotion
  (https://www.replicant.com/blog/the-surprising-roots-of-one-of-the-most-common-hold-music-songs).
  A "funky enough I'd get down at the club" thread runs underneath the calm.
  **Confidence: solid** (recurs across independent sources; exact quote wording
  is often paraphrased through aggregators).
- **Artist vision — there is none, and that's the story.** Composed **1989 by
  Tim Carleton (age 16) with Darrick Deel** on a 4-track in a California garage,
  "Yanni-loving computer nerd" synth-and-drum-machine noodling; forgotten for a
  decade until Deel, by then a Cisco engineer, pitched it as hold music — now on
  **65M+ IP phones**. Carleton is bemused/embarrassed, made **"not a penny"**
  from the rollout, and *retains 100% ownership* (Cisco licenses non-exclusively)
  (https://www.thisamericanlife.org/553/transcript ·
  https://www.mentalfloss.com/article/68163/ciscos-default-hold-music-was-written-16-year-old-computer-nerd
  · https://globalnews.ca/news/10416687/on-hold-music-history/). The compelling
  angle is **accidental ubiquity**, not intent. **Confidence: solid** on origin
  / ownership; **thin** on any themed statement (none exists).
- **Critics — no corpus.** Never released as a single, never reviewed by
  Pitchfork/Quietus/RA/AllMusic. Nearest critic voice: **Stereogum** — "generic
  '80s synth" that Tom Krell (How To Dress Well) "repurposed beautifully" on
  "Precious Love," capturing the uncanny where-have-I-heard-this hook
  (https://stereogum.com/1687898/the-story-behind-the-soothing-cisco-hold-music-sampled-on-how-to-dress-wells-precious-love/news);
  *TAL* calls it "one of the most widely-heard earworms that no one ever intended
  to listen to." Recurring words: **soothing, smooth jazz, beautiful music /
  Muzak**. **Confidence: solid** on the absence of crit + the two quote sources;
  no structural analysis exists to borrow.
- **Rights note (for the non-commercial art ask):** route to **Tim Carleton**
  (Deel co-writer); he owns and licenses it directly. No verified contact site
  confirmed (`opus1.info` 404'd) — unresolved follow-up.

## 4. Jeff's existing ideas (from the setlist sheet)

Feel tag: *"Classic — people know it, but it dawns on them what it is."*
Three ideas, all circling the same gag of mundane waiting-room boredom:

1. **Cold open.** Start black long enough that the room wonders *WTF is
   happening* — let the familiar tune do the work before any light.
2. **A boring clock** that just reiterates the hold — visualized patience,
   nothing happening.
3. **The boring clock turns esoteric** — a nerdy clock: binary, hex, or some
   other computer-nerd counter (leaning into the "computer nerd" origin).

## 5. Artistic feedback & direction

**Commit to the bit.** This is the one song in the piece that should read as
*deliberately, confidently boring* on the surface — and that restraint is what
sells the arc. After `rafters` leaves the room brooding, `opus` should feel
like the house lights half-came-up and someone put the venue on hold. The
audience does the emotional work: recognition → amusement → the small chill of
"God put me on hold." Do **not** over-produce it into another lush texture
piece; it must feel *thinner, flatter, and more deadpan* than everything around
it. Its neighbors are maximal (`bliss` screensavers, `chrome` cathedrals) — so
`opus`'s job is negative space and comic timing.

**Synthesize the three research lenses into one visual language:** liminal
**corporate-futurism / vaporwave** (the "training video for a software company
that never launched") *plus* the **on-hold clock** *plus* the **"God put me on
hold" divine punchline.** That's the throughline: a hold screen for the
afterlife's customer-service line.

**Choreography anchors (tie everything to these):**
- **0:00–0:19 — the cold open.** Near-black. Drums enter at 1.9 s but keep the
  canvas almost empty: maybe a single blinking cursor, a lone `PLEASE HOLD`, or
  just a ticking colon. Make them wait and wonder. This is Jeff's idea 1,
  honored literally — the darkness is the joke.
- **0:19 — first reveal (RMS peak 21.6 s, bass in).** The bureaucratic UI boots.
  The clock appears; a muted vaporwave grid ignites on the floor/faces. This is
  the "system came online" beat.
- **0:40–0:50 — the shimmer / funky middle (melody density peak).** Lean into
  the *secretly danceable* read: let the smooth-jazz lead drive the brightest,
  most fluid motion of the song — the "crystal staircase" moment. Still tasteful,
  still corporate-palette, but this is where the room is allowed to smile and
  sway. Brief, then pull back.
- **1:04 — bass drops, go esoteric.** Jeff's idea 3: the mundane clock mutates —
  decimal seconds flip to **binary, then hex**, an "estimated wait time" that's
  absurd (a countdown that never reaches zero, or ticks *up*). The nerd origin
  surfaces here.
- **1:25 — the button, 1:29 — hard cut.** Hit the final cadence chord, then cut
  to black on the silence. Because the tune resolves to **C not Eb**, it feels
  unfinished on purpose — the ideal button is one that implies *you're still on
  hold*: freeze the clock mid-tick, or loop-glitch one frame, then blackout.

**Lean into:** deadpan restraint, muted/desaturated "office beige + phosphor"
palette, exact grid-snapped motion, a few genuinely funny text beats.
**Avoid:** lushness, saturated palettes, continuous full-surface motion, anything
that competes with `chrome`. If in doubt, do less.

**Palette:** intentionally *off* from its neighbors. Pull the desaturated /
cream end of the sheet — **Gareth** (`#33CCFF · #CC99FF · #FFB38A`) or the cream
in **Try Radish** (`#FFE699`) — read as sun-faded 1989 corporate print + CRT
phosphor cyan. Keep saturation and brightness low so `chrome` can bloom by
contrast right after.

## 6. Pattern & effect plan

Ranked. One hero for first-pass implementation.

1. **HERO — `HoldClock` (NEW class, reuses `PixelFont5` / `Text` infra).** The
   spine of the song and the direct realization of Jeff's ideas 1–3. Renders a
   corporate hold-screen clock in the 5px font on a cube face (front, with
   `left/right/back` optionally mirroring). Behavior driven by composition clip
   lanes / `TempoLock`:
   - cold-open blink through 0:00–0:19 (near-black, colon tick),
   - a mundane `HH:MM:SS` (or `00:00` elapsed) ticking on-beat from 0:19,
   - at 1:04 a **base-flip** mode: decimal → binary → hex counter, plus an
     absurd "ESTIMATED WAIT" that never resolves,
   - freeze/loop-glitch button at 1:25 → blackout at 1:29.
   Reuses `PixelFont5` glyphs and `Text`'s world-unit layout/rotation approach;
   new logic is the counter state machine and base rendering. `Audio` depth knob
   at 0 = pure clock; higher = subtle jitter on kicks. Triggers: beat tick,
   bass-in (0:19), base-flip. Zero-alloc (prebuild digit strings).
2. **Hold-message marquee — REUSE `Text`.** Scrolling/fading corporate boilerplate
   as counterpoint to the clock: `YOUR CALL IS IMPORTANT TO US`, `PLEASE CONTINUE
   TO HOLD`, `ALL DIVINE REPRESENTATIVES ARE BUSY`, and the payoff
   `GOD PUT ME ON HOLD` landing near the 0:40–0:50 shimmer or on the final button.
   `Text` already does StringParameter + `\n` + scroll/offset; drive `text` from
   clip-lane string events. Pure reuse, no new class.
3. **Vaporwave floor grid — REUSE `Outrun`.** The "training video for a software
   company that never launched" backdrop: a slow fly-over neon grid on the cube
   floor / lower faces, desaturated palette, `Audio` depth low. Boots at the 0:19
   reveal, gentle through the shimmer, recedes into the outro. Directly serves
   the vaporwave/corporate-futurism reception read. Reuse as-is with a muted
   palette + slow speed (respect the 5 s traversal cap).
4. **Smooth-jazz light-organ — REUSE `MirPitchGrid`.** Subtle, only during
   **0:40–0:50** melodic peak: map the "other"/lead synth to a restrained grid
   glow so the funky middle has motion tied to the actual notes. Keep brightness
   low; it's seasoning under the clock, not a takeover.
5. **Shimmer bloom / VHS button — REUSE `BlitFeedback` effect.** A light
   feedback bloom on the 0:40–0:50 "crystal staircase" shimmer, and a one-frame
   loop-glitch/melt on the 1:25 button before blackout — reinforcing the
   "unresolved, still-on-hold" ending. Effect layer over the hero, low amount.

Deprioritized / avoid: full-surface screensavers (Mountains/Mystify/Satori/
Pipes3D) — they belong to `bliss` and would blow the deadpan restraint this song
depends on. `Rip`/`Zot` are too aggressive for a hold screen. Keep the whole
song sparse and grid-locked.
