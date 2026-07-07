package apotheneum.jvyduna.util;

import java.util.HashMap;
import java.util.Map;

/**
 * A tiny 5x5 uppercase bitmap font for LED text that stays legible down to
 * 5 pixels tall. Glyphs are authored as ASCII art ('#' = lit) and parsed into
 * 5-bit row masks (bit 4 = leftmost column). Uppercase only; lowercase folds
 * to uppercase. Covers A-Z, 0-9, space, and '# : - . /'; unknown characters
 * render as '?'.
 *
 * No LX dependencies — run {@code main} to preview every glyph as ASCII and
 * eyeball legibility before wiring it into a pattern.
 */
public final class PixelFont5 {

  public static final int WIDTH = 5;
  public static final int HEIGHT = 5;

  private static final Map<Character, int[]> GLYPHS = new HashMap<>();

  private static void put(char c, String r0, String r1, String r2, String r3, String r4) {
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
    GLYPHS.put(c, mask);
  }

  static {
    put(' ', ".....", ".....", ".....", ".....", ".....");
    put('A', ".###.", "#...#", "#####", "#...#", "#...#");
    put('B', "####.", "#...#", "####.", "#...#", "####.");
    put('C', ".####", "#....", "#....", "#....", ".####");
    put('D', "####.", "#...#", "#...#", "#...#", "####.");
    put('E', "#####", "#....", "###..", "#....", "#####");
    put('F', "#####", "#....", "###..", "#....", "#....");
    put('G', ".####", "#....", "#..##", "#...#", ".####");
    put('H', "#...#", "#...#", "#####", "#...#", "#...#");
    put('I', "#####", "..#..", "..#..", "..#..", "#####");
    put('J', "..###", "...#.", "...#.", "#..#.", ".##..");
    put('K', "#...#", "#..#.", "###..", "#..#.", "#...#");
    put('L', "#....", "#....", "#....", "#....", "#####");
    put('M', "#...#", "##.##", "#.#.#", "#...#", "#...#");
    put('N', "#...#", "##..#", "#.#.#", "#..##", "#...#");
    put('O', ".###.", "#...#", "#...#", "#...#", ".###.");
    put('P', "####.", "#...#", "####.", "#....", "#....");
    put('Q', ".###.", "#...#", "#.#.#", "#..#.", ".##.#");
    put('R', "####.", "#...#", "####.", "#..#.", "#...#");
    put('S', ".####", "#....", ".###.", "....#", "####.");
    put('T', "#####", "..#..", "..#..", "..#..", "..#..");
    put('U', "#...#", "#...#", "#...#", "#...#", ".###.");
    put('V', "#...#", "#...#", "#...#", ".#.#.", "..#..");
    put('W', "#...#", "#...#", "#.#.#", "#.#.#", ".#.#.");
    put('X', "#...#", ".#.#.", "..#..", ".#.#.", "#...#");
    put('Y', "#...#", ".#.#.", "..#..", "..#..", "..#..");
    put('Z', "#####", "...#.", "..#..", ".#...", "#####");
    put('0', ".###.", "#..##", "#.#.#", "##..#", ".###.");
    put('1', "..#..", ".##..", "..#..", "..#..", ".###.");
    put('2', ".###.", "#...#", "..##.", ".#...", "#####");
    put('3', "####.", "....#", ".###.", "....#", "####.");
    put('4', "#..#.", "#..#.", "#####", "...#.", "...#.");
    put('5', "#####", "#....", "####.", "....#", "####.");
    put('6', ".###.", "#....", "####.", "#...#", ".###.");
    put('7', "#####", "....#", "...#.", "..#..", ".#...");
    put('8', ".###.", "#...#", ".###.", "#...#", ".###.");
    put('9', ".###.", "#...#", ".####", "....#", ".###.");
    put('#', ".#.#.", "#####", ".#.#.", "#####", ".#.#.");
    put(':', ".....", "..#..", ".....", "..#..", ".....");
    put('-', ".....", ".....", "#####", ".....", ".....");
    put('.', ".....", ".....", ".....", ".....", "..#..");
    put('/', "....#", "...#.", "..#..", ".#...", "#....");
    put('?', ".###.", "#...#", "..##.", ".....", "..#..");
  }

  /** Row masks (top to bottom) for a character; '?' if unknown. */
  public static int[] rows(char c) {
    int[] g = GLYPHS.get(Character.toUpperCase(c));
    return (g != null) ? g : GLYPHS.get('?');
  }

  /** Whether glyph pixel (gx from left, gy from top) is lit for this char. */
  public static boolean pixel(char c, int gx, int gy) {
    if (gx < 0 || gx >= WIDTH || gy < 0 || gy >= HEIGHT) {
      return false;
    }
    return (rows(c)[gy] & (1 << (WIDTH - 1 - gx))) != 0;
  }

  public static void main(String[] args) {
    final String sample = (args.length > 0) ? args[0]
      : "CRASH HIHAT RIDE SNARE TOMS KICK BASS SYNTH VOCALS G# MAJOR MINOR 0123456789";
    for (String word : sample.split(" ")) {
      for (int gy = 0; gy < HEIGHT; ++gy) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); ++i) {
          for (int gx = 0; gx < WIDTH; ++gx) {
            sb.append(pixel(word.charAt(i), gx, gy) ? '#' : '.');
          }
          sb.append(' ');
        }
        System.out.println(sb);
      }
      System.out.println();
    }
  }

  private PixelFont5() {}
}
