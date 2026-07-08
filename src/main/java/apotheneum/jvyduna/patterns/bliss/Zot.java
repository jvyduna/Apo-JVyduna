package apotheneum.jvyduna.patterns.bliss;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.doved.lightning.LSystemAlgorithm;
import apotheneum.doved.lightning.LightningGenerator;
import apotheneum.doved.lightning.LightningSegment;
import apotheneum.doved.lightning.MidpointDisplacementAlgorithm;
import apotheneum.doved.lightning.PhysicallyBasedAlgorithm;
import apotheneum.doved.lightning.RRTAlgorithm;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.audio.GraphicMeter;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * After Dark "Zot!" homage: lightning bolts strike down the Apotheneum surfaces.
 *
 * A thin wrapper over the {@code apotheneum.doved.lightning} generator library
 * (midpoint displacement / L-system / RRT / physically-based). Zot owns strike
 * scheduling (manual trigger, storm burst, or an audio-floor + EMA transient
 * detector on a chosen FFT bin-pair), the strike envelope (stepped leader
 * draw-in over StrkTime, one-frame return stroke, glow decay over FadeTime),
 * per-strike surface routing, and palette color; the doved generators own bolt
 * geometry and segment rendering and are reused unmodified.
 *
 * Each strike is routed onto {@code ESurfs} of the 5 external surfaces (4 cube
 * exterior faces + the exterior cylinder) and {@code ISurfs} of the 5 internal
 * surfaces (4 cube interior faces + the interior cylinder), chosen from those
 * currently displaying the fewest strikes. Cylinder strikes render into an
 * unrolled half-ring (60 of the 120 columns) placed at a random angular offset,
 * wrapping across the seam. Bolts run full height to the ground; whatever lands
 * on the door rows is simply lost.
 *
 * See Zot.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Zot")
@LXComponent.Description("After Dark Zot! lightning: leader draw-in, return-stroke flash and glow, struck by hand, storm or FFT transient, routed across cube + cylinder surfaces")
public class Zot extends ApotheneumPattern {

  // ---- Generator algorithms (shared stateless instances) --------------------

  public enum Algo {
    MIDPOINT("Midpoint", new MidpointDisplacementAlgorithm()),
    L_SYSTEM("L-System", new LSystemAlgorithm()),
    RRT("RRT", new RRTAlgorithm()),
    PHYSICAL("Physical", new PhysicallyBasedAlgorithm());

    public final String label;
    public final LightningGenerator generator;

    private Algo(String label, LightningGenerator generator) {
      this.label = label;
      this.generator = generator;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  // ---- Canvas geometry ------------------------------------------------------

  /** Cube face canvas: 50 columns x 45 rows */
  private static final int FACE_W = Apotheneum.GRID_WIDTH;
  private static final int FACE_H = Apotheneum.GRID_HEIGHT;
  /** Cylinder ring: 120 columns x 43 rows */
  private static final int CYL_W = Apotheneum.RING_LENGTH;
  private static final int CYL_H = Apotheneum.CYLINDER_HEIGHT;
  /** A cylinder strike's canvas is the unrolled half-ring (1/4 of the columns
   *  each way from a random start angle) */
  private static final int CYL_CANVAS_W = CYL_W / 2;

  // ---- Timing / envelope constants ------------------------------------------

  /** Concurrent bolt slots; further strikes recycle the oldest active slot */
  private static final int MAX_BOLTS = 16;

  /** Leader-phase brightness ramp: dim channel forming before the return stroke */
  private static final double LEADER_BRIGHTNESS_LO = 0.25;
  private static final double LEADER_BRIGHTNESS_HI = 0.55;

  /** Afterglow starts here (return stroke is 1.0) and decays quadratically to 0 */
  private static final double AFTERGLOW_BRIGHTNESS = 0.7;

  /** Glow/bleed strength passed to the doved renderers (halo around the core) */
  private static final double BLEED = 1.0;

  /**
   * Physical LEDs are extremely bright: whole-surface return-stroke flashes are
   * at most 1 frame long and at most one per this many milliseconds.
   * CURATE: interval unverified at sculpture scale.
   */
  private static final double FACE_FLASH_MIN_INTERVAL_MS = 1200;

  /** Storm trigger: burst of 3..5 bolts spread over ~2 seconds */
  private static final int STORM_MIN_BOLTS = 3;
  private static final int STORM_MAX_BOLTS = 5;
  private static final double STORM_SPACING_MIN_MS = 300;
  private static final double STORM_SPACING_MAX_MS = 700;

  /** Bolts start somewhere across the top, avoiding the extreme corners */
  private static final double START_X_MIN = 0.15;
  private static final double START_X_MAX = 0.85;

  // ---- Audio-trigger constants ----------------------------------------------

  /** A transient fires when the monitored bin average exceeds EMA times this.
   *  CURATE: 1.5 first-guess. */
  private static final double EMA_TRIGGER_MULT = 1.5;

  /** Blackout after an audio strike = this fraction of the EMADur window,
   *  floored to MIN_AUDIO_BLACKOUT_MS, killing double-fires on one transient. */
  private static final double BLACKOUT_FRAC = 0.15;
  private static final double MIN_AUDIO_BLACKOUT_MS = 60;

  // Fixed doved generator settings (curated defaults from doved's Lightning.java)
  private static final int MIDPOINT_DEPTH = 6;
  private static final double MIDPOINT_START_SPREAD = 0.1;
  private static final double MIDPOINT_END_SPREAD = 0.8;
  private static final double MIDPOINT_BRANCH_DISTANCE = 0.6;
  private static final double MIDPOINT_BRANCH_ANGLE = 0.5;
  private static final int LS_ITERATIONS = 4;
  private static final double LS_SEGMENT_LENGTH = 8;
  private static final double LS_LENGTH_VARIATION = 0.3;
  private static final double LS_BRANCH_ANGLE_MIN_DEG = 25;
  private static final double LS_BRANCH_ANGLE_MAX_DEG = 70;
  private static final double RRT_STEP_SIZE = 12;
  private static final double RRT_GOAL_BIAS = 0.15;
  private static final int RRT_MAX_ITERATIONS = 150;
  private static final double RRT_GOAL_RADIUS = 20;
  private static final double RRT_ELECTRICAL_FIELD = 0.5;
  private static final double PHYS_ELECTRIC_POTENTIAL = 0.8;
  private static final double PHYS_STEP_LENGTH = 8;
  private static final int PHYS_MAX_STEPS = 100;
  private static final double PHYS_BRANCH_SCALE = 0.8;
  private static final double PHYS_CHARGE_DECAY = 0.02;
  private static final double PHYS_CONNECTION_DISTANCE = 10;

  // ---- Parameters -----------------------------------------------------------

  public final TriggerParameter strike =
    new TriggerParameter("Strike", this::launchStrike)
    .setDescription("Strike one bolt now");

  public final TriggerParameter storm =
    new TriggerParameter("Storm", this::storm)
    .setDescription("Burst of 3-5 bolts spread over ~2 seconds");

  public final TriggerParameter nextAlgo =
    new TriggerParameter("NextAlgo", this::nextAlgorithm)
    .setDescription("Cycle to the next lightning generator algorithm");

  public final EnumParameter<Algo> algorithm =
    new EnumParameter<Algo>("Algo", Algo.MIDPOINT)
    .setDescription("Bolt generation algorithm (doved lightning library)");

  public final DiscreteParameter eSurfs =
    new DiscreteParameter("ESurfs", 2, 0, 6)
    .setDescription("How many of the 5 external surfaces (4 cube faces + cylinder) each strike lights");

  public final DiscreteParameter iSurfs =
    new DiscreteParameter("ISurfs", 1, 0, 6)
    .setDescription("How many of the 5 internal surfaces (4 cube faces + cylinder) each strike lights");

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", 2, 1, 3)
    .setDescription("Core stroke thickness in raster pixels (branches render at half)");

  public final CompoundParameter branch =
    new CompoundParameter("Branch", 0.4)
    .setDescription("Branchiness: probability/angle of secondary channels forking off the main bolt");

  public final CompoundParameter jag =
    new CompoundParameter("Jag", 0.5)
    .setDescription("Jaggedness: perpendicular displacement / angle variation of the channel");

  public final CompoundParameter flash =
    new CompoundParameter("Flash", 0.15, 0, 0.5)
    .setDescription("Whole-surface return-stroke flash brightness; 1 frame max, rate-limited; 0 = off");

  public final EnumParameter<Tempo.Division> strkTime =
    new EnumParameter<Tempo.Division>("StrkTime", Tempo.Division.QUARTER)
    .setDescription("Tempo division a strike takes to draw top-to-bottom (starts immediately on trigger)");

  public final EnumParameter<Tempo.Division> fadeTime =
    new EnumParameter<Tempo.Division>("FadeTime", Tempo.Division.HALF)
    .setDescription("Tempo division of the fade (return-stroke flash + glow decay); total life = StrkTime + FadeTime");

  public final BooleanParameter white =
    new BooleanParameter("White", true)
    .setDescription("On: white bolts. Off: each strike takes a random palette color");

  public final CompoundParameter audioFloor =
    new CompoundParameter("Audio", 0)
    .setDescription("FFT floor for audio strikes: the monitored bins must exceed this. 0 = audio strikes off (manual only)");

  public final DiscreteParameter audFreq =
    new DiscreteParameter("AudFreq", 0, 0, 15)
    .setDescription("Which neighbouring FFT bin-pair to watch: 0 = lowest 2 of 16, 14 = highest 2");

  public final DiscreteParameter emaDur =
    new DiscreteParameter("EMADur", 3, 0, 17)
    .setDescription("EMA / refractory window in beats: a strike usually won't re-fire within this many beats (0 = no EMA gate)");

  // ---- Surfaces (10 total: external[0..4], internal[0..4]) ------------------

  private static final class Surface {
    final SurfaceCanvas canvas;
    final boolean cylinder;
    Apotheneum.Surface target; // set lazily once the model exists
    int activeCount;

    Surface(int w, int h, boolean cylinder) {
      this.canvas = new SurfaceCanvas(w, h);
      this.cylinder = cylinder;
    }
  }

  private final Surface[] external = new Surface[5];
  private final Surface[] internal = new Surface[5];
  private final Surface[] allSurfaces = new Surface[10]; // flat: 0-4 ext, 5-9 int
  private boolean surfacesReady = false;

  // ---- Bolt slots (preallocated) --------------------------------------------

  private enum Phase { LEADER, FLASH, GLOW }

  private static final class Bolt {
    // Full geometry (filled by the doved generators at strike time) and the
    // growing leader prefix (extended in place, no per-frame allocation).
    final ArrayList<LightningSegment> faceSegments = new ArrayList<>();
    final ArrayList<LightningSegment> faceVisible = new ArrayList<>();
    final ArrayList<LightningSegment> cylSegments = new ArrayList<>();
    final ArrayList<LightningSegment> cylVisible = new ArrayList<>();
    final int[] surfaceIds = new int[10];
    int surfaceCount;
    boolean hasFace, hasCyl;
    LightningGenerator generator;
    boolean white;
    int color;      // ARGB, used only when !white
    int cylOffset;  // starting column (0..119) for cylinder placement
    boolean active = false;
    boolean flashThisStrike = false;
    Phase phase = Phase.GLOW;
    double ageMs, leaderMs, fadeMs;
  }

  private final Bolt[] bolts = new Bolt[MAX_BOLTS];

  // ---- Scratch (preallocated) -----------------------------------------------

  private final BufferedImage faceScratch =
    new BufferedImage(FACE_W, FACE_H, BufferedImage.TYPE_INT_ARGB);
  private final Graphics2D faceG;
  private final int[] facePixels = new int[FACE_W * FACE_H];

  private final BufferedImage cylScratch =
    new BufferedImage(CYL_CANVAS_W, CYL_H, BufferedImage.TYPE_INT_ARGB);
  private final Graphics2D cylG;
  private final int[] cylPixels = new int[CYL_CANVAS_W * CYL_H];

  private final boolean[] pickScratch = new boolean[5];

  // ---- State ----------------------------------------------------------------

  private final Random random = new Random();
  private final GraphicMeter meter;

  private double ema = 0;
  private double audioBlackoutMs = 0;
  private double msSinceFaceFlash = 1e9;
  private int stormRemaining = 0;
  private double stormNextMs = 0;

  public Zot(LX lx) {
    super(lx);
    this.meter = lx.engine.audio.meter;
    this.faceG = configureGraphics(this.faceScratch);
    this.cylG = configureGraphics(this.cylScratch);

    for (int i = 0; i < MAX_BOLTS; ++i) {
      this.bolts[i] = new Bolt();
    }
    // Canvases exist now; targets are bound lazily in ensureSurfaces()
    this.external[0] = new Surface(FACE_W, FACE_H, false);
    this.external[1] = new Surface(FACE_W, FACE_H, false);
    this.external[2] = new Surface(FACE_W, FACE_H, false);
    this.external[3] = new Surface(FACE_W, FACE_H, false);
    this.external[4] = new Surface(CYL_W, CYL_H, true);
    this.internal[0] = new Surface(FACE_W, FACE_H, false);
    this.internal[1] = new Surface(FACE_W, FACE_H, false);
    this.internal[2] = new Surface(FACE_W, FACE_H, false);
    this.internal[3] = new Surface(FACE_W, FACE_H, false);
    this.internal[4] = new Surface(CYL_W, CYL_H, true);
    for (int i = 0; i < 5; ++i) {
      this.allSurfaces[i] = this.external[i];
      this.allSurfaces[5 + i] = this.internal[i];
    }

    addParameter("strike", this.strike);
    addParameter("storm", this.storm);
    addParameter("nextAlgo", this.nextAlgo);
    addParameter("algorithm", this.algorithm);
    addParameter("eSurfs", this.eSurfs);
    addParameter("iSurfs", this.iSurfs);
    addParameter("thickness", this.thickness);
    addParameter("branch", this.branch);
    addParameter("jag", this.jag);
    addParameter("flash", this.flash);
    addParameter("strkTime", this.strkTime);
    addParameter("fadeTime", this.fadeTime);
    addParameter("white", this.white);
    addParameter("audio", this.audioFloor);
    addParameter("audFreq", this.audFreq);
    addParameter("emaDur", this.emaDur);
  }

  private static Graphics2D configureGraphics(BufferedImage image) {
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    return g;
  }

  /** Bind surface targets once the Apotheneum model is loaded (run() guards
   *  render() behind Apotheneum.exists, so cube/cylinder are valid here). */
  private void ensureSurfaces() {
    if (this.surfacesReady) {
      return;
    }
    if (Apotheneum.cube != null) {
      this.external[0].target = Apotheneum.cube.exterior.front;
      this.external[1].target = Apotheneum.cube.exterior.right;
      this.external[2].target = Apotheneum.cube.exterior.back;
      this.external[3].target = Apotheneum.cube.exterior.left;
      if (Apotheneum.cube.interior != null) {
        this.internal[0].target = Apotheneum.cube.interior.front;
        this.internal[1].target = Apotheneum.cube.interior.right;
        this.internal[2].target = Apotheneum.cube.interior.back;
        this.internal[3].target = Apotheneum.cube.interior.left;
      }
    }
    if (Apotheneum.cylinder != null) {
      this.external[4].target = Apotheneum.cylinder.exterior;
      this.internal[4].target = Apotheneum.cylinder.interior;
    }
    this.surfacesReady = true;
  }

  // ---- Strike sources --------------------------------------------------------

  private void storm() {
    this.stormRemaining = STORM_MIN_BOLTS
      + this.random.nextInt(STORM_MAX_BOLTS - STORM_MIN_BOLTS + 1);
    this.stormNextMs = 0; // first bolt on the next frame
    LX.log("Zot: storm of " + this.stormRemaining + " bolts");
  }

  private void nextAlgorithm() {
    this.algorithm.increment();
    LX.log("Zot: algorithm -> " + this.algorithm.getEnum());
  }

  /** Duration of one cycle of a tempo division at the current BPM, in ms */
  private double divisionMs(Tempo.Division division) {
    // period is ms per quarter-note beat; multiplier is divisions per beat
    return this.lx.engine.tempo.period.getValue() / division.multiplier;
  }

  /**
   * Strike one bolt: pick a free/recyclable slot, route it onto ESurfs external
   * + ISurfs internal surfaces (fewest-displayed first, random tiebreak),
   * generate the needed geometry, and start its LEADER phase.
   */
  private void launchStrike() {
    if (!this.surfacesReady) {
      return; // model not loaded yet
    }
    final int es = this.eSurfs.getValuei();
    final int is = this.iSurfs.getValuei();
    if (es + is == 0) {
      return; // nowhere to show it
    }

    final Bolt bolt = allocateBolt();
    bolt.active = false; // clean slot; only activated on success below
    bolt.surfaceCount = 0;
    pickSurfaces(es, 0, bolt);
    pickSurfaces(is, 5, bolt);
    if (bolt.surfaceCount == 0) {
      return; // no available surfaces (e.g. missing interior model)
    }

    bolt.hasFace = false;
    bolt.hasCyl = false;
    for (int j = 0; j < bolt.surfaceCount; ++j) {
      if (this.allSurfaces[bolt.surfaceIds[j]].cylinder) {
        bolt.hasCyl = true;
      } else {
        bolt.hasFace = true;
      }
    }

    // Per-STRIKE allocation (Parameters + LightningSegments inside the doved
    // generators) is event-rate, not per-frame: acceptable under the zero-alloc
    // render rule.
    final Algo algo = this.algorithm.getEnum();
    if (bolt.hasFace) {
      generate(bolt.faceSegments, algo, FACE_W, FACE_H);
    } else {
      bolt.faceSegments.clear();
    }
    if (bolt.hasCyl) {
      generate(bolt.cylSegments, algo, CYL_CANVAS_W, CYL_H);
    } else {
      bolt.cylSegments.clear();
    }
    final boolean any =
      (bolt.hasFace && !bolt.faceSegments.isEmpty())
      || (bolt.hasCyl && !bolt.cylSegments.isEmpty());
    if (!any) {
      return; // empty generation; leave the slot inactive, no counts touched
    }

    // Commit: claim the chosen surfaces
    for (int j = 0; j < bolt.surfaceCount; ++j) {
      this.allSurfaces[bolt.surfaceIds[j]].activeCount++;
    }
    bolt.faceVisible.clear();
    bolt.cylVisible.clear();
    bolt.faceVisible.ensureCapacity(bolt.faceSegments.size());
    bolt.cylVisible.ensureCapacity(bolt.cylSegments.size());
    bolt.generator = algo.generator;
    bolt.white = this.white.isOn();
    bolt.color = bolt.white ? 0xffffffff : pickColor();
    bolt.cylOffset = this.random.nextInt(CYL_W);
    bolt.leaderMs = divisionMs(this.strkTime.getEnum());
    bolt.fadeMs = divisionMs(this.fadeTime.getEnum());
    bolt.ageMs = 0;
    bolt.phase = Phase.LEADER;
    bolt.flashThisStrike = false;
    bolt.active = true;
  }

  /** Find a free slot, or recycle the oldest active bolt (releasing its surface
   *  counts first). */
  private Bolt allocateBolt() {
    for (Bolt b : this.bolts) {
      if (!b.active) {
        return b;
      }
    }
    Bolt oldest = this.bolts[0];
    for (Bolt b : this.bolts) {
      if (b.ageMs > oldest.ageMs) {
        oldest = b;
      }
    }
    releaseSurfaces(oldest);
    return oldest;
  }

  /** Choose {@code count} surfaces from a 5-pool (poolStart 0=external, 5=internal)
   *  with the lowest activeCount, random tiebreak; append their flat ids to the
   *  bolt. Skips surfaces with no bound target. */
  private void pickSurfaces(int count, int poolStart, Bolt bolt) {
    java.util.Arrays.fill(this.pickScratch, false);
    for (int k = 0; k < count; ++k) {
      int best = -1;
      int bestCount = Integer.MAX_VALUE;
      int ties = 0;
      for (int i = 0; i < 5; ++i) {
        if (this.pickScratch[i]) {
          continue;
        }
        final Surface s = this.allSurfaces[poolStart + i];
        if (s.target == null) {
          continue;
        }
        if (s.activeCount < bestCount) {
          bestCount = s.activeCount;
          best = i;
          ties = 1;
        } else if (s.activeCount == bestCount) {
          // reservoir sampling: uniform random among the current-minimum ties
          ++ties;
          if (this.random.nextInt(ties) == 0) {
            best = i;
          }
        }
      }
      if (best < 0) {
        break; // pool exhausted
      }
      this.pickScratch[best] = true;
      bolt.surfaceIds[bolt.surfaceCount++] = poolStart + best;
    }
  }

  private void releaseSurfaces(Bolt bolt) {
    for (int j = 0; j < bolt.surfaceCount; ++j) {
      final Surface s = this.allSurfaces[bolt.surfaceIds[j]];
      if (s.activeCount > 0) {
        s.activeCount--;
      }
    }
  }

  private int pickColor() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    if (!swatch.isEmpty()) {
      return swatch.get(this.random.nextInt(swatch.size())).getColor();
    }
    return LXColor.hsb(this.random.nextFloat() * 360, 100, 100);
  }

  private void generate(ArrayList<LightningSegment> out, Algo algo, int w, int h) {
    out.clear();
    algo.generator.generateLightning(out, buildParams(algo, w, h));
  }

  /** Build a per-strike doved Parameters object for the selected algorithm. */
  private Object buildParams(Algo algo, int w, int h) {
    final double startX = START_X_MIN
      + this.random.nextDouble() * (START_X_MAX - START_X_MIN);
    final double branchValue = this.branch.getValue();
    final double jagValue = this.jag.getValue();
    switch (algo) {
      case L_SYSTEM:
        return new LSystemAlgorithm.Parameters(
          LS_ITERATIONS, LS_SEGMENT_LENGTH, jagValue, LS_LENGTH_VARIATION,
          Ranges.lin(branchValue, LS_BRANCH_ANGLE_MIN_DEG, LS_BRANCH_ANGLE_MAX_DEG),
          startX, w, h);
      case RRT:
        return new RRTAlgorithm.Parameters(
          RRT_STEP_SIZE, RRT_GOAL_BIAS, RRT_MAX_ITERATIONS, branchValue, jagValue,
          RRT_GOAL_RADIUS, RRT_ELECTRICAL_FIELD, startX, w, h);
      case PHYSICAL:
        return new PhysicallyBasedAlgorithm.Parameters(
          PHYS_ELECTRIC_POTENTIAL, PHYS_STEP_LENGTH, PHYS_MAX_STEPS,
          branchValue * PHYS_BRANCH_SCALE, jagValue * Math.PI, // radians, as in doved
          PHYS_CHARGE_DECAY, PHYS_CONNECTION_DISTANCE, startX, w, h);
      default: // MIDPOINT
        return new MidpointDisplacementAlgorithm.Parameters(
          jagValue, MIDPOINT_DEPTH, startX, MIDPOINT_START_SPREAD, MIDPOINT_END_SPREAD,
          branchValue, MIDPOINT_BRANCH_DISTANCE, MIDPOINT_BRANCH_ANGLE, w, h);
    }
  }

  // ---- Render ---------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    ensureSurfaces();

    // Strike source 1: audio transient on the chosen bins
    updateAudio(deltaMs);

    // Strike source 2: pending storm-burst bolts
    if (this.stormRemaining > 0) {
      this.stormNextMs -= deltaMs;
      if (this.stormNextMs <= 0) {
        --this.stormRemaining;
        this.stormNextMs = STORM_SPACING_MIN_MS
          + this.random.nextDouble() * (STORM_SPACING_MAX_MS - STORM_SPACING_MIN_MS);
        launchStrike();
      }
    }

    // Advance lifecycles; detect return strokes (rate-limited whole-surface flash)
    this.msSinceFaceFlash += deltaMs;
    final boolean flashAllowed = (this.flash.getValue() > 0)
      && (this.msSinceFaceFlash >= FACE_FLASH_MIN_INTERVAL_MS);
    boolean flashConsumed = false;
    for (Bolt bolt : this.bolts) {
      if (!bolt.active) {
        continue;
      }
      bolt.ageMs += deltaMs;
      if (bolt.phase == Phase.LEADER) {
        if (bolt.ageMs >= bolt.leaderMs) {
          bolt.phase = Phase.FLASH; // one rendered frame at full brightness
          if (flashAllowed && !flashConsumed) {
            bolt.flashThisStrike = true;
            flashConsumed = true;
            this.msSinceFaceFlash = 0;
          }
        }
      } else if (bolt.phase == Phase.FLASH) {
        bolt.phase = Phase.GLOW; // the flash was rendered exactly once
      }
      if ((bolt.phase == Phase.GLOW) && (bolt.ageMs >= bolt.leaderMs + bolt.fadeMs)) {
        bolt.active = false;
        releaseSurfaces(bolt);
      }
    }

    // Clear all surface canvases, composite bolts, write to LEDs
    for (Surface s : this.allSurfaces) {
      if (s.target != null) {
        s.canvas.fill(0xff000000);
      }
    }
    final double thick = this.thickness.getValue();
    for (Bolt bolt : this.bolts) {
      if (bolt.active) {
        renderBolt(bolt, thick);
      }
    }
    for (Surface s : this.allSurfaces) {
      if (s.target != null) {
        s.canvas.copyTo(s.target, this.colors);
      }
    }
  }

  /** Render one bolt: draw via the doved generator into scratch, then tint +
   *  composite onto each assigned surface (cylinder placed at a wrapped offset). */
  private void renderBolt(Bolt bolt, double thick) {
    // Phase-dependent draw list + brightness
    final List<LightningSegment> faceDraw;
    final List<LightningSegment> cylDraw;
    final double fade;
    switch (bolt.phase) {
      case LEADER: {
        final double progress = Math.min(bolt.ageMs / bolt.leaderMs, 1);
        extendPrefix(bolt.faceVisible, bolt.faceSegments, progress);
        extendPrefix(bolt.cylVisible, bolt.cylSegments, progress);
        faceDraw = bolt.faceVisible;
        cylDraw = bolt.cylVisible;
        fade = LEADER_BRIGHTNESS_LO + (LEADER_BRIGHTNESS_HI - LEADER_BRIGHTNESS_LO) * progress;
        break;
      }
      case FLASH: {
        faceDraw = bolt.faceSegments;
        cylDraw = bolt.cylSegments;
        fade = 1.0;
        break;
      }
      default: { // GLOW
        faceDraw = bolt.faceSegments;
        cylDraw = bolt.cylSegments;
        final double u = Math.min((bolt.ageMs - bolt.leaderMs) / bolt.fadeMs, 1);
        fade = AFTERGLOW_BRIGHTNESS * (1 - u) * (1 - u);
        break;
      }
    }
    final float flashBright = (float) this.flash.getValue();

    if (bolt.hasFace && !bolt.faceSegments.isEmpty()) {
      clearScratch(this.faceG, FACE_W, FACE_H);
      bolt.generator.render(this.faceG, faceDraw, fade, 1.0, thick, BLEED);
      this.faceScratch.getRGB(0, 0, FACE_W, FACE_H, this.facePixels, 0, FACE_W);
      for (int j = 0; j < bolt.surfaceCount; ++j) {
        final Surface s = this.allSurfaces[bolt.surfaceIds[j]];
        if (s.cylinder) {
          continue;
        }
        if (bolt.flashThisStrike) {
          flashFill(s.canvas, 0, FACE_W, FACE_H, bolt, flashBright);
        }
        compositeBolt(s.canvas, this.facePixels, FACE_W, FACE_H, 0, bolt);
      }
    }

    if (bolt.hasCyl && !bolt.cylSegments.isEmpty()) {
      clearScratch(this.cylG, CYL_CANVAS_W, CYL_H);
      bolt.generator.render(this.cylG, cylDraw, fade, 1.0, thick, BLEED);
      this.cylScratch.getRGB(0, 0, CYL_CANVAS_W, CYL_H, this.cylPixels, 0, CYL_CANVAS_W);
      for (int j = 0; j < bolt.surfaceCount; ++j) {
        final Surface s = this.allSurfaces[bolt.surfaceIds[j]];
        if (!s.cylinder) {
          continue;
        }
        if (bolt.flashThisStrike) {
          flashFill(s.canvas, bolt.cylOffset, CYL_CANVAS_W, CYL_H, bolt, flashBright);
        }
        compositeBolt(s.canvas, this.cylPixels, CYL_CANVAS_W, CYL_H, bolt.cylOffset, bolt);
      }
    }

    bolt.flashThisStrike = false; // consumed (FLASH phase is one frame)
  }

  private static void extendPrefix(ArrayList<LightningSegment> visible,
      ArrayList<LightningSegment> segments, double progress) {
    final int target = (int) Math.ceil(progress * segments.size());
    while (visible.size() < target) {
      visible.add(segments.get(visible.size()));
    }
  }

  private void clearScratch(Graphics2D g, int w, int h) {
    g.setComposite(AlphaComposite.Clear);
    g.fillRect(0, 0, w, h);
    g.setComposite(AlphaComposite.SrcOver);
  }

  /** Composite a rendered (blue-white, alpha-keyed) scratch buffer onto a canvas
   *  with per-channel max blending; tinted to the strike color when !white. */
  private void compositeBolt(SurfaceCanvas canvas, int[] pixels, int w, int h,
      int xOffset, Bolt bolt) {
    for (int y = 0; y < h; ++y) {
      final int row = y * w;
      for (int x = 0; x < w; ++x) {
        final int argb = pixels[row + x];
        final int a = (argb >>> 24) & 0xff;
        if (a == 0) {
          continue;
        }
        canvas.setMax(xOffset + x, y, tint(argb, a, bolt.white, bolt.color));
      }
    }
  }

  /** Flatten a rendered pixel over black (white mode) or scale the strike color
   *  by the pixel's intensity (color mode). Returns opaque ARGB. */
  private static int tint(int argb, int a, boolean white, int color) {
    final int r = (argb >> 16) & 0xff;
    final int g = (argb >> 8) & 0xff;
    final int b = argb & 0xff;
    if (white) {
      return 0xff000000 | ((r * a / 255) << 16) | ((g * a / 255) << 8) | (b * a / 255);
    }
    final int maxc = Math.max(r, Math.max(g, b));
    final double intensity = (a / 255.0) * (maxc / 255.0);
    final int cr = (int) (((color >> 16) & 0xff) * intensity);
    final int cg = (int) (((color >> 8) & 0xff) * intensity);
    final int cb = (int) ((color & 0xff) * intensity);
    return 0xff000000 | (cr << 16) | (cg << 8) | cb;
  }

  private void flashFill(SurfaceCanvas canvas, int xOffset, int w, int h,
      Bolt bolt, float bright) {
    final int c = flashColor(bolt.white, bolt.color, bright);
    for (int y = 0; y < h; ++y) {
      for (int x = 0; x < w; ++x) {
        canvas.setMax(xOffset + x, y, c);
      }
    }
  }

  private static int flashColor(boolean white, int color, float bright) {
    if (white) {
      final int r = clamp8((int) (0.85f * 255 * bright));
      final int g = clamp8((int) (0.9f * 255 * bright));
      final int b = clamp8((int) (255 * bright));
      return 0xff000000 | (r << 16) | (g << 8) | b;
    }
    final int cr = clamp8((int) (((color >> 16) & 0xff) * bright));
    final int cg = clamp8((int) (((color >> 8) & 0xff) * bright));
    final int cb = clamp8((int) ((color & 0xff) * bright));
    return 0xff000000 | (cr << 16) | (cg << 8) | cb;
  }

  private static int clamp8(int v) {
    return (v < 0) ? 0 : (v > 255) ? 255 : v;
  }

  // ---- Audio trigger ---------------------------------------------------------

  /**
   * Audio strike: watch a neighbouring FFT bin-pair (AudFreq). A strike fires
   * when the pair average exceeds the Audio floor AND (EMADur>0) EMA*1.5, with a
   * short blackout to suppress double-fires. On a fire the EMA snaps up to the
   * peak so it self-suppresses over ~EMADur beats until a larger transient
   * arrives. Audio floor = 0 disables audio strikes entirely.
   */
  private void updateAudio(double deltaMs) {
    if (this.audioBlackoutMs > 0) {
      this.audioBlackoutMs -= deltaMs;
    }
    final double floor = this.audioFloor.getValue();
    if (floor <= 0) {
      return; // audio strikes off
    }

    final int f = this.audFreq.getValuei();
    final double avg = 0.5 * (this.meter.getBand(f) + this.meter.getBand(f + 1));

    final int dur = this.emaDur.getValuei();
    final double beatMs = this.lx.engine.tempo.period.getValue();
    if (dur > 0) {
      final double tauMs = dur * beatMs;
      this.ema += (avg - this.ema) * (1 - Math.exp(-deltaMs / tauMs));
    } else {
      this.ema = avg; // no EMA gate
    }

    final boolean emaGate = (dur == 0) || (avg > this.ema * EMA_TRIGGER_MULT);
    if ((avg > floor) && emaGate && (this.audioBlackoutMs <= 0)) {
      launchStrike();
      if (dur > 0) {
        this.ema = Math.max(this.ema, avg); // snap up: self-suppress for ~dur beats
      }
      this.audioBlackoutMs = Math.max(MIN_AUDIO_BLACKOUT_MS, BLACKOUT_FRAC * dur * beatMs);
    }
  }

  @Override
  public void dispose() {
    super.dispose();
    this.faceG.dispose();
    this.cylG.dispose();
  }
}
