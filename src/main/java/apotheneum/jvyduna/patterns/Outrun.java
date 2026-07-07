package apotheneum.jvyduna.patterns;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
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

/**
 * Retro 80s fly-over: one camera skims an infinite neon grid / wireframe
 * topography, rendered as a true 360-degree panorama around the four cube
 * faces. Each of the 200 exterior ring columns is an azimuth ray from the
 * camera, so the landscape rushes toward you on the leading face and recedes
 * behind you on the trailing face — the feeling of the classic SR-71/B-2
 * T-shirt wireframe-terrain graphics and the synthwave "laser grid".
 *
 * Flat ground (Relief 0) is a closed-form reverse projection per pixel, a
 * Java port of the math in TitanicsEnd's outrun_grid.fs shader
 * (titanicsend/LXStudio-TE, te-app/resources/shaders/outrun_grid.fs). With
 * Relief up, each column raymarches a value-noise heightfield near-to-far
 * with painter's occlusion — the classic 80s wireframe terrain renderer.
 * Colors come from the project palette swatch as a depth gradient (near =
 * first swatch color), falling back to phosphor green-to-cyan.
 *
 * No audio reactivity in v1; the standard Audio knob is registered but
 * unwired. See Outrun.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Outrun")
@LXComponent.Description("Retro 80s fly-over: neon perspective grid and wireframe topography wrapped 360 degrees around the cube")
public class Outrun extends ApotheneumPattern {

  public enum FillMode {
    WIRE("Wire"),
    FILLED("Filled"),
    GLOW("Glow");

    private final String label;

    FillMode(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  // ---- Geometry / renderer constants -----------------------------------------

  private static final int RING = Apotheneum.Cube.Ring.LENGTH;   // 200 columns
  private static final int ROWS = Apotheneum.GRID_HEIGHT;        // 45 rows

  /** Near / far clip of the ground rendering, in world units (~grid cells) */
  private static final double NEAR = 0.45, FAR = 28.0;

  /** Raymarch samples per column (exponentially spaced NEAR..FAR) */
  private static final int STEPS = 64;

  /** Color LUT resolution (indexed by normalized depth) */
  private static final int LUT_SIZE = 64;

  /** Max palette swatch colors sampled into the gradient */
  private static final int MAX_SWATCH = 8;

  // ---- Timing constants (physical intent) -------------------------------------

  /** Pulse bolt life, launch underfoot to horizon fade (event-like, >= 1.5 s) */
  private static final double PULSE_SEC = 2.2;

  /** Bank trigger: 90-degree turn duration (quarter ring in 5 s = 20 s/rev) */
  private static final double BANK_SEC = 5.0;

  /** Boost speed envelope decay time constant */
  private static final double BOOST_TAU_SEC = 1.1;

  /** Regen terrain crossfade duration */
  private static final double REGEN_SEC = 5.0;

  // ---- Tuning constants --------------------------------------------------------

  /** Flight speed at Speed knob 0 / 1, world units per second (exp-mapped) */
  private static final double SPEED_MIN = 0.15, SPEED_MAX = 2.5;

  /** Energy multiplier on flight speed at ambient / peak */
  private static final double SPEED_E_AMBIENT = 0.7, SPEED_E_PEAK = 1.3;

  /** Boost trigger peak speed multiplier is 1 + this */
  private static final double BOOST_GAIN = 1.6;

  /** Camera altitude above the plane at Altitude knob 0 / 1, world units */
  private static final double ALT_MIN = 0.7, ALT_MAX = 4.5;

  /** Vertical field of view at FOV knob 0 / 1, degrees */
  private static final double VFOV_MIN = 38, VFOV_MAX = 110;

  /** Grid cell size at Grid knob 0 / 1, world units (knob up = denser grid) */
  private static final double CELL_MAX = 2.4, CELL_MIN = 0.45;

  /** Gridline half-width at Glow knob 0 / 1, in cell units */
  private static final double LINE_W_MIN = 0.03, LINE_W_MAX = 0.16;

  /** Peak terrain amplitude at Relief 1, world units */
  private static final double RELIEF_AMP = 3.0;

  /** Terrain noise base wavelength at Rough knob 0 / 1, world units */
  private static final double LAMBDA_MAX = 14, LAMBDA_MIN = 4.5;

  /** Second-octave weight scale (times Rough) */
  private static final double OCTAVE2_GAIN = 0.35;

  /** Camera keeps at least this clearance above the terrain, world units */
  private static final double CAM_CLEARANCE = 0.45;

  /** Horizon-glow band brightness at ambient / peak energy */
  private static final double HGLOW_AMBIENT = 0.18, HGLOW_PEAK = 0.5;

  /** Pulse bolt amplitude at ambient / peak energy */
  private static final double PULSE_AMBIENT = 0.5, PULSE_PEAK = 1.0;

  /** Silhouette ridge-line brightness (times depth fade), WIRE/FILLED modes */
  private static final double RIDGE_LEVEL = 0.55;

  /** Roll tilt of the horizon at the peak of a Bank, in rows */
  private static final double TILT_MAX_ROWS = 4.0;

  // ---- Parameters -------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Outrun");

  public final TriggerParameter boost = bag.register(
    new TriggerParameter("Boost", this::boost)
    .setDescription("Afterburner: flight speed surges ~2.5x and decays back over ~3 s"));

  public final TriggerParameter bank = bag.register(
    new TriggerParameter("Bank", this::bank)
    .setDescription("Banked 90-degree turn over ~5 s: heading swings and the horizon tilts, then settles"));

  public final TriggerParameter regen = bag.register(
    new TriggerParameter("Regen", this::regen)
    .setDescription("Reseed the terrain: the landscape morphs to fresh topography over ~5 s"));

  public final TriggerParameter pulse = bag.register(
    new TriggerParameter("Pulse", this::pulse)
    .setDescription("One bright bolt races across the grid from underfoot to the horizon"));

  public final CompoundParameter energy = new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 soothing ambient, 0.6-1.0 high-energy 160 BPM regime");

  public final CompoundParameter speed = new CompoundParameter("Speed", 0.4)
    .setDescription("Flight speed over the ground");

  public final CompoundParameter altitude = new CompoundParameter("Altitude", 0.5)
    .setDescription("Camera height above the grid plane: low skims the deck, high is a map view");

  public final CompoundParameter pitch = new CompoundParameter("Pitch", 0, -0.5, 0.5)
    .setDescription("Look up (+) or down (-): shifts the horizon row");

  public final CompoundParameter fov = new CompoundParameter("FOV", 0.5)
    .setDescription("Vertical field of view: perspective strength");

  public final CompoundParameter heading = new CompoundParameter("Heading", 0)
    .setDescription("Yaw offset: rotates the whole panorama around the ring (wraps)");

  public final CompoundParameter gridSize = new CompoundParameter("Grid", 0.5)
    .setDescription("Grid density: cell size shrinks as the knob rises");

  public final CompoundParameter glow = new CompoundParameter("Glow", 0.4)
    .setDescription("Gridline width and glow");

  public final CompoundParameter relief = new CompoundParameter("Relief", 0.35)
    .setDescription("Terrain amplitude: 0 is a perfectly flat laser grid");

  public final CompoundParameter rough = new CompoundParameter("Rough", 0.4)
    .setDescription("Terrain character: rolling dunes to jagged ridges (wavelength and octave mix)");

  public final EnumParameter<FillMode> fill = new EnumParameter<FillMode>("Fill", FillMode.WIRE)
    .setDescription("Topography fill: wireframe only, dim-filled ground, or continuous synthwave glow");

  public final CompoundParameter hue = new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Hue offset in degrees applied to the palette-derived depth gradient");

  public final CompoundParameter audioDepth = new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: reserved, no audio wiring in this version");

  public final BooleanParameter sync = new BooleanParameter("Sync", true)
    .setDescription("Lock motion events to the tempo grid; off restores free-running timing");

  public final EnumParameter<Tempo.Division> tempoDiv = new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division that motion events land on when Sync is enabled");

  public final TriggerParameter meta = new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- State (all preallocated; zero allocation in the render path) -----------

  private final TempoLock tempoLock;
  private final Random random = new Random();

  private final SurfaceCanvas canvas = new SurfaceCanvas(RING, ROWS);

  /** Value-noise lattice permutation, doubled to avoid a second mask */
  private final int[] perm = new int[512];

  /** Exponentially spaced raymarch distances NEAR..FAR, shared by all columns */
  private final double[] marchD = new double[STEPS];

  // Depth-gradient color LUT, rebuilt only when the swatch or Hue changes
  private final int[] lut = new int[LUT_SIZE];
  private final int[] cachedSwatch = new int[MAX_SWATCH];
  private int cachedSwatchCount = -1; // forces the initial build
  private double cachedHue = Double.NaN;

  // Camera state
  private double camX = 0, camZ = 0;

  /** Accumulated heading from completed Banks, radians (Heading knob adds on top) */
  private double bankBase = 0;

  // Bank envelope: progress in [0,1], >= 1 when idle
  private double bankT = 1;
  private double bankRate = 1 / BANK_SEC;
  private int bankDir = 1;

  /** Boost speed envelope, decays exponentially from 1 */
  private double boostEnv = 0;

  // Pulse bolt: progress in [0,1], >= 1 when idle
  private double pulseT = 1;
  private double pulseRate = 1 / PULSE_SEC;

  // Terrain seeds: world-space noise offsets, crossfaded old -> new on Regen
  private double ox0, oz0, ox1, oz1;
  private double morphT = 1; // >= 1 when settled on (ox1, oz1)

  // Per-frame derived values, stored as fields so per-pixel helpers stay
  // argument-light and allocation-free
  private double fCell, fLineW, fLambda, fOct2, fAmp, fGain;
  private double fPulseD, fPulseAmp;
  private double fCamY;

  public Outrun(LX lx) {
    super(lx);
    this.tempoLock = new TempoLock(lx);

    addParameter("boost", this.boost);
    addParameter("bank", this.bank);
    addParameter("regen", this.regen);
    addParameter("pulse", this.pulse);
    addParameter("energy", this.energy);
    addParameter("speed", this.speed);
    addParameter("altitude", this.altitude);
    addParameter("pitch", this.pitch);
    addParameter("fov", this.fov);
    addParameter("heading", this.heading);
    addParameter("gridSize", this.gridSize);
    addParameter("glow", this.glow);
    addParameter("relief", this.relief);
    addParameter("rough", this.rough);
    addParameter("fill", this.fill);
    addParameter("hue", this.hue);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    // Jump candidates — mirrored 1:1 in Outrun.md "Jump candidates"
    this.bag.jumpable(this.altitude);
    this.bag.jumpable(this.gridSize, 0.15, 0.85);
    this.bag.jumpable(this.relief);
    this.bag.jumpable(this.fov);
    this.bag.jumpable(this.heading);

    // Shuffled lattice permutation for the value noise
    for (int i = 0; i < 256; ++i) {
      this.perm[i] = i;
    }
    for (int i = 255; i > 0; --i) {
      final int j = this.random.nextInt(i + 1);
      final int t = this.perm[i];
      this.perm[i] = this.perm[j];
      this.perm[j] = t;
    }
    System.arraycopy(this.perm, 0, this.perm, 256, 256);

    // Exponential march spacing: constant depth resolution in screen space
    for (int i = 0; i < STEPS; ++i) {
      this.marchD[i] = NEAR * Math.pow(FAR / NEAR, i / (double) (STEPS - 1));
    }

    this.ox1 = randomOffset();
    this.oz1 = randomOffset();
    this.ox0 = this.ox1;
    this.oz0 = this.oz1;
  }

  private double randomOffset() {
    return (this.random.nextDouble() - 0.5) * 16384;
  }

  // ---- Triggers ----------------------------------------------------------------

  private void boost() {
    LX.log("Outrun: boost");
    this.boostEnv = 1;
  }

  private void bank() {
    if (this.bankT < 1) {
      return; // one turn at a time; retrigger after it settles
    }
    this.bankDir = this.random.nextBoolean() ? 1 : -1;
    LX.log("Outrun: bank " + ((this.bankDir > 0) ? "right" : "left"));
    this.bankT = 0;
    this.bankRate = 1 / BANK_SEC;
    if (this.sync.isOn()) {
      // Land the end of the turn on a grid boundary. Slow-down only (max
      // scale 1): BANK_SEC is the 90-degree pace that keeps a full pan at
      // 20 s/rev, so the turn may stretch but never quicken.
      this.bankRate *= this.tempoLock.retime(BANK_SEC * 1000, this.tempoDiv.getEnum(), TempoLock.DEFAULT_MIN_SCALE, 1);
    }
  }

  private void regen() {
    LX.log("Outrun: regen");
    // If a morph is still in flight, snap to its destination first; the
    // landscape pops only in the (rare) spam case, then morphs smoothly
    this.ox0 = this.ox1;
    this.oz0 = this.oz1;
    this.ox1 = randomOffset();
    this.oz1 = randomOffset();
    this.morphT = 0;
  }

  private void pulse() {
    LX.log("Outrun: pulse");
    this.pulseT = 0;
    this.pulseRate = 1 / PULSE_SEC;
    if (this.sync.isOn()) {
      // Arrival at the horizon lands on the grid. The default clamp keeps
      // the bolt's life in 1.57..3.14 s, at or above the 1.5 s event floor
      this.pulseRate *= this.tempoLock.retime(PULSE_SEC * 1000, this.tempoDiv.getEnum());
    }
  }

  // ---- Render ------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    final double dt = deltaMs / 1000.0;
    final double e = this.energy.getValue();

    // Poll the gate every frame so it never goes stale (see TempoLock docs);
    // no per-frame events consume it in v1, but retime() calls share the lock
    this.tempoLock.crossed(this.tempoDiv.getEnum());

    updateLut();

    // -- Envelopes
    this.boostEnv *= Math.exp(-dt / BOOST_TAU_SEC);
    if (this.pulseT < 1) {
      this.pulseT = Math.min(1, this.pulseT + this.pulseRate * dt);
    }
    if (this.morphT < 1) {
      this.morphT = Math.min(1, this.morphT + dt / REGEN_SEC);
    }

    // -- Bank: smooth-step yaw, sinusoidal roll that peaks mid-turn
    double bankYaw = 0, tiltRows = 0;
    if (this.bankT < 1) {
      this.bankT += this.bankRate * dt;
      if (this.bankT >= 1) {
        this.bankT = 1;
        this.bankBase += this.bankDir * (Math.PI / 2);
        this.bankBase %= (2 * Math.PI);
      } else {
        final double t = this.bankT;
        bankYaw = this.bankDir * (Math.PI / 2) * (t * t * (3 - 2 * t));
        tiltRows = this.bankDir * TILT_MAX_ROWS * Math.sin(Math.PI * t);
      }
    }
    final double headingRad = this.bankBase + bankYaw + 2 * Math.PI * this.heading.getValue();

    // -- Camera kinematics
    final double v = Ranges.exp(this.speed.getValue(), SPEED_MIN, SPEED_MAX)
      * Ranges.lin(e, SPEED_E_AMBIENT, SPEED_E_PEAK)
      * (1 + BOOST_GAIN * this.boostEnv);
    this.camX += v * dt * Math.sin(headingRad);
    this.camZ += v * dt * Math.cos(headingRad);

    // -- Per-frame derived values for the pixel helpers
    this.fCell = Ranges.exp(this.gridSize.getValue(), CELL_MAX, CELL_MIN);
    this.fLineW = Ranges.lin(this.glow.getValue(), LINE_W_MIN, LINE_W_MAX);
    this.fLambda = Ranges.exp(this.rough.getValue(), LAMBDA_MAX, LAMBDA_MIN);
    this.fOct2 = OCTAVE2_GAIN * this.rough.getValue();
    this.fAmp = this.relief.getValue() * RELIEF_AMP;
    this.fGain = Ranges.lin(e, 0.8, 1.2);
    this.fPulseAmp = (this.pulseT < 1) ? Ranges.lin(e, PULSE_AMBIENT, PULSE_PEAK) : 0;
    this.fPulseD = NEAR + (0.9 * FAR - NEAR) * this.pulseT;

    // Camera height: altitude above the plane, floated over the local terrain
    final double altWorld = Ranges.exp(this.altitude.getValue(), ALT_MIN, ALT_MAX);
    final boolean terrain = this.fAmp > 0.03;
    this.fCamY = terrain ?
      Math.max(altWorld, terrainHeight(this.camX, this.camZ) + CAM_CLEARANCE) :
      altWorld;

    // Vertical mapping: rows <-> elevation angle
    final double vfovRad = Math.toRadians(Ranges.lin(this.fov.getValue(), VFOV_MIN, VFOV_MAX));
    final double anglePerRow = vfovRad / ROWS;
    final double horizonRowF = ROWS * clamp(0.35 + 0.8 * this.pitch.getValue(), 0.03, 0.85);

    final double horizonAmp = Ranges.lin(e, HGLOW_AMBIENT, HGLOW_PEAK);
    final FillMode mode = this.fill.getEnum();

    // -- Draw the panorama
    this.canvas.fill(0xff000000);
    for (int x = 0; x < RING; ++x) {
      final double az = headingRad + 2 * Math.PI * x / RING;
      final double sinAz = Math.sin(az);
      final double cosAz = Math.cos(az);
      // Roll: the horizon rises on one side of the travel axis, sinks on the
      // other (offset ~ sin of the angle off the flight direction)
      final double hRow = horizonRowF + tiltRows * Math.sin(az - headingRad);

      renderSkyGlow(x, hRow, horizonAmp);
      if (terrain) {
        renderTerrainColumn(x, sinAz, cosAz, hRow, anglePerRow, mode);
      } else {
        renderFlatColumn(x, sinAz, cosAz, hRow, anglePerRow, mode);
      }
    }

    setColors(LXColor.BLACK); // cylinder intentionally dark
    this.canvas.copyTo(Apotheneum.cube.exterior, this.colors);
    copyCubeExterior();
  }

  /** Thin glow band hugging the horizon line, in the far-depth palette color */
  private void renderSkyGlow(int x, double hRow, double amp) {
    final int yLo = Math.max(0, (int) Math.ceil(hRow - 2));
    final int yHi = Math.min(ROWS - 1, (int) Math.floor(hRow));
    for (int y = yLo; y <= yHi; ++y) {
      final double b = amp * (1 - (hRow - y) / 2.2);
      if (b > 0.02) {
        this.canvas.set(x, y, dim(this.lut[LUT_SIZE - 1], b));
      }
    }
  }

  /**
   * Flat ground: closed-form reverse projection per pixel below the horizon
   * (the outrun_grid.fs technique — d = camY / tan(-elevation)).
   */
  private void renderFlatColumn(int x, double sinAz, double cosAz, double hRow,
                                double anglePerRow, FillMode mode) {
    final int yStart = Math.max(0, (int) Math.ceil(hRow + 0.001));
    for (int y = yStart; y < ROWS; ++y) {
      final double phi = (hRow - y) * anglePerRow; // negative below horizon
      if (phi >= -1e-4) {
        continue;
      }
      final double d = this.fCamY / Math.tan(-phi);
      if (d > FAR) {
        continue; // beyond the haze, still sky
      }
      final double wx = this.camX + d * sinAz;
      final double wz = this.camZ + d * cosAz;
      writeGroundPixel(x, y, wx, wz, d, mode, false);
    }
  }

  /**
   * Terrain: per-column raymarch near-to-far over the heightfield with
   * painter's occlusion; consecutive samples are joined by vertical pixel
   * runs so slopes have no holes. The top pixel of each newly exposed run is
   * the silhouette ridge line in WIRE/FILLED modes.
   */
  private void renderTerrainColumn(int x, double sinAz, double cosAz, double hRow,
                                   double anglePerRow, FillMode mode) {
    int minRow = ROWS; // lowest still-unwritten row boundary (occlusion)
    for (int i = 0; i < STEPS; ++i) {
      final double d = this.marchD[i];
      final double wx = this.camX + d * sinAz;
      final double wz = this.camZ + d * cosAz;
      final double h = terrainHeight(wx, wz);
      final double rowF = hRow - Math.atan2(h - this.fCamY, d) / anglePerRow;
      final int rowTop = Math.max(0, (int) Math.ceil(rowF));
      if (rowTop >= minRow) {
        continue; // hidden behind nearer terrain
      }
      // Newly visible vertical run [rowTop, minRow-1]
      for (int y = rowTop; y < minRow; ++y) {
        if (y < ROWS) {
          writeGroundPixel(x, y, wx, wz, d, mode, y == rowTop);
        }
      }
      minRow = rowTop;
      if (minRow == 0) {
        break; // column fully covered
      }
    }
  }

  /**
   * Shade one ground pixel from its world-space position and depth: gridline
   * glow, fill-mode ground level, pulse bolt, depth fade, palette-by-depth.
   */
  private void writeGroundPixel(int x, int y, double wx, double wz, double d,
                                FillMode mode, boolean ridge) {
    double fade = 1 - d / FAR;
    if (fade <= 0) {
      return;
    }
    fade *= fade;

    // Distance to the nearest gridline along each axis, in cell units, with
    // the line widened by depth to tame aliasing near the horizon
    double cx = wx / this.fCell;
    cx -= Math.floor(cx);
    double cz = wz / this.fCell;
    cz -= Math.floor(cz);
    final double fx = Math.min(cx, 1 - cx);
    final double fz = Math.min(cz, 1 - cz);
    final double w = this.fLineW * (1 + 1.5 * d / FAR);
    double line = 1 - Math.min(fx, fz) / w;
    line = (line > 0) ? line * line : 0;

    double intensity = line;
    switch (mode) {
      case GLOW:
        intensity = Math.max(intensity, 0.22); // continuous ground glow
        break;
      case FILLED:
        intensity = Math.max(intensity, 0.1); // dim solid ground
        break;
      case WIRE:
      default:
        break;
    }
    if (ridge && (mode != FillMode.GLOW)) {
      intensity = Math.max(intensity, RIDGE_LEVEL);
    }
    if (this.fPulseAmp > 0) {
      final double pd = (d - this.fPulseD) / (0.06 * FAR);
      intensity += this.fPulseAmp * Math.exp(-pd * pd);
    }
    intensity *= fade * this.fGain;
    if (intensity <= 0.015) {
      return;
    }
    if (intensity > 1) {
      intensity = 1;
    }
    double zn = d / FAR;
    if (zn > 0.999) {
      zn = 0.999;
    }
    this.canvas.set(x, y, dim(this.lut[(int) (zn * LUT_SIZE)], intensity));
  }

  // ---- Terrain heightfield -------------------------------------------------------

  /**
   * Terrain height above the ground plane at a world position: two-octave
   * value noise, wavelength and octave mix set by Rough, amplitude by Relief.
   * During a Regen morph the old and new noise fields are crossfaded.
   */
  private double terrainHeight(double wx, double wz) {
    double n = fbm(wx + this.ox1, wz + this.oz1);
    if (this.morphT < 1) {
      final double t = this.morphT * this.morphT * (3 - 2 * this.morphT);
      n = fbm(wx + this.ox0, wz + this.oz0) * (1 - t) + n * t;
    }
    return this.fAmp * n;
  }

  private double fbm(double wx, double wz) {
    final double inv = 1 / this.fLambda;
    double n = valueNoise(wx * inv, wz * inv);
    if (this.fOct2 > 0) {
      n = (1 - this.fOct2) * n
        + this.fOct2 * valueNoise(wx * 2.15 * inv + 37.7, wz * 2.15 * inv - 11.3);
    }
    return n;
  }

  /** Smooth 2D value noise in [0,1] on the shuffled lattice */
  private double valueNoise(double x, double y) {
    final int xi = (int) Math.floor(x);
    final int yi = (int) Math.floor(y);
    double u = x - xi;
    double v = y - yi;
    u = u * u * (3 - 2 * u);
    v = v * v * (3 - 2 * v);
    final int x0 = xi & 255, y0 = yi & 255;
    final double n00 = this.perm[this.perm[x0] + y0] / 255.0;
    final double n10 = this.perm[this.perm[x0 + 1] + y0] / 255.0;
    final double n01 = this.perm[this.perm[x0] + y0 + 1] / 255.0;
    final double n11 = this.perm[this.perm[x0 + 1] + y0 + 1] / 255.0;
    final double nx0 = n00 + (n10 - n00) * u;
    final double nx1 = n01 + (n11 - n01) * u;
    return nx0 + (nx1 - nx0) * v;
  }

  // ---- Color LUT -------------------------------------------------------------------

  /**
   * Depth gradient LUT: index 0 = nearest ground. Built from the project
   * palette swatch (first color near, last far) when it has >= 2 colors,
   * otherwise a phosphor green-to-cyan fallback. Rebuilt only when the
   * swatch or the Hue offset actually changes.
   */
  private void updateLut() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = Math.min(swatch.size(), MAX_SWATCH);
    final double hueOff = this.hue.getValue();
    boolean changed = (n != this.cachedSwatchCount) || (hueOff != this.cachedHue);
    for (int i = 0; i < n; ++i) {
      final int c = swatch.get(i).getColor();
      if (c != this.cachedSwatch[i]) {
        changed = true;
        this.cachedSwatch[i] = c;
      }
    }
    if (!changed) {
      return;
    }
    this.cachedSwatchCount = n;
    this.cachedHue = hueOff;
    if (n >= 2) {
      // Non-wrapping gradient: swatch colors spaced evenly near -> far
      for (int j = 0; j < LUT_SIZE; ++j) {
        final float pos = j * (n - 1) / (float) LUT_SIZE;
        final int i0 = (int) pos;
        final int c = LXColor.lerp(this.cachedSwatch[i0], this.cachedSwatch[i0 + 1], pos - i0);
        this.lut[j] = (hueOff == 0) ? c :
          LXColor.hsb((LXColor.h(c) + hueOff) % 360, LXColor.s(c), LXColor.b(c));
      }
    } else {
      // Sparse swatch: phosphor green fading to dim cyan at the horizon
      for (int j = 0; j < LUT_SIZE; ++j) {
        final double t = j / (double) (LUT_SIZE - 1);
        this.lut[j] = LXColor.hsb(
          (125 + 60 * t + hueOff) % 360,
          95 - 15 * t,
          100 - 45 * t);
      }
    }
  }

  // ---- Small helpers -----------------------------------------------------------------

  private static double clamp(double v, double lo, double hi) {
    return (v < lo) ? lo : (v > hi) ? hi : v;
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
