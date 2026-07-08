package apotheneum.jvyduna.patterns.distance;

import java.util.List;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * PulseOrb — the breathing sanctuary core for Helios' "Our Distance." A warm
 * volumetric orb, read interior-first as a home/heart, breathes on a slow
 * tempo-loose LFO (the ~1.4 s pad swell) while a SECOND, deliberately slower
 * system — a morphing domain-warp wall field, the "ketamine K-hole"
 * inside-out folding topology (warm reds/roses/teals) — drifts across the
 * four outer walls as the "world's light moving across the wall." The two
 * systems run at different speeds so the eye always has slow evolution to
 * follow; that coupling is the anti-monotony insurance the finale demands.
 *
 * The orb blooms to its apex on its OWN time (the MIR global RMS peak at 2:57,
 * driven by timeline automation of Size/Bloom + the Pulse trigger), sends one
 * decisive ripple outward for the late percussion re-escalation (~4:04, the
 * Ripple trigger / snare peak), then recedes and contracts toward a single
 * point as the set fades to black by 5:24. This pattern is the intended
 * source field for the {@code effects/BlitFeedback} effect — layer BlitFeedback
 * over it (slow warm feedback zoom/rotate) to get the Inception/Interstellar
 * melt on top of the wall topology.
 *
 * See PulseOrb.md (beside this file) for the full design note. The wall field is
 * a seamless inside-out-fold iterative domain warp (the sample coordinates warped
 * by low-frequency functions of themselves), and the {@code Fold} trigger drives
 * a true inside-out coordinate reconfiguration (vertical center&lt;-&gt;edge
 * inversion + rigid ring rotation + warp re-texture) that eases as a smooth
 * topological morph. The interior-first weighting and BlitFeedback pairing remain
 * hardware-balance CURATE items.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("PulseOrb")
@LXComponent.Description("Breathing warm sanctuary orb (interior-first) coupled with a slower morphing domain-warp wall topology — the Our Distance exhale, source field for BlitFeedback")
public class PulseOrb extends ApotheneumPattern {

  private static final double TWO_PI = Math.PI * 2;

  // ---- Surface geometry ---------------------------------------------------------

  private static final int RING_W = Apotheneum.Cube.Ring.LENGTH;   // 200 (4 faces)
  private static final int RING_H = Apotheneum.GRID_HEIGHT;        // 45
  private static final int FACE_W = Apotheneum.GRID_WIDTH;         // 50
  private static final int CYL_W = Apotheneum.RING_LENGTH;         // 120
  private static final int CYL_H = Apotheneum.CYLINDER_HEIGHT;     // 43

  /** Vertical center of each surface (where the orb's light sits) */
  private static final double RING_CY = (RING_H - 1) / 2.0;
  private static final double CYL_CY = (CYL_H - 1) / 2.0;
  /** Horizontal center of a single 50-wide cube face */
  private static final double FACE_CX = (FACE_W - 1) / 2.0;

  // ---- Timing constants (all >= 5 s sustained; see compliance in the .md) -------

  /** Orb breathe cycle period (full inhale+exhale), ambient -> peak energy.
   *  CURATE: 8 s reads as the pad swell; keep >= 5 s. */
  private static final double BREATHE_MS_AMBIENT = 8000;
  private static final double BREATHE_MS_PEAK = 5000;

  /** Wall-field drift period — deliberately MUCH slower than the orb so the two
   *  systems decorrelate (the anti-monotony coupling). CURATE. */
  private static final double WALL_MS_AMBIENT = 24000;
  private static final double WALL_MS_PEAK = 12000;

  /** Orb radius (pixels) floor and ceiling as Size sweeps 0..1 */
  private static final double ORB_MIN_R = 2.0;
  private static final double ORB_MAX_R = 30.0;   // CURATE: can exceed a face to bloom
  /** Breathe never fully collapses the orb: floor fraction of the swing kept */
  private static final double BREATHE_FLOOR = 0.35;

  /** Audio bass adds up to this many pixels of radius at Audio depth 1. CURATE. */
  private static final double ORB_AUDIO_R = 8.0;
  /** A Pulse trigger adds up to this many pixels of radius at full envelope */
  private static final double ORB_PULSE_R = 10.0;

  private static final double ORB_BASE_BRIGHT = 1.0;

  /** Interior is the "home": orb dominant, wall recessive. Exterior is the
   *  "world outside the window": wall dominant, orb a bleed. CURATE weights. */
  private static final double ORB_INT_WEIGHT = 1.0;
  private static final double WALL_INT_WEIGHT = 0.35;
  private static final double ORB_EXT_WEIGHT = 0.35;
  private static final double WALL_EXT_WEIGHT = 1.0;
  /** Orb glow on the cylinder (no cube interior to anchor it — a faint belt) */
  private static final double ORB_CYL_WEIGHT = 0.25;

  private static final double WALL_BRIGHT = 0.9;

  /** Ripple (snare bloom) expansion + life. Event-like: >= 1.5 s. */
  private static final double RIPPLE_SPEED_PX_PER_MS = 0.014; // ~35 px over 2.5 s
  private static final double RIPPLE_LIFE_MS = 3000;
  private static final double RIPPLE_SIGMA = 3.0;             // shell half-width (px)

  private static final double PULSE_DECAY_MS = 2000;          // manual breath swell
  /** Topology fold lerp rate toward its target (per ms) — a slow inside-out morph */
  private static final double FOLD_LERP_PER_MS = 0.0004;      // CURATE: ~2.5 s to settle
  /** Persistent ring rotation (radians) applied per completed Fold. A rigid,
   *  seamless constant angle offset (same at every column, so the wrap survives)
   *  that guarantees successive folds never settle on the same topology even
   *  when the inside-out vertical parity has cycled back. Kept small so the
   *  reconfiguration reads as a fold, not a spin. CURATE. */
  private static final double FOLD_ROT = 0.6;

  private static final int MAX_SWATCH = 4;

  // ---- Warm-sanctuary fallback palette (used only with an EMPTY swatch) --------
  // CURATE: picked blind to the brief's amber/rose/deep-teal (candlelight
  // through a window); the F->Fm ache cools via the Warmth knob, not these.
  private static final int FB_CORE = 0xffffb38a;  // peach core
  private static final int FB_HALO = 0xffcc99ff;  // lilac halo
  private static final int FB_FIELD_WARM = 0xffcc5a6a; // rose
  private static final int FB_FIELD_COOL = 0xff1e6b6b; // deep teal

  // ---- Parameters ---------------------------------------------------------------

  private final AudioReactive audio;

  public final TriggerParameter pulse =
    new TriggerParameter("Pulse", this::onPulse)
    .setDescription("A single breath swell — the orb radius surges and settles over ~2 s");

  public final TriggerParameter ripple =
    new TriggerParameter("Ripple", this::onRipple)
    .setDescription("One decisive ring expands outward from the orb across the walls (~3 s) — the 4:04 snare bloom");

  public final TriggerParameter fold =
    new TriggerParameter("Fold", this::onFold)
    .setDescription("Fold the wall topology inside-out into a new configuration — the K-hole world reconfigures over ~2.5 s");

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: raises breathe/drift rate and brightness within the >= 5 s traversal cap");

  public final CompoundParameter size =
    new CompoundParameter("Size", 0.4)
    .setDescription("Base orb radius (breathes around this); automate up toward the 2:57 bloom, down toward the 5:07 contract-to-point");

  public final CompoundParameter warmth =
    new CompoundParameter("Warmth", 0.7)
    .setDescription("Palette warm/cool bias: high = candlelight warmth, low = the plagal F->Fm cool ache");

  public final CompoundParameter morph =
    new CompoundParameter("Morph", 0.5)
    .setDescription("Wall-field presence: 0 = walls near-dark (bare intro/breakdown), 1 = full morphing K-hole topology");

  public final CompoundParameter warp =
    new CompoundParameter("Warp", 0.5)
    .setDescription("Domain-warp fold intensity — how inside-out the wall topology folds");

  public final CompoundParameter drift =
    new CompoundParameter("Drift", 1, 0.5, 2)
    .setDescription("Wall-field drift-speed multiplier (kept slower than the orb so the two systems decorrelate)");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  // ---- Preallocated buffers (zero-alloc render) --------------------------------

  private final SurfaceCanvas ringExt = new SurfaceCanvas(RING_W, RING_H);
  private final SurfaceCanvas ringInt = new SurfaceCanvas(RING_W, RING_H);
  private final SurfaceCanvas cyl = new SurfaceCanvas(CYL_W, CYL_H);

  // Palette role colors, rebuilt only when the swatch actually changes
  private int coreColor = FB_CORE;
  private int haloColor = FB_HALO;
  private int fieldWarm = FB_FIELD_WARM;
  private int fieldCool = FB_FIELD_COOL;
  private final int[] cachedSwatch = new int[MAX_SWATCH];
  private int cachedSwatchCount = -1; // forces the initial build

  // ---- Continuous state (primitives; no per-frame allocation) ------------------

  private double orbPhase = 0;   // breathe LFO phase (rad)
  private double wallPhase = 0;  // wall-field drift phase (rad)
  private double foldPhase = 0;  // current topology-fold phase
  private double foldTarget = 0; // target the fold lerps toward

  private double pulseEnv = 0;   // 0..1 manual-breath envelope

  private boolean rippleActive = false;
  private double rippleAgeMs = 0;
  private double rippleRadiusPx = 0;
  private double rippleEnv = 0;

  public PulseOrb(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);

    // Registration order: triggers, Energy, pattern params, Audio.
    addParameter("pulse", this.pulse);
    addParameter("ripple", this.ripple);
    addParameter("fold", this.fold);
    addParameter("energy", this.energy);
    addParameter("size", this.size);
    addParameter("warmth", this.warmth);
    addParameter("morph", this.morph);
    addParameter("warp", this.warp);
    addParameter("drift", this.drift);
    addParameter("audio", this.audioDepth);
  }

  // ---- Trigger handlers (event-rate; minimal allocation permitted) --------------

  /** Manual breath swell: arm the pulse envelope to full. */
  private void onPulse() {
    this.pulseEnv = 1;
  }

  /** Snare bloom: (re)start the outward ripple from the orb center. */
  private void onRipple() {
    this.rippleActive = true;
    this.rippleAgeMs = 0;
    this.rippleRadiusPx = 0;
    this.rippleEnv = 1;
  }

  /**
   * Fold the wall topology inside-out toward a fresh configuration. Advances the
   * fold target by ONE fold-unit; {@code foldPhase} then eases continuously
   * toward it over ~2.5 s (FOLD_LERP_PER_MS). The unit is consumed inside
   * {@link #wallField} as a true coordinate reconfiguration — a smooth 0..1..0
   * inside-out inversion of the vertical axis (wall center &lt;-&gt; wall edges)
   * plus a rigid seamless ring rotation and a re-texturing of the warp — not a
   * bare phase add. Because the transform is driven by the eased continuous
   * {@code foldPhase}, the render crossfades the pre- and post-fold coordinate
   * mapping automatically, giving the "world reconfigures inside-out" morph.
   */
  private void onFold() {
    this.foldTarget += 1;
    LX.log("PulseOrb Fold -> foldTarget " + String.format("%.2f", this.foldTarget));
  }

  // ---- Render -------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    updatePalette();
    advanceClocks(deltaMs);

    final double sizeBase = this.size.getValue();
    final double warmthVal = this.warmth.getValue();
    final double morphVal = this.morph.getValue();
    final double warpVal = this.warp.getValue();

    // Breathe shape 0..1 and the derived orb radius / brightness
    final double breathe01 = 0.5 + 0.5 * Math.sin(this.orbPhase);
    final double breatheEnv = BREATHE_FLOOR + (1 - BREATHE_FLOOR) * breathe01;
    double radiusPx = ORB_MIN_R + (ORB_MAX_R - ORB_MIN_R) * sizeBase * breatheEnv;
    radiusPx += this.audio.bass * ORB_AUDIO_R;   // depth-scaled inside AudioReactive
    radiusPx += this.pulseEnv * ORB_PULSE_R;
    final double orbBright = ORB_BASE_BRIGHT * breatheEnv
      + 0.6 * this.pulseEnv + 0.4 * this.audio.bass;

    final double levelLift = 1 + 0.3 * this.audio.level; // gentle glow with the mix

    renderRing(radiusPx, orbBright, morphVal, warpVal, warmthVal, levelLift);
    renderCylinder(radiusPx, orbBright, morphVal, warpVal, warmthVal, levelLift);

    // Cube: interior (home) and exterior (world) rendered independently
    this.ringExt.copyTo(Apotheneum.cube.exterior, this.colors);
    if (Apotheneum.cube.interior != null) {
      this.ringInt.copyTo(Apotheneum.cube.interior, this.colors);
    }

    // Cylinder: one field, mirrored inside->outside
    this.cyl.copyTo(Apotheneum.cylinder.exterior, this.colors);
    copyCylinderExterior();
  }

  /** Advance the breathe + wall-drift phases, the fold lerp, and the pulse /
   *  ripple envelopes. Both periods free-run at their energy-derived values. */
  private void advanceClocks(double deltaMs) {
    final double e = this.energy.getValue();

    final double orbPeriod = Ranges.lin(e, BREATHE_MS_AMBIENT, BREATHE_MS_PEAK);
    final double orbRate = TWO_PI / orbPeriod;
    this.orbPhase = wrapTwoPi(this.orbPhase + orbRate * deltaMs);

    final double wallPeriod =
      Ranges.lin(e, WALL_MS_AMBIENT, WALL_MS_PEAK) / this.drift.getValue();
    final double wallRate = TWO_PI / wallPeriod;
    this.wallPhase = wrapTwoPi(this.wallPhase + wallRate * deltaMs);

    // Topology fold eases toward its target (set by the Fold trigger)
    final double foldStep = clamp(FOLD_LERP_PER_MS * deltaMs, 0, 1);
    this.foldPhase += (this.foldTarget - this.foldPhase) * foldStep;

    // Pulse envelope decay
    this.pulseEnv = Math.max(0, this.pulseEnv - deltaMs / PULSE_DECAY_MS);

    // Ripple envelope: expand outward, fade over its life
    if (this.rippleActive) {
      this.rippleAgeMs += deltaMs;
      this.rippleRadiusPx += RIPPLE_SPEED_PX_PER_MS * deltaMs;
      this.rippleEnv = Math.max(0, 1 - this.rippleAgeMs / RIPPLE_LIFE_MS);
      if (this.rippleEnv <= 0) {
        this.rippleActive = false;
      }
    }
  }

  /** Render both cube ring canvases (exterior = world, interior = home) in one
   *  pass; the wall field and orb glow are computed once per pixel and weighted
   *  differently into each canvas. */
  private void renderRing(double radiusPx, double orbBright, double morphVal,
                          double warpVal, double warmthVal, double levelLift) {
    for (int y = 0; y < RING_H; ++y) {
      final double yn = y / (double) RING_H;
      final double dy = y - RING_CY;
      for (int x = 0; x < RING_W; ++x) {
        // Wall field (the morphing K-hole topology)
        final double xn = x / (double) RING_W;
        final double f = wallField(xn, yn, this.wallPhase, warpVal);
        final int fc = fieldColor(f, warmthVal);
        final double wallLevel = morphVal * WALL_BRIGHT * f * levelLift;

        // Orb glow: per-face radial spot (4 face centers around the ring), so
        // it can contract toward a point at each face center
        final double faceLocalX = x - (x / FACE_W) * FACE_W;
        final double dxo = faceLocalX - FACE_CX;
        final double dist = Math.sqrt(dxo * dxo + dy * dy);
        final double coreL = orbBright * orbFalloff(dist, radiusPx * 0.5);
        final double haloL = orbBright * 0.6 * orbFalloff(dist, radiusPx);

        // Ripple shell (event-like snare bloom), same per-face metric
        final double rippleL = rippleLevel(dist);

        int ext = 0xff000000;
        ext = addScaled(ext, fc, wallLevel * WALL_EXT_WEIGHT);
        ext = addScaled(ext, this.haloColor, haloL * ORB_EXT_WEIGHT);
        ext = addScaled(ext, this.coreColor, coreL * ORB_EXT_WEIGHT);
        ext = addScaled(ext, this.coreColor, rippleL);
        this.ringExt.set(x, y, ext);

        int in = 0xff000000;
        in = addScaled(in, fc, wallLevel * WALL_INT_WEIGHT);
        in = addScaled(in, this.haloColor, haloL * ORB_INT_WEIGHT);
        in = addScaled(in, this.coreColor, coreL * ORB_INT_WEIGHT);
        in = addScaled(in, this.coreColor, rippleL);
        this.ringInt.set(x, y, in);
      }
    }
  }

  /** Render the cylinder: the wall field plus a faint horizontal orb belt (the
   *  cylinder has no cube interior to anchor a point-orb, so the orb reads as a
   *  breathing band of the sanctuary light bleeding onto the outer ring). */
  private void renderCylinder(double radiusPx, double orbBright, double morphVal,
                              double warpVal, double warmthVal, double levelLift) {
    for (int y = 0; y < CYL_H; ++y) {
      final double yn = y / (double) CYL_H;
      final double dy = Math.abs(y - CYL_CY);
      final double coreL = orbBright * ORB_CYL_WEIGHT * orbFalloff(dy, radiusPx * 0.5);
      final double haloL = orbBright * ORB_CYL_WEIGHT * 0.6 * orbFalloff(dy, radiusPx);
      final double rippleL = rippleLevel(dy);
      for (int x = 0; x < CYL_W; ++x) {
        final double xn = x / (double) CYL_W;
        final double f = wallField(xn, yn, this.wallPhase, warpVal);
        final int fc = fieldColor(f, warmthVal);
        final double wallLevel = morphVal * WALL_BRIGHT * f * levelLift;

        int c = 0xff000000;
        c = addScaled(c, fc, wallLevel);
        c = addScaled(c, this.haloColor, haloL);
        c = addScaled(c, this.coreColor, coreL);
        c = addScaled(c, this.coreColor, rippleL);
        this.cyl.set(x, y, c);
      }
    }
  }

  // ---- Field / glow math --------------------------------------------------------

  /**
   * The morphing wall topology, a scalar in [0,1] — the "ketamine K-hole"
   * inside-out folding field. An INSIDE-OUT-FOLD domain warp: the sample
   * coordinates are warped by low-frequency functions of themselves, twice
   * (Quilez-style fbm-of-fbm), which is what produces the slow topology
   * "folding / wrapping into itself" Inception/Interstellar structure rather
   * than a flat scrolling texture. Deliberately low-frequency / soft-focus (no
   * fine texture) and slow (driven only by the &gt;=12 s wall {@code phase} and
   * the eased fold), per the compliance math in PulseOrb.md.
   *
   * SEAMLESS WRAP: xn enters the field ONLY through the ring angle {@code a},
   * and ONLY ever as an INTEGER harmonic (sin/cos of n·a). Since a and a+2π are
   * the same physical column, every sin(n·a) agrees across the 200/120-column
   * seam. Every warp that DISPLACES the angle is a function of the orthogonal
   * (vertical) coordinate only — so it, too, matches on both sides of the seam.
   * (Verified: substituting xn -&gt; xn+1, i.e. a -&gt; a+2π, leaves the result
   * bit-for-bit unchanged — a1/a2 shift by exactly 2π, v/v1/v2 are invariant.)
   */
  private double wallField(double xn, double yn, double phase, double warpAmt) {
    final double fp = this.foldPhase;   // eased fold amount, in fold-units

    // ---- Fold pre-transform: the true inside-out coordinate reconfiguration --
    // Each Fold press eases fp up by one unit. A smooth 0..1..0 parity turns the
    // vertical axis INSIDE-OUT (wall center <-> wall edges) and back to a fresh
    // configuration; a small rigid ring rotation (a seamless constant angle
    // offset) makes successive folds land on genuinely different topologies; and
    // foldRad re-textures the warp itself. Blending on the eased fp means the
    // render crossfades the pre- and post-fold mapping — a smooth topological
    // morph, not a bare phase add.
    final double inv = 0.5 - 0.5 * Math.cos(fp * Math.PI);   // 0,1,0,1 at folds
    final double vFold = Math.abs(2.0 * yn - 1.0);           // center->0, edges->1
    final double v = yn + (vFold - yn) * inv;                // eased inside-out fold
    final double a = xn * TWO_PI + fp * FOLD_ROT;            // seamless rigid rotation
    final double foldRad = fp * Math.PI;                     // fold also re-textures

    // ---- Iterative domain warp (the topology folding into itself) ------------
    final double amp = 0.5 + 1.5 * warpAmt;                  // Warp knob -> fold depth

    // Pass 1: displace the angle by a function of v ONLY (keeps x-periodic), and
    //         the vertical by low integer harmonics of the angle.
    final double a1 = a + amp * Math.sin(v * TWO_PI + phase);
    final double v1 = v + amp * 0.12 * (Math.sin(a) + 0.5 * Math.sin(2.0 * a - foldRad));

    // Pass 2: FOLD the warped domain back into itself — the new displacements are
    //         functions of the pass-1 warped coordinates (the iteration).
    final double a2 = a1 + amp * Math.sin(v1 * TWO_PI * 1.5 + phase * 0.4 + foldRad);
    final double v2 = v1 + amp * 0.10 * Math.cos(a1 + phase * 0.3);

    // ---- Sample the folded field with a few LOW integer harmonics (soft-focus)
    final double s =
        Math.sin(a2 + phase)
      + 0.6 * Math.cos(2.0 * a2 - v2 * TWO_PI + foldRad)
      + 0.5 * Math.sin(v2 * TWO_PI * 1.5 - phase * 0.5);

    // s ~ [-2.1, 2.1] -> soft-shouldered [0,1]
    return clamp(0.5 + 0.24 * s, 0, 1);
  }

  /** Warm/cool field color: lerp cool->warm, biased by the Warmth knob so low
   *  Warmth pulls the whole field toward the cool teal (the F->Fm ache). */
  private int fieldColor(double f, double warmthVal) {
    final double t = clamp(f * (0.4 + 1.2 * warmthVal), 0, 1);
    return LXColor.lerp(this.fieldCool, this.fieldWarm, (float) t);
  }

  /** Smooth radial falloff, 1 at center to 0 at radiusPx (quadratic shoulder). */
  private static double orbFalloff(double dist, double radiusPx) {
    if (radiusPx <= 0) {
      return 0;
    }
    final double u = dist / radiusPx;
    if (u >= 1) {
      return 0;
    }
    final double k = 1 - u;
    return k * k;
  }

  /** Gaussian shell around the current ripple radius, scaled by its envelope. */
  private double rippleLevel(double dist) {
    if (!this.rippleActive || this.rippleEnv <= 0) {
      return 0;
    }
    final double d = dist - this.rippleRadiusPx;
    return this.rippleEnv * Math.exp(-(d * d) / (2 * RIPPLE_SIGMA * RIPPLE_SIGMA));
  }

  // ---- Palette (Satori-style change cache) --------------------------------------

  /** Rebuild the four role colors only when the project swatch actually changes.
   *  >= 4 colors: core/halo/warm/cool = swatch 0..3. Sparser swatches derive the
   *  missing roles; an empty swatch uses the warm-sanctuary fallback constants. */
  private void updatePalette() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = Math.min(swatch.size(), MAX_SWATCH);
    boolean changed = (n != this.cachedSwatchCount);
    for (int i = 0; i < n; ++i) {
      final int c = swatch.get(i).getColor();
      if (c != this.cachedSwatch[i]) {
        changed = true;
        this.cachedSwatch[i] = c;
      }
    }
    if (!changed) {
      return;
    }
    this.cachedSwatchCount = n;
    if (n == 0) {
      this.coreColor = FB_CORE;
      this.haloColor = FB_HALO;
      this.fieldWarm = FB_FIELD_WARM;
      this.fieldCool = FB_FIELD_COOL;
      return;
    }
    this.coreColor = this.cachedSwatch[0];
    this.haloColor = (n >= 2) ? this.cachedSwatch[1]
      : LXColor.lerp(this.cachedSwatch[0], FB_HALO, 0.5f);
    this.fieldWarm = (n >= 3) ? this.cachedSwatch[2] : this.coreColor;
    this.fieldCool = (n >= 4) ? this.cachedSwatch[3]
      : ((n >= 2) ? this.cachedSwatch[1] : FB_FIELD_COOL);
  }

  // ---- Small helpers ------------------------------------------------------------

  /** Add {@code argb} scaled by {@code level} onto {@code base}, per-channel,
   *  clamping to 255 (so overlapping bright contributions bloom to white). */
  private static int addScaled(int base, int argb, double level) {
    if (level <= 0) {
      return base;
    }
    if (level > 4) {
      level = 4; // guard against a runaway envelope; channels still clamp
    }
    final int r = Math.min(255, ((base >> 16) & 0xff) + (int) (((argb >> 16) & 0xff) * level));
    final int g = Math.min(255, ((base >> 8) & 0xff) + (int) (((argb >> 8) & 0xff) * level));
    final int b = Math.min(255, (base & 0xff) + (int) ((argb & 0xff) * level));
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  private static double wrapTwoPi(double v) {
    v %= TWO_PI;
    return (v < 0) ? v + TWO_PI : v;
  }

  private static double clamp(double v, double lo, double hi) {
    return (v < lo) ? lo : (v > hi) ? hi : v;
  }
}
