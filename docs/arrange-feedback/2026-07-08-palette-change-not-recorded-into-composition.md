# Feature gap: palette / swatch changes cannot be recorded into a composition

**Branch:** `arrange` (LX + LXStudio, 1.2.2-SNAPSHOT, as of 2026-07-08)
**Reported by:** Jeff Vyduna · **Analysis:** read-only source investigation

## Symptom

With the arrange timeline armed and a channel/master armed for recording,
changing the active palette swatch live (recalling a saved swatch, advancing
the swatch cycle, toggling a transition) produces **no recorded event** in the
composition. Every other armed live action records; this one silently does
nothing. There is no lane to hand-author one into either — a palette parameter
has no bus, so a manually-inserted lane targets no channel row (same failure
class as the global-modulator lane-visibility note in `CLAUDE.md`).

## Root cause

Palette state lives on `lx.engine.palette` (`LXPalette extends LXComponent`),
a **top-level engine component with no `LXBus` ancestor**. The composition's
recorder captures a parameter change only when (a) the owning component was
registered, and (b) the parameter's ancestor bus is armed. Palette fails both.

**(a) The composition never registers the palette.**

```java
// LX: heronarts/lx/clip/LXComposition.java:187-193
private void initializeRegister() {
  for (LXAbstractChannel channel : lx.engine.mixer.channels) {
    registerBus(channel);
  }
  registerBus(lx.engine.mixer.masterBus);
  registerParameter(lx.engine.mixer.crossfader);
  // no registerComponent(lx.engine.palette)
}
```

`registerBus` only reaches `bus.fader`, `channel.enabled`, effects, and
patterns — nothing palette-related.

**(b) Even if registered, the arm gate rejects a null-bus parameter.**

```java
// LX: heronarts/lx/clip/LXClip.java:676-683
protected LXBus getParameterLaneBus(LXParameter p) {
  // NOTE(mcslee): kind of a HORRENDOUS hack here, but not sure what else to do...
  final LX lx = p.getParent().getLX();
  if (p == lx.engine.mixer.crossfader) {
    return lx.engine.mixer.masterBus;
  }
  return p.getAncestor(LXBus.class);   // → null for any lx.engine.palette param
}
```

```java
// LX: heronarts/lx/clip/LXComposition.java:87-89
protected boolean isLaneRecording(LXBus bus) {
  return (bus != null) && bus.arm.isOn();   // null bus → false → dropped
}
```

The active swatch is selected solely by `LXPalette.autoCycleCursor`
(`DiscreteParameter`, `LXPalette.java:110`) via `LXPalette.setSwatch(...)`
(`LXPalette.java:430`), which is what `LXSwatch.recall` fires
(`LXSwatch.java:55-57`). All of these are `lx.engine.palette`-scoped — no
channel- or master-scoped "which swatch is active" parameter exists anywhere
in `heronarts/lx/mixer/`. So the recorder has nothing bus-anchored to catch.

## The one place it *does* record — and why it doesn't help the timeline

A mixer **master grid clip** captures palette changes correctly:

```java
// LX: heronarts/lx/clip/LXMasterClip.java:26-28
super(lx, lx.engine.mixer.masterBus, index, true);
registerParameter(lx.engine.mixer.crossfader);
registerComponent(lx.engine.palette);        // ← palette registered
```

…and `LXGridClip.isLaneRecording(...)` returns `true` unconditionally, so the
palette lanes both record and play back (playback runs the generic
`ParameterClipEvent.execute()` → `parameter.setNormalized(...)`, driving
`lx.engine.palette` at launch). But the arrange composition has **no lane or
event that can launch or reference a grid clip** — its lane set is BUS, AUDIO,
NOTES, PATTERN, MIDI_NOTE, PARAMETER (`LXComposition.loadClipLane`), and a grid
clip's `launch` param is never registered by `registerBus`. So the grid-clip
capture path is a dead end for timeline authoring without new host code.

## Suggested minimal fix (≈ mirrors the crossfader special-case)

Two small changes in LX make swatch changes record like any other parameter:

1. **Register the palette** in `LXComposition.initializeRegister()` (and undo
   in `initializeUnregister()`):
   ```java
   registerComponent(lx.engine.palette);
   ```
   As with `LXMasterClip`, a plain `LXComponent` only registers its own direct
   params, so this captures the swatch index (`autoCycleCursor`),
   `triggerSwatchCycle`, and the transition/auto-cycle toggles — the actual
   "change the palette" gestures — without recursing into per-swatch
   `LXDynamicColor` values.

2. **Route null-bus palette params to the master bus** in
   `LXClip.getParameterLaneBus`, extending the existing crossfader hack:
   ```java
   if (p == lx.engine.mixer.crossfader ||
       p.getParent() instanceof heronarts.lx.color.LXPalette) {
     return lx.engine.mixer.masterBus;
   }
   ```
   Then a palette change records into the composition's master row and is
   gated by the master bus's arm — consistent with how the crossfader already
   behaves, and it lands under a visible lane instead of nowhere.

Net effect: recalling a swatch on an armed master writes a Discrete event on a
palette-index lane under the master row, playing back at composition time.

## Workarounds available today (no host change)

- **Per-pattern color via `LinkedColorParameter` (recordable now).** A
  pattern's linked-color subparameters (`index`, `mode`, and in STATIC mode
  hue/sat/brightness) live on the pattern → on the channel bus, pass
  `isAutomationParameter`, and are registered by `registerBus → registerPattern`.
  With that channel armed, the composition **does** capture live changes to
  them. STATIC mode records literal colors; PALETTE `index` records which slot
  (1–5) of the active swatch a pattern reads. Limitation: per-pattern, and it
  cannot move the *global* active swatch.
- There is no clean JSON hand-authoring path: a palette parameter has no bus
  ancestor, so a manually-inserted parameter lane targeting it groups under no
  channel and is effectively invisible/inert in the composition.

## Side notes

- This is the same structural issue as the CLAUDE.md "composition lane
  visibility" gotcha (global-scoped parameters have no `LXBus` ancestor). The
  crossfader is the only global parameter the recorder currently rescues; the
  palette is the obvious second candidate, and arguably the more important one
  for choreographed timeline work.
- If per-swatch color *values* (not just the active-swatch index) ever need to
  record into the composition, `registerComponent` would need to recurse into
  the swatch's `LXDynamicColor` children — a larger change, probably not worth
  it given swatches are normally authored ahead of time and only *selected*
  live.
