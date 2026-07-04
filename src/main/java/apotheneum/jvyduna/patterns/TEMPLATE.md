# <PatternName>

<One-sentence description matching the @LXComponent.Description annotation.>

> Sidecar design doc convention: this file lives beside `<PatternName>.java` and is
> the source of truth for design decisions and curation history. Mark any constant
> or behavior you could not visually verify with an inline `CURATE:` note so the
> review session can find them (grep for `CURATE:`).

## Original / inspiration

What screensaver this recreates, which version/platform, and what visual
signature we're preserving. If the design is an interpretation (original
unconfirmed), say so explicitly.

## Rendering approach

- Base class (`ApotheneumPattern` / `ApotheneumRasterPattern`) and why
- Surfaces covered: cube exterior/interior, cylinder — and how interior is
  handled (copied / intentionally dark)
- Geometry mapping (per-point, SurfaceCanvas 200x45 ring, 120x43 cylinder, ...)
- Buffers and their preallocation strategy (zero-alloc render rule)
- Door-column handling

## Audio mapping

Which `AudioReactive` taps drive what (bass/mid/treble/level, ratios,
bassHit/trebleHit), and the silence behavior (what the pattern does with all
taps at 0 — it must still look good).

## Energy mapping

| Quantity | Ambient (e=0) | Peak (e=1) | Curve (lin/exp) |
|---|---|---|---|
| ... | ... | ... | ... |

Sustained motion must respect the >=5s full-traversal cap even at e=1.

## Parameters

UI order: triggers first, Energy, pattern parameters, Meta last.

| Param | Label | Type | Default | Range | Meaning |
|---|---|---|---|---|---|
| ... | ... | ... | ... | ... | ... |

## Triggers

- `name` — what suddenly changes, and how long the change takes to read on the
  sculpture

## Jump candidates

Rows mirror the `bag.jumpable(...)` lines in the constructor 1:1. Status is
updated during curation.

| Param | Jump range | Status | Notes |
|---|---|---|---|
| ... | ... | candidate | ... |

Status values: `candidate` (initial) / `confirmed` / `dropped` / `re-ranged to [a,b]`.

## Simulation-principles compliance

Show the math: fastest sustained motion at default energy AND at energy=1, in
seconds per full sculpture traversal (>=5s required; event-like motion such as
a lightning stroke may be faster but must have >=1.5s total visual life).
Contrast/brightness choices (bold forms, no fine texture, posterization etc.).

## Curation log

| Date | Change | Why |
|---|---|---|
| ... | ... | ... |
