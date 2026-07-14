package apotheneum.jvyduna.patterns.bliss;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundDiscreteParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * After Dark-style psychedelic color-cycling generator.
 *
 * A static scalar phase field is precomputed once over every exterior point
 * (cube ring 200x45 + cylinder 120x43, normalized to [0,1] per surface). Each
 * frame the color of a point is simply LUT[frac(field * cycles + t)], so the
 * geometry cost of motion is zero: all apparent movement is the classic
 * palette-rotation trick, inherently smooth and slow. The LUT is built from
 * the project palette swatch (or a perceptual rainbow when the swatch is
 * sparse) and posterized into bold bands that stay legible through LED gaps
 * at sculpture scale.
 *
 * Audio reactivity is gated by the standard Audio depth knob (default 0 =
 * pure screensaver): a slow level tap boosts the cycle rate, bass hits fire
 * the radial phase pulse, and treble lerps the LUT toward white.
 *
 * See Satori.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Satori")
@LXComponent.Description("After Dark-style psychedelic color cycling over a static phase field: interference, moire rings, angular sweeps or kaleidoscope folds")
public class Satori extends ApotheneumPattern {

  // ---- Field variants -------------------------------------------------------

  public enum FieldMode {
    INTERFERENCE("Interfere"),
    RINGS("Rings"),
    ANGULAR("Angular"),
    KALEIDO("Kaleido");

    public final String label;
    private FieldMode(String label) { this.label = label; }

    @Override
    public String toString() { return this.label; }
  }

  // ---- Timing / shape constants ---------------------------------------------

  /** One full color cycle takes 8s at Speed 100%; Speed 200% halves it to 4s */
  private static final double CYCLE_SEC = 8;

  /** Pulse attack fraction of TrigDiv: smoothstep up to max over the first 1/8, decay over the last 7/8 */
  private static final double PULSE_ATTACK_FRAC = 0.125;

  /** Radial pulse wavefront depth in LUT cycles (CURATE: 6x the old default-energy 0.22) */
  private static final double PULSE_DEPTH = 1.3;

  /** Slow-smoothed audio level boosts cycle speed by up to +300% (CURATE: 6x the old +50% max boost) */
  private static final double AUDIO_SPEED_DEPTH = 3.0;

  /** Time constant (ms) of the extra-slow level smoothing that drives speed modulation */
  private static final double SLOW_LEVEL_MS = 1200;

  /** Max lerp toward white of the whole LUT under treble shimmer (CURATE: 6x the old 0.30, clamped at full white) */
  private static final double SHIMMER_MAX = 1.0;

  /** Color LUT resolution; power of two for cheap index masking */
  private static final int LUT_SIZE = 256;

  /** Max swatch colors sampled from the project palette */
  private static final int MAX_SWATCH = 12;

  /** Max radial centers per surface for INTERFERENCE */
  private static final int MAX_CENTERS = 3;

  /** Smooth: per-pixel temporal color low-pass time constant at Smooth=1 (CURATE) */
  private static final double TAU_MS = 300;

  /** Floor on the TrigDiv-driven morph duration so short divisions / fast tempi don't strobe (CURATE) */
  private static final double MIN_MORPH_MS = 250;

  /** Max posterize band count (CompoundDiscreteParameter max is exclusive: 2..16) */
  private static final int MAX_BANDS = 16;

  // ---- Parameters -------------------------------------------------------------

  public final TriggerParameter reseed =
    new TriggerParameter("ReSeed", this::reseed)
    .setDescription("Reseed the field centers and seeds, keeping the current Field variant");

  public final TriggerParameter reverse =
    new TriggerParameter("Reverse", this::reverseDirection)
    .setDescription("Flip the direction of the color cycle");

  public final TriggerParameter pulse =
    new TriggerParameter("Pulse", this::firePulse)
    .setDescription("Launch the radial phase pulse manually: bands warp outward from the seeded center over the TrigDiv duration");

  /** Labels for {@link #trigDiv}; parallel to {@link #TRIG_DIV_UNITS} / {@link #TRIG_DIV_BARS}. */
  private static final String[] TRIG_DIV_LABELS = {
    "1/16", "1/8", "1/4", "1/2", "1 bar", "2 bar", "4 bar", "8 bar"
  };

  /** Length of each division: beats for sub-bar entries, bars for bar entries (see {@link #TRIG_DIV_BARS}). */
  private static final double[] TRIG_DIV_UNITS = {
    0.25, 0.5, 1, 2, 1, 2, 4, 8
  };

  /** Whether each {@link #TRIG_DIV_UNITS} entry is measured in bars (true) or beats (false). */
  private static final boolean[] TRIG_DIV_BARS = {
    false, false, false, false, true, true, true, true
  };

  /** Default division index ("1 bar"). */
  private static final int TRIG_DIV_DEFAULT = 4;

  public final DiscreteParameter trigDiv =
    new DiscreteParameter("TrigDiv", TRIG_DIV_LABELS, TRIG_DIV_DEFAULT)
    .setDescription("Duration for the next ReSeed blend, Reverse ease, or Pulse envelope — a musical division read at the current BPM, not grid-locked");

  public final EnumParameter<FieldMode> fieldMode =
    new EnumParameter<FieldMode>("Field", FieldMode.INTERFERENCE)
    .setDescription("Static phase-field variant the colors rotate through");

  public final CompoundParameter speed = new CompoundParameter("Speed", 1, 0, 2)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Color cycle rate: 0% pauses, 100% = 8s per cycle, 200% = 4s");

  public final CompoundParameter width = new CompoundParameter("Width", 5, 1, 6)
    .setDescription("Band width: higher = wider bands, fewer repeats across each surface");

  public final CompoundDiscreteParameter posterize =
    new CompoundDiscreteParameter("Bands", 6, 2, 17)
    .setDescription("Posterize the color LUT into N bold bands; smooth gradients read as mud through LED gaps");

  public final CompoundParameter rnbwSm = new CompoundParameter("RnbwSm", 0)
    .setDescription("Rainbow band-edge smoothing: each pixel smoothsteps along the LUT (hue-shifting path) from one band color to the next; 0 = hard edges");

  public final CompoundParameter smooth = new CompoundParameter("Smooth", 0)
    .setDescription("Soften motion and transitions: eases each pixel's color over time and anti-aliases band edges on a linear-mix (low hue-shift) path; 0 = as-is");

  public final CompoundParameter audioDepth = new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  // ---- State ------------------------------------------------------------------

  private final AudioReactive audio;
  private final Random random = new Random();

  // Phase field over all model points; only exterior indices are filled, the
  // interior receives a verbatim copy of the exterior each frame.
  private float[] field = null;      // normalized [0,1] per surface
  private float[] fieldOld = null;   // pre-morph snapshot, lerped toward field
  private float[] pulseDist = null;  // normalized [0,1] distance from the pulse center
  private int[] prevColor = null;    // Smooth: per-pixel temporal color low-pass
  private boolean primeSmooth = true;// write the temporal buffer through on (re)alloc
  private boolean fieldDirty = true;
  private boolean reseedPending = true;

  // TrigDiv-long decelerating morph on ReSeed / Field / Bands change
  private boolean morphPending = false;
  private boolean morphActive = false;
  private double morphMs = 0;
  private double morphDurMs = 0;     // TrigDiv duration captured when the morph starts
  private double bandsOld = 0;
  private int bandsPrev = -1;        // last frame's band count (the "old" count)

  // Band -> LUT position table (R4): rebuilt only when Bands or swatch count change
  private final float[] bandPos = new float[MAX_BANDS];
  private int lastTableBands = -1;
  private int lastTableN = -1;

  // Per-frame paint inputs (set in render, read in paint; single engine thread)
  private double frameCycles, frameBandsEff, frameE, frameRnbwSm, frameSmooth, frameAlpha, framePulseDepth;
  private int frameBands, frameN;
  private boolean frameMorphActive, frameFractionalBands, framePrime;

  // Per-surface field seeds: index 0 = cube exterior, 1 = cylinder exterior
  private final int[] numCenters = new int[2];
  private final float[][] centerX = new float[2][MAX_CENTERS];
  private final float[][] centerY = new float[2][MAX_CENTERS];
  private final float[] wave0 = new float[2];      // RINGS ring-set wavelengths (rows)
  private final float[] wave1 = new float[2];
  private final int[] arms = new int[2];           // ANGULAR mirrored stripe count
  private final float[] pitch = new float[2];      // ANGULAR vertical pitch (signed)
  private final float[] kaleidoMix = new float[2]; // KALEIDO radial vs angular fold mix

  // Color LUT, rebuilt only when the palette swatch actually changes
  private final int[] baseLut = new int[LUT_SIZE];
  private final int[] frameLut = new int[LUT_SIZE];
  private final int[] cachedSwatch = new int[MAX_SWATCH];
  private int cachedSwatchCount = -1; // forces the initial build

  // Cycle state
  private double phase = 0;
  private double cycleDirection = 1; // continuous [-1,1], eased through 0 during a Reverse
  private double slowLevel = 0;

  // Reverse: ease the cycle direction from its current value to the flipped
  // target over the TrigDiv duration (a decelerating pass through a momentary pause)
  private int dirTarget = 1;         // +1 / -1 target sign
  private boolean reverseActive = false;
  private double reverseMs = 0;
  private double reverseDurMs = 0;
  private double reverseFrom = 1;

  // Pulse: fixed-duration attack/decay envelope over TrigDiv (manual trigger or
  // bass hit). pulseFirePending carries a manual Pulse from the trigger callback
  // into the next render; strength is 1 (manual) or audio depth (bass hit).
  private boolean pulseActive = false;
  private double pulseMs = 0;
  private double pulseDurMs = 0;
  private double pulseStrength = 0;
  private double pulseFirePending = 0;

  public Satori(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);

    addParameter("reseed", this.reseed);
    addParameter("reverse", this.reverse);
    addParameter("pulse", this.pulse);
    addParameter("trigDiv", this.trigDiv);
    addParameter("fieldMode", this.fieldMode);
    addParameter("speed", this.speed);
    addParameter("width", this.width);
    addParameter("posterize", this.posterize);
    addParameter("rnbwSm", this.rnbwSm);
    addParameter("smooth", this.smooth);
    addParameter("audio", this.audioDepth);
  }

  // ---- Triggers ---------------------------------------------------------------

  /** Reseed: reseed centers/seeds, rebuild the field, and blend into it over TrigDiv */
  private void reseed() {
    this.reseedPending = true;
    this.fieldDirty = true;
    this.morphPending = true;
  }

  /** Reverse: ease the palette-rotation direction to its flip over TrigDiv; the field is untouched */
  private void reverseDirection() {
    this.dirTarget = -this.dirTarget;
    this.reverseFrom = this.cycleDirection;
    this.reverseMs = 0;
    this.reverseDurMs = trigDivMs();
    this.reverseActive = true;
  }

  /** Pulse: request a manual radial pulse (started in render at full strength, regardless of audio depth) */
  private void firePulse() {
    this.pulseFirePending = 1;
  }

  /** TrigDiv as a duration in ms at the live BPM — a musical division, never grid-locked */
  private double trigDivMs() {
    final int i = this.trigDiv.getValuei();
    final double beats = TRIG_DIV_BARS[i]
      ? TRIG_DIV_UNITS[i] * this.lx.engine.tempo.beatsPerBar.getValuei()
      : TRIG_DIV_UNITS[i];
    return beats * this.lx.engine.tempo.period.getValue();
  }

  @Override
  public void onParameterChanged(LXParameter parameter) {
    super.onParameterChanged(parameter);
    if (parameter == this.fieldMode) {
      // Rebuild with the existing seeds so variants can be A/B'd on one layout,
      // and morph the old field into the new one over one beat
      this.fieldDirty = true;
      this.morphPending = true;
    } else if (parameter == this.posterize) {
      // Ease the band count from old to new over one beat (field is untouched)
      this.morphPending = true;
    }
  }

  // ---- Field generation (event-rate only, never per frame) --------------------

  private void rollSeeds() {
    for (int s = 0; s < 2; ++s) {
      final Apotheneum.Orientation orientation = (s == 0) ?
        Apotheneum.cube.exterior : Apotheneum.cylinder.exterior;
      final int w = orientation.width();
      final int h = orientation.height();
      this.numCenters[s] = 2 + this.random.nextInt(2); // 2..3
      for (int c = 0; c < MAX_CENTERS; ++c) {
        this.centerX[s][c] = this.random.nextFloat() * w;
        // Keep centers in the middle vertical band so rings read on-surface
        this.centerY[s][c] = h * (0.15f + 0.7f * this.random.nextFloat());
      }
      this.wave0[s] = h * (0.6f + 0.4f * this.random.nextFloat());
      this.wave1[s] = this.wave0[s] * (0.65f + 0.25f * this.random.nextFloat());
      this.arms[s] = 1 + this.random.nextInt(3); // 1..3
      this.pitch[s] = (this.random.nextBoolean() ? 1 : -1) * (0.5f + this.random.nextFloat());
      this.kaleidoMix[s] = 0.35f + 0.3f * this.random.nextFloat();
    }
  }

  private void buildField() {
    final int size = this.colors.length;
    if ((this.field == null) || (this.field.length != size)) {
      // Allocation only here: first build or model change, never steady-state
      this.field = new float[size];
      this.fieldOld = new float[size];
      this.pulseDist = new float[size];
      this.prevColor = new int[size];
      this.primeSmooth = true; // seed the temporal buffer from the first paint
    }
    if (this.reseedPending) {
      rollSeeds();
      this.reseedPending = false;
    }
    final FieldMode mode = this.fieldMode.getEnum();
    // Fold widths: one cube face (50) => 4-fold symmetry; 40 on the cylinder => 3 mirrored sectors
    buildSurface(0, Apotheneum.cube.exterior, Apotheneum.GRID_WIDTH, mode);
    buildSurface(1, Apotheneum.cylinder.exterior, 40, mode);
    LX.log("Satori: built " + mode + " field (" + this.numCenters[0] + "/" + this.numCenters[1] + " centers)");
  }

  private void buildSurface(int s, Apotheneum.Orientation orientation, int foldWidth, FieldMode mode) {
    final Apotheneum.Column[] columns = orientation.columns();
    final int w = columns.length;
    final int h = orientation.height();
    float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
    float maxPulse = 1e-6f;
    for (int x = 0; x < w; ++x) {
      final LXPoint[] points = columns[x].points; // door columns are shorter; bound by length
      for (int y = 0; y < points.length; ++y) {
        final int idx = points[y].index;
        final float v = fieldValue(mode, s, x, y, w, h, foldWidth);
        this.field[idx] = v;
        if (v < min) min = v;
        if (v > max) max = v;
        final float pd = dist(x, y, this.centerX[s][0], this.centerY[s][0], w);
        this.pulseDist[idx] = pd;
        if (pd > maxPulse) maxPulse = pd;
      }
    }
    // Normalize the field to span exactly [0,1] per surface: Width then maps
    // to exactly N color cycles across the surface for every variant.
    final float scale = (max > min) ? (1f / (max - min)) : 0f;
    final float pulseScale = 1f / maxPulse;
    for (int x = 0; x < w; ++x) {
      final LXPoint[] points = columns[x].points;
      for (int y = 0; y < points.length; ++y) {
        final int idx = points[y].index;
        this.field[idx] = (this.field[idx] - min) * scale;
        this.pulseDist[idx] *= pulseScale;
      }
    }
  }

  private float fieldValue(FieldMode mode, int s, int x, int y, int w, int h, int foldWidth) {
    switch (mode) {
      case INTERFERENCE: {
        // Summed distances from 2-3 radial centers: smooth ovoid interference lobes
        float sum = 0;
        final int k = this.numCenters[s];
        for (int c = 0; c < k; ++c) {
          sum += dist(x, y, this.centerX[s][c], this.centerY[s][c], w);
        }
        return sum;
      }
      case RINGS: {
        // Moire of two concentric ring sets with slightly different wavelengths
        final float d0 = dist(x, y, this.centerX[s][0], this.centerY[s][0], w);
        final float d1 = dist(x, y, this.centerX[s][1], this.centerY[s][1], w);
        return d0 / this.wave0[s] - d1 / this.wave1[s];
      }
      case ANGULAR: {
        // 1-3 mirrored arms: triangle wave over arms copies of the azimuth
        // (seam-free at the wrap for integer arms) plus a signed vertical
        // pitch term => helical chevrons around the sculpture. NB: arms must
        // fold the azimuth, not scale amplitude — the per-surface [0,1]
        // normalization would absorb a constant scale into a no-op.
        final float uu = x * this.arms[s] / (float) w;
        final float fu = uu - (float) Math.floor(uu);
        final float tri = 1 - Math.abs(1 - 2 * fu);
        return tri + this.pitch[s] * (y / (float) h);
      }
      default: { // KALEIDO
        // Fold x into mirrored sectors and y about the vertical center; mix a
        // radial term with a folded-diagonal angular term. Mirror symmetry makes
        // sector seams and face edges continuous by construction.
        final float halfW = 0.5f * (foldWidth - 1);
        final float halfH = 0.5f * (h - 1);
        final float fx = Math.abs((x % foldWidth) - halfW) / halfW;
        final float fy = Math.abs(y - halfH) / halfH;
        final float r = (float) Math.hypot(fx, fy) * 0.70710678f;
        float a = (float) (Math.atan2(fy, fx + 1e-6f) * (2 / Math.PI));
        a = 1 - Math.abs(1 - 2 * a);
        return this.kaleidoMix[s] * r + (1 - this.kaleidoMix[s]) * a;
      }
    }
  }

  /** Wrap-aware distance in grid cells on a cylindrical surface of width w */
  private static float dist(int x, int y, float cx, float cy, int w) {
    float dx = x - cx;
    if (dx > 0.5f * w) {
      dx -= w;
    } else if (dx < -0.5f * w) {
      dx += w;
    }
    final float dy = y - cy;
    return (float) Math.sqrt(dx * dx + dy * dy);
  }

  // ---- Color LUT ---------------------------------------------------------------

  private void updateLut() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = Math.min(swatch.size(), MAX_SWATCH);
    boolean changed = (n != this.cachedSwatchCount);
    for (int i = 0; i < n; ++i) {
      final int c = swatch.get(i).getColor();
      if (c != this.cachedSwatch[i]) {
        changed = true;
        this.cachedSwatch[i] = c;
      }
    }
    if (!changed) {
      return; // LUT rebuilt only when its inputs actually change
    }
    this.cachedSwatchCount = n;
    if (n >= 2) {
      // Project palette: n swatch colors spaced evenly around the cycle,
      // lerped between neighbors, wrapping back to the first for continuity
      for (int j = 0; j < LUT_SIZE; ++j) {
        final float pos = j * n / (float) LUT_SIZE;
        final int i0 = (int) pos;
        this.baseLut[j] = LXColor.lerp(
          this.cachedSwatch[i0 % n],
          this.cachedSwatch[(i0 + 1) % n],
          pos - i0);
      }
    } else {
      // Sparse swatch: evenly spaced perceptual rainbow
      for (int j = 0; j < LUT_SIZE; ++j) {
        this.baseLut[j] = PerceptualHue.color(j / (float) LUT_SIZE);
      }
    }
  }

  // ---- Band -> LUT position table (palette-exact posterization) -----------------

  /**
   * Map each posterize band to a position in the color LUT. Because updateLut()
   * places palette color k at LUT position k/n, a band's color is fully
   * expressed as a LUT position, so treble shimmer (which recolors the LUT)
   * still applies. Rebuilt only when Bands or the swatch count change.
   *
   *   n < 2   : rainbow fallback, even band centers (i+0.5)/bands (unchanged look)
   *   bands<=n: band i -> palette color i exactly (strict first-N subset)
   *   bands >n: all n palette colors placed exactly; fillers interpolate position
   */
  private void buildBandTable(int bands, int n) {
    if ((bands == this.lastTableBands) && (n == this.lastTableN)) {
      return;
    }
    this.lastTableBands = bands;
    this.lastTableN = n;
    if (n < 2) {
      for (int i = 0; i < bands; ++i) {
        this.bandPos[i] = (i + 0.5f) / bands;
      }
    } else if (bands <= n) {
      for (int i = 0; i < bands; ++i) {
        this.bandPos[i] = i / (float) n; // lands exactly on palette color i
      }
    } else {
      // bands > n: each palette color k anchored at band round(k*bands/n) (>1
      // apart, so no collisions), non-anchor bands interpolate LUT position
      // between bracketing anchors.
      for (int k = 0; k < n; ++k) {
        final int a0 = Math.round(k * bands / (float) n);
        final int a1 = Math.round((k + 1) * bands / (float) n); // == bands at k = n-1
        final float p0 = k / (float) n;
        final float p1 = (k + 1) / (float) n;
        for (int b = a0; b < a1; ++b) {
          final float t = (a1 > a0) ? (b - a0) / (float) (a1 - a0) : 0f;
          this.bandPos[b % bands] = p0 + t * (p1 - p0);
        }
      }
    }
  }

  /** Continuous band -> LUT position, used mid-morph when the band count is fractional */
  private static double contBandPos(int band, double bandsEff, int n) {
    if (n < 2) {
      return (band + 0.5) / bandsEff;
    }
    return band / (double) n;
  }

  /** Cubic smoothstep, clamped to [0,1] */
  private static double smoothstep(double t) {
    if (t <= 0) {
      return 0;
    }
    if (t >= 1) {
      return 1;
    }
    return t * t * (3 - 2 * t);
  }

  /**
   * Pulse attack/decay over normalized time u in [0,1]: smoothstep up to the
   * maximum over the first {@link #PULSE_ATTACK_FRAC} (1/8), then smoothstep
   * back to the default over the remaining 7/8. 0 outside [0,1].
   */
  private static double pulseEnvelope(double u) {
    if ((u <= 0) || (u >= 1)) {
      return 0;
    }
    if (u < PULSE_ATTACK_FRAC) {
      return smoothstep(u / PULSE_ATTACK_FRAC);
    }
    return 1 - smoothstep((u - PULSE_ATTACK_FRAC) / (1 - PULSE_ATTACK_FRAC));
  }

  /** Trailing-fraction smoothstep: 0 until (1-amt) of the band, ramps to 1 at its end */
  private static double ramp(double f, double amt) {
    if (amt <= 0) {
      return 0;
    }
    final double t = (f - (1 - amt)) / amt;
    return (t > 0) ? t * t * (3 - 2 * t) : 0;
  }

  // ---- Render (zero allocation) --------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);

    final int bands = this.posterize.getValuei();
    if (this.bandsPrev < 0) {
      this.bandsPrev = bands; // first frame: no morph baseline yet
    }

    // Morph snapshot: capture the OLD field BEFORE buildField overwrites it, and
    // the OLD band count from the previous frame. Skipped on a model-size change.
    if (this.morphPending) {
      if ((this.field != null) && (this.field.length == this.colors.length)) {
        System.arraycopy(this.field, 0, this.fieldOld, 0, this.field.length);
        this.bandsOld = this.bandsPrev;
        this.morphMs = 0;
        this.morphDurMs = Math.max(trigDivMs(), MIN_MORPH_MS);
        this.morphActive = true;
      }
      this.morphPending = false;
    }

    if (this.fieldDirty || (this.field == null) || (this.field.length != this.colors.length)) {
      buildField();
      this.fieldDirty = false;
    }
    updateLut();
    buildBandTable(bands, this.cachedSwatchCount);

    // Slow-smoothed level boosts cycle speed (boost-only: silence and audio
    // depth 0 both read level 0 => exactly 1x, so the Speed knob alone owns
    // the base rate; loud music at full depth boosts up to 4x).
    this.slowLevel += (this.audio.level - this.slowLevel) * (1 - Math.exp(-deltaMs / SLOW_LEVEL_MS));
    final double speedMul = 1 + AUDIO_SPEED_DEPTH * this.slowLevel;

    // Reverse: ease the cycle direction from its captured value to the flipped
    // target over TrigDiv (a decelerating smoothstep through a momentary pause)
    if (this.reverseActive) {
      this.reverseMs += deltaMs;
      final double u = (this.reverseDurMs > 0) ? Math.min(1.0, this.reverseMs / this.reverseDurMs) : 1.0;
      this.cycleDirection = this.reverseFrom + (this.dirTarget - this.reverseFrom) * smoothstep(u);
      if (u >= 1.0) {
        this.cycleDirection = this.dirTarget;
        this.reverseActive = false;
      }
    }

    // Speed multiplies the phase rate (never divides), so 0 is a clean pause
    this.phase += this.cycleDirection * speedMul * this.speed.getValue() * deltaMs / (CYCLE_SEC * 1000);
    this.phase -= Math.floor(this.phase);

    // Radial phase pulse: wavefront offset proportional to distance from the
    // pulse center, on a fixed TrigDiv-long attack/decay envelope (smoothstep up
    // over the first 1/8, back down over the last 7/8). Sources: the manual Pulse
    // trigger (strength 1) and bass hits (scaled by depth so a barely-open Audio
    // knob pulses gently). A new fire restarts the envelope at its captured length.
    double pulseFire = this.pulseFirePending;
    this.pulseFirePending = 0;
    if (this.audio.bassHit()) {
      pulseFire = Math.max(pulseFire, this.audio.depth());
    }
    if (pulseFire > 0) {
      this.pulseStrength = this.pulseActive ? Math.max(this.pulseStrength, pulseFire) : pulseFire;
      this.pulseMs = 0;
      this.pulseDurMs = trigDivMs();
      this.pulseActive = true;
    }
    double pulseEnv = 0;
    if (this.pulseActive) {
      this.pulseMs += deltaMs;
      final double u = (this.pulseDurMs > 0) ? this.pulseMs / this.pulseDurMs : 1.0;
      if (u >= 1.0) {
        this.pulseActive = false;
      } else {
        pulseEnv = this.pulseStrength * pulseEnvelope(u);
      }
    }
    final double pulseDepth = pulseEnv * PULSE_DEPTH;

    // Treble shimmer (depth-scaled inside AudioReactive's treble tap)
    final double shimmer = SHIMMER_MAX * Math.min(1, this.audio.treble);
    int[] lut = this.baseLut;
    if (shimmer > 0.005) {
      for (int j = 0; j < LUT_SIZE; ++j) {
        this.frameLut[j] = LXColor.lerp(this.baseLut[j], LXColor.WHITE, (float) shimmer);
      }
      lut = this.frameLut;
    }

    // Smooth: temporal color low-pass alpha (per frame, not per pixel). smooth=0
    // => alpha 1 (no easing); at smooth=1 the time constant is TAU_MS.
    final double sm = this.smooth.getValue();
    final double alpha = (sm <= 0) ? 1.0 : 1.0 - Math.exp(-deltaMs / (sm * TAU_MS));

    // One-beat decelerating morph on Field / Bands change (ease-out cubic)
    double e = 1.0;
    double bandsEff = bands;
    boolean fractionalBands = false;
    if (this.morphActive) {
      this.morphMs += deltaMs;
      final double u = Math.min(1.0, this.morphMs / this.morphDurMs);
      final double omu = 1.0 - u;
      e = 1.0 - omu * omu * omu;
      bandsEff = this.bandsOld * (1.0 - e) + bands * e;
      fractionalBands = (this.bandsOld != bands); // Field-only morph keeps bands integer
      if (u >= 1.0) {
        this.morphActive = false;
      }
    }

    // Publish per-frame paint inputs (single engine thread; read in paint)
    this.frameCycles = 7 - this.width.getValue(); // Width inverted into cycles/surface
    this.frameBands = bands;
    this.frameBandsEff = bandsEff;
    this.frameE = e;
    this.frameN = this.cachedSwatchCount;
    this.frameMorphActive = this.morphActive;
    this.frameFractionalBands = fractionalBands;
    this.frameRnbwSm = this.rnbwSm.getValue();
    this.frameSmooth = sm;
    this.frameAlpha = alpha;
    this.framePrime = this.primeSmooth;
    this.framePulseDepth = pulseDepth;

    paint(Apotheneum.cube.exterior.columns, lut);
    paint(Apotheneum.cylinder.exterior.columns, lut);

    this.primeSmooth = false;
    this.bandsPrev = bands;

    copyCubeExterior();
    copyCylinderExterior();
  }

  private void paint(Apotheneum.Column[] columns, int[] lut) {
    final double phase = this.phase;
    final double cycles = this.frameCycles;
    final double bandsEff = this.frameBandsEff;
    final int bandCount = this.frameBands;
    final int n = this.frameN;
    final boolean fractional = this.frameFractionalBands;
    final boolean morph = this.frameMorphActive;
    final float ef = (float) this.frameE;
    final double rnbwSmVal = this.frameRnbwSm;
    final double sm = this.frameSmooth;
    final double pulseDepth = this.framePulseDepth;
    final double a = this.framePrime ? 1.0 : this.frameAlpha;
    for (int x = 0; x < columns.length; ++x) {
      final LXPoint[] points = columns[x].points; // door columns bound by length
      for (int y = 0; y < points.length; ++y) {
        final int idx = points[y].index;
        // Morph: lerp the pre-change field toward the new one (decelerating)
        final float fv = morph ? (this.fieldOld[idx] * (1f - ef) + this.field[idx] * ef) : this.field[idx];
        double pos = fv * cycles + phase + pulseDepth * this.pulseDist[idx];
        pos -= Math.floor(pos);
        // Posterize: band index + within-band fraction. Band colors come from
        // the band->LUT-position table (exact palette anchors) at rest; during a
        // Bands morph the fractional count uses the continuous mapping instead.
        final double bpos = pos * bandsEff;
        final int i = (int) bpos;
        final double f = bpos - i;
        double p0, p1;
        if (fractional) {
          p0 = contBandPos(i, bandsEff, n);
          p1 = contBandPos(i + 1, bandsEff, n);
        } else {
          p0 = this.bandPos[i % bandCount];
          p1 = this.bandPos[(i + 1) % bandCount];
        }
        if (p1 <= p0) {
          p1 += 1.0; // keep monotonic across the cyclic wrap from last band to first
        }
        // RnbwSm: smoothstep along the LUT between band centers (hue-shifting path)
        final double wR = ramp(f, rnbwSmVal);
        final int cHue = lut[((int) Math.round((p0 + wR * (p1 - p0)) * LUT_SIZE)) & (LUT_SIZE - 1)];
        // Smooth (spatial): linear-RGB anti-alias toward the next band center
        final double wS = ramp(f, sm);
        int target = cHue;
        if (wS > 0) {
          final int cNext = lut[((int) Math.round(p1 * LUT_SIZE)) & (LUT_SIZE - 1)];
          target = LXColor.lerp(cHue, cNext, (float) wS);
        }
        // Smooth (temporal): low-pass the output color per pixel (linear RGB mix)
        this.colors[idx] = this.prevColor[idx] =
          (a >= 1.0) ? target : LXColor.lerp(this.prevColor[idx], target, (float) a);
      }
    }
  }
}
