package apotheneum.jvyduna.util;

/**
 * An eight-glyph "alien" numeral set for a base-8 (octal) counter, drawn in the
 * same 5x5 cell as {@link PixelFont5} so the two can share a blit path. There
 * are no real Arabic numerals here — the glyphs are picked to read as an
 * unfamiliar sign system at LED pitch, deliberately avoiding shapes that scan
 * as Latin letters, digits, or common math symbols (a lone pipe reads as '1',
 * '+ - = / &lt; &gt;' as operators, curved brackets as set operators).
 *
 * The family (indexed by octal value 0..7):
 * <ul>
 * <li>0 — an upside-down T (⊥): a centered stem into a bottom bar</li>
 * <li>1 / 7 — a diagonal meeting a full horizontal bar (bottom / top mirror)</li>
 * <li>2 / 3 / 6 — three rotated-L corner hooks (top-left, bottom-right,
 *     top-right; the upright bottom-left L is skipped so none reads as 'L')</li>
 * <li>4 / 5 — filled triangles (up / down), clearly pictographic</li>
 * </ul>
 *
 * Glyphs are authored as ASCII art ('#' = lit) and parsed into 5-bit row masks
 * (bit 4 = leftmost column), matching {@link PixelFont5}'s encoding. No LX
 * dependencies — run {@code main} to preview every glyph and eyeball legibility
 * and mutual distinctness before/while tuning on hardware.
 */
public final class AlienGlyph8 {

  public static final int WIDTH = 5;
  public static final int HEIGHT = 5;

  /** Base of the counting system these glyphs represent. */
  public static final int BASE = 8;

  private static final int[][] GLYPHS = new int[BASE][];

  private static void put(int value, String r0, String r1, String r2, String r3, String r4) {
    final String[] rows = { r0, r1, r2, r3, r4 };
    final int[] mask = new int[HEIGHT];
    for (int y = 0; y < HEIGHT; ++y) {
      int m = 0;
      final String row = rows[y];
      for (int x = 0; x < WIDTH; ++x) {
        if (x < row.length() && row.charAt(x) == '#') {
          m |= (1 << (WIDTH - 1 - x));
        }
      }
      mask[y] = m;
    }
    GLYPHS[value] = mask;
  }

  static {
    put(0, "..#..", "..#..", "..#..", "..#..", "#####"); // ⊥ upside-down T
    put(1, "#....", ".#...", "..#..", "...#.", "#####"); // diagonal + bottom bar
    put(2, "####.", "#....", "#....", "#....", "....."); // rotated-L, top-left corner
    put(3, ".....", "....#", "....#", "....#", ".####"); // rotated-L, bottom-right corner
    put(4, ".....", "..#..", ".###.", "#####", "....."); // filled triangle, up
    put(5, ".....", "#####", ".###.", "..#..", "....."); // filled triangle, down
    put(6, ".####", "....#", "....#", "....#", "....."); // rotated-L, top-right corner
    put(7, "#####", "...#.", "..#..", ".#...", "#...."); // diagonal + top bar (mirror of 1)
  }

  /** Whether glyph pixel (gx from left, gy from top) is lit for octal value. */
  public static boolean pixel(int value, int gx, int gy) {
    if (value < 0 || value >= BASE || gx < 0 || gx >= WIDTH || gy < 0 || gy >= HEIGHT) {
      return false;
    }
    return (GLYPHS[value][gy] & (1 << (WIDTH - 1 - gx))) != 0;
  }

  public static void main(String[] args) {
    for (int gy = 0; gy < HEIGHT; ++gy) {
      StringBuilder sb = new StringBuilder();
      for (int v = 0; v < BASE; ++v) {
        for (int gx = 0; gx < WIDTH; ++gx) {
          sb.append(pixel(v, gx, gy) ? '#' : '.');
        }
        sb.append("  ");
      }
      System.out.println(sb);
    }
  }

  private AlienGlyph8() {}
}
