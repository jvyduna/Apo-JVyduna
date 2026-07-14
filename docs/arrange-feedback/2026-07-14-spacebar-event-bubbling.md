# Bug report: spacebar isn't scoped to the composition editor — it lands on whatever widget holds key focus (usually the toolbar transport)

**Branch:** `arrange` (LXStudio + GLX, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## Symptom

With the arrange composition editor open and visibly "active," pressing the
spacebar doesn't reliably drive the composition's own play/pause. Sometimes it
does; sometimes it behaves like the toolbar transport button (quantized launch),
and sometimes it does nothing — depending on what was last clicked. Jeff's read:
"a keyboard handler is bubbling the spacebar event when compositions is focused."

The composition editor *does* have a correct, dedicated spacebar handler. The
problem is that it only fires when a **lane** inside the editor is the focused
element, and the editor's container panes are not focusable, so "the composition
pane is active" is not the same as "a composition element holds key focus."

## How key events dispatch and bubble

GLX delivers each key event to the focused window's root, which walks the focus
chain top-down and runs each object's `onKeyPressed` bottom-up (leaf first, root
last):

```java
// GLX: heronarts/glx/InputDispatch.java:293  → engine thread
this.glx.ui.keyEvent(keyEvent);
// GLX: heronarts/glx/ui/UI.java:1391
case PRESS, REPEAT -> root.keyPressed(keyEvent, keyChar, keyCode);
```

```java
// GLX: heronarts/glx/ui/UIObject.java:902-933
void keyPressed(KeyEvent keyEvent, char keyChar, int keyCode) {
  if (this.focusedChild != null) {
    this.focusedChild.keyPressed(keyEvent, keyChar, keyCode);  // recurse DOWN first
  }
  ...
  if (!keyEvent.isConsumed()) {
    onKeyPressed(keyEvent, keyChar, keyCode);                  // THIS object handles last
  }
}
```

Two consequences that matter here:

1. **`onKeyPressed` only runs for objects on the focus chain** (root → … →
   focused leaf). An object that is *not* an ancestor of the focused element
   never sees the event, regardless of where it sits on screen.
2. The root's own `onKeyPressed` runs **last**, and it forwards to the
   app-level `topLevelKeyEventHandler`:

```java
// GLX: heronarts/glx/ui/UI.java:185-206  (UIRoot.onKeyPressed)
if (topLevelKeyEventHandler != null) {
  topLevelKeyEventHandler.onKeyPressed(keyEvent, keyChar, keyCode);
}
```

Bubbling stops as soon as any handler calls `keyEvent.consume()`.

## Where spacebar is handled

There is **no global bare-spacebar shortcut.** The top-level handler
(`LXStudio.java:621-667`) only acts on unmodified `VK_COMMA` / `VK_PERIOD` /
`VK_SLASH`, and `registerDefaultKeyboardShortcuts` (`LXStudio.java:765-867`)
registers nothing for `VK_SPACE`. So bare space that reaches the top level does
nothing (unless computer-keyboard MIDI is on, an unrelated path).

That leaves exactly **two** spacebar handlers relevant to composition playback,
and each fires only when *its own* object is on the focus chain:

**A. Composition editor (the intended handler).** The editor's inspector routes
its key events into the shared grid-clip key handler:

```java
// LXStudio: ui/composition/UICompositionInspector.java:82-85
protected void onKeyPressed(KeyEvent keyEvent, char keyChar, int keyCode) {
  super.onKeyPressed(keyEvent, keyChar, keyCode);
  UIClipInspector.onGridKeyPressed(getLX(), this.composition, keyEvent, keyChar, keyCode);
}
```

```java
// LXStudio: ui/clip/UIClipInspector.java:116-123
if ((clip != null) && (keyCode == KeyEvent.VK_SPACE)) {
  keyEvent.consume();
  if (!clip.isRunning() && keyEvent.isShiftDown()) {
    clip.playFromCursor();
  } else {
    clip.triggerAction(false, false);   // immediate pause / play-from-marker
  }
}
```

This consumes space and drives the composition **immediately**. But
`UICompositionInspector extends UIFillContainer` (a plain `UI2dContainer`,
**not** `UIFocus`), and `UICompositionEditor extends UITimeline extends
UI2dContainer` (also **not** `UIFocus`). Only the individual lanes are
focusable — `UILane implements UIFocus` (`ui/timeline/lane/UILane.java:34`),
`UIClipEnvelope … implements UIFocus` (`ui/clip/UIClipEnvelope.java:54`). So
handler A is only on the focus chain when a **lane leaf** is the focused
element (i.e., the user last clicked a lane body). Click the ruler, the
overview, empty header space, or anything outside the editor, and the inspector
is no longer an ancestor of the focused object — handler A never runs.

**B. Toolbar transport button (the competing handler).** The transport controls
are "Play/Stop/Record controls for the composition engine," with Play bound to
`composition.launch`:

```java
// LXStudio: ui/toolbar/UITransportControls.java:56-60, 78
this.play = new UIPlay(ui), ...
this.play.setParameter(this.composition.launch).setEnabled(true);
```

```java
// LXStudio: ui/toolbar/UITransportControls.java:186-196  (UITransportButton)
protected void onKeyPressed(KeyEvent keyEvent, char keyChar, int keyCode) {
  super.onKeyPressed(keyEvent, keyChar, keyCode);
  if (keyCode == KeyEvent.VK_SPACE) {
    keyEvent.consume();
    if (enabled) { action(); }   // → composition.launch, QUANTIZED trigger
    ...
  }
}
```

`action()` (`UITransportControls.java:147-154`) calls `.trigger()` on the
`QuantizedTriggerParameter composition.launch` — a **quantized** launch, not the
immediate `triggerAction` of handler A. `UIPlay` is a `UIButton`, which
`implements … UIFocus` (`UIButton.java:43`), so **clicking the toolbar Play
button gives it keyboard focus and it keeps that focus** until something else is
clicked (`UIObject.java:709-711`: a non-consumed press on a `UIMouseFocus`
object calls `focus()`).

## Root cause

The composition editor's panes are not focusable, so the composition's spacebar
handler (A) is scoped to *a focused lane leaf*, not to *the editor pane being
active*. Whenever key focus is anywhere other than a composition lane, space is
handled by whatever focusable widget currently owns focus. The most natural way
to end up in that state is the transport itself: click the toolbar **Play**
button once (a completely reasonable thing to do while working on a
composition), and it now holds key focus (handler B). From then on, spacebar
runs the **quantized** `composition.launch` from the toolbar instead of the
editor's **immediate** `clip.triggerAction`, even though the composition editor
is the pane you're looking at. If focus instead sits on some unrelated widget
that ignores space, the event bubbles all the way to the top-level handler and
does nothing.

So this is not a literal missing `consume()` on a bubbling path — both handlers
consume correctly. It's a **focus-scope gap**: there is no container-level
`UIFocus` on the composition editor to claim/retain focus when the pane is the
active editing surface, so "the composition is focused" (visually) and "a
composition element holds key focus" (actually) diverge. Two handlers with
**different** semantics (immediate `triggerAction` vs. quantized `launch`) then
answer the same key depending on that hidden focus state.

**Confirmed vs. inferred.** Confirmed from source: the bubble/consume model
(`UIObject.java:902-933`, `UI.java:185-206,1391`); that no global bare-space
shortcut exists (`LXStudio.java:621-667,765-867`); the two competing handlers
and what each does (`UIClipInspector.java:116-123`;
`UITransportControls.java:56-60,147-154,186-196`); and that the editor panes are
not `UIFocus` while the lanes and the transport button are
(`UIFillContainer`/`UITimeline`; `UILane.java:34`, `UIClipEnvelope.java:54`,
`UIButton.java:43`). Inferred: the specific everyday reproduction (clicking the
toolbar Play button, which then retains focus so subsequent space keeps hitting
the transport) — this follows directly from the focus-on-press rule at
`UIObject.java:709-711`, but I could not exercise the running app to confirm the
exact widget that holds focus in Jeff's session.

## Suggested minimal fix

Give the composition editor a container-level focus target so space is scoped to
the composition whenever that pane is the active editing surface, matching the
lane-level behavior that already works:

1. **Make the editor container focusable and claim focus on press.** Have
   `UICompositionEditor` (or its body) `implement UIFocus` (or at least
   `UIKeyFocus`) and route its `onKeyPressed` through
   `UIClipInspector.onGridKeyPressed(getLX(), composition, …)`, the same call
   the inspector already makes (`UICompositionInspector.java:82-85`). Then a
   click anywhere in the editor keeps space on handler A, and the event only
   escapes to the toolbar/global level when the editor genuinely isn't focused.
   This is host-side (LX/LXStudio) work — outside this package's `composition-ui`
   scope, so it belongs in a feedback note to mcslee/JKB, not a package change.

2. **(Secondary) Reconcile the two behaviors.** Handler A does an immediate
   `triggerAction`; the toolbar handler B does a quantized `launch`. Even after
   focus is fixed, decide whether editor-space and toolbar-space should mean the
   same thing; if so, point one at the other so the spacebar is consistent
   regardless of which surface has focus.

No package-side workaround fully substitutes for the host fix. The reliable
habit today is to **click a lane body in the editor before pressing space** so a
`UIFocus` lane owns key focus and handler A (`UIClipInspector.java:116-123`)
runs; avoid pressing space right after clicking the toolbar Play button.

Key files: `heronarts/lx/studio/ui/composition/UICompositionInspector.java`
(editor space entry, l.82-85),
`heronarts/lx/studio/ui/clip/UIClipInspector.java` (`onGridKeyPressed`,
l.112-155), `heronarts/lx/studio/ui/toolbar/UITransportControls.java` (competing
transport handler, l.56-60/147-154/186-196),
`heronarts/lx/studio/ui/timeline/lane/UILane.java` (`UIFocus`, l.34),
`heronarts/lx/studio/ui/clip/UIClipEnvelope.java` (`UIFocus`, l.54),
`heronarts/lx/studio/LXStudio.java` (top-level handler + shortcut table, no bare
space, l.621-667/765-867), `glx/ui/component/UIButton.java` (`UIFocus`, l.43),
`glx/ui/UIObject.java` (bubble/consume + focus-on-press, l.709-711/902-940),
`glx/ui/UI.java` (root dispatch + `topLevelKeyEventHandler`, l.185-206/1383-1392),
`glx/InputDispatch.java` (event → UI, l.286-298).
