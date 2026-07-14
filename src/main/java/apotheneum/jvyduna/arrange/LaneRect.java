package apotheneum.jvyduna.arrange;

import java.util.ArrayList;
import java.util.List;

import heronarts.lx.clip.LXClipLane;
import heronarts.lx.studio.ui.timeline.lane.UIClipLane;

/**
 * Plugin-owned rectangular selection: the host's single-lane time-range
 * selection (the anchor) extended vertically over a CONTIGUOUS block of
 * lanes, in composition order. The host enforces one-lane-at-a-time
 * selection, so this lives entirely plugin-side; RangeHighlightOverlay
 * draws it, and shift/copy operate on the member lanes.
 *
 * Extents are counts of lanes above/below the anchor. Extending toward a
 * direction grows that extent; extending the opposite way shrinks the far
 * side first (standard DAW selection feel). The rect is valid only while
 * the anchor lane remains the focused, selected lane — any anchor change
 * invalidates it (checked lazily by the controller).
 */
class LaneRect {

  final UIClipLane anchorUiLane;
  final LXClipLane<?> anchorLane;

  private int extentUp = 0;
  private int extentDown = 0;

  LaneRect(SelectionReader.LaneSelection sel) {
    this.anchorUiLane = sel.uiLane;
    this.anchorLane = sel.lane;
  }

  /**
   * Grow/shrink the rect. Returns false if the rect collapsed back to just
   * the anchor (caller should discard it).
   */
  boolean extend(boolean up, SelectionReader.LaneSelection sel) {
    final int anchorIndex = sel.clip.lanes.indexOf(this.anchorLane);
    if (anchorIndex < 0) {
      return false;
    }
    if (up) {
      if (this.extentDown > 0) {
        --this.extentDown;
      } else if (anchorIndex - this.extentUp > 0) {
        ++this.extentUp;
      }
    } else {
      if (this.extentUp > 0) {
        --this.extentUp;
      } else if (anchorIndex + this.extentDown < sel.clip.lanes.size() - 1) {
        ++this.extentDown;
      }
    }
    return (this.extentUp > 0) || (this.extentDown > 0);
  }

  int getExtentUp() {
    return this.extentUp;
  }

  int getExtentDown() {
    return this.extentDown;
  }

  /**
   * Directly sets the extents (mouse drag-rect path), clamped to the clip's
   * lane bounds. Returns false if the result is anchor-only (caller should
   * discard the rect).
   */
  boolean setExtents(int up, int down, SelectionReader.LaneSelection sel) {
    final int anchorIndex = sel.clip.lanes.indexOf(this.anchorLane);
    if (anchorIndex < 0) {
      return false;
    }
    this.extentUp = Math.min(Math.max(0, up), anchorIndex);
    this.extentDown = Math.min(Math.max(0, down), sel.clip.lanes.size() - 1 - anchorIndex);
    return (this.extentUp > 0) || (this.extentDown > 0);
  }

  /**
   * The member model lanes (anchor included), clamped to the clip's current
   * lane list. Null if the anchor is no longer in the clip.
   */
  List<LXClipLane<?>> memberLanes(SelectionReader.LaneSelection sel) {
    final int anchorIndex = sel.clip.lanes.indexOf(this.anchorLane);
    if (anchorIndex < 0) {
      return null;
    }
    final int lo = Math.max(0, anchorIndex - this.extentUp);
    final int hi = Math.min(sel.clip.lanes.size() - 1, anchorIndex + this.extentDown);
    return new ArrayList<>(sel.clip.lanes.subList(lo, hi + 1));
  }

}
