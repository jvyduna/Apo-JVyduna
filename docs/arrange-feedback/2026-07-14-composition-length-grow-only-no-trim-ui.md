# Feature gap: composition `length` can only grow, never shrink — no UI to trim it, and `playEnd`/`playStart` are dead for compositions

**Branch:** `arrange` (LXStudio + LX, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## Symptom

`2 - Bliss.lxp` kept playing for **~2 extra minutes of black** after the song
ended. The composition's audio clip (`2 - Ducky - Bliss.wav`) starts at 0:06
and ends at 4:12; the last real content in the whole composition is a
`master/fader → 0` event at 4:18. But the composition's `length` parameter was
**6:08** — nearly two minutes past the last event — so playback ran to 6:08
before stopping.

Digging into why: the composition also has a `playEnd` marker sitting at
**4:06**, *before* the fader event at 4:18. That looked at first like the
obvious culprit ("the end marker is stale, just move it out") until tracing
playback showed `playEnd` isn't consulted for compositions at all — `length`
is the only thing that terminates a composition's playback, and there is no
UI control anywhere that sets it directly.

## Source trace

**1. Compositions ignore `playEnd`/`playStart` during playback — always run to `length`.**

```java
// LX: LXClip.java:1526-1536  (runAutomation)
Cursor endCursor = this.playEnd.cursor;
if (isLoop) {
  endCursor = this.loopEnd.cursor;
} else if (isOverdub) {
  endCursor = this.length.cursor;
} else if (this instanceof LXComposition) {
  // Compositions always play to their end
  endCursor = this.length.cursor;
}
```

For an ordinary `LXClip`, `playEnd` is the effective stop point. For an
`LXComposition`, this branch overrides that and always uses `length` instead
— `playEnd`/`playStart` are computed and serialized but never read on the
composition playback path.

**2. `length` only ever grows — nothing in the engine shrinks it back.**

```java
// LX: LXClip.java:971-975  (onCompositionEventImport)
final void onCompositionEventImport(LXCompositionEvent<?> event) {
  if (CursorOp().isAfter(event.end, this.length.cursor)) {
    this.length.set(event.end);
  }
  ...
```

This is called from `addAudioLane` (`LXComposition.java:524-531`) and other
content-import paths — any event whose end extends past the current `length`
pulls `length` out to meet it. There is no matching call anywhere that pulls
`length` *back in* when content is deleted, trimmed, or moved earlier.

**3. There's no UI handle for `length` at all, and no `playEnd`/`playStart`
handles for compositions either.**

Regular clips get playable-region drag handles in `UIScrubLane`:

```java
// LXStudio: UIScrubLane.java:88-89, 141-142  (UIClipEditor's scrub lane)
final float startX = this.lens.toPixelsFromCursor(this.clip.playStart.cursor);
final float endX = this.lens.toPixelsFromCursor(this.clip.playEnd.cursor);
```

But the composition editor uses a **different** class,
`UICompositionScrubLane` (`UICompositionEditor.java:78`,
`new UICompositionScrubLane(ui, this)`), and that class has no reference to
`playStart`/`playEnd`/`length` for dragging at all — the only draggable
marker it implements is `insertMarker`
(`UICompositionScrubLane.java:204-235`). The sole place `composition.length`
appears in the LXStudio UI source is a read-only pixel-ratio calculation for
laying out the timeline's total width:

```java
// LXStudio: UICompositionEditor.java:516
return this.horizontalPadding + (getWidthInner() * (float) composition.CursorOp().getRatio(cursor, composition.length.cursor));
```

No `LXCommand` sets a composition's `length`, `playEnd`, or `playStart`
either (`grep`'d `heronarts/lx/command/LXCommand.java` — no hits). So the only
way `length` moves at all is the auto-grow in `onCompositionEventImport`, and
the only way to shrink it is to hand-edit the `.lxp` — which is what I ended
up doing for `2 - Bliss.lxp`.

## Where the divergence in this file likely came from

Reading `_stopFirstRecording` (`LXClip.java:1088-1096`) and
`setRecordingLength`/`setQuantizedRecordingLength`
(`LXClip.java:1026-1082`), a composition's **first** recording pass sets
`playEnd = length` in lockstep. That explains why `playEnd` existed and was
plausible at all — it was in sync once, at 4:06, presumably matching a stop
point from an early take. Every recording/import pass *after* that only grows
`length` (step 2 above) without ever touching `playEnd` again, so:

- the audio import (or a later re-record) pushed `length` out further than
  4:06 without moving `playEnd`,
- the fader-out event at 4:18 was added later still, past even that stale
  `playEnd`,
- and `length` ended up at 6:08 from some intermediate edit (a dragged clip,
  an extended recording take) whose content was later deleted — with nothing
  to pull `length` back in once that content was gone.

None of this was visible in the editor: there's no handle showing where
`length` actually is, and the `playEnd` marker — the one end-of-region marker
that *is* visible on a clip's scrub lane elsewhere in the app — quietly does
nothing on a composition.

## Suggested minimal fix

Two independent, additive changes (neither touches playback/parse behavior
for existing loaded compositions beyond what's described):

1. **Give `UICompositionScrubLane` a `length` drag handle.** Mirror the
   `UIScrubLane.playEnd` drag interaction (`UIScrubLane.java:141-230`) but
   target `composition.length` directly via a new `LXCommand.Composition.SetLength`
   (or reuse `LXClip`'s existing `length` `Cursor.Parameter` — it's already a
   registered, undoable parameter, just never wired to a UI gesture). This is
   the direct fix: it gives compositions the trim-the-end affordance clips
   already have.
2. **Either wire `playEnd` into composition playback, or stop serializing it
   for compositions.** Right now `playEnd`/`playStart` round-trip through
   every `.lxp` save/load for a composition, are visible in raw JSON, and
   *look* authoritative — but are 100% inert. Cheapest option: drop the
   `this instanceof LXComposition` special case in `runAutomation` and let
   `playEnd` do double duty as the trim point compositions currently lack
   (still leaves `length` as the "how far content has been recorded" high
   water mark, same relationship playStart/playEnd/length already have on
   ordinary clips). If that's too big a playback-semantics change this late,
   the low-risk alternative is a one-line comment + `LX.warning` when a
   composition's serialized `playEnd < length`, so this state is at least
   diagnosable without reading the source.

Fix #1 alone would have let this be trimmed from the timeline UI in seconds
instead of a `.lxp` JSON hand-edit; #2 removes the misleading dead parameter
(or makes it real).

Key files: `heronarts/lx/clip/LXClip.java` (`runAutomation` composition
override, `onCompositionEventImport`, `setRecordingLength`/
`setQuantizedRecordingLength`), `heronarts/lx/clip/LXComposition.java`
(`addAudioLane` calling `onCompositionEventImport`),
`heronarts/lx/studio/ui/clip/UIScrubLane.java` (the drag-handle pattern to
mirror), `heronarts/lx/studio/ui/composition/UICompositionScrubLane.java`
(where a `length` handle is missing), `heronarts/lx/studio/ui/composition/
UICompositionEditor.java:516` (the one place `composition.length` is read in
the UI), `heronarts/lx/command/LXCommand.java` (no `Composition.SetLength`
command exists to add one).

## Applied workaround (this file)

Hand-edited `2 - Bliss.lxp`'s `length` and `playEnd` cursors from
`(981 beats / 367888.04 ms)` and `(656 beats / 246013.97 ms)` down to a
shared `(692 beats / 259500.0 ms)` — 4 beats (1 bar at 160 BPM) past the
final `master/fader → 0` event at beat 688 / 258000 ms, so that event still
plays before the composition stops. Verified via the tempo's fixed 160 BPM /
375 ms-per-beat relationship (`millis = (beatCount + beatBasis) × period`,
confirmed exact against all three existing cursor fields) that no other
cursor in the file needed adjusting, and confirmed no other lane has any
event later than that fader event.
