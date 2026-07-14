# Bug report: locator label click-to-rename can never work (double-click detection defeated by launch-consume)

**Branch:** `arrange` (LXStudio + GLX, 1.2.2-SNAPSHOT, as of 2026-07-08)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## Symptom

Clicking a locator's text label and typing does not rename it — the only
working path is focusing the flag and pressing `Cmd+R`. Every click on the
label also (surprisingly) launches playback from that position.

## How the pieces are wired

Each locator is a `UILocator extends UI2dContainer implements UIMouseFocus`
(96×11 px) in the scrub lane; the label is a real child `UITextBox` bound to
`locator.label`, occupying x=12..96, built with **`.disableImmediateEdit()`**
and `setMouseCursor(MouseCursor.CLIP_PLAY)`
(`UICompositionScrubLane.java:156-165`). The flag's hit region is only the
leftmost 11 px (`mx < this.height`, line 192).

`Cmd+R` works because `UILocator.onKeyPressed` handles it locally
(`UICompositionScrubLane.java:243-247`): `textBox.focus(keyEvent);
textBox.edit();` — `UIInputBox.edit()` (`UIInputBox.java:421-434`) being the
single entry point for inline editing. (No global VK_R shortcut exists.)

## Why click-and-type does nothing

1. Single-click on the label: `UITextBox.onMousePressed` only starts editing
   on `isDoubleClick()` (`UITextBox.java:169-178`); a single click just
   focuses without consuming.
2. `UILocator`'s flag guard (`mx < 11`) doesn't match label clicks, so the
   press bubbles to the scrub lane, which **sets the insert marker, launches
   playback, and consumes** (`UICompositionScrubLane.java:277-291`).
3. Typing afterwards is dead because of `disableImmediateEdit()`: the
   focused-but-not-editing branch of `UIInputBox.onKeyPressed` requires
   `immediateEdit` for type-to-edit (`UIInputBox.java:763-770`). (Obscure
   footnote: plain `Enter` *would* start editing via `returnKeyEdit`,
   `UIInputBox.java:84,782-786` — but only when the textbox has focus, which
   is undiscoverable.)

## Why even double-click-to-edit can never fire

`InputDispatch` only counts a press as a repeat click if the **previous**
press was NOT consumed (`glx/InputDispatch.java:150-151`):

```java
if ((this.previousMousePress != null) && !this.previousMousePress.isConsumed()
    && this.previousMousePress.isRepeat(mouseEvent)) {
  mouseEvent.setCount(this.previousMousePress.getCount() + 1);
```

The first click of any double-click on a label is consumed by the scrub
lane's launch-playback handler, so the second press arrives with `count = 1`
and `isDoubleClick()` (`count % 2 == 0`, `MouseEvent.java:198-200`) is false.
The base timeline lane deliberately avoids exactly this trap —
`// Do not consume mouseEvent, leave for double-click count`
(`UIClipLane.java:242`) — but `UICompositionScrubLane`'s override consumes
anyway.

So every click on a locator label both launches playback *and* resets the
double-click counter: the `UITextBox`'s built-in double-click-to-edit
affordance is unreachable by construction.

History: this has been the behavior since the `UILocator` overhaul
(`8e5035c8`, "proper UI element with Cursor.Parameter support, launching,
renaming, etc.", Jun 16 2026) — not a recent regression.

## Suggested minimal fix

In `UICompositionScrubLane` (option 1 matches the rest of the UI — lane
headers and clip names edit on double-click):

1. In `UILocator.onMousePressed`, route label double-clicks to the existing
   entry point:
   `if (mouseEvent.isDoubleClick() && mx >= this.height) { mouseEvent.consume(); this.textBox.focus(mouseEvent); this.textBox.edit(); }`
   …and make the double-click detectable at all by following
   `UIClipLane`'s own convention: don't `consume()` the launch-playback
   press at `UICompositionScrubLane.java:288` (or defer the launch/consume
   to mouse-release when the press landed on a locator label).
2. Alternative (not recommended): drop `.disableImmediateEdit()` so
   click-then-type renames — but stray keystrokes would then rename
   locators, and it conflicts with `UILocator`'s arrow/delete bindings.

Key files: `heronarts/lx/studio/ui/composition/UICompositionScrubLane.java`,
`glx/ui/component/UITextBox.java`, `glx/ui/component/UIInputBox.java`,
`glx/InputDispatch.java`, `ui/timeline/lane/UIClipLane.java` (the
convention comment).
