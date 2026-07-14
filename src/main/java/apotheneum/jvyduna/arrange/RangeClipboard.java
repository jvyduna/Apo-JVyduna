package apotheneum.jvyduna.arrange;

import java.util.ArrayList;
import java.util.List;

import heronarts.lx.LX;
import heronarts.lx.clip.Cursor;
import heronarts.lx.clip.LXClipEvent;
import heronarts.lx.clip.LXClipLane;
import heronarts.lx.clip.ParameterClipEvent;
import heronarts.lx.clip.ParameterClipLane;
import heronarts.lx.clip.PatternClipEvent;
import heronarts.lx.clip.PatternClipLane;

/**
 * Feature B clipboard: a plugin-owned capture of the events inside a
 * selection range. Deliberately NOT integrated with lx.clipboard —
 * LXClipboardComponent has no event-set item type and we don't invent one
 * in the host's clipboard.
 *
 * v1 captures ParameterClipLane events (relative cursor + normalized value)
 * and PatternClipLane events (relative cursor + pattern index). Other lane
 * types (MIDI/audio/bus/text) are skipped with a counted log; a JSON-based
 * capture generalizing to all lane types is the planned phase-2 upgrade.
 */
class RangeClipboard {

  static final class EventCapture {
    final Cursor relative;   // cursor relative to the selection min
    final double normalized; // ParameterClipLane payload
    final int patternIndex;  // PatternClipLane payload

    private EventCapture(Cursor relative, double normalized, int patternIndex) {
      this.relative = relative;
      this.normalized = normalized;
      this.patternIndex = patternIndex;
    }
  }

  static final class LaneCapture {
    final heronarts.lx.command.LXCommand.ComponentReference<LXClipLane<?>> laneRef;
    final Class<?> laneClass;
    final List<EventCapture> events = new ArrayList<>();

    private LaneCapture(LXClipLane<?> lane) {
      this.laneRef = new heronarts.lx.command.LXCommand.ComponentReference<>(lane);
      this.laneClass = lane.getClass();
    }
  }

  private final List<LaneCapture> lanes = new ArrayList<>();
  private Cursor rangeLength = null;
  private int clipId = -1;

  boolean isEmpty() {
    return this.lanes.isEmpty();
  }

  List<LaneCapture> getLanes() {
    return this.lanes;
  }

  Cursor getRangeLength() {
    return this.rangeLength;
  }

  int getClipId() {
    return this.clipId;
  }

  /**
   * Captures the selection range from the given source lanes (focused lane,
   * a rectangular lane block, or every lane of the clip). Non-mutating.
   * Returns the number of events captured.
   */
  int capture(SelectionReader.LaneSelection sel, List<LXClipLane<?>> sources) {
    this.lanes.clear();
    this.rangeLength = sel.max.subtract(sel.min);
    this.clipId = sel.clip.getId();

    int captured = 0;
    int skippedLanes = 0;
    for (LXClipLane<?> lane : sources) {
      if (!((lane instanceof ParameterClipLane) || (lane instanceof PatternClipLane))) {
        if (!lane.events.isEmpty()) {
          ++skippedLanes;
        }
        continue;
      }
      LaneCapture capture = captureLane(lane, sel.min, sel.max);
      if (capture != null) {
        this.lanes.add(capture);
        captured += capture.events.size();
      }
    }
    if (skippedLanes > 0) {
      LX.log("[ArrangeTools] Copy skipped " + skippedLanes + " unsupported lane(s) (MIDI/audio/bus/text)");
    }
    LX.log("[ArrangeTools] Copied " + captured + " event(s) from " + this.lanes.size() + " lane(s)");
    return captured;
  }

  private LaneCapture captureLane(LXClipLane<?> lane, Cursor min, Cursor max) {
    final Cursor.Operator op = lane.clip.CursorOp();
    LaneCapture capture = new LaneCapture(lane);
    // Host idiom (UIParameterClipEnvelope.initializeEventsFrom): iterate
    // from the first event at/after min, stop past max
    final java.util.ListIterator<? extends LXClipEvent<?>> iter = lane.eventIterator(min);
    while (iter.hasNext()) {
      LXClipEvent<?> event = iter.next();
      if (op.isAfter(event.cursor, max)) {
        break;
      }
      Cursor relative = event.cursor.subtract(min);
      if (event instanceof ParameterClipEvent) {
        capture.events.add(new EventCapture(relative, ((ParameterClipEvent) event).getNormalized(), -1));
      } else if (event instanceof PatternClipEvent) {
        capture.events.add(new EventCapture(relative, 0, ((PatternClipEvent) event).getPattern().getIndex()));
      }
    }
    return capture.events.isEmpty() ? null : capture;
  }

}
