package apotheneum.jvyduna;

import java.util.ArrayList;
import java.util.List;

import apotheneum.Apotheneum;
import heronarts.glx.GLX;
import heronarts.glx.View;
import heronarts.glx.shader.Text3d;
import heronarts.glx.ui.UI;
import heronarts.glx.ui.text3d.UI3dText;
import heronarts.lx.LX;
import heronarts.lx.LXPlugin;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.studio.LXStudio;

/**
 * MIR-verify labeling method #2: floats rasterized text labels in the 3D
 * preview under each bar of the light-organ template — the counterpart to the
 * in-LED {@code Text} pattern labels. Billboarded (always faces the camera)
 * and drawn on top (no depth test) so they stay readable over the LEDs.
 *
 * A pattern cannot draw in the 3D preview; only a plugin gets the UI hooks.
 * Enable "MIR Verify Labels" in Chromatik (left pane → CONTENT → PLUGINS). The
 * layout is hardcoded to match the template: 6 drum labels on the Left face
 * (reversed crash..kick), 3 instrument labels on the Front face; each is matched
 * to its bar by face-local horizontal position.
 *
 * Labels are (re)built lazily in {@code onDraw} whenever the Apotheneum model
 * is present — NOT once at startup — so they appear regardless of project
 * load order and follow model changes.
 */
@LXPlugin.Name("MIR Verify Labels")
public class MirVerifyLabelsPlugin implements LXStudio.Plugin {

  private static final String[] DRUM_LABELS =
    { "CRASH", "HIHAT", "RIDE", "SNARE", "TOMS", "KICK" };
  private static final String[] INSTRUMENT_LABELS =
    { "BASS", "SYNTH", "VOCALS" };

  @Override
  public void initialize(LX lx) {
    Apotheneum.initialize(lx);
  }

  @Override
  public void initializeUI(LXStudio lx, LXStudio.UI ui) {}

  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    // Add unconditionally; each component builds its labels when the Apotheneum
    // model is available (see BarLabels.onDraw).
    ui.preview.addComponent(new BarLabels(lx));
    ui.previewAux.addComponent(new BarLabels(lx));
  }

  /** A UI3dText that rebuilds its labels from Apotheneum.cube on demand. */
  private static class BarLabels extends UI3dText {

    private final List<Text3d.Label> mine = new ArrayList<>();
    private Object builtFor = null;  // the Cube reference we last built for

    BarLabels(GLX glx) {
      super(glx);
    }

    @Override
    protected void onDraw(UI ui, View view) {
      final Object cube = Apotheneum.exists ? Apotheneum.cube : null;
      if (cube != this.builtFor) {
        for (Text3d.Label label : this.mine) {
          removeLabel(label);
        }
        this.mine.clear();
        if (cube != null) {
          build();
        }
        this.builtFor = cube;
      }
      super.onDraw(ui, view);
    }

    private void build() {
      final Apotheneum.Cube.Orientation ext = Apotheneum.cube.exterior;
      float cx = 0, cz = 0;
      for (Apotheneum.Cube.Face f : ext.faces) {
        cx += f.model.cx;
        cz += f.model.cz;
      }
      cx /= ext.faces.length;
      cz /= ext.faces.length;
      addFaceLabels(ext.left, false, DRUM_LABELS, cx, cz);      // Left: Z axis
      addFaceLabels(ext.front, true, INSTRUMENT_LABELS, cx, cz); // Front: X axis
    }

    /** One billboarded label centered under each bar on a face. */
    private void addFaceLabels(Apotheneum.Cube.Face face, boolean axisIsX,
                               String[] labels, float cx, float cz) {
      final LXModel fm = face.model;
      final float hMin = axisIsX ? fm.xMin : fm.zMin;
      final float hRange = axisIsX ? fm.xRange : fm.zRange;
      if (hRange <= 0) {
        return;
      }
      final int n = labels.length;
      final float size = 0.06f * fm.yRange;
      final float drop = 0.08f * fm.yRange;
      final float push = 0.05f * hRange;

      for (int i = 0; i < n; ++i) {
        final float target = (i + 0.5f) / n;
        Apotheneum.Column best = null;
        float bestD = Float.MAX_VALUE;
        for (Apotheneum.Column col : face.columns) {
          final float h = axisIsX ? col.model.cx : col.model.cz;
          final float d = Math.abs((h - hMin) / hRange - target);
          if (d < bestD) {
            bestD = d;
            best = col;
          }
        }
        if (best == null) {
          continue;
        }
        LXPoint bottom = null;
        for (LXPoint p : best.points) {
          if (bottom == null || p.y < bottom.y) {
            bottom = p;
          }
        }
        if (bottom == null) {
          continue;
        }
        float dx = bottom.x - cx;
        float dz = bottom.z - cz;
        float len = (float) Math.hypot(dx, dz);
        if (len < 1e-3f) {
          len = 1f;
        }
        final Text3d.Label label = addLabel(labels[i])
          .setPosition(bottom.x + dx / len * push, bottom.y - drop, bottom.z + dz / len * push)
          .setOrientation(Text3d.TextOrientation.CAMERA)
          .setTextScale(Text3d.TextScale.WORLD)
          .setTextSize(size)
          .setHorizontalAlignment(Text3d.HorizontalAlignment.CENTER)
          .setVerticalAlignment(Text3d.VerticalAlignment.MIDDLE)
          .setDepthTest(false)
          .setTextColorARGB(0xffffffff)
          .setBackgroundColorARGB(0xb0000000);
        this.mine.add(label);
      }
    }
  }
}
