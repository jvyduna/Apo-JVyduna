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
 * The magnitude ratios (vs ~1.5s running averages) follow the proven
 * TitanicsEnd TEAudioPattern approach, computed directly from the
 * GraphicMeter.
 *
 * Hit detection is trough-referenced rather than average-referenced: a hit
 * fires when the (dB-normalized) band level rises {@link #HIT_RISE_DB} above
 * its recent minimum. With dense hits (eighth notes at 160 BPM = 187.5 ms
 * apart) the meter envelope only decays ~9 dB between onsets and the running
 * average parks within ~4 dB of the peaks, so any average-referenced
 * threshold starves; the trough reference keeps a reliable margin at any
 * playback level and hit density. Verified by simulation of the meter
 * dynamics (attack 10 ms / release 100 ms, 48 dB range): 100% detection of
 * eighth-note kick trains at 160-174 BPM across hot/quiet mixes, with and
 * without a sustained bassline, versus 10-50% for the previous
 * ratio-vs-average recipe.
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

  /** A hit requires the band level to rise this many dB above its recent
   *  trough. Band values are dB-normalized, so this is level-independent.
   *  Eighth notes at 160 BPM leave a ~9 dB peak-to-trough swing on the
   *  default meter; 3 dB keeps solid margin there while rejecting the
   *  1-2 dB wobble of sustained (non-transient) bass. */
  private static final double HIT_RISE_DB = 3.0;

  /** Trough tracker relax time constant: follows the signal down instantly,
   *  relaxes back up toward it with this tau. Long enough not to erode the
   *  rise margin between 187.5 ms hits, short enough to re-adapt within a
   *  couple seconds when the material jumps to a louder sustained level. */
  private static final double TROUGH_RELAX_MS = 500;

  /** Cap on the default tempo-derived retrigger gate, so eighth notes at
   *  160 BPM (187.5 ms apart) are never gated out even when the project
   *  tempo is slower than the material. */
  private static final double MAX_GATE_MS = 140;

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
  // Recent-minimum trackers for hit detection; start at 1 so activating the
  // helper mid-song doesn't fire a spurious hit on the first frame
  private double troughBass = 1, troughTreble = 1;
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
      // Default: 80% of a tempo eighth note between hits, capped so that
      // eighth notes at >= 160 BPM always clear the gate regardless of the
      // project tempo setting
      Math.min(.8 * this.lx.engine.tempo.period.getValue() / 2, MAX_GATE_MS);

    // Band values are dB-normalized against the meter range, so a fixed dB
    // rise is a fixed normalized delta
    final double rise = HIT_RISE_DB / this.meter.range.getValue();

    // Trough trackers: follow the signal down instantly, relax back up slowly
    final double troughAlpha = alpha(deltaMs, TROUGH_RELAX_MS);
    this.troughBass = (rawBass < this.troughBass) ? rawBass :
      this.troughBass + (rawBass - this.troughBass) * troughAlpha;
    this.troughTreble = (rawTreble < this.troughTreble) ? rawTreble :
      this.troughTreble + (rawTreble - this.troughTreble) * troughAlpha;

    // Hit detection and retrigger bookkeeping run on the real signal at any
    // depth; the public flags are only masked when depth is effectively zero.
    // A hit = rising edge that has climbed HIT_RISE_DB above the recent
    // trough, above the running-average floor (silence/noise guard), with
    // the retrigger gate expired.
    boolean hit = false;
    if ((rawBass > this.troughBass + rise)
        && (rawBass > Math.max(this.avgBass, AVG_FLOOR))
        && (rawBass > this.lastRawBass)
        && (this.msSinceBassHit > gate)) {
      hit = true;
      this.msSinceBassHit = 0;
    }
    this.bassHit = hit && (d > DEPTH_EPSILON);
    this.msSinceBassHit += deltaMs;
    this.lastRawBass = rawBass;

    hit = false;
    if ((rawTreble > this.troughTreble + rise)
        && (rawTreble > Math.max(this.avgTreble, AVG_FLOOR))
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
   * (80% of a tempo eighth note tracking live BPM, capped at 140 ms so
   * 160 BPM eighth-note trains always pass).
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
