# Feature request: pin waveform audio lanes to the top so they don't scroll with the lane list

**Branch:** `arrange` (LXStudio + GLX, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## What Jeff wants

> "Waveform audio lanes need to be pinnable to the top and not scroll with the
> massive lanes list. Pinning other lanes is not as critical for now."

On a real 20-minute composition the channel/bus lane list gets tall enough to
scroll vertically. The audio waveform is the visual reference everything is
choreographed against, so it needs to stay visible at the top while you scroll
the bus lanes underneath it. Today the audio lane is just the first row of the
same vertically-scrolling body, so it scrolls off the top like anything else.

## Symptom (verified from layout)

An `AudioClipLane` is always inserted at composition-lanes **index 0**
(`LXComposition.addAudioLane(...)` calls the private
`addAudioLane(laneObj, 0)`):

```java
// LX: heronarts/lx/clip/LXComposition.java:525-552
public AudioClipLane addAudioLane(File file) {
  final AudioClipLane lane = addAudioLane((JsonObject) null, 0);   // index 0
  ...
}
public AudioClipLane addAudioLane(JsonObject laneObj) {
  return addAudioLane(laneObj, 0);                                 // index 0
}
private AudioClipLane addAudioLane(JsonObject laneObj, int index) {
  ...
  this.mutableLanes.add(index, lane);                             // top of lane list
  onClipLaneAdded(lane);
}
```

So the waveform renders as the top row — but of the *scrolling* body, so it
scrolls away. There is currently **no pinned / sticky region inside the body**;
only the header and footer bands are vertically fixed (see below).

## How the lanes are laid out today

`UICompositionEditor extends UITimeline` (`UICompositionEditor.java:56`). The
timeline is three stacked bands: a fixed **header**, a vertically-scrolling
**body**, and a fixed **footer**.

```java
// LXStudio: heronarts/lx/studio/ui/timeline/UITimeline.java:80-86
this.header = new Header(ui);
this.footer = new Footer(ui);
this.body   = new Body(ui);
addChildren(this.header, this.body, this.footer);
this.ruler = new UIRuler(ui, this);
this.ruler.addToContainer(this.header);
```

**Header and footer are already "pinned" bands.** Both are `LensScrollContainer`,
which disables vertical scrolling and only scrolls horizontally, driven by the
time lens:

```java
// UITimeline.java:312-318
public abstract class LensScrollContainer extends UI2dScrollContainer {
  protected LensScrollContainer(...) {
    super(ui, x, y, w, h);
    setVerticalScrollingEnabled(false);   // pinned vertically
    setHorizontalScrollingEnabled(true);  // follows the lens
  }
```

The composition adds its scrub/locator lane into that header, so the header
already hosts a real composition sub-lane that stays put while the body scrolls:

```java
// UICompositionEditor.java:76-79
this.header.addChildren(
  this.loopBrace = new UILoopBrace(ui, this),
  this.scrubLane = new UICompositionScrubLane(ui, this)
);
```

A `LensScrollContainer` stacks its children vertically and auto-sizes its own
height (`onReflow`, `UITimeline.java:351-375`), and the body is positioned to
begin right below whatever height the header reports:

```java
// UITimeline.java:175-186
private float _getBodyHeight() {
  return LXUtils.maxf(0, this.height - this.header.getHeight() - this.footer.getHeight());
}
private void _setBodyPosition() {
  this.body.setPosition(0, this.header.getHeight(), this.width, _getBodyHeight());
}
```

**The body is where the scrolling happens.** `Body extends UI2dScrollContainer`
(vertical scroll) and holds exactly two children — a fixed-width `sideBar`
(per-lane metadata column) and a `content` pane (the lanes themselves, a
`LensScrollContainer` so it also side-scrolls with the lens):

```java
// UITimeline.java:407-432
public class Body extends UI2dScrollContainer {
  public final UI2dContainer sideBar;
  public final LensScrollContainer content;
  public Body(UI ui) {
    ...
    this.sideBar = new SideBar(ui);
    this.content = ... new LensScrollContainer(...) { ... };
    addChildren(this.sideBar, this.content);
  }
```

Every lane is added to **both** `body.content` (the envelope/waveform) and
`body.sideBar` (its metadata widget) at the same index:

```java
// UITimeline.java:286-298
protected final void addLane(UILane uiLane, int index) {
  ...
  uiLane.addToContainer(this.body.content, index);
  UI2dComponent sideBarComponent = uiLane.getSideBarComponent();
  ...
  sideBarComponent.setHeight(uiLane.getHeight()).addToContainer(this.body.sideBar, index);
}
```

The body's vertical scroll height is driven off the sidebar's stacked height
(`SideBar.onReflow` → `Body.this.setScrollHeight(getHeight())`,
`UITimeline.java:452-460`), so `sideBar` and `content` scroll vertically as one
unit. There is no per-lane exemption.

The composition wires audio lanes into this body like any other lane —
`addCompositionLane` builds a `UIClipEnvelope` (a `UIAudioLane` for audio lanes)
and calls `addLane(uiLane, lane.getIndex())`:

```java
// UICompositionEditor.java:199-209
private void addCompositionLane(LXClipLane<?> lane) {
  ...
  UIClipEnvelope<?> uiLane = UIClipEnvelope.create(this.ui, this, lane);
  this.laneMap.put(lane, uiLane);
  addLane(uiLane, lane.getIndex());
  this.overview.redraw();
}
```

`UIAudioLane` is the class that draws the clip bar + waveform
(`UIAudioLane.onDraw` / `drawWaveform`, `UIAudioLane.java:98-211`) and supplies
the enable + gain sidebar widget (`UIAudioLaneMetadata`, `UIAudioLane.java:244-270`).

### Is there any existing "pinned lane" notion? No — but there is a lane *class*

The model already classifies audio lanes as **major** lanes
(their own section, alongside bus lanes and text notes):

```java
// LX: heronarts/lx/clip/LXClipLane.java:76-83
public boolean isCompositionMajorLane() {
  return switch (this) {
    case AudioClipLane l -> true;
    case BusClipLane l   -> true;
    case TextNoteClipLane l -> true;
    default -> false;
  };
}
```

But "major" only governs section dividers and reorder grouping
(`LXComposition.validateMoveClipLaneIndex`, `LXComposition.java:456-487`); it has
**no** effect on scrolling. And note the sidebar is drag-to-reorder
(`SideBar` sets `setDragToReorder(true)`, `UITimeline.java:449`), so an audio
lane can currently even be dragged *below* bus lanes — there is nothing today
that keeps it anchored at the top beyond its index-0 insertion.

The two corner widgets confirm the header/footer already own a fixed sidebar
corner region: `CornerControls` sits over the header's sidebar corner and
`BottomCornerControls` over the footer's (`UICompositionEditor.java:94-97`,
`286-377`) — positioned in `onReflow` (`UICompositionEditor.java:113-133`).

## Suggested approach

Two viable shapes. Both are **host-side (LXStudio) UI plumbing** — this cannot
be done from the package (no hook lets a package re-parent host lanes; see the
package-side note at the end).

### Option A — add a pinned band to `Body` (recommended, most general)

Give `Body` a third region: a fixed-height, **non-vertical-scrolling** pinned
strip above `content`/`sideBar`, itself a `sideBar` + `LensScrollContainer`
`content` pair so the pinned waveform still time-scrolls horizontally with the
lens. Then:

1. Add a "pinned" predicate on the lane — the minimal version is just
   `lane instanceof AudioClipLane`, or a new `LXClipLane.isPinnedLane()` /
   `uiPinned` flag if Jeff later wants arbitrary lanes pinnable (his note says
   audio is all that matters for now).
2. In `UITimeline.addLane` / `removeLane` (`UITimeline.java:286-303`) and
   `UICompositionEditor.moveCompositionLane` (`UICompositionEditor.java:219-223`),
   dispatch pinned lanes into the pinned band's `content`/`sideBar` instead of
   the scrolling ones.
3. Reserve the pinned band's height in the body layout — subtract it in the
   equivalent of `_getBodyHeight` / `Body.onResize` so the scrolling `content`
   starts below it.
4. Extend `drawCursorIndicators` (`UITimeline.java:221-272`) to also paint the
   insert-marker / cursor / launch line across the pinned band, so the playhead
   stays continuous through the pinned waveform.

This keeps audio lanes as normal `UIAudioLane`/`AudioClipLane` instances
(waveform, gain, enable, resize all unchanged) — only their container changes.

### Option B — host the audio lane(s) in the existing header band

The header is *already* a pinned, lens-horizontal-scrolling band that already
hosts a composition sub-lane (`scrubLane`). Adding the `UIAudioLane`(s) into the
header stack below the scrub lane reuses all of that infrastructure and the
`_getBodyHeight` math automatically makes room (header height auto-sizes from its
children, `UITimeline.java:351-375`). The catch: the header has **no per-lane
sidebar column** — its sidebar corner is occupied by `CornerControls`
(`UICompositionEditor.java:286-313`) — so the audio lane's enable/gain
metadata widget (`UIAudioLaneMetadata`) needs a home, and the header wasn't
built to carry the full lane sidebar/reorder machinery. Simpler to prototype,
but less clean if more than one audio lane is ever pinned.

Either way, once audio lanes live in a pinned region, the drag-to-reorder edge
case (audio lane draggable below bus lanes) also goes away for free, since
pinned lanes would no longer share the body sidebar's reorder container.

## Honesty / uncertainty

- **Verified in source:** the three-band header/body/footer structure; that
  header/footer are vertically-pinned `LensScrollContainer`s while the body
  scrolls vertically as one unit; that audio lanes are inserted at index 0 and
  render as the top scrolling row; that no per-lane scroll exemption exists
  today; that `isCompositionMajorLane` does not affect scrolling; and the exact
  `addLane`/`Body` wiring a fix would touch.
- **Inference (not run):** that the waveform "scrolls away" in practice — I did
  not run the app, but it follows directly from the audio lane living in the
  vertically-scrolling `body.content` with unified scroll height. Matches Jeff's
  report.
- **Not fully traced:** the precise reflow/scroll-height recomputation a new
  pinned band would need in `Body` (I read the layout entry points but did not
  enumerate every `onResize`/`onReflow` interaction). Flagged as work, not a
  blind spot in the current behavior.
- **Package-side note:** per the `composition-ui` contract this is host-only —
  there is no package hook to re-parent host lanes, and read-only reflection
  can't restructure the live UI. This write-up is a host feature request for
  mcslee, not something implementable in `apotheneum.jvyduna.arrange`.

Key files:
- `heronarts/lx/studio/ui/timeline/UITimeline.java` — `Header`/`Footer`/`Body`
  bands, `LensScrollContainer`, `addLane`/`removeLane`, body layout
  (`:80-86, :175-186, :286-303, :312-318, :351-375, :383-462`).
- `heronarts/lx/studio/ui/composition/UICompositionEditor.java` — composition
  header wiring, `addCompositionLane`/`moveCompositionLane`, corner controls
  (`:56, :76-79, :199-223, :286-377`).
- `heronarts/lx/studio/ui/composition/UIAudioLane.java` — waveform + clip-bar
  draw and audio sidebar metadata (`:98-211, :244-270`).
- `heronarts/lx/clip/LXComposition.java` — audio lane always inserted at index 0
  (`:525-552`), major-lane reorder rules (`:456-487`).
- `heronarts/lx/clip/LXClipLane.java` — `isCompositionMajorLane` (`:76-83`).
