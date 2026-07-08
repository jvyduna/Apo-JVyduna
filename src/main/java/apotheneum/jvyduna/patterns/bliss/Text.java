package apotheneum.jvyduna.patterns.bliss;

import apotheneum.Apotheneum;
import apotheneum.jvyduna.util.FontCrt;
import apotheneum.jvyduna.util.FontSeg;
import apotheneum.jvyduna.util.FontZx;
import apotheneum.jvyduna.util.GlyphFont;
import apotheneum.jvyduna.util.PixelFont5;
import apotheneum.jvyduna.util.PixelFont5Font;
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
 * Left/Right); {@code flip} mirrors it for faces viewed from the far side.
 *
 * A surface's interior and exterior are physically distinct LED layers that face
 * opposite ways, so identical content reads mirror-reversed from the far side.
 * {@code autoProj} (on by default) classifies each LED from the model
 * ({@link Apotheneum} interior collections) and mirrors the interior layer's
 * sampling so the same block reads correctly from BOTH sides at once; use it on a
 * single-surface (both-layer) view. {@code flipProj} inverts which layer is
 * canonical (or, with auto off, mirrors everything), and {@code flipRead} swaps
 * the mirror to the vertical axis so 90°-rotated text reads the intended
 * direction. The bitmap font is selectable via {@code fontSel}.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Text")
@LXComponent.Description("Multi-line bitmap-font text with scale/rotation/translation and interior/exterior legibility; type \\n for line breaks")
public class Text extends LXPattern implements UIDeviceControls<Text> {

  /** Selectable bitmap fonts. Constant NAMES are the .lxp serialization key — keep stable. */
  public enum Font {
    FIVE("5px", PixelFont5Font.INSTANCE),
    ZX("ZX", FontZx.INSTANCE),
    CRT("CRT", FontCrt.INSTANCE),
    SEG("7Seg", FontSeg.INSTANCE);

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

  public final BooleanParameter flip =
    new BooleanParameter("Flip", false)
    .setDescription("Mirror the horizontal axis (per-face calibration)");

  public final EnumParameter<Font> fontSel =
    new EnumParameter<Font>("Font", Font.FIVE)
    .setDescription("Bitmap font");

  public final BooleanParameter autoProj =
    new BooleanParameter("Auto", true)
    .setDescription("Auto-mirror the interior LED layer so both sides read");

  public final BooleanParameter flipProj =
    new BooleanParameter("FlipProj", false)
    .setDescription("Swap which layer is front (Auto on) / mirror the whole projection (Auto off)");

  public final BooleanParameter flipRead =
    new BooleanParameter("FlipRead", false)
    .setDescription("Mirror vertically instead of horizontally (reading direction for 90° text)");

  /** Per-global-index interior-layer flags, built once from the model (no render-loop alloc). */
  private boolean[] interior;
  private Object cachedCube;
  private Object cachedCylinder;

  public Text(LX lx) {
    super(lx);
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
    addParameter("flip", this.flip);
    addParameter("fontSel", this.fontSel);
    addParameter("autoProj", this.autoProj);
    addParameter("flipProj", this.flipProj);
    addParameter("flipRead", this.flipRead);
  }

  /**
   * Build (or rebuild) the interior-layer lookup from the model's per-surface
   * collections. Lazy because {@link Apotheneum}'s statics are populated by its
   * own global LX listener, whose ordering vs. a pattern lifecycle hook isn't
   * guaranteed. Rebuilds only when the model's cube/cylinder references change.
   */
  private void ensureInterior() {
    if (this.interior != null
      && this.cachedCube == Apotheneum.cube
      && this.cachedCylinder == Apotheneum.cylinder) {
      return;
    }
    this.interior = new boolean[getLX().getModel().size];
    if (Apotheneum.exists && Apotheneum.hasInterior) {
      if (Apotheneum.cube != null && Apotheneum.cube.interior != null) {
        for (Apotheneum.Cube.Face face : Apotheneum.cube.interior.faces) {
          markInterior(face.columns);
        }
      }
      if (Apotheneum.cylinder != null && Apotheneum.cylinder.interior != null) {
        markInterior(Apotheneum.cylinder.interior.columns);
      }
    }
    this.cachedCube = Apotheneum.cube;
    this.cachedCylinder = Apotheneum.cylinder;
  }

  private void markInterior(Apotheneum.Column[] columns) {
    for (Apotheneum.Column column : columns) {
      for (LXPoint p : column.points) {
        if (p.index < this.interior.length) {
          this.interior[p.index] = true;
        }
      }
    }
  }

  @Override
  protected void run(double deltaMs) {
    final String raw = this.text.getString();
    if (raw == null || raw.isEmpty()) {
      setColors(LXColor.BLACK);
      return;
    }
    final String[] lines = raw.replace("\\n", "\n").split("\n", -1);

    ensureInterior();
    final GlyphFont glyphFont = this.fontSel.getEnum().font;
    final int FW = glyphFont.width();
    final int FH = glyphFont.height();

    final float vRange = this.model.yRange;
    final boolean useX = this.model.xRange >= this.model.zRange;
    final float hRange = useX ? this.model.xRange : this.model.zRange;
    final float hMin = useX ? this.model.xMin : this.model.zMin;
    final float vMin = this.model.yMin;

    final float em = this.scale.getValuef() * vRange / FH;
    if (em <= 0 || vRange <= 0) {
      setColors(LXColor.BLACK);
      return;
    }

    final float cellW = FW + this.charSpacing.getValuef();
    final float lineH = FH + this.lineSpacing.getValuef();
    int maxLen = 0;
    for (String ln : lines) {
      maxLen = Math.max(maxLen, ln.length());
    }
    final int nLines = lines.length;
    final float wEm = (maxLen > 0) ? (maxLen * cellW - this.charSpacing.getValuef()) : 0;
    final float hEm = nLines * lineH - this.lineSpacing.getValuef();

    final float anchorH = hMin + this.xOffset.getValuef() * hRange;
    final float anchorV = vMin + this.yOffset.getValuef() * vRange;

    final double theta = Math.toRadians(this.rotation.getValuef());
    final float cos = (float) Math.cos(theta);
    final float sin = (float) Math.sin(theta);
    final boolean flipOn = this.flip.isOn();
    final boolean autoOn = this.autoProj.isOn();
    final boolean flipProjOn = this.flipProj.isOn();
    final boolean flipReadOn = this.flipRead.isOn();
    final boolean[] interiorFlags = this.interior;

    final int color = LXColor.hsb(
      this.hue.getValue(), this.saturation.getValue(), this.brightness.getValue());

    for (LXPoint p : this.model.points) {
      // A back-facing (interior) layer reads mirror-reversed; correct it with one
      // reflection taken about the text anchor (not the model center) so the block
      // stays co-located on both layers. flipRead picks the vertical axis instead,
      // which for rotated text flips the reading direction (they differ by 180°).
      final boolean interiorPt = (p.index < interiorFlags.length) && interiorFlags[p.index];
      final boolean mirrorThis = autoOn ? (interiorPt ^ flipProjOn) : flipProjOn;
      final boolean hRef = flipOn ^ (mirrorThis && !flipReadOn);
      final boolean vRef = mirrorThis && flipReadOn;
      final float hWorld = useX ? p.x : p.z;
      float dh = hWorld - anchorH;
      if (hRef) {
        dh = -dh;
      }
      float dv = p.y - anchorV;
      if (vRef) {
        dv = -dv;
      }
      // world -> local text pixels (inverse of world = anchor + R(theta)*em*(u,-vDown))
      final float u = (dh * cos + dv * sin) / em;
      final float vDown = (dh * sin - dv * cos) / em;
      final float bx = u + wEm / 2f;
      final float by = vDown + hEm / 2f;
      if (bx < 0 || bx >= wEm || by < 0 || by >= hEm) {
        this.colors[p.index] = LXColor.BLACK;
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
      newDropMenu(pattern.fontSel, 130)
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
      newButton(pattern.flip)).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);
    addColumn(uiDevice, "Proj",
      newButton(pattern.autoProj),
      newButton(pattern.flipProj),
      newButton(pattern.flipRead)).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);
    addColumn(uiDevice, "Color",
      newKnob(pattern.hue),
      newKnob(pattern.saturation),
      newKnob(pattern.brightness)).setChildSpacing(6);
  }
}
