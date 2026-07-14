# CompositionAutomation

An automation-only `LXEffect` that lets **global engine state be recorded into an
arrange composition** by exposing it as bus-scoped, recordable parameters.

## Why this exists

The arrange recorder captures a live parameter change only when the parameter's
owning component has an `LXBus` ancestor **and** that bus is armed
(`LXComposition.isLaneRecording` → `bus.arm.isOn()`). Global engine state such as
`lx.engine.palette` lives on no bus, so changing it live is silently dropped by
the composition recorder. (It *is* captured by a mixer **master grid clip**, but
the arrange timeline has no lane that can launch a grid clip.) Full analysis:
`docs/arrange-feedback/2026-07-08-palette-change-not-recorded-into-composition.md`.

Placing this effect on the **Master** bus gives its parameters a bus ancestor, so
they record like any other master-bus automation. Each parameter is a stand-in
that forwards to the real global state in its change listener — the same net
action a global macro modulator performs, but on a parameter the composition can
actually capture. The listener fires on both the live edit (recorded) and clip
playback (the lane drives the param → the listener re-applies the state).

## How to use

1. Add **Composition Automation** as an effect on the **Master** bus.
2. Arm the **master** bus and the composition, and record.
3. Move any control live (or automate it) — the change writes to a clip lane
   under the master row, and on playback the lane re-applies the global state.

Never touches the color buffer (pure passthrough — safe to leave enabled on
Master). Forwarding happens in listeners, not in `run()`, so record→playback
works whether or not the effect is enabled.

## Controls

### Two swatch selectors (use one per composition)

- **PIndex** (field `paletteIndex`) — `DiscreteParameter`, 1-based, range
  **1–10**. Recalls the saved swatch at that **live position**
  (`palette.swatches[PIndex-1]`). Simple, but **not stable**: reordering or
  deleting swatches changes what an index means, so old recordings drift. Kept
  for simple/static palettes.
- **PName** (field `paletteName`) — the **reorder-stable** selector (by swatch
  name). A fixed set of **32 slots**, each bound to a swatch by its stable engine
  `id`. The dropdown shows each slot's current swatch name. Recorded events
  survive palette **reorder, rename, create, and delete** (see below).

Both call `LXPalette.setSwatch(...)`, which honors the palette's own transition
settings.

### Palette transition stand-ins

- **Trans** — `BooleanParameter` → `palette.transitionEnabled`. Whether swatch
  changes crossfade.
- **TrMode** — `EnumParameter<GradientUtils.BlendMode>` (RGB / HSV / HSV-Min /
  HSV-CW / HSV-CCW) → `palette.transitionMode`. Blend mode of the crossfade.
- **TrTime** — `CompoundParameter`, 0.1–180 s → `palette.transitionTimeSecs`.
  Crossfade duration.

Defaults match the palette's own defaults (off / RGB / 5 s), so adding the effect
changes nothing until a knob moves.

**Event ordering:** `setSwatch` reads `transitionEnabled` *at call time*, while
`TrMode`/`TrTime` are consumed later while the transition runs. So when a moment
should both change transition settings **and** swap the swatch, place the
transition events a hair **before** the swatch event. (The stand-ins forward
independently — a swatch recall does not re-push the transition values.)

## The stable-slot design (why 32 fixed slots)

Clip lanes persist **only** a normalized value per event, and for discrete params
the decode is **count-relative**: `value = floor(normalized × range)`. So if the
number of options changed, every previously recorded event would resolve to a
*different* slot. Stability therefore requires a **fixed option count** — hence a
constant 32-slot range whose normalized encoding never shifts.

On top of the fixed range sits a **slot → swatch-`id`** registry (serialized on
the effect). `LXSwatch.getId()` is engine-assigned, serialized, restored on load,
monotonic-unique, and unaffected by rename or reorder — unlike `getIndex()`
(positional) or the label (mutable). This is what makes the selector survive
palette edits:

- **Reorder** — registry is keyed by id, not position → recordings unaffected;
  only labels refresh.
- **Rename** — id unchanged → recall still correct; the dropdown label updates
  (a per-swatch label listener refreshes it).
- **Create** — the new swatch takes the lowest free slot; existing recordings are
  untouched (their slots don't move).
- **Delete** — the slot becomes a **tombstone**: its id is kept (ids are never
  reused, so it can't accidentally match a future swatch) and the label shows
  `Name (del)`. Recorded events for that slot become **logged no-ops**. The slot
  is never auto-reclaimed, so other slots never renumber.

**User cleanup (by design):** deleted-swatch slots stay as no-op tombstones until
you deal with them. There is intentionally no auto-compaction — compacting would
renumber later slots and break their recordings. If you want to reclaim a
tombstone, delete/re-record the affected events yourself. Runtime reconciliation
during playback is not attempted (not needed per the design).

If more than 32 distinct swatches are ever bound, the overflow is logged and that
swatch is simply not selectable (raise `SWATCH_SLOTS` if it ever matters).

## Behavior notes

- **Fires only on change** (live edit or clip playback), not every frame.
- **Out-of-range / missing is a logged no-op**, never an error: an empty slot, a
  tombstoned slot, or a positional `Palette` index past the saved swatches.
- **Persistence:** the slot registry round-trips in the effect's own JSON
  (`swatchSlots`), restored *before* `super.load` so a recall fired during load
  sees the map. On load/construct it reconciles — any swatch not already mapped
  is appended to a free slot.
- **No re-record loop:** listeners drive `lx.engine.palette` (not registered by
  the composition), so re-applying state doesn't feed back into recording.

## Smooth-knob exemption

Not a visual pattern/effect — it renders nothing and has no motion or form to
blend or antialias — so the standard **Smooth** knob does not apply and is
intentionally omitted (per the `docs/TEMPLATE.md` static/non-visual exemption).

## Future

Named generically ("Composition Automation") on purpose: other global engine
state the arrange recorder can't otherwise reach can be added here as additional
bus-scoped stand-in parameters, each forwarding in its own listener.
