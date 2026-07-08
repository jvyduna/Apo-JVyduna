# SymbolGlyphs — the shared religious-symbol asset

One reusable glyph table for the whole piece. The composition critique flagged
"incorporates many religious symbols" as the theme's spine, so the symbol set is
authored **once** here and recurs, mutated, across movements — it is NOT
re-implemented per song. Every pattern that shows a spiritual symbol pulls from
this file; that is what makes the recurrence read as one motif transmuting rather
than nine unrelated pattern ideas.

Modeled on `PixelFont5`: glyphs are ASCII-art bitmaps (`#` = lit) parsed into row
bit-masks, no LX dependency beyond `SurfaceCanvas`, with a `main()` ASCII preview.

## Glyphs

Nine abstracted, deliberately **non-denominational** spiritual glyphs (enum
`Symbol`, stable ordinal order):

| Symbol     | Motif                          | Notes |
|------------|--------------------------------|-------|
| `CROSS`    | Latin cross                    | clean, the anchor shape |
| `EYE`      | Eye of providence (almond+pupil) | enclosing triangle dropped — doesn't read at 11px (CURATE) |
| `SUN`      | Sun / halo, 8 rays             | doubles as halo behind other glyphs |
| `FISH`     | Ichthys, vesica + crossed tail | tail muddies at distance (CURATE) |
| `CRESCENT` | Waxing moon                    | |
| `OM`       | ॐ — crescent+bindu over a curl | weakest at 11px; top CURATE candidate |
| `ANKH`     | Loop + crossbar + stem         | |
| `STAR`     | Six-point hexagram (outline)   | |
| `TREE`     | Tree of life (canopy/trunk/roots) | |

## Size — CURATE

`SIZE = 11` (square). Chosen because 11 is the smallest **odd** square with a true
center row/column, which the symmetric glyphs (cross, star, sun, ankh) need to
sit straight; and 11px still reads as a *symbol* rather than a blob at the
Apotheneum LED pitch. On a 50-column cube face an unscaled glyph is ~1/5 of the
face; use the integer `scale` arg to grow it for hero moments. CURATE flags: OM
and FISH are the least legible and are the first candidates for a hand-tuned
redraw (or, for OM, rendering the real Unicode char through `Text`/`PixelFont5`
instead). The enclosing triangle of the providence eye was intentionally omitted.

## Morph model — CURATE

`morph(from, to, t)` is a **bitmap cross-dissolve**: per cell, coverage =
`(fromLit ? 1-t : 0) + (toLit ? t : 0)`, clamped, used as an alpha on the render
color. At `t=0` only `from` shows, at `t=1` only `to`; between, one shape fades
out through the other. Zero-allocation; composites with `SurfaceCanvas.Blend`
(default `MAX`/lighten so glyphs layer without darkening).

This first pass reads as a symbol **condensing / transmuting** into another —
exactly the rafters→chrome intent. CURATE: a true **vector / distance-field
morph** that slides strokes continuously between shapes is a later upgrade; the
dissolve is the placeholder until then.

## How each movement reuses the SAME asset differently

The point of the shared table: one call site changes per movement, the glyph data
does not.

- **Rafters — "wet condensing symbols."** Draw glyphs faintly and let them
  *accrete*: render with a low-`scale`, dim color, into a canvas that is
  `decay()`-ed each frame so symbols bloom and drip like condensation on the
  rafters. Use `morph(prevSymbol, nextSymbol, t)` with `t` creeping slowly so one
  symbol is always half-forming out of the last — the "wet" precondensation look.
- **Chrome — "occult codex glyphs."** Same glyphs, now hard and metallic: full
  `scale`, bright/specular color, `Blend.XOR` or `DIFF` for engraved
  interference, arranged as a grid/codex across the chrome. Step `morph` `t` **on
  the beat** (snap 0→1) so glyphs flip like pages of a codex rather than dissolve.
- **Distance — "a settled AMEN / HOME."** The transmutation resolves: pick one or
  two glyphs (e.g. `CROSS`, `STAR`) at `t=0` or `t=1` (no ongoing morph), large
  `scale`, steady color, centered and still — paired with `PixelFont5` text
  ("AMEN" / "HOME"). The recurring symbol finally *lands*.

## API

```java
public static final int SIZE = 11;
public enum Symbol { CROSS, EYE, SUN, FISH, CRESCENT, OM, ANKH, STAR, TREE }

public static Symbol[] all();                 // shared, read-only
public static int[]    rows(Symbol s);        // row bit-masks, shared, read-only
public static boolean  pixel(Symbol s, int gx, int gy);

public static void render(SurfaceCanvas c, Symbol s, int cx, int cy, int argb, int scale);
public static void render(SurfaceCanvas c, Symbol s, int cx, int cy, int argb, int scale,
                          SurfaceCanvas.Blend blend);
public static void morph(SurfaceCanvas c, Symbol from, Symbol to, double t,
                         int cx, int cy, int argb, int scale);
public static void morph(SurfaceCanvas c, Symbol from, Symbol to, double t,
                         int cx, int cy, int argb, int scale, SurfaceCanvas.Blend blend);
```

`(cx, cy)` is the glyph **center** in canvas coords (x wraps per `SurfaceCanvas`).
`scale` is integer nearest-neighbor block upscale (clamped ≥ 1). `argb` RGB is
used, alpha ignored. Everything after construction is allocation-free.
