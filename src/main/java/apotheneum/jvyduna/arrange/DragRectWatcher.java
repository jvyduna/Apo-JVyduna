package apotheneum.jvyduna.arrange;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import heronarts.glx.ui.UI2dComponent;
import heronarts.glx.ui.UIObject;
import heronarts.lx.LX;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.timeline.UITimeline;
import heronarts.lx.studio.ui.timeline.lane.UIClipLane;
import heronarts.lx.studio.ui.timeline.lane.UILane;

/**
 * Makes a cross-lane mouse drag create the rectangular selection, without
 * intercepting any host mouse events (GLX routes each event to exactly one
 * component and drags stay captured by the pressed lane — there is no
 * observe-only hook). Instead this POLLS once per UI frame, entirely via
 * public API:
 *
 *  - GLFW cursor position + left-button state on the focused window's
 *    public handle; window→UI coordinate scale = getUIWidth()/windowWidth
 *    (the same formula WindowEngine uses internally for cursorScale).
 *  - The focused lane's selection via SelectionReader.
 *
 * Rubber-band fingerprint: on a fresh selection drag the host CLEARS the
 * lane's selection at mouse-press (clearMouseSelection) and re-establishes
 * it once the drag passes the threshold — so hasSelection false→true while
 * the left button stays down identifies a rubber-band (event move-drags and
 * handle drags keep hasSelection true throughout and never match). While
 * the drag is live, the lane row under the pointer sets the rect's vertical
 * extents each frame; release freezes the rect; any later click dismisses.
 *
 * Known heuristic edge: composition lanes establish a selection when an
 * event is click-selected, which can match the fingerprint — a visible,
 * click-dismissable rect in the worst case. A very fast flick can miss the
 * one-frame cleared window (no rect starts; Cmd+Shift+J/K still works).
 */
class DragRectWatcher {

  private enum State { IDLE, PRESSED, DRAGGING }

  private final LXStudio.UI ui;
  private final SelectionReader reader;
  private final ArrangeToolsController controller;

  private State state = State.IDLE;
  private boolean wasDown = false;
  private boolean sawCleared = false;
  private UIClipLane pressedLane = null;
  private boolean dead = false;

  DragRectWatcher(LXStudio.UI ui, SelectionReader reader, ArrangeToolsController controller) {
    this.ui = ui;
    this.reader = reader;
    this.controller = controller;
  }

  /** Called once per UI frame from the controller's loop task. */
  void poll() {
    if (this.dead) {
      return;
    }
    try {
      pollImpl();
    } catch (Throwable t) {
      this.dead = true;
      LX.error(t, "[ArrangeTools] Drag-rect watcher disabled (keyboard Cmd+Shift+J/K still works)");
    }
  }

  private void pollImpl() {
    final boolean alt =
      this.ui.lx.windowEngine.getFocusedWindow() == this.ui.lx.windowEngine.altWindow;
    final long handle = (alt
      ? this.ui.lx.windowEngine.altWindow
      : this.ui.lx.windowEngine.mainWindow).getHandle();
    final boolean down =
      GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

    final SelectionReader.LaneSelection sel = this.reader.read(this.ui);

    switch (this.state) {
      case IDLE -> {
        if (down && !this.wasDown) {
          final SelectionReader.FocusedLane focused = this.reader.readFocused(this.ui);
          if (focused != null) {
            this.state = State.PRESSED;
            this.pressedLane = focused.uiLane;
            this.sawCleared = (sel == null);
          }
        }
      }
      case PRESSED -> {
        if (!down) {
          this.state = State.IDLE; // plain click, no drag
        } else if (sel == null) {
          this.sawCleared = true; // host cleared at press: rubber-band pending
        } else if (this.sawCleared && sel.uiLane == this.pressedLane) {
          this.state = State.DRAGGING; // fresh rubber-band confirmed
        }
      }
      case DRAGGING -> {
        if (!down) {
          this.state = State.IDLE; // release: rect (if any) stays frozen
        } else if (sel == null || sel.uiLane != this.pressedLane) {
          this.state = State.IDLE; // selection vanished mid-drag (e.g. Esc)
        } else {
          updateFromPointer(handle, alt, sel);
        }
      }
    }
    this.wasDown = down;
  }

  /** Extends the rect to the lane row under the pointer, each frame. */
  private void updateFromPointer(long handle, boolean alt, SelectionReader.LaneSelection sel) {
    final UITimeline timeline = timelineOf(sel.uiLane);
    if (timeline == null) {
      return;
    }
    final List<UILane> rows = new ArrayList<>();
    for (UIObject child : timeline.body.content) {
      if (child instanceof UILane) {
        rows.add((UILane) child);
      }
    }
    final int anchorRow = rows.indexOf(sel.uiLane);
    if (anchorRow < 0) {
      return;
    }

    final float uiY = cursorUiY(handle, alt);
    // Row under the pointer, clamped to the lane stack (dragging past the
    // top/bottom pins to the first/last lane)
    int pointerRow = anchorRow;
    if (uiY < rows.get(0).getAbsoluteY()) {
      pointerRow = 0;
    } else {
      pointerRow = rows.size() - 1;
      for (int i = 0; i < rows.size(); ++i) {
        final UILane row = rows.get(i);
        if (uiY < row.getAbsoluteY() + row.getHeight()) {
          pointerRow = i;
          break;
        }
      }
    }

    final int up = Math.max(0, anchorRow - pointerRow);
    final int downExtent = Math.max(0, pointerRow - anchorRow);
    this.controller.setDragRect(sel, up, downExtent);
  }

  /** Cursor y in UI coordinates: GLFW window coords x (uiHeight/windowHeight). */
  private float cursorUiY(long handle, boolean alt) {
    try (MemoryStack stack = MemoryStack.stackPush()) {
      final DoubleBuffer x = stack.mallocDouble(1);
      final DoubleBuffer y = stack.mallocDouble(1);
      GLFW.glfwGetCursorPos(handle, x, y);
      final IntBuffer w = stack.mallocInt(1);
      final IntBuffer h = stack.mallocInt(1);
      GLFW.glfwGetWindowSize(handle, w, h);
      final float uiHeight = alt
        ? this.ui.lx.windowEngine.altWindow.getUIHeight()
        : this.ui.lx.windowEngine.mainWindow.getUIHeight();
      final int windowHeight = h.get(0);
      return (windowHeight > 0) ? (float) (y.get(0) * (uiHeight / windowHeight)) : -1;
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
