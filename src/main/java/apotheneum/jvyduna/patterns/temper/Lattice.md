# Lattice — subpixel-arrangement bench

A visualization / bench tool for **simulating subpixel arrangements** that form
color fields on the Apotheneum square LED lattice. Three colors **A, B, C** (the
project palette's first three swatch colors, assumed **pastel CMY**) are
distributed across the pixels under a chosen screen scheme; the eye integrates
neighbours into a solid field. It is deliberately static — no audio, no tempo —
so schemes can be compared side by side.

Lives in the `temper` namespace but targets the standard cube + cylinder grid.
Surface targeting (exterior vs interior, cube vs cylinder) is done by the user
via **view filtering** at the pattern or channel level, so the same lattice is
rendered identically to all four surfaces.

## Colors A / B / C

Read live from `lx.engine.palette.swatch.colors` indices 0, 1, 2 (forced
opaque). Any missing swatch slot falls back to a hand-authored **pastel CMY**
(soft cyan / magenta / yellow) so the pattern is never colorless. Edit the
swatch to recolor A/B/C in real time.

## Modes (the `Mode` knob, 0..6, continuous)

`Mode` is continuous and **crossfades between integer schemes** along the
current `Path`. The schemes are ordered to **minimize the number of pixels that
change between neighbours**, so most crossfades are subtle:

| # | Name | `role(x,y)` | A:B:C | Auto-dim |
|---|------|-------------|-------|----------|
| 0 | Vertical triad | `x mod 3` | 1:1:1 | — |
| 1 | S-stripe | `(x + tri(y)) mod 3`, `tri` = triangle 0,1,2,3,2,1 | 1:1:1 | — |
| 2 | Diagonal / 3-sublattice (XO) | `(x + y) mod 3` | 1:1:1 | — |
| 3 | Horizontal triad | `y mod 3` | 1:1:1 | — |
| 4 | PenTile diamond (Bayer) | 2×2 `A B / C A` | 2:1:1 | **A ×0.5** |
| 5 | PenTile orthogonal (RGBG) | odd cols = B; even cols A/C by row | 1:2:1 | **B ×0.5** |
| 6 | Dither | `hash(x,y) mod 3` | ~1:1:1 | — |

Ordering rationale: **0–3** are pure shears / serpentines of a triad stripe (a
pixel only changes color at a stripe boundary → smooth crossfades); **4–5** are
the two PenTile screens (adjacent to each other); **6** is the stochastic
odd-one-out at the end.

Mode 6 is described as a blue-noise dither; it is currently a deterministic
integer **hash** dither (white-noise), which already gives the stripe-free field
we want. A precomputed blue-noise mask could be dropped into `hashTriad(x,y)`
later without touching anything else.

## Overrepresentation auto-dim

When a scheme uses one color more often than the others, that color would
otherwise dominate the integrated field. Each scheme's per-role dim is
`minCount / count[role]` over its tile — the **rarest** color stays full, a **2×**
color drops to **0.5**. This is exactly the requested behaviour: a Bayer
`GRBG = ABCA` doubles A, so A renders at half brightness (see mode 4). The dim is
keyed on **role**, not final color, so it stays correct as `Shift` rotates the
roles through A→B→C. Encoded in `OVERREP_DIM[mode][role]`.

## Shift (0..3)

Rotates **every pixel through A→B→C**. The integer part rotates the role
(`role → (role + floor(shift)) mod 3`); the fractional part interpolates between
the two palette colors **along `Path`**. At `Shift = 1` every A becomes B, B→C,
C→A; `Shift = 3` wraps back to the identity.

## Path (color-space route)

Selects the route used for **both** the Shift interpolation **and** the Mode
crossfade:

- **RGB** — per-channel linear.
- **HSV** — raw (non-wrapping) hue lerp + linear S/V (mirrors LX's default).
- **HSV CW / HSV CCW** — force the hue to travel clockwise / counter-clockwise
  around the wheel (matters for the wrap-crossing CMY pairs).
- **Thru Black** — constant hue, routed through black (each color dims out, the
  next rises from black at the midpoint).
- **Thru White** — routed through white (each color brightens to white, the next
  emerges from white).

A black endpoint in the HSV modes adopts the other endpoint's hue/sat, so a fade
to/from black (OFF sites, dim schemes) never sweeps hue.

## Alpha

Sets the **per-pixel ARGB alpha byte** on every lit pixel, for compositing this
pattern over other Chromatik channels. Verified that LX's `NormalBlend` /
`AddBlend` honour the source (pattern) alpha byte (`LXColor.lerp` reads
`src >>> 24`). **OFF and fully-dark sites are written fully transparent**
(`0x00000000`) so lower channels show through the screen-door gaps and the
darkest dip of a through-black crossfade.

## DimA / DimB / DimC

Three independent 0..1 multipliers that dim every pixel currently showing color
A / B / C (identity taken **after** Shift). Default 1.

## Implementation notes

- Extends `ApotheneumPattern`, overrides `render(deltaMs)`. Auto-generated UI
  only (no `UIDeviceControls`), per package preference.
- **Alloc-free.** The crossfaded output for each of the 3×3 `(role0, role1)`
  pairs is computed a handful of times per frame into `out[][]`; the per-LED
  loop is then two integer `roleFor` lookups plus a table read. HSB conversions
  happen ~15×/frame, not once per LED.
- Iterates each surface's `columns()` × `column.points`, bounding rows by
  `points.length` (door columns are shorter). `Y = 0` is the top row.
- `setColors(0x00000000)` each frame so any undrawn LED stays transparent.

## Verification

1. `mvn -Pinstall install`; keep only one `jvyduna-apotheneum-*.jar` in
   `~/Chromatik/Packages`. Launch "Chromatik (arrange)".
2. Add **Lattice** to a channel:
   - `Mode = 0` → vertical CMY triad stripes; sweep `Mode` 0→6 and watch the
     crossfades (subtle across 0–3, then the two PenTile screens, then dither).
   - `Mode = 4` (Bayer) → the doubled A renders visibly dimmer than B/C.
   - `Shift` 0→1 rotates A→B→C; `Shift = 3` returns to start.
   - `Path` changes the hue routing and the through-black / through-white fades.
   - `Alpha < 1` lets a solid pattern on a lower channel show through.
   - `DimA/B/C` dim each color independently; edit the swatch to recolor A/B/C
     live; empty the swatch → pastel-CMY fallback.
