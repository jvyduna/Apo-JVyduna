package apotheneum.jvyduna.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * CPU rasterizer for an OTF/TTF font file, for sampling antialiased text onto
 * LEDs. Loads the file's main face via {@link Font#createFont} (handles both
 * OpenType and TrueType) with kerning enabled, renders a multi-line block to a
 * grayscale coverage raster (only when the text/font/spacing changes — never
 * per frame), and answers bilinear coverage queries in the render loop.
 *
 * Headless-AWT only (BufferedImage + Graphics2D) — safe in Chromatik, which
 * sets {@code java.awt.headless=true} at startup. No LX dependencies; callers
 * do their own logging. Run {@code main} for an ASCII preview.
 */
public final class OtfTypeface {

  /** Raster line height target in px; LED text is sampled down from this. */
  public static final float RASTER_EM = 48f;

  /** Coverage rasters larger than this are refused (absurd text/spacing). */
  private static final int MAX_RASTER_DIM = 8192;

  private String loadedPath;
  private Font font;

  private int width;
  private int height;
  private float lineHeight;
  private byte[] coverage;

  /**
   * Load (or confirm) the font at this path. Cheap when the path is unchanged.
   * Returns whether a usable font is loaded; failures clear any prior raster.
   */
  public boolean load(String path) {
    if (path == null || path.isEmpty()) {
      unload(null);
      return false;
    }
    if (path.equals(this.loadedPath)) {
      return this.font != null;
    }
    try {
      final Font base = Font.createFont(Font.TRUETYPE_FONT, new File(path));
      final Map<TextAttribute, Object> attrs = new HashMap<>();
      attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
      attrs.put(TextAttribute.SIZE, RASTER_EM);
      unload(path);
      this.font = base.deriveFont(attrs);
      return true;
    } catch (Exception x) {
      unload(path);
      return false;
    }
  }

  private void unload(String path) {
    this.loadedPath = path;
    this.font = null;
    this.coverage = null;
    this.width = this.height = 0;
  }

  public boolean isLoaded() {
    return this.font != null;
  }

  public boolean hasRaster() {
    return this.coverage != null;
  }

  /** Raster width in px. */
  public int width() {
    return this.width;
  }

  /** Raster height in px. */
  public int height() {
    return this.height;
  }

  /** One line's height (ascent + descent) in raster px, excluding extra spacing. */
  public float lineHeight() {
    return this.lineHeight;
  }

  /**
   * Render the text block: left-aligned lines, with {@code leading} extra space
   * between lines as a fraction of the line height (0 = solid-set). Allocates —
   * call only on change. Returns whether a raster was produced.
   */
  public boolean render(String[] lines, float leading) {
    this.coverage = null;
    this.width = this.height = 0;
    if (this.font == null || lines.length == 0) {
      return false;
    }
    final FontRenderContext frc = new FontRenderContext(null, true, true);
    // TextLayout applies the KERNING attribute; FontMetrics.stringWidth may not
    final TextLayout[] layouts = new TextLayout[lines.length];
    float maxAdvance = 0, maxAscent = 0, maxDescent = 0;
    for (int i = 0; i < lines.length; ++i) {
      if (!lines[i].isEmpty()) {  // TextLayout rejects empty strings
        layouts[i] = new TextLayout(lines[i], this.font, frc);
        maxAdvance = Math.max(maxAdvance, layouts[i].getAdvance());
        maxAscent = Math.max(maxAscent, layouts[i].getAscent());
        maxDescent = Math.max(maxDescent, layouts[i].getDescent());
      }
    }
    if (maxAdvance <= 0 || maxAscent + maxDescent <= 0) {
      return false;
    }
    this.lineHeight = maxAscent + maxDescent;
    final float extraPx = leading * this.lineHeight;
    final float pitch = this.lineHeight + extraPx;
    final int w = (int) Math.ceil(maxAdvance);
    final int h = Math.max(1, Math.round(lines.length * pitch - extraPx));
    if (w > MAX_RASTER_DIM || h > MAX_RASTER_DIM) {
      return false;
    }

    final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g.setColor(Color.WHITE);
    float y = maxAscent;
    for (TextLayout layout : layouts) {
      if (layout != null) {
        layout.draw(g, 0, y);
      }
      y += pitch;
    }
    g.dispose();

    final int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
    final byte[] cov = new byte[w * h];
    for (int i = 0; i < argb.length; ++i) {
      cov[i] = (byte) (argb[i] >>> 24);
    }
    this.width = w;
    this.height = h;
    this.coverage = cov;
    return true;
  }

  /**
   * Bilinear coverage in [0,1] at raster coordinate (x, y); 0 outside the
   * raster. Pixel centers are at integer + 0.5. Allocation-free.
   */
  public float sample(float x, float y) {
    final float fx = x - 0.5f;
    final float fy = y - 0.5f;
    final int x0 = (int) Math.floor(fx);
    final int y0 = (int) Math.floor(fy);
    final float tx = fx - x0;
    final float ty = fy - y0;
    final float c00 = cov(x0, y0);
    final float c10 = cov(x0 + 1, y0);
    final float c01 = cov(x0, y0 + 1);
    final float c11 = cov(x0 + 1, y0 + 1);
    final float top = c00 + tx * (c10 - c00);
    final float bottom = c01 + tx * (c11 - c01);
    return top + ty * (bottom - top);
  }

  private float cov(int x, int y) {
    if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
      return 0f;
    }
    return (this.coverage[y * this.width + x] & 0xff) / 255f;
  }

  public static void main(String[] args) {
    final String path = (args.length > 0) ? args[0]
      : "/System/Library/Fonts/Supplemental/Arial.ttf";
    final String text = (args.length > 1) ? args[1] : "AVATAR To. 0123";
    final OtfTypeface otf = new OtfTypeface();
    if (!otf.load(path)) {
      System.out.println("Failed to load: " + path);
      return;
    }
    if (!otf.render(text.split("\n"), 0)) {
      System.out.println("Nothing rendered");
      return;
    }
    System.out.println("Raster " + otf.width() + "x" + otf.height()
      + ", lineHeight " + otf.lineHeight());
    // Downsample to a console-sized ASCII luminance dump (2:1 aspect correction)
    final String ramp = " .:-=+*#%@";
    final int cols = Math.min(120, otf.width());
    final float sx = otf.width() / (float) cols;
    final int rows = (int) (otf.height() / (sx * 2f)) + 1;
    for (int r = 0; r < rows; ++r) {
      final StringBuilder sb = new StringBuilder();
      for (int c = 0; c < cols; ++c) {
        final float v = otf.sample((c + 0.5f) * sx, (r + 0.5f) * sx * 2f);
        sb.append(ramp.charAt(Math.min(ramp.length() - 1, (int) (v * ramp.length()))));
      }
      System.out.println(sb);
    }
  }
}
