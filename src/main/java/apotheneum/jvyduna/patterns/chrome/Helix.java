package apotheneum.jvyduna.patterns.chrome;

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

/**
 * Chrome Country finale centerpiece (~4:05): a lightning strike to earth (echoing
 * the Zot vocabulary) opening into a white DNA-like double-helix ascension that
 * twists UP the cylinder, ringed by a counter-rotating parallax starfield. Bright
 * pulses ride up the strands; as the held note sustains, the two strands UNSTRAND
 * (separate vertically) while the twist tightens, and an XOR-blended copy of the
 * strands is mixed in to develop an interference MOIRE.
 *
 * The two strands are drawn directly in cylindrical-unwrap space: a canvas column
 * IS the angular position around the ring, so a strand that advances Twist full
 * turns from bottom to top is a physically correct spiral. Two strands a half-turn
 * apart form the double helix. "Ascension" is the whole winding rotating slowly
 * around the ring over time (spin), capped so one full turn always takes >= 5 s.
 *
 * The moire copy is drawn into a separate scratch canvas with MAX blending, then
 * XOR-composited onto the main canvas — the classic interference look, without the
 * self-cancellation a directly-XORed overlapping polyline would suffer.
 *
 * Cylinder-primary; the cube ring is an optional secondary echo (Cube). Interiors
 * mirror their exteriors (copyCylinderExterior / copyCubeExterior) so the audience
 * standing inside is enclosed by the ascension.
 *
 * This is a beatless song (see chrome-brief.md): the Sync/TempoDiv pair is present
 * per series convention but is intended OFF here — the pattern is envelope- and
 * Audio-driven, and free-running is its native mode.
 *
 * See Helix.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Helix")
@LXComponent.Description("Chrome finale: a lightning strike to earth opening into a white double-helix ascension up the cylinder, ringed by a counter-rotating parallax starfield, with pulses riding the strands, unstranding separation, tightening twist and an XOR moire")
public class Helix extends ApotheneumPattern {

  // ---- Structural maxima (all state preallocated at these sizes) --------------

  private static final int STRAND_COUNT = 2;
  private static final int MAX_PULSES = 24;
  private static final int MAX_STARS = 160;
  /** Bolt column samples along the normalized height (top -> ground) */
  private static final int BOLT_SAMPLES = 64;
  /** Upper bound on helix draw sub-steps per strand per frame */
  private static final int MAX_HELIX_STEPS = 6000;

  // ---- Motion caps / envelope constants ---------------------------------------

  /** Helix spin (ascension twist) in turns/sec: ambient (e=0) .. peak (e=1).
   *  Peak 0.12 turns/s -> one full ring turn in 8.3 s; the Climb multiplier
   *  (<= 1.5) tops out at 0.18 turns/s = 5.5 s/turn, still >= the 5 s cap. */
  private static final double SPIN_TURNS_AMBIENT = 0.03;
  private static final double SPIN_TURNS_PEAK = 0.12;

  /** Pulse travel speed in normalized-height/sec: ambient .. peak. Peak 0.18
   *  -> a pulse crosses the full height in 5.5 s (>= the 5 s cap). */
  private static final double PULSE_SPEED_AMBIENT = 0.11;
  private static final double PULSE_SPEED_PEAK = 0.18;

  /** Nearest-star counter-rotation in turns/sec: ambient .. peak. Peak 0.13
   *  -> 7.7 s for the nearest layer to sweep the ring (>= 5 s). */
  private static final double STAR_TURNS_AMBIENT = 0.06;
  private static final double STAR_TURNS_PEAK = 0.13;

  /** Auto-pulse spacing (ms) at Pulses = 0 (sparse) .. 1 (dense) */
  private static final double PULSE_INTERVAL_SPARSE_MS = 2000;
  private static final double PULSE_INTERVAL_DENSE_MS = 450;

  /** Lightning strike total visual life and initial full-bright flash (ms).
   *  Life >= 1.5 s satisfies the event-like-motion minimum. */
  private static final double BOLT_LIFE_MS = 1600;
  private static final double BOLT_FLASH_MS = 140;
  /** Random-walk jaggedness of the descending bolt (normalized column units) */
  private static final double BOLT_JAG = 0.06;

  /** Max vertical HALF-separation each strand slides at Unstrnd = 1 (normalized
   *  height); total opened gap is twice this. */
  private static final double MAX_SEP_V = 0.22;

  /** Extra whole turns added to Twist as Unstrnd rises (the twist "tightens"
   *  as the strands separate) */
  private static final double UNSTRAND_EXTRA_TWIST = 2.0;

  /** Moire XOR-copy column shift, as a fraction of canvas width */
  private static final double MOIRE_SHIFT_FRAC = 0.025;

  /** Audio level bloom: exterior brightness multiplier tops out here at level 1 */
  private static final double LEVEL_BLOOM = 0.30;

  /** Treble star twinkle depth */
  private static final double TREBLE_TWINKLE = 0.5;

  /** Deep-red fallback when the palette swatch is empty (CURATE: picked blind) */
  private static final int FALLBACK_RED = LXColor.hsb(0, 90, 100);

  // ---- Parameters -------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Helix");
  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  public final TriggerParameter pulse = bag.register(
    new TriggerParameter("Pulse", this::firePulse)
    .setDescription("Inject one bright pulse riding up each strand from the base"));

  public final TriggerParameter reverse = bag.register(
    new TriggerParameter("Reverse", this::reverse)
    .setDescription("Reverse the ascension twist direction of the helix"));

  public final TriggerParameter strike = bag.register(
    new TriggerParameter("Strike", this::strike)
    .setDescription("Fire a white lightning bolt to earth — the finale-ignition event"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: scales ascension/pulse/star speed within the >=5s traversal cap");

  public final CompoundParameter climb =
    new CompoundParameter("Climb", 1.0, 0, 1.5)
    .setDescription("Ascension twist-rate multiplier (helix rotation around the ring over time)");

  public final CompoundParameter twist =
    new CompoundParameter("Twist", 3.0, 0.5, 8)
    .setDescription("Helix tightness: whole turns each strand winds around the ring over the full height");

  public final CompoundParameter unstrand =
    new CompoundParameter("Unstrnd", 0)
    .setDescription("Strand separation: 0 = paired double helix, 1 = strands slid fully apart (twist also tightens)");

  public final CompoundParameter moire =
    new CompoundParameter("Moire", 0)
    .setDescription("Strength of the XOR-blended strand copy that develops an interference moire; 0 = off");

  public final CompoundParameter stars =
    new CompoundParameter("Stars", 0.4)
    .setDescription("Parallax starfield density/brightness surrounding the helix; 0 = no stars");

  public final CompoundParameter pulses =
    new CompoundParameter("Pulses", 0.5)
    .setDescription("Auto-emitted pulse density riding the strands (free-running) or intensity on the grid (Sync)");

  public final CompoundParameter thick =
    new CompoundParameter("Thick", 1.5, 1, 3)
    .setDescription("Strand stroke thickness in raster pixels");

  public final BooleanParameter cube =
    new BooleanParameter("Cube", false)
    .setDescription("Also render the helix on the cube ring as a secondary echo (default cylinder-only)");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Rotates the palette-sampled colors (0 = pure project palette)");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final BooleanParameter sync =
    new BooleanParameter("Sync", true)
    .setDescription("Lock pulse emission to the tempo grid; off restores free-running timing (this song is beatless — leave off)");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.HALF)
    .setDescription("Tempo division pulses emit on when Sync is enabled");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire one of Helix's triggers or jump a parameter");

  // ---- Canvases (preallocated) ------------------------------------------------

  private final SurfaceCanvas cylCanvas =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT); // 120x43
  private final SurfaceCanvas cylScratch =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT);
  private final SurfaceCanvas cubeCanvas =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT); // 200x45
  private final SurfaceCanvas cubeScratch =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT);

  // ---- Simulation state (all primitive / preallocated) ------------------------

  /** Accumulated helix spin in turns; drives the ascension twist over time */
  private double spin = 0;
  private double spinDir = 1;

  /** Pulses: parallel arrays, t normalized 0 (base) .. 1 (top) along a strand */
  private final boolean[] pulseActive = new boolean[MAX_PULSES];
  private final int[] pulseStrand = new int[MAX_PULSES];
  private final double[] pulseT = new double[MAX_PULSES];

  /** Stars: normalized angular position (drifts), fixed height, parallax depth */
  private final double[] starU = new double[MAX_STARS];
  private final double[] starV = new double[MAX_STARS];
  private final double[] starDepth = new double[MAX_STARS];

  /** Lightning bolt: normalized column fraction along the height, plus envelope */
  private final double[] boltCol = new double[BOLT_SAMPLES];
  private double boltAgeMs = BOLT_LIFE_MS; // start inactive

  private double pulseTimerMs = 0;

  // ---- Palette cache (Satori-style change detection) --------------------------

  private int cachedBase = 0;
  private int cachedHueKey = Integer.MIN_VALUE;
  private int strandArgb = FALLBACK_RED;
  private int pulseArgb = 0xffffffff;
  private int starArgb = FALLBACK_RED;

  public Helix(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    // Seed the starfield once (event-rate init, not render-loop)
    for (int i = 0; i < MAX_STARS; ++i) {
      this.starU[i] = this.random.nextDouble();
      this.starV[i] = this.random.nextDouble();
      this.starDepth[i] = 0.25 + 0.75 * this.random.nextDouble();
    }
    genBolt();

    addParameter("pulse", this.pulse);
    addParameter("reverse", this.reverse);
    addParameter("strike", this.strike);
    addParameter("energy", this.energy);
    addParameter("climb", this.climb);
    addParameter("twist", this.twist);
    addParameter("unstrand", this.unstrand);
    addParameter("moire", this.moire);
    addParameter("stars", this.stars);
    addParameter("pulses", this.pulses);
    addParameter("thick", this.thick);
    addParameter("cube", this.cube);
    addParameter("hue", this.hue);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    // Meta jump candidates — mirrored 1:1 in the Jump candidates table in Helix.md
    bag.jumpable(this.twist, 1, 6);
    bag.jumpable(this.unstrand, 0, 1);
    bag.jumpable(this.moire, 0, 0.7);
    bag.jumpable(this.stars, 0.1, 0.8);
    bag.jumpable(this.climb, 0.4, 1.3);
    bag.jumpable(this.pulses, 0.2, 0.9);
  }

  // ---- Trigger handlers -------------------------------------------------------

  /** Small: one bright pulse released at the base of each strand */
  private void firePulse() {
    spawnPulse(0);
    spawnPulse(1);
  }

  /** Medium: flip the direction the helix twists as it ascends */
  private void reverse() {
    this.spinDir = -this.spinDir;
  }

  /** Large: fire the lightning bolt (regenerate geometry, reset envelope) */
  private void strike() {
    genBolt();
    this.boltAgeMs = 0;
    LX.log("Helix: strike");
  }

  private void spawnPulse(int strand) {
    int slot = -1;
    double oldest = -1;
    for (int i = 0; i < MAX_PULSES; ++i) {
      if (!this.pulseActive[i]) {
        slot = i;
        break;
      }
      if (this.pulseT[i] > oldest) {
        oldest = this.pulseT[i];
        slot = i;
      }
    }
    this.pulseActive[slot] = true;
    this.pulseStrand[slot] = strand;
    this.pulseT[slot] = 0;
  }

  /** Regenerate the descending bolt column profile (event-rate; writes in place) */
  private void genBolt() {
    final double start = 0.2 + this.random.nextDouble() * 0.6;
    final double ground = 0.2 + this.random.nextDouble() * 0.6;
    double c = start;
    for (int i = 0; i < BOLT_SAMPLES; ++i) {
      final double t = i / (double) (BOLT_SAMPLES - 1);
      final double line = start + (ground - start) * t;
      c += (this.random.nextDouble() - 0.5) * 2 * BOLT_JAG;
      c += (line - c) * 0.15; // mean-revert toward the straight strike line
      this.boltCol[i] = (c < 0) ? 0 : (c > 1) ? 1 : c;
    }
  }

  // ---- Render -----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    updatePalette();

    final double e = this.energy.getValue();

    // Ascension twist: rotate the whole winding around the ring over time (capped)
    final double spinTurnsPerSec =
      this.climb.getValue() * Ranges.lin(e, SPIN_TURNS_AMBIENT, SPIN_TURNS_PEAK);
    this.spin += this.spinDir * spinTurnsPerSec * deltaMs * 0.001;

    // Pulses ride up the strands
    final double pulseSpeed = Ranges.lin(e, PULSE_SPEED_AMBIENT, PULSE_SPEED_PEAK);
    for (int i = 0; i < MAX_PULSES; ++i) {
      if (this.pulseActive[i]) {
        this.pulseT[i] += pulseSpeed * deltaMs * 0.001;
        if (this.pulseT[i] > 1) {
          this.pulseActive[i] = false;
        }
      }
    }

    // Star parallax drift (counter to the helix twist)
    final double starTurnsPerSec = Ranges.lin(e, STAR_TURNS_AMBIENT, STAR_TURNS_PEAK);
    for (int i = 0; i < MAX_STARS; ++i) {
      this.starU[i] -= this.starDepth[i] * starTurnsPerSec * deltaMs * 0.001;
      this.starU[i] -= Math.floor(this.starU[i]);
    }

    // Pulse emission: audio hits + auto (grid-gated when Sync, else free timer).
    // crossed() is polled unconditionally every frame (see TempoLock javadoc).
    final boolean crossed = this.tempoLock.crossed(this.tempoDiv.getEnum());
    if (this.audio.bassHit()) {
      firePulse();
    }
    this.pulseTimerMs += deltaMs;
    if (this.sync.isOn()) {
      if (crossed) {
        firePulse();
      }
    } else {
      double interval = Ranges.lin(this.pulses.getValue(),
        PULSE_INTERVAL_SPARSE_MS, PULSE_INTERVAL_DENSE_MS);
      interval /= (1 + this.audio.level); // audio level thickens the stream
      if (this.pulseTimerMs >= interval) {
        this.pulseTimerMs = 0;
        firePulse();
      }
    }

    // Bolt envelope
    if (this.boltAgeMs < BOLT_LIFE_MS) {
      this.boltAgeMs += deltaMs;
    }

    // Exterior bloom on the finale swell (1.0 at silence)
    final double bloom = 1 + LEVEL_BLOOM * this.audio.level;

    renderSurface(this.cylCanvas, this.cylScratch);
    this.cylCanvas.copyTo(Apotheneum.cylinder.exterior, this.colors, bloom, false);
    copyCylinderExterior();

    if (this.cube.isOn()) {
      renderSurface(this.cubeCanvas, this.cubeScratch);
      this.cubeCanvas.copyTo(Apotheneum.cube.exterior, this.colors, bloom, false);
      copyCubeExterior();
    }
  }

  /** Compose one surface: stars behind, main helix, pulses, XOR moire copy, bolt */
  private void renderSurface(SurfaceCanvas main, SurfaceCanvas scratch) {
    main.fill(LXColor.BLACK);
    drawStars(main);
    drawHelix(main, 0, 1.0);
    drawPulses(main);

    final double m = this.moire.getValue();
    if (m > 0.01) {
      scratch.fill(LXColor.BLACK);
      drawHelix(scratch, MOIRE_SHIFT_FRAC, 1.0);
      compositeXor(main, scratch, m);
    }

    if (this.boltAgeMs < BOLT_LIFE_MS) {
      drawBolt(main);
    }
  }

  // ---- Drawing ----------------------------------------------------------------

  /** Effective whole turns each strand winds: base Twist plus the tightening
   *  contributed by Unstrnd. */
  private double effectiveTwist() {
    return this.twist.getValue() + this.unstrand.getValue() * UNSTRAND_EXTRA_TWIST;
  }

  /**
   * Draw the double helix onto a canvas. Each strand advances effectiveTwist()
   * whole turns from base to top; a canvas column is the angular position, so the
   * strand is a physically correct spiral around the ring. Strand 1 is a half turn
   * out of phase; Unstrnd slides the two apart vertically.
   */
  private void drawHelix(SurfaceCanvas c, double colShiftFrac, double brightMult) {
    final int w = c.width;
    final int h = c.height;
    final double tw = effectiveTwist();
    final double sep = this.unstrand.getValue() * MAX_SEP_V;
    final double spinRad = this.spin * 2 * Math.PI;
    final double colShift = colShiftFrac * w;
    final double r = this.thick.getValue();
    final int argb = scaleRGB(this.strandArgb, brightMult);

    int steps = (int) (1.3 * (tw * w + h)) + 8;
    if (steps > MAX_HELIX_STEPS) {
      steps = MAX_HELIX_STEPS;
    }

    for (int s = 0; s < STRAND_COUNT; ++s) {
      final double phase = s * Math.PI;
      final double yOff = (s == 0) ? sep : -sep;
      for (int i = 0; i <= steps; ++i) {
        final double t = i / (double) steps;
        final double up = t + yOff;
        if (up < 0 || up > 1) {
          continue;
        }
        final double angle = spinRad + tw * 2 * Math.PI * t + phase;
        final double colf = angle / (2 * Math.PI) * w + colShift;
        final double row = (1 - up) * (h - 1);
        plotDisc(c, colf, row, r, argb);
      }
    }
  }

  /** Bright pulses riding the strands, using the same strand geometry */
  private void drawPulses(SurfaceCanvas c) {
    final int w = c.width;
    final int h = c.height;
    final double tw = effectiveTwist();
    final double sep = this.unstrand.getValue() * MAX_SEP_V;
    final double spinRad = this.spin * 2 * Math.PI;
    final double r = this.thick.getValue() + 1;
    for (int i = 0; i < MAX_PULSES; ++i) {
      if (!this.pulseActive[i]) {
        continue;
      }
      final int s = this.pulseStrand[i];
      final double phase = s * Math.PI;
      final double yOff = (s == 0) ? sep : -sep;
      final double t = this.pulseT[i];
      final double up = t + yOff;
      if (up < 0 || up > 1) {
        continue;
      }
      // Fade in over the first tenth, out over the last tenth of the run
      final double env = Math.min(1, Math.min(t / 0.1, (1 - t) / 0.1));
      final double angle = spinRad + tw * 2 * Math.PI * t + phase;
      final double colf = angle / (2 * Math.PI) * w;
      final double row = (1 - up) * (h - 1);
      plotDisc(c, colf, row, r, scaleRGB(this.pulseArgb, env));
    }
  }

  /** Dim parallax starfield; nearer (higher depth) stars are brighter */
  private void drawStars(SurfaceCanvas c) {
    final double amount = this.stars.getValue();
    if (amount <= 0.001) {
      return;
    }
    final int w = c.width;
    final int h = c.height;
    final double twinkle = 1 + TREBLE_TWINKLE * this.audio.treble;
    for (int i = 0; i < MAX_STARS; ++i) {
      final double b = amount * this.starDepth[i] * this.starDepth[i] * twinkle;
      if (b <= 0.02) {
        continue;
      }
      final int col = (int) Math.round(this.starU[i] * w);
      final int row = (int) Math.round(this.starV[i] * (h - 1));
      c.setMax(col, row, scaleRGB(this.starArgb, b));
    }
  }

  /** Draw the descending lightning bolt with its flash/glow envelope */
  private void drawBolt(SurfaceCanvas c) {
    final int w = c.width;
    final int h = c.height;
    final double age = this.boltAgeMs;
    final double env;
    if (age < BOLT_FLASH_MS) {
      env = 1.0;
    } else {
      final double u = (age - BOLT_FLASH_MS) / (BOLT_LIFE_MS - BOLT_FLASH_MS);
      env = 0.85 * (1 - u) * (1 - u);
    }
    final int argb = scaleRGB(0xffffffff, env);
    final double r = this.thick.getValue();
    for (int y = 0; y < h; ++y) {
      final double t = y / (double) (h - 1);
      int idx = (int) Math.round(t * (BOLT_SAMPLES - 1));
      if (idx < 0) {
        idx = 0;
      } else if (idx >= BOLT_SAMPLES) {
        idx = BOLT_SAMPLES - 1;
      }
      plotDisc(c, this.boltCol[idx] * w, y, r, argb);
    }
  }

  /** XOR-composite a scratch canvas (a clean bright helix) onto main, scaled by
   *  mult — the interference moire. Same dimensions required. */
  private void compositeXor(SurfaceCanvas main, SurfaceCanvas scratch, double mult) {
    final int w = main.width;
    final int h = main.height;
    for (int y = 0; y < h; ++y) {
      for (int x = 0; x < w; ++x) {
        final int sp = scratch.get(x, y);
        if ((sp & 0x00ffffff) == 0) {
          continue;
        }
        main.setBlend(x, y, scaleRGB(sp, mult), SurfaceCanvas.Blend.XOR);
      }
    }
  }

  /** Filled disc, max-blended; x wraps, out-of-range y dropped */
  private void plotDisc(SurfaceCanvas c, double cxf, double cyf, double r, int argb) {
    final int cx = (int) Math.round(cxf);
    final int cy = (int) Math.round(cyf);
    if (r <= 0.6) {
      c.setMax(cx, cy, argb);
      return;
    }
    final int ri = (int) Math.round(r);
    for (int dy = -ri; dy <= ri; ++dy) {
      final int dxMax = (int) Math.sqrt(r * r - dy * dy);
      for (int dx = -dxMax; dx <= dxMax; ++dx) {
        c.setMax(cx + dx, cy + dy, argb);
      }
    }
  }

  // ---- Palette ----------------------------------------------------------------

  /** Refresh cached colors from the palette swatch, rebuilt only on change. */
  private void updatePalette() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int base = swatch.isEmpty() ? FALLBACK_RED : swatch.get(0).getColor();
    final int hk = (int) Math.round(this.hue.getValue());
    if ((base == this.cachedBase) && (hk == this.cachedHueKey)) {
      return;
    }
    this.cachedBase = base;
    this.cachedHueKey = hk;
    final int b2 = (hk == 0) ? base
      : LXColor.hsb((LXColor.h(base) + hk) % 360, LXColor.s(base), LXColor.b(base));
    this.strandArgb = b2;
    this.pulseArgb = whiten(b2, 0.6); // pulses read near-white, tinted by palette
    this.starArgb = b2;               // dim red stars derived from the palette
  }

  // ---- Color helpers ----------------------------------------------------------

  private static int whiten(int argb, double f) {
    final int r = (argb >> 16) & 0xff;
    final int g = (argb >> 8) & 0xff;
    final int b = argb & 0xff;
    final int wr = (int) (r + (255 - r) * f);
    final int wg = (int) (g + (255 - g) * f);
    final int wb = (int) (b + (255 - b) * f);
    return 0xff000000 | (wr << 16) | (wg << 8) | wb;
  }

  private static int scaleRGB(int argb, double mult) {
    if (mult <= 0) {
      return 0xff000000;
    }
    if (mult > 1) {
      mult = 1;
    }
    final int r = (int) (((argb >> 16) & 0xff) * mult);
    final int g = (int) (((argb >> 8) & 0xff) * mult);
    final int b = (int) ((argb & 0xff) * mult);
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }
}
