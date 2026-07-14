package apotheneum.jvyduna.patterns.bliss;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
import apotheneum.jvyduna.util.SurfaceCanvas;
import apotheneum.jvyduna.util.TempoLock;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * After Dark style fractal mountain ranges, projected as a 3D ring of terrain.
 *
 * The terrain is a ring-shaped heightfield surrounding the viewer: DEPTH_ROWS
 * concentric ranges, each a 1D midpoint-displacement ridge wrapped seamlessly
 * around the surface (cube: all four faces as one 200-column strip; cylinder:
 * its full ring), blended with its neighbor for cross-range coherence.
 *
 * A draw head phase-locked to the transport sweeps the ring continuously: one
 * full ring (all four cube sides) per SpdDiv period, so it crosses one cube
 * corner each quarter-period and, at RotY 0, the cube corners are always
 * crossed exactly on the tempo grid, regardless of when
 * drawing started (pattern init or a wipe). Ranges reveal back-to-front; once
 * the field is full the head keeps drawing, redrawing the oldest range with
 * fresh terrain as it goes — the field never auto-wipes. The Wipe trigger is
 * manual-only: the old scene fades to black over exactly one beat while
 * drawing restarts immediately underneath.
 *
 * The scene is re-projected every frame, so the camera controls are live:
 * RotX/RotZ tilt the terrain ring about the two horizontal axes, RotY orbits
 * it about the vertical axis, Zoom scales it, and Persp compresses far ranges
 * toward the horizon. Elevation color comes from Bands equal color bands
 * (base to peak) drawn from the project palette swatch and perceptually
 * spaced when the swatch is short — no fixed water/forest/rock/snow ladder —
 * with a circular BndPhase shift and an optional white Snow cap. Style is a
 * continuous ladder: solid banded fills (0), the After Dark "Frame" wireframe
 * grid (1), glowing topo dots at the grid intersections (2) — neighbors
 * crossfade via per-channel max, so the mix stays as saturated as its sources.
 * The Audio knob sets the amount by which audio level makes the drawing
 * rougher (baked per range at spawn) and taller (live).
 *
 * See Mountains.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Mountains")
@LXComponent.Description("Palette-colored fractal mountain ranges on a rotatable 3D terrain ring, After Dark style")
public class Mountains extends ApotheneumPattern {

  // ---- Timing constants (physical intent) -----------------------------------

  /** Residual brightness fraction targeted at the end of the one-beat wipe fade */
  private static final double FADE_FLOOR = 0.004;

  // ---- Geography constants ---------------------------------------------------

  /** Concentric terrain ranges in the ring (field is "full" at this many) */
  private static final int DEPTH_ROWS = 8;

  /**
   * Cross-range coherence: each new range blends this fraction of its
   * reference range's heightfield into its own, so successive ranges read as
   * one terrain rather than unrelated ridgelines. CURATE: 0.35 picked blind —
   * raise for rolling continuity, lower for independent After Dark ranges.
   */
  private static final float ROW_BLEND = 0.35f;

  /** Maximum number of equal elevation color bands the Bands knob selects */
  private static final int MAX_BANDS = 5;

  /**
   * Normalized elevation is clamped just below 1 before the circular band map,
   * so a range's tallest column (normalizeRidge pins the max to exactly 1) lands
   * in the top band (m-1) at BndPhase 0 instead of wrapping back to band 0.
   */
  private static final double BAND_U_MAX = 0.999999;

  /**
   * Smooth = 1 anti-aliases the elevation band boundaries in the solid fill
   * over +/- this many screen rows (mirrors Terraform's band AA). 0 = hard
   * band steps.
   */
  private static final double BAND_AA_MAX_ROWS = 1.25;

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
   * Dot style: halo brightness fraction splatted around each topo dot's core
   * pixel (cross-shaped, lighten-blended) so a dot reads as a glow at LED
   * pitch. CURATE: 0.35 picked blind — raise if dots read as bare pixels,
   * lower if the field smears together.
   */
  private static final double DOT_HALO = 0.35;

  /**
   * Midpoint-displacement amplitude falloff per octave, mapped from effective
   * roughness: 0.40 yields smooth rolling hills, 0.75 jagged alpine ridges.
   */
  private static final double FALLOFF_SMOOTH = 0.40;
  private static final double FALLOFF_ROUGH = 0.75;

  /**
   * Water plane level as a fraction of normalized terrain height: valleys
   * below it flatten to a flat sea at generation time.
   */
  private static final float WATER_LEVEL = 0.18f;

  // ---- Audio constants ---------------------------------------------------------

  /** Roughness added to newly spawned ranges at full audio level (Audio = 1) */
  private static final double AUDIO_ROUGH_GAIN = 0.6;

  /**
   * Live height lift at full audio level (Audio = 1): range heights scale by
   * 1 + gain * level. CURATE: 0.4 picked blind — at max Relief plus full
   * audio, peaks can clip against the canvas top; drop the gain if that
   * reads badly on hardware.
   */
  private static final double AUDIO_HEIGHT_GAIN = 0.4;

  // ---- Speed divisions ---------------------------------------------------------

  /**
   * Tempo division per full turn of drawing: the draw head sweeps a complete
   * ring (all four cube sides) in exactly one period of this division, so it
   * crosses one cube corner (90 degrees about Y) each quarter-period. Labeled
   * like Tempo.Division ("1" = one bar of 4 beats); Tempo.Division itself tops
   * out at 16 bars, so this pattern-local enum extends the ladder to 32.
   */
  public enum SpeedDiv {
    SIXTEENTH("1/16", 0.25),
    EIGHTH("1/8", 0.5),
    QUARTER("1/4", 1),
    HALF("1/2", 2),
    BAR("1", 4),
    TWO("2", 8),
    FOUR("4", 16),
    EIGHT("8", 32),
    SIXTEEN("16", 64),
    THIRTYTWO("32", 128);

    private final String label;

    /** Quarter-note beats per full ring of drawing */
    public final double beats;

    private SpeedDiv(String label, double beats) {
      this.label = label;
      this.beats = beats;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  // ---- Parameters -------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Mountains");

  // Deliberately NOT registered in the meta bag: the wipe must never fire
  // without manual intervention (a full field recycles instead of wiping)
  public final TriggerParameter wipe =
    new TriggerParameter("Wipe", this::wipeNow)
    .setDescription("Fade the scene to black over exactly one beat; drawing restarts immediately underneath (manual only)");

  public final TriggerParameter newRidge = bag.register(
    new TriggerParameter("NewRidge", this::forceRidge)
    .setDescription("Finish the current range instantly and start drawing the next"));

  public final TriggerParameter invert = bag.register(
    new TriggerParameter("Invert", this::toggleInvert)
    .setDescription("Toggle cave mode: flip the whole render so ranges hang from the top"));

  public final TriggerParameter rndTrig =
    new TriggerParameter("RndTrig", bag::fire)
    .setDescription("Randomly fire a trigger or jump a parameter");

  public final EnumParameter<SpeedDiv> spdDiv =
    new EnumParameter<SpeedDiv>("SpdDiv", SpeedDiv.BAR)
    .setDescription("Tempo division per full turn of drawing (all four cube sides); the draw head crosses one cube corner each quarter-period, on this grid");

  public final CompoundParameter roughness =
    new CompoundParameter("Rough", 0.5)
    .setDescription("Base jaggedness of newly spawned ranges (audio adds on top)");

  public final CompoundParameter relief =
    new CompoundParameter("Relief", 0.55, 0.2, 0.75)
    .setDescription("Peak height of each range as a fraction of surface height (live; audio adds on top)");

  public final DiscreteParameter bands =
    new DiscreteParameter("Bands", 4, 1, MAX_BANDS + 1)
    .setDescription("Number of equal elevation color bands from valley to peak; colors come from the palette swatch, perceptually spaced when the swatch is short (1 = monochrome)");

  public final CompoundParameter bandPhase =
    new CompoundParameter("BndPhase", 0)
    .setDescription("Circular phase shift of the elevation color bands up each range (0 = base band at the valley floor)");

  public final BooleanParameter snowCap =
    new BooleanParameter("Snow", false)
    .setDescription("Force the highest elevation band pure white (snow caps)");

  public final CompoundParameter hueShift =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Rotates the palette-derived band hues (0 = pure project palette)");

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
    new CompoundParameter("Zoom", 1, 0, 2.5)
    .setDescription("Scales range heights and spacing about the horizon (After Dark's Zoom slider)");

  public final CompoundParameter persp =
    new CompoundParameter("Persp", 0.35, 0, 1)
    .setDescription("Perspective: 0 = flat isometric spacing, 1 = far ranges compress hard toward the horizon");

  public final CompoundParameter style =
    new CompoundParameter("Style", 0, 0, 2)
    .setDescription("Render style ladder: 0 = solid banded fills, 1 = After Dark Frame wireframe, 2 = glowing topo dots; fades continuously between neighbors");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 1.0)
    .setDescription("Subpixel crests + antialiasing of forms: 0 = hard pixel-snapped edges, 1 = smooth crests and antialiased wire/dots");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Amount by which audio level makes the drawing rougher (per new range) and taller (live); 0 = pure screensaver");

  // ---- State ------------------------------------------------------------------

  private final AudioReactive audio;
  private final TempoLock tempoLock;

  /** Cave mode: mirror the render vertically so ranges hang from the top */
  private boolean inverted = false;

  // Per-frame shared values computed once in render(), always before the
  // fields' advance()/repaint() read them

  /** Draw-head position around the ring this frame, in [0, 1) */
  private double frameRingFrac;

  /** Rings advanced since the previous frame (guarded against jumps) */
  private double frameDeltaRing;

  /** Previous frame's absolute ring phase (NaN = no frame rendered yet) */
  private double lastRingPhase = Double.NaN;

  /** Style-ladder brightness gains for the three render passes */
  private double frameSolidGain = 1;
  private double frameWireGain = 0;
  private double frameDotGain = 0;

  /** Antialiasing / subpixel-crest amount this frame (0 = hard, 1 = smooth) */
  private double frameSmooth = 1;

  /** Circular band phase shift this frame (BndPhase), read once per frame */
  private double frameBandPhase = 0;

  // Per-frame camera values (render() computes; repaint() reads)
  private double frameSinX;
  private double frameSinZ;
  private double frameYawNorm;
  private double frameZoom;
  private double framePersp;

  // Elevation band colors (up to MAX_BANDS): the first min(Bands, swatch)
  // come straight from the palette swatch, any remainder are perceptually
  // spaced into the gaps (Rubik/Terraform fillCircle idiom), then Hue-shifted.
  // Recomputed every frame (zero-alloc); mBands is the active count (>= 1).
  private final int[] bandColor = new int[MAX_BANDS];
  private final float[] hueWork = new float[MAX_BANDS];
  private final float[] hueOut = new float[MAX_BANDS];
  private int mBands = 1;

  // Pattern-level view support: the renderer writes fixed Apotheneum geometry
  // into the full-model colors buffer, so when this device has a restricted
  // view (this.model), out-of-view points are cleared each frame; the mask is
  // rebuilt only when the view model changes (rare; allocation acceptable)
  private LXModel viewModel = null;
  private boolean[] viewMask = null;

  private final Field cubeField = new Field("cube", 4 * Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);
  private final Field cylinderField = new Field("cylinder", Apotheneum.RING_LENGTH, Apotheneum.CYLINDER_HEIGHT);

  public Mountains(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("wipe", this.wipe);
    addParameter("newRidge", this.newRidge);
    addParameter("invert", this.invert);
    addParameter("rndTrig", this.rndTrig);
    addParameter("spdDiv", this.spdDiv);
    addParameter("roughness", this.roughness);
    addParameter("relief", this.relief);
    addParameter("bands", this.bands);
    addParameter("bandPhase", this.bandPhase);
    addParameter("snowCap", this.snowCap);
    addParameter("hueShift", this.hueShift);
    addParameter("rotX", this.rotX);
    addParameter("rotY", this.rotY);
    addParameter("rotZ", this.rotZ);
    addParameter("zoom", this.zoom);
    addParameter("persp", this.persp);
    addParameter("style", this.style);
    addParameter("smooth", this.smooth);
    addParameter("audio", this.audioDepth);

    // Jump candidates — mirrored 1:1 in the Mountains.md table
    bag.jumpable(this.roughness, 0.2, 0.9);
    bag.jumpable(this.bands);
    bag.jumpable(this.bandPhase);
    bag.jumpable(this.hueShift);
    bag.jumpable(this.rotY);
    bag.jumpable(this.zoom, 0.8, 1.6);
  }

  // ---- Palette-driven band colors ----------------------------------------------

  /**
   * Elevation band colors from the current palette swatch: M = Bands equal
   * bands valley->peak. The first min(M, swatch) colors come straight from the
   * swatch (anchored at their perceptual hue positions); any remaining bands
   * are generated perceptually (Rubik/Terraform fillCircle idiom), so a short
   * swatch still yields M legible, well-separated hues rather than a fixed
   * water/forest/rock/snow ladder. Hue rotates the whole set; Snow forces the
   * highest band (M-1) pure white without shifting the others. M = 1 is
   * monochrome bandColor[0]. Recomputed each frame (zero allocation).
   */
  private void computeBandColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int m = LXUtils.max(1, this.bands.getValuei());
    this.mBands = m;
    final double hs = this.hueShift.getValue();

    final int defined = Math.min(m, swatch.size());
    for (int i = 0; i < defined; ++i) {
      final int c = swatch.get(i).getColor();
      this.bandColor[i] = c;
      this.hueWork[i] = PerceptualHue.toPerceptualPosition(LXColor.h(c));
    }
    final int generate = m - defined;
    if (generate > 0) {
      PerceptualHue.fillCircle(this.hueWork, defined, generate, this.hueOut);
      for (int j = 0; j < generate; ++j) {
        this.bandColor[defined + j] = PerceptualHue.color(this.hueOut[j]);
      }
    }
    if (hs != 0) {
      for (int i = 0; i < m; ++i) {
        final int c = this.bandColor[i];
        this.bandColor[i] = LXColor.hsb((LXColor.h(c) + hs) % 360, LXColor.s(c), LXColor.b(c));
      }
    }
    if (this.snowCap.isOn()) {
      this.bandColor[m - 1] = LXColor.WHITE; // snow cap on the highest band
    }
  }

  /** Smoothstep of a value clamped to [0,1]: 0 at 0, 1 at 1, zero slope at both ends */
  private static double smoothstep(double t) {
    t = LXUtils.constrain(t, 0, 1);
    return t * t * (3 - 2 * t);
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

  private final class Field {

    private final String name;
    private final int width;
    private final int height;
    private final SurfaceCanvas canvas;

    /** Snapshot of the scene at the moment of a Wipe, faded out over one beat */
    private final SurfaceCanvas snapshot;

    /**
     * Normalized (0..1) heightfield per range, wrapped in x. Each range is
     * generated at its own spawn (so per-range roughness/audio still applies)
     * and blended with a reference range for cross-range coherence.
     */
    private final float[][] terrain;

    /** Heightfield of the range currently being revealed by the draw head */
    private final float[] pending;

    /** Midpoint-displacement scratch; [width] mirrors [0] for the wrap */
    private final float[] ridge;

    /** Per-column azimuth trig for the RotX/RotZ ring tilt (fixed at build) */
    private final float[] cosAz;
    private final float[] sinAz;

    // Per-repaint scratch (preallocated; zero-alloc render)
    private final float[] rowBase = new float[DEPTH_ROWS];
    private final float[] rowScale = new float[DEPTH_ROWS];
    private final int[] rowBand = new int[MAX_BANDS];
    private final int[] colFloor;
    private float[] crestRow;
    private float[] crestPrev;
    private final int[] wireColor;

    private final Random random = new Random();

    /** Fully revealed ranges; DEPTH_ROWS = field full (then rows recycle) */
    private int revealedRows = 0;

    /** Terrain row the current reveal is drawing into */
    private int revealRow = 0;

    /** Fraction (0..1) of the ring the current range has revealed */
    private double progress = 0;

    /** First spawn happens lazily on the first advance() */
    private boolean spawned = false;

    // Wipe overlay fade (one beat, measured at trigger time)
    private boolean fading = false;
    private double fadeTotalMs = 1;
    private double fadeElapsedMs = 0;

    // Reveal arc for this repaint, in canvas columns (head + trailing length)
    private int arcHead = 0;
    private int arcLen = 0;

    private Field(String name, int width, int height) {
      this.name = name;
      this.width = width;
      this.height = height;
      this.canvas = new SurfaceCanvas(width, height);
      this.snapshot = new SurfaceCanvas(width, height);
      this.terrain = new float[DEPTH_ROWS][width];
      this.pending = new float[width];
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

    /** True once every terrain row holds a revealed range (rows now recycle) */
    private boolean isFull() {
      return this.revealedRows >= DEPTH_ROWS;
    }

    private void advance(double deltaMs) {
      if (!this.spawned) {
        this.spawned = true;
        spawn();
      }
      // Drawing is continuous: ranges chain back-to-back at the head's
      // grid-locked rate, one full ring each
      this.progress += frameDeltaRing;
      while (this.progress >= 1) {
        this.progress -= 1;
        commit();
        spawn();
      }
      if (this.fading) {
        this.fadeElapsedMs += deltaMs;
        if (this.fadeElapsedMs >= this.fadeTotalMs) {
          this.fading = false;
        }
      }
    }

    /** Complete the current reveal: bake pending into its terrain row */
    private void commit() {
      System.arraycopy(this.pending, 0, this.terrain[this.revealRow], 0, this.width);
      if (this.revealedRows < DEPTH_ROWS) {
        this.revealedRows++;
      }
      // Next target: march nearer while filling; once full, recycle rows in
      // reveal order so new terrain always replaces the stalest range
      this.revealRow = (this.revealedRows < DEPTH_ROWS)
        ? this.revealedRows : (this.revealRow + 1) % DEPTH_ROWS;
    }

    /** Generate the pending heightfield for the current revealRow */
    private void spawn() {
      // Wrap-matched endpoints, midpoint displacement, then normalize to
      // [0, 1]; Relief/Zoom/audio scale it live at projection time, but
      // roughness (plus the audio amount) is baked per range here
      final double effRoughness = LXUtils.constrain(
        roughness.getValue() + AUDIO_ROUGH_GAIN * audio.level, 0, 1);
      final float falloff = (float) (FALLOFF_SMOOTH + (FALLOFF_ROUGH - FALLOFF_SMOOTH) * effRoughness);
      this.ridge[0] = 0;
      this.ridge[this.width] = 0;
      displace(0, this.width, 1f, falloff);
      normalizeRidge();

      // Cross-range coherence: blend with the neighbor row while filling,
      // or with the row being replaced while recycling
      final float[] blendRef = isFull() ? this.terrain[this.revealRow]
        : (this.revealRow > 0) ? this.terrain[this.revealRow - 1] : null;
      for (int x = 0; x < this.width; ++x) {
        float h = (blendRef == null) ? this.ridge[x]
          : ROW_BLEND * blendRef[x] + (1 - ROW_BLEND) * this.ridge[x];
        if (h < WATER_LEVEL) {
          h = WATER_LEVEL; // water plane: valleys flatten to a flat sea
        }
        this.pending[x] = h;
      }
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

    /** True if canvas column x is inside the current reveal arc */
    private boolean covered(int x) {
      return ((this.arcHead - x + this.width) % this.width) <= this.arcLen;
    }

    /**
     * Heightfield to sample for range d at canvas column x, honoring the
     * reveal arc: the revealing row reads pending inside the arc; outside it
     * reads the old row while recycling, or nothing (null) while filling.
     */
    private float[] rowSource(int d, int x) {
      if (d == this.revealRow) {
        if (covered(x)) {
          return this.pending;
        }
        if (!isFull()) {
          return null; // still unrevealed while filling
        }
      }
      return this.terrain[d];
    }

    /**
     * Re-project and redraw the whole revealed scene for this frame. The
     * canvas is cleared and rebuilt every frame so the camera parameters
     * (RotX/RotY/RotZ/Zoom/Persp) are live; the reveal arc and wipe fade
     * remain pure state (head/progress, snapshot fade) rather than baked
     * pixels.
     */
    private void repaint() {
      this.canvas.fill(LXColor.BLACK);

      // Reveal arc in canvas columns: the head position is a pure function
      // of the transport phase, so (at RotY 0) cube corners are crossed
      // exactly on SpdDiv boundaries no matter when drawing started;
      // progress determines how much of the ring trails behind the head
      this.arcHead = (int) (frameRingFrac * this.width) % this.width;
      this.arcLen = (int) (Math.min(1, this.progress) * this.width);

      final int maxRow = isFull() ? DEPTH_ROWS - 1 : this.revealedRows;

      // Camera layout for this frame: nearest range has scale ~zoom; farther
      // ranges compress by Persp. Row baselines accumulate downward from the
      // horizon so the farthest range's peaks just clear the top margin.
      // Audio makes the whole terrain taller, live.
      final double reliefRows = relief.getValue() * this.height
        * (1 + AUDIO_HEIGHT_GAIN * audio.level);
      for (int d = 0; d < DEPTH_ROWS; ++d) {
        final double compress = (DEPTH_ROWS > 1)
          ? (DEPTH_ROWS - 1 - d) / (double) (DEPTH_ROWS - 1) : 0;
        this.rowScale[d] = (float) (frameZoom / (1 + framePersp * PERSP_GAIN * compress));
      }
      this.rowBase[0] = (float) (TOP_MARGIN_ROWS + reliefRows * this.rowScale[0]);
      for (int d = 1; d < DEPTH_ROWS; ++d) {
        this.rowBase[d] = this.rowBase[d - 1] + (float) (ROW_SPACING_ROWS * this.rowScale[d]);
      }

      final double yawOffset = frameYawNorm * this.width;

      // Style mix: dimmed solid fills first (painter's algorithm), then the
      // wireframe grid lightened over them — per-channel max keeps the mix
      // as saturated as its sources (no additive pastel washout)
      if (frameSolidGain > 0) {
        Arrays.fill(this.colFloor, this.height);
        for (int d = maxRow; d >= 0; --d) {
          paintSolidRow(d, reliefRows, yawOffset);
        }
      }
      if (frameWireGain > 0) {
        for (int d = 0; d <= maxRow; ++d) {
          paintWireRow(d, reliefRows, yawOffset);
        }
      }
      if (frameDotGain > 0) {
        for (int d = 0; d <= maxRow; ++d) {
          paintDotRow(d, reliefRows, yawOffset);
        }
      }

      // Wipe overlay: the old scene fades to black under the fresh drawing
      if (this.fading) {
        this.canvas.maxFrom(this.snapshot,
          Math.pow(FADE_FLOOR, this.fadeElapsedMs / this.fadeTotalMs));
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

    /** Terrain height of a range at fractional terrain column xs (wrap + lerp) */
    private double sampleHeight(float[] range, double xs) {
      final int j0 = ((int) Math.floor(xs)) % this.width;
      final int j1 = (j0 + 1 == this.width) ? 0 : j0 + 1;
      final double f = xs - Math.floor(xs);
      return range[j0] * (1 - f) + range[j1] * f;
    }

    /**
     * Depth-hazed color of the band containing normalized elevation u in [0,1]
     * (crest bands for wire/dots — no AA, since a crest sits at one elevation).
     * mBands equal bands with a circular BndPhase shift.
     */
    private int crestBandColor(double u) {
      double pos = Math.min(u, BAND_U_MAX) + frameBandPhase;
      pos -= Math.floor(pos);
      int idx = (int) (pos * mBands);
      if (idx >= mBands) {
        idx = mBands - 1;
      }
      return this.rowBand[idx];
    }

    /**
     * Depth-hazed color for a solid-fill pixel at normalized elevation u in
     * [0,1], with the band boundaries anti-aliased over frameSmooth screen rows
     * (Terraform's band-AA idiom). hAmpRows is the range's relief in screen
     * rows, so one band spans hAmpRows / mBands rows.
     */
    private int bandColorAt(double u, double hAmpRows) {
      final int m = mBands;
      double pos = Math.min(u, BAND_U_MAX) + frameBandPhase;
      pos -= Math.floor(pos);
      final double b = pos * m;
      int idx = (int) b;
      if (idx >= m) {
        idx = m - 1;
      }
      final int base = this.rowBand[idx];
      final double hw = frameSmooth * BAND_AA_MAX_ROWS;
      if ((hw <= 0) || (m <= 1)) {
        return base;
      }
      // Blend toward the nearer band boundary, measured in screen rows
      final double fb = b - idx;
      final double rowsPerBand = hAmpRows / m;
      final double dUp = (1 - fb) * rowsPerBand;
      final double dDown = fb * rowsPerBand;
      if (dUp <= dDown) {
        final double w = 0.5 * (1 - smoothstep(dUp / hw));
        return LXColor.lerp(base, this.rowBand[(idx + 1) % m], (float) w);
      }
      final double w = 0.5 * (1 - smoothstep(dDown / hw));
      return LXColor.lerp(base, this.rowBand[(idx - 1 + m) % m], (float) w);
    }

    /**
     * Solid style: fill range d from its crest downward, front-to-back with a
     * per-column watermark (colFloor) so nearer ranges occlude farther ones
     * with zero overdraw — the painter's-algorithm equivalent of the old
     * permanent column commits.
     */
    private void paintSolidRow(int d, double reliefRows, double yawOffset) {
      final double s = this.rowScale[d];
      final double hAmp = reliefRows * s;
      final double bright = rowBrightness(d) * frameSolidGain;
      for (int i = 0; i < mBands; ++i) {
        this.rowBand[i] = scaleRgb(bandColor[i], bright);
      }
      final double invAmp = (hAmp > 1e-6) ? 1.0 / hAmp : 0;

      for (int x = 0; x < this.width; ++x) {
        final float[] src = rowSource(d, x);
        if (src == null) {
          continue;
        }
        final double xs = x + yawOffset;
        final double baseY = this.rowBase[d] + tilt(d, x);
        final double crestF = baseY - sampleHeight(src, xs) * hAmp;
        final int crest = (int) Math.round(crestF);
        final int top = Math.max(0, crest);
        final int floorY = this.colFloor[x];
        if (top < floorY) {
          for (int y = top; y < floorY; ++y) {
            // Normalized elevation of this pixel within the range's relief,
            // colored by equal band (base at the valley, peak at the crest)
            final double u = LXUtils.constrain((baseY - y) * invAmp, 0, 1);
            this.canvas.set(x, y, bandColorAt(u, hAmp));
          }
          this.colFloor[x] = top;
          // Subpixel crest feather (Smooth): when the true edge sits above the
          // rounded top, brighten the pixel just above by the uncovered
          // fraction, so vertical crest motion reads smooth against the sky.
          // setMax only ever lightens, so an even-nearer range drawn earlier
          // is never darkened by a farther range's fringe.
          final double above = crest - crestF;
          if ((frameSmooth > 0) && (above > 0) && (top > 0)) {
            final double u = LXUtils.constrain((baseY - top) * invAmp, 0, 1);
            this.canvas.setMax(x, top - 1, scaleRgb(bandColorAt(u, hAmp), above * frameSmooth));
          }
        }
      }
    }

    /**
     * Wire style (After Dark "Frame"): crest polyline per range plus radial
     * connectors to the previous range every WIRE_COL_STEP columns — the dry
     * generating grid, no hidden-line removal, colored by crest band. Drawn
     * with lighten blending so mixed-style grids glow over the solid fills.
     */
    private void paintWireRow(int d, double reliefRows, double yawOffset) {
      final double s = this.rowScale[d];
      final double hAmp = reliefRows * s;
      final double bright = rowBrightness(d) * frameWireGain;
      for (int i = 0; i < mBands; ++i) {
        this.rowBand[i] = scaleRgb(bandColor[i], bright);
      }

      for (int x = 0; x < this.width; ++x) {
        final float[] src = rowSource(d, x);
        if (src == null) {
          this.crestRow[x] = Float.NaN;
          continue;
        }
        final double xs = x + yawOffset;
        final double hN = sampleHeight(src, xs);
        this.crestRow[x] = (float) (this.rowBase[d] + tilt(d, x) - hN * hAmp);
        this.wireColor[x] = crestBandColor(hN);
      }

      for (int x = 0; x < this.width; ++x) {
        final float cur = this.crestRow[x];
        if (Float.isNaN(cur)) {
          continue;
        }
        // Ring polyline segment to the next column (wraps at the seam).
        // Wu antialiasing with the Smooth dial: at Smooth 1 the fractional
        // crests draw as soft subpixel strokes, at Smooth 0 the coverage
        // hardens back to aliased full-brightness pixels.
        final float next = this.crestRow[(x + 1 == this.width) ? 0 : x + 1];
        if (!Float.isNaN(next)) {
          this.canvas.lineWu(x, cur, x + 1, next, this.wireColor[x], true, frameSmooth);
        }
        // Radial connector to the previous (farther) range's crest
        if ((d > 0) && (x % WIRE_COL_STEP == 0)) {
          final float prev = this.crestPrev[x];
          if (!Float.isNaN(prev)) {
            this.canvas.lineWu(x, prev, x, cur, this.wireColor[x], true, frameSmooth);
          }
        }
      }

      // This range's crests become the next (nearer) range's connector anchors
      final float[] swap = this.crestPrev;
      this.crestPrev = this.crestRow;
      this.crestRow = swap;
    }

    /**
     * Dot style: a dot at each topo grid intersection — the crest of each range
     * at every WIRE_COL_STEP-th column, exactly where the wire grid's ring
     * polylines cross its radial connectors — colored by crest band, no
     * hidden-point removal (matching Wire).
     *
     * <p>The core is ALWAYS subpixel-split across the two straddling rows,
     * independent of Smooth, so the dot glides continuously as the ridge moves
     * (RotX/RotZ, drawing) rather than snapping to the pixel grid. Smooth
     * controls only the glow: at Smooth 0 the dot is a crisp point with no
     * bleed into neighboring pixels, and the DOT_HALO cross fades in with
     * Smooth up to a full lighten-blended bloom at Smooth 1 (variable glow).
     */
    private void paintDotRow(int d, double reliefRows, double yawOffset) {
      final double s = this.rowScale[d];
      final double hAmp = reliefRows * s;
      final double bright = rowBrightness(d) * frameDotGain;
      for (int i = 0; i < mBands; ++i) {
        this.rowBand[i] = scaleRgb(bandColor[i], bright);
      }

      for (int x = 0; x < this.width; x += WIRE_COL_STEP) {
        final float[] src = rowSource(d, x);
        if (src == null) {
          continue;
        }
        final double xs = x + yawOffset;
        final double hN = sampleHeight(src, xs);
        final double yf = this.rowBase[d] + tilt(d, x) - hN * hAmp;
        final int yLo = (int) Math.floor(yf);
        final double frac = yf - yLo;
        final int color = crestBandColor(hN);
        // Core: subpixel position interpolation, always on (independent of
        // Smooth) so ridge motion reads continuous, not pixel-quantized.
        final double covLo = 1 - frac;
        final double covHi = frac;
        this.canvas.setMax(x, yLo, scaleRgb(color, covLo));
        this.canvas.setMax(x, yLo + 1, scaleRgb(color, covHi));
        // Glow halo: variable with Smooth. Smooth 0 = none (no glare into
        // neighbors); Smooth 1 = full DOT_HALO cross around the brighter core.
        if (frameSmooth > 0) {
          final int yc = (covHi >= covLo) ? yLo + 1 : yLo;
          final int halo = scaleRgb(color, DOT_HALO * frameSmooth);
          this.canvas.setMax(x - 1, yc, halo);
          this.canvas.setMax(x + 1, yc, halo);
          this.canvas.setMax(x, yc - 1, halo);
          this.canvas.setMax(x, yc + 1, halo);
        }
      }
    }

    /** Manual wipe: snapshot the scene to fade over one beat, restart drawing now */
    private void wipe(double fadeMs) {
      this.snapshot.copyFrom(this.canvas);
      this.fading = true;
      this.fadeTotalMs = Math.max(1, fadeMs);
      this.fadeElapsedMs = 0;
      this.revealedRows = 0;
      this.revealRow = 0;
      this.progress = 0;
      this.spawned = true;
      spawn();
      LX.log("Mountains[" + this.name + "]: wipe — one-beat fade, drawing restarted");
    }

    /** NewRidge: complete the current range instantly, start the next at the head */
    private void force() {
      if (!this.spawned) {
        return;
      }
      commit();
      spawn();
      this.progress = 0;
    }
  }

  // ---- Trigger handlers --------------------------------------------------------

  private void wipeNow() {
    // Exactly one beat, measured at trigger time (a later BPM change does
    // not retime a fade already in flight)
    final double beatMs = this.tempoLock.beatPeriodMs();
    this.cubeField.wipe(beatMs);
    this.cylinderField.wipe(beatMs);
  }

  private void forceRidge() {
    this.cubeField.force();
    this.cylinderField.force();
  }

  private void toggleInvert() {
    this.inverted = !this.inverted;
    LX.log("Mountains: invert -> " + (this.inverted ? "cave (hanging)" : "normal (rising)"));
  }

  // ---- Render --------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);

    // Draw-head phase: one full ring per SpdDiv period (a cube side each
    // quarter-period), phase-locked to the transport's absolute beat position
    // — corners land on the grid. The per-frame delta is guarded so a
    // transport restart or a SpdDiv change cannot instantly complete (or
    // rewind) reveals; the head itself simply jumps to its new grid-true
    // position.
    final double ringPhase = this.tempoLock.beatPosition() / this.spdDiv.getEnum().beats;
    double delta = ringPhase - this.lastRingPhase;
    if (!(delta >= 0) || (delta > 0.5)) {
      delta = 0; // first frame, transport restart, or division jump
    }
    this.lastRingPhase = ringPhase;
    this.frameDeltaRing = delta;
    this.frameRingFrac = ringPhase - Math.floor(ringPhase);

    // Style ladder: solid fades out over 0..1, wire peaks at 1 and fades
    // toward both neighbors, dots fade in over 1..2 — all lighten-composited
    final double mix = LXUtils.constrain(this.style.getValue(), 0, 2);
    this.frameSolidGain = Math.max(0, 1 - mix);
    this.frameWireGain = 1 - Math.min(1, Math.abs(mix - 1));
    this.frameDotGain = Math.max(0, mix - 1);

    this.frameSinX = Math.sin(Math.toRadians(this.rotX.getValue()));
    this.frameSinZ = Math.sin(Math.toRadians(this.rotZ.getValue()));
    this.frameYawNorm = this.rotY.getValue() / 360;
    this.frameZoom = this.zoom.getValue();
    this.framePersp = this.persp.getValue();
    this.frameSmooth = LXUtils.constrain(this.smooth.getValue(), 0, 1);
    this.frameBandPhase = this.bandPhase.getValue();

    computeBandColors();

    this.cubeField.advance(deltaMs);
    this.cylinderField.advance(deltaMs);

    this.cubeField.repaint();
    this.cylinderField.repaint();

    this.cubeField.canvas.copyTo(Apotheneum.cube.exterior, this.colors, 1, this.inverted);
    this.cylinderField.canvas.copyTo(Apotheneum.cylinder.exterior, this.colors, 1, this.inverted);

    copyCubeExterior();
    copyCylinderExterior();

    applyViewMask();
  }

  /**
   * Respect a pattern-level view. The renderer writes fixed Apotheneum
   * geometry into the full-model colors buffer, so a restricted device view
   * (this.model, set by the engine before run()) would otherwise be ignored:
   * channel-level views are clipped by the mixer when the channel blends,
   * but nothing clips a pattern's own writes in playlist compositing.
   * Clearing out-of-view points to transparent (not black) lets lower layers
   * show through, matching how view-aware patterns behave.
   */
  private void applyViewMask() {
    final LXModel m = this.model;
    if (m != this.viewModel) {
      this.viewModel = m;
      if (m.size >= this.colors.length) {
        this.viewMask = null; // full model: no masking needed
      } else {
        if ((this.viewMask == null) || (this.viewMask.length != this.colors.length)) {
          this.viewMask = new boolean[this.colors.length];
        } else {
          Arrays.fill(this.viewMask, false);
        }
        // View models clone points but preserve their master-buffer indices
        for (LXPoint p : m.points) {
          this.viewMask[p.index] = true;
        }
      }
    }
    if (this.viewMask != null) {
      for (int i = 0; i < this.colors.length; ++i) {
        if (!this.viewMask[i]) {
          this.colors[i] = 0;
        }
      }
    }
  }
}
