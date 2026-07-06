# Composition

The **Composition** window is Chromatik's arrangement timeline. Where the clip grid on the mixer lets you launch material live, the Composition lays your whole project out on a single left-to-right timeline — pattern changes, parameter automation, MIDI, audio files, and annotations for every channel — so a show can be arranged, edited, and played back from start to finish.

Everything in the Composition is saved inside your project (`.lxp`) file, and every edit described below is undoable with `Cmd+Z`.

> **Modifier keys**: This guide writes `Cmd` throughout — use `Ctrl` on Windows/Linux. `Alt` is `Option` on macOS.

---

## Opening the Composition window

The Composition lives in its own OS window with its own toolbar and transport (Play / Stop / Record).

- In the **bottom status bar** of the main window, at the far right with the other layout controls, click the **stacked-bars button** (three horizontal bars). This toggles the Composition window open and closed.
- The button lights up in the accent color while the window is open.

The Composition window has three fixed zones:

- **Toolbar** (top): tempo, transport, grid + snap controls, and the time base selector.
- **Timeline** (middle): ruler, loop brace, locator lane, and the lane stack. Note that the **lane headers (sidebar) are on the *right* side** of the window, not the left.
- **Status bar** (bottom): hover over almost any control or region and contextual help text appears here.

---

## Finding your way around

### Zooming and scrolling

There are three ways to navigate the timeline; none of them are labeled, so they're worth memorizing:

| Gesture | Where | Result |
|---|---|---|
| Click + drag **vertically** | Either ruler (top or bottom) | Zoom in (drag down) / out (drag up), centered on the mouse |
| Click + drag **horizontally** | Either ruler | Scroll the timeline sideways |
| Drag vertically / horizontally | **Overview strip** (the thin bar above the timeline) | Zoom / scroll, same as the ruler |
| **Double-click** | Overview strip | Zoom all the way out |
| Trackpad / wheel scroll | Lane area | Scroll horizontally (lane content) and vertically (lane stack) |

The overview strip always shows the full composition, with a rectangle outlining the portion currently visible and small blocks marking where audio and clip events live — useful for jumping around a long arrangement.

### The grid and snapping

The grid controls are in the **toolbar**, between the transport and the time base button:

- **Grid icon button** — toggles snapping on/off (`Cmd+4`). Grid lines dim when snap is off.
- **Spacing dropdown** — grid density. In *Adaptive* mode the grid follows your zoom level; in *Fixed* mode you choose an explicit spacing.
- **Mode button** (far right of the group) — switches Adaptive / Fixed (`Cmd+5`).
- `Cmd+1` / `Cmd+2` — decrease / increase grid density from the keyboard.

**Hold `Cmd` while dragging anything to temporarily invert snapping** — snap off if it's on, on if it's off. This works for events, locators, loop markers, everything.

### Time base

The **time base button** at the right end of the toolbar switches the composition between:

- **Absolute** — positions are wall-clock time (minutes:seconds). The right choice when syncing to a fixed audio file.
- **Tempo** — positions are bars/beats tied to the project BPM. Grid, snapping, and launch quantization follow the beat, and changing the BPM re-times the arrangement.

---

## Playback

The toolbar transport has **Play**, **Stop**, and **Record** buttons. Playback always starts from the **insert marker** — the small triangle in the locator lane with a vertical line running down through the lanes.

Ways to set and use the insert marker:

- **Click in any lane** — moves the insert marker there (snapped to grid).
- **Click in the empty part of the locator lane** — moves the insert marker *and immediately launches playback* from that point.
- **`←` / `→`** — step the insert marker one grid division at a time.
- **`Space`** — play / pause from the insert marker (when the timeline has focus).
- **`Shift+Space`** — resume playing from wherever the playback cursor currently sits, rather than jumping back to the insert marker.

A dashed vertical line means a launch is **pending** — in Tempo time base, launches are quantized to the launch quantization setting, so playback waits for the next boundary. The solid moving line is the playback cursor (it turns red while recording).

Starting composition playback automatically switches the audio engine into **Timeline** mode so that any audio lanes play out of your speakers, and audio-reactive patterns react to that audio.

### The loop region

The **loop brace** is the thin strip directly above the locator lane, with a flag at each end:

- Drag the **start** or **end flag** to set the loop boundaries (snapped; `Cmd` inverts snap).
- Drag the **bar between them** to slide the whole loop region.
- With a marker selected, `←` / `→` nudge it by one grid division (`Cmd+←/→` targets the end marker when the brace is selected).
- **Right-click the brace** for *Enable Loop / Disable Loop*, or press `Cmd+L`.

---

## Locators

Locators are named position markers — use them to label sections ("drop", "verse 2", "finale") and jump between them. They live in the **locator lane**, the row just under the loop brace. There is no "add locator" button.

**To add a locator:** move the insert marker where you want it, then click the **`Set` button to the right of the ruler** (in the right sidebar header area, above to the ◀ ▶ arrows). A locator flag appears at the insert marker position, snapped to the grid.

Once a locator exists:

| Action | How |
|---|---|
| **Rename** | Click its text label and type (or focus the flag and press `Cmd+R`) |
| **Move** | Drag the flag left/right (snaps; `Cmd` inverts snap), or nudge with `←` / `→` |
| **Jump to it** | Click the flag — while stopped, this moves the insert marker; while playing (or with `Cmd` held), it relaunches playback from the locator |
| **Delete** | Focus the flag and press `Delete` |
| **Step between locators** | The **◀ ▶ buttons** in the top-right corner jump to the previous / next locator |

The prev/next locator actions are trigger parameters on the composition, so they can be **MIDI- or OSC-mapped** for show control, as can each locator's launch trigger.

---

## Lanes

The lane stack mirrors your mixer: every channel, group, and the master bus automatically gets a **bus lane** (the dark full-width header row), with its pattern, MIDI, and automation lanes nested under it. Reordering channels in the mixer reorders the lanes. On top of these you can add three kinds of lanes yourself:

### Adding lanes

- **Automation lane** — click the **`+ Add Lane` button in the bottom-right corner** of the window. A parameter picker opens listing every eligible parameter in the project (device knobs, channel faders, effect and modulator parameters, the master crossfader…). Pick one to create an editable envelope lane for it. Automation lanes are also created automatically when you record parameter movements.
- **Notes lane** — **right-click anywhere in the timeline** and choose **Add Notes Lane**. A notes lane holds free-text blocks; handy for cues and section descriptions.
- **Audio lane** — **drag an audio file from your file manager and drop it anywhere on the Chromatik window**. There is no menu or button for this. Supported formats: `.wav`, `.aiff`, `.aif`, `.au` (MP3 is not supported — convert to WAV first). Dropping a file into an empty composition also sets the composition length to the audio length. Note the project stores the file's *path*, not the audio itself, so moving the file later breaks the link.

### The lane sidebar (right edge)

Each lane's header cell in the right-hand sidebar has:

- **Expander triangle** — collapse / expand the lane. Modifiers do more:
  - `Cmd+click` the triangle (or `Cmd+Enter` / `Shift+Enter` with the lane focused): **maximize** the lane to fill the window.
  - `Alt+click` the triangle: expand or collapse **all** lanes at once (all bus lanes if used on a bus lane, all of one bus's sub-lanes otherwise).
  - `Enter` toggles expanded; pressing it on a maximized lane restores it.
- **Resize** — drag the **bottom edge** of a header cell up/down to set a custom lane height.
- **Reorder** — drag a header cell vertically to move the lane (bus lanes stay put; they follow the mixer order).
- **Rename** — audio and notes lanes have editable names: click the label, or press `Cmd+R` with the lane focused.
- **Delete** — with an audio, notes, or automation lane's header focused, press `Delete` to remove the whole lane.
- Bus lane headers include the channel's **Active / Cue / Arm** buttons (the master bus header has Arm), so you can arm channels for recording without switching to the mixer window.
- Audio lane headers have a **speaker toggle** (mute/unmute the lane) and a **gain box** (dB).

---

## Working with events

Events — audio regions, text notes, and recorded clip segments — share one editing model. The cursor shape tells you what a click will do:

- Near an event's **left or right edge** (on the bar strip at the top of taller lanes): a **brace cursor** — drag to **trim** the start or end.
- Over the **middle of an event bar**: a **hand cursor** — drag to **move** it. Clicking an unselected event selects it and starts the drag in one motion.
- Elsewhere: **drag across the lane** to rubber-band select a range. Every event touching the range is selected; drag any selected event's bar to **move them all together** (trim handles are disabled for multi-selections).

While editing:

- All moves and trims **snap to the grid**; hold `Cmd` to invert snapping.
- **`Esc`** during a drag cancels it and restores the original positions; `Esc` otherwise clears the selection.
- **`Delete`** removes the selected event(s), or everything inside a selected range.
- **`Cmd+A`** selects the entire lane.
- Selecting an event in only one lane at a time is enforced — clicking in a new lane clears the old selection.

### Per-lane specifics

**Pattern lanes** show which pattern is active over time, one row per pattern in the channel:

- **Double-click** in empty space to insert a pattern change at that time, on the row you clicked.
- Drag an event to move it in time *and* change which pattern it triggers (vertical drag).
- With an event selected: `↑` / `↓` switch pattern, `←` / `→` move to the previous/next grid line, `Delete` removes it.

**Parameter automation lanes** are breakpoint envelopes:

- **Double-click** to add a breakpoint — the vertical position of your click sets the value.
- Drag breakpoints to move them; select a range to move or scale a whole region using the handles that appear around the selection.
- Hover a breakpoint and press `Delete` to remove it.

**Notes lanes**:

- **Double-click empty space** to create a note and start typing; **double-click an existing note** to edit its text. Click away (or finish editing) to commit.
- Notes move and trim like any other event.

**Audio events**:

- Trim with the edge handles — trimming the start offsets playback into the file, keeping everything downstream in sync.
- **Double-click the start handle** to reset the trim back to the beginning of the audio file.
- Audio events are allowed to extend past the end of the composition; other event types are constrained to it.
- The waveform is drawn in the lane body (expand the lane to see it better), and the file name label sticks to the visible edge as you scroll.

---

## Recording

The Composition records the way clips do: **arm, then perform**.

1. **Arm** the channels you want to capture (the Arm buttons in the bus lane headers or on the mixer).
2. Click the **Record button** in the Composition toolbar (this is the timeline's arm switch — the playback cursor and record button turn red).
3. Press **Play**. Everything you do live — switching patterns, playing MIDI, moving armed parameters — is written onto the timeline as events, creating automation lanes as needed.
4. Stop, then clean up: nudge events onto the grid, adjust envelope breakpoints, delete mistakes, and overdub another pass if needed.

If the *Start Transport With Record* preference is enabled, arming record starts playback immediately.

A practical arrangement workflow: drop your audio file in, set locators at the musical moments that matter, record a live pass against the audio, then use snap and the event editing tools to tighten the timing.

---

## Quick reference

| Shortcut | Action |
|---|---|
| `Space` | Play / pause from insert marker |
| `Shift+Space` | Play from current cursor position |
| `←` / `→` | Move insert marker (or selected event/locator/loop marker) by one grid division |
| `Cmd` (held while dragging) | Invert grid snapping |
| `Cmd+1` / `Cmd+2` | Decrease / increase grid density |
| `Cmd+4` | Toggle snap |
| `Cmd+5` | Toggle grid mode (Adaptive / Fixed) |
| `Cmd+L` | Toggle loop |
| `Cmd+A` | Select all in lane |
| `Cmd+R` | Rename focused lane / locator |
| `Enter` | Expand / collapse focused lane |
| `Cmd+Enter` or `Shift+Enter` | Maximize focused lane |
| `Delete` | Delete selected events, breakpoint, locator, or lane |
| `Esc` | Cancel drag / clear selection |
| `Cmd+Z` | Undo |
| Double-click | Add pattern event / breakpoint / note; edit note; reset audio trim; zoom out (overview) |
