package apotheneum.jvyduna.patterns.bliss;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
import apotheneum.jvyduna.util.TempoLock;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * An evolving terrain skyline wrapped around the sculpture. A 1D heightfield
 * circles the cube ring (200 columns) and, independently, the cylinder (120
 * columns). Each column is rendered bottom-up as elevation-banded land over a
 * settable sea, with a bright one-row waterline at the sea surface.
 *
 * <p>The simulation has three legible, choreographable subsystems (2026-07-12
 * redesign):
 *
 * <ol>
 * <li><b>Tempo-driven eruptions</b> — on each {@code TrigDiv} phase crossing an
 *     eruption may fire (probability {@code Chance}), offset by {@code Phase}.
 *     Each eruption is a rise-then-fall overlay living exactly {@code MtnLife}
 *     with rise fraction {@code Duty}; it fully returns to baseline (never
 *     commits to the terrain). The manual {@code Erupt} trigger fires one now.
 *     All eruptions are forced to breach the current sea by
 *     {@code ERUPT_MARGIN_ROWS} so they are always visible.</li>
 * <li><b>Conservation-of-mass water</b> — a signed integrator: land rising
 *     above the {@code SeaLevel} reference pulls water down, land eroding lets
 *     it rise, and at rest the sea settles back to {@code SeaLevel}.
 *     {@code LndWtr} sets the coupling depth (strongest at mid SeaLevel); the
 *     water fraction is always clamped to [0,1] so SeaLevel 0 = dry and
 *     SeaLevel 1 = full.</li>
 * <li><b>Bands as a color count</b> — {@code Bands} (0..5) equal color bands
 *     ground&rarr;peak with a circular {@code BndPhase}; {@code Smooth} both
 *     antialiases the band and silhouette edges and motion-smooths the crest.
 *     {@code WhtCps} forces the highest band white.</li>
 * </ol>
 *
 * <p>Audio is used ONLY for the {@code TrbSprk} treble crest-flash, gated by the
 * {@code Audio} depth knob (default 0 = pure screensaver).
 *
 * <p>See Terraform.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Terraform")
@LXComponent.Description("Evolving terrain skyline: tempo-driven eruptions rise and fall, conservation-of-mass water couples to the land, and elevation color bands with a settable sea")
public class Terraform extends ApotheneumPattern {

  // ---- Timing constants (physical intent) -----------------------------------

  /** Floor on a mountain's total life so a tiny division can't divide by ~0 (ms) */
  private static final double MTN_LIFE_MIN_MS = 50;

  /** Peak cataclysm shake displacement, in rows */
  private static final double SHAKE_AMP_ROWS = 2.5;

  /** Cataclysm shake oscillation frequency (Hz) */
  private static final double SHAKE_RATE_HZ = 7;

  /** Spatial wavelength of the shake ripple, radians per column */
  private static final double SHAKE_WAVE = 0.6;

  // ---- Mountain / eruption constants -----------------------------------------

  /** Eruption amplitude (fraction of full height) at Uplift = 1, before breach forcing */
  private static final double UPLIFT_AMP_FRAC = 0.6;

  /** Eruption bump sigma (fraction of ring length) */
  private static final double UPLIFT_SIGMA_FRAC = 0.03;

  /** Erupt guarantees a new peak this many rows above the current sea surface */
  private static final double ERUPT_MARGIN_ROWS = 3;

  // ---- Conservation water constants ------------------------------------------

  /** Signed integrator gain: normalized above-sea volume change -> water displacement.
   *  CURATE: GAIN 6 chosen so a default eruption visibly dips/swells the sea; verify magnitude. */
  private static final double LAND_WATER_GAIN = 6;

  /** Water integrator decay time constant (s): the sea settles back to SeaLevel at rest.
   *  CURATE: TAU 8 s chosen to settle in a few seconds; verify it reads as a tide, not a lag. */
  private static final double LAND_WATER_TAU = 8;

  /** Per-frame clamp on the above-sea volume delta, so cataclysm/reseed can't spike the sea */
  private static final double LAND_WATER_DVOL_CLAMP = 0.05;

  // ---- Roughness constants ----------------------------------------------------

  /** Roughness jitter amplitude, +/- fraction of full height (~2.7 rows on the cube) */
  private static final double ROUGH_AMP_FRAC = 0.06;

  /** Roughness fades in over the first few rows of land, so cleared ground stays
   *  perfectly flat and eruptions don't pop craggy at birth (rows). */
  private static final double ROUGH_FADE_ROWS = 3;

  /** Roughness drift: columns re-targeted to a fresh random value per frame (per surface).
   *  CURATE: 1 col/frame + ease 0.04 chosen for slow craggy drift; verify not boiling/static. */
  private static final int ROUGH_RETARGET_PER_FRAME = 1;

  /** Roughness drift: per-frame ease of the field toward its (drifting) target. CURATE. */
  private static final double ROUGH_EASE = 0.04;

  // ---- Smooth (crest AA + motion) constants ----------------------------------

  /** Smooth = 1 anti-aliases band / silhouette / sea edges over +/- this many rows */
  private static final double SMOOTH_MAX_ROWS = 1.25;

  /** Smooth = 1 slows the crest motion low-pass to this fraction of instant.
   *  CURATE: 0.85 (alpha 0.15 at Smooth 1) chosen for a gliding, not laggy, crest. */
  private static final double CREST_MOTION_MAX = 0.85;

  // ---- Treble flash constants ------------------------------------------------

  /** Treble flash exponential decay time constant (ms); < 10% by ~105 ms */
  private static final double FLASH_DECAY_MS = 45;

  /** Fraction of crest pixels that flash at TrbSprk = 1 */
  private static final double FLASH_COVERAGE = 0.45;

  /** Treble flash reaches this many rows down from the crest */
  private static final double FLASH_CREST_ROWS = 4;

  // ---- Fixed colors (sea + sky; land bands come from the palette) ------------

  private static final int SKY = LXColor.BLACK;
  private static final int WATER_DEEP = LXColor.hsb(215, 85, 40);
  private static final int WATERLINE = LXColor.hsb(190, 35, 100);

  // ---- Parameters -------------------------------------------------------------

  public final TriggerParameter cataclysm =
    new TriggerParameter("Cataclysm", this::cataclysm)
    .setDescription("Heave up a transient ridge with a whole-ring shake over a 1/4 bar, then burst 4 concurrent eruptions; leaves no lasting terrain");

  public final TriggerParameter reseed =
    new TriggerParameter("Reseed", this::reseed)
    .setDescription("Glide every currently-active mountain to a fresh random position over a 1/4 bar; no new or persistent terrain");

  public final TriggerParameter erupt =
    new TriggerParameter("Erupt", this::erupt)
    .setDescription("Raise one new mountain now on each surface, forced to breach the current sea");

  public final EnumParameter<Tempo.Division> triggerDiv =
    new EnumParameter<Tempo.Division>("TrigDiv", Tempo.Division.WHOLE)
    .setDescription("Tempo division whose phase crossings offer an ambient eruption");

  public final CompoundParameter chance = new CompoundParameter("Chance", 0.5)
    .setDescription("Probability an eruption fires on each TrigDiv opportunity");

  public final EnumParameter<Tempo.Division> mtnLife =
    new EnumParameter<Tempo.Division>("MtnLife", Tempo.Division.FOUR)
    .setDescription("Total rise-then-fall lifetime of each eruption");

  public final CompoundParameter eruptDuty = new CompoundParameter("Duty", 0.3)
    .setDescription("Fraction of a mountain's life spent rising (rest is the fall)");

  public final CompoundParameter trigPhase = new CompoundParameter("Phase", 0)
    .setDescription("Phase offset within the TrigDiv cycle at which eruptions may fire");

  public final CompoundParameter upliftSize = new CompoundParameter("Uplift", 0.5)
    .setDescription("Amplitude of new mountain eruptions");

  public final CompoundParameter rough = new CompoundParameter("Rough", 0.25)
    .setDescription("Terrain ruggedness: a slowly-drifting per-column noise added to the displayed skyline");

  public final CompoundParameter seaLevel = new CompoundParameter("SeaLevel", 0.5, 0, 1)
    .setDescription("Resting sea level as a fraction of sculpture height; 0 = dry, 1 = fully flooded. Land displacement couples water around this via LndWtr");

  public final CompoundParameter lndWtr = new CompoundParameter("LndWtr", 0.5)
    .setDescription("Land-to-water coupling depth: how far a rising/eroding mountain pushes/pulls the sea (strongest at mid SeaLevel)");

  public final DiscreteParameter bands = new DiscreteParameter("Bands", 4, 0, 6)
    .setDescription("Number of equal elevation color bands ground->peak (0/1 = monochrome, up to 5)");

  public final CompoundParameter bndPhase = new CompoundParameter("BndPhase", 0)
    .setDescription("Circular phase shift of the color bands up the wall (phase 0 = phase 1)");

  public final CompoundParameter smoothing = new CompoundParameter("Smooth", 0.3)
    .setDescription("Anti-aliases band, silhouette and sea edges and motion-smooths the crest; 0 = hard/instant");

  public final BooleanParameter whiteCaps = new BooleanParameter("WhtCps", false)
    .setDescription("Force the highest color band pure white (snow cap); the treble flash then crackles the peaks dark");

  public final CompoundParameter trbSprk = new CompoundParameter("TrbSprk", 0.5)
    .setDescription("Treble-hit white flash bursts on the mountain crests");

  public final CompoundParameter audioDepth = new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full treble-flash reactivity");

  // ---- State (all preallocated; zero allocation in the render path) -----------

  private final AudioReactive audio;
  private final Random random = new Random();
  private final TempoLock tempoLock;

  // Base terrain in rows. There is no persistent landscape: the base stays flat
  // (all zero) and every mountain rides on top as a transient eruption overlay
  // (Erupt / TrigDiv / Cataclysm / Reseed). height[] is kept as the zero base so
  // the breach calc and renderer can index it uniformly.
  private final double[] cubeHeight = new double[Apotheneum.Cube.Ring.LENGTH];
  private final double[] cylinderHeight = new double[Apotheneum.Cylinder.Ring.LENGTH];

  // Slowly-drifting per-column roughness noise in [-1, 1] (field chases target).
  private final double[] cubeRough = new double[Apotheneum.Cube.Ring.LENGTH];
  private final double[] cubeRoughTarget = new double[Apotheneum.Cube.Ring.LENGTH];
  private final double[] cylinderRough = new double[Apotheneum.Cylinder.Ring.LENGTH];
  private final double[] cylinderRoughTarget = new double[Apotheneum.Cylinder.Ring.LENGTH];

  // Motion-smoothed crest (low-pass of the displayed height), rendered from.
  private final double[] cubeSmooth = new double[Apotheneum.Cube.Ring.LENGTH];
  private final double[] cylinderSmooth = new double[Apotheneum.Cylinder.Ring.LENGTH];

  /**
   * Rising-then-falling mountains: a fixed pool of Div-timed rise/fall
   * envelopes rendered as an additive overlay on height[]. Nothing commits to
   * the terrain — a mountain fully vanishes at end of life.
   */
  private static final int MAX_UPLIFTS = 16;

  private static final class MountainPool {
    final double[] center = new double[MAX_UPLIFTS]; // current (possibly gliding) column
    final double[] ampRows = new double[MAX_UPLIFTS];
    final double[] sigmaCols = new double[MAX_UPLIFTS];
    final double[] elapsedMs = new double[MAX_UPLIFTS];
    final double[] lifeMs = new double[MAX_UPLIFTS]; // <= 0 marks a free slot
    final double[] dutyFrac = new double[MAX_UPLIFTS];
    // Reseed center glide: interpolate center from `centerFrom` to `centerTo`
    // over `centerAnimDur` ms (0 = not gliding, center resting at its value).
    final double[] centerFrom = new double[MAX_UPLIFTS];
    final double[] centerTo = new double[MAX_UPLIFTS];
    final double[] centerAnimMs = new double[MAX_UPLIFTS];
    final double[] centerAnimDur = new double[MAX_UPLIFTS];
    final double[] overlay;

    MountainPool(int width) {
      this.overlay = new double[width];
    }
  }

  private final MountainPool cubeUplifts = new MountainPool(Apotheneum.Cube.Ring.LENGTH);
  private final MountainPool cylinderUplifts = new MountainPool(Apotheneum.Cylinder.Ring.LENGTH);

  /** Signed conservation-water integrator (0 at rest); displaces the sea around SeaLevel */
  private double landWater = 0;

  /** Previous frame's normalized above-(SeaLevel-reference) land volume, for the signed delta */
  private double prevAboveVol = 0;

  /** Current sea level as a fraction of sculpture height, shared by both surfaces */
  private double seaFrac;

  // Eruption phase-crossing detector state
  private double prevBasis = Double.NaN;
  private Tempo.Division prevTriggerDiv = null;

  private double shakeMs = 0;      // remaining shake time (linear decay)
  private double shakeDurMs = 1;   // total shake duration (envelope denominator)
  private double shakePhase = 0;

  /** Cataclysm deferred burst: after the ridge animates for a 1/4 bar, fire the
   *  4-eruption burst. >= 0 while counting down; < 0 = idle. */
  private double cataclysmEruptMs = -1;

  /** Treble flash envelope (1 on a hit, exponential decay) and its per-hit pixel-subset seed */
  private double flash = 0;
  private int flashSeed = 0;

  // Land band colors from the palette (up to 5). Scratch for perceptual hue
  // allocation (no per-frame heap), as in Rubik. mBands = active count (>= 1).
  private final int[] bandColor = new int[5];
  private final float[] hueWork = new float[5];
  private final float[] hueOut = new float[5];
  private int mBands = 1;

  public Terraform(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("cataclysm", this.cataclysm);
    addParameter("reseed", this.reseed);
    addParameter("erupt", this.erupt);
    addParameter("triggerDiv", this.triggerDiv);
    addParameter("chance", this.chance);
    addParameter("mtnLife", this.mtnLife);
    addParameter("eruptDuty", this.eruptDuty);
    addParameter("trigPhase", this.trigPhase);
    addParameter("upliftSize", this.upliftSize);
    addParameter("rough", this.rough);
    addParameter("seaLevel", this.seaLevel);
    addParameter("lndWtr", this.lndWtr);
    addParameter("bands", this.bands);
    addParameter("bndPhase", this.bndPhase);
    addParameter("smoothing", this.smoothing);
    addParameter("whiteCaps", this.whiteCaps);
    addParameter("trbSprk", this.trbSprk);
    addParameter("audio", this.audioDepth);

    // Start with a clear base: no terrain. height[]/smooth[] stay zero-filled, so
    // the sculpture opens on flat ground (sky + sea at SeaLevel) and every
    // mountain arrives later as a transient eruption (Erupt / TrigDiv / triggers).

    // Seed the roughness fields with a stable random craggy texture
    for (int x = 0; x < this.cubeRough.length; ++x) {
      this.cubeRough[x] = this.cubeRoughTarget[x] = 2 * this.random.nextDouble() - 1;
    }
    for (int x = 0; x < this.cylinderRough.length; ++x) {
      this.cylinderRough[x] = this.cylinderRoughTarget[x] = 2 * this.random.nextDouble() - 1;
    }

    // Settle the water at rest so there is no start-up jump: landWater = 0,
    // seaFrac = SeaLevel, and prevAboveVol captured from the (flat) terrain.
    final int cubeW = this.cubeUplifts.overlay.length;
    final int cylW = this.cylinderUplifts.overlay.length;
    final double ref = this.seaLevel.getValue();
    final double denom = cubeW * Apotheneum.GRID_HEIGHT + cylW * Apotheneum.CYLINDER_HEIGHT;
    final double sum =
      aboveVolume(this.cubeHeight, this.cubeUplifts.overlay, ref * Apotheneum.GRID_HEIGHT)
      + aboveVolume(this.cylinderHeight, this.cylinderUplifts.overlay, ref * Apotheneum.CYLINDER_HEIGHT);
    this.prevAboveVol = sum / denom;
    this.landWater = 0;
    this.seaFrac = ref;
  }

  // ---- Triggers --------------------------------------------------------------

  private void cataclysm() {
    LX.log("Terraform: cataclysm");
    final double durMs = quarterBarMs();
    bookRidge(this.cubeUplifts, Apotheneum.GRID_HEIGHT, durMs);
    bookRidge(this.cylinderUplifts, Apotheneum.CYLINDER_HEIGHT, durMs);
    this.shakeMs = durMs;
    this.shakeDurMs = durMs;
    this.cataclysmEruptMs = durMs; // burst 4 eruptions when the ridge finishes
  }

  private void erupt() {
    LX.log("Terraform: erupt");
    spawnEruptions(1);
  }

  private void reseed() {
    LX.log("Terraform: reseed");
    final double durMs = quarterBarMs();
    reseedGlide(this.cubeUplifts, durMs);
    reseedGlide(this.cylinderUplifts, durMs);
  }

  /** Duration of a 1/4 bar (quarter note) at the current tempo, floored (ms). */
  private double quarterBarMs() {
    return Math.max(MTN_LIFE_MIN_MS, this.tempoLock.divisionMs(Tempo.Division.QUARTER));
  }

  /**
   * A cataclysm mountain range as a transient overlay ridge: a tall central peak
   * flanked by two shoulders, rising then falling over {@code durMs} (a 1/4 bar,
   * duty 0.5 = symmetric) so it leaves no lasting terrain — the base stays clear.
   */
  private void bookRidge(MountainPool pool, int fullHeight, double durMs) {
    final int w = pool.overlay.length;
    final int center = this.random.nextInt(w);
    final double sigma = 0.05 * w;
    final int shoulder = (int) (1.5 * sigma);
    bookAt(pool, center, 0.85 * fullHeight, sigma, durMs, 0.5);
    bookAt(pool, center - shoulder, 0.55 * fullHeight, sigma, durMs, 0.5);
    bookAt(pool, center + shoulder, 0.55 * fullHeight, sigma, durMs, 0.5);
  }

  /**
   * Reseed: glide every active mountain from its current column to a fresh random
   * one over {@code durMs} (a 1/4 bar). Amplitude and life are untouched, so each
   * mountain keeps its current status and no new terrain is created. A no-op when
   * nothing is active.
   */
  private void reseedGlide(MountainPool pool, double durMs) {
    for (int i = 0; i < MAX_UPLIFTS; ++i) {
      if (pool.lifeMs[i] <= 0) {
        continue;
      }
      pool.centerFrom[i] = pool.center[i];
      pool.centerTo[i] = this.random.nextInt(pool.overlay.length);
      pool.centerAnimMs[i] = 0;
      pool.centerAnimDur[i] = durMs;
    }
  }

  /** Add a wrap-aware Gaussian into the uplift overlay (unclamped; clamped at render). */
  private static void addOverlayBump(double[] overlay, int center, double amp, double sigma) {
    addGaussian(overlay, center, amp, sigma, Double.POSITIVE_INFINITY);
  }

  /**
   * The one Gaussian kernel behind bumps and overlays, so a rising overlay is
   * always the exact shape of the bump it would commit as. Windowed to +/- 4
   * sigma (tails beyond contribute < 1 LSB of height); the window is capped
   * below the antipode so no wrap column is visited twice.
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

    computeBandColors();

    // -- Tempo-driven eruption scheduling: unconditionally poll the TrigDiv
    // sawtooth basis and fire (gated by Chance) on the frame it passes Phase.
    scheduleEruptions();

    // -- Advance mountain lifecycles: rebuild the additive overlay each frame,
    // free any that reached end of life (no commit — they fully vanish).
    advanceMountains(this.cubeUplifts, deltaMs);
    advanceMountains(this.cylinderUplifts, deltaMs);

    // -- Roughness: slowly drift the per-column noise field (zero allocation)
    driftRough(this.cubeRough, this.cubeRoughTarget);
    driftRough(this.cylinderRough, this.cylinderRoughTarget);

    // -- Conservation water: signed integrator around the SeaLevel reference.
    updateWater(dt);

    // -- Cataclysm shake envelope (1/4 bar, linearly decaying)
    double shakeAmp = 0;
    if (this.shakeMs > 0) {
      this.shakeMs -= deltaMs;
      this.shakePhase += dt * SHAKE_RATE_HZ * 2 * Math.PI;
      shakeAmp = SHAKE_AMP_ROWS * Math.max(this.shakeMs, 0) / this.shakeDurMs;
    }

    // -- Cataclysm deferred burst: once the ridge has animated a 1/4 bar, launch
    // the 4-eruption burst on each surface.
    if (this.cataclysmEruptMs >= 0) {
      this.cataclysmEruptMs -= deltaMs;
      if (this.cataclysmEruptMs <= 0) {
        this.cataclysmEruptMs = -1;
        spawnEruptions(4);
      }
    }

    // -- Treble flash: each treble hit fires a white burst on a fresh random
    // subset of crest pixels, decaying fast enough to read as a strobe glint.
    if (this.audio.trebleHit()) {
      this.flash = this.audio.depth();
      this.flashSeed = this.random.nextInt();
    }
    this.flash -= this.flash * Math.min(deltaMs / FLASH_DECAY_MS, 1);
    final int flashByte = (int) (256 * FLASH_COVERAGE * this.trbSprk.getValue());
    final double flashLevel = ((this.flash > 0.02) && (flashByte > 0)) ? this.flash : 0;

    // Crest motion low-pass factor: 1 = instant (Smooth 0), small = slow glide
    final double crestAlpha = LXUtils.constrain(1 - this.smoothing.getValue() * CREST_MOTION_MAX, 0.02, 1);

    setColors(LXColor.BLACK);
    renderSurface(Apotheneum.cube.exterior, this.cubeHeight, this.cubeUplifts.overlay,
      this.cubeRough, this.cubeSmooth, Apotheneum.GRID_HEIGHT, shakeAmp, crestAlpha, flashLevel, flashByte);
    renderSurface(Apotheneum.cylinder.exterior, this.cylinderHeight, this.cylinderUplifts.overlay,
      this.cylinderRough, this.cylinderSmooth, Apotheneum.CYLINDER_HEIGHT, shakeAmp, crestAlpha, flashLevel, flashByte);
    copyCubeExterior();
    copyCylinderExterior();
  }

  /**
   * Poll the TrigDiv sawtooth basis and, on the frame it passes the Phase
   * offset, spawn an eruption on each surface with probability Chance. A
   * division change or the first frame resyncs without firing.
   */
  private void scheduleEruptions() {
    final Tempo.Division div = this.triggerDiv.getEnum();
    final double basis = this.lx.engine.tempo.getBasis(div);
    final double phase = this.trigPhase.getValue();
    final boolean divChanged = (div != this.prevTriggerDiv);
    if (!divChanged && !Double.isNaN(this.prevBasis)
        && crossedPhase(this.prevBasis, basis, phase)
        && (this.random.nextDouble() < this.chance.getValue())) {
      spawnEruptions(1);
    }
    this.prevBasis = basis;
    this.prevTriggerDiv = div;
  }

  /**
   * Did the 0..1 sawtooth pass {@code phase} going from {@code prev} to
   * {@code cur}? Within one cycle (cur >= prev) it is prev &lt; phase &lt;= cur;
   * on a wrap (cur &lt; prev) it is (prev &lt; phase) || (phase &lt;= cur).
   */
  private static boolean crossedPhase(double prev, double cur, double phase) {
    if (cur >= prev) {
      return (prev < phase) && (phase <= cur);
    }
    return (prev < phase) || (phase <= cur);
  }

  /** Spawn {@code n} breach-forced eruptions on each surface (independent random centers). */
  private void spawnEruptions(int n) {
    for (int i = 0; i < n; ++i) {
      bookMountain(this.cubeUplifts, this.cubeHeight, Apotheneum.GRID_HEIGHT);
      bookMountain(this.cylinderUplifts, this.cylinderHeight, Apotheneum.CYLINDER_HEIGHT);
    }
  }

  /**
   * Book one rise-then-fall mountain: amplitude scaled by Uplift but forced high
   * enough to breach the current sea by ERUPT_MARGIN_ROWS so it is always
   * visible. Life = MtnLife, rise fraction = Duty. No terrain commit ever.
   */
  private void bookMountain(MountainPool pool, double[] height, int fullHeight) {
    final int w = pool.overlay.length;
    final int center = this.random.nextInt(w);
    final double lifeMs = Math.max(MTN_LIFE_MIN_MS, this.tempoLock.divisionMs(this.mtnLife.getEnum()));
    final double duty = LXUtils.constrain(this.eruptDuty.getValue(), 0.01, 0.99);
    double ampRows = this.upliftSize.getValue() * UPLIFT_AMP_FRAC * fullHeight;
    // Breach: force the crest at least ERUPT_MARGIN_ROWS above the current sea
    final double waterRows = this.seaFrac * fullHeight;
    final double neededRows = waterRows + ERUPT_MARGIN_ROWS - height[center];
    if (ampRows < neededRows) {
      ampRows = neededRows;
    }
    final double sigmaCols = Math.max(2, UPLIFT_SIGMA_FRAC * w);
    bookAt(pool, center, ampRows, sigmaCols, lifeMs, duty);
  }

  /** A free slot, or (pool full) the most-progressed one, stolen (no commit — a
   *  near-done mountain vanishing is imperceptible, and nothing pops). */
  private static int allocSlot(MountainPool pool) {
    for (int i = 0; i < MAX_UPLIFTS; ++i) {
      if (pool.lifeMs[i] <= 0) {
        return i;
      }
    }
    int slot = 0;
    double best = -1;
    for (int i = 0; i < MAX_UPLIFTS; ++i) {
      final double p = pool.elapsedMs[i] / pool.lifeMs[i];
      if (p > best) {
        best = p;
        slot = i;
      }
    }
    return slot;
  }

  /** Fill a fresh mountain slot at a resting center (no glide) with the given envelope. */
  private static void bookAt(MountainPool pool, double center, double ampRows,
                             double sigmaCols, double lifeMs, double duty) {
    final int slot = allocSlot(pool);
    pool.center[slot] = center;
    pool.ampRows[slot] = ampRows;
    pool.sigmaCols[slot] = sigmaCols;
    pool.elapsedMs[slot] = 0;
    pool.lifeMs[slot] = lifeMs;
    pool.dutyFrac[slot] = duty;
    pool.centerAnimDur[slot] = 0; // resting; not gliding
  }

  /**
   * Advance one surface's mountain lifecycles: rebuild the additive overlay
   * from each active envelope, free any that reached end of life. The envelope
   * rises (smoothstep 0->1) over the Duty fraction, then falls (1->0) over the
   * remainder; at end of life the slot frees with no terrain commit. A slot may
   * also be gliding its center to a new column (Reseed) over centerAnimDur.
   */
  private static void advanceMountains(MountainPool pool, double deltaMs) {
    final int w = pool.overlay.length;
    Arrays.fill(pool.overlay, 0);
    for (int i = 0; i < MAX_UPLIFTS; ++i) {
      if (pool.lifeMs[i] <= 0) {
        continue;
      }
      pool.elapsedMs[i] += deltaMs;
      final double p = pool.elapsedMs[i] / pool.lifeMs[i];
      if (p >= 1) {
        pool.lifeMs[i] = 0; // vanished; free the slot
        continue;
      }
      // Reseed center glide: interpolate along the shortest wrap path, then rest.
      if (pool.centerAnimDur[i] > 0) {
        pool.centerAnimMs[i] += deltaMs;
        final double g = pool.centerAnimMs[i] / pool.centerAnimDur[i];
        if (g >= 1) {
          pool.center[i] = Math.floorMod((int) Math.round(pool.centerTo[i]), w);
          pool.centerAnimDur[i] = 0;
        } else {
          pool.center[i] = wrapLerp(pool.centerFrom[i], pool.centerTo[i], smoothstep(g), w);
        }
      }
      final double duty = pool.dutyFrac[i];
      final double e = (p < duty)
        ? smoothstep(p / duty)
        : smoothstep(1 - (p - duty) / (1 - duty));
      final int center = Math.floorMod((int) Math.round(pool.center[i]), w);
      addOverlayBump(pool.overlay, center, e * pool.ampRows[i], pool.sigmaCols[i]);
    }
  }

  /** Interpolate a ring column from {@code from} to {@code to} along the shortest
   *  wrap path, returned in [0, w). */
  private static double wrapLerp(double from, double to, double t, int w) {
    double delta = to - from;
    delta -= w * Math.floor(delta / w + 0.5); // shortest signed delta in (-w/2, w/2]
    double c = from + delta * t;
    c -= w * Math.floor(c / w); // wrap into [0, w)
    return c;
  }

  /** Slowly drift a roughness field: re-target a few random columns, ease all toward target. */
  private void driftRough(double[] field, double[] target) {
    for (int k = 0; k < ROUGH_RETARGET_PER_FRAME; ++k) {
      target[this.random.nextInt(field.length)] = 2 * this.random.nextDouble() - 1;
    }
    for (int x = 0; x < field.length; ++x) {
      field[x] += (target[x] - field[x]) * ROUGH_EASE;
    }
  }


  /**
   * Conservation-of-mass water: a signed integrator around the SeaLevel
   * reference. Rising land (above-reference volume up) pulls the sea down,
   * eroding land (volume down) lets it rise, and at rest the integrator decays
   * to 0 so the sea settles back to SeaLevel. The reference is the knob (not the
   * live water), so there is no feedback loop. The final fraction is clamped to
   * [0,1] with an env(s) = 4 s (1 - s) weight, so SeaLevel 0 = dry and 1 = full.
   */
  private void updateWater(double dt) {
    final int cubeW = this.cubeUplifts.overlay.length;
    final int cylW = this.cylinderUplifts.overlay.length;
    final double ref = this.seaLevel.getValue();
    final double denom = cubeW * Apotheneum.GRID_HEIGHT + cylW * Apotheneum.CYLINDER_HEIGHT;
    final double sum =
      aboveVolume(this.cubeHeight, this.cubeUplifts.overlay, ref * Apotheneum.GRID_HEIGHT)
      + aboveVolume(this.cylinderHeight, this.cylinderUplifts.overlay, ref * Apotheneum.CYLINDER_HEIGHT);
    final double aboveVol = sum / denom;

    double dVol = aboveVol - this.prevAboveVol;
    dVol = LXUtils.constrain(dVol, -LAND_WATER_DVOL_CLAMP, LAND_WATER_DVOL_CLAMP);
    this.landWater -= LAND_WATER_GAIN * dVol;            // rise -> water down; erosion -> water up
    this.landWater -= this.landWater * Math.min(dt / LAND_WATER_TAU, 1); // decay to 0 at rest
    this.prevAboveVol = aboveVol;

    final double s = ref;
    final double env = 4 * s * (1 - s); // 0 at s=0 and s=1, peak 1 at s=0.5
    this.seaFrac = LXUtils.constrain(s + this.lndWtr.getValue() * env * this.landWater, 0, 1);
  }

  /** Sum over columns of max(0, (height + overlay) - seaRefRows): above-reference land volume. */
  private static double aboveVolume(double[] height, double[] overlay, double seaRefRows) {
    double sum = 0;
    for (int x = 0; x < height.length; ++x) {
      final double v = height[x] + overlay[x] - seaRefRows;
      if (v > 0) {
        sum += v;
      }
    }
    return sum;
  }

  /**
   * Land band colors from the current palette swatch: M = max(1, Bands) equal
   * bands ground->peak. The first min(M, swatch) colors come straight from the
   * swatch (anchored at their perceptual hue positions); any remaining bands are
   * generated perceptually (Rubik/Satori fillCircle idiom). WhtCps forces the
   * highest band (M-1) pure white without shifting the others. M = 1 is
   * monochrome bandColor[0].
   */
  private void computeBandColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int m = Math.max(1, this.bands.getValuei());
    this.mBands = m;

    final int defined = Math.min(m, swatch.size());
    for (int i = 0; i < defined; ++i) {
      final int c = swatch.get(i).getColor();
      this.bandColor[i] = c;
      this.hueWork[i] = PerceptualHue.toPerceptualPosition(LXColor.h(c));
    }
    final int generate = m - defined;
    if (generate > 0) {
      PerceptualHue.fillCircle(this.hueWork, defined, generate, this.hueOut);
      for (int j = 0; j < generate; ++j) {
        this.bandColor[defined + j] = PerceptualHue.color(this.hueOut[j]);
      }
    }
    if (this.whiteCaps.isOn()) {
      this.bandColor[m - 1] = LXColor.WHITE; // snow cap on the highest band
    }
  }

  /**
   * Draw one surface's skyline into the exterior color buffer. Elevation is
   * counted in rows above the ground: column.points[0] is the top row, so
   * elev = fullHeight - 1 - yi. Every column carries the full point count (the
   * model enforces this; door cutouts are masked by the core doors effect, not
   * by shorter columns), so indexing elevation from the top keeps the sea and
   * band boundaries aligned across all columns.
   *
   * <p>Per column: the displayed height (base + eruption overlay + roughness +
   * shake) is low-passed into smoothHeight[] (the crest glides at high Smooth,
   * snaps at Smooth 0), then rendered. Land is colored by circular elevation
   * band; the band edges, land/sky silhouette and sea surface are all
   * antialiased over +/- hw rows.
   */
  private void renderSurface(Apotheneum.Orientation surface, double[] height, double[] overlay,
                             double[] roughField, double[] smoothHeight, int fullHeight,
                             double shakeAmp, double crestAlpha, double flashLevel, int flashByte) {
    final double seaRows = this.seaFrac * fullHeight;
    // At SeaLevel 0 the surface sits on the physical ground plane, so the AA
    // band that would straddle it has nowhere below to render — draw nothing
    // wet at all. Without this the bottom row pins at a 50% waterline tint
    // (edge() is 0.5 exactly at the boundary), breaking the "0 = dry" invariant.
    final boolean dry = this.seaFrac <= 0;
    final int m = this.mBands;
    final double phaseShift = this.bndPhase.getValue();
    final double roughAmp = this.rough.getValue() * ROUGH_AMP_FRAC * fullHeight;
    // Anti-alias half-width (rows) for band, silhouette and sea-surface edges; 0 = hard
    final double hw = this.smoothing.getValue() * SMOOTH_MAX_ROWS;
    // White-cap crests flash dark (white-on-white would be invisible)
    final int flashColor = this.whiteCaps.isOn() ? LXColor.BLACK : LXColor.WHITE;

    final Apotheneum.Column[] columns = surface.columns();
    for (int x = 0; x < columns.length; ++x) {
      // Displayed height = flat base + eruption overlay + (land-gated) roughness
      // + shake. Roughness fades in with land height so cleared ground stays flat
      // and eruptions don't pop craggy at birth.
      final double landRows = height[x] + overlay[x];
      final double roughGate = LXUtils.constrain(landRows / ROUGH_FADE_ROWS, 0, 1);
      double disp = landRows + roughAmp * roughField[x] * roughGate;
      if (shakeAmp > 0) {
        disp += shakeAmp * Math.sin(x * SHAKE_WAVE + this.shakePhase);
      }
      // Motion low-pass: instant at Smooth 0 (alpha 1), gliding at Smooth 1
      smoothHeight[x] += (disp - smoothHeight[x]) * crestAlpha;
      final double h = smoothHeight[x];

      final Apotheneum.Column column = columns[x];
      final int len = column.points.length;
      for (int yi = 0; yi < len; ++yi) {
        final int elev = fullHeight - 1 - yi; // rows above the physical ground

        int land = bandLandColor(elev, fullHeight, m, phaseShift, hw);

        // Treble flash: white (or dark under WhtCps) bursts on the top rows of
        // the terrain; the flashing subset is stable across one flash's decay.
        if ((flashLevel > 0) && (elev <= h) && (elev >= h - FLASH_CREST_ROWS)) {
          int hh = (x * 0x9E3779B1) ^ (elev * 0x85EBCA6B) ^ this.flashSeed;
          hh ^= hh >>> 15;
          hh *= 0x2C1B3C6D;
          hh ^= hh >>> 12;
          if ((hh & 0xFF) < flashByte) {
            land = LXColor.lerp(land, flashColor, (float) flashLevel);
          }
        }

        // Land below the crest, sky above, antialiased across the silhouette
        final int aboveWater = LXColor.lerp(land, SKY, (float) edge(elev, h, hw));

        // Water (bright specular waterline on the top row) below the sea surface,
        // antialiased at the surface into the above-water color. When dry there
        // is no sea, so skip the water term entirely (see `dry` above).
        if (dry) {
          this.colors[column.points[yi].index] = aboveWater;
        } else {
          final int waterCol = (elev > seaRows - 1) ? WATERLINE : WATER_DEEP;
          this.colors[column.points[yi].index] =
            LXColor.lerp(waterCol, aboveWater, (float) edge(elev, seaRows, hw));
        }
      }
    }
  }

  /**
   * Color of a land pixel at elevation {@code elev} under M circular color
   * bands with the given phase shift, antialiased between adjacent bands over
   * +/- hw rows. M = 1 is monochrome. The bands are circular, so band M-1 blends
   * into band 0 (phase 0 == phase 1, top and bottom can share a color).
   */
  private int bandLandColor(int elev, int fullHeight, int m, double phaseShift, double hw) {
    if (m <= 1) {
      return this.bandColor[0];
    }
    final double u = elev / (double) fullHeight;       // 0..1 up the wall
    double pos = u + phaseShift;
    pos -= Math.floor(pos);                            // frac -> circular [0,1)
    final double b = pos * m;                          // band coordinate [0, m)
    int idx = (int) b;
    if (idx >= m) {
      idx = m - 1;
    }
    final int base = this.bandColor[idx];
    if (hw <= 0) {
      return base; // hard bands
    }
    // Blend toward the nearest band boundary in ROWS. One band spans
    // fullHeight/m rows; distances to the boundaries above/below this pixel:
    final double fb = b - idx;                          // fractional band position [0,1)
    final double rowsPerBand = fullHeight / (double) m;
    final double dUp = (1 - fb) * rowsPerBand;          // rows up to the next boundary
    final double dDown = fb * rowsPerBand;              // rows down to the previous boundary
    if (dUp <= dDown) {
      // Near the upper boundary: blend toward the next band (0.5 weight at the
      // boundary, fading to 0 by hw rows below it) — continuous across the seam.
      final double w = 0.5 * (1 - smoothstep(dUp / hw));
      return LXColor.lerp(base, this.bandColor[(idx + 1) % m], (float) w);
    }
    // Near the lower boundary: blend toward the previous band, symmetrically.
    final double w = 0.5 * (1 - smoothstep(dDown / hw));
    return LXColor.lerp(base, this.bandColor[(idx - 1 + m) % m], (float) w);
  }

  /**
   * Edge weight for an anti-aliased boundary: 0 below the boundary, 1 above,
   * smoothstepped across +/- halfWidth rows. A halfWidth <= 0 collapses to a
   * hard step.
   */
  private static double edge(double elev, double boundary, double halfWidth) {
    if (halfWidth <= 0) {
      return elev < boundary ? 0 : 1;
    }
    final double t = LXUtils.constrain((elev - boundary) / (2 * halfWidth) + 0.5, 0, 1);
    return t * t * (3 - 2 * t);
  }

  /** Smoothstep of a value clamped to [0,1]: 0 at 0, 1 at 1, zero slope at both ends. */
  private static double smoothstep(double t) {
    t = LXUtils.constrain(t, 0, 1);
    return t * t * (3 - 2 * t);
  }
}
