# Bug report: notes-lane events render wrong lengths at high zoom (unsorted event list)

**Branch:** `arrange` (LXStudio + LX, 1.2.2-SNAPSHOT, as of 2026-07-08)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation + real project data

## Symptom

Text-note events in the composition's notes lane display incorrect bar
lengths — or vanish entirely — when zoomed in, especially with the view
scrolled far left. At full zoom-out everything is always correct. Observed
live in `2 - Bliss.lxp`; the leftmost "Synth" note is a reliable repro.

## Root cause

`TextNoteClipLane.addEvent` **appends** instead of inserting sorted:

```java
// LX: heronarts/lx/clip/TextNoteClipLane.java:55-57
public TextNoteClipEvent addEvent(String note, Cursor cursor, Cursor length) {
  TextNoteClipEvent event = new TextNoteClipEvent(this.lx, this, cursor, length);
  this.mutableEvents.add(event);   // append — should be _insertEvent(event)
```

Every other insertion path (recording via `commitRecordQueue`,
`insertEvent`) maintains cursor-sorted order via `_insertEvent`
(`LXClipLane.java:243-251`). Double-click-created notes land in **creation
order**, not time order — and since `LXClipLane.load` preserves file order
(`LXClipLane.java:729-751`, no sort), the disorder persists across
save/load. (`AudioClipLane.addEvent`, `AudioClipLane.java:168-172`, has the
same latent append; audio events usually get dragged, and the drag paths
re-insert sorted, which is why notes show it first.)

The notes renderer requires sorted order twice:

```java
// LXStudio: heronarts/lx/studio/ui/composition/UITextNoteLane.java:86-101
ListIterator<TextNoteClipEvent> iter = this.lane.eventIterator(events, this.lens.toCursorFromViewPosition(), -1);
while (iter.hasNext()) {
  TextNoteClipEvent event = iter.next();
  float startX = this.lens.toPixelsFromCursor(event.cursor);
  ...
  if (startX > maxX) {
    break;                                   // (1) early-exit assumes sorted
  }
  float renderEndX = getRenderEndX(event, startX, endX, iter);
```

```java
// UICompositionLane.java:109-118
float renderEndX = LXUtils.maxf(endX, startX + getMinEventWidth(event));
if (iter.hasNext()) {
  E nextEvent = iter.next();
  float nextStartX = this.lens.toPixelsFromCursor(nextEvent.cursor);
  renderEndX = LXUtils.minf(renderEndX, nextStartX);  // (2) clamp-to-next assumes sorted
  iter.previous();
}
```

…and the iterator's start index comes from `LXClipLane._cursorIndex`
(`LXClipLane.java:200-225`), a **binary search** — only valid on a sorted
list.

## Concrete mechanism (actual data from `2 - Bliss.lxp`)

The saved notes lane holds, in memory order:
`[""@104,944.9 ms (len 35,163 ms), "Synth"@6,168.67 ms (len 17,595.4 ms)]` —
the unnamed later note sits *before* the earlier "Synth" in the list.
(Time base TEMPO, 160 BPM, composition length 367,888 ms; ~1200 px view.)

- **High zoom** (view e.g. [0..46 s], or sitting right on Synth): the first
  list event is `""` at 104.9 s → `startX > maxX` → `break` fires on the
  very first iteration → **Synth is never drawn**, even though it's in view.
- **Moderate zoom, view pinned left** (saved zoom 2.09, view [0..176 s]):
  `""` passes the break test, and clamp (2) sets its render end to
  next-in-list Synth's start → `vg.rect(x=715, w=-673, …)` — a
  **negative-width phantom bar** spanning 6.2 s→104.9 s (NanoVG fills
  negative-width rounded rects), painted in the same color under the real
  Synth bar. Visually: one "Synth" bar of wildly wrong length.
- **Scroll slightly right** (view start past 6.2 s): the phantom fails the
  `renderEndX > minX` test and disappears; Synth draws correctly — hence
  "some combo of zoom and leftward view position."

## Why full zoom-out is always correct

At zoom 1, `maxX = totalWidthPixels` ≥ every event's `startX`, so the early
`break` can never fire and every real note draws at its true geometry (the
phantom degenerates to an unlabeled background fill hidden behind the
correct bars). Both failure modes need the viewport to exclude the
out-of-order event — impossible zoomed out.

`Lens.toPixelsFromCursor` (Lens.java:220-226) is exonerated: the ratio math
is double-precision and pixel magnitudes stay far below float trouble at
max zoom.

## Suggested minimal fix

1. **Primary (one line each):** `TextNoteClipLane.addEvent` and
   `AudioClipLane.addEvent` — replace `this.mutableEvents.add(event)` with
   the sorted `_insertEvent(event)`.
2. **Repair existing files:** in `LXClipLane.load`, sort `loadEvents` by
   cursor before `mutableEvents.set(loadEvents)` — projects saved with the
   bug (like `2 - Bliss.lxp`) carry the disorder permanently otherwise.
3. **Optional hardening:** clamp `renderEndX = maxf(renderEndX, startX)` in
   `UICompositionLane.getRenderEndX` so an out-of-order list can never paint
   negative-width phantoms.

## Side notes worth including

- The unsorted list also silently breaks anything binary-searching the lane
  (`getEventAtMouse` hit-testing/selection, `setEventsCursors` range edits).
- `LXClipLane.eventIterator(List, Cursor, int)` (LXClipLane.java:175-177)
  computes its index via `cursorPlayIndex(fromCursor)` against
  `this.events` instead of the passed (UI-thread) list — a
  thread-consistency inconsistency spotted along the way. **Update
  2026-07-09: this is not minor — it is a genuine data race that caused a
  fatal crash live.** `this.events` is `unmodifiableList(mutableEvents)`,
  whose `get()` reads the live engine list; binary-searching it from the
  render thread while a boundary-drag edit rebuilds the lane via
  `set()`/`clear()` threw `IndexOutOfBoundsException: Index 2 out of bounds
  for length 0` out of `UITextNoteLane.onDraw` and took down the UI. Full
  writeup, log, repro, and one-line fix (call the existing
  `cursorPlayIndex(events, fromCursor)` overload) in
  [`2026-07-09-textnote-lane-draw-crash.md`](2026-07-09-textnote-lane-draw-crash.md).

## Related upstream issue (separate report)

`setEventsCursors`' MOVE reorder branch leaves duration events that overlap
a moved range with `cursor < selectionMin` cursor-rewritten but not
repositioned (list ends up unsorted) when the move distance exceeds the
selection width — details in `src/main/java/apotheneum/jvyduna/arrange/ArrangeTools.md`
("Known upstream host bug"). Same failure class: unsorted lane list.
