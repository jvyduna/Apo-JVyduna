# Arrange Tools — composition editor helpers (design doc)

An `LXStudio.Plugin` in this package that adds arrange-timeline editing
features the `arrange` host lacks, **without modifying the LXStudio/LX/GLX
repos**:

1. **Rectangular multi-lane selection + range copy/paste.** The host's
   selection is a single-lane time range (one lane at a time, enforced).
   The plugin extends it into a rectangle — a contiguous block of lanes ×
   the time range — created by dragging across lanes or with `Cmd+Shift+J/K`,
   drawn by a click-through overlay, and copy/pasteable as a block back
   into the same lanes at a new position.
2. **Cross-lane range shift.** Shift every event inside the selected time
   range left/right across all lanes (or just the rectangle's lanes), with
   locators traveling along — one undoable operation.
3. **Keyboard shortcut reference.** A keyboard icon in the arrange window's
   status bar (or `Cmd+Shift+/`) overlays the shortcut tables parsed live
   from the bundled `Composition-Guide.md`.

User-facing docs live in `docs/Composition-Guide.md` ("Arrange Tools"
section — also the source the in-app panel renders). Upstream host bugs
found along the way are written up in `docs/arrange-feedback/`.

## Hard contract (see CLAUDE.md "Branch conventions")

- Package-side only; host repos are read-only reference source.
- Reflection into host UI internals is **read-only and narrow** (the fields
  listed below), every access try/catch-guarded: on any failure the feature
  logs one `LX.error` and permanently no-ops for the session. Never write
  host UI state reflectively. (One sanctioned exception, below: the
  status-bar icon adds a child via public `addToContainer` after two
  reflective reads — explicitly requested by Jeff 2026-07-08.)
- Editor-only: all work happens inside shortcut handlers and per-frame
  read-only polls; output is ordinary undoable `LXCommand.Clip.Event.*` /
  `LXCommand.Composition.*` mutations, so saved `.lxp` data is
  indistinguishable from hand-edited. Never affects playback/parse.
- Every mutating operation is a single undo entry (Cmd+Z restores exactly).
- Edited `.lxp`s must be verified to load cleanly on stock Chromatik 1.2.2.

## Verified host facts this design relies on (arrange HEAD, 2026-07-08)

- The focus walk is FULLY PUBLIC: `GLX.windowEngine.getFocusedWindow()`
  (WindowEngine.java:271, kept current by the GLFW focus callback at :723) →
  `UI.getRoot(UI.Window)` (UI.java:1343) → `UIObject.getFocusedChild()` chain
  (UIObject.java:350). Only the OS-focused window's root may be consulted:
  each window's focus chain and lane selection persist while the other
  window is used (nothing cross-blurs roots; `UIClipLane.onBlur` clears
  selection only on its own blur), so reading the unfocused root lets a
  stale selection hijack a shortcut. (`UI#focusedWindow` is vestigial —
  never written — do not use it.) Lane→model hops are protected fields:
  `UIClipEnvelope#lane` (LXStudio UIClipEnvelope.java:56), `UIGridLane#clip`
  (UIGridLane.java:42).
- Selection fields: `UIClipLane#selectionStart/#selectionEnd/#hasSelection`
  (UIClipLane.java:45-48). Selection cleared on blur (`onBlur`), single-lane
  enforced by `UICompositionEditor.clearSelections` (UICompositionEditor.java:257).
- Timeline geometry is FULLY PUBLIC: `UITimeline.lens` (public final) with
  `toPixelsFromCursor`/inverses (Lens.java:220+); lane rows enumerated from
  `timeline.body.content` (public fields; child order == composition lane
  order); `UI2dComponent.getAbsoluteX()/getAbsoluteY()` fold in BOTH scroll
  axes automatically (UI2dComponent.java:304-335). Host selection tint:
  `ui.theme.selectionColor.maskf(.66f)` (UIClipLane.drawSelection:215) —
  but that draws UNDER lane events; anything drawn on top must use far less.
- Mouse: GLX routes each event to exactly ONE component (first `contains()`
  hit; drags stay captured by the pressed component) — there is NO
  observe-only hook. `contains()` is overridable: returning false makes a
  layer click-through. Cursor/button state is pollable via GLFW statics on
  the public window handle; window→UI scale = `getUIWidth()/windowWidth`
  (WindowEngine's own cursorScale formula, WindowEngine.java:715-717).
- Model side is fully public: `lx.engine.timeline.getComposition()/getFocusedClip()`
  (LXTimelineEngine.java:73/123), `LXClip.lanes` (LXClip.java:157),
  `LXClip.insertMarker` (Cursor.Parameter, LXClip.java:128), `length`,
  `setInsertMarker`, `CursorOp()`, `constructTempoCursor(int,double)` /
  `(Tempo.Division)` (LXClip.java:1657/1671), `LXComposition.locators`
  (LXComposition.java:56).
- Commands to reuse: `LXCommand.Clip.Event.SetCursors<T>` (LXCommand.java:4157;
  ctor + `.update(toMin,toMax,Operation)`; undo = lane JSON preState reload),
  `RemoveRange` (4120), `Parameter.InsertEvent(ParameterClipLane,Cursor,double)`
  (4761), `Pattern.InsertEvent` (4719), `Composition.MoveLocator` (4961).
  `LXCommand` is subclassable by a plugin; `lx.command.perform(LXCommand)` is
  public. **No composite/batch command exists in LX — we define our own.**
- In-range semantics to mirror: point events use cursor ∈ [selMin, selMax]
  inclusive (`UIParameterClipEnvelope.initializeEventsFrom`, lines 466-497,
  LinkedHashMap ordering required); duration events (`LXCompositionEvent`)
  use `event.end` overlap (`UICompositionLane.onSelectionChanged`, 219-247)
  and pass `fromValues = null` to SetCursors.
- Keyboard: registered shortcuts (`ui.registerKeyboardShortcut`, public,
  LXStudio.java:261-291) fire from the top-level handler only AFTER the focus
  chain declines the event. **ALL arrow keys are unusable while a lane is
  focused** (verified in the field): `UIClipLane.onKeyPressed` consumes
  LEFT/RIGHT unconditionally, and `UI2dContainer.onKeyPressed` with
  `arrowKeyFocus == VERTICAL` (the timeline's lane stack) consumes UP/DOWN
  with NO modifier check anywhere in the ancestor chain. Hence J/K.
  Default-table collisions checked: `VK_J` unbound; `VK_K` only at plain Cmd
  (computer-MIDI toggle); brackets free; default VK_C binding is Shift-only;
  no `UICopy`/`UIPaste` exists in the lane focus chain.
- Dialog/overlay: `ui.showContextDialogMessage(String)` (GLX UI.java:1197)
  and `showContextOverlay(Window, ...)` (UI.java:1206) public; context
  overlays get Escape + click-outside dismissal for free (UI.java:178,202).
- Threading: host builds command maps on the UI thread against the engine
  `lane.events` list and performs from UI-thread handlers — we do the same.
  (`getUIThreadEvents()` is display-only; SetCursors maps need engine-list
  identity.) GLFW polls run on the render thread (GLFW main thread under
  `-XstartOnFirstThread`).
- **Transparent overlay layers must self-clear**: VGraphics' shared bgfx
  view clears DEPTH|STENCIL only between context renders (VGraphics.java:392).
  Host contexts paint opaque backgrounds so they never notice; a transparent
  `UI2dContext` ACCUMULATES frames (translucent fills go opaque, stale
  pixels persist). Fix: write a transparent full-context rect in `NVG_COPY`
  composite mode at the top of every draw (via public `vg.getHandle()` +
  lwjgl NanoVG statics). Feedback candidate for Mark: opt-in color clear
  for UI2dContext framebuffers.

## Class layout (`apotheneum.jvyduna.arrange`)

| Class | Role |
|---|---|
| `ArrangeToolsPlugin` | `@LXPlugin.Name("Arrange Tools")`, `implements LXStudio.Plugin`. `onUIReady` only: `try { new ArrangeToolsController(lx, ui).register(); } catch (Throwable t) { LX.error(...); }`. No arrange-only types in its own signatures/fields so it classloads (inert) on stock hosts — `LinkageError` is caught. |
| `ArrangeToolsController` | Registers the shortcut table, creates the overlays + drag watcher + status-bar icon; every action body try/catch(Throwable) → `LX.error`. Owns the rect state (`LaneRect`) and its lifecycle. |
| `SelectionReader` | Read-only reflection bridge (the plugin's ONLY reflection besides the status-bar icon reads). Guarded `Field` handles: `UIClipLane#selectionStart/#selectionEnd/#hasSelection`, `UIClipEnvelope#lane`, `UIGridLane#clip`. Focus walk is public (see facts). Returns `FocusedLane` / `LaneSelection` (cloned, normalized cursors + source `Window`). Any failure → dead for the session, features no-op. |
| `LaneRect` | Plugin-owned rectangular selection: host anchor selection + contiguous lane extents (up/down counts). `extend()` for the J/K path (opposite key shrinks first), `setExtents()` for the drag path (clamped). Valid only while the anchor lane stays the focused, selected lane — checked lazily; any click that clears/moves the anchor selection dismisses it. |
| `DragRectWatcher` | Cross-lane mouse drag creates the rect, WITHOUT intercepting host events: per-frame poll of GLFW cursor + left-button on the focused window's public handle. Rubber-band fingerprint: `hasSelection` false→true while the button stays down (host clears at press, re-establishes past the drag threshold) — event/handle drags never match. While live, the lane row under the pointer drives the extents (clamped past the stack edges); release freezes. A very fast flick can miss the one-frame window (J/K fallback); composition-lane event click-selects can false-positive (visible, click-dismissed). |
| `RangeHighlightOverlay` | Full-window click-through `UI2dContext` layer, one per window (`ui.addLayer`/`addLayerAlt`), `contains() -> false`. Self-clears its framebuffer every draw (NVG_COPY — see facts). Draws near-transparent member-lane tints (`selectionColor.maskf(0.2f)`; the anchor keeps the host highlight) plus a 1.5px `selectionColor` border around the full rect. Geometry recomputed per frame (public lens + absolute coords), manually scissored to the timeline body viewport — tracks pan/zoom/lane-resize live. |
| `VirtualRangeTracker` | After a shift the host highlight doesn't move (we never write host UI state); remembers (lane, stale ui range → actual shifted range) so repeated nudges chain. Records only when the composite actually landed on the undo stack; invalidated by foreign undo/redo or selection changes. |
| `RangeShift` | Feature 2 builder: per-lane `SetCursors` + locator `MoveLocator` children (below). |
| `RangeClipboard` / `RangePaste` | Feature 1 clipboard + paste builder (below). Never touches `lx.clipboard`. |
| `CompositeCommand` | `extends LXCommand`; performs children in order; rolls back the performed prefix (reverse) on `InvalidCommandException` OR `RuntimeException` before rethrowing (`LXCommandEngine.perform` clears both stacks on any exception — atomicity matters); `undo` runs children in reverse. |
| `ShortcutsPanel` | Feature 3 panel (below). |
| Stretch (phase 2) | `JsonRangePaste` (all-lane-type paste via lane save/load JSON merge). |

## Feature 1 — Rectangular multi-lane selection + range copy/paste

The host enforces one-lane-at-a-time selection, so the rectangle lives
entirely plugin-side: the host's drag-selection in ONE lane is the anchor
(time range + anchor lane), and the rect adds contiguous lane extents.

**Creating the rectangle** (both paths share `LaneRect`):
- **Mouse**: rubber-band as usual, keep dragging vertically into other
  lanes — `DragRectWatcher` follows the pointer each frame.
- **Keyboard**: `Cmd+Shift+J` / `Cmd+Shift+K` grow down/up one lane
  (vim j/k; the opposite key shrinks first; collapse discards the rect).

**Display**: `RangeHighlightOverlay` — border + near-transparent tints,
live-tracking pan/zoom. Any click dismisses (the click clears or moves the
anchor selection the rect is built on).

**Copy (`Cmd+Shift+C`)** — non-mutating; rectangle's member lanes if one is
active, else the focused lane. Per lane store
`ComponentReference<LXClipLane<?>>` + per event a relative cursor
(`event.cursor.subtract(selMin)`) and payload — v1: `ParameterClipLane` →
normalized value; `PatternClipLane` → pattern index. MIDI/audio/bus/text
lanes skipped with a counted log (phase 2: `JsonRangePaste`). Store
`rangeLength` + source clip id. The tracker's virtual range applies, so a
copy after nudges captures what the user sees.

**Paste (`Cmd+Shift+V`)** — base = `clip.insertMarker.cursor` (user places
it by clicking a lane). Validate refs still resolve and share one clip.
Composite children per lane: `RemoveRange(lane, base, base.add(rangeLength))`
(paste-replace, `isIgnored()` if empty) then one `Parameter.InsertEvent` /
`Pattern.InsertEvent` per event; events past `clip.length` skipped +
counted. Single `CompositeCommand` → one undo; reverse-order undo keeps
InsertEvent indices valid, RemoveRange preState reload last → exact
restoration. **Multi-lane pastes always return to their source lanes** —
by design there is NO lane-offset/vertical retargeting and no type
coercion (Jeff explicitly scoped this out; the rectangle back to its own
lanes at a new time is the use case).

**Cross-lane paste** (single-source-lane copies only): if the clipboard
holds exactly ONE lane and the focused lane differs, paste retargets to the
focused lane — strict same-event-semantics gate:
Normalized→Normalized, Boolean→Boolean, Trigger→Trigger; Discrete→Discrete
only with an identical value range. Never for enum/discrete mismatches,
pattern lanes (indices are clip-local), MIDI/audio/bus/text, or multi-lane
clipboards. Rejection → modal `UIDialogBox` naming both types and the
reason; no partial paste ever occurs.

(The former `Cmd+Alt+Shift+C` all-lanes copy was REMOVED 2026-07-08 —
the rectangle covers it.)

## Feature 2 — Cross-lane range shift

Shortcuts: `Cmd+Shift+]` / `Cmd+Shift+[` grid-step right/left;
`Cmd+Alt+]` / `Cmd+Alt+[` fine (¼ beat).

1. `sel = SelectionReader.read(ui)` (VirtualRangeTracker substitution
   applied); null → status log, no-op.
2. Delta: walk `getContainer()` (public) up from the lane to `UITimeline`,
   use public `gridMetrics.getGridSpacing()`; fallback
   `clip.constructTempoCursor(1, 0)`. Fine = `constructTempoCursor(0, 0.25)`.
3. Clamp like host `UIClipLane.moveSelection` (right: bound by
   `clip.length.cursor.subtract(selMax)`; left: bound by `selMin`).
   Zero → no-op.
4. Target lanes: the rectangle's member lanes if one is active, else ALL
   lanes of the clip. Per lane: collect in-range events into LinkedHashMap
   from/to cursor maps (host-parity inclusion rules; `to` bounded into
   `[0, clip.length]`). Skip empty lanes. Build
   `SetCursors<>(lane, selMin, selMax, fromValues, from, to)` +
   `.update(shiftedMin, shiftedMax, MOVE_RIGHT/MOVE_LEFT)` (fromValues:
   empty map for parameter lanes, null for duration lanes — host parity).
   **MIDI note lanes are EXCLUDED** (skipped with a counted log): note
   on/off are separate point events with a partner link; the host only
   edits them via pair-aware `LXCommand.Clip.Event.Midi.*` commands, and
   the generic SetCursors path provably splits/orphans pairs. Supporting
   MIDI shift means a pair-aware path, not a flag flip.
5. **Locators travel with all-lanes shifts**: when `sel.clip instanceof
   LXComposition` and no rectangle narrows the scope, append one
   `LXCommand.Composition.MoveLocator` per locator with
   `position.cursor ∈ [min, max]`. Rectangle (subset) shifts leave
   locators alone — a local edit shouldn't move global markers.
6. `lx.command.perform(new CompositeCommand("Shift Range", children))` —
   one undo entry.
7. Record the shifted range in `VirtualRangeTracker` ONLY if the composite
   landed on the undo stack (`lx.command.getUndoCommand() == command` —
   the engine swallows failures internally); else clear the tracker.

Notes: moved ranges clobber events they land on — identical to the host's
single-lane drag-move, fully undoable.

## Feature 3 — Keyboard shortcut reference

- **Icon**: `UIButton.Action` with `ui.theme.iconKeyboard`, appended to the
  arrange window's status bar. This is the one sanctioned step past
  read-only reflection: two reflective READS (`LXStudio.UI#altContext`
  private, `AltContext#statusBar` private) then PUBLIC `addToContainer`.
  `UIStatusBarAlt` is a LEFT_TO_RIGHT fill container, so the appended child
  is right-aligned and reflows on resize automatically (the MAIN window's
  `UIStatusBar` is `Layout.NONE` with a private sections array — late
  children don't reflow there, which is why the icon is alt-window only).
  Guarded; failure degrades to the shortcut-only path.
- **Panel** (`ShortcutsPanel`): parses every markdown table from the
  bundled `/arrange/Composition-Guide.md` jar resource (pom bundles
  `docs/Composition-Guide.md` — the guide is the single source of truth),
  grouped under the nearest heading; the row preceding each `|---|`
  separator (the column-header row) is dropped. Rendering: left column
  (shortcut/action name) `#FFF`, right-side explanation `#CCC`, headers
  `#CCC` — no theme periwinkle. Shown via
  `showContextOverlay(Window.ALT, ...)`: Escape and click-outside dismissal
  are free; clicking the panel or the icon also dismisses. Toggle also on
  `Cmd+Shift+/`.

## Shortcut table

| Shortcut | Action |
|---|---|
| drag across lanes | Create/extend the selection rectangle with the mouse |
| Cmd+Shift+J / Cmd+Shift+K | Grow the rectangle down / up one lane |
| Cmd+Shift+C | Copy range (rectangle if active, else focused lane) |
| Cmd+Shift+V | Paste at insert marker (cross-lane if a compatible lane is focused) |
| Cmd+Shift+`]` / `[` | Shift range right/left, grid step (rectangle lanes, else all lanes) |
| Cmd+Alt+`]` / `[` | Shift range right/left, fine (¼ beat) |
| Cmd+Shift+/ | Toggle the shortcut reference panel |

Avoid: ALL arrow keys (consumed regardless of modifiers — see facts),
Cmd+A (lane select-all), Cmd+Z, and defaults on A/C/F/G/M/P/K/S/T, 0/-/=,
comma/period, F5. Re-verify the default table (LXStudio.java:765-867)
against arrange HEAD before changing keys.

## Verification checklist

1. `mvn -Pinstall install` in this worktree → single jvyduna jar in
   `~/Chromatik/Packages`; launch via IntelliJ "Chromatik (arrange)"; enable
   the plugin; no `[ArrangeTools]` warnings at startup.
2. Test `.lxp` (committed): composition with ≥2 parameter lanes, a pattern
   lane, a bus lane, locators.
   - Rectangle: drag across lanes → border + tints follow the pointer,
     track pan/zoom; release freezes; any click dismisses (no stale
     pixels); J/K grow/shrink; collapse discards.
   - Copy/paste: rectangle copy → move insert marker → paste lands in the
     same lanes; replace semantics; overflow skip; single undo/redo.
     Cross-lane: Normalized→Normalized retargets to the focused lane;
     Boolean→Normalized → modal dialog, no mutation.
   - Shift: no selection/focus → no-op with log; all-lanes shift moves
     in-range events AND locators, one Cmd+Z restores everything; nudges
     chain; clamps at 0 and clip length; rectangle shift moves only member
     lanes and leaves locators.
   - Panel: icon toggles; Cmd+Shift+/ toggles; Escape / click-away /
     click-panel / click-icon dismiss; no column-header rows rendered.
   - Cross-window: repeat rectangle + shift in the composition editor
     (alt window) and the clip view; shortcuts in one window never act on
     a stale selection in the other.
3. `.lxp` diff: only expected lanes' event arrays (and locator positions)
   change; undo-then-save → empty diff.
4. Stability: edited `.lxp` loads and plays identically on stock 1.2.2.

## Risks / degradation

- Host field rename → SelectionReader init/read fails → one `LX.error`,
  features no-op for the session. Never writes host UI state.
- Stock host → controller classload throws `LinkageError` → caught, plugin
  inert. Status-bar icon failure → logged, `Cmd+Shift+/` still works.
- Drag watcher failure (GLFW/geometry) → logged once, watcher dead, J/K
  keyboard path unaffected.
- Partial composite failure → performed prefix rolled back before rethrow
  (both checked and runtime exceptions).
- Highlight doesn't follow a shift (can't write host selection) →
  VirtualRangeTracker makes repeated nudges correct; documented quirk.
- Focus loss clears host selection (`onBlur`) → rect + shortcuts need the
  lane focused; inherent host behavior.

## Known upstream (arrange host) bugs found during this work

Full write-ups for Mark in `docs/arrange-feedback/`:

- **Unsorted lane events** (2026-07-08-textnote-unsorted-events.md):
  `TextNoteClipLane.addEvent` appends instead of sorted-inserting; the
  notes renderer binary-searches + early-breaks assuming order → wrong/
  phantom note lengths at high zoom. Same class of bug as the
  `setEventsCursors` MOVE reorder branch leaving overlap duration events
  cursor-rewritten but not repositioned (unsorted lane) when a move exceeds
  the selection width — host-parity, we inherit it, do not work around.
- **Locator label rename unreachable** (2026-07-08-locator-label-rename.md):
  label clicks launch playback and their consume() resets InputDispatch's
  repeat counter, so the textbox's double-click-to-edit can never fire;
  `disableImmediateEdit` blocks click-then-type. `Cmd+R` is the only path.
- Feedback candidates, not yet written up: `UI2dContainer` arrow-focus
  consuming arrows with modifiers held; no color-clear option for
  transparent `UI2dContext` framebuffers; host-native multi-lane selection
  (Mark's own `// TODO: if command or shift is held, expand the current
  selection` in UICompositionLane.java:329).
