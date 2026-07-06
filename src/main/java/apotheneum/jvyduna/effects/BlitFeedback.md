# Blit Feedback — design note

Video-feedback glitch effect, and the first `LXEffect` in this package
(everything else is patterns). While the momentary **TriGate** is held, a
shape-masked region of the output is sampled at a crawling cursor and
restamped one **Step** along **Angle** on every **Tempo** tick. Each copy
bakes in the previous copy's result — camera pointed at its own monitor —
so the trail degrades and smears as it travels. Releasing the gate fades the
accumulated copies to transparent over one **Fade** division.

## Architecture

- Extends `ApotheneumEffect` (exists-guard + `render(deltaMs, enabledAmount)`
  contract). Auto-generated control panel only, no custom UI.
- Four persistent straight-ARGB rasters (`Layer`), one per surface:
  cube ext/int 200×45, cylinder ext/int 120×43. `0x00000000` = empty.
  X wraps (`floorMod`), Y never wraps. Interiors get independent buffers and
  cursors (not mirrors of the exterior). Orientations are re-fetched every
  frame because `Apotheneum` rebuilds them on model changes; a null interior
  just deactivates that layer.
- `SurfaceCanvas` was deliberately **not** used: its `decay()` forces alpha
  opaque and `copyTo()` overwrites `colors[]` — both wrong for a compositing
  effect (and changing it would risk Mountains).

## Render order (per frame)

1. Refresh orientations; poll `TempoLock.crossed(Tempo)` unconditionally
   (a lapsed poll fires one stale event — see TempoLock javadoc).
2. Gate rising edge: reset each layer's cursor to SourceX/Y and arm the
   first grab. Cube x-mapping `wrap(SourceX·200 + 25)` puts SourceX 0 at the
   front-face center and 0.25 at the next face; cylinder `wrap(SourceX·120 +
   15)` lands the same four azimuths. SourceY 0 = top.
3. On rising edge or tick while held: **blit** each active layer —
   sample the rotated rect/oval/triangle mask at the cursor into a 128×128
   scratch raster, advance the cursor Step along Angle (buffer y is
   inverted: dy = −sin), stamp scratch at the new position. The scratch
   pass exists because sample and paste regions overlap whenever
   Step < Size; in-place copy would smear within a single tick.
4. Gate closed: exponential **alpha-only** fade,
   `scale = pow(1/255, deltaMs/fadeMs)` with an anti-stick decrement
   (BlurEffect idiom), sized so full alpha is culled after exactly one Fade
   division at the current BPM. Frame-rate independent. RGB is untouched:
   every LX blend function scales src contribution by its alpha byte, so one
   mechanism fades all Blend modes uniformly and ghosts keep their hue while
   going transparent. Fade also runs on toggled-off surfaces so stale
   content never pops back when a surface is re-enabled.
5. Composite each active layer over `colors[]` with the selected Blend
   (FreezeEffect-style `LXColor::` method refs) at
   `blendMask(enabledAmount × Level)`; alpha-0 pixels are skipped, so idle
   layers are nearly free.

## Sampling semantics (SampleSrc)

- **Composite**: each copy samples `blend(liveColors, buffer, levelMask)` per
  pixel, on the fly — exactly what the viewer saw last frame, so fresh
  pattern content keeps feeding the smear. Computed before the composite
  pass, while `colors[]` still holds the raw upstream frame.
- **Buffer**: the first grab after gate rise samples the composite (the
  buffer is empty); every later copy re-samples only its own prior copies,
  fading alpha included — a pure degrading echo of the gate-open moment.

## Mask + border

Shared `inside(i, j)` test: buffer offset → cartesian (negate j) → inverse
rotate by BlitAng → rect / ellipse / triangle test. Triangle: base width =
Size, height = AspRatio·Size, centered on the centroid (base at −h/3, apex at
+2h/3). Border pixel = inside with any 4-neighbor outside — stays uniformly
1px under rotation and anisotropy. Border color: 0–100% BorBright scales the
first palette swatch color's brightness; 100–200% lerps it toward pure white.

## Phase-2 "glitch spice" ideas (not built)

1. **Gain** (1..1.3): per-copy RGB multiply — overdrives the smear into
   clipped bloom after a few generations.
2. **Tear**: stamp the R channel offset +1..2px along the motion vector and B
   opposite — chromatic-aberration tearing, free inside the stamp loop.
3. **Jitter**: per-tick random paste offset (0..N px) — analog tracking error.

## Performance

Zero allocations in the render loop. Buffers total ~28K ints + 16K scratch,
allocated once in the constructor. Blit work happens only on ticks; the
per-frame fade and composite loops early-out on alpha 0.
