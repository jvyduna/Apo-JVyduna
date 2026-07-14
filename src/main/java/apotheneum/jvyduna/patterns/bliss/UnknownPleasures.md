# Unknown Pleasures

CP 1919 pulsar ridgeline waterfall: stacked, occluding spectrum silhouettes scrolling like the 1979 Joy Division "Unknown Pleasures" album cover.

## Original / inspiration

Peter Saville's cover for Joy Division's *Unknown Pleasures* (1979), itself a
stacked-ridgeline plot of successive radio pulses from pulsar CP 1919 (Harold
Craft's PhD data). The signature look: thin white ridgelines on true black,
each line's silhouette blacking out the lines behind it, peaks concentrated in
the center with the edges pinned flat to the baseline — and crucially, tall
peaks freely overlapping the ground and peak levels of the lines behind them.
Line profiles are synthesized CP 1919-style pulses (or a 3D noise field in
Simplex mode); the Audio knob live-mixes the spectrum into every displayed
line. Strokes are always white — colorize with an external effect or a color
multiply channel.

## Rendering approach

- Base class: `ApotheneumPattern` (needs the cylinder, so no raster base).
- **Slot rasters.** Up to four 50×45 fields (`cubeRaster[4]`), one per cube
  wall; in Dup mode only slot 0 is painted and all walls share it. Each
  exterior face is blitted from its slot (`blitFace`), then one
  `copyCubeExterior()` arraycopy mirrors exterior → interior per-face. The
  cylinder is divided into `CylWavs` equal segments (120 divisible by 1–4);
  each segment resamples one slot raster to its arc. The blit is rotated
  `CYL_ROTATION = 15` columns (45°) so the center of a flat inter-waveform
  interface faces a cube corner (`CURATE:` verify against the physical bearing
  of cylinder column 0; adjust sign/offset). Helix mode with CylWavs ≠ 4
  paints separate cylinder rasters (`cylHelixRaster`) because segment phase
  lags depend on the segment count.
- **Everything is functional/live — nothing is baked into a buffer.** Line
  profiles are recomputed *every frame* by `computeShape(index, slot, synth)`
  from a deterministic per-line seed (`lineSeed = hash(k, reseedSalt)`) and the
  *current* parameter values. So Simplex scale/turbulence, Jag, Amp, Spacing,
  and WavMode all re-shape every already-visible line the instant you move
  them; no reseed needed. The only per-line stored state is pulse metadata
  (`ringKey`/`ringGain`/`ringSynth`, a small ring keyed by birth index `k`) so
  a Pulse can be consumed by the right line; Reseed just bumps `reseedSalt`.
  The synthesized path reseeds a reused `Random` via `setSeed` (no allocation)
  so a line's Gaussians and jag walk are stable frame-to-frame yet respond to
  live Jag.
- **True antialiased curve rasterizer.** `drawCurve` connects the per-column
  samples: each column draws the contour over the vertical span between its
  neighbor-midpoints (`yL = ½(yc[x-1]+yc[x])`, `yR = ½(yc[x]+yc[x+1])`, plus
  the sample itself so peaks/valleys keep their tip). Because column *x*'s
  right edge equals column *x+1*'s left edge, consecutive columns share an
  endpoint row — the ridge never breaks apart, even at high amplitude or high
  Jag where adjacent samples are far apart (steep segments render as bright
  vertical connectors). A near-flat column falls back to the classic two-row
  fractional stroke. Below each contour the interior is filled black, and that
  fill occludes the lines behind it (painter's algorithm, back-to-front by
  baseline). `marginRows = Amp × Spacing × pulse gain` is the existence-window
  margin so ridges never pop in/out at the field edges.
- Door columns: bounded by `column.points.length` in both blit loops.

## Amplitude (in units of Spacing)

Each line's signal is normalized to **[0, 1]** regardless of mode: Simplex is
`½ + ½·noise`, windowed; the synthesized pulse is down-scaled only (divided by
its own max when that exceeds 1) so a strong line peaks at 1 while weaker lines
stay shorter — peaks are "up to," not "always," full height. **Amp (range
0–2) then scales that peak in units of Spacing**: peak rows = `signal × Amp ×
Spacing` (× 1.8 for a Pulse line). Amp = 0 → flat lines in every mode; Amp = 1
→ a full peak reaches the baseline of the line above; Amp = 2 → a full peak
rises two rows-of-spacing tall, overlapping the two lines behind it. So the
overlap drama scales directly and predictably with Amp, independent of Spacing
and mode.

## Tempo mapping (division-locked scrolling)

TempoDiv **is** the speed. One line emerges from the entry edge on every
division; the scroll rate is `Spacing` rows per division period, so Spacing is
a pure zoom about the entry edge — changing it never disturbs emergence
timing. There is no Sync toggle and no retiming: positions are absolute.

- Division-time `U = tempo.getCycleCount(div) + tempo.getBasis(div)` is
  continuous through BPM changes. Discontinuities (division change, transport
  jump: `dU < 0` or `> 4`) are folded out by an **integer** shift (`kBase +=
  round(dU)`), which preserves fractional phase — emergence stays exactly
  on-division; lines nudge at most half a division at the fold.
- Line `k`'s baseline: down-scroll `(E − k) · spacing`, up-scroll
  `HEIGHT − (E − k) · spacing`, where `E = U − kBase`. The baseline crosses
  the entry edge exactly when `E` crosses `k`, i.e. on the division.
- Existence window: down-scroll `k ∈ [ceil(E − (45+44)/sp) − 1, floor(E) + 1]`
  (one future line for AA fade-in); up-scroll pre-births below the bottom by
  the full ridge margin so tall contours never pop in. The −1 slack covers
  helix phase lags; Shift mode generates shapes 3 indices ahead.
- **No speed cap by design.** The old 5 s traversal floor is retired; pacing
  is operator-owned (TempoDiv × Spacing × BPM). 1/16 at high BPM with wide
  spacing is intentionally extreme.

## WavMode / CylWavs (wall variation)

`WavMode` selects how the four walls relate; `CylWavs` (1–4) sets how many
distinct waveforms wrap the cylinder (segments use the first CylWavs slots;
every waveform is pinned flat at both ends by the window, so segment
interfaces are seamless flats).

- **Dup** — all walls identical (the original behavior). One raster painted.
- **Shift** — wall *s* shows the series shifted by *s* lines: adjacent walls
  replicate the waveform of the falling series one ahead/behind. A Pulse
  therefore appears on wall 3 first and reaches wall 0 three divisions later.
- **Simplex** — profiles are samples of an underlying 3D noise field
  (`LXUtils.noise` / `noiseFBM` — LX's stb-perlin port; CP 1919 synth shapes
  are discarded except for the explicit Pulse trigger, which spikes all walls
  at once). The four slots trace the four sides of a square in the field's
  X-Z plane — adjacent walls share corners, so the walls read as one volume
  wrapped by the architecture; the field advances in Y per line. Params:
  `NzScl` (X-Z scale), `NzTurb` (fBm octaves 1–4), both live — moving them
  re-shapes every visible line. Jag's synth-noise term does not apply here
  (turbulence covers roughness).
- **Helix** — all walls show the same series, each phase-lagged by ¼ division
  (segments by 1/CylWavs), so the stack descends as a helix wrapping the
  building; after four walls the lag totals one full line, so the winding is
  continuous around the corner.

WavMode and CylWavs changes take effect instantly — everything is recomputed
live per frame, so no ring invalidation is needed.

## Audio mapping (live per-frame mix)

The pattern owns its **own `GraphicMeter`** (registered via `startModulator`),
so the **Decay** knob can own the band release time (propagated to
`meter.release` each frame; the meter's parameter is parented to the meter and
can't be registered on the pattern).

**Meter source follows the engine mode.** The engine rebinds the *shared*
`lx.engine.audio.meter` to whichever buffer `lx.engine.audio.mode` selects —
`input`, `output`, or the **composition `timeline`** — and stops the others.
A pattern-owned meter does **not** get that for free: hardcoding
`lx.engine.audio.input.mix` reads a **stopped** buffer during composition
(timeline) or output-mode playback, so every band decays to 0 and Audio = 1
collapses to flat baselines *even while the global FFT is visibly pumping*.
So `render()` rebinds our meter each frame to `AudioReactive.activeSource(lx)`
(the mode-selected component), calling `setBuffer` only when the source
changes. (Fixed 2026-07-12; this was the "no audio reactivity during playback"
bug.)

- **Audio depth is a live mix into every displayed line, every frame**:
  `h(x) = lerp(shape[x], fftShape[x], depth)`. 0 = pure synthesized/simplex
  screensaver; 1 = 100% FFT — every line in the stack pulses with the music
  simultaneously (dramatic by design). There is no birth-time crossfade
  anymore.
- **Mirrored layout**: `fftShape` puts band 0 (bass) at the line center
  (columns 24/25) and replicates successively higher bands bidirectionally
  outward — band 15 at columns 0/49 — then applies the Hann^1.5 window, so
  the highest bands at the edges are nearly deadened and every line keeps the
  cover's center-heavy silhouette. Column map:
  `band(x) = round((|x − 24.5| − 0.5) × 15 / 24)`.
- **Jag doubles as bin smoothing**: `fftShape` is smoothed with
  `1 + round((1−jag)×5)` passes of a 3-tap [¼ ½ ¼] kernel. Jag = 1 → one pass:
  the 16 mirrored bins (32 half-bands across the line) are just barely
  smoothed, nearly stair-stepped; Jag = 0 → 6 passes, wide soft interpolation.
  (`CURATE:` pass-count range.) Jag keeps its baked-noise role for synth
  shapes.
- Cylinder segments inherit the mix implicitly (they resample the painted
  rasters).
- **Silence behavior**: with no audio the bands decay to 0, so at Audio = 1
  the field flattens to scrolling baselines. Intentional — no gating; the
  operator owns the knob. At Audio = 0 the pattern is a pure screensaver.

## Parameters

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| reseed | Reseed | Trigger | — | — | Clear and refill the whole stack |
| flip | Flip | Trigger | — | — | Reverse scroll direction |
| pulse | Pulse | Trigger | — | — | Inject one oversized synth pulse line at the next division |
| rndTrig | RndTrig | Trigger | — | — | Random trigger or parameter jump |
| spacing | Spacing | Compound | 2 | 2–16 | Rows between baselines = scroll rows per division (zoom) |
| amplitude | Amp | Compound | 1 | 0–2 | Peak height in units of Spacing: 0 = flat, 2 = peaks 2× spacing tall |
| jaggedness | Jag | Compound | 0.15 | 0–1 | Synth profile roughness (live); at full Audio, FFT bin smoothing amount |
| wavMode | WavMode | Enum | Dup | Dup/Shift/Simplex/Helix | How the four walls relate |
| cylWavs | CylWavs | Discrete | 4 | 1–4 | Distinct waveforms around the cylinder |
| nzScale | NzScl | Compound | 0.5 | 0.05–2 | Simplex: X-Z field scale |
| nzTurb | NzTurb | Compound | 0.3 | 0–1 | Simplex: turbulence (fBm octaves) |
| audio | Audio | Compound | 0 | 0–1 | Live FFT mix into all lines: 0 = screensaver, 1 = 100% FFT |
| decay | Decay | Compound | 100 ms | 0–1000 ms (exp 2) | Release of the pattern's own FFT analysis |
| tempoDiv | TempoDiv | Enum | QUARTER | Tempo.Division | Division lines emerge on (with Spacing, the speed) |

Removed in the 2026-07-06 rework: `energy` (speed is TempoDiv × Spacing now),
`tintHue`/`tintAmount` (colorize externally), `sync` (locking is structural).
Series RndTrig pass (2026-07-06): `meta` → `rndTrig` (label Meta → RndTrig),
moved up to 4th, immediately after the triggers.

## Triggers

Roster spans small → large: pulse (one new line), flip (behavior reversal),
reseed (full scene reset).

- `pulse` (small) — arms one 1.8× amplitude (`PULSE_GAIN`) synthesized line,
  consumed by the next entry-edge birth — i.e. inherently quantized to the
  division, no separate grid logic. In Simplex mode the spike appears on all
  walls at once; in Shift mode it enters on wall 3 and propagates. When
  scrolling up, the spike is born below the 44-row bottom margin and takes
  `44/spacing` divisions of scroll to enter view (`CURATE:` latency).
- `flip` (medium) — toggles direction; the field mirrors vertically at the
  flip instant (line at `b` jumps to `HEIGHT − b`) and scrolls the other way,
  still grid-exact. Instant-event semantics, consistent with the roster.
- `reseed` (large) — clears the ring; the next frame refills every grid-locked
  position with fresh shapes. Instant scene reset with unchanged timing.

## Jump candidates

| Param | Jump range | Status | Notes |
|---|---|---|---|
| spacing | [2, 8] | candidate | Density jump; kept below the new 16 max — a jump to 16 is a violent zoom |
| amplitude | [0.5, 2] | candidate | Calm ripples (peaks under 1 spacing) vs peaks overlapping two lines |
| jaggedness | [0, 0.5] | candidate | Above ~0.5 lines may read as static (`CURATE:`) |
| wavMode | full | candidate | Scene change (clears the ring) |
| cylWavs | full (1–4) | candidate | Cylinder-only re-segmentation, seamless |

## Simulation-principles compliance

- Pacing is tempo-derived: at the QUARTER default and 120 BPM, spacing 2 →
  4 rows/s → an 11.3 s full-field traversal (ambient-like); spacing and
  division scale it linearly. The old 5 s series cap is **retired by design**
  — extreme combos (1/16 × 16 × high BPM) are operator-owned choices.
- Contours render at full brightness on true black (maximum contrast); the
  two-row anti-aliased stroke is the only gradient, used for motion
  smoothness, not texture.
- Minimum spacing of 2 rows keeps adjacent ridgelines separate at LED-gap
  scale. Peak height is bounded and predictable (`signal ≤ 1` × Amp ≤ 2 ×
  Spacing), so a full peak overlaps at most the two lines behind it; a Pulse
  (×1.8) can momentarily dominate the field — that is the cover's drama.
- Event motion (pulse/reseed/flip/mode change) changes content instantly but
  every line then develops over its full multi-second traversal.

## Curation log

| Date | Change | Why |
|---|---|---|
| 2026-07-04 | Initial implementation | Wave 1 build (sidecar completed by coordinator; worker session ended at spend limit) |
| 2026-07-05 | Review + upgrade: added Audio depth knob (birth-time FFT crossfade), Sync/tempoDiv grid-locked births via TempoLock, reseed pop-in fix | Series-wide audio-depth/tempo-sync pass + bug hunt |
| 2026-07-05 | Adversarial review fix: `crossed()` evaluated every frame so the cycle-count gate never goes stale across a Sync-off interval | Match the documented behavior |
| 2026-07-06 | Curation rework: unclamped peaks (removed [0,1] shape clamp and `MAX_RIDGE_ROWS`; overlap via painter's fill); removed Tint/TintAmt/Energy/Sync; Spacing max 2→16; division-locked absolute positioning (TempoDiv is the speed, Spacing is zoom, ring-buffer line store replaces the pool, TempoLock dropped); new WavMode (Dup/Shift/Simplex/Helix) + CylWavs with 45° cylinder rotation; Audio reworked to live per-frame mirrored-FFT mix with pattern-owned GraphicMeter + Decay knob, Jag doubling as bin smoothing | User curation session: restore the cover's overlapping-peak look, make walls distinct, make audio dramatic and tempo the timebase |
| 2026-07-06 | Series RndTrig ordering: `meta` → `rndTrig` (label RndTrig), moved from last to 4th, immediately after the triggers; `.lxp` values on the old `meta` path are dropped on load | Series convention: TriggerBag meta trigger sits right after the other trigger params |
| 2026-07-08 | Curation follow-up: (1) true connected-curve rasterizer (`drawCurve` spans neighbor-midpoints per column) so ridges no longer break apart at high Amp/Jag; (2) fully functional/live shapes — profiles recomputed each frame from a salted per-line seed + current params instead of baked at birth, so Simplex/Jag/mode changes affect already-visible lines; ring now holds only pulse metadata; (3) Amp reframed to 0–2 in units of Spacing (signal normalized to [0,1]; peak = signal × Amp × Spacing), so Amp 0 = flat and Amp 2 = peaks two spacings tall in every mode | User: fix high-amplitude line breaks, make all controls live, and make amplitude a predictable spacing-relative overlap control |
| 2026-07-12 | Audio-source fix: the pattern-owned `GraphicMeter` was hardcoded to `lx.engine.audio.input.mix`, so it went silent during composition/timeline (and output-mode) playback while the shared meter kept pumping. `render()` now rebinds it each frame to `AudioReactive.activeSource(lx)` (the `lx.engine.audio.mode`-selected component), `setBuffer` only on change; added the shared `AudioReactive.activeSource` helper | User: no audio reactivity at Audio = 1 during composition playback |
