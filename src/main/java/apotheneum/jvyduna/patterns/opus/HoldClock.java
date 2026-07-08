package apotheneum.jvyduna.patterns.opus;

import java.util.List;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PixelFont5;
import apotheneum.jvyduna.util.SurfaceCanvas;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * The deadpan hold-screen clock for "Opus No 1" (the Cisco hold music) — a
 * bureaucratic waiting-room clock that ticks ON THE BEAT, then slowly mutates
 * from an institutional analog/decimal face into a nerdy esoteric counter
 * (binary → hex → a base-N mystical dial), reiterating "you're on hold."
 *
 * The song choreography is driven from the composition (clip lanes on
 * {@link #phase}, {@link #clockMode}, {@link #shimmer}, {@link #freeze}) so the
 * pattern is the reusable spine and the .lxp holds the timing. Two committed
 * beats from the arc critique are wired to parameters, not buried in code:
 *
 * <ol>
 * <li><b>Crystal-staircase shimmer (0:40–0:50)</b> — the {@link #shimmer} knob
 *     is allowed to make this the BRIGHTEST thing in the whole song for ~10 s,
 *     an ascending cascade, then snaps back to deadpan. (Anti-sag insurance.)</li>
 * <li><b>"GOD PUT ME ON HOLD" button (~1:25)</b> — {@link #freeze} latches a
 *     single HELD hard-cut frame of the {@link #message} text (not a scroll),
 *     for a beat longer than comfortable, then the composition cuts to
 *     BLACKOUT — the doorway into `chrome`.</li>
 * </ol>
 *
 * Cold open: at song start the composition holds {@link Phase#COLD_OPEN} —
 * near-black with a lone blinking colon whose visibility DAWNS over ~10 s, so
 * recognition ("wait… is this the Cisco hold music?") lands quickly rather than
 * doubling down on darkness after `rafters`.
 *
 * Standalone (no composition driving it) the defaults are a good deadpan
 * screensaver: a decimal clock ticking at the quarter-note BPM period at a
 * muted phosphor brightness.
 *
 * See HoldClock.md (beside this file) for the full design note. All visual
 * subsystems are built: the bezel/tick/AA-hand analog face, the split-flap
 * flip face, the binary dot-grid, the base-N occult dial, the multi-strand
 * crystal-staircase shimmer cascade, the word-wrapped held message, the
 * never-resolving "estimated wait" line and the VHS button-glitch melt. The
 * remaining CURATE constants (dawn/brightness/sweep timings, mystic base) are
 * blind-picked and want a hardware pass.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("HoldClock")
@LXComponent.Description("Deadpan corporate hold-screen clock ticking on the beat, morphing decimal → binary → hex → mystical dial, with a crystal-staircase shimmer and a held \"GOD PUT ME ON HOLD\" button")
public class HoldClock extends ApotheneumPattern {

  // ---- Timing / look constants (CURATE: all tuned on hardware) ---------------

  /** Cold-open dawn: the blinking colon ramps from invisible to visible over
   *  this long, so recognition lands by ~0:10 without a long stare. */
  private static final double DAWN_MS = 10000; // CURATE: recognition timing

  /** Deadpan resting brightness multiplier (office phosphor, not lush). */
  private static final double BRIGHT_DEFAULT = 0.35; // CURATE

  /** Peak display multiplier the shimmer knob reaches at 1.0 — the brightest
   *  the whole song is permitted to get, per the anti-sag critique. */
  private static final double SHIMMER_PEAK_MULT = 1.0; // CURATE

  /** Display multiplier while the punchline frame is held (full, hard-cut). */
  private static final double PUNCH_MULT = 1.0; // CURATE

  /** One full ascending shimmer sweep takes this long — kept >= 5 s so the
   *  sustained rise respects the traversal cap. */
  private static final double SHIMMER_SWEEP_MS = 5000; // CURATE

  /** Button glitch/melt envelope duration (event-like, >= 1.5 s life). */
  private static final double GLITCH_MS = 1600; // CURATE

  /** Max pixel jitter applied to the clock on a bass kick at Audio depth 1. */
  private static final int JITTER_PX = 1; // CURATE

  /** Fallback phosphor when the palette swatch is empty: Gareth cyan
   *  (#33CCFF), the sun-faded 1989 corporate-CRT read from the brief. */
  private static final int FALLBACK_PHOSPHOR = 0xff33ccff; // CURATE

  /** Number of positions on the mystical base-N dial. */
  private static final int MYSTIC_BASE = 12; // CURATE

  /** Treads per staircase flight in the shimmer cascade. One flight spans the
   *  full canvas and scrolls up exactly one full height per SHIMMER_SWEEP_MS,
   *  so the sustained rise still honors the >= 5 s traversal cap. */
  private static final int SHIMMER_STEPS = 5; // CURATE

  /** Parallel ascending light strands offset across the staircase. */
  private static final int SHIMMER_STRANDS = 3; // CURATE

  /** Split-flap flap-fall envelope armed on each digit change (event-like). */
  private static final double FLIP_MS = 220; // CURATE

  /** Max stacked lines the held word-wrapped message can occupy. */
  private static final int MAX_MSG_LINES = 8;

  /** Largest integer glyph scale the held message tries before wrapping smaller. */
  private static final int MAX_MSG_SCALE = 4;

  // ---- Enums -----------------------------------------------------------------

  /** Top-level screen state; the composition drives this via a clip lane. */
  public enum Phase {
    COLD_OPEN("ColdOpen"), // near-black, blinking colon dawning in
    RUNNING("Running"),    // the clock is on screen and ticking
    BLACKOUT("Blackout");  // hard black (the 1:29 cut / any silence)

    public final String label;
    private Phase(String label) { this.label = label; }
    @Override public String toString() { return this.label; }
  }

  /** Clock face style; baseFlip walks this list to morph the counter. */
  public enum ClockMode {
    ANALOG("Analog"),   // institutional hands (bezel, ticks, AA hands)
    FLIP("Flip"),       // split-flap flip-clock digits
    DECIMAL("Decimal"), // HH:MM:SS phosphor digits
    BINARY("Binary"),   // binary BCD dot-grid counter
    HEX("Hex"),         // hexadecimal counter
    MYSTIC("Mystic");   // base-N occult dial

    public final String label;
    private ClockMode(String label) { this.label = label; }
    @Override public String toString() { return this.label; }
  }

  // ---- Composition helpers ---------------------------------------------------

  private final AudioReactive audio;

  // ---- Parameters (registration order per house style) -----------------------

  public final TriggerParameter tick =
    new TriggerParameter("Tick", this::tick)
    .setDescription("Advance the clock by one unit immediately (a manual on-beat tick)");

  public final TriggerParameter boot =
    new TriggerParameter("Boot", this::boot)
    .setDescription("The 0:19 reveal: leave the cold open and boot the hold-screen clock");

  public final TriggerParameter baseFlip =
    new TriggerParameter("BaseFlip", this::baseFlip)
    .setDescription("Advance the clock to the next (more esoteric) mode: analog → flip → decimal → binary → hex → mystic");

  public final TriggerParameter glitch =
    new TriggerParameter("Glitch", this::glitch)
    .setDescription("One-frame loop-glitch / VHS melt of the current frame (the button flourish)");

  public final EnumParameter<Phase> phase =
    new EnumParameter<Phase>("Phase", Phase.RUNNING)
    .setDescription("Screen state: ColdOpen (near-black blinking), Running (clock on the beat), Blackout (hard cut). Composition-driven; default Running for standalone use");

  public final EnumParameter<ClockMode> clockMode =
    new EnumParameter<ClockMode>("Mode", ClockMode.DECIMAL)
    .setDescription("Clock face style; the song morphs decimal → binary → hex → mystic across the outro");

  public final CompoundParameter brightness =
    new CompoundParameter("Bright", BRIGHT_DEFAULT, 0, 1)
    .setDescription("Deadpan resting display brightness (kept muted so `chrome` blooms by contrast right after)");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Rotates the palette-sampled phosphor hue (0 = pure project palette)");

  public final CompoundParameter shimmer =
    new CompoundParameter("Shimmer", 0, 0, 1)
    .setDescription("Crystal-staircase cascade intensity (committed beat 0:40–0:50): at 1.0 the brightest the whole song gets, then snap back to 0");

  public final BooleanParameter freeze =
    new BooleanParameter("Freeze", false)
    .setDescription("Latch the held \"GOD PUT ME ON HOLD\" punchline frame (committed beat ~1:25): a single hard-cut frame, clock frozen, until the composition cuts to Blackout");

  public final StringParameter message =
    new StringParameter("Msg", "")
    .setDescription("Held-frame / cold-open text (e.g. GOD PUT ME ON HOLD, PLEASE HOLD); driven by clip-lane string events");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 1.0)
    .setDescription("Motion blending + antialiasing: 0 = steppy/pixel-snapped/hard edges, 1 = smooth sub-pixel motion and antialiased forms");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure clock (default), 1 = subtle per-kick jitter");

  // ---- Surfaces (preallocated; zero-alloc render) ----------------------------

  private final SurfaceCanvas faceCanvas =
    new SurfaceCanvas(Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);        // 50x45
  private final SurfaceCanvas cylinderCanvas =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT); // 120x43

  /** Reusable glyph assembly buffer — no String allocation in render. */
  private final char[] displayBuf = new char[32];

  /** Binary-clock BCD columns (H10 H1 M10 M1 S10 S1); refilled each frame. */
  private final int[] bcd = new int[6];

  /** Word-wrap line char ranges into {@link #message}, filled per frame. */
  private final int[] lineStart = new int[MAX_MSG_LINES];
  private final int[] lineEndArr = new int[MAX_MSG_LINES];

  /** Scratch canvases for the button-glitch melt (read source while writing). */
  private final SurfaceCanvas faceScratch =
    new SurfaceCanvas(Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);        // 50x45
  private final SurfaceCanvas cylinderScratch =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT); // 120x43

  private static final SurfaceCanvas.Blend MAX = SurfaceCanvas.Blend.MAX;

  // ---- Mutable state ---------------------------------------------------------

  private int elapsedTicks = 0;      // the clock's counted units (ticks ≈ seconds)
  private boolean colonOn = true;    // blinks each tick
  private double freeRunMs = 0;      // free-running tick accumulator (BPM period)
  private double coldOpenMs = 0;     // time spent in COLD_OPEN, for the dawn ramp
  private double shimmerPhase = 0;   // 0..1 ascending-sweep position
  private double glitchMs = 0;       // remaining button-glitch envelope
  private int jitterX = 0;           // per-frame audio kick offset
  private double estWaitMs = 0;      // institutional "estimated wait" — only ever grows
  private double glitchPhase = 0;    // horizontal wobble phase for the melt
  private int glitchSeed = 0;        // per-frame jitter seed for the row tears
  private int lastFlipTick = 0;      // last elapsedTicks a flap was armed for
  private double flipMs = 0;         // split-flap flap-fall envelope
  private double smoothAA = 1.0;     // frame-cached Smooth: edge AA + eased transitions

  public HoldClock(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.clockMode.setWrappable(true); // baseFlip wraps mystic → analog

    // Triggers first
    addParameter("tick", this.tick);
    addParameter("boot", this.boot);
    addParameter("baseFlip", this.baseFlip);
    addParameter("glitch", this.glitch);
    // Pattern parameters
    addParameter("phase", this.phase);
    addParameter("clockMode", this.clockMode);
    addParameter("brightness", this.brightness);
    addParameter("hue", this.hue);
    addParameter("shimmer", this.shimmer);
    addParameter("freeze", this.freeze);
    addParameter("message", this.message);
    addParameter("smooth", this.smooth);
    // Audio
    addParameter("audio", this.audioDepth);
  }

  // ---- Trigger handlers ------------------------------------------------------

  /** Manual on-beat tick — advance one unit and toggle the colon blink. */
  private void tick() {
    this.elapsedTicks++;
    this.colonOn = !this.colonOn;
  }

  /** The 0:19 "system came online" reveal: leave the cold open. */
  private void boot() {
    this.phase.setValue(Phase.RUNNING);
  }

  /** Walk the clock one step down the esoteric ladder. */
  private void baseFlip() {
    final ClockMode[] modes = ClockMode.values();
    final int next = (this.clockMode.getEnum().ordinal() + 1) % modes.length;
    this.clockMode.setValue(modes[next]);
  }

  /** Arm the one-frame loop-glitch / melt envelope (the button flourish). */
  private void glitch() {
    this.glitchMs = GLITCH_MS;
  }

  // ---- Render ----------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    this.smoothAA = LXUtils.constrain(this.smooth.getValue(), 0, 1);
    setColors(LXColor.BLACK);

    advanceClock(deltaMs);

    // Cold-open dawn ramp + shimmer sweep + glitch envelope
    final Phase ph = this.phase.getEnum();
    this.coldOpenMs = (ph == Phase.COLD_OPEN) ? (this.coldOpenMs + deltaMs) : 0;
    this.shimmerPhase = (this.shimmerPhase + deltaMs / SHIMMER_SWEEP_MS) % 1.0;
    if (this.glitchMs > 0) {
      this.glitchMs = Math.max(0, this.glitchMs - deltaMs);
      this.glitchPhase += deltaMs * 0.02;
      this.glitchSeed++;
    }

    // Institutional "estimated wait" — counts UP forever while the clock runs,
    // never resolving (the on-hold gag). Frozen while the punchline is held.
    if (ph == Phase.RUNNING && !this.freeze.isOn()) {
      this.estWaitMs += deltaMs;
    }

    // Split-flap: arm a flap-fall whenever the counted value changes.
    if (this.elapsedTicks != this.lastFlipTick) {
      this.lastFlipTick = this.elapsedTicks;
      this.flipMs = FLIP_MS;
    }
    this.flipMs = Math.max(0, this.flipMs - deltaMs);

    // Subtle audio kick jitter (depth-scaled; 0 at Audio=0)
    this.jitterX = (int) Math.round(this.audio.bassPulseRaw * this.audio.depth() * JITTER_PX);

    // Overall display multiplier (brightness lives here so drawing stays full-color)
    final double mult = displayMult(ph);

    // Draw the same deadpan scene onto both surfaces (centered), then paint
    // exterior + interior on the cube (all faces) and the cylinder ring.
    drawScene(this.faceCanvas, this.faceScratch, ph);
    this.faceCanvas.copyTo(Apotheneum.cube.exterior.front, this.colors, mult, false);
    copyCubeFace(Apotheneum.cube.exterior.front); // replicate to all 8 faces

    drawScene(this.cylinderCanvas, this.cylinderScratch, ph);
    this.cylinderCanvas.copyTo(Apotheneum.cylinder.exterior, this.colors, mult, false);
    copyCylinderExterior();
  }

  /** Advance the counted clock and colon blink on a free-running BPM-period
   *  timer (it ticks at the musical rate without grid-phase locking). */
  private void advanceClock(double deltaMs) {
    if (this.freeze.isOn()) {
      return; // held button frame: the clock is frozen mid-tick
    }
    this.freeRunMs += deltaMs;
    final double period = Math.max(1, this.lx.engine.tempo.period.getValue());
    while (this.freeRunMs >= period) {
      this.freeRunMs -= period;
      this.elapsedTicks++;
      this.colonOn = !this.colonOn;
    }
  }

  /** Overall brightness multiplier for the current phase / committed beats. */
  private double displayMult(Phase ph) {
    if (this.freeze.isOn()) {
      return PUNCH_MULT;
    }
    switch (ph) {
      case BLACKOUT:
        return 0;
      case COLD_OPEN: {
        final double dawn = LXUtils.constrain(this.coldOpenMs / DAWN_MS, 0, 1);
        // Smooth eases the dawn ramp into a soft crossfade; at Smooth = 0 it is
        // the raw linear (harder-stepping) fade-in.
        return this.brightness.getValue() * eased(dawn); // near-black, dawning in
      }
      case RUNNING:
      default: {
        final double base = this.brightness.getValue();
        // Smooth eases the shimmer brightness excursion (soft rise/fall) rather
        // than a linear step; gated so Smooth = 0 keeps the raw ramp.
        final double sh = eased(this.shimmer.getValue());
        return base + sh * (SHIMMER_PEAK_MULT - base); // shimmer → brightest
      }
    }
  }

  // ---- Scene drawing ---------------------------------------------------------

  /** Draw the current scene, at full color, centered on the given canvas.
   *  Brightness is applied later at copy time via {@link #displayMult}. */
  private void drawScene(SurfaceCanvas c, SurfaceCanvas scratch, Phase ph) {
    c.fill(LXColor.BLACK);

    // The held punchline frame overrides everything (hard cut).
    if (this.freeze.isOn()) {
      drawMessage(c);
      return;
    }

    switch (ph) {
      case BLACKOUT:
        return; // stays black
      case COLD_OPEN:
        drawColdOpen(c);
        break;
      case RUNNING:
      default:
        drawClock(c);
        if (this.shimmer.getValue() > 0.01) {
          drawShimmer(c);
        }
        break;
    }

    // Button-glitch melt: the row-tear / phase-wobble / VHS drip flourish that
    // distorts the freshly drawn frame just before the composition cuts black.
    if (this.glitchMs > 0) {
      applyGlitch(c, scratch);
    }
  }

  /** Cold open: a lone blinking colon in the center, brightness dawning via
   *  the copy-time multiplier. If a message is set, show that instead (e.g. a
   *  faint PLEASE HOLD). */
  private void drawColdOpen(SurfaceCanvas c) {
    final int color = phosphorColor();
    if (this.message.getString() != null && !this.message.getString().isEmpty()) {
      drawMessage(c);
      return;
    }
    if (this.colonOn) {
      this.displayBuf[0] = ':';
      blitCentered(c, 1, color);
    }
  }

  /** The clock proper, dispatched by mode. */
  private void drawClock(SurfaceCanvas c) {
    final int color = phosphorColor();
    switch (this.clockMode.getEnum()) {
      case ANALOG:
        drawAnalog(c, color);
        break;
      case FLIP:
        drawFlip(c, color);
        break;
      case DECIMAL:
        blitCentered(c, writeTime(this.displayBuf, this.colonOn), color);
        break;
      case BINARY:
        drawBinary(c, color);
        break;
      case HEX:
        blitCentered(c, writeHex(this.displayBuf, this.elapsedTicks, 4), color);
        break;
      case MYSTIC:
      default:
        drawMystic(c, color);
        break;
    }
    // The never-resolving "estimated wait", counting UP under the clock.
    drawEstimatedWait(c, color);
  }

  /** The institutional-hold gag: an "EST WAIT MM:SS" status line under the
   *  clock whose time only ever grows, never resolving. Falls back to a bare
   *  MM:SS when the labelled form will not fit the surface width. */
  private void drawEstimatedWait(SurfaceCanvas c, int color) {
    final int cellW = PixelFont5.WIDTH + 1;
    final int labelLen = writeEstWait(this.displayBuf, true);
    final int fitLen = ((labelLen * cellW - 1) <= (c.width - 2))
      ? labelLen
      : writeEstWait(this.displayBuf, false);
    final int dim = scaleColor(color, 0.55); // a quieter, secondary read
    final int y0 = c.height - PixelFont5.HEIGHT - 1; // pinned to the bottom
    blitText(c, fitLen, 1, y0, dim);
  }

  /**
   * Blit the message word-wrapped across multiple stacked lines, filling the
   * frame as one held hard-cut. Picks the largest glyph scale at which the
   * greedy word-wrap fits vertically, so "GOD PUT ME ON HOLD" stacks and reads
   * big rather than clipping as a single centered line.
   */
  private void drawMessage(SurfaceCanvas c) {
    final String m = this.message.getString();
    if (m == null || m.isEmpty()) {
      return;
    }
    // Choose the largest scale whose wrapped layout fits the canvas height.
    int scale = 1;
    int nLines = 1;
    for (int s = MAX_MSG_SCALE; s >= 1; --s) {
      final int cellW = (PixelFont5.WIDTH + 1) * s;
      final int cpl = Math.max(1, (c.width - 2) / cellW);
      final int lines = wrapLines(m, cpl);
      final int gap = Math.max(1, s);
      final int totalH = lines * (PixelFont5.HEIGHT * s + gap) - gap;
      if ((totalH <= c.height - 1 && lines <= MAX_MSG_LINES) || s == 1) {
        scale = s;
        nLines = lines;
        break; // wrapLines already left the chosen wrap in the line arrays
      }
    }

    final int color = phosphorColor();
    final int gap = Math.max(1, scale);
    final int lineH = PixelFont5.HEIGHT * scale + gap;
    final int totalH = nLines * lineH - gap;
    final int yTop = (c.height - totalH) / 2;
    for (int k = 0; k < nLines; ++k) {
      int len = 0;
      for (int j = this.lineStart[k]; j < this.lineEndArr[k] && len < this.displayBuf.length; ++j) {
        this.displayBuf[len++] = m.charAt(j);
      }
      blitText(c, len, scale, yTop + k * lineH, color);
    }
  }

  /**
   * Greedy word-wrap of {@code m} into at most {@link #MAX_MSG_LINES} lines of
   * up to {@code cpl} characters, writing each line's char range into
   * {@link #lineStart}/{@link #lineEndArr}. Words longer than a line are hard
   * broken. Zero-alloc: reads the string in place, writes only the pre-sized
   * line-range fields. Returns the line count (>= 1).
   */
  private int wrapLines(String m, int cpl) {
    final int n = m.length();
    int lines = 0;
    int i = 0;
    while (i < n && lines < MAX_MSG_LINES) {
      while (i < n && m.charAt(i) == ' ') {
        ++i; // skip leading spaces
      }
      if (i >= n) {
        break;
      }
      final int lineBeg = i;
      int lineEnd = i;
      while (i < n) {
        final int ws = i;
        while (i < n && m.charAt(i) != ' ') {
          ++i; // consume a word
        }
        int we = i;
        if (ws == lineBeg && (we - ws) > cpl) {
          we = lineBeg + cpl; // hard-break an over-long first word
          i = we;
          lineEnd = we;
          break;
        }
        if (we - lineBeg <= cpl) {
          lineEnd = we; // the word fits on this line
          if (i < n && m.charAt(i) == ' ') {
            ++i; // eat the single separating space
          }
        } else {
          i = ws; // rewind: the word starts the next line
          break;
        }
      }
      this.lineStart[lines] = lineBeg;
      this.lineEndArr[lines] = lineEnd;
      ++lines;
    }
    return Math.max(1, lines);
  }

  /** Institutional analog face: a bezel ring, 12 tick marks and anti-aliased
   *  hour/minute/second hands with a smooth (sub-tick) second sweep. */
  private void drawAnalog(SurfaceCanvas c, int color) {
    final double cx = c.width / 2.0 + this.jitterX;
    final double cy = c.height / 2.0;
    final double r = Math.min(cx, c.height / 2.0) - 1;
    if (r < 3) {
      return;
    }
    final int dim = scaleColor(color, 0.22);

    // Bezel ring
    double px = cx + r, py = cy;
    final int seg = 96;
    for (int i = 1; i <= seg; ++i) {
      final double a = 2 * Math.PI * i / seg;
      final double x = cx + Math.cos(a) * r;
      final double y = cy + Math.sin(a) * r;
      smoothLine(c, px, py, x, y, dim, false);
      px = x;
      py = y;
    }

    // 12 tick marks (the quarters read brighter)
    for (int k = 0; k < 12; ++k) {
      final double a = 2 * Math.PI * k / 12 - Math.PI / 2;
      final double inner = (k % 3 == 0) ? r * 0.78 : r * 0.86;
      final int tc = (k % 3 == 0) ? color : scaleColor(color, 0.6);
      smoothLine(c, cx + Math.cos(a) * inner, cy + Math.sin(a) * inner,
        cx + Math.cos(a) * r, cy + Math.sin(a) * r, tc, false);
    }

    // Hands from a continuous (smoothly-swept) tick position
    final double t = smoothTicks();
    final double hourFrac = (t / 3600.0) % 12 / 12.0;
    final double minFrac = (t / 60.0) % 60 / 60.0;
    final double secFrac = (t % 60) / 60.0;
    drawHand(c, cx, cy, hourFrac, r * 0.5, color, 3);
    drawHand(c, cx, cy, minFrac, r * 0.78, color, 2);
    drawHand(c, cx, cy, secFrac, r * 0.9, scaleColor(color, 0.85), 1);
    stampDisc(c, (int) Math.round(cx), (int) Math.round(cy), 1, color);
  }

  /** Split-flap flip-clock face: each digit sits on a dim card with a central
   *  flap seam, and the changing (rightmost) card animates a flap fall. */
  private void drawFlip(SurfaceCanvas c, int color) {
    final int len = writeTime(this.displayBuf, this.colonOn);
    final int cellW = PixelFont5.WIDTH + 1;
    final int textW = len * cellW - 1;
    final int scale = Math.max(1, Math.min(
      (c.width - 2) / Math.max(1, textW),
      (c.height - 2) / PixelFont5.HEIGHT));
    final int totalW = textW * scale;
    final int x0 = (c.width - totalW) / 2 + this.jitterX;
    final int y0 = (c.height - PixelFont5.HEIGHT * scale) / 2;

    final int card = scaleColor(color, 0.14);
    final int seam = 0xff000000; // the split-flap hinge line
    final int seamMid = y0 + (PixelFont5.HEIGHT * scale) / 2;
    final int padY = Math.max(1, scale);
    final int cardTop = y0 - padY;
    final int cardH = PixelFont5.HEIGHT * scale + 2 * padY;
    final double flip = (this.flipMs > 0) ? this.flipMs / FLIP_MS : 0; // 1..0

    for (int ci = 0; ci < len; ++ci) {
      final char ch = this.displayBuf[ci];
      final int gx0 = x0 + ci * cellW * scale;
      final boolean isDigit = (ch >= '0' && ch <= '9');
      final int cardW = PixelFont5.WIDTH * scale;
      if (isDigit) {
        // Card background
        for (int yy = cardTop; yy < cardTop + cardH; ++yy) {
          for (int xx = 0; xx < cardW; ++xx) {
            c.setMax(gx0 + xx, yy, card);
          }
        }
      }
      // Glyph
      for (int gy = 0; gy < PixelFont5.HEIGHT; ++gy) {
        for (int gx = 0; gx < PixelFont5.WIDTH; ++gx) {
          if (PixelFont5.pixel(ch, gx, gy)) {
            fillBlock(c, gx0 + gx * scale, y0 + gy * scale, scale, color);
          }
        }
      }
      if (isDigit) {
        // Central flap seam
        for (int xx = 0; xx < cardW; ++xx) {
          c.set(gx0 + xx, seamMid, seam);
        }
        // The two seconds cards flap-fall on a fresh tick
        if (flip > 0 && ci >= len - 2) {
          // Smooth eases the flap fall into a smooth drop; raw/stepped at 0.
          final int edge = cardTop + (int) Math.round((1 - eased(flip)) * cardH);
          final int hl = scaleColor(color, 0.7);
          for (int xx = 0; xx < cardW; ++xx) {
            c.setMax(gx0 + xx, edge, hl);
          }
        }
      }
    }
  }

  /** A proper binary DOT-GRID clock: six BCD columns (H10 H1 M10 M1 S10 S1),
   *  four bits each (MSB on top), lit dots in phosphor over dim placeholders. */
  private void drawBinary(SurfaceCanvas c, int color) {
    final int s = Math.floorMod(this.elapsedTicks, 360000);
    final int hh = (s / 3600) % 100;
    final int mm = (s / 60) % 60;
    final int ss = s % 60;
    this.bcd[0] = (hh / 10) % 10;
    this.bcd[1] = hh % 10;
    this.bcd[2] = (mm / 10) % 6;
    this.bcd[3] = mm % 10;
    this.bcd[4] = (ss / 10) % 6;
    this.bcd[5] = ss % 10;

    final int cols = 6;
    final int rows = 4;
    final double spanX = Math.min(c.width - 4, c.width * 0.8);
    final double spanY = Math.min(c.height - 4, c.height * 0.7);
    final double dx = spanX / cols;
    final double dy = spanY / rows;
    final int radius = Math.max(1, (int) Math.round(Math.min(dx, dy) / 2 - 0.6));
    final double x0 = (c.width - dx * (cols - 1)) / 2.0 + this.jitterX;
    final double y0 = (c.height - dy * (rows - 1)) / 2.0;
    final int off = scaleColor(color, 0.12);

    for (int col = 0; col < cols; ++col) {
      final int v = this.bcd[col];
      final int cxp = (int) Math.round(x0 + col * dx);
      for (int b = 0; b < rows; ++b) {
        final int cyp = (int) Math.round(y0 + b * dy);
        final boolean lit = ((v >> (rows - 1 - b)) & 1) != 0;
        stampDisc(c, cxp, cyp, radius, lit ? color : off);
      }
    }
  }

  /** A base-N (MYSTIC_BASE) occult dial: a bezel ring with an esoteric glyph
   *  mark at each position, a smoothly rotating pointer to the active position,
   *  and a slow counter-rotating inner ring for the arcane read. */
  private void drawMystic(SurfaceCanvas c, int color) {
    final double cx = c.width / 2.0 + this.jitterX;
    final double cy = c.height / 2.0;
    final double r = Math.min(cx, c.height / 2.0) - 1;
    if (r < 3) {
      return;
    }
    final int dim = scaleColor(color, 0.25);
    final double t = smoothTicks();
    final int active = Math.floorMod(this.elapsedTicks, MYSTIC_BASE);

    // Outer bezel ring
    double px = cx + r, py = cy;
    final int seg = 96;
    for (int i = 1; i <= seg; ++i) {
      final double a = 2 * Math.PI * i / seg;
      final double x = cx + Math.cos(a) * r;
      final double y = cy + Math.sin(a) * r;
      smoothLine(c, px, py, x, y, dim, false);
      px = x;
      py = y;
    }

    // Esoteric glyph marks around the outer ring
    final double gr = r * 0.86;
    final double markSize = Math.max(1.4, r * 0.11);
    for (int k = 0; k < MYSTIC_BASE; ++k) {
      final double a = 2 * Math.PI * k / MYSTIC_BASE - Math.PI / 2;
      final double mx = cx + Math.cos(a) * gr;
      final double my = cy + Math.sin(a) * gr;
      final int mc = (k == active) ? color : scaleColor(color, 0.45);
      drawGlyphMark(c, mx, my, a, k % 4, (k == active) ? markSize * 1.3 : markSize, mc);
    }

    // Counter-rotating inner sigil ring (arcane flavor)
    final double ir = r * 0.45;
    final double spin = -t * (2 * Math.PI / MYSTIC_BASE) * 0.5;
    for (int k = 0; k < MYSTIC_BASE; ++k) {
      final double a = 2 * Math.PI * k / MYSTIC_BASE + spin;
      c.setMax((int) Math.round(cx + Math.cos(a) * ir),
        (int) Math.round(cy + Math.sin(a) * ir), scaleColor(color, 0.4));
    }

    // Smoothly rotating pointer to the active position
    final double pf = (t % MYSTIC_BASE) / MYSTIC_BASE;
    drawHand(c, cx, cy, pf, r * 0.72, color, 2);
    stampDisc(c, (int) Math.round(cx), (int) Math.round(cy), 1, color);
  }

  /**
   * The crystal-staircase cascade (committed anti-sag beat): a seamless
   * repeating staircase of light — several parallel ascending strands — that
   * scrolls up exactly one full canvas height per SHIMMER_SWEEP_MS, so its
   * sustained rise sits at the >= 5 s traversal cap. Drawn bright/whitened with
   * MAX blending; the copy-time multiplier makes it the brightest ~10 s of the
   * song. A moving highlight band rides up the flight for the "prince dancing
   * up a crystal staircase" bloom.
   */
  private void drawShimmer(SurfaceCanvas c) {
    final int w = c.width;
    final int h = c.height;
    final int base = phosphorColor();
    final int bright = whiten(base, 0.75);
    final double stepH = h / (double) SHIMMER_STEPS;
    final double treadW = w / (double) SHIMMER_STEPS;
    final double scroll = this.shimmerPhase * h; // rises one full height per sweep

    // A highlight band (the "wave of bliss") ascending at the same rate.
    final double hiRow = (1 - this.shimmerPhase) * (h - 1);

    for (int strand = 0; strand < SHIMMER_STRANDS; ++strand) {
      // Offset each strand horizontally and vertically for parallel climbers.
      final double xOff = strand * (treadW / SHIMMER_STRANDS);
      final double vOff = strand * (stepH / SHIMMER_STRANDS);
      final double sb = 1 - 0.22 * strand; // trailing strands slightly dimmer
      for (int k = -SHIMMER_STEPS - 1; k <= 2 * SHIMMER_STEPS + 1; ++k) {
        final double rk = (h - 1) - k * stepH - scroll + vOff;
        final double rNext = rk - stepH;
        if (rk < -stepH && rNext < -stepH) {
          continue;
        }
        if (rk > h + stepH && rNext > h + stepH) {
          continue;
        }
        final double xk = k * treadW + xOff;
        // Proximity to the ascending highlight band brightens this tread. The
        // falloff is eased by Smooth so the "wave of bliss" crossfades smoothly
        // (Smooth = 1) rather than stepping (Smooth = 0).
        final double d = Math.abs(rk - hiRow) / (h * 0.5 + 1);
        final double hi = eased(Math.max(0, 1 - d));
        final int treadArgb = scaleColor(bright, sb * (0.5 + 0.5 * hi));
        // Tread (horizontal) then riser (vertical) — the staircase profile.
        smoothLine(c, xk, rk, xk + treadW, rk, treadArgb, true);
        smoothLine(c, xk + treadW, rk, xk + treadW, rNext, scaleColor(treadArgb, 0.8), true);
        // A crystalline glint at the leading corner of the tread.
        if (hi > 0.35) {
          stampDisc(c, (int) Math.round(xk + treadW), (int) Math.round(rk), 1,
            scaleColor(bright, sb));
        }
      }
    }
  }

  // ---- Smooth (house AA / interpolation convention) --------------------------

  /**
   * Draw a fractional line whose edge antialiasing is gated by the Smooth knob
   * ({@link #smoothAA}). At Smooth = 1 it is a full Xiaolin-Wu antialiased line
   * (soft sub-pixel edges); at 0 it collapses to a hard, pixel-snapped integer
   * line (steppy edges); between, the AA fringe fades in with Smooth while a
   * hard core keeps the form crisp. Zero-alloc: no per-call allocation, and the
   * default (Smooth = 1) takes the single pure-Wu path with no overhead.
   */
  private void smoothLine(SurfaceCanvas c, double x0, double y0, double x1, double y1,
                          int argb, boolean wrapX) {
    final double s = this.smoothAA;
    if (s >= 0.999) {
      c.lineWu(x0, y0, x1, y1, argb, wrapX, MAX); // full AA (default)
      return;
    }
    // Hard, pixel-snapped core so Smooth = 0 reads steppy. lineMax wraps x
    // (floorMod); the clock forms sit centered within the canvas so no fringe
    // wraps onto the opposite edge here. CURATE: revisit if any smoothLine
    // caller ever draws right up to a face seam.
    c.lineMax((int) Math.round(x0), (int) Math.round(y0),
      (int) Math.round(x1), (int) Math.round(y1), argb);
    if (s > 0) {
      // Layer the AA fringe on top, its coverage scaled by Smooth so soft
      // edges fade in continuously (MAX blend keeps the hard core intact).
      c.lineWu(x0, y0, x1, y1, scaleColor(argb, s), wrapX, MAX);
    }
  }

  /** Classic smoothstep ease (identity at the ends), for eased transitions. */
  private static double smoothstep(double t) {
    if (t <= 0) {
      return 0;
    }
    if (t >= 1) {
      return 1;
    }
    return t * t * (3 - 2 * t);
  }

  /** Blend a linear ramp {@code t} toward its smoothstep-eased form by the
   *  Smooth knob: at Smooth = 0 the raw (hard/steppy) ramp, at 1 a fully eased
   *  crossfade — so brightness / shimmer / flap transitions gate on Smooth. */
  private double eased(double t) {
    return LXUtils.lerp(t, smoothstep(t), this.smoothAA);
  }

  /** Draw one clock hand from center at a fraction (0 = 12 o'clock, clockwise),
   *  anti-aliased via lineWu, thickened by drawing perpendicular offsets. */
  private void drawHand(SurfaceCanvas c, double cx, double cy, double frac,
                        double len, int argb, double thick) {
    final double a = 2 * Math.PI * frac - Math.PI / 2;
    final double ex = cx + Math.cos(a) * len;
    final double ey = cy + Math.sin(a) * len;
    if (thick <= 1) {
      smoothLine(c, cx, cy, ex, ey, argb, false);
      return;
    }
    final double perpx = -Math.sin(a);
    final double perpy = Math.cos(a);
    final double half = (thick - 1) / 2.0;
    for (double o = -half; o <= half + 1e-6; o += 1) {
      smoothLine(c, cx + perpx * o, cy + perpy * o, ex + perpx * o, ey + perpy * o, argb, false);
    }
  }

  /** Draw one esoteric glyph mark at a dial position (shape cycles 0..3). */
  private void drawGlyphMark(SurfaceCanvas c, double mx, double my, double angle,
                             int shape, double size, int argb) {
    final double rad = angle; // radial direction outward
    final double dxr = Math.cos(rad), dyr = Math.sin(rad);
    final double dxt = -Math.sin(rad), dyt = Math.cos(rad); // tangential
    switch (shape) {
      case 0: // dot
        stampDisc(c, (int) Math.round(mx), (int) Math.round(my),
          Math.max(1, (int) Math.round(size * 0.5)), argb);
        break;
      case 1: // radial dash
        smoothLine(c, mx - dxr * size, my - dyr * size, mx + dxr * size, my + dyr * size, argb, false);
        break;
      case 2: // cross (X)
        smoothLine(c, mx - dxr * size, my - dyr * size, mx + dxr * size, my + dyr * size, argb, false);
        smoothLine(c, mx - dxt * size, my - dyt * size, mx + dxt * size, my + dyt * size, argb, false);
        break;
      default: { // caret / open triangle facing outward
        final double bx = mx - dxr * size, by = my - dyr * size;
        smoothLine(c, bx - dxt * size, by - dyt * size, mx + dxr * size * 0.4, my + dyr * size * 0.4, argb, false);
        smoothLine(c, bx + dxt * size, by + dyt * size, mx + dxr * size * 0.4, my + dyr * size * 0.4, argb, false);
        break;
      }
    }
  }

  /** Small filled disc, max-blended; x wraps, out-of-range y dropped. */
  private void stampDisc(SurfaceCanvas c, int px, int py, int r, int argb) {
    if (r <= 0) {
      c.setMax(px, py, argb);
      return;
    }
    for (int dy = -r; dy <= r; ++dy) {
      final int dxMax = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
      for (int dx = -dxMax; dx <= dxMax; ++dx) {
        c.setMax(px + dx, py + dy, argb);
      }
    }
  }

  /**
   * The button-glitch melt: a VHS-style distortion of the freshly drawn frame.
   * Copies the frame to {@code scratch}, then rewrites each pixel from a
   * horizontally phase-wobbled + row-torn, vertically dripping ("melting")
   * source — the flourish before the composition cuts to black. Zero-alloc:
   * the scratch canvas is preallocated. Intensity follows the glitch envelope.
   */
  private void applyGlitch(SurfaceCanvas c, SurfaceCanvas scratch) {
    scratch.copyFrom(c);
    final int w = c.width;
    final int h = c.height;
    final double p = LXUtils.constrain(this.glitchMs / GLITCH_MS, 0, 1); // 1..0
    final double wobbleAmp = (0.4 + 0.6 * p) * w * 0.16;
    final double meltMax = (1 - p) * h * 0.55; // drips grow as the button fades
    for (int y = 0; y < h; ++y) {
      // Horizontal phase wobble plus an occasional torn band.
      int hShift = (int) Math.round(wobbleAmp * Math.sin(y * 0.5 + this.glitchPhase));
      final int band = hash((y >> 1) ^ this.glitchSeed);
      if ((band & 7) == 0) {
        hShift += (band >> 3) % Math.max(1, w);
      }
      for (int x = 0; x < w; ++x) {
        // Per-column drip: reads from a row above, revealing black as it melts.
        final int drip = (int) Math.round(meltMax * (0.35 + 0.65 * frac01(hash(x * 2654435761L))));
        c.set(x, y, scratch.get(x - hShift, y - drip));
      }
    }
  }

  // ---- Glyph rasterization (zero-alloc) --------------------------------------

  /** Blit displayBuf[0..len) centered on the canvas at the largest integer
   *  scale that fits, plus the audio jitter offset. X is clamped (never wraps)
   *  so overlong text clips at the edge rather than wrapping around. */
  private void blitCentered(SurfaceCanvas c, int len, int color) {
    if (len <= 0) {
      return;
    }
    final int cellW = PixelFont5.WIDTH + 1;
    final int textW = len * cellW - 1;
    final int scale = Math.max(1, Math.min(
      (c.width - 2) / Math.max(1, textW),
      (c.height - 2) / PixelFont5.HEIGHT));
    final int y0 = (c.height - PixelFont5.HEIGHT * scale) / 2;
    blitText(c, len, scale, y0, color);
  }

  /** Blit displayBuf[0..len) horizontally centered at the given integer scale
   *  with its top row at y0 (plus the audio jitter offset). X is clamped so
   *  overlong text clips at the edge rather than wrapping around. */
  private void blitText(SurfaceCanvas c, int len, int scale, int y0, int color) {
    if (len <= 0) {
      return;
    }
    final int cellW = PixelFont5.WIDTH + 1;
    final int totalW = (len * cellW - 1) * scale;
    final int x0 = (c.width - totalW) / 2 + this.jitterX;
    for (int ci = 0; ci < len; ++ci) {
      final char ch = this.displayBuf[ci];
      final int gx0 = x0 + ci * cellW * scale;
      for (int gy = 0; gy < PixelFont5.HEIGHT; ++gy) {
        for (int gx = 0; gx < PixelFont5.WIDTH; ++gx) {
          if (PixelFont5.pixel(ch, gx, gy)) {
            fillBlock(c, gx0 + gx * scale, y0 + gy * scale, scale, color);
          }
        }
      }
    }
  }

  /** Fill a scale×scale block; x is guarded to the canvas so text never wraps. */
  private static void fillBlock(SurfaceCanvas c, int px, int py, int s, int color) {
    for (int dy = 0; dy < s; ++dy) {
      for (int dx = 0; dx < s; ++dx) {
        final int x = px + dx;
        if (x >= 0 && x < c.width) {
          c.set(x, py + dy, color); // out-of-range y is dropped by set()
        }
      }
    }
  }

  /** Write HH:MM:SS from elapsedTicks (ticks treated as seconds). */
  private int writeTime(char[] buf, boolean colon) {
    // CURATE: ticks display as seconds 1:1; map to real wall-clock elapsed if
    // the song wants that instead (see HoldClock.md CURATE notes).
    final int s = Math.floorMod(this.elapsedTicks, 360000); // wrap at 100h
    final int hh = (s / 3600) % 100;
    final int mm = (s / 60) % 60;
    final int ss = s % 60;
    int i = two(buf, 0, hh);
    buf[i++] = colon ? ':' : ' ';
    i = two(buf, i, mm);
    buf[i++] = colon ? ':' : ' ';
    i = two(buf, i, ss);
    return i;
  }

  private static int two(char[] buf, int i, int v) {
    buf[i++] = (char) ('0' + (v / 10) % 10);
    buf[i++] = (char) ('0' + v % 10);
    return i;
  }

  /** Write the ever-growing "estimated wait" (MM:SS), optionally labelled. */
  private int writeEstWait(char[] buf, boolean labelled) {
    final int total = (int) (this.estWaitMs / 1000);
    final int mm = (total / 60) % 100;
    final int ss = total % 60;
    int i = 0;
    if (labelled) {
      for (int k = 0; k < 8; ++k) {
        buf[i++] = "EST WAIT".charAt(k);
      }
      buf[i++] = ' ';
    }
    i = two(buf, i, mm);
    buf[i++] = ':';
    i = two(buf, i, ss);
    return i;
  }

  /** The continuous (fractional) tick position, for smooth analog/dial sweep:
   *  the integer counted tick plus progress toward the next tick. */
  private double smoothTicks() {
    return this.elapsedTicks + interTickFraction();
  }

  /** Progress in [0, 1) from the last tick toward the next, from the
   *  free-running BPM accumulator. */
  private double interTickFraction() {
    if (this.freeze.isOn()) {
      return 0;
    }
    final double period = Math.max(1, this.lx.engine.tempo.period.getValue());
    return LXUtils.constrain(this.freeRunMs / period, 0, 1);
  }

  /** Fixed-width binary (no allocation, no reversal). */
  private int writeBinary(char[] buf, int value, int bits) {
    int i = 0;
    for (int b = bits - 1; b >= 0; --b) {
      buf[i++] = ((value >> b) & 1) != 0 ? '1' : '0';
    }
    return i;
  }

  /** Fixed-width uppercase hex (no allocation). */
  private int writeHex(char[] buf, int value, int nibbles) {
    int i = 0;
    for (int shift = (nibbles - 1) * 4; shift >= 0; shift -= 4) {
      final int nib = (value >> shift) & 0xf;
      buf[i++] = (char) (nib < 10 ? ('0' + nib) : ('A' + nib - 10));
    }
    return i;
  }

  // ---- Color -----------------------------------------------------------------

  /** Phosphor color: the first project-palette swatch color rotated by Hue,
   *  or the Gareth-cyan fallback when the swatch is empty. Cheap enough to
   *  compute per call (one color; no per-frame rebuild needed). */
  private int phosphorColor() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    int base = swatch.isEmpty() ? FALLBACK_PHOSPHOR : swatch.get(0).getColor();
    final double h = this.hue.getValue();
    if (h != 0) {
      base = LXColor.hsb(LXColor.h(base) + h, LXColor.s(base), LXColor.b(base));
    }
    return base;
  }

  private static int scaleColor(int argb, double m) {
    if (m <= 0) {
      return 0xff000000;
    }
    if (m > 1) {
      m = 1;
    }
    final int r = (int) (((argb >> 16) & 0xff) * m);
    final int g = (int) (((argb >> 8) & 0xff) * m);
    final int b = (int) ((argb & 0xff) * m);
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  /** Blend a color toward white by fraction f (0 = unchanged, 1 = white). */
  private static int whiten(int argb, double f) {
    final int r = (argb >> 16) & 0xff;
    final int g = (argb >> 8) & 0xff;
    final int b = argb & 0xff;
    final int wr = (int) (r + (255 - r) * f);
    final int wg = (int) (g + (255 - g) * f);
    final int wb = (int) (b + (255 - b) * f);
    return 0xff000000 | (wr << 16) | (wg << 8) | wb;
  }

  /** Cheap deterministic 32-bit integer hash (for zero-alloc glitch noise). */
  private static int hash(int x) {
    x ^= x >>> 16;
    x *= 0x7feb352d;
    x ^= x >>> 15;
    x *= 0x846ca68b;
    x ^= x >>> 16;
    return x;
  }

  private static int hash(long x) {
    return hash((int) (x ^ (x >>> 32)));
  }

  /** Map any int to [0, 1) via its hash's low bits. */
  private static double frac01(int h) {
    return (h & 0x7fffffff) / (double) 0x80000000L;
  }

}
