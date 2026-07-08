# 00 — Adversarial completeness critique: "Communicating"

> A hostile read of the six briefs + the overview + reuse-threads, hunting for
> what is UNDER-SERVED, UNBUILT, or OVERCLAIMED. The overview's own risk list
> (R1 sag · R2 symbols · R3 hinges · R4 six-demos · R5 scope) is good but
> *generous to itself*: it rates the arc ✅ while resting that ✅ on work that
> isn't built and meaning the audience can't see. This doc attacks the seams the
> overview smooths over. Ranked by severity × likelihood-of-actually-going-wrong.

---

## The 10 gaps, ranked

### 1. The opener under-serves the birth. The hero is three fading words; the climax has no bloom.

temper is the genesis — the overview calls its interior/exterior womb split "the
single best structural idea in the whole piece" and says the whole 21 minutes is
framed from here. Then the pattern plan hands the entire 3:45 opener to
**"Luminous Words" (Text + BlitFeedback)** — HEAL / BIRTH / HOPE ghosting in fog
— and demotes the one idea that actually *is* the song ("Aumākua" cave-painting
family tree: childbirth, ancestry, ʻaumākua — literally Vandever's stated meaning)
to *"heavier build, do it after the hero proves the palette,"* i.e. the thing most
likely to get cut for scope. Two problems compound:

- **The birth-bloom climax at 2:32 (energy 0.78, the emotional peak of the opener)
  has no visual that can bloom.** The brief's own language is a hand-wave:
  "whatever hero pattern is doing gets its brightest." Text getting brighter is
  not a birth. Fog getting brighter is not a bloom. The single most important
  moment in the establishing song is unassigned.
- **The hero carries neither "childbirth" nor "ancestry"** — the actual emotional
  core of the song — only "sacred words." That's an emotional-intent vs. visual
  mismatch at the arc's foundation.

**Fix:** Promote a *rising-figures / rising-light-strands* system (the Aumākua
family tree, or at minimum an upward-drifting generative bloom driven by the
trombone envelope) to **co-hero**, and demote Text to the exterior-accent role it's
genuinely good at. The climb (D6 at 1:09, G5 at 2:32) *is* the language — something
must physically rise and bloom at 2:32. "Ship the words first" is low-risk /
low-ceiling sold as "high narrative payoff." The ceiling is the problem.

### 2. "Accepting" reads as "pretty cathedral," not as *return to the father's faith*. The arc's meaning is under-visualized.

chrome is the emotional summit AND the semantic keystone: "accepting my father's
*strict* religion," message "*Beauty in the Occult*." But nothing in the visual
plan communicates **acceptance-as-return** — a clean cathedral growing and
flooding with light at 4:05 reads as generic transcendence, not as *reconciliation
with a specific inherited faith the protagonist left*. Two failures:

- **The "return" is invisible.** Acceptance only means something against what was
  rejected (rafters' doubt). The only mechanisms that would make chrome read as
  *homecoming* rather than *new pretty song* are the cohesion callbacks — HEAL
  returning from temper, the symbol glyphs returning carved — and those are
  filed under "near-free, choose consciously" (i.e. unbuilt, easily skipped).
  The back half's entire thesis rests on callbacks nobody has committed to build.
- **The occult is under-served.** "Beauty in the Occult" is the stated *message*,
  and the brief itself warns against "literal religious kitsch." Yet the hero is a
  clean growing Cathedral (Terraform arches) — the kitsch — while the genuinely
  occult/uncanny idea (Wolfram-CA cathedral as a codex torn down by rotating
  slices 90°) is priority #2, "the most net-new work," i.e. the cut candidate.
  The song's message lives in the pattern most likely not to ship.

**Fix:** Make the uncanny/Wolfram treatment the hero's *default rendering mode*,
not a secondary pattern — the cathedral should be visibly generated-wrong from the
start (that IS the "twisted, almost-corny" Lopatin hinge). And commit to at least
ONE concrete return-callback as a build item (HEAL reappearing at 1:43 is the
cheapest), not a "choose consciously" aspiration.

### 3. Cohesion is asserted, not engineered. "Near-free" is the overclaim that sinks R4.

The overview's R4 ("six .lxp projects risk reading as six demos") is real, and the
entire mitigation is reuse-threads.md. But that doc rates threads 4–9 **"near-free
— the only work is choosing the variation consciously."** That is precisely the
kind of work that silently never happens across six separate projects built at
different times in a beta host. Worse:

- **Thread 1 (the religious-symbol glyph set) is the theme's literal spine** —
  "incorporates *many* religious symbols" is the stated theme — and it is NOT
  near-free: it requires authoring a new glyph asset AND wiring it into three
  songs, two of which (GlassRain, Cathedral) are themselves unbuilt heroes. If any
  of that slips, the stated theme collapses to the **3–4 scattered symbol moments
  the overview already flags as R2**, with no evolution between them.
- So the two things holding the piece together (the symbol thread and the palette
  journey) are the *least* certain to ship, because they're either labeled free or
  buried inside the heaviest new code.

**Fix:** Build the shared **glyph/Text/PixelFont5 symbol module as infrastructure
FIRST**, before any hero pattern — a single asset every song imports. Treat the
cohesion threads as first-class build tickets with owners, not as a taste note.
Cohesion you don't schedule is cohesion you don't get.

### 4. The sag's designated rescue is the most under-resourced beat in the piece.

The overview stakes the mid-piece sag (R1, ~10:30–12:15) on ONE moment: opus's
**0:40–0:50 crystal-staircase shimmer** — "commit to that as a real, brief,
smile-inducing motion spike… that one bump is what keeps the sag from becoming a
stall." Then opus's pattern plan gives that exact moment the weakest treatment in
the brief: **"MirPitchGrid, subtle, keep brightness low, seasoning under the clock,
not a takeover"** + a light BlitFeedback bloom. A pitch-grid light-organ is not
"a prince dancing up a crystal staircase, head flung back in bliss" — it's the
literal opposite of that image, and MirPitchGrid was explicitly rejected elsewhere
(temper's brief) as reading like a cold light-organ. The single most load-bearing
anti-sag beat is assigned the song's most tentative, lowest-brightness visual.

**Fix:** Give the shimmer a genuine, brief, *fluid* motion look (an ascending
sweep / rising cascade — a staircase of light) that is allowed to be the brightest
thing in the whole song for those 10 seconds, then snap back to deadpan. It is the
one place opus is permitted to be beautiful; the plan currently forbids it.

### 5. The piece peaks at minute ~16 and then runs ~6 minutes of downhill on its thinnest song.

The overview celebrates "ending in darkness" and waves off the chrome→distance
joint as "landing, not sagging." Adversarially: the whole-piece high-water mark is
**chrome 4:05 (≈16:24 set-time)**, after which you have chrome's fade + **all 5:24
of distance** = roughly **6 minutes of continuous decrescendo** to close a 21-minute
piece — and distance is the set's **longest** song, its **thinnest-sourced**, and
carries a documented **monotony** knock. Two blacks bump at the joint (chrome fades
to black 4:57, distance opens near-black). The risk isn't a "sag" in the middle
sense — it's that **the last 27% of the runtime is denouement**, and denouement is
where long-form pieces lose the room a *second* way: not with a jolt but with a
drift. This is a real second soft spot the overview doesn't rank.

Compounding it, distance's own hero is internally contradicted: the brief says
**"PulseOrb alone: the piece works with this alone,"** while its own anti-monotony
fix requires *two coupled systems* (orb + morphing walls). A breathing orb for
5:24 with the walls cut for scope IS the monotony failure mode, stated as the
fallback.

**Fix:** Guarantee distance's second system (wall topology morph, or the settling
symbol thread) as non-optional — it's the anti-monotony insurance, not a nicety.
Consider shortening the distance edit, or giving it *one* late, quiet
re-escalation (the 4:04 snare bloom) real visual weight so the tail has a heartbeat,
not just a fade. Don't let "works with the orb alone" become the shipping decision.

### 6. rafters — the pivotal first-doubt song — is a single point of failure.

The entire "certainty → doubt" turn (the arc's inciting incident, per R3) lands in
rafters, and rafters rests **entirely on one brand-new unbuilt hero, GlassRain**,
which is simultaneously asked to do rain physics + morphing religious symbols
(thread 1's introduction) + carry all-arc dynamics + a palette bloom — a lot for a
first-pass new class. Its only backup (Life/CA) is flagged with its own risk:
"can look like noise on LEDs." So the piece's most important narrative pivot has
one unproven hero and a legibility-risky spare.

**Fix:** De-risk by splitting GlassRain's jobs — let the symbol-condensation ride
the shared glyph module (gap #3) so the hero only owns rain + dynamics. Prototype
the CA backup's legibility *early* so it's a real fallback, not a hope.

### 7. Build scope is under-counted. R5 says "four new heroes"; it's closer to eight to ten.

Actual net-new classes across the briefs: Aumākua (temper), GlassRain + Life
(rafters), HoldClock (opus), Cathedral + Wolfram + Helix (chrome), PulseOrb
(distance), the shared religious-glyph set, plus a possible Drip (bliss outro).
That's **~8–10 net-new classes, most of them the sole HERO for their song** — a
single point of failure per project — for one artist who is *also* dogfooding a
beta host. The overview's "four brand-new hero classes" softens this by roughly
half. The realistic worst case isn't "chrome's finale under-delivers" (R5's
framing); it's that **most heroes ship as stubs, the near-free cohesion never gets
wired, and you get six demos with three fading-word moments** — every risk firing
at once because they share one root cause: too much unbuilt for the time.

**Fix:** Ruthlessly rank heroes by "song fails without it," build those to
completion, and let the rest degrade to reused existing patterns (bliss's kit is
already built and can cover more ground than the briefs admit).

### 8. bliss and temper are the *same* beat; the arc is front-loaded and the turn gets squeezed.

The overview flags this (Gap A) but still rates the arc ✅. Adversarially: songs 1
and 2 are both "birthed into," songs 3 and 4 are both "traveling away," and
"accepting" + "reconstructing" get one song each. That means the two turns the
whole thesis depends on (certainty→doubt, doubt→acceptance) each happen *inside a
transition* rather than getting a song — and the certainty→doubt turn in particular
is currently an *unbuilt* choreography note (the cracked Rubik face, thread 8). The
arc "reads" only if two gap-transitions get authored that currently don't exist.

**Fix:** Commit the Rubik-crack (bliss outro) and the opus-button-as-doorway
(into chrome) as actual build items on the timeline, not overview prose. They are
the arc's two hinges; right now they live in a markdown file, not in a .lxp.

### 9. opus's existential punchline is delivered by a scrolling marquee.

"GOD PUT ME ON HOLD" — the line the overview correctly calls the *doorway into the
cathedral*, the thing that converts the joke into the summit's question — is
carried by `Text` scrolling corporate boilerplate. That's underpowered for a hinge
into the most sacred five minutes of the piece. The chill should be *staged*, not
read off a marquee.

**Fix:** Make the punchline a single, held, hard-cut frame (not a scroll) on the
1:25 button — freeze the whole hold-screen on those four words for a beat longer
than comfortable, then black. Let the *timing* deliver the existential turn, per
the overview's own "hold the blackout a half-beat longer" note.

### 10. Small overclaims worth not believing.

- **distance's "Zot detuned to soft point-twinkles."** Zot is *lightning* and is
  thread 3's aggressive strike vocabulary (bliss/chrome). Reusing it soft in
  distance muddies that thread and is an unsupported convenience — a 30-line
  sparkle layer is cleaner and doesn't dilute the strike motif.
- **temper's "Low build risk, high narrative payoff, ship first."** Low risk yes;
  high payoff no — see gap #1. Don't let the low-risk framing make it the default.
- **"Empty space is the content" (chrome, distance).** True, but it's also the
  cheapest thing to claim and the easiest to hide an under-built song behind.
  Empty space earns its keep only if what surrounds it is fully realized; two
  songs leaning on it back-to-back (chrome valley + distance intro) is a lot of
  faith in restraint.

---

## The single root cause

Eight of these ten trace to one thing: **the arc's meaning and cohesion live in
prose (this overview, reuse-threads) and in Jeff's private intent, not yet in the
compositions.** The spine is genuinely strong on paper. The danger is that the
strongest, most theme-carrying ideas (Aumākua birth, the occult Wolfram cathedral,
the mutating symbol thread, the two hinges) are uniformly the *heaviest to build*
and the *first to be cut*, while the lightest-to-build ideas (words in fog, a
clock, a breathing orb) are load-bearing. Build the theme-carriers first as shared
infrastructure, or the piece regresses to its safe, thin defaults.
