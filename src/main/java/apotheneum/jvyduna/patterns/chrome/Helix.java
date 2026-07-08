package apotheneum.jvyduna.patterns.chrome;

import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.LXLayer;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.CompoundParameter;

/**
 * Chrome Country centerpiece: a triple-helix ascension twisting UP the cylinder,
 * ringed by a counter-rotating parallax starfield, with bright pulses riding the
 * strands and a swarm of electrons spiralling up the strand paths. As the held
 * note sustains the strands UNSTRAND (fan apart in angle and slide apart
 * vertically) while the twist tightens, and an XOR-blended copy develops an
 * interference MOIRE.
 *
 * The strands are drawn directly in cylindrical-unwrap space: a canvas column IS
 * the angular position around the ring, so a strand that advances Twist full
 * turns from bottom to top is a physically correct spiral. All three strands
 * coincide exactly when Unstrnd = 0 and fan into an evenly-spaced trefoil as it
 * rises. "Ascension" is the whole winding rotating slowly around the ring over
 * time (spin); signed Climb sets both the rate and the direction.
 *
 * Cylinder-only. The interior mirrors the exterior (copyCylinderExterior) so the
 * audience standing inside is enclosed by the ascension.
 *
 * Beatless song (see chrome-brief.md): envelope- and Audio-driven; all timing
 * free-runs. The electron swarm uses the LXLayer-per-entity idiom (one Electron
 * layer each), composited in afterLayers().
 *
 * See Helix.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Helix")
@LXComponent.Description("Chrome: a triple-helix ascension up the cylinder, ringed by a counter-rotating parallax starfield, with pulses riding the strands, an electron swarm spiralling up them, unstranding separation, tightening twist and an XOR moire")
public class Helix extends ApotheneumPattern {

  // ---- Structural maxima (all state preallocated at these sizes) --------------

  private static final int STRAND_COUNT = 3;
  private static final int MAX_PULSES = 24;
  private static final int MAX_STARS = 160;
  private static final int MAX_ELECTRONS = 48;
  /** Upper bound on helix draw sub-steps per strand per frame */
  private static final int MAX_HELIX_STEPS = 6000;

  // ---- Motion caps / envelope constants ---------------------------------------

  /** Helix spin (ascension twist) in turns/sec: ambient (Speed=0) .. peak (=1),
   *  before the signed Climb multiplier. Climb's wide/signed range intentionally
   *  relaxes the >=5s traversal cap (Chrome is the per-song tempo outlier). */
  private static final double SPIN_TURNS_AMBIENT = 0.03;
  private static final double SPIN_TURNS_PEAK = 0.12;

  /** Pulse travel speed in normalized-height/sec at PlsSpd 0 .. 1. CURATE. */
  private static final double PULSE_SPEED_MIN = 0.05;
  private static final double PULSE_SPEED_MAX = 0.50;

  /** Nearest-star counter-rotation in turns/sec: ambient .. peak. */
  private static final double STAR_TURNS_AMBIENT = 0.06;
  private static final double STAR_TURNS_PEAK = 0.13;

  /** Auto-pulse spacing (ms) at Pulses = 0 (sparse) .. 1 (dense) */
  private static final double PULSE_INTERVAL_SPARSE_MS = 2000;
  private static final double PULSE_INTERVAL_DENSE_MS = 450;

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

  /** Floor on the live beat period (ms) for the electron death timer */
  private static final double MIN_BEAT_MS = 50;

  // ---- Electron swarm tunables (CURATE: first-guess screensaver values) -------

  private static final double ELECTRON_BIRTH_PER_SEC_MAX = 12;
  private static final double ELECTRON_CLIMB_MIN = 0.08; // norm-height/sec
  private static final double ELECTRON_CLIMB_MAX = 0.35;
  private static final double ELECTRON_ORBIT_MIN = 1.5;  // rad/sec
  private static final double ELECTRON_ORBIT_MAX = 4.0;
  private static final double ELECTRON_ORBIT_AMP = 3.0;  // columns
  private static final double ELECTRON_ORBIT_BOB = 0.02; // normalized height
  private static final double ELECTRON_RADIUS = 1.0;     // raster px
  /** Fade time-constant, in beats, toward the Electro target (death within ~1 beat) */
  private static final double ELECTRON_FADE_BEATS = 0.4;
  private static final double ELECTRON_DEATH_EPS = 0.03;

  /** Fallbacks when the palette swatch is short (CURATE: picked blind) */
  private static final int FALLBACK_STRAND = LXColor.hsb(0, 90, 100);   // deep red
  private static final int FALLBACK_ELECTRON = LXColor.hsb(190, 80, 100); // cyan

  // ---- Parameters -------------------------------------------------------------

  private final AudioReactive audio;
  private final Random random = new Random();

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 0.35)
    .setDescription("Master rate for the ascension spin and star drift");

  public final CompoundParameter climb =
    new CompoundParameter("Climb", 1.0, -5, 5)
    .setDescription("Signed ascension twist-rate: sign sets direction, magnitude the rate (wide; cap relaxed)");

  public final CompoundParameter twist =
    new CompoundParameter("Twist", 3.0, 0, 8)
    .setDescription("Helix tightness: whole turns each strand winds around the ring (0 = vertical)");

  public final CompoundParameter unstrand =
    new CompoundParameter("Unstrnd", 0)
    .setDescription("Strand separation: 0 = all three converged, 1 = fanned into a trefoil and slid apart");

  public final CompoundParameter moire =
    new CompoundParameter("Moire", 0)
    .setDescription("Strength of the XOR-blended strand copy that develops an interference moire; 0 = off");

  public final CompoundParameter stars =
    new CompoundParameter("Stars", 0.4)
    .setDescription("Parallax starfield density/brightness surrounding the helix; 0 = no stars");

  public final CompoundParameter pulses =
    new CompoundParameter("Pulses", 0.5)
    .setDescription("Auto-emitted pulse density (inverse spacing) riding the strands");

  public final CompoundParameter plsSpd =
    new CompoundParameter("PlsSpd", 0.4)
    .setDescription("Pulse travel speed up the strands");

  public final CompoundParameter electro =
    new CompoundParameter("Electro", 0.3)
    .setDescription("Electron swarm: 0 = all die within one beat, 1 = more, faster and brighter electrons spiralling up the strands");

  public final CompoundParameter thick =
    new CompoundParameter("Thick", 1.5, 1, 3)
    .setDescription("Strand stroke thickness in raster pixels");

  public final CompoundParameter desat =
    new CompoundParameter("Desat", 0)
    .setDescription("Desaturates the palette-sampled colors toward white; 0 = pure palette");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 1.0)
    .setDescription("Motion blending + antialiasing: 0 = steppy/pixel-snapped/hard edges, 1 = smooth sub-pixel motion and antialiased forms");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  // ---- Canvases (preallocated) ------------------------------------------------

  private final SurfaceCanvas cylCanvas =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT); // 120x43
  private final SurfaceCanvas cylScratch =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT);

  // ---- Simulation state (all primitive / preallocated) ------------------------

  /** Accumulated helix spin in turns; drives the ascension twist over time */
  private double spin = 0;

  /** Pulses: parallel arrays, t normalized 0 (base) .. 1 (top) along a strand */
  private final boolean[] pulseActive = new boolean[MAX_PULSES];
  private final int[] pulseStrand = new int[MAX_PULSES];
  private final double[] pulseT = new double[MAX_PULSES];

  /** Stars: normalized angular position (drifts), fixed height, parallax depth */
  private final double[] starU = new double[MAX_STARS];
  private final double[] starV = new double[MAX_STARS];
  private final double[] starDepth = new double[MAX_STARS];

  private double pulseTimerMs = 0;
  private double electronTimerMs = 0;

  /** Per-frame cached Smooth amount (AA / sub-pixel strength), set in render() */
  private double smoothAmt = 1.0;

  /** Audio bloom multiplier, stashed in render() for the afterLayers() blit */
  private double bloom = 1;

  // ---- Palette cache (Satori-style change detection) --------------------------

  private int cachedHash = Integer.MIN_VALUE;
  private int strandArgb = FALLBACK_STRAND;
  private int pulseArgb = 0xffffffff;
  private int starArgb = FALLBACK_STRAND;
  private int electronArgb = FALLBACK_ELECTRON;

  public Helix(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);

    // Seed the starfield once (event-rate init, not render-loop)
    for (int i = 0; i < MAX_STARS; ++i) {
      this.starU[i] = this.random.nextDouble();
      this.starV[i] = this.random.nextDouble();
      this.starDepth[i] = 0.25 + 0.75 * this.random.nextDouble();
    }

    addParameter("speed", this.speed);
    addParameter("climb", this.climb);
    addParameter("twist", this.twist);
    addParameter("unstrand", this.unstrand);
    addParameter("moire", this.moire);
    addParameter("stars", this.stars);
    addParameter("pulses", this.pulses);
    addParameter("plsSpd", this.plsSpd);
    addParameter("electro", this.electro);
    addParameter("thick", this.thick);
    addParameter("desat", this.desat);
    addParameter("smooth", this.smooth);
    addParameter("audio", this.audioDepth);
  }

  // ---- Pulse emission ---------------------------------------------------------

  /** One bright pulse released at the base of each strand */
  private void firePulse() {
    for (int s = 0; s < STRAND_COUNT; ++s) {
      spawnPulse(s);
    }
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

  // ---- Render -----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    updatePalette();

    final double e = this.speed.getValue();
    this.smoothAmt = this.smooth.getValue();

    // Ascension twist: signed Climb sets both rate and direction
    final double spinTurnsPerSec =
      this.climb.getValue() * Ranges.lin(e, SPIN_TURNS_AMBIENT, SPIN_TURNS_PEAK);
    this.spin += spinTurnsPerSec * deltaMs * 0.001;

    // Pulses ride up the strands at the dedicated PlsSpd rate
    final double pulseSpeed =
      Ranges.lin(this.plsSpd.getValue(), PULSE_SPEED_MIN, PULSE_SPEED_MAX);
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

    // Pulse emission: audio hits + a free-running density timer (Pulses = density)
    if (this.audio.bassHit()) {
      firePulse();
    }
    this.pulseTimerMs += deltaMs;
    final double interval = Ranges.lin(this.pulses.getValue(),
      PULSE_INTERVAL_SPARSE_MS, PULSE_INTERVAL_DENSE_MS);
    if (this.pulseTimerMs >= interval) {
      this.pulseTimerMs = 0;
      firePulse();
    }

    // Electron births (the swarm itself is drawn by the Electron layers)
    spawnElectrons(deltaMs);

    // Exterior bloom on the finale swell (1.0 at silence), used in afterLayers()
    this.bloom = 1 + LEVEL_BLOOM * this.audio.level;

    // Draw stars + helix + pulses + moire into the canvas. Electron layers add
    // themselves into the same canvas next, then afterLayers() blits + mirrors.
    renderSurface(this.cylCanvas, this.cylScratch);
  }

  @Override
  protected void afterLayers(double deltaMs) {
    this.cylCanvas.copyTo(Apotheneum.cylinder.exterior, this.colors, this.bloom, false);
    copyCylinderExterior();
  }

  /** Compose one surface: stars behind, main helix, pulses, XOR moire copy */
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
  }

  // ---- Strand geometry --------------------------------------------------------

  /** Effective whole turns each strand winds: base Twist plus the tightening
   *  contributed by Unstrnd. */
  private double effectiveTwist() {
    return this.twist.getValue() + this.unstrand.getValue() * UNSTRAND_EXTRA_TWIST;
  }

  /** Angular phase of strand s. At Unstrnd = 0 all strands share phase 0
   *  (converged); at Unstrnd = 1 they are evenly spaced around the ring. */
  private double strandPhase(int s) {
    return this.unstrand.getValue() * s * (2 * Math.PI / STRAND_COUNT);
  }

  /** Vertical offset of strand s (normalized height). Centered on the middle
   *  strand and zero for all when Unstrnd = 0. */
  private double strandYOff(int s) {
    return this.unstrand.getValue() * (s - 1) * MAX_SEP_V;
  }

  // ---- Drawing ----------------------------------------------------------------

  /**
   * Draw the helix onto a canvas. Each strand advances effectiveTwist() whole
   * turns from base to top; a canvas column is the angular position, so the
   * strand is a physically correct spiral around the ring. Strands fan apart in
   * phase and slide apart vertically as Unstrnd rises, and coincide exactly at 0.
   */
  private void drawHelix(SurfaceCanvas c, double colShiftFrac, double brightMult) {
    final int w = c.width;
    final int h = c.height;
    final double tw = effectiveTwist();
    final double spinRad = this.spin * 2 * Math.PI;
    final double colShift = colShiftFrac * w;
    final double r = this.thick.getValue();
    final int argb = scaleRGB(this.strandArgb, brightMult);

    int steps = (int) (1.3 * (tw * w + h)) + 8;
    if (steps > MAX_HELIX_STEPS) {
      steps = MAX_HELIX_STEPS;
    }

    for (int s = 0; s < STRAND_COUNT; ++s) {
      final double phase = strandPhase(s);
      final double yOff = strandYOff(s);
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
    final double spinRad = this.spin * 2 * Math.PI;
    final double r = this.thick.getValue() + 1;
    for (int i = 0; i < MAX_PULSES; ++i) {
      if (!this.pulseActive[i]) {
        continue;
      }
      final int s = this.pulseStrand[i];
      final double phase = strandPhase(s);
      final double yOff = strandYOff(s);
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

  /** Dim parallax starfield; nearer (higher depth) stars are brighter. The
   *  bilinear sub-pixel splat is gated by Smooth (sm=0 collapses to the floor
   *  pixel), giving continuous drift with no rasterizer switch. */
  private void drawStars(SurfaceCanvas c) {
    final double amount = this.stars.getValue();
    if (amount <= 0.001) {
      return;
    }
    final int w = c.width;
    final int h = c.height;
    final double twinkle = 1 + TREBLE_TWINKLE * this.audio.treble;
    final double sm = this.smoothAmt;
    for (int i = 0; i < MAX_STARS; ++i) {
      final double b = amount * this.starDepth[i] * this.starDepth[i] * twinkle;
      if (b <= 0.02) {
        continue;
      }
      final double fx = this.starU[i] * w;
      final double fy = this.starV[i] * (h - 1);
      final int ix = (int) Math.floor(fx);
      final int iy = (int) Math.floor(fy);
      final double tx = (fx - ix) * sm;
      final double ty = (fy - iy) * sm;
      c.setMax(ix,     iy,     scaleRGB(this.starArgb, b * (1 - tx) * (1 - ty)));
      c.setMax(ix + 1, iy,     scaleRGB(this.starArgb, b * tx * (1 - ty)));
      c.setMax(ix,     iy + 1, scaleRGB(this.starArgb, b * (1 - tx) * ty));
      c.setMax(ix + 1, iy + 1, scaleRGB(this.starArgb, b * tx * ty));
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

  /**
   * Filled disc, max-blended; x wraps, out-of-range y dropped. A single
   * continuous formulation across the whole Smooth (cached in {@link #smoothAmt})
   * range — no rasterizer switch, so there is no jump leaving Smooth = 0:
   *   - the center is snapped to the nearest pixel at Smooth = 0 and interpolated
   *     toward the true sub-pixel position as Smooth rises;
   *   - the radius is a constant float {@code r} for all Smooth (the old integer
   *     radius at Smooth 0 was the source of the thickness jump);
   *   - the edge half-width grows continuously from 0.5 px (near-hard) to 1 px,
   *     coverage-scaled via scaleRGB. CURATE: the 0.5..1 px half-width endpoints.
   * Zero heap allocation.
   */
  private void plotDisc(SurfaceCanvas c, double cxf, double cyf, double r, int argb) {
    final double sm = this.smoothAmt;
    final double rx = Math.round(cxf);
    final double ry = Math.round(cyf);
    final double cx = rx + (cxf - rx) * sm; // snapped at Smooth 0, true at 1
    final double cy = ry + (cyf - ry) * sm;
    final double hw = 0.5 + 0.5 * sm;       // CURATE: edge half-width in px
    final double outer = r + hw;
    final double denom = 2 * hw;
    final int x0 = (int) Math.floor(cx - outer);
    final int x1 = (int) Math.ceil(cx + outer);
    final int y0 = (int) Math.floor(cy - outer);
    final int y1 = (int) Math.ceil(cy + outer);
    for (int py = y0; py <= y1; ++py) {
      final double ddy = py - cy;
      for (int px = x0; px <= x1; ++px) {
        final double ddx = px - cx;
        final double d = Math.sqrt(ddx * ddx + ddy * ddy);
        final double cov = (outer - d) / denom;
        if (cov <= 0) {
          continue;
        }
        c.setMax(px, py, scaleRGB(argb, (cov >= 1) ? 1.0 : cov));
      }
    }
  }

  // ---- Electron swarm ---------------------------------------------------------

  /** Spawn new electrons on a free-running timer scaled by Electro (0 = none),
   *  capped at MAX_ELECTRONS. Deaths happen inside each Electron layer. */
  private void spawnElectrons(double deltaMs) {
    final double birthPerSec = Ranges.lin(this.electro.getValue(), 0, ELECTRON_BIRTH_PER_SEC_MAX);
    if (birthPerSec <= 0.001) {
      this.electronTimerMs = 0;
      return;
    }
    this.electronTimerMs += deltaMs;
    final double interval = 1000.0 / birthPerSec;
    int projected = getLayers().size();
    while (this.electronTimerMs >= interval) {
      this.electronTimerMs -= interval;
      if (projected < MAX_ELECTRONS) {
        addLayer(new Electron(this.lx));
        ++projected;
      }
    }
  }

  /**
   * One electron: it climbs a strand while circling around the strand path, and
   * fades toward the Electro level. At Electro = 0 the fade target is 0, so it
   * dies within ~1 beat (ELECTRON_FADE_BEATS · beat period). Draws itself into
   * the shared cylinder canvas so it inherits the AA / Smooth / wrap / bloom.
   */
  private class Electron extends LXLayer {

    private final int strand;
    private final double orbitSpeed; // rad/sec
    private final double orbitAmp;   // columns
    private final double orbitBob;   // normalized height
    private final double climbJit;   // -0.3 .. 0.3
    private double height;           // 0 (base) .. 1 (top) along the strand
    private double orbitPhase;
    private double fade;             // 0 .. 1 brightness envelope

    private Electron(LX lx) {
      super(lx);
      final Random rnd = Helix.this.random;
      this.strand = rnd.nextInt(STRAND_COUNT);
      this.orbitSpeed = ELECTRON_ORBIT_MIN + (ELECTRON_ORBIT_MAX - ELECTRON_ORBIT_MIN) * rnd.nextDouble();
      this.orbitAmp = ELECTRON_ORBIT_AMP * (0.6 + 0.8 * rnd.nextDouble());
      this.orbitBob = ELECTRON_ORBIT_BOB * (0.6 + 0.8 * rnd.nextDouble());
      this.climbJit = (rnd.nextDouble() - 0.5) * 0.6;
      this.height = 0;
      this.orbitPhase = rnd.nextDouble() * 2 * Math.PI;
      this.fade = 0;
    }

    @Override
    public void run(double deltaMs) {
      final double dt = deltaMs * 0.001;
      final double electroNow = Helix.this.electro.getValue();
      final double beatMs = Math.max(Helix.this.lx.engine.tempo.period.getValue(), MIN_BEAT_MS);

      // Climb the strand (faster at Electro = 1)
      final double climbRate = Ranges.lin(electroNow, ELECTRON_CLIMB_MIN, ELECTRON_CLIMB_MAX);
      this.height += climbRate * (1 + this.climbJit) * dt;

      // Fade eases toward the Electro level; at Electro = 0 it dies within ~1 beat
      final double tau = ELECTRON_FADE_BEATS * beatMs;
      this.fade += (electroNow - this.fade) * (1 - Math.exp(-deltaMs / tau));

      if (this.height > 1 || (electroNow < 0.02 && this.fade < ELECTRON_DEATH_EPS)) {
        remove();
        return;
      }

      // Circle the strand path as it climbs
      this.orbitPhase += this.orbitSpeed * dt;

      final SurfaceCanvas c = Helix.this.cylCanvas;
      final int w = c.width;
      final int h = c.height;
      final double tw = Helix.this.effectiveTwist();
      final double spinRad = Helix.this.spin * 2 * Math.PI;
      final double phase = Helix.this.strandPhase(this.strand);
      final double yOff = Helix.this.strandYOff(this.strand);

      final double up = this.height + yOff + this.orbitBob * Math.sin(this.orbitPhase);
      if (up < 0 || up > 1) {
        return; // off the surface this frame; keep alive
      }
      final double angle = spinRad + tw * 2 * Math.PI * this.height + phase;
      final double colf = angle / (2 * Math.PI) * w + this.orbitAmp * Math.cos(this.orbitPhase);
      final double row = (1 - up) * (h - 1);
      Helix.this.plotDisc(c, colf, row, ELECTRON_RADIUS, scaleRGB(Helix.this.electronArgb, this.fade));
    }
  }

  // ---- Palette ----------------------------------------------------------------

  /** Refresh cached role colors from the palette swatch + Desat, rebuilt only on
   *  change. Strand/stars = swatch 1; pulses = swatch 2 (or white); electrons =
   *  swatch 3 (or a cyan fallback). */
  private void updatePalette() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = swatch.size();
    final int c0 = (n >= 1) ? swatch.get(0).getColor() : FALLBACK_STRAND;
    final int c1 = (n >= 2) ? swatch.get(1).getColor() : 0xffffffff;      // pulses: swatch 2 or white
    final int c2 = (n >= 3) ? swatch.get(2).getColor() : FALLBACK_ELECTRON; // electrons: swatch 3
    final int dk = (int) Math.round(this.desat.getValue() * 100);

    int hash = 17;
    hash = 31 * hash + c0;
    hash = 31 * hash + c1;
    hash = 31 * hash + c2;
    hash = 31 * hash + dk;
    if (hash == this.cachedHash) {
      return;
    }
    this.cachedHash = hash;

    final double ds = this.desat.getValue();
    this.strandArgb = desaturate(c0, ds);
    this.pulseArgb = desaturate(c1, ds);
    this.starArgb = this.strandArgb;
    this.electronArgb = desaturate(c2, ds);
  }

  // ---- Color helpers ----------------------------------------------------------

  /** Pull a color's saturation down toward white by {@code amount} (0..1). */
  private static int desaturate(int argb, double amount) {
    if (amount <= 0) {
      return 0xff000000 | (argb & 0x00ffffff);
    }
    final float h = LXColor.h(argb);
    final float s = LXColor.s(argb);
    final float b = LXColor.b(argb);
    return LXColor.hsb(h, (float) (s * (1 - amount)), b);
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
