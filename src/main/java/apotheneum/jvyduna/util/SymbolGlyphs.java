package apotheneum.jvyduna.util;

/**
 * A small set of abstracted, NON-denominational religious / spiritual glyphs
 * authored as bitmap art (the same '#' = lit ASCII-art idiom as
 * {@link PixelFont5}) and parsed into row bit-masks. This is the SHARED symbol
 * asset for the piece: one reusable table of glyphs that recurs, mutated, across
 * movements rather than being re-implemented per song. See SymbolGlyphs.md for
 * how rafters / chrome / distance each reuse it differently.
 *
 * <p>Glyphs are {@value #SIZE}x{@value #SIZE}. That size is a CURATE choice: 11
 * is the smallest odd square that gives a true center column/row (needed for the
 * symmetric symbols — cross, star, sun, ankh) while still reading as a symbol
 * rather than a blob on the Apotheneum LED pitch. On a 50-column cube face an
 * unscaled glyph is ~1/5 of the face width; scale it up with the {@code scale}
 * arg for hero moments (see {@link #render}).
 *
 * <p>The set: {@link Symbol#CROSS}, {@link Symbol#EYE} (eye of providence),
 * {@link Symbol#SUN} (sun / halo), {@link Symbol#FISH} (ichthys),
 * {@link Symbol#CRESCENT}, {@link Symbol#OM}, {@link Symbol#ANKH},
 * {@link Symbol#STAR} (six-point), {@link Symbol#TREE} (tree of life). OM and
 * FISH are the hardest to read at this pitch and are the strongest CURATE
 * candidates for a hand-tuned redraw (see the .md).
 *
 * <p>No LX dependencies beyond {@link SurfaceCanvas} — run {@link #main} to
 * preview every glyph as ASCII and eyeball legibility before wiring it into a
 * pattern. All glyph tables are {@code static final} and the render / morph
 * methods allocate nothing.
 */
public final class SymbolGlyphs {

  /** Glyph bounding box, square. See class doc for why 11 (CURATE). */
  public static final int SIZE = 11;

  /**
   * The shared symbol set. Ordinal order is the authoring order and is stable —
   * downstream sequencing (e.g. a morph chain across a song) may rely on it.
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

  // Row bit-masks, one int per row, top (y=0) to bottom. Bit (SIZE-1-x) is the
  // pixel at column x, so bit SIZE-1 is the leftmost column — matching
  // PixelFont5's convention. Indexed by Symbol.ordinal().
  private static final int[][] MASKS = new int[Symbol.values().length][];

  private static void put(Symbol s, String... rows) {
    final int[] mask = new int[SIZE];
    for (int y = 0; y < SIZE; ++y) {
      int m = 0;
      final String row = (y < rows.length) ? rows[y] : "";
      for (int x = 0; x < SIZE; ++x) {
        if (x < row.length() && row.charAt(x) == '#') {
          m |= (1 << (SIZE - 1 - x));
        }
      }
      mask[y] = m;
    }
    MASKS[s.ordinal()] = mask;
  }

  static {
    // Latin cross — vertical bar + upper crossbeam.
    put(Symbol.CROSS,
      "....###....",
      "....###....",
      "....###....",
      ".#########.",
      ".#########.",
      "....###....",
      "....###....",
      "....###....",
      "....###....",
      "....###....",
      "....###....");

    // Eye of providence — almond eye with a pupil. (The enclosing triangle is
    // dropped; it does not read at 11px. CURATE: add rays for the full motif.)
    put(Symbol.EYE,
      "...........",
      "...........",
      "..#######..",
      ".##.....##.",
      "#...###...#",
      "#..#####..#",
      "#...###...#",
      ".##.....##.",
      "..#######..",
      "...........",
      "...........");

    // Sun / halo — radiant disk with eight rays.
    put(Symbol.SUN,
      ".....#.....",
      ".#...#...#.",
      "..#.###.#..",
      "...#####...",
      "..#######..",
      "#.#######.#",
      "..#######..",
      "...#####...",
      "..#.###.#..",
      ".#...#...#.",
      ".....#.....");

    // Ichthys — vesica body pointing left, crossed tail on the right.
    // CURATE: the tail cross is legible up close but muddies at distance.
    put(Symbol.FISH,
      "...........",
      "...........",
      "......###..",
      "...###...#.",
      ".##.....#.#",
      "#.......#.#",
      ".##.....#.#",
      "...###...#.",
      "......###..",
      "...........",
      "...........");

    // Crescent — waxing moon opening to the right.
    put(Symbol.CRESCENT,
      "....###....",
      "..##...#...",
      ".##........",
      ".#.........",
      "##.........",
      "##.........",
      "##.........",
      ".#.........",
      ".##........",
      "..##...#...",
      "....###....");

    // Om (ॐ) — abstract stylization: crescent + bindu dot on top over a curled
    // body. CURATE: this is the weakest glyph at 11px; strongest candidate for a
    // hand-drawn redraw or for rendering as the actual Unicode char via Text.
    put(Symbol.OM,
      ".....#.....",
      "...#####...",
      "...........",
      "..###......",
      ".#...#.###.",
      "....#.#..#.",
      "..##...#.#.",
      ".#...#..#..",
      ".#....#.#..",
      ".#...#.#...",
      "..###.#....");

    // Ankh — hollow loop over a crossbar and stem.
    put(Symbol.ANKH,
      "...#####...",
      "..##...##..",
      "..#.....#..",
      "..##...##..",
      ".#########.",
      ".#########.",
      "....###....",
      "....###....",
      "....###....",
      "....###....",
      "....###....");

    // Six-point star (hexagram) — two interlocking triangles, outline.
    put(Symbol.STAR,
      ".....#.....",
      "....#.#....",
      "...#...#...",
      "###########",
      "..#.....#..",
      ".#.......#.",
      "..#.....#..",
      "###########",
      "...#...#...",
      "....#.#....",
      ".....#.....");

    // Tree of life — canopy, trunk, spreading roots.
    put(Symbol.TREE,
      "...#####...",
      "..#######..",
      ".#########.",
      ".#########.",
      "..#######..",
      "....###....",
      "....###....",
      "....###....",
      "...#.#.#...",
      "..#..#..#..",
      ".#...#...#.");
  }

  // Cached enum array so all() is allocation-free. Treat as READ-ONLY.
  private static final Symbol[] ALL = Symbol.values();

  /**
   * The full glyph set in stable ordinal order. Allocation-free: returns a
   * shared array — do not mutate it.
   */
  public static Symbol[] all() {
    return ALL;
  }

  /** Row bit-masks (top to bottom) for a glyph; shared, do not mutate. */
  public static int[] rows(Symbol s) {
    return MASKS[s.ordinal()];
  }

  /** Whether glyph pixel (gx from left, gy from top) is lit. */
  public static boolean pixel(Symbol s, int gx, int gy) {
    if (gx < 0 || gx >= SIZE || gy < 0 || gy >= SIZE) {
      return false;
    }
    return (MASKS[s.ordinal()][gy] & (1 << (SIZE - 1 - gx))) != 0;
  }

  /**
   * Draw a glyph centered at (cx, cy) into a canvas, upscaled by {@code scale}
   * (integer nearest-neighbor block), lighten-blended (MAX) so it composites
   * over existing canvas content without darkening it. Allocation-free.
   *
   * @param canvas render target
   * @param s      glyph
   * @param cx     center column (canvas coords; x wraps in SurfaceCanvas)
   * @param cy     center row
   * @param argb   color; RGB is used, alpha ignored (canvas is opaque)
   * @param scale  integer upscale, clamped to >= 1
   */
  public static void render(SurfaceCanvas canvas, Symbol s, int cx, int cy, int argb, int scale) {
    draw(canvas, s, s, 0.0, cx, cy, argb, scale, SurfaceCanvas.Blend.MAX);
  }

  /** {@link #render} with an explicit blend mode. */
  public static void render(SurfaceCanvas canvas, Symbol s, int cx, int cy, int argb, int scale,
      SurfaceCanvas.Blend blend) {
    draw(canvas, s, s, 0.0, cx, cy, argb, scale, blend);
  }

  /**
   * Cross-dissolve between two glyphs by {@code t} in [0, 1]: at t=0 only
   * {@code from} shows, at t=1 only {@code to}, in between each lit cell fades
   * with per-cell alpha (from-cell coverage 1-t, to-cell coverage t, summed and
   * clamped). This is a bitmap alpha blend — CURATE: a true vector / distance-
   * field morph that slides strokes between shapes is a later upgrade; for the
   * first pass the dissolve reads as a "condensing / transmuting" symbol, which
   * is exactly the rafters-to-chrome intent. Allocation-free.
   *
   * @param t interpolation, clamped to [0, 1]
   */
  public static void morph(SurfaceCanvas canvas, Symbol from, Symbol to, double t,
      int cx, int cy, int argb, int scale) {
    draw(canvas, from, to, t, cx, cy, argb, scale, SurfaceCanvas.Blend.MAX);
  }

  /** {@link #morph} with an explicit blend mode. */
  public static void morph(SurfaceCanvas canvas, Symbol from, Symbol to, double t,
      int cx, int cy, int argb, int scale, SurfaceCanvas.Blend blend) {
    draw(canvas, from, to, t, cx, cy, argb, scale, blend);
  }

  // Core: alpha-blended nearest-neighbor blit of the from/to cross-dissolve.
  private static void draw(SurfaceCanvas canvas, Symbol from, Symbol to, double t,
      int cx, int cy, int argb, int scale, SurfaceCanvas.Blend blend) {
    if (scale < 1) {
      scale = 1;
    }
    final double tt = (t < 0) ? 0 : (t > 1 ? 1 : t);
    final int[] a = MASKS[from.ordinal()];
    final int[] b = MASKS[to.ordinal()];
    final int span = SIZE * scale;
    final int x0 = cx - span / 2;
    final int y0 = cy - span / 2;
    final int cr = (argb >> 16) & 0xff;
    final int cg = (argb >> 8) & 0xff;
    final int cb = argb & 0xff;
    for (int gy = 0; gy < SIZE; ++gy) {
      final int ra = a[gy];
      final int rb = b[gy];
      final int py0 = y0 + gy * scale;
      for (int gx = 0; gx < SIZE; ++gx) {
        final int bit = 1 << (SIZE - 1 - gx);
        double cov = ((ra & bit) != 0 ? (1.0 - tt) : 0.0)
                   + ((rb & bit) != 0 ? tt : 0.0);
        if (cov <= 0) {
          continue;
        }
        if (cov > 1) {
          cov = 1;
        }
        final int col = 0xff000000
          | ((int) (cr * cov) << 16)
          | ((int) (cg * cov) << 8)
          | ((int) (cb * cov));
        final int px0 = x0 + gx * scale;
        for (int sy = 0; sy < scale; ++sy) {
          for (int sx = 0; sx < scale; ++sx) {
            canvas.setBlend(px0 + sx, py0 + sy, col, blend);
          }
        }
      }
    }
  }

  /** ASCII preview of every glyph (mirrors {@link PixelFont5#main}), plus a
   *  CROSS -> STAR morph strip so the dissolve can be eyeballed. */
  public static void main(String[] args) {
    for (Symbol s : all()) {
      System.out.println(s.name() + ":");
      for (int gy = 0; gy < SIZE; ++gy) {
        StringBuilder sb = new StringBuilder();
        for (int gx = 0; gx < SIZE; ++gx) {
          sb.append(pixel(s, gx, gy) ? '#' : '.');
        }
        System.out.println(sb);
      }
      System.out.println();
    }

    // Morph preview: threshold the cross-dissolve coverage at 0.5 for ASCII.
    System.out.println("MORPH CROSS -> STAR (t = 0, .25, .5, .75, 1):");
    final double[] ts = { 0.0, 0.25, 0.5, 0.75, 1.0 };
    for (int gy = 0; gy < SIZE; ++gy) {
      StringBuilder sb = new StringBuilder();
      for (double t : ts) {
        for (int gx = 0; gx < SIZE; ++gx) {
          final boolean fa = pixel(Symbol.CROSS, gx, gy);
          final boolean fb = pixel(Symbol.STAR, gx, gy);
          final double cov = (fa ? (1 - t) : 0) + (fb ? t : 0);
          sb.append(cov >= 0.5 ? '#' : '.');
        }
        sb.append("  ");
      }
      System.out.println(sb);
    }
  }

  private SymbolGlyphs() {}
}
