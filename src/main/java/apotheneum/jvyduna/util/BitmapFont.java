package apotheneum.jvyduna.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Instance-based monospaced {@link GlyphFont} whose glyphs are authored as ASCII
 * art ('#' = lit). Unlike {@link PixelFont5} (a fixed 5x5 static table) the cell
 * height is arbitrary — {@link #put} takes a varargs of row strings, so a font
 * can be 5, 7, or any number of rows tall. Width is fixed per font; each row
 * string is left-aligned into the cell (bit {@code width-1-x} = leftmost column,
 * matching PixelFont5's convention).
 *
 * Characters fold to uppercase, unknown characters render as '?', out-of-bounds
 * pixel queries return false. No LX dependencies — use {@link #preview} to
 * eyeball glyphs as ASCII, and {@link #assertCovers} to verify the full set.
 */
public final class BitmapFont implements GlyphFont {

  /** Characters every font is expected to define (besides '?'). */
  public static final String REQUIRED =
    " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#:-./";

  private final String label;
  private final int width;
  private final int height;
  private final Map<Character, int[]> glyphs = new HashMap<>();

  public BitmapFont(String label, int width, int height) {
    this.label = label;
    this.width = width;
    this.height = height;
  }

  /**
   * Define one glyph from {@code height} row strings, top to bottom. Throws if
   * the wrong number of rows is supplied so authoring mistakes fail loudly.
   */
  public BitmapFont put(char c, String... rows) {
    if (rows.length != this.height) {
      throw new IllegalArgumentException(
        "Glyph '" + c + "' has " + rows.length + " rows, expected " + this.height);
    }
    final int[] mask = new int[this.height];
    for (int y = 0; y < this.height; ++y) {
      int m = 0;
      final String row = rows[y];
      for (int x = 0; x < this.width; ++x) {
        if (x < row.length() && row.charAt(x) == '#') {
          m |= (1 << (this.width - 1 - x));
        }
      }
      mask[y] = m;
    }
    this.glyphs.put(c, mask);
    return this;
  }

  /** Row masks (top to bottom) for a character; '?' if unknown. */
  public int[] rows(char c) {
    final int[] g = this.glyphs.get(Character.toUpperCase(c));
    return (g != null) ? g : this.glyphs.get('?');
  }

  @Override
  public int width() {
    return this.width;
  }

  @Override
  public int height() {
    return this.height;
  }

  @Override
  public boolean pixel(char c, int gx, int gy) {
    if (gx < 0 || gx >= this.width || gy < 0 || gy >= this.height) {
      return false;
    }
    final int[] r = rows(c);
    return (r != null) && (r[gy] & (1 << (this.width - 1 - gx))) != 0;
  }

  @Override
  public String label() {
    return this.label;
  }

  /** Throw if any REQUIRED char (or '?') is missing — call from a font's static block. */
  public BitmapFont assertCovers() {
    final StringBuilder missing = new StringBuilder();
    for (int i = 0; i < REQUIRED.length(); ++i) {
      final char c = REQUIRED.charAt(i);
      if (!this.glyphs.containsKey(c)) {
        missing.append(c == ' ' ? "<space>" : String.valueOf(c));
      }
    }
    if (!this.glyphs.containsKey('?')) {
      missing.append('?');
    }
    if (missing.length() > 0) {
      throw new IllegalStateException("Font '" + this.label + "' missing glyphs: " + missing);
    }
    return this;
  }

  /** Dump sample words as ASCII to stdout for legibility eyeballing. */
  public static void preview(GlyphFont font, String sample) {
    for (String word : sample.split(" ")) {
      for (int gy = 0; gy < font.height(); ++gy) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); ++i) {
          for (int gx = 0; gx < font.width(); ++gx) {
            sb.append(font.pixel(word.charAt(i), gx, gy) ? '#' : '.');
          }
          sb.append(' ');
        }
        System.out.println(sb);
      }
      System.out.println();
    }
  }
}
