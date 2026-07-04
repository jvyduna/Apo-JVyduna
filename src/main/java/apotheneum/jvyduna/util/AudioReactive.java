package apotheneum.jvyduna.util;

import heronarts.lx.LX;
import heronarts.lx.audio.GraphicMeter;

/**
 * Composition helper providing smoothed FFT audio taps for patterns.
 *
 * Both ApotheneumPattern.run() and ApotheneumRasterPattern.render(double) are
 * final, so audio reactivity is provided by composition rather than
 * inheritance: construct one of these in the pattern constructor and call
 * {@link #tick(double)} as the first line of render().
 *
 * All state is primitive; no allocation occurs after construction. Values are
 * silence-safe: with no audio input everything decays to 0, hits never fire,
 * and no NaN/Infinity is ever produced. Patterns must look presentable with
 * all taps at 0.
 *
 * Band mapping over the default 16-band {@link GraphicMeter}:
 * bass = bands 0-1, mid = bands 4-7, treble = bands 8-15,
 * level = overall meter normalized level.
 *
 * The transient-detection recipe (ratios vs ~1.5s running averages,
 * retrigger-gated rising-edge hits) follows the proven TitanicsEnd
 * TEAudioPattern approach, computed directly from the GraphicMeter.
 */
public class AudioReactive {

  /** Attack time constant for instantaneous level smoothing (ms) */
  private static final double ATTACK_MS = 15;

  /** Release time constant for instantaneous level smoothing (ms) */
  private static final double RELEASE_MS = 250;

  /** Time constant of the running averages used for auto-gain ratios (ms) */
  private static final double RUNNING_AVG_MS = 1500;

  /** Floor on running averages so silence yields ratio ~0, not noise spikes */
  private static final double AVG_FLOOR = 0.02;

  /** Ratio clamp ceiling; typical musical values land in 0.2..3 */
  private static final double RATIO_MAX = 8;

  /** A hit requires the instantaneous level to exceed this multiple of the running average */
  private static final double HIT_THRESHOLD = 1.2;

  private final LX lx;
  private final GraphicMeter meter;

  /** Smoothed instantaneous levels in 0..1 */
  public double bass, mid, treble, level;

  /** Ratio of instantaneous level to its ~1.5s running average (0..8, ~1 = average) */
  public double bassRatio, trebleRatio, levelRatio;

  private double avgBass = AVG_FLOOR, avgTreble = AVG_FLOOR, avgLevel = AVG_FLOOR;
  private double lastRawBass, lastRawTreble;
  private boolean bassHit, trebleHit;
  private double msSinceBassHit = 1e9, msSinceTrebleHit = 1e9;
  private double retriggerMs = -1;

  public AudioReactive(LX lx) {
    this.lx = lx;
    this.meter = lx.engine.audio.meter;
  }

  /**
   * Update all taps. Call exactly once per frame, first line of render().
   */
  public void tick(double deltaMs) {
    final double rawBass = this.meter.getAverage(0, 2);
    final double rawMid = this.meter.getAverage(4, 4);
    final double rawTreble = this.meter.getAverage(8, 8);
    final double rawLevel = this.meter.getNormalized();

    this.bass = smooth(this.bass, rawBass, deltaMs);
    this.mid = smooth(this.mid, rawMid, deltaMs);
    this.treble = smooth(this.treble, rawTreble, deltaMs);
    this.level = smooth(this.level, rawLevel, deltaMs);

    final double avgAlpha = alpha(deltaMs, RUNNING_AVG_MS);
    this.avgBass += (rawBass - this.avgBass) * avgAlpha;
    this.avgTreble += (rawTreble - this.avgTreble) * avgAlpha;
    this.avgLevel += (rawLevel - this.avgLevel) * avgAlpha;

    this.bassRatio = ratio(rawBass, this.avgBass);
    this.trebleRatio = ratio(rawTreble, this.avgTreble);
    this.levelRatio = ratio(rawLevel, this.avgLevel);

    final double gate = (this.retriggerMs > 0) ?
      this.retriggerMs :
      // Default: 80% of a tempo eighth note must pass between hits
      .8 * this.lx.engine.tempo.period.getValue() / 2;

    this.bassHit = false;
    if ((rawBass > HIT_THRESHOLD * Math.max(this.avgBass, AVG_FLOOR))
        && (rawBass > this.lastRawBass)
        && (this.msSinceBassHit > gate)) {
      this.bassHit = true;
      this.msSinceBassHit = 0;
    }
    this.msSinceBassHit += deltaMs;
    this.lastRawBass = rawBass;

    this.trebleHit = false;
    if ((rawTreble > HIT_THRESHOLD * Math.max(this.avgTreble, AVG_FLOOR))
        && (rawTreble > this.lastRawTreble)
        && (this.msSinceTrebleHit > gate)) {
      this.trebleHit = true;
      this.msSinceTrebleHit = 0;
    }
    this.msSinceTrebleHit += deltaMs;
    this.lastRawTreble = rawTreble;
  }

  /** True on the frame of a rising bass transient (retrigger-gated) */
  public boolean bassHit() {
    return this.bassHit;
  }

  /** True on the frame of a rising treble transient (retrigger-gated) */
  public boolean trebleHit() {
    return this.trebleHit;
  }

  /**
   * Override the hit retrigger gate. Pass <=0 to restore the default
   * (80% of a tempo eighth note, tracking live BPM).
   */
  public AudioReactive setRetriggerMs(double ms) {
    this.retriggerMs = ms;
    return this;
  }

  /** Raw normalized band passthrough, i in [0, numBands) */
  public double band(int i) {
    return this.meter.getBand(i);
  }

  public int numBands() {
    return this.meter.numBands;
  }

  private static double smooth(double current, double target, double deltaMs) {
    final double tau = (target > current) ? ATTACK_MS : RELEASE_MS;
    return current + (target - current) * alpha(deltaMs, tau);
  }

  private static double alpha(double deltaMs, double tauMs) {
    return 1 - Math.exp(-deltaMs / tauMs);
  }

  private static double ratio(double raw, double avg) {
    final double r = raw / Math.max(avg, AVG_FLOOR);
    return (r > RATIO_MAX) ? RATIO_MAX : r;
  }
}
