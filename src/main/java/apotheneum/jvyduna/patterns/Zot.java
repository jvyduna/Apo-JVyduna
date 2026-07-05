package apotheneum.jvyduna.patterns;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import apotheneum.ApotheneumRasterPattern;
import apotheneum.doved.lightning.LSystemAlgorithm;
import apotheneum.doved.lightning.LightningGenerator;
import apotheneum.doved.lightning.LightningSegment;
import apotheneum.doved.lightning.MidpointDisplacementAlgorithm;
import apotheneum.doved.lightning.PhysicallyBasedAlgorithm;
import apotheneum.doved.lightning.RRTAlgorithm;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * After Dark "Zot!" homage: lightning bolts strike down the cube faces.
 *
 * A thin wrapper over the {@code apotheneum.doved.lightning} generator library
 * (midpoint displacement / L-system / RRT / physically-based). Zot owns the
 * strike scheduling (manual trigger, bass transients, silence-safe ambient
 * Poisson storms) and the strike envelope (stepped leader draw-in, one-frame
 * return stroke, long afterglow); the doved generators own bolt geometry and
 * segment rendering, and are reused unmodified.
 *
 * See Zot.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Zot")
@LXComponent.Description("After Dark Zot! lightning: leader draw-in, return-stroke flash and long afterglow, struck by hand, bass or ambient storm")
public class Zot extends ApotheneumRasterPattern {

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

  // ---- Timing / envelope constants (physical intent noted) ------------------

  /** Concurrent bolt slots; further strikes recycle the oldest expired-life slot */
  private static final int MAX_BOLTS = 3;

  /** Stepped leader draws top-to-bottom over this range (randomized per strike) */
  private static final double LEADER_MIN_MS = 300;
  private static final double LEADER_MAX_MS = 600;

  /**
   * Simulation-principles floor: every bolt's total visual life
   * (leader + flash + afterglow) is at least this long, so the branching form
   * registers at 40-foot sculpture scale. Also gates slot recycling: a strike
   * is dropped rather than cutting short a bolt younger than this.
   */
  private static final double MIN_VISUAL_LIFE_MS = 1500;

  /** Leader-phase brightness ramp: dim channel forming before the return stroke */
  private static final double LEADER_BRIGHTNESS_LO = 0.25;
  private static final double LEADER_BRIGHTNESS_HI = 0.55;

  /** Afterglow starts here (return stroke is 1.0) and decays quadratically to 0 */
  private static final double AFTERGLOW_BRIGHTNESS = 0.7;

  /** Energy scales the Glow knob: long lazy afterglow when ambient, snappier at peak */
  private static final double GLOW_SCALE_AMBIENT = 1.4;
  private static final double GLOW_SCALE_PEAK = 0.8;

  /**
   * Physical LEDs are extremely bright: whole-face return-stroke flashes are
   * at most 1 frame long and at most one per this many milliseconds.
   */
  private static final double FACE_FLASH_MIN_INTERVAL_MS = 8000;

  /** Ambient Poisson mean strike interval: sparse rolling storm at e=0 ... */
  private static final double AMBIENT_MEAN_MS_AMBIENT = 30000;
  /** ... down to one beat at the series' 160 BPM convention at e=1 */
  private static final double AMBIENT_MEAN_MS_PEAK = 375;

  /** Minimum interval between audio-triggered strikes, energy-scaled */
  private static final double AUDIO_GATE_MS_AMBIENT = 4000;
  private static final double AUDIO_GATE_MS_PEAK = 375;

  /** Storm trigger: burst of 3..5 bolts spread over ~2 seconds */
  private static final int STORM_MIN_BOLTS = 3;
  private static final int STORM_MAX_BOLTS = 5;
  private static final double STORM_SPACING_MIN_MS = 300;
  private static final double STORM_SPACING_MAX_MS = 700;

  /** Bolts start somewhere across the top, avoiding the extreme corners */
  private static final double START_X_MIN = 0.15;
  private static final double START_X_MAX = 0.85;

  /** Glow/bleed strength passed to the doved renderers (halo around the core) */
  private static final double BLEED = 1.0;

  // Fixed doved generator settings not worth a knob (curated defaults from
  // doved's Lightning.java, biased toward tall top-to-bottom strikes)
  private static final int MIDPOINT_DEPTH = 6;
  private static final double MIDPOINT_START_SPREAD = 0.9;
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

  // ---- Parameters (UI order: triggers, energy, pattern params, meta last) ---

  private final TriggerBag bag = new TriggerBag("Zot");

  public final TriggerParameter strike = bag.register(
    new TriggerParameter("Strike", this::launchBolt)
    .setDescription("Strike one bolt now"));

  public final TriggerParameter storm = bag.register(
    new TriggerParameter("Storm", this::storm)
    .setDescription("Burst of 3-5 bolts spread over ~2 seconds"));

  public final TriggerParameter nextAlgo = bag.register(
    new TriggerParameter("NextAlgo", this::nextAlgorithm)
    .setDescription("Cycle to the next lightning generator algorithm"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-40% sparse long-afterglow ambient storms, 60-100% beat-rate strikes (160 BPM)");

  public final EnumParameter<Algo> algorithm =
    new EnumParameter<Algo>("Algo", Algo.MIDPOINT)
    .setDescription("Bolt generation algorithm (doved lightning library)");

  public final CompoundParameter threshold =
    new CompoundParameter("Thresh", 1.5, 1, 4)
    .setDescription("Bass ratio a transient must exceed to trigger a strike (higher = only the biggest hits)");

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", 2, 1, 3)
    .setDescription("Core stroke thickness in raster pixels (branches render at half)");

  public final CompoundParameter branch =
    new CompoundParameter("Branch", 0.4)
    .setDescription("Branchiness: probability/angle of secondary channels forking off the main bolt");

  public final CompoundParameter jag =
    new CompoundParameter("Jag", 0.5)
    .setDescription("Jaggedness: perpendicular displacement / angle variation of the channel");

  public final CompoundParameter glow =
    new CompoundParameter("Glow", 2.5, 1, 6)
    .setDescription("Afterglow fade time in seconds (energy-scaled; total bolt life is floored at 1.5s)");

  public final CompoundParameter ambient =
    new CompoundParameter("Ambient", 1, 0, 4)
    .setDescription("Ambient strike rate multiplier on the energy-driven Poisson storm (0 = manual/audio only)");

  public final CompoundParameter flash =
    new CompoundParameter("Flash", 0.15, 0, 0.5)
    .setDescription("Whole-face return-stroke flash brightness; 1 frame max, rate-limited to one per 8s; 0 = off");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- Bolt slots (preallocated) ---------------------------------------------

  private enum Phase { LEADER, FLASH, GLOW }

  private static final class Bolt {
    // Segment lists are filled by the doved generators at strike time
    // (event-rate allocation, acceptable); `visible` is the growing prefix
    // rendered during the leader phase, extended in place with no allocation.
    final ArrayList<LightningSegment> segments = new ArrayList<>();
    final ArrayList<LightningSegment> visible = new ArrayList<>();
    LightningGenerator generator;
    boolean active = false;
    Phase phase = Phase.GLOW;
    double ageMs, leaderMs, glowMs;
  }

  private final Bolt[] bolts = new Bolt[MAX_BOLTS];

  private final Random random = new Random();
  private final AudioReactive audio;

  private double msSinceAudioStrike = 1e9;
  private double msSinceFaceFlash = 1e9;
  private int stormRemaining = 0;
  private double stormNextMs = 0;

  public Zot(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx);
    for (int i = 0; i < MAX_BOLTS; ++i) {
      this.bolts[i] = new Bolt();
    }

    addParameter("strike", this.strike);
    addParameter("storm", this.storm);
    addParameter("nextAlgo", this.nextAlgo);
    addParameter("energy", this.energy);
    addParameter("algorithm", this.algorithm);
    addParameter("threshold", this.threshold);
    addParameter("thickness", this.thickness);
    addParameter("branch", this.branch);
    addParameter("jag", this.jag);
    addParameter("glow", this.glow);
    addParameter("ambient", this.ambient);
    addParameter("flash", this.flash);
    addParameter("meta", this.meta);

    // Jump candidates — mirrored 1:1 in the Zot.md "Jump candidates" table
    bag.jumpable(this.algorithm);
    bag.jumpable(this.thickness, 1, 3);
    bag.jumpable(this.branch, 0.1, 0.8);
    bag.jumpable(this.jag, 0.15, 0.9);
    bag.jumpable(this.glow, 1.5, 5);
    bag.jumpable(this.ambient, 0.25, 2);
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

  /**
   * Strike one bolt into a free slot. If all slots are busy, the oldest bolt is
   * recycled — but only once it has had its minimum visual life; otherwise the
   * strike is dropped (never cut a bolt short of the 1.5s floor).
   */
  private void launchBolt() {
    Bolt bolt = null;
    for (Bolt b : this.bolts) {
      if (!b.active) {
        bolt = b;
        break;
      }
    }
    if (bolt == null) {
      Bolt oldest = this.bolts[0];
      for (Bolt b : this.bolts) {
        if (b.ageMs > oldest.ageMs) {
          oldest = b;
        }
      }
      if (oldest.ageMs < MIN_VISUAL_LIFE_MS) {
        return; // drop the strike rather than truncate a young bolt
      }
      bolt = oldest;
    }

    // Per-STRIKE allocation below (Parameters object + LightningSegments inside
    // the doved generators) is event-rate, not per-frame: acceptable under the
    // zero-alloc render rule. See Zot.md.
    final Algo algo = this.algorithm.getEnum();
    bolt.segments.clear();
    algo.generator.generateLightning(bolt.segments, buildParams(algo));
    if (bolt.segments.isEmpty()) {
      return;
    }
    bolt.visible.clear();
    bolt.visible.ensureCapacity(bolt.segments.size());
    bolt.generator = algo.generator;
    bolt.leaderMs = LEADER_MIN_MS
      + this.random.nextDouble() * (LEADER_MAX_MS - LEADER_MIN_MS);
    final double glowMs = this.glow.getValue() * 1000
      * Ranges.lin(this.energy.getValue(), GLOW_SCALE_AMBIENT, GLOW_SCALE_PEAK);
    // Enforce the minimum total visual life (simulation principles)
    bolt.glowMs = Math.max(glowMs, MIN_VISUAL_LIFE_MS - bolt.leaderMs);
    bolt.ageMs = 0;
    bolt.phase = Phase.LEADER;
    bolt.active = true;
  }

  /** Build a per-strike doved Parameters object for the selected algorithm. */
  private Object buildParams(Algo algo) {
    final double startX = START_X_MIN
      + this.random.nextDouble() * (START_X_MAX - START_X_MIN);
    final double branchValue = this.branch.getValue();
    final double jagValue = this.jag.getValue();
    switch (algo) {
      case L_SYSTEM:
        return new LSystemAlgorithm.Parameters(
          LS_ITERATIONS,
          LS_SEGMENT_LENGTH,
          jagValue,
          LS_LENGTH_VARIATION,
          Ranges.lin(branchValue, LS_BRANCH_ANGLE_MIN_DEG, LS_BRANCH_ANGLE_MAX_DEG),
          startX,
          RASTER_WIDTH, RASTER_HEIGHT);
      case RRT:
        return new RRTAlgorithm.Parameters(
          RRT_STEP_SIZE,
          RRT_GOAL_BIAS,
          RRT_MAX_ITERATIONS,
          branchValue,
          jagValue,
          RRT_GOAL_RADIUS,
          RRT_ELECTRICAL_FIELD,
          startX,
          RASTER_WIDTH, RASTER_HEIGHT);
      case PHYSICAL:
        return new PhysicallyBasedAlgorithm.Parameters(
          PHYS_ELECTRIC_POTENTIAL,
          PHYS_STEP_LENGTH,
          PHYS_MAX_STEPS,
          branchValue * PHYS_BRANCH_SCALE,
          jagValue * Math.PI, // radians, as in doved's Lightning.java
          PHYS_CHARGE_DECAY,
          PHYS_CONNECTION_DISTANCE,
          startX,
          RASTER_WIDTH, RASTER_HEIGHT);
      default: // MIDPOINT
        return new MidpointDisplacementAlgorithm.Parameters(
          jagValue,
          MIDPOINT_DEPTH,
          startX,
          MIDPOINT_START_SPREAD,
          MIDPOINT_END_SPREAD,
          branchValue,
          MIDPOINT_BRANCH_DISTANCE,
          MIDPOINT_BRANCH_ANGLE,
          RASTER_WIDTH, RASTER_HEIGHT);
    }
  }

  // ---- Render ------------------------------------------------------------------

  @Override
  protected void render(double deltaMs, Graphics2D graphics) {
    this.audio.tick(deltaMs);
    final double e = this.energy.getValue();

    // Strike source 1: audio bass transients over the ratio threshold,
    // gated by an energy-scaled minimum interval. Silence-safe: with no
    // audio, bassHit() never fires and this path is simply inert.
    this.msSinceAudioStrike += deltaMs;
    final double audioGateMs = Ranges.exp(e, AUDIO_GATE_MS_AMBIENT, AUDIO_GATE_MS_PEAK);
    if (this.audio.bassHit()
        && (this.audio.bassRatio >= this.threshold.getValue())
        && (this.msSinceAudioStrike >= audioGateMs)) {
      this.msSinceAudioStrike = 0;
      launchBolt();
    }

    // Strike source 2: ambient Poisson storm — mean interval from energy,
    // scaled by the Ambient knob. Keeps storms rolling with no audio at all.
    final double ambientRate = this.ambient.getValue();
    if (ambientRate > 0) {
      final double meanMs =
        Ranges.exp(e, AMBIENT_MEAN_MS_AMBIENT, AMBIENT_MEAN_MS_PEAK) / ambientRate;
      if (this.random.nextDouble() < deltaMs / meanMs) {
        launchBolt();
      }
    }

    // Strike source 3: pending storm-burst bolts
    if (this.stormRemaining > 0) {
      this.stormNextMs -= deltaMs;
      if (this.stormNextMs <= 0) {
        --this.stormRemaining;
        this.stormNextMs = STORM_SPACING_MIN_MS
          + this.random.nextDouble() * (STORM_SPACING_MAX_MS - STORM_SPACING_MIN_MS);
        launchBolt();
      }
    }

    // Update bolt lifecycles; detect return strokes (no drawing yet)
    this.msSinceFaceFlash += deltaMs;
    boolean faceFlash = false;
    for (Bolt bolt : this.bolts) {
      if (!bolt.active) {
        continue;
      }
      bolt.ageMs += deltaMs;
      if (bolt.phase == Phase.LEADER) {
        if (bolt.ageMs >= bolt.leaderMs) {
          // Return stroke: exactly one rendered frame at full brightness
          bolt.phase = Phase.FLASH;
          if ((this.flash.getValue() > 0)
              && (this.msSinceFaceFlash >= FACE_FLASH_MIN_INTERVAL_MS)) {
            this.msSinceFaceFlash = 0;
            faceFlash = true;
          }
        }
      } else if (bolt.phase == Phase.FLASH) {
        bolt.phase = Phase.GLOW; // the flash was rendered exactly once
      }
      if ((bolt.phase == Phase.GLOW)
          && (bolt.ageMs >= bolt.leaderMs + bolt.glowMs)) {
        bolt.active = false;
      }
    }

    // Draw
    clear();
    if (faceFlash) {
      // One-frame dim whole-face flash under the bolts (event-rate allocation)
      graphics.setColor(new Color(0.85f, 0.9f, 1f, (float) this.flash.getValue()));
      graphics.fillRect(0, 0, RASTER_WIDTH, RASTER_HEIGHT);
    }
    final double thick = this.thickness.getValue();
    for (Bolt bolt : this.bolts) {
      if (!bolt.active) {
        continue;
      }
      final List<LightningSegment> draw;
      final double fade;
      switch (bolt.phase) {
        case LEADER: {
          // Progressive top-to-bottom draw-in: render a growing prefix of the
          // segment list (generators emit segments in growth order)
          final double progress = Math.min(bolt.ageMs / bolt.leaderMs, 1);
          final int target = (int) Math.ceil(progress * bolt.segments.size());
          while (bolt.visible.size() < target) {
            bolt.visible.add(bolt.segments.get(bolt.visible.size()));
          }
          draw = bolt.visible;
          fade = LEADER_BRIGHTNESS_LO
            + (LEADER_BRIGHTNESS_HI - LEADER_BRIGHTNESS_LO) * progress;
          break;
        }
        case FLASH: {
          draw = bolt.segments;
          fade = 1.0; // return stroke: whole bolt at full brightness, one frame
          break;
        }
        default: { // GLOW
          draw = bolt.segments;
          final double u = Math.min((bolt.ageMs - bolt.leaderMs) / bolt.glowMs, 1);
          fade = AFTERGLOW_BRIGHTNESS * (1 - u) * (1 - u);
          break;
        }
      }
      bolt.generator.render(graphics, draw, fade, 1.0, thick, BLEED);
    }
  }
}
