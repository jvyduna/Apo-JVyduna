package apotheneum.jvyduna.patterns;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
import apotheneum.jvyduna.util.TempoLock;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Recreation of the Windows NT "3D Pipes" screensaver. A self-avoiding pipe
 * lattice grows through a single ~10x9x10-cell room volume; the cube's four
 * walls are the four walls of that room, each showing an orthographic
 * projection of the SAME shared 3D object. Per-wall depth buffers resolve
 * overlap (nearer pipe wins); a Phong lighting rig (sun + corner fills,
 * rotating about Y once per 32 beats) shades the pipes as round tubes and
 * antialiases the projection — three coordinate systems: the walls (fixed),
 * the lattice (RotX/RotY), and the lighting rig.
 *
 * Growth is beat-planned, Mystify-style: every cap (turn/elbow) lands on the
 * CapDiv grid phase-aligned to the global tempo, with per-frame velocity
 * derived from remaining-distance / time-to-cap (exact arrival, BPM-robust).
 * The Speed knob steers how many divisions each run spans.
 *
 * Cube-only in v1; the cylinder stays dark (follow-up curation item). See
 * Pipes3D.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Pipes 3D")
@LXComponent.Description("NT 3D Pipes: a beat-planned self-avoiding pipe lattice grows in one shared room volume, projected (and optionally rotated) onto all four cube walls")
public class Pipes3D extends ApotheneumPattern {

  // ---- Geometry constants ---------------------------------------------------

  private static final int W = Apotheneum.GRID_WIDTH;   // 50 px per wall
  private static final int H = Apotheneum.GRID_HEIGHT;  // 45 px per wall
  private static final int WALLS = 4;                   // front, right, back, left

  /** Grid density bounds: cells across the room width/depth (x and z) */
  private static final int MIN_DENSITY = 6;
  private static final int MAX_DENSITY = 12;
  /** Max vertical cells = round(MAX_DENSITY * H / W) = 11 */
  private static final int MAX_DENSITY_Y = 11;

  // ---- Timing constants (physical intent) -----------------------------------

  /** Auto-drain when this fraction of room cells is occupied */
  private static final double FILL_LIMIT = 0.6;

  /** Decay time of the bass-hit elbow sparkle flash (stationary, not motion) */
  private static final double SPARKLE_MS = 500; // CURATE: flash length vs. subtlety

  // ---- Look constants --------------------------------------------------------

  /** Chance of continuing straight when the straight-ahead run is free */
  private static final double P_STRAIGHT = 0.55; // CURATE: NT pipes turn often; higher = longer runs

  /** Specular: saturation floor at full intensity (lower = whiter) */
  private static final float SPEC_SAT_FLOOR = 35;  // CURATE
  /** Base gain on the Blinn-Phong lobe; scaled by the Shaded knob and audio */
  private static final double SPEC_GAIN = 1.4; // CURATE: highlight visibility
  /** How much loud music inflates the highlights (rides audio.level, depth-scaled) */
  private static final double AUDIO_SPEC_BOOST = 1.5; // CURATE

  // ---- Lighting rig (rotates about Y, independent of the lattice) -----------

  /** Beats per full revolution of the lighting rig about the y axis */
  private static final double RIG_ROT_BEATS = 32;
  /** Ambient floor: the dark side of a pipe never goes fully black */
  private static final double AMBIENT = 0.15; // CURATE
  /** Diffuse weight of the sun (key light, directional) */
  private static final double KD_SUN = 0.65; // CURATE
  /** Diffuse weight of each corner fill point light */
  private static final double KD_FILL = 0.30; // CURATE
  /** Fill distance in room half-extents from the center (direction only, no falloff) */
  private static final double FILL_DIST = 3.0; // CURATE
  /** Fill corner directions (unit-box corners; -y is up, so both sit above the room) */
  private static final double[][] FILL_CORNERS = { { -1, -1, 1 }, { 1, -1, -1 } }; // CURATE

  /** How fast loudness widens the flashed-cap set on a bass hit (1 = at full level) */
  private static final double SPARKLE_BREADTH = 2; // CURATE

  /** Elbow ball: extra radius in px beyond the pipe half-thickness */
  private static final double BALL_EXTRA_PX = 1;

  /** Thickness parameter bounds (px); THICK_MAX also sizes the sparkle
   *  visibility tolerance in sparkleOverlay() */
  private static final double THICK_MIN = 1, THICK_MAX = 6;

  /** Elbow ball saturation / brightness boost (reads as a shiny joint) */
  private static final float BALL_SAT = 70;        // CURATE
  private static final float BALL_BOOST = 1.15f;

  /** Depth cue: brightness = 1 - DEPTH_DIM * normalizedDepth (far = 50%) */
  private static final float DEPTH_DIM = 0.5f;

  /** Peak brightness of the bass sparkle overlay (percent) */
  private static final float SPARKLE_BRIGHTNESS = 90; // CURATE

  private static final int MAX_PIPES = 3;

  /** Attempts to find a free relocation cell before giving up and draining */
  private static final int RELOCATE_TRIES = 60;

  /** Recent elbows retained for the bass sparkle overlay (loud hits flash more of them) */
  private static final int ELBOW_HISTORY = 48;

  /**
   * Retained geometry bounds. Normal growth is bounded by occupancy
   * (FILL_LIMIT * 12*11*12 ~ 950 cells, and merged straight runs use fewer
   * segments than cells); these are reachable only through long best-effort
   * intersect episodes, and overflowing simply starts a drain.
   */
  private static final int MAX_SEGMENTS = 1600;
  private static final int MAX_BALLS = 1600;

  // 6 axis directions: -x +x -y +y -z +z (opposite of d is d^1)
  private static final int[] DX = { -1, 1, 0, 0, 0, 0 };
  private static final int[] DY = { 0, 0, -1, 1, 0, 0 };
  private static final int[] DZ = { 0, 0, 0, 0, -1, 1 };

  // ---- Sun (world-fixed directional light for the specular stripe) ----------

  /**
   * Per-wall view directions (toward the viewer, along the wall's outward
   * normal in room coordinates). The walls are the world frame: the lattice
   * rotates, the sun and the viewers do not.
   */
  private static final double[][] VIEW = { { 0, 0, -1 }, { 1, 0, 0 }, { 0, 0, 1 }, { -1, 0, 0 } };

  /** Base sun direction (toward the light) at rig angle 0; rotated per frame */
  private static final double[] SUN;
  static {
    // Sun at infinity toward an upper corner. Rows index top-down on the
    // walls (column.points[0] is the top row), so "up" is -y in room
    // coordinates. CURATE: elevation.
    final double sx = 0.35, sy = -0.85, sz = 0.35;
    final double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
    SUN = new double[] { sx / sl, sy / sl, sz / sl };
  }

  /** Per-wall screen-u directions in room coordinates (for sphere normals) */
  private static final double[][] UDIR = { { 1, 0, 0 }, { 0, 0, 1 }, { -1, 0, 0 }, { 0, 0, -1 } };

  // ---- OnBeats grid ----------------------------------------------------------

  /**
   * Cap-grid interval in beats. A custom enum rather than Tempo.Division:
   * the {3/4, 1, 2, 4, 8} set is non-contiguous in Division's ordinal space
   * (triplet/dotted members interleave), so no Division subrange expresses it.
   */
  public enum Beats {
    THREE_QUARTER("3/4", 0.75),
    ONE("1", 1),
    TWO("2", 2),
    FOUR("4", 4),
    EIGHT("8", 8);

    public final String label;
    public final double beats;

    Beats(String label, double beats) {
      this.label = label;
      this.beats = beats;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  // ---- Parameters -----------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Pipes3D");

  public final TriggerParameter drain = bag.register(
    new TriggerParameter("Drain", this::startDrain)
    .setDescription("Fade the room out, concluding exactly on a beat (0.5-1.5 beats), then restart with the Pipes-knob count in the next palette color"));

  public final TriggerParameter teleport = bag.register(
    new TriggerParameter("Teleport", this::teleportRandomPipe)
    .setDescription("A growing pipe jumps to a random free cell and continues (the classic)"));

  public final DiscreteParameter pipes =
    new DiscreteParameter("Pipes", 1, 1, MAX_PIPES + 1)
    .setDescription("Number of concurrent pipes; raising spawns a fresh pipe, lowering culls the oldest");

  public final TriggerParameter sparkle = bag.register(
    new TriggerParameter("Sparkle", this::flashSparkle)
    .setDescription("Flash the recent elbow joints white (the bass sparkle, fired manually)"));

  public final TriggerParameter rstRot = bag.register(
    new TriggerParameter("RstRot", this::resetRotation)
    .setDescription("Zero the rotation speeds and snap back to the orthogonal projection"));

  public final CompoundParameter speed =
    (CompoundParameter) new CompoundParameter("Speed", 1, 0, 64)
    .setExponent(3) // CURATE: knob resolution concentrated in the musical low end
    .setDescription("Target growth speed in cells per beat; steers how many CapDivs each run spans (caps always land on the grid); 0 pauses growth");

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", 3.5, THICK_MIN, THICK_MAX)
    .setDescription("Pipe thickness in pixels, applied to the whole model in realtime");

  public final DiscreteParameter density =
    new DiscreteParameter("Density", 10, MIN_DENSITY, MAX_DENSITY + 1)
    .setDescription("Room grid cells across each axis; takes effect at the next drain");

  public final CompoundParameter shaded =
    new CompoundParameter("Shaded", 0.5)
    .setDescription("Phong shading amount: rounded-pipe lighting + edge antialiasing; 0 disables all shading (fast flat render)");

  public final CompoundParameter rotX =
    new CompoundParameter("RotX", 0)
    .setDescription("Lattice rotation speed about the horizontal x axis: 100% = 90 degrees per beat");

  public final CompoundParameter rotY =
    new CompoundParameter("RotY", 0)
    .setDescription("Lattice rotation speed about the vertical y axis: 100% = 90 degrees per beat");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full bass-sparkle response");

  public final EnumParameter<Beats> onBeats =
    new EnumParameter<Beats>("CapDiv", Beats.ONE)
    .setDescription("Beat division that every cap/turn lands on, phase-aligned to the global tempo grid");

  public final TriggerParameter rndTrig =
    new TriggerParameter("RndTrig", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- Preallocated state (zero-alloc render path) ---------------------------

  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  /** Per-wall color and depth buffers, cleared and fully re-rastered each frame */
  private final int[][] colorBuf = new int[WALLS][W * H];
  private final float[][] depthBuf = new float[WALLS][W * H];

  /** Occupancy at max density; only [gx][gy][gz] is used at the current density */
  private final boolean[][][] occupied = new boolean[MAX_DENSITY][MAX_DENSITY_Y][MAX_DENSITY];
  private int occupiedCount = 0;

  // Current room dimensions in cells, and cell size in pixels
  private int gx, gy, gz;
  private double cw, ch;

  // Retained geometry in lattice cell coordinates (rotated + projected each
  // frame). Hue is captured at creation so committed geometry keeps its
  // color; thickness reads the live Thick knob (whole-model realtime).
  private final double[] segAx = new double[MAX_SEGMENTS], segAy = new double[MAX_SEGMENTS], segAz = new double[MAX_SEGMENTS];
  private final double[] segBx = new double[MAX_SEGMENTS], segBy = new double[MAX_SEGMENTS], segBz = new double[MAX_SEGMENTS];
  private final float[] segHue = new float[MAX_SEGMENTS];
  private final float[] segSat = new float[MAX_SEGMENTS];
  private final float[] segBri = new float[MAX_SEGMENTS];
  private int segCount = 0;

  private final double[] ballX = new double[MAX_BALLS], ballY = new double[MAX_BALLS], ballZ = new double[MAX_BALLS];
  private final float[] ballHue = new float[MAX_BALLS];
  private final float[] ballSat = new float[MAX_BALLS];
  private final float[] ballBri = new float[MAX_BALLS];
  private int ballCount = 0;

  // Pipe state (parallel arrays, MAX_PIPES slots). Heads are continuous cell
  // coordinates (cell k's center is at k + 0.5) and move only along pDir.
  private final boolean[] pAlive = new boolean[MAX_PIPES];
  private final int[] pDir = new int[MAX_PIPES];        // 0..5, or -1 right after placement
  private final double[] pHeadX = new double[MAX_PIPES];
  private final double[] pHeadY = new double[MAX_PIPES];
  private final double[] pHeadZ = new double[MAX_PIPES];
  private final double[] pCapBeat = new double[MAX_PIPES]; // absolute composite-basis beat of the next cap
  private final double[] pGoal = new double[MAX_PIPES];    // target coordinate along the run axis
  private final int[] pSeg = new int[MAX_PIPES];           // live segment index, or -1
  private final float[] pHue = new float[MAX_PIPES];
  private final float[] pSat = new float[MAX_PIPES];
  private final float[] pBri = new float[MAX_PIPES];
  private final long[] pBirth = new long[MAX_PIPES];       // spawn order, for oldest-first culls
  private long birthCounter = 0;

  /** Previous frame's Speed value, to catch the pause->resume transition */
  private double prevRate;

  // Scratch for palette-derived pipe colors (Rubik-style fill; event-rate only)
  private final int[] pipeColors = new int[MAX_PIPES];
  private final float[] hueWork = new float[MAX_PIPES * 2];
  private final float[] hueOut = new float[MAX_PIPES];

  /** Scratch: free / in-bounds run lengths per direction */
  private final int[] freeRun = new int[6];
  private final int[] boundRun = new int[6];

  // Beat clock: composite basis is monotonic fractional beats. Caps land on
  // the GLOBAL tempo grid — absolute-beat multiples of CapDiv — so all pipes
  // (and re-loads, and drains) share one phase.
  private double lastBeats;

  // Lattice rotation state (accumulated angles) and per-frame trig
  private double axDeg = 0, ayDeg = 0;
  private double sinAx, cosAx, sinAy, cosAy;
  private double cxr, cyr, czr;

  /** This frame's live half-thicknesses (Thick applies to the whole model in realtime) */
  private double frameHalfPx, frameBallHalfPx;

  /** This frame's shading state: Shaded mix, specular gain, rig-rotated lights */
  private double frameShadeMix;
  private double frameSpecScale;
  private double rigDeg = 0;
  private final double[] sunDir = new double[3];
  private final double[][] halfVec = new double[WALLS][3];
  private final double[][] fillPos = new double[FILL_CORNERS.length][3];

  /** Scratch result of xformPoint/xformDir */
  private double tx, ty, tz;

  // Elbow sparkle ring buffer (untransformed lattice coordinates; the current
  // rotation is applied at draw time so sparkles track rotated elbows)
  private final float[] elbowX = new float[ELBOW_HISTORY];
  private final float[] elbowY = new float[ELBOW_HISTORY];
  private final float[] elbowZ = new float[ELBOW_HISTORY];
  private int elbowCount = 0, elbowNext = 0;
  private double sparkleLevel = 0;
  private int sparkleCount = 0; // how many recent caps the overlay flashes

  // Drain state
  private boolean draining = false;
  private double drainRemainMs = 0;
  private double drainTotalMs = 1;
  private int drainCount = 0; // advances the palette color index each drain

  public Pipes3D(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("drain", this.drain);
    addParameter("teleport", this.teleport);
    addParameter("pipes", this.pipes);
    addParameter("sparkle", this.sparkle);
    addParameter("rndTrig", this.rndTrig); // series convention: right after the plain triggers
    addParameter("speed", this.speed);
    addParameter("onBeats", this.onBeats); // key kept for .lxp compat; UI label is CapDiv
    addParameter("thickness", this.thickness);
    addParameter("density", this.density);
    addParameter("shaded", this.shaded);
    addParameter("rotX", this.rotX);
    addParameter("rotY", this.rotY);
    addParameter("rstRot", this.rstRot);
    addParameter("audio", this.audioDepth);

    bag.jumpable(this.thickness);
    bag.jumpable(this.density);
    // Spawn/cull as an ambient event. CURATE: do uncommanded culls read well?
    bag.jumpable(this.pipes);
    // Never fully disables shading (0 = flat render). CURATE
    bag.jumpable(this.shaded, 0.25, 1);
    // Musical mid-band only (0.5..4): excludes 0 — RndTrig must not silently
    // freeze the pattern — and the blazing top of the knob. CURATE
    bag.jumpable(this.speed, 0.5, 4);
    // Exclude 3/4 (a random polyrhythm jump reads as a timing bug) and 8
    // (caps stall for many seconds). CURATE: unverified visually
    bag.jumpable(this.onBeats, Beats.ONE.ordinal(), Beats.FOUR.ordinal());
    // Slow ambient drift only (<= 22.5 deg/beat); a jump to full rate is
    // disorienting. RstRot is registered too, so RndTrig can snap back. CURATE
    bag.jumpable(this.rotX, 0, 0.25);
    bag.jumpable(this.rotY, 0, 0.25);

    this.lastBeats = lx.engine.tempo.getCompositeBasis();
    this.prevRate = this.speed.getValue();
    applyDensity();
    clearRoom();
    syncPipeCount();
  }

  @Override
  protected void onActive() {
    super.onActive();
    // The composite basis kept running while inactive: rebase the frame
    // clock. Live pipes' pCapBeat values are now in the past, so each snaps
    // to its goal and replans on the global grid on the first frame.
    this.lastBeats = this.lx.engine.tempo.getCompositeBasis();
  }

  // ---- Room / lifecycle -------------------------------------------------------

  /** Read the density parameter into grid dimensions. Only at construction/drain. */
  private void applyDensity() {
    this.gx = this.density.getValuei();
    this.gz = this.gx;
    this.gy = Math.round(this.gx * (float) H / W); // keeps cells ~square (10 -> 9)
    this.cw = (double) W / this.gx;
    this.ch = (double) H / this.gy;
  }

  /** Clear geometry, occupancy and sparkle state. Event-rate only (construction / drain end). */
  private void clearRoom() {
    for (boolean[][] plane : this.occupied) {
      for (boolean[] row : plane) {
        Arrays.fill(row, false);
      }
    }
    this.occupiedCount = 0;
    this.segCount = 0;
    this.ballCount = 0;
    this.elbowCount = 0;
    this.elbowNext = 0;
    this.sparkleLevel = 0;
    this.sparkleCount = 0;
  }

  private boolean inBounds(int x, int y, int z) {
    return x >= 0 && x < this.gx && y >= 0 && y < this.gy && z >= 0 && z < this.gz;
  }

  private boolean hasFreeNeighbor(int x, int y, int z) {
    for (int d = 0; d < 6; ++d) {
      int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
      if (inBounds(nx, ny, nz) && !this.occupied[nx][ny][nz]) {
        return true;
      }
    }
    return false;
  }

  private void occupy(int x, int y, int z) {
    if (!this.occupied[x][y][z]) {
      this.occupied[x][y][z] = true;
      ++this.occupiedCount;
    }
  }

  /**
   * Pipe color at run start, straight from the project palette (series house
   * rules: full H/S/B from the swatch, no hue knob). With enough swatch
   * entries, pipe i takes slot (drainCount + i) so each drain advances the
   * rotation and concurrent pipes get distinct entries. A short swatch keeps
   * its defined colors and fills the remainder with perceptually-even
   * fully-saturated hues (the Rubik computeFaceColors template); an empty
   * swatch degenerates to an even perceptual spread. Event-rate only.
   */
  private int pipeColor(int i) {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = swatch.size();
    if (n >= MAX_PIPES) {
      return swatch.get((this.drainCount + i) % n).getColor();
    }
    for (int d = 0; d < n; ++d) {
      final int c = swatch.get(d).getColor();
      this.pipeColors[d] = c;
      this.hueWork[d] = PerceptualHue.toPerceptualPosition(LXColor.h(c));
    }
    PerceptualHue.fillCircle(this.hueWork, n, MAX_PIPES - n, this.hueOut);
    for (int j = 0; j < MAX_PIPES - n; ++j) {
      this.pipeColors[n + j] = PerceptualHue.color(this.hueOut[j]);
    }
    return this.pipeColors[(this.drainCount + i) % MAX_PIPES];
  }

  /** Capture a full palette color into pipe i's H/S/B slots */
  private void assignPipeColor(int i) {
    final int c = pipeColor(i);
    this.pHue[i] = LXColor.h(c);
    this.pSat[i] = LXColor.s(c);
    this.pBri[i] = LXColor.b(c);
  }

  // ---- Beat planning ------------------------------------------------------------

  /** Cap-grid interval in beats */
  private double gridInterval() {
    return this.onBeats.getEnum().beats;
  }

  /**
   * First global grid point strictly after beat b (grid = absolute-beat
   * multiples of CapDiv, phase-aligned to the transport). The epsilon means
   * "if b sits exactly on a grid point, return the next one".
   */
  private double nextGrid(double b) {
    final double g = gridInterval();
    return (Math.floor(b / g + 1e-6) + 1) * g;
  }

  /**
   * Schedule pipe i's next cap: dist cells remain to its target corner, and
   * the cap must land on the global CapDiv grid (the Mystify idiom — the cap
   * time is a hard absolute-beat target; velocity is derived from it each
   * frame, so BPM and knob changes never push a cap off the grid). The Speed
   * knob steers WHICH grid point: the one nearest the ideal arrival at the
   * target speed, never sooner than half a division out — so slow speeds span
   * many divisions, fast speeds cap every division, and congestion (short
   * dist) degrades speed rather than grid alignment. Escape hatch: when even
   * one whole division is far too slow for the knob, cap on a half-division
   * (it re-aligns to the whole grid at the next plan). From off-grid starts
   * (spawn, teleport, pause-resume, clock jumps) the first cap lands 0.5-1.5
   * divisions out. Speed 0 parks the cap at infinity until resume.
   */
  private void scheduleCap(int i, double now, double dist) {
    final double s = this.speed.getValue();
    if (s <= 0) {
      this.pCapBeat[i] = Double.POSITIVE_INFINITY;
      return;
    }
    final double g = gridInterval();
    final double tIdeal = dist / s;
    // First grid point at least half a division out
    final double firstAllowed = nextGrid(now + 0.5 * g - g * 1e-6);
    double capBeat = Math.max(firstAllowed, Math.rint((now + tIdeal) / g) * g);
    // CURATE: half-division escape thresholds
    if ((tIdeal <= 0.75 * g) && (capBeat - now >= 2 * tIdeal)) {
      capBeat = now + 0.5 * g;
    }
    this.pCapBeat[i] = capBeat;
  }

  private static int clampi(int v, int lo, int hi) {
    return (v < lo) ? lo : (v > hi) ? hi : v;
  }

  private static double clamp(double v, double lo, double hi) {
    return (v < lo) ? lo : (v > hi) ? hi : v;
  }

  /**
   * Plan the next run for pipe i, starting at the current (continuous) head:
   * pick a direction and an integer cell count that fit the room, then
   * schedule the cap on the global CapDiv grid (scheduleCap). Called at every
   * cap, at spawn, and after a teleport landing.
   */
  private void planRun(int i) {
    final double now = this.lastBeats;

    // Anchor: nearest in-bounds cell to the head. The head is clamped inside
    // the room during integration, so per-axis residuals are <= 0.5 cell;
    // an along-axis residual is absorbed into this run's distance, off-axis
    // residuals heal when a later run travels that axis.
    final int ax = clampi((int) Math.round(this.pHeadX[i] - 0.5), 0, this.gx - 1);
    final int ay = clampi((int) Math.round(this.pHeadY[i] - 0.5), 0, this.gy - 1);
    final int az = clampi((int) Math.round(this.pHeadZ[i] - 0.5), 0, this.gz - 1);
    occupy(ax, ay, az);

    // Per direction: consecutive free in-bounds cells (freeRun) and
    // consecutive in-bounds cells ignoring occupancy (boundRun)
    for (int d = 0; d < 6; ++d) {
      int x = ax, y = ay, z = az;
      int f = 0, b = 0;
      boolean blocked = false;
      while (true) {
        x += DX[d]; y += DY[d]; z += DZ[d];
        if (!inBounds(x, y, z)) {
          break;
        }
        ++b;
        if (!blocked && !this.occupied[x][y][z]) {
          ++f;
        } else {
          blocked = true;
        }
      }
      this.freeRun[d] = f;
      this.boundRun[d] = b;
    }

    // Direction: prefer straight, else weighted-random by free run length
    // (space-filling bias toward open directions). With no free direction at
    // all, intersect existing pipes rather than teleporting away — weighted
    // by in-bounds run length, excluding an immediate reversal when possible.
    final int straight = this.pDir[i];
    int dir = -1;
    boolean intersecting = false;
    if ((straight >= 0) && (this.freeRun[straight] >= 1) && (this.random.nextDouble() < P_STRAIGHT)) {
      dir = straight;
    } else {
      int total = 0;
      for (int d = 0; d < 6; ++d) {
        total += this.freeRun[d];
      }
      if (total > 0) {
        int r = this.random.nextInt(total);
        for (int d = 0; d < 6; ++d) {
          r -= this.freeRun[d];
          if (r < 0) {
            dir = d;
            break;
          }
        }
      } else {
        intersecting = true;
        final int opp = (straight >= 0) ? (straight ^ 1) : -1;
        total = 0;
        for (int d = 0; d < 6; ++d) {
          if (d != opp) {
            total += this.boundRun[d];
          }
        }
        if (total > 0) {
          int r = this.random.nextInt(total);
          for (int d = 0; d < 6; ++d) {
            if (d == opp) {
              continue;
            }
            r -= this.boundRun[d];
            if (r < 0) {
              dir = d;
              break;
            }
          }
        } else if ((opp >= 0) && (this.boundRun[opp] > 0)) {
          dir = opp;
        }
      }
    }
    if (dir < 0) {
      // Defensively unreachable (the anchor is in bounds in a >= 6-cell room)
      teleportPipe(i);
      return;
    }

    // Run length: uniform over 1..maxRun. Direction choice is weighted by
    // run length and the length pick is uniform within it, so every free
    // candidate cell is an equally likely elbow target — elbows distribute
    // through the room volume instead of clustering at the walls (the old
    // max-of-two long bias compounded with the direction weighting).
    final int maxRun = intersecting ? this.boundRun[dir] : this.freeRun[dir];
    final int n = 1 + this.random.nextInt(maxRun);

    // Geometry bookkeeping: continuing straight extends the live segment;
    // a turn caps here (elbow ball at the head, wherever it is) and opens a
    // fresh segment. pDir < 0 means just placed: the spawn ball already
    // marks the start, no elbow.
    if ((dir != straight) || (this.pSeg[i] < 0)) {
      if (straight >= 0) {
        addBall(this.pHeadX[i], this.pHeadY[i], this.pHeadZ[i], this.pHue[i], this.pSat[i], this.pBri[i]);
        recordElbow((float) this.pHeadX[i], (float) this.pHeadY[i], (float) this.pHeadZ[i]);
      }
      if (this.segCount >= MAX_SEGMENTS) {
        LX.log("Pipes3D: segment list full; draining");
        startDrain();
        return;
      }
      final int s = this.segCount++;
      this.segAx[s] = this.segBx[s] = this.pHeadX[i];
      this.segAy[s] = this.segBy[s] = this.pHeadY[i];
      this.segAz[s] = this.segBz[s] = this.pHeadZ[i];
      assignPipeColor(i);
      this.segHue[s] = this.pHue[i];
      this.segSat[s] = this.pSat[i];
      this.segBri[s] = this.pBri[i];
      this.pSeg[i] = s;
    }

    // Cap scheduling: the run covers dist cells (target = the corner n cells
    // from the anchor along dir; only the run-axis coordinate is targeted);
    // scheduleCap() picks the global grid point nearest the ideal arrival at
    // the Speed knob (hard target; velocity is derived from it per frame).
    double dist;
    if (DX[dir] != 0) {
      this.pGoal[i] = ax + 0.5 + DX[dir] * n;
      dist = Math.abs(this.pGoal[i] - this.pHeadX[i]);
    } else if (DY[dir] != 0) {
      this.pGoal[i] = ay + 0.5 + DY[dir] * n;
      dist = Math.abs(this.pGoal[i] - this.pHeadY[i]);
    } else {
      this.pGoal[i] = az + 0.5 + DZ[dir] * n;
      dist = Math.abs(this.pGoal[i] - this.pHeadZ[i]);
    }
    this.pDir[i] = dir;
    scheduleCap(i, now, dist);

    // Occupy the swept cells (idempotent; intersecting sweeps add only the
    // newly free ones), then check the auto-drain fill limit
    for (int s = 1; s <= n; ++s) {
      occupy(ax + DX[dir] * s, ay + DY[dir] * s, az + DZ[dir] * s);
    }
    if (this.occupiedCount > FILL_LIMIT * this.gx * this.gy * this.gz) {
      startDrain();
    }
  }

  // ---- Pipe lifecycle -----------------------------------------------------------

  /** Place pipe i at a fresh free cell: cap ball, then plan its first run. */
  private void placePipe(int i, int x, int y, int z) {
    occupy(x, y, z);
    this.pAlive[i] = true;
    this.pHeadX[i] = x + 0.5;
    this.pHeadY[i] = y + 0.5;
    this.pHeadZ[i] = z + 0.5;
    this.pDir[i] = -1;
    this.pSeg[i] = -1;
    assignPipeColor(i);
    addBall(this.pHeadX[i], this.pHeadY[i], this.pHeadZ[i], this.pHue[i], this.pSat[i], this.pBri[i]);
    planRun(i);
  }

  /** Random free cell that has at least one free neighbor, into rlX/rlY/rlZ. */
  private int rlX, rlY, rlZ;
  private boolean findFreeCell() {
    for (int t = 0; t < RELOCATE_TRIES; ++t) {
      int x = this.random.nextInt(this.gx);
      int y = this.random.nextInt(this.gy);
      int z = this.random.nextInt(this.gz);
      if (!this.occupied[x][y][z] && hasFreeNeighbor(x, y, z)) {
        this.rlX = x; this.rlY = y; this.rlZ = z;
        return true;
      }
    }
    return false;
  }

  private void spawnPipe() {
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (!this.pAlive[i]) {
        if (findFreeCell()) {
          // Fresh birth (teleports keep their original stamp — a teleported
          // pipe is still the same, old pipe for oldest-first culls)
          this.pBirth[i] = this.birthCounter++;
          placePipe(i, this.rlX, this.rlY, this.rlZ);
        } else {
          LX.log("Pipes3D: no free cell to spawn a pipe; draining");
          startDrain();
        }
        return;
      }
    }
  }

  /**
   * Reconcile the number of live pipes to the Pipes knob: spawn fresh pipes
   * when below, cull the oldest when above (capped with a ball wherever its
   * head stopped; its geometry stays). Idempotent — safe from the parameter
   * callback, construction, and drain end.
   */
  private void syncPipeCount() {
    final int target = this.pipes.getValuei();
    int alive = 0;
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (this.pAlive[i]) {
        ++alive;
      }
    }
    while (!this.draining && (alive < target)) {
      spawnPipe(); // fills exactly one slot, or starts a drain when the room is full
      ++alive;
    }
    while (alive > target) {
      int oldest = -1;
      for (int i = 0; i < MAX_PIPES; ++i) {
        if (this.pAlive[i] && ((oldest < 0) || (this.pBirth[i] < this.pBirth[oldest]))) {
          oldest = i;
        }
      }
      addBall(this.pHeadX[oldest], this.pHeadY[oldest], this.pHeadZ[oldest],
        this.pHue[oldest], this.pSat[oldest], this.pBri[oldest]);
      this.pAlive[oldest] = false;
      --alive;
    }
  }

  @Override
  public void onParameterChanged(LXParameter parameter) {
    super.onParameterChanged(parameter);
    if ((parameter == this.pipes) && !this.draining) {
      // While draining, pipes are already dead; finishDrain() reconciles
      syncPipeCount();
    }
  }

  // Trigger: manually fire the elbow sparkle flash at full brightness, all caps
  private void flashSparkle() {
    this.sparkleLevel = 1;
    this.sparkleCount = ELBOW_HISTORY;
  }

  // Trigger: zero the rotation speeds and snap the projection orthogonal
  private void resetRotation() {
    this.rotX.setValue(0);
    this.rotY.setValue(0);
    this.axDeg = 0;
    this.ayDeg = 0;
  }

  // Trigger: one random growing pipe jumps to a random free cell and continues
  private void teleportRandomPipe() {
    if (this.draining) {
      return;
    }
    int alive = 0;
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (this.pAlive[i]) {
        ++alive;
      }
    }
    if (alive == 0) {
      return;
    }
    int pick = this.random.nextInt(alive);
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (this.pAlive[i] && (pick-- == 0)) {
        teleportPipe(i);
        return;
      }
    }
  }

  /** Cap the pipe where it is, then continue from a random free cell (classic). */
  private void teleportPipe(int i) {
    // Cap ball at the disconnect point (the continuous head — off-corner
    // positions after a speed change are capped as-is)
    addBall(this.pHeadX[i], this.pHeadY[i], this.pHeadZ[i], this.pHue[i], this.pSat[i], this.pBri[i]);
    if (findFreeCell()) {
      placePipe(i, this.rlX, this.rlY, this.rlZ);
    } else {
      startDrain();
    }
  }

  // Trigger + auto: fade everything out, concluding exactly on a beat, then
  // restart with one pipe in the next palette color
  private void startDrain() {
    if (!this.draining) {
      this.draining = true;
      final double periodMs = this.lx.engine.tempo.period.getValue();
      double ms = this.tempoLock.msUntilNext(Tempo.Division.QUARTER);
      if (ms < 0.5 * periodMs) {
        ms += periodMs; // never shorter than half a beat: target the beat after
      }
      this.drainTotalMs = this.drainRemainMs = ms;
      LX.log("Pipes3D: drain started (fill " + this.occupiedCount + " cells, "
        + Math.round(ms) + " ms to the beat)");
    }
  }

  /**
   * Drain finished (on a beat): fresh room at the (possibly jumped) density,
   * next palette color, the Pipes-knob count of pipes (the knob never moves
   * on its own). Caps stay phase-aligned to the global tempo grid — a drain
   * does not reset cap phase.
   */
  private void finishDrain() {
    this.draining = false;
    ++this.drainCount;
    for (int i = 0; i < MAX_PIPES; ++i) {
      this.pAlive[i] = false;
    }
    applyDensity(); // density jumps take effect here
    clearRoom();
    syncPipeCount();
    LX.log("Pipes3D: drain complete; density " + this.gx + "x" + this.gy + "x" + this.gz);
  }

  // ---- Growth ---------------------------------------------------------------------

  /**
   * Move every live pipe's head toward its target with DERIVED velocity (the
   * Mystify idiom): each frame the head covers the same fraction of its
   * remaining distance as the frame covers of the remaining time to the cap
   * beat — exact arrival on the grid, robust to live BPM changes. The Speed
   * knob does not move in-flight heads (it steers the next plan); caps are
   * hard absolute-beat targets.
   */
  private void updatePipes(double now, double dBeats) {
    if (this.speed.getValue() <= 0) {
      return; // paused: heads and caps freeze (rotation/sparkle/drain unaffected)
    }
    final double bPrev = now - dBeats;
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (!this.pAlive[i] || (this.pDir[i] < 0)) {
        continue;
      }
      if (now >= this.pCapBeat[i]) {
        // Cap: snap exactly to the target corner and plan the next run
        final int d = this.pDir[i];
        if (DX[d] != 0) {
          this.pHeadX[i] = this.pGoal[i];
        } else if (DY[d] != 0) {
          this.pHeadY[i] = this.pGoal[i];
        } else {
          this.pHeadZ[i] = this.pGoal[i];
        }
        final int sSnap = this.pSeg[i];
        if (sSnap >= 0) {
          this.segBx[sSnap] = this.pHeadX[i];
          this.segBy[sSnap] = this.pHeadY[i];
          this.segBz[sSnap] = this.pHeadZ[i];
        }
        planRun(i); // may start the drain (fill limit / list overflow)
        if (this.draining) {
          return;
        }
        continue; // the fresh run starts integrating next frame
      }
      final double denom = this.pCapBeat[i] - bPrev;
      final double dt = now - bPrev;
      if ((denom > 1e-9) && (dt > 0) && Double.isFinite(denom)) {
        final double f = Math.min(1, dt / denom);
        final int d = this.pDir[i];
        if (DX[d] != 0) {
          this.pHeadX[i] += (this.pGoal[i] - this.pHeadX[i]) * f;
        } else if (DY[d] != 0) {
          this.pHeadY[i] += (this.pGoal[i] - this.pHeadY[i]) * f;
        } else {
          this.pHeadZ[i] += (this.pGoal[i] - this.pHeadZ[i]) * f;
        }
        final int s = this.pSeg[i];
        if (s >= 0) {
          this.segBx[s] = this.pHeadX[i];
          this.segBy[s] = this.pHeadY[i];
          this.segBz[s] = this.pHeadZ[i];
        }
      }
    }
  }

  /**
   * Re-anchor stale cap schedules to the global grid: used on pause-resume
   * (caps parked at infinity) and on backward transport jumps (the grid
   * phase moved under the schedules). Each live pipe re-schedules from the
   * distance its head still has to cover — landing 0.5-1.5 divisions out.
   */
  private void realignCaps(double now) {
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (!this.pAlive[i] || (this.pDir[i] < 0)) {
        continue;
      }
      final int d = this.pDir[i];
      final double head = (DX[d] != 0) ? this.pHeadX[i]
        : (DY[d] != 0) ? this.pHeadY[i] : this.pHeadZ[i];
      scheduleCap(i, now, Math.abs(this.pGoal[i] - head));
    }
  }

  private void recordElbow(float x, float y, float z) {
    this.elbowX[this.elbowNext] = x;
    this.elbowY[this.elbowNext] = y;
    this.elbowZ[this.elbowNext] = z;
    this.elbowNext = (this.elbowNext + 1) % ELBOW_HISTORY;
    this.elbowCount = Math.min(this.elbowCount + 1, ELBOW_HISTORY);
  }

  /** Append a joint/cap ball at a (continuous) lattice position */
  private void addBall(double x, double y, double z, float hueDeg, float satPct, float briPct) {
    if (this.ballCount >= MAX_BALLS) {
      LX.log("Pipes3D: ball list full; draining");
      startDrain();
      return;
    }
    final int b = this.ballCount++;
    this.ballX[b] = x;
    this.ballY[b] = y;
    this.ballZ[b] = z;
    this.ballHue[b] = hueDeg;
    this.ballSat[b] = satPct;
    this.ballBri[b] = briPct;
  }

  // ---- Rasterization -------------------------------------------------------------
  //
  // The lattice is rotated about the room center (RotX pitch, then RotY yaw)
  // into room/world coordinates, then each wall projects orthographically
  // (corner-continuous, so all four walls show one shared object):
  //   wall 0 front: u = x            v = y   depth = z
  //   wall 1 right: u = z            v = y   depth = gx - x
  //   wall 2 back:  u = gx - x       v = y   depth = gz - z
  //   wall 3 left:  u = gz - z       v = y   depth = x
  // At zero angles this reproduces the classic static axis-select projection
  // exactly. Cell coordinates are continuous (cell k spans [k, k+1]); u,v
  // scale by cw,ch into the 50x45 pixel grid. Depth normalizes by gz (== gx)
  // and clamps — rotated content may stick out of the viewport/depth range,
  // which simply clips.

  /** Current pipe thickness in px (unclamped: 6 px at high density deliberately merges cells) */
  private double thicknessPx() {
    return this.thickness.getValue();
  }

  /** Rotate a lattice point about the room center into world coordinates (tx/ty/tz) */
  private void xformPoint(double x, double y, double z) {
    final double x0 = x - this.cxr, y0 = y - this.cyr, z0 = z - this.czr;
    final double y1 = this.cosAx * y0 - this.sinAx * z0;
    final double z1 = this.sinAx * y0 + this.cosAx * z0;
    this.tx = this.cosAy * x0 + this.sinAy * z1 + this.cxr;
    this.ty = y1 + this.cyr;
    this.tz = -this.sinAy * x0 + this.cosAy * z1 + this.czr;
  }

  private double wallU(int w, double x, double z) {
    switch (w) {
      case 0: return x * this.cw;
      case 1: return z * this.cw;
      case 2: return (this.gx - x) * this.cw;
      default: return (this.gz - z) * this.cw;
    }
  }

  private double wallDepth(int w, double x, double z) {
    switch (w) {
      case 0: return z;
      case 1: return this.gx - x;
      case 2: return this.gz - z;
      default: return x;
    }
  }

  /** Screen-u component of a world direction on wall w (in px per cell) */
  private double wallMu(int w, double mx, double mz) {
    switch (w) {
      case 0: return mx * this.cw;
      case 1: return mz * this.cw;
      case 2: return -mx * this.cw;
      default: return -mz * this.cw;
    }
  }

  private void clearWallBuffers() {
    for (int w = 0; w < WALLS; ++w) {
      Arrays.fill(this.colorBuf[w], LXColor.BLACK);
      Arrays.fill(this.depthBuf[w], Float.MAX_VALUE);
    }
  }

  /**
   * Rasterize one retained segment into all four wall buffers: a thick 2D
   * line stepped ~1 px along its screen length with a perpendicular span of
   * ~thickness px, depth-tested and depth-shaded per step. The sun-specular
   * stripe (world-fixed light, so highlights slide around pipes as the
   * lattice rotates) is written in a second pass so the span sweep cannot
   * clobber it at equal depth.
   */
  private void rasterSegment(int s) {
    xformPoint(this.segAx[s], this.segAy[s], this.segAz[s]);
    final double wax = this.tx, way = this.ty, waz = this.tz;
    xformPoint(this.segBx[s], this.segBy[s], this.segBz[s]);
    final double wbx = this.tx, wby = this.ty, wbz = this.tz;

    final double ddx = wbx - wax, ddy = wby - way, ddz = wbz - waz;
    final double len3 = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
    if (len3 < 1e-6) {
      return; // degenerate (just-planned) segment; its start ball covers it
    }
    final double axn = ddx / len3, ayn = ddy / len3, azn = ddz / len3;

    final float hueDeg = this.segHue[s];
    final float satPct = this.segSat[s];
    final float briPct = this.segBri[s];
    final double halfPx = this.frameHalfPx;
    final int span = Math.max(1, (int) Math.round(2 * halfPx));

    for (int w = 0; w < WALLS; ++w) {
      final double u0 = wallU(w, wax, waz), u1 = wallU(w, wbx, wbz);
      final double v0 = way * this.ch, v1 = wby * this.ch;
      final float dn0 = (float) clamp(wallDepth(w, wax, waz) / this.gz, 0, 1);
      final float dn1 = (float) clamp(wallDepth(w, wbx, wbz) / this.gz, 0, 1);
      final double du = u1 - u0, dv = v1 - v0;
      final double screenLen = Math.sqrt(du * du + dv * dv);

      final int[] cBuf = this.colorBuf[w];
      final float[] dBuf = this.depthBuf[w];

      if (screenLen < 0.5) {
        // Seen end-on: a circular cross-section, sphere-shaded like a joint
        stampDisc(cBuf, dBuf, w, (u0 + u1) / 2, (v0 + v1) / 2, halfPx,
          Math.min(dn0, dn1), (wax + wbx) / 2, (way + wby) / 2, (waz + wbz) / 2,
          hueDeg, satPct, briPct, 1f);
        continue;
      }

      final boolean shading = this.frameShadeMix > 0;

      // Cylinder shading frame for this wall: M̂ = cross-pipe direction on
      // screen, V̂′ = view-facing normal component (both unit); the surface
      // normal at cross-pipe offset t in [-1, 1] is N(t) = t·M̂ + √(1−t²)·V̂′,
      // so every light dot reduces to a·t + b·√(1−t²) with precomputed a, b.
      double mhx = 0, mhy = 0, mhz = 0, vpx = 0, vpy = 0, vpz = 0;
      double aSun = 0, bSun = 0, aH = 0, bH = 0;
      boolean lit = false;
      if (shading) {
        final double[] vw = VIEW[w];
        final double dotVA = vw[0] * axn + vw[1] * ayn + vw[2] * azn;
        final double ppx = vw[0] - dotVA * axn;
        final double ppy = vw[1] - dotVA * ayn;
        final double ppz = vw[2] - dotVA * azn;
        final double plen = Math.sqrt(ppx * ppx + ppy * ppy + ppz * ppz);
        if (plen > 1e-4) { // not end-on
          lit = true;
          vpx = ppx / plen; vpy = ppy / plen; vpz = ppz / plen;
          mhx = (vw[1] * azn - vw[2] * ayn) / plen;
          mhy = (vw[2] * axn - vw[0] * azn) / plen;
          mhz = (vw[0] * ayn - vw[1] * axn) / plen;
          // Orient M̂ so +t points along the screen minor axis (+m2)
          final double mu = wallMu(w, mhx, mhz), mv = mhy * this.ch;
          if (mu * (-dv) + mv * du < 0) {
            mhx = -mhx; mhy = -mhy; mhz = -mhz;
          }
          final double[] hw = this.halfVec[w];
          aSun = mhx * this.sunDir[0] + mhy * this.sunDir[1] + mhz * this.sunDir[2];
          bSun = vpx * this.sunDir[0] + vpy * this.sunDir[1] + vpz * this.sunDir[2];
          aH = mhx * hw[0] + mhy * hw[1] + mhz * hw[2];
          bH = vpx * hw[0] + vpy * hw[1] + vpz * hw[2];
        }
      }

      final int steps = Math.max(1, (int) Math.ceil(screenLen));
      final double m2u = -dv / screenLen, m2v = du / screenLen;
      // Shading widens the span one pixel each side for the soft AA rim
      final int spanPix = shading ? span + 2 : span;
      final double tNorm = 1 / Math.max(halfPx, 0.5);

      for (int k = 0; k <= steps; ++k) {
        final double f = (double) k / steps;
        final double cu = u0 + du * f, cv = v0 + dv * f;
        final float dnk = dn0 + (dn1 - dn0) * (float) f;
        final float bf = 1 - DEPTH_DIM * dnk;

        if (!shading) {
          // Flat fast path: exactly the pre-shading render (Shaded = 0)
          final int colMain = LXColor.hsb(hueDeg, satPct, briPct * bf);
          for (int j = 0; j < spanPix; ++j) {
            final double o = j - (spanPix - 1) * 0.5;
            final int pu = (int) Math.floor(cu + m2u * o);
            final int pv = (int) Math.floor(cv + m2v * o);
            if (pu < 0 || pu >= W || pv < 0 || pv >= H) {
              continue;
            }
            final int idx = pv * W + pu;
            if (dnk <= dBuf[idx]) {
              dBuf[idx] = dnk;
              cBuf[idx] = colMain;
            }
          }
          continue;
        }

        // Fill-light dot pairs at this step's centerline point (the fills
        // are point sources: direction varies along the pipe)
        double aF1 = 0, bF1 = 0, aF2 = 0, bF2 = 0;
        if (lit) {
          final double px = wax + ddx * f, py = way + ddy * f, pz = waz + ddz * f;
          double lx0 = this.fillPos[0][0] - px, ly0 = this.fillPos[0][1] - py, lz0 = this.fillPos[0][2] - pz;
          double ll = Math.sqrt(lx0 * lx0 + ly0 * ly0 + lz0 * lz0);
          if (ll > 1e-9) { lx0 /= ll; ly0 /= ll; lz0 /= ll; }
          aF1 = mhx * lx0 + mhy * ly0 + mhz * lz0;
          bF1 = vpx * lx0 + vpy * ly0 + vpz * lz0;
          double lx1 = this.fillPos[1][0] - px, ly1 = this.fillPos[1][1] - py, lz1 = this.fillPos[1][2] - pz;
          ll = Math.sqrt(lx1 * lx1 + ly1 * ly1 + lz1 * lz1);
          if (ll > 1e-9) { lx1 /= ll; ly1 /= ll; lz1 /= ll; }
          aF2 = mhx * lx1 + mhy * ly1 + mhz * lz1;
          bF2 = vpx * lx1 + vpy * ly1 + vpz * lz1;
        }

        for (int j = 0; j < spanPix; ++j) {
          final double o = j - (spanPix - 1) * 0.5;
          final double cov = clamp(halfPx + 0.5 - Math.abs(o), 0, 1);
          if (cov <= 0) {
            continue;
          }
          final int pu = (int) Math.floor(cu + m2u * o);
          final int pv = (int) Math.floor(cv + m2v * o);
          if (pu < 0 || pu >= W || pv < 0 || pv >= H) {
            continue;
          }
          double diff, spec;
          if (lit) {
            final double t = clamp(o * tNorm, -1, 1);
            final double sN = Math.sqrt(Math.max(0, 1 - t * t));
            diff = KD_SUN * Math.max(0, aSun * t + bSun * sN)
                 + KD_FILL * (Math.max(0, aF1 * t + bF1 * sN) + Math.max(0, aF2 * t + bF2 * sN));
            final double nh = Math.max(0, aH * t + bH * sN);
            final double nh2 = nh * nh;
            // Exponent 4 (broad lobe). CURATE: exponent/gain
            spec = Math.min(1, nh2 * nh2 * this.frameSpecScale);
          } else {
            diff = KD_SUN + KD_FILL; // degenerate frame: neutral fully-lit
            spec = 0;
          }
          compositePixel(cBuf, dBuf, pv * W + pu, dnk, cov, hueDeg, satPct, briPct, bf, diff, spec);
        }
      }
    }
  }

  /**
   * Composite one shaded pixel: Phong (ambient + diffuse) blended against
   * the flat color by the Shaded mix, specular pushed toward white
   * (brightness up, saturation toward the glossy floor), edge coverage
   * folded into brightness (the antialiasing). Full-coverage pixels
   * depth-test and write; partial-coverage rim pixels blend with lightest()
   * and leave the depth buffer alone, so soft rims can't occlude.
   */
  private void compositePixel(int[] cBuf, float[] dBuf, int idx, float dn, double cov,
                              float hueDeg, float satPct, float briBase, float bf,
                              double diff, double spec) {
    final double mix = this.frameShadeMix;
    final double phong = Math.min(1, AMBIENT + diff);
    final double shadeF = (1 - mix) + mix * phong;
    double bri = briBase * bf * shadeF;
    float sat = satPct;
    if (spec > 0) {
      bri += spec * (100 - bri);
      sat = (float) (satPct - (satPct - Math.min(satPct, SPEC_SAT_FLOOR)) * spec);
    }
    final int col = LXColor.hsb(hueDeg, sat, (float) (bri * cov));
    if (cov >= 1) {
      if (dn <= dBuf[idx]) {
        dBuf[idx] = dn;
        cBuf[idx] = col;
      }
    } else if (dn <= dBuf[idx]) {
      cBuf[idx] = LXColor.lightest(cBuf[idx], col);
    }
  }

  /** Rasterize one joint/cap ball: a sphere-shaded disc per wall */
  private void rasterBall(int b) {
    xformPoint(this.ballX[b], this.ballY[b], this.ballZ[b]);
    final double wx = this.tx, wy = this.ty, wz = this.tz;
    final double half = this.frameBallHalfPx;
    final float sat = Math.min(this.ballSat[b], BALL_SAT); // glossy: never more saturated than the joint cap
    for (int w = 0; w < WALLS; ++w) {
      final float dn = (float) clamp(wallDepth(w, wx, wz) / this.gz, 0, 1);
      stampDisc(this.colorBuf[w], this.depthBuf[w], w,
        wallU(w, wx, wz), wy * this.ch, half, dn, wx, wy, wz,
        this.ballHue[b], sat, this.ballBri[b], BALL_BOOST);
    }
  }

  /**
   * Depth-tested disc stamp centered at (cu, cv) px with radius ~half px —
   * the orthographic projection of a sphere (joints) or a pipe's circular
   * cross-section (end-on segments), correct under rotation. With shading
   * on, per-pixel sphere normals get the full Phong treatment plus the AA
   * rim; at Shaded = 0 it is the flat disc of the pre-shading render.
   * (wx, wy, wz) is the world-space center, used for the fill directions.
   */
  private void stampDisc(int[] cBuf, float[] dBuf, int w, double cu, double cv,
                         double half, float dn, double wx, double wy, double wz,
                         float hueDeg, float sat, float bri, float boost) {
    final boolean shading = this.frameShadeMix > 0;
    final double pad = shading ? 1 : 0;
    final int iu0 = Math.max(0, (int) Math.floor(cu - half - pad));
    final int iu1 = Math.min(W - 1, (int) Math.ceil(cu + half + pad) - 1);
    final int iv0 = Math.max(0, (int) Math.floor(cv - half - pad));
    final int iv1 = Math.min(H - 1, (int) Math.ceil(cv + half + pad) - 1);
    if (iu1 < iu0 || iv1 < iv0) {
      return;
    }
    // +0.25 px so small radii don't decimate to a plus sign
    final double r = half + 0.25;
    final float bf = 1 - DEPTH_DIM * dn;
    final float briBase = Math.min(100f, bri * boost);

    if (!shading) {
      final double r2 = r * r;
      final int col = LXColor.hsb(hueDeg, sat, Math.min(100f, bri * bf * boost));
      for (int v = iv0; v <= iv1; ++v) {
        final int rowBase = v * W;
        final double dy = v + 0.5 - cv;
        for (int u = iu0; u <= iu1; ++u) {
          final double dx = u + 0.5 - cu;
          if (dx * dx + dy * dy > r2) {
            continue;
          }
          final int idx = rowBase + u;
          if (dn <= dBuf[idx]) {
            dBuf[idx] = dn;
            cBuf[idx] = col;
          }
        }
      }
      return;
    }

    // Sphere shading: normal N = ρu·Û + ρv·Ŷ + c·V̂ in room coordinates
    // (Û = the wall's screen-u direction, Ŷ = screen-down = room +y, V̂ =
    // toward the viewer; c = √(1−ρ²)). Each light dot reduces to
    // ρu·LU + ρv·LY + c·LV with the three components precomputed per stamp.
    final double[] ud = UDIR[w];
    final double[] vw = VIEW[w];
    final double[] hw = this.halfVec[w];
    final double sunU = this.sunDir[0] * ud[0] + this.sunDir[1] * ud[1] + this.sunDir[2] * ud[2];
    final double sunY = this.sunDir[1];
    final double sunV = this.sunDir[0] * vw[0] + this.sunDir[1] * vw[1] + this.sunDir[2] * vw[2];
    final double hU = hw[0] * ud[0] + hw[1] * ud[1] + hw[2] * ud[2];
    final double hY = hw[1];
    final double hV = hw[0] * vw[0] + hw[1] * vw[1] + hw[2] * vw[2];
    double f1U = 0, f1Y = 0, f1V = 0, f2U = 0, f2Y = 0, f2V = 0;
    {
      double lx = this.fillPos[0][0] - wx, ly = this.fillPos[0][1] - wy, lz = this.fillPos[0][2] - wz;
      double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
      if (ll > 1e-9) { lx /= ll; ly /= ll; lz /= ll; }
      f1U = lx * ud[0] + ly * ud[1] + lz * ud[2];
      f1Y = ly;
      f1V = lx * vw[0] + ly * vw[1] + lz * vw[2];
      lx = this.fillPos[1][0] - wx; ly = this.fillPos[1][1] - wy; lz = this.fillPos[1][2] - wz;
      ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
      if (ll > 1e-9) { lx /= ll; ly /= ll; lz /= ll; }
      f2U = lx * ud[0] + ly * ud[1] + lz * ud[2];
      f2Y = ly;
      f2V = lx * vw[0] + ly * vw[1] + lz * vw[2];
    }

    for (int v = iv0; v <= iv1; ++v) {
      final double dyp = v + 0.5 - cv;
      for (int u = iu0; u <= iu1; ++u) {
        final double dxp = u + 0.5 - cu;
        final double dist = Math.sqrt(dxp * dxp + dyp * dyp);
        final double cov = clamp(r + 0.5 - dist, 0, 1);
        if (cov <= 0) {
          continue;
        }
        double ru = dxp / r, rv = dyp / r;
        final double rho2 = ru * ru + rv * rv;
        double c;
        if (rho2 >= 1) {
          final double scale = 1 / Math.sqrt(rho2); // silhouette normal
          ru *= scale;
          rv *= scale;
          c = 0;
        } else {
          c = Math.sqrt(1 - rho2);
        }
        final double diff = KD_SUN * Math.max(0, ru * sunU + rv * sunY + c * sunV)
          + KD_FILL * (Math.max(0, ru * f1U + rv * f1Y + c * f1V)
                     + Math.max(0, ru * f2U + rv * f2Y + c * f2V));
        final double nh = Math.max(0, ru * hU + rv * hY + c * hV);
        final double nh2 = nh * nh;
        final double spec = Math.min(1, nh2 * nh2 * this.frameSpecScale);
        compositePixel(cBuf, dBuf, v * W + u, dn, cov, hueDeg, sat, briBase, bf, diff, spec);
      }
    }
  }

  // ---- Render ---------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK); // cylinder (and anything unmapped) stays dark in v1

    // Beat clock: composite basis is monotonic fractional beats. A backward
    // jump (tap-tempo / transport reset) rebases the epoch and all scheduled
    // caps so grid alignment survives relative to the new clock.
    final double now = this.lx.engine.tempo.getCompositeBasis();
    double dBeats = now - this.lastBeats;
    if (dBeats < 0) {
      // Backward transport jump (tap tempo / reset): the global grid phase
      // moved under the schedules — re-anchor every live cap to it
      dBeats = 0;
      realignCaps(now);
    }
    this.lastBeats = now;

    // Pause/resume: resuming from Speed 0 re-schedules every in-flight cap
    // onto the global CapDiv grid, landing 0.5-1.5 divisions out
    final double rate = this.speed.getValue();
    if ((this.prevRate == 0) && (rate > 0)) {
      realignCaps(now);
    }
    this.prevRate = rate;

    // Rotation accumulation (tempo-locked: 100% = 90 degrees per beat) and
    // this frame's transform state
    this.axDeg = (this.axDeg + this.rotX.getValue() * 90 * dBeats) % 360;
    this.ayDeg = (this.ayDeg + this.rotY.getValue() * 90 * dBeats) % 360;
    final double axr = Math.toRadians(this.axDeg), ayr = Math.toRadians(this.ayDeg);
    this.sinAx = Math.sin(axr); this.cosAx = Math.cos(axr);
    this.sinAy = Math.sin(ayr); this.cosAy = Math.cos(ayr);
    this.cxr = this.gx * 0.5; this.cyr = this.gy * 0.5; this.czr = this.gz * 0.5;
    this.frameHalfPx = thicknessPx() / 2;
    this.frameBallHalfPx = this.frameHalfPx + BALL_EXTRA_PX;
    // Shading amount (0 = flat fast path) and specular gain: the Shaded knob
    // (0.5 = nominal) plus an audio level boost (depth-scaled, silence-safe)
    this.frameShadeMix = this.shaded.getValue();
    this.frameSpecScale = SPEC_GAIN
      * (2 * this.frameShadeMix + AUDIO_SPEC_BOOST * this.audio.level);

    // Lighting rig: the sun and corner fills rotate together about Y, one
    // full revolution per RIG_ROT_BEATS beats — the third coordinate system
    // (walls fixed; lattice on RotX/RotY; rig autonomous, untouched by RstRot)
    this.rigDeg = (this.rigDeg + (360.0 / RIG_ROT_BEATS) * dBeats) % 360;
    final double rigR = Math.toRadians(this.rigDeg);
    final double sinR = Math.sin(rigR), cosR = Math.cos(rigR);
    this.sunDir[0] = cosR * SUN[0] + sinR * SUN[2];
    this.sunDir[1] = SUN[1];
    this.sunDir[2] = -sinR * SUN[0] + cosR * SUN[2];
    for (int w = 0; w < WALLS; ++w) {
      final double hx = this.sunDir[0] + VIEW[w][0];
      final double hy = this.sunDir[1] + VIEW[w][1];
      final double hz = this.sunDir[2] + VIEW[w][2];
      final double hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
      this.halfVec[w][0] = hx / hl;
      this.halfVec[w][1] = hy / hl;
      this.halfVec[w][2] = hz / hl;
    }
    for (int j = 0; j < FILL_CORNERS.length; ++j) {
      final double fx = FILL_CORNERS[j][0] * this.cxr * FILL_DIST;
      final double fy = FILL_CORNERS[j][1] * this.cyr * FILL_DIST;
      final double fz = FILL_CORNERS[j][2] * this.czr * FILL_DIST;
      this.fillPos[j][0] = this.cxr + cosR * fx + sinR * fz;
      this.fillPos[j][1] = this.cyr + fy;
      this.fillPos[j][2] = this.czr - sinR * fx + cosR * fz;
    }

    double outputScale = 1;
    if (this.draining) {
      this.drainRemainMs -= deltaMs;
      if (this.drainRemainMs <= 0) {
        finishDrain();
      } else {
        outputScale = this.drainRemainMs / this.drainTotalMs;
      }
    } else {
      updatePipes(now, dBeats);
    }

    // Bass sparkle envelope on recent elbows; the hit response scales with
    // the Audio depth knob (max() so a manual Sparkle trigger is not dimmed),
    // and louder music flashes MORE of the recent caps
    if (this.audio.bassHit()) {
      this.sparkleLevel = Math.max(this.sparkleLevel, this.audio.depth());
      this.sparkleCount = 1 + (int) Math.round(
        (ELBOW_HISTORY - 1) * Math.min(1, SPARKLE_BREADTH * this.audio.level));
    } else {
      this.sparkleLevel = Math.max(0, this.sparkleLevel - deltaMs / SPARKLE_MS);
    }

    // Full re-raster of the retained geometry under this frame's rotation.
    // Balls draw after segments so joints win ties at equal depth.
    clearWallBuffers();
    for (int s = 0; s < this.segCount; ++s) {
      rasterSegment(s);
    }
    for (int b = 0; b < this.ballCount; ++b) {
      rasterBall(b);
    }

    blit(outputScale);
    if (this.sparkleLevel > 0 && this.elbowCount > 0) {
      sparkleOverlay(outputScale);
    }
    copyCubeExterior(); // interior faces are copies of the exterior render
  }

  /** Copy the wall buffers to the cube exterior LEDs, scaled by the drain fade. */
  private void blit(double scale) {
    final Apotheneum.Cube.Face[] faces = Apotheneum.cube.exterior.faces;
    for (int w = 0; w < WALLS; ++w) {
      final int[] cBuf = this.colorBuf[w];
      final Apotheneum.Column[] cols = faces[w].columns;
      for (int u = 0; u < W; ++u) {
        // NB: cube face columns always carry GRID_HEIGHT points (the Face
        // constructor enforces it); door openings are not shorter columns,
        // so writing every row is correct. Bounded loop kept for safety.
        final heronarts.lx.model.LXPoint[] pts = cols[u].points;
        final int n = Math.min(pts.length, H);
        for (int v = 0; v < n; ++v) {
          this.colors[pts[v].index] = scaleColor(cBuf[v * W + u], scale);
        }
      }
    }
  }

  /** White flash on recent elbow joints, drawn over the blit (depth-tested). */
  private void sparkleOverlay(double scale) {
    final Apotheneum.Cube.Face[] faces = Apotheneum.cube.exterior.faces;
    final float b = (float) (SPARKLE_BRIGHTNESS * this.sparkleLevel * scale);
    final int flash = LXColor.hsb(0, 0, b);
    // Visibility tolerance: the elbow's own ball may have written the depth
    // buffer up to (THICK_MAX/2 + BALL_EXTRA_PX) px nearer than the center
    // depth tested here. Anything nearer than that margin is a genuinely
    // occluding pipe (occluders are >= 1 cell nearer).
    final float tol = (float) (((0.5 * THICK_MAX + BALL_EXTRA_PX) / this.cw + 0.05) / this.gz);
    // Newest-first ring walk over the sparkleCount most recent caps (loud
    // bass hits latch a larger count; the manual trigger flashes them all)
    final int count = Math.min(this.sparkleCount, this.elbowCount);
    for (int j = 0; j < count; ++j) {
      final int e = (this.elbowNext - 1 - j + ELBOW_HISTORY) % ELBOW_HISTORY;
      // Elbows are stored untransformed; rotate through the same transform
      // as the geometry so sparkles track rotated joints
      xformPoint(this.elbowX[e], this.elbowY[e], this.elbowZ[e]);
      final double wx = this.tx, wy = this.ty, wz = this.tz;
      for (int w = 0; w < WALLS; ++w) {
        final float dn = (float) clamp(wallDepth(w, wx, wz) / this.gz, 0, 1);
        final int u = (int) Math.floor(wallU(w, wx, wz));
        final int v = (int) Math.floor(wy * this.ch);
        if (u < 0 || u >= W || v < 0 || v >= H) {
          continue;
        }
        // Only sparkle elbows that are visible on this wall (not occluded)
        if (dn > this.depthBuf[w][v * W + u] + tol) {
          continue;
        }
        final Apotheneum.Column[] cols = faces[w].columns;
        plot(cols, u, v, flash);
        plot(cols, u - 1, v, flash);
        plot(cols, u + 1, v, flash);
        plot(cols, u, v - 1, flash);
        plot(cols, u, v + 1, flash);
      }
    }
  }

  private void plot(Apotheneum.Column[] cols, int u, int v, int color) {
    if (u < 0 || u >= W || v < 0) {
      return;
    }
    final heronarts.lx.model.LXPoint[] pts = cols[u].points;
    if (v < pts.length) {
      this.colors[pts[v].index] = LXColor.lightest(this.colors[pts[v].index], color);
    }
  }

  private static int scaleColor(int argb, double f) {
    if (f >= 1) return argb;
    if (f <= 0) return LXColor.BLACK;
    final int r = (int) (((argb >> 16) & 0xff) * f);
    final int g = (int) (((argb >> 8) & 0xff) * f);
    final int b = (int) ((argb & 0xff) * f);
    return LXColor.rgba(r, g, b, 255);
  }
}
