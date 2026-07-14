# Bug report: composition channel rows don't follow mixer channel order (a guard added in `c1b6ba93` blocks the reorder)

**Branch:** `arrange` (LXStudio + LX, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## Symptom

Reordering channels in the main mixer strip is not reflected in the arrange
timeline: a composition's per-channel bus rows keep their original top-to-bottom
order even after the mixer order changes. Jeff's note — "Channels (which can't
currently be reordered independently in a composition) didn't follow/match the
channel order in the main channel UI. I'd be fine with not having control as long
as it was consistent." The composition is *supposed* to have no independent
reorder and simply mirror the mixer; it currently fails to mirror.

## How composition rows are ordered

The timeline UI does **not** re-derive row order from `lx.engine.mixer.channels`
at render time. It renders `composition.lanes` in **list order** — a stored
`ObservableList` (`LXClip.mutableLanes`, `LXClip.java:155-157`) — and each row's
position is just its index in that list:

```java
// LXStudio: UICompositionEditor.java:199-208  (add a row)
UIClipEnvelope<?> uiLane = UIClipEnvelope.create(this.ui, this, lane);
this.laneMap.put(lane, uiLane);
addLane(uiLane, lane.getIndex());        // position == list index
```
```java
// LX: LXClipLane.java:102-104
public int getIndex() {
  return this.clip.lanes.indexOf(this);  // pure list position
}
```

`setComposition` seeds the rows by iterating `composition.lanes` in order
(`UICompositionEditor.java:184-186`), and the overview strip iterates the same
list (`UICompositionEditor.java:458`). So **row order == `composition.lanes`
order**, full stop.

That list order is established in two places, both of which *do* start out
matching the mixer:

1. **At construction** — `initializeRegister` walks the live mixer list and
   creates a `BusClipLane` for each, inserting each at its mixer-index position:
   ```java
   // LX: LXComposition.java:187-193
   for (LXAbstractChannel channel : lx.engine.mixer.channels) {
     registerBus(channel);      // → findBusLane → addBusLanes → getBusLaneInsertIndex
   }
   ```
   `getBusLaneInsertIndex` places each new bus lane just before the lane whose
   `bus.getIndex()` is one greater (i.e. in mixer order), or before master
   (`LXComposition.java:342-354`), keyed off `LXAbstractChannel.getIndex()`
   (`LXAbstractChannel.java:356-357`).

2. **At load** — order comes from the **serialized** lane array, not the mixer.
   `load` unregisters, lets `super.load()` rebuild lanes from JSON in file order,
   then re-registers (`LXComposition.java:729-749`); `loadBusLane` recreates each
   bus lane at its serialized index (`LXComposition.java:492-516`). There is no
   pass that reconciles the loaded order against the current mixer order.

After that seed, the **only** thing that maintains the order against later mixer
reordering is the mixer listener:

```java
// LX: LXComposition.java:233-248
public void channelMoved(LXMixerEngine mixer, LXAbstractChannel channel) {
  moveBusLanes(channel);
}
```
```java
// LX: LXComposition.java:216-231
private void moveBusLanes(LXAbstractChannel bus) {
  int fromIndex = this.busLanes.get(bus).getIndex();
  int toIndex = getBusLaneInsertIndex(bus);
  ... for (LXClipLane<?> move : findAllBusLanes(bus, true)) {   // true == include the main BusClipLane
        moveClipLane(move, toIndex ...);
      }
}
```

`findAllBusLanes(bus, true)` deliberately includes the channel's **main
`BusClipLane`** — the row that represents the channel itself — plus its minor
lanes (`LXComposition.java:203-211`). So `moveBusLanes` clearly *intends* to move
the major row.

## Where the order diverges

**`moveClipLane` refuses to move a `BusClipLane`.** In commit `c1b6ba93`
("Validate moving of bus lanes", 2026-06-18) a guard was added at the top of the
public reorder method:

```java
// LX: LXClip.java:596-600
public LXClip moveClipLane(LXClipLane<?> lane, int index) {
  if (lane instanceof BusClipLane) {
    LX.error("Cannot directly move BusClipLane, reorder via the mixer");
    return this;                     // ← early return, no move, no listener fired
  }
  ...
}
```

`moveBusLanes` — the mixer-driven path the guard's own message points you to
("reorder via the mixer") — calls this **same public method** for the main
`BusClipLane`. The call is rejected and logged; the major row never moves in
`mutableLanes`, so `clipLaneMoved` never fires and
`UICompositionEditor.moveCompositionLane` (`UICompositionEditor.java:219-223`) is
never invoked for it. Only the channel's **minor** lanes (MIDI / Pattern /
Parameter) get repositioned. Net effect: **every mixer channel reorder leaves the
composition's channel rows frozen in their creation/serialized order.** The two
lists diverge the moment any channel is moved.

Both reorder entry points hit this same wall:

- **Strip up/down buttons** → `LXCommand.Mixer.MoveChannel` →
  `moveChannel(channel, delta)` (`LXCommand.java:1868`,
  `UIAbstractChannelStripControls.java:295,298`), which fires `channelMoved`
  (`LXMixerEngine.java:846-848`) → `moveBusLanes` → guarded out.
- **Drag to a new slot** → `moveChannel(bus, index, group)`
  (`LXMixerEngine.java:918-925`) → same `channelMoved` → same wall.

This is the most plausible mechanism behind Jeff's report, and it predicts a
**testable side effect**: each attempted reorder should emit
`Cannot directly move BusClipLane, reorder via the mixer` in `~/Chromatik/Logs`.
If that line is present after a reorder that didn't take, this is confirmed.

Two secondary contributors worth noting (both currently masked by the primary
guard, but real):

- **Group nudges fire only one `channelMoved`.** `moveChannel(channel, delta)`
  can physically reposition a whole group's subchannels in `mutableChannels`
  (e.g. `LXMixerEngine.java:798-806`, `812-817`) yet fires `channelMoved` for
  only the single `channel` argument (`846-848`). The drag variant correctly
  fires for every member (`918-925`). So even if the guard were lifted, the ±1
  nudge path would still under-notify the composition for group moves, leaving
  subchannel rows stranded.
- **Load never reconciles.** Because loaded order comes from the serialized
  array and `initializeRegister` only ensures a lane *exists* (it never re-sorts
  existing lanes to mixer order), any divergence baked into a saved `.lxp`
  persists across reloads with no self-healing pass.

**Confidence:** the code path is traced precisely at HEAD (`1d71721d`) and via
`git log`; I have not run the arrange host to observe it live. The guard-blocks-
the-mixer-path reading is unambiguous in source, and the log-line prediction
above is the cheap way to confirm empirically.

## Suggested minimal fix

Jeff is fine with "no independent reorder" as long as rows consistently mirror
the mixer — so the fix is to make the mixer→composition propagation actually move
the major row, not to add user control.

1. **Give `moveBusLanes` an unguarded internal move.** The guard should reject
   *user/command* attempts to drag a bus row, but not the internal mixer-driven
   reorder. Simplest: add a package-private `moveBusClipLane(...)` (or a
   `boolean internal` overload of `moveClipLane`) that skips the
   `instanceof BusClipLane` early-return, does the `mutableLanes` remove/add, and
   fires `clipLaneMoved`; have `moveBusLanes` call that for the main lane while
   still routing minor lanes through the public, `validateMoveClipLaneIndex`-
   checked path. This is the direct, behavior-preserving repair for the
   regression introduced in `c1b6ba93`.
2. **Add a reconciliation pass** at the end of `initializeRegister` (and after
   `load`) that re-sorts `busLanes` into current `mixer.channels` order via the
   same internal move. This heals any serialized divergence and makes load
   consistent, independent of fix #1.
3. **(If groups matter)** either route the strip ±1 button through
   `moveChannel(bus, index, group)` (which already notifies all members) or have
   `moveChannel(channel, delta)` fire `channelMoved` for every channel it
   physically repositioned, mirroring `LXMixerEngine.java:918-925`.

Fix #1 alone restores the intended "rows mirror the mixer" behavior for ordinary
channel reorders; #2 makes it robust across reloads; #3 closes the group edge
case.

Key files: `heronarts/lx/clip/LXClip.java` (the `moveClipLane` guard),
`heronarts/lx/clip/LXComposition.java` (`moveBusLanes`, `findAllBusLanes`,
`getBusLaneInsertIndex`, `initializeRegister`, `load`/`loadBusLane`),
`heronarts/lx/clip/LXClipLane.java` (`getIndex`),
`heronarts/lx/mixer/LXMixerEngine.java` (`moveChannel` overloads,
`_reindexChannels`, `channelMoved` firing),
`heronarts/lx/command/LXCommand.java` (`Mixer.MoveChannel`/`DropChannel`),
`heronarts/lx/studio/ui/composition/UICompositionEditor.java` (renders rows in
`composition.lanes` order),
`heronarts/lx/studio/ui/mixer/UIAbstractChannelStripControls.java` (strip
up/down buttons).
</content>
</invoke>
