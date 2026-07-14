# Bug report: composition window position is discarded when its top-left has a non-positive X or Y (defeats second-monitor placement)

**Branch:** `arrange` (LXStudio + GLX, 1.2.2-SNAPSHOT, as of 2026-07-14)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## Symptom

> "2nd monitor never gets the composition window position on relaunch — did I
> already log this? Probably not a .lxp prop."

Drag the composition window onto a second monitor, quit, relaunch: the window
comes back centered on the primary monitor instead of where it was left. The
main window's own position usually survives; the composition window on a
secondary display does not.

## What the composition window actually is

It is a **separate GLFW window**, not a docked UI region. GLX owns two OS
windows — `MainWindow` and `AltWindow` — and the `AltWindow` is the composition
window, titled `"Composition"` and keyed to preferences slot `ALT`:

```java
// GLX: heronarts/glx/WindowEngine.java:917-923
public class AltWindow extends Window {
    private AltWindow() {
      super(LXPreferences.Window.ALT, BASE_VIEW_ID_ALT, "Composition");
      // Hide until we are loaded and confirmed visible
      hide();
```

Its visibility is bound to the composition toggle, confirming the identity:

```java
// GLX: heronarts/glx/GLX.java:174
windowEngine.showAltWindow(this.engine.showCompositionWindow.isOn());
```

LXStudio then lays the composition editor (`AltContext`) into that window using
`altWindow` dimensions (`LXStudio.java:592`, `907`). So window *geometry* is
100% a GLX/preferences concern; LXStudio only fills the client area.

## How window geometry is persisted today

Jeff's hunch is correct: **it is not a `.lxp` property.** Position and size for
both windows live in the app-wide preferences file `.lxpreferences`, keyed
`windows.main` / `windows.alt` — completely independent of any project:

```java
// LX: heronarts/lx/LXPreferences.java:43, 340
private static final String PREFERENCES_FILE_NAME = ".lxpreferences";
...
this.file = new File(flags.mediaPath, PREFERENCES_FILE_NAME);
```

```java
// LX: heronarts/lx/LXPreferences.java:468-471  (save)
final JsonObject windows = new JsonObject();
windows.add(Window.MAIN.key, LXSerializable.Utils.toObject(this.lx, this.windowSettingsMain));
windows.add(Window.ALT.key,  LXSerializable.Utils.toObject(this.lx, this.windowSettingsAlt));
```

Save is driven from the two GLFW callbacks the `AltWindow` registers, each
writing to the `ALT` prefs slot on every move/resize:

```java
// GLX: heronarts/glx/WindowEngine.java:766-769  (move)
glfwSetWindowPosCallback(this.handle, (window, x, y) -> {
  setPosition(x, y);
  preferences.setWindowPosition(this.key, x, y);   // this.key == ALT
});
```
(resize path: `WindowEngine.java:747-764` → `preferences.setWindowSettings(...)`.)

On relaunch, `Window.locate()` reads the saved settings back, then tries to
match the saved top-left to a currently-connected monitor; if no monitor
contains it, it falls back to centering on the first monitor:

```java
// GLX: heronarts/glx/WindowEngine.java:560-620  (abridged)
WindowSettings settings = preferences.getWindowSettings(this.key);
if (settings.hasPosition()) setPosition(settings.getX(), settings.getY());
if (settings.hasSize())     setSize(settings.getWidth(), settings.getHeight());
...
if (hasPosition()) {
  for (Monitor m : monitorConfig.monitors)          // m coords are virtual-screen
    if (m.contains(getX(), getY())) { this.monitor = m; break; }
  ...
} else {
  // Default: center the window on the primary monitor
  this.monitor = monitorConfig.monitors.getFirst();
  ...center on it...
}
```

Monitor bounds come from `glfwGetMonitorWorkarea` in **virtual-screen
coordinates** (`Monitor.java:41-67`), so the `contains()` match itself is
multi-monitor-aware and would work — *if the saved position ever reached it.*

## Why the second monitor fails

The position is thrown away — on both save and load — by a single guard in
`WindowSettings.setPosition`, which only considers a position "present" when
**both** X and Y are strictly positive:

```java
// LX: heronarts/lx/LXPreferences.java:158-163
public WindowSettings setPosition(int x, int y) {
  this.x = x;
  this.y = y;
  this.hasPosition = this.x > 0 && this.y > 0;   // <-- the bug
  return this;
}
```

`save()` only serializes X/Y when `hasPosition()` is true:

```java
// LX: heronarts/lx/LXPreferences.java:312-322
public void save(LX lx, JsonObject object) {
  if (hasPosition()) { object.addProperty(KEY_X, this.x); object.addProperty(KEY_Y, this.y); }
  if (hasSize())     { ... }
}
```

and `load()` re-runs the same `setPosition` gate (`LXPreferences.java:302-309`),
and `locate()` only re-applies a saved position when `settings.hasPosition()`
(`WindowEngine.java:566`). So a top-left with `x <= 0` **or** `y <= 0` is:

1. never written to `.lxpreferences` when you move the window there, and
2. even if some older file has it, discarded on load, and
3. treated as "no saved position" by `locate()`, which then hits the `else`
   branch and **centers on `monitorConfig.monitors.getFirst()`** — the
   first-enumerated (primary) monitor (`WindowEngine.java:608-619`).

That maps exactly onto "second monitor never gets the position." Common
multi-monitor arrangements put a secondary display at a non-positive origin:

- Monitor to the **left** of primary → negative X.
- Monitor **above** primary → negative Y.
- Monitor top-aligned to the right (very common) → its work-area top is `y = 0`
  (no menu bar on the secondary), and dragging the window flush to the top
  yields `y = 0`. `0 > 0` is false, so the whole position is dropped — even
  though X is a healthy `1920`+.

Only a window whose top-left lands strictly inside the positive quadrant
(essentially: on the primary display, below the menu bar) round-trips. That is
why the main window usually "just works" and the composition window on a second
monitor never does.

Two secondary factors make recovery worse, though they are not the primary
cause:

- The "if the exact position no longer lands on a monitor, match a monitor of
  the same dimensions" fallback is **stubbed out** — no monitor identity is
  saved to preferences, so `hasSavedMonitor` is always false:
  ```java
  // GLX: heronarts/glx/WindowEngine.java:497-505, 588-598
  // JKB note: Not yet implemented. Would need to save/reload monitor in preferences.
  private boolean hasSavedMonitor = false;
  ```
- `MonitorConfiguration` has no real `equals()` (`MonitorConfiguration.java:16
  // TODO: add equals()`), so `refreshMonitors()` can't cheaply detect layout
  changes — minor here since monitors are only enumerated once at startup.

I did not find any second, project-scoped (`.lxp`) persistence of the
composition window's OS geometry; `.lxp` stores UI *panel* state
(`previewAux`, `LXStudio.java:1155-1200`) but not the GLFW window x/y. So the
finding on Jeff's hypothesis is: **confirmed — it's a `.lxpreferences` prop, not
a `.lxp` prop**, and it's being silently dropped for non-positive coordinates.

## Suggested minimal fix

The root fix is in LX, one line, and it also improves the main window:

1. **Stop treating negative/zero coordinates as "no position."** A valid
   virtual-screen position can legitimately be negative or zero. Replace the
   product-of-signs test with a real "was a position ever set" flag:
   ```java
   // LXPreferences.WindowSettings.setPosition  (:158-163)
   this.hasPosition = true;   // any set position is a position
   ```
   Then `save()` / `load()` / `locate()` round-trip second-monitor placements
   unchanged, and `WindowEngine.locate()`'s existing `Monitor.contains(...)`
   loop (which already works in virtual-screen coordinates,
   `WindowEngine.java:580-586`) will re-match the correct monitor. The
   `constrain(...)`/`exceeds(...)` clamp (`WindowEngine.java:601-606`) already
   protects against a monitor that has since shrunk or disappeared.

   Note the same `x > 0 && y > 0` shape is used for *size* at
   `LXPreferences.java:168`; leave that one — width/height genuinely should be
   positive. Only the *position* test is wrong.

2. **(Optional, robustness)** Persist a monitor fingerprint (index or
   work-area size) alongside the window so the "match a similar monitor"
   fallback at `WindowEngine.java:588-598` can actually run when the exact
   coordinates no longer land on any connected display. This is the
   already-envisioned-but-unimplemented path (`// JKB note: Not yet
   implemented`); it's a nice-to-have on top of fix #1, not required to close
   the reported bug.

This is host-side (LX/GLX) — outside the package's editable scope — so it's a
feedback item for mcslee/jkbelcher, not a package change. No `.lxp`
compatibility impact: the fix touches only `.lxpreferences` handling.

Key files: `heronarts/lx/LXPreferences.java` (`WindowSettings.setPosition`
:158-163, save/load :292-322, prefs file :43/:340), `heronarts/glx/WindowEngine.java`
(`AltWindow` "Composition" :917-923, `locate()` :560-620, pos/size callbacks
:747-769, stubbed saved-monitor fallback :497-505/:588-598), `heronarts/glx/GLX.java`
(:174 alt-window ↔ `showCompositionWindow`), `heronarts/glx/Monitor.java`
(:41-67 work-area virtual coords), `heronarts/glx/MonitorConfiguration.java`
(:16 missing `equals()`).
