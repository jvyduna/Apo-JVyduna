package apotheneum.jvyduna.patterns.temper;

import java.util.List;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.utils.LXUtils;

/**
 * LATTICE — a bench tool for simulating SUBPIXEL ARRANGEMENTS that form color
 * fields on the Apotheneum square LED lattice. Three colors A, B, C (the
 * project palette's first three swatch colors, assumed pastel CMY, with a
 * pastel-CMY fallback) are distributed across the pixels under a chosen screen
 * scheme, and the eye integrates neighbours into a solid field.
 *
 * The continuous {@link #mode Mode} knob crossfades between seven schemes
 * (integers 0..6), ordered so adjacent schemes differ in as few pixels as
 * possible (the pure-shear triads sit together, the two PenTile screens sit
 * together). Where a scheme OVER-REPRESENTS one color, that color's pixels are
 * auto-dimmed in proportion (a Bayer GRBG = ABCA doubles A, so A renders at
 * half brightness) so every color contributes equal luminous flux.
 *
 *  0 Vertical triad      A B C by column
 *  1 S-stripe            vertical triad serpentined row-to-row
 *  2 Diagonal (XO)       three-sublattice, (x+y) mod 3
 *  3 Horizontal triad    A B C by row
 *  4 PenTile diamond     2x2 Bayer A B / C A   (A doubled -> A dimmed 0.5)
 *  5 PenTile orthogonal  RGBG, B is the doubled "green" (B dimmed 0.5)
 *  6 Dither              per-pixel hash triad (stochastic, stripe-free field)
 *
 * {@link #shift Shift} (0..3) rotates every pixel through A->B->C; {@link #path
 * Path} selects the color-space route used BOTH for that shift and for the mode
 * crossfade. {@link #alpha Alpha} sets the per-pixel ARGB alpha byte so the
 * pattern composites over lower Chromatik channels (dark/OFF sites go fully
 * transparent). {@link #dimA DimA}/{@link #dimB DimB}/{@link #dimC DimC}
 * independently dim each color.
 *
 * The same lattice is rendered to every surface (cube + cylinder, exterior +
 * interior); surface targeting is done by the user via view filtering. No
 * audio, no tempo — this is a static visualization tool.
 *
 * Alloc-free: the crossfaded output for each of the 3x3 role pairs is computed
 * a handful of times per frame into {@link #out}, then the per-LED loop is a
 * pair of integer {@code roleFor} lookups plus a table read.
 *
 * See Lattice.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Lattice")
@LXComponent.Description("Subpixel-arrangement bench: distribute pastel-CMY A/B/C across the LED lattice under crossfadeable screen schemes (triads, PenTile, Bayer, dither) with shift, color-path, per-pixel alpha and per-color dimming")
public class Lattice extends ApotheneumPattern {

  private static final int NUM_MODES = 7;

  /** Pastel CMY fallback when the swatch has fewer than three colors. Soft,
   *  high-brightness, low-saturation — the Temper watercolour register. */
  private static final int[] PASTEL_CMY = {
    LXColor.hsb(186, 42, 96), // A ~ pastel cyan
    LXColor.hsb(320, 40, 97), // B ~ pastel magenta
    LXColor.hsb(52, 40, 99),  // C ~ pastel yellow
  };

  /**
   * Overrepresentation dim per [mode][role], = minCount / count[role] over the
   * scheme's tile so the rarest color stays full and a 2x color drops to 0.5.
   * Role-based (not color-based), so it stays correct as Shift rotates roles.
   */
  private static final double[][] OVERREP_DIM = {
    { 1, 1, 1 },       // 0 vertical    1:1:1
    { 1, 1, 1 },       // 1 s-stripe    1:1:1
    { 1, 1, 1 },       // 2 diagonal    1:1:1
    { 1, 1, 1 },       // 3 horizontal  1:1:1
    { 0.5, 1, 1 },     // 4 bayer       A:2 B:1 C:1 -> A dimmed
    { 1, 0.5, 1 },     // 5 pentile     A:1 B:2 C:1 -> B dimmed
    { 1, 1, 1 },       // 6 dither      ~1:1:1
  };

  /** Color-space route for both the Shift interpolation and the Mode crossfade.
   *  RGB/HSV family mirror LX's gradient blend modes; the last two hold hue
   *  constant and route the blend via black / via white. */
  public enum Path {
    RGB("RGB"),
    HSV("HSV"),
    HSV_CW("HSV CW"),
    HSV_CCW("HSV CCW"),
    THROUGH_BLACK("Thru Black"),
    THROUGH_WHITE("Thru White");

    public final String label;
    private Path(String label) { this.label = label; }

    @Override
    public String toString() { return this.label; }
  }

  // ---- Parameters ------------------------------------------------------------

  public final CompoundParameter mode =
    new CompoundParameter("Mode", 0, 0, NUM_MODES - 1)
    .setDescription("Subpixel scheme, crossfaded continuously: 0 vertical, 1 s-stripe, 2 diagonal, 3 horizontal, 4 Bayer/PenTile-diamond, 5 PenTile-orthogonal, 6 dither");

  public final CompoundParameter shift =
    new CompoundParameter("Shift", 0, 0, 3)
    .setDescription("Rotate every pixel through A->B->C (0..3, continuous), interpolated along Path");

  public final EnumParameter<Path> path =
    new EnumParameter<Path>("Path", Path.HSV)
    .setDescription("Color-space route for the Shift and Mode crossfades: RGB, HSV (and CW/CCW hue), or constant-hue through black / through white");

  public final CompoundParameter alpha =
    new CompoundParameter("Alpha", 1)
    .setDescription("Per-pixel transparency applied to every lit pixel, for compositing over other channels (OFF/dark sites are fully transparent)");

  public final CompoundParameter dimA =
    new CompoundParameter("DimA", 1)
    .setDescription("Dim every pixel currently showing color A");

  public final CompoundParameter dimB =
    new CompoundParameter("DimB", 1)
    .setDescription("Dim every pixel currently showing color B");

  public final CompoundParameter dimC =
    new CompoundParameter("DimC", 1)
    .setDescription("Dim every pixel currently showing color C");

  // ---- Per-frame scratch (preallocated) --------------------------------------

  private final int[] abc = new int[3];          // resolved A,B,C colors (opaque)
  private final double[] dims = new double[3];    // DimA/B/C snapshot
  private final int[] scheme0 = new int[3];       // schemeColor(m0, role)
  private final int[] scheme1 = new int[3];       // schemeColor(m1, role)
  private final int[][] out = new int[3][3];      // final ARGB per (role0, role1)

  public Lattice(LX lx) {
    super(lx);
    addParameter("mode", this.mode);
    addParameter("shift", this.shift);
    addParameter("path", this.path);
    addParameter("alpha", this.alpha);
    addParameter("dimA", this.dimA);
    addParameter("dimB", this.dimB);
    addParameter("dimC", this.dimC);
  }

  // ---- Render ----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    // Undrawn / OFF sites stay transparent so lower channels show through.
    setColors(0x00000000);

    resolveABC();

    final double modeVal = this.mode.getValue();
    final int m0 = (int) Math.floor(modeVal);
    final int m1 = Math.min(m0 + 1, NUM_MODES - 1);
    final float mFrac = (float) (modeVal - m0);

    final double shiftVal = this.shift.getValue();
    final int sInt = ((int) Math.floor(shiftVal)) % 3;
    final float sFrac = (float) (shiftVal - Math.floor(shiftVal));

    final Path p = this.path.getEnum();
    this.dims[0] = this.dimA.getValue();
    this.dims[1] = this.dimB.getValue();
    this.dims[2] = this.dimC.getValue();

    final int alphaByte = ((int) Math.round(255 * this.alpha.getValue())) & 0xff;
    final int alphaMask = alphaByte << 24;

    // Precompute the per-role scheme colors for the two active modes, then the
    // crossfaded output for every (role0, role1) pair — a few dozen HSB ops per
    // frame instead of one per LED.
    for (int r = 0; r < 3; ++r) {
      this.scheme0[r] = schemeColor(m0, r, sInt, sFrac, p);
      this.scheme1[r] = schemeColor(m1, r, sInt, sFrac, p);
    }
    for (int a = 0; a < 3; ++a) {
      for (int b = 0; b < 3; ++b) {
        final int c = pathLerp(this.scheme0[a], this.scheme1[b], mFrac, p);
        // Fully dark -> transparent (OFF sites and the dip of a through-black
        // crossfade); otherwise carry the global alpha for compositing.
        this.out[a][b] = ((c & 0x00ffffff) == 0) ? 0 : ((c & 0x00ffffff) | alphaMask);
      }
    }

    renderSurface(Apotheneum.cube.exterior, m0, m1);
    renderSurface(Apotheneum.cube.interior, m0, m1);
    renderSurface(Apotheneum.cylinder.exterior, m0, m1);
    renderSurface(Apotheneum.cylinder.interior, m0, m1);
  }

  private void renderSurface(Apotheneum.Surface surface, int m0, int m1) {
    if (surface == null) {
      return;
    }
    final Apotheneum.Column[] columns = surface.columns();
    for (int x = 0; x < columns.length; ++x) {
      final LXPoint[] points = columns[x].points; // door columns are shorter
      for (int y = 0; y < points.length; ++y) {
        this.colors[points[y].index] = this.out[roleFor(m0, x, y)][roleFor(m1, x, y)];
      }
    }
  }

  // ---- Color pipeline --------------------------------------------------------

  /** Resolve A/B/C from swatch 0/1/2, forced opaque; pastel-CMY fallback for
   *  any missing swatch slot so the pattern is never colorless. */
  private void resolveABC() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = swatch.size();
    for (int i = 0; i < 3; ++i) {
      final int c = (i < n) ? swatch.get(i).getColor() : PASTEL_CMY[i];
      this.abc[i] = 0xff000000 | (c & 0x00ffffff);
    }
  }

  /**
   * The rendered color for one scheme role: apply Shift (rotate the role
   * through A->B->C along Path), then the overrepresentation dim and the
   * per-color DimA/B/C. Returns an opaque, brightness-scaled ARGB (a zero
   * brightness collapses to black, which the caller maps to transparent).
   */
  private int schemeColor(int mode, int role, int sInt, float sFrac, Path p) {
    if (role < 0) {
      return 0; // OFF (no current mode produces this; future-proofing)
    }
    final int p0 = (role + sInt) % 3;
    final int p1 = (p0 + 1) % 3;
    final int col = pathLerp(this.abc[p0], this.abc[p1], sFrac, p);
    final double bmult = OVERREP_DIM[mode][role] * LXUtils.lerp(this.dims[p0], this.dims[p1], sFrac);
    return scaleBright(col, bmult);
  }

  /** Interpolate c0->c1 by t in [0,1] along the chosen Path. Returns opaque. */
  private static int pathLerp(int c0, int c1, float t, Path p) {
    if (t <= 0f) {
      return 0xff000000 | (c0 & 0x00ffffff);
    }
    if (t >= 1f) {
      return 0xff000000 | (c1 & 0x00ffffff);
    }
    switch (p) {
      case RGB:
        return rgbLerp(c0, c1, t);
      case THROUGH_BLACK:
        return thruBlack(c0, c1, t);
      case THROUGH_WHITE:
        return thruWhite(c0, c1, t);
      default:
        return hsvLerp(c0, c1, t, p);
    }
  }

  private static int rgbLerp(int c0, int c1, float t) {
    final int r = lerpChan((c0 >> 16) & 0xff, (c1 >> 16) & 0xff, t);
    final int g = lerpChan((c0 >> 8) & 0xff, (c1 >> 8) & 0xff, t);
    final int b = lerpChan(c0 & 0xff, c1 & 0xff, t);
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  /** HSV family: lerp S and B linearly, hue per the Path's direction. A black
   *  endpoint adopts the other's hue/sat so the fade doesn't sweep hue. */
  private static int hsvLerp(int c0, int c1, float t, Path p) {
    float h0 = LXColor.h(c0), s0 = LXColor.s(c0), b0 = LXColor.b(c0);
    float h1 = LXColor.h(c1), s1 = LXColor.s(c1), b1 = LXColor.b(c1);
    final boolean k0 = b0 <= 0, k1 = b1 <= 0;
    if (k0 && !k1) {
      h0 = h1;
      s0 = s1;
    } else if (k1 && !k0) {
      h1 = h0;
      s1 = s0;
    }
    final float h = hueInterp(h0, h1, t, p);
    final float s = LXUtils.lerpf(s0, s1, t);
    final float b = LXUtils.lerpf(b0, b1, t);
    return LXColor.hsb(h, s, b);
  }

  /** Directional hue interpolation on the 0..360 wheel. HSV is the raw
   *  (non-wrapping) lerp, matching LX; CW forces increasing, CCW decreasing. */
  private static float hueInterp(float h0, float h1, float t, Path p) {
    if (p == Path.HSV_CW) {
      if (h1 < h0) {
        h1 += 360;
      }
    } else if (p == Path.HSV_CCW) {
      if (h0 < h1) {
        h0 += 360;
      }
    }
    float h = LXUtils.lerpf(h0, h1, t);
    h %= 360;
    if (h < 0) {
      h += 360;
    }
    return h;
  }

  /** Constant-hue crossfade routed through black: c0 dims to black by t=0.5,
   *  then c1 rises from black. */
  private static int thruBlack(int c0, int c1, float t) {
    if (t < 0.5f) {
      return scaleBright(c0, 1 - t * 2);
    }
    return scaleBright(c1, (t - 0.5f) * 2);
  }

  /** Crossfade routed through white: c0 -> white by t=0.5, then white -> c1. */
  private static int thruWhite(int c0, int c1, float t) {
    if (t < 0.5f) {
      return rgbLerp(c0, 0xffffffff, t * 2);
    }
    return rgbLerp(0xffffffff, c1, (t - 0.5f) * 2);
  }

  private static int lerpChan(int a, int b, float t) {
    return a + Math.round((b - a) * t);
  }

  /** Multiply RGB by mult (clamped 0..1), opaque. mult<=0 -> black. */
  private static int scaleBright(int argb, double mult) {
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

  // ---- Schemes: role(x, y) -> {0=A, 1=B, 2=C} (or <0 for OFF) -----------------

  private static int roleFor(int mode, int x, int y) {
    switch (mode) {
      case 0: return Math.floorMod(x, 3);                 // vertical triad
      case 1: return Math.floorMod(x + serpentine(y), 3); // s-stripe
      case 2: return Math.floorMod(x + y, 3);             // diagonal / 3-sublattice (XO)
      case 3: return Math.floorMod(y, 3);                 // horizontal triad
      case 4: return bayer(x, y);                         // PenTile diamond
      case 5: return pentileOrtho(x, y);                  // PenTile orthogonal (RGBG)
      case 6: return hashTriad(x, y);                     // dither
      default: return 0;
    }
  }

  /** Triangle wave 0,1,2,3,2,1 (period 6) that bends the vertical stripes into
   *  an alternating serpentine S as they climb. */
  private static int serpentine(int y) {
    final int q = Math.floorMod(y, 6);
    return (q <= 3) ? q : (6 - q);
  }

  /** 2x2 Bayer / PenTile-diamond tile: A B / C A (A doubled). */
  private static int bayer(int x, int y) {
    final int bx = Math.floorMod(x, 2);
    final int by = Math.floorMod(y, 2);
    if (by == 0) {
      return (bx == 0) ? 0 : 1; // A B
    }
    return (bx == 0) ? 2 : 0;   // C A
  }

  /** PenTile RGBG: odd columns are always the doubled "green" = B; even columns
   *  alternate A (even row) / C (odd row). Counts A:1 B:2 C:1. */
  private static int pentileOrtho(int x, int y) {
    if (Math.floorMod(x, 2) == 1) {
      return 1; // B (green)
    }
    return (Math.floorMod(y, 2) == 0) ? 0 : 2; // A / C
  }

  /** Deterministic per-pixel hash mapped to a triad — a stochastic, stripe-free
   *  dither. (White-noise from an integer hash; a true blue-noise mask could be
   *  substituted here without touching anything else.) */
  private static int hashTriad(int x, int y) {
    int h = x * 374761393 + y * 668265263;
    h = (h ^ (h >>> 13)) * 1274126177;
    h ^= (h >>> 16);
    return Math.floorMod(h, 3);
  }
}
