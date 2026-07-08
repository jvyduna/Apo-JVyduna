package apotheneum.jvyduna.patterns.bliss;

import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXSwatch;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Recreation of "Lorre" by Egil Stevens (The Floating Point collection): a swarm
 * of particles tracing the two-lobed Lorenz butterfly attractor, slowly rotating
 * about its vertical axis, with speed-lit fading trails.
 *
 * Up to 150 particles (Count) integrate the Lorenz ODE (sigma = 10, beta = 8/3, rho adjustable)
 * in its natural coordinates, are rotated about the attractor's vertical z axis,
 * and are orthographically projected into two SurfaceCanvases (cube exterior ring
 * 200x45, cylinder 120x43). The cube canvas holds four views of the same attractor
 * 90 degrees apart (one centered on each face) and the cylinder two views 180
 * degrees apart, so from any side the sculpture reads as a single 3D attractor
 * seen from that direction. Interiors mirror the exteriors.
 *
 * Signature move: the Kick trigger punts every particle off the attractor and the
 * swarm visibly re-converges onto the butterfly over a few seconds.
 *
 * Color comes from the active palette swatch: each particle is dealt a random
 * swatch color at birth, and the Tint trigger crossfades every particle to its
 * next swatch color (mod swatch size) over one beat.
 *
 * Audio reactivity is gated by the standard Audio depth knob (default 0 = pure
 * screensaver): bass pumps the output brightness and bass transients fire
 * mini-kicks. BassSpd is deliberately independent of the Audio knob and acts
 * as its own depth control: it turns the bass level (depth-independent
 * bassFast, a fast-attack/fast-release smoothed band level) into a speed
 * surge of up to ~17x the current Speed, so particles lurch forward on
 * drumbeats and stay accelerated under sustained bass. Both knobs default
 * to 0 = pure screensaver.
 *
 * Triggers (Kick/Reseed/Tint, including RndTrig-fired ones) execute
 * immediately; alignment to the music happens in the composition timeline.
 * Y rotation is tempo-relative: one quarter turn per YRotDiv division.
 *
 * See Lorre.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Lorre")
@LXComponent.Description("A rotating Lorenz-attractor particle swarm with speed-lit fading trails on the cube and cylinder")
public class Lorre extends ApotheneumPattern {

  // ---- Lorenz system --------------------------------------------------------

  /** Maximum swarm particles; the Count knob activates 1..NUM_PARTICLES of them */
  private static final int NUM_PARTICLES = 150;

  private static final double SIGMA = 10;         // classic Lorenz sigma
  private static final double BETA = 8.0 / 3.0;   // classic Lorenz beta

  /**
   * Sim time units per wall second at Speed = 1 (100%). Calibrated to the old
   * build's slowest effective rate (0.16 base x 0.4 knob min x 1.01 energy x
   * 0.7 silence), so the rebaselined Speed knob preserves the old calibration:
   * one lobe orbit (~0.73 sim units at rho = 28, measured) takes ~16 s at
   * Speed 1, ~6.5 s at the default Speed 2.5 (the old default look), ~2.0 s at
   * the Speed 8 max. CURATE: verify orbit tempo on the sculpture.
   */
  private static final double SIM_RATE_AT_SPEED_1 = 0.045;

  /**
   * Max forward-Euler substep (sim units). Verified stable off-line: zero escapes
   * over 300 particles x 900 sim units at rho = 40.
   */
  private static final double MAX_SUBSTEP_DT = 0.004;

  /**
   * Max substeps per frame; longer frames dilate sim time instead of exploding.
   * Sized so the worst case (Speed 8 + saturated bass at full BassSpd,
   * simDt ~ 0.102 at 60 fps) clears the clamp with headroom.
   */
  private static final int MAX_SUBSTEPS = 32;

  /** Respawn a particle if it strays this far from the origin (attractor lives within ~100 even at rho = 45) */
  private static final double ESCAPE_RADIUS = 250;

  /** Sim time integrated at construction so the first frame already shows the butterfly */
  private static final double SETTLE_SIM_TIME = 2.0;

  // ---- Triggers / audio -------------------------------------------------------

  /**
   * Kick trigger: per-axis uniform perturbation magnitude (Lorenz units). Measured
   * re-convergence at silence-default rate: avg distance to attractor 13.5 -> 0.8
   * over 5 s. CURATE: confirm the scatter-and-snap-back reads on the sculpture.
   */
  private static final double KICK_MAGNITUDE = 25;

  /**
   * bassHit mini-kick magnitude (Lorenz units), scaled by audio depth. Raised
   * ~2-3x from the original 2-6 so max Audio depth is unmistakable.
   * CURATE: obvious punch at depth 1, but still short of the Kick trigger's 25.
   */
  private static final double MINI_KICK_MAGNITUDE = 10;

  /**
   * Floor on the Speed value the BassSpd drive scales against, preserving the
   * frozen-swarm lurch: at Speed 0 full bass still adds speed. Relative to
   * Speed, so the surge stays perceptible at any orbit tempo, and independent
   * of the Audio depth knob. CURATE.
   */
  private static final double BASS_SPEED_MIN_BASE = 1;

  /**
   * Bass drive to sim-rate gain: at BassSpd = 1, saturated bass runs the sim
   * at up to (1 + 16)x the current Speed -- 16x the forward progress of the
   * old 2x ceiling. The drive (AudioReactive.bassFast) is level-based, not
   * trough-referenced, so sustained bass keeps the sim accelerated while its
   * fast attack/release still resolves 16th-note hits at 160 BPM. CURATE.
   */
  private static final double BASS_SPEED_GAIN = 16;

  /** Output brightness pump per unit of depth-scaled bass: up to a 1.6x flash. CURATE. */
  private static final double BASS_PUMP = 0.6;

  /** trebleRatio above its running average shortens trails by up to ~3.5x. CURATE: crispness under busy hi-hats. */
  private static final double TREBLE_DECAY_GAIN = 0.35;

  // ---- Particle lifecycle -------------------------------------------------------

  /** Jitter (Lorenz units) applied to a newborn particle copied off a live one. CURATE. */
  private static final double BIRTH_JITTER = 1;

  // ---- Projection ---------------------------------------------------------------

  /** Cube ring shows 4 views of the attractor, 90 degrees apart, one per face */
  private static final int CUBE_VIEWS = 4;
  private static final int CUBE_VIEW_SPACING = Apotheneum.Cube.Ring.LENGTH / CUBE_VIEWS; // 50
  private static final int CUBE_VIEW_CENTER = CUBE_VIEW_SPACING / 2;                     // face center

  /** Cylinder shows 2 views, 180 degrees apart */
  private static final int CYLINDER_VIEWS = 2;
  private static final int CYLINDER_VIEW_SPACING = Apotheneum.Cylinder.Ring.LENGTH / CYLINDER_VIEWS; // 60
  private static final int CYLINDER_VIEW_CENTER = CYLINDER_VIEW_SPACING / 2;

  /** Blank rows kept above/below the attractor (brief: fit with a couple rows margin) */
  private static final int VERTICAL_MARGIN = 2;

  /**
   * Attractor z extent is ~[0, 2*rho - 6]: linear fit to long-run measured maxima
   * (z_max = 49.9 at rho=28, 74.0 at rho=40; 300 particles x 900 sim units)
   */
  private static final double Z_MAX_SLOPE = 2.0;
  private static final double Z_MAX_OFFSET = -6;

  /** Max rotated horizontal radius as a multiple of rho (measured max |(x,y)|: 33.0 at rho=28, 45.4 at rho=40) */
  private static final double U_SPAN_PER_RHO = 1.2;

  // ---- Color -------------------------------------------------------------------

  /** Particle speed |v| mapping to full brightness, as a multiple of rho (typical fast |v| ~ 8*rho). CURATE. */
  private static final double SPEED_FULL_PER_RHO = 8.0;

  /** Brightness floor so slow (lobe-center) particles stay visible at LED distance */
  private static final double BRIGHTNESS_FLOOR = 0.3;

  /**
   * Hue spread (degrees) across the attractor's height, centered on each
   * particle's palette hue, for depth cueing. +-12 degrees keeps every
   * particle unambiguously "its" swatch color. CURATE.
   */
  private static final double HUE_SPREAD_DEG = 24;

  /**
   * Per-particle color indices are dealt in [0, 60) and reduced mod the swatch
   * size at draw time; 60 = LCM(1..5) keeps the deal uniform for any swatch
   * size up to LXSwatch.MAX_COLORS, so the swatch can grow or shrink live
   * without re-dealing.
   */
  private static final int COLOR_INDEX_WRAP = 60;

  // ---- Trails ------------------------------------------------------------------

  /** Trail half-life range (ms), exp-mapped from the Trails knob. CURATE: max must not wash to mush. */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 150;
  private static final double TRAIL_HALF_LIFE_MAX_MS = 1200;

  // ---- Parameters ----------------------------------------------------------------

  /** Y-rotation rate choices: one quarter turn per division period, or frozen */
  public enum RotDiv {
    OFF("Off", null),
    QUARTER("1/4", Tempo.Division.QUARTER),
    HALF("1/2", Tempo.Division.HALF),
    WHOLE("1 bar", Tempo.Division.WHOLE),
    DOUBLE("2 bars", Tempo.Division.DOUBLE),
    FOUR("4 bars", Tempo.Division.FOUR),
    EIGHT("8 bars", Tempo.Division.EIGHT),
    SIXTEEN("16 bars", Tempo.Division.SIXTEEN);

    public final String label;
    public final Tempo.Division division; // null = frozen

    RotDiv(String label, Tempo.Division division) {
      this.label = label;
      this.division = division;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  private final TriggerBag bag = new TriggerBag("Lorre");

  public final TriggerParameter kick = bag.register(
    new TriggerParameter("Kick", this::kickSwarm)
    .setDescription("Punt every particle off the attractor; the swarm re-converges over ~5s"));

  public final TriggerParameter reseed = bag.register(
    new TriggerParameter("Reseed", this::reseedSwarm)
    .setDescription("Re-scatter all particles uniformly in the attractor's bounding box"));

  public final TriggerParameter tint = bag.register(
    new TriggerParameter("Tint", this::tintSwarm)
    .setDescription("Crossfade every particle to its next palette color over one beat"));

  public final TriggerParameter rndTrig =
    new TriggerParameter("RndTrig", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  public final CompoundParameter desat =
    new CompoundParameter("Desat", 0.55)
    .setDescription("Fast-particle desaturation: 0 = fully saturated, 1 = white-hot pastel heads");

  public final CompoundParameter rho =
    new CompoundParameter("Rho", 28, 20, 45)
    .setDescription("Lorenz rho: chaotic butterfly near 28, regime hops across 24-40");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 2.5, 0, 8)
    .setDescription("Orbit tempo: 0 = frozen moment, 1 = 100% (slowest orbit), 8 = 800%");

  public final DiscreteParameter count =
    new DiscreteParameter("Count", 40, 1, NUM_PARTICLES + 1)
    .setDescription("Simulated particles: decreases kill oldest first, increases birth out of the live swarm");

  public final CompoundParameter vis =
    new CompoundParameter("Vis%", 50, 0, 100)
    .setUnits(CompoundParameter.Units.PERCENT)
    .setDescription("Density reveal: 0 = a single particle and its trail, 100 = the whole swarm; hidden particles keep simulating");

  public final CompoundParameter yPos =
    new CompoundParameter("YPos", 0, -0.5, 0.5)
    .setDescription("Attractor vertical position: -0.5 = 50% below the sculpture, +0.5 = 50% above");

  public final EnumParameter<RotDiv> yRotDiv =
    new EnumParameter<RotDiv>("YRotDiv", RotDiv.FOUR)
    .setDescription("Y-rotation rate: one quarter turn per division; Off freezes rotation");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", 0.5, 0, 1)
    .setDescription("Trail persistence: half-life 150ms - 1.2s");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio depth: bass hits mini-kick the swarm, bass pumps output brightness, treble shortens trails; 0 = pure screensaver");

  public final CompoundParameter bassSpd =
    new CompoundParameter("BassSpd", 0)
    .setDescription("Bass level to speed, independent of the Audio knob: drives the orbit tempo up to ~17x the current Speed at full, holding under sustained bass");

  // ---- State (all preallocated; zero allocation in render) ----------------------

  private final AudioReactive audio;
  private final Random random = new Random();

  /** Particle positions in Lorenz coordinates: [axis x/y/z][particle] */
  private final double[][] pos = new double[3][NUM_PARTICLES];

  /** Last integration-step speed |v| per particle, for brightness */
  private final double[] spd = new double[NUM_PARTICLES];

  /** Per-particle palette color index in [0, COLOR_INDEX_WRAP), dealt at birth */
  private final int[] colorIndex = new int[NUM_PARTICLES];

  /** Committed global shift applied to every colorIndex, advanced by Tint */
  private int tintOffset = 0;

  /** In-flight Tint crossfade (one beat, captured at trigger time) */
  private boolean tintActive = false;
  private double tintElapsedMs = 0;
  private double tintDurationMs = 1;

  // Per-frame cache of the active swatch, tint-blended and decomposed once
  // (at most LXSwatch.MAX_COLORS entries) so the per-particle path stays flat
  private final int[] palColor = new int[LXSwatch.MAX_COLORS];
  private final float[] palHue = new float[LXSwatch.MAX_COLORS];
  private final float[] palSat = new float[LXSwatch.MAX_COLORS];
  private final float[] palBri = new float[LXSwatch.MAX_COLORS];

  /**
   * Live-particle ring window: slots (head + k) % NUM_PARTICLES for
   * k < activeCount are simulated; Count decreases advance head (killing
   * oldest first), increases birth at the tail. Compute scales with Count.
   */
  private int head = 0;
  private int activeCount = NUM_PARTICLES;

  private final SurfaceCanvas cubeCanvas =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT);
  private final SurfaceCanvas cylinderCanvas =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT);

  /** Per-view rotation cos/sin, refreshed each frame */
  private final double[] cubeCos = new double[CUBE_VIEWS];
  private final double[] cubeSin = new double[CUBE_VIEWS];
  private final double[] cylinderCos = new double[CYLINDER_VIEWS];
  private final double[] cylinderSin = new double[CYLINDER_VIEWS];

  private double rotationAngle = 0;

  public Lorre(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);

    // Fine control near the frozen end; 0/1/2/4/8 are the meaningful stops
    this.speed.setExponent(2);

    addParameter("kick", this.kick);
    addParameter("reseed", this.reseed);
    addParameter("tint", this.tint);
    addParameter("rndTrig", this.rndTrig);
    addParameter("desat", this.desat);
    addParameter("rho", this.rho);
    addParameter("speed", this.speed);
    addParameter("count", this.count);
    addParameter("vis", this.vis);
    addParameter("yPos", this.yPos);
    addParameter("yRotDiv", this.yRotDiv);
    addParameter("trails", this.trails);
    addParameter("audio", this.audioDepth);
    addParameter("bassSpd", this.bassSpd);

    // Jump candidates -- each line mirrors a row in Lorre.md's Jump-candidates table
    bag.jumpable(this.rho, 24, 40);          // regime-hopping, best jump in the suite
    bag.jumpable(this.yRotDiv, RotDiv.FOUR.ordinal(), RotDiv.SIXTEEN.ordinal()); // slow window, never Off
    bag.jumpable(this.trails);               // full range
    bag.jumpable(this.speed, 1.5, 3.5);      // capped: keeps orbit tempo in a musical window (was [0.6, 1.4] pre-rebaseline)

    // Scatter, then integrate silently so frame one already shows the butterfly
    reseedSwarm();
    final int settleSteps = (int) Math.ceil(SETTLE_SIM_TIME / MAX_SUBSTEP_DT);
    final double rhoNow = this.rho.getValue();
    for (int s = 0; s < settleSteps; ++s) {
      step(MAX_SUBSTEP_DT, rhoNow);
    }
  }

  // ---- Triggers ------------------------------------------------------------------

  // Triggers execute immediately; alignment to the music is the composition
  // timeline's job, not the pattern's.

  /** Kick: perturb every particle by a random offset; the attractor pulls them back */
  private void kickSwarm() {
    perturb(KICK_MAGNITUDE);
  }

  /** Tint: start a one-beat crossfade of every particle to its next palette color */
  private void tintSwarm() {
    if (this.tintActive) {
      // Retrigger mid-fade: commit the in-flight target, restart toward the next
      this.tintOffset = (this.tintOffset + 1) % COLOR_INDEX_WRAP;
    }
    this.tintActive = true;
    this.tintElapsedMs = 0;
    this.tintDurationMs = Math.max(1, this.lx.engine.tempo.period.getValue());
  }

  /** Array index of the k-th live particle in the ring window */
  private int slot(int k) {
    return (this.head + k) % NUM_PARTICLES;
  }

  /**
   * Reconcile the live window to the Count knob, called once per frame before
   * any physics. Decreases advance head, killing the oldest particles (their
   * trails fade out via canvas decay); increases birth at the tail by copying
   * a random live particle plus a small jitter, so newborns visibly emerge
   * from the swarm and diverge chaotically -- a sudden knob change reads as
   * births/deaths, never a discontinuity.
   */
  private void reconcileCount() {
    final int target = this.count.getValuei();
    while (this.activeCount > target) {
      this.head = (this.head + 1) % NUM_PARTICLES;
      --this.activeCount;
    }
    while (this.activeCount < target) {
      final int src = slot(this.random.nextInt(this.activeCount));
      final int i = slot(this.activeCount);
      this.pos[0][i] = this.pos[0][src] + (2 * this.random.nextDouble() - 1) * BIRTH_JITTER;
      this.pos[1][i] = this.pos[1][src] + (2 * this.random.nextDouble() - 1) * BIRTH_JITTER;
      this.pos[2][i] = this.pos[2][src] + (2 * this.random.nextDouble() - 1) * BIRTH_JITTER;
      this.spd[i] = 0;
      this.colorIndex[i] = this.random.nextInt(COLOR_INDEX_WRAP);
      ++this.activeCount;
    }
  }

  /** Reseed: re-scatter live particles uniformly in the attractor's bounding box */
  private void reseedSwarm() {
    final double rhoNow = this.rho.getValue();
    for (int k = 0; k < this.activeCount; ++k) {
      final int i = slot(k);
      this.pos[0][i] = (2 * this.random.nextDouble() - 1) * 0.8 * rhoNow;
      this.pos[1][i] = (2 * this.random.nextDouble() - 1) * rhoNow;
      this.pos[2][i] = this.random.nextDouble() * 0.75 * (Z_MAX_SLOPE * rhoNow + Z_MAX_OFFSET);
      this.colorIndex[i] = this.random.nextInt(COLOR_INDEX_WRAP);
    }
  }

  /** Offset every live particle by a uniform random vector in [-magnitude, magnitude]^3 */
  private void perturb(double magnitude) {
    for (int k = 0; k < this.activeCount; ++k) {
      final int i = slot(k);
      this.pos[0][i] += (2 * this.random.nextDouble() - 1) * magnitude;
      this.pos[1][i] += (2 * this.random.nextDouble() - 1) * magnitude;
      this.pos[2][i] += (2 * this.random.nextDouble() - 1) * magnitude;
    }
  }

  /** Respawn a lost particle near one of the attractor's two fixed points C+- */
  private void respawn(int i, double rhoNow) {
    final double wing = Math.sqrt(BETA * (rhoNow - 1)); // fixed points at (+-w, +-w, rho-1)
    final double sign = this.random.nextBoolean() ? 1 : -1;
    this.pos[0][i] = sign * wing + (2 * this.random.nextDouble() - 1) * 3;
    this.pos[1][i] = sign * wing + (2 * this.random.nextDouble() - 1) * 3;
    this.pos[2][i] = (rhoNow - 1) + (2 * this.random.nextDouble() - 1) * 3;
    this.spd[i] = 0;
    this.colorIndex[i] = this.random.nextInt(COLOR_INDEX_WRAP);
  }

  // ---- Integration -----------------------------------------------------------------

  /** One forward-Euler substep of h sim units for the live swarm, with escape guard */
  private void step(double h, double rhoNow) {
    final double escapeSq = ESCAPE_RADIUS * ESCAPE_RADIUS;
    for (int k = 0; k < this.activeCount; ++k) {
      final int i = slot(k);
      final double x = this.pos[0][i];
      final double y = this.pos[1][i];
      final double z = this.pos[2][i];
      final double dx = SIGMA * (y - x);
      final double dy = x * (rhoNow - z) - y;
      final double dz = x * y - BETA * z;
      final double nx = x + h * dx;
      final double ny = y + h * dy;
      final double nz = z + h * dz;
      if (Double.isFinite(nx) && Double.isFinite(ny) && Double.isFinite(nz)
          && (nx * nx + ny * ny + nz * nz) < escapeSq) {
        this.pos[0][i] = nx;
        this.pos[1][i] = ny;
        this.pos[2][i] = nz;
        this.spd[i] = Math.sqrt(dx * dx + dy * dy + dz * dz);
      } else {
        respawn(i, rhoNow);
      }
    }
  }

  // ---- Render ------------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);

    final double rhoNow = this.rho.getValue();

    // Reconcile the live-particle window to the Count knob before any physics
    reconcileCount();

    // bassHit -> mini-kick: a punchy small-scale version of the Kick trigger.
    // Hits are boolean (fire at any depth > 0.01), so the response is scaled
    // by depth(); works even on a frozen (Speed 0) swarm
    if (this.audio.bassHit()) {
      perturb(MINI_KICK_MAGNITUDE * this.audio.depth());
    }

    // Integration dt: Speed knob (0 = frozen moment) plus the BassSpd drive.
    // bassFast (level-based, fast attack/release, 0..1) rises on a kick within
    // ~15 ms and holds under sustained bass; it scales the *current* Speed by
    // up to BASS_SPEED_GAIN, so full BassSpd reads as a hard lurch at any
    // orbit tempo. Independent of the Audio depth knob -- BassSpd is its own
    // depth control for this coupling. Silence-safe: drive ~0 with no input.
    final double bassDrive = this.audio.bassFast;
    final double speedNow = this.speed.getValue();
    final double simRate = SIM_RATE_AT_SPEED_1
      * (speedNow + BASS_SPEED_GAIN * this.bassSpd.getValue() * bassDrive
         * Math.max(speedNow, BASS_SPEED_MIN_BASE));

    // Clamped-dt integration: fixed-size substeps; overlong frames dilate sim time
    double simDt = deltaMs * .001 * simRate;
    if (simDt > MAX_SUBSTEP_DT * MAX_SUBSTEPS) {
      simDt = MAX_SUBSTEP_DT * MAX_SUBSTEPS;
    }
    if (simDt > 0) {
      final int steps = Math.max(1, (int) Math.ceil(simDt / MAX_SUBSTEP_DT));
      final double h = simDt / steps;
      for (int s = 0; s < steps; ++s) {
        step(h, rhoNow);
      }
    }

    // Tempo-relative rotation about the attractor's vertical z axis: one
    // quarter turn per YRotDiv division. Incremental accumulation, so BPM or
    // division changes only change the rate -- the angle never snaps.
    final Tempo.Division rotDiv = this.yRotDiv.getEnum().division;
    if (rotDiv != null) {
      final double divMs = this.lx.engine.tempo.period.getValue() / rotDiv.multiplier;
      this.rotationAngle += (deltaMs / divMs) * (Math.PI / 2);
    }
    if (this.rotationAngle > 2 * Math.PI) {
      this.rotationAngle -= 2 * Math.PI;
    }

    // Advance the Tint crossfade; on completion commit the +1 shift for all
    double tintBlend = 0;
    if (this.tintActive) {
      this.tintElapsedMs += deltaMs;
      if (this.tintElapsedMs >= this.tintDurationMs) {
        this.tintActive = false;
        this.tintOffset = (this.tintOffset + 1) % COLOR_INDEX_WRAP;
      } else {
        tintBlend = this.tintElapsedMs / this.tintDurationMs;
      }
    }

    // Trail decay: Trails knob sets the half-life; busy treble shortens it (crisper).
    // Silence-safe: trebleRatio ~ 0 leaves the base half-life untouched.
    final double halfLifeMs = Ranges.exp(this.trails.getValue(), TRAIL_HALF_LIFE_MIN_MS, TRAIL_HALF_LIFE_MAX_MS)
      / (1 + TREBLE_DECAY_GAIN * Math.max(0, this.audio.trebleRatio - 1));
    final double decayMult = Math.pow(0.5, deltaMs / halfLifeMs);
    this.cubeCanvas.decay(decayMult);
    this.cylinderCanvas.decay(decayMult);

    plot(rhoNow, tintBlend);

    // Exterior surfaces from the canvases (door columns guarded inside copyTo);
    // bass pumps output brightness so the whole swarm flashes with the low end.
    // Silence/depth-0 (pump == 1) takes the plain copy -- bit-identical output
    final double pump = 1 + BASS_PUMP * this.audio.bass;
    if (pump > 1.001) {
      this.cubeCanvas.copyTo(Apotheneum.cube.exterior, this.colors, pump, false);
      this.cylinderCanvas.copyTo(Apotheneum.cylinder.exterior, this.colors, pump, false);
    } else {
      this.cubeCanvas.copyTo(Apotheneum.cube.exterior, this.colors);
      this.cylinderCanvas.copyTo(Apotheneum.cylinder.exterior, this.colors);
    }
    // ...then interiors mirror the exteriors
    copyCubeExterior();
    copyCylinderExterior();
  }

  /** Rotate, orthographically project, and plot the visible swarm into both canvases */
  private void plot(double rhoNow, double tintBlend) {
    // Per-view rotation: view v sees the attractor from a heading 360/N * v away,
    // so all views are one coherent 3D object at the center of the building.
    for (int v = 0; v < CUBE_VIEWS; ++v) {
      final double a = this.rotationAngle + v * (2 * Math.PI / CUBE_VIEWS);
      this.cubeCos[v] = Math.cos(a);
      this.cubeSin[v] = Math.sin(a);
    }
    for (int v = 0; v < CYLINDER_VIEWS; ++v) {
      final double a = this.rotationAngle + v * (2 * Math.PI / CYLINDER_VIEWS);
      this.cylinderCos[v] = Math.cos(a);
      this.cylinderSin[v] = Math.sin(a);
    }

    // Scales track rho so regime hops change shape/tempo, not size. Vertical fits
    // z in [0, zMax] with VERTICAL_MARGIN rows spare; horizontal is capped so each
    // view stays inside its own span of the ring.
    final double zMax = Z_MAX_SLOPE * rhoNow + Z_MAX_OFFSET;
    final double uMax = U_SPAN_PER_RHO * rhoNow;
    final double cubeVScale = (Apotheneum.GRID_HEIGHT - 2 * VERTICAL_MARGIN - 1) / zMax;
    final double cubeUScale = Math.min(cubeVScale, (CUBE_VIEW_SPACING / 2 - 1) / uMax);
    final double cylVScale = (Apotheneum.CYLINDER_HEIGHT - 2 * VERTICAL_MARGIN - 1) / zMax;
    final double cylUScale = Math.min(cylVScale, (CYLINDER_VIEW_SPACING / 2 - 1) / uMax);

    final double speedFull = SPEED_FULL_PER_RHO * rhoNow;
    final double whiteHot = this.desat.getValue();

    // Cache the active swatch once per frame: tint-blend toward each color's
    // successor, then decompose to h/s/b, so the per-particle path is flat
    // array reads. At most MAX_COLORS entries; swatch size changes just change n.
    final LXSwatch swatch = this.lx.engine.palette.swatch;
    final int n = Math.min(swatch.colors.size(), LXSwatch.MAX_COLORS);
    for (int c = 0; c < n; ++c) {
      this.palColor[c] = swatch.getColor(c).getColor();
    }
    for (int c = 0; c < n; ++c) {
      final int col = (tintBlend > 0)
        ? LXColor.lerp(this.palColor[c], this.palColor[(c + 1) % n], tintBlend)
        : this.palColor[c];
      this.palHue[c] = LXColor.h(col);
      this.palSat[c] = LXColor.s(col);
      this.palBri[c] = LXColor.b(col);
    }

    // YPos slides the attractor by up to half the surface height; off-canvas
    // rows are silently dropped by SurfaceCanvas.set(). Positive = up, and
    // canvas y is top-down, so the offset is negated
    final double yPosNow = this.yPos.getValue();
    final double cubeYOff = -yPosNow * Apotheneum.GRID_HEIGHT;
    final double cylYOff = -yPosNow * Apotheneum.CYLINDER_HEIGHT;

    // Vis gate: only the first visibleCount window slots are drawn; the rest
    // keep simulating invisibly (constant compute), so raising Vis is an
    // instant reveal. Ring order is uncorrelated with spatial position
    // (chaotic mixing), so density thins uniformly across the attractor.
    // CURATE: linear map; consider vis^2 if the low end needs finer control
    final int visibleCount = 1 + (int) Math.round((this.vis.getValue() / 100) * (this.activeCount - 1));

    for (int k = 0; k < visibleCount; ++k) {
      final int i = slot(k);
      final double x = this.pos[0][i];
      final double y = this.pos[1][i];
      final double z = this.pos[2][i];

      // Brightness by particle speed; fast heads run white-hot, slow ones stay
      // colored. Hue/sat/bri come from the particle's palette color (mod n at
      // draw time, so swatch resizes are safe) with the speed ramps and a
      // small vertical hue spread applied on top; LXColor.hsb wraps any hue.
      final double b = Math.min(1, this.spd[i] / speedFull);
      final int ci = (this.colorIndex[i] + this.tintOffset) % n;
      final float hueDeg = (float) (this.palHue[ci] + HUE_SPREAD_DEG * (z / zMax - 0.5));
      final float sat = (float) (this.palSat[ci] * (1 - whiteHot * b * b));
      final float bri = (float) (this.palBri[ci] * (BRIGHTNESS_FLOOR + (1 - BRIGHTNESS_FLOOR) * b));
      final int argb = LXColor.hsb(hueDeg, sat, bri);

      final int cubeY = (int) Math.round(VERTICAL_MARGIN + (zMax - z) * cubeVScale + cubeYOff);
      for (int v = 0; v < CUBE_VIEWS; ++v) {
        final double u = x * this.cubeCos[v] - y * this.cubeSin[v];
        this.cubeCanvas.set(CUBE_VIEW_CENTER + v * CUBE_VIEW_SPACING + (int) Math.round(u * cubeUScale), cubeY, argb);
      }

      final int cylY = (int) Math.round(VERTICAL_MARGIN + (zMax - z) * cylVScale + cylYOff);
      for (int v = 0; v < CYLINDER_VIEWS; ++v) {
        final double u = x * this.cylinderCos[v] - y * this.cylinderSin[v];
        this.cylinderCanvas.set(CYLINDER_VIEW_CENTER + v * CYLINDER_VIEW_SPACING + (int) Math.round(u * cylUScale), cylY, argb);
      }
    }
  }
}
