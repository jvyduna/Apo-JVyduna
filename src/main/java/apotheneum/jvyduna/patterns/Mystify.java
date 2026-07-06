package apotheneum.jvyduna.patterns;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
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
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * Recreation of the classic "Mystify Your Mind" screensaver family: one or two
 * closed polylines whose vertices drift independently, leaving distinct fading
 * leftover lines behind the live shape. Vertices always bounce off the top and
 * bottom; horizontally they either bounce (per-face mode) or wrap continuously
 * around the cube ring or the cylinder.
 *
 * The gapped-moire look comes from the trail architecture: the live shape is
 * drawn fresh every frame but only STAMPED into the persistent trail canvas on
 * crossings of the TimeGap tempo division, so successive leftover lines are
 * spatially separated and fade smoothly to black at the Trails decay rate.
 *
 * Bounce timing is hard-locked to the transport beat grid: every wall bounce
 * lands exactly on a multiple of BounceOnBeats quarter-note beats (grid
 * re-anchored by Scatter). Each vertex axis plans a target arrival beat and
 * derives its velocity from remaining-distance / time-to-target each frame, so
 * arrivals are exact and live BPM changes are absorbed; Speed (0-5x, 0 =
 * pause) is a target the planner quantizes to the grid, and speed changes slew
 * smoothly to their new plans.
 *
 * See Mystify.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Mystify")
@LXComponent.Description("Mystify-screensaver polylines leaving beat-spaced fading lines, bouncing on the tempo grid on the cube faces or wrapping around the cube ring or cylinder")
public class Mystify extends ApotheneumPattern {

  // ---- Timing / motion constants ---------------------------------------------

  /** Seconds for a vertex to cross the full canvas width at energy=0, Speed=1 */
  private static final double TRAVERSE_SEC_AMBIENT = 12;

  /** Seconds for a vertex to cross the full canvas width at energy=1, Speed=1.
   *  The simulation-principles >= 5 s traversal floor holds at Speed <= 1;
   *  the Speed knob deliberately extends to 5x this rate (see Mystify.md) */
  private static final double TRAVERSE_SEC_PEAK = 5;

  /** Slowest vertex velocity component, as a fraction of the fastest — keeps
   *  every vertex visibly moving and spreads natural arrival times so
   *  different vertices pick different bounce-grid beats */
  private static final double VEL_MIN = 0.45;

  /** Trail half-life range (ms), mapped exponentially from the Trails knob:
   *  50 ms reads as almost bare lines; 2500 ms leaves seconds-long comets */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 50;
  private static final double TRAIL_HALF_LIFE_MAX_MS = 2500;

  /** Treble transients shorten trails by up to ~30% (1 + 0.15 * 2) — subtle */
  private static final double TREBLE_TRAIL_SHORTEN = 0.15;

  /** Bass-hit vertex flash fades to black over this long — a visible pop.
   *  Hits gated closer together than this (the retrigger gate is ~an eighth
   *  note) simply re-arm the envelope before it empties. */
  private static final double FLASH_DECAY_MS = 500;

  /** Speed-retarget smoothstep slew window: knob moves re-plan every axis and
   *  the effective arrival times blend from old to new plan over this long */
  private static final double SLEW_MS = 250;

  /** Within this of the target beat counts as arrived (bounce fires) */
  private static final double ARRIVAL_EPS_MS = 30;

  /** A grid beat closer than this can't be targeted; plan the next one */
  private static final double MIN_HEADROOM_MS = 100;

  /** Speed below this reads as a full pause */
  private static final double PAUSE_SPEED_EPS = 1e-3;

  /** Rate change larger than this fraction of the planned rate triggers a
   *  retarget-with-slew (Speed or Energy knob moves) */
  private static final double RATE_CHANGE_FRAC = 0.02;

  /** A target this far in the past with real distance remaining is stale
   *  (transport restart / huge BPM jump / inactive gap) — replan, don't jump */
  private static final double STALE_TARGET_MS = 250;

  /** Structural maxima; all state is preallocated at these sizes */
  private static final int MAX_SHAPES = 2;
  private static final int MAX_VERTS = 5;

  // ---- Geometry modes ---------------------------------------------------------

  public enum Geometry {
    FACES("Faces"),        // one 50x45 sim mirrored to all 4 cube faces; x bounces
    CUBE_RING("CubeRing"), // one 200x45 sim around the cube exterior; x wraps
    CYLINDER("Cylinder");  // one 120x43 sim around the cylinder; x wraps

    public final String label;
    private Geometry(String label) { this.label = label; }

    @Override
    public String toString() { return this.label; }
  }

  /** Beat grid for wall bounces, in quarter-note beats */
  public enum BounceGrid {
    ONE(1),
    TWO(2),
    FOUR(4),
    EIGHT(8);

    public final int beats;
    private BounceGrid(int beats) { this.beats = beats; }

    @Override
    public String toString() { return Integer.toString(this.beats); }
  }

  // ---- Parameters ---------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Mystify");
  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  public final TriggerParameter scatter = bag.register(
    new TriggerParameter("Scatter", this::scatter)
    .setDescription("Re-randomize every vertex position and velocity and re-anchor the bounce grid"));

  public final TriggerParameter reverse = bag.register(
    new TriggerParameter("Reverse", this::reverse)
    .setDescription("Negate all vertex velocities so the shapes retrace their paths"));

  public final TriggerParameter hueJump = bag.register(
    new TriggerParameter("HueJump", this::hueJump)
    .setDescription("Rotate the palette color assignment of the polylines"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 soothing ambient, 0.6-1.0 high-energy 160 BPM regime; sets the base traversal rate and flash intensity");

  public final EnumParameter<Geometry> geometry =
    new EnumParameter<Geometry>("Geometry", Geometry.CUBE_RING)
    .setDescription("Surface topology: Faces bounces on each cube face; CubeRing/Cylinder wrap continuously around");

  public final DiscreteParameter shapes =
    new DiscreteParameter("Shapes", 2, 1, MAX_SHAPES + 1)
    .setDescription("Number of independent polylines (1 or 2)");

  public final DiscreteParameter vertices =
    new DiscreteParameter("Vertices", 4, 3, MAX_VERTS + 1)
    .setDescription("Vertices per polyline (3-5)");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1, 0, 5)
    .setExponent(2)
    .setDescription("Target speed as a multiple of the energy-set traversal rate; 0 = pause. Bounce arrivals quantize to the BounceOnBeats grid, so this is a target, not an exact velocity");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", 0.5)
    .setDescription("Decay rate of stamped leftover lines: 0 = fade almost instantly, 1 = seconds-long persistence");

  public final EnumParameter<Tempo.Division> timeGap =
    new EnumParameter<Tempo.Division>("TimeGap", Tempo.Division.QUARTER)
    .setDescription("Tempo division on whose crossings the live shape is stamped into the trail as a distinct leftover line");

  public final BooleanParameter wu =
    new BooleanParameter("Wu", false)
    .setDescription("Draw lines with Xiaolin Wu antialiasing (smooth, slightly softer) instead of Bresenham");

  public final EnumParameter<BounceGrid> bounceOnBeats =
    new EnumParameter<BounceGrid>("BounceOnBeats", BounceGrid.ONE)
    .setDescription("Beat grid (in quarter-note beats) that every wall bounce lands on, anchored by pattern load / Scatter");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire one of Mystify's triggers or jump a parameter");

  // ---- Simulation state (all preallocated; normalized [0,1] coordinates) ------

  private final double[] posX = new double[MAX_SHAPES * MAX_VERTS];
  private final double[] posY = new double[MAX_SHAPES * MAX_VERTS];
  private final double[] velX = new double[MAX_SHAPES * MAX_VERTS]; // canvas-widths/sec at cap=1
  private final double[] velY = new double[MAX_SHAPES * MAX_VERTS]; // canvas-heights/sec at cap=1

  /** Planned wall-arrival per axis as an ABSOLUTE transport beat index
   *  (see TempoLock.beatPosition). NaN = unplanned: paused, or x on wrap
   *  topologies where there is no bounce event to schedule. */
  private final double[] targetBeatX = new double[MAX_SHAPES * MAX_VERTS];
  private final double[] targetBeatY = new double[MAX_SHAPES * MAX_VERTS];

  /** Blend source during a speed-retarget slew; equal to targetBeat* when no
   *  slew is active (bounce replans reset them together). */
  private final double[] slewOldBeatX = new double[MAX_SHAPES * MAX_VERTS];
  private final double[] slewOldBeatY = new double[MAX_SHAPES * MAX_VERTS];

  /** Whole beat the bounce grid is anchored to: allowed bounce beats are
   *  anchorBeat + k * BounceOnBeats. Reset at load and by Scatter. */
  private double anchorBeat = 0;

  /** Remaining ms of the current retarget slew (0 = no slew active) */
  private double slewRemainMs = 0;

  /** rateSpansPerSec at the last (re)plan, for material-change detection */
  private double plannedRate = 0;

  private boolean needsReanchor = true;
  private boolean needsReplan = true;
  private boolean wasPaused = false;
  private BounceGrid lastBounceGrid = null;

  /** Two hues per polyline, refreshed from the palette each frame */
  private final int[] shapeColor = new int[MAX_SHAPES * 2];
  private int hueOffset = 0;

  /** Bass-hit vertex flash envelope, 0..1 (armed to audio depth on each hit) */
  private double flash = 0;

  /** This frame's rate in canvas-spans/sec at velocity magnitude 1; the basis
   *  for bounce planning as well as wrap-x integration */
  private double rateSpansPerSec = 0;

  /** Persistent trail canvases: hold only STAMPED leftover lines, decaying */
  private final SurfaceCanvas facesCanvas =
    new SurfaceCanvas(Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);          // 50x45
  private final SurfaceCanvas ringCanvas =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT);    // 200x45
  private final SurfaceCanvas cylinderCanvas =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT); // 120x43

  /** Scratch canvases: trail + live shape composited fresh every frame */
  private final SurfaceCanvas facesScratch =
    new SurfaceCanvas(Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);
  private final SurfaceCanvas ringScratch =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT);
  private final SurfaceCanvas cylinderScratch =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT);

  private Geometry lastGeometry = null;

  public Mystify(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("scatter", this.scatter);
    addParameter("reverse", this.reverse);
    addParameter("hueJump", this.hueJump);
    addParameter("energy", this.energy);
    addParameter("geometry", this.geometry);
    addParameter("shapes", this.shapes);
    addParameter("vertices", this.vertices);
    addParameter("speed", this.speed);
    addParameter("trails", this.trails);
    addParameter("timeGap", this.timeGap);
    addParameter("wu", this.wu);
    addParameter("bounceOnBeats", this.bounceOnBeats);
    addParameter("audio", this.audioDepth);
    addParameter("meta", this.meta);

    // Meta jump candidates — mirrored 1:1 in the Jump candidates table in Mystify.md
    bag.jumpable(this.trails, 0.25, 0.85);
    bag.jumpable(this.vertices);
    bag.jumpable(this.geometry);
    bag.jumpable(this.speed, 0.4, 1.5); // CURATE: upper bound; full-range jumps to 5x would jar
    bag.jumpable(this.shapes);

    scatter();
  }

  // ---- Trigger handlers ---------------------------------------------------------

  /** Re-randomize all vertex positions and velocities (also the initial state)
   *  and re-anchor the bounce grid. Fires immediately — never quantized; the
   *  replan itself lands on the next frame, where the tempo state is live. */
  private void scatter() {
    for (int i = 0; i < this.posX.length; ++i) {
      this.posX[i] = this.random.nextDouble();
      this.posY[i] = this.random.nextDouble();
      this.velX[i] = randomVelocity();
      this.velY[i] = randomVelocity();
    }
    this.needsReanchor = true;
    this.needsReplan = true;
  }

  /** Random signed velocity component in [VEL_MIN, 1] canvas-spans/sec at cap=1 */
  private double randomVelocity() {
    final double magnitude = VEL_MIN + this.random.nextDouble() * (1 - VEL_MIN);
    return this.random.nextBoolean() ? magnitude : -magnitude;
  }

  /** Directions just flipped, so every planned target is behind us — replan
   *  (grid anchor is untouched; only Scatter re-anchors) */
  private void reverse() {
    for (int i = 0; i < this.velX.length; ++i) {
      this.velX[i] = -this.velX[i];
      this.velY[i] = -this.velY[i];
    }
    this.needsReplan = true;
  }

  private void hueJump() {
    this.hueOffset = (this.hueOffset + 1) % this.shapeColor.length;
  }

  // ---- Render -------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    final Geometry geom = this.geometry.getEnum();
    boolean geomChanged = false;
    if (geom != this.lastGeometry) {
      // Fresh start on the new topology; stale trails from the old mode vanish
      this.facesCanvas.fill(LXColor.BLACK);
      this.ringCanvas.fill(LXColor.BLACK);
      this.cylinderCanvas.fill(LXColor.BLACK);
      this.lastGeometry = geom;
      geomChanged = true;
    }
    final SurfaceCanvas trail = canvasFor(geom);
    final SurfaceCanvas scratch = scratchFor(geom);
    final boolean wrapX = (geom != Geometry.FACES);

    // TimeGap stamp gate: polled unconditionally every frame so it never
    // goes stale (see TempoLock.crossed)
    final boolean stampNow = this.tempoLock.crossed(this.timeGap.getEnum());

    // Speed: energy sets the base traversal rate; Speed scales it 0-5x.
    // This is the planner's TARGET rate — actual per-axis velocities are
    // derived from grid-quantized arrival times in advance().
    final double e = this.energy.getValue();
    final double capSpansPerSec = 1 / Ranges.lin(e, TRAVERSE_SEC_AMBIENT, TRAVERSE_SEC_PEAK);
    this.rateSpansPerSec = capSpansPerSec * this.speed.getValue();
    final boolean paused = this.speed.getValue() < PAUSE_SPEED_EPS;

    // Bounce planner bookkeeping (order matters: anchor before any replan)
    final BounceGrid grid = this.bounceOnBeats.getEnum();
    if (this.needsReanchor) {
      this.anchorBeat = this.tempoLock.beatCycle();
      this.needsReanchor = false;
      this.needsReplan = true;
    }
    if (paused) {
      if (!this.wasPaused) {
        clearPlans();
        this.needsReplan = true; // resume replans from wherever we froze
      }
    } else if (this.needsReplan || (grid != this.lastBounceGrid) || geomChanged) {
      replanAll(wrapX);
      this.needsReplan = false;
    } else if (Math.abs(this.rateSpansPerSec - this.plannedRate) > RATE_CHANGE_FRAC * this.plannedRate) {
      retargetAll(wrapX); // Speed/Energy knob move: new plans, smoothstep slew
    }
    this.wasPaused = paused;
    this.lastBounceGrid = grid;

    advance(deltaMs, wrapX, paused);

    // Retarget slew clock; when it empties, plans settle to their new targets
    if (this.slewRemainMs > 0) {
      this.slewRemainMs = Math.max(0, this.slewRemainMs - deltaMs);
      if (this.slewRemainMs == 0) {
        settleSlew();
      }
    }

    // Trails: pure decay of the stamped leftover lines (never cleared);
    // treble subtly shortens them
    double halfLifeMs = Ranges.exp(this.trails.getValue(), TRAIL_HALF_LIFE_MIN_MS, TRAIL_HALF_LIFE_MAX_MS);
    halfLifeMs /= 1 + TREBLE_TRAIL_SHORTEN * LXUtils.constrain(this.audio.trebleRatio - 1, 0, 2);
    trail.decay(Math.pow(0.5, deltaMs / halfLifeMs));

    // Bass-hit vertex flash envelope, armed to the audio depth so the pop
    // scales with the knob (depth 1 = full white)
    if (this.audio.bassHit()) {
      this.flash = this.audio.depth();
    } else {
      this.flash = Math.max(0, this.flash - deltaMs / FLASH_DECAY_MS);
    }

    computeColors();

    // Stamp a leftover line into the trail on each TimeGap crossing — the
    // source of the gapped-moire look. The live shape itself never persists.
    if (stampNow) {
      drawShapeLines(trail, wrapX);
    }
    if (this.flash > 0.01) {
      stampFlash(trail, wrapX, e); // pops land in the trail so they decay out
    }

    // Composite: fading leftovers underneath, live shape fresh on top
    scratch.copyFrom(trail);
    drawShapeLines(scratch, wrapX);

    // Output: paint the active surface exterior, then mirror to its interior.
    // The other component stays dark (see Mystify.md for the rationale).
    switch (geom) {
      case FACES:
        copyToFrontFace(scratch);
        copyCubeFace(Apotheneum.cube.exterior.front); // replicates to all 8 faces
        break;
      case CUBE_RING:
        scratch.copyTo(Apotheneum.cube.exterior, this.colors);
        copyCubeExterior();
        break;
      case CYLINDER:
        scratch.copyTo(Apotheneum.cylinder.exterior, this.colors);
        copyCylinderExterior();
        break;
    }
  }

  private SurfaceCanvas canvasFor(Geometry geom) {
    switch (geom) {
      case FACES: return this.facesCanvas;
      case CUBE_RING: return this.ringCanvas;
      default: return this.cylinderCanvas;
    }
  }

  private SurfaceCanvas scratchFor(Geometry geom) {
    switch (geom) {
      case FACES: return this.facesScratch;
      case CUBE_RING: return this.ringScratch;
      default: return this.cylinderScratch;
    }
  }

  // ---- Bounce planner -------------------------------------------------------------

  /**
   * Plan one axis's wall arrival: the allowed grid beat (anchorBeat + k *
   * BounceOnBeats) nearest the NATURAL arrival time at the target rate, with
   * a minimum headroom so an imminent beat is never targeted. The returned
   * absolute beat is exact — advance() derives velocity from it each frame.
   */
  private double planAxis(double pos, double vel) {
    final double remDist = (vel > 0) ? (1 - pos) : pos;
    final double axisRate = Math.abs(vel) * this.rateSpansPerSec; // spans/sec
    final double beatMs = this.tempoLock.beatPeriodMs();
    final int n = this.bounceOnBeats.getEnum().beats;
    final double now = this.tempoLock.beatPosition();
    final double naturalBeats = ((axisRate > 0) && (beatMs > 0))
      ? (1000 * remDist / axisRate) / beatMs
      : n; // degenerate rate/tempo: one grid step out
    final double k = Math.rint((now + naturalBeats - this.anchorBeat) / n);
    double target = this.anchorBeat + k * n;
    // Headroom: never target a beat closer than MIN_HEADROOM_MS (or past).
    // Bounded loop only as paranoia against a degenerate tempo period.
    for (int guard = 0; (guard < 1024) && (this.tempoLock.msUntilBeat(target) < MIN_HEADROOM_MS); ++guard) {
      target += n;
    }
    return target;
  }

  /** Re-plan every planned axis from current positions; no slew (used at
   *  anchor/trigger/geometry/grid-change/resume events, where a step is fine) */
  private void replanAll(boolean wrapX) {
    for (int i = 0; i < this.posX.length; ++i) {
      this.targetBeatX[i] = wrapX ? Double.NaN : planAxis(this.posX[i], this.velX[i]);
      this.slewOldBeatX[i] = this.targetBeatX[i];
      this.targetBeatY[i] = planAxis(this.posY[i], this.velY[i]);
      this.slewOldBeatY[i] = this.targetBeatY[i];
    }
    this.slewRemainMs = 0;
    this.plannedRate = this.rateSpansPerSec;
  }

  /**
   * Speed/Energy moved materially: re-plan every axis toward the rate's new
   * preferred grid beats, blending the effective arrival smoothly over
   * SLEW_MS. The current BLENDED target is frozen as the new blend source so
   * chained knob moves stay continuous instead of snapping.
   */
  private void retargetAll(boolean wrapX) {
    final double t = (this.slewRemainMs > 0) ? smoothstep(1 - this.slewRemainMs / SLEW_MS) : 1;
    for (int i = 0; i < this.posX.length; ++i) {
      if (!wrapX) {
        this.slewOldBeatX[i] = LXUtils.lerp(this.slewOldBeatX[i], this.targetBeatX[i], t);
        this.targetBeatX[i] = planAxis(this.posX[i], this.velX[i]);
      }
      this.slewOldBeatY[i] = LXUtils.lerp(this.slewOldBeatY[i], this.targetBeatY[i], t);
      this.targetBeatY[i] = planAxis(this.posY[i], this.velY[i]);
    }
    this.slewRemainMs = SLEW_MS;
    this.plannedRate = this.rateSpansPerSec;
  }

  /** Pause: abandon all plans (resume replans fresh from the frozen state) */
  private void clearPlans() {
    for (int i = 0; i < this.posX.length; ++i) {
      this.targetBeatX[i] = Double.NaN;
      this.targetBeatY[i] = Double.NaN;
      this.slewOldBeatX[i] = Double.NaN;
      this.slewOldBeatY[i] = Double.NaN;
    }
    this.slewRemainMs = 0;
  }

  /** Slew finished: collapse blend sources onto the settled targets so the
   *  per-frame fast path (no lerp) applies until the next retarget */
  private void settleSlew() {
    for (int i = 0; i < this.posX.length; ++i) {
      this.slewOldBeatX[i] = this.targetBeatX[i];
      this.slewOldBeatY[i] = this.targetBeatY[i];
    }
  }

  // ---- Simulation ----------------------------------------------------------------

  /**
   * Integrate vertex motion. Planned axes (y always; x in FACES mode) derive
   * velocity from remaining-distance / time-to-target-beat each frame, so
   * every bounce lands exactly on the BounceOnBeats grid and BPM changes are
   * absorbed continuously. Wrap-x free-runs at the target rate — a wrap has
   * no visible event to align. X and Y are advanced independently, so a
   * same-frame corner double-bounce just works: each axis clamps, flips, and
   * replans itself.
   */
  private void advance(double deltaMs, boolean wrapX, boolean paused) {
    for (int i = 0; i < this.posX.length; ++i) {
      if (wrapX) {
        this.posX[i] += this.velX[i] * this.rateSpansPerSec * deltaMs * 0.001;
        this.posX[i] -= Math.floor(this.posX[i]);
      } else if (!paused && !Double.isNaN(this.targetBeatX[i])) {
        advancePlanned(this.posX, this.velX, this.targetBeatX, this.slewOldBeatX, i, deltaMs);
      }
      if (!paused && !Double.isNaN(this.targetBeatY[i])) {
        advancePlanned(this.posY, this.velY, this.targetBeatY, this.slewOldBeatY, i, deltaMs);
      }
    }
  }

  /** One planned axis, one frame: blend the effective arrival time during a
   *  slew, bounce exactly on arrival, else move at remaining/remaining rate */
  private void advancePlanned(double[] pos, double[] vel, double[] target, double[] slewOld, int i, double deltaMs) {
    final double dir = (vel[i] > 0) ? 1 : -1;
    final double wall = (dir > 0) ? 1 : 0;
    final double remDist = Math.abs(wall - pos[i]);

    double msEff = this.tempoLock.msUntilBeat(target[i]);
    if (this.slewRemainMs > 0) {
      final double t = smoothstep(1 - this.slewRemainMs / SLEW_MS);
      msEff = LXUtils.lerp(this.tempoLock.msUntilBeat(slewOld[i]), msEff, t);
    }

    // Stale plan (transport restart, huge BPM jump, inactive gap): the target
    // slid well into the past while real distance remains — replan, no jump
    if ((msEff < -STALE_TARGET_MS) && (remDist > 0.02)) {
      target[i] = planAxis(pos[i], vel[i]);
      slewOld[i] = target[i];
      return;
    }

    // Arrival: clamp to the wall ON the grid beat, flip, replan immediately
    if ((msEff <= ARRIVAL_EPS_MS) || (remDist <= 0)) {
      pos[i] = wall;
      vel[i] = -vel[i];
      target[i] = planAxis(pos[i], vel[i]);
      slewOld[i] = target[i];
      return;
    }

    // Self-correcting velocity: exact arrival, absorbs BPM drift for free
    final double v = dir * remDist / (msEff * 0.001); // spans/sec
    pos[i] = LXUtils.constrain(pos[i] + v * deltaMs * 0.001, 0, 1);
  }

  private static double smoothstep(double t) {
    t = LXUtils.constrain(t, 0, 1);
    return t * t * (3 - 2 * t);
  }

  // ---- Drawing -------------------------------------------------------------------

  /**
   * Refresh the two hues per polyline from the active palette swatch, rotated by
   * hueOffset. With an empty swatch, fall back to four evenly-spaced saturated
   * perceptual hues so the pattern is never colorless.
   */
  private void computeColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = swatch.size();
    for (int k = 0; k < this.shapeColor.length; ++k) {
      final int idx = this.hueOffset + k;
      this.shapeColor[k] = (n > 0)
        ? swatch.get(idx % n).getColor()
        : PerceptualHue.color((idx % this.shapeColor.length) / (float) this.shapeColor.length);
    }
  }

  /**
   * Draw the polylines onto a canvas — the trail (stamping a leftover) or the
   * scratch (the live shape). Both line styles composite with per-channel max
   * (lighten), so crossings never punch dark notches through brighter fading
   * lines — that union-of-brightness is what preserves the moire structure.
   */
  private void drawShapeLines(SurfaceCanvas canvas, boolean wrapX) {
    final int w = canvas.width;
    final int h = canvas.height;
    final int nShapes = this.shapes.getValuei();
    final int nVerts = this.vertices.getValuei();
    final boolean smooth = this.wu.isOn();

    for (int s = 0; s < nShapes; ++s) {
      final int base = s * MAX_VERTS;
      final int colorA = this.shapeColor[2 * s];
      final int colorB = this.shapeColor[2 * s + 1];
      for (int i = 0; i < nVerts; ++i) {
        final int j = (i + 1) % nVerts;
        final int argb = ((i & 1) == 0) ? colorA : colorB;
        if (smooth) {
          final double x0 = pixelXf(this.posX[base + i], w, wrapX);
          final double y0 = pixelYf(this.posY[base + i], h);
          double x1 = pixelXf(this.posX[base + j], w, wrapX);
          final double y1 = pixelYf(this.posY[base + j], h);
          if (wrapX) {
            // Take the short way around the ring; SurfaceCanvas wraps x for us
            final double dx = x1 - x0;
            if (dx > w / 2.0) {
              x1 -= w;
            } else if (dx < -w / 2.0) {
              x1 += w;
            }
          }
          canvas.lineWu(x0, y0, x1, y1, argb, wrapX);
        } else {
          final int x0 = pixelX(this.posX[base + i], w, wrapX);
          final int y0 = pixelY(this.posY[base + i], h);
          int x1 = pixelX(this.posX[base + j], w, wrapX);
          final int y1 = pixelY(this.posY[base + j], h);
          if (wrapX) {
            final int dx = x1 - x0;
            if (dx > w / 2) {
              x1 -= w;
            } else if (dx < -w / 2) {
              x1 += w;
            }
          }
          canvas.lineMax(x0, y0, x1, y1, argb);
        }
      }
    }
  }

  /** Bass-hit flash: a bright white cross at every active vertex, stamped
   *  into the trail canvas so the pop decays out with the trails. Max-blend;
   *  on the bounce topology out-of-range x is dropped, not wrapped. */
  private void stampFlash(SurfaceCanvas canvas, boolean wrapX, double energyValue) {
    final int w = canvas.width;
    final int h = canvas.height;
    final int nShapes = this.shapes.getValuei();
    final int nVerts = this.vertices.getValuei();
    final int flashColor = LXColor.gray(100 * this.flash * Ranges.lin(energyValue, 0.6, 1.0));
    for (int s = 0; s < nShapes; ++s) {
      final int base = s * MAX_VERTS;
      for (int i = 0; i < nVerts; ++i) {
        final int px = pixelX(this.posX[base + i], w, wrapX);
        final int py = pixelY(this.posY[base + i], h);
        if (wrapX) {
          canvas.setMax(px, py, flashColor);
          canvas.setMax(px, py - 1, flashColor);
          canvas.setMax(px, py + 1, flashColor);
          canvas.setMax(px - 1, py, flashColor);
          canvas.setMax(px + 1, py, flashColor);
        } else {
          canvas.setMaxClamped(px, py, flashColor);
          canvas.setMaxClamped(px, py - 1, flashColor);
          canvas.setMaxClamped(px, py + 1, flashColor);
          canvas.setMaxClamped(px - 1, py, flashColor);
          canvas.setMaxClamped(px + 1, py, flashColor);
        }
      }
    }
  }

  private static int pixelX(double xn, int width, boolean wrapX) {
    // Wrap mode spans the full circumference; bounce mode pins endpoints to edges
    return wrapX ? (int) Math.round(xn * width) : (int) Math.round(xn * (width - 1));
  }

  private static int pixelY(double yn, int height) {
    return (int) Math.round(yn * (height - 1));
  }

  /** Fractional pixel coordinates for the Wu path (same mapping, unrounded) */
  private static double pixelXf(double xn, int width, boolean wrapX) {
    return wrapX ? xn * width : xn * (width - 1);
  }

  private static double pixelYf(double yn, int height) {
    return yn * (height - 1);
  }

  /** FACES mode: blit a 50x45 canvas onto the front exterior face, door-guarded */
  private void copyToFrontFace(SurfaceCanvas canvas) {
    final Apotheneum.Cube.Face front = Apotheneum.cube.exterior.front;
    for (int x = 0; x < canvas.width; ++x) {
      final Apotheneum.Column column = front.columns[x];
      final int h = Math.min(canvas.height, column.points.length);
      for (int y = 0; y < h; ++y) {
        this.colors[column.points[y].index] = canvas.get(x, y);
      }
    }
  }
}
