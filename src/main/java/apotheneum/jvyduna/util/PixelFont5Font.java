package apotheneum.jvyduna.util;

/**
 * {@link GlyphFont} adapter for the static 5x5 {@link PixelFont5}. Lets the
 * original font participate in font selection without touching PixelFont5 (which
 * other patterns, e.g. HoldClock, still use through its static API).
 */
public final class PixelFont5Font implements GlyphFont {

  public static final PixelFont5Font INSTANCE = new PixelFont5Font();

  private PixelFont5Font() {}

  @Override
  public int width() {
    return PixelFont5.WIDTH;
  }

  @Override
  public int height() {
    return PixelFont5.HEIGHT;
  }

  @Override
  public boolean pixel(char c, int gx, int gy) {
    return PixelFont5.pixel(c, gx, gy);
  }

  @Override
  public String label() {
    return "5px";
  }
}
