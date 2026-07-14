package apotheneum.jvyduna.arrange;

import java.lang.reflect.Field;

import heronarts.glx.ui.UIObject;
import heronarts.lx.LX;
import heronarts.lx.clip.Cursor;
import heronarts.lx.clip.LXClip;
import heronarts.lx.clip.LXClipLane;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.clip.UIClipEnvelope;
import heronarts.lx.studio.ui.timeline.lane.UIClipLane;
import heronarts.lx.studio.ui.timeline.lane.UIGridLane;

/**
 * The one reflection bridge in this plugin, per the composition-ui contract
 * in CLAUDE.md: READ-ONLY access to a handful of host UI internals, every
 * access hard-guarded. The host offers no public path to the focused clip
 * lane's selection range, so we reflect exactly:
 *
 *   UIClipLane#selectionStart/#selectionEnd/#hasSelection — the range
 *   UIClipEnvelope#lane                          — UI lane -> model lane
 *   UIGridLane#clip                              — UI lane -> model clip
 *
 * The focus walk itself is fully public: the OS-focused window via
 * GLX.windowEngine.getFocusedWindow(), its root via UI.getRoot(Window), and
 * the chain via UIObject.getFocusedChild(). Only the focused window's root
 * is walked — each window's focus chain (and any lane selection in it)
 * persists while the other window is used, so consulting the unfocused root
 * would let a stale selection hijack the shortcut.
 *
 * Never writes host state. Any failure (host refactor, stock host) marks
 * the bridge dead for the session: one LX.error, then every read returns
 * null and the features no-op.
 */
class SelectionReader {

  /** A focused clip lane, with or without an active selection range. */
  static final class FocusedLane {
    final UIClipLane uiLane;
    final LXClipLane<?> lane;
    final LXClip clip;
    final heronarts.glx.ui.UI.Window window;

    private FocusedLane(UIClipLane uiLane, LXClipLane<?> lane, LXClip clip,
                        heronarts.glx.ui.UI.Window window) {
      this.uiLane = uiLane;
      this.lane = lane;
      this.clip = clip;
      this.window = window;
    }
  }

  /** A focused clip lane with its selection range, normalized to min <= max. */
  static final class LaneSelection {
    final UIClipLane uiLane;
    final LXClipLane<?> lane;
    final LXClip clip;
    final heronarts.glx.ui.UI.Window window;
    final Cursor min;
    final Cursor max;

    private LaneSelection(UIClipLane uiLane, LXClipLane<?> lane, LXClip clip,
                          heronarts.glx.ui.UI.Window window, Cursor min, Cursor max) {
      this.uiLane = uiLane;
      this.lane = lane;
      this.clip = clip;
      this.window = window;
      this.min = min;
      this.max = max;
    }

    /** Same lane, different range (e.g. the tracker's virtual range). */
    LaneSelection withRange(Cursor min, Cursor max) {
      return new LaneSelection(this.uiLane, this.lane, this.clip, this.window, min, max);
    }
  }

  private boolean dead = false;

  private Field fSelectionStart, fSelectionEnd, fHasSelection, fLane, fClip;

  SelectionReader() {
    try {
      this.fSelectionStart = UIClipLane.class.getDeclaredField("selectionStart");
      this.fSelectionEnd = UIClipLane.class.getDeclaredField("selectionEnd");
      this.fHasSelection = UIClipLane.class.getDeclaredField("hasSelection");
      this.fLane = UIClipEnvelope.class.getDeclaredField("lane");
      this.fClip = UIGridLane.class.getDeclaredField("clip");
      this.fSelectionStart.setAccessible(true);
      this.fSelectionEnd.setAccessible(true);
      this.fHasSelection.setAccessible(true);
      this.fLane.setAccessible(true);
      this.fClip.setAccessible(true);
    } catch (Throwable t) {
      die(t);
    }
  }

  private void die(Throwable t) {
    if (!this.dead) {
      this.dead = true;
      LX.error(t, "[ArrangeTools] Host UI internals not as expected, features disabled for this session");
    }
  }

  boolean isDead() {
    return this.dead;
  }

  /**
   * Finds the deepest focused UIClipLane in the OS-focused window's focus
   * chain, resolving its model lane + clip. Null if none / bridge dead.
   * Only the focused window is consulted (see class comment).
   */
  FocusedLane readFocused(LXStudio.UI ui) {
    if (this.dead) {
      return null;
    }
    try {
      final boolean altFocused =
        ui.lx.windowEngine.getFocusedWindow() == ui.lx.windowEngine.altWindow;
      final heronarts.glx.ui.UI.Window window = altFocused
        ? heronarts.glx.ui.UI.Window.ALT
        : heronarts.glx.ui.UI.Window.MAIN;
      UIClipLane uiLane = focusedClipLane(ui.getRoot(window));
      if (uiLane == null) {
        return null;
      }
      LXClipLane<?> lane = null;
      if (uiLane instanceof UIClipEnvelope) {
        lane = (LXClipLane<?>) this.fLane.get(uiLane);
      }
      LXClip clip = (LXClip) this.fClip.get(uiLane);
      if (lane == null || clip == null) {
        return null;
      }
      return new FocusedLane(uiLane, lane, clip, window);
    } catch (Throwable t) {
      die(t);
      return null;
    }
  }

  /**
   * Reads the focused lane's active selection range. Null if no focused
   * lane, no selection, or bridge dead. Cursors are cloned and normalized
   * (drags can be right-to-left).
   */
  LaneSelection read(LXStudio.UI ui) {
    FocusedLane focused = readFocused(ui);
    if (focused == null) {
      return null;
    }
    try {
      if (!this.fHasSelection.getBoolean(focused.uiLane)) {
        return null;
      }
      Cursor start = ((Cursor) this.fSelectionStart.get(focused.uiLane)).clone();
      Cursor end = ((Cursor) this.fSelectionEnd.get(focused.uiLane)).clone();
      Cursor.Operator op = focused.clip.CursorOp();
      Cursor min = op.isBefore(start, end) ? start : end;
      Cursor max = (min == start) ? end : start;
      return new LaneSelection(focused.uiLane, focused.lane, focused.clip, focused.window, min, max);
    } catch (Throwable t) {
      die(t);
      return null;
    }
  }

  /**
   * Walks the public getFocusedChild() chain from a window root, returning
   * the deepest UIClipLane in the chain, or null.
   */
  private UIClipLane focusedClipLane(UIObject root) {
    UIClipLane found = null;
    UIObject obj = root;
    while (obj != null) {
      if (obj instanceof UIClipLane) {
        found = (UIClipLane) obj;
      }
      obj = obj.getFocusedChild();
    }
    return found;
  }

}
