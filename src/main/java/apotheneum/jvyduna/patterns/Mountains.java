package apotheneum.jvyduna.patterns;

import java.util.Arrays;
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
 * After Dark style fractal mountain ranges, projected as a 3D ring of terrain.
 *
 * The terrain is a ring-shaped heightfield surrounding the viewer: DEPTH_ROWS
 * concentric ranges, each a 1D midpoint-displacement ridge wrapped seamlessly
 * around the surface (cube: all four faces as one 200-column strip; cylinder:
 * its full ring), blended with its neighbor for cross-range coherence. Ranges
 * reveal one at a time back-to-front via a wipe traveling around the ring,
 * stepping down the surface like the original screensaver; when all ranges
 * are revealed the field fades to black and the sequence restarts.
 *
 * The scene is re-projected every frame, so the camera controls are live:
 * RotX/RotZ tilt the terrain ring about the two horizontal axes (ranges rise
 * on one side of the building and dip on the other), RotY orbits it about the
 * vertical axis, Zoom scales it, and Persp compresses far ranges toward the
 * horizon. Elevation band colors come from the project palette swatch; the
 * Planet parameter selects an After Dark planet terrain scheme (band spacing,
 * snowline, water plane, roughness) without touching color. Style switches
 * between solid banded fills and the After Dark "Frame" wireframe grid.
 *
 * See Mountains.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Mountains")
@LXComponent.Description("Palette-colored fractal mountain ranges on a rotatable 3D terrain ring, After Dark style")
public class Mountains extends ApotheneumPattern {

  // ---- Timing constants (physical intent) -----------------------------------

  /**
   * Seconds for a new range's reveal wipe to travel the full ring. Sustained
   * motion on the 40-foot sculpture must take >= 5 s to traverse it, at every
   * energy, so even the peak endpoint sits at the cap.
   */
  private static final double REVEAL_SECONDS_AMBIENT = 8;
  private static final double REVEAL_SECONDS_PEAK = 5;

  /**
   * Idle seconds between one range finishing its reveal and the next spawning.
   * Ambient: a new range every ~22 s (14 idle + 8 reveal) reads as slow
   * geologic accumulation; peak: ranges chain nearly back-to-back.
   */
  private static final double SPAWN_IDLE_SECONDS_AMBIENT = 14;
  private static final double SPAWN_IDLE_SECONDS_PEAK = 2.5;

  /** Above this energy, range spawns wait for a bass transient (beat-locked feel) */
  private static final double BASS_GATE_ENERGY = 0.55;

  /** ... but never wait longer than this for a hit (silence safety) */
  private static final double BASS_GATE_FALLBACK_MS = 3000;

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
   * Sync retime clamp for the reveal wipe: [0.7, 1.0]. The upper bound is 1
   * (never faster than nominal) because REVEAL_SECONDS_PEAK sits exactly at
   * the >= 5 s full-traversal cap — any speed-up at Energy = 1 would break it.
   * Slowing by up to 30% stretches a reveal to at most 1.43x nominal.
   */
  private static final double REVEAL_RETIME_MAX = 1.0;

  // ---- Geography constants ---------------------------------------------------

  /** Concentric terrain ranges per cycle (one reveals per spawn, then restart) */
  private static final int DEPTH_ROWS = 8;

  /**
   * Cross-range coherence: each new range blends this fraction of the previous
   * range's heightfield into its own, so successive ranges read as one terrain
   * rather than unrelated ridgelines. CURATE: 0.35 picked blind — raise for
   * rolling continuity, lower for independent After Dark style ranges.
   */
  private static final float ROW_BLEND = 0.35f;

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

  /** Screen rows between successive range baselines at Zoom 1, Persp 0 */
  private static final int ROW_SPACING_ROWS = 6;

  /** Rows of sky left above the farthest range's tallest possible peak */
  private static final int TOP_MARGIN_ROWS = 2;

  /** Brightness of the farthest range relative to the nearest (haze depth cue) */
  private static final double FAR_BRIGHTNESS = 0.55;

  /**
   * RotX/RotZ tilt displacement in screen rows at full tilt: the farthest
   * (smallest radius) range swings +/- TILT_BASE, each nearer range adds
   * TILT_STEP more, so the ring tilts rigidly rather than shearing.
   * CURATE: 12/3 picked blind — at 45 degrees the nearest range swings
   * ~23 rows; verify the tilted horizon reads as one coherent plane.
   */
  private static final double TILT_BASE_ROWS = 12;
  private static final double TILT_STEP_ROWS = 3;

  /**
   * Perspective strength: at Persp = 1 the farthest range's row spacing and
   * heights compress to 1/(1+PERSP_GAIN) of the nearest. CURATE: 2.0 picked
   * blind — verify far ranges still resolve at LED pitch when compressed.
   */
  private static final double PERSP_GAIN = 2.0;

  /** Wire style: canvas columns between radial grid connector lines */
  private static final int WIRE_COL_STEP = 5;

  /**
   * Midpoint-displacement amplitude falloff per octave, mapped from effective
   * roughness: 0.40 yields smooth rolling hills, 0.75 jagged alpine ridges.
   */
  private static final double FALLOFF_SMOOTH = 0.40;
  private static final double FALLOFF_ROUGH = 0.75;

  /** How much recent treble adds to the roughness of a newly spawned range */
  private static final double TREBLE_TO_ROUGHNESS = 0.6;

  // ---- Fallback band palette (used only when the project swatch is empty) ---
  // CURATE: hues/sats/brightness picked blind; verify band contrast on hardware.

  private static final double BASE_HUE = 225, BASE_SAT = 85, BASE_BRI = 35;   // deep water/valley blue
  private static final double FOREST_HUE = 130, FOREST_SAT = 75, FOREST_BRI = 48;
  private static final double ROCK_HUE = 30, ROCK_SAT = 45, ROCK_BRI = 62;
  private static final double SNOW_HUE = 0, SNOW_SAT = 10, SNOW_BRI = 100;

  /**
   * Sparse-swatch legibility ladder: with 1-3 swatch colors the four bands are
   * lerped from too few anchors to guarantee contrast, so each band is also
   * stepped in brightness (base darkest, snow brightest). CURATE: verify the
   * single-color-swatch case still reads as distinct elevation bands.
   */
  private static final double[] SPARSE_BAND_LIFT = { 0.55, 0.75, 0.90, 1.0 };

  /**
   * Audio level lift: pixels are baked at full band brightness and displayed at
   * LIFT_BASE..LIFT_BASE+LIFT_SPAN of it, so loud passages gently glow without
   * clipping. Silence sits steady at 80%.
   */
  private static final double LIFT_BASE = 0.80;
  private static final double LIFT_SPAN = 0.20;
  private static final double LIFT_GAIN = 1.25;

  // ---- Planets ----------------------------------------------------------------

  /**
   * After Dark planet terrain schemes — the module's original popup was
   * Mercury..Pluto plus Random. Schemes shape terrain only (colors always come
   * from the project palette): band threshold spacing multiplier, snowline
   * offset in rows (positive raises it away), water plane level as a fraction
   * of normalized terrain height (< 0 = no water plane), roughness bias,
   * relief multiplier, and range spacing multiplier.
   * CURATE: all scheme values picked blind (no authoritative source records
   * the original planet data) — tune each on hardware.
   */
  public enum Planet {
    MERCURY("Mercury", 0.9, 6, -1, 0.20, 0.75, 0.85),   // airless cratered hills
    VENUS("Venus", 1.1, 8, -1, -0.15, 0.70, 1.10),      // smooth volcanic plains
    EARTH("Earth", 1.0, 0, 0.18, 0, 1.0, 1.0),          // the neutral reference
    MARS("Mars", 1.0, 1, -1, 0.05, 1.30, 1.0),          // dry, Olympus-scale relief
    JUPITER("Jupiter", 1.3, 9, 0.40, -0.30, 1.35, 1.30),// vast smooth cloud swells
    SATURN("Saturn", 1.4, 9, 0.35, -0.25, 1.20, 1.40),
    URANUS("Uranus", 1.0, -2, 0.30, -0.10, 0.90, 1.0),  // icy, low snowline
    NEPTUNE("Neptune", 1.1, -1, 0.30, 0.10, 1.10, 1.10),
    PLUTO("Pluto", 0.8, -3, -1, 0.25, 0.90, 0.80),      // jagged ice mountains
    RANDOM("Random", 1.0, 0, 0.18, 0, 1.0, 1.0);        // re-rolled each cycle

    private final String label;
    private final double bandMult;
    private final int snowOffsetRows;
    private final double waterLevel;
    private final double roughBias;
    private final double reliefMult;
    private final double spacingMult;

    private Planet(String label, double bandMult, int snowOffsetRows,
      double waterLevel, double roughBias, double reliefMult, double spacingMult) {
      this.label = label;
      this.bandMult = bandMult;
      this.snowOffsetRows = snowOffsetRows;
      this.waterLevel = waterLevel;
      this.roughBias = roughBias;
      this.reliefMult = reliefMult;
      this.spacingMult = spacingMult;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  /** Cached Planet.values() so RANDOM resolution never allocates (RANDOM is last) */
  private static final Planet[] PLANETS = Planet.values();

  /** Render style: solid banded fills, or the After Dark "Frame" wireframe grid */
  public enum Style {
    SOLID("Solid"),
    WIRE("Wire");

    private final String label;

    private Style(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  // ---- Parameters -------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Mountains");

  public final TriggerParameter wipe = bag.register(
    new TriggerParameter("Wipe", this::wipeNow)
    .setDescription("Fade the whole field to black over 2s and restart the sequence"));

  public final TriggerParameter newRidge = bag.register(
    new TriggerParameter("NewRidge", this::forceRidge)
    .setDescription("Reveal the next range now (ignored while one is revealing or fading)"));

  public final TriggerParameter invert = bag.register(
    new TriggerParameter("Invert", this::toggleInvert)
    .setDescription("Toggle cave mode: flip the whole render so ranges hang from the top"));

  public final TriggerParameter glint = bag.register(
    new TriggerParameter("Glint", this::glintNow)
    .setDescription("Brightness swell: the whole field glows to full and settles back over 2s"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 slow ambient accumulation, 0.6-1.0 beat-locked 160 BPM regime");

  public final CompoundParameter roughness =
    new CompoundParameter("Rough", 0.5)
    .setDescription("Base jaggedness of newly spawned ranges (planet bias and recent treble add on top)");

  public final CompoundParameter relief =
    new CompoundParameter("Relief", 0.55, 0.2, 0.75)
    .setDescription("Peak height of each range as a fraction of surface height (live; planet multiplies)");

  public final CompoundParameter bandOffset =
    new CompoundParameter("Bands", 0, -1, 1)
    .setDescription("Shifts all elevation band thresholds down/up (+1 raises the snowline ~4 rows)");

  public final CompoundParameter hueShift =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Rotates the palette-sampled band hues (0 = pure project palette)");

  public final CompoundParameter spawnRate =
    new CompoundParameter("Spawn", 1, 0.25, 4)
    .setDescription("Multiplier on range spawn rate (>1 = shorter gaps between ranges)");

  public final CompoundParameter rotX =
    new CompoundParameter("RotX", 0, -45, 45)
    .setDescription("Tilt (degrees) of the terrain ring about the X axis: ranges rise on one side of the building, dip on the other");

  public final CompoundParameter rotY =
    new CompoundParameter("RotY", 0, 0, 360)
    .setDescription("Orbit (degrees) of the terrain ring about the vertical axis: spins the whole landscape around the sculpture");

  public final CompoundParameter rotZ =
    new CompoundParameter("RotZ", 0, -45, 45)
    .setDescription("Tilt (degrees) of the terrain ring about the Z axis, 90 degrees around the ring from RotX");

  public final CompoundParameter zoom =
    new CompoundParameter("Zoom", 1, 0.5, 2.5)
    .setDescription("Scales range heights and spacing about the horizon (After Dark's Zoom slider)");

  public final CompoundParameter persp =
    new CompoundParameter("Persp", 0.35, 0, 1)
    .setDescription("Perspective: 0 = flat isometric spacing, 1 = far ranges compress hard toward the horizon");

  public final EnumParameter<Planet> planet =
    new EnumParameter<Planet>("Planet", Planet.EARTH)
    .setDescription("After Dark planet terrain scheme (spacing, snowline, water, roughness) — colors always come from the project palette; applies at the next cycle");

  public final EnumParameter<Style> style =
    new EnumParameter<Style>("Style", Style.SOLID)
    .setDescription("Solid banded fills, or the After Dark Frame-style wireframe grid");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final BooleanParameter sync =
    new BooleanParameter("Sync", true)
    .setDescription("Lock range spawns and reveal completions to the tempo grid; off restores free-running timing");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division that range spawns and reveal completions land on when Sync is enabled");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  // ---- State ------------------------------------------------------------------

  private final AudioReactive audio;
  private final TempoLock tempoLock;

  /** Cave mode: mirror the render vertically so ranges hang from the top */
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
  private boolean frameBassGated;
  private boolean frameBassHit;
  private boolean frameSyncOn;
  private boolean frameGridCross;

  // Per-frame camera values (render() computes; repaint() reads)
  private double frameSinX;
  private double frameSinZ;
  private double frameYawNorm;
  private double frameZoom;
  private double framePersp;
  private boolean frameWire;

  // Palette-sampled band base colors (water/forest/rock/snow), full brightness,
  // hue-shifted; rebuilt only when the swatch or Hue actually changes
  private final int[] bandBase = new int[4];
  private final int[] sampledSwatch = new int[4];
  private int cachedSwatchCount = -1;
  private double cachedHueShift = Double.NaN;

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
    addParameter("energy", this.energy);
    addParameter("roughness", this.roughness);
    addParameter("relief", this.relief);
    addParameter("bandOffset", this.bandOffset);
    addParameter("hueShift", this.hueShift);
    addParameter("spawnRate", this.spawnRate);
    addParameter("rotX", this.rotX);
    addParameter("rotY", this.rotY);
    addParameter("rotZ", this.rotZ);
    addParameter("zoom", this.zoom);
    addParameter("persp", this.persp);
    addParameter("planet", this.planet);
    addParameter("style", this.style);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    // Jump candidates — mirrored 1:1 in the Mountains.md table
    bag.jumpable(this.roughness, 0.2, 0.9);
    bag.jumpable(this.bandOffset, -0.75, 1.0);
    bag.jumpable(this.hueShift);
    bag.jumpable(this.spawnRate, 0.5, 2);
    bag.jumpable(this.rotY);
    bag.jumpable(this.zoom, 0.8, 1.6);
    bag.jumpable(this.planet);
  }

  // ---- Palette-driven band colors ----------------------------------------------

  /**
   * Sample the four elevation band colors (water/forest/rock/snow) from the
   * project palette swatch: first four colors when the swatch has >= 4,
   * four evenly spaced lerp samples (plus a brightness ladder for legibility)
   * when it has 1-3, and the fixed fallback landscape constants when empty.
   * Rebuilds only when the sampled inputs or Hue actually change.
   */
  private void updateBandColors() {
    final double hs = this.hueShift.getValue();
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = Math.min(swatch.size(), 4);

    if (n == 0) {
      if ((this.cachedSwatchCount != 0) || (hs != this.cachedHueShift)) {
        this.cachedSwatchCount = 0;
        this.cachedHueShift = hs;
        this.bandBase[0] = LXColor.hsb((BASE_HUE + hs) % 360, BASE_SAT, BASE_BRI);
        this.bandBase[1] = LXColor.hsb((FOREST_HUE + hs) % 360, FOREST_SAT, FOREST_BRI);
        this.bandBase[2] = LXColor.hsb((ROCK_HUE + hs) % 360, ROCK_SAT, ROCK_BRI);
        this.bandBase[3] = LXColor.hsb((SNOW_HUE + hs) % 360, SNOW_SAT, SNOW_BRI);
      }
      return;
    }

    boolean changed = (n != this.cachedSwatchCount) || (hs != this.cachedHueShift);
    if (n >= 4) {
      for (int i = 0; i < 4; ++i) {
        final int c = swatch.get(i).getColor();
        if (c != this.sampledSwatch[i]) {
          changed = true;
          this.sampledSwatch[i] = c;
        }
      }
    } else {
      // 1-3 colors: four even samples along the lerp chain, brightness-stepped
      for (int i = 0; i < 4; ++i) {
        final double p = i * (n - 1) / 3.0;
        final int i0 = (int) p;
        final int i1 = Math.min(i0 + 1, n - 1);
        final int c = scaleRgb(LXColor.lerp(
          swatch.get(i0).getColor(), swatch.get(i1).getColor(), (float) (p - i0)),
          SPARSE_BAND_LIFT[i]);
        if (c != this.sampledSwatch[i]) {
          changed = true;
          this.sampledSwatch[i] = c;
        }
      }
    }
    if (!changed) {
      return;
    }
    this.cachedSwatchCount = n;
    this.cachedHueShift = hs;
    for (int i = 0; i < 4; ++i) {
      final int c = this.sampledSwatch[i];
      this.bandBase[i] = (hs == 0) ? c :
        LXColor.hsb((LXColor.h(c) + hs) % 360, LXColor.s(c), LXColor.b(c));
    }
  }

  /** Multiply an ARGB color's RGB channels by mult (<= 0 renders black) */
  private static int scaleRgb(int argb, double mult) {
    if ((mult <= 0) || ((argb & 0x00ffffff) == 0)) {
      return 0xff000000;
    }
    final int r = Math.min(255, (int) (((argb >> 16) & 0xff) * mult));
    final int g = Math.min(255, (int) (((argb >> 8) & 0xff) * mult));
    final int b = Math.min(255, (int) ((argb & 0xff) * mult));
    return 0xff000000 | (r << 16) | (g << 8) | b;
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

    /**
     * Normalized (0..1) heightfield per range, wrapped in x. Each range is
     * generated at its own spawn (so per-range roughness/treble still applies)
     * and blended with its predecessor for cross-range coherence.
     */
    private final float[][] terrain;

    /** Midpoint-displacement scratch; [width] mirrors [0] for the wrap */
    private final float[] ridge;

    /** Per-column azimuth trig for the RotX/RotZ ring tilt (fixed at build) */
    private final float[] cosAz;
    private final float[] sinAz;

    // Per-repaint scratch (preallocated; zero-alloc render)
    private final float[] rowBase = new float[DEPTH_ROWS];
    private final float[] rowScale = new float[DEPTH_ROWS];
    private final int[] rowBand = new int[4];
    private final int[] colFloor;
    private float[] crestRow;
    private float[] crestPrev;
    private final int[] wireColor;

    private final Random random = new Random();

    private int state = IDLE;

    /** Fully revealed ranges this cycle; DEPTH_ROWS = field full */
    private int revealedRows = 0;

    /** Terrain scheme resolved at cycle start (RANDOM re-rolls here) */
    private Planet scheme = Planet.EARTH;

    /** Time spent idle since the last reveal finished (starts huge: spawn on frame 1) */
    private double idleMs = 1e12;

    /** Time spent waiting for a bass hit once the idle interval has elapsed */
    private double bassWaitMs = 0;

    private double fadeMs = 0;

    /** Restart-fade brightness multiplier, folded into the copyTo lift */
    private double fadeMult = 1;

    // Current revealing range
    private double front = 0;   // wipe front in terrain columns traveled
    private int painted = 0;    // terrain columns revealed so far
    private int wipeStart = 0;  // terrain column where this range's wipe began

    /**
     * Reveal duration fixed at spawn when Sync is on, retimed so the wipe
     * completes on a tempo-grid boundary; 0 = none (free-running: follow the
     * live per-frame energy value, exactly the Sync-off behavior).
     */
    private double syncedRevealMs = 0;

    private Field(String name, int width, int height) {
      this.name = name;
      this.width = width;
      this.height = height;
      this.canvas = new SurfaceCanvas(width, height);
      this.terrain = new float[DEPTH_ROWS][width];
      this.ridge = new float[width + 1];
      this.cosAz = new float[width];
      this.sinAz = new float[width];
      this.colFloor = new int[width];
      this.crestRow = new float[width];
      this.crestPrev = new float[width];
      this.wireColor = new int[width];
      for (int x = 0; x < width; ++x) {
        final double az = 2 * Math.PI * x / width;
        this.cosAz[x] = (float) Math.cos(az);
        this.sinAz[x] = (float) Math.sin(az);
      }
    }

    private void advance(double deltaMs) {
      switch (this.state) {

      case FADING:
        this.fadeMs += deltaMs;
        this.fadeMult = Math.pow(FADE_FLOOR, this.fadeMs / (FADE_SECONDS * 1000));
        if (this.fadeMs >= FADE_SECONDS * 1000) {
          this.revealedRows = 0;
          this.fadeMult = 1;
          this.state = IDLE;
          this.idleMs = 1e12; // restart immediately (still subject to bass/grid gating)
          this.bassWaitMs = 0;
        }
        break;

      case REVEALING:
        // Sync on: per-range duration fixed at spawn (retimed onto the grid);
        // Sync off (or spawned while off): live energy-driven duration
        final double revealMs = (frameSyncOn && (this.syncedRevealMs > 0))
          ? this.syncedRevealMs : frameRevealMs;
        this.front += this.width * deltaMs / revealMs;
        this.painted = (int) Math.min(this.front, this.width);
        if (this.painted >= this.width) {
          this.revealedRows++;
          this.state = IDLE;
          this.idleMs = 0;
          this.bassWaitMs = 0;
        }
        break;

      default: // IDLE
        this.idleMs = Math.min(this.idleMs + deltaMs, 1e12);
        if (this.idleMs >= frameSpawnIdleMs) {
          if (frameSyncOn) {
            // Grid-locked regime: once due, spawn (or start the restart fade)
            // exactly on the next TempoDiv boundary. This supersedes the
            // bass-hit gate — the grid is the beat anchor when Sync is on.
            if (frameGridCross) {
              if (isFull()) {
                startFade("field full");
              } else {
                spawn();
              }
            }
          } else if (isFull()) {
            startFade("field full");
          } else if (!frameBassGated || frameBassHit || this.bassWaitMs >= BASS_GATE_FALLBACK_MS) {
            spawn();
          } else {
            this.bassWaitMs += deltaMs;
          }
        }
        break;
      }
    }

    private boolean isFull() {
      return this.revealedRows >= DEPTH_ROWS;
    }

    private void spawn() {
      final int row = this.revealedRows;
      if (row == 0) {
        // Cycle start: resolve the terrain scheme (RANDOM re-rolls a planet)
        final Planet selected = planet.getEnum();
        this.scheme = (selected == Planet.RANDOM)
          ? PLANETS[this.random.nextInt(PLANETS.length - 1)] : selected;
      }

      // Heightfield: wrap-matched endpoints, midpoint displacement, then
      // normalized to [0, 1]; Relief/Zoom scale it live at projection time.
      final double effRoughness = LXUtils.constrain(
        roughness.getValue() + this.scheme.roughBias + TREBLE_TO_ROUGHNESS * audio.treble, 0, 1);
      final float falloff = (float) (FALLOFF_SMOOTH + (FALLOFF_ROUGH - FALLOFF_SMOOTH) * effRoughness);
      this.ridge[0] = 0;
      this.ridge[this.width] = 0;
      displace(0, this.width, 1f, falloff);
      normalizeRidge();

      final float[] range = this.terrain[row];
      final float wl = (float) this.scheme.waterLevel;
      for (int x = 0; x < this.width; ++x) {
        float h = (row == 0) ? this.ridge[x]
          : ROW_BLEND * this.terrain[row - 1][x] + (1 - ROW_BLEND) * this.ridge[x];
        if (wl >= 0 && h < wl) {
          h = wl; // water plane: valleys flatten to a flat sea
        }
        range[x] = h;
      }

      this.wipeStart = this.random.nextInt(this.width);
      this.front = 0;
      this.painted = 0;
      // Sync: fix this range's reveal duration now, stretched (never
      // compressed — see REVEAL_RETIME_MAX) so the wipe closes the loop on a
      // TempoDiv boundary. Spawns themselves land on a boundary when gated
      // through advance(), so the whole reveal is grid-aligned end to end.
      this.syncedRevealMs = 0;
      if (sync.isOn()) {
        final double s = tempoLock.retime(frameRevealMs, tempoDiv.getEnum(),
          TempoLock.DEFAULT_MIN_SCALE, REVEAL_RETIME_MAX);
        this.syncedRevealMs = frameRevealMs / s;
      }
      this.state = REVEALING;
      LX.log("Mountains[" + this.name + "]: range " + row + "/" + DEPTH_ROWS
        + " planet=" + this.scheme + " roughness=" + String.format("%.2f", effRoughness));
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

    /** Remap the raw heightfield to [0, 1] */
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
        this.ridge[i] = (this.ridge[i] - min) / span;
      }
    }

    /** True if terrain column j is inside the current reveal wipe arc */
    private boolean inArc(int j) {
      return ((j - this.wipeStart + this.width) % this.width) < this.painted;
    }

    /**
     * Re-project and redraw the whole revealed scene for this frame. The
     * canvas is cleared and rebuilt every frame so the camera parameters
     * (RotX/RotY/RotZ/Zoom/Persp) are live; the reveal wipe and restart fade
     * remain pure state (painted count / fadeMult) rather than baked pixels.
     */
    private void repaint() {
      this.canvas.fill(LXColor.BLACK);

      final int maxRow = (this.state == REVEALING) ? this.revealedRows : this.revealedRows - 1;
      if (maxRow < 0) {
        return;
      }

      // Camera layout for this frame: nearest range has scale ~zoom; farther
      // ranges compress by Persp. Row baselines accumulate downward from the
      // horizon so the farthest range's peaks just clear the top margin.
      final double reliefRows = relief.getValue() * this.height * this.scheme.reliefMult;
      final double spacing = ROW_SPACING_ROWS * this.scheme.spacingMult;
      for (int d = 0; d < DEPTH_ROWS; ++d) {
        final double compress = (DEPTH_ROWS > 1)
          ? (DEPTH_ROWS - 1 - d) / (double) (DEPTH_ROWS - 1) : 0;
        this.rowScale[d] = (float) (frameZoom / (1 + framePersp * PERSP_GAIN * compress));
      }
      this.rowBase[0] = (float) (TOP_MARGIN_ROWS + reliefRows * this.rowScale[0]);
      for (int d = 1; d < DEPTH_ROWS; ++d) {
        this.rowBase[d] = this.rowBase[d - 1] + (float) (spacing * this.rowScale[d]);
      }

      // Elevation band thresholds (rows above a range's baseline, pre-scale)
      final int off = (int) Math.round(bandOffset.getValue() * BAND_OFFSET_RANGE_ROWS);
      final int sp = Math.max(1, (int) Math.round(BAND_SPACING_ROWS * this.scheme.bandMult));
      final double t1 = Math.max(MIN_BASE_BAND_ROWS, sp + off);
      final double t2 = 2 * sp + off;
      double t3 = 3 * sp + off + this.scheme.snowOffsetRows;
      if (reliefRows < t3 + MIN_SNOW_ROWS) {
        t3 = 1e9; // snow would be a sliver; drop it entirely
      }

      final double yawOffset = frameYawNorm * this.width;

      if (frameWire) {
        for (int d = 0; d <= maxRow; ++d) {
          paintWireRow(d, reliefRows, yawOffset, t1, t2, t3);
        }
      } else {
        Arrays.fill(this.colFloor, this.height);
        for (int d = maxRow; d >= 0; --d) {
          paintSolidRow(d, reliefRows, yawOffset, t1, t2, t3);
        }
      }
    }

    /** Per-range depth haze: farthest dimmest, nearest full bright */
    private double rowBrightness(int d) {
      final double progress = (DEPTH_ROWS > 1) ? d / (double) (DEPTH_ROWS - 1) : 1;
      return FAR_BRIGHTNESS + (1 - FAR_BRIGHTNESS) * progress;
    }

    /** RotX/RotZ ring tilt: vertical displacement of range d at canvas column x */
    private double tilt(int d, int x) {
      return (TILT_BASE_ROWS + TILT_STEP_ROWS * d)
        * (frameSinX * this.sinAz[x] - frameSinZ * this.cosAz[x]);
    }

    /** Terrain height of range d at fractional terrain column xs (wrap + lerp) */
    private double sampleHeight(int d, double xs) {
      final int j0 = ((int) Math.floor(xs)) % this.width;
      final int j1 = (j0 + 1 == this.width) ? 0 : j0 + 1;
      final double f = xs - Math.floor(xs);
      final float[] range = this.terrain[d];
      return range[j0] * (1 - f) + range[j1] * f;
    }

    /**
     * Solid style: fill range d from its crest downward, front-to-back with a
     * per-column watermark (colFloor) so nearer ranges occlude farther ones
     * with zero overdraw — the painter's-algorithm equivalent of the old
     * permanent column commits.
     */
    private void paintSolidRow(int d, double reliefRows, double yawOffset,
      double t1, double t2, double t3) {
      final boolean revealing = (this.state == REVEALING) && (d == this.revealedRows);
      final double s = this.rowScale[d];
      final double hAmp = reliefRows * s;
      final double bright = rowBrightness(d);
      for (int i = 0; i < 4; ++i) {
        this.rowBand[i] = scaleRgb(bandBase[i], bright);
      }
      final double yOff1 = t1 * s, yOff2 = t2 * s, yOff3 = t3 * s;

      for (int x = 0; x < this.width; ++x) {
        final double xs = x + yawOffset;
        if (revealing && !inArc(((int) Math.floor(xs)) % this.width)) {
          continue;
        }
        final double baseY = this.rowBase[d] + tilt(d, x);
        final int crest = (int) Math.round(baseY - sampleHeight(d, xs) * hAmp);
        final int top = Math.max(0, crest);
        final int floorY = this.colFloor[x];
        if (top < floorY) {
          final double ySnow = baseY - yOff3;
          final double yRock = baseY - yOff2;
          final double yForest = baseY - yOff1;
          for (int y = top; y < floorY; ++y) {
            final int color = (y <= ySnow) ? this.rowBand[3]
              : (y <= yRock) ? this.rowBand[2]
              : (y <= yForest) ? this.rowBand[1]
              : this.rowBand[0];
            this.canvas.set(x, y, color);
          }
          this.colFloor[x] = top;
        }
      }
    }

    /**
     * Wire style (After Dark "Frame"): crest polyline per range plus radial
     * connectors to the previous range every WIRE_COL_STEP columns — the dry
     * generating grid, no hidden-line removal, colored by crest band.
     */
    private void paintWireRow(int d, double reliefRows, double yawOffset,
      double t1, double t2, double t3) {
      final boolean revealing = (this.state == REVEALING) && (d == this.revealedRows);
      final double s = this.rowScale[d];
      final double hAmp = reliefRows * s;
      final double bright = rowBrightness(d);
      for (int i = 0; i < 4; ++i) {
        this.rowBand[i] = scaleRgb(bandBase[i], bright);
      }

      for (int x = 0; x < this.width; ++x) {
        final double xs = x + yawOffset;
        if (revealing && !inArc(((int) Math.floor(xs)) % this.width)) {
          this.crestRow[x] = Float.NaN;
          continue;
        }
        final double hN = sampleHeight(d, xs);
        this.crestRow[x] = (float) (this.rowBase[d] + tilt(d, x) - hN * hAmp);
        final double elev = hN * reliefRows;
        this.wireColor[x] = (elev >= t3) ? this.rowBand[3]
          : (elev >= t2) ? this.rowBand[2]
          : (elev >= t1) ? this.rowBand[1]
          : this.rowBand[0];
      }

      for (int x = 0; x < this.width; ++x) {
        final float cur = this.crestRow[x];
        if (Float.isNaN(cur)) {
          continue;
        }
        // Ring polyline segment to the next column (wraps at the seam)
        final float next = this.crestRow[(x + 1 == this.width) ? 0 : x + 1];
        if (!Float.isNaN(next)) {
          this.canvas.line(x, Math.round(cur), x + 1, Math.round(next), this.wireColor[x]);
        }
        // Radial connector to the previous (farther) range's crest
        if ((d > 0) && (x % WIRE_COL_STEP == 0)) {
          final float prev = this.crestPrev[x];
          if (!Float.isNaN(prev)) {
            this.canvas.line(x, Math.round(prev), x, Math.round(cur), this.wireColor[x]);
          }
        }
      }

      // This range's crests become the next (nearer) range's connector anchors
      final float[] swap = this.crestPrev;
      this.crestPrev = this.crestRow;
      this.crestRow = swap;
    }

    private void startFade(String why) {
      if (this.state == FADING) {
        return;
      }
      this.state = FADING;
      this.fadeMs = 0;
      LX.log("Mountains[" + this.name + "]: fade to restart (" + why + ")");
    }

    /** NewRidge trigger: reveal the next range if idle; full field fades instead */
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
    this.frameBassGated = (e >= BASS_GATE_ENERGY);
    this.frameBassHit = this.audio.bassHit();
    this.frameSyncOn = this.sync.isOn();
    // crossed() must tick every frame, even with Sync off, so re-enabling
    // Sync doesn't misread a stale cycle count as an instant off-grid crossing
    this.frameGridCross = this.tempoLock.crossed(this.tempoDiv.getEnum()) && this.frameSyncOn;

    this.frameSinX = Math.sin(Math.toRadians(this.rotX.getValue()));
    this.frameSinZ = Math.sin(Math.toRadians(this.rotZ.getValue()));
    this.frameYawNorm = this.rotY.getValue() / 360;
    this.frameZoom = this.zoom.getValue();
    this.framePersp = this.persp.getValue();
    this.frameWire = (this.style.getEnum() == Style.WIRE);

    updateBandColors();

    this.cubeField.advance(deltaMs);
    this.cylinderField.advance(deltaMs);

    this.cubeField.repaint();
    this.cylinderField.repaint();

    // Glint trigger: linear settle from full lift back to the baseline
    this.glintLevel = Math.max(0, this.glintLevel - deltaMs / (GLINT_SECONDS * 1000));

    final double audioLift = Math.min(1, this.audio.level * LIFT_GAIN);
    final double lift = LIFT_BASE + LIFT_SPAN * Math.max(audioLift, this.glintLevel);
    this.cubeField.canvas.copyTo(Apotheneum.cube.exterior, this.colors,
      lift * this.cubeField.fadeMult, this.inverted);
    this.cylinderField.canvas.copyTo(Apotheneum.cylinder.exterior, this.colors,
      lift * this.cylinderField.fadeMult, this.inverted);

    copyCubeExterior();
    copyCylinderExterior();
  }
}
