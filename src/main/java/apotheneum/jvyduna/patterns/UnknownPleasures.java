package apotheneum.jvyduna.patterns;

import java.util.Arrays;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.TempoLock;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * CP 1919 pulsar ridgeline waterfall — the 1979 Joy Division "Unknown Pleasures"
 * album cover as a scrolling stack of occluding spectrum silhouettes.
 *
 * A pool of horizontal ridgelines scrolls vertically. Each line is a 50-sample
 * height profile born from the live FFT (center-weighted so the edges pin flat
 * to the baseline) or, in silence, from synthesized CP 1919-style pulse shapes
 * (sums of 2-3 random Gaussians). Rendering uses the painter's algorithm per
 * column, back to front: each line's black fill (contour down to baseline)
 * erases the lines behind it, producing the classic ridgeline occlusion; the
 * contour itself is a bright anti-aliased stroke (classic white, tintable).
 *
 * The 50x45 field is computed once per frame into a raster, blitted to the
 * front cube face, copied to all cube faces and interiors, and sampled onto
 * the cylinder with x mapped 120 -> 50.
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

  // ---- Timing (physical intent) ---------------------------------------------

  /** Full-field traversal time at energy = 0: 45 rows in 12 s (soothing drift) */
  private static final double SCROLL_SEC_AMBIENT = 12;

  /** Full-field traversal time at energy = 1: 45 rows in 6 s — never faster (sim cap is 5 s) */
  private static final double SCROLL_SEC_PEAK = 6;

  /**
   * Hard series cap on sustained motion: full-field traversal must never take
   * less than 5 s, even after tempo-sync retiming. The retime() upper clamp is
   * derived from this per call, so a 1.4x nudge at peak energy cannot break it.
   */
  private static final double SCROLL_SEC_CAP = 5;

  // ---- Line geometry --------------------------------------------------------

  /**
   * Tallest possible ridge in rows: amplitude max (12) x energy amp factor
   * (1.15) x pulse gain (1.8) = 24.84, rounded up. Also the off-screen margin
   * for line birth/death so ridges never pop in or out.
   */
  private static final int MAX_RIDGE_ROWS = 25;

  /**
   * Line pool size: visible span (45 rows) + off-screen ridge margin (25) at
   * the minimum spacing of 2 rows = 35 concurrent lines, plus slack.
   */
  private static final int MAX_LINES = 48;

  /** Amplitude multiplier baked into the next-born line after a bass hit */
  private static final double BASS_BOOST = 1.5;

  /** Amplitude multiplier of a Pulse-trigger line (oversized, always synthesized) */
  private static final double PULSE_GAIN = 1.8;

  /** Energy scaling of ridge amplitude: subtle growth from ambient to peak */
  private static final double AMP_ENERGY_AMBIENT = 0.85; // CURATE: subtle; may want stronger swing
  private static final double AMP_ENERGY_PEAK = 1.15;

  // ---- Shape synthesis ------------------------------------------------------

  /** audio.level at/above which newborn shapes are fully FFT-driven; below, crossfade to synth pulses */
  private static final double FFT_CROSSFADE_LEVEL = 0.15; // CURATE: crossfade knee vs typical music level

  /** Center-weighting window exponent (Hann^p); higher pins the edges flatter to the baseline */
  private static final double WINDOW_POWER = 1.5; // CURATE: edge-pinning strength

  /** Jaggedness noise contribution at jaggedness = 1, in normalized shape units */
  private static final double JAG_SCALE = 0.5; // CURATE: max roughness before lines read as static

  /** One-pole smoothing of the per-sample jaggedness noise walk (0 = white, ~1 = very smooth) */
  private static final double NOISE_SMOOTH = 0.5; // CURATE: noise spatial scale at LED pitch

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
    .setDescription("Inject one oversized synthesized pulsar pulse line at the birth edge"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 soothing ambient drift, 0.6-1.0 160 BPM show pace");

  public final CompoundParameter spacing =
    new CompoundParameter("Spacing", 2, 2, 8)
    .setDescription("Rows between adjacent ridgeline baselines (min 2 so lines stay separate at LED-gap scale)");

  public final CompoundParameter amplitude =
    new CompoundParameter("Amp", 7, 2, 12)
    .setDescription("Peak ridge height in rows at the center of a line");

  public final CompoundParameter jaggedness =
    new CompoundParameter("Jag", 0.15, 0, 1)
    .setDescription("Small-scale noise baked into each newborn line's profile");

  public final CompoundParameter tintHue =
    new CompoundParameter("Tint", 0, 0, 360)
    .setDescription("Hue of the ridgeline strokes when TintAmt > 0");

  public final CompoundParameter tintAmount =
    new CompoundParameter("TintAmt", 0, 0, 1)
    .setDescription("Stroke saturation: 0 = classic white lines, 1 = fully saturated Tint hue");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final BooleanParameter sync =
    new BooleanParameter("Sync", true)
    .setDescription("Lock motion events to the tempo grid; off restores free-running timing");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division that motion events land on when Sync is enabled");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire one trigger or jump one parameter");

  // ---- Line pool (preallocated; recycled, never allocated per frame) --------

  private static final class Line {
    boolean alive;
    double baseline;  // row of the flat base (y = 0 is TOP; grows downward)
    double gain;      // amplitude multiplier baked at birth (bass boost / pulse)
    final float[] shape = new float[WIDTH]; // normalized 0..1 ridge profile
  }

  private final Line[] lines = new Line[MAX_LINES];
  private final int[] order = new int[MAX_LINES];        // painter's sort scratch
  private final int[] raster = new int[WIDTH * HEIGHT];  // one frame of the 50x45 field
  private final double[] window = new double[WIDTH];     // center-heavy Hann^p weighting
  private final double[] fftScratch = new double[WIDTH]; // FFT resampled to 50 samples
  private final double[] synthScratch = new double[WIDTH]; // synthesized pulse shape

  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  /** True = lines scroll downward (born at top); flipped by the Flip trigger */
  private boolean scrollDown = true;

  /**
   * Amplitude multiplier armed for the next-born line. Set from a bass
   * transient (scaled by audio depth toward BASS_BOOST); consumed at birth.
   */
  private double boostGain = 1;

  /** Multiplier on the scroll rate while Sync is on; recomputed at each line birth */
  private double syncScale = 1;

  /** Pulse trigger deferred to the next tempo-grid boundary while Sync is on */
  private boolean pulsePending = false;

  public UnknownPleasures(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("reseed", this.reseed);
    addParameter("flip", this.flip);
    addParameter("pulse", this.pulse);
    addParameter("energy", this.energy);
    addParameter("spacing", this.spacing);
    addParameter("amplitude", this.amplitude);
    addParameter("jaggedness", this.jaggedness);
    addParameter("tintHue", this.tintHue);
    addParameter("tintAmount", this.tintAmount);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    bag.jumpable(this.spacing, 2, 6);
    bag.jumpable(this.amplitude, 4, 12);
    bag.jumpable(this.tintHue);
    bag.jumpable(this.jaggedness, 0, 0.5);

    for (int i = 0; i < MAX_LINES; ++i) {
      this.lines[i] = new Line();
    }
    for (int x = 0; x < WIDTH; ++x) {
      // Hann window raised to WINDOW_POWER: 1 at center, 0 at the edges
      final double hann = Math.sin(Math.PI * (x + 0.5) / WIDTH);
      this.window[x] = Math.pow(hann, WINDOW_POWER);
    }

    reseed();
  }

  // ---- Triggers -------------------------------------------------------------

  /** Kill every line and refill the visible field at the current spacing. */
  private void reseed() {
    for (Line line : this.lines) {
      line.alive = false;
    }
    final double spacing = this.spacing.getValue();
    // Fill the visible field plus, when scrolling up, the bottom entry margin:
    // otherwise advance() would immediately birth mid-margin lines whose tall
    // contours could already poke into view (a one-frame pop-in).
    final double start = this.scrollDown ? HEIGHT : HEIGHT + MAX_RIDGE_ROWS;
    for (double b = start; b >= -1; b -= spacing) {
      birth(b, 1, false);
    }
    LX.log("UnknownPleasures: reseed");
  }

  /** Reverse scroll direction; births switch to the opposite edge. */
  private void flip() {
    this.scrollDown = !this.scrollDown;
    LX.log("UnknownPleasures: flip -> scrolling " + (this.scrollDown ? "down" : "up"));
  }

  /**
   * Pulse trigger handler. With Sync on the launch is quantized: it arms a
   * pending pulse that fires on the next tempo-grid boundary (in render());
   * with Sync off it fires immediately, exactly the pre-sync behavior.
   */
  private void injectPulse() {
    if (this.sync.isOn()) {
      this.pulsePending = true;
      return;
    }
    firePulse();
  }

  /** Inject one oversized synthesized pulse line at the birth edge, spacing-safe. */
  private void firePulse() {
    final double spacing = this.spacing.getValue();
    boolean any = false;
    double edge = 0;
    for (Line line : this.lines) {
      if (line.alive) {
        if (!any) {
          edge = line.baseline;
          any = true;
        } else {
          edge = this.scrollDown ? Math.min(edge, line.baseline) : Math.max(edge, line.baseline);
        }
      }
    }
    final double b = any
      ? (this.scrollDown ? edge - spacing : edge + spacing)
      : (this.scrollDown ? 0 : HEIGHT);
    birth(b, PULSE_GAIN, true);
    LX.log("UnknownPleasures: pulse injected");
  }

  // ---- Line lifecycle -------------------------------------------------------

  /** Free slot if one exists, else recycle the line farthest along its exit path. */
  private Line acquireSlot() {
    Line worst = this.lines[0];
    for (Line line : this.lines) {
      if (!line.alive) {
        return line;
      }
      if (this.scrollDown ? (line.baseline > worst.baseline) : (line.baseline < worst.baseline)) {
        worst = line;
      }
    }
    return worst;
  }

  private void birth(double baseline, double gain, boolean forceSynth) {
    final Line line = acquireSlot();
    line.alive = true;
    line.baseline = baseline;
    line.gain = gain;
    generateShape(line.shape, forceSynth);
  }

  /** Amplitude multiplier for the next normal birth, consuming a pending bass boost. */
  private double nextGain() {
    final double gain = this.boostGain;
    this.boostGain = 1;
    return gain;
  }

  /**
   * Scroll all lines by dRows, kill exited lines, and birth new ones at the
   * entry edge. Returns true if any line was born (a sync retiming moment).
   */
  private boolean advance(double dRows) {
    final double delta = this.scrollDown ? dRows : -dRows;
    for (Line line : this.lines) {
      if (!line.alive) {
        continue;
      }
      line.baseline += delta;
      // Ridges extend up to MAX_RIDGE_ROWS above the baseline, so a line only
      // becomes invisible when its whole [baseline - ridge, baseline] span is off-field.
      if (this.scrollDown
          ? (line.baseline - MAX_RIDGE_ROWS > HEIGHT + 1)
          : (line.baseline < -2)) {
        line.alive = false;
      }
    }

    final double spacing = this.spacing.getValue();
    boolean any = false;
    double edge = 0;
    for (Line line : this.lines) {
      if (line.alive) {
        if (!any) {
          edge = line.baseline;
          any = true;
        } else {
          edge = this.scrollDown ? Math.min(edge, line.baseline) : Math.max(edge, line.baseline);
        }
      }
    }
    boolean born = false;
    if (this.scrollDown) {
      // Born just above the top edge; the baseline row appears first, the
      // contour forms as it scrolls in (background is black, so it is seamless).
      if (!any) {
        birth(-1, nextGain(), false);
        edge = -1;
        born = true;
      }
      while (edge - spacing >= -1) {
        edge -= spacing;
        birth(edge, nextGain(), false);
        born = true;
      }
    } else {
      // Born below the bottom edge, far enough out that even the tallest ridge
      // (which pokes upward from the baseline) has not yet crossed into view.
      if (!any) {
        birth(HEIGHT, nextGain(), false);
        edge = HEIGHT;
        born = true;
      }
      while (edge + spacing <= HEIGHT + MAX_RIDGE_ROWS) {
        edge += spacing;
        birth(edge, nextGain(), false);
        born = true;
      }
    }
    return born;
  }

  // ---- Shape generation (event-rate, uses preallocated scratch only) --------

  /**
   * Fill a newborn line's normalized profile: crossfade of live-FFT resample and
   * synthesized CP 1919 pulses, plus smoothed jaggedness noise, all pinned to the
   * baseline at the edges by the center-heavy window.
   */
  private void generateShape(float[] out, boolean forceSynth) {
    final double mix = forceSynth ? 0 : Math.min(1, this.audio.level / FFT_CROSSFADE_LEVEL);
    if (mix < 1) {
      synthesizePulse(this.synthScratch);
    }
    if (mix > 0) {
      resampleFft(this.fftScratch);
    }
    final double jag = this.jaggedness.getValue() * JAG_SCALE;
    double noise = 0;
    for (int x = 0; x < WIDTH; ++x) {
      noise = NOISE_SMOOTH * noise + (1 - NOISE_SMOOTH) * (this.random.nextDouble() * 2 - 1);
      double v = mix * this.fftScratch[x] + (1 - mix) * this.synthScratch[x] + jag * noise;
      out[x] = (float) LXUtils.constrain(v * this.window[x], 0, 1);
    }
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

  /** Linear resample of the 16 GraphicMeter bands to 50 samples (bands are meter-normalized 0..1). */
  private void resampleFft(double[] out) {
    final int numBands = this.audio.numBands();
    for (int x = 0; x < WIDTH; ++x) {
      final double pos = x * (numBands - 1) / (double) (WIDTH - 1);
      final int i = (int) pos;
      final double f = pos - i;
      out[x] = (i + 1 < numBands)
        ? LXUtils.lerp(this.audio.band(i), this.audio.band(i + 1), f)
        : this.audio.band(numBands - 1);
    }
  }

  // ---- Render ---------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    if (this.audio.bassHit()) {
      // Arm the boost for the next-born line, scaled by the audio depth knob:
      // full 1.5x at depth 1, fading to no boost as depth approaches 0
      this.boostGain = 1 + (BASS_BOOST - 1) * this.audio.depth();
    }

    final boolean syncOn = this.sync.isOn();

    // Quantized pulse launch: a pending Pulse fires on the next grid boundary.
    // crossed() runs unconditionally so its cycle-count gate never goes stale
    // across a Sync-off interval (a stale gate would return a spurious true
    // on the first sync frame back, firing a just-armed pulse off-grid).
    final boolean grid = this.tempoLock.crossed(this.tempoDiv.getEnum()) && syncOn;
    boolean born = false;
    if (this.pulsePending && (grid || !syncOn)) {
      this.pulsePending = false;
      firePulse();
      born = true; // pulse birth is a retiming moment, same as a regular birth
    }

    final double e = this.energy.getValue();
    final double nominalRowsPerSec = Ranges.lin(e, HEIGHT / SCROLL_SEC_AMBIENT, HEIGHT / SCROLL_SEC_PEAK);
    if (!syncOn) {
      this.syncScale = 1; // free-running: exact pre-sync behavior
    }
    born |= advance(nominalRowsPerSec * this.syncScale * deltaMs * 0.001);

    if (syncOn && born) {
      // A line was just born; the next birth is `spacing` rows away at the
      // nominal rate. Nudge the scroll rate so that birth lands on the tempo
      // grid. The scale is replaced (not compounded), so it always stays
      // within the clamp of the nominal rate; the upper clamp is derived from
      // the 5 s traversal cap so retiming can never break it.
      final double msUntil = this.spacing.getValue() / nominalRowsPerSec * 1000;
      final double maxScale = Math.min(
        TempoLock.DEFAULT_MAX_SCALE, (HEIGHT / SCROLL_SEC_CAP) / nominalRowsPerSec);
      this.syncScale = this.tempoLock.retime(
        msUntil, this.tempoDiv.getEnum(), TempoLock.DEFAULT_MIN_SCALE, maxScale);
    }

    // Back-to-front painter's order: ascending baseline. Lines lower on the
    // sculpture (larger baseline row) are nearer, painted last, and their black
    // fill erases the contours of the lines behind them — the ridgeline occlusion.
    int n = 0;
    for (int i = 0; i < MAX_LINES; ++i) {
      if (this.lines[i].alive) {
        this.order[n++] = i;
      }
    }
    for (int i = 1; i < n; ++i) {
      final int idx = this.order[i];
      final double b = this.lines[idx].baseline;
      int j = i - 1;
      while (j >= 0 && this.lines[this.order[j]].baseline > b) {
        this.order[j + 1] = this.order[j];
        --j;
      }
      this.order[j + 1] = idx;
    }

    Arrays.fill(this.raster, LXColor.BLACK);
    final double ampRows = this.amplitude.getValue() * Ranges.lin(e, AMP_ENERGY_AMBIENT, AMP_ENERGY_PEAK);
    final float hue = (float) this.tintHue.getValue();
    final float sat = (float) (100 * this.tintAmount.getValue());
    for (int i = 0; i < n; ++i) {
      paintLine(this.lines[this.order[i]], ampRows, hue, sat);
    }

    blitCubeFront();
    copyCubeFace(Apotheneum.cube.exterior.front); // all 4 exterior faces + all 4 interior faces
    blitCylinder();
    copyCylinderExterior();
  }

  /**
   * Paint one line into the raster: per column, black-fill from just under the
   * contour down to the baseline (occluding whatever was behind), then draw the
   * contour as a brightness-weighted two-row stroke at its fractional height,
   * so sub-pixel scrolling stays smooth.
   */
  private void paintLine(Line line, double ampRows, float hue, float sat) {
    final double b = line.baseline;
    if (b - MAX_RIDGE_ROWS > HEIGHT) {
      return; // fully below the field
    }
    final double lineAmp = ampRows * line.gain;
    final int baseRow = (int) Math.floor(b);
    for (int x = 0; x < WIDTH; ++x) {
      double h = line.shape[x] * lineAmp;
      if (h > MAX_RIDGE_ROWS) {
        h = MAX_RIDGE_ROWS;
      }
      final double yc = b - h; // contour row (fractional)
      final int contourRow = (int) Math.floor(yc);

      // Fill (silhouette interior): rows strictly below the contour, down to the baseline
      final int fillStart = Math.max(0, contourRow + 1);
      final int fillEnd = Math.min(HEIGHT - 1, baseRow);
      for (int y = fillStart; y <= fillEnd; ++y) {
        this.raster[y * WIDTH + x] = LXColor.BLACK;
      }

      // Contour stroke, anti-aliased across two rows by the fractional part
      final double f = yc - contourRow;
      if (contourRow >= 0 && contourRow < HEIGHT && f < 0.98) {
        this.raster[contourRow * WIDTH + x] = LXColor.hsb(hue, sat, (float) (100 * (1 - f)));
      }
      final int below = contourRow + 1;
      if (below >= 0 && below < HEIGHT && f > 0.02) {
        this.raster[below * WIDTH + x] = LXColor.hsb(hue, sat, (float) (100 * f));
      }
    }
  }

  private void blitCubeFront() {
    final Apotheneum.Cube.Face front = Apotheneum.cube.exterior.front;
    for (int x = 0; x < WIDTH; ++x) {
      final Apotheneum.Column column = front.columns[x];
      final int columnHeight = column.points.length; // door columns are shorter
      for (int y = 0; y < columnHeight; ++y) {
        colors[column.points[y].index] = this.raster[y * WIDTH + x];
      }
    }
  }

  private void blitCylinder() {
    final Apotheneum.Cylinder.Orientation cylinder = Apotheneum.cylinder.exterior;
    final int cylinderWidth = cylinder.width(); // 120
    for (int cx = 0; cx < cylinderWidth; ++cx) {
      final int x = cx * WIDTH / cylinderWidth; // sample the same 50-wide field
      final Apotheneum.Column column = cylinder.column(cx);
      final int columnHeight = column.points.length; // 43, or shorter at doors
      for (int y = 0; y < columnHeight; ++y) {
        colors[column.points[y].index] = this.raster[y * WIDTH + x];
      }
    }
  }
}
