package apotheneum.jvyduna.util;

import heronarts.lx.LX;
import heronarts.lx.Tempo;

/**
 * Helper for locking pattern motion events to the engine tempo grid.
 *
 * Two facilities, both zero-allocation per frame:
 *
 * <ol>
 * <li><b>{@link #retime(double, Tempo.Division)}</b> — predictive rate
 *     nudging. When a pattern knows a future event's arrival time at its
 *     current rate (e.g. "this ball hits the wall in 1370 ms"), retime()
 *     returns a scale factor {@code s} to apply as {@code velocity *= s} so
 *     the event lands exactly on a boundary of the given tempo division.
 *     Boundaries are aligned to the engine's actual beat phase (via
 *     {@link Tempo#getBasis(Tempo.Division)}), not to arbitrary multiples of
 *     the period measured from "now". The factor is chosen as close to 1 as
 *     possible within a clamp (default {@value #DEFAULT_MIN_SCALE}..{@value
 *     #DEFAULT_MAX_SCALE}); if no grid boundary is reachable within the
 *     clamp (e.g. the event is imminent but the division is a whole bar),
 *     the clamped factor is returned — rate change stays bounded and the
 *     event lands as near the grid as the clamp allows. Never returns 0,
 *     NaN or Infinity at any BPM or msUntilEvent.</li>
 * <li><b>{@link #crossed(Tempo.Division)}</b> — a per-frame boundary gate
 *     (Pipes3D-style). Call once per frame; it returns true on exactly the
 *     frames where the engine crossed a boundary of the division, for firing
 *     spawn/step events on the grid.</li>
 * <li><b>{@link #quantizePeriod(double, Tempo.Division)}</b> — period
 *     quantization for continuous cyclic drifts (Satori-style). Where
 *     retime() phase-aligns a single scheduled future event, quantizePeriod()
 *     snaps a rolling <em>repeating</em> period (e.g. a palette band-advance
 *     period) to the nearest whole multiple or unit fraction of the division,
 *     with an internal harmonic latch so per-frame reapplication does not
 *     jitter. Safe (and intended) to call every frame.</li>
 * <li><b>Absolute beat position</b> — {@link #beatPosition()},
 *     {@link #beatCycle()}, {@link #beatPeriodMs()} and
 *     {@link #msUntilBeat(double)} expose the transport's continuous
 *     quarter-note beat index (Mystify-style). Patterns that hard-lock events
 *     to the grid store a target as an absolute beat and derive velocity from
 *     remaining-distance / msUntilBeat(target) each frame — exact arrival,
 *     robust to live BPM changes.</li>
 * </ol>
 *
 * <h2>When to call retime()</h2>
 *
 * Call it at event-scheduling moments (a bounce just happened and the next
 * bounce time is known), not necessarily every frame. Per-frame reapplication
 * is safe and self-corrects under live BPM changes — after the first
 * application subsequent calls return ~1 — but prefer event-rate calls; near
 * the boundary itself, tiny msUntilEvent values simply return a clamped
 * factor.
 *
 * <h2>Standard parameter pair</h2>
 *
 * Patterns adopting tempo locking add exactly this pair, registered after the
 * "Audio" knob and before Meta (series order: triggers, Energy,
 * pattern-specific, Audio, Sync, TempoDiv, Meta):
 *
 * <pre>
 *   public final BooleanParameter sync =
 *     new BooleanParameter("Sync", true)
 *     .setDescription("Lock motion events to the tempo grid; off restores free-running timing");
 *
 *   public final EnumParameter&lt;Tempo.Division&gt; tempoDiv =
 *     new EnumParameter&lt;Tempo.Division&gt;("TempoDiv", Tempo.Division.QUARTER) // per-pattern default
 *     .setDescription("Tempo division that motion events land on when Sync is enabled");
 *
 *   // constructor:
 *   private final TempoLock tempoLock = new TempoLock(lx);
 *   addParameter("sync", this.sync);
 *   addParameter("tempoDiv", this.tempoDiv);
 * </pre>
 *
 * Usage in the pattern:
 *
 * <pre>
 *   // At an event-scheduling moment (e.g. just bounced; next bounce known):
 *   if (this.sync.isOn()) {
 *     double msUntil = distanceToWall / Math.abs(velocity) * 1000;
 *     velocity *= this.tempoLock.retime(msUntil, this.tempoDiv.getEnum());
 *   }
 *
 *   // Or, firing discrete events on the grid, once per frame in render():
 *   if (this.sync.isOn() &amp;&amp; this.tempoLock.crossed(this.tempoDiv.getEnum())) {
 *     spawnSomething(); // event-rate; minimal allocation permitted
 *   }
 * </pre>
 *
 * When Sync is off, simply skip these calls — the pattern's free-running
 * timing is untouched (retime is multiplicative and transient, so no state
 * needs restoring).
 */
public class TempoLock {

  /** Default lower clamp on the retime() rate scale (max 30% slow-down) */
  public static final double DEFAULT_MIN_SCALE = 0.7;

  /** Default upper clamp on the retime() rate scale (max 40% speed-up) */
  public static final double DEFAULT_MAX_SCALE = 1.4;

  private final Tempo tempo;

  // crossed() gate state
  private Tempo.Division lastDivision = null;
  private int lastCycleCount = 0;

  // quantizePeriod() harmonic latch state
  private double quantDivMs = 0;
  private double quantTargetMs = 0;

  public TempoLock(LX lx) {
    this.tempo = lx.engine.tempo;
  }

  /**
   * Period of one cycle of the given division at the current tempo.
   *
   * @param division Tempo division
   * @return Duration of the division in milliseconds
   */
  public double divisionMs(Tempo.Division division) {
    // period is ms per quarter-note beat; multiplier is divisions per beat
    return this.tempo.period.getValue() / division.multiplier;
  }

  /**
   * Milliseconds from now until the next boundary of the given division,
   * phase-aligned to the engine beat via {@link Tempo#getBasis(Tempo.Division)}.
   * In (0, divisionMs]; returns exactly divisionMs when the engine is exactly
   * on a boundary. Useful for scheduling a one-shot ramp (e.g. a fade) to
   * conclude on the grid: measure once at trigger time and count down in ms
   * (a subsequent BPM change then drifts the conclusion — re-measure if that
   * matters).
   *
   * @param division Tempo division whose next boundary to measure
   * @return Ms until the next boundary of the division
   */
  public double msUntilNext(Tempo.Division division) {
    return (1 - this.tempo.getBasis(division)) * divisionMs(division);
  }

  /**
   * retime() with the default scale clamp
   * [{@value #DEFAULT_MIN_SCALE}, {@value #DEFAULT_MAX_SCALE}].
   *
   * @param msUntilEvent Time until the event at the current rate, in ms
   * @param division Tempo division whose grid the event should land on
   * @return Rate scale factor, apply as {@code velocity *= s}
   */
  public double retime(double msUntilEvent, Tempo.Division division) {
    return retime(msUntilEvent, division, DEFAULT_MIN_SCALE, DEFAULT_MAX_SCALE);
  }

  /**
   * Compute the rate scale factor that makes an event, currently due in
   * msUntilEvent, arrive exactly on a boundary of the given tempo division.
   * Boundaries are phase-aligned to the engine beat via
   * {@link Tempo#getBasis(Tempo.Division)}. Picks the boundary yielding the
   * scale nearest 1; if that scale falls outside [minScale, maxScale] the
   * adjacent boundary is used, and as a last resort the scale is clamped
   * (event lands off-grid but the rate change stays bounded).
   *
   * @param msUntilEvent Time until the event at the current rate, in ms;
   *                     values <= 0, NaN or Infinity return 1
   * @param division Tempo division whose grid the event should land on
   * @param minScale Lower clamp on the returned scale, must be > 0
   * @param maxScale Upper clamp on the returned scale, >= minScale
   * @return Rate scale factor in [minScale, maxScale], never 0/NaN/Infinity;
   *         apply as {@code velocity *= s}
   */
  public double retime(double msUntilEvent, Tempo.Division division, double minScale, double maxScale) {
    final double divMs = divisionMs(division);
    // NB: isFinite matters — msUntilEvent = Infinity (e.g. distance/velocity
    // with velocity 0) would otherwise flow through to s = Inf/Inf = NaN,
    // and NaN slips past the final clamp's comparisons
    if (!(divMs > 0) || !(msUntilEvent > 0) || !Double.isFinite(msUntilEvent)) {
      return 1;
    }
    // Sanitize the clamp so the result is always positive and ordered
    final double lo = (minScale > 1e-6) ? minScale : 1e-6;
    final double hi = (maxScale > lo) ? maxScale : lo;

    // Ms until the next division boundary, phase-aligned to the engine beat.
    // getBasis() is in [0, 1), so untilNext is in (0, divMs].
    final double untilNext = (1 - this.tempo.getBasis(division)) * divMs;

    // Candidate boundaries lie at untilNext + k*divMs, k = 0, 1, 2, ...
    // Pick k whose boundary is nearest the unscaled arrival time (s nearest 1)
    double k = Math.rint((msUntilEvent - untilNext) / divMs);
    if (k < 0) {
      k = 0;
    }
    double s = msUntilEvent / (untilNext + k * divMs);

    // If the nearest boundary violates the clamp, try the adjacent one
    if (s > hi) {
      // Boundary too soon: aim one boundary later (slow down instead)
      s = msUntilEvent / (untilNext + (k + 1) * divMs);
    } else if ((s < lo) && (k >= 1)) {
      // Boundary too late: aim one boundary earlier (speed up instead)
      s = msUntilEvent / (untilNext + (k - 1) * divMs);
    }

    // Final safety clamp: bounded, positive, finite
    return (s < lo) ? lo : (s > hi) ? hi : s;
  }

  /**
   * quantizePeriod() with the default scale clamp
   * [{@value #DEFAULT_MIN_SCALE}, {@value #DEFAULT_MAX_SCALE}].
   *
   * @param periodMs Repeating period at the current rate, in ms
   * @param division Tempo division whose grid the period should lock to
   * @return Rate scale factor, apply as {@code rate *= s} every frame
   */
  public double quantizePeriod(double periodMs, Tempo.Division division) {
    return quantizePeriod(periodMs, division, DEFAULT_MIN_SCALE, DEFAULT_MAX_SCALE);
  }

  /**
   * Compute the rate scale factor that snaps a continuous repeating period
   * (a rolling cyclic drift with no single future event to phase-align —
   * palette rotation, plasma advance, etc.) to the tempo grid: the target is
   * the nearest whole multiple of the division period (period >= division)
   * or unit fraction of it (period &lt; division).
   *
   * The chosen grid target is <em>latched</em> and only re-quantized when the
   * scale needed to hold it leaves [minScale, maxScale], or when the
   * division period changes (BPM/division change) — hysteresis, so input
   * wobble (e.g. audio-modulated speed) near a rounding boundary cannot
   * flip-flop the rate between harmonics. This is why per-frame reapplication
   * is stable here while per-frame {@link #retime} of a rolling period would
   * jitter.
   *
   * Uses internal latch state: one quantizePeriod() stream per TempoLock
   * instance (crossed() state is independent; sharing one instance for one
   * quantize stream plus one gate is fine).
   *
   * @param periodMs Repeating period at the current rate, in ms; values
   *                 <= 0, NaN or Infinity return 1
   * @param division Tempo division whose grid the period should lock to
   * @param minScale Lower clamp on the returned scale, must be > 0
   * @param maxScale Upper clamp on the returned scale, >= minScale
   * @return Rate scale factor in [minScale, maxScale], never 0/NaN/Infinity;
   *         apply as {@code rate *= s} every frame
   */
  public double quantizePeriod(double periodMs, Tempo.Division division, double minScale, double maxScale) {
    final double divMs = divisionMs(division);
    if (!(divMs > 0) || !(periodMs > 0) || !Double.isFinite(periodMs)) {
      return 1;
    }
    // Sanitize the clamp so the result is always positive and ordered
    final double lo = (minScale > 1e-6) ? minScale : 1e-6;
    final double hi = (maxScale > lo) ? maxScale : lo;

    if (divMs != this.quantDivMs) {
      // BPM or division changed: the latched target is off the new grid
      this.quantDivMs = divMs;
      this.quantTargetMs = 0;
    }
    double s = (this.quantTargetMs > 0) ? periodMs / this.quantTargetMs : -1;
    if (!((s >= lo) && (s <= hi))) {
      // No target latched, or holding it needs a scale outside the window:
      // re-latch to the harmonic nearest the unscaled period
      this.quantTargetMs = (periodMs >= divMs) ?
        Math.rint(periodMs / divMs) * divMs :
        divMs / Math.rint(divMs / periodMs);
      s = periodMs / this.quantTargetMs;
    }
    // Final safety clamp: bounded, positive, finite
    return (s < lo) ? lo : (s > hi) ? hi : s;
  }

  /**
   * The transport's continuous absolute beat position: integer quarter-note
   * cycle count plus fractional phase. Monotonic while the transport runs;
   * resets if the engine tempo restarts, so callers holding absolute-beat
   * targets should treat a wildly stale target as a replan event. (Int cycle
   * wrap is ~2^31 quarter notes, decades at any musical BPM — ignored.)
   *
   * @return Absolute beat index, e.g. 133.25 = a quarter past beat 133
   */
  public double beatPosition() {
    return this.tempo.getCycleCount(Tempo.Division.QUARTER)
         + this.tempo.getBasis(Tempo.Division.QUARTER);
  }

  /**
   * Integer quarter-note cycle count, for anchoring beat grids.
   *
   * @return The transport's current whole-beat index
   */
  public int beatCycle() {
    return this.tempo.getCycleCount(Tempo.Division.QUARTER);
  }

  /**
   * Milliseconds per quarter-note beat at the current BPM.
   *
   * @return Beat period in ms
   */
  public double beatPeriodMs() {
    return this.tempo.period.getValue();
  }

  /**
   * Signed time until an absolute beat index at the current BPM; negative if
   * the beat is already past.
   *
   * @param absoluteBeat Absolute beat index (see {@link #beatPosition()})
   * @return Milliseconds until that beat, negative if past
   */
  public double msUntilBeat(double absoluteBeat) {
    return (absoluteBeat - beatPosition()) * beatPeriodMs();
  }

  /**
   * Per-frame boundary gate: returns true on exactly the frames where the
   * engine tempo crossed a boundary of the given division. Call once per
   * frame per TempoLock instance (use separate instances for independent
   * gates). The first call after construction, or after the division
   * argument changes, resynchronizes and returns false.
   *
   * <p>Call it <em>unconditionally</em> every frame, even while the result is
   * unused (e.g. the pattern's Sync toggle is off) — the gate only stays
   * current by being polled. A lapsed call pattern (e.g.
   * {@code syncOn && crossed(div)}) reports the first boundary that elapsed
   * during the lapse as "crossed" on the next call, firing one stale event
   * when Sync is re-enabled.
   *
   * @param division Tempo division to gate on
   * @return True if a boundary of the division was crossed this frame
   */
  public boolean crossed(Tempo.Division division) {
    final int count = this.tempo.getCycleCount(division);
    if (division != this.lastDivision) {
      this.lastDivision = division;
      this.lastCycleCount = count;
      return false;
    }
    final boolean crossed = (count != this.lastCycleCount);
    this.lastCycleCount = count;
    return crossed;
  }
}
