package apotheneum.jvyduna.util;

import heronarts.lx.color.LXColor;

/**
 * Fast, allocation-free perceptual hue remapping and even hue allocation.
 *
 * <p>A naive HSV hue wheel is perceptually lumpy: we readily name red, orange,
 * yellow, green, aqua, blue, purple and pink as distinct bands, but the spectral
 * hue degrees between them are very uneven (orange occupies a sliver, green/blue a
 * huge arc). This remaps a perceptually-uniform position {@code u ∈ [0,1)} onto
 * spectral HSV hue degrees so that equal steps in {@code u} read as roughly equal
 * perceptual jumps — the same idea as FastLED's default "rainbow" map, which gives
 * orange/yellow extra room and compresses green/blue.
 *
 * <p>Implemented as a monotonic piecewise-linear LUT (8 evenly-spaced perceptual
 * anchors), so both the forward map and its inverse are O(1)/O(8) with no heap.
 */
public final class PerceptualHue {

  private PerceptualHue() {}

  /**
   * Spectral HSV hue (degrees) at each of 8 evenly-spaced perceptual anchors, plus
   * the wrap back to red at 360. Approximates FastLED's rainbow spacing: red(0),
   * orange(30), yellow(60), green(120), aqua(180), blue(240), purple(280),
   * pink(320). Monotonic increasing so the inverse is a simple segment search.
   */
  private static final float[] SPECTRAL = {
    0f, 30f, 60f, 120f, 180f, 240f, 280f, 320f, 360f
  };
  private static final int SEGMENTS = SPECTRAL.length - 1; // 8

  /** Map a perceptual position {@code u ∈ [0,1)} to a spectral HSV hue in [0,360). */
  public static float toSpectralHue(float u) {
    u -= (float) Math.floor(u); // wrap into [0,1)
    float f = u * SEGMENTS;
    int i = (int) f;
    if (i >= SEGMENTS) {
      i = SEGMENTS - 1;
    }
    float frac = f - i;
    return SPECTRAL[i] + frac * (SPECTRAL[i + 1] - SPECTRAL[i]);
  }

  /** Inverse: map a spectral HSV hue (degrees) to its perceptual position in [0,1). */
  public static float toPerceptualPosition(float hueDeg) {
    hueDeg -= 360f * (float) Math.floor(hueDeg / 360f); // wrap into [0,360)
    for (int i = 0; i < SEGMENTS; ++i) {
      float lo = SPECTRAL[i], hi = SPECTRAL[i + 1];
      if (hueDeg <= hi) {
        float frac = (hi > lo) ? (hueDeg - lo) / (hi - lo) : 0f;
        return (i + frac) / SEGMENTS;
      }
    }
    return 0f;
  }

  /** Fully-saturated, full-brightness color at perceptual position {@code u ∈ [0,1)}. */
  public static int color(float u) {
    return LXColor.hsb(toSpectralHue(u), 100f, 100f);
  }

  /**
   * Allocate {@code m} new positions on the unit circle [0,1) that fill the gaps
   * between {@code existingCount} anchors as evenly as possible, minimizing the
   * spread of the resulting gap sizes (greedy: repeatedly bisect the largest current
   * gap). Wrap-aware and allocation-free.
   *
   * @param work scratch/state: entries {@code [0, existingCount)} hold the existing
   *             positions on entry; must have capacity {@code >= existingCount + m}.
   *             Mutated (sorted, appended).
   * @param existingCount number of pre-placed anchors in {@code work}
   * @param m number of new positions to allocate
   * @param out receives the {@code m} new positions (insertion order)
   */
  public static void fillCircle(float[] work, int existingCount, int m, float[] out) {
    if (existingCount == 0) {
      // No anchors: spread m points evenly from 0.
      for (int k = 0; k < m; ++k) {
        out[k] = (float) k / m;
        work[k] = out[k];
      }
      return;
    }
    int n = existingCount;
    for (int k = 0; k < m; ++k) {
      // Insertion-sort the current n positions (n is tiny).
      for (int i = 1; i < n; ++i) {
        float key = work[i];
        int j = i - 1;
        while (j >= 0 && work[j] > key) {
          work[j + 1] = work[j];
          --j;
        }
        work[j + 1] = key;
      }
      // Find the largest gap (wrap-around) and bisect it.
      float bestMid = 0f, bestLen = -1f;
      for (int i = 0; i < n; ++i) {
        float a = work[i];
        float b = (i + 1 < n) ? work[i + 1] : work[0] + 1f;
        float len = b - a;
        if (len > bestLen) {
          bestLen = len;
          bestMid = a + len * 0.5f;
        }
      }
      bestMid -= (float) Math.floor(bestMid);
      out[k] = bestMid;
      work[n++] = bestMid;
    }
  }
}
