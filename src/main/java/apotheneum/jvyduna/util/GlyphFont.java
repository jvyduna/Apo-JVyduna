package apotheneum.jvyduna.util;

/**
 * A monospaced bitmap font addressable by character and pixel coordinate. The
 * contract mirrors {@link PixelFont5}: characters fold to uppercase, unknown
 * characters render as '?', and out-of-bounds pixel queries return false.
 *
 * Implementations expose a fixed {@link #width()} x {@link #height()} cell (the
 * font is monospaced) so callers can lay text out in world/pixel units without
 * per-glyph metrics. No LX dependencies — fonts can be previewed as ASCII.
 */
public interface GlyphFont {

  /** Cell width in pixels (columns), same for every glyph. */
  int width();

  /** Cell height in pixels (rows), same for every glyph. */
  int height();

  /** Whether glyph pixel (gx from left, gy from top) is lit for this char. */
  boolean pixel(char c, int gx, int gy);

  /** Short human label for UI (e.g. dropdown menu). */
  String label();
}
