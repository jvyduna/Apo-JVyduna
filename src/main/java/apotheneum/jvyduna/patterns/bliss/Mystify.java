package apotheneum.jvyduna.patterns.bliss;

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
 * Recreation of the classic "Mystify Your Mind" screensaver family: polylines
 * whose vertices drift independently, leaving distinct fading leftover lines
 * behind the live shape. Vertices always bounce off the top and bottom;
 * horizontally they either bounce (the per-face modes) or wrap continuously
 * around the cube ring or the cylinder. Faces4 runs four fully independent
 * simulations, one per cube face.
 *
 * The gapped-moire look comes from the trail architecture: the live shape is
 * drawn fresh every frame (max-blended) but only STAMPED into the persistent
 * trail canvas on crossings of the TrailDiv tempo division. Stamps composite
 * with the TrailBlend mode — the default continuous XOR erases trail pixels
 * where a new line crosses old ones, developing the classic interference
 * moire in the decaying buffer.
 *
 * Bounce timing is hard-locked to the transport beat grid: every wall bounce
 * lands exactly on a multiple of BounceDiv beats (grid re-anchored by
 * Scatter). Each vertex axis independently plans its next arrival a RANDOM
 * 1..k grid steps out (k = 8 / Speed), which decorrelates x/y arrival times
 * so the ricochet angle and bounce location vary — no periodic corner-to-
 * corner locking. Velocity is derived from remaining-distance / time-to-
 * target each frame, so arrivals are exact and live BPM changes are absorbed;
 * speed changes slew smoothly to their new plans.
 *
 * See Mystify.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Mystify")
@LXComponent.Description("Mystify-screensaver polylines leaving beat-spaced XOR-moire trails, bouncing on random beat multiples, on the cube faces (shared or independent), the cube ring, or the cylinder")
public class Mystify extends ApotheneumPattern {

  // ---- Timing / motion constants ---------------------------------------------

  /** Seconds for a vertex to cross the full canvas width at Speed=1 nominal
   *  rate (wrap-x drift; planned axes use random grid arrivals, dart-guarded
   *  at low Speed — the guard fades out as Speed approaches SPEED_MAX) */
  private static final double TRAVERSE_SEC = 12;

  /** Top of the Speed knob range; at this value bounce planning is
   *  deterministic (exactly one BounceDiv apart) and the dart guard is off */
  private static final double SPEED_MAX = 5;

  /** Slowest vertex velocity component, as a fraction of the fastest — keeps
   *  wrap-x drift varied per vertex (planned axes use it only for direction) */
  private static final double VEL_MIN = 0.45;

  /** Trail half-life range (ms), mapped exponentially from the Trails knob:
   *  50 ms reads as almost bare lines; 20 s leaves near-permanent layers */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 50;
  private static final double TRAIL_HALF_LIFE_MAX_MS = 20000;

  /** Treble transients shorten trails by up to ~30% (1 + 0.15 * 2) — subtle */
  private static final double TREBLE_TRAIL_SHORTEN = 0.15;

  /** Bass-hit vertex flash fades to black over this long — a visible pop.
   *  Hits gated closer together than this (the retrigger gate is ~an eighth
   *  note) simply re-arm the envelope before it empties. */
  private static final double FLASH_DECAY_MS = 500;

  /** Peak brightness of the flash ball as a fraction of the edge color */
  private static final double FLASH_LEVEL = 0.6;

  /** Flash ball radius at full envelope (pulses with the envelope; radius 1
   *  reproduces the original 5-pixel cross footprint) */
  private static final int FLASH_MAX_RADIUS = 3;

  /** Random bounce window at Speed=1: next arrival is 1..8 grid steps out */
  private static final int RAND_BOUNCE_STEPS = 8;

  /** Upper clamp on the speed-scaled random window (8 / Speed) */
  private static final int RAND_WINDOW_MAX = 64;

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
   *  retarget-with-slew (Speed knob moves) */
  private static final double RATE_CHANGE_FRAC = 0.02;

  /** A target this far in the past with real distance remaining is stale
   *  (transport restart / huge BPM jump / inactive gap) — replan, don't jump */
  private static final double STALE_TARGET_MS = 250;

  /** Structural maxima; all state is preallocated at these sizes */
  private static final int MAX_SHAPES = 2;
  private static final int MAX_VERTS = 5;
  private static final int FACE_GROUPS = 4;
  private static final int GROUP_SIZE = MAX_SHAPES * MAX_VERTS;

  // ---- Geometry modes ---------------------------------------------------------

  public enum Geometry {
    FACES("Faces", false),        // one 50x45 sim mirrored to all 4 cube faces; x bounces
    FACES_4("Faces4", false),     // four independent 50x45 sims, one per face; x bounces
    CUBE_RING("CubeRing", true),  // one 200x45 sim around the cube exterior; x wraps
    CYLINDER("Cylinder", true);   // one 120x43 sim around the cylinder; x wraps

    public final String label;
    public final boolean wrapX;
    private Geometry(String label, boolean wrapX) {
      this.label = label;
      this.wrapX = wrapX;
    }

    @Override
    public String toString() { return this.label; }
  }

  /** Beat grid for wall bounces, in quarter-note beats */
  public enum BounceGrid {
    THREE_QUARTER(0.75, "3/4"),
    ONE(1, "1"),
    TWO(2, "2"),
    FOUR(4, "4"),
    EIGHT(8, "8");

    public final double beats;
    public final String label;
    private BounceGrid(double beats, String label) {
      this.beats = beats;
      this.label = label;
    }

    @Override
    public String toString() { return this.label; }
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
    .setDescription("Rotate the palette color assignment of the polyline edges"));

  public final EnumParameter<Geometry> geometry =
    new EnumParameter<Geometry>("GeoMode", Geometry.CUBE_RING)
    .setDescription("Surface topology: Faces mirrors one sim to all cube faces, Faces4 runs one independent sim per face (x bounces); CubeRing/Cylinder wrap continuously around");

  public final DiscreteParameter shapes =
    new DiscreteParameter("Shapes", 2, 1, MAX_SHAPES + 1)
    .setDescription("Number of independent polylines (1 or 2)");

  public final DiscreteParameter vertices =
    new DiscreteParameter("Vrtices", 4, 2, MAX_VERTS + 1)
    .setDescription("Vertices per polyline (2 = a single open line, 3-5 = closed polygon)");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1, 0, SPEED_MAX)
    .setExponent(2)
    .setDescription("Bounce cadence: at max every bounce lands exactly one BounceDiv apart; lower values widen the random arrival window (1..8/Speed BounceDiv steps); 0 = pause");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", 0.25)
    .setDescription("Decay rate of stamped leftover lines: 0 = fade almost instantly, 1 = ~20 s half-life near-permanent layers");

  public final EnumParameter<Tempo.Division> trailDiv =
    new EnumParameter<Tempo.Division>("TrailDiv", Tempo.Division.EIGHTH)
    .setDescription("Tempo division on whose crossings the live shape is stamped into the trail as a distinct leftover line");

  public final EnumParameter<SurfaceCanvas.Blend> trailBlend =
    new EnumParameter<SurfaceCanvas.Blend>("TrailBlend", SurfaceCanvas.Blend.XOR)
    .setDescription("How stamped lines composite against existing trail pixels: Xor erases at crossings (interference moire), Max lightens, Diff soft-inverts, Add builds up");

  public final BooleanParameter wu =
    new BooleanParameter("Wu", false)
    .setDescription("Draw lines with Xiaolin Wu antialiasing (smooth, slightly softer) instead of Bresenham");

  public final BooleanParameter mirror =
    new BooleanParameter("Mirror", false)
    .setDescription("Draw a mirrored copy of every line and flash ball across the geometry's reflection axis (local centerline on face modes, the verified X=Z world diagonal on CubeRing/Cylinder)");

  public final EnumParameter<BounceGrid> bounceDiv =
    new EnumParameter<BounceGrid>("BncDiv", BounceGrid.ONE)
    .setDescription("Beat grid (in quarter-note beats) that every wall bounce lands on, anchored by pattern load / Scatter");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire one of Mystify's triggers or jump a parameter");

  // ---- Simulation state (all preallocated; normalized [0,1] coordinates) ------
  // Four face groups of MAX_SHAPES*MAX_VERTS vertices each; group g occupies
  // indices [g*GROUP_SIZE, (g+1)*GROUP_SIZE). All 40 are always simulated;
  // FACES/CUBE_RING/CYLINDER draw group 0 only, FACES_4 draws group f per face.

  private final double[] posX = new double[FACE_GROUPS * GROUP_SIZE];
  private final double[] posY = new double[FACE_GROUPS * GROUP_SIZE];
  private final double[] velX = new double[FACE_GROUPS * GROUP_SIZE]; // canvas-widths/sec at rate=1
  private final double[] velY = new double[FACE_GROUPS * GROUP_SIZE]; // canvas-heights/sec at rate=1

  /** Planned wall-arrival per axis as an ABSOLUTE transport beat index
   *  (see TempoLock.beatPosition). NaN = unplanned: paused, or x on wrap
   *  topologies where there is no bounce event to schedule. */
  private final double[] targetBeatX = new double[FACE_GROUPS * GROUP_SIZE];
  private final double[] targetBeatY = new double[FACE_GROUPS * GROUP_SIZE];

  /** Blend source during a speed-retarget slew; equal to targetBeat* when no
   *  slew is active (bounce replans reset them together). */
  private final double[] slewOldBeatX = new double[FACE_GROUPS * GROUP_SIZE];
  private final double[] slewOldBeatY = new double[FACE_GROUPS * GROUP_SIZE];

  /** Whole beat the bounce grid is anchored to: allowed bounce beats are
   *  anchorBeat + k * BounceDiv. Reset at load and by Scatter. */
  private double anchorBeat = 0;

  /** Remaining ms of the current retarget slew (0 = no slew active) */
  private double slewRemainMs = 0;

  /** rateSpansPerSec at the last (re)plan, for material-change detection */
  private double plannedRate = 0;

  private boolean needsReanchor = true;
  private boolean needsReplan = true;
  private boolean wasPaused = false;
  private BounceGrid lastBounceGrid = null;

  /** Per-edge colors (edge i of shape s at [s*MAX_VERTS + i]), refreshed from
   *  the palette each frame, cycling across ALL swatch colors — shared by all
   *  four FACES_4 groups so the faces stay color-coherent */
  private final int[] edgeColor = new int[MAX_SHAPES * MAX_VERTS];
  private int hueOffset = 0;

  /** Bass-hit vertex flash envelope, 0..1 (armed to audio depth on each hit) */
  private double flash = 0;

  /** This frame's rate in canvas-spans/sec at velocity magnitude 1 — drives
   *  wrap-x drift and rate-change (retarget) detection */
  private double rateSpansPerSec = 0;

  /** Persistent trail canvases (hold only STAMPED leftover lines, decaying)
   *  and per-frame scratch composites. faceTrail[0] doubles as FACES mode's
   *  canvas; FACES_4 uses all four. */
  private final SurfaceCanvas[] faceTrail = new SurfaceCanvas[FACE_GROUPS];    // 50x45 each
  private final SurfaceCanvas[] faceScratch = new SurfaceCanvas[FACE_GROUPS];
  private final SurfaceCanvas ringCanvas =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT);    // 200x45
  private final SurfaceCanvas ringScratch =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT);
  private final SurfaceCanvas cylinderCanvas =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT); // 120x43
  private final SurfaceCanvas cylinderScratch =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT);

  private Geometry lastGeometry = null;

  public Mystify(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);
    this.geometry.setWrappable(false); // knob stops at Faces/Cylinder ends instead of wrapping
    for (int f = 0; f < FACE_GROUPS; ++f) {
      this.faceTrail[f] = new SurfaceCanvas(Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);
      this.faceScratch[f] = new SurfaceCanvas(Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);
    }

    addParameter("scatter", this.scatter);
    addParameter("reverse", this.reverse);
    addParameter("hueJump", this.hueJump);
    addParameter("geometry", this.geometry);
    addParameter("shapes", this.shapes);
    addParameter("vertices", this.vertices);
    addParameter("speed", this.speed);
    addParameter("trails", this.trails);
    addParameter("trailDiv", this.trailDiv);
    addParameter("trailBlend", this.trailBlend);
    addParameter("wu", this.wu);
    addParameter("mirror", this.mirror);
    addParameter("bounceDiv", this.bounceDiv);
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

  /** Random signed velocity component in [VEL_MIN, 1] canvas-spans/sec at rate=1 */
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
    this.hueOffset = (this.hueOffset + 1) % 1024;
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
      for (int f = 0; f < FACE_GROUPS; ++f) {
        this.faceTrail[f].fill(LXColor.BLACK);
      }
      this.ringCanvas.fill(LXColor.BLACK);
      this.cylinderCanvas.fill(LXColor.BLACK);
      this.lastGeometry = geom;
      geomChanged = true;
    }
    final boolean wrapX = geom.wrapX;

    // TrailDiv stamp gate: polled EXACTLY ONCE per frame (crossed() consumes
    // the crossing; the FACES_4 per-face loop must reuse this boolean)
    final boolean stampNow = this.tempoLock.crossed(this.trailDiv.getEnum());

    // Nominal rate: drives wrap-x drift and retarget detection. Planned-axis
    // velocity comes from the random grid arrivals in planAxis().
    this.rateSpansPerSec = this.speed.getValue() / TRAVERSE_SEC;
    final boolean paused = this.speed.getValue() < PAUSE_SPEED_EPS;

    // Bounce planner bookkeeping (order matters: anchor before any replan)
    final BounceGrid grid = this.bounceDiv.getEnum();
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
      retargetAll(wrapX); // Speed knob move: new random plans, smoothstep slew
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
    final double decayMult = Math.pow(0.5, deltaMs / halfLifeMs);

    // Bass-hit vertex flash envelope, armed to the audio depth so the ball
    // size and brightness scale with the knob
    if (this.audio.bassHit()) {
      this.flash = this.audio.depth();
    } else {
      this.flash = Math.max(0, this.flash - deltaMs / FLASH_DECAY_MS);
    }

    computeColors();

    // Render the active surface(s), then paint exterior + mirror interior.
    // The inactive component stays dark (see Mystify.md for the rationale).
    switch (geom) {
      case FACES:
        renderSurface(this.faceTrail[0], this.faceScratch[0], geom, 0, stampNow, decayMult);
        this.faceScratch[0].copyTo(Apotheneum.cube.exterior.front, this.colors);
        copyCubeFace(Apotheneum.cube.exterior.front); // replicates to all 8 faces
        break;
      case FACES_4:
        for (int f = 0; f < FACE_GROUPS; ++f) {
          renderSurface(this.faceTrail[f], this.faceScratch[f], geom, f, stampNow, decayMult);
          final Apotheneum.Cube.Face exterior = Apotheneum.cube.exterior.faces[f];
          this.faceScratch[f].copyTo(exterior, this.colors);
          if (Apotheneum.cube.interior != null) {
            copy(exterior, Apotheneum.cube.interior.faces[f]);
          }
        }
        break;
      case CUBE_RING:
        renderSurface(this.ringCanvas, this.ringScratch, geom, 0, stampNow, decayMult);
        this.ringScratch.copyTo(Apotheneum.cube.exterior, this.colors);
        copyCubeExterior();
        break;
      case CYLINDER:
        renderSurface(this.cylinderCanvas, this.cylinderScratch, geom, 0, stampNow, decayMult);
        this.cylinderScratch.copyTo(Apotheneum.cylinder.exterior, this.colors);
        copyCylinderExterior();
        break;
    }
  }

  /** One surface's frame: decay trail, stamp leftover lines (TrailBlend) on
   *  TrailDiv crossings, stamp flash balls, then composite the live shape
   *  (always max-blend — a live line XOR-redrawn over its own fresh stamp
   *  would self-invert to black) on top of the trail into scratch. */
  private void renderSurface(SurfaceCanvas trail, SurfaceCanvas scratch, Geometry geom,
                             int group, boolean stampNow, double decayMult) {
    trail.decay(decayMult);
    if (stampNow) {
      drawShapeLines(trail, geom, group, this.trailBlend.getEnum());
    }
    if (this.flash > 0.01) {
      stampFlash(trail, geom, group); // balls land in the trail so they decay out
    }
    scratch.copyFrom(trail);
    drawShapeLines(scratch, geom, group, SurfaceCanvas.Blend.MAX);
  }

  // ---- Bounce planner -------------------------------------------------------------

  /**
   * Plan one axis's wall arrival: a RANDOM allowed grid beat (anchorBeat +
   * m * BounceDiv) 1..kMax steps from now, where kMax = floor(8/Speed) —
   * drawn independently per vertex per axis per bounce, so x/y arrival times
   * decorrelate and the ricochet angle and bounce location vary (no periodic
   * corner-to-corner locking). At Speed max kMax is 1 and the dart guard is
   * fully faded, so every bounce lands exactly one BounceDiv apart; at
   * Speed <= 1 the guard runs at full strength (bumping draws that would
   * imply traversal faster than TRAVERSE_SEC/5 per span), preserving the
   * low-speed look.
   */
  private double planAxis(double pos, double vel) {
    final double n = this.bounceDiv.getEnum().beats;
    final double beatMs = this.tempoLock.beatPeriodMs();
    final double now = this.tempoLock.beatPosition();
    final double speedVal = Math.max(this.speed.getValue(), PAUSE_SPEED_EPS);
    final int kMax = (int) LXUtils.constrain(Math.floor(RAND_BOUNCE_STEPS / speedVal), 1, RAND_WINDOW_MAX);

    // First allowed grid beat at least MIN_HEADROOM_MS away, then a random
    // number of additional whole grid steps
    final double headroomBeats = (beatMs > 0) ? MIN_HEADROOM_MS / beatMs : n;
    double target = this.anchorBeat + Math.ceil((now + headroomBeats - this.anchorBeat) / n) * n;
    target += n * this.random.nextInt(kMax);

    // Dart guard, faded with Speed: full strength at Speed <= 1 (implied
    // traversal never faster than TRAVERSE_SEC/5 per span), gone at
    // SPEED_MAX so exact-BounceDiv darts are allowed. Bounded loop as
    // paranoia against a degenerate tempo period.
    final double guardFactor = LXUtils.constrain((SPEED_MAX - speedVal) / (SPEED_MAX - 1), 0, 1);
    final double remDist = (vel > 0) ? (1 - pos) : pos;
    final double minMs = Math.max(MIN_HEADROOM_MS, 1000 * remDist * (TRAVERSE_SEC / 5) * guardFactor);
    for (int guard = 0; (guard < 1024) && (this.tempoLock.msUntilBeat(target) < minMs); ++guard) {
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
   * Speed moved materially: re-plan every axis toward fresh random grid
   * beats under the new window, blending the effective arrival smoothly over
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
   * Integrate vertex motion for all four face groups. Planned axes (y always;
   * x on the face modes) derive velocity from remaining-distance /
   * time-to-target-beat each frame, so every bounce lands exactly on the
   * BounceDiv grid and BPM changes are absorbed continuously. Wrap-x
   * free-runs at the nominal rate — a wrap has no visible event to align.
   * X and Y are advanced independently, so a same-frame corner double-bounce
   * just works: each axis clamps, flips, and replans itself.
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
    // (planAxis re-rolls the random window — fresh ricochet every bounce)
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
   * Refresh the per-edge colors from the active palette swatch, rotated by
   * hueOffset: edge i of shape s cycles through ALL swatch colors (with a
   * 3-color palette and 5 vertices, edges use colors 0,1,2,0,1 — not just the
   * first two). With an empty swatch, fall back to evenly-spaced saturated
   * perceptual hues so the pattern is never colorless.
   */
  private void computeColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = swatch.size();
    for (int s = 0; s < MAX_SHAPES; ++s) {
      for (int i = 0; i < MAX_VERTS; ++i) {
        final int slot = this.hueOffset + s * MAX_VERTS + i;
        this.edgeColor[s * MAX_VERTS + i] = (n > 0)
          ? swatch.get(slot % n).getColor()
          : PerceptualHue.color((slot % this.edgeColor.length) / (float) this.edgeColor.length);
      }
    }
  }

  /**
   * Draw one group's polylines onto a canvas with the given blend — the trail
   * (stamping a leftover, TrailBlend) or the scratch (the live shape, MAX).
   * At Vertices=2 the "polygon" is a single open line (one edge). When Mirror
   * is on, every edge gets a second reflected copy (same color). With a
   * self-inverting blend (anything but MAX), closed-polygon edges draw
   * half-open so each shared vertex is plotted exactly once — the second
   * write would XOR a full-bright vertex pixel back to black.
   */
  private void drawShapeLines(SurfaceCanvas canvas, Geometry geom, int group, SurfaceCanvas.Blend blend) {
    final int w = canvas.width;
    final int h = canvas.height;
    final boolean wrapX = geom.wrapX;
    final int nShapes = this.shapes.getValuei();
    final int nVerts = this.vertices.getValuei();
    final int edgeCount = (nVerts == 2) ? 1 : nVerts;
    final boolean smooth = this.wu.isOn();
    final boolean mirrorOn = this.mirror.isOn();
    final boolean halfOpen = (nVerts > 2) && (blend != SurfaceCanvas.Blend.MAX);

    for (int s = 0; s < nShapes; ++s) {
      final int base = (group * MAX_SHAPES + s) * MAX_VERTS;
      for (int i = 0; i < edgeCount; ++i) {
        final int j = (i + 1) % nVerts;
        final int argb = this.edgeColor[s * MAX_VERTS + i];
        if (smooth) {
          final double x0 = pixelXf(this.posX[base + i], w, wrapX);
          final double y0 = pixelYf(this.posY[base + i], h);
          final double x1 = pixelXf(this.posX[base + j], w, wrapX);
          final double y1 = pixelYf(this.posY[base + j], h);
          drawEdgeWu(canvas, x0, y0, x1, y1, argb, wrapX, blend);
          if (mirrorOn) {
            drawEdgeWu(canvas, mirrorPixelXf(x0, geom, w), y0,
                               mirrorPixelXf(x1, geom, w), y1, argb, wrapX, blend);
          }
        } else {
          final int x0 = pixelX(this.posX[base + i], w, wrapX);
          final int y0 = pixelY(this.posY[base + i], h);
          final int x1 = pixelX(this.posX[base + j], w, wrapX);
          final int y1 = pixelY(this.posY[base + j], h);
          drawEdgeBresenham(canvas, x0, y0, x1, y1, argb, wrapX, blend, halfOpen);
          if (mirrorOn) {
            drawEdgeBresenham(canvas, mirrorPixelX(x0, geom, w), y0,
                                       mirrorPixelX(x1, geom, w), y1, argb, wrapX, blend, halfOpen);
          }
        }
      }
    }
  }

  /** One Wu edge: takes the short way around the ring (re-derived here so a
   *  mirrored copy's own short way, which can differ from the original's, is
   *  computed independently), then plots. Wu endpoint double-plots under XOR
   *  are accepted — fringe coverage rarely hits full twice (CURATE). */
  private static void drawEdgeWu(SurfaceCanvas canvas, double x0, double y0, double x1, double y1,
                                 int argb, boolean wrapX, SurfaceCanvas.Blend blend) {
    final double w = canvas.width;
    if (wrapX) {
      final double dx = x1 - x0;
      if (dx > w / 2.0) {
        x1 -= w;
      } else if (dx < -w / 2.0) {
        x1 += w;
      }
    }
    canvas.lineWu(x0, y0, x1, y1, argb, wrapX, blend);
  }

  /** Bresenham counterpart of drawEdgeWu. */
  private static void drawEdgeBresenham(SurfaceCanvas canvas, int x0, int y0, int x1, int y1,
                                        int argb, boolean wrapX, SurfaceCanvas.Blend blend, boolean halfOpen) {
    final int w = canvas.width;
    if (wrapX) {
      final int dx = x1 - x0;
      if (dx > w / 2) {
        x1 -= w;
      } else if (dx < -w / 2) {
        x1 += w;
      }
    }
    canvas.lineBlend(x0, y0, x1, y1, argb, blend, halfOpen);
  }

  /**
   * Mirror a pixel x-coordinate across the Mirror reflection axis. The face
   * modes and CubeRing all reflect at (width-1)-x: for the face modes that is
   * a local flip across the face's own vertical centerline; for CubeRing it
   * is the verified physical X=Z world diagonal (through the front-left and
   * back-right corner posts), which happens to land at the same formula
   * because those two corners sit exactly at the ring's column-0 seam and
   * column-~99.5 seam. Cylinder differs because it is angularly (not
   * arc-length) parameterized, so its diagonal lands at 0.75*width instead.
   */
  private static int mirrorPixelX(int px, Geometry geom, int width) {
    if (geom == Geometry.CYLINDER) {
      return Math.floorMod((3 * width / 4) - px, width); // 90 for width=120
    }
    return (width - 1) - px;
  }

  /** Fractional counterpart of mirrorPixelX for the Wu path — the underlying
   *  world-geometry algebra is linear/exact, so it extends continuously. */
  private static double mirrorPixelXf(double px, Geometry geom, int width) {
    if (geom == Geometry.CYLINDER) {
      return LXUtils.wrap(0.75 * width - px, 0, width);
    }
    return (width - 1) - px;
  }

  /** Bass-hit flash: a palette-colored ball at every active vertex of the
   *  group, radius pulsing with the flash envelope (up to FLASH_MAX_RADIUS),
   *  stamped into the trail canvas so the pop decays out with the trails.
   *  Always max-blended (a flash should pop, not erase); mirrored alongside
   *  the shape when Mirror is on. */
  private void stampFlash(SurfaceCanvas canvas, Geometry geom, int group) {
    final int w = canvas.width;
    final int h = canvas.height;
    final boolean wrapX = geom.wrapX;
    final boolean mirrorOn = this.mirror.isOn();
    final int nShapes = this.shapes.getValuei();
    final int nVerts = this.vertices.getValuei();
    final int r = Math.max(1, (int) Math.round(FLASH_MAX_RADIUS * this.flash));
    final double level = FLASH_LEVEL * this.flash;
    for (int s = 0; s < nShapes; ++s) {
      final int base = (group * MAX_SHAPES + s) * MAX_VERTS;
      for (int i = 0; i < nVerts; ++i) {
        final int argb = scaleRGB(this.edgeColor[s * MAX_VERTS + i], level);
        final int px = pixelX(this.posX[base + i], w, wrapX);
        final int py = pixelY(this.posY[base + i], h);
        stampFlashBall(canvas, px, py, r, argb, wrapX);
        if (mirrorOn) {
          stampFlashBall(canvas, mirrorPixelX(px, geom, w), py, r, argb, wrapX);
        }
      }
    }
  }

  /** One flash ball: filled disc, max-blended, x wrapped on wrap topologies,
   *  x clamped (dropped) on the face modes. Radius 1 = the original 5-pixel
   *  cross footprint. */
  private static void stampFlashBall(SurfaceCanvas canvas, int px, int py, int r, int argb, boolean wrapX) {
    for (int dy = -r; dy <= r; ++dy) {
      final int dxMax = (int) Math.sqrt(r * r - dy * dy);
      for (int dx = -dxMax; dx <= dxMax; ++dx) {
        if (wrapX) {
          canvas.setMax(px + dx, py + dy, argb);
        } else {
          canvas.setMaxClamped(px + dx, py + dy, argb);
        }
      }
    }
  }

  /** Scale an ARGB color's RGB channels by mult (clamped 0..1), alpha opaque */
  private static int scaleRGB(int argb, double mult) {
    if (mult <= 0) {
      return 0xff000000;
    }
    if (mult > 1) {
      mult = 1;
    }
    final int r = (int) (((argb >> 16) & 0xff) * mult);
    final int g = (int) (((argb >> 8) & 0xff) * mult);
    final int b = (int) ((argb & 0xff) * mult);
    return 0xff000000 | (r << 16) | (g << 8) | b;
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

}
