package apotheneum.jvyduna.patterns;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
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
import heronarts.lx.parameter.TriggerParameter;

/**
 * Recreation of the Windows NT "3D Pipes" screensaver. A self-avoiding pipe
 * lattice grows through a single ~10x9x10-cell room volume; the cube's four
 * walls are the four walls of that room, each showing an orthographic
 * projection of the SAME shared 3D object. Per-wall depth buffers resolve
 * overlap (nearer pipe wins) and drive depth-cued brightness; a world-fixed
 * sun at infinity puts a specular highlight stripe on each pipe.
 *
 * Growth is beat-planned: every cap (turn/elbow) lands on a multiple of the
 * OnBeats grid counted from pattern load or the end of a drain, and all pipes
 * grow at (near-)equal Speed in cells per beat — each run covers a whole
 * number of cells with its speed trimmed slightly so the cap lands exactly on
 * the grid AND on a lattice corner. The whole lattice can also rotate (RotX /
 * RotY, tempo-locked) with the projection re-rendered each frame.
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

  /**
   * Simulation-principles cap: a full wall crossing (gx cells) must take at
   * least this long. Enforced exactly: the effective speed is clamped to
   * gx * period / TRAVERSAL_MIN_MS cells per beat, and run planning never
   * schedules a run faster than that clamp.
   */
  private static final double TRAVERSAL_MIN_MS = 5000;

  /** Auto-drain when this fraction of room cells is occupied */
  private static final double FILL_LIMIT = 0.6;

  /** Decay time of the bass-hit elbow sparkle flash (stationary, not motion) */
  private static final double SPARKLE_MS = 500; // CURATE: flash length vs. subtlety

  // ---- Look constants --------------------------------------------------------

  /** Chance of continuing straight when the straight-ahead run is free */
  private static final double P_STRAIGHT = 0.55; // CURATE: NT pipes turn often; higher = longer runs

  /** Hue offset between concurrent pipes, degrees */
  private static final float PIPE_HUE_SPREAD = 40; // CURATE: separation of simultaneous pipe colors

  /** Sun-specular stripe: saturation at full specular intensity (lower = whiter) */
  private static final float STRIPE_SAT = 35;      // CURATE
  /** Sun-specular stripe: brightness boost at full specular intensity */
  private static final float STRIPE_BOOST = 1.15f;
  /** Gain applied to the Blinn-Phong lobe before clamping to 1 */
  private static final double SPEC_GAIN = 1.4; // CURATE: highlight visibility
  /** Skip the stripe below this intensity (invisible anyway) */
  private static final double SPEC_MIN = 0.05;

  /** Elbow ball: extra radius in px beyond the pipe half-thickness */
  private static final double BALL_EXTRA_PX = 1;

  /** Thickness parameter bounds (px); THICK_MAX also sizes the sparkle
   *  visibility tolerance in sparkleOverlay() */
  private static final double THICK_MIN = 3, THICK_MAX = 5;

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

  /** Recent elbows retained for the bass sparkle overlay */
  private static final int ELBOW_HISTORY = 16;

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

  /** Sun direction (toward the light) and per-wall Blinn-Phong half vectors */
  private static final double[] SUN;
  private static final double[][] HALF_VEC;
  static {
    // Sun at infinity toward an upper corner. Rows index top-down on the
    // walls (column.points[0] is the top row), so "up" is -y in room
    // coordinates. CURATE: corner choice / elevation; flip signs if the
    // highlight reads as lit from below on the sculpture.
    final double sx = 0.35, sy = -0.85, sz = 0.35;
    final double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
    SUN = new double[] { sx / sl, sy / sl, sz / sl };
    HALF_VEC = new double[WALLS][3];
    for (int w = 0; w < WALLS; ++w) {
      final double hx = SUN[0] + VIEW[w][0];
      final double hy = SUN[1] + VIEW[w][1];
      final double hz = SUN[2] + VIEW[w][2];
      final double hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
      HALF_VEC[w][0] = hx / hl;
      HALF_VEC[w][1] = hy / hl;
      HALF_VEC[w][2] = hz / hl;
    }
  }

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
    .setDescription("Fade the room out, concluding exactly on a beat (0.5-1.5 beats), then restart with one pipe in the next palette color"));

  public final TriggerParameter teleport = bag.register(
    new TriggerParameter("Teleport", this::teleportRandomPipe)
    .setDescription("A growing pipe jumps to a random free cell and continues (the classic)"));

  public final TriggerParameter newPipe = bag.register(
    new TriggerParameter("NewPipe", this::spawnAnotherPipe)
    .setDescription("Spawn another concurrent pipe (max 3)"));

  public final TriggerParameter sparkle = bag.register(
    new TriggerParameter("Sparkle", this::flashSparkle)
    .setDescription("Flash the recent elbow joints white (the bass sparkle, fired manually)"));

  public final TriggerParameter rstRot = bag.register(
    new TriggerParameter("RstRot", this::resetRotation)
    .setDescription("Zero the rotation speeds and snap back to the orthogonal projection"));

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1.0, 0.25, 4.0)
    .setDescription("Growth speed in cells per beat, shared by all pipes; clamped so a full wall crossing takes at least 5 s");

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", 3.5, THICK_MIN, THICK_MAX)
    .setDescription("Pipe thickness in pixels (applies to newly started runs)");

  public final DiscreteParameter density =
    new DiscreteParameter("Density", 10, MIN_DENSITY, MAX_DENSITY + 1)
    .setDescription("Room grid cells across each axis; takes effect at the next drain");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Hue offset in degrees added to the palette-derived pipe color");

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
    new EnumParameter<Beats>("OnBeats", Beats.ONE)
    .setDescription("Beat-grid interval that every cap/turn lands on, counted from pattern load or the end of a drain");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
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
  // frame). Hue and thickness are captured at creation so committed geometry
  // keeps its look, like real pipes already installed.
  private final double[] segAx = new double[MAX_SEGMENTS], segAy = new double[MAX_SEGMENTS], segAz = new double[MAX_SEGMENTS];
  private final double[] segBx = new double[MAX_SEGMENTS], segBy = new double[MAX_SEGMENTS], segBz = new double[MAX_SEGMENTS];
  private final float[] segHue = new float[MAX_SEGMENTS];
  private final float[] segHalfPx = new float[MAX_SEGMENTS];
  private int segCount = 0;

  private final double[] ballX = new double[MAX_BALLS], ballY = new double[MAX_BALLS], ballZ = new double[MAX_BALLS];
  private final float[] ballHue = new float[MAX_BALLS];
  private final float[] ballHalfPx = new float[MAX_BALLS];
  private int ballCount = 0;

  // Pipe state (parallel arrays, MAX_PIPES slots). Heads are continuous cell
  // coordinates (cell k's center is at k + 0.5) and move only along pDir.
  private final boolean[] pAlive = new boolean[MAX_PIPES];
  private final int[] pDir = new int[MAX_PIPES];        // 0..5, or -1 right after placement
  private final double[] pHeadX = new double[MAX_PIPES];
  private final double[] pHeadY = new double[MAX_PIPES];
  private final double[] pHeadZ = new double[MAX_PIPES];
  private final double[] pCapBeat = new double[MAX_PIPES]; // absolute composite-basis beat of the next cap
  private final double[] pRatio = new double[MAX_PIPES];   // run speed / speedEff at plan time
  private final int[] pSeg = new int[MAX_PIPES];           // live segment index, or -1
  private final float[] pHue = new float[MAX_PIPES];

  /** Scratch: free / in-bounds run lengths per direction */
  private final int[] freeRun = new int[6];
  private final int[] boundRun = new int[6];

  // Beat clock: composite basis is monotonic fractional beats; caps land on
  // epochBeats + m * OnBeats. The epoch is set at load/activation and at the
  // end of each drain, snapped to a whole engine beat.
  private double epochBeats;
  private double lastBeats;

  // Lattice rotation state (accumulated angles) and per-frame trig
  private double axDeg = 0, ayDeg = 0;
  private double sinAx, cosAx, sinAy, cosAy;
  private double cxr, cyr, czr;

  /** Scratch result of xformPoint/xformDir */
  private double tx, ty, tz;

  // Elbow sparkle ring buffer (untransformed lattice coordinates; the current
  // rotation is applied at draw time so sparkles track rotated elbows)
  private final float[] elbowX = new float[ELBOW_HISTORY];
  private final float[] elbowY = new float[ELBOW_HISTORY];
  private final float[] elbowZ = new float[ELBOW_HISTORY];
  private int elbowCount = 0, elbowNext = 0;
  private double sparkleLevel = 0;

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
    addParameter("newPipe", this.newPipe);
    addParameter("sparkle", this.sparkle);
    addParameter("rstRot", this.rstRot);
    addParameter("speed", this.speed);
    addParameter("thickness", this.thickness);
    addParameter("density", this.density);
    addParameter("hue", this.hue);
    addParameter("rotX", this.rotX);
    addParameter("rotY", this.rotY);
    addParameter("audio", this.audioDepth);
    addParameter("onBeats", this.onBeats);
    addParameter("meta", this.meta);

    bag.jumpable(this.thickness);
    bag.jumpable(this.density);
    bag.jumpable(this.hue);
    // Musical mid-band only: below 0.5 the lattice crawls, above 2.0 the
    // traversal clamp eats the jump at typical BPM anyway. CURATE
    bag.jumpable(this.speed, 0.5, 2.0);
    // Exclude 3/4 (a random polyrhythm jump reads as a timing bug) and 8
    // (caps stall for many seconds). CURATE: unverified visually
    bag.jumpable(this.onBeats, Beats.ONE.ordinal(), Beats.FOUR.ordinal());
    // Slow ambient drift only (<= 22.5 deg/beat); a jump to full rate is
    // disorienting. RstRot is registered too, so meta can snap back. CURATE
    bag.jumpable(this.rotX, 0, 0.25);
    bag.jumpable(this.rotY, 0, 0.25);

    this.lastBeats = lx.engine.tempo.getCompositeBasis();
    this.epochBeats = Math.rint(this.lastBeats);
    applyDensity();
    clearRoom();
    spawnPipe();
  }

  @Override
  protected void onActive() {
    super.onActive();
    // The composite basis kept running while inactive: rebase the clock and
    // epoch. Live pipes' pCapBeat values are now in the past, so each replans
    // on the first frame, re-aligning every cap to the fresh epoch.
    this.lastBeats = this.lx.engine.tempo.getCompositeBasis();
    this.epochBeats = Math.rint(this.lastBeats);
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

  /** Pipe color at run start: palette color (advanced per drain) + hue offset. */
  private float pipeHue(int i) {
    float base = 0;
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    if (!swatch.isEmpty()) {
      base = LXColor.h(swatch.get(this.drainCount % swatch.size()).getColor());
    }
    float h = (float) ((base + this.hue.getValue() + i * PIPE_HUE_SPREAD) % 360);
    return (h < 0) ? h + 360 : h;
  }

  // ---- Beat planning ------------------------------------------------------------

  /** Cap-grid interval in beats */
  private double gridInterval() {
    return this.onBeats.getEnum().beats;
  }

  /**
   * First grid point strictly after beat b (grid = epochBeats + m * OnBeats).
   * The epsilon means "if b sits exactly on a grid point, return the next
   * one" — so a cap planned at a cap lands one full interval later.
   */
  private double nextGrid(double b) {
    final double g = gridInterval();
    return this.epochBeats + (Math.floor((b - this.epochBeats) / g + 1e-6) + 1) * g;
  }

  /**
   * Effective shared growth speed in cells per beat: the Speed knob clamped
   * so a full wall crossing (gx cells) takes >= TRAVERSAL_MIN_MS at the
   * current tempo. Run planning never exceeds this, so the traversal floor
   * holds exactly at any BPM and density.
   */
  private double speedEff() {
    final double periodMs = this.lx.engine.tempo.period.getValue();
    return Math.min(this.speed.getValue(), this.gx * periodMs / TRAVERSAL_MIN_MS);
  }

  private static int clampi(int v, int lo, int hi) {
    return (v < lo) ? lo : (v > hi) ? hi : v;
  }

  private static double clamp(double v, double lo, double hi) {
    return (v < lo) ? lo : (v > hi) ? hi : v;
  }

  /**
   * Plan the next run for pipe i, starting at the current (continuous) head:
   * pick a direction and an integer cell count that fit the room, schedule
   * the cap on the OnBeats grid, and trim the run's speed ratio so the head
   * arrives exactly at the target corner exactly on the grid. Called at every
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

    // Run length: 1..maxRun cells, max-of-two for a mild long bias.
    // CURATE: bias strength — longer runs fill space faster but turn less.
    final int maxRun = intersecting ? this.boundRun[dir] : this.freeRun[dir];
    final int n = 1 + Math.max(this.random.nextInt(maxRun), this.random.nextInt(maxRun));

    // Geometry bookkeeping: continuing straight extends the live segment;
    // a turn caps here (elbow ball at the head, wherever it is) and opens a
    // fresh segment. pDir < 0 means just placed: the spawn ball already
    // marks the start, no elbow.
    if ((dir != straight) || (this.pSeg[i] < 0)) {
      if (straight >= 0) {
        addBall(this.pHeadX[i], this.pHeadY[i], this.pHeadZ[i], this.pHue[i]);
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
      this.pHue[i] = pipeHue(i);
      this.segHue[s] = this.pHue[i];
      this.segHalfPx[s] = (float) (thicknessPx() / 2);
      this.pSeg[i] = s;
    }

    // Cap scheduling: the run covers dist cells (target = the corner n cells
    // from the anchor along dir; only the run-axis coordinate is targeted).
    // Pick the number of grid intervals k nearest the nominal duration at
    // speedEff, never allowing the run to move faster than speedEff, and
    // store the trim as a ratio so the live Speed knob still scales mid-run.
    final double g = gridInterval();
    final double sEff = speedEff();
    final double d0 = nextGrid(now) - now; // (0, g]
    double dist;
    if (DX[dir] != 0) {
      dist = Math.abs((ax + 0.5 + DX[dir] * n) - this.pHeadX[i]);
    } else if (DY[dir] != 0) {
      dist = Math.abs((ay + 0.5 + DY[dir] * n) - this.pHeadY[i]);
    } else {
      dist = Math.abs((az + 0.5 + DZ[dir] * n) - this.pHeadZ[i]);
    }
    long k = Math.max(1, Math.round((dist / sEff - d0) / g) + 1);
    double t = d0 + (k - 1) * g;
    if (dist / t > sEff) {
      ++k;
      t += g;
    }
    this.pDir[i] = dir;
    this.pCapBeat[i] = nextGrid(now) + (k - 1) * g;
    this.pRatio[i] = (dist / t) / sEff;

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
    this.pHue[i] = pipeHue(i);
    addBall(this.pHeadX[i], this.pHeadY[i], this.pHeadZ[i], this.pHue[i]);
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
          placePipe(i, this.rlX, this.rlY, this.rlZ);
        } else {
          LX.log("Pipes3D: no free cell to spawn a pipe; draining");
          startDrain();
        }
        return;
      }
    }
  }

  // Trigger: spawn another concurrent pipe (max 3)
  private void spawnAnotherPipe() {
    if (!this.draining) {
      spawnPipe();
    }
  }

  // Trigger: manually fire the elbow sparkle flash at full brightness
  private void flashSparkle() {
    this.sparkleLevel = 1;
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
    addBall(this.pHeadX[i], this.pHeadY[i], this.pHeadZ[i], this.pHue[i]);
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
   * next palette color, exactly ONE pipe, and a fresh cap-grid epoch.
   */
  private void finishDrain() {
    this.draining = false;
    ++this.drainCount;
    for (int i = 0; i < MAX_PIPES; ++i) {
      this.pAlive[i] = false;
    }
    applyDensity(); // density jumps take effect here
    clearRoom();
    // The drain concluded on an engine beat by construction; rint absorbs
    // the <= 1-frame countdown overshoot. Caps re-count from here.
    this.epochBeats = Math.rint(this.lastBeats);
    spawnPipe();
    LX.log("Pipes3D: drain complete; density " + this.gx + "x" + this.gy + "x" + this.gz);
  }

  // ---- Growth ---------------------------------------------------------------------

  /**
   * Integrate every live pipe's head up to (exactly) its cap time, then
   * replan at caps. Heads move only along pDir at ratio * speedEff cells per
   * beat — the live Speed knob and BPM changes flow through immediately; the
   * beat-aligned cap then fires wherever the head is, and the next plan
   * re-anchors to the lattice (self-healing, no snapping).
   */
  private void updatePipes(double now, double dBeats) {
    final double sEff = speedEff();
    final double bPrev = now - dBeats;
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (!this.pAlive[i]) {
        continue;
      }
      final double bEnd = Math.min(now, this.pCapBeat[i]);
      final double dt = bEnd - bPrev;
      if ((dt > 0) && (this.pDir[i] >= 0)) {
        final int d = this.pDir[i];
        final double step = this.pRatio[i] * sEff * dt;
        // Clamp inside the room: a head that outruns the plan (Speed turned
        // up mid-run) stalls at the wall until its cap beat — caps are never
        // early, and the next plan steers back in
        this.pHeadX[i] = clamp(this.pHeadX[i] + DX[d] * step, 0.5, this.gx - 0.5);
        this.pHeadY[i] = clamp(this.pHeadY[i] + DY[d] * step, 0.5, this.gy - 0.5);
        this.pHeadZ[i] = clamp(this.pHeadZ[i] + DZ[d] * step, 0.5, this.gz - 0.5);
        final int s = this.pSeg[i];
        if (s >= 0) {
          this.segBx[s] = this.pHeadX[i];
          this.segBy[s] = this.pHeadY[i];
          this.segBz[s] = this.pHeadZ[i];
        }
      }
      if (now >= this.pCapBeat[i]) {
        planRun(i); // may start the drain (fill limit / list overflow)
        if (this.draining) {
          return;
        }
      }
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
  private void addBall(double x, double y, double z, float hueDeg) {
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
    this.ballHalfPx[b] = (float) (thicknessPx() / 2 + BALL_EXTRA_PX);
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

  /** Current pipe thickness in px, clamped to the cell size so lattice cells stay distinct */
  private double thicknessPx() {
    return Math.min(this.thickness.getValue(), Math.min(this.cw, this.ch));
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
    final double halfPx = this.segHalfPx[s];
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
        // Seen end-on: a small square at the near depth
        stampSquare(cBuf, dBuf, (u0 + u1) / 2, (v0 + v1) / 2, halfPx,
          Math.min(dn0, dn1), hueDeg, 100f, 1f);
        continue;
      }

      // Sun specular for this wall: Blinn-Phong on a cylinder. The lobe
      // peaks where the surface normal aligns with the half vector H; in the
      // (M, V') frame across the pipe (M = view x axis, V' = view-facing
      // normal component, both of magnitude sqrt(1 - (V.A)^2)) the peak sits
      // at cross-pipe offset tStar with height sqrt(a^2 + b^2) <= 1.
      double specI = 0, tScreen = 0;
      {
        final double[] vw = VIEW[w];
        final double[] hw = HALF_VEC[w];
        final double dotVA = vw[0] * axn + vw[1] * ayn + vw[2] * azn;
        final double ppx = vw[0] - dotVA * axn;
        final double ppy = vw[1] - dotVA * ayn;
        final double ppz = vw[2] - dotVA * azn;
        final double plen = Math.sqrt(ppx * ppx + ppy * ppy + ppz * ppz);
        if (plen > 1e-4) { // not end-on
          final double mx = vw[1] * azn - vw[2] * ayn;
          final double my = vw[2] * axn - vw[0] * azn;
          final double mz = vw[0] * ayn - vw[1] * axn;
          final double a = (mx * hw[0] + my * hw[1] + mz * hw[2]) / plen;
          final double b = (ppx * hw[0] + ppy * hw[1] + ppz * hw[2]) / plen;
          if (b > 0) {
            final double s0 = Math.sqrt(a * a + b * b);
            final double s2 = s0 * s0, s4 = s2 * s2;
            specI = Math.min(1, s4 * s4 * SPEC_GAIN); // exponent 8; CURATE
            final double tStar = (s0 > 1e-6) ? a / s0 : 0;
            // Orient the cross-pipe offset onto the screen minor axis
            final double mu = wallMu(w, mx, mz), mv = my * this.ch;
            final double orient = mu * (-dv) + mv * du;
            tScreen = (orient >= 0) ? tStar : -tStar;
          }
        }
      }
      final int stripeJ = ((specI > SPEC_MIN) && (span >= 2))
        ? clampi((int) Math.round((span - 1) * (tScreen + 1) / 2), 0, span - 1) : -1;
      final float specIf = (float) specI;

      final int steps = Math.max(1, (int) Math.ceil(screenLen));
      final double m2u = -dv / screenLen, m2v = du / screenLen;

      // Pass 1: body
      for (int k = 0; k <= steps; ++k) {
        final double f = (double) k / steps;
        final double cu = u0 + du * f, cv = v0 + dv * f;
        final float dnk = dn0 + (dn1 - dn0) * (float) f;
        final float bf = 1 - DEPTH_DIM * dnk;
        final int colMain = LXColor.hsb(hueDeg, 100f, 100f * bf);
        for (int j = 0; j < span; ++j) {
          final double o = j - (span - 1) * 0.5;
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
      }

      // Pass 2: specular stripe (1 px at the lobe's cross-pipe offset)
      if (stripeJ >= 0) {
        final double o = stripeJ - (span - 1) * 0.5;
        final float stripeSat = 100 - (100 - STRIPE_SAT) * specIf;
        for (int k = 0; k <= steps; ++k) {
          final double f = (double) k / steps;
          final int pu = (int) Math.floor(u0 + du * f + m2u * o);
          final int pv = (int) Math.floor(v0 + dv * f + m2v * o);
          if (pu < 0 || pu >= W || pv < 0 || pv >= H) {
            continue;
          }
          final int idx = pv * W + pu;
          final float dnk = dn0 + (dn1 - dn0) * (float) f;
          if (dnk <= dBuf[idx] + 1e-6f) {
            final float bf = 1 - DEPTH_DIM * dnk;
            dBuf[idx] = Math.min(dBuf[idx], dnk);
            cBuf[idx] = LXColor.hsb(hueDeg, stripeSat,
              Math.min(100f, 100f * bf * (1 + (STRIPE_BOOST - 1) * specIf)));
          }
        }
      }
    }
  }

  /** Rasterize one joint/cap ball: a bright desaturated square stamp per wall */
  private void rasterBall(int b) {
    xformPoint(this.ballX[b], this.ballY[b], this.ballZ[b]);
    final double wx = this.tx, wy = this.ty, wz = this.tz;
    final double half = this.ballHalfPx[b];
    for (int w = 0; w < WALLS; ++w) {
      final float dn = (float) clamp(wallDepth(w, wx, wz) / this.gz, 0, 1);
      stampSquare(this.colorBuf[w], this.depthBuf[w],
        wallU(w, wx, wz), wy * this.ch, half, dn, this.ballHue[b], BALL_SAT, BALL_BOOST);
    }
  }

  /** Depth-tested square stamp centered at (cu, cv) px with half-extent half px */
  private void stampSquare(int[] cBuf, float[] dBuf, double cu, double cv,
                           double half, float dn, float hueDeg, float sat, float boost) {
    final int iu0 = Math.max(0, (int) Math.floor(cu - half));
    final int iu1 = Math.min(W - 1, (int) Math.ceil(cu + half) - 1);
    final int iv0 = Math.max(0, (int) Math.floor(cv - half));
    final int iv1 = Math.min(H - 1, (int) Math.ceil(cv + half) - 1);
    if (iu1 < iu0 || iv1 < iv0) {
      return;
    }
    final float bf = 1 - DEPTH_DIM * dn;
    final int col = LXColor.hsb(hueDeg, sat, Math.min(100f, 100f * bf * boost));
    for (int v = iv0; v <= iv1; ++v) {
      final int rowBase = v * W;
      for (int u = iu0; u <= iu1; ++u) {
        final int idx = rowBase + u;
        if (dn <= dBuf[idx]) {
          dBuf[idx] = dn;
          cBuf[idx] = col;
        }
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
      this.epochBeats += dBeats;
      for (int i = 0; i < MAX_PIPES; ++i) {
        this.pCapBeat[i] += dBeats;
      }
      dBeats = 0;
    }
    this.lastBeats = now;

    // Rotation accumulation (tempo-locked: 100% = 90 degrees per beat) and
    // this frame's transform state
    this.axDeg = (this.axDeg + this.rotX.getValue() * 90 * dBeats) % 360;
    this.ayDeg = (this.ayDeg + this.rotY.getValue() * 90 * dBeats) % 360;
    final double axr = Math.toRadians(this.axDeg), ayr = Math.toRadians(this.ayDeg);
    this.sinAx = Math.sin(axr); this.cosAx = Math.cos(axr);
    this.sinAy = Math.sin(ayr); this.cosAy = Math.cos(ayr);
    this.cxr = this.gx * 0.5; this.cyr = this.gy * 0.5; this.czr = this.gz * 0.5;

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
    // the Audio depth knob (max() so a manual Sparkle trigger is not dimmed)
    if (this.audio.bassHit()) {
      this.sparkleLevel = Math.max(this.sparkleLevel, this.audio.depth());
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
    for (int e = 0; e < this.elbowCount; ++e) {
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
