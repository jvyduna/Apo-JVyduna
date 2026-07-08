package apotheneum.jvyduna.patterns.temper;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
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
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * A generative ochre CAVE-PAINTING of human figures that slowly rise up the
 * exterior surfaces, weaving continuous FAMILY TREES of humanity. Each figure
 * is a Lascaux/Chauvet stick-body form; as it climbs, its limbs DRIP downward
 * and seed new figures below, so lineage strands branch upward across the wall.
 *
 * The opener of "Communicating" — the genesis (childbirth + ancestry;
 * "aumakua" = ancestral guardian spirits). The cube INTERIOR is a womb-dark,
 * softer/darker echo of the exterior; the figures and their ochre warmth live
 * on the exterior faces and the cylinder ring. The trombone envelope
 * (AudioReactive level/mid) warms the glow and quickens births; at the 2:32
 * G5 melodic peak the field blooms — a burst of raised-arm ("orant") figures
 * rising brightest.
 *
 * Zero-alloc: two independent {@link Field}s (cube ring, cylinder) each own a
 * fixed pool of {@link Figure} objects, all preallocated in the constructor;
 * births reuse an inactive pool slot rather than allocating. Color is
 * palette-driven (project swatch, sampled per lineage generation, warmed toward
 * ochre) with a documented ochre fallback for an empty swatch.
 *
 * See Aumakua.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Aumakua")
@LXComponent.Description("Generative ochre cave-painting human figures rising in family trees up the exterior, dripping new figures below, blooming with the trombone; womb-dark interior")
public class Aumakua extends ApotheneumPattern {

  // ---- Timing / motion constants ---------------------------------------------

  /** Rise speed endpoints in rows/sec, mapped from Energy. Ambient is patient
   *  (a figure crosses the ~45-row cube in ~30 s); peak still keeps the full
   *  traversal >= 5 s (45 / 6.43 = 7.0 s), well inside the simulation cap. */
  private static final double RISE_ROWS_PER_SEC_AMBIENT = 1.5;
  private static final double RISE_ROWS_PER_SEC_PEAK = 6.43; // CURATE: 7.0s cube traversal at e=1

  /** Root-spawn idle interval endpoints (ms), mapped from Energy (exp). The
   *  slow ambient cadence is the point — roots are the rare new ancestors;
   *  most figures arrive as drips from rising parents. */
  private static final double ROOT_INTERVAL_MS_AMBIENT = 11000; // CURATE
  private static final double ROOT_INTERVAL_MS_PEAK = 2200;     // CURATE

  /** Trombone level shortens the root interval by up to this factor at level=1
   *  (interval /= 1 + AUDIO_SPAWN * level) — swells birth new ancestors. */
  private static final double AUDIO_SPAWN = 2.5; // CURATE

  /** A newborn figure fades IN over this long (its "birth"), so every figure
   *  has >= 1.5 s of visual life before it can be judged an event-flash. */
  private static final double BIRTH_IN_MS = 2200;

  /** Rows below the top edge over which a rising figure fades back OUT as it
   *  reaches the "safer space" at the top of the wall. */
  private static final double FADE_TOP_ROWS = 8;

  /** Trail (feedback smear / rising comet) half-life endpoints (ms), mapped
   *  from the Smear knob. Low = crisp figures, high = gauzy gossamer loops. */
  private static final double SMEAR_HALF_LIFE_MIN_MS = 200;
  private static final double SMEAR_HALF_LIFE_MAX_MS = 6000;

  /** Trombone level lifts figure glow above its resting brightness by up to
   *  this fraction at level=1 (the ochre "warms" with the horn). */
  private static final double AUDIO_GLOW = 0.6; // CURATE

  /** Structural maxima — all pool state is preallocated at these sizes */
  private static final int MAX_FIGURES = 64;
  private static final int MAX_GEN = 5; // lineage depth colors cycle over

  // ---- Cave-painting figure geometry (fractions of figure height) ------------

  private static final double HIP_FRAC = 0.38;
  private static final double SHOULDER_FRAC = 0.72;
  private static final double LEG_SPREAD = 0.22;
  private static final double ARM_SPREAD = 0.28;
  /** Arm end Y offset from shoulder as a fraction of height: down-and-out vs
   *  raised (orant) pose, lerped by the figure's armRaise. */
  private static final double ARM_DOWN_DY = 0.28;
  private static final double ARM_UP_DY = -0.42;
  private static final double HEAD_RADIUS_FRAC = 0.09;

  /** Figure height endpoints (rows), mapped from the Scale knob */
  private static final double FIG_HEIGHT_MIN = 7;
  private static final double FIG_HEIGHT_MAX = 15;

  /** A rising parent drips a child once it has climbed this many of its own
   *  body-heights, if the Drip roll passes and lineage depth allows. */
  private static final double DRIP_RISE_BODIES = 1.1; // CURATE

  /** Faint lineage-strand brightness (fraction of the child color) drawn from
   *  parent foot down to child head at a drip birth; persists via decay. */
  private static final double STRAND_LEVEL = 0.35; // CURATE

  /** Ochre fallback (empty swatch): hue drifts sienna-red with lineage depth */
  private static final float OCHRE_HUE = 32; // CURATE
  private static final float OCHRE_HUE_DEEP = 20; // CURATE

  // ---- Composition helpers / parameters --------------------------------------

  private final TriggerBag bag = new TriggerBag("Aumakua");
  private final AudioReactive audio;
  private final TempoLock tempoLock;

  public final TriggerParameter seed = bag.register(
    new TriggerParameter("Seed", this::seed)
    .setDescription("Birth one new root ancestor at the base of both surfaces now"));

  public final TriggerParameter bloom = bag.register(
    new TriggerParameter("Bloom", this::bloom)
    .setDescription("Birth bloom: raise the arms of all figures and spawn a burst of raised-arm ancestors (the 2:32 climax)"));

  public final TriggerParameter drift = bag.register(
    new TriggerParameter("Drift", this::drift)
    .setDescription("Re-randomize every figure's gentle horizontal sway phase and direction"));

  public final TriggerParameter genesis = bag.register(
    new TriggerParameter("Genesis", this::genesis)
    .setDescription("Fade both fields to black and begin a fresh lineage (new genesis)"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.3)
    .setDescription("Master energy: rise speed and birth cadence (ambient <-> quicker); stays within the >=5s traversal cap");

  public final CompoundParameter density =
    new CompoundParameter("Density", 0.5)
    .setDescription("How many figures live at once and how readily new ancestors are born");

  public final CompoundParameter figScale =
    new CompoundParameter("Scale", 0.5)
    .setDescription("Height of the cave-painting figures");

  public final CompoundParameter warmth =
    new CompoundParameter("Warmth", 0.6)
    .setDescription("Ochre warmth: pulls the palette color toward amber/sienna and lifts its glow");

  public final CompoundParameter dripChance =
    new CompoundParameter("Drip", 0.6)
    .setDescription("Lineage branching: how readily a rising figure drips a new child below it");

  public final CompoundParameter sway =
    new CompoundParameter("Sway", 0.4)
    .setDescription("Amplitude of each figure's gentle organic horizontal sway");

  public final CompoundParameter smear =
    new CompoundParameter("Smear", 0.5)
    .setDescription("Feedback-smear trail length: 0 = crisp figures, 1 = gauzy gossamer rising loops");

  public final CompoundParameter womb =
    new CompoundParameter("Womb", 0.25)
    .setDescription("Interior brightness: the dark-womb echo of the exterior (0 = black interior)");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full trombone-driven warmth and births");

  public final BooleanParameter sync =
    new BooleanParameter("Sync", true)
    .setDescription("Hold each due birth to the next tempo-grid boundary; off = free-running rubato births");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.WHOLE)
    .setDescription("Slow tempo division that due births land on when Sync is enabled (WHOLE = one breathing bar)");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire one of Aumakua's triggers or jump a parameter");

  // ---- Palette color cache (Satori-style change detection) -------------------

  private final int[] genColor = new int[MAX_GEN + 1];
  private final int[] cachedSwatch = new int[16];
  private int cachedSwatchCount = -1;
  private double cachedWarmth = Double.NaN;

  // ---- Fields (surfaces) -----------------------------------------------------

  private final Field cubeField;
  private final Field cylinderField;

  /** True on this frame if a birth may fire now (tempo gate crossed, or Sync
   *  off). Computed once per frame, shared by both fields. */
  private boolean birthGateOpen;

  public Aumakua(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    this.cubeField = new Field(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT, 0x5eed01);
    this.cylinderField = new Field(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT, 0x5eed02);

    addParameter("seed", this.seed);
    addParameter("bloom", this.bloom);
    addParameter("drift", this.drift);
    addParameter("genesis", this.genesis);
    addParameter("energy", this.energy);
    addParameter("density", this.density);
    addParameter("figScale", this.figScale);
    addParameter("warmth", this.warmth);
    addParameter("dripChance", this.dripChance);
    addParameter("sway", this.sway);
    addParameter("smear", this.smear);
    addParameter("womb", this.womb);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    // Meta jump candidates — mirrored 1:1 in the Jump candidates table in Aumakua.md
    bag.jumpable(this.density, 0.25, 0.9);
    bag.jumpable(this.figScale, 0.2, 0.9);
    bag.jumpable(this.warmth, 0.3, 1.0);
    bag.jumpable(this.dripChance, 0.3, 1.0);
    bag.jumpable(this.sway, 0.0, 0.8);
    bag.jumpable(this.smear, 0.2, 0.9);

    // Seed a couple of ancestors so the field is never empty at load
    this.cubeField.spawnRoot(false, 1.0);
    this.cubeField.spawnRoot(false, 1.0);
    this.cylinderField.spawnRoot(false, 1.0);
  }

  // ---- Trigger handlers ------------------------------------------------------

  private void seed() {
    this.cubeField.spawnRoot(false, 1.0);
    this.cylinderField.spawnRoot(false, 1.0);
  }

  /** The birth bloom: raise arms across the living lineage and add a burst of
   *  raised-arm ancestors, brighter — the 2:32 climax as a manual cue. */
  private void bloom() {
    this.cubeField.bloom();
    this.cylinderField.bloom();
  }

  private void drift() {
    this.cubeField.reseed();
    this.cylinderField.reseed();
  }

  /** Fade to black and clear the lineage — a fresh genesis. */
  private void genesis() {
    this.cubeField.genesis();
    this.cylinderField.genesis();
  }

  // ---- Render ----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    // Birth gate: poll the tempo crossing EXACTLY ONCE per frame (crossed()
    // consumes the crossing; both fields must share this boolean). Rubato
    // songs use a slow division, so this only nudges births onto a breathing
    // bar line — never a foot-tappable click. Sync off = always open.
    final boolean crossed = this.tempoLock.crossed(this.tempoDiv.getEnum());
    this.birthGateOpen = !this.sync.isOn() || crossed;

    refreshColors();

    // The trombone envelope: level (overall) blended with mid (the horn's
    // voice sits in the mid bands). Depth-gated inside AudioReactive, so this
    // is exactly 0 at silence / Audio=0 (pure screensaver).
    final double trom = 0.6 * this.audio.level + 0.4 * this.audio.mid;

    final double e = this.energy.getValue();
    final double riseRowsPerSec = Ranges.lin(e, RISE_ROWS_PER_SEC_AMBIENT, RISE_ROWS_PER_SEC_PEAK);
    double rootIntervalMs = Ranges.exp(e, ROOT_INTERVAL_MS_AMBIENT, ROOT_INTERVAL_MS_PEAK);
    rootIntervalMs /= (1 + AUDIO_SPAWN * trom);
    rootIntervalMs *= LXUtils.lerp(1.8, 0.5, this.density.getValue()); // dense = faster ancestors

    // Warmth-lifted glow: resting brightness plus the trombone envelope
    final double glowMult = 1 + AUDIO_GLOW * trom;

    double halfLifeMs = Ranges.exp(this.smear.getValue(), SMEAR_HALF_LIFE_MIN_MS, SMEAR_HALF_LIFE_MAX_MS);
    final double decayMult = Math.pow(0.5, deltaMs / halfLifeMs);

    // Trombone swell also biases newborns toward the raised (orant) pose
    final double raiseBias = LXUtils.constrain(0.15 + trom, 0, 1);

    this.cubeField.render(deltaMs, riseRowsPerSec, rootIntervalMs, decayMult, glowMult, raiseBias);
    this.cylinderField.render(deltaMs, riseRowsPerSec, rootIntervalMs, decayMult, glowMult, raiseBias);

    // Exterior at full brightness; interior a dimmed, softer womb echo.
    final double wombMult = this.womb.getValue();
    this.cubeField.canvas.copyTo(Apotheneum.cube.exterior, this.colors);
    this.cylinderField.canvas.copyTo(Apotheneum.cylinder.exterior, this.colors);
    if (Apotheneum.cube.interior != null) {
      this.cubeField.canvas.copyTo(Apotheneum.cube.interior, this.colors, wombMult, false);
    }
    if (Apotheneum.cylinder.interior != null) {
      this.cylinderField.canvas.copyTo(Apotheneum.cylinder.interior, this.colors, wombMult, false);
    }
  }

  // ---- Palette-driven ochre colors -------------------------------------------

  /** Rebuild the per-generation lineage colors only when the swatch or Warmth
   *  actually changed (Satori cache idiom). Colors come from the project
   *  palette, warmed toward ochre; an empty swatch falls back to an ochre
   *  ramp so the pattern is never colorless. */
  private void refreshColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = Math.min(swatch.size(), this.cachedSwatch.length);
    final double w = this.warmth.getValue();

    boolean changed = (n != this.cachedSwatchCount) || (w != this.cachedWarmth);
    for (int i = 0; i < n && !changed; ++i) {
      if (this.cachedSwatch[i] != swatch.get(i).getColor()) {
        changed = true;
      }
    }
    if (!changed) {
      return;
    }
    this.cachedSwatchCount = n;
    this.cachedWarmth = w;
    for (int i = 0; i < n; ++i) {
      this.cachedSwatch[i] = swatch.get(i).getColor();
    }

    for (int g = 0; g <= MAX_GEN; ++g) {
      final int base = (n > 0)
        ? this.cachedSwatch[g % n]
        : ochreFallback(g);
      this.genColor[g] = warmColor(base, w, g);
    }
  }

  /** Ochre ramp for an empty swatch: hue drifts sienna-red and dims with depth */
  private int ochreFallback(int gen) {
    final float t = gen / (float) MAX_GEN;
    final float hue = LXUtils.lerpf(OCHRE_HUE, OCHRE_HUE_DEEP, t);
    final float bright = LXUtils.lerpf(88, 52, t); // CURATE: depth-haze ramp
    return LXColor.hsb(hue, 62, bright);
  }

  /** Warm a base color toward ochre by the Warmth knob and dim slightly with
   *  lineage depth so older generations recede. */
  private int warmColor(int base, double w, int gen) {
    final float h = LXColor.h(base);
    final float s = LXColor.s(base);
    final float b = LXColor.b(base);
    final float target = LXUtils.lerpf(OCHRE_HUE, OCHRE_HUE_DEEP, gen / (float) MAX_GEN);
    final float hue = hueLerp(h, target, (float) (0.5 * w));
    final float sat = LXUtils.constrainf(s + (float) (18 * w), 0, 100);
    final float depth = LXUtils.lerpf(1f, 0.72f, gen / (float) MAX_GEN);
    final float bright = LXUtils.constrainf(b * depth + (float) (10 * w), 0, 100);
    return LXColor.hsb(hue, sat, bright);
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

  // ---- Figure ----------------------------------------------------------------

  /** One cave-painting human. Pooled and reused; never allocated in render. */
  private static final class Figure {
    boolean active;
    double x;          // column (wraps in [0, width))
    double y;          // feet row; top = 0, so rising means y decreases
    double height;     // rows
    double armRaise;   // 0 = arms down/out, 1 = raised (orant) pose
    int gen;           // lineage depth (colors by min(gen, MAX_GEN))
    double glow;       // 0..1 birth-in / fade-out envelope
    double ageMs;
    double swayPhase;
    double swayRate;   // rad/ms
    double legJitter;  // per-figure leg spread jitter
    double birthY;     // feet row at birth, for measuring rise since last drip
    double lastDripY;  // feet row at the last drip
    int children;
  }

  // ---- Field (one surface's generative system) -------------------------------

  private final class Field {
    private final SurfaceCanvas canvas;
    private final int W, H;
    private final Figure[] pool = new Figure[MAX_FIGURES];
    private final Random rng;
    private double sinceRootMs;

    private Field(int width, int height, long seed) {
      this.canvas = new SurfaceCanvas(width, height);
      this.W = width;
      this.H = height;
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

    private int liveCount() {
      int c = 0;
      for (int i = 0; i < MAX_FIGURES; ++i) {
        if (this.pool[i].active) {
          ++c;
        }
      }
      return c;
    }

    /** Birth a root ancestor at the base of the wall. */
    private void spawnRoot(boolean raised, double glowScale) {
      final int slot = freeSlot();
      if (slot < 0) {
        return;
      }
      final Figure f = this.pool[slot];
      final double h = figureHeight();
      f.active = true;
      f.x = this.rng.nextInt(this.W);
      f.y = this.H - 1 - this.rng.nextInt(3); // feet near the bottom
      f.height = h;
      f.armRaise = raised ? 1.0 : (this.rng.nextDouble() < 0.15 ? 1.0 : 0.0);
      f.gen = 0;
      f.glow = 0; // birth-in envelope handles the fade-up; glowScale caps it
      f.ageMs = 0;
      f.swayPhase = this.rng.nextDouble() * Math.PI * 2;
      f.swayRate = (0.4 + this.rng.nextDouble() * 0.6) * 0.001; // slow
      f.legJitter = 0.75 + this.rng.nextDouble() * 0.5;
      f.birthY = f.y;
      f.lastDripY = f.y;
      f.children = 0;
    }

    /** Drip a child below a rising parent — the lineage branch. */
    private void spawnChild(Figure parent) {
      final int slot = freeSlot();
      if (slot < 0) {
        return;
      }
      final Figure c = this.pool[slot];
      final double h = figureHeight() * (0.8 + this.rng.nextDouble() * 0.3);
      c.active = true;
      final double dx = (this.rng.nextBoolean() ? 1 : -1)
        * (parent.height * (0.4 + this.rng.nextDouble() * 0.6));
      c.x = Math.floorMod((int) Math.round(parent.x + dx), this.W);
      // Child head hangs just below the parent's feet
      c.y = parent.y + h + parent.height * 0.25;
      if (c.y >= this.H + h) {
        c.active = false; // dripped off the bottom; drop it
        return;
      }
      c.height = h;
      c.armRaise = (this.rng.nextDouble() < 0.2) ? 1.0 : 0.0;
      c.gen = parent.gen + 1;
      c.glow = 0;
      c.ageMs = 0;
      c.swayPhase = this.rng.nextDouble() * Math.PI * 2;
      c.swayRate = (0.4 + this.rng.nextDouble() * 0.6) * 0.001;
      c.legJitter = 0.75 + this.rng.nextDouble() * 0.5;
      c.birthY = c.y;
      c.lastDripY = c.y;
      c.children = 0;
      parent.children++;

      // Lineage strand: faint descending line from parent feet to child head,
      // drawn once, left to decay as a fading branch of the family tree.
      final int strand = LXColor.scaleBrightness(this.genColor(c.gen), (float) STRAND_LEVEL);
      this.canvas.lineMax(
        (int) Math.round(parent.x), (int) Math.round(parent.y),
        (int) Math.round(c.x), (int) Math.round(c.y - c.height), strand);
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
        spawnRoot(true, 1.0);
      }
    }

    private void reseed() {
      for (int i = 0; i < MAX_FIGURES; ++i) {
        final Figure f = this.pool[i];
        if (f.active) {
          f.swayPhase = this.rng.nextDouble() * Math.PI * 2;
          f.swayRate = (0.4 + this.rng.nextDouble() * 0.6) * 0.001;
        }
      }
    }

    private void genesis() {
      for (int i = 0; i < MAX_FIGURES; ++i) {
        this.pool[i].active = false;
      }
      this.canvas.fill(LXColor.BLACK);
      this.sinceRootMs = 0;
    }

    private void render(double deltaMs, double riseRowsPerSec, double rootIntervalMs,
                        double decayMult, double glowMult, double raiseBias) {
      this.canvas.decay(decayMult);

      // Root births on the idle timer, gated to the tempo grid this frame.
      this.sinceRootMs += deltaMs;
      final int cap = 6 + (int) Math.round((MAX_FIGURES - 8) * density.getValue());
      if ((this.sinceRootMs >= rootIntervalMs) && birthGateOpen && (liveCount() < cap)) {
        spawnRoot(this.rng.nextDouble() < raiseBias, 1.0);
        this.sinceRootMs = 0;
      }

      final double dripThreshold = dripChance.getValue();
      final double riseRows = riseRowsPerSec * deltaMs * 0.001;

      for (int i = 0; i < MAX_FIGURES; ++i) {
        final Figure f = this.pool[i];
        if (!f.active) {
          continue;
        }
        f.ageMs += deltaMs;
        f.y -= riseRows;

        // Birth-in envelope, then fade out as the figure nears the top.
        double env = LXUtils.constrain(f.ageMs / BIRTH_IN_MS, 0, 1);
        if (f.y < FADE_TOP_ROWS) {
          env *= LXUtils.constrain(f.y / FADE_TOP_ROWS, 0, 1);
        }
        f.glow = env;

        // Retire once fully risen off the top.
        if (f.y + f.height < 0) {
          f.active = false;
          continue;
        }

        // Drip a child once the figure has climbed ~DRIP_RISE_BODIES of its own
        // height since its last drip (lineage branching), tempo-gated too.
        final double climbed = f.lastDripY - f.y;
        if ((climbed >= DRIP_RISE_BODIES * f.height)
            && (f.gen < MAX_GEN)
            && (f.children < 2)
            && (f.glow > 0.4)
            && birthGateOpen
            && (this.rng.nextDouble() < dripThreshold)) {
          spawnChild(f);
          f.lastDripY = f.y;
        }

        drawFigure(f, glowMult);
      }
    }

    /** Draw one cave-painting figure with MAX blending (limbs and lineage
     *  union, never erase) at its glow, warmth-lifted by glowMult. */
    private void drawFigure(Figure f, double glowMult) {
      final double glow = LXUtils.constrain(f.glow * glowMult, 0, 1.5);
      if (glow <= 0.01) {
        return;
      }
      final int argb = LXColor.scaleBrightness(genColor(f.gen), (float) Math.min(1.0, glow));

      final double swayX = sway.getValue() * f.height * 0.35
        * Math.sin(f.swayPhase + f.ageMs * f.swayRate);
      final double cx = f.x + swayX;
      final double feetY = f.y;
      final double h = f.height;
      final double hipY = feetY - HIP_FRAC * h;
      final double shoulderY = feetY - SHOULDER_FRAC * h;
      final double headY = feetY - h;
      final int headR = Math.max(1, (int) Math.round(HEAD_RADIUS_FRAC * h));

      final int xc = (int) Math.round(cx);
      final int legX = (int) Math.round(LEG_SPREAD * h * f.legJitter);
      final int armX = (int) Math.round(ARM_SPREAD * h);
      final double armDy = LXUtils.lerp(ARM_DOWN_DY, ARM_UP_DY, f.armRaise) * h;
      final int armEndY = (int) Math.round(shoulderY + armDy);

      // Torso + neck
      this.canvas.lineMax(xc, (int) Math.round(hipY), xc, (int) Math.round(shoulderY), argb);
      this.canvas.lineMax(xc, (int) Math.round(shoulderY), xc, (int) Math.round(headY + headR), argb);
      // Head disc
      stampDisc(xc, (int) Math.round(headY), headR, argb);
      // Legs
      this.canvas.lineMax(xc, (int) Math.round(hipY), xc - legX, (int) Math.round(feetY), argb);
      this.canvas.lineMax(xc, (int) Math.round(hipY), xc + legX, (int) Math.round(feetY), argb);
      // Arms
      this.canvas.lineMax(xc, (int) Math.round(shoulderY), xc - armX, armEndY, argb);
      this.canvas.lineMax(xc, (int) Math.round(shoulderY), xc + armX, armEndY, argb);
    }

    /** Small filled disc (the head), max-blended, x wraps. */
    private void stampDisc(int px, int py, int r, int argb) {
      for (int dy = -r; dy <= r; ++dy) {
        final int dxMax = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
        for (int dx = -dxMax; dx <= dxMax; ++dx) {
          this.canvas.setMax(px + dx, py + dy, argb);
        }
      }
    }
  }
}
