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
| `File` | —    | An OTF/TTF/TTC loaded from disk (see below) |

### OTF/TTF mode (`Font = File`)

Pick a font with the **Font File...** button (or set `fontFile` to an absolute
path; it serializes into the `.lxp`). Rendering uses headless-AWT
(`util/OtfTypeface`): the file's main face is loaded with **kerning enabled**,
each text block is rasterized once to a ~48px-per-line antialiased coverage
bitmap (rebuilt only when text/path/spacing change), and LEDs bilinear-sample
it — so edges are smooth and brightness carries the antialiasing. `charSpacing`
is ignored in this mode (kerning governs); `scale`, `rotation`, offsets,
`lineSpacing`, multi-line `\n`, and all projection/legibility params work
unchanged. An unloadable path logs once and falls back to the 5px builtin.
Tiny text (< ~8 LED px per line) looks better in the bitmap fonts — that's what
they're for.

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

The pattern derives each LED's **emission normal** from the model (cube-face planes
+ cylinder column radials via `Apotheneum.cube/cylinder`, built once, no render-loop
cost). World-planar text is legible only from one side of its plane (`N = up × h`);
a layer whose emission faces away from `N` ("back-facing") shows mirror-reversed text
to its viewers and gets **one horizontal mirror** to fix it. That single H-mirror is
the *only* correction ever needed — a transform-algebra check shows it makes text
read correctly for horizontal **and both** 90° vertical directions; a vertical mirror
is never the right fix (so there is no "flip verts" control — vertical reading
direction is chosen with the **Rotate** knob, 90 vs 270). The reflection is taken
**about the text anchor**, not the model center, so off-center text
(`xOffset`/`yOffset` ≠ 0.5) registers identically on both layers.

### `Orient` (Orientation column) — which layers get the fix

A single dropdown (replaces the old Auto/FlipProj/FlipVerts booleans). Opposite faces
of a surface always have opposite normals, so whichever layers are corrected, the two
sides of a surface stay mutually reversed.

- **`Auto`** (default) — correct every back-facing layer. Highest readership; serves
  outside and inside audiences at once, horizontal or vertical.
- **`Exterior`** — correct only the **outward-emitting** layers (cube/cylinder
  exteriors); interior layers are left as literal projection (appear mirrored). These
  outward layers carry the large majority of viewers, so this keeps most readership
  while freeing the interior for a mirror-script effect.
- **`Interior`** — correct only the **inward-emitting** layers; serves the inside
  (cylinder / cube-interior) audience.
- **`Raw`** — no correction; literal projection (back-facing layers appear mirrored).
  For symmetric/abstract content, a deliberate mirror effect, or visually confirming
  which side is naturally readable.

### Why these modes (legibility simulation)

A Monte-Carlo sim (10 m cube, 6 m cylinder; text projected surface-normal through one
cube face → the 8 layers the normal line crosses; viewers 70% outside / 20% in-cube /
10% in-cylinder, facing center, 90° FOV, ≥2 m, line-of-sight occluded by walls +
cylinder, nearest surface weighted 0.6) gives weighted readership: **Auto ≈ 82%**
(outside 94% / in-cube 40% / in-cyl 82%), **Exterior ≈ 73%**, **Interior ≈ 9%**,
**Raw ≈ 41%**. Outward layers draw ~89% of all eyeballs (the two cube exteriors alone
~40% each), which is why `Auto` and `Exterior` dominate and `Auto` is the default. The
inside-cube / outside-cylinder region tops out ~40% under *any* mode — a geometry
limit (the cylinder occludes cross-cube sightlines and the ≥2 m rule blanks LEDs near
the walls), not something orientation can fix.

## Side selection

- **`OtherSides`** (`otherSides`, under **Place**) — flips the auto-detected
  horizontal axis (X↔Z). By default the text lands on the wall-pair whose surface
  lies in the XY plane (front/back of a cube view); turning this on writes on the
  other pair (left/right) instead, and on a cylinder view rotates the legible band
  90° around Y. The `Orient` correction tracks the selected axis, so far-side
  mirroring stays correct on whichever pair is chosen. (On a single-face view the
  off-axis range is ~zero, so `OtherSides` blanks the text — it's a
  cube/cylinder-spanning-view tool.)

**Usage note:** the world-planar layout is not per-face axis-corrected across a
*whole-cube* view (different faces use X vs Z as their in-plane axis). Place `Text`
on a **single surface / single face** view (both LED layers of one wall, or the
cylinder) for correct results — which is also where dual-layer legibility matters.

## Parameters

`text`, `scale`, `rotation`, `xOffset`/`yOffset`, `lineSpacing`/`charSpacing`,
`hue`/`saturation`/`brightness`, plus `fontSel`, `fontFile`, `otherSides`, `orient`.
UI columns: Text (+ font + file picker) · Layout · Place (X/Y/OtherSides + `Orient`
dropdown) · Color. Vertical (90°) reading direction is the `Rotate` knob (90 =
bottom→top, 270 = top→bottom).
