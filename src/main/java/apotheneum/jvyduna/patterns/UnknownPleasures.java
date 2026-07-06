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
 * the entry edge). Each line is a 50-sample height profile; profiles come from
 * synthesized CP 1919-style pulse shapes (sums of 2-3 random Gaussians), or in
 * Simplex mode from an underlying 3D noise field sampled around the building's
 * perimeter. The Audio knob live-mixes a bass-centered, mirrored FFT profile
 * into every displayed line each frame.
 *
 * Rendering uses the painter's algorithm per column, back to front: each
 * line's black fill (contour down to its own baseline) erases the lines behind
 * it, producing the classic ridgeline occlusion. Peaks are unclamped and
 * freely overlap the lines behind them; the fill handles the layering. The
 * contour is a white anti-aliased two-row stroke (colorize externally).
 *
 * WavMode makes the four cube walls distinct (Dup / Shift / Simplex / Helix),
 * each wall blitted from its own 50x45 slot raster; CylWavs wraps 1-4 distinct
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

  /**
   * Sanity ceiling on a normalized shape sample. Not an aesthetic clamp: peaks
   * routinely exceed 1.0 and overlap the lines behind them (the cover's look);
   * this only bounds pathological Gaussian pile-ups so RIDGE_MARGIN stays finite.
   */
  private static final double SHAPE_MAX = 2.0;

  /**
   * Tallest possible ridge in rows: SHAPE_MAX (2) x amplitude max (12) x pulse
   * gain (1.8) = 43.2, rounded up. Used only for the line existence window so
   * ridges never pop in or out at the field edges.
   */
  private static final int RIDGE_MARGIN = 44;

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
   * Ring buffer of line entries keyed by birth index. Must exceed the widest
   * existence window: (45 visible + 44 ridge margin) / min spacing 2 + helix
   * and shift slack ~ 52.
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
    new CompoundParameter("Amp", 7, 2, 12)
    .setDescription("Peak ridge height in rows at the center of a line");

  public final CompoundParameter jaggedness =
    new CompoundParameter("Jag", 0.15, 0, 1)
    .setDescription("Noise baked into newborn profiles; at full Audio, how little the FFT bins are smoothed");

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

  // ---- Line store (ring buffer keyed by integer birth index) ----------------

  private final long[] ringKey = new long[RING];    // birth index; Long.MIN_VALUE = empty
  private final double[] ringGain = new double[RING];
  private final float[] ringMax = new float[RING];  // max shape sample, for paint early-outs
  private final float[][][] ringShape = new float[RING][NUM_SLOTS][WIDTH]; // plane 0 only, except SIMPLEX

  // ---- Preallocated render state (no allocation in the render path) ---------

  private final int[][] cubeRaster = new int[NUM_SLOTS][WIDTH * HEIGHT];
  private final int[][] cylHelixRaster = new int[NUM_SLOTS][WIDTH * HEIGHT]; // only used in HELIX with CylWavs != 4
  private final int[][] cylRasterRef = new int[NUM_SLOTS][]; // per-frame references into the above

  private final double[] window = new double[WIDTH];   // center-heavy Hann^p weighting
  private final double[] synthScratch = new double[WIDTH]; // synthesized pulse shape
  private final int[] fftBand = new int[WIDTH];        // mirrored column -> band map (bass at center)
  private final float[] fftA = new float[WIDTH];       // FFT smoothing ping-pong buffers
  private final float[] fftB = new float[WIDTH];
  private final float[] fftShape = new float[WIDTH];   // windowed live FFT profile, rebuilt per frame
  private float fftMax = 0;

  private final GraphicMeter meter;
  private final Random random = new Random();

  /** True = lines scroll downward (born at top); flipped by the Flip trigger */
  private boolean scrollDown = true;

  /** Pulse trigger armed; consumed by the next entry-edge line generation (on-division) */
  private boolean pulsePending = false;

  // Division-time tracking (see render): E = U - kBase is the continuous line phase
  private double lastU = Double.NaN;
  private long kBase = 0;

  // Per-frame globals shared by the slot-raster painters
  private double frameE, frameSp, frameAmpRows, frameDepth;
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
    bag.jumpable(this.amplitude, 4, 12);
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

  /** Empty the ring; the next frame's reconcile refills every grid-locked position with fresh shapes. */
  private void clearRing() {
    Arrays.fill(this.ringKey, Long.MIN_VALUE);
  }

  private void reseed() {
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
   * entry-edge line generation, which happens exactly on a tempo division —
   * quantization is structural, no separate grid logic.
   */
  private void injectPulse() {
    this.pulsePending = true;
    LX.log("UnknownPleasures: pulse armed for next division");
  }

  @Override
  public void onParameterChanged(LXParameter parameter) {
    super.onParameterChanged(parameter);
    if (parameter == this.wavMode) {
      // SIMPLEX needs planes 1-3 populated and SHIFT needs lookahead entries;
      // cheapest correct invalidation is a full regenerate (reseed semantics)
      clearRing();
    }
  }

  // ---- Shape generation (event-rate, uses preallocated scratch only) --------

  private static int ringIndex(long k) {
    return (int) Math.floorMod(k, RING);
  }

  /**
   * Generate the ring entry for birth index k. Entry-edge births (k at the top
   * of the window) consume a pending Pulse; backfills (reseed, spacing zoom-out,
   * flip) never do.
   */
  private void generateEntry(int idx, long k, boolean entryEdge) {
    double gain = 1;
    boolean forceSynth = false;
    if (this.pulsePending && entryEdge) {
      this.pulsePending = false;
      gain = PULSE_GAIN;
      forceSynth = true;
      LX.log("UnknownPleasures: pulse injected");
    }
    final float[][] planes = this.ringShape[idx];
    float max;
    if ((this.wavMode.getEnum() == WavMode.SIMPLEX) && !forceSynth) {
      max = 0;
      for (int s = 0; s < NUM_SLOTS; ++s) {
        max = Math.max(max, fillSimplexShape(planes[s], s, k));
      }
    } else {
      max = fillSynthShape(planes[0], forceSynth);
      if (this.wavMode.getEnum() == WavMode.SIMPLEX) {
        // A pulse in Simplex mode appears on all walls at once
        for (int s = 1; s < NUM_SLOTS; ++s) {
          System.arraycopy(planes[0], 0, planes[s], 0, WIDTH);
        }
      }
    }
    this.ringKey[idx] = k;
    this.ringGain[idx] = gain;
    this.ringMax[idx] = max;
  }

  /**
   * Synthesized CP 1919 profile plus smoothed jaggedness noise, pinned to the
   * baseline at the edges by the center-heavy window. Returns the max sample.
   */
  private float fillSynthShape(float[] out, boolean isPulse) {
    synthesizePulse(this.synthScratch);
    final double jag = isPulse ? 0 : this.jaggedness.getValue() * JAG_SCALE;
    double noise = 0;
    float max = 0;
    for (int x = 0; x < WIDTH; ++x) {
      noise = NOISE_SMOOTH * noise + (1 - NOISE_SMOOTH) * (this.random.nextDouble() * 2 - 1);
      final double v = this.synthScratch[x] + jag * noise;
      final float s = (float) LXUtils.constrain(v * this.window[x], 0, SHAPE_MAX);
      out[x] = s;
      if (s > max) {
        max = s;
      }
    }
    return max;
  }

  /** CP 1919-style pulse: sum of 2-3 Gaussians with random center/width/height near the middle. */
  private void synthesizePulse(double[] out) {
    Arrays.fill(out, 0);
    final int count = 2 + this.random.nextInt(2);
    for (int g = 0; g < count; ++g) {
      final double center = WIDTH * (0.34 + 0.32 * this.random.nextDouble()); // CURATE: pulse center spread
      final double sigma = 2.5 + 4.5 * this.random.nextDouble();              // CURATE: pulse width range
      final double height = 0.35 + 0.65 * this.random.nextDouble();
      for (int x = 0; x < WIDTH; ++x) {
        final double d = (x - center) / sigma;
        out[x] += height * Math.exp(-0.5 * d * d);
      }
    }
  }

  /**
   * Simplex mode: sample the underlying 3D noise field along one wall of the
   * building. The four slots trace the four sides of a square in the field's
   * X-Z plane (adjacent walls share corners, so the field reads as one volume
   * wrapped by the architecture); the field advances in Y per line index.
   * Returns the max sample.
   */
  private float fillSimplexShape(float[] out, int slot, long k) {
    final double scale = this.nzScale.getValue();
    final float sx = (float) (scale * NZ_X_STEP);
    final float py = (float) (k * scale * NZ_LINE_STEP);
    final float c = 25 * sx; // wall offset from field center
    final int octaves = 1 + (int) Math.round(3 * this.nzTurb.getValue()); // 1..4
    float max = 0;
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
      final float s = (float) LXUtils.constrain((0.5 + 0.5 * v) * this.window[x], 0, SHAPE_MAX);
      out[x] = s;
      if (s > max) {
        max = s;
      }
    }
    return max;
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
    final WavMode mode = this.wavMode.getEnum();

    // Existence window of integer birth indices. Down-scroll: the entry edge
    // is the top (kHi includes one not-yet-emerged line for AA fade-in); the
    // exit allows RIDGE_MARGIN of contour to trail below the field. Up-scroll
    // mirrors: pre-birth below the bottom by the ridge margin so tall contours
    // never pop in, exit at the top. The trailing -1 covers helix phase lag.
    final long kHi, kLo;
    if (this.scrollDown) {
      kHi = (long) Math.floor(E) + 1;
      kLo = (long) Math.ceil(E - (HEIGHT + RIDGE_MARGIN) / sp) - 1;
    } else {
      kHi = (long) Math.floor(E + RIDGE_MARGIN / sp) + 1;
      kLo = (long) Math.ceil(E - (HEIGHT + 1) / sp) - 1;
    }
    // SHIFT walls display the series up to 3 lines ahead
    final long shapeHi = kHi + ((mode == WavMode.SHIFT) ? (NUM_SLOTS - 1) : 0);

    // Reconcile: generate any missing entries (event-rate work)
    for (long k = kLo; k <= shapeHi; ++k) {
      final int idx = ringIndex(k);
      if (this.ringKey[idx] != k) {
        generateEntry(idx, k, k >= kHi);
      }
    }

    buildFftShape();

    this.frameE = E;
    this.frameSp = sp;
    this.frameKLo = kLo;
    this.frameKHi = kHi;
    this.frameAmpRows = this.amplitude.getValue();
    this.frameDepth = this.audioDepth.getValue();

    // Paint the slot rasters
    final int active = (mode == WavMode.DUPLICATE) ? 1 : NUM_SLOTS;
    for (int s = 0; s < active; ++s) {
      final int plane = (mode == WavMode.SIMPLEX) ? s : 0;
      final int shift = (mode == WavMode.SHIFT) ? s : 0;
      final double lag = (mode == WavMode.HELIX) ? s / (double) NUM_SLOTS : 0;
      paintSlotRaster(this.cubeRaster[s], plane, shift, lag);
    }

    // Cylinder rasters: reuse the cube's, except HELIX with a segment count
    // other than 4, whose phase lags depend on the segment count
    final int nSeg = this.cylWavs.getValuei();
    if ((mode == WavMode.HELIX) && (nSeg != NUM_SLOTS)) {
      for (int s = 0; s < nSeg; ++s) {
        paintSlotRaster(this.cylHelixRaster[s], 0, 0, s / (double) nSeg);
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
   */
  private void paintSlotRaster(int[] raster, int plane, int shift, double lag) {
    Arrays.fill(raster, LXColor.BLACK);
    if (this.scrollDown) {
      // Larger k = newer = higher on screen = farther; paint first
      for (long k = this.frameKHi; k >= this.frameKLo; --k) {
        paintLineAt(raster, k, plane, shift, lag);
      }
    } else {
      // Larger k = newer = lower on screen = nearer; paint last
      for (long k = this.frameKLo; k <= this.frameKHi; ++k) {
        paintLineAt(raster, k, plane, shift, lag);
      }
    }
  }

  private void paintLineAt(int[] raster, long k, int plane, int shift, double lag) {
    final int idx = ringIndex(k + shift);
    if (this.ringKey[idx] != k + shift) {
      return; // not generated (transiently possible across a mode change)
    }
    final double progress = (this.frameE - k - lag) * this.frameSp;
    final double b = this.scrollDown ? progress : HEIGHT - progress;
    paintLine(raster, this.ringShape[idx][plane], this.ringMax[idx], b, this.ringGain[idx]);
  }

  /**
   * Paint one line into a raster: per column, black-fill from just under the
   * contour down to the baseline (occluding whatever was behind — peaks are
   * unclamped and freely overlap the lines above), then draw the contour as a
   * white brightness-weighted two-row stroke at its fractional height, so
   * sub-pixel scrolling stays smooth. The displayed profile is the per-frame
   * live mix of the line's baked shape and the mirrored FFT profile.
   */
  private void paintLine(int[] raster, float[] shape, float shapeMax, double b, double gain) {
    if (b < -1) {
      return; // fully above the field
    }
    final double depth = this.frameDepth;
    final double lineAmp = this.frameAmpRows * gain;
    // Upper bound on this line's mixed height, for the fully-below early-out
    final double hBound = LXUtils.lerp(shapeMax, this.fftMax, depth) * lineAmp;
    if (b - hBound > HEIGHT) {
      return; // fully below the field
    }
    final int baseRow = (int) Math.floor(b);
    for (int x = 0; x < WIDTH; ++x) {
      final double s = shape[x] + depth * (this.fftShape[x] - shape[x]);
      final double yc = b - s * lineAmp; // contour row (fractional)
      final int contourRow = (int) Math.floor(yc);

      // Fill (silhouette interior): rows strictly below the contour, down to the baseline
      final int fillStart = Math.max(0, contourRow + 1);
      final int fillEnd = Math.min(HEIGHT - 1, baseRow);
      for (int y = fillStart; y <= fillEnd; ++y) {
        raster[y * WIDTH + x] = LXColor.BLACK;
      }

      // Contour stroke, anti-aliased across two rows by the fractional part
      final double f = yc - contourRow;
      if (contourRow >= 0 && contourRow < HEIGHT && f < 0.98) {
        raster[contourRow * WIDTH + x] = LXColor.gray(100 * (1 - f));
      }
      final int below = contourRow + 1;
      if (below >= 0 && below < HEIGHT && f > 0.02) {
        raster[below * WIDTH + x] = LXColor.gray(100 * f);
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
