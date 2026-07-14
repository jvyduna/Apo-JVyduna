package apotheneum.jvyduna.patterns.rafters;

import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.SurfaceCanvas;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UIColorControl;
import heronarts.glx.ui.component.UIDropMenu;
import heronarts.glx.ui.component.UIKnob;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LinkedColorParameter;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.studio.LXStudio.UI;
import heronarts.lx.studio.ui.device.UIDevice;
import heronarts.lx.studio.ui.device.UIDeviceControls;
import heronarts.lx.utils.LXUtils;

/**
 * Kurashima — a 20-mode "grand tour" of moire illusions for the cube walls,
 * after Takahiro Kurashima's <i>Moiremotion</i> / <i>Poemotion</i> books, where a
 * transparent striped foil slid over a dithered printed page conjures surprising
 * motion out of stillness. This is the emissive negative of the book: a black
 * page on which razor-crisp light-printed gratings enter from the wings, cross,
 * and bloom — the emergent moire fringe is always the hero (full brightness),
 * the raw carriers always the quiet ground (recessed to {@link #CARRIER_GROUND}).
 *
 * <p><b>The engine.</b> Every mode is <em>two procedural fields combined per
 * pixel</em> — exactly what the book does (a printed base layer times a striped
 * revealing foil). <b>FieldA</b> enters from one wing of each cube wall,
 * <b>FieldB</b> from the other. {@code Meet} (resting at 0.3) sets how far
 * they converge; the {@code Pulse} trigger plays an 8-second phrase — part, rise,
 * a held bloom at full convergence, settle. Windowed modes converge as sliding
 * sheets; radial modes ({@code sepCenters}) converge as two Oster centers that
 * enter from off-wall and merge (hyperbolic double-source fringes
 * degenerating continuously into the concentric bloom); the Glass trio converges
 * as two random dot clouds whose pair-correlation pops into existence only in the
 * overlap. Alternate cube walls are mirrored so FieldA/FieldB emanate outward
 * from the shared vertical corner. The <b>Op</b> operator selects how the overlap
 * interferes in <em>brightness</em> — <b>AND</b> (both-lit intersection, the
 * Kurashima "both-black" logical AND), MIN, MAX, <b>XOR</b> (bright-over-bright
 * goes dark — classic interference), DIFF, ADD, SCREEN — while a separate
 * <b>BlendMode</b> mixes the two palette-linked colors (ColorA/ColorB) there.
 * {@code Pitch} rescales each field about the overlap midline (center column
 * fixed). {@code Warp} rotates FieldB's frame (a per-mode second geometry). Motion
 * is tempo-locked: {@code Speed} is a linear 0-2 scaler (1 = one emergent beat per
 * {@code TempoDiv}, 0 = paused). {@code Swap} crossfades which surface shows the
 * carriers vs. the ghost. {@code Sat} is a master desaturation (0 = greyscale for
 * every mode, the book's black-and-white).
 *
 * <p><b>The third multiply.</b> On paper there are two layers; on our LEDs there
 * is a third, unavoidable one — the fixed pixel lattice itself samples the
 * product. {@code Smooth} dials that third AND: 0 = hard thresholding that lets
 * the lattice alias (steppy, wagon-wheel), 1 = flat-top stripes with ~1 px
 * analytic-AA edges and continuous sub-pixel glide (razor foil, never gray haze).
 * Two modes (Shimmer, Wagon) exist to make that lattice-beat visible.
 *
 * <p><b>The tour (five acts + coda).</b>
 * Act I, the mechanism: Bars, Breathe, Wave, Zoom.
 * Act II, line becomes curve: Rings, Lens, Pinwheel, Hyperbolic, DotScreen.
 * Act III, the hidden figure: Reveal, Magnify, Walk, Spin.
 * Act IV, order from randomness: Swirl, Burst, Vortex.
 * Act V, the medium itself: Wagon, Shimmer.
 * Coda: ColorBeat, Rosette (Next wraps back to Bars, da capo).
 * Mode changes dip through true black (a page turn, nadir at ~0.15 s); Random
 * stages its re-rolls so the scramble is hidden at the nadir.
 *
 * <p><b>Interior is the ghost.</b> Outside reads the mechanism — two carriers
 * entering and meeting. Inside the cube, windowed modes render only the emergent
 * interference (solo carriers vanish), so a person walking through the door sees
 * the apparition floating on darkness with no visible cause.
 *
 * <p><b>The questions.</b> How do small scales Fourier into large slow motions
 * (moire magnification, {@code v_moire/v_slide = p/dp} — governed here so every
 * emergent fringe sails at a retina-legible, >=5 s/face rate)? How does a straight
 * slide become rotation (Pinwheel, Spin), expansion (Rings, Zoom), curved flow
 * (Wave, Vortex)? What is hidden in the regular (Reveal, Magnify, Walk)? Is the
 * visible world just a moire of some finer underlying field (Shimmer)?
 *
 * <p>Rendered on one 50x45 face canvas, copied to all four cube walls; interior
 * gets the ghost pass (or the same canvas dimmed, for modes that are already pure
 * moire). Optional {@code Cyl} toggle wraps the same field math over the 120x43
 * cylinder. Values that can only be judged on the LEDs are marked {@code CURATE:}.
 * See Kurashima.md beside this file.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Kurashima")
@LXComponent.Description("A 20-mode grand tour of moire illusions in five acts: FieldA and FieldB enter from opposite wings of each cube wall (alternate walls mirrored so the fields emanate from the shared vertical corner) and bloom into surprising motion where they cross; inside, only the emergent ghost renders (Swap crossfades that). Two palette colors, tempo-locked (after Kurashima's Moiremotion)")
public class Kurashima extends ApotheneumPattern implements UIDeviceControls<Kurashima> {

  private static final double TWO_PI = Math.PI * 2;

  private static final int FACE_W = Apotheneum.GRID_WIDTH;   // 50
  private static final int FACE_H = Apotheneum.GRID_HEIGHT;  // 45
  private static final int CYL_W = 120;
  private static final int CYL_H = Apotheneum.CYLINDER_HEIGHT; // 43

  /** Number of dots in the Glass-pattern modes (Swirl/Burst/Vortex). CURATE:
   *  density vs. streamline legibility on the real LEDs; per-dot entrance
   *  windowing halves visible pairs at low Meet — may want this raised. */
  private static final int GLASS_N = 150;

  /** Fraction of the Glass dot cloud re-rolled per second, so the correlation
   *  percept scintillates like Glass's original random-dot demos instead of
   *  freezing into a constellation. CURATE: 0.08-0.35. */
  private static final double CHURN_RATE = 0.15;

  /** Glass phosphor-trail half-life range in seconds, mapped from Smooth
   *  (0 = pure static Glass pattern, 1 = delicate 4-8 px decay arcs). CURATE. */
  private static final double GLASS_TRAIL_MIN = 0.03;
  private static final double GLASS_TRAIL_MAX = 2.5;

  /** Seconds for one full Pulse phrase: part -> rise -> held bloom -> settle. */
  private static final double PULSE_SEC = 8.0;

  /** Pulse phrase segment fractions (sum 1.0): ease apart, rise to full
   *  convergence, hold the bloom (~2 s), settle back to the live Meet. */
  private static final double F_PART = 0.20;
  private static final double F_RISE = 0.35;
  private static final double F_HOLD = 0.25;
  private static final double F_SETTLE = 0.20;

  /** Slide units advanced per TempoDiv period at Speed 1 (SPEED_TRIM 1), i.e. how
   *  much slide clock = "one expected moire beat" on the grid. Speed then scales
   *  this linearly (0..2). CURATE — the single global tempo-feel knob. */
  private static final double BASE_CYCLE_UNITS = 0.2;

  /** Beat-period floor (ms) so runaway/zero tempo can't divide-by-tiny. 300 bpm. */
  private static final double MIN_BEAT_MS = 200.0;

  /** The >=5 s full-traversal floor, kept as a SAFETY clamp on the tempo-locked
   *  rate: emergent fringe velocity is capped to FACE_H / this (px/s). At fast
   *  TempoDiv / high magnification this clamp can pull a mode slightly off-grid. */
  private static final double TRAVERSAL_FLOOR_SEC = 5.0;

  /** Max FieldB-frame rotation (radians) from Warp, for modes without their own
   *  Warp geometry — the universal "second geometry" so every mode uses Warp.
   *  ~15 deg at Warp 1. CURATE. */
  private static final double MAX_WARP_RAD = Math.toRadians(15.0);

  /** Grating pitch (px) at the Pitch default — the calibration anchor so modes
   *  that newly fold in Pitch reproduce their prior look at the default. */
  private static final double PITCH_DEFAULT = 0.33;
  private static final double P_DEFAULT = LXUtils.lerp(2.5, 14.0, PITCH_DEFAULT);

  /** Brightness of un-met solo carriers relative to the emergent moire (the
   *  fringe alone reaches full brightness; carriers recede as ground).
   *  CURATE: 0.5-0.7. */
  private static final double CARRIER_GROUND = 0.6;

  /** Interior (ghost) brightness multiplier. CURATE: may want 0.7 at 5 m. */
  private static final double INTERIOR_LEVEL = 0.85;

  /** Off-wall rest separation of the two Oster centers, in face widths:
   *  at Meet 0 centers sit at -0.15W and 1.15W. CURATE. */
  private static final double SEP_FRAC = 0.65;

  /** Analytic-AA halfwidth of grating edges in px at Smooth 1. CURATE: 0.7-1.5. */
  private static final double AA_PX = 1.0;

  /** Page-turn dip: total seconds, and the nadir fraction (0.15 s down, 0.45 s
   *  rise — the reveal lands ~9 frames after the trigger; the slow rise reads
   *  as the page settling, not a strobe). CURATE: the 0.25 split. */
  private static final double DIP_SEC = 0.6;
  private static final double DIP_NADIR = 0.25;

  /** Per-mode tempo-rate trims (how many BASE_CYCLE_UNITS of slide = one expected
   *  moire beat), indexed by Mode.ordinal() (act order below). Higher = the mode's
   *  emergent beat needs more carrier slide per division (high-magnification modes).
   *  CURATE: every value — first hardware session. */
  private static final double[] SPEED_TRIM = {
    1, 1, 1, 1,       // BARS, BREATHE, WAVE, ZOOM
    1, 1, 8, 1, 1,    // RINGS, LENS, PINWHEEL, HYPER, DOTSCREEN
    6, 6, 1, 1,       // REVEAL, MAGNIFY, WALK, SPIN
    1, 1, 1,          // SWIRL, BURST, VORTEX
    5.5, 1,           // WAGON (spoke passage capped ~5-6/s judder), SHIMMER
    1, 1              // COLORBEAT, ROSETTE
  };

  // ---- Combine operators (how the two fields interfere) ------------------------

  public enum Combine {
    AUTO("Auto"),     // use the current mode's default operator
    AND("And"),       // a*b — both-lit intersection (Kurashima logical AND)
    MIN("Min"),
    MAX("Max"),
    XOR("Xor"),       // a+b-2ab — bright-over-bright goes dark (interference)
    DIFF("Diff"),
    ADD("Add"),
    SCREEN("Screen"); // a+b-ab — the De Morgan dual of AND (light-native union)

    public final String label;
    private Combine(String label) { this.label = label; }
    @Override public String toString() { return this.label; }
  }

  // ---- The 20 modes in act order, each carrying its default operator + traits --
  // NOTE: enum order IS the .lxp serialization index (EnumParameter stores the
  // ordinal). This act order is final once any project references Kurashima.

  public enum Mode {
    // Act I — the mechanism
    BARS("Bars", Combine.XOR, false, false, false),
    BREATHE("Breathe", Combine.AND, false, false, false),
    WAVE("Wave", Combine.XOR, false, false, false),
    ZOOM("Zoom", Combine.AND, false, false, false),
    // Act II — line becomes curve
    RINGS("Rings", Combine.AND, false, true, false),
    LENS("Lens", Combine.AND, false, true, false),
    PINWHEEL("Pinwhl", Combine.XOR, false, true, false),
    HYPER("Hyprbl", Combine.XOR, false, false, false),
    DOTSCREEN("DotScr", Combine.AND, false, false, false),
    // Act III — the hidden figure
    REVEAL("Reveal", Combine.AND, false, false, false),
    MAGNIFY("Magnif", Combine.AND, false, false, false),
    WALK("Walk", Combine.AND, false, false, false),
    SPIN("Spin", Combine.AND, false, false, false),
    // Act IV — order from randomness
    SWIRL("Swirl", Combine.MAX, false, false, true),
    BURST("Burst", Combine.MAX, false, false, true),
    VORTEX("Vortex", Combine.MAX, false, false, true),
    // Act V — the medium itself
    WAGON("Wagon", Combine.AND, true, true, false),
    SHIMMER("Shimmr", Combine.AND, false, false, false),
    // Coda
    COLORBEAT("ColorB", Combine.ADD, false, false, false),
    ROSETTE("Rosett", Combine.AND, false, true, false);

    public final String label;
    public final Combine defOp;
    public final boolean fullField;   // no A/B windows (Wagon: FieldB == 1)
    public final boolean sepCenters;  // Meet slides two Oster centers together
    public final boolean glass;       // Glass-pattern dot pre-pass

    private Mode(String label, Combine defOp, boolean fullField, boolean sepCenters,
                 boolean glass) {
      this.label = label;
      this.defOp = defOp;
      this.fullField = fullField;
      this.sepCenters = sepCenters;
      this.glass = glass;
    }
    @Override public String toString() { return this.label; }

    /** Modes that consume Warp in their own geometry — the universal FieldB-frame
     *  rotation stands down for these so their tuned look is preserved. */
    boolean usesInternalWarp() {
      return this == WAVE || this == HYPER || this == LENS || this == ROSETTE;
    }
  }

  // ---- Color blend modes (how the two palette colors mix in the overlap) --------
  // Distinct from Op/Combine (which mixes brightness). Order is the .lxp index.

  public enum BlendMode {
    LERP("Lerp"),
    ADD("Add"),
    SCREEN("Screen"),
    MULTIPLY("Mult"),
    MAX("Max");

    public final String label;
    private BlendMode(String label) { this.label = label; }
    @Override public String toString() { return this.label; }
  }

  /** 8x8 base figure revealed by the hidden-figure modes (Reveal / Magnify). */
  private static final int[] HEART = {
    0b00000000,
    0b01100110,
    0b11111111,
    0b11111111,
    0b01111110,
    0b00111100,
    0b00011000,
    0b00000000,
  };

  // ---- Parameters (template order: triggers, speed, specifics, smooth, audio) --

  public final TriggerParameter nextMode =
    new TriggerParameter("Next", this::advanceMode)
    .setDescription("Advance to the next moire mode (rides the page-turn dip; reveal lands ~0.15s after the trigger)");

  public final TriggerParameter randomize =
    new TriggerParameter("Random", this::randomizeAll)
    .setDescription("Jump to a random mode; geometry/color re-rolls are staged and land hidden at the page-turn nadir");

  public final TriggerParameter pulse =
    new TriggerParameter("Pulse", this::firePulse)
    .setDescription("Play the 8s convergence phrase: part, rise, hold the bloom at full overlap, settle back to Meet");

  public final TriggerParameter reseed =
    new TriggerParameter("Reseed", this::reseedRandomness)
    .setDescription("Re-roll the Glass-pattern dot cloud");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1.0, 0, 2)
    .setDescription("Tempo-lock rate scaler: 1 = one expected moire beat per TempoDiv, 0.5 = half speed (2x the period), 0 = paused, 2 = double. Linear (center 1.0)");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("Div", Tempo.Division.WHOLE)
    .setDescription("The musical period on which the moire's major expected beat lands (Speed 1 = one emergent beat per this division)");

  public final EnumParameter<Mode> mode =
    new EnumParameter<Mode>("Mode", Mode.BARS)
    .setDescription("Which moire illusion (20-mode tour in five acts + coda)");

  public final CompoundParameter pitch =
    new CompoundParameter("Pitch", PITCH_DEFAULT, 0, 1)
    .setDescription("Base grating pitch (fine -> coarse), rescaled about the overlap midline (center column fixed). Fine pitch beats harder against the LED lattice");

  public final CompoundParameter detune =
    new CompoundParameter("Detune", 0.35, 0, 1)
    .setDescription("The tiny A/B difference that Fouriers small scales into big slow motion; low = huge magnification (the governor may slow carriers to hold the floor)");

  public final CompoundParameter meet =
    new CompoundParameter("Meet", 0.3, 0, 1)
    .setDescription("How far FieldA and FieldB converge (0 = apart, 0.3 = wings touching at center, 1 = fully overlapped bloom)");

  public final CompoundParameter swap =
    new CompoundParameter("Swap", 0, 0, 1)
    .setDescription("Crossfade which surface shows the carriers vs. the emergent ghost: 0 = exterior mechanism / interior ghost, 1 = reversed, between = both");

  public final CompoundParameter warp =
    new CompoundParameter("Warp", 0.3, 0, 1)
    .setDescription("Second geometry: rotates FieldB's frame (all modes), plus each mode's own Warp term where it has one (chirp / bend / petal / lens rate)");

  public final EnumParameter<Combine> op =
    new EnumParameter<Combine>("Op", Combine.AUTO)
    .setDescription("How the two fields combine in BRIGHTNESS where they overlap (Auto = the mode's own default)");

  public final LinkedColorParameter colorA =
    new LinkedColorParameter("Color A", 0xff40c0ff)
    .setDescription("FieldA color (link to a palette swatch to pull it live)");

  public final LinkedColorParameter colorB =
    new LinkedColorParameter("Color B", 0xffff8040)
    .setDescription("FieldB color (link to a palette swatch to pull it live)");

  public final EnumParameter<BlendMode> blendMode =
    new EnumParameter<BlendMode>("Blend", BlendMode.ADD)
    .setDescription("How ColorA and ColorB mix in the overlap (color blend, alongside the Op brightness blend)");

  public final CompoundParameter sat =
    new CompoundParameter("Sat", 0, 0, 1)
    .setDescription("Master saturation of both colors (0 = greyscale for every mode, Kurashima's black-and-white book)");

  public final BooleanParameter cylinder =
    new BooleanParameter("Cyl", false)
    .setDescription("Also render the moire wrapped around the cylinder");

  public final CompoundParameter smooth =
    new CompoundParameter("Smooth", 1.0, 0, 1)
    .setDescription("0 = hard-thresholded (lets the LED lattice alias, steppy), 1 = flat-top stripes with ~1px analytic-AA edges + sub-pixel glide; Glass modes: phosphor-trail length");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = level breathes Meet + bass hits fire the Pulse phrase");

  // ---- State -------------------------------------------------------------------

  private final SurfaceCanvas face = new SurfaceCanvas(FACE_W, FACE_H);
  private final SurfaceCanvas faceIn = new SurfaceCanvas(FACE_W, FACE_H);
  private final SurfaceCanvas cyl = new SurfaceCanvas(CYL_W, CYL_H);
  private final AudioReactive audio;
  private final Random random = new Random();

  private final double[] glassX = new double[GLASS_N];
  private final double[] glassY = new double[GLASS_N];

  /** Reused window scratch [winA, winB]; LX renders single-threaded so one is safe. */
  private final double[] winScratch = new double[2];

  /** Reused field scratch [a, b] for the two-color composite. */
  private final double[] abScratch = new double[2];

  /** Custom-UI widgets whose enabled state tracks the mode (null until built). */
  private UIKnob pitchKnob;
  private UIDropMenu opMenu;
  private UIButton reseedButton;

  private double slide = 0;            // moire slide clock, in slide units
  private double pulseT = -1;          // >=0 while a Pulse phrase runs
  private double pulseFrom = 0;        // meetEff captured at Pulse fire (continuity)
  private double lastMeetEff = 0.3;    // meetEff of the previous frame

  private Mode liveMode = Mode.BARS;   // the mode actually rendering (latches at dip nadir)
  private double modeDip = -1;         // >=0 while a page-turn dip runs
  private boolean pendingRandomize = false;
  private double pendingPitch, pendingDetune, pendingWarp;

  private double churnAcc = 0;         // fractional Glass-dot re-rolls carried between frames

  public Kurashima(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    reseedRandomness();

    addParameter("nextMode", this.nextMode);
    addParameter("randomize", this.randomize);
    addParameter("pulse", this.pulse);
    addParameter("reseed", this.reseed);
    addParameter("speed", this.speed);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("mode", this.mode);
    addParameter("pitch", this.pitch);
    addParameter("detune", this.detune);
    addParameter("meet", this.meet);
    addParameter("swap", this.swap);
    addParameter("warp", this.warp);
    addParameter("op", this.op);
    addParameter("colorA", this.colorA);
    addParameter("colorB", this.colorB);
    addParameter("blendMode", this.blendMode);
    addParameter("sat", this.sat);
    addParameter("cylinder", this.cylinder);
    addParameter("smooth", this.smooth);
    addParameter("audio", this.audioDepth);
  }

  // ---- Triggers ----------------------------------------------------------------

  private void advanceMode() {
    final Mode[] all = Mode.values();
    this.mode.setValue((this.mode.getEnum().ordinal() + 1) % all.length);
    // The render loop sees the param != liveMode and runs the page-turn dip.
  }

  private void randomizeAll() {
    final Mode[] all = Mode.values();
    // Stage the geometry/color re-rolls; they land hidden at the dip nadir so
    // the old mode never visibly scrambles before the page turns.
    this.pendingPitch = 0.15 + 0.7 * this.random.nextDouble();
    this.pendingDetune = 0.1 + 0.6 * this.random.nextDouble();
    this.pendingWarp = this.random.nextDouble();
    this.pendingRandomize = true;
    this.mode.setValue(this.random.nextInt(all.length));
    if (this.modeDip < 0) {
      this.modeDip = 0;  // random may land on the same mode; dip regardless
    }
  }

  private void firePulse() {
    this.pulseFrom = this.lastMeetEff;  // continuous from wherever we are
    this.pulseT = 0;
  }

  private void reseedRandomness() {
    for (int i = 0; i < GLASS_N; ++i) {
      this.glassX[i] = this.random.nextDouble();
      this.glassY[i] = this.random.nextDouble();
    }
  }

  // ---- Custom device UI --------------------------------------------------------
  // Jeff asked for a bespoke panel here (overrides the usual prefer-auto-panel
  // rule for this pattern). Controls that a mode can't use stay in place but grey
  // out — cosmetic only, playback is untouched. Columns kept to <=3 controls.

  @Override
  public void buildDeviceControls(UI ui, UIDevice uiDevice, Kurashima pattern) {
    uiDevice.setLayout(UIDevice.Layout.HORIZONTAL, 4);

    addColumn(uiDevice, "Play",
      newButton(pattern.nextMode), newButton(pattern.randomize), newButton(pattern.pulse)
    ).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);

    addColumn(uiDevice, "Motion",
      newKnob(pattern.speed), newDropMenu(pattern.tempoDiv), newDropMenu(pattern.mode)
    ).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);

    this.pitchKnob = newKnob(pattern.pitch);
    addColumn(uiDevice, "Geom",
      this.pitchKnob, newKnob(pattern.detune), newKnob(pattern.meet)
    ).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);

    this.opMenu = newDropMenu(pattern.op);
    addColumn(uiDevice, "Field",
      newKnob(pattern.warp), this.opMenu, newKnob(pattern.swap)
    ).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);

    addColumn(uiDevice, "Color",
      newColorControl(pattern.colorA), newColorControl(pattern.colorB), newDropMenu(pattern.blendMode)
    ).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);

    addColumn(uiDevice, "Master",
      newKnob(pattern.sat), newKnob(pattern.smooth), newButton(pattern.cylinder)
    ).setChildSpacing(6);
    addVerticalBreak(ui, uiDevice);

    this.reseedButton = newButton(pattern.reseed);
    addColumn(uiDevice, "Extra",
      this.reseedButton, newKnob(pattern.audioDepth)
    ).setChildSpacing(6);

    // Grey out controls a mode can't use; fire once now for the initial state.
    uiDevice.addListener(pattern.mode, p -> updateControlEnablement(pattern.mode.getEnum()), true);
  }

  private void updateControlEnablement(Mode m) {
    final boolean fixedBarrier = (m == Mode.WALK || m == Mode.SPIN);
    if (this.pitchKnob != null) {
      this.pitchKnob.setEnabled(!m.glass && !fixedBarrier);  // no grating pitch there
    }
    if (this.opMenu != null) {
      this.opMenu.setEnabled(!m.glass);  // Glass ignores the Op brightness blend
    }
    if (this.reseedButton != null) {
      this.reseedButton.setEnabled(m.glass);  // only the Glass dot cloud reseeds
    }
  }

  // ---- Render ------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    final double dt = deltaMs * 0.001;

    // Audio (depth 0 => taps read silence values, so this is inert by default).
    if (this.audio.bassHit()) {
      firePulse();
    }

    // Page-turn dip: detect a Mode change, dim through true black, latch the
    // new mode (and any staged Random re-rolls) at the nadir.
    if (this.mode.getEnum() != this.liveMode && this.modeDip < 0) {
      this.modeDip = 0;
    }
    double fade = 1;
    if (this.modeDip >= 0) {
      final double prev = this.modeDip;
      this.modeDip += dt / DIP_SEC;
      if (prev < DIP_NADIR && this.modeDip >= DIP_NADIR) {
        this.liveMode = this.mode.getEnum();  // re-read: mid-dip changes land on the latest
        if (this.pendingRandomize) {
          this.pendingRandomize = false;
          this.pitch.setValue(this.pendingPitch);
          this.detune.setValue(this.pendingDetune);
          this.warp.setValue(this.pendingWarp);
          reseedRandomness();
        }
        // No cross-mode ghosts in the decaying Glass canvases.
        this.face.fill(0xff000000);
        this.cyl.fill(0xff000000);
      }
      if (this.modeDip >= 1) {
        this.modeDip = -1;
      } else {
        fade = (this.modeDip < DIP_NADIR)
          ? 1 - ss(this.modeDip / DIP_NADIR)
          : ss((this.modeDip - DIP_NADIR) / (1 - DIP_NADIR));
      }
    }
    final Mode m = this.liveMode;

    // Tempo-locked slide clock: at Speed 1 the mode's expected moire beat lands
    // once per TempoDiv period (BASE_CYCLE_UNITS * SPEED_TRIM slide units per
    // division). Speed is a linear 0-2 scaler (0 = paused). The >=5s/face governor
    // survives as a SAFETY clamp only (legibility at fast div / high magnification).
    final double det = this.detune.getValue();
    final double wrp = this.warp.getValue();
    final double P = LXUtils.lerp(2.5, 14.0, this.pitch.getValue());
    final double beatMs = Math.max(this.lx.engine.tempo.period.getValue(), MIN_BEAT_MS);
    final double divSec = (beatMs / this.tempoDiv.getEnum().multiplier) / 1000.0;
    double rate = (BASE_CYCLE_UNITS * SPEED_TRIM[m.ordinal()] / divSec) * this.speed.getValue();
    final double fringePx = fringePxPerSlide(m, det, wrp, P);
    final double vMax = FACE_H / TRAVERSAL_FLOOR_SEC;
    if (rate * fringePx > vMax) {
      rate = vMax / fringePx;
    }
    this.slide += dt * rate;

    // Convergence: the live Meet (breathed by audio when depth is up), or the
    // eased Pulse phrase — continuous at fire, retrigger, and completion.
    double meetEff;
    final double restMeet = LXUtils.constrain(
      this.meet.getValue() + 0.5 * this.audio.level * this.audio.depth(), 0, 1);
    if (this.pulseT >= 0) {
      this.pulseT += dt / PULSE_SEC;
      if (this.pulseT >= 1) {
        this.pulseT = -1;
        meetEff = restMeet;
      } else {
        final double u = this.pulseT;
        if (u < F_PART) {
          meetEff = this.pulseFrom * (1 - ss(u / F_PART));
        } else if (u < F_PART + F_RISE) {
          meetEff = ss((u - F_PART) / F_RISE);
        } else if (u < F_PART + F_RISE + F_HOLD) {
          meetEff = 1;
        } else {
          meetEff = 1 + (restMeet - 1) * ss((u - (F_PART + F_RISE + F_HOLD)) / F_SETTLE);
        }
      }
    } else {
      meetEff = restMeet;
    }
    this.lastMeetEff = meetEff;

    // Glass dot churn, once per frame (shared by the face and cylinder clouds).
    if (m.glass) {
      this.churnAcc += dt * CHURN_RATE * GLASS_N;
      int k = (int) this.churnAcc;
      if (k > 0) {
        this.churnAcc -= k;
        if (k > GLASS_N) {
          k = GLASS_N;
        }
        for (int i = 0; i < k; ++i) {
          final int j = this.random.nextInt(GLASS_N);
          this.glassX[j] = this.random.nextDouble();
          this.glassY[j] = this.random.nextDouble();
        }
      }
    }

    // Swap crossfades solo-carrier visibility between the two surfaces: at 0 the
    // exterior shows the mechanism and the interior the ghost; at 1 they reverse.
    final double swp = this.swap.getValue();
    final double extSolo = CARRIER_GROUND * (1 - swp);
    final double intSolo = CARRIER_GROUND * swp;

    // Exterior. Alternate walls are mirrored so FieldA/FieldB emanate outward from
    // the shared vertical corner as Meet changes.
    renderField(this.face, m, meetEff, extSolo, fade, dt);
    int fi = 0;
    for (Apotheneum.Cube.Face f : Apotheneum.cube.exterior.faces) {
      if ((fi++ & 1) == 0) {
        this.face.copyTo(f, this.colors);
      } else {
        this.face.copyToMirrored(f, this.colors, 1.0);
      }
    }

    // Interior: the ghost pass (intSolo). fullField/glass modes are already pure
    // moire, so reuse the exterior canvas (Swap is inert there).
    if (Apotheneum.hasInterior) {
      final SurfaceCanvas src;
      if (m.fullField || m.glass) {
        src = this.face;
      } else {
        renderField(this.faceIn, m, meetEff, intSolo, fade, dt);
        src = this.faceIn;
      }
      int gi = 0;
      for (Apotheneum.Cube.Face f : Apotheneum.cube.interior.faces) {
        if ((gi++ & 1) == 0) {
          src.copyToMirrored(f, this.colors, INTERIOR_LEVEL);
        } else {
          src.copyTo(f, this.colors, INTERIOR_LEVEL, false);
        }
      }
    }

    if (this.cylinder.isOn()) {
      renderField(this.cyl, m, meetEff, CARRIER_GROUND, fade, dt);
      this.cyl.copyTo(Apotheneum.cylinder.exterior, this.colors);
      if (Apotheneum.hasInterior) {
        // CURATE: cylinder interior shows the mechanism (dimmed), not the ghost —
        // a third field pass for an off-by-default surface was judged not worth it.
        this.cyl.copyToMirrored(Apotheneum.cylinder.interior, this.colors, INTERIOR_LEVEL);
      }
    } else {
      setColor(Apotheneum.cylinder, LXColor.BLACK);
    }
  }

  /** Render the selected moire into a canvas (works for the 50-wide face and the
   *  120-wide cylinder — the field math is purely a function of pixel coords).
   *
   *  @param soloScale Brightness of un-overlapped solo carriers: CARRIER_GROUND
   *                   outside, 0 for the interior ghost pass. */
  private void renderField(SurfaceCanvas c, Mode m, double meetEff, double soloScale,
                           double fade, double dt) {
    final double t = this.slide;
    final double sm = this.smooth.getValue();
    final double P = LXUtils.lerp(2.5, 14.0, this.pitch.getValue());
    final double det = this.detune.getValue();
    final double wrp = this.warp.getValue();
    final double satv = this.sat.getValue();

    // Per-frame colors: master Sat desaturates both; the overlap color is the
    // BlendMode mix (a constant per frame — scaled per pixel by the moire value).
    final int cA = applySat(this.colorA.calcColor(), satv);
    final int cB = applySat(this.colorB.calcColor(), satv);
    final int cOverlap = blendColors(this.blendMode.getEnum(), cA, cB);

    if (m.glass) {
      renderGlass(c, m, t, det, meetEff, cA, cB, sm, dt, fade);
      return;
    }

    final int W = c.width, H = c.height;
    final Combine chosen = this.op.getEnum();
    final Combine cop = (chosen == Combine.AUTO) ? m.defOp : chosen;
    final double cx = W * 0.5, cy = H * 0.5;
    final double sep = m.sepCenters ? (1.0 - meetEff) * W * SEP_FRAC : 0.0;
    final double warpAng = m.usesInternalWarp() ? 0.0 : wrp * MAX_WARP_RAD;

    for (int y = 0; y < H; ++y) {
      final double yf = y + 0.5;
      for (int x = 0; x < W; ++x) {
        final double xf = x + 0.5;
        sampleFields(m, xf, yf, cx, cy, sep, warpAng, t, P, det, wrp, sm, W, H, this.abScratch);
        final double a = this.abScratch[0], b = this.abScratch[1];
        windows(m.fullField, xf, W, meetEff, this.winScratch);
        final double winA = this.winScratch[0], winB = this.winScratch[1];
        final double ov = winA * winB;
        final double onlyA = winA * (1 - winB);
        final double onlyB = winB * (1 - winA);
        final double moire = combine(cop, a, b);
        // Solo carriers wear their own color (recessed by soloScale); the emergent
        // overlap wears the blended color at full-bright moire.
        final int argb = addColors(
          addColors(scaleRGB(cA, soloScale * onlyA * a * fade),
                    scaleRGB(cB, soloScale * onlyB * b * fade)),
          scaleRGB(cOverlap, ov * moire * fade));
        c.set(x, y, argb);
      }
    }
  }

  /** Master saturation: scale a color's S toward grey (Sat 0 = greyscale for
   *  every mode). Sat 1 leaves the palette color untouched. */
  private static int applySat(int argb, double sat) {
    if (sat >= 1.0) {
      return argb | 0xff000000;
    }
    final float h = LXColor.h(argb);
    final float s = (float) (LXColor.s(argb) * clamp01(sat));
    final float br = LXColor.b(argb);
    return LXColor.hsb(h, s, br);
  }

  /** Mix the two field colors for the overlap — the BlendMode, distinct from the
   *  Op brightness blend. Full-brightness result; the caller scales it by moire. */
  private static int blendColors(BlendMode bm, int a, int b) {
    switch (bm) {
      case ADD: return LXColor.add(a, b);
      case SCREEN: return LXColor.screen(a, b);
      case MULTIPLY: return LXColor.multiply(a, b);
      case MAX: return LXColor.lightest(a, b);
      case LERP:
      default: return LXColor.lerp(a, b, 0.5f);
    }
  }

  /** Fill out[0]=FieldA, out[1]=FieldB at one pixel. FieldA enters from one wing
   *  (Oster-separated by +sep); FieldB from the other (-sep) with its whole frame
   *  rotated by warpAng about the face center — the universal second geometry that
   *  gives every mode a Warp response. Coords are center-relative so Pitch rescales
   *  about the overlap midline (center column fixed). */
  private static void sampleFields(Mode m, double xf, double yf, double cx, double cy,
                                   double sep, double warpAng, double t, double P,
                                   double det, double wrp, double sm, int W, int H, double[] out) {
    final double dx = xf - cx, dy = yf - cy;
    out[0] = fieldA(m, xf, yf, dx + sep, dy, t, P, det, wrp, sm, W, H);
    final double bdx0 = dx - sep;
    final double cw = Math.cos(warpAng), sw = Math.sin(warpAng);
    final double bdx = bdx0 * cw - dy * sw;
    final double bdy = bdx0 * sw + dy * cw;
    out[1] = fieldB(m, cx + bdx, cy + bdy, bdx, bdy, t, P, det, wrp, sm, W, H);
  }

  /** Left/right convergence windows into scratch[0]=winA, scratch[1]=winB.
   *  Crisp sheet edges (constant ~1.5px feather) — the entering edge is a foil
   *  edge, not a fog bank. */
  private static void windows(boolean fullField, double xf, int W, double meetEff, double[] scratch) {
    if (fullField) {
      scratch[0] = 1;
      scratch[1] = 1;
      return;
    }
    final double half = W * 0.5;
    final double push = meetEff * half;
    final double aEdge = half + push;
    final double bEdge = half - push;
    final double feather = 1.5;
    scratch[0] = clamp01(0.5 + (aEdge - xf) / feather);
    scratch[1] = clamp01(0.5 + (xf - bEdge) / feather);
  }

  /** The full grayscale brightness at one pixel: two fields (Oster-center separated
   *  when the mode converges by centers, FieldB warp-rotated), A/B windows, combine;
   *  solo carriers recede to soloScale, the emergent moire alone reaches full
   *  brightness. Static, used by the headless {@code main()} (the runtime path is
   *  the two-color composite in {@link #renderField}). */
  private static double sampleGray(Mode m, Combine cop, int W, int H, double meetEff,
                                   double t, double sm, double P, double det, double wrp,
                                   double xf, double yf, double soloScale, double[] scratch) {
    final double cx = W * 0.5, cy = H * 0.5;
    final double sep = m.sepCenters ? (1.0 - meetEff) * W * SEP_FRAC : 0.0;
    final double warpAng = m.usesInternalWarp() ? 0.0 : wrp * MAX_WARP_RAD;
    final double[] ab = new double[2];
    sampleFields(m, xf, yf, cx, cy, sep, warpAng, t, P, det, wrp, sm, W, H, ab);
    final double a = ab[0], b = ab[1];
    windows(m.fullField, xf, W, meetEff, scratch);
    final double winA = scratch[0], winB = scratch[1];
    final double moire = combine(cop, a, b);
    final double onlyA = winA * (1 - winB);
    final double onlyB = winB * (1 - winA);
    final double ov = winA * winB;
    return clamp01(soloScale * (onlyA * a + onlyB * b) + ov * moire);
  }

  // ---- Field A / Field B (the two layers, per mode) ----------------------------
  // dx, dy arrive center-relative (so Pitch rescales about the overlap midline)
  // and Oster-separated for sepCenters modes. FieldB's dx/dy/xf/yf are additionally
  // warp-rotated about the center (the universal second geometry). xf/yf remain
  // available for the few genuinely absolute cases (Reveal position, Walk/Spin
  // lattice barrier).

  private static double fieldA(Mode m, double xf, double yf, double dx, double dy,
                        double t, double P, double det, double wrp, double sm, int W, int H) {
    switch (m) {
      case BARS:
      case COLORBEAT:
        // The printed page is STILL — the book's first trick is stillness
        // becoming motion. Local pitch 0.68*P puts >=1.5 emergent bands on the
        // face at defaults (CURATE: the regime, not the exact curve).
        return duty(dx, P * 0.68, sm);
      case BREATHE:
      case HYPER:
        return duty(dx, P, sm);
      case PINWHEEL: {
        final int n = spokeCount(6, 18, det, P);
        final double r = Math.hypot(dx, dy);
        final double L = Math.max(1.0, TWO_PI * r / n);  // px per spoke cycle at r
        return duty((Math.atan2(dy, dx) * n / TWO_PI + t) * L, L, sm);
      }
      case RINGS:
      case ZOOM:
      case ROSETTE:
        return duty(Math.hypot(dx, dy), P, sm);
      case LENS: {
        final double kA = lensRate(wrp, P);  // local ring period, Pitch-scaled
        final double r = Math.max(1.0, Math.hypot(dx, dy));
        final double L = Math.max(1.0, 1.0 / (2 * kA * r));  // px per ring at r
        return duty((dx * dx + dy * dy) * kA * L, L, sm);
      }
      case REVEAL: {
        final double pmb = Math.max(3.0, P);
        final double gu = clamp01(xf / W);
        final double gv = frac(yf / pmb);
        return glyphValue(gu, gv);
      }
      case MAGNIFY: {
        final double pm = Math.max(6.0, P);
        return glyphValue(frac(dx / pm), frac(dy / pm));
      }
      case WALK: {
        // Interlaced poses: a disc that hops, one pose per barrier column.
        final double bp = 4.0;
        final int n = 4;
        final int frame = ((int) (frac(xf / bp) * bp)) % n;
        final double bobAmp = Math.min(3.0 + det * 3.0, 4.0);  // feet clear the door rows
        final double bob = Math.sin(frame / (double) n * TWO_PI) * bobAmp;
        return edge(9.0 - Math.hypot(xf - W * 0.5, yf - (20.0 + bob)), sm);
      }
      case SPIN: {
        final double bp = 4.0;
        final int n = 4;
        final int frame = ((int) (frac(xf / bp) * bp)) % n;
        final double ang = frame / (double) n * Math.PI;  // 45-degree steps
        final double perp = Math.abs(dx * Math.sin(ang) - dy * Math.cos(ang));
        return edge(3.0 - perp, sm);
      }
      case SHIMMER: {
        final double pa = 2.0 + det * 0.4;
        return duty(dx + t * pa * 2.0, pa, sm);
      }
      case WAGON: {
        // fullField mode (FieldB is the constant lattice), so the universal
        // FieldB warp-rotation can't reach it — Warp instead spirals the spokes
        // by radius (twist grows toward the rim) so Wagon still responds to Warp.
        final int n = spokeCount(8, 16, det, P);
        final double r = Math.hypot(dx, dy);
        final double L = Math.max(1.0, TWO_PI * r / n);
        final double spiral = wrp * 0.03 * r;  // spoke-cycles of twist at radius r
        return duty((Math.atan2(dy, dx) * n / TWO_PI + t * 6.0 + spiral) * L, L, sm);
      }
      case DOTSCREEN:
        return duty(dx, P, sm) * duty(dy, P, sm);
      case WAVE: {
        final double bend = (2.0 + wrp * 8.0) * Math.sin(TWO_PI * dy / 12.0);
        return duty(dx + bend, P, sm);
      }
      default:
        return duty(dx, P, sm);
    }
  }

  /** Spoke count for the rotary modes: base + Detune spread, scaled by Pitch so
   *  finer Pitch packs more spokes (= 1 at the Pitch default, preserving the
   *  prior look). */
  private static int spokeCount(int base, double detSpread, double det, double P) {
    final double pf = P_DEFAULT / P;
    return Math.max(3, (int) Math.round((base + det * detSpread) * pf));
  }

  /** Zone-plate ring rate for Lens, Warp-modulated and Pitch-scaled (= the prior
   *  rate at the Pitch default). */
  private static double lensRate(double wrp, double P) {
    return (0.002 + wrp * 0.004) * (P_DEFAULT / P);
  }

  private static double fieldB(Mode m, double xf, double yf, double dx, double dy,
                        double t, double P, double det, double wrp, double sm, int W, int H) {
    switch (m) {
      case BARS:
      case COLORBEAT: {
        // The foil: detuned pitch, sliding at 2x the base pitch per slide unit
        // (preserves the net beat speed against the still page).
        final double Pb = P * 0.68;
        final double pB = Pb * (1.05 + det * 0.45);
        return duty(dx - t * 2.0 * Pb, pB, sm);
      }
      case BREATHE: {
        final double th = (0.02 + det * 0.35) * Math.sin(TWO_PI * t);
        return duty(dx * Math.cos(th) + dy * Math.sin(th), P, sm);
      }
      case HYPER: {
        final double th = 0.05 + det * 0.25;
        final double pB = P * (1.0 + 0.1 * (0.2 + wrp) * Math.sin(TWO_PI * t));
        return duty(dx * Math.cos(th) + dy * Math.sin(th), pB, sm);
      }
      case PINWHEEL: {
        final int n = spokeCount(6, 18, det, P);
        final double r = Math.hypot(dx, dy);
        final double L = Math.max(1.0, TWO_PI * r / n);
        return duty((Math.atan2(dy, dx) * n / TWO_PI - t) * L, L, sm);
      }
      case RINGS: {
        final double off = (W * 0.2) * Math.sin(TWO_PI * t);
        return duty(Math.hypot(dx - off, dy), P, sm);
      }
      case LENS: {
        final double kA = lensRate(wrp, P);
        final double kB = kA * (1.04 + det * 0.15);
        final double off = (W * 0.18) * Math.sin(TWO_PI * t);
        final double dbx = dx - off;
        final double r = Math.max(1.0, Math.hypot(dbx, dy));
        final double L = Math.max(1.0, 1.0 / (2 * kB * r));
        return duty((dbx * dbx + dy * dy) * kB * L, L, sm);
      }
      case ZOOM:
        return duty(dx - t * P, P, sm);
      case ROSETTE: {
        final double pB = P * (1.05 + det * 0.25);
        final double ang = Math.atan2(dy, dx);
        final double petal = (0.5 + wrp * 3.0) * Math.sin(4 * ang) * Math.sin(TWO_PI * t);
        return duty(Math.hypot(dx, dy) + petal, pB, sm);
      }
      case REVEAL: {
        // Solve the band-moire magnification from a target figure height, so
        // the heart always fits the face (det-up = smaller figure).
        final double pmb = Math.max(3.0, P);
        final double HT = H * (0.85 - 0.4 * det);
        final double prb = pmb * HT / Math.max(HT - pmb, 1.5);
        final double sy = frac((yf - t * prb) / prb) * prb;
        return edge(Math.min(sy, 1.3 - sy), sm);
      }
      case MAGNIFY: {
        // 1-D band-moire magnifier (Hersch/Chosson): horizontal slits over
        // vertically compressed micro-glyphs -> a colonnade of stretched
        // glyphs scrolling. CURATE: hole width from 20m.
        final double pm = Math.max(6.0, P);
        final double pr = pm * (1.15 + det * 0.25);
        final double sy = frac((yf - t * pr) / pr) * pr;
        return edge(Math.min(sy, 2.0 - sy), sm);
      }
      case WALK:
      case SPIN: {
        // Lattice-locked barrier: pitch 4, slit exactly one LED column — the
        // cleanest demo of the lattice as the third AND. x6 rate: poses fuse.
        final double bp = 4.0;
        final double w = 1.0;
        final double slit = frac((xf - t * bp * 6.0) / bp) * bp;
        return edge(Math.min(slit, w - slit), sm);
      }
      case SHIMMER: {
        // A second detuned near-lattice grating from the other wing: the overlap
        // is a three-layer AND (grating x grating x lattice) — the thesis itself.
        final double pa = 2.0 + det * 0.4;
        final double pb = pa * (1.02 + det * 0.05);
        return duty(dx - t * pb * 0.25, pb, sm);
      }
      case DOTSCREEN: {
        final double pB = P * (1.06 + det * 0.2);
        return duty(dx - t * pB, pB, sm) * duty(dy, pB, sm);
      }
      case WAVE:
        return duty(dx - t * P, P, sm);
      case WAGON:
        return 1.0;  // single-field mode: the LED lattice is the second layer
      default:
        return duty(dx, P, sm);
    }
  }

  /** Emergent-fringe px moved per unit slide, per mode — the governor's model
   *  of moire magnification (v_fringe = v_carrier * p/dp). Conservative;
   *  transients (Breathe's blowup near theta=0) and Wagon's capped spoke
   *  judder are exempt by returning small values. */
  private static double fringePxPerSlide(Mode m, double det, double wrp, double P) {
    switch (m) {
      case BARS:
      case COLORBEAT: {
        final double Pb = P * 0.68;
        final double M = (1.05 + 0.45 * det) / (0.05 + 0.45 * det);
        return 2.0 * Pb * M;
      }
      case DOTSCREEN: {
        final double pB = P * (1.06 + 0.2 * det);
        final double M = (1.06 + 0.2 * det) / (0.06 + 0.2 * det);
        return pB * M;
      }
      case LENS: {
        final double M = 1.0 / (0.04 + 0.15 * det);
        return 0.18 * FACE_W * TWO_PI * M * (P_DEFAULT / P);  // rate scales with Pitch
      }
      case RINGS:
        return 0.2 * FACE_W * TWO_PI * 2.0;
      case REVEAL: {
        final double pmb = Math.max(3.0, P);
        final double HT = FACE_H * (0.85 - 0.4 * det);
        final double prb = pmb * HT / Math.max(HT - pmb, 1.5);
        return prb * (HT / pmb);
      }
      case MAGNIFY: {
        final double pm = Math.max(6.0, P);
        final double pr = pm * (1.15 + det * 0.25);
        final double M = (1.15 + det * 0.25) / (0.15 + det * 0.25);
        return pr * M;
      }
      case SHIMMER: {
        final double pa = 2.0 + det * 0.4;
        final double pb = pa * (1.02 + det * 0.05);
        final double M = (1.02 + det * 0.05) / (0.02 + det * 0.05);
        return (2.0 * pa + 0.25 * pb) * M;
      }
      case PINWHEEL: {
        final int n = spokeCount(6, 18, det, P);
        return TWO_PI * 27.0 / n;  // rim tangential px per spoke cycle
      }
      case WALK:
      case SPIN:
        return 6.0 * 4.0;  // slit comb velocity (figure itself bobs in place)
      case ROSETTE: {
        final double M = (1.05 + 0.25 * det) / (0.05 + 0.25 * det);
        return (0.5 + 3.0 * wrp) * TWO_PI * M;
      }
      case ZOOM:
        return 2.0 * P;
      case WAVE:
        return 4.0 * P;  // CURATE: local fringe amplification along the crests
      default:
        return P;  // BREATHE/HYPER (transient-dominated), WAGON (judder-capped), glass
    }
  }

  // ---- Glass patterns (Swirl / Burst / Vortex) ---------------------------------

  private void renderGlass(SurfaceCanvas c, Mode m, double t, double det, double meetEff,
                           int cA, int cB, double smooth, double dtSec, double fade) {
    // Phosphor trail: Smooth maps the decay half-life (0 = static Glass pattern,
    // 1 = delicate arcs on-mood for Rafters).
    final double halfLife = LXUtils.lerp(GLASS_TRAIL_MIN, GLASS_TRAIL_MAX, smooth);
    c.decay(Math.pow(0.5, dtSec / Math.max(halfLife, 1e-3)));

    final int W = c.width, H = c.height;
    final double cx = W * 0.5, cy = H * 0.5;
    final double amp = 0.5 + 0.5 * Math.sin(TWO_PI * t);
    final double rotMax = 0.12 + det * 0.28;
    final double sclMax = 1.0 + 0.05 + det * 0.18;
    double rot = 0, scl = 1.0;
    switch (m) {
      case SWIRL:  rot = rotMax * amp; scl = 1.0; break;
      case BURST:  rot = 0; scl = 1.0 + (sclMax - 1.0) * amp; break;
      case VORTEX: rot = rotMax * amp; scl = 1.0 + (sclMax - 1.0) * amp; break;
      default: break;
    }
    final double cos = Math.cos(rot), sin = Math.sin(rot);
    for (int i = 0; i < GLASS_N; ++i) {
      final double px = this.glassX[i] * W;
      final double py = this.glassY[i] * H;
      // Base cloud (ColorA) enters from one wing, transformed cloud (ColorB) from
      // the other; the pair-correlation (the percept) pops into existence only in
      // the overlap.
      windows(false, px, W, meetEff, this.winScratch);
      plotDot(c, px, py, scaleRGB(cA, this.winScratch[0] * fade));
      final double ox = px - cx, oy = py - cy;
      final double tx = cx + (ox * cos - oy * sin) * scl;
      final double ty = cy + (ox * sin + oy * cos) * scl;
      windows(false, tx, W, meetEff, this.winScratch);
      plotDot(c, tx, ty, scaleRGB(cB, this.winScratch[1] * fade));
    }
  }

  /** Plot a soft dot with 2x2 bilinear coverage, lighten-blended. */
  private void plotDot(SurfaceCanvas c, double px, double py, int argb) {
    final int xi = (int) Math.floor(px);
    final int yi = (int) Math.floor(py);
    final double fx = px - xi, fy = py - yi;
    c.setMax(xi, yi, scaleRGB(argb, (1 - fx) * (1 - fy)));
    c.setMax(xi + 1, yi, scaleRGB(argb, fx * (1 - fy)));
    c.setMax(xi, yi + 1, scaleRGB(argb, (1 - fx) * fy));
    c.setMax(xi + 1, yi + 1, scaleRGB(argb, fx * fy));
  }

  // ---- Field / color math ------------------------------------------------------

  /** 1-D grating duty in [0,1]: 50%-duty flat-top stripes with analytic-AA
   *  edges. Signed px distance to the nearest stripe edge over an AA halfwidth
   *  of smooth*AA_PX (clamped to a quarter pitch, which gracefully collapses
   *  near-lattice gratings like Shimmer's toward a triangle wave). smooth=0 is
   *  the hard sign() threshold — the aliased end of the knob. Phase matches the
   *  old sin()>=0 convention (bar center at quarter phase). */
  private static double duty(double coord, double pitch, double smooth) {
    if (pitch < 1e-6) {
      pitch = 1e-6;
    }
    final double u = coord / pitch + 0.25;
    final double f = u - Math.floor(u);
    final double sd = (0.25 - Math.abs(f - 0.5)) * pitch;  // signed px, + inside
    final double aa = Math.min(Math.max(1e-3, smooth * AA_PX), pitch * 0.25);
    final double v = clamp01(0.5 + sd / aa);
    return v * v * (3 - 2 * v);
  }

  /** Trapezoid edge coverage from a signed inside-distance in px, softened by
   *  smooth (consistent with duty()'s AA) — the barrier/figure family's AA. */
  private static double edge(double insideDist, double sm) {
    return clamp01(0.5 + insideDist / Math.max(sm, 1e-3));
  }

  /** Smoothstep. */
  private static double ss(double u) {
    u = clamp01(u);
    return u * u * (3 - 2 * u);
  }

  private static double combine(Combine op, double a, double b) {
    switch (op) {
      case MIN: return Math.min(a, b);
      case MAX: return Math.max(a, b);
      case XOR: return a + b - 2 * a * b;
      case DIFF: return Math.abs(a - b);
      case ADD: return Math.min(1.0, a + b);
      case SCREEN: return a + b - a * b;
      case AND:
      default: return a * b;
    }
  }

  /** Sample the 8x8 HEART bitmap at normalized (gu, gv) in [0,1). */
  private static double glyphValue(double gu, double gv) {
    final int gx = ((int) (gu * 8)) & 7;
    final int gy = ((int) (gv * 8)) & 7;
    return ((HEART[gy] >> (7 - gx)) & 1);
  }

  private static double frac(double v) {
    return v - Math.floor(v);
  }

  private static double clamp01(double v) {
    return (v < 0) ? 0 : (v > 1) ? 1 : v;
  }

  private static int scaleRGB(int argb, double mult) {
    if (mult <= 0) {
      return 0xff000000;
    }
    if (mult > 1) {
      mult = 1;
    }
    final int r = (int) (((argb >> 16) & 0xff) * mult);
    final int g = (int) (((argb >> 8) & 0xff) * mult);
    final int b = (int) ((argb & 0xff) * mult);
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  private static int addColors(int a, int b) {
    final int r = Math.min(255, ((a >> 16) & 0xff) + ((b >> 16) & 0xff));
    final int g = Math.min(255, ((a >> 8) & 0xff) + ((b >> 8) & 0xff));
    final int bl = Math.min(255, (a & 0xff) + (b & 0xff));
    return 0xff000000 | (r << 16) | (g << 8) | bl;
  }

  /**
   * Headless ASCII preview of every non-glass mode's grayscale field on a 50x45
   * face at meet in {0.1, 0.5, 1.0} (maps printed for the entrance and the
   * bloom; stats for all three), used to sanity-check the field math
   * off-hardware: flags NaN/Inf and flat fields, and reports the governed
   * fringe velocity per mode so the >=5s floor is verified analytically.
   * Not used at runtime. Run with:
   * {@code java -cp <classes+lx+apotheneum+gson> apotheneum.jvyduna.patterns.rafters.Kurashima}
   */
  public static void main(String[] args) {
    final int W = FACE_W, H = FACE_H;
    final double[] scratch = new double[2];
    final double P = LXUtils.lerp(2.5, 14.0, 0.4);
    final double det = 0.35, wrp = 0.3, sm = 1.0, t = 0.37;
    final double[] meets = { 0.1, 0.5, 1.0 };
    final String ramp = " .:-=+*#%@";
    final double vMax = FACE_H / TRAVERSAL_FLOOR_SEC;
    int bad = 0;
    for (Mode m : Mode.values()) {
      if (m.glass) {
        System.out.println("== " + m + " (Glass dot-cloud — visual only, skipped) ==\n");
        continue;
      }
      final Combine cop = m.defOp;
      boolean nan = false;
      double mnAll = 1e9, mxAll = -1e9;
      System.out.println("== " + m + "  op=" + cop
        + (m.fullField ? "  [fullField]" : "") + (m.sepCenters ? "  [sepCenters]" : "") + " ==");
      for (double meet : meets) {
        double mn = 1e9, mx = -1e9, sum = 0;
        int count = 0;
        final StringBuilder sb = new StringBuilder();
        final boolean printMap = (meet != 0.5);
        for (int y = 0; y < H; ++y) {  // stats scan every row; maps subsample x3
          final boolean mapRow = printMap && (y % 3 == 0);
          for (int x = 0; x < W; ++x) {
            final double v = sampleGray(m, cop, W, H, meet, t, sm, P, det, wrp,
              x + 0.5, y + 0.5, CARRIER_GROUND, scratch);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
              nan = true;
            }
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
            sum += v;
            ++count;
            if (mapRow) {
              sb.append(ramp.charAt((int) (clamp01(v) * (ramp.length() - 1))));
            }
          }
          if (mapRow) {
            sb.append('\n');
          }
        }
        if (printMap) {
          System.out.println("-- meet=" + meet + " --");
          System.out.print(sb);
        }
        System.out.printf("   meet=%.1f min=%.2f max=%.2f mean=%.3f%n", meet, mn, mx, sum / count);
        mnAll = Math.min(mnAll, mn);
        mxAll = Math.max(mxAll, mx);
      }
      // Governor check at the worst case (Speed 2, a fast 120bpm quarter division):
      // the clamped fringe velocity must still respect vMax.
      final double fringePx = fringePxPerSlide(m, det, wrp, P);
      final double divSec = 0.5;  // nominal 120bpm quarter
      double rate = (BASE_CYCLE_UNITS * SPEED_TRIM[m.ordinal()] / divSec) * 2.0;
      if (rate * fringePx > vMax) {
        rate = vMax / fringePx;
      }
      System.out.printf("   governor: fringe %.1f px/slide, governed v=%.2f px/s (max %.1f) %s%n",
        fringePx, rate * fringePx, vMax, (rate * fringePx <= vMax + 1e-9) ? "OK" : "VIOLATION");
      // Warp sensitivity: every mode must respond to Warp (requirement). Compare
      // the field at wrp=0 vs wrp=0.8 at meet=0.5; flag a mode that ignores Warp.
      double warpDelta = 0;
      for (int y = 0; y < H; ++y) {
        for (int x = 0; x < W; ++x) {
          final double v0 = sampleGray(m, cop, W, H, 0.5, t, sm, P, det, 0.0,
            x + 0.5, y + 0.5, CARRIER_GROUND, scratch);
          final double v1 = sampleGray(m, cop, W, H, 0.5, t, sm, P, det, 0.8,
            x + 0.5, y + 0.5, CARRIER_GROUND, scratch);
          warpDelta += Math.abs(v0 - v1);
        }
      }
      System.out.printf("   warp-delta=%.2f %s%n", warpDelta, (warpDelta > 1e-3) ? "OK" : "!! WARP INERT");
      if (nan || mxAll - mnAll < 1e-6) {
        ++bad;
        System.out.println("   !! DEGENERATE (flat or non-finite)");
      }
      if (warpDelta <= 1e-3) {
        ++bad;
        System.out.println("   !! WARP INERT (mode ignores Warp)");
      }
      System.out.println();
    }
    System.out.println(bad == 0 ? "ALL FIELD MODES OK" : (bad + " DEGENERATE MODE(S)"));
  }
}
