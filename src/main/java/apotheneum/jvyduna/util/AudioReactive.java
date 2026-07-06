package apotheneum.jvyduna.util;

import heronarts.lx.LX;
import heronarts.lx.audio.GraphicMeter;
import heronarts.lx.parameter.LXParameter;

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
 *
 * <h2>Audio depth knob</h2>
 *
 * Every pattern in the series exposes a standard depth knob and attaches it
 * via {@link #setDepth(LXParameter)}:
 *
 * <pre>
 *   public final CompoundParameter audioDepth =
 *     new CompoundParameter("Audio", 0)
 *     .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");
 *   // constructor:
 *   this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
 *   addParameter("audio", this.audioDepth); // after pattern params, before sync/tempoDiv, Meta last
 * </pre>
 *
 * Depth semantics (consulted once per {@link #tick(double)}):
 * <ul>
 * <li>depth = 0: every public tap reads exactly its silence value — bass /
 *     mid / treble / level and all ratios are 0.0, bassHit()/trebleHit() are
 *     never true. The pattern behaves as if the room were silent.</li>
 * <li>depth = 1 (or no depth source attached): identical to the ungated
 *     behavior.</li>
 * <li>0 &lt; depth &lt; 1: magnitude taps (bass/mid/treble/level and the
 *     ratios) are scaled linearly by depth toward their silence value of 0.
 *     Hits are boolean events and carry no magnitude, so they fire normally
 *     whenever depth &gt; 0.01; patterns wanting a scaled hit response should
 *     multiply their reaction by {@link #depth()} or by a magnitude tap.</li>
 * </ul>
 *
 * The internal smoothers, running averages and hit/retrigger bookkeeping
 * always track the <em>real</em> signal regardless of depth, so raising the
 * knob mid-song immediately yields correctly-adapted values rather than
 * re-converging from silence.
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

  /** Hits are suppressed when the effective depth is at or below this */
  private static final double DEPTH_EPSILON = 0.01;

  private final LX lx;
  private final GraphicMeter meter;

  /** Smoothed instantaneous levels in 0..1, scaled by the attached depth */
  public double bass, mid, treble, level;

  /** Ratio of instantaneous level to its ~1.5s running average (0..8, ~1 =
   *  average), scaled by the attached depth */
  public double bassRatio, trebleRatio, levelRatio;

  /** Depth-independent smoothed levels; these keep tracking the real signal */
  private double smoothBass, smoothMid, smoothTreble, smoothLevel;

  private double avgBass = AVG_FLOOR, avgTreble = AVG_FLOOR, avgLevel = AVG_FLOOR;
  private double lastRawBass, lastRawTreble;
  private boolean bassHit, trebleHit;
  private double msSinceBassHit = 1e9, msSinceTrebleHit = 1e9;
  private double retriggerMs = -1;

  private LXParameter depthParameter = null;
  private double depth = 1;

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

    // Depth-independent smoothing and running averages: always track the
    // real signal so raising the depth knob mid-song lands on adapted values
    this.smoothBass = smooth(this.smoothBass, rawBass, deltaMs);
    this.smoothMid = smooth(this.smoothMid, rawMid, deltaMs);
    this.smoothTreble = smooth(this.smoothTreble, rawTreble, deltaMs);
    this.smoothLevel = smooth(this.smoothLevel, rawLevel, deltaMs);

    final double avgAlpha = alpha(deltaMs, RUNNING_AVG_MS);
    this.avgBass += (rawBass - this.avgBass) * avgAlpha;
    this.avgTreble += (rawTreble - this.avgTreble) * avgAlpha;
    this.avgLevel += (rawLevel - this.avgLevel) * avgAlpha;

    // Effective depth: 1 with no source attached; d == 1 is bit-identical
    // to ungated behavior, d == 0 pins every public tap to its silence value
    final double d = this.depth = (this.depthParameter == null) ? 1 :
      clamp01(this.depthParameter.getValue());

    this.bass = d * this.smoothBass;
    this.mid = d * this.smoothMid;
    this.treble = d * this.smoothTreble;
    this.level = d * this.smoothLevel;

    this.bassRatio = d * ratio(rawBass, this.avgBass);
    this.trebleRatio = d * ratio(rawTreble, this.avgTreble);
    this.levelRatio = d * ratio(rawLevel, this.avgLevel);

    final double gate = (this.retriggerMs > 0) ?
      this.retriggerMs :
      // Default: 80% of a tempo eighth note must pass between hits
      .8 * this.lx.engine.tempo.period.getValue() / 2;

    // Hit detection and retrigger bookkeeping run on the real signal at any
    // depth; the public flags are only masked when depth is effectively zero
    boolean hit = false;
    if ((rawBass > HIT_THRESHOLD * Math.max(this.avgBass, AVG_FLOOR))
        && (rawBass > this.lastRawBass)
        && (this.msSinceBassHit > gate)) {
      hit = true;
      this.msSinceBassHit = 0;
    }
    this.bassHit = hit && (d > DEPTH_EPSILON);
    this.msSinceBassHit += deltaMs;
    this.lastRawBass = rawBass;

    hit = false;
    if ((rawTreble > HIT_THRESHOLD * Math.max(this.avgTreble, AVG_FLOOR))
        && (rawTreble > this.lastRawTreble)
        && (this.msSinceTrebleHit > gate)) {
      hit = true;
      this.msSinceTrebleHit = 0;
    }
    this.trebleHit = hit && (d > DEPTH_EPSILON);
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

  /**
   * Attach a 0..1 depth source (typically the pattern's "Audio" knob),
   * consulted once per tick(). At 0 every public tap reads its silence
   * value and hits never fire; at 1 behavior is identical to having no
   * depth source. See the class javadoc for the full semantics.
   *
   * @param depth Depth parameter, or null to detach (equivalent to depth 1)
   */
  public AudioReactive setDepth(LXParameter depth) {
    this.depthParameter = depth;
    // Keep depth() coherent even before the first tick()
    this.depth = (depth == null) ? 1 : clamp01(depth.getValue());
    return this;
  }

  /**
   * Effective depth applied on the most recent tick(), clamped to 0..1
   * (1 if no depth source is attached). Useful for scaling hit responses.
   */
  public double depth() {
    return this.depth;
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

  private static double clamp01(double v) {
    // NB: written so NaN falls through to 0 (silence) rather than poisoning
    // every tap — (NaN < 0) and (NaN > 1) are both false in the naive form
    return (v >= 0) ? ((v > 1) ? 1 : v) : 0;
  }
}
