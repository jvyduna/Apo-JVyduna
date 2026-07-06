package apotheneum.jvyduna.patterns;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.TempoLock;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundDiscreteParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * After Dark-style psychedelic color-cycling generator.
 *
 * A static scalar phase field is precomputed once over every exterior point
 * (cube ring 200x45 + cylinder 120x43, normalized to [0,1] per surface). Each
 * frame the color of a point is simply LUT[frac(field * spread + t)], so the
 * geometry cost of motion is zero: all apparent movement is the classic
 * palette-rotation trick, inherently smooth and slow. The LUT is built from
 * the project palette swatch (or a perceptual rainbow when the swatch is
 * sparse) and posterized into bold bands that stay legible through LED gaps
 * at sculpture scale.
 *
 * Audio reactivity is gated by the standard Audio depth knob (default 0 =
 * pure screensaver). With Sync on, the band-advance period is quantized to
 * the tempo grid and a radial breath pulse fires on each TempoDiv boundary.
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

  /** One full color cycle takes 16s at ambient energy; worst-case ambient drift period is 16/(1.5 audio x 1.4 sync) = 7.6s */
  private static final double CYCLE_SEC_AMBIENT = 16;

  /** One full color cycle takes 8s at peak energy — the sync cap budget below pins worst-case traversal to exactly 5.0s */
  private static final double CYCLE_SEC_PEAK = 8;

  /** Series cap: sustained motion must take >= 5s to traverse the sculpture; sync retiming is budgeted against this */
  private static final double TRAVERSAL_CAP_SEC = 5;

  /** Radial phase pulse (bass hit / grid breath / manual trigger) relaxes over 2s (event visual life >= 1.5s) */
  private static final double PULSE_RELAX_MS = 2000;

  /** Radial pulse wavefront depth in LUT cycles at ambient energy */
  private static final double PULSE_DEPTH_AMBIENT = 0.15;

  /** Radial pulse wavefront depth in LUT cycles at peak energy */
  private static final double PULSE_DEPTH_PEAK = 0.35;

  /** Slow-smoothed audio level modulates cycle speed by up to +/-50% */
  private static final double AUDIO_SPEED_DEPTH = 0.5;

  /** Time constant (ms) of the extra-slow level smoothing that drives speed modulation */
  private static final double SLOW_LEVEL_MS = 1200;

  /** Max lerp toward white of the whole LUT under treble shimmer */
  private static final double SHIMMER_MAX = 0.30;

  /** Energy below which treble shimmer is fully gated off */
  private static final double SHIMMER_GATE = 0.6;

  /** Color LUT resolution; power of two for cheap index masking */
  private static final int LUT_SIZE = 256;

  /** Max swatch colors sampled from the project palette */
  private static final int MAX_SWATCH = 12;

  /** Max radial centers per surface for INTERFERENCE */
  private static final int MAX_CENTERS = 3;

  // ---- Parameters -------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Satori");

  public final TriggerParameter newField = bag.register(
    new TriggerParameter("NewField", this::reseed)
    .setDescription("Reseed the field centers and seeds, keeping the current Field variant"));

  public final TriggerParameter reverse = bag.register(
    new TriggerParameter("Reverse", this::reverseDirection)
    .setDescription("Flip the direction of the color cycle"));

  public final TriggerParameter pulse = bag.register(
    new TriggerParameter("Pulse", this::firePulse)
    .setDescription("Launch the radial phase pulse manually: bands warp outward from the seeded center, relaxing over 2s"));

  public final CompoundParameter energy = new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 soothing ambient, 0.6-1.0 high-energy (cycle rate, pulse depth, treble shimmer)");

  public final EnumParameter<FieldMode> fieldMode =
    new EnumParameter<FieldMode>("Field", FieldMode.INTERFERENCE)
    .setDescription("Static phase-field variant the colors rotate through");

  public final CompoundParameter speed = new CompoundParameter("Speed", 1, 0.25, 1)
    .setDescription("Trim on the energy-set cycle rate; can only slow it (1 = full rate, keeps the traversal cap)");

  public final CompoundParameter spread = new CompoundParameter("Spread", 2, 1, 6)
    .setDescription("How many full color cycles span each surface (higher = more, thinner bands)");

  public final CompoundDiscreteParameter posterize =
    new CompoundDiscreteParameter("Bands", 6, 2, 17)
    .setDescription("Posterize the color LUT into N bold bands; smooth gradients read as mud through LED gaps");

  public final CompoundParameter audioDepth = new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final BooleanParameter sync = new BooleanParameter("Sync", true)
    .setDescription("Lock motion events to the tempo grid; off restores free-running timing");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.WHOLE)
    .setDescription("Tempo division that motion events land on when Sync is enabled");

  public final TriggerParameter meta = new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- State ------------------------------------------------------------------

  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  // Phase field over all model points; only exterior indices are filled, the
  // interior receives a verbatim copy of the exterior each frame.
  private float[] field = null;      // normalized [0,1] per surface
  private float[] pulseDist = null;  // normalized [0,1] distance from the pulse center
  private boolean fieldDirty = true;
  private boolean reseedPending = true;

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
  private int cycleDirection = 1;
  private double slowLevel = 0;
  private double pulseEnv = 0;

  public Satori(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("newField", this.newField);
    addParameter("reverse", this.reverse);
    addParameter("pulse", this.pulse);
    addParameter("energy", this.energy);
    addParameter("fieldMode", this.fieldMode);
    addParameter("speed", this.speed);
    addParameter("spread", this.spread);
    addParameter("posterize", this.posterize);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    // Meta jump candidates — mirrored 1:1 in the Satori.md table
    this.bag.jumpable(this.fieldMode);
    this.bag.jumpable(this.speed, 0.4, 1.0);
    this.bag.jumpable(this.spread);
    this.bag.jumpable(this.posterize);
  }

  // ---- Triggers ---------------------------------------------------------------

  /** NewField: reseed centers/seeds and rebuild the field (one O(n) pass, event-rate) */
  private void reseed() {
    this.reseedPending = true;
    this.fieldDirty = true;
  }

  /** Reverse: flip the direction of the palette rotation; the field is untouched */
  private void reverseDirection() {
    this.cycleDirection = -this.cycleDirection;
  }

  /** Pulse: launch the radial phase wave manually, at full strength regardless of audio depth */
  private void firePulse() {
    this.pulseEnv = 1;
  }

  @Override
  public void onParameterChanged(LXParameter parameter) {
    super.onParameterChanged(parameter);
    if (parameter == this.fieldMode) {
      // Rebuild with the existing seeds so variants can be A/B'd on one layout
      this.fieldDirty = true;
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
      this.pulseDist = new float[size];
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
    // Normalize the field to span exactly [0,1] per surface: Spread then means
    // exactly N color cycles across the surface, and the traversal-cap math
    // (traversal time = spread * cyclePeriod) is exact for every variant.
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

  // ---- Render (zero allocation) --------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);

    if (this.fieldDirty || (this.field == null) || (this.field.length != this.colors.length)) {
      buildField();
      this.fieldDirty = false;
    }
    updateLut();

    final double e = this.energy.getValue();
    final int bands = this.posterize.getValuei();

    // Slow-smoothed level modulates cycle speed +/-50%; silence floors at 0.5x
    // (slower than base = still cycling, never frozen, never faster). At audio
    // depth 0 the level tap reads 0, so the default is the 0.5x floor.
    this.slowLevel += (this.audio.level - this.slowLevel) * (1 - Math.exp(-deltaMs / SLOW_LEVEL_MS));
    final double speedMul = 1 + AUDIO_SPEED_DEPTH * (2 * this.slowLevel - 1);

    final double cycleSec = Ranges.exp(e, CYCLE_SEC_AMBIENT, CYCLE_SEC_PEAK) / this.speed.getValue();

    // Tempo lock (sustained): quantize the band-advance period — the visible
    // rhythm; each point flips to its next posterized band once per band
    // period — to the nearest whole multiple (or unit fraction) of the sync
    // division, via TempoLock.quantizePeriod(). Its internal harmonic latch
    // only re-quantizes when the required rate scale leaves the [0.7, 1.4]
    // window, so audio speed wobble near a rounding boundary cannot
    // flip-flop the rate. Palette rotation has no globally-simultaneous
    // event to phase-align (band flips are staggered across points by the
    // field), so period quantization is the whole lock; the grid-breath
    // pulse below carries the downbeat.
    double syncScale = 1;
    if (this.sync.isOn()) {
      final double bandMs = 1000 * cycleSec / (speedMul * bands);
      final double s = this.tempoLock.quantizePeriod(bandMs, this.tempoDiv.getEnum());
      // Cap budget: speedMul * syncScale <= cycleSec / TRAVERSAL_CAP_SEC keeps
      // worst-case traversal (spread = 1, e = 1, full audio boost) at >= 5.0s.
      // cycleSec >= 8 and speedMul <= 1.5, so capMax >= 1.067 > minScale.
      final double capMax = Math.min(TempoLock.DEFAULT_MAX_SCALE,
        cycleSec / (TRAVERSAL_CAP_SEC * speedMul));
      syncScale = Math.min(s, capMax);
    }

    this.phase += this.cycleDirection * speedMul * syncScale * deltaMs / (cycleSec * 1000);
    this.phase -= Math.floor(this.phase);

    // Radial phase pulse: wavefront offset proportional to distance from the
    // pulse center, relaxing over ~2s. Sources: the tempo-grid breath (full
    // strength — it is a tempo feature, alive even at audio depth 0), bass
    // hits (scaled by depth so a barely-open Audio knob pulses gently), and
    // the manual Pulse trigger (sets pulseEnv directly at event rate).
    // crossed() runs unconditionally so its counter never goes stale while
    // Sync is off — re-enabling Sync must not fire a phantom pulse.
    final boolean gridCross = this.tempoLock.crossed(this.tempoDiv.getEnum());
    double pulseFire = (this.sync.isOn() && gridCross) ? 1 : 0;
    if (this.audio.bassHit()) {
      pulseFire = Math.max(pulseFire, this.audio.depth());
    }
    if (pulseFire > 0) {
      this.pulseEnv = Math.max(this.pulseEnv, pulseFire);
    } else {
      this.pulseEnv = Math.max(0, this.pulseEnv - deltaMs / PULSE_RELAX_MS);
    }
    final double pulseDepth = this.pulseEnv * Ranges.lin(e, PULSE_DEPTH_AMBIENT, PULSE_DEPTH_PEAK);

    // Treble shimmer, gated to the high-energy regime only
    final double shimmerGate = Math.max(0, (e - SHIMMER_GATE) / (1 - SHIMMER_GATE));
    final double shimmer = SHIMMER_MAX * shimmerGate * Math.min(1, this.audio.treble);
    int[] lut = this.baseLut;
    if (shimmer > 0.005) {
      for (int j = 0; j < LUT_SIZE; ++j) {
        this.frameLut[j] = LXColor.lerp(this.baseLut[j], LXColor.WHITE, (float) shimmer);
      }
      lut = this.frameLut;
    }

    final double spreadAmt = this.spread.getValue();
    paint(Apotheneum.cube.exterior.columns, lut, spreadAmt, bands, pulseDepth);
    paint(Apotheneum.cylinder.exterior.columns, lut, spreadAmt, bands, pulseDepth);

    copyCubeExterior();
    copyCylinderExterior();
  }

  private void paint(Apotheneum.Column[] columns, int[] lut, double spreadAmt, int bands, double pulseDepth) {
    final double phase = this.phase;
    for (int x = 0; x < columns.length; ++x) {
      final LXPoint[] points = columns[x].points; // door columns bound by length
      for (int y = 0; y < points.length; ++y) {
        final int idx = points[y].index;
        double pos = this.field[idx] * spreadAmt + phase + pulseDepth * this.pulseDist[idx];
        pos -= Math.floor(pos);
        // Posterize: quantize to band centers => N bold bands whose edges
        // still sweep smoothly through space as the phase drifts
        pos = (Math.floor(pos * bands) + 0.5) / bands;
        this.colors[idx] = lut[((int) (pos * LUT_SIZE)) & (LUT_SIZE - 1)];
      }
    }
  }
}
