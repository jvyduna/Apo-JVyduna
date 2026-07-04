package apotheneum.jvyduna.util;

/**
 * Helpers for the screensaver series Energy convention.
 *
 * Every pattern exposes a master Energy parameter (0..1, default 0.35).
 * Energy ~0-0.4 is the soothing-ambient regime; ~0.6-1.0 is the 160 BPM
 * high-energy regime. Internal rates/densities/decays are declared as
 * (ambient, peak) endpoint pairs and resolved per frame through these
 * helpers, so one knob moves a pattern between shows. Each pattern's
 * sidecar .md documents its endpoints in an "Energy mapping" table.
 *
 * Hard rule: Energy scales intensity, density, spawn rate and decay — but
 * sustained motion speed stays within the simulation-principles cap
 * (full-sculpture traversal >= 5 seconds) even at energy = 1.
 */
public final class Ranges {

  private Ranges() {}

  /** Linear interpolation from ambient (e=0) to peak (e=1); e is clamped */
  public static double lin(double e, double ambient, double peak) {
    return ambient + (peak - ambient) * clamp(e);
  }

  /**
   * Geometric interpolation from ambient (e=0) to peak (e=1) — use for rates
   * spanning decades (e.g. 0.05..5 events/sec). Both endpoints must be
   * positive.
   */
  public static double exp(double e, double ambient, double peak) {
    return ambient * Math.pow(peak / ambient, clamp(e));
  }

  private static double clamp(double e) {
    return (e < 0) ? 0 : (e > 1) ? 1 : e;
  }
}
