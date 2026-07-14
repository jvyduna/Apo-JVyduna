package apotheneum.jvyduna.arrange;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.UI2dContext;
import heronarts.glx.ui.UIObject;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.clip.Cursor;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.timeline.UITimeline;
import heronarts.lx.studio.ui.timeline.lane.UIClipLane;
import heronarts.lx.studio.ui.timeline.lane.UILane;

/**
 * A full-window, click-through overlay layer that draws the rectangular
 * multi-lane selection (LaneRect): the anchor lane keeps the host's own
 * selection highlight; this layer tints the OTHER member lanes over the
 * same time range, matching the host's selection color.
 *
 * Geometry is fully public API (verified on arrange HEAD): the timeline's
 * Lens converts cursors to content pixels, getAbsoluteX/Y fold in both
 * scroll axes automatically, and lane rows are enumerated from
 * timeline.body.content in visual (== composition) order. Drawing is
 * manually scissored to the timeline body viewport since a top-level layer
 * doesn't inherit the scroll container's clipping.
 *
 * Mouse transparency: contains() returns false, so the parent dispatch
 * loop never routes events here — everything falls through to the editor.
 */
class RangeHighlightOverlay extends UI2dContext {

  /** Everything needed to draw one frame, computed by the controller. */
  static final class View {
    final UIClipLane anchorUiLane;
    final int extentUp, extentDown;
    final Cursor min, max;

    View(UIClipLane anchorUiLane, int extentUp, int extentDown, Cursor min, Cursor max) {
      this.anchorUiLane = anchorUiLane;
      this.extentUp = extentUp;
      this.extentDown = extentDown;
      this.min = min;
      this.max = max;
    }
  }

  private final LXStudio.UI lxUi;
  private final Supplier<View> viewSupplier;
  private final Supplier<float[]> windowSize;
  private boolean wasActive = false;

  RangeHighlightOverlay(LXStudio.UI ui, Supplier<View> viewSupplier, Supplier<float[]> windowSize) {
    super(ui, 0, 0, Math.max(1, windowSize.get()[0]), Math.max(1, windowSize.get()[1]));
    this.lxUi = ui;
    this.viewSupplier = viewSupplier;
    this.windowSize = windowSize;
    ui.addLoopTask(deltaMs -> onLoop());
  }

  @Override
  public boolean contains(float x, float y) {
    // Click-through: never a mouse target; drawing is unaffected
    return false;
  }

  private void onLoop() {
    // Track window resizes
    float[] size = this.windowSize.get();
    if (size[0] >= 1 && size[1] >= 1 && (size[0] != getWidth() || size[1] != getHeight())) {
      setSize(size[0], size[1]);
    }
    // Repaint while active (selection, scroll, and zoom all move under us),
    // plus one final repaint when deactivating to clear the last highlight
    final boolean active = (this.viewSupplier.get() != null);
    if (active || this.wasActive) {
      redraw();
    }
    this.wasActive = active;
  }

  @Override
  protected void onDraw(heronarts.glx.ui.UI ui, VGraphics vg) {
    // CRITICAL: clear our framebuffer to transparent before drawing.
    // VGraphics' shared view clears only DEPTH|STENCIL between renders
    // (VGraphics.java:392) — host contexts paint opaque backgrounds so
    // they never notice, but a transparent overlay ACCUMULATES: 0.2-alpha
    // rects composited every frame go opaque, and stale pixels persist
    // after the rect state clears. NVG_COPY writes the transparent fill
    // straight into the buffer; state resets at the next beginFrame but
    // we restore SOURCE_OVER explicitly anyway.
    final long nvg = vg.getHandle();
    org.lwjgl.nanovg.NanoVG.nvgGlobalCompositeOperation(nvg, org.lwjgl.nanovg.NanoVG.NVG_COPY);
    vg.beginPath();
    vg.fillColor(0x00000000);
    vg.rect(0, 0, getWidth(), getHeight());
    vg.fill();
    org.lwjgl.nanovg.NanoVG.nvgGlobalCompositeOperation(nvg, org.lwjgl.nanovg.NanoVG.NVG_SOURCE_OVER);

    final View view = this.viewSupplier.get();
    if (view == null) {
      return;
    }
    // Locate the owning timeline via the public container walk
    final UITimeline timeline = timelineOf(view.anchorUiLane);
    if (timeline == null) {
      return;
    }

    // Lane rows in visual order (matches composition lane order)
    final List<UILane> rows = new ArrayList<>();
    for (UIObject child : timeline.body.content) {
      if (child instanceof UILane) {
        rows.add((UILane) child);
      }
    }
    final int anchorRow = rows.indexOf(view.anchorUiLane);
    if (anchorRow < 0) {
      return;
    }
    final int lo = Math.max(0, anchorRow - view.extentUp);
    final int hi = Math.min(rows.size() - 1, anchorRow + view.extentDown);

    // Time range in content pixels; getAbsoluteX already folds in scroll
    final float x0 = timeline.lens.toPixelsFromCursor(view.min);
    final float x1 = timeline.lens.toPixelsFromCursor(view.max);
    final float w = Math.abs(x1 - x0);
    if (w <= 0) {
      return;
    }

    // Clip to the timeline body viewport (a top-level layer doesn't inherit
    // the scroll container's scissor)
    final float bodyX = timeline.body.getAbsoluteX();
    final float bodyY = timeline.body.getAbsoluteY();
    vg.scissorPush(bodyX, bodyY, timeline.body.getWidth(), timeline.body.getHeight());
    try {
      for (int i = lo; i <= hi; ++i) {
        if (i == anchorRow) {
          continue; // host draws the anchor's own selection highlight
        }
        final UILane row = rows.get(i);
        if (!row.isVisible()) {
          continue;
        }
        final float rx = row.getAbsoluteX() + Math.min(x0, x1);
        final float ry = row.getAbsoluteY();
        vg.beginPath();
        // Near-transparent tint: the host draws its selection UNDER lane
        // events, but this layer sits on top of everything — a heavier
        // alpha obscures the lanes. The border carries the shape.
        vg.fillColor(this.lxUi.theme.selectionColor.maskf(0.2f));
        vg.rect(rx, ry, w, row.getHeight());
        vg.fill();
      }

      // Border around the full rectangle (time range x lane block), so the
      // multi-lane selection reads as one crisp region. Recomputed every
      // frame, so it tracks pan/zoom/lane-resize exactly.
      final UILane top = rows.get(lo);
      final UILane bottom = rows.get(hi);
      final float bx = top.getAbsoluteX() + Math.min(x0, x1);
      final float by = top.getAbsoluteY();
      final float bh = (bottom.getAbsoluteY() + bottom.getHeight()) - by;
      vg.beginPath();
      vg.strokeColor(this.lxUi.theme.selectionColor);
      vg.strokeWidth(1.5f);
      vg.rect(bx + 0.5f, by + 0.5f, w - 1, bh - 1);
      vg.stroke();
    } finally {
      vg.scissorPop();
    }
  }

  private UITimeline timelineOf(UIClipLane lane) {
    UIObject obj = lane;
    while (obj != null) {
      if (obj instanceof UITimeline) {
        return (UITimeline) obj;
      }
      obj = (obj instanceof UI2dComponent) ? ((UI2dComponent) obj).getContainer() : null;
    }
    return null;
  }

}
