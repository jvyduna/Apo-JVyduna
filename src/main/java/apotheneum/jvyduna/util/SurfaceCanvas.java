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
