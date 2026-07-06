package apotheneum.jvyduna.patterns;

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
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * After Dark 1.0 style fractal mountain ranges that accumulate in layers.
 *
 * Each new ridge is a 1D midpoint-displacement heightfield wrapped seamlessly
 * around the surface ring (cube: all four faces as one 200-column strip;
 * cylinder: 120 columns), filled with hard elevation bands (base water /
 * forest / rock / snow) and revealed by a slow wipe traveling around the
 * ring. Ridges step downward front-over-back like the original screensaver;
 * when the field is full it fades to black and the sequence restarts.
 *
 * See Mountains.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Mountains")
@LXComponent.Description("Fractal mountain ranges accumulate in layers around the cube and cylinder, After Dark style")
public class Mountains extends ApotheneumPattern {

  // ---- Timing constants (physical intent) -----------------------------------

  /**
   * Seconds for a new ridge's reveal wipe to travel the full ring. Sustained
   * motion on the 40-foot sculpture must take >= 5 s to traverse it, at every
   * energy, so even the peak endpoint sits at the cap.
   */
  private static final double REVEAL_SECONDS_AMBIENT = 8;
  private static final double REVEAL_SECONDS_PEAK = 5;

  /**
   * Idle seconds between one ridge finishing its reveal and the next spawning.
   * Ambient: a new range every ~22 s (14 idle + 8 reveal) reads as slow
   * geologic accumulation; peak: ridges chain nearly back-to-back.
   */
  private static final double SPAWN_IDLE_SECONDS_AMBIENT = 14;
  private static final double SPAWN_IDLE_SECONDS_PEAK = 2.5;

  /**
   * Seconds for the restart wipe (full field fades to black). Event-like: it
   * happens once per multi-minute cycle and has 2 s of visual life (>= 1.5 s
   * event minimum).
   */
  private static final double FADE_SECONDS = 2;

  /** Residual brightness fraction targeted at the end of the restart fade */
  private static final double FADE_FLOOR = 0.004;

  /**
   * Seconds for the Glint trigger's brightness swell to settle back to the
   * baseline lift. Event-like, no spatial motion; 2 s >= 1.5 s event minimum.
   */
  private static final double GLINT_SECONDS = 2;

  /**
   * Retime clamp for the reveal wipe: [0.7, 1.0]. The upper bound is 1
   * (never faster than nominal) because REVEAL_SECONDS_PEAK sits exactly at
   * the >= 5 s full-traversal cap — any speed-up at Energy = 1 would break it.
   * Slowing by up to 30% stretches a reveal to at most 1.43x nominal.
   */
  private static final double REVEAL_RETIME_MAX = 1.0;

  // ---- Geography constants ---------------------------------------------------

  /**
   * Rows between elevation band thresholds. 6 rows comfortably exceeds the
   * 4-row minimum for a band to read as a solid stripe at LED pitch.
   */
  private static final int BAND_SPACING_ROWS = 6;

  /** Max rows the Bands parameter shifts all thresholds up/down */
  private static final int BAND_OFFSET_RANGE_ROWS = 4;

  /** Lowest threshold is clamped here so the base band never becomes a sliver */
  private static final int MIN_BASE_BAND_ROWS = 4;

  /** Snow band is dropped entirely if fewer than this many rows would show */
  private static final int MIN_SNOW_ROWS = 4;

  /** Rows each successive ridge's baseline steps down (nearer = lower) */
  private static final int BASELINE_STEP_ROWS = 6;

  /** Rows of sky left above the tallest possible first-ridge peak */
  private static final int TOP_MARGIN_ROWS = 2;

  /** Brightness of the farthest (first) ridge relative to the nearest (haze depth cue) */
  private static final double FAR_BRIGHTNESS = 0.55;

  /**
   * Midpoint-displacement amplitude falloff per octave, mapped from effective
   * roughness: 0.40 yields smooth rolling hills, 0.75 jagged alpine ridges.
   */
  private static final double FALLOFF_SMOOTH = 0.40;
  private static final double FALLOFF_ROUGH = 0.75;

  /** How much recent treble adds to the roughness of a newly spawned ridge */
  private static final double TREBLE_TO_ROUGHNESS = 0.6;

  // ---- Band palette (fixed hues; Hue parameter rotates them) ----------------
  // CURATE: hues/sats/brightness picked blind; verify band contrast on hardware.

  private static final double BASE_HUE = 225, BASE_SAT = 85, BASE_BRI = 35;   // deep water/valley blue
  private static final double FOREST_HUE = 130, FOREST_SAT = 75, FOREST_BRI = 48;
  private static final double ROCK_HUE = 30, ROCK_SAT = 45, ROCK_BRI = 62;
  private static final double SNOW_HUE = 0, SNOW_SAT = 10, SNOW_BRI = 100;

  /**
   * Audio level lift: pixels are baked at full band brightness and displayed at
   * LIFT_BASE..LIFT_BASE+LIFT_SPAN of it, so loud passages gently glow without
   * clipping. Silence sits steady at 80%.
   */
  private static final double LIFT_BASE = 0.80;
  private static final double LIFT_SPAN = 0.20;
  private static final double LIFT_GAIN = 1.25;

  // ---- Parameters -------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Mountains");

  public final TriggerParameter wipe = bag.register(
    new TriggerParameter("Wipe", this::wipeNow)
    .setDescription("Fade the whole field to black over 2s and restart the sequence"));

  public final TriggerParameter newRidge = bag.register(
    new TriggerParameter("NewRidge", this::forceRidge)
    .setDescription("Spawn a new ridge now (ignored while one is revealing or fading)"));

  public final TriggerParameter invert = bag.register(
    new TriggerParameter("Invert", this::toggleInvert)
    .setDescription("Toggle cave mode: flip the whole render so ridges hang from the top"));

  public final TriggerParameter glint = bag.register(
    new TriggerParameter("Glint", this::glintNow)
    .setDescription("Brightness swell: the whole field glows to full and settles back over 2s"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 slow ambient accumulation, 0.6-1.0 beat-locked 160 BPM regime");

  public final CompoundParameter roughness =
    new CompoundParameter("Rough", 0.5)
    .setDescription("Base jaggedness of newly spawned ridges (recent treble adds on top)");

  public final CompoundParameter relief =
    new CompoundParameter("Relief", 0.55, 0.2, 0.75)
    .setDescription("Peak height of each ridge as a fraction of surface height");

  public final CompoundParameter bandOffset =
    new CompoundParameter("Bands", 0, -1, 1)
    .setDescription("Shifts all elevation band thresholds down/up (+1 raises the snowline ~4 rows)");

  public final CompoundParameter hueShift =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Rotates all band hues; snow stays near-white");

  public final CompoundParameter spawnRate =
    new CompoundParameter("Spawn", 1, 0.25, 4)
    .setDescription("Multiplier on ridge spawn rate (>1 = shorter gaps between ridges)");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division that ridge spawns and reveal completions land on");

  public final TriggerParameter rndTrig =
    new TriggerParameter("RndTrig", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- State ------------------------------------------------------------------

  private final AudioReactive audio;
  private final TempoLock tempoLock;

  /** Cave mode: mirror the render vertically so ridges hang from the top */
  private boolean inverted = false;

  /** Glint trigger envelope: set to 1 on trigger, decays linearly to 0 */
  private double glintLevel = 0;

  // Per-frame shared values computed once in render(), always before the
  // fields' advance() reads them. Initialized to their e=0 values because one
  // reader can run pre-render: retime() inside a trigger-fired spawn() (e.g.
  // MIDI NewRidge), which should see a real duration rather than 0 (retime
  // guards 0 by returning 1, which would silently leave that reveal unsynced).
  private double frameRevealMs = 1000 * REVEAL_SECONDS_AMBIENT;
  private double frameSpawnIdleMs = 1000 * SPAWN_IDLE_SECONDS_AMBIENT;
  private boolean frameGridCross;

  private final Field cubeField = new Field("cube", 4 * Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);
  private final Field cylinderField = new Field("cylinder", Apotheneum.RING_LENGTH, Apotheneum.CYLINDER_HEIGHT);

  public Mountains(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("wipe", this.wipe);
    addParameter("newRidge", this.newRidge);
    addParameter("invert", this.invert);
    addParameter("glint", this.glint);
    addParameter("rndTrig", this.rndTrig);
    addParameter("energy", this.energy);
    addParameter("roughness", this.roughness);
    addParameter("relief", this.relief);
    addParameter("bandOffset", this.bandOffset);
    addParameter("hueShift", this.hueShift);
    addParameter("spawnRate", this.spawnRate);
    addParameter("audio", this.audioDepth);
    addParameter("tempoDiv", this.tempoDiv);

    // Jump candidates — mirrored 1:1 in the Mountains.md table
    bag.jumpable(this.roughness, 0.2, 0.9);
    bag.jumpable(this.bandOffset, -0.75, 1.0);
    bag.jumpable(this.hueShift);
    bag.jumpable(this.spawnRate, 0.5, 2);
  }

  // ---- One mountain field per surface ----------------------------------------

  private static final int IDLE = 0;
  private static final int REVEALING = 1;
  private static final int FADING = 2;

  private final class Field {

    private final String name;
    private final int width;
    private final int height;
    private final SurfaceCanvas canvas;

    /** Heightfield scratch, rows above baseline; [width] mirrors [0] for the wrap */
    private final float[] ridge;

    private final Random random = new Random();

    private int state = IDLE;

    /** Baseline row of the NEXT ridge to spawn; < 0 = empty field, start a cycle */
    private int baseline = -1;

    /** Baseline of the first ridge of the current cycle (for the depth haze ramp) */
    private int cycleStart;

    /** Time spent idle since the last reveal finished (starts huge: spawn on frame 1) */
    private double idleMs = 1e12;

    private double fadeMs = 0;

    // Current revealing ridge
    private double front = 0;   // wipe front in columns traveled
    private int painted = 0;    // columns committed so far
    private int wipeStart = 0;  // ring column where this ridge's wipe began

    /**
     * Reveal duration fixed at spawn, retimed so the wipe completes on a
     * tempo-grid boundary; 0 = not yet retimed (follow the live per-frame
     * energy value).
     */
    private double syncedRevealMs = 0;
    private int reliefRows = 0;
    private int t1, t2, t3;     // elevation band thresholds (rows above baseline)
    private final int[] bandColors = new int[4];

    private Field(String name, int width, int height) {
      this.name = name;
      this.width = width;
      this.height = height;
      this.canvas = new SurfaceCanvas(width, height);
      this.ridge = new float[width + 1];
    }

    private void advance(double deltaMs) {
      switch (this.state) {

      case FADING:
        this.fadeMs += deltaMs;
        this.canvas.decay(Math.pow(FADE_FLOOR, deltaMs / (FADE_SECONDS * 1000)));
        if (this.fadeMs >= FADE_SECONDS * 1000) {
          this.canvas.fill(LXColor.BLACK);
          this.baseline = -1;
          this.state = IDLE;
          this.idleMs = 1e12; // restart immediately (still subject to grid gating)
        }
        break;

      case REVEALING:
        // Per-ridge duration fixed at spawn (retimed onto the grid);
        // pre-retime spawns (trigger-fired) fall back to the live duration
        final double revealMs = (this.syncedRevealMs > 0)
          ? this.syncedRevealMs : frameRevealMs;
        this.front += this.width * deltaMs / revealMs;
        final int target = (int) Math.min(this.front, this.width);
        while (this.painted < target) {
          paintColumn((this.wipeStart + this.painted) % this.width);
          this.painted++;
        }
        if (this.painted >= this.width) {
          this.baseline += BASELINE_STEP_ROWS;
          this.state = IDLE;
          this.idleMs = 0;
        }
        break;

      default: // IDLE
        this.idleMs = Math.min(this.idleMs + deltaMs, 1e12);
        // Grid-locked: once due, spawn (or start the restart fade) exactly on
        // the next TempoDiv boundary — the grid is the beat anchor.
        if ((this.idleMs >= frameSpawnIdleMs) && frameGridCross) {
          if (isFull()) {
            startFade("field full");
          } else {
            spawn();
          }
        }
        break;
      }
    }

    /** Full when even the tallest possible crest of the next ridge is below the bottom */
    private boolean isFull() {
      final int reliefNow = (int) Math.round(relief.getValue() * this.height);
      return (this.baseline >= 0) && (this.baseline - reliefNow >= this.height);
    }

    private void spawn() {
      this.reliefRows = Math.max(6, (int) Math.round(relief.getValue() * this.height));
      if (this.baseline < 0) {
        // First ridge of a cycle: peaks just clear the top margin
        this.baseline = this.reliefRows + TOP_MARGIN_ROWS;
        this.cycleStart = this.baseline;
      }

      // Heightfield: wrap-matched endpoints, midpoint displacement, then
      // normalized to [0, reliefRows] so every ridge shows its full band range.
      final double effRoughness = LXUtils.constrain(
        roughness.getValue() + TREBLE_TO_ROUGHNESS * audio.treble, 0, 1);
      final float falloff = (float) (FALLOFF_SMOOTH + (FALLOFF_ROUGH - FALLOFF_SMOOTH) * effRoughness);
      this.ridge[0] = 0;
      this.ridge[this.width] = 0;
      displace(0, this.width, 1f, falloff);
      normalizeRidge();

      // Elevation band thresholds (rows above this ridge's baseline)
      final int off = (int) Math.round(bandOffset.getValue() * BAND_OFFSET_RANGE_ROWS);
      this.t1 = Math.max(MIN_BASE_BAND_ROWS, BAND_SPACING_ROWS + off);
      this.t2 = 2 * BAND_SPACING_ROWS + off;
      this.t3 = 3 * BAND_SPACING_ROWS + off;
      if (this.reliefRows < this.t3 + MIN_SNOW_ROWS) {
        this.t3 = Integer.MAX_VALUE; // snow would be a sliver; drop it entirely
      }

      // Depth haze: farther (earlier) ridges are dimmer, nearest is full bright
      final double span = this.height + this.reliefRows - this.cycleStart;
      final double progress = (span > 0)
        ? LXUtils.constrain((this.baseline - this.cycleStart) / span, 0, 1) : 1;
      final double bright = FAR_BRIGHTNESS + (1 - FAR_BRIGHTNESS) * progress;
      final double hs = hueShift.getValue();
      this.bandColors[0] = LXColor.hsb((BASE_HUE + hs) % 360, BASE_SAT, BASE_BRI * bright);
      this.bandColors[1] = LXColor.hsb((FOREST_HUE + hs) % 360, FOREST_SAT, FOREST_BRI * bright);
      this.bandColors[2] = LXColor.hsb((ROCK_HUE + hs) % 360, ROCK_SAT, ROCK_BRI * bright);
      this.bandColors[3] = LXColor.hsb((SNOW_HUE + hs) % 360, SNOW_SAT, SNOW_BRI * bright);

      this.wipeStart = this.random.nextInt(this.width);
      this.front = 0;
      this.painted = 0;
      // Fix this ridge's reveal duration now, stretched (never compressed —
      // see REVEAL_RETIME_MAX) so the wipe closes the loop on a TempoDiv
      // boundary. Spawns themselves land on a boundary when gated through
      // advance(), so the whole reveal is grid-aligned end to end.
      final double s = tempoLock.retime(frameRevealMs, tempoDiv.getEnum(),
        TempoLock.DEFAULT_MIN_SCALE, REVEAL_RETIME_MAX);
      this.syncedRevealMs = frameRevealMs / s;
      this.state = REVEALING;
      LX.log("Mountains[" + this.name + "]: ridge baseline=" + this.baseline
        + " relief=" + this.reliefRows + " roughness=" + String.format("%.2f", effRoughness));
    }

    /** Recursive 1D midpoint displacement over ridge[lo..hi] (spawn-time only) */
    private void displace(int lo, int hi, float amp, float falloff) {
      if (hi - lo < 2) {
        return;
      }
      final int mid = (lo + hi) >>> 1;
      this.ridge[mid] = 0.5f * (this.ridge[lo] + this.ridge[hi])
        + amp * (2 * this.random.nextFloat() - 1);
      displace(lo, mid, amp * falloff, falloff);
      displace(mid, hi, amp * falloff, falloff);
    }

    /** Remap the raw heightfield to [0, reliefRows] */
    private void normalizeRidge() {
      float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
      for (int i = 0; i < this.width; ++i) {
        min = Math.min(min, this.ridge[i]);
        max = Math.max(max, this.ridge[i]);
      }
      float span = max - min;
      if (span < 1e-6f) {
        span = 1;
      }
      for (int i = 0; i <= this.width; ++i) {
        this.ridge[i] = (this.ridge[i] - min) / span * this.reliefRows;
      }
    }

    /** Commit one ring column of the revealing ridge into the canvas */
    private void paintColumn(int x) {
      final int crest = this.baseline - Math.round(this.ridge[x]);
      for (int y = Math.max(0, crest); y < this.height; ++y) {
        final int elevation = this.baseline - y;
        final int color;
        if (elevation >= this.t3) {
          color = this.bandColors[3];      // snow
        } else if (elevation >= this.t2) {
          color = this.bandColors[2];      // rock
        } else if (elevation >= this.t1) {
          color = this.bandColors[1];      // forest
        } else {
          color = this.bandColors[0];      // base / water
        }
        this.canvas.set(x, y, color);
      }
    }

    private void startFade(String why) {
      if (this.state == FADING) {
        return;
      }
      this.state = FADING;
      this.fadeMs = 0;
      LX.log("Mountains[" + this.name + "]: fade to restart (" + why + ")");
    }

    /** NewRidge trigger: spawn immediately if idle; full field fades instead */
    private void force() {
      if (this.state != IDLE) {
        LX.log("Mountains[" + this.name + "]: NewRidge ignored (busy)");
        return;
      }
      if (isFull()) {
        startFade("field full (forced)");
      } else {
        spawn();
      }
    }
  }

  // ---- Trigger handlers --------------------------------------------------------

  private void wipeNow() {
    this.cubeField.startFade("wipe trigger");
    this.cylinderField.startFade("wipe trigger");
  }

  private void forceRidge() {
    this.cubeField.force();
    this.cylinderField.force();
  }

  private void toggleInvert() {
    this.inverted = !this.inverted;
    LX.log("Mountains: invert -> " + (this.inverted ? "cave (hanging)" : "normal (rising)"));
  }

  private void glintNow() {
    this.glintLevel = 1;
  }

  // ---- Render --------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);

    final double e = this.energy.getValue();
    this.frameRevealMs = 1000 * Ranges.lin(e, REVEAL_SECONDS_AMBIENT, REVEAL_SECONDS_PEAK);
    this.frameSpawnIdleMs =
      1000 * Ranges.exp(e, SPAWN_IDLE_SECONDS_AMBIENT, SPAWN_IDLE_SECONDS_PEAK)
      / this.spawnRate.getValue();
    // crossed() must tick every frame so the gate never misreads a stale
    // cycle count as an instant off-grid crossing
    this.frameGridCross = this.tempoLock.crossed(this.tempoDiv.getEnum());

    this.cubeField.advance(deltaMs);
    this.cylinderField.advance(deltaMs);

    // Glint trigger: linear settle from full lift back to the baseline
    this.glintLevel = Math.max(0, this.glintLevel - deltaMs / (GLINT_SECONDS * 1000));

    final double audioLift = Math.min(1, this.audio.level * LIFT_GAIN);
    final double lift = LIFT_BASE + LIFT_SPAN * Math.max(audioLift, this.glintLevel);
    this.cubeField.canvas.copyTo(Apotheneum.cube.exterior, this.colors, lift, this.inverted);
    this.cylinderField.canvas.copyTo(Apotheneum.cylinder.exterior, this.colors, lift, this.inverted);

    copyCubeExterior();
    copyCylinderExterior();
  }
}
