package apotheneum.jvyduna.patterns;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
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
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Recreation of the Windows NT "3D Pipes" screensaver. A self-avoiding pipe
 * lattice grows through a single ~10x9x10-cell room volume; the cube's four
 * walls are the four walls of that room, each showing an orthographic
 * projection of the SAME shared 3D object along its inward normal. Per-wall
 * depth buffers resolve overlap (nearer pipe wins) and drive depth-cued
 * brightness. Pipes turn at right angles with bright sphere joints at the
 * elbows, occasionally teleport, and when the room is >60% full everything
 * fades out and a fresh lattice starts in the next palette color.
 *
 * Cube-only in v1; the cylinder stays dark (follow-up curation item). See
 * Pipes3D.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Pipes 3D")
@LXComponent.Description("NT 3D Pipes: a self-avoiding pipe lattice grows in one shared room volume, projected orthographically onto all four cube walls")
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

  /** Ms to extrude one ~5 px cell at energy 0 — a slow, meditative crawl */
  private static final double SEG_MS_AMBIENT = 2000;

  /** Ms to extrude one cell at energy 1, before tempo quantization rounds it up */
  private static final double SEG_MS_PEAK = 1000;

  /** Above this energy (with Sync on), segment starts gate to the tempo grid */
  private static final double HIGH_ENERGY = 0.6;

  /**
   * Simulation-principles cap: a full wall crossing (gx cells) must take at
   * least this long, even with the worst-case audio boost and retime speed-up.
   */
  private static final double TRAVERSAL_MIN_MS = 5000;

  /** Max growth-rate boost from the smoothed audio level (0..1) — modest push */
  private static final double LEVEL_RATE_BOOST = 0.3; // CURATE: subtle; raise if growth should visibly surge with loudness

  /** Safety: a grid-gated pipe never waits longer than this for a boundary/bass hit */
  private static final double BEAT_WAIT_TIMEOUT_MS = 1500;

  /** Full-room fade-out duration when draining (brightness ramp, not motion) */
  private static final double DRAIN_MS = 3000;

  /** Auto-drain when this fraction of room cells is occupied */
  private static final double FILL_LIMIT = 0.6;

  /** Decay time of the treble-hit elbow sparkle flash (stationary, not motion) */
  private static final double SPARKLE_MS = 500; // CURATE: flash length vs. subtlety

  // ---- Look constants --------------------------------------------------------

  /** Chance of continuing straight when the straight-ahead cell is free */
  private static final double P_STRAIGHT = 0.55; // CURATE: NT pipes turn often; higher = longer runs

  /** Hue offset between concurrent pipes, degrees */
  private static final float PIPE_HUE_SPREAD = 40; // CURATE: separation of simultaneous pipe colors

  /** Highlight stripe: desaturated (glossy) and brightness-boosted */
  private static final float STRIPE_SAT = 35;      // CURATE: lower = whiter specular
  private static final float STRIPE_BOOST = 1.15f;

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

  /** Peak brightness of the treble sparkle overlay (percent) */
  private static final float SPARKLE_BRIGHTNESS = 90; // CURATE

  private static final int MAX_PIPES = 3;

  /** Attempts to find a free relocation cell before giving up and draining */
  private static final int RELOCATE_TRIES = 60;

  /** Recent elbows retained for the treble sparkle overlay */
  private static final int ELBOW_HISTORY = 16;

  // 6 axis directions: -x +x -y +y -z +z
  private static final int[] DX = { -1, 1, 0, 0, 0, 0 };
  private static final int[] DY = { 0, 0, -1, 1, 0, 0 };
  private static final int[] DZ = { 0, 0, 0, 0, -1, 1 };

  // ---- Parameters -----------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Pipes3D");

  public final TriggerParameter drain = bag.register(
    new TriggerParameter("Drain", this::startDrain)
    .setDescription("Fade the room out over 3s, then restart with the next palette color"));

  public final TriggerParameter teleport = bag.register(
    new TriggerParameter("Teleport", this::teleportRandomPipe)
    .setDescription("A growing pipe jumps to a random free cell and continues (the classic)"));

  public final TriggerParameter newPipe = bag.register(
    new TriggerParameter("NewPipe", this::spawnAnotherPipe)
    .setDescription("Spawn another concurrent pipe (max 3)"));

  public final TriggerParameter sparkle = bag.register(
    new TriggerParameter("Sparkle", this::flashSparkle)
    .setDescription("Flash the recent elbow joints white (the treble sparkle, fired manually)"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("0-0.4 ambient crawl; 0.6-1.0 grid-gated high-energy growth");

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", 3.5, THICK_MIN, THICK_MAX)
    .setDescription("Pipe thickness in pixels (applies to newly drawn segments)");

  public final DiscreteParameter density =
    new DiscreteParameter("Density", 10, MIN_DENSITY, MAX_DENSITY + 1)
    .setDescription("Room grid cells across each axis; takes effect at the next drain");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Hue offset in degrees added to the palette-derived pipe color");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final BooleanParameter sync =
    new BooleanParameter("Sync", true)
    .setDescription("Lock motion events to the tempo grid; off restores free-running timing");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.SIXTEENTH)
    .setDescription("Tempo division that segment growth quantizes and gates to when Sync is enabled");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- Preallocated state (zero-alloc render path) ---------------------------

  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  /** Per-wall persistent color and depth buffers; completed geometry lives here */
  private final int[][] colorBuf = new int[WALLS][W * H];
  private final float[][] depthBuf = new float[WALLS][W * H];

  /** Occupancy at max density; only [gx][gy][gz] is used at the current density */
  private final boolean[][][] occupied = new boolean[MAX_DENSITY][MAX_DENSITY_Y][MAX_DENSITY];
  private int occupiedCount = 0;

  // Current room dimensions in cells, and cell size in pixels
  private int gx, gy, gz;
  private double cw, ch;

  /** Density-aware floor on segment duration enforcing the >=5s traversal cap */
  private double minSegMs;

  // Pipe state (parallel arrays, MAX_PIPES slots)
  private final boolean[] pAlive = new boolean[MAX_PIPES];
  private final int[] pAx = new int[MAX_PIPES], pAy = new int[MAX_PIPES], pAz = new int[MAX_PIPES];
  private final int[] pBx = new int[MAX_PIPES], pBy = new int[MAX_PIPES], pBz = new int[MAX_PIPES];
  private final double[] pFrac = new double[MAX_PIPES];
  private final double[] pSegMs = new double[MAX_PIPES];
  private final boolean[] pWaiting = new boolean[MAX_PIPES];
  private final double[] pWaitMs = new double[MAX_PIPES];
  private final float[] pHue = new float[MAX_PIPES];

  /** Scratch for free-neighbor direction candidates */
  private final int[] dirCandidates = new int[6];

  // Elbow sparkle ring buffer (cell-center coordinates)
  private final float[] elbowX = new float[ELBOW_HISTORY];
  private final float[] elbowY = new float[ELBOW_HISTORY];
  private final float[] elbowZ = new float[ELBOW_HISTORY];
  private int elbowCount = 0, elbowNext = 0;
  private double sparkleLevel = 0;

  // Drain state
  private boolean draining = false;
  private double drainRemainMs = 0;
  private int drainCount = 0; // advances the palette color index each drain

  public Pipes3D(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("drain", this.drain);
    addParameter("teleport", this.teleport);
    addParameter("newPipe", this.newPipe);
    addParameter("sparkle", this.sparkle);
    addParameter("energy", this.energy);
    addParameter("thickness", this.thickness);
    addParameter("density", this.density);
    addParameter("hue", this.hue);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    bag.jumpable(this.thickness);
    bag.jumpable(this.density);
    bag.jumpable(this.hue);
    // Curated musical subrange only: full Division range runs up to 16 bars,
    // which would quantize a segment to tens of seconds. CURATE: unverified
    // visually — confirm a 1/16..1/2 grid jump reads as a groove change.
    bag.jumpable(this.tempoDiv,
      Tempo.Division.SIXTEENTH.ordinal(), Tempo.Division.HALF.ordinal());

    applyDensity();
    clearRoom();
    spawnPipe();
  }

  // ---- Room / lifecycle -------------------------------------------------------

  /** Read the density parameter into grid dimensions. Only at construction/drain. */
  private void applyDensity() {
    this.gx = this.density.getValuei();
    this.gz = this.gx;
    this.gy = Math.round(this.gx * (float) H / W); // keeps cells ~square (10 -> 9)
    this.cw = (double) W / this.gx;
    this.ch = (double) H / this.gy;
    // A wall crossing is gx cells; budget the per-cell floor so that even
    // with the max audio boost (x1.3 rate) and a max retime speed-up (x1.4)
    // the tip still needs >= TRAVERSAL_MIN_MS to cross a full wall
    this.minSegMs = TRAVERSAL_MIN_MS * (1 + LEVEL_RATE_BOOST) * TempoLock.DEFAULT_MAX_SCALE / this.gx;
  }

  /** Clear buffers and occupancy. Event-rate only (construction / drain end). */
  private void clearRoom() {
    for (int w = 0; w < WALLS; ++w) {
      Arrays.fill(this.colorBuf[w], LXColor.BLACK);
      Arrays.fill(this.depthBuf[w], Float.MAX_VALUE);
    }
    for (boolean[][] plane : this.occupied) {
      for (boolean[] row : plane) {
        Arrays.fill(row, false);
      }
    }
    this.occupiedCount = 0;
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

  /** Pipe color at segment start: palette color (advanced per drain) + hue offset. */
  private float pipeHue(int i) {
    float base = 0;
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    if (!swatch.isEmpty()) {
      base = LXColor.h(swatch.get(this.drainCount % swatch.size()).getColor());
    }
    float h = (float) ((base + this.hue.getValue() + i * PIPE_HUE_SPREAD) % 360);
    return (h < 0) ? h + 360 : h;
  }

  /**
   * Nominal duration for a new segment: energy interpolates 2s -> 1s per
   * cell, floored by the density-aware traversal cap. With Sync on, the
   * duration additionally rounds up to a whole number of TempoDiv divisions;
   * beginSegment() then phase-aligns it to the engine grid when growth
   * actually starts.
   */
  private double nextSegmentMs() {
    final double e = this.energy.getValue();
    double ms = Math.max(Ranges.lin(e, SEG_MS_AMBIENT, SEG_MS_PEAK), this.minSegMs);
    if (this.sync.isOn()) {
      final double divMs = this.tempoLock.divisionMs(this.tempoDiv.getEnum());
      if (divMs > 1) {
        ms = Math.ceil(ms / divMs) * divMs;
      }
    }
    return ms;
  }

  /**
   * Growth of the current segment is actually starting now (immediately after
   * an advance, or on the frame a grid-gate wait ends). With Sync on, nudge
   * the duration (rate scale clamped to [0.7, 1.4]) so the cell completes
   * exactly on a TempoDiv boundary, phase-aligned to the real engine beat.
   * Errors never accumulate: each segment re-aligns against Tempo.getBasis.
   */
  private void beginSegment(int i) {
    if (this.sync.isOn()) {
      // Estimate the actual arrival time: updatePipes grows at rate
      // (1 + LEVEL_RATE_BOOST * level), so fold the boost in (level sampled
      // now — exact for steady level, approximate while it changes). Without
      // this, completion would land up to 23% early of the retimed boundary
      // whenever the Audio knob is up; at Audio = 0 the divisor is 1.
      final double msUntil = this.pSegMs[i] / (1 + LEVEL_RATE_BOOST * this.audio.level);
      this.pSegMs[i] /= this.tempoLock.retime(msUntil, this.tempoDiv.getEnum());
    }
  }

  /** Place pipe i at a fresh free cell: cap ball, degenerate segment; advance() picks a direction. */
  private boolean placePipe(int i, int x, int y, int z) {
    occupy(x, y, z);
    this.pAlive[i] = true;
    this.pAx[i] = this.pBx[i] = x;
    this.pAy[i] = this.pBy[i] = y;
    this.pAz[i] = this.pBz[i] = z;
    this.pFrac[i] = 1;
    this.pWaiting[i] = false;
    this.pWaitMs[i] = 0;
    this.pSegMs[i] = nextSegmentMs();
    this.pHue[i] = pipeHue(i);
    rasterBall(x + 0.5, y + 0.5, z + 0.5, this.pHue[i]);
    return true;
  }

  /** Random free cell that has at least one free neighbor, or x = -1 in result. */
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
    // Cap ball at the disconnect point
    rasterBall(this.pBx[i] + 0.5, this.pBy[i] + 0.5, this.pBz[i] + 0.5, this.pHue[i]);
    if (findFreeCell()) {
      placePipe(i, this.rlX, this.rlY, this.rlZ);
    } else {
      startDrain();
    }
  }

  // Trigger + auto: fade everything over 3s, then restart in the next palette color
  private void startDrain() {
    if (!this.draining) {
      this.draining = true;
      this.drainRemainMs = DRAIN_MS;
      LX.log("Pipes3D: drain started (fill " + this.occupiedCount + " cells)");
    }
  }

  /** Drain finished: fresh room at the (possibly jumped) density, next palette color. */
  private void finishDrain() {
    this.draining = false;
    ++this.drainCount;
    int alive = 0;
    for (int i = 0; i < MAX_PIPES; ++i) {
      if (this.pAlive[i]) {
        ++alive;
      }
      this.pAlive[i] = false;
    }
    applyDensity(); // density jumps take effect here
    clearRoom();
    for (int i = 0; i < Math.max(1, alive); ++i) {
      if (findFreeCell()) {
        placePipe(i, this.rlX, this.rlY, this.rlZ);
      }
    }
    LX.log("Pipes3D: drain complete; density " + this.gx + "x" + this.gy + "x" + this.gz);
  }

  // ---- Walk logic --------------------------------------------------------------

  private int dirIndex(int dx, int dy, int dz) {
    for (int d = 0; d < 6; ++d) {
      if (DX[d] == dx && DY[d] == dy && DZ[d] == dz) {
        return d;
      }
    }
    return -1;
  }

  /**
   * The head cell B just completed. Choose the next cell (self-avoiding, with a
   * preference for continuing straight), draw the elbow ball on turns, or
   * teleport when boxed in.
   */
  private void advancePipe(int i) {
    final int bx = this.pBx[i], by = this.pBy[i], bz = this.pBz[i];
    final int straight = dirIndex(bx - this.pAx[i], by - this.pAy[i], bz - this.pAz[i]);

    int n = 0;
    boolean straightFree = false;
    for (int d = 0; d < 6; ++d) {
      int nx = bx + DX[d], ny = by + DY[d], nz = bz + DZ[d];
      if (inBounds(nx, ny, nz) && !this.occupied[nx][ny][nz]) {
        this.dirCandidates[n++] = d;
        if (d == straight) {
          straightFree = true;
        }
      }
    }

    if (n == 0) {
      // Boxed in: the classic behavior — teleport and keep going
      teleportPipe(i);
      return;
    }

    int dir;
    if (straightFree && this.random.nextDouble() < P_STRAIGHT) {
      dir = straight;
    } else {
      dir = this.dirCandidates[this.random.nextInt(n)];
    }

    if ((straight >= 0) && (dir != straight)) {
      // Turning: bright ball joint at the corner, remembered for treble sparkles
      rasterBall(bx + 0.5, by + 0.5, bz + 0.5, this.pHue[i]);
      recordElbow(bx + 0.5f, by + 0.5f, bz + 0.5f);
    }

    this.pAx[i] = bx;   this.pAy[i] = by;   this.pAz[i] = bz;
    this.pBx[i] = bx + DX[dir];
    this.pBy[i] = by + DY[dir];
    this.pBz[i] = bz + DZ[dir];
    occupy(this.pBx[i], this.pBy[i], this.pBz[i]);
    this.pFrac[i] = 0;
    this.pSegMs[i] = nextSegmentMs();
    this.pHue[i] = pipeHue(i);
    // High energy with Sync on: hold the new segment for the next grid
    // boundary (or bass hit); otherwise growth starts immediately
    this.pWaiting[i] = this.sync.isOn() && (this.energy.getValue() > HIGH_ENERGY);
    this.pWaitMs[i] = 0;
    if (!this.pWaiting[i]) {
      beginSegment(i);
    }

    if (this.occupiedCount > FILL_LIMIT * this.gx * this.gy * this.gz) {
      startDrain();
    }
  }

  private void recordElbow(float x, float y, float z) {
    this.elbowX[this.elbowNext] = x;
    this.elbowY[this.elbowNext] = y;
    this.elbowZ[this.elbowNext] = z;
    this.elbowNext = (this.elbowNext + 1) % ELBOW_HISTORY;
    this.elbowCount = Math.min(this.elbowCount + 1, ELBOW_HISTORY);
  }

  // ---- Rasterization -------------------------------------------------------------
  //
  // Wall mappings (corner-continuous so all four walls show one shared object):
  //   wall 0 front: u = x            v = y   depth = z
  //   wall 1 right: u = z            v = y   depth = gx - x
  //   wall 2 back:  u = gx - x       v = y   depth = gz - z
  //   wall 3 left:  u = gz - z       v = y   depth = x
  // Cell coordinates are continuous (cell k spans [k, k+1]); u,v scale by cw,ch
  // into the 50x45 pixel grid. Depth normalizes by gz (== gx) into [0,1].

  /** Current pipe thickness in px, clamped to the cell size so lattice cells stay distinct */
  private double thicknessPx() {
    return Math.min(this.thickness.getValue(), Math.min(this.cw, this.ch));
  }

  /**
   * Rasterize the growing segment of pipe i into all four wall buffers: an
   * axis-aligned box from the center of cell A toward the center of cell B,
   * truncated at the current extrusion fraction. Monotonic — each frame strictly
   * extends the previous frame's pixels, so drawing into the persistent buffers
   * is idempotent and completed geometry never needs re-rendering.
   */
  private void rasterSegment(int i) {
    final double frac = Math.min(this.pFrac[i], 1);
    final double axc = this.pAx[i] + 0.5, ayc = this.pAy[i] + 0.5, azc = this.pAz[i] + 0.5;
    final double exc = axc + (this.pBx[i] - this.pAx[i]) * frac;
    final double eyc = ayc + (this.pBy[i] - this.pAy[i]) * frac;
    final double ezc = azc + (this.pBz[i] - this.pAz[i]) * frac;

    double x0 = Math.min(axc, exc), x1 = Math.max(axc, exc);
    double y0 = Math.min(ayc, eyc), y1 = Math.max(ayc, eyc);
    double z0 = Math.min(azc, ezc), z1 = Math.max(azc, ezc);

    final int axis = (this.pBx[i] != this.pAx[i]) ? 0 : (this.pBy[i] != this.pAy[i]) ? 1 : 2;
    final double t = thicknessPx();
    final double htx = t / 2 / this.cw; // half-thickness in horizontal cell units
    final double hty = t / 2 / this.ch; // half-thickness in vertical cell units

    switch (axis) {
      case 0: y0 -= hty; y1 += hty; z0 -= htx; z1 += htx; break;
      case 1: x0 -= htx; x1 += htx; z0 -= htx; z1 += htx; break;
      default: x0 -= htx; x1 += htx; y0 -= hty; y1 += hty; break;
    }
    rasterBox(x0, x1, y0, y1, z0, z1, axis, this.pHue[i], false);
  }

  /** Bright sphere joint: a slightly-oversized box at a cell center */
  private void rasterBall(double cx, double cy, double cz, float hueDeg) {
    final double hx = (thicknessPx() / 2 + BALL_EXTRA_PX) / this.cw;
    final double hy = (thicknessPx() / 2 + BALL_EXTRA_PX) / this.ch;
    rasterBox(cx - hx, cx + hx, cy - hy, cy + hy, cz - hx, cz + hx, -1, hueDeg, true);
  }

  /**
   * Project an axis-aligned box (continuous cell coordinates) onto each wall
   * with depth test (nearer wins). Shading: brightness = 1 - 0.5 * depth, plus
   * a 1 px desaturated highlight stripe along the pipe axis (skipped when the
   * pipe is seen end-on). Balls render brighter with no stripe.
   */
  private void rasterBox(double x0, double x1, double y0, double y1, double z0, double z1,
                         int axis, float hueDeg, boolean ball) {
    final double depthRange = this.gz; // == gx: square room footprint
    for (int w = 0; w < WALLS; ++w) {
      double u0, u1, d;
      int uAxis; // which 3D axis maps to this wall's horizontal pixel axis
      switch (w) {
        case 0:  u0 = x0 * this.cw;             u1 = x1 * this.cw;             d = z0;           uAxis = 0; break;
        case 1:  u0 = z0 * this.cw;             u1 = z1 * this.cw;             d = this.gx - x1; uAxis = 2; break;
        case 2:  u0 = (this.gx - x1) * this.cw; u1 = (this.gx - x0) * this.cw; d = this.gz - z1; uAxis = 0; break;
        default: u0 = (this.gz - z1) * this.cw; u1 = (this.gz - z0) * this.cw; d = x0;           uAxis = 2; break;
      }
      final float dn = (float) Math.max(0, Math.min(1, d / depthRange));
      final float bf = 1 - DEPTH_DIM * dn;

      final int colMain, colStripe;
      if (ball) {
        colMain = LXColor.hsb(hueDeg, BALL_SAT, Math.min(100f, 100f * bf * BALL_BOOST));
        colStripe = colMain;
      } else {
        colMain = LXColor.hsb(hueDeg, 100f, 100f * bf);
        colStripe = LXColor.hsb(hueDeg, STRIPE_SAT, Math.min(100f, 100f * bf * STRIPE_BOOST));
      }

      int iu0 = (int) Math.round(u0), iu1 = (int) Math.round(u1) - 1;
      int iv0 = (int) Math.round(y0 * this.ch), iv1 = (int) Math.round(y1 * this.ch) - 1;
      if (iu1 < iu0) iu1 = iu0;
      if (iv1 < iv0) iv1 = iv0;
      if (iu1 < 0 || iu0 >= W || iv1 < 0 || iv0 >= H) {
        continue;
      }
      // Highlight stripe: 1 px inside the top/left edge, along the pipe axis
      final int stripeV = (!ball && (axis == uAxis)) ? Math.min(iv0 + 1, iv1) : -1;
      final int stripeU = (!ball && (axis == 1)) ? Math.min(iu0 + 1, iu1) : -1;
      iu0 = Math.max(iu0, 0); iu1 = Math.min(iu1, W - 1);
      iv0 = Math.max(iv0, 0); iv1 = Math.min(iv1, H - 1);

      final int[] cBuf = this.colorBuf[w];
      final float[] dBuf = this.depthBuf[w];
      for (int v = iv0; v <= iv1; ++v) {
        final int rowBase = v * W;
        for (int u = iu0; u <= iu1; ++u) {
          final int idx = rowBase + u;
          if (dn <= dBuf[idx]) {
            dBuf[idx] = dn;
            cBuf[idx] = (v == stripeV || u == stripeU) ? colStripe : colMain;
          }
        }
      }
    }
  }

  // ---- Render ---------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK); // cylinder (and anything unmapped) stays dark in v1

    double outputScale = 1;
    if (this.draining) {
      this.drainRemainMs -= deltaMs;
      if (this.drainRemainMs <= 0) {
        finishDrain();
      } else {
        outputScale = this.drainRemainMs / DRAIN_MS;
      }
    } else {
      updatePipes(deltaMs);
    }

    // Treble sparkle envelope on recent elbows; the hit response scales with
    // the Audio depth knob (max() so a manual Sparkle trigger is not dimmed)
    if (this.audio.trebleHit()) {
      this.sparkleLevel = Math.max(this.sparkleLevel, this.audio.depth());
    } else {
      this.sparkleLevel = Math.max(0, this.sparkleLevel - deltaMs / SPARKLE_MS);
    }

    blit(outputScale);
    if (this.sparkleLevel > 0 && this.elbowCount > 0) {
      sparkleOverlay(outputScale);
    }
    copyCubeExterior(); // interior faces are copies of the exterior render
  }

  private void updatePipes(double deltaMs) {
    final double e = this.energy.getValue();
    final boolean syncOn = this.sync.isOn();
    // Poll the grid gate every frame, even with Sync off (result unused), so
    // its crossing state never goes stale — the old short-circuit form
    // (syncOn && crossed()) reported a boundary that elapsed while Sync was
    // off and opened the gate spuriously on re-enable
    final boolean crossedNow = this.tempoLock.crossed(this.tempoDiv.getEnum());
    final boolean gridCross = syncOn && crossedNow;
    // Gate opens on a TempoDiv grid crossing or a bass transient; only Sync-on
    // high energy ever waits (Sync off is fully free-running)
    final boolean gateOpen = !syncOn
      || (e <= HIGH_ENERGY)
      || gridCross
      || this.audio.bassHit();

    for (int i = 0; i < MAX_PIPES; ++i) {
      if (!this.pAlive[i]) {
        continue;
      }
      if (this.pWaiting[i]) {
        this.pWaitMs[i] += deltaMs;
        if (gateOpen || (this.pWaitMs[i] >= BEAT_WAIT_TIMEOUT_MS)) {
          this.pWaiting[i] = false;
          beginSegment(i); // growth starts now: phase-align the duration
        } else {
          continue; // holding for the grid boundary
        }
      }
      // Audio level gives growth a modest push; silence -> base rate
      this.pFrac[i] += (deltaMs / this.pSegMs[i]) * (1 + LEVEL_RATE_BOOST * this.audio.level);
      rasterSegment(i);
      if (this.pFrac[i] >= 1) {
        advancePipe(i); // may teleport or start the drain
        if (this.draining) {
          return;
        }
      }
    }
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
    // Visibility tolerance: the elbow's own ball wrote the depth buffer with
    // its NEAR-face depth — up to (THICK_MAX/2 + BALL_EXTRA_PX) px nearer
    // than the cell-center depth tested here. Anything nearer than that
    // margin is a genuinely occluding pipe (occluders are >= 1 cell nearer).
    final float tol = (float) (((0.5 * THICK_MAX + BALL_EXTRA_PX) / this.cw + 0.05) / this.gz);
    for (int e = 0; e < this.elbowCount; ++e) {
      final double x = this.elbowX[e], y = this.elbowY[e], z = this.elbowZ[e];
      for (int w = 0; w < WALLS; ++w) {
        double uc, d;
        switch (w) {
          case 0:  uc = x * this.cw;             d = z;           break;
          case 1:  uc = z * this.cw;             d = this.gx - x; break;
          case 2:  uc = (this.gx - x) * this.cw; d = this.gz - z; break;
          default: uc = (this.gz - z) * this.cw; d = x;           break;
        }
        final float dn = (float) (d / this.gz);
        final int u = (int) Math.round(uc - 0.5);
        final int v = (int) Math.round(y * this.ch - 0.5);
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
