package apotheneum.jvyduna.patterns.bliss;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import apotheneum.jvyduna.util.TempoLock;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
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
 * Colors come from the full active palette swatch as a height+depth gradient
 * (slot 1 = peaks / nearest flat ground, last slot = valleys / far haze),
 * falling back to phosphor green-to-cyan.
 *
 * See Outrun.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Outrun")
@LXComponent.Description("Retro 80s fly-over: neon perspective grid and wireframe topography wrapped 360 degrees around the cube; Pulse bolts race across the center cylinder")
public class Outrun extends ApotheneumPattern {

  // ---- Geometry / renderer constants -----------------------------------------

  private static final int RING = Apotheneum.Cube.Ring.LENGTH;   // 200 columns
  private static final int ROWS = Apotheneum.GRID_HEIGHT;        // 45 rows

  /** Cylinder surface (Pulse bolts render here, and only here) */
  private static final int CYL_RING = Apotheneum.Cylinder.Ring.LENGTH; // 120 columns
  private static final int CYL_ROWS = Apotheneum.CYLINDER_HEIGHT;      // 43 rows

  /** Max simultaneous Pulse bolts in flight */
  private static final int MAX_PULSES = 8;

  /** Near / far clip of the ground rendering, in world units (~grid cells) */
  private static final double NEAR = 0.45, FAR = 28.0;

  /** Raymarch samples per column (exponentially spaced dStart..FAR); raised
   * 64 -> 96 with the per-frame march start, whose low-camera dStart widens
   * the exponential range (down to MARCH_NEAR_MIN vs the old fixed 0.45) */
  private static final int STEPS = 96;

  /** Color LUT resolution (indexed by normalized depth) */
  private static final int LUT_SIZE = 64;

  /**
   * Outrun uses ALL colors of the active swatch for its gradient, up to this
   * cap (2026-07-11; the earlier 3-slot reservation for other composition
   * elements is retired for fine-grained palette control).
   */
  private static final int MAX_SWATCH = 16;

  // ---- Timing constants (physical intent) -------------------------------------

  /**
   * Every emphasis event (Boost, Bank, Pulse, Regen) plays out over exactly one
   * TempoDiv period; this floors that period so a pathological tempo can never
   * divide-by-zero or strobe. CURATE: a 90-degree Bank over one QUARTER (~0.5 s
   * @120 BPM) is a fast snap, not a sustained pan — raise TempoDiv for slow pans.
   */
  private static final double MIN_EVENT_SEC = 0.15;

  // ---- Tuning constants --------------------------------------------------------

  /**
   * Flight speed at Speed knob 0 / 1, in grid CELLS per second (exp-mapped);
   * world speed = this x cell size, so the Grid knob changes density without
   * changing the perceived line-crossing rate. The range absorbs the retired
   * Energy multiplier (old 0.15..2.5 world/s x 0.7..1.3, / the 1.04 default
   * cell); default knob 0.4 lands at 0.40 cells/s, matching the old feel.
   */
  private static final double SPEED_MIN = 0.1, SPEED_MAX = 3.25;

  /** Boost trigger peak speed multiplier is 1 + this */
  private static final double BOOST_GAIN = 1.6;

  /** Camera height above the LOCAL terrain at Altitude knob 0 / 1, world
   * units (exp-mapped). Measured above the surface, not the plane, so the
   * knob acts across its whole range at any Relief (the old max(alt,
   * terrain + clearance) floor made Altitude < ~0.4 a no-op at high Relief);
   * ALT_MIN absorbs the retired CAM_CLEARANCE deck-skim margin.
   * CURATE: at Alt 1 the nearest ground is d~8+ against the fixed FAR=28
   * fade — consider scaling FAR with camY if the map view reads too dim. */
  private static final double ALT_MIN = 0.45, ALT_MAX = 9.0;

  /** camY terrain-follow low-pass time constant, seconds (soaks up noise
   * bumps at speed so the whole panorama doesn't judder) */
  private static final double CAM_SMOOTH_SEC = 0.4;

  /** Hard floor of the smoothed camY above the surface underfoot, world units */
  private static final double CAM_FLOOR = 0.3;

  /**
   * Soft-knee row->elevation compression (see rowToPhiDown): linear below
   * PHI_KNEE, tanh-asymptotic to PHI_CAP < 90 degrees. Guarantees every
   * screen row maps to a valid ground ray, so the old nadir clip (hard black
   * below hRow + 90deg/anglePerRow) cannot appear at any FOV/TopY combo.
   */
  private static final double PHI_KNEE = Math.toRadians(60);
  private static final double PHI_CAP = Math.toRadians(88);
  private static final double PHI_SPAN = PHI_CAP - PHI_KNEE;

  /** Floor of the per-frame march start distance, world units */
  private static final double MARCH_NEAR_MIN = 0.008;

  /** March-start guards: dStart = MARGIN * clearance / (tan(phiBottom) +
   * SLOPE_PER_AMP * amp). The slope term bounds the steepest possible
   * terrain (two-octave smoothstep value noise: ~0.7 * amp per world unit at
   * lambda 4.5, diagonal worst case), so the nearest sample's SURFACE — not
   * just the flat ground — projects at/below the bottom row even on a
   * max-slope hillside filling the view. */
  private static final double MARCH_START_MARGIN = 0.9;
  private static final double MARCH_SLOPE_PER_AMP = 0.7;

  /**
   * Soft cap on how far terrain may rise ABOVE the horizon row, in screen
   * rows: linear to UP_ROWS_KNEE, tanh-compressed to UP_ROWS_MAX. Bounds the
   * whole image to a fixed row band around hRow at every FOV (uncapped, low
   * FOV lets slope-limited peaks reach ~75 rows above the horizon), which is
   * what makes TopY 0 a guaranteed full hide without pushing TOPY_ROW_MIN —
   * and the knob's dead travel — absurdly far down. Peaks needing more than
   * UP_ROWS_KNEE rows compress smoothly; at the default horizon they can
   * still overtop the panel.
   */
  private static final double UP_ROWS_KNEE = 12;
  private static final double UP_ROWS_MAX = 24;
  private static final double UP_ROWS_SPAN = UP_ROWS_MAX - UP_ROWS_KNEE;

  /** Horizon row at TopY knob 0 / 1: from far enough below the bottom row
   * that terrain (up to UP_ROWS_MAX rows above the horizon, + 4 rows of Bank
   * tilt) and the 2.2-row sky-glow band are ALL invisible (whole landscape
   * hidden — animate TopY up to raise it from below ground) to the band just
   * escaped above the top row. CURATE: verify "barely gone" at both ends. */
  private static final double TOPY_ROW_MIN = ROWS + UP_ROWS_MAX + 4 /* TILT_MAX_ROWS */ + 2.5;
  private static final double TOPY_ROW_MAX = -0.6;

  /** Vertical field of view at FOV knob 0 / 1, degrees. CURATE: at 110 the
   * bottom half looks ~70 degrees down and cells magnify hugely (sparse but
   * now-crisp lines); revisit 90 if it still reads too empty at high FOV. */
  private static final double VFOV_MIN = 38, VFOV_MAX = 110;

  /** Grid cell size at Grid knob 0 / 1, world units (knob up = denser grid) */
  private static final double CELL_MAX = 2.4, CELL_MIN = 0.45;

  /** Gridline half-width at Glow knob 0 / 0.5, in cell units. Above 0.5 the
   * width holds at max and the laser core rises instead (see CORE_*). */
  private static final double LINE_W_MIN = 0.05, LINE_W_MAX = 0.16;

  /**
   * Laser core (Glow 0.5..1): a narrow, sharper coverage test lerps the pixel
   * toward the full-saturation/full-brightness LUT hue. Core half-width as a
   * fraction of the line width at Core knob 0 / 1, and the coverage falloff
   * exponent at 0 / 1 — higher Core = thinner and sharper.
   * CURATE: blind picks, tune on the sim / hardware.
   */
  private static final double CORE_W_FRAC_MAX = 0.5, CORE_W_FRAC_MIN = 0.15;
  private static final double CORE_POW_MIN = 1.5, CORE_POW_MAX = 4.0;

  /** Panorama azimuth step: radians of heading per ring column */
  private static final double AZ_STEP = 2 * Math.PI / RING;

  /** Peak-to-peak terrain amplitude at Relief 1, world units. The noise is
   * CENTERED (±half of this): Relief scales peak-to-valley without raising
   * the mean ground, so the land no longer appears to rise up the walls. */
  private static final double RELIEF_AMP = 3.0;

  /** Terrain noise base wavelength at Rough knob 0 / 1, world units */
  private static final double LAMBDA_MAX = 14, LAMBDA_MIN = 4.5;

  /** Second-octave weight scale (times Rough) */
  private static final double OCTAVE2_GAIN = 0.35;

  /** Horizon-glow band brightness. CURATE: fixed between the retired Energy
   * mapping's ambient 0.18 and peak 0.5 — retune on hardware. */
  private static final double HORIZON_GLOW = 0.35;

  /** Silhouette ridge-line brightness (times depth fade), held across Fill */
  private static final double RIDGE_LEVEL = 0.55;

  /**
   * A march-run top is lit as silhouette ridge only when the terrain behind
   * it drops at least this many screen rows below it (or it borders the sky).
   * CURATE: 1 = more ridge lines on gentle terrain, 3+ = only major crests.
   */
  private static final double RIDGE_DIP_ROWS = 2.0;

  /**
   * Ground fill floor at Fill 1 (glow); ramps from 0 at Fill 0 (wireframe).
   * Set to RIDGE_LEVEL so at Fill 1 the continuous ground catches up to the
   * silhouette: the ridge dissolves into an even glow by the floor *brightening*
   * to meet it, not by the ridge being deleted (deleting it made raising Fill
   * read as *less* fill, since the bright silhouette dominates the terrain).
   * CURATE: 0.55 = solid synthwave glow; trim toward ~0.4 if it washes out.
   */
  private static final double FILL_FLOOR_MAX = 0.55;

  /**
   * Fraction of FAR the LUT's DEPTH TERM saturates at (the depth position is
   * clamp(d / (FAR * this), 0, 1)). Compresses depth response into the
   * perceptible near/mid band, since the quadratic depth fade + horizon
   * row-compression crush the far half to black.
   * CURATE: 0.6 chosen from the default-column depth simulation; retune on hw.
   */
  private static final double GRADIENT_DEPTH = 0.6;

  /**
   * Terrain LUT mix: palette position = hw*heightPos + (1-hw)*depthPos.
   * heightPos = 0.5 - h/RELIEF_AMP is ABSOLUTE world height (peaks = first
   * color, valleys = last, full palette span only at Relief 1) and
   * camera-invariant, so a given topographical height keeps a consistent
   * color as the camera climbs (pure depth indexing made the palette slide
   * out from under the terrain with Altitude). The height weight hw =
   * (1 - LUT_DEPTH_MIX) * min(1, amp / LUT_HEIGHT_RAMP_AMP) RAMPS IN with
   * amplitude, so Relief 0 -> 0.01 is a smooth transition from the flat
   * pure-depth mapping instead of a hard recolor (relative h/amp
   * normalization made microscopic bumps span the whole palette). The
   * residual depth share keeps distant terrain a bit deeper into the LUT
   * for the dimmer horizon.
   * CURATE: both blind picks; RAMP_AMP=1.0 means full height weight from
   * Relief ~0.33 up.
   */
  private static final double LUT_DEPTH_MIX = 0.3;
  private static final double LUT_HEIGHT_RAMP_AMP = 1.0;

  /** Roll tilt of the horizon at the peak of a Bank, in rows */
  private static final double TILT_MAX_ROWS = 4.0;

  // ---- Parameters -------------------------------------------------------------

  public final TriggerParameter boost = new TriggerParameter("Boost", this::boost)
    .setDescription("Afterburner: flight speed surges then eases back over one TempoDiv period");

  public final TriggerParameter bank = new TriggerParameter("Bank", this::bank)
    .setDescription("Banked 90-degree turn over one TempoDiv period: heading swings and the horizon tilts, then settles");

  public final TriggerParameter regen = new TriggerParameter("Regen", this::regen)
    .setDescription("Reseed the terrain: the landscape morphs to fresh topography over one TempoDiv period");

  public final TriggerParameter pulse = new TriggerParameter("Pulse", this::pulse)
    .setDescription("A bright bolt races across the center cylinder from underfoot to the horizon in one TempoDiv period; up to 8 in flight, speeds baked at launch, overlaps add");

  public final CompoundParameter speed = new CompoundParameter("Speed", 0.4)
    .setDescription("Flight speed over the ground, in grid cells per second (Grid density does not change the perceived rate)");

  public final CompoundParameter altitude = new CompoundParameter("Altitude", 0.5)
    .setDescription("Camera height above the grid plane: low skims the deck, high is a map view");

  public final CompoundParameter topY = new CompoundParameter("TopY", 0.785)
    .setDescription("Horizon height: 0 hides the whole landscape below the bottom row, 1 pushes the horizon band off the top");

  public final CompoundParameter fov = new CompoundParameter("FOV", 0.5)
    .setDescription("Vertical field of view: perspective strength");

  public final CompoundParameter heading = new CompoundParameter("Heading", 0)
    .setDescription("Yaw offset: rotates the whole panorama around the ring (wraps)");

  public final CompoundParameter gridSize = new CompoundParameter("Grid", 0.5)
    .setDescription("Grid density: cell size shrinks as the knob rises");

  public final CompoundParameter glow = new CompoundParameter("Glow", 0.2)
    .setDescription("Gridline width and glow up to 0.5 (the former max); above it a laser-bright core rises inside each line");

  public final CompoundParameter core = new CompoundParameter("Core", 0.5)
    .setDescription("Laser core shape when Glow exceeds 0.5: thinner and sharper as the knob rises");

  public final CompoundParameter relief = new CompoundParameter("Relief", 0.35)
    .setDescription("Terrain amplitude: 0 is a perfectly flat laser grid");

  public final CompoundParameter rough = new CompoundParameter("Rough", 0.4)
    .setDescription("Terrain character: rolling dunes to jagged ridges (wavelength and octave mix)");

  public final CompoundParameter fill = new CompoundParameter("Fill", 0)
    .setDescription("Topography fill: blends wireframe (0) to dim-filled ground (0.5) to continuous synthwave glow (1)");

  public final CompoundParameter hue = new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Hue offset in degrees applied to the palette-derived depth gradient");

  public final EnumParameter<Tempo.Division> tempoDiv = new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division each emphasis event (Boost, Bank, Pulse, Regen) plays out over");

  // ---- State (all preallocated; zero allocation in the render path) -----------

  private final TempoLock tempoLock;
  private final Random random = new Random();

  private final SurfaceCanvas canvas = new SurfaceCanvas(RING, ROWS);

  /** Value-noise lattice permutation, doubled to avoid a second mask */
  private final int[] perm = new int[512];

  /** Exponentially spaced raymarch distances dStart..FAR, shared by all
   * columns; rebuilt each terrain frame so the nearest sample always projects
   * at/below the bottom row (see the march-start block in render) */
  private final double[] marchD = new double[STEPS];

  // Depth-gradient color LUT, rebuilt only when the swatch or Hue changes;
  // lutCore holds each entry's hue at full saturation/brightness (laser core)
  private final int[] lut = new int[LUT_SIZE];
  private final int[] lutCore = new int[LUT_SIZE];
  private final int[] cachedSwatch = new int[MAX_SWATCH];
  private int cachedSwatchCount = -1; // forces the initial build
  private double cachedHue = Double.NaN;

  // Camera state
  private double camX = 0, camZ = 0;

  /** Grid phase in cell units, INTEGRATED from camera motion (not derived
   * from absolute position), so changing the Grid knob rescales the lattice
   * about the camera's ground point: the view center holds and lines
   * enter/leave at the periphery instead of every line sweeping past.
   * Wrapped to [0,1) each frame to hold precision on long runs. */
  private double phaseX = 0, phaseZ = 0;

  /** Low-passed camera height (NaN = seed from the first frame's target) */
  private double camYSmooth = Double.NaN;

  /** Accumulated heading from completed Banks, radians (Heading knob adds on top) */
  private double bankBase = 0;

  // Bank envelope: progress in [0,1], >= 1 when idle; rate set at trigger
  private double bankT = 1;
  private double bankRate = 1;
  private int bankDir = 1;

  // Boost envelope: progress in [0,1], >= 1 when idle; boostEnv = (1-t)^2
  // (instant surge, quadratic decay) is derived from boostT each frame
  private double boostT = 1;
  private double boostRate = 1;
  private double boostEnv = 0;

  // Pulse bolts (cylinder-only): up to MAX_PULSES in flight, each with
  // progress in [0,1] (>= 1 when idle) and a rate BAKED at its own trigger
  // from the TempoDiv period then; pulseDepth[0..pulseCount) is the
  // per-frame compacted list of active bolt depths
  private final double[] pulseT = new double[MAX_PULSES];
  private final double[] pulseRate = new double[MAX_PULSES];
  private final double[] pulseDepth = new double[MAX_PULSES];
  private int pulseCount = 0;

  /** Cylinder canvas: black except the Pulse bolts */
  private final SurfaceCanvas cylCanvas = new SurfaceCanvas(CYL_RING, CYL_ROWS);

  // Terrain seeds: world-space noise offsets, crossfaded old -> new on Regen
  private double ox0, oz0, ox1, oz1;
  private double morphT = 1; // >= 1 when settled on (ox1, oz1)
  private double morphRate = 1; // set at Regen from the TempoDiv period

  // Per-frame derived values, stored as fields so per-pixel helpers stay
  // argument-light and allocation-free
  private double fCell, fLineW, fLambda, fOct2, fAmp;
  private double fCamY;
  private double fFloor, fRidge;
  private double fCoreAmt, fCoreW, fCorePow;

  // LUT height weight (see LUT_DEPTH_MIX / LUT_HEIGHT_RAMP_AMP): position =
  // hw*(0.5 - h/RELIEF_AMP) + (1-hw)*depth. Ramps in with amplitude and is 0
  // on flat frames, so the flat<->terrain switch never recolors
  private double fLutHeightW;

  // Gridline analytic-AA terms. Per frame: 1/cell, camY^2, and
  // anglePerRow/camY (so ddrow = (d^2 + camY^2) * fDdrowK is world units the
  // ground point moves per screen row). Per column: fwidth-style weights that
  // split a pixel's world footprint between the azimuth step and the row step
  // for each world axis. Column fields are safe for the terrain path's
  // deferred crest writes — those happen within the same column's call.
  private double fInvCell, fCamY2, fDdrowK;
  private double fFpxD, fFpxRow, fFpzD, fFpzRow;

  public Outrun(LX lx) {
    super(lx);
    this.tempoLock = new TempoLock(lx);

    addParameter("boost", this.boost);
    addParameter("bank", this.bank);
    addParameter("regen", this.regen);
    addParameter("pulse", this.pulse);
    addParameter("speed", this.speed);
    addParameter("altitude", this.altitude);
    addParameter("topY", this.topY);
    addParameter("fov", this.fov);
    addParameter("heading", this.heading);
    addParameter("gridSize", this.gridSize);
    addParameter("glow", this.glow);
    addParameter("core", this.core);
    addParameter("relief", this.relief);
    addParameter("rough", this.rough);
    addParameter("fill", this.fill);
    addParameter("hue", this.hue);
    addParameter("tempoDiv", this.tempoDiv);

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

    // All pulse slots idle (arrays default to 0 = "active at launch")
    java.util.Arrays.fill(this.pulseT, 1);
    java.util.Arrays.fill(this.pulseRate, 1);
  }

  private double randomOffset() {
    return (this.random.nextDouble() - 0.5) * 16384;
  }

  // ---- Triggers ----------------------------------------------------------------

  /** Progress rate (1/sec) for an event that plays out over one TempoDiv period */
  private double eventRate() {
    final double durSec = Math.max(MIN_EVENT_SEC,
      this.tempoLock.divisionMs(this.tempoDiv.getEnum()) / 1000.0);
    return 1 / durSec;
  }

  private void boost() {
    LX.log("Outrun: boost");
    this.boostT = 0;
    this.boostRate = eventRate();
  }

  private void bank() {
    if (this.bankT < 1) {
      return; // one turn at a time; retrigger after it settles
    }
    this.bankDir = this.random.nextBoolean() ? 1 : -1;
    LX.log("Outrun: bank " + ((this.bankDir > 0) ? "right" : "left"));
    this.bankT = 0;
    this.bankRate = eventRate();
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
    this.morphRate = eventRate();
  }

  private void pulse() {
    // Take a free slot; with all MAX_PULSES in flight, steal the bolt
    // nearest the horizon (most progressed — it had the least life left)
    int slot = -1;
    double maxT = -1;
    for (int i = 0; i < MAX_PULSES; ++i) {
      final double t = this.pulseT[i];
      if (t >= 1) {
        slot = i;
        break;
      }
      if (t > maxT) {
        maxT = t;
        slot = i;
      }
    }
    LX.log("Outrun: pulse");
    this.pulseT[slot] = 0;
    // Speed baked at launch from the TempoDiv period NOW; later TempoDiv or
    // tempo changes do not affect bolts already in flight
    this.pulseRate[slot] = eventRate();
  }

  // ---- Render ------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    final double dt = deltaMs / 1000.0;

    updateLut();

    // -- Envelopes: each advances over one TempoDiv period (rate set at trigger)
    if (this.boostT < 1) {
      this.boostT = Math.min(1, this.boostT + this.boostRate * dt);
      final double decay = 1 - this.boostT;
      this.boostEnv = decay * decay; // instant surge, quadratic decay
    } else {
      this.boostEnv = 0;
    }
    // Pulse bolts: advance each in-flight bolt at its own baked rate and
    // compact the live depths for the cylinder pass
    this.pulseCount = 0;
    for (int i = 0; i < MAX_PULSES; ++i) {
      if (this.pulseT[i] < 1) {
        this.pulseT[i] = Math.min(1, this.pulseT[i] + this.pulseRate[i] * dt);
        if (this.pulseT[i] < 1) {
          this.pulseDepth[this.pulseCount++] = NEAR + (0.9 * FAR - NEAR) * this.pulseT[i];
        }
      }
    }
    if (this.morphT < 1) {
      this.morphT = Math.min(1, this.morphT + this.morphRate * dt);
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

    // -- Per-frame derived values for the pixel helpers (fCell feeds kinematics)
    this.fCell = Ranges.exp(this.gridSize.getValue(), CELL_MAX, CELL_MIN);
    this.fInvCell = 1 / this.fCell;
    // Glow: 0..0.5 sweeps the full former width range (old max now at 0.5);
    // 0.5..1 holds max width and raises the laser core instead
    final double glowN = this.glow.getValue();
    this.fLineW = Ranges.lin(Math.min(1, glowN * 2), LINE_W_MIN, LINE_W_MAX);
    this.fCoreAmt = Math.max(0, glowN * 2 - 1);
    final double coreN = this.core.getValue();
    this.fCoreW = Ranges.lin(coreN, CORE_W_FRAC_MAX, CORE_W_FRAC_MIN) * this.fLineW;
    this.fCorePow = Ranges.lin(coreN, CORE_POW_MIN, CORE_POW_MAX);
    this.fLambda = Ranges.exp(this.rough.getValue(), LAMBDA_MAX, LAMBDA_MIN);
    this.fOct2 = OCTAVE2_GAIN * this.rough.getValue();
    this.fAmp = this.relief.getValue() * RELIEF_AMP;

    // -- Camera kinematics: Speed is in grid cells per second, so the Grid
    // knob changes density without changing the perceived line-crossing rate
    final double v = Ranges.exp(this.speed.getValue(), SPEED_MIN, SPEED_MAX)
      * this.fCell
      * (1 + BOOST_GAIN * this.boostEnv);
    final double dCamX = v * dt * Math.sin(headingRad);
    final double dCamZ = v * dt * Math.cos(headingRad);
    this.camX += dCamX;
    this.camZ += dCamZ;
    // Camera-anchored grid phase (see the phaseX/phaseZ field note)
    this.phaseX += dCamX * this.fInvCell;
    this.phaseX -= Math.floor(this.phaseX);
    this.phaseZ += dCamZ * this.fInvCell;
    this.phaseZ -= Math.floor(this.phaseZ);

    // Fill blend: ground floor ramps 0->FILL_FLOOR_MAX (wire->glow) while the
    // silhouette ridge stays crisp. At Fill 1 the floor reaches RIDGE_LEVEL, so
    // the ground reads as continuous glow with the silhouette dissolved into it
    // (brightened up to meet the ridge, not deleted). Keeping the ridge fixed
    // also decouples "fill" from terrain roughness: Fill, not Rough, drives it.
    final double fillN = this.fill.getValue();
    this.fFloor = FILL_FLOOR_MAX * fillN;
    this.fRidge = RIDGE_LEVEL;

    // Camera height: Altitude above the LOCAL terrain, low-passed so rough
    // ground at speed doesn't judder the panorama, with a hard floor just
    // above the surface underfoot. Terrain-relative (not max-ed against a
    // plane altitude) so the knob acts across its whole range at any Relief.
    final double altWorld = Ranges.exp(this.altitude.getValue(), ALT_MIN, ALT_MAX);
    final boolean terrain = this.fAmp > 0.03;
    final double hCam = terrain ? terrainHeight(this.camX, this.camZ) : 0;
    final double camYTarget = hCam + altWorld;
    if (Double.isNaN(this.camYSmooth)) {
      this.camYSmooth = camYTarget;
    } else {
      this.camYSmooth += (camYTarget - this.camYSmooth) * (1 - Math.exp(-dt / CAM_SMOOTH_SEC));
    }
    this.fCamY = Math.max(this.camYSmooth, hCam + CAM_FLOOR);

    // LUT height weight: ramps in with terrain amplitude, 0 when flat
    this.fLutHeightW = terrain
      ? (1 - LUT_DEPTH_MIX) * Math.min(1, this.fAmp / LUT_HEIGHT_RAMP_AMP)
      : 0;

    // Vertical mapping: rows <-> elevation angle, soft-knee compressed (see
    // rowToPhiDown) so no row ever looks past straight down. TopY sweeps the
    // horizon from below the bottom row (0: hidden) to off the top (1).
    final double vfovRad = Math.toRadians(Ranges.lin(this.fov.getValue(), VFOV_MIN, VFOV_MAX));
    final double anglePerRow = vfovRad / ROWS;
    final double horizonRowF = Ranges.lin(this.topY.getValue(), TOPY_ROW_MIN, TOPY_ROW_MAX);

    // March start distance, per frame: the nearest sample's SURFACE must
    // project at or below the bottom row, or every row below it is painted
    // from that single sample — the old frozen-stripe smear band. Scaled
    // from the camera's LOCAL clearance; the slope term bounds how much the
    // terrain can rise within dStart (see MARCH_* constants). CURATE: if the
    // wider exponential range shows mid-field depth banding at low camY,
    // bump STEPS again.
    if (terrain) {
      final double phiBottom = rowToPhiDown(ROWS - horizonRowF, anglePerRow);
      double dStart = NEAR;
      if (phiBottom > 0.02) {
        dStart = clamp(
          MARCH_START_MARGIN * (this.fCamY - hCam)
            / (Math.tan(phiBottom) + MARCH_SLOPE_PER_AMP * this.fAmp),
          MARCH_NEAR_MIN, NEAR);
      }
      for (int i = 0; i < STEPS; ++i) {
        this.marchD[i] = dStart * Math.pow(FAR / dStart, i / (double) (STEPS - 1));
      }
    }

    // Gridline-AA per-frame terms (need fCamY and anglePerRow, so set here);
    // the row-step estimate keys off |camY| with a floor, since the terrain-
    // relative camera can sit below the plane in a deep valley
    this.fCamY2 = this.fCamY * this.fCamY;
    this.fDdrowK = anglePerRow / Math.max(0.25, Math.abs(this.fCamY));

    // -- Draw the panorama
    this.canvas.fill(0xff000000);
    for (int x = 0; x < RING; ++x) {
      final double az = headingRad + 2 * Math.PI * x / RING;
      final double sinAz = Math.sin(az);
      final double cosAz = Math.cos(az);
      // Roll: the horizon rises on one side of the travel axis, sinks on the
      // other (offset ~ sin of the angle off the flight direction)
      final double hRow = horizonRowF + tiltRows * Math.sin(az - headingRad);

      // Gridline-AA per-column footprint weights: how a pixel's world
      // footprint splits between the azimuth step and the row step, per axis
      this.fFpxD = AZ_STEP * Math.abs(cosAz);
      this.fFpxRow = Math.abs(sinAz);
      this.fFpzD = AZ_STEP * Math.abs(sinAz);
      this.fFpzRow = Math.abs(cosAz);

      renderSkyGlow(x, hRow, HORIZON_GLOW);
      if (terrain) {
        renderTerrainColumn(x, sinAz, cosAz, hRow, anglePerRow);
      } else {
        renderFlatColumn(x, sinAz, cosAz, hRow, anglePerRow);
      }
    }

    setColors(LXColor.BLACK); // cylinder dark except the Pulse bolts below
    this.canvas.copyTo(Apotheneum.cube.exterior, this.colors);
    copyCubeExterior();

    // Pulse bolts render on the center cylinder ONLY
    if (this.pulseCount > 0) {
      renderCylinderPulses(headingRad, horizonRowF, tiltRows, anglePerRow);
      this.cylCanvas.copyTo(Apotheneum.cylinder.exterior, this.colors);
      copyCylinderExterior();
    }
  }

  /**
   * Pulse bolts on the cylinder: each active bolt is a gaussian band of
   * ground at its baked depth, projected with the same camera (camY, TopY
   * horizon, FOV soft-knee mapping, Bank tilt, heading) and shaded through
   * the same depth LUT/fade as the cube — but the cylinder shows ONLY the
   * bolts, on the flat ground plane (no gridlines/fill, and no terrain
   * occlusion — a bolt is a fast transient, not topography). Overlapping
   * bolts ADD: the gaussians are summed per pixel before the clamp, which is
   * exactly per-channel additive blending since they share the pixel's LUT
   * color. Cylinder rows map 1:1 to cube rows (43 vs 45, same row 0 = top).
   */
  private void renderCylinderPulses(double headingRad, double horizonRowF,
                                    double tiltRows, double anglePerRow) {
    this.cylCanvas.fill(0xff000000);
    final double invW = 1 / (0.06 * FAR); // gaussian half-width, world units
    for (int x = 0; x < CYL_RING; ++x) {
      final double az = headingRad + 2 * Math.PI * x / CYL_RING;
      final double hRow = horizonRowF + tiltRows * Math.sin(az - headingRad);
      final int yStart = Math.max(0, (int) Math.ceil(hRow + 0.001));
      for (int y = yStart; y < CYL_ROWS; ++y) {
        final double phiDown = rowToPhiDown(y - hRow, anglePerRow);
        if (phiDown <= 1e-4) {
          continue;
        }
        final double d = this.fCamY / Math.tan(phiDown);
        if (d > FAR) {
          continue;
        }
        double fade = 1 - d / FAR;
        fade *= fade;
        double sum = 0;
        for (int i = 0; i < this.pulseCount; ++i) {
          final double pd = (d - this.pulseDepth[i]) * invW;
          sum += Math.exp(-pd * pd); // ADD on overlap
        }
        double intensity = sum * fade;
        if (intensity <= 0.015) {
          continue;
        }
        if (intensity > 1) {
          intensity = 1;
        }
        // Pure depth position (flat plane), same band compression as the cube
        double depthN = d / (FAR * GRADIENT_DEPTH);
        if (depthN > 1) {
          depthN = 1;
        }
        int zi = (int) (depthN * LUT_SIZE);
        if (zi > LUT_SIZE - 1) {
          zi = LUT_SIZE - 1;
        }
        this.cylCanvas.set(x, y, dim(this.lut[zi], intensity));
      }
    }
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
   * (the outrun_grid.fs technique — d = camY / tan(elevation-down)). The
   * soft-knee row mapping caps the angle below 90 degrees, so d is always
   * positive: the ground reaches the bottom row at every FOV/TopY combo (the
   * old past-nadir black band is unreachable).
   */
  private void renderFlatColumn(int x, double sinAz, double cosAz, double hRow,
                                double anglePerRow) {
    final int yStart = Math.max(0, (int) Math.ceil(hRow + 0.001));
    for (int y = yStart; y < ROWS; ++y) {
      final double phiDown = rowToPhiDown(y - hRow, anglePerRow);
      if (phiDown <= 1e-4) {
        continue;
      }
      final double d = this.fCamY / Math.tan(phiDown);
      if (d > FAR) {
        continue; // beyond the haze
      }
      writeGroundPixel(x, y, d * sinAz, d * cosAz, d, 0, false);
    }
  }

  /**
   * Terrain: per-column raymarch near-to-far over the heightfield with
   * painter's occlusion; consecutive samples are joined by vertical pixel
   * runs so slopes have no holes. A run top is lit as silhouette ridge ONLY
   * when it is a true crest — the surface behind it drops at least
   * RIDGE_DIP_ROWS below it, or it borders the sky at the far end of the
   * march. Marking every run top (the old behavior) filled the whole terrain
   * at RIDGE_LEVEL, since mid/far runs are ~1 pixel tall.
   */
  private void renderTerrainColumn(int x, double sinAz, double cosAz, double hRow,
                                   double anglePerRow) {
    // Lowest still-unwritten row boundary (occlusion). No nadir clip needed:
    // the soft-knee mapping keeps every row a valid ray, and the per-frame
    // march start puts the nearest sample at/below the bottom row.
    int minRow = ROWS;
    // Silhouette candidate: top of the most recent visible run
    boolean crestPending = false;
    double crestRowF = 0, crestRelX = 0, crestRelZ = 0, crestD = 0, crestH = 0;
    int crestY = 0;
    for (int i = 0; i < STEPS; ++i) {
      final double d = this.marchD[i];
      final double relX = d * sinAz;
      final double relZ = d * cosAz;
      final double h = terrainHeight(this.camX + relX, this.camZ + relZ);
      final double rowF = hRow
        + softCapUp(phiDownToRowOff(Math.atan2(this.fCamY - h, d), anglePerRow));
      final int rowTop = Math.max(0, (int) Math.ceil(rowF));
      if (rowTop >= minRow) {
        // Hidden behind nearer terrain. Once the surface dips well below the
        // standing candidate, that candidate is a genuine skyline — light it.
        if (crestPending && (rowF >= crestRowF + RIDGE_DIP_ROWS)) {
          writeGroundPixel(x, crestY, crestRelX, crestRelZ, crestD, crestH, true);
          crestPending = false;
        }
        continue;
      }
      // Newly visible vertical run [rowTop, minRow-1]
      for (int y = rowTop; y < minRow; ++y) {
        if (y < ROWS) {
          writeGroundPixel(x, y, relX, relZ, d, h, false);
        }
      }
      // This run's top supersedes the candidate (terrain still rising)
      crestPending = true;
      crestRowF = rowF;
      crestY = rowTop;
      crestRelX = relX;
      crestRelZ = relZ;
      crestD = d;
      crestH = h;
      minRow = rowTop;
      if (minRow == 0) {
        crestPending = false; // clipped at the panel top, not a true crest
        break;
      }
    }
    if (crestPending) {
      // Farthest visible terrain: the silhouette against the sky
      writeGroundPixel(x, crestY, crestRelX, crestRelZ, crestD, crestH, true);
    }
  }

  /**
   * Shade one ground pixel from its camera-relative position, depth, and
   * terrain height: gridline glow, Fill-blend ground level, laser core,
   * depth fade, palette by height+depth (see LUT_DEPTH_MIX). Positions are
   * camera-relative (relX = wx − camX) so the gridline test runs on the
   * integrated phase — see phaseX/phaseZ. h is the terrain height at the
   * point (0 on the flat path, where the LUT terms reduce to pure depth).
   */
  private void writeGroundPixel(int x, int y, double relX, double relZ, double d,
                                double h, boolean ridge) {
    double fade = 1 - d / FAR;
    if (fade <= 0) {
      return;
    }
    fade *= fade;

    // Distance to the nearest gridline along each world axis, in cell units
    double cx = relX * this.fInvCell + this.phaseX;
    cx -= Math.floor(cx);
    double cz = relZ * this.fInvCell + this.phaseZ;
    cz -= Math.floor(cz);
    final double fx = Math.min(cx, 1 - cx);
    final double fz = Math.min(cz, 1 - cz);

    // Analytic AA (fwidth-style): the pixel's world footprint per axis is
    // |d(w-axis)/d(column)| + |d(w-axis)/d(row)| in cell units. The effective
    // line half-width never drops below half the footprint (so thin lines stay
    // CONNECTED instead of falling between samples), and a sub-pixel line's
    // peak is attenuated by sqrt(w/effW) — perceptual middle ground: full
    // energy conservation reads too dim on top of the quadratic depth fade.
    // Near field footprint < w, so this degenerates to the crisp exact test.
    // CURATE: if far lines still fade too early, floor the attenuation ~0.3.
    final double ddrow = (d * d + this.fCamY2) * this.fDdrowK; // world per row
    final double fpx = (d * this.fFpxD + ddrow * this.fFpxRow) * this.fInvCell;
    final double fpz = (d * this.fFpzD + ddrow * this.fFpzRow) * this.fInvCell;
    final double w = this.fLineW;
    final double effWx = Math.max(w, 0.5 * fpx);
    final double effWz = Math.max(w, 0.5 * fpz);
    double lineX = 1 - fx / effWx;
    lineX = (lineX > 0) ? lineX * lineX * ((effWx > w) ? Math.sqrt(w / effWx) : 1) : 0;
    double lineZ = 1 - fz / effWz;
    lineZ = (lineZ > 0) ? lineZ * lineZ * ((effWz > w) ? Math.sqrt(w / effWz) : 1) : 0;
    final double line = Math.max(lineX, lineZ);

    // Fill blend: ground floor lifts the continuous ground (wire 0 -> glow
    // FILL_FLOOR_MAX); the silhouette ridge holds crisp then dissolves at glow
    double intensity = Math.max(line, this.fFloor);
    if (ridge) {
      intensity = Math.max(intensity, this.fRidge);
    }
    intensity *= fade;
    if (intensity <= 0.015) {
      return;
    }
    if (intensity > 1) {
      intensity = 1;
    }
    // LUT position: camera-invariant ABSOLUTE-height term (weight ramps in
    // with amplitude; 0 when flat => pure depth) + depth remainder. The
    // height term is in [0,1] by construction (|h| <= RELIEF_AMP/2).
    double depthN = d / (FAR * GRADIENT_DEPTH);
    if (depthN > 1) {
      depthN = 1;
    }
    final double pos = this.fLutHeightW * (0.5 - h * (1 / RELIEF_AMP))
      + (1 - this.fLutHeightW) * depthN;
    int zi = (int) (pos * LUT_SIZE);
    if (zi > LUT_SIZE - 1) {
      zi = LUT_SIZE - 1;
    }
    int c = dim(this.lut[zi], intensity);
    // Laser core (Glow > 0.5): a narrower, sharper coverage test lerps the
    // pixel toward the full-saturation/full-brightness LUT hue — brightness
    // the dimmed LUT alone can't reach. sqrt(fade) lets the core outlive the
    // quadratic base fade into the distance. Same analytic-AA structure as
    // the main line so the core stays connected at depth.
    if (this.fCoreAmt > 0) {
      final double effCx = Math.max(this.fCoreW, 0.5 * fpx);
      final double effCz = Math.max(this.fCoreW, 0.5 * fpz);
      double coreX = 1 - fx / effCx;
      coreX = (coreX > 0)
        ? Math.pow(coreX, this.fCorePow) * ((effCx > this.fCoreW) ? Math.sqrt(this.fCoreW / effCx) : 1)
        : 0;
      double coreZ = 1 - fz / effCz;
      coreZ = (coreZ > 0)
        ? Math.pow(coreZ, this.fCorePow) * ((effCz > this.fCoreW) ? Math.sqrt(this.fCoreW / effCz) : 1)
        : 0;
      final double mix = this.fCoreAmt * Math.max(coreX, coreZ) * Math.sqrt(fade);
      if (mix > 0.01) {
        c = LXColor.lerp(c, this.lutCore[zi], mix);
      }
    }
    this.canvas.set(x, y, c);
  }

  // ---- Terrain heightfield -------------------------------------------------------

  /**
   * Terrain height relative to the ground plane at a world position:
   * two-octave value noise, wavelength and octave mix set by Rough,
   * peak-to-peak amplitude by Relief. CENTERED (noise − 0.5, so ±fAmp/2):
   * Relief scales peaks up AND valleys down without raising the mean ground.
   * During a Regen morph the old and new noise fields are crossfaded.
   */
  private double terrainHeight(double wx, double wz) {
    double n = fbm(wx + this.ox1, wz + this.oz1);
    if (this.morphT < 1) {
      final double t = this.morphT * this.morphT * (3 - 2 * this.morphT);
      n = fbm(wx + this.ox0, wz + this.oz0) * (1 - t) + n * t;
    }
    return this.fAmp * (n - 0.5);
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

  // ---- Row <-> elevation mapping ---------------------------------------------------

  /**
   * Row offset below the horizon -> downward elevation angle, radians.
   * Linear (rowOff * anglePerRow) below PHI_KNEE, then tanh-compressed to
   * asymptote at PHI_CAP < 90 degrees: every screen row maps to a valid
   * ground ray, so the old past-nadir black band cannot appear on the wall
   * at any FOV/TopY combination. Negative offsets (above the horizon) stay
   * linear. Exact over most of the image; only the steep near field
   * compresses.
   */
  private static double rowToPhiDown(double rowOff, double anglePerRow) {
    final double lin = rowOff * anglePerRow;
    if (lin <= PHI_KNEE) {
      return lin;
    }
    return PHI_KNEE + PHI_SPAN * Math.tanh((lin - PHI_KNEE) / PHI_SPAN);
  }

  /**
   * Inverse of rowToPhiDown: downward elevation angle -> row offset below
   * the horizon. Angles at/past PHI_CAP resolve far below the screen
   * (correctly treated as occluded/off-panel by the terrain march).
   */
  private static double phiDownToRowOff(double phiDown, double anglePerRow) {
    if (phiDown <= PHI_KNEE) {
      return phiDown / anglePerRow;
    }
    double t = (phiDown - PHI_KNEE) / PHI_SPAN;
    if (t > 0.9999) {
      t = 0.9999;
    }
    // artanh(t) = 0.5 ln((1+t)/(1-t))
    return (PHI_KNEE + PHI_SPAN * 0.5 * Math.log((1 + t) / (1 - t))) / anglePerRow;
  }

  /**
   * Soft cap on a terrain sample's row offset ABOVE the horizon (negative
   * offsets): linear to -UP_ROWS_KNEE, tanh-compressed to -UP_ROWS_MAX. See
   * the UP_ROWS_* constants — this fixed row band is what lets TOPY_ROW_MIN
   * guarantee a full hide at TopY 0. Offsets at/below the horizon pass
   * through untouched.
   */
  private static double softCapUp(double rowOff) {
    if (rowOff >= -UP_ROWS_KNEE) {
      return rowOff;
    }
    return -UP_ROWS_KNEE - UP_ROWS_SPAN * Math.tanh((-rowOff - UP_ROWS_KNEE) / UP_ROWS_SPAN);
  }

  // ---- Color LUT -------------------------------------------------------------------

  /**
   * Gradient LUT across ALL colors of the active swatch (slot 1 = index 0 =
   * high peaks / nearest flat ground, last slot = valleys / far haze /
   * horizon glow) when it has >= 2 colors, otherwise a phosphor
   * green-to-cyan fallback. Indexed by the height+depth position (see
   * LUT_DEPTH_MIX); the depth-band compression (GRADIENT_DEPTH) happens in
   * the index math, so the LUT itself spans its full range. Rebuilt only
   * when the swatch or the Hue offset actually changes.
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
      // Non-wrapping gradient: swatch colors spaced evenly across the full
      // LUT (band compression happens in the index math via GRADIENT_DEPTH)
      for (int j = 0; j < LUT_SIZE; ++j) {
        final double pos = (j / (double) (LUT_SIZE - 1)) * (n - 1);
        final int i0 = Math.min((int) pos, n - 2);
        final int c = LXColor.lerp(this.cachedSwatch[i0], this.cachedSwatch[i0 + 1], pos - i0);
        this.lut[j] = (hueOff == 0) ? c :
          LXColor.hsb((LXColor.h(c) + hueOff) % 360, LXColor.s(c), LXColor.b(c));
      }
    } else {
      // Sparse swatch: phosphor green fading to dim cyan across the full LUT
      for (int j = 0; j < LUT_SIZE; ++j) {
        final double t = j / (double) (LUT_SIZE - 1);
        this.lut[j] = LXColor.hsb(
          (125 + 60 * t + hueOff) % 360,
          95 - 15 * t,
          100 - 45 * t);
      }
    }
    // Laser-core companion: each depth's hue at full saturation/brightness
    for (int j = 0; j < LUT_SIZE; ++j) {
      this.lutCore[j] = LXColor.hsb(LXColor.h(this.lut[j]), 100, 100);
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
