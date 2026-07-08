package apotheneum.jvyduna.patterns.rafters;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import apotheneum.jvyduna.util.SymbolGlyphs;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * GlassRain — window-rain on a loft/church window that carries the entire arc of
 * Lusine's "Rafters" (song 3 of <i>Communicating</i>, the "traveling away /
 * first doubt" beat). Droplets spawn at the top of the cube ring and the
 * cylinder and streak DOWN the glass, leaving wet vertical decay-trails, over a
 * cold desaturated G-minor palette. A single {@code Bloom} envelope (automated
 * across the .lxp timeline) warms the palette and deepens the trails for the
 * one lush maj7 bloom (1:22–2:20), then yanks it back to cold nervous streaks
 * and finally to a hollow near-black outro. At peaks a faint 1/16-grid moiré
 * shimmers over the rain, and — the theme's spine — abstracted pseudo-religious
 * symbols condense out of the droplets and morph between one another. The symbol
 * glyphs are NOT drawn here: they ride the shared {@link SymbolGlyphs} module so
 * the same asset recurs, mutated, across the whole piece.
 *
 * <p>Built out: the rain layer carries real droplet physics (per-drop TTL /
 * lifetime, gentle horizontal wobble, variable head brightness, variable
 * weight/width, and ADD-blended head accumulation where beads merge); the
 * symbol auto-condenses out of the rain on its own via an internal formation
 * envelope (the Form knob still overrides); and the moiré is a tempo-locked 2D
 * interference shimmer (grating phases advanced on the 1/16 grid from the engine
 * tempo's beat position). Remaining values that can only be judged on the LEDs are
 * marked with inline {@code CURATE:} notes. See GlassRain.md beside this file.
 *
 * <p>Candidate alternative layer (NOT built here): a cellular-automata
 * "life"/blinkfade field. Deferred until its LED legibility is prototyped
 * separately — CA can read as noise at this pitch (see the .md).
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("GlassRain")
@LXComponent.Description("Window-rain streaking down the glass over a cold G-minor palette, with a single warm bloom, 1/16 moire at peaks, and pseudo-religious symbols condensing out of the rain (rafters hero)")
public class GlassRain extends ApotheneumPattern {

  // ---- Structural maxima (all droplet state preallocated at these sizes) -------

  private static final int CUBE_DROPS = 160;
  private static final int CYL_DROPS = 100;

  // ---- Rain physics constants (CURATE: droplet feel is untuned on hardware) ----

  /** Droplet fall speed in canvas rows/sec at energy 0 → energy 1. A drop
   *  crossing the 45-row cube ring at FALL_MIN takes ~3.75 s, at FALL_MAX
   *  ~2.05 s — both event-like (a single streak), comfortably above the 1.5 s
   *  event-life minimum. CURATE: tune the wetness/weight feel. */
  private static final double FALL_MIN = 12.0;
  private static final double FALL_MAX = 22.0;

  /** Base droplet spawn rate (drops/sec across a surface) at energy 0 → 1,
   *  before the Density knob and audio scale it. CURATE. */
  private static final double RAIN_MIN_PER_SEC = 2.0;
  private static final double RAIN_MAX_PER_SEC = 14.0;

  /** Heavy-drop burst size (DropBurst trigger / bass hit). CURATE. */
  private static final int BURST_DROPS = 18;

  /** Max gentle horizontal wobble amplitude (columns) as a heavy bead slides
   *  down the glass; the per-drop amplitude is biased small (rand²). CURATE. */
  private static final double WOBBLE_MAX_COLS = 1.6;

  /** Fraction of a drop's TTL after which its head fades out (it evaporates /
   *  the bead runs dry), so a drop that dies mid-glass doesn't pop off. */
  private static final double LIFE_FADE_START = 0.75;

  /** Wet-trail brightness as a fraction of the head color; the head itself is
   *  drawn brighter and ADD-blended so merging beads accumulate. CURATE. */
  private static final double TRAIL_LEVEL = 0.5;

  /** A drop whose random weight exceeds this reads as a FAT bead — a 3-column
   *  streak with side beads instead of a single column. CURATE. */
  private static final double FAT_WEIGHT = 0.6;

  /** Streak trail half-life range (ms), mapped from the Streak knob and
   *  lengthened by Bloom for the "dreamscape" smear. CURATE. */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 120;
  private static final double TRAIL_HALF_LIFE_MAX_MS = 2500;

  /** Bloom multiplies the trail half-life up to this factor at full bloom
   *  (1:22–2:20 trail-smeared rain). CURATE. */
  private static final double BLOOM_TRAIL_STRETCH = 2.5;

  // ---- Symbol / bloom constants ------------------------------------------------

  /** Integer upscale for the 11px SymbolGlyphs when stamped on the glass.
   *  Scale 2 → 22 px tall on the 45-row cube / 43-row cylinder. CURATE. */
  private static final int SYMBOL_SCALE = 2;

  /** Seconds for a symbol to morph from the previous glyph to the current one
   *  when Condense fires or the Glyph knob changes. CURATE. */
  private static final double MORPH_SECONDS = 3.0;

  /** After a symbol condenses (Condense trigger or a Glyph change) the internal
   *  formation envelope holds fully-formed this long before dissolving back
   *  into the rain, so a symbol surfaces on its own with no operator input. The
   *  Form knob still overrides (effective form = max(knob, envelope)). CURATE. */
  private static final double AUTO_FORM_HOLD_SECONDS = 4.0;

  /** Symbol repeat spacing (columns) so a glyph appears once per cube face and
   *  a few times around the cylinder. CURATE: positions/count. */
  private static final int CUBE_FACE_W = 50;   // 4 faces around the 200-col ring
  private static final int CYL_SYMBOL_W = 40;  // 3 around the 120-col ring

  // ---- Moiré constants ---------------------------------------------------------

  /** Two nearby vertical grating frequencies (radians/column) whose product
   *  beats into horizontal moiré fringes; their relative phase is advanced on
   *  the 1/16 tempo grid so the fringes sweep in time. CURATE the beat pitch. */
  private static final double MOIRE_FREQ_A = 0.62;
  private static final double MOIRE_FREQ_B = 0.71;

  /** Vertical grating frequency (radians/row): the shimmer's vertical structure,
   *  its phase drifting on the grid so the field reads as a 2D interference
   *  shimmer, not static vertical bars. CURATE. */
  private static final double MOIRE_FREQ_V = 0.55;

  /** Moiré phase advance per 1/16 grid step (radians), horizontal and vertical
   *  respectively. Small so the shimmer drifts slowly (a full cycle over ~a
   *  bar), tempo-locked to the engine tempo's beat position. CURATE. */
  private static final double MOIRE_PHASE_H_PER_16 = 0.5;
  private static final double MOIRE_PHASE_V_PER_16 = 0.33;

  /** Peak moiré brightness as a fraction of a channel at Moire = 1. CURATE. */
  private static final double MOIRE_MAX_BRIGHT = 0.35;

  /** Sheet-wash sweep duration (seconds): a bright band rinses down the glass.
   *  Event-like; > 1.5 s life. CURATE. */
  private static final double WASH_SECONDS = 2.5;
  private static final int WASH_THICKNESS = 3;

  // ---- Cold/warm palette fallbacks (documented; used only on empty swatch) -----

  /** Cold slate-steel blue — the G-minor spine when the project swatch is empty.
   *  CURATE: picked blind. */
  private static final int COLD_FALLBACK = LXColor.hsb(212f, 55f, 62f);

  /** Warm cream-amber (#FFB38A family) — the maj7 bloom color when the swatch
   *  has no 3rd color. CURATE: picked blind. */
  private static final int WARM_FALLBACK = LXColor.hsb(24f, 42f, 100f);

  // ---- Composition helpers -----------------------------------------------------

  private final AudioReactive audio;
  private final Random random = new Random();

  // ---- Parameters (registration order: triggers, energy, pattern, audio —
  //      see GlassRain.md) --------------------------------------------------------

  public final TriggerParameter dropBurst =
    new TriggerParameter("DropBurst", this::dropBurst)
    .setDescription("Spatter a cluster of heavy droplets across both surfaces (small change; on a kick)");

  public final TriggerParameter condense =
    new TriggerParameter("Condense", this::condense)
    .setDescription("Begin condensing a new religious symbol out of the rain, morphing from the current one (medium change; on the 2:16 vocal cluster)");

  public final TriggerParameter wash =
    new TriggerParameter("SheetWash", this::sheetWash)
    .setDescription("Send a bright sheet of water washing down the glass, rinsing the field (large change; on a section change)");

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.3)
    .setDescription("Master energy: raises droplet density and fall speed toward the peak");

  public final CompoundParameter density =
    new CompoundParameter("Density", 0.4)
    .setDescription("Rain density: how many droplets are on the glass at once");

  public final CompoundParameter streak =
    new CompoundParameter("Streak", 0.5)
    .setDescription("Streak length: 0 = bare drops that fade instantly, 1 = long wet trails");

  public final CompoundParameter bloom =
    new CompoundParameter("Bloom", 0)
    .setDescription("Arc dynamics envelope (automate in the timeline): 0 = cold spine, 1 = warm maj7 bloom with deep trail-smear");

  public final EnumParameter<SymbolGlyphs.Symbol> glyph =
    new EnumParameter<SymbolGlyphs.Symbol>("Glyph", SymbolGlyphs.Symbol.CROSS)
    .setDescription("Which religious symbol is currently condensing on the glass; changing it morphs from the previous one");

  public final CompoundParameter form =
    new CompoundParameter("Form", 0)
    .setDescription("How fully the symbol has condensed out of the rain: 0 = pure rain, 1 = fully-formed glyph");

  public final CompoundParameter moire =
    new CompoundParameter("Moire", 0)
    .setDescription("1/16-grid moire interference shimmer over the rain (peaks only)");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 1.0)
    .setDescription("Motion blending + antialiasing: 0 = steppy/pixel-snapped/hard edges, 1 = smooth sub-pixel motion and antialiased forms");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  // ---- Rain fields (preallocated in the constructor) ---------------------------

  private final RainField cubeField =
    new RainField(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT, CUBE_DROPS);
  private final RainField cylField =
    new RainField(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT, CYL_DROPS);

  // ---- Palette change-cache (Satori idiom) -------------------------------------

  private int cachedSwatchCount = -1;
  private int cachedC0 = 0, cachedC2 = 0;
  private int cold = COLD_FALLBACK;
  private int warm = WARM_FALLBACK;

  // ---- Symbol morph state ------------------------------------------------------

  private SymbolGlyphs.Symbol prevGlyph = SymbolGlyphs.Symbol.CROSS;
  private SymbolGlyphs.Symbol curGlyph = SymbolGlyphs.Symbol.CROSS;
  private SymbolGlyphs.Symbol lastGlyphEnum = SymbolGlyphs.Symbol.CROSS;
  private double morphT = 1; // 1 = settled on curGlyph

  // Auto-formation envelope: a symbol condenses out of the rain on its own when
  // Condense fires or the Glyph changes, without the operator moving Form. The
  // effective Form is max(knob, autoForm), so the knob/timeline still overrides.
  private double autoForm = 0;       // current envelope value in [0,1]
  private double autoFormTarget = 0; // where it is ramping (1 = condense, 0 = dissolve)
  private double autoFormHoldMs = 0; // remaining hold at full form before dissolving

  /** Sheet-wash sweep progress in [0,1]; < 0 = inactive. Shared by both
   *  surfaces so the wash reads as one gesture across the whole room. */
  private double washT = -1;

  public GlassRain(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);

    addParameter("dropBurst", this.dropBurst);
    addParameter("condense", this.condense);
    addParameter("wash", this.wash);
    addParameter("energy", this.energy);
    addParameter("density", this.density);
    addParameter("streak", this.streak);
    addParameter("bloom", this.bloom);
    addParameter("glyph", this.glyph);
    addParameter("form", this.form);
    addParameter("moire", this.moire);
    addParameter("smooth", this.smooth);
    addParameter("audio", this.audioDepth);
  }

  // ---- Trigger handlers --------------------------------------------------------

  /** Heavy-drop burst: spatter a cluster of fast droplets over both surfaces. */
  private void dropBurst() {
    burst(this.cubeField, BURST_DROPS);
    burst(this.cylField, (int) Math.round(BURST_DROPS * 0.6));
  }

  /** Advance to the next symbol in the shared set and start a fresh morph. The
   *  glyph change is detected in {@link #render} and drives both the cross-
   *  dissolve and the auto-formation envelope (the symbol condenses out of the
   *  rain on its own), so Condense needs only to advance the selection here. */
  private void condense() {
    final SymbolGlyphs.Symbol[] all = SymbolGlyphs.all();
    final SymbolGlyphs.Symbol next = all[(this.curGlyph.ordinal() + 1) % all.length];
    this.glyph.setValue(next); // drives the morph via the change-detect in render
  }

  /** Kick off a sheet-wash sweep down the glass. */
  private void sheetWash() {
    this.washT = 0;
  }

  private void burst(RainField f, int n) {
    final int e = 100; // burst drops always fall fast regardless of the Energy knob
    for (int i = 0; i < n; ++i) {
      spawnDrop(f, e);
    }
  }

  // ---- Render ------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    updatePalette();

    // Glyph knob change (via Condense or the operator) → start a morph from the
    // previous glyph AND kick the auto-formation envelope so the symbol
    // condenses out of the rain on its own.
    final SymbolGlyphs.Symbol g = this.glyph.getEnum();
    if (g != this.lastGlyphEnum) {
      this.prevGlyph = this.curGlyph;
      this.curGlyph = g;
      this.lastGlyphEnum = g;
      this.morphT = 0;
      this.autoFormTarget = 1;
      this.autoFormHoldMs = AUTO_FORM_HOLD_SECONDS * 1000.0;
    }
    if (this.morphT < 1) {
      this.morphT = Math.min(1, this.morphT + deltaMs / (MORPH_SECONDS * 1000.0));
    }

    // Auto-formation: ramp the envelope over MORPH_SECONDS toward its target,
    // hold at full form for AUTO_FORM_HOLD_SECONDS, then ease it back to 0 so
    // the glyph dissolves back into the rain autonomously.
    final double formStep = deltaMs / (MORPH_SECONDS * 1000.0);
    if (this.autoForm < this.autoFormTarget) {
      this.autoForm = Math.min(this.autoFormTarget, this.autoForm + formStep);
    } else if (this.autoForm > this.autoFormTarget) {
      this.autoForm = Math.max(this.autoFormTarget, this.autoForm - formStep);
    }
    if ((this.autoFormTarget >= 1) && (this.autoForm >= 1)) {
      this.autoFormHoldMs -= deltaMs;
      if (this.autoFormHoldMs <= 0) {
        this.autoFormTarget = 0;
      }
    }
    // Effective Form: the knob / timeline overrides the autonomous envelope.
    final double effForm = Math.max(this.form.getValue(), this.autoForm);

    // Bass hit → an extra spatter (audio-gated by the depth knob)
    if (this.audio.bassHit()) {
      burst(this.cubeField, (int) Math.round(BURST_DROPS * this.audio.depth()));
    }

    // Trail decay: Streak knob mapped exponentially, stretched by Bloom
    double halfLifeMs = Ranges.exp(this.streak.getValue(), TRAIL_HALF_LIFE_MIN_MS, TRAIL_HALF_LIFE_MAX_MS);
    halfLifeMs *= 1 + BLOOM_TRAIL_STRETCH * this.bloom.getValue();
    final double decayMult = Math.pow(0.5, deltaMs / halfLifeMs);

    // Advance the wash sweep
    if (this.washT >= 0) {
      this.washT += deltaMs / (WASH_SECONDS * 1000.0);
      if (this.washT > 1) {
        this.washT = -1;
      }
    }
    // Moiré phases locked to the 1/16 tempo grid via the continuous beat
    // position (a quarter-note beat = 4 sixteenths), so the interference
    // shimmer drifts in time instead of free-running. Gated by the Moire knob;
    // cheap: two scalars/frame.
    final Tempo tempo = this.lx.engine.tempo;
    final double sixteenth = (tempo.getCycleCount(Tempo.Division.QUARTER)
      + tempo.getBasis(Tempo.Division.QUARTER)) * 4.0;
    final double moirePhaseH = sixteenth * MOIRE_PHASE_H_PER_16;
    final double moirePhaseV = sixteenth * MOIRE_PHASE_V_PER_16;

    final int dropletColor = LXColor.lerp(this.cold, this.warm, this.bloom.getValue());

    // Smooth: 0 = pixel-snapped/steppy droplets, 1 = sub-pixel fall + AA edges.
    final double smoothAmt = this.smooth.getValue();

    renderField(this.cubeField, CUBE_FACE_W, 4, deltaMs, decayMult,
      dropletColor, effForm, moirePhaseH, moirePhaseV, smoothAmt);
    renderField(this.cylField, CYL_SYMBOL_W, 3, deltaMs, decayMult,
      dropletColor, effForm, moirePhaseH, moirePhaseV, smoothAmt);

    // Cube ring exterior → interior copy; cylinder likewise
    this.cubeField.canvas.copyTo(Apotheneum.cube.exterior, this.colors);
    copyCubeExterior();
    this.cylField.canvas.copyTo(Apotheneum.cylinder.exterior, this.colors);
    copyCylinderExterior();
  }

  /** One surface's frame: spawn rain, step droplets, overlay moiré + wash, then
   *  condense the symbol on top. */
  private void renderField(RainField f, int symbolW, int symbolRepeat, double deltaMs,
                           double decayMult, int dropletColor,
                           double formAmt, double moirePhaseH, double moirePhaseV,
                           double smoothAmt) {
    spawnRain(f, deltaMs);
    f.step(deltaMs, decayMult, dropletColor, smoothAmt);

    // Moiré overlay (peaks): a tempo-locked 2D interference shimmer, gated by
    // the Moire knob and flickered by treble.
    final double moireAmt = this.moire.getValue()
      * (1 + LXUtils.constrain(this.audio.trebleRatio - 1, 0, 2)); // treble flickers it
    if (moireAmt > 0.01) {
      drawMoire(f, moireAmt, moirePhaseH, moirePhaseV, dropletColor);
    }

    // Sheet-wash band
    if (this.washT >= 0) {
      drawWash(f.canvas, this.washT, dropletColor);
    }

    // Condensing symbol — the shared SymbolGlyphs asset, faded in by the
    // effective Form (operator knob or the autonomous formation envelope).
    // Symbols are warmer than the rain (they are the "meaning" surfacing).
    if (formAmt > 0.01) {
      final int symArgb = scaleRGB(this.warm, formAmt);
      final int cy = f.canvas.height / 2;
      for (int k = 0; k < symbolRepeat; ++k) {
        final int cx = symbolW / 2 + k * symbolW;
        SymbolGlyphs.morph(f.canvas, this.prevGlyph, this.curGlyph, this.morphT,
          cx, cy, symArgb, SYMBOL_SCALE);
      }
    }
  }

  // ---- Rain spawning -----------------------------------------------------------

  private void spawnRain(RainField f, double deltaMs) {
    final double e = this.energy.getValue();
    // Density knob + audio level scale the base rate (audio auto-gated by depth)
    final double dens = LXUtils.constrain(this.density.getValue() * (1 + 0.6 * this.audio.level), 0, 2);
    final double rate = Ranges.lin(e, RAIN_MIN_PER_SEC, RAIN_MAX_PER_SEC) * (0.25 + dens);
    f.spawnAccum += rate * deltaMs * 0.001;
    int budget = (int) f.spawnAccum;
    f.spawnAccum -= budget;
    for (int i = 0; i < budget; ++i) {
      spawnDrop(f, (int) Math.round(100 * e));
    }
  }

  /** Spawn one droplet with a full physics profile. energyPct in [0,100] biases
   *  the fall speed. Each drop gets a random fall speed (varying its streak
   *  length), a weight (its width), a head brightness, a gentle wobble, and a
   *  TTL — so the field reads as real wet-glass streaking, not uniform lines.
   *  All randomization is at spawn (event rate); step() then allocates nothing. */
  private void spawnDrop(RainField f, int energyPct) {
    final int col = this.random.nextInt(f.canvas.width);
    final double eNorm = LXUtils.constrain(energyPct / 100.0, 0, 1);
    final double vy = Ranges.lin(eNorm, FALL_MIN, FALL_MAX) * (0.8 + 0.4 * this.random.nextDouble());
    final double weight = this.random.nextDouble();               // 0 = thin .. 1 = fat bead
    final double headBright = 0.7 + 0.3 * this.random.nextDouble(); // brighter head varies per drop
    // Wobble amplitude biased small (rand²) so most drops run nearly straight and
    // only a few heavy beads visibly weave; rate is slow (rad/ms).
    final double r = this.random.nextDouble();
    final double wobbleAmp = WOBBLE_MAX_COLS * r * r;
    final double wobbleRate = (0.6 + 0.8 * this.random.nextDouble()) * 0.001;
    final double wobblePhase = this.random.nextDouble() * Math.PI * 2;
    // TTL ~ the time to fall the full height, randomized: most fall off the
    // bottom, a fraction (factor < 1) evaporate mid-glass (the head fades out).
    final double fallMs = f.canvas.height / vy * 1000.0;
    final double ttlMs = fallMs * (0.75 + 0.7 * this.random.nextDouble());
    f.spawn(col, vy, ttlMs, weight, headBright, wobbleAmp, wobbleRate, wobblePhase);
  }

  // ---- Palette -----------------------------------------------------------------

  /** Rebuild the cold/warm endpoints only when the swatch actually changes.
   *  cold = swatch color 0, warm = swatch color 2 (the reserved warm accent);
   *  documented fallbacks otherwise. */
  private void updatePalette() {
    final List<LXDynamicColor> sw = this.lx.engine.palette.swatch.colors;
    final int n = sw.size();
    final int c0 = (n > 0) ? sw.get(0).getColor() : 0;
    final int c2 = (n > 2) ? sw.get(2).getColor() : ((n > 0) ? sw.get(n - 1).getColor() : 0);
    if (n == this.cachedSwatchCount && c0 == this.cachedC0 && c2 == this.cachedC2) {
      return;
    }
    this.cachedSwatchCount = n;
    this.cachedC0 = c0;
    this.cachedC2 = c2;
    this.cold = (n > 0) ? c0 : COLD_FALLBACK;
    this.warm = (n > 2) ? c2 : WARM_FALLBACK; // CURATE: warm = 3rd swatch color
  }

  // ---- Moiré + wash overlays ---------------------------------------------------

  /**
   * Tempo-locked 2D moiré shimmer. Two nearby vertical gratings (FREQ_A/B) beat
   * into horizontal interference fringes; a vertical grating (FREQ_V) adds
   * vertical structure so the field reads as a shimmering interference SHEET,
   * not static vertical bars. Both grating phases are advanced on the 1/16 grid
   * (moirePhaseH / moirePhaseV, derived from the engine tempo's beat position),
   * so the fringes sweep and the vertical shimmer drifts in time with the music.
   * Max-blended over the rain, scaled by the (treble-flickered) Moire amount.
   *
   * <p>Separable and zero-alloc: the horizontal fringe product is precomputed
   * per column and the vertical grating per row into the field's preallocated
   * scratch arrays, so the inner loop is a multiply, not a trig call.
   */
  private void drawMoire(RainField f, double intensity, double phaseH, double phaseV, int color) {
    final SurfaceCanvas c = f.canvas;
    final int w = c.width;
    final int h = c.height;
    final double amp = LXUtils.constrain(intensity, 0, 1) * MOIRE_MAX_BRIGHT;

    final double[] col = f.moireCol;
    for (int x = 0; x < w; ++x) {
      final double g1 = 0.5 + 0.5 * Math.sin(x * MOIRE_FREQ_A + phaseH);
      final double g2 = 0.5 + 0.5 * Math.sin(x * MOIRE_FREQ_B - phaseH);
      col[x] = g1 * g2; // horizontal moiré fringe strength at this column
    }
    final double[] row = f.moireRow;
    for (int y = 0; y < h; ++y) {
      row[y] = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(y * MOIRE_FREQ_V - phaseV));
    }

    for (int x = 0; x < w; ++x) {
      final double cx = col[x];
      if (cx <= 0.0001) {
        continue;
      }
      for (int y = 0; y < h; ++y) {
        final int argb = scaleRGB(color, amp * cx * row[y]);
        if ((argb & 0x00ffffff) == 0) {
          continue;
        }
        c.setMax(x, y, argb);
      }
    }
  }

  /** Sheet-wash: a bright horizontal band sweeping down the glass. */
  private void drawWash(SurfaceCanvas c, double t, int color) {
    final int yc = (int) Math.round(LXUtils.constrain(t, 0, 1) * (c.height - 1));
    for (int dy = -WASH_THICKNESS; dy <= WASH_THICKNESS; ++dy) {
      final double falloff = 1.0 - Math.abs(dy) / (double) (WASH_THICKNESS + 1);
      final int argb = scaleRGB(color, falloff);
      for (int x = 0; x < c.width; ++x) {
        c.setMax(x, yc + dy, argb);
      }
    }
  }

  /** Scale an ARGB color's RGB channels by mult (clamped 0..1), alpha opaque. */
  private static int scaleRGB(int argb, double mult) {
    if (mult <= 0) {
      return 0xff000000;
    }
    if (mult > 1) {
      mult = 1;
    }
    final int r = (int) (((argb >> 16) & 0xff) * mult);
    final int g = (int) (((argb >> 8) & 0xff) * mult);
    final int b = (int) ((argb & 0xff) * mult);
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  // ---- Rain field: struct-of-arrays droplet pool (zero-alloc after ctor) -------

  private static final class RainField {
    final SurfaceCanvas canvas;
    final int cap;
    // Per-drop struct-of-arrays (all preallocated). x is the current wobbled
    // column, x0 the nominal column the bead runs down; the rest are physics.
    final double[] x, x0, y, vy, ageMs, ttlMs, weight, headBright,
                   wobbleAmp, wobbleRate, wobblePhase;
    final boolean[] active;
    // Moiré scratch (per-column fringe / per-row shimmer), sized to the surface.
    final double[] moireCol, moireRow;
    double spawnAccum = 0;

    RainField(int width, int height, int cap) {
      this.canvas = new SurfaceCanvas(width, height);
      this.cap = cap;
      this.x = new double[cap];
      this.x0 = new double[cap];
      this.y = new double[cap];
      this.vy = new double[cap];
      this.ageMs = new double[cap];
      this.ttlMs = new double[cap];
      this.weight = new double[cap];
      this.headBright = new double[cap];
      this.wobbleAmp = new double[cap];
      this.wobbleRate = new double[cap];
      this.wobblePhase = new double[cap];
      this.active = new boolean[cap];
      this.moireCol = new double[width];
      this.moireRow = new double[height];
    }

    /** Spawn a droplet at the top with a full physics profile; silently dropped
     *  if the pool is full. */
    void spawn(int col, double vyRowsPerSec, double ttl, double wt, double head,
               double wAmp, double wRate, double wPhase) {
      for (int i = 0; i < this.cap; ++i) {
        if (!this.active[i]) {
          this.active[i] = true;
          this.x0[i] = col;
          this.x[i] = col;
          this.y[i] = 0;
          this.vy[i] = vyRowsPerSec;
          this.ageMs[i] = 0;
          this.ttlMs[i] = ttl;
          this.weight[i] = wt;
          this.headBright[i] = head;
          this.wobbleAmp[i] = wAmp;
          this.wobbleRate[i] = wRate;
          this.wobblePhase[i] = wPhase;
          return;
        }
      }
    }

    /** Decay the trail, advance every droplet one frame along a gently wobbling
     *  path, paint the new streak segment (max-blend so trails stack) with an
     *  ADD-blended head (so merging beads accumulate/brighten), and retire drops
     *  that fall off the bottom or reach the end of their TTL. */
    void step(double deltaMs, double decayMult, int color, double smooth) {
      this.canvas.decay(decayMult);
      final double dt = deltaMs * 0.001;
      for (int i = 0; i < this.cap; ++i) {
        if (!this.active[i]) {
          continue;
        }
        this.ageMs[i] += deltaMs;
        final double xOld = this.x[i];
        final double yOld = this.y[i];
        final double xNew = this.x0[i]
          + this.wobbleAmp[i] * Math.sin(this.wobblePhase[i] + this.ageMs[i] * this.wobbleRate[i]);
        final double yNew = yOld + this.vy[i] * dt;
        this.x[i] = xNew;
        this.y[i] = yNew;

        // Life envelope: fade the head out over the last fraction of the TTL so
        // an evaporating bead doesn't pop off. The trail decays on its own.
        double env = 1;
        if (this.ttlMs[i] > 0) {
          final double lifeFrac = this.ageMs[i] / this.ttlMs[i];
          if (lifeFrac > LIFE_FADE_START) {
            env = Math.max(0, (1 - lifeFrac) / (1 - LIFE_FADE_START));
          }
        }

        paintStreak(xOld, yOld, xNew, yNew, this.weight[i], this.headBright[i] * env, color, smooth);

        if ((yNew >= this.canvas.height) || (this.ageMs[i] >= this.ttlMs[i])) {
          this.active[i] = false;
        }
      }
    }

    /** Paint one drop's per-frame streak: a dim wet trail (MAX-blended, widened
     *  to 3 columns for a fat bead) plus a brighter ADD-blended head so merging
     *  beads accumulate. The {@code smooth} amount (0..1) crossfades the whole
     *  streak from pixel-snapped/steppy (0) to sub-pixel + antialiased (1): the
     *  trail is a Wu-AA line and the head's fall position is a fractional-row
     *  crossfade. Zero-alloc — {@code scaleRGB} returns an int, no heap. */
    private void paintStreak(double xOld, double yOld, double xNew, double yNew,
                             double wt, double headMul, int color, double smooth) {
      final boolean fat = wt > FAT_WEIGHT;
      final double s = (smooth < 0) ? 0 : (smooth > 1 ? 1 : smooth);

      final int trail = scaleRGB(color, TRAIL_LEVEL * (0.7 + 0.3 * headMul));
      paintTrail(xOld, yOld, xNew, yNew, trail, s);
      if (fat) {
        final int trailDim = scaleRGB(trail, 0.55);
        paintTrail(xOld - 1, yOld, xNew - 1, yNew, trailDim, s);
        paintTrail(xOld + 1, yOld, xNew + 1, yNew, trailDim, s);
      }

      // Head: brighter, ADD-blended so two beads crossing the same pixel bead up.
      final int head = scaleRGB(color, Math.min(1, headMul));
      paintHead(xNew, yNew, head, s);
      if (fat) {
        final int headDim = scaleRGB(head, 0.5);
        paintHead(xNew - 1, yNew, headDim, s);
        paintHead(xNew + 1, yNew, headDim, s);
      }
    }

    /** Draw one wet-trail segment as a smooth-gated crossfade between a
     *  pixel-snapped integer MAX line (steppy edges, weight 1-s) and a Xiaolin-Wu
     *  antialiased sub-pixel MAX line (soft edges, weight s). Both MAX-blend, so a
     *  dim AA fringe never darkens a brighter trail crossing. Zero-alloc. */
    private void paintTrail(double x0, double y0, double x1, double y1, int argb, double s) {
      if (s < 1) {
        final int snap = scaleRGB(argb, 1 - s);
        this.canvas.lineMax((int) Math.round(x0), (int) Math.round(y0),
                            (int) Math.round(x1), (int) Math.round(y1), snap);
      }
      if (s > 0) {
        final int aa = scaleRGB(argb, s);
        this.canvas.lineWu(x0, y0, x1, y1, aa, true); // Wu AA, MAX-blend, x wraps
      }
    }

    /** ADD-blend a head bead at a sub-pixel fall position. The smooth amount
     *  gates a fractional-row crossfade: at s=0 the head snaps entirely onto the
     *  nearest row (steppy), at s=1 it splits (1-frac)/(frac) across the two
     *  straddled rows so the fall reads as continuous sub-pixel motion. Zero-alloc. */
    private void paintHead(double x, double y, int argb, double s) {
      final int xi = (int) Math.round(x);
      // Snapped contribution (weight 1-s) lands entirely on the nearest row.
      if (s < 1) {
        final int snap = scaleRGB(argb, 1 - s);
        this.canvas.setBlend(xi, (int) Math.round(y), snap, SurfaceCanvas.Blend.ADD);
      }
      // Smooth contribution (weight s) crossfades across the two straddled rows.
      if (s > 0) {
        final int yLo = (int) Math.floor(y);
        final double frac = y - yLo;
        this.canvas.setBlend(xi, yLo, scaleRGB(argb, s * (1 - frac)), SurfaceCanvas.Blend.ADD);
        this.canvas.setBlend(xi, yLo + 1, scaleRGB(argb, s * frac), SurfaceCanvas.Blend.ADD);
      }
    }
  }
}
