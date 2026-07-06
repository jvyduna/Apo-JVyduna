package apotheneum.jvyduna.patterns;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.PerceptualHue;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundDiscreteParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * A virtual Rubik's Cube that continuously scrambles and solves, projected onto
 * the four walls of the outer cube. The projection is a central (gnomonic)
 * projection from the installation's center: a ray is cast from the center
 * through each wall LED and intersected with a virtual 3x3x3 cube. At rest each
 * wall shows a flat, undistorted 3x3 face; during a turn the moving slice's
 * cubies rotate in true 3D, so stickers foreshorten, bulge toward the corners
 * and tip over the top/bottom edges. Motion is tempo-locked and, when solved,
 * the cube pulses to the beat.
 *
 * See Rubik.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Rubik")
@LXComponent.Description("A virtual Rubik's Cube that scrambles and solves, projected from the center onto the four walls")
public class Rubik extends ApotheneumPattern {

  // ---- Motion easing curves -------------------------------------------------

  public enum Easing {
    DECELERATE("Decel") { public double apply(double t) { return 1 - (1 - t) * (1 - t); } },
    ACCELERATE("Accel") { public double apply(double t) { return t * t; } },
    SMOOTHSTEP("Smooth") { public double apply(double t) { return t * t * (3 - 2 * t); } },
    LINEAR("Linear") { public double apply(double t) { return t; } };

    public final String label;
    private Easing(String label) { this.label = label; }
    public abstract double apply(double t);

    @Override
    public String toString() { return this.label; }
  }

  public enum Surface {
    OUTER("Outer"),
    INNER("Inner"),
    BOTH("Both");

    public final String label;
    private Surface(String label) { this.label = label; }

    @Override
    public String toString() { return this.label; }
  }

  /** Which way a reset animates: toward solved, or toward scrambled. */
  public enum Direction {
    SOLVING("Solving"),
    SCRAMBLING("Scrambling");

    public final String label;
    private Direction(String label) { this.label = label; }

    @Override
    public String toString() { return this.label; }
  }

  // ---- Parameters -----------------------------------------------------------

  public final TriggerParameter reset =
    new TriggerParameter("Reset", this::reset)
    .setDescription("Restart the sequence in the current Direction");

  public final EnumParameter<Direction> direction =
    new EnumParameter<Direction>("Direction", Direction.SOLVING)
    .setDescription("On reset: Solving jumps to a scrambled state and solves; Scrambling starts solved and scrambles");

  public final CompoundDiscreteParameter steps =
    new CompoundDiscreteParameter("Steps", 16, 1, 65)
    .setDescription("Number of turns used to scramble (and therefore to solve)");

  public final EnumParameter<Tempo.Division> division =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division for one turn (and the solved beat pulse)");

  public final CompoundParameter movingDuty =
    new CompoundParameter("MoveDuty", 0.6, 0.05, 1)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Fraction of each cycle spent turning vs. paused");

  public final CompoundParameter phaseOffset =
    new CompoundParameter("Phase", 0, 0, 1)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Shifts the turn cycle relative to the tempo grid");

  public final EnumParameter<Easing> easing =
    new EnumParameter<Easing>("Ease", Easing.DECELERATE)
    .setDescription("Shape of the turn motion");

  public final CompoundParameter gap =
    new CompoundParameter("Gap", 0.14, 0, 1)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Gap between stickers (0 = tiles touch; 100% = maximum gap, stickers disappear)");

  public final CompoundParameter edgeFade =
    new CompoundParameter("Fade", 0.25, 0, 1)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Softness of the tile edges (0 = hard, 1 = only the center is full bright)");

  public final BooleanParameter beamMode =
    new BooleanParameter("Beam", false)
    .setDescription("Projection: off = gnomonic perspective from center (bulges during turns); on = orthographic collimated emitters — each sticker a 0°-beam searchlight, free of perspective bulge");

  public final CompoundParameter scale =
    new CompoundParameter("Scale", 1, 0, 4)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Overall scale of the projection (100% = fills the wall). In Beam mode: >100% is reverse-projected but clipped at center");

  public final CompoundParameter yCenter =
    new CompoundParameter("YCenter", 0, -1, 1)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Vertical center of the projected image (0 = wall center; + moves it up)");

  public final CompoundParameter yFloor =
    new CompoundParameter("YFloor", -1, -1, 2)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Floor below which LEDs are muted: -100% = 3 rows below the image; 0% = just below the bottom row; 100% = just above the top row; 200% = 3 rows above the image");

  public final EnumParameter<Surface> surfaces =
    new EnumParameter<Surface>("Surface", Surface.OUTER)
    .setDescription("Which surfaces to render onto");

  public Rubik(LX lx) {
    super(lx);
    addParameter("reset", this.reset);
    addParameter("direction", this.direction);
    addParameter("steps", this.steps);
    addParameter("division", this.division);
    addParameter("movingDuty", this.movingDuty);
    addParameter("phaseOffset", this.phaseOffset);
    addParameter("easing", this.easing);
    addParameter("gap", this.gap);
    addParameter("edgeFade", this.edgeFade);
    addParameter("beamMode", this.beamMode);
    addParameter("scale", this.scale);
    addParameter("yCenter", this.yCenter);
    addParameter("yFloor", this.yFloor);
    addParameter("surfaces", this.surfaces);

    reset();
  }

  // ---- Logical cube model ---------------------------------------------------

  /** faceId for an outward unit direction. +X=0(R) -X=1(L) +Y=2(U) -Y=3(D) +Z=4(B) -Z=5(F). */
  private static int faceId(int dx, int dy, int dz) {
    if (dx == 1) return 0;
    if (dx == -1) return 1;
    if (dy == 1) return 2;
    if (dy == -1) return 3;
    if (dz == 1) return 4;
    return 5;
  }

  private static final class Sticker {
    int dx, dy, dz;      // outward unit normal in {-1,0,1}^3
    final int colorId;   // 0..5, the face this sticker belongs to when solved
    Sticker(int dx, int dy, int dz, int colorId) {
      this.dx = dx; this.dy = dy; this.dz = dz; this.colorId = colorId;
    }
  }

  private static final class Cubie {
    int px, py, pz;      // cell position in {-1,0,1}^3
    final List<Sticker> stickers = new ArrayList<>(3);
    Cubie(int px, int py, int pz) { this.px = px; this.py = py; this.pz = pz; }
  }

  /** A single 90-degree layer turn. */
  private static final class Move {
    final int axis;      // 0=X 1=Y 2=Z
    final int layer;     // -1 or +1 (the outer layer on that axis)
    final int sign;      // +1 or -1 turn direction
    Move(int axis, int layer, int sign) { this.axis = axis; this.layer = layer; this.sign = sign; }
    Move inverse() { return new Move(this.axis, this.layer, -this.sign); }
  }

  private final List<Cubie> cubies = new ArrayList<>(27);
  private final List<Move> lastScramble = new ArrayList<>();
  private final Random random = new Random();

  private Move currentMove = null;

  // Travel along the fixed path solved(0) <-> scrambled(lastScramble.size()).
  // pos = forward moves committed from solved; currentAdvance = how the in-flight
  // move changes pos when committed (+1 scrambling, -1 solving, 0 holding). Because
  // position (not a one-way queue) drives selection, Direction can flip live.
  private int pos = 0;
  private int currentAdvance = 0;

  /** Divisions to hold the current (solved) state before starting to animate. */
  private int holdDivisions = 0;

  /** Candidate scrambles evaluated per reset; the most visually mixed one wins. */
  private static final int SCRAMBLE_TRIALS = 5;
  private final int[][] entropyCounts = new int[6][6]; // [faceId][colorId], reset-time scratch

  private void buildSolved() {
    this.cubies.clear();
    for (int x = -1; x <= 1; ++x) {
      for (int y = -1; y <= 1; ++y) {
        for (int z = -1; z <= 1; ++z) {
          if (x == 0 && y == 0 && z == 0) {
            continue; // core has no stickers
          }
          Cubie c = new Cubie(x, y, z);
          if (x != 0) c.stickers.add(new Sticker(x, 0, 0, faceId(x, 0, 0)));
          if (y != 0) c.stickers.add(new Sticker(0, y, 0, faceId(0, y, 0)));
          if (z != 0) c.stickers.add(new Sticker(0, 0, z, faceId(0, 0, z)));
          this.cubies.add(c);
        }
      }
    }
  }

  /** Rotate an integer vector 90*sign degrees about the given axis. */
  private static void iRot(int[] v, int axis, int sign) {
    int x = v[0], y = v[1], z = v[2];
    switch (axis) {
      case 0: v[0] = x;         v[1] = -sign * z; v[2] = sign * y;  break; // X
      case 1: v[0] = sign * z;  v[1] = y;         v[2] = -sign * x; break; // Y
      default: v[0] = -sign * y; v[1] = sign * x; v[2] = z;         break; // Z
    }
  }

  private final int[] scratch = new int[3];

  /** Commit a completed turn to the logical state. */
  private void applyMove(Move m) {
    for (Cubie c : this.cubies) {
      int coord = (m.axis == 0) ? c.px : (m.axis == 1) ? c.py : c.pz;
      if (coord != m.layer) {
        continue;
      }
      scratch[0] = c.px; scratch[1] = c.py; scratch[2] = c.pz;
      iRot(scratch, m.axis, m.sign);
      c.px = scratch[0]; c.py = scratch[1]; c.pz = scratch[2];
      for (Sticker s : c.stickers) {
        scratch[0] = s.dx; scratch[1] = s.dy; scratch[2] = s.dz;
        iRot(scratch, m.axis, m.sign);
        s.dx = scratch[0]; s.dy = scratch[1]; s.dz = scratch[2];
      }
    }
  }

  private boolean isSolved() {
    // Each of the 6 outward face directions must carry a single color.
    int[] faceColor = { -2, -2, -2, -2, -2, -2 };
    for (Cubie c : this.cubies) {
      for (Sticker s : c.stickers) {
        int f = faceId(s.dx, s.dy, s.dz);
        if (faceColor[f] == -2) {
          faceColor[f] = s.colorId;
        } else if (faceColor[f] != s.colorId) {
          return false;
        }
      }
    }
    return true;
  }

  /** Build one random scramble of n moves into {@code out} (no immediate axis repeats). */
  private void randomScramble(int n, List<Move> out) {
    out.clear();
    int prevAxis = -1;
    for (int i = 0; i < n; ++i) {
      int axis;
      do {
        axis = this.random.nextInt(3);
      } while (axis == prevAxis);
      prevAxis = axis;
      int layer = this.random.nextBoolean() ? 1 : -1;
      int sign = this.random.nextBoolean() ? 1 : -1;
      out.add(new Move(axis, layer, sign));
    }
  }

  /**
   * Visual-mix score of the current cube state: summed Shannon entropy of the
   * sticker-color histogram on each of the 6 visible faces. A solved cube scores
   * 0 (each face one color); a well-shuffled cube scores higher.
   */
  private double faceEntropy() {
    for (int[] row : this.entropyCounts) {
      java.util.Arrays.fill(row, 0);
    }
    for (Cubie c : this.cubies) {
      for (Sticker s : c.stickers) {
        this.entropyCounts[faceId(s.dx, s.dy, s.dz)][s.colorId]++;
      }
    }
    double total = 0;
    for (int f = 0; f < 6; ++f) {
      for (int k = 0; k < 6; ++k) {
        int n = this.entropyCounts[f][k];
        if (n > 0) {
          double p = n / 9.0;
          total -= p * Math.log(p);
        }
      }
    }
    return total;
  }

  private final List<Move> scrambleCandidate = new ArrayList<>();

  /**
   * Choose the most visually random of {@link #SCRAMBLE_TRIALS} candidate scrambles
   * (highest {@link #faceEntropy()}) and store it in {@link #lastScramble}. Leaves
   * the cube solved; the caller decides which direction to animate.
   */
  private void computeBestScramble() {
    int n = this.steps.getValuei();
    double bestScore = -1;
    this.lastScramble.clear();
    for (int t = 0; t < SCRAMBLE_TRIALS; ++t) {
      randomScramble(n, this.scrambleCandidate);
      buildSolved();
      for (Move m : this.scrambleCandidate) {
        applyMove(m);
      }
      double score = faceEntropy();
      if (score > bestScore) {
        bestScore = score;
        this.lastScramble.clear();
        this.lastScramble.addAll(this.scrambleCandidate);
      }
    }
    buildSolved();
  }

  /**
   * Pick the move for the coming division from the current {@link #pos} and
   * {@link #direction}: step forward toward scrambled, backward toward solved, or
   * hold (null) at the endpoint in that direction. Reading Direction here — rather
   * than a fixed queue — is what lets Direction flip live mid-sequence.
   */
  private void selectMove() {
    boolean scrambling = (this.direction.getEnum() == Direction.SCRAMBLING);
    if (scrambling && this.pos < this.lastScramble.size()) {
      this.currentMove = this.lastScramble.get(this.pos);
      this.currentAdvance = +1;
    } else if (!scrambling && this.pos > 0) {
      this.currentMove = this.lastScramble.get(this.pos - 1).inverse();
      this.currentAdvance = -1;
    } else {
      this.currentMove = null;   // at the endpoint for this direction; hold
      this.currentAdvance = 0;
    }
  }

  /**
   * Restart the sequence in the current {@link #direction}. Both directions travel
   * the same high-entropy scramble path (best of {@link #SCRAMBLE_TRIALS}):
   * <ul>
   *   <li>SOLVING — jump to the scrambled endpoint, then animate toward solved;
   *       ends solved and pulses.</li>
   *   <li>SCRAMBLING — stay solved (held one division so it reads as solved), then
   *       animate toward the scrambled endpoint.</li>
   * </ul>
   * Direction may be flipped live afterward; travel reverses at the next division.
   */
  private void reset() {
    computeBestScramble(); // fills lastScramble; leaves the cube solved (pos 0)
    this.holdDivisions = 0;
    this.currentAdvance = 0;
    if (this.direction.getEnum() == Direction.SOLVING) {
      // Jump straight to the scrambled endpoint, then animate toward solved.
      for (Move m : this.lastScramble) {
        applyMove(m);
      }
      this.pos = this.lastScramble.size();
      selectMove();
    } else { // SCRAMBLING
      // Stay solved; hold one division so viewers register the solved start.
      this.pos = 0;
      this.holdDivisions = 1;
      this.currentMove = null;
    }
    this.prevCyclePhase = -1;
  }

  // ---- Tempo cycle tracking -------------------------------------------------

  private double prevCyclePhase = -1;

  // ---- Geometry (installation center + scale), computed once ---------------

  private boolean geometryReady = false;
  private double cx, cy, cz, sxz, sy;

  private void computeGeometry() {
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
    for (Apotheneum.Cube.Face face : Apotheneum.cube.exterior.faces) {
      for (LXPoint p : face.model.points) {
        minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
        minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
      }
    }
    this.cx = 0.5 * (minX + maxX);
    this.cy = 0.5 * (minY + maxY);
    this.cz = 0.5 * (minZ + maxZ);
    // Horizontal extent (center to wall) and vertical extent, normalized so a
    // wall spans [-1, 1] in both axes => a solved wall exactly fills with a 3x3.
    this.sxz = 0.25 * ((maxX - minX) + (maxZ - minZ));
    this.sy = 0.5 * (maxY - minY);
    this.geometryReady = true;
  }

  // ---- Per-frame render sticker geometry (rebuilt in place, no allocation) --

  private static final int MAX_STICKERS = 54;
  private final double[][] rc = new double[MAX_STICKERS][3]; // sticker centers
  private final double[][] ru = new double[MAX_STICKERS][3]; // half-edge U
  private final double[][] rv = new double[MAX_STICKERS][3]; // half-edge V
  private final double[][] rn = new double[MAX_STICKERS][3]; // outward normal
  private final int[] rColor = new int[MAX_STICKERS];
  private int rCount = 0;

  private static final double CELL = 2.0 / 3.0;   // cubie spacing in [-1,1]
  private static final double HALF = 1.0 / 3.0;   // half tile width

  /** Rotate (x,y,z) about axis (cos c, sin s) into out. */
  private static void fRot(double[] out, double x, double y, double z, int axis, double c, double s) {
    switch (axis) {
      case 0: out[0] = x;              out[1] = c * y - s * z;  out[2] = s * y + c * z;  break; // X
      case 1: out[0] = c * x + s * z;  out[1] = y;              out[2] = -s * x + c * z; break; // Y
      default: out[0] = c * x - s * y; out[1] = s * x + c * y;  out[2] = z;              break; // Z
    }
  }

  private void buildRenderStickers(double angle) {
    this.rCount = 0;
    final boolean moving = (this.currentMove != null);
    final int mAxis = moving ? this.currentMove.axis : -1;
    final int mLayer = moving ? this.currentMove.layer : 0;
    final double c = Math.cos(angle);
    final double s = Math.sin(angle);

    for (Cubie cubie : this.cubies) {
      int coord = (mAxis == 0) ? cubie.px : (mAxis == 1) ? cubie.py : cubie.pz;
      boolean rotate = moving && (coord == mLayer);
      for (Sticker st : cubie.stickers) {
        int i = this.rCount++;
        // Home geometry: center on the outer face of this cell; U,V span the tile.
        double cxi = cubie.px * CELL + st.dx * HALF;
        double cyi = cubie.py * CELL + st.dy * HALF;
        double czi = cubie.pz * CELL + st.dz * HALF;
        // In-plane axes chosen from the normal.
        double ux, uy, uz, vx, vy, vz;
        if (st.dx != 0) {        // normal along X -> U=Y, V=Z
          ux = 0; uy = HALF; uz = 0; vx = 0; vy = 0; vz = HALF;
        } else if (st.dy != 0) { // normal along Y -> U=Z, V=X
          ux = 0; uy = 0; uz = HALF; vx = HALF; vy = 0; vz = 0;
        } else {                 // normal along Z -> U=X, V=Y
          ux = HALF; uy = 0; uz = 0; vx = 0; vy = HALF; vz = 0;
        }
        if (rotate) {
          fRot(this.rc[i], cxi, cyi, czi, mAxis, c, s);
          fRot(this.ru[i], ux, uy, uz, mAxis, c, s);
          fRot(this.rv[i], vx, vy, vz, mAxis, c, s);
          fRot(this.rn[i], st.dx, st.dy, st.dz, mAxis, c, s);
        } else {
          this.rc[i][0] = cxi; this.rc[i][1] = cyi; this.rc[i][2] = czi;
          this.ru[i][0] = ux;  this.ru[i][1] = uy;  this.ru[i][2] = uz;
          this.rv[i][0] = vx;  this.rv[i][1] = vy;  this.rv[i][2] = vz;
          this.rn[i][0] = st.dx; this.rn[i][1] = st.dy; this.rn[i][2] = st.dz;
        }
        this.rColor[i] = st.colorId;
      }
    }
  }

  // ---- Palette --------------------------------------------------------------

  private final int[] faceColor = new int[6];
  // Scratch for perceptual hue allocation (no per-frame heap): hueWork holds the
  // defined colors' perceptual positions plus the generated ones; hueOut receives
  // the generated positions.
  private final float[] hueWork = new float[8];
  private final float[] hueOut = new float[6];

  private void computeFaceColors() {
    List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    int defined = Math.min(swatch.size(), 5);
    if (swatch.size() >= 5) {
      // Full 5-color palette: faces 0..4 from the swatch, sixth = 50% white.
      for (int i = 0; i < 5; ++i) {
        this.faceColor[i] = swatch.get(i).getColor();
      }
      this.faceColor[5] = LXColor.hsb(0f, 0f, 50f); // 50% brightness white
      return;
    }
    // Fewer than 5 defined: keep the defined colors, then fill the remaining faces
    // with fully-saturated hues placed to make all six hues as perceptually
    // even (equal-jump) as possible, wrap-aware. Defined colors are anchored at
    // their own perceptual positions.
    for (int i = 0; i < defined; ++i) {
      int c = swatch.get(i).getColor();
      this.faceColor[i] = c;
      this.hueWork[i] = PerceptualHue.toPerceptualPosition(LXColor.h(c));
    }
    int generate = 6 - defined;
    PerceptualHue.fillCircle(this.hueWork, defined, generate, this.hueOut);
    for (int j = 0; j < generate; ++j) {
      this.faceColor[defined + j] = PerceptualHue.color(this.hueOut[j]);
    }
  }

  private static int scaleColor(int argb, double f) {
    if (f <= 0) return LXColor.BLACK;
    if (f > 1) f = 1;
    int r = (int) (((argb >> 16) & 0xff) * f);
    int g = (int) (((argb >> 8) & 0xff) * f);
    int b = (int) ((argb & 0xff) * f);
    return LXColor.rgba(r, g, b, 255);
  }

  // ---- Render ---------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    setColors(LXColor.BLACK);

    if (!this.geometryReady) {
      computeGeometry();
    }
    computeFaceColors();

    // Advance the tempo cycle and pick the active move / rotation angle.
    final Tempo.Division div = this.division.getEnum();
    double cyclePhase = this.lx.engine.tempo.getBasis(div) + this.phaseOffset.getValue();
    cyclePhase -= Math.floor(cyclePhase);

    if (this.prevCyclePhase >= 0 && cyclePhase < this.prevCyclePhase) {
      // Cycle wrapped: commit the finished move (advancing our path position),
      // then select the next one for the current Direction (which may have flipped).
      if (this.currentMove != null) {
        applyMove(this.currentMove);
        this.pos += this.currentAdvance;
      }
      if (this.holdDivisions > 0) {
        this.holdDivisions--;   // hold the (solved) state one more division
        this.currentMove = null;
        this.currentAdvance = 0;
      } else {
        selectMove();
      }
    }
    this.prevCyclePhase = cyclePhase;

    // Sub-phase within the moving portion of the cycle.
    double duty = this.movingDuty.getValue();
    double angle = 0;
    if (this.currentMove != null) {
      double sub = LXUtils.constrain(cyclePhase / duty, 0, 1);
      angle = this.easing.getEnum().apply(sub) * this.currentMove.sign * (Math.PI / 2);
    }

    boolean solvedNow = (this.currentMove == null) && isSolved();
    double pulse = solvedNow
      ? LXUtils.lerp(1.0, 0.5, this.lx.engine.tempo.getBasis(div))
      : 1.0;

    buildRenderStickers(angle);

    // gap is the gap amount (100% = stickers vanish); g is the colored square size.
    final double g = 1.0 - this.gap.getValue();
    final double feather = this.edgeFade.getValue() * g;

    // Overall scale + vertical recentering. yCenter shifts the vertical origin; the
    // vertical sample vy is shared by both projection modes and the floor test.
    final double s = Math.max(this.scale.getValue(), 1e-3);
    final double yc = this.yCenter.getValue();
    final boolean beam = this.beamMode.isOn();

    // YFloor mutes LEDs whose vertical vy falls below a floor line, measured in the
    // same frame as the sticker rows: rows span [-1,-1/3] (bottom), [-1/3,1/3]
    // (middle), [1/3,1] (top). Bipolar, default -100%: -100% = -3 (3 rows of
    // headroom below the image); 0% = -1 (just below the bottom row); 100% = +1
    // (just above the top row); 200% = +3 (3 rows above the image).
    final double floorLine = -1.0 + this.yFloor.getValue() * 2.0;

    for (Apotheneum.Cube.Face face : Apotheneum.cube.exterior.faces) {
      for (Apotheneum.Column column : face.columns) {
        for (LXPoint p : column.points) {
          double nx = (p.x - this.cx) / this.sxz;
          double vy = ((p.y - this.cy) / this.sy - yc) / s;
          if (vy < floorLine) {
            this.colors[p.index] = LXColor.BLACK;
            continue; // below the floor -> muted
          }
          double nz = (p.z - this.cz) / this.sxz;
          if (beam) {
            // Orthographic: scale is a true model resize, so the LED position is
            // sampled at P/s against the unscaled sticker geometry.
            this.colors[p.index] = projectLEDOrtho(nx / s, vy, nz / s, g, feather, pulse);
          } else {
            // Gnomonic: scale only the in-plane (transverse) horizontal axis; the
            // dominant horizontal axis is this wall's outward normal (|comp|~1).
            double dx = nx, dz = nz;
            if (Math.abs(dx) >= Math.abs(dz)) {
              dz /= s; // X-normal wall -> Z is the horizontal transverse axis
            } else {
              dx /= s; // Z-normal wall -> X is the horizontal transverse axis
            }
            this.colors[p.index] = projectLED(dx, vy, dz, g, feather, pulse);
          }
        }
      }
    }

    distributeSurfaces();
  }

  /**
   * Orthographic (collimated-emitter) projection: each sticker is a rectangular
   * beam along its own surface normal, 0° divergence, so there is no perspective
   * bulge. An LED at normalized position P is lit by a sticker when P projects
   * inside the sticker's u,v rectangle and lies in the sticker's outward hemisphere
   * (P·n >= 0 — the "cannot cross past center" clip that keeps a beam from wrapping
   * to the opposite wall). Nearest sticker (smallest |P-c|·n depth) wins. At
   * scale = 100% the emitter cube fills the installation (a giant flashlight);
   * < 100% projects smaller un-magnified rectangles; > 100% reverse-projects.
   */
  private int projectLEDOrtho(double px, double py, double pz, double g, double feather, double pulse) {
    double bestDepth = Double.MAX_VALUE;
    int bestColor = LXColor.BLACK;

    for (int i = 0; i < this.rCount; ++i) {
      double[] n = this.rn[i];
      if (px * n[0] + py * n[1] + pz * n[2] < 0) {
        continue; // behind the center plane for this emitter -> clipped
      }
      double[] cc = this.rc[i];
      double wx = px - cc[0];
      double wy = py - cc[1];
      double wz = pz - cc[2];
      double[] u = this.ru[i];
      double a = (wx * u[0] + wy * u[1] + wz * u[2]) / (u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
      if (a < -1 || a > 1) {
        continue;
      }
      double[] v = this.rv[i];
      double b = (wx * v[0] + wy * v[1] + wz * v[2]) / (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
      if (b < -1 || b > 1) {
        continue;
      }
      double depth = Math.abs(wx * n[0] + wy * n[1] + wz * n[2]);
      if (depth < bestDepth) {
        bestDepth = depth;
        double mask = tileMask(a, g, feather) * tileMask(b, g, feather);
        bestColor = scaleColor(this.faceColor[this.rColor[i]], mask * pulse);
      }
    }
    return bestColor;
  }

  /** Cast a ray from the center along (dx,dy,dz), return the color of the nearest visible sticker. */
  private int projectLED(double dx, double dy, double dz, double g, double feather, double pulse) {
    double bestT = Double.MAX_VALUE;
    int bestColor = LXColor.BLACK;

    for (int i = 0; i < this.rCount; ++i) {
      double[] n = this.rn[i];
      double denom = dx * n[0] + dy * n[1] + dz * n[2];
      if (denom <= 1e-6) {
        continue; // parallel or back-facing (interior of the cube)
      }
      double[] cc = this.rc[i];
      double t = (cc[0] * n[0] + cc[1] * n[1] + cc[2] * n[2]) / denom;
      if (t <= 0 || t >= bestT) {
        continue;
      }
      double hx = t * dx - cc[0];
      double hy = t * dy - cc[1];
      double hz = t * dz - cc[2];
      double[] u = this.ru[i];
      double[] v = this.rv[i];
      double a = (hx * u[0] + hy * u[1] + hz * u[2]) / (u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
      if (a < -1 || a > 1) {
        continue;
      }
      double b = (hx * v[0] + hy * v[1] + hz * v[2]) / (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
      if (b < -1 || b > 1) {
        continue;
      }
      bestT = t;
      double mask = tileMask(a, g, feather) * tileMask(b, g, feather);
      bestColor = scaleColor(this.faceColor[this.rColor[i]], mask * pulse);
    }
    return bestColor;
  }

  /** Brightness in [0,1] for a tile-local coordinate in [-1,1] given square size g and feather. */
  private static double tileMask(double t, double g, double feather) {
    double d = g - Math.abs(t);
    if (feather < 1e-4) {
      return d >= 0 ? 1 : 0;
    }
    return LXUtils.constrain(d / feather, 0, 1);
  }

  private void distributeSurfaces() {
    switch (this.surfaces.getEnum()) {
      case OUTER:
        break; // interior already black from setColors()
      case BOTH:
        copyCubeExterior();
        break;
      case INNER:
        copyCubeExterior();
        for (Apotheneum.Cube.Face face : Apotheneum.cube.exterior.faces) {
          setColor(face, LXColor.BLACK);
        }
        break;
    }
  }
}
