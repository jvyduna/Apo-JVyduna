# Bug + feature: lane collapse state is inconsistent on load and not persisted (nor is zoom)

**Branch:** `arrange` (LXStudio + LX, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## Symptom

On every relaunch, a channel's major bus lane in the arrange timeline shows a
**collapsed** chevron (and collapses its own row height) while its **child
parameter lanes are still rendered** underneath it — an internally contradictory
state. A single manual expand/collapse toggle of that lane reconciles the two,
and it behaves correctly from then on. Jeff also asks, as a feature, that the
timeline **zoom level** and per-channel **collapse state** survive a
quit/relaunch the way they were left.

Central finding: the collapse *and* zoom values Jeff wants remembered are, per
the source, **already serialized** (both are internal parameters saved into the
`.lxp`). The visible defect is not missing persistence but a **load-time
reconcile bug**: the restored "collapsed" flag on a major lane is never
propagated to its child lanes' visibility flag, because the only code that syncs
them is a UI listener that fires on *change* and is attached *after* the load
has already set the value. Fixing that one reconcile makes the collapse feature
effectively work; zoom already round-trips in code (flagged below with the
caveat that I could not run it).

## How collapse state is initialized today

There are **two independent flags** per lane, plus one derived visual:

```java
// LX: heronarts/lx/clip/LXClipLane.java:42-45
public final MutableParameter uiHeight    = new MutableParameter("UI Height");
public final BooleanParameter uiVisible   = new BooleanParameter("UI Visible", true);
public final BooleanParameter uiExpanded  = new BooleanParameter("UI Expanded", true);
public final BooleanParameter uiMaximized = new BooleanParameter("UI Maximized", false);
```

- **`uiExpanded`** — the *major* (bus) lane's collapse chevron and its own row
  height. Registered as an **internal parameter**, so it *is* serialized and
  restored:
  ```java
  // LX: heronarts/lx/clip/LXClipLane.java:64-66
  addInternalParameter("uiHeight", this.uiHeight);
  addInternalParameter("uiExpanded", this.uiExpanded);
  addInternalParameter("uiMaximized", this.uiMaximized);
  ```
- **`uiVisible`** — whether a *child* (parameter/pattern) lane is shown. It is
  conspicuously **absent** from that `addInternalParameter` block above and has
  no `addParameter` anywhere (only two references exist:
  `LXClipLane.java:43` and `LXComposition.java:103`). So it is **never
  serialized** and always reconstructs at its default `true`.

Internal params are the ones written under `"internal"` and reloaded:
```java
// LX: heronarts/lx/LXComponent.java:1509  (save)
obj.add(KEY_INTERNAL, LXSerializable.Utils.saveParameters(this.internalParameters));
// LX: heronarts/lx/LXComponent.java:1529-1532  (load)
if (obj.has(KEY_INTERNAL)) { ... loadParameters(this, parametersObj, this.internalParameters); }
```

The **only bridge** between the persisted `uiExpanded` (parent) and the
non-persisted `uiVisible` (children) is a single UI-side listener that runs
`toggleBusLaneVisibility` whenever the chevron changes:

```java
// LXStudio: ui/composition/UIBusLane.java:278-280  (in UIBusLaneMetadata ctor)
addListener(lane.uiExpanded, p -> {
  lane.composition.toggleBusLaneVisibility(lane, lane.uiExpanded.isOn());
});
```
```java
// LX: heronarts/lx/clip/LXComposition.java:101-105
public void toggleBusLaneVisibility(BusClipLane busLane, boolean expanded) {
  for (LXClipLane<?> lane : findAllBusLanes(busLane.bus, false)) {  // non-major children only
    lane.uiVisible.setValue(expanded);
  }
}
```
(`findAllBusLanes(bus, /*includeMainBusLane*/ false)` returns exactly the
child lanes of that bus — `LXComposition.java:203-211`.) Downstream, the child
lane's UI honors `uiVisible`, and the major lane's height honors `uiExpanded`:
```java
// LXStudio: ui/clip/UIClipEnvelope.java:92,95
addListener(this.lane.uiVisible,  p -> setVisible(lane.uiVisible.isOn())); // child visibility; NOT immediate
addListener(this.lane.uiExpanded, this, true);                            // chevron/height; immediate=true
```

### Why parent-collapsed and children-shown disagree on load

The composition is rebuilt fresh on every project load, and the sync listener is
not present while the persisted `uiExpanded` value is being applied:

1. `LXTimelineEngine.load` constructs a **new** `LXComposition`, fully loads it
   (lanes + internal params, including each bus lane's restored `uiExpanded`),
   and only *then* notifies the UI:
   ```java
   // LX: heronarts/lx/clip/LXTimelineEngine.java:206-219
   LXComposition composition = new LXComposition(lx, this);
   composition.load(lx, compObj);      // uiExpanded restored here, UI not attached yet
   this.composition = composition;
   ...
   notifyCompositionChanged();          // UI setComposition() runs AFTER load
   ```
   During `composition.load()` the bus lanes are created and their `uiExpanded`
   is set to the saved value, but no `UIBusLane`/`UIBusLaneMetadata` exists yet,
   so the reconcile listener above is not attached and never fires. (When
   `LXComposition.load` later calls `initializeRegister()`, `registerBus`
   deliberately *reuses* the already-restored lane rather than resetting it —
   `LXComposition.java:279-282` — so `uiExpanded` keeps its loaded value.)
2. The UI then attaches on `setComposition`, walking the already-loaded lanes:
   ```java
   // LXStudio: ui/composition/UICompositionEditor.java:184-186
   for (LXClipLane<?> lane : this.composition.lanes) {
     addCompositionLane(lane);   // builds UIBusLane (attaches uiExpanded listener) + child UIClipEnvelopes
   }
   ```
   `UIBusLane`'s `addListener(lane.uiExpanded, …)` (`UIBusLane.java:278`) is a
   plain listener with **no immediate-invoke flag**, so attaching it does *not*
   run `toggleBusLaneVisibility` for the value that is already sitting on the
   lane. Meanwhile each child `UIClipEnvelope` is built with `uiVisible` at its
   default `true`, and its visibility listener (`UIClipEnvelope.java:92`) is also
   non-immediate — so children render.
3. Net: a lane saved collapsed comes back with **`uiExpanded == false`** (chevron
   collapsed, row height collapsed via the immediate listener at
   `UIClipEnvelope.java:95`) but **every child `uiVisible == true`** (children
   shown). The first user toggle flips `uiExpanded`, which *does* fire the
   `UIBusLane` listener → `toggleBusLaneVisibility` → children's `uiVisible`
   finally tracks the chevron, and the two stay consistent thereafter. This
   matches Jeff's "toggling to expand once fixes state from there" exactly.

This is confirmed from source (the flag definitions, the missing
`addInternalParameter`, the single non-immediate reconcile listener, and the
load-before-attach ordering). I did **not** run the app to watch the frames, but
the mechanism is unambiguous and self-consistent with the reported behavior.

## Persistence: collapse + zoom

**Collapse — already persisted, surfaced broken by the bug above.**
`uiExpanded` (and `uiMaximized`, `uiHeight`) are internal parameters
(`LXClipLane.java:64-66`), so a bus lane's collapsed/expanded chevron *does*
round-trip through the `.lxp`. What does **not** persist is the derived child
`uiVisible` — by design it is meant to be recomputed from `uiExpanded` on load,
but the recompute never happens (the reconcile bug). So the feature Jeff wants
("remember collapse state") is ~90% already implemented; the reconcile fix is
what actually delivers it. (Note: `uiVisible` *should* stay non-persisted and
derived — persisting it independently would just create a second way for the two
flags to disagree.)

**Zoom — also already persisted and, per source, restored.**
Timeline zoom is stored per-clip as an internal parameter and is written by the
UI whenever the user zooms:
```java
// LX: heronarts/lx/clip/LXClip.java:183,256
public final MutableParameter zoom = new MutableParameter("Zoom", 1);
addInternalParameter("zoom", this.zoom);           // → serialized in "internal"
```
```java
// LXStudio: ui/timeline/Lens.java:132   (write on every user zoom)
if (this.clip != null) { this.clip.zoom.setValue(this.zoom); }
```
On load, the new composition is fully loaded before the UI's `Lens.setClip` runs
(via `compositionChanged` → `UICompositionInspector.setComposition` →
`UICompositionEditor.setComposition` → `lens.setClip`,
`UICompositionInspector.java:64-70`, `UICompositionEditor.java:175`), and
`setClip` restores the saved zoom into the lens:
```java
// LXStudio: ui/timeline/Lens.java:319-341  (setClip, runs once per clip identity)
if (this.clip != clip) {
  ...
  _calcZoomMax();
  this.zoom = LXUtils.constrainf(this.clip.zoom.getValuef(), 1, this.zoomMax);  // :337
  ...
}
```
Because `LXTimelineEngine.load` always builds a *new* composition object
(`LXTimelineEngine.java:206`) and fires `notifyCompositionChanged`
(`:219`), the `if (this.clip != clip)` guard passes and the restore block runs
against the already-loaded `zoom`. So **per the source, zoom should already
survive relaunch.** I could not run the arrange host to confirm, and Jeff
observes it not sticking; if that's real, the discrepancy is narrower than
"never saved," and the likely suspects to reproduce/verify are:
- `zoomMax` clamping at `Lens.java:337`: the saved zoom is constrained to
  `_calcZoomMax()` (`Lens.java:176-180`), which is `1` when the timeline is
  empty/very short — a saved high zoom would be pulled back to `1` on a short
  composition.
- lens identity/ordering edge cases if a code path calls `setClip` with the same
  composition object it already holds (the guard would then skip the restore).

I recommend Jeff confirm the zoom value in the saved `.lxp` (look for
`"internal": { ... "zoom": <n> ... }` on the composition) to distinguish
"saved but not restored" from "not saved."

## Suggested minimal fix

Two separable pieces. Both are LX/LXStudio host changes — out of scope for the
package, so these are recommendations to upstream, not edits to make here.

**1. Load-consistency bugfix (the real defect).** Make the restored `uiExpanded`
reconcile child visibility exactly once at load, instead of only on later
change. Options, cheapest first:

- Have `UIBusLane`/`UIBusLaneMetadata` invoke the reconcile immediately when it
  attaches, i.e. attach the `uiExpanded` listener with the immediate-invoke flag
  (the same `, true` overload already used at `UIClipEnvelope.java:95`) so
  `toggleBusLaneVisibility(lane, lane.uiExpanded.isOn())` runs against the
  loaded value as the UI is built (`UIBusLane.java:278-280`).
- Or, after `LXComposition.load` finishes reconstructing lanes, call
  `toggleBusLaneVisibility(busLane, busLane.uiExpanded.isOn())` for each bus lane
  so the model-side `uiVisible` is coherent before any UI attaches (keeps the fix
  UI-independent). Either way the parent chevron and child visibility agree on
  the first frame and no manual toggle is needed.

**2. Persistence feature.** Largely already delivered by serialization; the
remaining work is small:
- **Collapse:** nothing new to persist — `uiExpanded`/`uiMaximized` already
  round-trip. Shipping fix #1 is what makes the restored collapse actually take
  effect on relaunch. (Leave `uiVisible` derived/non-persisted.)
- **Zoom:** `LXClip.zoom` already round-trips and `Lens.setClip` already restores
  it. The actionable step is to **verify** the restore end-to-end and, if it
  fails, address the narrower cause (most likely the `zoomMax` clamp at
  `Lens.java:337` on short/empty timelines — e.g. defer or re-apply the saved
  zoom after `totalTime`/view width are known, rather than constraining against a
  provisional `zoomMax`). No new serialization is required.

Key files:
`heronarts/lx/clip/LXClipLane.java` (uiExpanded/uiVisible fields + which are
internal, `42-45`, `64-66`),
`heronarts/lx/clip/LXComposition.java` (`toggleBusLaneVisibility` `101-105`,
`findAllBusLanes` `203-211`, load/register ordering `279-282`, `730-741`),
`heronarts/lx/clip/LXTimelineEngine.java` (new-composition load + notify
`196-220`),
`heronarts/lx/clip/LXClip.java` (`zoom` internal param `183`, `256`),
`heronarts/lx/LXComponent.java` (internal-param save/load `1509`, `1529-1532`),
`heronarts/lx/studio/ui/composition/UIBusLane.java` (reconcile listener
`278-280`),
`heronarts/lx/studio/ui/clip/UIClipEnvelope.java` (uiVisible/uiExpanded UI
listeners `92`, `95`),
`heronarts/lx/studio/ui/composition/UICompositionEditor.java` (setComposition →
setClip + lane build `175`, `184-186`),
`heronarts/lx/studio/ui/composition/UICompositionInspector.java`
(compositionChanged wiring `64-70`),
`heronarts/lx/studio/ui/timeline/Lens.java` (zoom write/restore `132`, `337`,
setClip guard `319-341`).
