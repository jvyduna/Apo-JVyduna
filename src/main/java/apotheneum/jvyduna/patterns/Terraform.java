package apotheneum.jvyduna.patterns;

import java.util.Arrays;
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
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * An evolving terrain skyline wrapped around the sculpture. A 1D heightfield
 * circles the cube ring (200 columns) and, independently, the cylinder (120
 * columns). Each column is rendered bottom-up as hard elevation bands — water,
 * then palette-driven land bands (sand, grass, rock, peaks) — with a bright
 * one-row waterline at the sea surface.
 *
 * Mountains are born by uplift (bass hits when the music drives hard, a
 * spontaneous timer at ambient) and rise as tempo-quantized envelopes that
 * complete exactly on the tempo grid. They age by erosion (neighbor diffusion
 * + slow subsidence), and drown or emerge as the sea tracks the music level
 * within a beat: quiet music raises the ocean over the land; loud music drains
 * it and the peaks come out. Eroded mountain mass silts the basin, visibly
 * raising the sea. Treble hits flash the peaks white. All audio reactivity is
 * gated by the Audio depth knob (default 0 = pure screensaver).
 *
 * See Terraform.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Terraform")
@LXComponent.Description("Evolving terrain skyline: tempo-locked uplifts raise mountains, erosion silts the sea, and the sea pumps with the music")
public class Terraform extends ApotheneumPattern {

  // ---- Timing constants (physical intent) -----------------------------------

  /** Background terrain chase: full-height (45-row) change takes >= 5 s (erosion/reseed/cataclysm) */
  private static final double RISE_FULL_SEC = 5;

  /** Flood trigger sea sweep: full range in 5 s (~4 s from a typical drained sea) */
  private static final double FLOOD_RAMP_SEC = 5;

  /** Flood holds at maximum sea for 2 s before draining back */
  private static final double FLOOD_HOLD_SEC = 2;

  /** Time constant of the audio-level smoothing that substitutes for the old Energy knob (s) */
  private static final double DRIVE_TAU_SEC = 2;

  /** Time constant of the fast audio-level smoothing that steers the sea (s) */
  private static final double SEA_TAU_SEC = 0.35;

  /** Cataclysm shake duration — the one event-like exception, <= 0.5 s */
  private static final double SHAKE_SEC = 0.45;

  /** Peak cataclysm shake displacement, in rows */
  private static final double SHAKE_AMP_ROWS = 2.5;

  /** Cataclysm shake oscillation frequency (Hz) */
  private static final double SHAKE_RATE_HZ = 7;

  /** Spatial wavelength of the shake ripple, radians per column */
  private static final double SHAKE_WAVE = 0.6;

  /** Terrain subsidence (slump) time constant at ambient drive (s) */
  private static final double SUBSIDE_TAU_AMBIENT_SEC = 120;

  /** Terrain subsidence (slump) time constant at peak drive (s) */
  private static final double SUBSIDE_TAU_PEAK_SEC = 30;

  // ---- Rates and thresholds --------------------------------------------------

  /** Spontaneous (timer) uplifts per second per surface at ambient (~1 per 17 s) */
  private static final double UPLIFT_RATE_AMBIENT_HZ = 0.06;

  /** Spontaneous uplifts per second per surface at peak drive (~1 per 2 s) */
  private static final double UPLIFT_RATE_PEAK_HZ = 0.5;

  /** Diffusion coefficient (Laplacian fraction/s) at erosion=1, ambient drive */
  private static final double DIFFUSION_AMBIENT = 0.4;

  /** Diffusion coefficient (Laplacian fraction/s) at erosion=1, peak drive */
  private static final double DIFFUSION_PEAK = 2.0;

  /** Per-sub-step diffusion stability clamp (3-tap stencil requires < 0.5) */
  private static final double DIFFUSION_MAX_FRAME = 0.24;

  /** Cap on diffusion sub-steps per frame (saturates only on pathological dt) */
  private static final int MAX_DIFFUSION_STEPS = 10;

  /** Uplift amplitude factor (fraction of full height, x Uplift param) at ambient / peak drive */
  private static final double UPLIFT_AMP_AMBIENT = 0.4, UPLIFT_AMP_PEAK = 0.9;

  /** Uplift bump sigma (fraction of ring length) at ambient / peak drive */
  private static final double UPLIFT_SIGMA_AMBIENT = 0.02, UPLIFT_SIGMA_PEAK = 0.045;

  /** Bass hits trigger uplifts only when the depth-independent smoothed music
   *  level exceeds this (the gate compares drive against this x audio depth) */
  private static final double BASS_UPLIFT_MIN_LEVEL = 0.35;

  /** Sea drop from the SeaBias baseline at full sustained music level */
  private static final double SEA_SWING = 0.45;

  /** Audio-driven sea goal clamp: the sea never fully vanishes or tops out */
  private static final double SEA_MIN = 0.05, SEA_MAX = 0.9;

  /** Sea fraction the flood trigger ramps to */
  private static final double SEA_FLOOD = 0.97;

  /** Silt: sea-level rise per unit of normalized eroded above-sea volume */
  private static final double SILT_GAIN = 8;

  /** Silt washes out of the basin over this time constant (s) */
  private static final double SILT_DECAY_SEC = 25;

  /** Silt contribution to the sea goal never exceeds this fraction of height */
  private static final double SILT_MAX = 0.3;

  /** Roughness jitter bumps per second per surface at Rough = 1 */
  private static final double ROUGH_RATE_MAX_HZ = 25;

  /** Roughness jitter amplitude, +/- fraction of full height (~2.7 rows) */
  private static final double ROUGH_AMP_FRAC = 0.06;

  /** Treble flash exponential decay time constant (ms); < 10% by ~105 ms */
  private static final double FLASH_DECAY_MS = 45;

  /** Fraction of peak-band pixels that flash at TrbSprk = 1 */
  private static final double FLASH_COVERAGE = 0.45;

  /** Treble flashes spill this many rows down into the rock band */
  private static final double FLASH_ROCK_SPILL_ROWS = 2;

  // Elevation band tops as fractions of full sculpture height (before BandShift)
  private static final double SAND_TOP = 0.30;
  private static final double GRASS_TOP = 0.55;
  private static final double ROCK_TOP = 0.80;

  // ---- Fixed colors (sea + sky; land bands come from the palette) ------------

  private static final int SKY = LXColor.BLACK;
  private static final int WATER_DEEP = LXColor.hsb(215, 85, 40);
  private static final int WATERLINE = LXColor.hsb(190, 35, 100);

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

  public final CompoundParameter upliftSize = new CompoundParameter("Uplift", 0.5)
    .setDescription("Amplitude of new mountain uplifts");

  public final CompoundParameter erosion = new CompoundParameter("Erosion", 0.4, 0, 10)
    .setDescription("How fast mountains age: neighbor diffusion plus slow subsidence; quadratic knob, top is 10x");

  public final CompoundParameter rough = new CompoundParameter("Rough", 0.25)
    .setDescription("Terrain ruggedness: continuous small jitter bumps and divots injected into the land");

  public final CompoundParameter seaBias = new CompoundParameter("SeaBias", 0.55, 0.15, 0.85)
    .setDescription("Baseline sea level as a fraction of sculpture height; music level lowers the sea from here, erosion silt raises it");

  public final CompoundParameter bandShift = new CompoundParameter("Bands", 0, -0.2, 0.2)
    .setDescription("Shifts all elevation band thresholds up (+) or down (-) as a fraction of height");

  public final BooleanParameter whiteCaps = new BooleanParameter("WhtCps", false)
    .setDescription("Render the peaks band pure white; swatch color 0 is skipped (rock/grass/sand stay on swatch 1/2/3) and TrbSprk crackles the peaks dark for contrast");

  public final CompoundParameter trbSprk = new CompoundParameter("TrbSprk", 0.5)
    .setDescription("Treble-hit white flash bursts on the peaks");

  public final CompoundParameter audioDepth = new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final EnumParameter<Tempo.Division> tempoDiv = new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division that motion events land on");

  public final TriggerParameter rndTrig = new TriggerParameter("RndTrig", bag::fire)
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

  /**
   * Rising mountains: fixed pool of eased envelope events rendered as an
   * additive overlay on height[], committed into target[]+height[] exactly at
   * their deadline so the peak lands on the tempo grid (the background chase
   * rate would miss sub-second deadlines).
   */
  private static final int MAX_UPLIFTS = 8;

  private static final class UpliftPool {
    final int[] center = new int[MAX_UPLIFTS];
    final double[] ampRows = new double[MAX_UPLIFTS];
    final double[] sigmaCols = new double[MAX_UPLIFTS];
    final double[] elapsedMs = new double[MAX_UPLIFTS];
    final double[] durMs = new double[MAX_UPLIFTS]; // <= 0 marks a free slot
    final double[] overlay;

    UpliftPool(int width) {
      this.overlay = new double[width];
    }
  }

  private final UpliftPool cubeUplifts = new UpliftPool(Apotheneum.Cube.Ring.LENGTH);
  private final UpliftPool cylinderUplifts = new UpliftPool(Apotheneum.Cylinder.Ring.LENGTH);

  /** Smoothed music level substituting for the old Energy knob (0..1, tau = DRIVE_TAU_SEC) */
  private double drive = 0;

  /** Fast-smoothed music level steering the sea (0..1, tau = SEA_TAU_SEC) */
  private double seaLevel = 0;

  /** Eroded-mountain silt raising the sea goal (0..SILT_MAX, slow decay) */
  private double silt = 0;

  /** Current sea level as a fraction of sculpture height, shared by both surfaces */
  private double seaFrac;

  private int floodPhase = FLOOD_NONE;
  private double floodHoldMs = 0;

  /** Sea rise rate (fraction/s) while FLOOD_RISING; retimed onto the grid at trigger */
  private double floodRate = 1 / FLOOD_RAMP_SEC;

  private double shakeMs = 0;
  private double shakePhase = 0;

  /** Treble flash envelope (1 on a hit, exponential decay) and its per-hit pixel-subset seed */
  private double flash = 0;
  private int flashSeed = 0;

  // Land band colors from the palette: [0] peaks, [1] rock, [2] grass, [3] sand.
  // Scratch for perceptual hue allocation (no per-frame heap), as in Rubik.
  private final int[] bandColor = new int[4];
  private final float[] hueWork = new float[4];
  private final float[] hueOut = new float[4];

  public Terraform(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);
    this.erosion.setExponent(2);

    addParameter("cataclysm", this.cataclysm);
    addParameter("flood", this.flood);
    addParameter("reseed", this.reseed);
    addParameter("erupt", this.erupt);
    addParameter("rndTrig", this.rndTrig);
    addParameter("upliftSize", this.upliftSize);
    addParameter("erosion", this.erosion);
    addParameter("rough", this.rough);
    addParameter("seaBias", this.seaBias);
    addParameter("bandShift", this.bandShift);
    addParameter("whiteCaps", this.whiteCaps);
    addParameter("trbSprk", this.trbSprk);
    addParameter("audio", this.audioDepth);
    addParameter("tempoDiv", this.tempoDiv);

    // Jump candidates — mirrored 1:1 in Terraform.md "Jump candidates"
    this.bag.jumpable(this.erosion, 0.15, 3);
    this.bag.jumpable(this.upliftSize);
    this.bag.jumpable(this.rough, 0, 0.8);
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
    // Retime the ramp so the sea tops out on a tempo-grid boundary.
    // Slow-down only (max scale 1): FLOOD_RAMP_SEC already sits at the 5 s
    // full-traversal cap, so the ramp may stretch to ~7.1 s but never quicken.
    final double msUntilFull = (SEA_FLOOD - this.seaFrac) * FLOOD_RAMP_SEC * 1000;
    this.floodRate *= this.tempoLock.retime(msUntilFull, this.tempoDiv.getEnum(), TempoLock.DEFAULT_MIN_SCALE, 1);
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
    addGaussian(target, center, amp, sigma, 1.02 * fullHeight);
  }

  /** Add a wrap-aware Gaussian into the uplift overlay (unclamped; clamped at render). */
  private static void addOverlayBump(double[] overlay, int center, double amp, double sigma) {
    addGaussian(overlay, center, amp, sigma, Double.POSITIVE_INFINITY);
  }

  /**
   * The one Gaussian kernel behind bumps and overlays, so a rising overlay is
   * always the exact shape of the bump it commits as. Windowed to +/- 4 sigma
   * (tails beyond contribute < 1 LSB of height); the window is capped below
   * the antipode so no wrap column is visited twice.
   */
  private static void addGaussian(double[] field, int center, double amp, double sigma, double maxHeight) {
    final int w = field.length;
    final int c = Math.floorMod(center, w);
    final double denom = 2 * sigma * sigma;
    int r = (int) (4 * sigma);
    if (r > (w - 1) / 2) {
      r = (w - 1) / 2;
    }
    for (int off = -r; off <= r; ++off) {
      final int x = (c + off + w) % w;
      final double t = field[x] + amp * Math.exp(-(off * (double) off) / denom);
      field[x] = Math.min(t, maxHeight);
    }
  }

  // ---- Render ----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    final double dt = deltaMs / 1000.0;

    // -- Audio smoothers: drive substitutes for the old Energy knob; seaLevel
    // is fast enough for the sea to pump within a beat
    this.drive += (this.audio.level - this.drive) * Math.min(dt / DRIVE_TAU_SEC, 1);
    this.seaLevel += (this.audio.level - this.seaLevel) * Math.min(dt / SEA_TAU_SEC, 1);

    computeBandColors();

    // -- Advance uplift envelopes; completed ones commit into target + height.
    // Runs before the spawn decision (so a rise booked this frame is not
    // credited this frame's pre-booking deltaMs, which would land its peak a
    // frame ahead of the grid) and before erosion (so a committed bump ages
    // from the same frame).
    advanceUplifts(this.cubeUplifts, this.cubeTarget, this.cubeHeight, Apotheneum.GRID_HEIGHT, deltaMs);
    advanceUplifts(this.cylinderUplifts, this.cylinderTarget, this.cylinderHeight, Apotheneum.CYLINDER_HEIGHT, deltaMs);

    // -- Uplift: spontaneous births always run (silence-safe); bass adds when
    // the music drives hard. Births land on the tempo grid, firing on grid
    // crossings with a probability that preserves the Poisson expected rate
    // (capped at one birth per division).
    final double upliftRate = Ranges.exp(this.drive, UPLIFT_RATE_AMBIENT_HZ, UPLIFT_RATE_PEAK_HZ);
    // crossed() polls every frame so the gate never goes stale
    final boolean gridCross = this.tempoLock.crossed(this.tempoDiv.getEnum());
    final boolean timerUplift = gridCross
      && (this.random.nextDouble() < upliftRate * this.tempoLock.divisionMs(this.tempoDiv.getEnum()) * 0.001);
    // Gate scaled by depth() so it reads the depth-independent smoothed level:
    // drive tops out at the Audio knob value, and a fixed gate would silently
    // disable bass uplifts for any knob setting below it
    final boolean bassUplift = (this.drive >= BASS_UPLIFT_MIN_LEVEL * this.audio.depth()) && this.audio.bassHit();
    if (timerUplift || bassUplift) {
      // Bass-born mountains scale by how hard the transient hit
      spawnUplift(bassUplift ? 0.6 + 0.4 * Math.min(this.audio.bassRatio, 2.5) / 2.5 : 1);
    }

    // -- Roughness: continuous small jitter bumps and divots on the targets
    final double roughExpect = this.rough.getValue() * ROUGH_RATE_MAX_HZ * dt;
    roughen(this.cubeTarget, Apotheneum.GRID_HEIGHT, roughExpect);
    roughen(this.cylinderTarget, Apotheneum.CYLINDER_HEIGHT, roughExpect);

    // -- Erosion: diffusion + subsidence on targets; heights chase rate-limited.
    // The diffusion pass is sub-stepped so the stencil stays stable (each
    // sub-step k <= DIFFUSION_MAX_FRAME < 0.5) at the 10x top of the knob.
    // The silt loss is measured inside advanceTerrain, so it covers exactly
    // diffusion + subsidence — uplift commits and roughness jitter must stay
    // ABOVE this point or their target[] writes would be counted as erosion.
    final double kTotal = this.erosion.getValue() * Ranges.lin(this.drive, DIFFUSION_AMBIENT, DIFFUSION_PEAK) * dt;
    int diffusionSteps = 1 + (int) (kTotal / DIFFUSION_MAX_FRAME);
    if (diffusionSteps > MAX_DIFFUSION_STEPS) {
      diffusionSteps = MAX_DIFFUSION_STEPS;
    }
    final double kStep = Math.min(kTotal / diffusionSteps, DIFFUSION_MAX_FRAME);
    final double subsideTau = Ranges.exp(this.drive, SUBSIDE_TAU_AMBIENT_SEC, SUBSIDE_TAU_PEAK_SEC);
    final double subsideAlpha = 1 - Math.exp(-this.erosion.getValue() * dt / subsideTau);
    final double cubeLoss = advanceTerrain(this.cubeTarget, this.cubeHeight, this.cubeScratch,
      Apotheneum.GRID_HEIGHT, dt, kStep, diffusionSteps, subsideAlpha, this.seaFrac * Apotheneum.GRID_HEIGHT);
    final double cylinderLoss = advanceTerrain(this.cylinderTarget, this.cylinderHeight, this.cylinderScratch,
      Apotheneum.CYLINDER_HEIGHT, dt, kStep, diffusionSteps, subsideAlpha, this.seaFrac * Apotheneum.CYLINDER_HEIGHT);

    // -- Silt: eroded above-sea volume raises the sea goal, washing out slowly
    this.silt += SILT_GAIN * 0.5 * (cubeLoss + cylinderLoss);
    this.silt -= this.silt * Math.min(dt / SILT_DECAY_SEC, 1);
    if (this.silt > SILT_MAX) {
      this.silt = SILT_MAX;
    }

    // -- Sea level: tracks the fast-smoothed music level plus silt, reaching
    // any goal within one beat at the current tempo; flood overrides
    double seaGoal = LXUtils.constrain(
      this.seaBias.getValue() - SEA_SWING * this.seaLevel + this.silt, SEA_MIN, SEA_MAX);
    double seaRate = 1000 / this.lx.engine.tempo.period.getValue(); // full sweep in one beat
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

    // -- Treble flash: each treble hit fires a white burst on a fresh random
    // subset of peak pixels, decaying fast enough to read as a strobe glint.
    // Hits are boolean at any depth > 0.01, so the burst amplitude carries
    // the depth scaling itself (the AudioReactive hit-response contract)
    if (this.audio.trebleHit()) {
      this.flash = this.audio.depth();
      this.flashSeed = this.random.nextInt();
    }
    this.flash -= this.flash * Math.min(deltaMs / FLASH_DECAY_MS, 1);
    final int flashByte = (int) (256 * FLASH_COVERAGE * this.trbSprk.getValue());
    final double flashLevel = ((this.flash > 0.02) && (flashByte > 0)) ? this.flash : 0;

    setColors(LXColor.BLACK);
    renderSurface(Apotheneum.cube.exterior, this.cubeHeight, this.cubeUplifts.overlay,
      Apotheneum.GRID_HEIGHT, shakeAmp, flashLevel, flashByte);
    renderSurface(Apotheneum.cylinder.exterior, this.cylinderHeight, this.cylinderUplifts.overlay,
      Apotheneum.CYLINDER_HEIGHT, shakeAmp, flashLevel, flashByte);
    copyCubeExterior();
    copyCylinderExterior();
  }

  /**
   * Land band colors from the current palette swatch: peaks <- swatch 0,
   * rock <- 1, grass <- 2, sand <- 3. A short swatch is completed with
   * fully-saturated hues placed perceptually even against the defined ones
   * (same rules as Rubik's face colors). WhtCps forces the peaks band pure
   * white and discards swatch 0 without shifting the other bands.
   */
  private void computeBandColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int first = this.whiteCaps.isOn() ? 1 : 0;
    if (first == 1) {
      this.bandColor[0] = LXColor.WHITE;
    }
    int defined = 0;
    for (int i = first; i < 4; ++i) {
      if (i < swatch.size()) {
        final int c = swatch.get(i).getColor();
        this.bandColor[i] = c;
        this.hueWork[defined++] = PerceptualHue.toPerceptualPosition(LXColor.h(c));
      }
    }
    final int generate = (4 - first) - defined;
    if (generate > 0) {
      PerceptualHue.fillCircle(this.hueWork, defined, generate, this.hueOut);
      int j = 0;
      for (int i = Math.max(first, swatch.size()); i < 4; ++i) {
        this.bandColor[i] = PerceptualHue.color(this.hueOut[j++]);
      }
    }
  }

  /**
   * Terrain aging: sub-stepped neighbor diffusion (wrap-continuous) plus
   * exponential subsidence on target[], then height[] chases target[] with the
   * per-point rate limit (full height in no less than RISE_FULL_SEC).
   *
   * @return the normalized loss of visible (above-sea) terrain volume this
   *         frame, in units of (w x fullHeight) — the silt source
   */
  private static double advanceTerrain(double[] target, double[] height, double[] scratch,
                                       int fullHeight, double dt, double kStep, int steps,
                                       double subsideAlpha, double seaRows) {
    final int w = target.length;
    double visBefore = 0;
    for (int x = 0; x < w; ++x) {
      if (target[x] > seaRows) {
        visBefore += target[x] - seaRows;
      }
    }
    for (int s = 0; s < steps; ++s) {
      System.arraycopy(target, 0, scratch, 0, w);
      for (int x = 0; x < w; ++x) {
        final double left = scratch[(x + w - 1) % w];
        final double right = scratch[(x + 1) % w];
        target[x] = scratch[x] + kStep * (left + right - 2 * scratch[x]);
      }
    }
    final double maxStep = fullHeight * dt / RISE_FULL_SEC;
    double visAfter = 0;
    for (int x = 0; x < w; ++x) {
      double t = target[x];
      t -= t * subsideAlpha; // slump toward the sea floor
      if (t < 0) {
        t = 0;
      }
      target[x] = t;
      if (t > seaRows) {
        visAfter += t - seaRows;
      }
      final double d = t - height[x];
      height[x] += (d > maxStep) ? maxStep : (d < -maxStep) ? -maxStep : d;
    }
    final double loss = visBefore - visAfter;
    return (loss > 0) ? loss / (w * fullHeight) : 0;
  }

  /** Continuous roughness jitter: expect bumps this frame, +/- amplitude, tiny sigma. */
  private void roughen(double[] target, int fullHeight, double expect) {
    int n = (int) expect + ((this.random.nextDouble() < expect - (int) expect) ? 1 : 0);
    while (n-- > 0) {
      addBump(target,
        this.random.nextInt(target.length),
        (2 * this.random.nextDouble() - 1) * ROUGH_AMP_FRAC * fullHeight,
        1 + 2 * this.random.nextDouble(),
        fullHeight);
    }
  }

  /**
   * Book one new mountain on each surface (random independent centers),
   * sized by drive and the Uplift knob, optionally scaled by ampScale. The
   * rise is an eased envelope completing exactly on the tempo grid.
   * Zero-allocation; called from the render path and from the Erupt trigger.
   */
  private void spawnUplift(double ampScale) {
    final double amp = ampScale * this.upliftSize.getValue() * Ranges.lin(this.drive, UPLIFT_AMP_AMBIENT, UPLIFT_AMP_PEAK);
    final double sigmaFrac = Ranges.lin(this.drive, UPLIFT_SIGMA_AMBIENT, UPLIFT_SIGMA_PEAK);
    final double durMs = upliftDurationMs();
    bookUplift(this.cubeUplifts, this.cubeTarget, this.cubeHeight, Apotheneum.GRID_HEIGHT, amp, sigmaFrac, durMs);
    bookUplift(this.cylinderUplifts, this.cylinderTarget, this.cylinderHeight, Apotheneum.CYLINDER_HEIGHT, amp, sigmaFrac, durMs);
  }

  /**
   * Rise time for a new uplift: finish exactly on the next tempoDiv boundary,
   * unless it is less than half a division away — then target the boundary
   * after (grid-strict; durations land in [0.5, 1.5) divisions, and on-grid
   * timer births get exactly 1.0).
   */
  private double upliftDurationMs() {
    final Tempo.Division div = this.tempoDiv.getEnum();
    final double divMs = this.tempoLock.divisionMs(div);
    double untilNext = (1 - this.lx.engine.tempo.getBasis(div)) * divMs;
    if (untilNext < 0.5 * divMs) {
      untilNext += divMs;
    }
    return untilNext;
  }

  private void bookUplift(UpliftPool pool, double[] target, double[] height, int fullHeight,
                          double ampFrac, double sigmaFrac, double durMs) {
    final int w = pool.overlay.length;
    int slot = -1;
    for (int i = 0; i < MAX_UPLIFTS; ++i) {
      if (pool.durMs[i] <= 0) {
        slot = i;
        break;
      }
    }
    if (slot < 0) {
      // Pool full: steal the most-progressed rise by handing it to the
      // background chase without a pop — height keeps exactly the eased
      // fraction the overlay was showing (and that overlay contribution is
      // cancelled), target gets the full bump, and the chase raises the rest
      double best = -1;
      for (int i = 0; i < MAX_UPLIFTS; ++i) {
        final double p = pool.elapsedMs[i] / pool.durMs[i];
        if (p > best) {
          best = p;
          slot = i;
        }
      }
      final double s = Math.min(pool.elapsedMs[slot] / pool.durMs[slot], 1);
      final double p = s * s * (3 - 2 * s);
      addBump(target, pool.center[slot], pool.ampRows[slot], pool.sigmaCols[slot], fullHeight);
      addBump(height, pool.center[slot], p * pool.ampRows[slot], pool.sigmaCols[slot], fullHeight);
      addOverlayBump(pool.overlay, pool.center[slot], -p * pool.ampRows[slot], pool.sigmaCols[slot]);
      pool.durMs[slot] = 0;
    }
    pool.center[slot] = this.random.nextInt(w);
    pool.ampRows[slot] = ampFrac * fullHeight;
    pool.sigmaCols[slot] = Math.max(2, sigmaFrac * w);
    pool.elapsedMs[slot] = 0;
    pool.durMs[slot] = durMs;
  }

  /** Write an uplift's full Gaussian into both fields and free its slot. */
  private static void commitUplift(UpliftPool pool, int i, double[] target, double[] height, int fullHeight) {
    addBump(target, pool.center[i], pool.ampRows[i], pool.sigmaCols[i], fullHeight);
    addBump(height, pool.center[i], pool.ampRows[i], pool.sigmaCols[i], fullHeight);
    pool.durMs[i] = 0;
  }

  /** Advance one surface's uplift envelopes: rebuild the overlay, commit any that hit their deadline. */
  private static void advanceUplifts(UpliftPool pool, double[] target, double[] height, int fullHeight, double deltaMs) {
    Arrays.fill(pool.overlay, 0);
    for (int i = 0; i < MAX_UPLIFTS; ++i) {
      if (pool.durMs[i] <= 0) {
        continue;
      }
      pool.elapsedMs[i] += deltaMs;
      final double s = pool.elapsedMs[i] / pool.durMs[i];
      if (s >= 1) {
        commitUplift(pool, i, target, height, fullHeight);
      } else {
        final double p = s * s * (3 - 2 * s); // smoothstep ease, exactly 1 at the deadline
        addOverlayBump(pool.overlay, pool.center[i], p * pool.ampRows[i], pool.sigmaCols[i]);
      }
    }
  }

  /**
   * Draw one surface's skyline into the exterior color buffer. Elevation is
   * counted in rows above the ground: column.points[0] is the top row, so
   * elev = fullHeight - 1 - yi. Every column carries the full point count
   * (the model enforces this; door cutouts are masked by the core doors
   * effect, not by shorter columns) — indexing elevation from the top keeps
   * the sea and band thresholds aligned across all columns regardless.
   */
  private void renderSurface(Apotheneum.Orientation surface, double[] height, double[] overlay,
                             int fullHeight, double shakeAmp, double flashLevel, int flashByte) {
    final double seaRows = this.seaFrac * fullHeight;
    final double shiftRows = this.bandShift.getValue() * fullHeight;
    final double sandTop = SAND_TOP * fullHeight + shiftRows;
    final double grassTop = GRASS_TOP * fullHeight + shiftRows;
    final double rockTop = ROCK_TOP * fullHeight + shiftRows;
    final double flashFloor = rockTop - FLASH_ROCK_SPILL_ROWS;
    // White flash on white caps would be invisible; crackle the peaks dark instead
    final boolean flashDarkPeaks = this.whiteCaps.isOn();
    final Apotheneum.Column[] columns = surface.columns();
    for (int x = 0; x < columns.length; ++x) {
      double h = height[x] + overlay[x];
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
            c = this.bandColor[3];
          } else if (elev < grassTop) {
            c = this.bandColor[2];
          } else if (elev < rockTop) {
            c = this.bandColor[1];
          } else {
            c = this.bandColor[0];
          }
          // Treble flash: white bursts on the peaks band plus a short spill
          // into the rock band; the flashing subset is stable across one
          // flash's decay (hash re-seeded per hit) so it reads as a burst
          if ((flashLevel > 0) && (elev >= flashFloor)) {
            int hh = (x * 0x9E3779B1) ^ (elev * 0x85EBCA6B) ^ this.flashSeed;
            hh ^= hh >>> 15;
            hh *= 0x2C1B3C6D;
            hh ^= hh >>> 12;
            if ((hh & 0xFF) < flashByte) {
              final int flashColor = (flashDarkPeaks && (elev >= rockTop)) ? LXColor.BLACK : LXColor.WHITE;
              c = LXColor.lerp(c, flashColor, (float) flashLevel);
            }
          }
        } else {
          c = SKY;
        }
        this.colors[column.points[yi].index] = c;
      }
    }
  }
}
