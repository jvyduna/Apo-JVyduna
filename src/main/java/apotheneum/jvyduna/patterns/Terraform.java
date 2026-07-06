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
 * An evolving terrain skyline wrapped around the sculpture. A 1D heightfield
 * circles the cube ring (200 columns) and, independently, the cylinder (120
 * columns). Each column is rendered bottom-up as hard elevation bands — water,
 * sand, grass, rock, snow — with a bright one-row waterline at the sea surface.
 *
 * Mountains are born by uplift (bass hits at high energy, a spontaneous
 * timer at ambient), age by erosion (neighbor diffusion + slow subsidence),
 * and drown or emerge as the sea breathes with the slow-smoothed music level:
 * quiet music raises the ocean over the land; loud music drains it and the
 * peaks come out. Treble shimmers the snowcaps at high energy. All audio
 * reactivity is gated by the Audio depth knob (default 0 = pure screensaver).
 * With Sync on, spontaneous mountain births land on the tempo grid and the
 * Flood trigger's sea ramp is retimed to top out on a grid boundary.
 *
 * See Terraform.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Terraform")
@LXComponent.Description("Evolving terrain skyline: uplift raises mountains, erosion ages them, and the sea breathes with the music")
public class Terraform extends ApotheneumPattern {

  // ---- Timing constants (physical intent) -----------------------------------

  /** Any point's full-height (45-row) terrain change takes >= 5 s (sim-principles cap) */
  private static final double RISE_FULL_SEC = 5;

  /** Ambient sea level full sweep (bottom to top) in 8 s */
  private static final double SEA_RAMP_SEC = 8;

  /** Flood trigger sea sweep: full range in 5 s (the >= 5 s cap; ~4 s from a typical drained sea) */
  private static final double FLOOD_RAMP_SEC = 5;

  /** Flood holds at maximum sea for 2 s before draining back */
  private static final double FLOOD_HOLD_SEC = 2;

  /** Time constant of the slow audio-level smoothing that steers the sea (s) */
  private static final double SLOW_LEVEL_TAU_SEC = 4;

  /** Cataclysm shake duration — the one event-like exception, <= 0.5 s */
  private static final double SHAKE_SEC = 0.45;

  /** Peak cataclysm shake displacement, in rows */
  private static final double SHAKE_AMP_ROWS = 2.5;

  /** Cataclysm shake oscillation frequency (Hz) */
  private static final double SHAKE_RATE_HZ = 7;

  /** Spatial wavelength of the shake ripple, radians per column */
  private static final double SHAKE_WAVE = 0.6;

  /** Terrain subsidence (slump) time constant at ambient energy (s) */
  private static final double SUBSIDE_TAU_AMBIENT_SEC = 120;

  /** Terrain subsidence (slump) time constant at peak energy (s) */
  private static final double SUBSIDE_TAU_PEAK_SEC = 30;

  // ---- Rates and thresholds --------------------------------------------------

  /** Spontaneous (timer) uplifts per second per surface at ambient (~1 per 17 s) */
  private static final double UPLIFT_RATE_AMBIENT_HZ = 0.06;

  /** Spontaneous uplifts per second per surface at peak energy (~1 per 2 s) */
  private static final double UPLIFT_RATE_PEAK_HZ = 0.5;

  /** Diffusion coefficient (Laplacian fraction/s) at erosion=1, ambient energy */
  private static final double DIFFUSION_AMBIENT = 0.4;

  /** Diffusion coefficient (Laplacian fraction/s) at erosion=1, peak energy */
  private static final double DIFFUSION_PEAK = 2.0;

  /** Per-frame diffusion stability clamp (must stay < 0.5) */
  private static final double DIFFUSION_MAX_FRAME = 0.24;

  /** Uplift amplitude factor (fraction of full height, x Uplift param) at ambient / peak energy */
  private static final double UPLIFT_AMP_AMBIENT = 0.4, UPLIFT_AMP_PEAK = 0.9;

  /** Uplift bump sigma (fraction of ring length) at ambient / peak energy */
  private static final double UPLIFT_SIGMA_AMBIENT = 0.02, UPLIFT_SIGMA_PEAK = 0.045;

  /** Bass hits trigger uplifts only above this energy */
  private static final double BASS_UPLIFT_MIN_ENERGY = 0.55;

  /** Sea drop from the SeaBias baseline at full sustained music level */
  private static final double SEA_SWING = 0.45;

  /** Audio-driven sea goal clamp: the sea never fully vanishes or tops out */
  private static final double SEA_MIN = 0.05, SEA_MAX = 0.9;

  /** Sea fraction the flood trigger ramps to */
  private static final double SEA_FLOOD = 0.97;

  // Elevation band tops as fractions of full sculpture height (before BandShift)
  private static final double SAND_TOP = 0.30;
  private static final double GRASS_TOP = 0.55;
  private static final double ROCK_TOP = 0.80;

  // ---- Band colors (fixed natural hues; see Terraform.md) --------------------

  private static final int SKY = LXColor.BLACK;
  private static final int WATER_DEEP = LXColor.hsb(215, 85, 40);
  private static final int WATERLINE = LXColor.hsb(190, 35, 100);
  private static final int SAND = LXColor.hsb(45, 60, 85);
  private static final int GRASS = LXColor.hsb(115, 85, 55);
  private static final int ROCK = LXColor.hsb(25, 30, 45);
  private static final int SNOW = LXColor.hsb(210, 4, 100);

  // ---- Flood state machine ----------------------------------------------------

  private static final int FLOOD_NONE = 0, FLOOD_RISING = 1, FLOOD_HOLDING = 2;

  // ---- Parameters -------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Terraform");

  public final TriggerParameter cataclysm = bag.register(
    new TriggerParameter("Cataclysm", this::cataclysm)
    .setDescription("Raise a huge mountain ridge with a brief whole-ring shake, then let it settle over seconds"));

  public final TriggerParameter flood = bag.register(
    new TriggerParameter("Flood", this::flood)
    .setDescription("Ramp the sea to maximum over a few seconds, hold briefly, then drain back"));

  public final TriggerParameter reseed = bag.register(
    new TriggerParameter("Reseed", this::reseed)
    .setDescription("Morph to a fresh random terrain over ~5 s"));

  public final TriggerParameter erupt = bag.register(
    new TriggerParameter("Erupt", this::erupt)
    .setDescription("Raise one new mountain now, exactly as a spontaneous uplift would"));

  public final CompoundParameter energy = new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 soothing ambient, 0.6-1.0 high-energy 160 BPM regime");

  public final CompoundParameter upliftSize = new CompoundParameter("Uplift", 0.5)
    .setDescription("Amplitude of new mountain uplifts");

  public final CompoundParameter erosion = new CompoundParameter("Erosion", 0.4)
    .setDescription("How fast mountains age: neighbor diffusion plus slow subsidence");

  public final CompoundParameter seaBias = new CompoundParameter("SeaBias", 0.55, 0.15, 0.85)
    .setDescription("Sea level in silence, as a fraction of sculpture height; sustained music level lowers the sea from here");

  public final CompoundParameter bandShift = new CompoundParameter("Bands", 0, -0.2, 0.2)
    .setDescription("Shifts all elevation band thresholds up (+) or down (-) as a fraction of height");

  public final CompoundParameter sparkle = new CompoundParameter("Sparkle", 0.5)
    .setDescription("Treble-driven shimmer on snowcap pixels, active only at high energy");

  public final CompoundParameter audioDepth = new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final BooleanParameter sync = new BooleanParameter("Sync", true)
    .setDescription("Lock motion events to the tempo grid; off restores free-running timing");

  public final EnumParameter<Tempo.Division> tempoDiv = new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division that motion events land on when Sync is enabled");

  public final TriggerParameter meta = new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- State (all preallocated; zero allocation in the render path) -----------

  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  // Terrain heightfields in rows. target[] receives uplift/erosion; height[]
  // (the displayed field) chases target rate-limited to fullHeight/RISE_FULL_SEC.
  private final double[] cubeTarget = new double[Apotheneum.Cube.Ring.LENGTH];
  private final double[] cubeHeight = new double[Apotheneum.Cube.Ring.LENGTH];
  private final double[] cubeScratch = new double[Apotheneum.Cube.Ring.LENGTH];
  private final double[] cylinderTarget = new double[Apotheneum.Cylinder.Ring.LENGTH];
  private final double[] cylinderHeight = new double[Apotheneum.Cylinder.Ring.LENGTH];
  private final double[] cylinderScratch = new double[Apotheneum.Cylinder.Ring.LENGTH];

  /** Slow-smoothed music level steering the sea (0..1) */
  private double slowLevel = 0;

  /** Current sea level as a fraction of sculpture height, shared by both surfaces */
  private double seaFrac;

  private int floodPhase = FLOOD_NONE;
  private double floodHoldMs = 0;

  /** Sea rise rate (fraction/s) while FLOOD_RISING; retimed at trigger when Sync is on */
  private double floodRate = 1 / FLOOD_RAMP_SEC;

  private double shakeMs = 0;
  private double shakePhase = 0;

  public Terraform(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("cataclysm", this.cataclysm);
    addParameter("flood", this.flood);
    addParameter("reseed", this.reseed);
    addParameter("erupt", this.erupt);
    addParameter("energy", this.energy);
    addParameter("upliftSize", this.upliftSize);
    addParameter("erosion", this.erosion);
    addParameter("seaBias", this.seaBias);
    addParameter("bandShift", this.bandShift);
    addParameter("sparkle", this.sparkle);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    // Jump candidates — mirrored 1:1 in Terraform.md "Jump candidates"
    this.bag.jumpable(this.erosion, 0.15, 1);
    this.bag.jumpable(this.upliftSize);
    this.bag.jumpable(this.bandShift, -0.12, 0.12);
    this.bag.jumpable(this.seaBias, 0.3, 0.75);

    // Start with a formed landscape: seed and snap heights to it
    seedTerrain(this.cubeTarget, Apotheneum.GRID_HEIGHT);
    seedTerrain(this.cylinderTarget, Apotheneum.CYLINDER_HEIGHT);
    System.arraycopy(this.cubeTarget, 0, this.cubeHeight, 0, this.cubeTarget.length);
    System.arraycopy(this.cylinderTarget, 0, this.cylinderHeight, 0, this.cylinderTarget.length);
    this.seaFrac = this.seaBias.getValue();
  }

  // ---- Triggers --------------------------------------------------------------

  private void cataclysm() {
    LX.log("Terraform: cataclysm");
    addRidge(this.cubeTarget, Apotheneum.GRID_HEIGHT);
    addRidge(this.cylinderTarget, Apotheneum.CYLINDER_HEIGHT);
    this.shakeMs = SHAKE_SEC * 1000;
  }

  private void flood() {
    LX.log("Terraform: flood");
    this.floodPhase = FLOOD_RISING;
    this.floodRate = 1 / FLOOD_RAMP_SEC;
    if (this.sync.isOn()) {
      // Retime the ramp so the sea tops out on a tempo-grid boundary.
      // Slow-down only (max scale 1): FLOOD_RAMP_SEC already sits at the 5 s
      // full-traversal cap, so the ramp may stretch to ~7.1 s but never quicken.
      final double msUntilFull = (SEA_FLOOD - this.seaFrac) * FLOOD_RAMP_SEC * 1000;
      this.floodRate *= this.tempoLock.retime(msUntilFull, this.tempoDiv.getEnum(), TempoLock.DEFAULT_MIN_SCALE, 1);
    }
  }

  private void erupt() {
    LX.log("Terraform: erupt");
    spawnUplift(1);
  }

  private void reseed() {
    LX.log("Terraform: reseed");
    // Heights are left in place; they chase the new targets over <= RISE_FULL_SEC
    seedTerrain(this.cubeTarget, Apotheneum.GRID_HEIGHT);
    seedTerrain(this.cylinderTarget, Apotheneum.CYLINDER_HEIGHT);
  }

  /** A cataclysm mountain range: one huge central bump flanked by two shoulders. */
  private void addRidge(double[] target, int fullHeight) {
    final int w = target.length;
    final int center = this.random.nextInt(w);
    final double sigma = w * 0.05;
    final int shoulder = (int) (1.5 * sigma);
    addBump(target, center, 0.85 * fullHeight, sigma, fullHeight);
    addBump(target, center - shoulder, 0.55 * fullHeight, sigma, fullHeight);
    addBump(target, center + shoulder, 0.55 * fullHeight, sigma, fullHeight);
  }

  /** Fresh random landscape: a low base plus a handful of random mountains. */
  private void seedTerrain(double[] target, int fullHeight) {
    final int w = target.length;
    final double base = (0.05 + 0.1 * this.random.nextDouble()) * fullHeight;
    Arrays.fill(target, base);
    final int bumps = 3 + w / 40; // cube: 8, cylinder: 6
    for (int i = 0; i < bumps; ++i) {
      addBump(target,
        this.random.nextInt(w),
        (0.25 + 0.55 * this.random.nextDouble()) * fullHeight,
        w * (0.02 + 0.04 * this.random.nextDouble()),
        fullHeight);
    }
  }

  /** Add a wrap-aware Gaussian bump to a heightfield, clamped just above full height. */
  private static void addBump(double[] target, int center, double amp, double sigma, int fullHeight) {
    final int w = target.length;
    final int c = Math.floorMod(center, w);
    final double maxHeight = 1.02 * fullHeight;
    final double denom = 2 * sigma * sigma;
    for (int x = 0; x < w; ++x) {
      int dx = Math.abs(x - c);
      if (dx > w / 2) {
        dx = w - dx; // wrap-continuous distance around the ring
      }
      final double t = target[x] + amp * Math.exp(-(dx * (double) dx) / denom);
      target[x] = Math.min(t, maxHeight);
    }
  }

  // ---- Render ----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    final double e = this.energy.getValue();
    final double dt = deltaMs / 1000.0;

    // -- Uplift: spontaneous births always run (silence-safe); bass adds at
    // high energy. Free-running: a Poisson timer. Sync on: births land on the
    // tempo grid, firing on grid crossings with a probability that preserves
    // the same expected rate (capped at one birth per division cycle).
    final double upliftRate = Ranges.exp(e, UPLIFT_RATE_AMBIENT_HZ, UPLIFT_RATE_PEAK_HZ);
    // crossed() polls every frame, even with Sync off (result unused), so the
    // gate never goes stale — a lapsed gate would report a boundary that
    // elapsed while Sync was off and fire one spurious birth on re-enable
    final boolean gridCross = this.tempoLock.crossed(this.tempoDiv.getEnum());
    final boolean timerUplift = this.sync.isOn() ?
      gridCross
        && (this.random.nextDouble() < upliftRate * this.tempoLock.divisionMs(this.tempoDiv.getEnum()) * 0.001) :
      this.random.nextDouble() < upliftRate * dt;
    final boolean bassUplift = (e >= BASS_UPLIFT_MIN_ENERGY) && this.audio.bassHit();
    if (timerUplift || bassUplift) {
      // Bass-born mountains scale by how hard the transient hit
      spawnUplift(bassUplift ? 0.6 + 0.4 * Math.min(this.audio.bassRatio, 2.5) / 2.5 : 1);
    }

    // -- Erosion: diffusion + subsidence on targets; heights chase rate-limited
    final double diffusion = this.erosion.getValue() * Ranges.lin(e, DIFFUSION_AMBIENT, DIFFUSION_PEAK);
    final double kFrame = Math.min(diffusion * dt, DIFFUSION_MAX_FRAME);
    final double subsideTau = Ranges.exp(e, SUBSIDE_TAU_AMBIENT_SEC, SUBSIDE_TAU_PEAK_SEC);
    final double subsideAlpha = Math.min(this.erosion.getValue() * dt / subsideTau, 0.05);
    advanceTerrain(this.cubeTarget, this.cubeHeight, this.cubeScratch, Apotheneum.GRID_HEIGHT, dt, kFrame, subsideAlpha);
    advanceTerrain(this.cylinderTarget, this.cylinderHeight, this.cylinderScratch, Apotheneum.CYLINDER_HEIGHT, dt, kFrame, subsideAlpha);

    // -- Sea level: breathes with the slow-smoothed music level; flood overrides
    this.slowLevel += (this.audio.level - this.slowLevel) * Math.min(dt / SLOW_LEVEL_TAU_SEC, 1);
    double seaGoal = LXUtils.constrain(this.seaBias.getValue() - SEA_SWING * this.slowLevel, SEA_MIN, SEA_MAX);
    double seaRate = 1 / SEA_RAMP_SEC;
    if (this.floodPhase == FLOOD_RISING) {
      seaGoal = SEA_FLOOD;
      seaRate = this.floodRate;
      if (this.seaFrac >= SEA_FLOOD - 0.01) {
        this.floodPhase = FLOOD_HOLDING;
        this.floodHoldMs = FLOOD_HOLD_SEC * 1000;
      }
    } else if (this.floodPhase == FLOOD_HOLDING) {
      seaGoal = SEA_FLOOD;
      this.floodHoldMs -= deltaMs;
      if (this.floodHoldMs <= 0) {
        this.floodPhase = FLOOD_NONE; // drain back at the normal sea rate
      }
    }
    this.seaFrac += LXUtils.constrain(seaGoal - this.seaFrac, -seaRate * dt, seaRate * dt);

    // -- Cataclysm shake envelope (<= 0.5 s, linearly decaying)
    double shakeAmp = 0;
    if (this.shakeMs > 0) {
      this.shakeMs -= deltaMs;
      this.shakePhase += dt * SHAKE_RATE_HZ * 2 * Math.PI;
      shakeAmp = SHAKE_AMP_ROWS * Math.max(this.shakeMs, 0) / (SHAKE_SEC * 1000);
    }

    // -- Snow sparkle: treble shimmer, gated to the high-energy regime
    final double sparkleGate = LXUtils.constrain((e - 0.5) / 0.3, 0, 1);
    final double sparkleDepth = 0.45 * this.sparkle.getValue() * sparkleGate * Math.min(this.audio.treble * 1.6, 1);

    setColors(LXColor.BLACK);
    renderSurface(Apotheneum.cube.exterior, this.cubeHeight, Apotheneum.GRID_HEIGHT, shakeAmp, sparkleDepth);
    renderSurface(Apotheneum.cylinder.exterior, this.cylinderHeight, Apotheneum.CYLINDER_HEIGHT, shakeAmp, sparkleDepth);
    copyCubeExterior();
    copyCylinderExterior();
  }

  /**
   * One step of terrain aging: neighbor diffusion (wrap-continuous) plus
   * exponential subsidence on target[], then height[] chases target[] with the
   * per-point rate limit (full height in no less than RISE_FULL_SEC).
   */
  private static void advanceTerrain(double[] target, double[] height, double[] scratch,
                                     int fullHeight, double dt, double kFrame, double subsideAlpha) {
    final int w = target.length;
    System.arraycopy(target, 0, scratch, 0, w);
    final double maxStep = fullHeight * dt / RISE_FULL_SEC;
    for (int x = 0; x < w; ++x) {
      final double left = scratch[(x + w - 1) % w];
      final double right = scratch[(x + 1) % w];
      double t = scratch[x] + kFrame * (left + right - 2 * scratch[x]);
      t -= t * subsideAlpha; // slump toward the sea floor
      if (t < 0) {
        t = 0;
      }
      target[x] = t;
      final double d = t - height[x];
      height[x] += (d > maxStep) ? maxStep : (d < -maxStep) ? -maxStep : d;
    }
  }

  /**
   * Raise one new mountain on each surface (random independent centers),
   * sized by Energy and the Uplift knob, optionally scaled by ampScale.
   * Zero-allocation; called from the render path and from the Erupt trigger.
   */
  private void spawnUplift(double ampScale) {
    final double e = this.energy.getValue();
    final double amp = ampScale * this.upliftSize.getValue() * Ranges.lin(e, UPLIFT_AMP_AMBIENT, UPLIFT_AMP_PEAK);
    final double sigmaFrac = Ranges.lin(e, UPLIFT_SIGMA_AMBIENT, UPLIFT_SIGMA_PEAK);
    uplift(this.cubeTarget, Apotheneum.GRID_HEIGHT, amp, sigmaFrac);
    uplift(this.cylinderTarget, Apotheneum.CYLINDER_HEIGHT, amp, sigmaFrac);
  }

  private void uplift(double[] target, int fullHeight, double ampFrac, double sigmaFrac) {
    addBump(target,
      this.random.nextInt(target.length),
      ampFrac * fullHeight,
      Math.max(2, sigmaFrac * target.length),
      fullHeight);
  }

  /**
   * Draw one surface's skyline into the exterior color buffer. Elevation is
   * counted in rows above the ground: column.points[0] is the top row, so
   * elev = fullHeight - 1 - yi. Every column carries the full point count
   * (the model enforces this; door cutouts are masked by the core doors
   * effect, not by shorter columns) — indexing elevation from the top keeps
   * the sea and band thresholds aligned across all columns regardless.
   */
  private void renderSurface(Apotheneum.Orientation surface, double[] height,
                             int fullHeight, double shakeAmp, double sparkleDepth) {
    final double seaRows = this.seaFrac * fullHeight;
    final double shiftRows = this.bandShift.getValue() * fullHeight;
    final double sandTop = SAND_TOP * fullHeight + shiftRows;
    final double grassTop = GRASS_TOP * fullHeight + shiftRows;
    final double rockTop = ROCK_TOP * fullHeight + shiftRows;
    final Apotheneum.Column[] columns = surface.columns();
    for (int x = 0; x < columns.length; ++x) {
      double h = height[x];
      if (shakeAmp > 0) {
        h += shakeAmp * Math.sin(x * SHAKE_WAVE + this.shakePhase);
      }
      final Apotheneum.Column column = columns[x];
      final int len = column.points.length;
      for (int yi = 0; yi < len; ++yi) {
        final int elev = fullHeight - 1 - yi; // rows above the physical ground
        int c;
        if (elev <= seaRows) {
          // Water sits in front of any submerged land; top water row is the
          // specular waterline accent
          c = (elev > seaRows - 1) ? WATERLINE : WATER_DEEP;
        } else if (elev <= h) {
          if (elev < sandTop) {
            c = SAND;
          } else if (elev < grassTop) {
            c = GRASS;
          } else if (elev < rockTop) {
            c = ROCK;
          } else {
            c = SNOW;
            if (sparkleDepth > 0) {
              c = dim(c, 1 - sparkleDepth * this.random.nextDouble());
            }
          }
        } else {
          c = SKY;
        }
        this.colors[column.points[yi].index] = c;
      }
    }
  }

  private static int dim(int argb, double f) {
    if (f <= 0) {
      return LXColor.BLACK;
    }
    if (f > 1) {
      f = 1;
    }
    final int r = (int) (((argb >> 16) & 0xff) * f);
    final int g = (int) (((argb >> 8) & 0xff) * f);
    final int b = (int) ((argb & 0xff) * f);
    return LXColor.rgba(r, g, b, 255);
  }
}
