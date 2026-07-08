# SymbolGlyphs — the shared religious-symbol asset

One reusable glyph table for the whole piece. The composition critique flagged
"incorporates many religious symbols" as the theme's spine, so the symbol set is
authored **once** here and recurs, mutated, across movements — it is NOT
re-implemented per song. Every pattern that shows a spiritual symbol pulls from
this file; that is what makes the recurrence read as one motif transmuting rather
than nine unrelated pattern ideas.

## Vector, not bitmap (2026-07-08 rewrite)

Glyphs are now **true vector geometry** (`java.awt.Shape` — bars, discs,
ellipses, arcs, stroked paths, boolean `Area` ops) rasterized on demand to an
**antialiased coverage grid**, instead of the old fixed 11px `#`-ASCII masks.
Why: the caller (GlassRain's liquid-symbol sim) needs a glyph mask at an
arbitrary cell size that renders diagonals and curves cleanly, and the house
`Smooth` knob should control the antialiasing. Vector → raster gives both — a
glyph scales to any size and its edges are as soft or hard as `Smooth` asks.

Rasterization reuses the **headless-AWT technique proven in `OtfTypeface`**
(`BufferedImage` + `Graphics2D`, `java.awt.headless=true` is set by Chromatik).
`coverage(sym, cols, rows, smooth)` supersamples the shape (4×), box-averages
down to `cols×rows`, and **caches** the result keyed by
`(symbol, cols, rows, smooth-quantized-to-0.1)` — so it rasters once per distinct
request and is a map lookup thereafter (never per frame). No LX dependency.

`smooth ∈ [0,1]` dials edge antialiasing by remapping coverage around 0.5 (the
same `sharpen()` idea as `SurfaceCanvas.lineWu`): `1` = soft AA edges, `0` = a
hard on/off mask.

## Glyphs

Nine abstracted, deliberately **non-denominational** spiritual glyphs (enum
`Symbol`, stable ordinal order — a continuous selector floors to an index):

| Symbol     | Motif                              | Notes |
|------------|------------------------------------|-------|
| `CROSS`    | Latin **cross pommée** (bulbed arm ends) | bars + a disc at each of the 4 ends |
| `EYE`      | Eye of providence (almond ring + pupil) | vesica outline via circle intersection |
| `SUN`      | Sun / halo, disc + 8 tapered rays  | doubles as a halo behind other glyphs |
| `FISH`     | Ichthys, two crossing stroked arcs | body left, open crossed tail right |
| `CRESCENT` | Waxing moon, **rotated +45°**      | outer disc minus an offset disc |
| `OM`       | ॐ — open curl + hook + crescent/bindu, **rotated −90°** | most abstract; top CURATE candidate |
| `ANKH`     | Loop (ring) + crossbar + stem      | |
| `STAR`     | Star of David / hexagram **outline**, **rotated −90°** | two stroked interlocking triangles |
| `TREE`     | Tree of life (canopy / trunk / roots) | |

Rotations are baked in via an `AffineTransform` about the glyph center before
rasterization, so they stay crisp at any size. Per Jeff's 2026-07-08 feedback:
Star −90°, Om −90°, Crescent +45°, and Cross switched from a plain Latin cross to
the pommée form.

## Size

No fixed size any more — the caller asks for a `cols×rows` cell box (GlassRain
uses `GLYPH_PX = 24`, ~half the cube/cylinder height). Geometry is authored in a
normalized unit box `[0,1]²` (y down) with a small `PAD` margin so bulbs and
outline strokes don't clip at the raster edge.

CURATE: `OM` is still the least legible and the first candidate for a hand-tuned
redraw (or rendering the real Unicode char through `Text`). The providence eye's
enclosing triangle is still intentionally omitted (doesn't read at pitch).

## Reverse-normal (how callers make glyphs read from inside)

`SymbolGlyphs` produces an upright coverage grid; it does **not** itself handle
interior/exterior legibility. A caller drawing on a two-layer Apotheneum surface
places glyph instances **symmetric about the surface's horizontal center** and
copies the interior layer with `SurfaceCanvas.copyToMirrored` — the whole-canvas
mirror then maps each glyph onto its identical mirror partner, so it reads
un-reversed from inside. See GlassRain for the reference wiring.

## How each movement reuses the SAME asset differently

The point of the shared table: one call site changes per movement, the glyph data
does not.

- **Rafters — "liquid condensing symbols."** GlassRain builds a per-cell glyph
  `mask` from `coverage(...)` and lets rain **pool into** it: drops are absorbed
  into masked cells, gravity + surface-tension settle the liquid, it tints to the
  warm swatch, fills, and overflows out the bottom lips. The glyph literally forms
  out of water.
- **Chrome — "occult codex glyphs."** Same glyphs, now hard and metallic: full
  size, bright/specular color, `Blend.XOR`/`DIFF` for engraved interference,
  arranged as a grid/codex. Flip glyph selection on the beat like codex pages.
- **Distance — "a settled AMEN / HOME."** The transmutation resolves: one or two
  glyphs (e.g. `CROSS`, `STAR`) large, steady, centered and still — paired with
  `PixelFont5` text. The recurring symbol finally *lands*.

## API

```java
public enum Symbol { CROSS, EYE, SUN, FISH, CRESCENT, OM, ANKH, STAR, TREE }

public static int      count();               // number of glyphs (1..count selector)
public static Symbol[] all();                 // shared, read-only
public static Symbol   byIndex(int i);        // clamped ordinal lookup

// Antialiased coverage grid, row-major cov[y*cols + x] in [0,1], y down.
// Cached per (symbol, cols, rows, smooth); returned array is SHARED / READ-ONLY.
public static float[]  coverage(Symbol s, int cols, int rows, double smooth);

public static void main(String[] args);       // ASCII preview (arg[0] = smooth)
```

Run `main` (optionally with a smooth value) to eyeball every glyph as ASCII —
the cross pommée bulbs and the Star/Om/Crescent rotations are visible there.
`rasterize` allocates a `BufferedImage` on a **cache miss only** (a glyph/size/
smooth first seen), never on a hit, so render loops that reuse sizes stay
allocation-free after warmup.

## History

- Original: 11px `#`-ASCII bitmap masks + a bitmap cross-dissolve `morph()`.
- 2026-07-08: rewritten to vector→coverage rasterization (this doc). The bitmap
  `MASKS`/`rows`/`pixel`/`render`/`morph` API was **removed** — GlassRain's liquid
  model replaces the cross-dissolve, and no other pattern used `SymbolGlyphs` yet.
