package apotheneum.jvyduna.patterns.bliss;

import apotheneum.Apotheneum;
import apotheneum.jvyduna.util.FontCrt;
import apotheneum.jvyduna.util.FontSeg;
import apotheneum.jvyduna.util.FontZx;
import apotheneum.jvyduna.util.GlyphFont;
import apotheneum.jvyduna.util.OtfTypeface;
import apotheneum.jvyduna.util.PixelFont5;
import apotheneum.jvyduna.util.PixelFont5Font;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UITextBox;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.studio.LXStudio.UI;
import heronarts.lx.studio.ui.device.UIDevice;
import heronarts.lx.studio.ui.device.UIDeviceControls;

/**
 * Renders multi-line text in a 5px-tall bitmap font ({@link PixelFont5}) into
 * the pattern's view, with scale, rotation, translation and line spacing. Built
 * for the MIR-verify light organ (labeling vertical bars, showing the key), but
 * general — drop it on any view and type a string.
 *
 * The text string is a {@link StringParameter} (serializes into the .lxp and is
 * settable by the mir-compose generator). It's edited in the control area via a
 * single-line text box; type literal {@code \n} for line breaks (Chromatik has
 * no multi-line text widget) — real newlines set programmatically also work.
 *
 * Layout is done in world units so rotation is undistorted regardless of face
 * aspect. Lines advance along the text's local +X after rotation: at 0° text
 * reads left-to-right with lines stacked downward; at 90° each line becomes a
 * column reading upward with successive lines to the right (bars, left to
 * right). Horizontal axis is auto-detected (X on Front/Back faces, Z on
 * Left/Right); {@code otherSides} flips that choice to write on the opposite
 * cube wall-pair (and rotate the cylinder projection 90° around Y).
 *
 * A surface's interior and exterior are physically distinct LED layers that face
 * opposite ways, so identical content reads mirror-reversed from the far side.
 * The {@code orient} mode derives each LED's emission normal from the model
 * ({@link Apotheneum} cube-face planes and cylinder column radials) and applies
 * one horizontal mirror to any back-facing layer so its text reads L-to-R for
 * viewers on its emission side. That single H-mirror is the only correction ever
 * needed — it fixes horizontal and both 90° vertical reading directions alike
 * (vertical direction is set by {@code rotation}); a vertical mirror never helps
 * legibility. {@code orient} = AUTO corrects every layer (default, max readership
 * per the legibility simulation in Text.md); EXTERIOR / INTERIOR correct only the
 * outward- or inward-emitting layers to serve one audience; RAW applies no
 * correction (literal projection). Bitmap font selectable via {@code fontSel};
 * an OTF/TTF file can be loaded from disk ({@code fontFile}) for kerned,
 * antialiased text.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Text")
@LXComponent.Description("Multi-line text (bitmap fonts or an OTF/TTF file) with scale/rotation/translation and auto interior/exterior legibility; type \\n for line breaks")
public class Text extends LXPattern implements UIDeviceControls<Text> {

  /** Selectable fonts. Constant NAMES are the .lxp serialization key — keep stable. */
  public enum Font {
    FIVE("5px", PixelFont5Font.INSTANCE),
    ZX("ZX", FontZx.INSTANCE),
    CRT("CRT", FontCrt.INSTANCE),
    SEG("7Seg", FontSeg.INSTANCE),
    OTF("File", null);  // OTF/TTF from fontFile; falls back to 5px when unloadable

    public final String label;
    public final GlyphFont font;

    private Font(String label, GlyphFont font) {
      this.label = label;
      this.font = font;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  /**
   * Projection legibility mode: which LED layers get the readability H-mirror so
   * text reads correctly for viewers on that layer's emission side. A back-facing
   * layer reads mirror-reversed without correction; the only correction ever
   * needed is a horizontal mirror (verified across horizontal and both 90°
   * reading directions — vertical direction is owned by the Rotate knob).
   * Constant NAMES are the .lxp serialization key — keep stable.
   */
  public enum Orient {
    AUTO("Auto"),          // correct every layer (max readership) — default
    EXTERIOR("Exterior"),  // correct only outward-emitting layers (the majority audience)
    INTERIOR("Interior"),  // correct only inward-emitting layers (inside audience)
    RAW("Raw");            // no correction; literal projection (back-facing appear mirrored)

    public final String label;

    private Orient(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public final StringParameter text =
    new StringParameter("Text", "")
    .setDescription("Text to render; \\n = line break");

  public final CompoundParameter scale =
    new CompoundParameter("Scale", 0.2, 0.02, 1.0)
    .setDescription("Letter height as a fraction of the view's height");

  public final CompoundParameter rotation =
    new CompoundParameter("Rotate", 0, 0, 360)
    .setUnits(CompoundParameter.Units.DEGREES)
    .setDescription("Rotation in degrees (90 = reads upward)");

  public final CompoundParameter xOffset =
    new CompoundParameter("X", 0.5, 0, 1)
    .setDescription("Horizontal anchor (block center) within the view");

  public final CompoundParameter yOffset =
    new CompoundParameter("Y", 0.5, 0, 1)
    .setDescription("Vertical anchor (block center) within the view");

  public final CompoundParameter lineSpacing =
    new CompoundParameter("Line", 2, 0, 40)
    .setDescription("Extra pixels between lines");

  public final CompoundParameter charSpacing =
    new CompoundParameter("Char", 1, 0, 10)
    .setDescription("Extra pixels between characters");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Text hue");

  public final CompoundParameter saturation =
    new CompoundParameter("Sat", 0, 0, 100)
    .setDescription("Text saturation (0 = white)");

  public final CompoundParameter brightness =
    new CompoundParameter("Bright", 100, 0, 100)
    .setDescription("Text brightness");

  public final BooleanParameter otherSides =
    new BooleanParameter("OtherSides", false)
    .setDescription("Write on the other wall-pair (rotate the cylinder projection 90° around Y)");

  public final EnumParameter<Font> fontSel =
    new EnumParameter<Font>("Font", Font.FIVE)
    .setDescription("Font: builtin bitmap fonts, or File = OTF/TTF from fontFile");

  public final StringParameter fontFile =
    new StringParameter("FontFile", "")
    .setDescription("Absolute path to an .otf/.ttf font file (used when Font = File)");

  public final EnumParameter<Orient> orient =
    new EnumParameter<Orient>("Orient", Orient.AUTO)
    .setDescription("Which LED layers get the readability mirror (Auto = all; Exterior/Interior = one audience; Raw = none)");

  /**
   * Per-global-index LED emission normals (x/z components; y is never a surface
   * normal here) plus an outward/inward layer tag, built once from the model
   * (no render-loop alloc). Zero/false for points not on a known Apotheneum
   * surface.
   */
  private float[] nx;
  private float[] nz;
  private boolean[] outward;
  private Object cachedCube;
  private Object cachedCylinder;

  /** OTF raster state; rebuilt only when text/path/spacing change, never per frame. */
  private final OtfTypeface otf = new OtfTypeface();
  private String rasterText;
  private String rasterPath;
  private float rasterSpacing = Float.NaN;
  private String failLogged;

  public Text(LX lx) {
    super(lx);
    // Idempotent; installs the model listener that populates Apotheneum.cube /
    // .cylinder. ApotheneumPattern subclasses do this too, but Text extends
    // LXPattern directly, so nothing else guarantees it in a Text-only session.
    Apotheneum.initialize(lx);
    addParameter("text", this.text);
    addParameter("scale", this.scale);
    addParameter("rotation", this.rotation);
    addParameter("xOffset", this.xOffset);
    addParameter("yOffset", this.yOffset);
    addParameter("lineSpacing", this.lineSpacing);
    addParameter("charSpacing", this.charSpacing);
    addParameter("hue", this.hue);
    addParameter("saturation", this.saturation);
    addParameter("brightness", this.brightness);
    addParameter("otherSides", this.otherSides);
    addParameter("fontSel", this.fontSel);
    addParameter("fontFile", this.fontFile);
    addParameter("orient", this.orient);
  }

  /**
   * Build (or rebuild) the per-LED emission-normal lookup from the model's
   * per-surface collections. Cube wall normals are a fixed world axis with sign
   * from the face plane vs. the cube center; cylinder normals are per-column
   * radials; interior layers emit opposite their exterior twins. Lazy because
   * {@link Apotheneum}'s statics are populated by its own global LX listener,
   * whose ordering vs. a pattern lifecycle hook isn't guaranteed. Rebuilds only
   * when the model's cube/cylinder references change.
   */
  private void ensureNormals() {
    if (this.nx != null
      && this.cachedCube == Apotheneum.cube
      && this.cachedCylinder == Apotheneum.cylinder) {
      return;
    }
    final int size = getLX().getModel().size;
    this.nx = new float[size];
    this.nz = new float[size];
    this.outward = new boolean[size];
    if (Apotheneum.exists) {
      if (Apotheneum.cube != null) {
        float centerX = 0, centerZ = 0;
        for (Apotheneum.Cube.Face face : Apotheneum.cube.exterior.faces) {
          centerX += face.model.cx;
          centerZ += face.model.cz;
        }
        centerX /= 4f;
        centerZ /= 4f;
        markCubeNormals(Apotheneum.cube.exterior, centerX, centerZ, 1f);
        if (Apotheneum.cube.interior != null) {
          markCubeNormals(Apotheneum.cube.interior, centerX, centerZ, -1f);
        }
      }
      if (Apotheneum.cylinder != null) {
        float centerX = 0, centerZ = 0;
        final Apotheneum.Column[] extColumns = Apotheneum.cylinder.exterior.columns;
        for (Apotheneum.Column column : extColumns) {
          centerX += column.model.cx;
          centerZ += column.model.cz;
        }
        centerX /= extColumns.length;
        centerZ /= extColumns.length;
        markCylinderNormals(Apotheneum.cylinder.exterior, centerX, centerZ, 1f);
        if (Apotheneum.cylinder.interior != null) {
          markCylinderNormals(Apotheneum.cylinder.interior, centerX, centerZ, -1f);
        }
      }
    }
    this.cachedCube = Apotheneum.cube;
    this.cachedCylinder = Apotheneum.cylinder;
  }

  /** emit = +1 for the exterior layer (outward), -1 for interior (inward). */
  private void markCubeNormals(Apotheneum.Cube.Orientation orientation,
    float centerX, float centerZ, float emit) {
    for (Apotheneum.Cube.Face face : orientation.faces) {
      // The wall's normal axis is the degenerate (near-zero-range) one
      final boolean xNormal = face.model.xRange < face.model.zRange;
      final float fnx = xNormal ? (face.model.cx >= centerX ? emit : -emit) : 0f;
      final float fnz = xNormal ? 0f : (face.model.cz >= centerZ ? emit : -emit);
      for (Apotheneum.Column column : face.columns) {
        setNormals(column, fnx, fnz, emit > 0);
      }
    }
  }

  private void markCylinderNormals(Apotheneum.Cylinder.Orientation orientation,
    float centerX, float centerZ, float emit) {
    for (Apotheneum.Column column : orientation.columns) {
      float dx = column.model.cx - centerX;
      float dz = column.model.cz - centerZ;
      final float len = (float) Math.sqrt(dx * dx + dz * dz);
      if (len > 0) {
        dx /= len;
        dz /= len;
      }
      setNormals(column, dx * emit, dz * emit, emit > 0);
    }
  }

  private void setNormals(Apotheneum.Column column, float fnx, float fnz, boolean outwardLayer) {
    for (LXPoint p : column.points) {
      if (p.index < this.nx.length) {
        this.nx[p.index] = fnx;
        this.nz[p.index] = fnz;
        this.outward[p.index] = outwardLayer;
      }
    }
  }

  /**
   * Load the OTF/TTF file and (re)build its coverage raster when the text,
   * path, or line spacing changed. Per-frame cost when nothing changed is a
   * couple of string equals — no allocation. Returns whether a raster is ready.
   */
  private boolean prepareOtf(String raw) {
    final String path = this.fontFile.getString();
    if (!this.otf.load(path)) {
      if (path != null && !path.isEmpty() && !path.equals(this.failLogged)) {
        LX.error("Text: could not load font file: " + path);
        this.failLogged = path;
      }
      this.rasterText = null;
      return false;
    }
    // Quantized so a modulated lineSpacing doesn't rebuild the raster every frame
    final float spacing = Math.round(this.lineSpacing.getValuef() * 4f) * 0.25f;
    if (!raw.equals(this.rasterText) || !path.equals(this.rasterPath)
      || spacing != this.rasterSpacing) {
      this.rasterText = raw;
      this.rasterPath = path;
      this.rasterSpacing = spacing;
      // lineSpacing knob is in 5px-font pixels; one such pixel = lineHeight/5
      this.otf.render(raw.replace("\\n", "\n").split("\n", -1), spacing / 5f);
    }
    return this.otf.hasRaster();
  }

  @Override
  protected void run(double deltaMs) {
    final String raw = this.text.getString();
    if (raw == null || raw.isEmpty()) {
      setColors(LXColor.BLACK);
      return;
    }
    final String[] lines = raw.replace("\\n", "\n").split("\n", -1);

    ensureNormals();

    // OTF mode samples a kerned, antialiased raster; builtins sample a glyph
    // grid. OTF falls back to the 5px builtin when no font file is loaded.
    final boolean otfMode = (this.fontSel.getEnum() == Font.OTF) && prepareOtf(raw);
    final GlyphFont glyphFont = otfMode ? null
      : (this.fontSel.getEnum().font != null) ? this.fontSel.getEnum().font : PixelFont5Font.INSTANCE;
    final int FW = otfMode ? 0 : glyphFont.width();
    final int FH = otfMode ? 0 : glyphFont.height();

    final float vRange = this.model.yRange;
    // Horizontal world axis is auto-detected (X on front/back, Z on left/right);
    // OtherSides flips it to write on the opposite wall-pair / rotate the cylinder
    // projection 90° around Y. The mirror rule below is keyed on useX, so far-side
    // legibility follows the selected axis automatically.
    final boolean baseUseX = this.model.xRange >= this.model.zRange;
    final boolean useX = this.otherSides.isOn() ? !baseUseX : baseUseX;
    final float hRange = useX ? this.model.xRange : this.model.zRange;
    final float hMin = useX ? this.model.xMin : this.model.zMin;
    final float vMin = this.model.yMin;

    // em = world units per font/raster pixel; scale = one line height as a
    // fraction of the view height in both modes
    final float em = this.scale.getValuef() * vRange
      / (otfMode ? this.otf.lineHeight() : FH);
    if (em <= 0 || vRange <= 0) {
      setColors(LXColor.BLACK);
      return;
    }

    final float cellW = otfMode ? 1f : FW + this.charSpacing.getValuef();
    final float lineH = otfMode ? 1f : FH + this.lineSpacing.getValuef();
    int maxLen = 0;
    for (String ln : lines) {
      maxLen = Math.max(maxLen, ln.length());
    }
    final int nLines = lines.length;
    final float wEm = otfMode ? this.otf.width()
      : (maxLen > 0) ? (maxLen * cellW - this.charSpacing.getValuef()) : 0;
    final float hEm = otfMode ? this.otf.height()
      : nLines * lineH - this.lineSpacing.getValuef();

    final float anchorH = hMin + this.xOffset.getValuef() * hRange;
    final float anchorV = vMin + this.yOffset.getValuef() * vRange;

    final double theta = Math.toRadians(this.rotation.getValuef());
    final float cos = (float) Math.cos(theta);
    final float sin = (float) Math.sin(theta);
    final Orient mode = this.orient.getEnum();
    final float[] nxArr = this.nx;
    final float[] nzArr = this.nz;
    final boolean[] outwardArr = this.outward;

    final int color = LXColor.hsb(
      this.hue.getValue(), this.saturation.getValue(), this.brightness.getValue());

    for (LXPoint p : this.model.points) {
      // World-planar text with +h rightward is legible only from the side its
      // readable normal N = up x h points to (h=+X -> N=-Z; h=+Z -> N=+X, anchored
      // empirically by exterior column order reading L-to-R from outside). A layer
      // whose emission faces away from N ("back") shows mirror-reversed text to its
      // viewers; the fix is one horizontal reflection, taken about the text anchor
      // (not the model center) so the block stays co-located on both layers. This
      // H-mirror is the only correction ever needed — it fixes horizontal and both
      // 90° reading directions alike (vertical direction is set by Rotate). Orient
      // picks WHICH back-facing layers get corrected: AUTO all, EXTERIOR only
      // outward-emitting, INTERIOR only inward, RAW none.
      final boolean back = (p.index < nzArr.length)
        && (useX ? (nzArr[p.index] > 0f) : (nxArr[p.index] < 0f));
      final boolean outwardPt = (p.index < outwardArr.length) && outwardArr[p.index];
      final boolean correct =
           (mode == Orient.AUTO)
        || (mode == Orient.EXTERIOR && outwardPt)
        || (mode == Orient.INTERIOR && !outwardPt);
      final boolean hRef = correct && back;
      final float hWorld = useX ? p.x : p.z;
      float dh = hWorld - anchorH;
      if (hRef) {
        dh = -dh;
      }
      final float dv = p.y - anchorV;
      // world -> local text pixels (inverse of world = anchor + R(theta)*em*(u,-vDown))
      final float u = (dh * cos + dv * sin) / em;
      final float vDown = (dh * sin - dv * cos) / em;
      final float bx = u + wEm / 2f;
      final float by = vDown + hEm / 2f;
      if (bx < 0 || bx >= wEm || by < 0 || by >= hEm) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }
      if (otfMode) {
        final float cov = this.otf.sample(bx, by);
        this.colors[p.index] =
          (cov <= 0f) ? LXColor.BLACK : LXColor.lerp(LXColor.BLACK, color, cov);
        continue;
      }
      final int line = (int) (by / lineH);
      final float wy = by - line * lineH;
      if (line >= nLines || wy >= FH) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }
      final int col = (int) (bx / cellW);
      final float wx = bx - col * cellW;
      final String ln = lines[line];
      if (col >= ln.length() || wx >= FW) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }
      this.colors[p.index] =
        glyphFont.pixel(ln.charAt(col), (int) wx, (int) wy) ? color : LXColor.BLACK;
    }
  }

  @Override
  public void buildDeviceControls(UI ui, UIDevice uiDevice, Text pattern) {
    uiDevice.setLayout(UIDevice.Layout.HORIZONTAL, 4);
    addColumn(uiDevice, 130, "Text (\\n = line break)",
      new UITextBox(0, 0, 130, 16, pattern.text).setEmptyValueAllowed(true),
      newDropMenu(pattern.fontSel, 130),
      new UIButton.Action(130, 16, "Font File...") {
        @Override
        public void onClick() {
          ui.lx.showOpenFileDialog(
            "Open Font",
            "Font File",
            new String[] { "otf", "ttf", "ttc" },
            null,
            (path) -> {
              pattern.fontFile.setValue(path);
              pattern.fontSel.setValue(Font.OTF);
            }
          );
        }
      }
    ).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);
    addColumn(uiDevice, "Layout",
      newKnob(pattern.scale),
      newKnob(pattern.rotation),
      newKnob(pattern.lineSpacing),
      newKnob(pattern.charSpacing)).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);
    addColumn(uiDevice, "Place",
      newKnob(pattern.xOffset),
      newKnob(pattern.yOffset),
      newButton(pattern.otherSides),
      newDropMenu(pattern.orient, 76)).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);
    addColumn(uiDevice, "Color",
      newKnob(pattern.hue),
      newKnob(pattern.saturation),
      newKnob(pattern.brightness)).setChildSpacing(6);
  }
}
