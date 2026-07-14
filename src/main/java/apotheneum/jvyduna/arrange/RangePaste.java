package apotheneum.jvyduna.arrange;

import java.util.ArrayList;
import java.util.List;

import heronarts.lx.LX;
import heronarts.lx.clip.Cursor;
import heronarts.lx.clip.LXClip;
import heronarts.lx.clip.LXClipLane;
import heronarts.lx.clip.ParameterClipLane;
import heronarts.lx.clip.PatternClipLane;
import heronarts.lx.command.LXCommand;
import heronarts.lx.parameter.DiscreteParameter;

/**
 * Feature B paste: replays a RangeClipboard capture at the clip's insert
 * marker (the position the user set by clicking a lane), as one undoable
 * composite. Per lane: RemoveRange over the destination window
 * (paste-replace, matching the host's move-clobber semantics; free if the
 * window is empty) followed by one InsertEvent per captured event.
 *
 * Cross-lane paste: when the clipboard holds exactly ONE lane and the
 * currently focused lane differs from it, the paste targets the focused
 * lane instead — gated by a strict same-event-semantics compatibility check
 * (no coercion). Incompatible pastes are rejected whole via a modal dialog;
 * no partial paste ever occurs.
 */
class RangePaste {

  /** Either a built command, or a user-facing rejection message. */
  static final class Result {
    final CompositeCommand command;
    final String error;

    private Result(CompositeCommand command, String error) {
      this.command = command;
      this.error = error;
    }

    static Result ok(CompositeCommand command) {
      return new Result(command, null);
    }

    static Result reject(String error) {
      return new Result(null, error);
    }
  }

  static Result build(RangeClipboard clipboard, SelectionReader.FocusedLane focused) {
    if (clipboard.isEmpty()) {
      return Result.reject("Nothing copied yet: select a range and use the copy shortcut first.");
    }

    // Resolve capture lanes; they may have been deleted since the copy
    final List<RangeClipboard.LaneCapture> captures = clipboard.getLanes();
    final List<LXClipLane<?>> targets = new ArrayList<>();
    for (RangeClipboard.LaneCapture capture : captures) {
      LXClipLane<?> lane = capture.laneRef.get();
      if (lane == null) {
        return Result.reject("Paste unavailable: a copied lane no longer exists.");
      }
      targets.add(lane);
    }

    // Cross-lane retarget: single-lane clipboard + a different focused lane
    if (captures.size() == 1 && focused != null && focused.lane != targets.get(0)) {
      String incompatible = checkCompatibility(targets.get(0), focused.lane);
      if (incompatible != null) {
        return Result.reject(incompatible);
      }
      targets.set(0, focused.lane);
    }

    // All targets must share one clip; its insert marker is the paste base
    final LXClip clip = targets.get(0).clip;
    for (LXClipLane<?> lane : targets) {
      if (lane.clip != clip) {
        return Result.reject("Paste unavailable: copied lanes no longer share one clip.");
      }
    }

    final Cursor.Operator op = clip.CursorOp();
    final Cursor base = clip.insertMarker.cursor.clone();
    final Cursor destEnd = base.add(clipboard.getRangeLength());

    final List<LXCommand> children = new ArrayList<>();
    int inserted = 0;
    int overflow = 0;
    for (int i = 0; i < captures.size(); ++i) {
      final RangeClipboard.LaneCapture capture = captures.get(i);
      final LXClipLane<?> lane = targets.get(i);
      // Paste-replace: clear the destination window on this lane first.
      // RemoveRange is isIgnored() when nothing was there.
      children.add(new LXCommand.Clip.Event.RemoveRange(lane, base, destEnd));
      for (RangeClipboard.EventCapture event : capture.events) {
        final Cursor dest = base.add(event.relative);
        if (op.isAfter(dest, clip.length.cursor)) {
          ++overflow;
          continue;
        }
        if (lane instanceof ParameterClipLane) {
          children.add(new LXCommand.Clip.Event.Parameter.InsertEvent((ParameterClipLane) lane, dest, event.normalized));
          ++inserted;
        } else if (lane instanceof PatternClipLane) {
          children.add(new LXCommand.Clip.Event.Pattern.InsertEvent((PatternClipLane) lane, dest, event.patternIndex));
          ++inserted;
        }
      }
    }
    if (inserted == 0) {
      return Result.reject("Nothing to paste here: all copied events fall past the clip length.");
    }
    if (overflow > 0) {
      LX.log("[ArrangeTools] Paste skipped " + overflow + " event(s) past clip length");
    }
    LX.log("[ArrangeTools] Pasting " + inserted + " event(s) into " + captures.size() + " lane(s)");
    return Result.ok(new CompositeCommand("Paste Range (" + inserted + " events)", children));
  }

  /**
   * Strict cross-lane compatibility: only between ParameterClipLanes of the
   * SAME concrete event semantics (Normalized/Boolean/Trigger), Discrete
   * additionally requiring an identical value range. No enums-to-anything,
   * no pattern lanes (indices are clip-local), no coercion. Returns null if
   * compatible, else the user-facing rejection reason.
   */
  private static String checkCompatibility(LXClipLane<?> source, LXClipLane<?> target) {
    if (!(source instanceof ParameterClipLane) || !(target instanceof ParameterClipLane)) {
      return "Cross-lane paste is only supported between parameter lanes (copied: "
        + laneTypeName(source) + ", focused: " + laneTypeName(target) + ").";
    }
    if (source.getClass() != target.getClass()) {
      return "Cross-lane paste needs matching lane types: copied " + laneTypeName(source)
        + " events cannot paste into a " + laneTypeName(target) + " lane.";
    }
    if (source instanceof ParameterClipLane.Discrete) {
      DiscreteParameter sp = (DiscreteParameter) ((ParameterClipLane) source).parameter;
      DiscreteParameter tp = (DiscreteParameter) ((ParameterClipLane) target).parameter;
      if ((sp.getMinValue() != tp.getMinValue()) || (sp.getMaxValue() != tp.getMaxValue())) {
        return "Cross-lane paste between discrete lanes needs an identical value range (copied "
          + sp.getMinValue() + ".." + sp.getMaxValue() + ", focused "
          + tp.getMinValue() + ".." + tp.getMaxValue() + ").";
      }
    }
    return null;
  }

  private static String laneTypeName(LXClipLane<?> lane) {
    if (lane instanceof ParameterClipLane.Normalized) return "normalized-parameter";
    if (lane instanceof ParameterClipLane.Discrete) return "discrete-parameter";
    if (lane instanceof ParameterClipLane.Boolean) return "boolean-parameter";
    if (lane instanceof ParameterClipLane.Trigger) return "trigger-parameter";
    if (lane instanceof PatternClipLane) return "pattern";
    return lane.getClass().getSimpleName();
  }

}
