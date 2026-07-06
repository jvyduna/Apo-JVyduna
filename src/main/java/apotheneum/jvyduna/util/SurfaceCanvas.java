package apotheneum.jvyduna.util;

import apotheneum.Apotheneum;

/**
 * Preallocated ARGB pixel buffer for drawing on Apotheneum surfaces that
 * ApotheneumRasterPattern cannot reach (it is cube-face-only).
 *
 * Typical sizes: 200x45 for the cube exterior ring (all four faces as one
 * continuous wrap-around strip), 120x43 for the cylinder. X coordinates wrap
 * (floorMod), matching the physical topology of both surfaces; out-of-range Y
 * writes are silently dropped.
 *
 * Y = 0 is the TOP row, matching Apotheneum column point order.
 *
 * All methods are allocation-free after construction. Trails are achieved by
 * calling {@link #decay(double)} once per frame instead of clearing.
 */
public class SurfaceCanvas {

  public final int width;
  public final int height;
  private final int[] pixels;

  public SurfaceCanvas(int width, int height) {
    this.width = width;
    this.height = height;
    this.pixels = new int[width * height];
    // Start opaque black (not transparent 0x00000000), consistent with
    // fill()/decay()/get(), so a canvas drawn before any explicit fill()
    // never copies alpha-0 pixels into the pattern color buffer.
    fill(0xff000000);
  }

  /** Set a pixel; x wraps around the surface, out-of-range y is ignored */
  public void set(int x, int y, int argb) {
    if (y < 0 || y >= this.height) {
      return;
    }
    this.pixels[y * this.width + Math.floorMod(x, this.width)] = argb;
  }

  /**
   * Set a pixel with per-channel max (lighten) blending: each RGB channel
   * keeps the brighter of the existing and incoming values. A dim pixel
   * (e.g. an antialiasing fringe) never darkens a brighter pixel already
   * there — trail crossings stay intact. X wraps, out-of-range y is ignored.
   */
  public void setMax(int x, int y, int argb) {
    if (y < 0 || y >= this.height) {
      return;
    }
    maxBlend(y * this.width + Math.floorMod(x, this.width), argb);
  }

  /**
   * setMax variant that drops out-of-range x instead of wrapping it, for
   * bounce topologies where a fringe at one face edge must not land on the
   * opposite edge.
   */
  public void setMaxClamped(int x, int y, int argb) {
    if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
      return;
    }
    maxBlend(y * this.width + x, argb);
  }

  private void maxBlend(int idx, int argb) {
    final int c = this.pixels[idx];
    final int r = Math.max((c >> 16) & 0xff, (argb >> 16) & 0xff);
    final int g = Math.max((c >> 8) & 0xff, (argb >> 8) & 0xff);
    final int b = Math.max(c & 0xff, argb & 0xff);
    this.pixels[idx] = 0xff000000 | (r << 16) | (g << 8) | b;
  }

  /** Get a pixel; x wraps, out-of-range y reads black */
  public int get(int x, int y) {
    if (y < 0 || y >= this.height) {
      return 0xff000000;
    }
    return this.pixels[y * this.width + Math.floorMod(x, this.width)];
  }

  /** Fill the whole canvas with one color */
  public void fill(int argb) {
    java.util.Arrays.fill(this.pixels, argb);
  }

  /**
   * Multiply every pixel's RGB by mult (0..1), for trail effects. Alpha is
   * forced opaque. Channels floor to 0, so trails fully extinguish. Values
   * outside 0..1 are clamped (a channel scaled past 255 would otherwise bleed
   * into the neighboring channel's bits).
   */
  public void decay(double mult) {
    if (mult > 1) {
      mult = 1;
    } else if (mult < 0) {
      mult = 0;
    }
    for (int i = 0; i < this.pixels.length; ++i) {
      final int c = this.pixels[i];
      if ((c & 0x00ffffff) == 0) {
        continue;
      }
      final int r = (int) (((c >> 16) & 0xff) * mult);
      final int g = (int) (((c >> 8) & 0xff) * mult);
      final int b = (int) ((c & 0xff) * mult);
      this.pixels[i] = 0xff000000 | (r << 16) | (g << 8) | b;
    }
  }

  /**
   * Integer Bresenham line. Endpoints may lie outside the canvas; x wraps,
   * off-canvas y pixels are dropped. For wrap-aware motion, callers should
   * pass x deltas smaller than width/2 per segment.
   */
  public void line(int x0, int y0, int x1, int y1, int argb) {
    int dx = Math.abs(x1 - x0);
    int dy = -Math.abs(y1 - y0);
    int sx = (x0 < x1) ? 1 : -1;
    int sy = (y0 < y1) ? 1 : -1;
    int err = dx + dy;
    while (true) {
      set(x0, y0, argb);
      if ((x0 == x1) && (y0 == y1)) {
        break;
      }
      final int e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
  }

  /**
   * Integer Bresenham line plotted with {@link #setMax} (lighten blending)
   * instead of overwrite — crossings with existing content keep the brighter
   * channel values. Same endpoint semantics as {@link #line}.
   */
  public void lineMax(int x0, int y0, int x1, int y1, int argb) {
    int dx = Math.abs(x1 - x0);
    int dy = -Math.abs(y1 - y0);
    int sx = (x0 < x1) ? 1 : -1;
    int sy = (y0 < y1) ? 1 : -1;
    int err = dx + dy;
    while (true) {
      setMax(x0, y0, argb);
      if ((x0 == x1) && (y0 == y1)) {
        break;
      }
      final int e2 = 2 * err;
      if (e2 >= dy) {
        err += dy;
        x0 += sx;
      }
      if (e2 <= dx) {
        err += dx;
        y0 += sy;
      }
    }
  }

  /**
   * Xiaolin Wu antialiased line with fractional endpoints, composited with
   * per-channel max (lighten) blending: coverage scales the incoming color's
   * RGB, so a partial fringe brightens dark pixels without ever darkening a
   * brighter (e.g. fading-trail) pixel beneath it.
   *
   * @param wrapX True on wrap topologies (x floorMod-wraps, matching set());
   *              false on bounce topologies, where fringe pixels outside
   *              [0, width) are dropped rather than wrapped onto the
   *              opposite face edge
   */
  public void lineWu(double x0, double y0, double x1, double y1, int argb, boolean wrapX) {
    final boolean steep = Math.abs(y1 - y0) > Math.abs(x1 - x0);
    if (steep) {
      double t = x0; x0 = y0; y0 = t;
      t = x1; x1 = y1; y1 = t;
    }
    if (x0 > x1) {
      double t = x0; x0 = x1; x1 = t;
      t = y0; y0 = y1; y1 = t;
    }
    final double dx = x1 - x0;
    final double gradient = (dx > 0) ? (y1 - y0) / dx : 1;

    // First endpoint
    double xend = Math.round(x0);
    double yend = y0 + gradient * (xend - x0);
    double xgap = 1 - fpart(x0 + 0.5);
    final int xpxl1 = (int) xend;
    final int ypxl1 = ipart(yend);
    plotWu(xpxl1, ypxl1, argb, (1 - fpart(yend)) * xgap, steep, wrapX);
    plotWu(xpxl1, ypxl1 + 1, argb, fpart(yend) * xgap, steep, wrapX);
    double intery = yend + gradient;

    // Second endpoint
    xend = Math.round(x1);
    yend = y1 + gradient * (xend - x1);
    xgap = fpart(x1 + 0.5);
    final int xpxl2 = (int) xend;
    final int ypxl2 = ipart(yend);
    plotWu(xpxl2, ypxl2, argb, (1 - fpart(yend)) * xgap, steep, wrapX);
    plotWu(xpxl2, ypxl2 + 1, argb, fpart(yend) * xgap, steep, wrapX);

    for (int x = xpxl1 + 1; x < xpxl2; ++x) {
      plotWu(x, ipart(intery), argb, 1 - fpart(intery), steep, wrapX);
      plotWu(x, ipart(intery) + 1, argb, fpart(intery), steep, wrapX);
      intery += gradient;
    }
  }

  /** Plot one Wu pixel: coverage-scaled color, max-blended. In steep mode the
   *  traversal axes are swapped back to canvas coordinates here. */
  private void plotWu(int x, int y, int argb, double coverage, boolean steep, boolean wrapX) {
    if (coverage <= 0) {
      return;
    }
    if (coverage > 1) {
      coverage = 1;
    }
    if (steep) {
      final int t = x; x = y; y = t;
    }
    final int r = (int) (((argb >> 16) & 0xff) * coverage);
    final int g = (int) (((argb >> 8) & 0xff) * coverage);
    final int b = (int) ((argb & 0xff) * coverage);
    final int c = 0xff000000 | (r << 16) | (g << 8) | b;
    if (wrapX) {
      setMax(x, y, c);
    } else {
      setMaxClamped(x, y, c);
    }
  }

  private static int ipart(double v) {
    return (int) Math.floor(v);
  }

  private static double fpart(double v) {
    return v - Math.floor(v);
  }

  /**
   * Overwrite this canvas's pixels with another canvas's contents. Both
   * canvases must have identical dimensions.
   */
  public void copyFrom(SurfaceCanvas other) {
    if (other.width != this.width || other.height != this.height) {
      throw new IllegalArgumentException(
        "SurfaceCanvas.copyFrom dimension mismatch: " + other.width + "x" + other.height
        + " -> " + this.width + "x" + this.height);
    }
    System.arraycopy(other.pixels, 0, this.pixels, 0, this.pixels.length);
  }

  /**
   * Copy the canvas onto an Apotheneum orientation, column-major. Works for
   * both the cube (Orientation spans all 4 faces = 200 columns) and the
   * cylinder (120 columns). Columns shortened by doors are guarded via
   * column.points.length. Copies min(width, orientation width) columns and
   * min(height, column length) rows.
   *
   * @param orientation Target surface
   * @param colors Pattern color buffer (this.colors in the pattern)
   */
  public void copyTo(Apotheneum.Orientation orientation, int[] colors) {
    final Apotheneum.Column[] columns = orientation.columns();
    final int w = Math.min(this.width, columns.length);
    for (int x = 0; x < w; ++x) {
      final Apotheneum.Column column = columns[x];
      final int h = Math.min(this.height, column.points.length);
      for (int y = 0; y < h; ++y) {
        colors[column.points[y].index] = this.pixels[y * this.width + x];
      }
    }
  }

  /**
   * Copy variant with a brightness multiplier and optional vertical flip.
   * mult scales each pixel's RGB (clamped to 255, alpha forced opaque);
   * flipY reads the canvas upside down without touching its contents.
   *
   * @param orientation Target surface
   * @param colors Pattern color buffer (this.colors in the pattern)
   * @param mult RGB multiplier, typically 0..1 for dimming, >1 to boost;
   *             <=0 renders opaque black
   * @param flipY Read rows bottom-to-top (e.g. cave/inversion modes)
   */
  public void copyTo(Apotheneum.Orientation orientation, int[] colors, double mult, boolean flipY) {
    final Apotheneum.Column[] columns = orientation.columns();
    final int w = Math.min(this.width, columns.length);
    for (int x = 0; x < w; ++x) {
      final Apotheneum.Column column = columns[x];
      final int h = Math.min(this.height, column.points.length);
      for (int y = 0; y < h; ++y) {
        final int src = this.pixels[(flipY ? (this.height - 1 - y) : y) * this.width + x];
        colors[column.points[y].index] = scale(src, mult);
      }
    }
  }

  private static int scale(int argb, double mult) {
    // Negative mult would produce negative channel ints whose bits bleed
    // across channel boundaries when shifted; render black instead
    if ((mult <= 0) || ((argb & 0x00ffffff) == 0)) {
      return 0xff000000;
    }
    final int r = Math.min(255, (int) (((argb >> 16) & 0xff) * mult));
    final int g = Math.min(255, (int) (((argb >> 8) & 0xff) * mult));
    final int b = Math.min(255, (int) ((argb & 0xff) * mult));
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }
}
