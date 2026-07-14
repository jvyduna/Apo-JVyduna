# Feature request: apply composition state while scrubbing (curve preview + pattern-change seek)

**Branch:** `arrange` (LX + LXStudio, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## What Jeff wants

Drag the composition playhead and see the **live output update to match the
state at that time** — the interpolated value of every parameter lane (even
today's linear ramps), and the pattern that a pattern-change event says should
be active — without having to actually start playback. Two motivations:

1. **Curve preview** while designing an automation envelope ("most useful with
   snap off"): scrub across a ramp and watch the fixture track the curve.
2. **Pattern-change seek**: start playback mid-phrase and have the correct
   pattern already active, instead of having to place the marker on a
   pattern-change event so the right pattern gets caught.

## Two problems

Both reduce to the same structural fact: **moving the insert marker is not a
"seek" — it applies no state.** State is only ever applied while the transport
is *running*.

- **(a) No curve preview on scrub.** Dragging a locator or clicking in the
  scrub lane moves `insertMarker` (a plain cursor value). Nothing evaluates the
  parameter lanes at that position, so the fixtures don't move until you launch
  playback and the playhead actually sweeps past events.
- **(b) Pattern-change seek is only half-solved.** When you *launch*
  (`launchAutomationFrom`), the host already seeds the correct pattern at the
  launch cursor (see `initializeCursorPlayback` below) — so a mid-phrase launch
  is not actually broken. What's missing is applying that same "which pattern is
  active here" logic to a **stopped** scrub, so you can preview / confirm the
  active pattern before committing to play. Jeff's "catch a pattern change
  keyframe" workaround is the pre-launch static state showing the *previous*
  pattern until the transport moves. (Uncertainty flagged below.)

## How scrub / playback applies state today

**Scrubbing sets a marker and nothing else.** In the composition scrub lane,
dragging a locator body (release without Cmd, transport stopped) calls only
`setInsertMarker`:

```java
// LXStudio: UICompositionScrubLane.java:227-238  (UILocator.onMouseReleased)
if (this.hasLaunch && (composition != null) && !composition.isRecording()) {
  if (mouseEvent.isCommand() || composition.isRunning()) {
    this.locator.launch.trigger();
  } else {
    composition.setInsertMarker(this.locator.position.cursor);  // marker only
  }
}
```

`setInsertMarker` just stores a bounded cursor — no lane evaluation, no
`setCursor`, no playback:

```java
// LX: LXClip.java:824-827
public LXClip setInsertMarker(Cursor insertMarker) {
  this.insertMarker.set(insertMarker.bound(this));
  return this;
}
```

A click in empty scrub-lane space is the one gesture that *does* apply state —
but only because it **launches playback** (it does not stay stopped):

```java
// LXStudio: UICompositionScrubLane.java:277-291  (onMousePressed, empty area)
this.composition.setInsertMarker(position);
this.composition.launchAutomationFrom(position);   // starts the transport
```

The base clip scrub lane behaves the same way — marker drags move
`playStart`/`playEnd`, and an empty-area click does `launchAutomationFrom`
(`UIScrubLane.java:160-208`). There is **no draggable playhead that applies
state as it moves** anywhere in `ui/clip/` or `ui/composition/`.

**State is applied only while running.** `LXRunnableComponent.loop` gates
`run()` on the running flag, so a stopped composition never advances or
evaluates anything:

```java
// LX: LXRunnableComponent.java:173-174
public void loop(double deltaMs) {
  if (this.running.isOn()) {
    run(deltaMs);
    ...
```

When the transport *is* running, `run` → `runAutomation` → `playCursor` walks
each lane between the old and new cursor (`LXClip.java:1460-1585`,
`1409-1417`). Two evaluators there are exactly the "apply state at cursor"
primitives we'd want on scrub:

- **Parameter value at cursor** (the curve preview) — `playCursor` sets the
  parameter from the envelope, interpolating linearly between the surrounding
  events at the target cursor:

  ```java
  // LX: ParameterClipLane.java:465-474
  if ((prior == null) || CursorOp().isAfter(to, next.cursor)) {
    this.parameter.setNormalized(next.getNormalized());
  } else if (hasInterpolation()) {
    this.parameter.setNormalized(LXUtils.lerp(
      prior.getNormalized(),
      next.getNormalized(),
      CursorOp().getLerpFactor(to, prior.cursor, next.cursor)));  // <- linear curve
  }
  ```

- **Active pattern at cursor** (the pattern seek) — already factored into a
  seek-style helper that is called at playback start, not on scrub:

  ```java
  // LX: PatternClipLane.java:147-157
  private void triggerPatternAtCursor(Cursor to) {
    LXPattern pattern = getPatternAtCursor(to);          // event at/just before cursor
    if ((pattern != null) && (this.engine.getTargetPattern() != pattern)) {
      triggerPattern(pattern);                            // -> engine.goPattern(pattern)
    }
  }
  @Override
  void initializeCursorPlayback(Cursor to) {
    triggerPatternAtCursor(to);
  }
  ```

`initializeCursorPlayback` is invoked for every lane exactly once, at
transport start, from the launch cursor:

```java
// LX: LXClip.java:920-923  (onStart)
for (LXClipLane<?> lane : this.lanes) {
  lane.initializeCursorPlayback(this.cursor);
}
```

The base implementation is a no-op (`LXClipLane.java:614`); only
`PatternClipLane` overrides it. Notably **parameter lanes do not seed a value in
`initializeCursorPlayback`** — their value is first set on the next `playCursor`
frame — so even the launch path relies on the running transport to paint the
first parameter value.

**Net:** the machinery to compute "the value / active pattern at an arbitrary
cursor" exists and is well-isolated (`ParameterClipLane.playCursor`,
`PatternClipLane.triggerPatternAtCursor`), but it is only reachable through the
running transport. A stopped scrub takes the `setInsertMarker` path, which
touches none of it.

## Suggested minimal design

Add a stopped-transport **seek** that applies composition state at a cursor,
and call it whenever the insert marker moves via scrub. This reuses the exact
evaluators above, so playback semantics are unchanged.

1. **`LXClip.seekToCursor(Cursor)` (or `LXComposition`-level).** When
   `!isRunning()`, for each lane apply the state at the cursor:
   - `PatternClipLane` → `initializeCursorPlayback(cursor)` (already the right
     call; it hard-cuts via `goPattern` only when the target differs).
   - `ParameterClipLane` (non-`Trigger`) → apply the envelope value at the
     cursor. The value branch of `playCursor` (`ParameterClipLane.java:461-474`)
     is what we want; factor its body into an `applyValueAtCursor(cursor)` and
     call that. **Skip `Trigger` lanes** — a scrub must not fire trigger events
     as it drags (today's `playCursor` fires them via the `Trigger` branch,
     `ParameterClipLane.java:453-456`, which is why we don't just reuse
     `playCursor(cursor, cursor, true)` wholesale).
   - Skip `Audio`, `MidiNote`, `TextNote` (nothing to preview / would emit).
   Do **not** call `setCursor`/advance the transport; this is a preview apply,
   not a play.

2. **Invoke it on scrub.** In `UICompositionScrubLane`, after each
   `setInsertMarker(...)` on a stopped, non-recording composition — the locator
   release (`:232`), the locator drag (which currently only moves the locator),
   and ideally continuously during an insert-marker drag — call
   `composition.seekToCursor(position)`. `goPrevious/goNextLocator`
   (`LXComposition.java:598-630`) already branch stopped→`setInsertMarker`;
   route those through the same seek.

3. **Snap-off is the default win.** Jeff calls out "most useful with snap off":
   the seek should use the raw dragged cursor (the code already computes
   `targetCursor` before the optional `shouldSnap` snap,
   `UICompositionScrubLane.java:207-212`), so smooth sub-grid scrubbing shows the
   interpolated curve.

Because pattern seeks go through `engine.goPattern`, a scrub that crosses a
pattern-change boundary will hard-cut the active pattern (channel transition
rules apply). That is the desired "correct pattern is already active" behavior
for (b), and makes a subsequent mid-phrase launch visually continuous.

**Uncertainty / honesty:**
- I could not fully reconcile Jeff's pattern-change complaint with the code:
  `launchAutomationFrom` → `onStart` → `initializeCursorPlayback` already
  triggers `getPatternAtCursor(launchCursor)`
  (`LXClip.java:920-923` → `PatternClipLane.java:154-157`), so a mid-phrase
  *launch* should select the right pattern. The gap I can prove is the
  **stopped** case (marker moved, transport not started) applying nothing. If
  Jeff is also seeing a wrong pattern after an actual launch, that's a separate
  issue (possibly `goPattern` transition/blend timing, or `getTargetPattern()`
  already equalling the target) worth reproducing before assuming the fix above
  covers it.
- Whether the seek should run continuously during a marker *drag* vs. only on
  release is a UX call. Continuous is what "scrub to preview a curve" implies,
  but it means a `goPattern` per boundary crossed — acceptable, but confirm it
  feels right interactively.
- All of this is **host-side (LX/LXStudio)** work and therefore out of scope for
  the package-only `composition-ui` contract; this write-up is upstream
  feedback, not something to implement in `apotheneum.jvyduna`.

Key files: `heronarts/lx/clip/LXClip.java` (`setInsertMarker` :824,
`onStart`/`initializeCursorPlayback` dispatch :920, `run`/`runAutomation`/
`playCursor` :1460/:1522/:1409), `heronarts/lx/clip/ParameterClipLane.java`
(value-at-cursor :461-474; `Trigger` branch :453-456),
`heronarts/lx/clip/PatternClipLane.java` (`triggerPatternAtCursor` /
`initializeCursorPlayback` :147-157), `heronarts/lx/clip/LXClipLane.java`
(base `initializeCursorPlayback` :614), `heronarts/lx/LXRunnableComponent.java`
(running gate :173-174), `heronarts/lx/studio/ui/composition/UICompositionScrubLane.java`
(scrub handlers :227-238, :277-291),
`heronarts/lx/studio/ui/clip/UIScrubLane.java` (base scrub :160-208).
