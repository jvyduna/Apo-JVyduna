package apotheneum.jvyduna.effects.bliss;

import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumEffect;
import apotheneum.jvyduna.util.TempoLock;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
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
 * Each rip freezes its durations, width, skew, and jag geometry at trigger time
 * and never re-reads those params during its animation; live knob changes only
 * affect the next trigger.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Rip")
@LXComponent.Description("Triggered torn-fabric subtract: tears a jagged growing triangle out of a lit surface, then fades it back")
public class Rip extends ApotheneumEffect {

  /** Concurrent rips; further triggers reuse the oldest slot. */
  private static final int MAX_RIPS = 12;

  /** Surface height in pixels, by component. */
  private static final int CUBE_H = Apotheneum.GRID_HEIGHT;      // 45
  private static final int CYL_H = Apotheneum.CYLINDER_HEIGHT;   // 43

  /** Number of jag samples stored along a rip's depth axis. */
  private static final int JAG_N = 48;
  /** Max jag displacement (px) of a torn side at Jag = 1. */
  private static final double JAG_MAX_PX = 4.0;
  /** Max feather width (px) of a ripped side at Smooth = 1. */
  private static final double SMOOTH_MAX_PX = 4.0;
  /** Max apex tilt (slope: px of drift per px of depth) at Skew = 1 (~45deg). */
  private static final double SKEW_MAX_SLOPE = 1.0;

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

  public final TriggerParameter trigger =
    new TriggerParameter("Trigger", this::requestSpawn)
    .setDescription("Tear a rip out of a random surface that currently has content");

  public final EnumParameter<Tempo.Division> ripDiv =
    new EnumParameter<Tempo.Division>("RipDiv", Tempo.Division.QUARTER)
    .setDescription("Musical duration of the rip's grow (tear) animation");

  public final EnumParameter<Curve> curve =
    new EnumParameter<Curve>("Curve", Curve.LINEAR)
    .setDescription("Speed curve of the tear over RipDiv: accelerate, constant, or decelerate");

  public final CompoundParameter curveExp =
    new CompoundParameter("Exp", 2, 1, 5)
    .setDescription("Strength of the Accel/Decel curve; 1 = linear (ignored when Curve is Linear)");

  public final EnumParameter<Tempo.Division> fadeDiv =
    new EnumParameter<Tempo.Division>("FadeDiv", Tempo.Division.WHOLE)
    .setDescription("Musical duration for the white rip zone to fade back to black");

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
    .setDescription("Draw a 1px outline in the first palette color along the ripped sides");

  /**
   * A single active tear. All geometry is frozen at trigger time; render only
   * advances {@code ageMs}. The surface is stored as a descriptor and
   * re-resolved every frame so a model rebuild never leaves a stale reference.
   */
  private static final class RipState {
    boolean active;
    long serial;

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
    double fadeDurMs;
    double ageMs;

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
    addParameter("ripDiv", this.ripDiv);
    addParameter("curve", this.curve);
    addParameter("curveExp", this.curveExp);
    addParameter("fadeDiv", this.fadeDiv);
    addParameter("ripWidt", this.ripWidt);
    addParameter("jag", this.jag);
    addParameter("skew", this.skew);
    addParameter("smooth", this.smooth);
    addParameter("border", this.border);
  }

  @Override
  protected void onEnable() {
    super.onEnable();
    for (RipState r : this.rips) {
      r.active = false;
    }
    this.pendingSpawns = 0;
  }

  /** Trigger callback: defer to render() so we read the live composite buffer. */
  private void requestSpawn() {
    ++this.pendingSpawns;
  }

  @Override
  protected void render(double deltaMs, double enabledAmount) {
    // Consume any triggers fired since the last frame, reading the composite
    // that the channels below have just produced into this.colors[].
    while (this.pendingSpawns > 0) {
      --this.pendingSpawns;
      spawnRip();
    }

    for (RipState r : this.rips) {
      if (r.active) {
        advanceAndDraw(r, deltaMs, enabledAmount);
      }
    }
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
   * Scan the ten surfaces' composite content, pick one that is lit, and seed a
   * rip slot. Skips silently (logging once) when nothing is lit.
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
    final int pick = this.litCandidates[this.random.nextInt(n)];
    seed(claimSlot(), this.candCube[pick], this.candExt[pick], this.candFace[pick]);
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

  /** Freeze all geometry for a new rip on the given surface descriptor. */
  private void seed(RipState r, boolean cube, boolean exterior, int faceIndex) {
    r.active = true;
    r.serial = ++this.spawnCounter;
    r.cube = cube;
    r.exterior = exterior;
    r.faceIndex = faceIndex;
    r.height = cube ? CUBE_H : CYL_H;
    r.wrapU = !cube;

    final Apotheneum.Surface s = resolve(cube, exterior, faceIndex);
    final int w = s.columns().length;

    r.fromTop = this.random.nextBoolean();
    r.startU = this.random.nextDouble() * w;
    final double depthFrac = DEPTH_FRAC_MIN + this.random.nextDouble() * (DEPTH_FRAC_MAX - DEPTH_FRAC_MIN);
    r.maxDepth = depthFrac * r.height;
    r.halfWidthMax = 0.5 * this.ripWidt.getValue() * w;
    final double sk = this.skew.getValue() * SKEW_MAX_SLOPE;
    r.slope = (2 * this.random.nextDouble() - 1) * sk;
    r.curve = this.curve.getEnum();
    r.curveExp = this.curveExp.getValue();
    r.ripDurMs = Math.max(1, this.tempoLock.divisionMs(this.ripDiv.getEnum()));
    r.fadeDurMs = Math.max(1, this.tempoLock.divisionMs(this.fadeDiv.getEnum()));
    r.ageMs = 0;

    fillJag(r.jagLeft, this.jag.getValue() * JAG_MAX_PX);
    fillJag(r.jagRight, this.jag.getValue() * JAG_MAX_PX);
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
  private void advanceAndDraw(RipState r, double deltaMs, double enabledAmount) {
    r.ageMs += deltaMs;

    final double p;
    final double whiteLevel;
    if (r.ageMs < r.ripDurMs) {
      // Grow phase: triangle scales up from the source edge, full white. The
      // speed curve reshapes linear clock time into tear progress.
      p = shapeProgress(r.ageMs / r.ripDurMs, r.curve, r.curveExp);
      whiteLevel = 1;
    } else {
      // Fade phase: full shape held, white decays exponentially to black.
      final double fadeT = (r.ageMs - r.ripDurMs) / r.fadeDurMs;
      if (fadeT >= 1) {
        r.active = false;
        return;
      }
      p = 1;
      whiteLevel = Math.pow(1.0 / 255.0, fadeT);
    }

    final Apotheneum.Surface surface = resolve(r.cube, r.exterior, r.faceIndex);
    if (surface == null) {
      r.active = false;
      return;
    }
    final Apotheneum.Column[] cols = surface.columns();
    final int w = cols.length;
    final double currentDepth = p * r.maxDepth;
    if (currentDepth <= 0) {
      return;
    }
    final double currentHalfWidth = p * r.halfWidthMax;
    final double feather = this.smooth.getValue() * SMOOTH_MAX_PX;
    final boolean drawBorder = this.border.isOn();
    final int borderColor = drawBorder ? this.lx.engine.palette.getColor() : 0;
    final double level = whiteLevel * LXUtils.clamp(enabledAmount, 0, 1);
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
