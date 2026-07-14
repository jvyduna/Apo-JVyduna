package apotheneum.jvyduna.effects.bliss;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumEffect;
import apotheneum.jvyduna.util.TempoLock;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * Triggered "torn-fabric" subtract effect. On each {@code Trigger}, Rip picks
 * one of the ten Apotheneum surfaces (4 cube exterior faces, 4 cube interior
 * faces, cylinder exterior, cylinder interior) that currently carries non-black
 * pixel data from the channels composited below this effect, then tears a
 * jagged, growing triangle out of it: an all-white "rip zone" that darkens
 * (subtracts) whatever content lies beneath. The triangle's base sits on a
 * random top/bottom source edge and its apex advances inward over {@code RipDiv}
 * (reaching 50-120% of the surface height and {@code RipWidt}% of the edge
 * width), tilted by a random per-rip skew. When the grow completes the zone
 * fades exponentially back to black over {@code FadeDiv}, then the rip is
 * forgotten.
 *
 * Built as an {@link ApotheneumEffect} rather than a pattern because only an
 * effect sees the composited buffer below it ({@code this.colors[]}) — a pattern
 * cannot know which surfaces other channels have lit. Place it on a group or
 * master bus above the content it should tear. See Rip.md for the design note.
 *
 * Each rip freezes its grow duration, width, skew, and jag geometry at trigger
 * time and never re-reads those params during its animation; live knob changes
 * only affect the next trigger. The one exception is {@code FadeDiv}: the heal
 * rate is live and shared, so turning it retimes every in-flight rip's fade at
 * once.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Rip")
@LXComponent.Description("Triggered torn-fabric subtract: tears a jagged growing triangle out of a lit surface, then fades it back")
public class Rip extends ApotheneumEffect {

  /**
   * Concurrent rips; further triggers reuse the oldest slot. Tripled (was 24)
   * to accommodate the {@code Rips} param starting up to 3 rips per trigger.
   */
  private static final int MAX_RIPS = 72;

  /** Surface height in pixels, by component. */
  private static final int CUBE_H = Apotheneum.GRID_HEIGHT;      // 45
  private static final int CYL_H = Apotheneum.CYLINDER_HEIGHT;   // 43

  /** Number of jag samples stored along a rip's depth axis. */
  private static final int JAG_N = 48;
  /** Max jag displacement (px) of a torn side at Jag = 1. */
  private static final double JAG_MAX_PX = 4.0;
  /** Max feather width (px) of a ripped side at Smooth = 1. */
  private static final double SMOOTH_MAX_PX = 4.0;
  /**
   * Full width of the Skew tilt range at Skew = 1. On a cube face it is a
   * one-sided [0, 90deg] range leaning toward the face center; on the cylinder
   * it is symmetric about straight (±45deg). Skew = 0 forces straight-in.
   */
  private static final double SKEW_MAX_ANGLE = Math.toRadians(90);
  /** Clamp the tilt slope (drift px per depth px) so angles near 90deg stay finite. */
  private static final double SLOPE_CAP = 7.0;

  /** Cylinder azimuth jitter (fraction of ring) around the avoidance target. */
  private static final double ORIGIN_SIGMA_X = 0.18;
  /** Std dev of the cylinder edge (top/bottom) choice; larger = more mixing. */
  private static final double ORIGIN_SIGMA_EDGE = 0.28;
  /** Cube origin jitter: std dev as a fraction of the half-gap to the nearest origin. */
  private static final double ORIGIN_SIGMA_FRAC = 0.4;
  /** Gaps within this of the largest count as tied, for random tie-breaking. */
  private static final double GAP_EPS = 1e-6;

  /** Rip depth fraction is drawn uniformly from [MIN, MAX] of surface height. */
  private static final double DEPTH_FRAC_MIN = 0.5;
  private static final double DEPTH_FRAC_MAX = 1.2;

  /** A pixel counts as "lit" when its integer luma exceeds this (0-255). */
  private static final int LUMA_MIN = 8;
  /** A surface is a rip candidate when at least this fraction of it is lit. */
  private static final double MIN_LIT = 0.02;

  /** Shape of the grow animation's progress over time. */
  public enum Curve {
    ACCEL("Accel"),   // ease-in: starts slow, tears faster  -> t^exp
    LINEAR("Linear"), // constant tear speed                 -> t
    DECEL("Decel");   // ease-out: starts fast, slows to stop -> 1-(1-t)^exp

    public final String label;

    private Curve(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  /**
   * Fade duration in bars. Tempo.Division tops out at 2 bars, so the fade tail
   * gets its own span enum reaching the long (32/64-bar) fades. One bar =
   * {@code divisionMs(WHOLE)}.
   */
  public enum FadeSpan {
    QUARTER("1/4", 0.25),
    HALF("1/2", 0.5),
    ONE("1", 1),
    TWO("2", 2),
    FOUR("4", 4),
    EIGHT("8", 8),
    SIXTEEN("16", 16),
    THIRTYTWO("32", 32),
    SIXTYFOUR("64", 64);

    public final String label;
    public final double bars;

    private FadeSpan(String label, double bars) {
      this.label = label;
      this.bars = bars;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public final TriggerParameter trigger =
    new TriggerParameter("Trigger", this::requestSpawn)
    .setDescription("Tear a rip out of a random surface that currently has content");

  public final DiscreteParameter ripCount =
    new DiscreteParameter("Rips", 1, 1, 4)
    .setDescription("How many new rips to start on each trigger (1-3); a Through double-sided pair counts as one");

  public final EnumParameter<Tempo.Division> ripDiv =
    new EnumParameter<Tempo.Division>("RipDiv", Tempo.Division.QUARTER)
    .setDescription("Musical duration of the rip's grow (tear) animation");

  public final EnumParameter<Curve> curve =
    new EnumParameter<Curve>("Curve", Curve.LINEAR)
    .setDescription("Speed curve of the tear over RipDiv: accelerate, constant, or decelerate");

  public final CompoundParameter curveExp =
    new CompoundParameter("Exp", 2, 1, 6)
    .setDescription("Strength of the Accel/Decel curve; 1 = linear (ignored when Curve is Linear)");

  public final EnumParameter<FadeSpan> fadeDiv =
    new EnumParameter<FadeSpan>("FadeDiv", FadeSpan.ONE)
    .setDescription("Duration (in bars) for the white rip zone to fade back to black");

  public final CompoundParameter ripWidt =
    new CompoundParameter("RipWidt", 0.4)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Fraction of the source edge's width the tear's base reaches at grow completion");

  public final CompoundParameter jag =
    new CompoundParameter("Jag", 0.5)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("How jagged the torn sides are");

  public final CompoundParameter skew =
    new CompoundParameter("Skew", 0.3)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Max random tilt of the tear's apex off perpendicular; 0 = always straight in");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 0)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Feather the ripped side boundaries; the leading front stays hard white");

  public final BooleanParameter border =
    new BooleanParameter("Border", false)
    .setDescription("Draw a 1px outline in the selected palette color along the ripped sides");

  public final DiscreteParameter bColor =
    new DiscreteParameter("BColor", 1, 1, 6)
    .setDescription("Which palette swatch slot (1-5) colors the border; too-high indices fall back to the last defined slot");

  public final BooleanParameter through =
    new BooleanParameter("Through", false)
    .setDescription("Tear through the wall: mirror each rip onto the opposite (interior/exterior) side of its surface");

  /**
   * A single active tear. All geometry is frozen at trigger time; render only
   * advances {@code ageMs}. The surface is stored as a descriptor and
   * re-resolved every frame so a model rebuild never leaves a stale reference.
   */
  private static final class RipState {
    boolean active;
    long serial;
    int surfaceId;     // which of the ten surfaces (index into the cand* table)

    // Surface descriptor (re-resolved each frame)
    boolean cube;      // true = cube face, false = cylinder orientation
    boolean exterior;
    int faceIndex;     // 0-3 for cube faces, ignored for cylinder
    int height;        // surface height in px
    boolean wrapU;     // along-edge wrap (cylinder only)

    // Frozen tear geometry
    boolean fromTop;   // source edge
    double startU;     // along-edge base center
    double maxDepth;   // px the apex travels at completion (may exceed height)
    double halfWidthMax; // px half-width of the base at completion
    double slope;      // apex horizontal drift per px of depth
    Curve curve;       // grow speed curve
    double curveExp;   // curve exponent
    double ripDurMs;
    double ageMs;      // elapsed since trigger (grow clock)
    double fadeT;      // fade/heal progress 0..1, advanced at the live shared rate

    final double[] jagLeft = new double[JAG_N];
    final double[] jagRight = new double[JAG_N];
  }

  private final RipState[] rips = new RipState[MAX_RIPS];
  private final TempoLock tempoLock;
  private final Random random = new Random();

  // Trigger callback runs off the render pass; defer the buffer read to render()
  private int pendingSpawns = 0;
  private long spawnCounter = 0;

  // Preallocated candidate scan (the ten surface descriptors + pick scratch)
  private final boolean[] candCube = new boolean[10];
  private final boolean[] candExt = new boolean[10];
  private final int[] candFace = new int[10];
  private final int[] litCandidates = new int[10];
  private final int[] surfaceRipCount = new int[10];

  // Origin avoidance scratch: existing origins mapped into the wrapped 1D space,
  // plus the largest-gap result (midpoint + half width) chosen among them.
  private final double[] scratchT = new double[MAX_RIPS];
  private double gapMid;
  private double gapHalf;

  public Rip(LX lx) {
    super(lx);
    this.tempoLock = new TempoLock(lx);
    for (int i = 0; i < this.rips.length; ++i) {
      this.rips[i] = new RipState();
    }
    // Fixed descriptor table for the ten surfaces: 8 cube faces, 2 cylinders.
    int k = 0;
    for (int f = 0; f < 4; ++f) { this.candCube[k] = true; this.candExt[k] = true; this.candFace[k] = f; ++k; }
    for (int f = 0; f < 4; ++f) { this.candCube[k] = true; this.candExt[k] = false; this.candFace[k] = f; ++k; }
    this.candCube[k] = false; this.candExt[k] = true; this.candFace[k] = -1; ++k;
    this.candCube[k] = false; this.candExt[k] = false; this.candFace[k] = -1; ++k;

    addParameter("trigger", this.trigger);
    addParameter("rips", this.ripCount);
    addParameter("ripDiv", this.ripDiv);
    addParameter("curve", this.curve);
    addParameter("curveExp", this.curveExp);
    addParameter("fadeDiv", this.fadeDiv);
    addParameter("ripWidt", this.ripWidt);
    addParameter("jag", this.jag);
    addParameter("skew", this.skew);
    addParameter("smooth", this.smooth);
    addParameter("border", this.border);
    addParameter("bColor", this.bColor);
    addParameter("through", this.through);
  }

  @Override
  protected void onEnable() {
    super.onEnable();
    for (RipState r : this.rips) {
      r.active = false;
    }
    this.pendingSpawns = 0;
  }

  /**
   * Trigger callback: defer to render() so we read the live composite buffer.
   * Enqueues {@code Rips} spawns; each becomes one {@link RipState}, which in
   * Through mode tears both wall sides itself, so a double-sided pair still
   * counts as a single rip against the requested count.
   */
  private void requestSpawn() {
    this.pendingSpawns += this.ripCount.getValuei();
  }

  @Override
  protected void render(double deltaMs, double enabledAmount) {
    // Consume any triggers fired since the last frame, reading the composite
    // that the channels below have just produced into this.colors[].
    while (this.pendingSpawns > 0) {
      --this.pendingSpawns;
      spawnRip();
    }

    // FadeDiv is a live, shared "healing rate": all in-flight rips fade at the
    // current division, so turning the knob retimes every fade at once.
    final double fadeDurMs = Math.max(1,
      this.fadeDiv.getEnum().bars * this.tempoLock.divisionMs(Tempo.Division.WHOLE));

    for (RipState r : this.rips) {
      if (r.active) {
        advanceAndDraw(r, deltaMs, enabledAmount, fadeDurMs);
      }
    }
  }

  /**
   * The border color: the palette swatch slot selected by {@code BColor} (1-based).
   * When the requested slot is beyond the number of defined slots, the last
   * defined slot is used. Falls back to the palette's primary color if the
   * swatch is empty.
   */
  private int borderColor() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    if (swatch.isEmpty()) {
      return this.lx.engine.palette.getColor();
    }
    final int idx = Math.min(this.bColor.getValuei() - 1, swatch.size() - 1);
    return swatch.get(idx).getColor();
  }

  /** Resolve a rip/candidate descriptor to a live surface, or null. */
  private Apotheneum.Surface resolve(boolean cube, boolean exterior, int faceIndex) {
    if (cube) {
      if (Apotheneum.cube == null) {
        return null;
      }
      final Apotheneum.Cube.Orientation o = exterior ? Apotheneum.cube.exterior : Apotheneum.cube.interior;
      return (o == null) ? null : o.faces[faceIndex];
    } else {
      if (Apotheneum.cylinder == null) {
        return null;
      }
      return exterior ? Apotheneum.cylinder.exterior : Apotheneum.cylinder.interior;
    }
  }

  /**
   * The opposite (interior/exterior) surface of the same wall, in cand-table
   * indices: cube exterior 0-3 pairs with interior 4-7; cylinder 8 with 9.
   */
  private static int counterpartId(int surfaceId) {
    if (surfaceId < 4) {
      return surfaceId + 4;   // cube exterior -> interior
    }
    if (surfaceId < 8) {
      return surfaceId - 4;   // cube interior -> exterior
    }
    return (surfaceId == 8) ? 9 : 8; // cylinder exterior <-> interior
  }

  /**
   * Scan the ten surfaces' composite content, pick a lit one carrying the fewest
   * existing rips (ties broken at random), and seed a rip slot. Skips silently
   * (logging once) when nothing is lit.
   */
  private void spawnRip() {
    int n = 0;
    for (int i = 0; i < 10; ++i) {
      final Apotheneum.Surface s = resolve(this.candCube[i], this.candExt[i], this.candFace[i]);
      if (s == null) {
        continue;
      }
      if (litFraction(s) > MIN_LIT) {
        this.litCandidates[n++] = i;
      }
    }
    if (n == 0) {
      LX.log("Rip: no lit surface, skipping trigger");
      return;
    }

    // Tally active rips per surface, then keep only the lit candidates carrying
    // the minimum load so triggers spread across surfaces instead of piling up.
    // With Through on, a rip occupies both sides of its wall, so it counts
    // against its counterpart surface too — new rips then favor a fresh wall.
    final boolean throughOn = this.through.isOn();
    Arrays.fill(this.surfaceRipCount, 0);
    for (RipState r : this.rips) {
      if (r.active) {
        ++this.surfaceRipCount[r.surfaceId];
        if (throughOn) {
          ++this.surfaceRipCount[counterpartId(r.surfaceId)];
        }
      }
    }
    int minCount = Integer.MAX_VALUE;
    for (int j = 0; j < n; ++j) {
      final int c = this.surfaceRipCount[this.litCandidates[j]];
      if (c < minCount) {
        minCount = c;
      }
    }
    int m = 0;
    for (int j = 0; j < n; ++j) {
      if (this.surfaceRipCount[this.litCandidates[j]] == minCount) {
        this.litCandidates[m++] = this.litCandidates[j];
      }
    }

    seed(claimSlot(), this.litCandidates[this.random.nextInt(m)]);
  }

  /** Fraction of a surface's mapped points whose luma exceeds LUMA_MIN. */
  private double litFraction(Apotheneum.Surface surface) {
    final Apotheneum.Column[] cols = surface.columns();
    int lit = 0;
    int total = 0;
    for (Apotheneum.Column col : cols) {
      final LXPoint[] pts = col.points;
      for (LXPoint p : pts) {
        final int c = this.colors[p.index];
        final int luma = (77 * ((c >> 16) & 0xff) + 150 * ((c >> 8) & 0xff) + 29 * (c & 0xff)) >> 8;
        if (luma > LUMA_MIN) {
          ++lit;
        }
        ++total;
      }
    }
    return (total > 0) ? ((double) lit / total) : 0;
  }

  /** Find a free slot, or the oldest active one to recycle. */
  private RipState claimSlot() {
    RipState oldest = this.rips[0];
    for (RipState r : this.rips) {
      if (!r.active) {
        return r;
      }
      if (r.serial < oldest.serial) {
        oldest = r;
      }
    }
    return oldest;
  }

  /** Freeze all geometry for a new rip on the given surface (0-9 cand index). */
  private void seed(RipState r, int surfaceId) {
    final boolean cube = this.candCube[surfaceId];
    final boolean exterior = this.candExt[surfaceId];
    final int faceIndex = this.candFace[surfaceId];
    r.active = true;
    r.serial = ++this.spawnCounter;
    r.surfaceId = surfaceId;
    r.cube = cube;
    r.exterior = exterior;
    r.faceIndex = faceIndex;
    r.height = cube ? CUBE_H : CYL_H;
    r.wrapU = !cube;

    final Apotheneum.Surface s = resolve(cube, exterior, faceIndex);
    final int w = s.columns().length;

    chooseOrigin(r, surfaceId, w);
    final double depthFrac = DEPTH_FRAC_MIN + this.random.nextDouble() * (DEPTH_FRAC_MAX - DEPTH_FRAC_MIN);
    r.maxDepth = depthFrac * r.height;
    r.halfWidthMax = 0.5 * this.ripWidt.getValue() * w;
    r.slope = chooseSlope(r, w);
    r.curve = this.curve.getEnum();
    r.curveExp = this.curveExp.getValue();
    r.ripDurMs = Math.max(1, this.tempoLock.divisionMs(this.ripDiv.getEnum()));
    r.ageMs = 0;
    r.fadeT = 0;
    // FadeDiv is intentionally NOT frozen here: healing rate is live and shared.

    fillJag(r.jagLeft, this.jag.getValue() * JAG_MAX_PX);
    fillJag(r.jagRight, this.jag.getValue() * JAG_MAX_PX);
  }

  /**
   * Choose the rip's origin (edge + position). With no other rips on this
   * surface it is uniform-random. Otherwise it stays random but is biased away
   * from the existing origins.
   *
   * <p>On a <b>cube face</b> the origin space is a single wrapped 1D loop
   * concatenating the two valid edges (rips cannot start on the vertical sides):
   * top-left = 0, top-right = 0.5, bottom-right = 0.5+, bottom-left = 1 ≡ 0
   * (the sides are the zero-length seams that close the loop). Avoidance aims at
   * the <b>midpoint of the largest gap</b> between existing origins on this loop
   * (random among ties), jittered by a Gaussian whose width scales with the gap.
   * One existing rip at 10% thus pushes the next toward 60%; two antipodal ones
   * push the third to 35% or 85% (50/50), etc.
   *
   * <p>On the <b>cylinder</b> (no vertical seams; each edge is its own ring)
   * the azimuth uses the circular-mean antipode and the edge flees the crowded
   * ring — the two are handled independently.
   *
   * <p>With Through on, the counterpart surface's rips share these origins
   * (same column index) and are folded into the avoidance set.
   */
  private void chooseOrigin(RipState r, int surfaceId, int w) {
    final int counterpart = counterpartId(surfaceId);
    final boolean throughOn = this.through.isOn();
    final boolean cyl = !r.cube;

    int count = 0;
    int bottomCount = 0;
    double sumSin = 0, sumCos = 0;   // cylinder azimuth circular mean
    for (RipState o : this.rips) {
      if (!o.active || (o == r)) {
        continue;
      }
      if ((o.surfaceId == surfaceId) || (throughOn && (o.surfaceId == counterpart))) {
        final double ox = o.startU / w;
        if (!o.fromTop) {
          ++bottomCount;
        }
        if (cyl) {
          final double a = ox * 2 * Math.PI;
          sumSin += Math.sin(a);
          sumCos += Math.cos(a);
        } else {
          this.scratchT[count] = cubeEdgeToLoop(ox, o.fromTop);
        }
        ++count;
      }
    }

    if (count == 0) {
      r.fromTop = this.random.nextBoolean();
      r.startU = this.random.nextDouble() * w;
      return;
    }

    if (cyl) {
      // Azimuth: antipode of the circular mean, jittered. Edge: flee the
      // crowded ring (pTop = fraction of existing origins on the bottom).
      double targetX = (Math.atan2(sumSin, sumCos) + Math.PI) / (2 * Math.PI);
      targetX += this.random.nextGaussian() * ORIGIN_SIGMA_X;
      targetX -= Math.floor(targetX);
      r.startU = targetX * w;
      final double pTop = (double) bottomCount / count;
      r.fromTop = (pTop + this.random.nextGaussian() * ORIGIN_SIGMA_EDGE) >= 0.5;
      return;
    }

    // Cube: aim at the largest gap on the top+bottom loop, jitter by the gap.
    pickLargestGap(this.scratchT, count);
    double t = this.gapMid + this.random.nextGaussian() * (this.gapHalf * ORIGIN_SIGMA_FRAC);
    t -= Math.floor(t);
    setCubeOriginFromLoop(r, t, w);
  }

  /**
   * Map a cube-face edge origin to the wrapped loop coordinate in [0, 1):
   * top edge left→right occupies [0, 0.5), bottom edge right→left occupies
   * [0.5, 1). (Bottom-left, x=0, maps to 1.0 ≡ 0, closing the loop.)
   */
  private static double cubeEdgeToLoop(double x, boolean fromTop) {
    final double t = fromTop ? (0.5 * x) : (1.0 - 0.5 * x);
    return t - Math.floor(t);
  }

  /** Inverse of {@link #cubeEdgeToLoop}: set r.fromTop and r.startU from a loop t. */
  private void setCubeOriginFromLoop(RipState r, double t, int w) {
    t -= Math.floor(t);
    final double x;
    if (t < 0.5) {
      r.fromTop = true;
      x = 2.0 * t;             // top edge, left→right
    } else {
      r.fromTop = false;
      x = 2.0 * (1.0 - t);     // bottom edge, right→left
    }
    r.startU = LXUtils.clamp(x, 0, 1) * w;
  }

  /**
   * On the wrapped circle of values in [0, 1), find the largest gap between
   * adjacent origins (choosing uniformly at random among tied-largest gaps) and
   * store its midpoint in {@link #gapMid} and half its width in
   * {@link #gapHalf}. Sorts {@code t[0..count)} in place (scratch).
   */
  private void pickLargestGap(double[] t, int count) {
    Arrays.sort(t, 0, count);
    double maxGap = -1;
    for (int i = 0; i < count; ++i) {
      final double gap = (i == count - 1) ? (t[0] + 1.0 - t[count - 1]) : (t[i + 1] - t[i]);
      if (gap > maxGap) {
        maxGap = gap;
      }
    }
    int ties = 0;
    for (int i = 0; i < count; ++i) {
      final double start = t[i];
      final double gap = (i == count - 1) ? (t[0] + 1.0 - t[count - 1]) : (t[i + 1] - t[i]);
      if (gap >= maxGap - GAP_EPS) {
        ++ties;
        if (this.random.nextInt(ties) == 0) {
          this.gapHalf = 0.5 * gap;
          final double mid = start + this.gapHalf;
          this.gapMid = mid - Math.floor(mid);
        }
      }
    }
  }

  /**
   * Choose the apex tilt over a Skew*90deg-wide angle range (Skew = 0 is always
   * straight up/down). On a cube face the range is one-sided, [0, Skew*90deg],
   * always leaning toward the face center. On the continuous cylinder there is
   * no left/right center, so the range is symmetric, centered on straight:
   * ±Skew*45deg, random direction. The slope is capped so near-90deg angles
   * stay finite.
   */
  private double chooseSlope(RipState r, int w) {
    final double skewFrac = this.skew.getValue();
    if (skewFrac <= 0) {
      return 0;
    }
    if (!r.cube) {
      // Cylinder: symmetric range centered on straight, width Skew*90deg.
      final double theta = (2 * this.random.nextDouble() - 1) * skewFrac * (SKEW_MAX_ANGLE / 2);
      return LXUtils.clamp(Math.tan(theta), -SLOPE_CAP, SLOPE_CAP);
    }
    // Cube: one-sided range [0, Skew*90deg] leaning toward the face center.
    final double theta = this.random.nextDouble() * skewFrac * SKEW_MAX_ANGLE;
    final double center = 0.5 * w;
    final double sign;
    if (r.startU < center) {
      sign = 1;   // origin left of center -> lean right, toward center
    } else if (r.startU > center) {
      sign = -1;  // origin right of center -> lean left, toward center
    } else {
      sign = this.random.nextBoolean() ? 1 : -1;
    }
    return sign * Math.min(SLOPE_CAP, Math.tan(theta));
  }

  /**
   * Fill a jag profile with smoothed value noise in [-amp, amp]: white noise
   * then two box-smoothing passes so the torn edge is continuous, not spiky.
   */
  private void fillJag(double[] out, double amp) {
    for (int i = 0; i < out.length; ++i) {
      out[i] = (2 * this.random.nextDouble() - 1) * amp;
    }
    for (int pass = 0; pass < 2; ++pass) {
      double prev = out[0];
      for (int i = 1; i < out.length - 1; ++i) {
        final double smoothed = (prev + out[i] + out[i + 1]) / 3.0;
        prev = out[i];
        out[i] = smoothed;
      }
    }
  }

  /** Advance a rip's clock and subtract its white zone into the buffer. */
  private void advanceAndDraw(RipState r, double deltaMs, double enabledAmount, double fadeDurMs) {
    r.ageMs += deltaMs;

    final double p;
    final double whiteLevel;
    if (r.ageMs < r.ripDurMs) {
      // Grow phase: triangle scales up from the source edge, full white. The
      // speed curve reshapes linear clock time into tear progress. RipDiv is
      // frozen per rip.
      p = shapeProgress(r.ageMs / r.ripDurMs, r.curve, r.curveExp);
      whiteLevel = 1;
    } else {
      // Fade phase: full shape held, white decays exponentially to black. The
      // heal progress advances at the live, shared FadeDiv rate, so a knob
      // change retimes every in-flight rip rather than only the next trigger.
      r.fadeT += deltaMs / fadeDurMs;
      if (r.fadeT >= 1) {
        r.active = false;
        return;
      }
      p = 1;
      whiteLevel = Math.pow(1.0 / 255.0, r.fadeT);
    }

    final Apotheneum.Surface surface = resolve(r.cube, r.exterior, r.faceIndex);
    if (surface == null) {
      r.active = false;
      return;
    }
    final double currentDepth = p * r.maxDepth;
    if (currentDepth <= 0) {
      return;
    }
    final double currentHalfWidth = p * r.halfWidthMax;
    final double feather = this.smooth.getValue() * SMOOTH_MAX_PX;
    final boolean drawBorder = this.border.isOn();
    final int borderColor = drawBorder ? borderColor() : 0;
    final double level = whiteLevel * LXUtils.clamp(enabledAmount, 0, 1);

    drawTear(r, surface, currentDepth, currentHalfWidth, feather, drawBorder, borderColor, level);

    // "Through" also tears the opposite (interior/exterior) side of the SAME
    // wall at the SAME location: the two surfaces are front/back of one sheet,
    // exterior column i and interior column i sharing a world (x, y) (only the
    // depth inset differs, per Apotheneum.lxf). So the identical tear on the
    // counterpart is one hole through the material; the mirror-image look to an
    // inside viewer falls out of the geometry, no column reversal needed.
    if (this.through.isOn()) {
      final Apotheneum.Surface other = resolve(r.cube, !r.exterior, r.faceIndex);
      if (other != null) {
        drawTear(r, other, currentDepth, currentHalfWidth, feather, drawBorder, borderColor, level);
      }
    }
  }

  /** Subtract one rip's white zone onto a surface. */
  private void drawTear(RipState r, Apotheneum.Surface surface,
      double currentDepth, double currentHalfWidth, double feather,
      boolean drawBorder, int borderColor, double level) {
    final Apotheneum.Column[] cols = surface.columns();
    final int w = cols.length;
    final int halfW = w / 2;

    for (int x = 0; x < w; ++x) {
      final LXPoint[] pts = cols[x].points;
      final int yMax = Math.min(r.height, pts.length);
      for (int y = 0; y < yMax; ++y) {
        final int d = r.fromTop ? y : (r.height - 1 - y);
        if (d > currentDepth) {
          continue;
        }
        // Half-width narrows linearly from the base (source edge) to the apex.
        final double frac = 1.0 - d / currentDepth;
        final double hw = currentHalfWidth * frac;
        final int ji = LXUtils.constrain((int) (d / r.maxDepth * (JAG_N - 1)), 0, JAG_N - 1);
        final double leftBound = -(hw + r.jagLeft[ji]);
        final double rightBound = hw + r.jagRight[ji];

        final double uCenter = r.startU + r.slope * d;
        double du = x - uCenter;
        if (r.wrapU) {
          du = Math.floorMod((int) Math.round(du) + halfW, w) - halfW;
        }
        if (du < leftBound || du > rightBound) {
          continue;
        }

        final int idx = pts[y].index;
        if (drawBorder && (du - leftBound < 1 || rightBound - du < 1)) {
          // Blend toward the palette border color by the same level the region
          // subtracts by, so the border fades out in lockstep with the rip zone.
          blendColor(idx, borderColor, level);
          continue;
        }
        double coverage = 1;
        if (feather > 0) {
          final double distToSide = Math.min(du - leftBound, rightBound - du);
          coverage = LXUtils.clamp(distToSide / feather, 0, 1);
        }
        subtract(idx, level * coverage);
      }
    }
  }

  /**
   * Reshape linear grow progress t (0-1) by the frozen speed curve. Accel
   * eases in (t^exp), Decel eases out (1-(1-t)^exp), Linear passes through.
   */
  private static double shapeProgress(double t, Curve curve, double exp) {
    switch (curve) {
    case ACCEL:
      return Math.pow(t, exp);
    case DECEL:
      return 1.0 - Math.pow(1.0 - t, exp);
    default:
      return t;
    }
  }

  /** Blend a pixel toward target by amount (0-1); at 0 the pixel is unchanged. */
  private void blendColor(int idx, int target, double amount) {
    if (amount <= 0) {
      return;
    }
    if (amount > 1) {
      amount = 1;
    }
    final int c = this.colors[idx];
    final int cr = (c >> 16) & 0xff, cg = (c >> 8) & 0xff, cb = c & 0xff;
    final int r = (int) (cr + (((target >> 16) & 0xff) - cr) * amount);
    final int g = (int) (cg + (((target >> 8) & 0xff) - cg) * amount);
    final int b = (int) (cb + ((target & 0xff) - cb) * amount);
    this.colors[idx] = (c & LXColor.ALPHA_MASK) | (r << 16) | (g << 8) | b;
  }

  /** Subtract a white amount (0-1) from a pixel, clamping each channel at 0. */
  private void subtract(int idx, double amount) {
    if (amount <= 0) {
      return;
    }
    final int sub = (int) (amount * 255);
    if (sub <= 0) {
      return;
    }
    final int c = this.colors[idx];
    final int r = Math.max(0, ((c >> 16) & 0xff) - sub);
    final int g = Math.max(0, ((c >> 8) & 0xff) - sub);
    final int b = Math.max(0, (c & 0xff) - sub);
    this.colors[idx] = (c & LXColor.ALPHA_MASK) | (r << 16) | (g << 8) | b;
  }
}
