package apotheneum.jvyduna.arrange;

import java.lang.reflect.Field;
import java.util.List;

import heronarts.glx.event.KeyEvent;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.UIDialogBox;
import heronarts.glx.ui.UIObject;
import heronarts.glx.ui.component.UIButton;
import heronarts.lx.LX;
import heronarts.lx.clip.Cursor;
import heronarts.lx.clip.LXClipLane;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.timeline.UITimeline;
import heronarts.lx.studio.ui.timeline.lane.UIClipLane;

/**
 * Wires the Arrange Tools features to global keyboard shortcuts via the
 * host's public registerKeyboardShortcut API. This class references
 * arrange-branch host types at class level, so it is only classloaded from
 * inside ArrangeToolsPlugin's Throwable guard — on a stock host it fails to
 * link and the plugin stays inert.
 *
 * Shortcut table (collision-checked against the host defaults; arrows are
 * unusable — UIClipLane consumes VK_LEFT/RIGHT unconditionally when focused):
 *
 *   Cmd+Shift+]  /  Cmd+Shift+[   shift range right/left by one grid step
 *   Cmd+Alt+]    /  Cmd+Alt+[     shift range right/left by 1/4 beat
 *   Cmd+Shift+K / Cmd+Shift+J     extend the selection rectangle up/down (vim
 *                                 j/k — arrows are unusable: UI2dContainer's
 *                                 VERTICAL arrowKeyFocus consumes UP/DOWN with
 *                                 no modifier check anywhere in the lane's
 *                                 ancestor chain)
 *   Cmd+Shift+C                   copy range (rectangle if active, else focused lane)
 *   Cmd+Shift+V                   paste at insert marker
 *   Cmd+Shift+/                   toggle the shortcut reference panel
 *
 * With a rectangle active (LaneRect), shift and copy operate on the member
 * lanes instead of all/focused; RangeHighlightOverlay draws it.
 */
class ArrangeToolsController {

  private final LX lx;
  private final LXStudio.UI ui;

  private final SelectionReader reader = new SelectionReader();
  private final VirtualRangeTracker tracker;
  private final RangeClipboard clipboard = new RangeClipboard();

  private LaneRect rect = null;
  private ShortcutsPanel shortcutsPanel = null;

  ArrangeToolsController(LX lx, LXStudio.UI ui) {
    this.lx = lx;
    this.ui = ui;
    this.tracker = new VirtualRangeTracker(lx);
  }

  void register() {
    if (this.reader.isDead()) {
      return; // constructor already logged; register nothing
    }
    // Click-through highlight overlays for the rectangular selection,
    // one per window (each UI2dContext owns a per-window framebuffer)
    this.ui.addLayer(new RangeHighlightOverlay(this.ui,
      () -> rectView(heronarts.glx.ui.UI.Window.MAIN),
      () -> new float[] { this.ui.getWidth(), this.ui.getHeight() }));
    this.ui.addLayerAlt(new RangeHighlightOverlay(this.ui,
      () -> rectView(heronarts.glx.ui.UI.Window.ALT),
      () -> new float[] {
        this.ui.lx.windowEngine.altWindow.getUIWidth(),
        this.ui.lx.windowEngine.altWindow.getUIHeight() }));
    // Cross-lane mouse drag creates the rectangle (poll-based; see class doc)
    final DragRectWatcher dragWatcher = new DragRectWatcher(this.ui, this.reader, this);
    this.ui.addLoopTask(deltaMs -> dragWatcher.poll());
    installStatusBarIcon();
    this.ui.registerKeyboardShortcut(KeyEvent.VK_RIGHT_BRACKET, KeyEvent.COMMAND | KeyEvent.SHIFT,
      keyEvent -> guard("shift right", () -> shift(true, false)));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_LEFT_BRACKET, KeyEvent.COMMAND | KeyEvent.SHIFT,
      keyEvent -> guard("shift left", () -> shift(false, false)));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_RIGHT_BRACKET, KeyEvent.COMMAND | KeyEvent.ALT,
      keyEvent -> guard("fine shift right", () -> shift(true, true)));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_LEFT_BRACKET, KeyEvent.COMMAND | KeyEvent.ALT,
      keyEvent -> guard("fine shift left", () -> shift(false, true)));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_C, KeyEvent.COMMAND | KeyEvent.SHIFT,
      keyEvent -> guard("copy range", this::copy));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_V, KeyEvent.COMMAND | KeyEvent.SHIFT,
      keyEvent -> guard("paste range", this::paste));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_K, KeyEvent.COMMAND | KeyEvent.SHIFT,
      keyEvent -> guard("extend rectangle up", () -> extendRect(true)));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_J, KeyEvent.COMMAND | KeyEvent.SHIFT,
      keyEvent -> guard("extend rectangle down", () -> extendRect(false)));
    this.ui.registerKeyboardShortcut(KeyEvent.VK_SLASH, KeyEvent.COMMAND | KeyEvent.SHIFT,
      keyEvent -> guard("toggle shortcut reference", this::toggleShortcutsPanel));
    LX.log("[ArrangeTools] Registered shortcuts: Cmd+Shift+[/] shift, Cmd+Alt+[/] fine shift, "
      + "Cmd+Shift+J/K rectangle, Cmd+Shift+C copy, Cmd+Shift+V paste, Cmd+Shift+/ shortcuts");
  }

  /** Every shortcut body runs inside this; a failure logs, never crashes the host. */
  private void guard(String what, Runnable action) {
    try {
      action.run();
    } catch (Throwable t) {
      LX.error(t, "[ArrangeTools] Error during " + what);
    }
  }

  // --- Feature A: cross-lane range shift ---

  private void shift(boolean right, boolean fine) {
    final SelectionReader.LaneSelection sel = this.reader.read(this.ui);
    if (sel == null) {
      LX.log("[ArrangeTools] Shift: no clip-lane selection (drag-select a range in a lane first)");
      return;
    }
    // The host highlight doesn't follow our shifts; substitute the tracked
    // virtual range so repeated nudges chain correctly
    final SelectionReader.LaneSelection range = effective(sel);

    final Cursor delta = fine
      ? sel.clip.constructTempoCursor(0, 0.25)
      : gridSpacing(sel);

    // With a rectangle active, shift only its member lanes; else all lanes
    final List<LXClipLane<?>> targetLanes = rectLanes(sel);
    final Cursor[] shiftedRange = new Cursor[2];
    final CompositeCommand command = RangeShift.build(sel, range.min, range.max, delta, right, shiftedRange, targetLanes);
    if (command == null) {
      LX.log("[ArrangeTools] Shift: nothing to move (empty range or at clip boundary)");
      return;
    }
    this.tracker.perform(command);
    // LXCommandEngine.perform swallows failures internally (pushes an error,
    // clears the stacks, returns normally) — only record the virtual range
    // if our command actually landed on the undo stack
    if (this.lx.command.getUndoCommand() == command) {
      // Key by the range the host UI still displays (sel.min/max), not the
      // substituted one, so the next read maps stale-highlight -> new position
      this.tracker.record(sel.uiLane, sel.min, sel.max, shiftedRange[0], shiftedRange[1]);
    } else {
      this.tracker.clear();
      LX.log("[ArrangeTools] Shift did not complete (see error above); nothing was moved");
    }
  }

  /** The selection with any tracked virtual range substituted in. */
  private SelectionReader.LaneSelection effective(SelectionReader.LaneSelection sel) {
    final Cursor[] virtual = this.tracker.substitute(sel.uiLane, sel.min, sel.max, sel.clip.CursorOp());
    return (virtual == null) ? sel : sel.withRange(virtual[0], virtual[1]);
  }

  // --- Rectangular multi-lane selection ---

  private void extendRect(boolean up) {
    final SelectionReader.LaneSelection sel = this.reader.read(this.ui);
    if (sel == null) {
      LX.log("[ArrangeTools] Rectangle: drag-select a range in a lane first");
      return;
    }
    if (this.rect == null || this.rect.anchorUiLane != sel.uiLane) {
      this.rect = new LaneRect(sel);
    }
    if (!this.rect.extend(up, sel)) {
      this.rect = null;
      LX.log("[ArrangeTools] Rectangle collapsed to single lane");
      return;
    }
    final List<LXClipLane<?>> members = this.rect.memberLanes(sel);
    LX.log("[ArrangeTools] Rectangle: " + (members != null ? members.size() : 0) + " lane(s)");
  }

  /**
   * The rectangle's member lanes if one is active and still anchored to the
   * current selection; null otherwise (meaning: default lane scope). A
   * stale rectangle (anchor changed) is discarded here.
   */
  private List<LXClipLane<?>> rectLanes(SelectionReader.LaneSelection sel) {
    if (this.rect == null) {
      return null;
    }
    if (this.rect.anchorUiLane != sel.uiLane) {
      this.rect = null;
      return null;
    }
    final List<LXClipLane<?>> members = this.rect.memberLanes(sel);
    if (members == null || members.size() <= 1) {
      this.rect = null;
      return null;
    }
    return members;
  }

  /**
   * Mouse drag-rect path (DragRectWatcher): sets the rect's extents to
   * follow the pointer each frame during a live cross-lane rubber-band.
   */
  void setDragRect(SelectionReader.LaneSelection sel, int extentUp, int extentDown) {
    if (extentUp == 0 && extentDown == 0) {
      // Pointer back on the anchor lane: no rectangle
      if (this.rect != null && this.rect.anchorUiLane == sel.uiLane) {
        this.rect = null;
      }
      return;
    }
    if (this.rect == null || this.rect.anchorUiLane != sel.uiLane) {
      this.rect = new LaneRect(sel);
    }
    if (!this.rect.setExtents(extentUp, extentDown, sel)) {
      this.rect = null;
    }
  }

  /** Drawing data for the highlight overlay in the given window, or null. */
  private RangeHighlightOverlay.View rectView(heronarts.glx.ui.UI.Window window) {
    final LaneRect r = this.rect;
    if (r == null) {
      return null;
    }
    final SelectionReader.LaneSelection sel = this.reader.read(this.ui);
    if (sel == null || sel.uiLane != r.anchorUiLane) {
      this.rect = null; // anchor selection gone or moved: rectangle is stale
      return null;
    }
    if (sel.window != window) {
      return null;
    }
    final SelectionReader.LaneSelection eff = effective(sel);
    return new RangeHighlightOverlay.View(r.anchorUiLane, r.getExtentUp(), r.getExtentDown(), eff.min, eff.max);
  }

  /** Grid step of the lane's owning timeline; falls back to one beat. */
  private Cursor gridSpacing(SelectionReader.LaneSelection sel) {
    UIObject obj = sel.uiLane;
    while (obj != null) {
      if (obj instanceof UITimeline) {
        return ((UITimeline) obj).gridMetrics.getGridSpacing().clone();
      }
      obj = (obj instanceof heronarts.glx.ui.UI2dComponent)
        ? ((heronarts.glx.ui.UI2dComponent) obj).getContainer()
        : null;
    }
    return sel.clip.constructTempoCursor(1, 0);
  }

  // --- Feature B: range copy/paste ---

  private void copy() {
    final SelectionReader.LaneSelection sel = this.reader.read(this.ui);
    if (sel == null) {
      LX.log("[ArrangeTools] Copy: no clip-lane selection (drag-select a range in a lane first)");
      return;
    }
    // Scope: active rectangle if one is extended, else the focused lane
    List<LXClipLane<?>> sources = rectLanes(sel);
    if (sources == null) {
      sources = List.of(sel.lane);
    }
    // Copy what the user sees moved: apply the virtual range like shift does
    if (this.clipboard.capture(effective(sel), sources) == 0) {
      LX.log("[ArrangeTools] Copy: no supported events in the selected range");
    }
  }

  private void paste() {
    final SelectionReader.FocusedLane focused = this.reader.readFocused(this.ui);
    final RangePaste.Result result = RangePaste.build(this.clipboard, focused);
    if (result.error != null) {
      showDialog(result.error, focused != null ? focused.uiLane : null);
      return;
    }
    // Plain perform (not tracker.perform): a paste invalidates any tracked
    // shift range via the undoChanged bang, which is what we want
    this.lx.command.perform(result.command);
  }

  /**
   * Modal error dialog. The (overlay, source) overload routes the dialog to
   * whichever window (main or arrange/alt) contains the source lane.
   */
  private void showDialog(String message, UIClipLane source) {
    LX.log("[ArrangeTools] " + message);
    if (source != null) {
      this.ui.showContextOverlay(new UIDialogBox(this.ui, message), source);
    } else {
      this.ui.showContextDialogMessage(message);
    }
  }

  // --- Keyboard shortcut reference ---

  /**
   * Adds the keyboard icon to the arrange window's status bar. This is the
   * plugin's one sanctioned step past read-only reflection: two reflective
   * READS (private LXStudio.UI#altContext, private AltContext#statusBar)
   * followed by the public addToContainer. UIStatusBarAlt is a left-to-right
   * fill container, so an appended child lands right-aligned and reflows on
   * resize automatically. Failure is non-fatal — the Cmd+Shift+/ shortcut
   * still opens the panel.
   */
  private void installStatusBarIcon() {
    try {
      final Field fAltContext = LXStudio.UI.class.getDeclaredField("altContext");
      fAltContext.setAccessible(true);
      final Object altContext = fAltContext.get(this.ui);
      final Field fStatusBar = altContext.getClass().getDeclaredField("statusBar");
      fStatusBar.setAccessible(true);
      final UI2dContainer statusBar = (UI2dContainer) fStatusBar.get(altContext);

      final UIButton.Action button = new UIButton.Action(0, 4, 18, 18, "",
        () -> guard("toggle shortcut reference", this::toggleShortcutsPanel));
      button
        .setIcon(this.ui.theme.iconKeyboard)
        .setDescription("Show keyboard shortcut reference (Cmd+Shift+/)");
      button.addToContainer(statusBar);
    } catch (Throwable t) {
      LX.error(t, "[ArrangeTools] Could not add keyboard icon to status bar (Cmd+Shift+/ still works)");
    }
  }

  private void toggleShortcutsPanel() {
    if (this.shortcutsPanel == null) {
      this.shortcutsPanel = new ShortcutsPanel(this.ui);
    }
    if (this.shortcutsPanel.isShowing()) {
      this.ui.clearContextOverlay(this.shortcutsPanel);
    } else {
      this.shortcutsPanel.center(
        this.ui.lx.windowEngine.altWindow.getUIWidth(),
        this.ui.lx.windowEngine.altWindow.getUIHeight());
      this.ui.showContextOverlay(heronarts.glx.ui.UI.Window.ALT, this.shortcutsPanel);
    }
  }

}
