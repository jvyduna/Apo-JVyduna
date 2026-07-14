package apotheneum.jvyduna.util;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * A small set of abstracted, NON-denominational religious / spiritual glyphs
 * defined as TRUE VECTOR geometry ({@link java.awt.Shape}) and rasterized to a
 * coverage grid on demand. This is the SHARED symbol asset for the piece: one
 * reusable table of glyphs that recurs across movements rather than being
 * re-implemented per song. See SymbolGlyphs.md for how rafters / chrome /
 * distance each reuse it.
 *
 * <p><b>Vector, not bitmap.</b> Every glyph is built from primitives (bars,
 * discs, ellipses, arcs, stroked paths, boolean {@link Area} ops), so diagonals
 * and curves stay crisp at any size instead of stair-stepping like the old 11px
 * ASCII-art masks. {@link #coverage} rasterizes a glyph into an antialiased
 * {@code cols x rows} coverage grid (headless AWT, same technique proven in
 * {@link OtfTypeface}) and caches it — the raster is rebuilt only when the
 * glyph, cell size, or smoothing changes, never per frame. The {@code smooth}
 * argument dials the antialiasing: {@code 1} = soft AA edges, {@code 0} = a hard
 * on/off mask (coverage sharpened around 0.5, the same remap
 * {@link SurfaceCanvas#lineWu} uses), so a caller's Smooth knob is the single
 * antialiasing control.
 *
 * <p>The set: {@link Symbol#CROSS} (a cross <i>pommée</i> — bulbed arm ends),
 * {@link Symbol#EYE} (eye of providence), {@link Symbol#SUN} (sun / halo),
 * {@link Symbol#FISH} (ichthys), {@link Symbol#CRESCENT} (rotated +45°),
 * {@link Symbol#OM} (rotated -90°), {@link Symbol#ANKH},
 * {@link Symbol#STAR} (Star of David / hexagram outline, rotated -90°),
 * {@link Symbol#TREE} (tree of life). OM is still the most abstract at LED pitch
 * and the strongest CURATE candidate for a hand-tuned redraw (see the .md).
 *
 * <p>Headless-AWT only ({@link BufferedImage} + {@link Graphics2D}) — safe in
 * Chromatik, which sets {@code java.awt.headless=true} at startup. No LX
 * dependencies. Run {@link #main} to preview every glyph as ASCII and eyeball
 * legibility before wiring it into a pattern.
 */
public final class SymbolGlyphs {

  /**
   * The shared symbol set. Ordinal order is the authoring order and is stable —
   * downstream sequencing (a continuous Glyph selector floored to an index) may
   * rely on it.
   */
  public enum Symbol {
    CROSS,
    EYE,
    SUN,
    FISH,
    CRESCENT,
    OM,
    ANKH,
    STAR,
    TREE;
  }

  private static final Symbol[] ALL = Symbol.values();

  /** Supersample factor for the coverage raster (averaged down per cell). */
  private static final int SS = 4;

  /** Fraction of the cell box left as margin around the unit-box glyph, so bulbs
   *  and outline strokes don't clip at the raster edge. */
  private static final double PAD = 0.06;

  /** Cache of rasterized coverage grids, keyed by (symbol, cols, rows, smooth
   *  quantized to 0.1). Read-only float[] values — callers must not mutate. */
  private static final Map<Long, float[]> CACHE = new HashMap<>();

  /** Number of glyphs (for a {@code 1..count()} selection param). */
  public static int count() {
    return ALL.length;
  }

  /** The full glyph set in stable ordinal order; shared, do not mutate. */
  public static Symbol[] all() {
    return ALL;
  }

  /** Glyph at ordinal {@code i}, clamped to the valid range. */
  public static Symbol byIndex(int i) {
    if (i < 0) {
      i = 0;
    } else if (i >= ALL.length) {
      i = ALL.length - 1;
    }
    return ALL[i];
  }

  /**
   * Antialiased coverage grid for a glyph rasterized into {@code cols x rows}
   * cells, row-major ({@code cov[y * cols + x]} in [0,1], y down / top row
   * first). Cached per (symbol, cols, rows, smooth); the returned array is
   * SHARED and READ-ONLY. {@code smooth} in [0,1] dials edge antialiasing:
   * 1 = soft AA, 0 = hard on/off mask.
   */
  public static float[] coverage(Symbol s, int cols, int rows, double smooth) {
    if (cols < 1) {
      cols = 1;
    }
    if (rows < 1) {
      rows = 1;
    }
    final int sq = (int) Math.round(clamp01(smooth) * 10); // 0..10
    final long key = ((long) s.ordinal() << 40)
      | ((long) (cols & 0xffff) << 24)
      | ((long) (rows & 0xffff) << 8)
      | (sq & 0xff);
    float[] cov = CACHE.get(key);
    if (cov == null) {
      cov = rasterize(s, cols, rows, sq / 10.0);
      CACHE.put(key, cov);
    }
    return cov;
  }

  // ---- Rasterization -----------------------------------------------------------

  private static float[] rasterize(Symbol s, int cols, int rows, double smooth) {
    final int w = cols * SS;
    final int h = rows * SS;
    final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.setColor(Color.WHITE);

    // Glyph geometry lives in the unit box [0,1]x[0,1]; map it into the padded
    // supersampled raster (uniform scale keeps circles round).
    final AffineTransform t = new AffineTransform();
    t.translate(PAD * w, PAD * h);
    t.scale((1 - 2 * PAD) * w, (1 - 2 * PAD) * h);
    g.fill(t.createTransformedShape(shape(s)));
    g.dispose();

    final int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
    final float[] cov = new float[cols * rows];
    final float inv = 1f / (SS * SS * 255f);
    for (int cy = 0; cy < rows; ++cy) {
      for (int cx = 0; cx < cols; ++cx) {
        int sum = 0;
        for (int sy = 0; sy < SS; ++sy) {
          final int base = ((cy * SS + sy) * w) + cx * SS;
          for (int sx = 0; sx < SS; ++sx) {
            sum += (argb[base + sx] >>> 24);
          }
        }
        cov[cy * cols + cx] = sharpen(sum * inv, smooth);
      }
    }
    return cov;
  }

  /** Remap coverage around 0.5 by 1/smooth: identity at smooth=1, hard 0/1
   *  threshold as smooth approaches 0 (mirrors {@link SurfaceCanvas}). */
  private static float sharpen(float coverage, double smooth) {
    if (smooth >= 1) {
      return coverage;
    }
    final double c = 0.5 + (coverage - 0.5) / Math.max(smooth, 1e-3);
    return (float) ((c < 0) ? 0 : (c > 1) ? 1 : c);
  }

  // ---- Vector geometry (unit box [0,1]x[0,1], y down) --------------------------

  private static Shape shape(Symbol s) {
    switch (s) {
      case CROSS:    return cross();
      case EYE:      return eye();
      case SUN:      return sun();
      case FISH:     return fish();
      case CRESCENT: return rotate(crescent(), Math.toRadians(45));
      case OM:       return om();
      case ANKH:     return ankh();
      case STAR:     return rotate(star(), Math.toRadians(-90));
      case TREE:     return tree();
      default:       return cross();
    }
  }

  /** Greek cross <i>pommée</i>: equal vertical + horizontal bars crossing at
   *  their shared center, with a filled disc (bulb) at each of the four arm
   *  ends. Symmetric (not a Latin cross) so all four arms read identical. */
  private static Shape cross() {
    final double barW = 0.16;
    final double arm = 0.38;              // arm-end offset from center → ends at 0.12 / 0.88
    final double lo = 0.5 - arm, hi = 0.5 + arm;
    final double bulb = 0.15;             // ~50% larger than the old 0.10
    final Area a = new Area(rect(0.5, 0.5, barW, hi - lo));  // vertical   bar (len 0.76)
    a.add(new Area(rect(0.5, 0.5, hi - lo, barW)));          // horizontal bar (len 0.76)
    a.add(new Area(disc(0.5, lo, bulb)));   // top
    a.add(new Area(disc(0.5, hi, bulb)));   // bottom
    a.add(new Area(disc(lo, 0.5, bulb)));   // left
    a.add(new Area(disc(hi, 0.5, bulb)));   // right
    return a;
  }

  /** Eye of providence: a pointed almond outline ring with a filled pupil. Built
   *  from an explicit two-Bézier lens so the left/right corners meet at sharp
   *  points (a subtracted-circle vesica clipped its cusps to rounded stubs). */
  private static Shape eye() {
    final Area outer = almond(0.5, 0.5, 0.44, 0.30);        // points at 0.06/0.94
    final Area inner = almond(0.5, 0.5, 0.44 * 0.60, 0.30 * 0.52);
    final Area ring = new Area(outer);
    ring.subtract(inner);
    ring.add(new Area(disc(0.5, 0.5, 0.10))); // pupil
    return ring;
  }

  /** Sun / halo: a central disc with eight tapered rays. */
  private static Shape sun() {
    final double cx = 0.5, cy = 0.5;
    final Area a = new Area(disc(cx, cy, 0.22));
    final double rIn = 0.24, rOut = 0.48, half = 0.05;
    for (int i = 0; i < 8; ++i) {
      final double ang = i * Math.PI / 4;
      final double ca = Math.cos(ang), sa = Math.sin(ang);
      final double px = -sa, py = ca; // perpendicular
      final Path2D ray = new Path2D.Double();
      ray.moveTo(cx + ca * rIn + px * half, cy + sa * rIn + py * half);
      ray.lineTo(cx + ca * rIn - px * half, cy + sa * rIn - py * half);
      ray.lineTo(cx + ca * rOut, cy + sa * rOut);
      ray.closePath();
      a.add(new Area(ray));
    }
    return a;
  }

  /** Fish: two body arcs meeting at the head (left point); a rounded paddle tail
   *  fin on the right. The tail is a SINGLE arc that meets the body's two right
   *  endpoints exactly and bows out past them — an open, "open-backed" fin with
   *  no straight chord closing it (not an ichthys crossover). */
  private static Shape fish() {
    final Path2D body = new Path2D.Double();
    body.moveTo(0.86, 0.34);
    body.quadTo(0.36, 0.08, 0.10, 0.50); // upper curve, tail(right) -> head(left)
    body.moveTo(0.86, 0.66);
    body.quadTo(0.36, 0.92, 0.10, 0.50); // lower curve
    final Area a = new Area(stroke(body, 0.09));
    // Rounded paddle tail: one arc from the upper endpoint, bowing rightward past
    // the endpoints, back to the lower endpoint. No line closes the arc.
    final Path2D tail = new Path2D.Double();
    tail.moveTo(0.86, 0.34);
    tail.quadTo(1.10, 0.50, 0.86, 0.66);
    a.add(new Area(stroke(tail, 0.09)));
    return a;
  }

  /** Waxing crescent: an outer disc with an EQUAL-radius offset disc subtracted
   *  (opens right); rotated +45° by the caller. Equal radii put the two circle
   *  intersections symmetric about the mid-height, so both horns taper to sharp,
   *  matching points (unequal radii gave one blunt and one sharp horn). */
  private static Area crescent() {
    final double r = 0.44;
    final Area a = new Area(disc(0.42, 0.5, r));
    a.subtract(new Area(disc(0.60, 0.5, r)));
    return a;
  }

  /** Om (ॐ): a bold Devanagari-style body (two right-bulging bumps — the "3"
   *  form most viewers read as Om), a flag curling off the top-right, and a
   *  chandra (up-opening crescent) with a bindu dot floating above. Authored
   *  upright from explicit Béziers so it reads at LED pitch; no caller rotation. */
  private static Shape om() {
    final double sw = 0.085;
    // One continuous body stroke: the flag tail sweeps in from the upper right,
    // flows down into the "3"/ओ form (two right-bulging bumps sharing a waist),
    // and ends in a short lower-left tail — the canonical Om silhouette.
    final Path2D body = new Path2D.Double();
    body.moveTo(0.84, 0.30);                  // flag tip (upper right)
    body.quadTo(0.60, 0.22, 0.38, 0.34);      // flag sweeps down-left to body top
    body.quadTo(0.66, 0.40, 0.46, 0.53);      // upper bump -> waist
    body.quadTo(0.74, 0.58, 0.56, 0.74);      // lower bump (larger)
    body.quadTo(0.42, 0.88, 0.24, 0.80);      // down to lower-left tail
    final Area a = new Area(stroke(body, sw));

    // Chandra: an up-opening crescent (bowl) with the bindu dot above, floating
    // clear above the flag tip.
    final Path2D moon = new Path2D.Double();
    moon.moveTo(0.62, 0.14);
    moon.quadTo(0.76, 0.22, 0.90, 0.14);      // control below endpoints -> opens up
    a.add(new Area(stroke(moon, 0.045)));
    a.add(new Area(disc(0.76, 0.05, 0.04)));
    return a;
  }

  /** Ankh: a hollow loop over a crossbar and stem. */
  private static Shape ankh() {
    final Area a = new Area(ellipse(0.5, 0.26, 0.19, 0.22)); // loop
    a.subtract(new Area(ellipse(0.5, 0.26, 0.10, 0.12)));
    a.add(new Area(rect(0.5, 0.56, 0.62, 0.13)));            // crossbar
    a.add(new Area(rect(0.5, 0.74, 0.14, 0.44)));            // stem
    return a;
  }

  /** Star of David / hexagram: two interlocking triangles as an OUTLINE (the
   *  woven look), rotated -90° by the caller. The triangle corners are geometrically
   *  rounded so every one of the six points reads as a soft, well-rounded lobe. */
  private static Shape star() {
    final double round = 0.18; // corner cut distance in unit-box units
    final Path2D up = roundedTriangle(
      new double[] {0.5, 0.90, 0.10}, new double[] {0.08, 0.72, 0.72}, round);
    final Path2D down = roundedTriangle(
      new double[] {0.5, 0.90, 0.10}, new double[] {0.92, 0.28, 0.28}, round);
    final Area a = new Area(stroke(up, 0.08));
    a.add(new Area(stroke(down, 0.08)));
    return a;
  }

  /** Tree of life: a round canopy, a trunk, and spreading roots. */
  private static Shape tree() {
    final Area a = new Area(disc(0.5, 0.30, 0.28));   // canopy
    a.add(new Area(rect(0.5, 0.66, 0.12, 0.44)));     // trunk
    final Path2D roots = new Path2D.Double();
    roots.moveTo(0.5, 0.78);
    roots.lineTo(0.22, 0.96);
    roots.moveTo(0.5, 0.78);
    roots.lineTo(0.5, 0.98);
    roots.moveTo(0.5, 0.78);
    roots.lineTo(0.78, 0.96);
    a.add(new Area(stroke(roots, 0.07)));
    return a;
  }

  // ---- Geometry helpers --------------------------------------------------------

  private static Rectangle2D rect(double cx, double cy, double w, double h) {
    return new Rectangle2D.Double(cx - w / 2, cy - h / 2, w, h);
  }

  private static Ellipse2D disc(double cx, double cy, double r) {
    return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
  }

  private static Ellipse2D ellipse(double cx, double cy, double rx, double ry) {
    return new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
  }

  /** Pointed almond (vesica-like lens): two quadratic arcs from the left point to
   *  the right point, meeting at SHARP corners. Spans 2*halfW wide, 2*halfH tall;
   *  the corner angle is 2·atan(halfH/halfW), so a thinner lens = sharper point. */
  private static Area almond(double cx, double cy, double halfW, double halfH) {
    final double lx = cx - halfW, rx = cx + halfW;
    final Path2D p = new Path2D.Double();
    p.moveTo(lx, cy);
    p.quadTo(cx, cy - halfH, rx, cy); // top arc, left point -> right point
    p.quadTo(cx, cy + halfH, lx, cy); // bottom arc back to the left point
    p.closePath();
    return new Area(p);
  }

  private static Path2D triangle(double x0, double y0, double x1, double y1, double x2, double y2) {
    final Path2D p = new Path2D.Double();
    p.moveTo(x0, y0);
    p.lineTo(x1, y1);
    p.lineTo(x2, y2);
    p.closePath();
    return p;
  }

  /** Closed polygon with each corner rounded: at every vertex the path stops
   *  {@code r} short of the corner along both edges and sweeps through the corner
   *  with a quadratic (the vertex as control point), giving soft rounded points. */
  private static Path2D roundedTriangle(double[] xs, double[] ys, double r) {
    final int n = xs.length;
    final Path2D p = new Path2D.Double();
    for (int i = 0; i < n; ++i) {
      final double vx = xs[i], vy = ys[i];
      final double ax = xs[(i + n - 1) % n], ay = ys[(i + n - 1) % n];
      final double bx = xs[(i + 1) % n], by = ys[(i + 1) % n];
      final double la = Math.hypot(ax - vx, ay - vy);
      final double lb = Math.hypot(bx - vx, by - vy);
      final double p1x = vx + (ax - vx) / la * r, p1y = vy + (ay - vy) / la * r;
      final double p2x = vx + (bx - vx) / lb * r, p2y = vy + (by - vy) / lb * r;
      if (i == 0) {
        p.moveTo(p1x, p1y);
      } else {
        p.lineTo(p1x, p1y);
      }
      p.quadTo(vx, vy, p2x, p2y);
    }
    p.closePath();
    return p;
  }

  /** Open arc as a path: bounding box centered (cx,cy), half-extents (rx,ry),
   *  from {@code startDeg} sweeping {@code extentDeg}. (Arc2D measures angles in
   *  its own y-up convention; for our symmetric arcs the mirror is immaterial.) */
  private static Shape arc(double cx, double cy, double rx, double ry, double startDeg, double extentDeg) {
    return new java.awt.geom.Arc2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry,
      startDeg, extentDeg, java.awt.geom.Arc2D.OPEN);
  }

  /** Fill region of a stroked path (round caps/joins), as a boolean-op-ready shape. */
  private static Shape stroke(Shape path, double width) {
    return new BasicStroke((float) width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
      .createStrokedShape(path);
  }

  private static Shape rotate(Shape s, double theta) {
    return AffineTransform.getRotateInstance(theta, 0.5, 0.5).createTransformedShape(s);
  }

  private static double clamp01(double v) {
    return (v < 0) ? 0 : (v > 1) ? 1 : v;
  }

  // ---- ASCII preview -----------------------------------------------------------

  /** ASCII preview of every glyph at a console-friendly cell size, so the vector
   *  forms (cross pommée, the Star/Om/Crescent rotations) can be eyeballed before
   *  wiring into a pattern. Pass a smooth value as arg[0] (default 1). */
  public static void main(String[] args) {
    final double smooth = (args.length > 0) ? Double.parseDouble(args[0]) : 1.0;
    final int cols = 28, rows = 28;
    final String ramp = " .:-=+*#%@";
    for (Symbol s : all()) {
      System.out.println(s.name() + ":");
      final float[] cov = coverage(s, cols, rows, smooth);
      for (int y = 0; y < rows; ++y) {
        final StringBuilder sb = new StringBuilder();
        for (int x = 0; x < cols; ++x) {
          final float v = cov[y * cols + x];
          sb.append(ramp.charAt(Math.min(ramp.length() - 1, (int) (v * ramp.length()))));
        }
        System.out.println(sb);
      }
      System.out.println();
    }
  }

  private SymbolGlyphs() {}
}
