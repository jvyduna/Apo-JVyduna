package apotheneum.jvyduna.patterns;

import apotheneum.jvyduna.util.PixelFont5;
import heronarts.glx.ui.component.UITextBox;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
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
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Text")
@LXComponent.Description("Multi-line 5px bitmap-font text with scale/rotation/translation; type \\n for line breaks")
public class Text extends LXPattern implements UIDeviceControls<Text> {

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
  }

  @Override
  protected void run(double deltaMs) {
    final String raw = this.text.getString();
    if (raw == null || raw.isEmpty()) {
      setColors(LXColor.BLACK);
      return;
    }
    final String[] lines = raw.replace("\\n", "\n").split("\n", -1);

    final float vRange = this.model.yRange;
    final boolean useX = this.model.xRange >= this.model.zRange;
    final float hRange = useX ? this.model.xRange : this.model.zRange;
    final float hMin = useX ? this.model.xMin : this.model.zMin;
    final float vMin = this.model.yMin;

    final float em = this.scale.getValuef() * vRange / PixelFont5.HEIGHT;
    if (em <= 0 || vRange <= 0) {
      setColors(LXColor.BLACK);
      return;
    }

    final float cellW = PixelFont5.WIDTH + this.charSpacing.getValuef();
    final float lineH = PixelFont5.HEIGHT + this.lineSpacing.getValuef();
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
    final boolean flip = this.flip.isOn();

    final int color = LXColor.hsb(
      this.hue.getValue(), this.saturation.getValue(), this.brightness.getValue());

    for (LXPoint p : this.model.points) {
      float hWorld = useX ? p.x : p.z;
      if (flip) {
        hWorld = 2f * hMin + hRange - hWorld;
      }
      final float dh = hWorld - anchorH;
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
      final int line = (int) (by / lineH);
      final float wy = by - line * lineH;
      if (line >= nLines || wy >= PixelFont5.HEIGHT) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }
      final int col = (int) (bx / cellW);
      final float wx = bx - col * cellW;
      final String ln = lines[line];
      if (col >= ln.length() || wx >= PixelFont5.WIDTH) {
        this.colors[p.index] = LXColor.BLACK;
        continue;
      }
      this.colors[p.index] =
        PixelFont5.pixel(ln.charAt(col), (int) wx, (int) wy) ? color : LXColor.BLACK;
    }
  }

  @Override
  public void buildDeviceControls(UI ui, UIDevice uiDevice, Text pattern) {
    uiDevice.setLayout(UIDevice.Layout.HORIZONTAL, 4);
    addColumn(uiDevice, 130, "Text (\\n = line break)",
      new UITextBox(0, 0, 130, 16, pattern.text).setEmptyValueAllowed(true)
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
    addColumn(uiDevice, "Color",
      newKnob(pattern.hue),
      newKnob(pattern.saturation),
      newKnob(pattern.brightness)).setChildSpacing(6);
  }
}
