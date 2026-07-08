package apotheneum.jvyduna.util;

/**
 * A 4x7 seven-segment "digital" font — the calculator / LED-clock look, a nod to
 * the MIR light-organ and HoldClock heritage. Digits are canonical; letters use
 * the conventional seven-segment alphabet, so some pairs are intentionally
 * ambiguous the way a real seven-segment display is (0/O, 2/Z, 5/S, H/X, U/W).
 *
 * Glyphs are generated from a per-character segment set (segments A-G in the
 * standard layout) rather than hand-drawn; punctuation that has no segment
 * representation is authored directly. Run {@code main} to preview.
 */
public final class FontSeg {

  //   AAAA        row0: A (top)
  //  F    B       rows1-2: F (left), B (right)
  //  F    B
  //   GGGG        row3: G (middle)
  //  E    C       rows4-5: E (left), C (right)
  //  E    C
  //   DDDD        row6: D (bottom)
  private static String[] seg(String on) {
    final boolean a = on.indexOf('A') >= 0, b = on.indexOf('B') >= 0,
      c = on.indexOf('C') >= 0, d = on.indexOf('D') >= 0, e = on.indexOf('E') >= 0,
      f = on.indexOf('F') >= 0, g = on.indexOf('G') >= 0;
    final String top = (f ? "#" : ".") + ".." + (b ? "#" : ".");
    final String bot = (e ? "#" : ".") + ".." + (c ? "#" : ".");
    return new String[] {
      a ? ".##." : "....",
      top, top,
      g ? ".##." : "....",
      bot, bot,
      d ? ".##." : "....",
    };
  }

  public static final BitmapFont INSTANCE = new BitmapFont("7Seg", 4, 7)
    .put(' ', seg(""))
    .put('A', seg("ABCEFG")).put('B', seg("CDEFG")).put('C', seg("ADEF"))
    .put('D', seg("BCDEG")).put('E', seg("ADEFG")).put('F', seg("AEFG"))
    .put('G', seg("ACDEF")).put('H', seg("BCEFG")).put('I', seg("EF"))
    .put('J', seg("BCDE")).put('K', seg("CEFG")).put('L', seg("DEF"))
    .put('M', seg("ABCEF")).put('N', seg("CEG")).put('O', seg("ABCDEF"))
    .put('P', seg("ABEFG")).put('Q', seg("ABCFG")).put('R', seg("EG"))
    .put('S', seg("ACDFG")).put('T', seg("DEFG")).put('U', seg("BCDEF"))
    .put('V', seg("CDE")).put('W', seg("BCDEF")).put('X', seg("BCEFG"))
    .put('Y', seg("BCDFG")).put('Z', seg("ABDEG"))
    .put('0', seg("ABCDEF")).put('1', seg("BC")).put('2', seg("ABDEG"))
    .put('3', seg("ABCDG")).put('4', seg("BCFG")).put('5', seg("ACDFG"))
    .put('6', seg("ACDEFG")).put('7', seg("ABC")).put('8', seg("ABCDEFG"))
    .put('9', seg("ABCDFG"))
    .put('-', seg("G"))
    .put('#', ".#.#", "####", ".#.#", "####", ".#.#", "....", "....")
    .put(':', "....", "....", ".##.", "....", ".##.", "....", "....")
    .put('.', "....", "....", "....", "....", "....", ".##.", ".##.")
    .put('/', "...#", "..#.", "..#.", ".#..", ".#..", "#...", "#...")
    .put('?', ".##.", "#..#", "...#", "..#.", ".#..", "....", ".#..")
    .assertCovers();

  private FontSeg() {}

  public static void main(String[] args) {
    BitmapFont.preview(INSTANCE, args.length > 0 ? args[0]
      : "TEXT 0123456789 CLOCK G#MAJOR A:B-C.D/E");
  }
}
