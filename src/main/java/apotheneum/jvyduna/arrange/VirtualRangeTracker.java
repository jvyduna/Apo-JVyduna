package apotheneum.jvyduna.arrange;

import heronarts.lx.LX;
import heronarts.lx.clip.Cursor;
import heronarts.lx.command.LXCommand;
import heronarts.lx.studio.ui.timeline.lane.UIClipLane;

/**
 * After a cross-lane shift, the host's on-screen selection highlight stays
 * at the OLD range — we never write host UI state (contract), so we can't
 * move it. Without correction, a second nudge would re-read the stale
 * highlighted range and shift the wrong events.
 *
 * This tracker remembers (lane, ui-range-at-shift, shifted range). When the
 * next read returns the same lane with the same (stale) UI range, the
 * shifted range is substituted so repeated nudges chain correctly. Any
 * foreign undo/redo (or the user changing the selection) invalidates it.
 */
class VirtualRangeTracker {

  private final LX lx;

  private UIClipLane uiLane = null;
  private Cursor uiMin, uiMax;      // what the host UI still shows
  private Cursor virtualMin, virtualMax;  // where the events actually are

  private boolean selfPerform = false;

  VirtualRangeTracker(LX lx) {
    this.lx = lx;
    // Any undo/redo not initiated by us moves events out from under the
    // remembered range — including the user undoing our own shift.
    lx.command.undoChanged.addListener(p -> {
      if (!this.selfPerform) {
        clear();
      }
    });
    lx.command.redoChanged.addListener(p -> {
      if (!this.selfPerform) {
        clear();
      }
    });
  }

  void clear() {
    this.uiLane = null;
  }

  /**
   * If the reader's (lane, min, max) matches the remembered stale UI range,
   * returns {virtualMin, virtualMax} clones to use instead; else null.
   */
  Cursor[] substitute(UIClipLane lane, Cursor min, Cursor max, Cursor.Operator op) {
    if (this.uiLane == lane && this.uiLane != null
      && op.isEqual(min, this.uiMin) && op.isEqual(max, this.uiMax)) {
      return new Cursor[] { this.virtualMin.clone(), this.virtualMax.clone() };
    }
    return null;
  }

  /**
   * Records that the range shown at (uiMin, uiMax) on this lane now actually
   * lives at (virtualMin, virtualMax). uiMin/uiMax must be the range as the
   * host UI still displays it (i.e. the pre-substitution read).
   */
  void record(UIClipLane lane, Cursor uiMin, Cursor uiMax, Cursor virtualMin, Cursor virtualMax) {
    this.uiLane = lane;
    this.uiMin = uiMin.clone();
    this.uiMax = uiMax.clone();
    this.virtualMin = virtualMin.clone();
    this.virtualMax = virtualMax.clone();
  }

  /**
   * Performs a command through the undo engine without invalidating the
   * tracked range (for our own shifts, which record() right after).
   */
  void perform(LXCommand command) {
    this.selfPerform = true;
    try {
      this.lx.command.perform(command);
    } finally {
      this.selfPerform = false;
    }
  }

}
