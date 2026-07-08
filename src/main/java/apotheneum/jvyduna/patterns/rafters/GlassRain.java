package apotheneum.jvyduna.patterns.rafters;

import java.util.Arrays;
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
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
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
 * symbols CONDENSE OUT OF THE RAIN as liquid: with {@code Symbols} on, drops
 * flow into the selected glyph's form, pool by gravity, tint toward the palette's
 * warm 3rd swatch, spread until the glyph is full, then overflow and drip out its
 * lowest surfaces. The glyphs ride the shared {@link SymbolGlyphs} module (now
 * true vector art) and read correctly from inside and outside via the surface's
 * reverse-normal mirror.
 *
 * <p><b>Liquid symbol model.</b> Each surface carries a per-cell liquid field
 * (fill/mask/overflow grids, preallocated). When {@code Symbols} is on, a rain
 * drop whose head crosses a masked glyph cell is absorbed into that cell's fill;
 * a gravity + lateral-leveling sweep settles the liquid down and spreads it to
 * neighboring regions (surface tension) so a glyph fills from its low points up;
 * excess at a bottom lip (a masked cell with no masked cell below — a slope-0 /
 * concave-up discontinuity) spills back out as a new liquid-colored drip. When
 * the glyph changes ({@code Glyph} floors to a new index) or {@code Symbols}
 * turns off, all held liquid is released instantly as downward drops that run on
 * down the glass, and a fresh glyph starts accumulating. The liquid color eases
 * from the cold droplet color toward the warm 3rd swatch over {@code TINT_SECONDS}.
 *
 * <p>Values that can only be judged on the LEDs are marked with inline
 * {@code CURATE:} notes. See GlassRain.md beside this file.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("GlassRain")
@LXComponent.Description("Window-rain streaking down the glass over a cold G-minor palette, with a single warm bloom, 1/16 moire at peaks, and pseudo-religious symbols condensing as liquid out of the rain (rafters hero)")
public class GlassRain extends ApotheneumPattern {

  // ---- Structural maxima (all droplet state preallocated at these sizes) -------

  private static final int CUBE_DROPS = 220;
  private static final int CYL_DROPS = 140;

  // ---- Rain physics constants (CURATE: droplet feel is untuned on hardware) ----

  /** Droplet fall speed in canvas rows/sec at energy 0 → energy 1. CURATE. */
  private static final double FALL_MIN = 12.0;
  private static final double FALL_MAX = 22.0;

  /** Base droplet spawn rate (drops/sec across a surface) at energy 0 → 1,
   *  before the Density knob and audio scale it. CURATE. */
  private static final double RAIN_MIN_PER_SEC = 2.0;
  private static final double RAIN_MAX_PER_SEC = 14.0;

  /** Heavy-drop burst size (DropBurst trigger / bass hit). CURATE. */
  private static final int BURST_DROPS = 18;

  /** Max gentle horizontal wobble amplitude (columns). CURATE. */
  private static final double WOBBLE_MAX_COLS = 1.6;

  /** Fraction of a drop's TTL after which its head fades out. */
  private static final double LIFE_FADE_START = 0.75;

  /** Wet-trail brightness as a fraction of the head color. CURATE. */
  private static final double TRAIL_LEVEL = 0.5;

  /** A drop whose random weight exceeds this reads as a FAT bead. CURATE. */
  private static final double FAT_WEIGHT = 0.6;

  /** Streak trail half-life range (ms), mapped from the Streak knob and
   *  lengthened by Bloom for the "dreamscape" smear. CURATE. */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 120;
  private static final double TRAIL_HALF_LIFE_MAX_MS = 2500;

  /** Bloom multiplies the trail half-life up to this factor at full bloom. */
  private static final double BLOOM_TRAIL_STRETCH = 2.5;

  // ---- Symbol / liquid constants -----------------------------------------------

  /** Glyph render box in canvas cells (square). Scale 24 → ~half the 45-row cube
   *  / 43-row cylinder height. CURATE. */
  private static final int GLYPH_PX = 24;

  /** Glyph instance center columns, placed SYMMETRIC about each surface's
   *  horizontal center so the whole-canvas reverse-normal mirror
   *  ({@link SurfaceCanvas#copyToMirrored}) maps each glyph onto its identical
   *  mirror partner and it reads un-reversed from inside. Cube ring = 200 cols
   *  (4 faces), cylinder = 120 cols. CURATE: positions/counts (the ±0.5px
   *  even-width offset is intentional and invisible). */
  private static final int[] CUBE_CENTERS = { 25, 75, 125, 175 };
  private static final int[] CYL_CENTERS = { 20, 60, 99 };

  /** A canvas cell counts as part of the glyph when its mask coverage ≥ this. */
  private static final float MASK_MIN = 0.12f;

  /** Fill a captured drop adds to its glyph cell (units of cell depth). CURATE. */
  private static final float INFLOW_PER_DROP = 0.55f;

  /** Per-frame gravity transfer fraction (viscosity): 1 = water falls instantly,
   *  small = syrupy. Scaled down by low Smooth for a steppier pour. CURATE. */
  private static final float FLOW_DOWN = 0.9f;

  /** Per-frame lateral leveling fraction (surface tension spread). CURATE. */
  private static final float FLOW_SIDE = 0.3f;

  /** Fill level above which a bottom-lip cell spills its excess as a drip. */
  private static final float SPILL_LEVEL = 1.0f;

  /** Hard cap on a cell's fill so lips can build a little head before spilling. */
  private static final float FILL_MAX = 2.0f;

  /** Min fill for a cell to be released as a drop on glyph-change / Symbols-off. */
  private static final float REL_MIN = 0.08f;

  /** Max overflow drips spawned per surface per frame (rate-limit the pool). */
  private static final int MAX_SPILL_PER_FRAME = 5;

  /** Seconds for the liquid color to ease from the droplet color to the warm 3rd
   *  swatch after Symbols turns on / the glyph changes. CURATE. */
  private static final double TINT_SECONDS = 4.0;

  // ---- Moiré constants ---------------------------------------------------------

  private static final double MOIRE_FREQ_A = 0.62;
  private static final double MOIRE_FREQ_B = 0.71;
  private static final double MOIRE_FREQ_V = 0.55;
  private static final double MOIRE_PHASE_H_PER_16 = 0.5;
  private static final double MOIRE_PHASE_V_PER_16 = 0.33;
  private static final double MOIRE_MAX_BRIGHT = 0.35;

  /** Sheet-wash sweep duration (seconds) and thickness. CURATE. */
  private static final double WASH_SECONDS = 2.5;
  private static final int WASH_THICKNESS = 3;

  // ---- Cold/warm palette fallbacks (documented; used only on empty swatch) -----

  /** Cold slate-steel blue — the G-minor spine when the project swatch is empty. */
  private static final int COLD_FALLBACK = LXColor.hsb(212f, 55f, 62f);

  /** Warm cream-amber (#FFB38A family) — the maj7 bloom / liquid-symbol color when
   *  the swatch has no 3rd color. CURATE: picked blind. */
  private static final int WARM_FALLBACK = LXColor.hsb(24f, 42f, 100f);

  // ---- Composition helpers -----------------------------------------------------

  private final AudioReactive audio;
  private final Random random = new Random();

  // ---- Parameters (registration order referenced by saved .lxp) ----------------

  public final TriggerParameter dropBurst =
    new TriggerParameter("DropBurst", this::dropBurst)
    .setDescription("Spatter a cluster of heavy droplets across both surfaces (small change; on a kick)");

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

  public final BooleanParameter symbols =
    new BooleanParameter("Symbols", false)
    .setDescription("Condense the selected glyph out of the rain as liquid: drops flow into its form, tint to the warm swatch, fill and overflow. Off releases the held liquid to run down");

  public final CompoundParameter glyph =
    new CompoundParameter("Glyph", 1, 1, SymbolGlyphs.count())
    .setDescription("Which religious symbol condenses (continuous; floored to a glyph). Changing it releases the current liquid and starts the next glyph");

  public final CompoundParameter moire =
    new CompoundParameter("Moire", 0)
    .setDescription("1/16-grid moire interference shimmer over the rain (peaks only)");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 1.0)
    .setDescription("Motion blending + antialiasing: 0 = steppy/pixel-snapped/hard edges, 1 = smooth sub-pixel motion and antialiased forms (also antialiases the glyph mask)");

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

  // ---- Liquid-symbol state -----------------------------------------------------

  private int lastGlyphIndex = -1;   // floor(Glyph)-1 last frame; -1 forces rebuild
  private int lastSmoothQ = -1;      // quantized Smooth used to raster the mask
  private boolean symbolsWereOn = false;
  private double liquidTint = 0;     // eases droplet color → warm swatch, [0,1]

  /** Sheet-wash sweep progress in [0,1]; < 0 = inactive. */
  private double washT = -1;

  public GlassRain(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);

    addParameter("dropBurst", this.dropBurst);
    addParameter("wash", this.wash);
    addParameter("energy", this.energy);
    addParameter("density", this.density);
    addParameter("streak", this.streak);
    addParameter("bloom", this.bloom);
    addParameter("symbols", this.symbols);
    addParameter("glyph", this.glyph);
    addParameter("moire", this.moire);
    addParameter("smooth", this.smooth);
    addParameter("audio", this.audioDepth);
  }

  // ---- Trigger handlers --------------------------------------------------------

  private void dropBurst() {
    burst(this.cubeField, BURST_DROPS);
    burst(this.cylField, (int) Math.round(BURST_DROPS * 0.6));
  }

  private void sheetWash() {
    this.washT = 0;
  }

  private void burst(RainField f, int n) {
    for (int i = 0; i < n; ++i) {
      spawnDrop(f, 100); // burst drops always fall fast regardless of Energy
    }
  }

  // ---- Render ------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    updatePalette();

    final double smoothAmt = this.smooth.getValue();
    final int smoothQ = (int) Math.round(LXUtils.constrain(smoothAmt, 0, 1) * 10);

    // Selected glyph: continuous Glyph knob (1..count) floored to an ordinal.
    final int glyphIndex = LXUtils.constrain(
      (int) Math.floor(this.glyph.getValue()) - 1, 0, SymbolGlyphs.count() - 1);
    final SymbolGlyphs.Symbol sym = SymbolGlyphs.byIndex(glyphIndex);
    final boolean symbolsOn = this.symbols.isOn();

    // ---- Liquid-symbol lifecycle -------------------------------------------
    if (symbolsOn) {
      final boolean turnedOn = !this.symbolsWereOn;
      final boolean glyphChanged = (glyphIndex != this.lastGlyphIndex);
      // A glyph change (mid-run) releases the liquid held in the old glyph so it
      // runs down, then starts empty.
      if (glyphChanged && !turnedOn && this.lastGlyphIndex >= 0) {
        releaseLiquid(this.cubeField);
        releaseLiquid(this.cylField);
      }
      // (Re)raster + stamp the mask when the glyph or the Smooth AA changed, or
      // on first activation. Only the mask is rebuilt; existing fill is kept.
      if (glyphChanged || turnedOn || smoothQ != this.lastSmoothQ) {
        rebuildMask(this.cubeField, sym, CUBE_CENTERS, smoothAmt);
        rebuildMask(this.cylField, sym, CYL_CENTERS, smoothAmt);
      }
      if (glyphChanged || turnedOn) {
        this.liquidTint = 0; // fresh glyph starts cold and tints toward warm
      }
      this.liquidTint = Math.min(1, this.liquidTint + deltaMs / (TINT_SECONDS * 1000.0));
    } else if (this.symbolsWereOn) {
      // Symbols just turned off: release everything and stop absorbing.
      releaseLiquid(this.cubeField);
      releaseLiquid(this.cylField);
      clearMask(this.cubeField);
      clearMask(this.cylField);
    }
    this.symbolsWereOn = symbolsOn;
    this.lastGlyphIndex = glyphIndex;
    this.lastSmoothQ = smoothQ;

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

    // Moiré phases locked to the 1/16 tempo grid (a quarter-note beat = 4 16ths).
    final Tempo tempo = this.lx.engine.tempo;
    final double sixteenth = (tempo.getCycleCount(Tempo.Division.QUARTER)
      + tempo.getBasis(Tempo.Division.QUARTER)) * 4.0;
    final double moirePhaseH = sixteenth * MOIRE_PHASE_H_PER_16;
    final double moirePhaseV = sixteenth * MOIRE_PHASE_V_PER_16;

    final int dropletColor = LXColor.lerp(this.cold, this.warm, this.bloom.getValue());
    // Liquid symbol color eases from the droplet color toward the warm 3rd swatch.
    final int liquidColor = LXColor.lerp(dropletColor, this.warm, this.liquidTint);

    renderField(this.cubeField, deltaMs, decayMult, dropletColor, liquidColor,
      symbolsOn, moirePhaseH, moirePhaseV, smoothAmt);
    renderField(this.cylField, deltaMs, decayMult, dropletColor, liquidColor,
      symbolsOn, moirePhaseH, moirePhaseV, smoothAmt);

    // Reverse-normal: exterior straight, interior horizontally mirrored so the
    // glyphs read un-reversed from inside (rain is mirrored too — invisible).
    this.cubeField.canvas.copyTo(Apotheneum.cube.exterior, this.colors);
    this.cubeField.canvas.copyToMirrored(Apotheneum.cube.interior, this.colors, 1.0);
    this.cylField.canvas.copyTo(Apotheneum.cylinder.exterior, this.colors);
    this.cylField.canvas.copyToMirrored(Apotheneum.cylinder.interior, this.colors, 1.0);
  }

  /** One surface's frame: spawn rain, step droplets (absorbing into the glyph),
   *  step the liquid sim, render the liquid, then overlay moiré + wash. */
  private void renderField(RainField f, double deltaMs, double decayMult,
                           int dropletColor, int liquidColor, boolean symbolsOn,
                           double moirePhaseH, double moirePhaseV, double smoothAmt) {
    spawnRain(f, deltaMs);
    // Absorb drops into the glyph only when Symbols is on (mask is populated).
    f.step(deltaMs, decayMult, dropletColor, liquidColor, smoothAmt, symbolsOn, INFLOW_PER_DROP);

    if (symbolsOn) {
      stepLiquid(f, smoothAmt);
      renderLiquid(f, liquidColor);
    }

    // Moiré overlay (peaks): tempo-locked 2D interference, flickered by treble.
    final double moireAmt = this.moire.getValue()
      * (1 + LXUtils.constrain(this.audio.trebleRatio - 1, 0, 2));
    if (moireAmt > 0.01) {
      drawMoire(f, moireAmt, moirePhaseH, moirePhaseV, dropletColor);
    }

    // Sheet-wash band
    if (this.washT >= 0) {
      drawWash(f.canvas, this.washT, dropletColor);
    }
  }

  // ---- Rain spawning -----------------------------------------------------------

  private void spawnRain(RainField f, double deltaMs) {
    final double e = this.energy.getValue();
    final double dens = LXUtils.constrain(this.density.getValue() * (1 + 0.6 * this.audio.level), 0, 2);
    final double rate = Ranges.lin(e, RAIN_MIN_PER_SEC, RAIN_MAX_PER_SEC) * (0.25 + dens);
    f.spawnAccum += rate * deltaMs * 0.001;
    int budget = (int) f.spawnAccum;
    f.spawnAccum -= budget;
    for (int i = 0; i < budget; ++i) {
      spawnDrop(f, (int) Math.round(100 * e));
    }
  }

  /** Spawn one rain droplet at the top with a full physics profile. */
  private void spawnDrop(RainField f, int energyPct) {
    final int col = this.random.nextInt(f.canvas.width);
    final double eNorm = LXUtils.constrain(energyPct / 100.0, 0, 1);
    final double vy = Ranges.lin(eNorm, FALL_MIN, FALL_MAX) * (0.8 + 0.4 * this.random.nextDouble());
    final double weight = this.random.nextDouble();
    final double headBright = 0.7 + 0.3 * this.random.nextDouble();
    final double r = this.random.nextDouble();
    final double wobbleAmp = WOBBLE_MAX_COLS * r * r;
    final double wobbleRate = (0.6 + 0.8 * this.random.nextDouble()) * 0.001;
    final double wobblePhase = this.random.nextDouble() * Math.PI * 2;
    final double fallMs = f.canvas.height / vy * 1000.0;
    final double ttlMs = fallMs * (0.75 + 0.7 * this.random.nextDouble());
    f.spawn(col, 0, vy, ttlMs, weight, headBright, wobbleAmp, wobbleRate, wobblePhase, false);
  }

  /** Spawn a liquid drop (colored the liquid color) starting at (col, yStart),
   *  running on down the glass — used by overflow spills and the release burst. */
  private void spawnLiquidDrop(RainField f, int col, int yStart, double headBright) {
    final double vy = Ranges.lin(this.energy.getValue(), FALL_MIN, FALL_MAX)
      * (0.8 + 0.4 * this.random.nextDouble());
    final double remainingRows = Math.max(1, f.canvas.height - yStart);
    final double ttlMs = remainingRows / vy * 1000.0 * (0.9 + 0.5 * this.random.nextDouble());
    final double weight = 0.2 * this.random.nextDouble(); // liquid drips read thin
    final double wobbleAmp = WOBBLE_MAX_COLS * 0.15 * this.random.nextDouble();
    final double wobbleRate = (0.6 + 0.8 * this.random.nextDouble()) * 0.001;
    final double wobblePhase = this.random.nextDouble() * Math.PI * 2;
    f.spawn(col, yStart, vy, ttlMs, weight, headBright, wobbleAmp, wobbleRate, wobblePhase, true);
  }

  // ---- Liquid symbol sim -------------------------------------------------------

  /** (Re)raster the vector glyph to a coverage grid and stamp it into the field's
   *  mask at each symmetric instance center; recompute the bottom-lip cells (a
   *  masked cell with no masked cell directly below) used for overflow spill. */
  private void rebuildMask(RainField f, SymbolGlyphs.Symbol sym, int[] centers, double smooth) {
    final int w = f.canvas.width;
    final int h = f.canvas.height;
    Arrays.fill(f.mask, 0f);
    final float[] cov = SymbolGlyphs.coverage(sym, GLYPH_PX, GLYPH_PX, smooth);
    final int cy = h / 2;
    for (int center : centers) {
      final int x0 = center - GLYPH_PX / 2;
      final int y0 = cy - GLYPH_PX / 2;
      for (int gy = 0; gy < GLYPH_PX; ++gy) {
        final int y = y0 + gy;
        if (y < 0 || y >= h) {
          continue;
        }
        for (int gx = 0; gx < GLYPH_PX; ++gx) {
          final float c = cov[gy * GLYPH_PX + gx];
          if (c <= 0f) {
            continue;
          }
          final int x = Math.floorMod(x0 + gx, w);
          final int idx = y * w + x;
          if (c > f.mask[idx]) {
            f.mask[idx] = c;
          }
        }
      }
    }
    f.lipCount = 0;
    for (int y = 0; y < h; ++y) {
      for (int x = 0; x < w; ++x) {
        final int idx = y * w + x;
        if (f.mask[idx] < MASK_MIN) {
          f.overflow[idx] = false;
          continue;
        }
        final float below = (y + 1 < h) ? f.mask[(y + 1) * w + x] : 0f;
        final boolean lip = below < MASK_MIN;
        f.overflow[idx] = lip;
        if (lip) {
          f.lipCells[f.lipCount++] = idx;
        }
      }
    }
  }

  private void clearMask(RainField f) {
    Arrays.fill(f.mask, 0f);
    Arrays.fill(f.overflow, false);
    f.lipCount = 0;
  }

  /** Gravity + lateral leveling + bottom-lip overflow. Surface tension: liquid
   *  sinks where it can, otherwise levels sideways into neighboring glyph cells,
   *  so a glyph fills from its low points up until it saturates and spills. */
  private void stepLiquid(RainField f, double smooth) {
    final int w = f.canvas.width;
    final int h = f.canvas.height;
    final float[] fill = f.fill;
    final float[] mask = f.mask;
    // Low Smooth pours more steppily (lower viscosity feel). CURATE.
    final float flowDown = FLOW_DOWN * (float) (0.5 + 0.5 * LXUtils.constrain(smooth, 0, 1));

    // Gravity, bottom-up so a cell drains into already-settled water below.
    for (int y = h - 2; y >= 0; --y) {
      final int row = y * w;
      for (int x = 0; x < w; ++x) {
        final int idx = row + x;
        final float fv = fill[idx];
        if (fv <= 1e-4f || mask[idx] < MASK_MIN) {
          continue;
        }
        final int bidx = idx + w;
        if (mask[bidx] >= MASK_MIN) {
          final float room = 1f - fill[bidx];
          if (room > 0f) {
            final float t = Math.min(fv, room) * flowDown;
            fill[bidx] += t;
            fill[idx] -= t;
          }
        }
      }
    }

    // Lateral leveling for water that can't sink (below is full or non-glyph).
    for (int y = 0; y < h; ++y) {
      final int row = y * w;
      for (int x = 0; x < w; ++x) {
        final int idx = row + x;
        final float fv = fill[idx];
        if (fv <= 1e-4f || mask[idx] < MASK_MIN) {
          continue;
        }
        if (y + 1 < h && mask[idx + w] >= MASK_MIN && fill[idx + w] < 1f) {
          continue; // gravity will drain it next frame
        }
        levelInto(fill, mask, idx, row + Math.floorMod(x - 1, w));
        levelInto(fill, mask, idx, row + Math.floorMod(x + 1, w));
      }
    }

    // Overflow: bottom-lip cells above SPILL_LEVEL spill their excess as drips.
    f.overflowAccum = Math.max(0f, f.overflowAccum);
    for (int i = 0; i < f.lipCount; ++i) {
      final int idx = f.lipCells[i];
      final float fv = fill[idx];
      if (fv > SPILL_LEVEL) {
        f.overflowAccum += (fv - SPILL_LEVEL);
        fill[idx] = SPILL_LEVEL;
      }
    }
    int spills = (int) f.overflowAccum;
    if (spills > 0 && f.lipCount > 0) {
      f.overflowAccum -= spills;
      spills = Math.min(spills, MAX_SPILL_PER_FRAME);
      for (int s = 0; s < spills; ++s) {
        final int idx = f.lipCells[this.random.nextInt(f.lipCount)];
        final int lx = idx % w;
        final int ly = idx / w;
        spawnLiquidDrop(f, lx, Math.min(h - 1, ly + 1), 0.9);
      }
    }
  }

  /** Move a fraction of the level difference from cell a into glyph cell b. */
  private static void levelInto(float[] fill, float[] mask, int a, int b) {
    if (mask[b] < MASK_MIN) {
      return;
    }
    final float d = fill[a] - fill[b];
    if (d > 0f) {
      final float t = d * FLOW_SIDE * 0.5f;
      fill[a] -= t;
      fill[b] += t;
    }
  }

  /** Draw the liquid: each glyph cell lights at brightness = fill × mask in the
   *  liquid color (MAX over the rain), so the glyph appears as it fills. */
  private void renderLiquid(RainField f, int liquidColor) {
    final int w = f.canvas.width;
    final int h = f.canvas.height;
    final float[] fill = f.fill;
    final float[] mask = f.mask;
    for (int y = 0; y < h; ++y) {
      final int row = y * w;
      for (int x = 0; x < w; ++x) {
        final int idx = row + x;
        final float m = mask[idx];
        if (m < MASK_MIN) {
          continue;
        }
        final float fv = fill[idx];
        if (fv <= 0f) {
          continue;
        }
        final double b = Math.min(1f, fv) * m;
        final int argb = scaleRGB(liquidColor, b);
        if ((argb & 0x00ffffff) != 0) {
          f.canvas.setMax(x, y, argb);
        }
      }
    }
  }

  /** Release all held liquid as downward drops (one per filled column, at the
   *  column's lowest filled cell) and clear the fill grid — the "instant release"
   *  when the glyph changes or Symbols turns off. */
  private void releaseLiquid(RainField f) {
    final int w = f.canvas.width;
    final int h = f.canvas.height;
    final float[] fill = f.fill;
    final float[] mask = f.mask;
    for (int x = 0; x < w; ++x) {
      for (int y = h - 1; y >= 0; --y) {
        final int idx = y * w + x;
        if (mask[idx] >= MASK_MIN && fill[idx] > REL_MIN) {
          spawnLiquidDrop(f, x, y, 0.7 + 0.3 * Math.min(1f, fill[idx]));
          break; // lowest filled cell in this column
        }
      }
    }
    Arrays.fill(fill, 0f);
    f.overflowAccum = 0f;
  }

  // ---- Palette -----------------------------------------------------------------

  /** cold = swatch color 0, warm = swatch color 2 (the reserved warm accent, also
   *  the liquid-symbol target color); documented fallbacks otherwise. */
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
    this.warm = (n > 2) ? c2 : WARM_FALLBACK; // CURATE: warm/liquid = 3rd swatch color
  }

  // ---- Moiré + wash overlays ---------------------------------------------------

  private void drawMoire(RainField f, double intensity, double phaseH, double phaseV, int color) {
    final SurfaceCanvas c = f.canvas;
    final int w = c.width;
    final int h = c.height;
    final double amp = LXUtils.constrain(intensity, 0, 1) * MOIRE_MAX_BRIGHT;

    final double[] col = f.moireCol;
    for (int x = 0; x < w; ++x) {
      final double g1 = 0.5 + 0.5 * Math.sin(x * MOIRE_FREQ_A + phaseH);
      final double g2 = 0.5 + 0.5 * Math.sin(x * MOIRE_FREQ_B - phaseH);
      col[x] = g1 * g2;
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

  // ---- Rain field: struct-of-arrays droplet pool + liquid grids ----------------

  private static final class RainField {
    final SurfaceCanvas canvas;
    final int cap;
    // Per-drop struct-of-arrays. x is the wobbled column, x0 the nominal column.
    final double[] x, x0, y, vy, ageMs, ttlMs, weight, headBright,
                   wobbleAmp, wobbleRate, wobblePhase;
    final boolean[] active;
    final boolean[] isLiquid; // true = drip released from / overflowing a glyph
    // Liquid-symbol grids (canvas-sized). mask = glyph coverage, fill = liquid.
    final float[] mask, fill;
    final boolean[] overflow;
    final int[] lipCells; // indices of bottom-lip cells, [0, lipCount)
    int lipCount = 0;
    float overflowAccum = 0;
    // Moiré scratch (per-column fringe / per-row shimmer).
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
      this.isLiquid = new boolean[cap];
      final int cells = width * height;
      this.mask = new float[cells];
      this.fill = new float[cells];
      this.overflow = new boolean[cells];
      this.lipCells = new int[cells];
      this.moireCol = new double[width];
      this.moireRow = new double[height];
    }

    /** Spawn a droplet; silently dropped if the pool is full. */
    void spawn(int col, int yStart, double vyRowsPerSec, double ttl, double wt, double head,
               double wAmp, double wRate, double wPhase, boolean liquid) {
      for (int i = 0; i < this.cap; ++i) {
        if (!this.active[i]) {
          this.active[i] = true;
          this.isLiquid[i] = liquid;
          this.x0[i] = col;
          this.x[i] = col;
          this.y[i] = yStart;
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

    /** Decay the trail, advance every droplet, paint its streak, and — when
     *  {@code absorb} is on — capture a (non-liquid) drop into the glyph mask
     *  when its head crosses a masked cell (the drop "flows into" the form). */
    void step(double deltaMs, double decayMult, int rainColor, int liquidColor,
              double smooth, boolean absorb, float inflowPerDrop) {
      this.canvas.decay(decayMult);
      final int w = this.canvas.width;
      final int h = this.canvas.height;
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

        // Absorb into the glyph: a rain drop crossing a masked cell is captured.
        if (absorb && !this.isLiquid[i]) {
          final int hx = Math.floorMod((int) Math.round(xNew), w);
          final int hy = (int) Math.round(yNew);
          if (hy >= 0 && hy < h && this.mask[hy * w + hx] >= MASK_MIN) {
            final int cell = hy * w + hx;
            this.fill[cell] = Math.min(FILL_MAX, this.fill[cell] + inflowPerDrop);
            this.active[i] = false;
            continue;
          }
        }

        double env = 1;
        if (this.ttlMs[i] > 0) {
          final double lifeFrac = this.ageMs[i] / this.ttlMs[i];
          if (lifeFrac > LIFE_FADE_START) {
            env = Math.max(0, (1 - lifeFrac) / (1 - LIFE_FADE_START));
          }
        }

        final int color = this.isLiquid[i] ? liquidColor : rainColor;
        paintStreak(xOld, yOld, xNew, yNew, this.weight[i], this.headBright[i] * env, color, smooth);

        if ((yNew >= h) || (this.ageMs[i] >= this.ttlMs[i])) {
          this.active[i] = false;
        }
      }
    }

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

      final int head = scaleRGB(color, Math.min(1, headMul));
      paintHead(xNew, yNew, head, s);
      if (fat) {
        final int headDim = scaleRGB(head, 0.5);
        paintHead(xNew - 1, yNew, headDim, s);
        paintHead(xNew + 1, yNew, headDim, s);
      }
    }

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

    private void paintHead(double x, double y, int argb, double s) {
      final int xi = (int) Math.round(x);
      if (s < 1) {
        final int snap = scaleRGB(argb, 1 - s);
        this.canvas.setBlend(xi, (int) Math.round(y), snap, SurfaceCanvas.Blend.ADD);
      }
      if (s > 0) {
        final int yLo = (int) Math.floor(y);
        final double frac = y - yLo;
        this.canvas.setBlend(xi, yLo, scaleRGB(argb, s * (1 - frac)), SurfaceCanvas.Blend.ADD);
        this.canvas.setBlend(xi, yLo + 1, scaleRGB(argb, s * frac), SurfaceCanvas.Blend.ADD);
      }
    }
  }
}
