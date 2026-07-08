package apotheneum.jvyduna.patterns.temper;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * A generative ochre CAVE-PAINTING of human figures that slowly rise up the
 * exterior surfaces, weaving continuous FAMILY TREES of humanity. Figures are
 * drawn in the Kolo/Kondoa rock-art style — elongated legs, high hips, short
 * forward-leaning torsos, large oval heads, bent two-segment limbs. As a
 * figure climbs it drips children below it, connected by faint
 * genealogy-chart lines, so lineage strands branch upward across the wall.
 *
 * The opener of "Communicating" — the genesis (childbirth + ancestry;
 * "aumakua" = ancestral guardian spirits). The cube INTERIOR is a dark,
 * softer echo of the exterior (the Inner knob); the figures and their ochre
 * warmth live on the exterior faces and the cylinder ring. Silent by design (no
 * audio reactivity); the rise speed is BEAT-RELATIVE — a continuous rate that
 * follows the (rubato) arrange tempo lane, never snapping to a grid and never
 * jumping, so every Temper pattern stays super chill. This is not the retired
 * Sync/TempoDiv gate; it is a smooth tempo-relative speed.
 *
 * Zero-alloc: two independent {@link Field}s (cube ring, cylinder) each own a
 * fixed pool of {@link Figure} objects, all preallocated in the constructor;
 * births reuse an inactive pool slot rather than allocating. Attached
 * children derive their y from their living parent every frame, so the GenY
 * generation-spacing knob reshapes every living tree instantly. Color is
 * palette-driven (project swatch, sampled per lineage generation) and the
 * Ochre knob converges ALL generations to one deep red-brown at 1.
 *
 * See Aumakua.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Aumakua")
@LXComponent.Description("Generative ochre Kolo-style cave-painting figures rising in family trees up the exterior, dripping new figures below with genealogy-chart connectors; dark interior echo")
public class Aumakua extends ApotheneumPattern {

  // ---- Timing / motion constants ---------------------------------------------

  /** Rise speed endpoints in rows-per-BEAT, mapped from Speed, so motion is
   *  continuous and tempo-relative — it tracks the (rubato) arrange tempo lane
   *  rather than an absolute rows/sec. Each frame the endpoint is multiplied by
   *  the live beat rate (1000/beatMs) and clamped to MAX_RISE_ROWS_PER_SEC so
   *  the >= 5 s full-traversal floor holds at any tempo. Endpoints are chosen so
   *  that at the nominal ~96.8 BPM Temper tempo the rates match the prior
   *  1.5 / 6.4 rows-per-sec feel. This is NOT the retired Sync/TempoDiv grid
   *  gate: the rate is continuous and never snaps to a division. */
  private static final double RISE_ROWS_PER_BEAT_PEAK = 4.0; // CURATE: ~6.4 rows/s @ 96.8 BPM
  // Speed maps linearly from 0: Speed=0 is a TRUE FREEZE (no rise, no sway, no
  // births, no aging — the whole tableau holds still; only smear trails settle).
  /** Rise-rate ceiling (rows/sec): 45-row cube / 8 = 5.6 s, inside the 5 s cap. */
  private static final double MAX_RISE_ROWS_PER_SEC = 8.0;       // CURATE
  /** Floor on ms/beat, guarding against a zero/absurd host tempo. */
  private static final double MIN_BEAT_MS = 50;

  /** First-generation population: Density maps to roughly one ancestor per
   *  this many columns at pattern init / Genesis (min 1 per surface). There is
   *  NO ongoing root-spawn timer — after the first generation, all new figures
   *  come from Fertl drips (or the Seed/Bloom triggers). */
  private static final double INITIAL_ANCESTOR_COLS = 25.0; // CURATE: density=1 -> 8 cube / 5 cyl

  /** A newborn figure fades IN over this long (its "birth"), so every figure
   *  has >= 1.5 s of visual life before it can be judged an event-flash. */
  private static final double BIRTH_IN_MS = 2200;

  /** Rows below the top edge over which a rising figure fades back OUT as it
   *  reaches the "safer space" at the top of the wall. */
  private static final double FADE_TOP_ROWS = 8;

  /** Trail (feedback smear / rising comet) half-life endpoints (ms), mapped
   *  from the Smooth knob (exp). The 2 ms floor means Smooth=0 clears the
   *  canvas every frame — hard pixels, no ghosts; 1 = gauzy gossamer loops. */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 2;    // CURATE
  private static final double TRAIL_HALF_LIFE_MAX_MS = 6000; // CURATE

  /** Resting fraction of newborn roots taking the raised-arm (orant) pose. */
  private static final double RAISE_BIAS = 0.15;

  /** Structural maxima — all pool state is preallocated at these sizes */
  private static final int MAX_FIGURES = 64;
  private static final int MAX_GEN = 5; // lineage depth colors cycle over

  // ---- Kolo/Kondoa figure geometry (fractions of figure height) --------------

  /** High hips — the legs dominate, the classic elongated Kondoa proportion. */
  private static final double HIP_FRAC = 0.55;
  /** Short torso: shoulders sit just below the head. */
  private static final double SHOULDER_FRAC = 0.82;
  /** Knee height for the two-segment scissored legs. */
  private static final double KNEE_FRAC = 0.26;
  /** Head is a large filled ellipse, slightly wider than tall. */
  private static final double HEAD_RX_FRAC = 0.10;
  private static final double HEAD_RY_FRAC = 0.08;
  /** Leg x-spreads (fractions of the stride spread) for the scissored
   *  walking stance — front leg reaches, back leg trails. */
  private static final double FRONT_KNEE_DX = 0.10;
  private static final double FRONT_FOOT_DX = 0.20;
  private static final double BACK_KNEE_DX = 0.06;
  private static final double BACK_FOOT_DX = 0.22;
  /** Below this height (rows) limbs degrade to single segments — the Kondoa
   *  silhouette survives on proportions (high hip, big head, lean) alone. */
  private static final double TWO_SEGMENT_MIN_HEIGHT = 12;
  /** At/above this height the torso gets a second offset stroke (thicker
   *  painted body vs the 1px limbs). */
  private static final double TORSO_DOUBLE_MIN_HEIGHT = 14;

  /** Figure height endpoints (rows), mapped from the Scale knob */
  private static final double FIG_HEIGHT_MIN = 10;
  private static final double FIG_HEIGHT_MAX = 26;

  /** A rising parent drips a child once it has climbed this many of its own
   *  body-heights, if the Fertl roll passes and lineage depth allows. */
  private static final double DRIP_RISE_BODIES = 1.1; // CURATE

  // ---- Generation spacing (GenY) ----------------------------------------------

  /** Gap from a parent's feet to a child's head:
   *  GAP_MIN + GenY·GAP_RANGE + GAP_H_FRAC·parentHeight (rows). Deterministic
   *  per parent so siblings share one genealogy rank line. */
  private static final double GAP_MIN = 2;       // CURATE
  private static final double GAP_RANGE = 12;    // CURATE
  private static final double GAP_H_FRAC = 0.25; // CURATE

  /** Sibling y jitter half-range (rows) — siblings sit within ±this of the
   *  shared rank line instead of the old full-height scatter. */
  private static final double SIB_JITTER = 0.75;

  // Genealogy-chart connector brightness is the Lines knob (x child glow).

  // ---- Color -------------------------------------------------------------------

  /** Deep ochre convergence target: at Ochre=1 EVERY generation pulls fully to
   *  this deep, less-bright red-brown. */
  private static final float OCHRE_TARGET_HUE = 22;    // CURATE
  private static final float OCHRE_TARGET_SAT = 72;    // CURATE
  private static final float OCHRE_TARGET_BRIGHT = 45; // CURATE

  /** Ochre fallback (empty swatch): hue drifts sienna-red with lineage depth */
  private static final float OCHRE_HUE = 32; // CURATE
  private static final float OCHRE_HUE_DEEP = 20; // CURATE

  // ---- Parameters --------------------------------------------------------------

  public final TriggerParameter seed =
    new TriggerParameter("Seed", this::seed)
    .setDescription("Birth one new root ancestor at the base of both surfaces now");

  public final TriggerParameter bloom =
    new TriggerParameter("Bloom", this::bloom)
    .setDescription("Birth bloom: raise the arms of all figures and spawn a burst of raised-arm ancestors (the 2:32 climax)");

  public final TriggerParameter genesis =
    new TriggerParameter("Genesis", this::genesis)
    .setDescription("Fade both fields to black and begin a fresh lineage: a new figure's head immediately emerges from the bottom edge at a door-free column");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 0.3)
    .setDescription("Rise speed of the figures: 0 freezes the animation completely; max stays within the >=5s traversal cap");

  public final CompoundParameter density =
    new CompoundParameter("Density", 0.5)
    .setDescription("How many first-generation ancestors populate the wall at pattern init / Genesis (and the Bloom burst size)");

  public final CompoundParameter figScale =
    new CompoundParameter("Scale", 0.5)
    .setDescription("Height of the cave-painting figures");

  public final CompoundParameter ochre =
    new CompoundParameter("Ochre", 0.6)
    .setDescription("Ochre convergence: 0 = palette colors per generation, 1 = every figure pulled fully to one deep red-brown");

  public final CompoundParameter fertility =
    new CompoundParameter("Fertl", 0.6)
    .setDescription("Lineage branching: how readily a rising figure drips a new child below it");

  public final CompoundParameter genSpacing =
    new CompoundParameter("GenY", 0.4)
    .setDescription("Generational Y spacing of the family tree; re-spaces all living figures, not just new births");

  public final CompoundParameter sway =
    new CompoundParameter("Sway", 0.4)
    .setDescription("Amplitude of each figure's independent gentle horizontal sway");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 0.7)
    .setDescription("Antialiasing + sub-pixel motion + feedback-smear trails in one: 0 = hard on/off pixels, snapped motion, no trails; 1 = antialiased strokes, gliding sub-pixel rise, gauzy gossamer trails");

  public final CompoundParameter connectorLevel =
    new CompoundParameter("Lines", 0.3)
    .setDescription("Family-tree connector line brightness: 0 = no genealogy lines, 1 = full brightness");

  public final CompoundParameter interiorLevel =
    new CompoundParameter("Inner", 0.25)
    .setDescription("Interior brightness: the dark echo of the exterior (0 = black interior)");

  public final CompoundParameter cylinderLevel =
    new CompoundParameter("Cyl", 1.0)
    .setDescription("Brightness of all cylinder figures (scales the whole cylinder field)");

  // ---- Palette color cache (Satori-style change detection) -------------------

  private final int[] genColor = new int[MAX_GEN + 1];
  private final int[] cachedSwatch = new int[16];
  private int cachedSwatchCount = -1;
  private double cachedOchre = Double.NaN;

  // ---- Fields (surfaces) -----------------------------------------------------

  private final Field cubeField;
  private final Field cylinderField;

  public Aumakua(LX lx) {
    super(lx);

    this.cubeField = new Field(
      Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT,
      (Apotheneum.cube != null) ? Apotheneum.cube.exterior : null, 0x5eed01);
    this.cylinderField = new Field(
      Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT,
      (Apotheneum.cylinder != null) ? Apotheneum.cylinder.exterior : null, 0x5eed02);

    addParameter("seed", this.seed);
    addParameter("bloom", this.bloom);
    addParameter("genesis", this.genesis);
    addParameter("speed", this.speed);
    addParameter("density", this.density);
    addParameter("figScale", this.figScale);
    addParameter("ochre", this.ochre);
    addParameter("fertility", this.fertility);
    addParameter("genY", this.genSpacing);
    addParameter("sway", this.sway);
    addParameter("smooth", this.smooth);
    addParameter("lines", this.connectorLevel);
    addParameter("interior", this.interiorLevel);
    addParameter("cylinder", this.cylinderLevel);

    // First generation: Density-many ancestors so the field starts populated
    this.cubeField.seedInitialGeneration(false);
    this.cylinderField.seedInitialGeneration(false);
  }

  // ---- Trigger handlers ------------------------------------------------------

  private void seed() {
    this.cubeField.spawnRoot(false, false, 0);
    this.cylinderField.spawnRoot(false, false, 0);
  }

  /** The birth bloom: raise arms across the living lineage and add a burst of
   *  raised-arm ancestors, brighter — the 2:32 climax as a manual cue. */
  private void bloom() {
    this.cubeField.bloom();
    this.cylinderField.bloom();
  }

  /** Fade to black, clear the lineage, and immediately begin anew: one
   *  figure's head emerges from the bottom edge at a door-free column. */
  private void genesis() {
    this.cubeField.genesis();
    this.cylinderField.genesis();
  }

  // ---- Render ----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    setColors(LXColor.BLACK);
    refreshColors();

    // Beat-relative rise: rows-per-beat (Speed-mapped, linear from 0) scaled by
    // the live host beat rate, clamped so the full traversal stays >= 5 s at
    // any tempo. This is a continuous, tempo-following rate — never a
    // Sync/TempoDiv grid snap. Speed=0 freezes the animation completely.
    final double beatMs = Math.max(this.lx.engine.tempo.period.getValue(), MIN_BEAT_MS);
    final double riseRowsPerSec = Math.min(
      this.speed.getValue() * RISE_ROWS_PER_BEAT_PEAK * 1000.0 / beatMs,
      MAX_RISE_ROWS_PER_SEC);

    final double halfLifeMs = Ranges.exp(this.smooth.getValue(), TRAIL_HALF_LIFE_MIN_MS, TRAIL_HALF_LIFE_MAX_MS);
    final double decayMult = Math.pow(0.5, deltaMs / halfLifeMs);

    this.cubeField.render(deltaMs, riseRowsPerSec, decayMult);
    this.cylinderField.render(deltaMs, riseRowsPerSec, decayMult);

    // Exterior at full brightness (cylinder scaled by Cyl); interior a
    // dimmed, softer echo via Inner.
    final double innerMult = this.interiorLevel.getValue();
    final double cylMult = this.cylinderLevel.getValue();
    this.cubeField.canvas.copyTo(Apotheneum.cube.exterior, this.colors);
    this.cylinderField.canvas.copyTo(Apotheneum.cylinder.exterior, this.colors, cylMult, false);
    if (Apotheneum.cube.interior != null) {
      this.cubeField.canvas.copyTo(Apotheneum.cube.interior, this.colors, innerMult, false);
    }
    if (Apotheneum.cylinder.interior != null) {
      this.cylinderField.canvas.copyTo(Apotheneum.cylinder.interior, this.colors, innerMult * cylMult, false);
    }
  }

  // ---- Palette-driven ochre colors -------------------------------------------

  /** Rebuild the per-generation lineage colors only when the swatch or Ochre
   *  actually changed (Satori cache idiom). Colors come from the project
   *  palette; the Ochre knob converges them all to one deep red-brown at 1.
   *  An empty swatch falls back to an ochre ramp so the pattern is never
   *  colorless. */
  private void refreshColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = Math.min(swatch.size(), this.cachedSwatch.length);
    final double w = this.ochre.getValue();

    boolean changed = (n != this.cachedSwatchCount) || (w != this.cachedOchre);
    for (int i = 0; i < n && !changed; ++i) {
      if (this.cachedSwatch[i] != swatch.get(i).getColor()) {
        changed = true;
      }
    }
    if (!changed) {
      return;
    }
    this.cachedSwatchCount = n;
    this.cachedOchre = w;
    for (int i = 0; i < n; ++i) {
      this.cachedSwatch[i] = swatch.get(i).getColor();
    }

    for (int g = 0; g <= MAX_GEN; ++g) {
      final int base = (n > 0)
        ? this.cachedSwatch[g % n]
        : ochreFallback(g);
      this.genColor[g] = ochreColor(base, w, g);
    }
  }

  /** Ochre ramp for an empty swatch: hue drifts sienna-red and dims with depth */
  private int ochreFallback(int gen) {
    final float t = gen / (float) MAX_GEN;
    final float hue = LXUtils.lerpf(OCHRE_HUE, OCHRE_HUE_DEEP, t);
    final float bright = LXUtils.lerpf(88, 52, t); // CURATE: depth-haze ramp
    return LXColor.hsb(hue, 62, bright);
  }

  /** Converge a base color toward the deep ochre target by the Ochre knob.
   *  At w=0: the palette color, dimmed slightly with lineage depth so older
   *  generations recede. At w=1: exactly the OCHRE_TARGET for every
   *  generation — one deep, less-bright red-brown across the whole family
   *  tree. */
  private int ochreColor(int base, double w, int gen) {
    final float depth = LXUtils.lerpf(1f, 0.72f, gen / (float) MAX_GEN);
    final float baseHue = LXColor.h(base);
    final float baseSat = LXColor.s(base);
    final float baseBright = LXColor.b(base) * depth;
    final float t = (float) w;
    final float hue = hueLerp(baseHue, OCHRE_TARGET_HUE, t);
    final float sat = LXUtils.lerpf(baseSat, OCHRE_TARGET_SAT, t);
    final float bright = LXUtils.lerpf(baseBright, OCHRE_TARGET_BRIGHT, t);
    return LXColor.hsb(hue,
      LXUtils.constrainf(sat, 0, 100),
      LXUtils.constrainf(bright, 0, 100));
  }

  /** Shortest-path hue lerp on the 0..360 wheel */
  private static float hueLerp(float from, float to, float t) {
    float d = to - from;
    if (d > 180) {
      d -= 360;
    } else if (d < -180) {
      d += 360;
    }
    float h = from + d * t;
    if (h < 0) {
      h += 360;
    } else if (h >= 360) {
      h -= 360;
    }
    return h;
  }

  /** Scale an ARGB's RGB channels by coverage (0..1), alpha forced opaque —
   *  the coverage-before-blend used for antialiased rim fringes. Zero-alloc. */
  private static int scaleRgb(int argb, double cov) {
    final int r = (int) (((argb >> 16) & 0xff) * cov);
    final int g = (int) (((argb >> 8) & 0xff) * cov);
    final int b = (int) ((argb & 0xff) * cov);
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  // ---- Figure ----------------------------------------------------------------

  /** One Kolo-style cave-painting human. Pooled and reused; never allocated
   *  in render. */
  private static final class Figure {
    boolean active;
    double x;          // column (wraps in [0, width))
    double y;          // feet row; top = 0, so rising means y decreases
    double height;     // rows
    double armRaise;   // 0 = arms down-forward, 1 = raised (orant) pose
    int gen;           // lineage depth (colors by min(gen, MAX_GEN))
    double glow;       // 0..1 birth-in / fade-out envelope
    double ageMs;
    double swayPhase;
    double swayRate;   // rad/ms
    double legJitter;  // per-figure leg spread jitter
    double lastDripY;  // feet row at the last drip
    int children;
    // Family-tree attachment: while the parent lives, y is DERIVED from it
    // every frame (so GenY re-spaces living trees). -1 = root or detached.
    int parentSlot;
    int parentBirthId; // parent's birthId at link time (pool-reuse guard)
    int birthId;       // monotonic per-Field stamp, set at every spawn
    double sibJitter;  // tiny y offset off the shared sibling rank line
    int resolvedStamp; // frame memo for Field.resolveY()
    // Kolo pose
    double lean;       // forward pitch (fraction of h at head height)
    int dir;           // facing, ±1
    double stride;     // leg scissor amount
    double armAsym;    // hand-drawn arm asymmetry (fraction of h)
  }

  // ---- Field (one surface's generative system) -------------------------------

  private final class Field {
    private final SurfaceCanvas canvas;
    private final int W, H;
    private final Apotheneum.Orientation orientation; // door lookup; may be null
    private final Figure[] pool = new Figure[MAX_FIGURES];
    private final Random rng;
    private int birthCounter;
    private int resolveStamp; // per-frame memo stamp for resolveY()
    /** Smooth knob cached per-frame: 1 = sub-pixel glide + AA, 0 = pixel-snap. */
    private double smoothV = 1.0;

    private Field(int width, int height, Apotheneum.Orientation orientation, long seed) {
      this.canvas = new SurfaceCanvas(width, height);
      this.W = width;
      this.H = height;
      this.orientation = orientation;
      this.rng = new Random(seed);
      for (int i = 0; i < MAX_FIGURES; ++i) {
        this.pool[i] = new Figure();
      }
    }

    /** Find an inactive pool slot, or -1 if the field is full (no alloc). */
    private int freeSlot() {
      for (int i = 0; i < MAX_FIGURES; ++i) {
        if (!this.pool[i].active) {
          return i;
        }
      }
      return -1;
    }

    /** How many first-generation ancestors Density asks for on this surface. */
    private int initialAncestors() {
      return Math.max(1, (int) Math.round(density.getValue() * this.W / INITIAL_ANCESTOR_COLS));
    }

    /** Populate the first generation: Density-many gen-0 ancestors. When
     *  emerging (Genesis), the first head crests the bottom edge immediately
     *  and the rest start staggered a little deeper, so heads surface one
     *  after another rather than as a lockstep row. */
    private void seedInitialGeneration(boolean emerging) {
      final int n = initialAncestors();
      for (int i = 0; i < n; ++i) {
        final double extraDepth = (emerging && i > 0) ? this.rng.nextDouble() * FIG_HEIGHT_MAX : 0;
        spawnRoot(false, emerging, extraDepth);
      }
    }

    /** Gap from a parent's feet row down to a child's head row. Deterministic
     *  per parent — siblings share one genealogy rank line. */
    private double gapBase(Figure parent) {
      return GAP_MIN + genSpacing.getValue() * GAP_RANGE + GAP_H_FRAC * parent.height;
    }

    /** Derive an attached figure's y from its (already-resolved) parent this
     *  frame; detach orphans whose parent slot was retired or reused. Stamp
     *  memoization makes each figure resolve exactly once per frame. */
    private void resolveY(Figure c) {
      if (c.resolvedStamp == this.resolveStamp) {
        return;
      }
      c.resolvedStamp = this.resolveStamp;
      if (c.parentSlot < 0) {
        return;
      }
      final Figure p = this.pool[c.parentSlot];
      if (!p.active || (p.birthId != c.parentBirthId)) {
        c.parentSlot = -1; // orphan-safe: detach, keep last derived y
        return;
      }
      resolveY(p);
      c.y = p.y + gapBase(p) + c.height + c.sibJitter;
    }

    /** The figure's drawn x this frame: base x plus its own independent sway. */
    private double drawX(Figure f) {
      return f.x + sway.getValue() * f.height * 0.35
        * Math.sin(f.swayPhase + f.ageMs * f.swayRate);
    }

    /** Blend a coordinate between its true sub-pixel value (Smooth=1) and its
     *  integer-snapped value (Smooth=0): low Smooth reads as pixel-stepped
     *  motion, high Smooth as a smooth sub-pixel glide (motion blending). */
    private double snap(double v) {
      return LXUtils.lerp((double) Math.round(v), v, this.smoothV);
    }

    /** Randomize the per-figure sway and Kolo pose fields at any spawn. */
    private void randomizePose(Figure f) {
      f.swayPhase = this.rng.nextDouble() * Math.PI * 2;
      f.swayRate = (0.4 + this.rng.nextDouble() * 0.6) * 0.001; // slow
      f.legJitter = 0.75 + this.rng.nextDouble() * 0.5;
      f.dir = this.rng.nextBoolean() ? 1 : -1;
      f.lean = -0.05 + this.rng.nextDouble() * 0.19; // [-0.05, 0.14], biased forward
      f.stride = 0.35 + this.rng.nextDouble() * 0.65;
      f.armAsym = (this.rng.nextDouble() - 0.5) * 0.06;
    }

    /** Pick a spawn column whose surroundings are clear of door openings, so
     *  an emerging figure isn't swallowed by a portal. Bounded rng retries;
     *  falls back to the last try if the surface is too tight (never spins). */
    private int pickDoorFreeColumn(double height) {
      int x = this.rng.nextInt(this.W);
      if (this.orientation == null) {
        return x;
      }
      final int m = (int) Math.ceil(0.25 * height);
      for (int attempt = 0; attempt < 16; ++attempt) {
        x = this.rng.nextInt(this.W);
        boolean clear = true;
        for (int d = -m; d <= m && clear; ++d) {
          final int col = Math.floorMod(x + d, this.W);
          clear = (this.orientation.available(col) >= this.H);
        }
        if (clear) {
          return x;
        }
      }
      return x;
    }

    /** Birth a root ancestor at the base of the wall. When emerging, only the
     *  head crests the bottom edge (at a door-free column, extraDepth rows
     *  further below) and the body reveals as it rises — the Genesis
     *  entrance. */
    private void spawnRoot(boolean raised, boolean emerging, double extraDepth) {
      final int slot = freeSlot();
      if (slot < 0) {
        return;
      }
      final Figure f = this.pool[slot];
      final double h = figureHeight();
      f.active = true;
      f.height = h;
      if (emerging) {
        f.x = pickDoorFreeColumn(h);
        f.y = this.H - 1 + h + extraDepth; // feet below the wall; head at/under the crest
      } else {
        f.x = this.rng.nextInt(this.W);
        f.y = this.H - 1 - this.rng.nextInt(3); // feet near the bottom
      }
      f.armRaise = raised ? 1.0 : (this.rng.nextDouble() < RAISE_BIAS ? 1.0 : 0.0);
      f.gen = 0;
      f.glow = 0; // birth-in envelope handles the fade-up
      f.ageMs = 0;
      f.lastDripY = f.y;
      f.children = 0;
      f.parentSlot = -1;
      f.parentBirthId = 0;
      f.birthId = ++this.birthCounter;
      f.sibJitter = 0;
      randomizePose(f);
    }

    /** Drip a child below a rising parent — the lineage branch. Returns false
     *  (without consuming the drip) if the child's head would start below the
     *  wall; the parent then retries on later frames as it climbs. */
    private boolean spawnChild(Figure parent, int parentSlot) {
      final int slot = freeSlot();
      if (slot < 0) {
        return false;
      }
      final Figure c = this.pool[slot];
      final double h = figureHeight() * (0.8 + this.rng.nextDouble() * 0.3);
      final double sibJitter = (this.rng.nextDouble() - 0.5) * 2 * SIB_JITTER;
      final double headY = parent.y + gapBase(parent) + sibJitter;
      if (headY > this.H - 1) {
        return false; // no room below yet; retry as the parent rises
      }
      c.active = true;
      final double dx = (this.rng.nextBoolean() ? 1 : -1)
        * (parent.height * (0.4 + this.rng.nextDouble() * 0.6));
      c.x = Math.floorMod((int) Math.round(parent.x + dx), this.W);
      c.y = headY + h;
      c.height = h;
      c.armRaise = (this.rng.nextDouble() < 0.2) ? 1.0 : 0.0;
      c.gen = parent.gen + 1;
      c.glow = 0;
      c.ageMs = 0;
      c.lastDripY = c.y;
      c.children = 0;
      c.parentSlot = parentSlot;
      c.parentBirthId = parent.birthId;
      c.birthId = ++this.birthCounter;
      c.sibJitter = sibJitter;
      randomizePose(c);
      parent.children++;
      return true;
    }

    private int genColor(int gen) {
      return Aumakua.this.genColor[LXUtils.min(gen, MAX_GEN)];
    }

    private double figureHeight() {
      return LXUtils.lerp(FIG_HEIGHT_MIN, FIG_HEIGHT_MAX, figScale.getValue())
        * (0.85 + this.rng.nextDouble() * 0.3);
    }

    private void bloom() {
      for (int i = 0; i < MAX_FIGURES; ++i) {
        if (this.pool[i].active) {
          this.pool[i].armRaise = 1.0;
        }
      }
      final int burst = 3 + (int) Math.round(3 * density.getValue());
      for (int i = 0; i < burst; ++i) {
        spawnRoot(true, false, 0);
      }
    }

    /** Clear everything, then immediately begin the new lineage: a fresh
     *  first generation (Density-many ancestors) emerges from the bottom
     *  edge, the first head cresting immediately at a door-free column. */
    private void genesis() {
      for (int i = 0; i < MAX_FIGURES; ++i) {
        this.pool[i].active = false;
      }
      this.canvas.fill(LXColor.BLACK);
      seedInitialGeneration(true);
    }

    private void render(double deltaMs, double riseRowsPerSec, double decayMult) {
      this.smoothV = smooth.getValue();
      this.canvas.decay(decayMult);

      // Speed=0 is a TRUE FREEZE: no rise, no aging (so sway, glow envelopes
      // and drips all hold still). Only the trail decay above keeps settling
      // and GenY/pose knobs keep re-shaping the frozen tableau below.
      final boolean frozen = riseRowsPerSec <= 0;

      final double riseRows = riseRowsPerSec * deltaMs * 0.001;

      // (1) Integrate y for roots and detached figures only.
      if (!frozen) {
        for (int i = 0; i < MAX_FIGURES; ++i) {
          final Figure f = this.pool[i];
          if (f.active && (f.parentSlot < 0)) {
            f.y -= riseRows;
          }
        }
      }

      // (2) Attachment-resolve: an attached child's y is DERIVED from its
      // living parent, so GenY re-spaces living trees instantly (even while
      // frozen). Stamp-memoized recursion resolves each figure's ancestors
      // first; lineage depth is unbounded now that drips never stop, so this
      // replaces the old generation-indexed loop. Parent links form a forest
      // (gen strictly increases down a chain), so recursion cannot cycle.
      ++this.resolveStamp;
      for (int i = 0; i < MAX_FIGURES; ++i) {
        final Figure f = this.pool[i];
        if (f.active) {
          resolveY(f);
        }
      }

      // (3) Lifecycle: envelopes, retirement, drips. Fully skipped while
      // frozen — ages, glows and drip rolls all hold still.
      if (!frozen) {
        final double fertl = fertility.getValue();
        for (int i = 0; i < MAX_FIGURES; ++i) {
          final Figure f = this.pool[i];
          if (!f.active) {
            continue;
          }
          f.ageMs += deltaMs;

          // Birth-in envelope, then fade out as the figure nears the top.
          double env = LXUtils.constrain(f.ageMs / BIRTH_IN_MS, 0, 1);
          if (f.y < FADE_TOP_ROWS) {
            env *= LXUtils.constrain(f.y / FADE_TOP_ROWS, 0, 1);
          }
          f.glow = env;

          // Retire once fully risen off the top; detach my children in place
          // (their last derived y is continuous; they self-integrate after).
          if (f.y + f.height < 0) {
            for (int j = 0; j < MAX_FIGURES; ++j) {
              final Figure c = this.pool[j];
              if (c.active && (c.parentSlot == i) && (c.parentBirthId == f.birthId)) {
                c.parentSlot = -1;
              }
            }
            f.active = false;
            continue;
          }

          // Drip a child once the figure has climbed ~DRIP_RISE_BODIES of its
          // own height since its last drip (lineage branching). A drip blocked
          // by the bottom edge does NOT consume lastDripY — it retries as the
          // parent rises, so deep generations unroll from the bottom. There is
          // no generation cap (colors clamp at MAX_GEN): with no root respawn
          // timer, Fertl alone sustains the population.
          final double climbed = f.lastDripY - f.y;
          if ((climbed >= DRIP_RISE_BODIES * f.height)
              && (f.children < 2)
              && (f.glow > 0.4)
              && (this.rng.nextDouble() < fertl)) {
            if (spawnChild(f, i)) {
              f.lastDripY = f.y;
            }
          }
        }
      }

      // (4) Draw: genealogy connectors first, figures over them.
      for (int i = 0; i < MAX_FIGURES; ++i) {
        final Figure f = this.pool[i];
        if (f.active && (f.parentSlot >= 0)) {
          drawConnector(f);
        }
      }
      for (int i = 0; i < MAX_FIGURES; ++i) {
        final Figure f = this.pool[i];
        if (f.active) {
          drawFigure(f);
        }
      }
    }

    /** Genealogy-chart connector, drawn every frame between a living parent
     *  and an attached child: a right-angle T — vertical stub down from the
     *  parent's feet to the shared sibling rail, horizontal rail the short
     *  way around the ring, vertical drop to the child's head. Brightness is
     *  the Lines knob, faded with the child's glow; when the pair detaches it
     *  simply stops being drawn and the trail decay fades the residue. The
     *  endpoints ride the same Smooth sub-pixel interpolation as the figures,
     *  so the lines glide upward rather than stepping row to row. */
    private void drawConnector(Figure c) {
      final Figure p = this.pool[c.parentSlot]; // validated in pass (2)
      final double level = connectorLevel.getValue() * c.glow;
      if (level <= 0.01) {
        return;
      }
      final int conn = LXColor.scaleBrightness(genColor(c.gen), (float) level);
      final double px = snap(drawX(p));
      final double cxd = snap(drawX(c));
      final double railY = snap(p.y + 0.5 * gapBase(p));
      final double parentFeetY = snap(p.y);
      final double childHeadY = snap(c.y - c.height);
      // Short way around the ring; |dx| <= W/2 satisfies the wrap contract.
      double dx = (cxd - px) % this.W;
      if (dx > this.W / 2.0) {
        dx -= this.W;
      } else if (dx < -this.W / 2.0) {
        dx += this.W;
      }
      this.canvas.lineWu(px, parentFeetY, px, railY, conn, true, this.smoothV);
      this.canvas.lineWu(px, railY, px + dx, railY, conn, true, this.smoothV);
      this.canvas.lineWu(px + dx, railY, px + dx, childHeadY, conn, true, this.smoothV);
    }

    /** Draw one Kolo/Kondoa-style figure with MAX blending (strokes union,
     *  never erase) at its glow: elongated legs from high hips, short
     *  forward-leaning torso, large oval head, bent two-segment limbs with a
     *  scissored walking stride. Small figures degrade to single-segment
     *  limbs but keep the proportions. */
    private void drawFigure(Figure f) {
      final double glow = LXUtils.constrain(f.glow, 0, 1);
      if (glow <= 0.01) {
        return;
      }
      final int argb = LXColor.scaleBrightness(genColor(f.gen), (float) glow);

      final double h = f.height;
      final double cx = snap(drawX(f));
      final double feetY = snap(f.y);
      final double lean = f.lean * f.dir; // forward pitch, signed by facing

      // Joint x at height-fraction q leans forward proportionally to q.
      final double hipY = feetY - HIP_FRAC * h;
      final double hipX = cx + lean * HIP_FRAC * h;
      final double shoulderY = feetY - SHOULDER_FRAC * h;
      final double shoulderX = cx + lean * SHOULDER_FRAC * h;
      final int headRx = Math.max(1, (int) Math.round(HEAD_RX_FRAC * h));
      final int headRy = Math.max(1, (int) Math.round(HEAD_RY_FRAC * h));
      final double headCy = feetY - h + headRy;
      final double headCx = cx + lean * (h - headRy);

      final boolean twoSegment = h >= TWO_SEGMENT_MIN_HEIGHT;

      // Torso (double-stroked when big enough: painted body, thicker than limbs)
      this.canvas.lineWu(hipX, hipY, shoulderX, shoulderY, argb, true, this.smoothV);
      if (h >= TORSO_DOUBLE_MIN_HEIGHT) {
        this.canvas.lineWu(hipX + 0.7, hipY, shoulderX + 0.7, shoulderY, argb, true, this.smoothV);
      }
      // Neck up to the bottom of the head, then the big oval head
      this.canvas.lineWu(shoulderX, shoulderY, headCx, headCy + headRy, argb, true, this.smoothV);
      stampEllipse(headCx, headCy, headRx, headRy, argb);

      // Legs — scissored walking stride from the high hip
      final double kneeY = feetY - KNEE_FRAC * h;
      final double spread = f.stride * f.legJitter * h;
      final double frontFootX = hipX + f.dir * FRONT_FOOT_DX * spread;
      final double backFootX = hipX - f.dir * BACK_FOOT_DX * spread;
      if (twoSegment) {
        final double frontKneeX = hipX + f.dir * FRONT_KNEE_DX * spread;
        final double backKneeX = hipX - f.dir * BACK_KNEE_DX * spread;
        this.canvas.lineWu(hipX, hipY, frontKneeX, kneeY, argb, true, this.smoothV);
        this.canvas.lineWu(frontKneeX, kneeY, frontFootX, feetY, argb, true, this.smoothV);
        this.canvas.lineWu(hipX, hipY, backKneeX, kneeY, argb, true, this.smoothV);
        this.canvas.lineWu(backKneeX, kneeY, backFootX, feetY, argb, true, this.smoothV);
      } else {
        this.canvas.lineWu(hipX, hipY, frontFootX, feetY, argb, true, this.smoothV);
        this.canvas.lineWu(hipX, hipY, backFootX, feetY, argb, true, this.smoothV);
      }

      // Arms — two segments lerped between down-forward and raised (orant)
      final double r = f.armRaise;
      for (int s = -1; s <= 1; s += 2) {
        final double asym = (s > 0) ? f.armAsym * h : 0;
        final double elbowX = shoulderX + s * LXUtils.lerp(0.09, 0.15, r) * h + asym;
        final double elbowY = shoulderY + LXUtils.lerp(0.12, -0.05, r) * h;
        final double handX = shoulderX + s * LXUtils.lerp(0.12, 0.19, r) * h
          + f.dir * 0.04 * h * (1 - r) + asym;
        final double handY = shoulderY + LXUtils.lerp(0.24, -0.22, r) * h;
        if (twoSegment) {
          this.canvas.lineWu(shoulderX, shoulderY, elbowX, elbowY, argb, true, this.smoothV);
          this.canvas.lineWu(elbowX, elbowY, handX, handY, argb, true, this.smoothV);
        } else {
          this.canvas.lineWu(shoulderX, shoulderY, handX, handY, argb, true, this.smoothV);
        }
      }
    }

    /** Filled ellipse (the head), max-blended, x wraps. The left/right rim is
     *  antialiased by fractional edge coverage, blended toward a hard step as
     *  Smooth -> 0, so the biggest drawn form softens with the Smooth knob. */
    private void stampEllipse(double cx, double cy, int rx, int ry, int argb) {
      final int icx = (int) Math.round(cx);
      final int icy = (int) Math.round(cy);
      for (int dy = -ry; dy <= ry; ++dy) {
        final double t = dy / (double) ry;
        final double hw = rx * Math.sqrt(Math.max(0, 1 - t * t)); // exact half-width
        final int dxMax = (int) Math.ceil(hw);
        for (int dx = -dxMax; dx <= dxMax; ++dx) {
          final double aa = LXUtils.constrain(hw - Math.abs(dx) + 0.5, 0, 1);
          final double hard = (aa >= 0.5) ? 1 : 0;
          final double cov = LXUtils.lerp(hard, aa, this.smoothV);
          if (cov <= 0) {
            continue;
          }
          this.canvas.setMax(icx + dx, icy + dy,
            (cov >= 0.999) ? argb : scaleRgb(argb, cov));
        }
      }
    }
  }
}
