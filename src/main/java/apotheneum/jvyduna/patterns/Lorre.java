package apotheneum.jvyduna.patterns;

import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Recreation of "Lorre" by Egil Stevens (The Floating Point collection): a swarm
 * of particles tracing the two-lobed Lorenz butterfly attractor, slowly rotating
 * about its vertical axis, with speed-lit fading trails.
 *
 * 300 particles integrate the Lorenz ODE (sigma = 10, beta = 8/3, rho adjustable)
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
 * See Lorre.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Lorre")
@LXComponent.Description("A rotating Lorenz-attractor particle swarm with speed-lit fading trails on the cube and cylinder")
public class Lorre extends ApotheneumPattern {

  // ---- Lorenz system --------------------------------------------------------

  /** Number of swarm particles */
  private static final int NUM_PARTICLES = 300;

  private static final double SIGMA = 10;         // classic Lorenz sigma
  private static final double BETA = 8.0 / 3.0;   // classic Lorenz beta

  /**
   * Sim time units per wall second at Speed = 1, mid energy, mid audio level.
   * One orbit around a lobe takes ~0.73 sim units at rho = 28 (measured), so this
   * puts the lobe orbit at ~4.5 s with music, ~6.4 s in silence (brief: 4-8 s).
   * CURATE: verify orbit tempo on the sculpture.
   */
  private static final double BASE_SIM_RATE = 0.16;

  /**
   * Max forward-Euler substep (sim units). Verified stable off-line: zero escapes
   * over 300 particles x 900 sim units at rho = 40.
   */
  private static final double MAX_SUBSTEP_DT = 0.004;

  /** Max substeps per frame; longer frames dilate sim time instead of exploding */
  private static final int MAX_SUBSTEPS = 12;

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

  /** bassHit mini-kick magnitude (Lorenz units) at energy 0 / 1. CURATE: shimmer, not scatter. */
  private static final double MINI_KICK_AMBIENT = 2;
  private static final double MINI_KICK_PEAK = 6;

  /** Audio level breathes integration dt by +-30% (brief-specified) */
  private static final double DT_LEVEL_BREATHE = 0.3;

  /** trebleRatio above its running average shortens trails by up to ~3.5x. CURATE: crispness under busy hi-hats. */
  private static final double TREBLE_DECAY_GAIN = 0.35;

  // ---- Energy endpoints (ambient, peak) --------------------------------------

  /** Energy multiplies the sim rate: languid at ambient, urgent at peak */
  private static final double SIM_RATE_ENERGY_AMBIENT = 0.8;
  private static final double SIM_RATE_ENERGY_PEAK = 1.4;

  /** Fast particles desaturate toward white-hot; energy raises the effect. CURATE. */
  private static final double WHITE_HOT_AMBIENT = 0.4;
  private static final double WHITE_HOT_PEAK = 0.8;

  // ---- Motion caps ------------------------------------------------------------

  /** Fastest Y rotation (Rotate = 1): one full revolution in 30 s (>= 30 s per brief) */
  private static final double ROTATION_MAX_REV_PER_SEC = 1.0 / 30.0;

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

  /** Perceptual hue spread across the attractor's height, for depth cueing. CURATE. */
  private static final double HUE_SPREAD = 0.12;

  // ---- Trails ------------------------------------------------------------------

  /** Trail half-life range (ms), exp-mapped from the Trails knob. CURATE: max must not wash to mush. */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 150;
  private static final double TRAIL_HALF_LIFE_MAX_MS = 1200;

  // ---- Parameters ----------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Lorre");

  public final TriggerParameter kick = bag.register(
    new TriggerParameter("Kick", this::kickSwarm)
    .setDescription("Punt every particle off the attractor; the swarm re-converges over ~5s"));

  public final TriggerParameter reseed = bag.register(
    new TriggerParameter("Reseed", this::reseedSwarm)
    .setDescription("Re-scatter all particles uniformly in the attractor's bounding box"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 ambient drift, 0.6-1.0 high-energy show");

  public final CompoundParameter rho =
    new CompoundParameter("Rho", 28, 20, 45)
    .setDescription("Lorenz rho: chaotic butterfly near 28, regime hops across 24-40");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1, 0.4, 1.6)
    .setDescription("Integration rate multiplier (scales the swarm's orbit tempo)");

  public final CompoundParameter rotate =
    new CompoundParameter("Rotate", 0.75, 0, 1)
    .setDescription("Y-rotation speed: 0 = none, 1 = one revolution per 30s");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", 0.5, 0, 1)
    .setDescription("Trail persistence: half-life 150ms - 1.2s");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0.58, 0, 1)
    .setDescription("Base hue (perceptual position; default is Lorenz-classic blue)");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- State (all preallocated; zero allocation in render) ----------------------

  private final AudioReactive audio;
  private final Random random = new Random();

  /** Particle positions in Lorenz coordinates: [axis x/y/z][particle] */
  private final double[][] pos = new double[3][NUM_PARTICLES];

  /** Last integration-step speed |v| per particle, for brightness */
  private final double[] spd = new double[NUM_PARTICLES];

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
    this.audio = new AudioReactive(lx);

    addParameter("kick", this.kick);
    addParameter("reseed", this.reseed);
    addParameter("energy", this.energy);
    addParameter("rho", this.rho);
    addParameter("speed", this.speed);
    addParameter("rotate", this.rotate);
    addParameter("trails", this.trails);
    addParameter("hue", this.hue);
    addParameter("meta", this.meta);

    // Jump candidates -- each line mirrors a row in Lorre.md's Jump-candidates table
    bag.jumpable(this.rho, 24, 40);        // regime-hopping, best jump in the suite
    bag.jumpable(this.rotate, 0.15, 1.0);  // avoid a dead-stop jump
    bag.jumpable(this.trails);             // full range
    bag.jumpable(this.speed, 0.6, 1.4);    // capped: keeps orbit tempo in a musical window
    bag.jumpable(this.hue);                // full range

    // Scatter, then integrate silently so frame one already shows the butterfly
    reseedSwarm();
    final int settleSteps = (int) Math.ceil(SETTLE_SIM_TIME / MAX_SUBSTEP_DT);
    final double rhoNow = this.rho.getValue();
    for (int s = 0; s < settleSteps; ++s) {
      step(MAX_SUBSTEP_DT, rhoNow);
    }
  }

  // ---- Triggers ------------------------------------------------------------------

  /** Kick: perturb every particle by a random offset; the attractor pulls them back */
  private void kickSwarm() {
    perturb(KICK_MAGNITUDE);
  }

  /** Reseed: re-scatter particles uniformly in the attractor's bounding box */
  private void reseedSwarm() {
    final double rhoNow = this.rho.getValue();
    for (int i = 0; i < NUM_PARTICLES; ++i) {
      this.pos[0][i] = (2 * this.random.nextDouble() - 1) * 0.8 * rhoNow;
      this.pos[1][i] = (2 * this.random.nextDouble() - 1) * rhoNow;
      this.pos[2][i] = this.random.nextDouble() * 0.75 * (Z_MAX_SLOPE * rhoNow + Z_MAX_OFFSET);
    }
  }

  /** Offset every particle by a uniform random vector in [-magnitude, magnitude]^3 */
  private void perturb(double magnitude) {
    for (int i = 0; i < NUM_PARTICLES; ++i) {
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
  }

  // ---- Integration -----------------------------------------------------------------

  /** One forward-Euler substep of h sim units for the whole swarm, with escape guard */
  private void step(double h, double rhoNow) {
    final double escapeSq = ESCAPE_RADIUS * ESCAPE_RADIUS;
    for (int i = 0; i < NUM_PARTICLES; ++i) {
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

    final double e = this.energy.getValue();
    final double rhoNow = this.rho.getValue();

    // bassHit -> mini-kick: a shimmer-scale version of the Kick trigger
    if (this.audio.bassHit()) {
      perturb(Ranges.lin(e, MINI_KICK_AMBIENT, MINI_KICK_PEAK));
    }

    // Integration dt: Speed knob x energy x level breathing (+-30%, brief-specified).
    // Silence-safe: level = 0 just settles at 0.7x base rate.
    final double simRate = BASE_SIM_RATE
      * this.speed.getValue()
      * Ranges.lin(e, SIM_RATE_ENERGY_AMBIENT, SIM_RATE_ENERGY_PEAK)
      * (1 + DT_LEVEL_BREATHE * (2 * this.audio.level - 1));

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

    // Slow rotation about the attractor's vertical z axis
    this.rotationAngle += deltaMs * .001 * this.rotate.getValue() * ROTATION_MAX_REV_PER_SEC * 2 * Math.PI;
    if (this.rotationAngle > 2 * Math.PI) {
      this.rotationAngle -= 2 * Math.PI;
    }

    // Trail decay: Trails knob sets the half-life; busy treble shortens it (crisper).
    // Silence-safe: trebleRatio ~ 0 leaves the base half-life untouched.
    final double halfLifeMs = Ranges.exp(this.trails.getValue(), TRAIL_HALF_LIFE_MIN_MS, TRAIL_HALF_LIFE_MAX_MS)
      / (1 + TREBLE_DECAY_GAIN * Math.max(0, this.audio.trebleRatio - 1));
    final double decayMult = Math.pow(0.5, deltaMs / halfLifeMs);
    this.cubeCanvas.decay(decayMult);
    this.cylinderCanvas.decay(decayMult);

    plot(e, rhoNow);

    // Exterior surfaces from the canvases (door columns guarded inside copyTo)...
    this.cubeCanvas.copyTo(Apotheneum.cube.exterior, this.colors);
    this.cylinderCanvas.copyTo(Apotheneum.cylinder.exterior, this.colors);
    // ...then interiors mirror the exteriors
    copyCubeExterior();
    copyCylinderExterior();
  }

  /** Rotate, orthographically project, and plot the swarm into both canvases */
  private void plot(double e, double rhoNow) {
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
    final double whiteHot = Ranges.lin(e, WHITE_HOT_AMBIENT, WHITE_HOT_PEAK);
    final double hueBase = this.hue.getValue();

    for (int i = 0; i < NUM_PARTICLES; ++i) {
      final double x = this.pos[0][i];
      final double y = this.pos[1][i];
      final double z = this.pos[2][i];

      // Brightness by particle speed; fast heads run white-hot, slow ones stay colored
      final double b = Math.min(1, this.spd[i] / speedFull);
      final float hueDeg = PerceptualHue.toSpectralHue((float) (hueBase + HUE_SPREAD * z / zMax));
      final float sat = (float) (100 * (1 - whiteHot * b * b));
      final float bri = (float) (100 * (BRIGHTNESS_FLOOR + (1 - BRIGHTNESS_FLOOR) * b));
      final int argb = LXColor.hsb(hueDeg, sat, bri);

      final int cubeY = (int) Math.round(VERTICAL_MARGIN + (zMax - z) * cubeVScale);
      for (int v = 0; v < CUBE_VIEWS; ++v) {
        final double u = x * this.cubeCos[v] - y * this.cubeSin[v];
        this.cubeCanvas.set(CUBE_VIEW_CENTER + v * CUBE_VIEW_SPACING + (int) Math.round(u * cubeUScale), cubeY, argb);
      }

      final int cylY = (int) Math.round(VERTICAL_MARGIN + (zMax - z) * cylVScale);
      for (int v = 0; v < CYLINDER_VIEWS; ++v) {
        final double u = x * this.cylinderCos[v] - y * this.cylinderSin[v];
        this.cylinderCanvas.set(CYLINDER_VIEW_CENTER + v * CYLINDER_VIEW_SPACING + (int) Math.round(u * cylUScale), cylY, argb);
      }
    }
  }
}
