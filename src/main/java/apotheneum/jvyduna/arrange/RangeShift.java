package apotheneum.jvyduna.arrange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import heronarts.lx.LX;
import heronarts.lx.clip.Cursor;
import heronarts.lx.clip.LXClipEvent;
import heronarts.lx.clip.LXClipLane;
import heronarts.lx.clip.LXComposition;
import heronarts.lx.clip.LXCompositionEvent;
import heronarts.lx.clip.Locator;
import heronarts.lx.clip.MidiNoteClipLane;
import heronarts.lx.clip.ParameterClipLane;
import heronarts.lx.command.LXCommand;

/**
 * Feature A: shift every event inside a time range LEFT/RIGHT across ALL
 * lanes of the clip/composition, as one undoable operation. The range comes
 * from the single-lane selection the user dragged in the host UI (the host
 * enforces one-lane-at-a-time selection; we extend it model-side).
 *
 * Command construction mirrors the host's own single-lane range move
 * (UIParameterClipEnvelope MOVE / UICompositionLane BAR): per-lane
 * LXCommand.Clip.Event.SetCursors with LinkedHashMap from/to cursor maps,
 * MOVE_LEFT/MOVE_RIGHT operation, delta constrained to keep the range
 * inside [0, clip length]. Like the host's move, a shifted range clobbers
 * events it lands on — fully undoable via each lane's JSON preState.
 */
class RangeShift {

  /**
   * MIDI note lanes are EXCLUDED: note-on/note-off are separate point
   * events with a partner link, and the generic SetCursors path is
   * pair-unaware — a range boundary between a pair moves one half, and the
   * MOVE clobber window can delete a note-off whose note-on survives
   * (verified against LXClipLane.setEventsCursors; the host only ever edits
   * MIDI lanes via the pair-aware LXCommand.Clip.Event.Midi.* commands).
   * Supporting MIDI here means a pair-aware path, not a flag flip.
   */
  private static final boolean INCLUDE_MIDI_LANES = false;

  /**
   * Builds the composite shift command. Returns null if the constrained
   * delta is zero or no lane has events in range.
   *
   * @param min Range start (normalized min)
   * @param max Range end (normalized max)
   * @param delta Cursor distance to shift
   * @param add True to shift right, false left
   * @param shiftedRange Out-param (length 2): receives the post-shift min/max
   * @param targetLanes Lanes to shift, or null for all lanes of the clip.
   *   Locators travel only on all-lanes shifts — a rectangular subset shift
   *   is a local edit and shouldn't move global markers.
   */
  static CompositeCommand build(SelectionReader.LaneSelection sel, Cursor min, Cursor max,
                                Cursor delta, boolean add, Cursor[] shiftedRange,
                                List<LXClipLane<?>> targetLanes) {
    final Cursor.Operator op = sel.clip.CursorOp();

    // Constrain delta so the whole range stays inside [0, clip length],
    // matching UIClipLane.moveSelection / UICompositionLane BAR clamping
    Cursor constrained = delta.clone();
    if (add) {
      constrained = op.constrain(constrained, sel.clip.length.cursor.subtract(max));
    } else {
      constrained = op.constrain(constrained, min);
    }
    if (op.isZero(constrained)) {
      return null;
    }

    final Cursor shiftedMin = op.applyDelta(min.clone(), constrained.clone(), add);
    final Cursor shiftedMax = op.applyDelta(max.clone(), constrained.clone(), add);

    final List<LXCommand> children = new ArrayList<>();
    int skippedMidi = 0;
    for (LXClipLane<?> lane : (targetLanes != null) ? targetLanes : sel.clip.lanes) {
      if (!INCLUDE_MIDI_LANES && (lane instanceof MidiNoteClipLane)) {
        if (!lane.events.isEmpty()) {
          ++skippedMidi;
        }
        continue;
      }
      LXCommand child = laneShift(lane, min, max, constrained, add, shiftedMin, shiftedMax);
      if (child != null) {
        children.add(child);
      }
    }
    if (skippedMidi > 0) {
      LX.log("[ArrangeTools] Shift skipped " + skippedMidi + " MIDI lane(s) (note-pair-safe shift not supported)");
    }
    final int laneCount = children.size();

    // When shifting the arrange composition itself, locators inside the
    // range travel with the content (LXCommand.Composition.MoveLocator is
    // undoable and order-independent vs the lane commands). Subset shifts
    // (rectangular selection) leave locators alone.
    int locators = 0;
    if (targetLanes == null && sel.clip instanceof LXComposition) {
      final LXComposition composition = (LXComposition) sel.clip;
      for (Locator locator : composition.locators) {
        if (op.isInRange(locator.position.cursor, min, max)) {
          Cursor to = op.applyDelta(locator.position.cursor.clone(), constrained.clone(), add);
          children.add(new LXCommand.Composition.MoveLocator(composition, locator, to));
          ++locators;
        }
      }
    }

    if (children.isEmpty()) {
      return null;
    }

    shiftedRange[0] = shiftedMin;
    shiftedRange[1] = shiftedMax;
    LX.log("[ArrangeTools] Shift range " + (add ? "right" : "left") + " across " + laneCount + " lane(s)"
      + (locators > 0 ? " + " + locators + " locator(s)" : ""));
    return new CompositeCommand("Shift Range (" + laneCount + " lanes)", children);
  }

  /**
   * One lane's SetCursors command, or null if no events fall in the range.
   * In-range semantics mirror the host: point events by cursor within
   * [min, max] inclusive; duration events (LXCompositionEvent) by overlap
   * including their end cursor.
   */
  private static <T extends LXClipEvent<?>> LXCommand laneShift(LXClipLane<T> lane, Cursor min, Cursor max,
                                                                Cursor delta, boolean add,
                                                                Cursor shiftedMin, Cursor shiftedMax) {
    final Cursor.Operator op = lane.clip.CursorOp();

    // LinkedHashMap ordering matters — see UIParameterClipEnvelope's note on
    // same-cursor event reordering
    final Map<T, Cursor> fromCursors = new LinkedHashMap<>();
    final Map<T, Cursor> toCursors = new LinkedHashMap<>();
    for (T event : lane.events) {
      final boolean inRange;
      if (event instanceof LXCompositionEvent) {
        Cursor end = ((LXCompositionEvent<?>) event).end;
        inRange = op.isBeforeOrEqual(event.cursor, max) && op.isAfterOrEqual(end, min);
      } else {
        inRange = op.isInRange(event.cursor, min, max);
      }
      if (!inRange) {
        continue;
      }
      fromCursors.put(event, event.cursor.clone());
      // Clone both inputs: applyDelta mutates the cursor it's given (and can
      // bound-mutate the delta when subtracting)
      toCursors.put(event, op.applyDelta(event.cursor.clone(), delta.clone(), add));
    }
    if (fromCursors.isEmpty()) {
      return null;
    }

    // Host parity: parameter lanes pass an (empty, for pure moves) values
    // map; composition/duration lanes pass null (UICompositionLane BAR case)
    final Map<T, Double> fromValues = (lane instanceof ParameterClipLane) ? new LinkedHashMap<>() : null;

    return new LXCommand.Clip.Event.SetCursors<T>(lane, min, max, fromValues, fromCursors, toCursors)
      .update(shiftedMin, shiftedMax,
        add ? LXCommand.Clip.Event.SetCursors.Operation.MOVE_RIGHT
            : LXCommand.Clip.Event.SetCursors.Operation.MOVE_LEFT);
  }

}
