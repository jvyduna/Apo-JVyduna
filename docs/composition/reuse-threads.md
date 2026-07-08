# reuse-threads.md — cross-song cohesion for "Communicating"

> Companion to `00-overview-feedback.md` and `00-overview-critique.md`. Six songs
> shipped as six `.lxp` projects will read as six demos unless cohesion is
> *engineered*. This file lists concrete threads where a **single class, effect,
> palette, or motif recurs across multiple songs in varied form** so the
> recurrence reads as intentional (a returning character) rather than repetition
> (a reused asset). Every thread below names the **actual classes** now in the
> tree and **how each is varied per song**.
>
> **Inventory (as built).** The song heroes are now authored, one package per
> song:
> - `util/SymbolGlyphs` — NEW shared symbol asset (9 glyphs, 11×11, `Symbol` enum
>   `CROSS·EYE·SUN·FISH·CRESCENT·OM·ANKH·STAR·TREE`, with `render(...)` and a
>   `morph(from,to,t,...)` cross-dissolve). Zero-alloc, `main()` previews as ASCII.
> - `patterns/temper/Aumakua` — genesis: ochre cave-painting figures rising in
>   family trees, blooming with the trombone.
> - `patterns/rafters/GlassRain` — window-rain hero; **imports `SymbolGlyphs`**
>   (symbols condense out of the droplets) and has a native 1/16 moiré.
> - `patterns/opus/HoldClock` — deadpan hold-screen clock; `shimmer` and
>   `freeze` knobs stage the two committed opus beats.
> - `patterns/chrome/Cathedral` — Wolfram-CA gothic cathedral (bend/heal/codex
>   TearDown built in); `patterns/chrome/Helix` — the 4:05 lightning→ascension.
> - `patterns/distance/PulseOrb` — breathing sanctuary orb + coupled domain-warp
>   walls; declares itself the source field for `BlitFeedback`.
> - Existing kit under `patterns/bliss/`: Mountains, Mystify, Outrun, Pipes3D,
>   Lorre, Rubik, Satori, Terraform, UnknownPleasures, Zot, Text, MirPitchGrid.
> - Effects: `effects/BlitFeedback`, `effects/bliss/Rip`. Utils: `SymbolGlyphs`,
>   `PixelFont5`, `SurfaceCanvas`, `TempoLock`, `AudioReactive`, `PerceptualHue`.
>
> **Reading each thread:** *songs* · *class/effect* · *how it's varied so it reads
> as recurrence, not repetition.*

---

## The three strongest threads (build these first)

### Thread 1 ★ — `util/SymbolGlyphs`: the mutating symbol character (a BUILD item, the theme's spine)

**Songs:** rafters → chrome → distance (seeded by temper's words).
**Class:** `util/SymbolGlyphs` — the one shared glyph table
(`Symbol.{CROSS,EYE,SUN,FISH,CRESCENT,OM,ANKH,STAR,TREE}`), with `render()` for a
single glyph and `morph(from, to, t, ...)` for the cross-dissolve between two.
Authored once, mutated across three songs.

This is the **theme's spine** — "incorporates many religious symbols… religion
across a lifespan." Per the critique, it is **NOT "near-free":** the asset had to
be authored (done) *and* wired into three heroes, two of which are themselves
new. Treat the wiring as a scheduled build ticket, not a taste note. **Status:
`SymbolGlyphs` exists and `GlassRain` already calls it; `Cathedral` and
`PulseOrb` reference it in prose but do NOT yet import it — those two hookups are
the outstanding work.** The same glyph shapes must appear in four different
*material states*, tracking each song's beat:

- **temper (birth) — seed, pre-symbolic.** Not the glyphs yet: the *words*
  HEAL / BIRTH / HOPE via `bliss/Text` + `BlitFeedback`, ghosted through fog on
  the exterior while `temper/Aumakua` rises inside. Establishes "text/symbol on
  the outer wall = the sacred speaking." Language before iconography.
- **rafters (traveling away) — wet condensate. [WIRED]** `rafters/GlassRain`
  already imports `SymbolGlyphs` and drives `SymbolGlyphs.morph(...)`: cross /
  eye / sun / fish **condense half-formed out of the droplets** and morph/dissolve
  as they streak down. The symbols are *questions*, unstable, seen through rain —
  the protagonist first noticing the iconography of the faith they're leaving.
  (CURATE note in `GlassRain` flags the symbol-formation timing as hardware-tune.)
- **chrome (accepting) — carved codex. [BUILD: wire SymbolGlyphs into
  `chrome/Cathedral`]** The *same glyphs* return, but rendered as **hard-edged
  occult tracery inside the Wolfram-CA arches**, and torn down by the Cathedral's
  codex `TearDown` (rotating vertical slices toward their page-edges, "as if a book
  being closed" — already built in `Cathedral`). Feed `SymbolGlyphs` glyphs into
  the arch window tracery so the shapes are *architecture* now: solid, sacred,
  examined, dismantled. Wet/questioning → carved/structural.
- **distance (reconstructing) — settled benediction. [BUILD: wire into
  `distance/PulseOrb` or a thin Text layer]** The thread *resolves* to a **single
  settled word/glyph — AMEN or HOME (or the `TREE` of life)** — on the outer wall
  near the 2:57 apex, mirroring temper's exterior words as a bookend. The mutating
  symbol comes to rest. (Brief flags "cut if too literal"; within this thread it
  becomes the *payoff* of a 15-minute transformation, so keep it — but only once.)

**Why it reads as intentional:** identical glyph shapes, four material states
(fog-word → wet condensate → carved codex → settled benediction), tracking the
narrative beat of each song. The audience won't clock "same bitmap" but will feel
the cross/eye/sun return as a character they've met.

---

### Thread 2 ★ — `effects/BlitFeedback`: the recurring "dissolve" texture, four temperatures

**Songs:** temper → rafters → chrome → distance.
**Effect:** `effects/BlitFeedback` (frame-buffer feedback) — the single most
reusable connective tissue in the kit, present in *four* of six songs. The job is
to make its four uses read as **one evolving idea (memory / the past bleeding
into the present)** rather than "the glitch effect again." Variation axis =
**emotional temperature and gain**:

- **temper — gauze (warm, slow, low gain).** Layered over `temper/Aumakua`'s
  rising figures for the 3:18→3:41 dissolve into bliss; light smear sells the
  gauzy trombone-loop crumble. Feedback = *tender memory*, barely moving.
- **rafters — funhouse mirror (cool, unstable).** Over `rafters/GlassRain`:
  ramps at the 1:22 maj7 bloom, peaks ~2:03, then a *destabilizing warp* in the
  brooding coda ("you don't know what is real"). Feedback = *doubt / distorted
  perception*.
- **chrome — glitch that heals (sharp, gated).** Over `chrome/Cathedral`, whose
  bend/heal is already internal (dissonance hue-shift + tear-shear that resolves,
  the 1:43 HEAL callback). BlitFeedback adds the "frame-buffer overflow glitch" at
  2:57 and the 3:23 melt — but it must visibly **resolve** to clean, because "the
  healing of the resolutions" is the song's thesis. Feedback = *dissonance*; its
  settling = *absolution*.
- **distance — K-hole fold (warm, slow, deep).** `distance/PulseOrb` **declares
  itself the intended source field**: layer a slow warm feedback zoom/rotate over
  the orb + domain-warp walls for the Inception/Interstellar "worlds folding into
  themselves" look. Warm-biased, low gain — reads as *drift*, not glitch. Feedback
  = *the reconstructed world breathing*.

**Why it reads as intentional:** same effect, its character arc mirroring the
protagonist's — tender memory → distorted doubt → dissonance-that-heals → serene
fold. Keep it **absent from bliss and opus** (certainty and limbo have no "past
bleeding in") to sharpen the four uses by contrast.

---

### Thread 3 ★ — Lightning / vertical ascension: `Zot` echoing into `Helix`

**Songs:** bliss → chrome (with a vertical-motion lineage through all four heroes).
**Classes:** `bliss/Zot` (existing lightning) → `chrome/Helix` (the 4:05 finale).

`Helix`'s own doc is explicit: *"a lightning strike to earth (echoing the Zot
vocabulary) opening into a white double-helix ascension."* That makes the strike
motif a literal, buildable callback — seed it in bliss so 4:05 reads as *arrival
of something foreshadowed*:

- **bliss — the seed (kinetic, secular).** `bliss/Zot` lightning as pure
  tech-energy punctuation on the biggest hits (0:25 crash 0.86, the 2:48 Drop 2
  slam). Here it's *fun* — an 80s-poster bolt, no meaning. (Zot is on temper's
  "avoid" list precisely because it's bliss's toy — good; that's the point.)
- **chrome — the payoff (sacred, transformed).** `chrome/Helix` opens on the
  *same* downward strike, then **inverts it into upward white helical ascension**
  up the cylinder (pulses riding the strands, unstranding separation, tightening
  twist, an XOR moiré). The secular bolt becomes the sacred axis-mundi at the
  summit. Same grammar (a vertical high-contrast electric strike), opposite
  direction and meaning.

**Vertical-motion sub-thread (looser, now concrete across the four heroes):**
`temper/Aumakua` figures *rise* (birth) → `rafters/GlassRain` droplets fall
*down* (the inversion — gravity/departure) → `chrome/Helix` *ascends*
(acceptance) → `distance/PulseOrb` *breathes* radially (home/rest). The axis of
motion itself tells the story: rise → fall → ascend → rest. Worth choreographing
consciously even though it spans four different classes.

**Why it reads as intentional:** the bolt is distinctive enough that its return
at the piece's single most important cue (4:05) lands as "oh — *that*,
transformed." The down→up inversion is the whole arc in one gesture.

---

## Supporting threads (cohesion multipliers — schedule them, don't assume them)

> Per critique #3, these are NOT "near-free." They reuse built classes, but the
> *variation still has to be authored per project*, and cohesion you don't
> schedule is cohesion you don't get. Treat each as a small choreography ticket.

### Thread 4 — `bliss/Text` as the recurring "voice" (words on the outer wall)

**Songs:** temper → opus → chrome → distance.
**Class:** `bliss/Text` (5px `PixelFont5`, exterior-view support) — plus
`opus/HoldClock`, which rasterizes its own `message` text. The piece's literal
narrator; the *register* tracks the arc:

- **temper:** HEAL / BIRTH / HOPE — sincere, sacred, exterior only (the world
  speaking to the newborn), over `Aumakua`.
- **opus:** YOUR CALL IS IMPORTANT TO US / PLEASE CONTINUE TO HOLD / **GOD PUT
  ME ON HOLD** — the *same voice gone bureaucratic.* Per critique #9 the punchline
  is **not a scroll**: `HoldClock.freeze` latches a single HELD hard-cut frame of
  the four words at ~1:25, then the composition cuts to black — the doorway into
  chrome. The sacred word corrupted into corporate boilerplate is funnier
  *because* temper established the sincere register 7 minutes earlier.
- **chrome:** HEAL returns at 1:43 — `chrome/Cathedral` has the HEAL/resolution
  callback built into its heal cycle; render the word on the completing arches.
  The bureaucratic detour is over; the word means it again.
- **distance:** AMEN / HOME — the final word at rest (also Thread 1's resolution).

**Variation:** same font, same exterior placement; tone travels sincere → absurd
→ sincere-again → resting. HEAL in both temper and chrome is a deliberate rhyme.

### Thread 5 — Morphing "ground" / topology: `Terraform` → `Cathedral` → `PulseOrb`

**Songs:** bliss → chrome → distance.
**Classes:** `bliss/Terraform` (reused height-field) → `chrome/Cathedral`
(grown arches) → `distance/PulseOrb`'s coupled domain-warp wall field.

The *ground under the protagonist* changes character as their faith does. This
started as one Terraform engine; it's now carried by the dedicated heroes, but
it's still one motif — a wall topology that grows/morphs:

- **bliss — toy.** `Terraform` as a near-static "80s screensaver topograph" wash
  under the 0:00–0:24 pad intro — playful, saturated, low brightness.
- **chrome — cathedral.** The grown structure is now `Cathedral`'s own
  envelope-driven arcade rising from the floor toward the 4:05 nave (Wolfram-CA
  tracery, not a clean grid — the uncanny hero mode). Ground → sacred architecture.
- **distance — home.** `PulseOrb`'s *second, slower* system: a morphing
  domain-warp wall field (warm reds/roses/teals) drifting across the four outer
  walls as "the world's light moving across the wall." Ground → sanctuary.

**Variation:** one "the-ground-morphs-with-faith" idea, three engines and three
palettes: toy Terraform → grown Cathedral → warm folding walls. (`Terraform` can
still under-layer chrome/distance if a hero slips.)

### Thread 6 — `bliss/Mystify` as the recurring "drift," warm → cool

**Songs:** temper → bliss (→ rafters).
**Class:** `bliss/Mystify` (polygon line-web drift).

- **temper:** slow pastel companion-drone layer beside `Aumakua` — "the gentle
  whirling melody circling above a drone" almost verbatim. Warm, near-still.
  Mystify as *breath*.
- **bliss:** the breakdown "float" look (0:48–1:12, 2:00–2:29) — decelerated,
  cooled mint/sky, the "hold your breath" between drops. Mystify as *suspension*.
- **rafters (optional):** the cold nervous **moiré** of the coda now lives
  *natively in `GlassRain`* (its built-in 1/16 shimmer), so Mystify is redundant
  there — reserve it for temper/bliss unless the GlassRain moiré needs a partner.

**Variation:** same drift primitive, pastel-slow (breath) → cool-suspended (held
breath). A quiet barometer of the protagonist's calm as certainty erodes.

### Thread 7 — `effects/bliss/Rip`: the percussive "tear," bliss bleeding into rafters

**Songs:** bliss → rafters (one deliberate echo).
**Effect:** `effects/bliss/Rip` (FFT/trigger negative-space tears).

- **bliss:** the signature — rimshot rips fire continuously (0:25–0:40 crash
  burst, 2:03–2:16 stabs, through the 2:29–2:43 snare-roll build). High density,
  saturated. Rip *is* bliss's percussion made visible.
- **rafters:** exactly **one** decisive Rip over `GlassRain` on the 2:16
  vocal-chop cluster (the full→beats seam) — the Kendrick-style hard cut. Bliss's
  playful tearing returns *once*, now violent and singular, as the departure's
  rupture.

**Variation:** many playful tears (certainty) → one violent tear (the break). The
scarcity gives the single Rip its weight — "bliss's language, but wrong now."

### Thread 8 — `bliss/Rubik` as the "certainty," and its crack (the bliss→rafters hinge)

**Songs:** bliss (and the transition out of it). **Class:** `bliss/Rubik`.

Rubik is bliss's purest "naive certainty" image — a puzzle that *always solves*,
on-beat, in the six-color scheme. Use it as the vehicle for the
**certainty→doubt hinge** (overview Gap A / critique #8): as bliss fades
(3:35–4:06 vocal-chop outro), let the final solve **leave one face unsolved / one
square the wrong color.** That unresolved tile is the crack `rafters/GlassRain`
then grows its doubt out of. A scheduled build item on the bliss `.lxp` timeline,
not overview prose — it is the arc's inciting incident made visible.

### Thread 9 — The cool→warm palette journey (the color narrative)

**Songs:** all six. **Asset:** the named-triad family + `PerceptualHue`, expressed
through each hero's warmth control.

The arc's color spine: warm-soft pastel (`Aumakua`, birth) → saturated
CMY/Rubik primary (bliss, certainty) → cold slate-blue (`GlassRain`'s Bloom
envelope resting cold, doubt) → drained office-phosphor (`HoldClock`, limbo) →
cool body blooming to warm-gold (`Cathedral.Warmth` + `Helix` white/gold at 4:05,
acceptance) → warm amber-rose (`PulseOrb`, home). The **gold at chrome's finale
and the amber of distance are close relatives** — that kinship makes "acceptance"
and "reconstruction" read as one homecoming. Discipline to hold: bliss's cold
"twinkle starfield" and distance's warm one (`PulseOrb`) must be **opposite
temperatures of the same motif** — the bookend depends on it.

---

## Cohesion at a glance

| Thread | Songs | Class / effect | Variation axis |
|--------|-------|----------------|----------------|
| 1 ★ Symbol glyphs (BUILD) | rafters·chrome·distance (temper seed) | `util/SymbolGlyphs` (→ GlassRain[wired], Cathedral·PulseOrb[to wire]) | material state: word→wet→carved→settled |
| 2 ★ BlitFeedback | temper·rafters·chrome·distance | `effects/BlitFeedback` over Aumakua·GlassRain·Cathedral·PulseOrb | temperature: gauze→funhouse→heal→fold |
| 3 ★ Lightning/ascension | bliss·chrome | `bliss/Zot` → `chrome/Helix` | direction: strike-down→ascend-up |
| 4 Text voice | temper·opus·chrome·distance | `bliss/Text` + `opus/HoldClock` | tone: sincere→absurd→sincere→rest |
| 5 Morphing ground | bliss·chrome·distance | `Terraform`→`Cathedral`→`PulseOrb` walls | role: toy→cathedral→home |
| 6 Mystify drift | temper·bliss | `bliss/Mystify` | tension: breath→suspend |
| 7 Rip tear | bliss·rafters | `effects/bliss/Rip` | density: many→one violent |
| 8 Rubik certainty | bliss (→rafters hinge) | `bliss/Rubik` | solved→one-face-cracked |
| 9 Palette journey | all six | named triads + hero warmth knobs | cool→warm homecoming |

**Build priority:** threads 1, 2, 3 are load-bearing (theme spine, connective
texture, summit payoff). **Thread 1 is a real build ticket, not a taste note:**
`SymbolGlyphs` is authored and wired into `GlassRain`, but `Cathedral` (carved
codex) and `PulseOrb`/distance (settled word) still need the hookup — schedule
them or the theme collapses to 3–4 scattered symbol moments (critique R2/#3).
Threads 4–9 reuse built classes but their *per-song variation must still be
authored in each `.lxp`* — cohesion you don't schedule is cohesion you don't get.
Thread 9 is a constraint, not a build.
