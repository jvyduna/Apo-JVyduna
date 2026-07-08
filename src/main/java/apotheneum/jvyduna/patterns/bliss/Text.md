# Text

View-scoped LED text in a selectable bitmap font. Layout is done in **world
units** so rotation stays undistorted on any face aspect; each LED is mapped
through the inverse text transform (exact at 0/90/180/270°). Drop it on a view
and type a string; `\n` gives line breaks (Chromatik has no multi-line text
widget — real newlines set via JSON also work).

## Fonts (`fontSel`)

Monospaced bitmap fonts behind the `GlyphFont` interface (`util/`):

| Option | Cell | Style |
|--------|------|-------|
| `5px`  | 5×5  | The original `PixelFont5` (also used by HoldClock) |
| `ZX`   | 5×7  | Clean, thin, modern — Sinclair-ZX / classic dot-matrix |
| `CRT`  | 6×7  | Chunky two-pixel strokes — early-computer / terminal ROM |
| `7Seg` | 4×7  | Seven-segment "digital" — calculator / LED-clock look |

All cover A–Z, 0–9, and `space # : - . /` (unknown chars → `?`). Each font class
has a `main()` that dumps every glyph as ASCII (`BitmapFont.preview`) and asserts
full coverage; run it to eyeball legibility. `7Seg` letters are conventional
seven-segment approximations, so some pairs are intentionally ambiguous the way a
real display is (0/O, 2/Z, 5/S, H/X, U/W). The enum constant **names** are the
`.lxp` serialization key — keep them stable across releases.

## Interior/exterior legibility

A surface's exterior and interior are physically **distinct LED layers** that face
opposite ways (interior column *i* is co-located with exterior column *i* but
points inward), so identical content reads **mirror-reversed** from the far side.

- **`Auto`** (`autoProj`, on by default) — classifies each LED as interior or
  exterior *from the model* (`Apotheneum.cube/cylinder.interior` collections, built
  once, no render-loop cost) and applies one reflection to the interior layer's
  sampling. The text block stays in the **same bounding box** on both layers, so it
  reads correctly from **both** sides at once. Works whether the view is scoped to
  exterior-only, interior-only, or spans both.
- **`FlipProj`** (`flipProj`) — with Auto on, inverts which layer is treated as the
  front-readable side (for a surface wired opposite expectation, or an audience
  mostly on the inside). With Auto off, it manually mirrors the whole projection.
- **`FlipRead`** (`flipRead`) — reflects on the **vertical** axis instead of the
  horizontal. For 90°-rotated text (a word climbing bottom-to-top) the two axis
  choices differ by a 180° rotation, so this picks whether the facing projection
  reads bottom-to-top or top-to-bottom.
- **`Flip`** (`flip`) — the original manual whole-view horizontal mirror
  (per-face calibration); composes with the above.

Reflections are taken **about the text anchor**, not the model center, so
off-center text (`xOffset`/`yOffset` ≠ 0.5) registers identically on both layers.

**Usage note:** the world-planar layout is not per-face axis-corrected across a
*whole-cube* view (different faces use X vs Z as their in-plane axis). Place `Text`
on a **single surface / single face** view (both LED layers of one wall, or the
cylinder) for correct results — which is also where dual-layer legibility matters.

## Parameters

`text`, `scale`, `rotation`, `xOffset`/`yOffset`, `lineSpacing`/`charSpacing`,
`hue`/`saturation`/`brightness`, plus `fontSel`, `flip`, `autoProj`, `flipProj`,
`flipRead`. UI columns: Text (+ font) · Layout · Place · Proj · Color.
