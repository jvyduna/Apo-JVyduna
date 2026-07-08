package apotheneum.jvyduna.patterns;

import java.util.Arrays;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.audio.GraphicMeter;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * CP 1919 pulsar ridgeline waterfall — the 1979 Joy Division "Unknown Pleasures"
 * album cover as a scrolling stack of occluding spectrum silhouettes.
 *
 * A stack of horizontal ridgelines scrolls vertically, phase-locked to the
 * tempo: one line emerges from the entry edge on every TempoDiv division, and
 * the scroll rate is Spacing rows per division (Spacing is a pure zoom about
 * the entry edge). Each line's height profile is a normalized signal in [0,1];
 * Amp then scales it in units of Spacing, so Amp = 0 is flat lines and Amp = 2
 * lets a full peak rise two rows-of-spacing tall — overlapping the two lines
 * behind it. The Audio knob live-mixes a bass-centered, mirrored FFT profile
 * into every displayed line each frame.
 *
 * Everything is functional/live: line profiles are recomputed every frame from
 * a deterministic per-line seed and the current parameters (never baked into a
 * buffer), so changing Simplex scale, Jag, Amp, mode, etc. re-shapes every
 * already-visible line immediately. Reseed changes a salt, giving fresh shapes.
 *
 * Rendering is a true curve rasterizer: the contour is drawn as a connected
 * polyline through the per-column samples (each column fills the vertical span
 * between its neighbor-midpoints), so the ridge never breaks apart even when
 * jagged or at high amplitude. Below each contour the interior is black, and
 * that black fill occludes the lines behind it — the classic ridgeline
 * layering. Strokes are always white (colorize externally).
 *
 * WavMode makes the four cube walls distinct (Dup / Shift / Simplex / Helix),
 * each wall painted into its own 50x45 slot raster; CylWavs wraps 1-4 distinct
 * waveforms around the cylinder, rotated 45 degrees so a flat inter-waveform
 * interface faces a corner.
 *
 * See UnknownPleasures.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Unknown Pleasures")
@LXComponent.Description("CP 1919 pulsar ridgeline waterfall: stacked, occluding spectrum silhouettes scrolling like the Unknown Pleasures cover")
public class UnknownPleasures extends ApotheneumPattern {

  // ---- Field dimensions -----------------------------------------------------

  private static final int WIDTH = Apotheneum.GRID_WIDTH;   // 50 samples per line
  private static final int HEIGHT = Apotheneum.GRID_HEIGHT; // 45 rows

  // ---- Line geometry --------------------------------------------------------

  /** Amplitude multiplier of a Pulse-trigger line (oversized, always synthesized) */
  private static final double PULSE_GAIN = 1.8;

  // ---- Shape synthesis ------------------------------------------------------

  /** Center-weighting window exponent (Hann^p); higher pins the edges flatter to the baseline */
  private static final double WINDOW_POWER = 1.5; // CURATE: edge-pinning strength

  /** Jaggedness noise contribution at jaggedness = 1, in normalized shape units */
  private static final double JAG_SCALE = 0.5; // CURATE: max roughness before lines read as static

  /** One-pole smoothing of the per-sample jaggedness noise walk (0 = white, ~1 = very smooth) */
  private static final double NOISE_SMOOTH = 0.5; // CURATE: noise spatial scale at LED pitch

  /** Simplex field: horizontal field units per column per unit NzScl */
  private static final double NZ_X_STEP = 0.15; // CURATE: spatial frequency at LED pitch

  /** Simplex field: field units the sampling plane advances per line per unit NzScl */
  private static final double NZ_LINE_STEP = 0.45; // CURATE: line-to-line field coherence

  private static final float NZ_LACUNARITY = 2;
  private static final float NZ_GAIN = 0.5f;

  // ---- Timing ---------------------------------------------------------------

  /**
   * A frame advancing more than this many divisions is treated as a
   * discontinuity (division change, transport jump) and folded out by an
   * integer shift, preserving fractional phase so emergence stays on-division.
   */
  private static final double FOLD_DIVS = 4;

  // ---- Multi-wall geometry --------------------------------------------------

  /** Waveform slots: one per cube wall; the cylinder uses the first CylWavs of them */
  private static final int NUM_SLOTS = 4;

  /**
   * Ring buffer of line records (pulse metadata only, keyed by birth index).
   * Must exceed the widest existence window: (45 visible + ridge margin) / min
   * spacing 2 + slack.
   */
  private static final int RING = 64;

  /**
   * Static rotation of the cylinder blit: 45 degrees of the 120-column ring,
   * so the center of a flat inter-waveform interface faces a cube corner.
   */
  private static final int CYL_ROTATION = 15; // CURATE: verify against physical corner bearing; adjust sign/offset

  // ---- WavMode ----------------------------------------------------------------

  public enum WavMode {
    DUPLICATE("Dup"),
    SHIFT("Shift"),
    SIMPLEX("Simplex"),
    HELIX("Helix");

    private final String label;

    WavMode(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  // ---- Parameters -----------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("UnknownPleasures");

  public final TriggerParameter reseed = bag.register(
    new TriggerParameter("Reseed", this::reseed)
    .setDescription("Clear all ridgelines and refill the full stack from scratch"));

  public final TriggerParameter flip = bag.register(
    new TriggerParameter("Flip", this::flip)
    .setDescription("Reverse the scroll direction; lines then enter from the opposite edge"));

  public final TriggerParameter pulse = bag.register(
    new TriggerParameter("Pulse", this::injectPulse)
    .setDescription("Inject one oversized synthesized pulsar pulse line at the next division's birth"));

  public final CompoundParameter spacing =
    new CompoundParameter("Spacing", 2, 2, 16)
    .setDescription("Rows between adjacent baselines; also the scroll distance per division (zoom about the entry edge)");

  public final CompoundParameter amplitude =
    new CompoundParameter("Amp", 1, 0, 2)
    .setDescription("Peak height in units of Spacing: 0 = flat lines, 2 = a full peak rises two rows-of-spacing tall");

  public final CompoundParameter jaggedness =
    new CompoundParameter("Jag", 0.15, 0, 1)
    .setDescription("Roughness of synthesized profiles; at full Audio, how little the FFT bins are smoothed");

  public final EnumParameter<WavMode> wavMode =
    new EnumParameter<WavMode>("WavMode", WavMode.DUPLICATE)
    .setDescription("How the four walls relate: duplicated, series-shifted, simplex field angles, or helix phase offsets");

  public final DiscreteParameter cylWavs =
    new DiscreteParameter("CylWavs", 4, 1, 5)
    .setDescription("Distinct waveforms wrapped around the cylinder (1-4), flat interfaces facing corners");

  public final CompoundParameter nzScale =
    new CompoundParameter("NzScl", 0.5, 0.05, 2)
    .setDescription("Simplex mode: X-Z scale of the underlying noise field");

  public final CompoundParameter nzTurb =
    new CompoundParameter("NzTurb", 0.3)
    .setDescription("Simplex mode: turbulence (fBm octaves) of the underlying noise field");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Live FFT mix into every displayed line: 0 = pure synthesized/simplex shapes, 1 = 100% FFT");

  public final CompoundParameter decay =
    new CompoundParameter("Decay", 100, 0, 1000)
    .setExponent(2)
    .setUnits(LXParameter.Units.MILLISECONDS)
    .setDescription("Release time of this pattern's own FFT band analysis");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division on which lines emerge from the entry edge; with Spacing, sets the scroll speed");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire one trigger or jump one parameter");

  // ---- Line records (pulse metadata only; shapes are computed live) ---------

  private final long[] ringKey = new long[RING];    // birth index; Long.MIN_VALUE = empty
  private final double[] ringGain = new double[RING];
  private final boolean[] ringSynth = new boolean[RING]; // forced-synth (pulse) line

  /** Bumped on Reseed; salts the per-line seed so a reseed yields fresh shapes. */
  private long reseedSalt = 0;

  // ---- Preallocated render state (no allocation in the render path) ---------

  private final int[][] cubeRaster = new int[NUM_SLOTS][WIDTH * HEIGHT];
  private final int[][] cylHelixRaster = new int[NUM_SLOTS][WIDTH * HEIGHT]; // only used in HELIX with CylWavs != 4
  private final int[][] cylRasterRef = new int[NUM_SLOTS][]; // per-frame references into the above

  private final double[] window = new double[WIDTH];   // center-heavy Hann^p weighting
  private final float[] shapeScratch = new float[WIDTH]; // one line's normalized profile, rebuilt per line
  private final double[] curveY = new double[WIDTH];   // one line's per-column contour rows (fractional)
  private final int[] fftBand = new int[WIDTH];        // mirrored column -> band map (bass at center)
  private final float[] fftA = new float[WIDTH];       // FFT smoothing ping-pong buffers
  private final float[] fftB = new float[WIDTH];
  private final float[] fftShape = new float[WIDTH];   // windowed live FFT profile, rebuilt per frame
  private float fftMax = 0;

  private final GraphicMeter meter;
  private final Random random = new Random(); // reseeded per line via setSeed (no allocation)

  /** True = lines scroll downward (born at top); flipped by the Flip trigger */
  private boolean scrollDown = true;

  /** Pulse trigger armed; consumed by the next entry-edge line (on-division) */
  private boolean pulsePending = false;

  // Division-time tracking (see render): E = U - kBase is the continuous line phase
  private double lastU = Double.NaN;
  private long kBase = 0;

  // Per-frame globals shared by the slot-raster painters
  private WavMode frameMode = WavMode.DUPLICATE;
  private double frameE, frameSp, frameHeightUnit, frameDepth;
  private long frameKLo, frameKHi;

  public UnknownPleasures(LX lx) {
    super(lx);

    // Our own analysis (not the shared engine meter) so Decay can own its release
    this.meter = new GraphicMeter("UPMeter", lx.engine.audio.input.mix);
    startModulator(this.meter);

    addParameter("reseed", this.reseed);
    addParameter("flip", this.flip);
    addParameter("pulse", this.pulse);
    addParameter("spacing", this.spacing);
    addParameter("amplitude", this.amplitude);
    addParameter("jaggedness", this.jaggedness);
    addParameter("wavMode", this.wavMode);
    addParameter("cylWavs", this.cylWavs);
    addParameter("nzScale", this.nzScale);
    addParameter("nzTurb", this.nzTurb);
    addParameter("audio", this.audioDepth);
    addParameter("decay", this.decay);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    bag.jumpable(this.spacing, 2, 8);
    bag.jumpable(this.amplitude, 0.5, 2);
    bag.jumpable(this.jaggedness, 0, 0.5);
    bag.jumpable(this.wavMode);
    bag.jumpable(this.cylWavs);

    for (int x = 0; x < WIDTH; ++x) {
      // Hann window raised to WINDOW_POWER: 1 at center, 0 at the edges
      final double hann = Math.sin(Math.PI * (x + 0.5) / WIDTH);
      this.window[x] = Math.pow(hann, WINDOW_POWER);
    }

    // Mirrored FFT layout: bass (band 0) at the line center, highest band at
    // both edges, so the window nearly deadens the highs
    final int numBands = this.meter.numBands;
    final double center = (WIDTH - 1) * 0.5;         // 24.5
    final double halfSpan = WIDTH / 2 - 1.0;         // 24: distance from innermost to outermost column
    for (int x = 0; x < WIDTH; ++x) {
      final double d = Math.abs(x - center);         // 0.5 .. 24.5
      final int band = (int) Math.round((d - 0.5) * (numBands - 1) / halfSpan);
      this.fftBand[x] = LXUtils.constrain(band, 0, numBands - 1);
    }

    clearRing();
  }

  // ---- Triggers -------------------------------------------------------------

  private void clearRing() {
    Arrays.fill(this.ringKey, Long.MIN_VALUE);
  }

  private void reseed() {
    ++this.reseedSalt; // fresh per-line seeds
    clearRing();
    LX.log("UnknownPleasures: reseed");
  }

  /** Reverse scroll direction; the field mirrors vertically and births switch edges. */
  private void flip() {
    this.scrollDown = !this.scrollDown;
    LX.log("UnknownPleasures: flip -> scrolling " + (this.scrollDown ? "down" : "up"));
  }

  /**
   * Arm one oversized synthesized pulse line. It is consumed by the next
   * entry-edge line, which appears exactly on a tempo division — quantization
   * is structural, no separate grid logic.
   */
  private void injectPulse() {
    this.pulsePending = true;
    LX.log("UnknownPleasures: pulse armed for next division");
  }

  // ---- Line records ---------------------------------------------------------

  private static int ringIndex(long k) {
    return (int) Math.floorMod(k, RING);
  }

  /**
   * Register the line record for birth index k if not already present. Entry-
   * edge births (the newest line each division) consume a pending Pulse.
   */
  private void registerLine(long k, boolean entryEdge) {
    final int idx = ringIndex(k);
    if (this.ringKey[idx] == k) {
      return;
    }
    double gain = 1;
    boolean synth = false;
    if (this.pulsePending && entryEdge) {
      this.pulsePending = false;
      gain = PULSE_GAIN;
      synth = true;
      LX.log("UnknownPleasures: pulse injected");
    }
    this.ringKey[idx] = k;
    this.ringGain[idx] = gain;
    this.ringSynth[idx] = synth;
  }

  private double gainOf(long k) {
    final int idx = ringIndex(k);
    return (this.ringKey[idx] == k) ? this.ringGain[idx] : 1;
  }

  private boolean synthOf(long k) {
    final int idx = ringIndex(k);
    return (this.ringKey[idx] == k) && this.ringSynth[idx];
  }

  // ---- Live shape computation (functional; no baked buffers) ----------------

  /** Deterministic per-line seed; salted so Reseed yields fresh shapes. */
  private long lineSeed(long k) {
    long h = k * 0x9E3779B97F4A7C15L + this.reseedSalt * 0xC2B2AE3D27D4EB4FL;
    h ^= (h >>> 29);
    h *= 0xBF58476D1CE4E5B9L;
    h ^= (h >>> 32);
    return h;
  }

  /**
   * Compute a line's normalized [0,1] profile live from the current parameters.
   * Non-pulse lines in Simplex mode sample the noise field for the given slot;
   * everything else synthesizes a CP 1919-style pulse from the line's seed.
   */
  private void computeShape(float[] out, long index, int slot, boolean forceSynth) {
    if ((this.frameMode == WavMode.SIMPLEX) && !forceSynth) {
      computeSimplex(out, index, slot);
    } else {
      computeSynth(out, index, forceSynth);
    }
  }

  /**
   * Synthesized CP 1919 profile (sum of 2-3 Gaussians) plus smoothed jaggedness
   * noise, windowed to pin the edges flat, then normalized so its tallest point
   * is at most 1 (down-scaled only — short lines stay short, so peaks are "up
   * to" the full height rather than all identical). Pulse lines omit the jag.
   */
  private void computeSynth(float[] out, long index, boolean isPulse) {
    this.random.setSeed(lineSeed(index));
    Arrays.fill(out, 0f);
    final int count = 2 + this.random.nextInt(2);
    for (int g = 0; g < count; ++g) {
      final double c = WIDTH * (0.34 + 0.32 * this.random.nextDouble()); // CURATE: pulse center spread
      final double sigma = 2.5 + 4.5 * this.random.nextDouble();         // CURATE: pulse width range
      final double height = 0.35 + 0.65 * this.random.nextDouble();
      for (int x = 0; x < WIDTH; ++x) {
        final double d = (x - c) / sigma;
        out[x] += (float) (height * Math.exp(-0.5 * d * d));
      }
    }
    final double jag = isPulse ? 0 : this.jaggedness.getValue() * JAG_SCALE;
    double noise = 0;
    float max = 0;
    for (int x = 0; x < WIDTH; ++x) {
      noise = NOISE_SMOOTH * noise + (1 - NOISE_SMOOTH) * (this.random.nextDouble() * 2 - 1);
      final float v = (float) ((out[x] + jag * noise) * this.window[x]);
      out[x] = v;
      if (v > max) {
        max = v;
      }
    }
    if (max > 1) {
      final float inv = 1f / max;
      for (int x = 0; x < WIDTH; ++x) {
        out[x] *= inv;
      }
    }
  }

  /**
   * Simplex mode: sample the underlying 3D noise field along one wall of the
   * building, live from NzScl/NzTurb. The four slots trace the four sides of a
   * square in the field's X-Z plane (adjacent walls share corners, so the
   * field reads as one volume wrapped by the architecture); the field advances
   * in Y per line index. Windowed to pin the edges flat; naturally in [0,1].
   */
  private void computeSimplex(float[] out, long index, int slot) {
    final double scale = this.nzScale.getValue();
    final float sx = (float) (scale * NZ_X_STEP);
    final float py = (float) (index * scale * NZ_LINE_STEP);
    final float c = 25 * sx; // wall offset from field center
    final int octaves = 1 + (int) Math.round(3 * this.nzTurb.getValue()); // 1..4
    for (int x = 0; x < WIDTH; ++x) {
      final float u = (float) ((x - (WIDTH - 1) * 0.5) * sx);
      final float px, pz;
      switch (slot) {
        case 0:  px = u;  pz = c;  break; // front
        case 1:  px = c;  pz = -u; break; // right
        case 2:  px = -u; pz = -c; break; // back
        default: px = -c; pz = u;  break; // left
      }
      final float v = (octaves == 1)
        ? LXUtils.noise(px, py, pz)
        : LXUtils.noiseFBM(px, py, pz, NZ_LACUNARITY, NZ_GAIN, octaves);
      out[x] = (float) LXUtils.constrain((0.5 + 0.5 * v) * this.window[x], 0, 1);
    }
  }

  // ---- Live FFT profile -----------------------------------------------------

  /**
   * Rebuild the mirrored live FFT profile: bass bands at the center, higher
   * bands replicated bidirectionally outward, then smoothed per Jag and
   * deadened toward the edges by the window. Jag = 1 leaves the 16 mirrored
   * bins just barely smoothed (one 3-tap pass); lower Jag averages wider.
   */
  private void buildFftShape() {
    float[] src = this.fftA;
    float[] dst = this.fftB;
    for (int x = 0; x < WIDTH; ++x) {
      src[x] = this.meter.getBandf(this.fftBand[x]);
    }
    final int passes = 1 + (int) Math.round((1 - this.jaggedness.getValue()) * 5); // CURATE: smoothing range
    for (int p = 0; p < passes; ++p) {
      for (int x = 0; x < WIDTH; ++x) {
        final float a = src[Math.max(0, x - 1)];
        final float b = src[Math.min(WIDTH - 1, x + 1)];
        dst[x] = 0.25f * a + 0.5f * src[x] + 0.25f * b;
      }
      final float[] t = src;
      src = dst;
      dst = t;
    }
    float max = 0;
    for (int x = 0; x < WIDTH; ++x) {
      final float v = (float) (src[x] * this.window[x]);
      this.fftShape[x] = v;
      if (v > max) {
        max = v;
      }
    }
    this.fftMax = max;
  }

  // ---- Render ---------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    // Decay knob owns our meter's release (the meter's parameter is parented
    // to the meter, so it can't be registered on the pattern directly)
    this.meter.release.setValue(this.decay.getValue());

    // Continuous division-time U; integer-only folds preserve fractional phase
    // so line emergence stays exactly on-division across division changes and
    // transport jumps (BPM changes keep U continuous, nothing to fold).
    final Tempo tempo = this.lx.engine.tempo;
    final Tempo.Division div = this.tempoDiv.getEnum();
    final double U = tempo.getCycleCount(div) + tempo.getBasis(div);
    if (Double.isNaN(this.lastU)) {
      this.kBase = Math.round(U);
    } else {
      final double dU = U - this.lastU;
      if ((dU < 0) || (dU > FOLD_DIVS)) {
        this.kBase += Math.round(dU);
      }
    }
    this.lastU = U;
    final double E = U - this.kBase;

    final double sp = this.spacing.getValue();
    final double amp = this.amplitude.getValue();
    this.frameMode = this.wavMode.getEnum();

    // Existence window of integer birth indices. marginRows is the tallest a
    // ridge can reach above its baseline (peak = amp x spacing x pulse gain);
    // lines persist while any part of that span is on-field. Down-scroll: entry
    // edge is the top (kHi includes one not-yet-emerged line for AA fade-in).
    // Up-scroll mirrors: pre-birth below the bottom by the ridge margin so tall
    // contours never pop in. The trailing -1 covers helix phase lag.
    final double marginRows = amp * sp * PULSE_GAIN;
    final long kHi, kLo;
    if (this.scrollDown) {
      kHi = (long) Math.floor(E) + 1;
      kLo = (long) Math.ceil(E - (HEIGHT + marginRows) / sp) - 1;
    } else {
      kHi = (long) Math.floor(E + marginRows / sp) + 1;
      kLo = (long) Math.ceil(E - (HEIGHT + 2) / sp) - 1;
    }

    // Register line records (pulse tracking) for the window
    for (long k = kLo; k <= kHi; ++k) {
      registerLine(k, k == kHi);
    }

    buildFftShape();

    this.frameE = E;
    this.frameSp = sp;
    this.frameKLo = kLo;
    this.frameKHi = kHi;
    this.frameHeightUnit = amp * sp; // rows per unit-normalized peak
    this.frameDepth = this.audioDepth.getValue();

    // Paint the slot rasters
    final int active = (this.frameMode == WavMode.DUPLICATE) ? 1 : NUM_SLOTS;
    for (int s = 0; s < active; ++s) {
      paintSlotRaster(this.cubeRaster[s], s, NUM_SLOTS);
    }

    // Cylinder rasters: reuse the cube's, except HELIX with a segment count
    // other than 4, whose phase lags depend on the segment count
    final int nSeg = this.cylWavs.getValuei();
    if ((this.frameMode == WavMode.HELIX) && (nSeg != NUM_SLOTS)) {
      for (int s = 0; s < nSeg; ++s) {
        paintSlotRaster(this.cylHelixRaster[s], s, nSeg);
        this.cylRasterRef[s] = this.cylHelixRaster[s];
      }
    } else {
      for (int s = 0; s < nSeg; ++s) {
        this.cylRasterRef[s] = this.cubeRaster[s % active];
      }
    }

    blitFace(Apotheneum.cube.exterior.front, this.cubeRaster[0]);
    blitFace(Apotheneum.cube.exterior.right, this.cubeRaster[1 % active]);
    blitFace(Apotheneum.cube.exterior.back, this.cubeRaster[2 % active]);
    blitFace(Apotheneum.cube.exterior.left, this.cubeRaster[3 % active]);
    copyCubeExterior();
    blitCylinder(nSeg);
    copyCylinderExterior();
  }

  /**
   * Paint one slot's 50x45 field: back-to-front painter's order (ascending
   * baseline) so each nearer line's black fill erases the contours behind it.
   * numSlots divides the helix phase lag (4 walls on the cube, CylWavs segments
   * around the cylinder).
   */
  private void paintSlotRaster(int[] raster, int slot, int numSlots) {
    Arrays.fill(raster, LXColor.BLACK);
    if (this.scrollDown) {
      // Larger k = newer = higher on screen = farther; paint first
      for (long k = this.frameKHi; k >= this.frameKLo; --k) {
        paintLineAt(raster, k, slot, numSlots);
      }
    } else {
      // Larger k = newer = lower on screen = nearer; paint last
      for (long k = this.frameKLo; k <= this.frameKHi; ++k) {
        paintLineAt(raster, k, slot, numSlots);
      }
    }
  }

  private void paintLineAt(int[] raster, long k, int slot, int numSlots) {
    // Which line's signal this wall shows, and its position offset
    final long index = (this.frameMode == WavMode.SHIFT) ? (k + slot) : k;
    final double lag = (this.frameMode == WavMode.HELIX) ? (slot / (double) numSlots) : 0;
    final boolean synth = synthOf(index);
    computeShape(this.shapeScratch, index, slot, synth);

    final double progress = (this.frameE - k - lag) * this.frameSp;
    final double baseline = this.scrollDown ? progress : HEIGHT - progress;
    drawCurve(raster, this.shapeScratch, baseline, gainOf(index));
  }

  /**
   * Rasterize one line as a connected antialiased curve. The displayed profile
   * is the live mix of the baked-free signal and the mirrored FFT profile. Per
   * column the contour is drawn across the vertical span between its neighbor-
   * midpoints, so consecutive columns share an endpoint and the ridge stays
   * connected even where it is steep or tall; below the contour the interior is
   * filled black to occlude the lines behind it.
   */
  private void drawCurve(int[] raster, float[] shape, double baseline, double gain) {
    final double heightUnit = this.frameHeightUnit * gain;
    final double depth = this.frameDepth;

    // Cheap whole-line culls
    final double peakBound = heightUnit * Math.max(1.0, this.fftMax);
    if (baseline - peakBound > HEIGHT + 1 || baseline < -1) {
      return;
    }

    for (int x = 0; x < WIDTH; ++x) {
      final double disp = shape[x] + depth * (this.fftShape[x] - shape[x]);
      this.curveY[x] = baseline - disp * heightUnit;
    }

    final int baseRow = (int) Math.floor(baseline);
    for (int x = 0; x < WIDTH; ++x) {
      // Contour endpoints at the column's left/right edges = midpoints with
      // neighbors, so column x's right edge == column x+1's left edge (no gap)
      final double yL = (x > 0) ? 0.5 * (this.curveY[x - 1] + this.curveY[x]) : this.curveY[x];
      final double yR = (x < WIDTH - 1) ? 0.5 * (this.curveY[x] + this.curveY[x + 1]) : this.curveY[x];
      // Include the sample itself so local peaks/valleys (outside the midpoint
      // span) still get their tip drawn
      final double yTop = Math.min(this.curveY[x], Math.min(yL, yR));
      final double yBot = Math.max(this.curveY[x], Math.max(yL, yR));

      // Interior fill: everything strictly below the contour, down to baseline
      final int fillStart = Math.max(0, (int) Math.floor(yBot) + 1);
      final int fillEnd = Math.min(HEIGHT - 1, baseRow);
      for (int y = fillStart; y <= fillEnd; ++y) {
        raster[y * WIDTH + x] = LXColor.BLACK;
      }

      // Contour stroke
      if (yBot - yTop < 1e-3) {
        // Nearly flat: classic two-row antialiased stroke at the sample
        final double yc = this.curveY[x];
        final int r = (int) Math.floor(yc);
        final double f = yc - r;
        if (r >= 0 && r < HEIGHT && f < 0.98) {
          raster[r * WIDTH + x] = LXColor.gray(100 * (1 - f));
        }
        final int below = r + 1;
        if (below >= 0 && below < HEIGHT && f > 0.02) {
          raster[below * WIDTH + x] = LXColor.gray(100 * f);
        }
      } else {
        // Sloped: light every row the contour passes through in this column,
        // antialiasing the two endpoints by their fractional coverage
        final int y0 = (int) Math.floor(yTop);
        final int y1 = (int) Math.floor(yBot);
        for (int y = y0; y <= y1; ++y) {
          if (y < 0 || y >= HEIGHT) {
            continue;
          }
          final double cov = Math.min(y + 1, yBot) - Math.max(y, yTop);
          if (cov > 0) {
            raster[y * WIDTH + x] = LXColor.gray(100 * Math.min(1.0, cov));
          }
        }
      }
    }
  }

  private void blitFace(Apotheneum.Cube.Face face, int[] raster) {
    for (int x = 0; x < WIDTH; ++x) {
      final Apotheneum.Column column = face.columns[x];
      final int columnHeight = column.points.length; // door columns are shorter
      for (int y = 0; y < columnHeight; ++y) {
        colors[column.points[y].index] = raster[y * WIDTH + x];
      }
    }
  }

  /**
   * Blit the cylinder as nSeg equal segments, each resampling one slot raster
   * to its arc, rotated CYL_ROTATION columns so a flat interface between
   * waveforms (windowed to the baseline at both ends) centers on a corner.
   */
  private void blitCylinder(int nSeg) {
    final Apotheneum.Cylinder.Orientation cylinder = Apotheneum.cylinder.exterior;
    final int cylinderWidth = cylinder.width(); // 120, divisible by 1..4
    final int segWidth = cylinderWidth / nSeg;
    for (int cx = 0; cx < cylinderWidth; ++cx) {
      final int r = (cx + CYL_ROTATION) % cylinderWidth;
      final int[] raster = this.cylRasterRef[r / segWidth];
      final int x = (r % segWidth) * WIDTH / segWidth; // segment-local resample to the 50-wide field
      final Apotheneum.Column column = cylinder.column(cx);
      final int columnHeight = column.points.length; // 43, or shorter at doors
      for (int y = 0; y < columnHeight; ++y) {
        colors[column.points[y].index] = raster[y * WIDTH + x];
      }
    }
  }
}
