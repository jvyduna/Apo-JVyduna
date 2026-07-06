package apotheneum.jvyduna.patterns;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import apotheneum.jvyduna.util.PerceptualHue;
import apotheneum.jvyduna.util.Ranges;
import apotheneum.jvyduna.util.SurfaceCanvas;
import apotheneum.jvyduna.util.TempoLock;
import apotheneum.jvyduna.util.TriggerBag;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.color.LXColor;
import heronarts.lx.color.LXDynamicColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.utils.LXUtils;

/**
 * Recreation of the classic "Mystify Your Mind" screensaver family: one or two
 * closed polylines whose vertices drift independently, leaving fading trails.
 * Vertices always bounce off the top and bottom; horizontally they either
 * bounce (per-face mode) or wrap continuously around the cube ring or the
 * cylinder (the "Wraparound" variant, native to these closed topologies).
 *
 * The simulation runs on a preallocated SurfaceCanvas in normalized [0,1]
 * coordinates, so switching geometry preserves the shapes. Edges alternate
 * between two palette hues per polyline. Audio reactivity (bass-hit vertex
 * flashes, level speed-breath, treble trail-shortening) is gated by the Audio
 * depth knob, default 0 = pure screensaver. With Sync on, each vertex's
 * velocity is retimed per axis so wall bounces land on the TempoDiv grid.
 *
 * See Mystify.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Mystify")
@LXComponent.Description("Mystify-screensaver polylines with fading trails, bouncing on the cube faces or wrapping around the cube ring or cylinder")
public class Mystify extends ApotheneumPattern {

  // ---- Timing / motion constants ---------------------------------------------

  /** Seconds for a vertex to cross the full canvas width at energy=0 (speed cap) */
  private static final double TRAVERSE_SEC_AMBIENT = 12;

  /** Seconds for a vertex to cross the full canvas width at energy=1 — the
   *  simulation-principles floor (>= 5 s full-sculpture traversal), never faster */
  private static final double TRAVERSE_SEC_PEAK = 5;

  /** Audio level modulates speed by this fraction (+/-30%), always inside the cap */
  private static final double SPEED_AUDIO_DEPTH = 0.3;

  /** Slowest vertex velocity component, as a fraction of the fastest — keeps
   *  every vertex visibly moving without any exceeding the cap */
  private static final double VEL_MIN = 0.45;

  /** Trail half-life range (ms), mapped exponentially from the Trails knob:
   *  50 ms reads as almost bare lines; 2500 ms leaves seconds-long comets */
  private static final double TRAIL_HALF_LIFE_MIN_MS = 50;
  private static final double TRAIL_HALF_LIFE_MAX_MS = 2500;

  /** Treble transients shorten trails by up to ~30% (1 + 0.15 * 2) — subtle */
  private static final double TREBLE_TRAIL_SHORTEN = 0.15;

  /** Bass-hit vertex flash fades to black over this long — a visible pop.
   *  Hits gated closer together than this (the retrigger gate is ~an eighth
   *  note) simply re-arm the envelope before it empties. */
  private static final double FLASH_DECAY_MS = 500;

  /** Structural maxima; all state is preallocated at these sizes */
  private static final int MAX_SHAPES = 2;
  private static final int MAX_VERTS = 5;

  // ---- Geometry modes ---------------------------------------------------------

  public enum Geometry {
    FACES("Faces"),        // one 50x45 sim mirrored to all 4 cube faces; x bounces
    CUBE_RING("CubeRing"), // one 200x45 sim around the cube exterior; x wraps
    CYLINDER("Cylinder");  // one 120x43 sim around the cylinder; x wraps

    public final String label;
    private Geometry(String label) { this.label = label; }

    @Override
    public String toString() { return this.label; }
  }

  // ---- Parameters ---------------------------------------------------------------

  private final TriggerBag bag = new TriggerBag("Mystify");
  private final AudioReactive audio;
  private final TempoLock tempoLock;
  private final Random random = new Random();

  public final TriggerParameter scatter = bag.register(
    new TriggerParameter("Scatter", this::scatter)
    .setDescription("Re-randomize every vertex position and velocity"));

  public final TriggerParameter reverse = bag.register(
    new TriggerParameter("Reverse", this::reverse)
    .setDescription("Negate all vertex velocities so the shapes retrace their paths"));

  public final TriggerParameter hueJump = bag.register(
    new TriggerParameter("HueJump", this::hueJump)
    .setDescription("Rotate the palette color assignment of the polylines"));

  public final CompoundParameter energy =
    new CompoundParameter("Energy", 0.35)
    .setDescription("Master energy: 0-0.4 soothing ambient, 0.6-1.0 high-energy 160 BPM regime; scales speed within the traversal cap and flash intensity");

  public final EnumParameter<Geometry> geometry =
    new EnumParameter<Geometry>("Geometry", Geometry.CUBE_RING)
    .setDescription("Surface topology: Faces bounces on each cube face; CubeRing/Cylinder wrap continuously around");

  public final DiscreteParameter shapes =
    new DiscreteParameter("Shapes", 2, 1, MAX_SHAPES + 1)
    .setDescription("Number of independent polylines (1 or 2)");

  public final DiscreteParameter vertices =
    new DiscreteParameter("Vertices", 4, 3, MAX_VERTS + 1)
    .setDescription("Vertices per polyline (3-5)");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1, 0.2, 1)
    .setDescription("Speed as a fraction of the energy-set traversal cap (1 = at the cap)");

  public final CompoundParameter trails =
    new CompoundParameter("Trails", 0.5)
    .setDescription("Trail length: 0 = bare moving lines, 1 = seconds-long comets");

  public final CompoundParameter audioDepth =
    new CompoundParameter("Audio", 0)
    .setDescription("Audio reactivity depth: 0 = pure screensaver (default), 1 = full reactivity");

  public final BooleanParameter sync =
    new BooleanParameter("Sync", true)
    .setDescription("Lock vertex wall-bounces to the tempo grid; off restores free-running timing");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("TempoDiv", Tempo.Division.QUARTER)
    .setDescription("Tempo division that vertex bounces land on when Sync is enabled");

  public final TriggerParameter meta =
    new TriggerParameter("Meta", bag::fire)
    .setDescription("Randomly fire one of Mystify's triggers or jump a parameter");

  // ---- Simulation state (all preallocated; normalized [0,1] coordinates) ------

  private final double[] posX = new double[MAX_SHAPES * MAX_VERTS];
  private final double[] posY = new double[MAX_SHAPES * MAX_VERTS];
  private final double[] velX = new double[MAX_SHAPES * MAX_VERTS]; // canvas-widths/sec at cap=1
  private final double[] velY = new double[MAX_SHAPES * MAX_VERTS]; // canvas-heights/sec at cap=1

  /** Per-axis tempo-sync rate scales (1 = unscaled). Applied multiplicatively
   *  on top of velX/velY so Sync never mutates the free-running velocities;
   *  each re-plan REPLACES the scale (scales never compound). */
  private final double[] syncScaleX = new double[MAX_SHAPES * MAX_VERTS];
  private final double[] syncScaleY = new double[MAX_SHAPES * MAX_VERTS];

  /** Two hues per polyline, refreshed from the palette each frame */
  private final int[] shapeColor = new int[MAX_SHAPES * 2];
  private int hueOffset = 0;

  /** Bass-hit vertex flash envelope, 0..1 (armed to audio depth on each hit) */
  private double flash = 0;

  /** This frame's rate in canvas-spans/sec at velocity magnitude 1; the basis
   *  for sync planning (planScale) as well as integration */
  private double rateSpansPerSec = 0;

  private final SurfaceCanvas facesCanvas =
    new SurfaceCanvas(Apotheneum.GRID_WIDTH, Apotheneum.GRID_HEIGHT);          // 50x45
  private final SurfaceCanvas ringCanvas =
    new SurfaceCanvas(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT);    // 200x45
  private final SurfaceCanvas cylinderCanvas =
    new SurfaceCanvas(Apotheneum.Cylinder.Ring.LENGTH, Apotheneum.CYLINDER_HEIGHT); // 120x43

  private Geometry lastGeometry = null;
  private boolean wasSync = false;
  private Tempo.Division lastDivision = null;

  public Mystify(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx).setDepth(this.audioDepth);
    this.tempoLock = new TempoLock(lx);

    addParameter("scatter", this.scatter);
    addParameter("reverse", this.reverse);
    addParameter("hueJump", this.hueJump);
    addParameter("energy", this.energy);
    addParameter("geometry", this.geometry);
    addParameter("shapes", this.shapes);
    addParameter("vertices", this.vertices);
    addParameter("speed", this.speed);
    addParameter("trails", this.trails);
    addParameter("audio", this.audioDepth);
    addParameter("sync", this.sync);
    addParameter("tempoDiv", this.tempoDiv);
    addParameter("meta", this.meta);

    // Meta jump candidates — mirrored 1:1 in the Jump candidates table in Mystify.md
    bag.jumpable(this.trails, 0.25, 0.85);
    bag.jumpable(this.vertices);
    bag.jumpable(this.geometry);
    bag.jumpable(this.speed, 0.4, 1.0);
    bag.jumpable(this.shapes);

    Arrays.fill(this.syncScaleX, 1);
    Arrays.fill(this.syncScaleY, 1);
    scatter();
  }

  // ---- Trigger handlers ---------------------------------------------------------

  /** Re-randomize all vertex positions and velocities (also the initial state) */
  private void scatter() {
    for (int i = 0; i < this.posX.length; ++i) {
      this.posX[i] = this.random.nextDouble();
      this.posY[i] = this.random.nextDouble();
      this.velX[i] = randomVelocity();
      this.velY[i] = randomVelocity();
    }
    replanIfSyncOn();
  }

  /** Random signed velocity component in [VEL_MIN, 1] canvas-spans/sec at cap=1 */
  private double randomVelocity() {
    final double magnitude = VEL_MIN + this.random.nextDouble() * (1 - VEL_MIN);
    return this.random.nextBoolean() ? magnitude : -magnitude;
  }

  private void reverse() {
    for (int i = 0; i < this.velX.length; ++i) {
      this.velX[i] = -this.velX[i];
      this.velY[i] = -this.velY[i];
    }
    replanIfSyncOn();
  }

  private void hueJump() {
    this.hueOffset = (this.hueOffset + 1) % this.shapeColor.length;
  }

  // ---- Render -------------------------------------------------------------------

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setColors(LXColor.BLACK);

    final Geometry geom = this.geometry.getEnum();
    boolean geomChanged = false;
    if (geom != this.lastGeometry) {
      // Fresh start on the new topology; stale trails from the old mode vanish
      this.facesCanvas.fill(LXColor.BLACK);
      this.ringCanvas.fill(LXColor.BLACK);
      this.cylinderCanvas.fill(LXColor.BLACK);
      this.lastGeometry = geom;
      geomChanged = true;
    }
    final SurfaceCanvas canvas = canvasFor(geom);
    final boolean wrapX = (geom != Geometry.FACES);

    // Speed: energy sets the traversal cap; audio level breathes +/-30% around a
    // nominal that is cap/1.3, so even at full level the cap is never exceeded.
    // With the Audio knob at 0, level reads 0 and the rate is a steady 0.7x nominal.
    final double e = this.energy.getValue();
    final double capSpansPerSec = 1 / Ranges.lin(e, TRAVERSE_SEC_AMBIENT, TRAVERSE_SEC_PEAK);
    final double audioBreath = 1 + SPEED_AUDIO_DEPTH * (2 * LXUtils.constrain(this.audio.level, 0, 1) - 1);
    this.rateSpansPerSec = capSpansPerSec * this.speed.getValue() * audioBreath / (1 + SPEED_AUDIO_DEPTH);

    // Tempo sync: re-plan the whole flock on sync engage, TempoDiv or geometry
    // change, and on every division crossing (the crossing cadence cheaply
    // absorbs live BPM changes and speed/energy knob moves between bounces).
    // Individual bounces re-plan their own vertex axis inside advance().
    final boolean syncOn = this.sync.isOn();
    final Tempo.Division division = this.tempoDiv.getEnum();
    if (syncOn) {
      final boolean crossedNow = this.tempoLock.crossed(division);
      if (crossedNow || !this.wasSync || (division != this.lastDivision) || geomChanged) {
        replanAll(wrapX);
      }
    } else if (this.wasSync) {
      resetSyncScales(); // Sync off: free-running timing, bit-identical to no sync
    }
    this.wasSync = syncOn;
    this.lastDivision = division;

    advance(this.rateSpansPerSec * deltaMs * 0.001, wrapX, syncOn);

    // Trails: decay once per frame (never clear); treble subtly shortens them
    double halfLifeMs = Ranges.exp(this.trails.getValue(), TRAIL_HALF_LIFE_MIN_MS, TRAIL_HALF_LIFE_MAX_MS);
    halfLifeMs /= 1 + TREBLE_TRAIL_SHORTEN * LXUtils.constrain(this.audio.trebleRatio - 1, 0, 2);
    canvas.decay(Math.pow(0.5, deltaMs / halfLifeMs));

    // Bass-hit vertex flash envelope, armed to the audio depth so the pop
    // scales with the knob (depth 1 = full white, matching pre-knob behavior)
    if (this.audio.bassHit()) {
      this.flash = this.audio.depth();
    } else {
      this.flash = Math.max(0, this.flash - deltaMs / FLASH_DECAY_MS);
    }

    computeColors();
    drawShapes(canvas, wrapX, e);

    // Output: paint the active surface exterior, then mirror to its interior.
    // The other component stays dark (see Mystify.md for the rationale).
    switch (geom) {
      case FACES:
        copyToFrontFace();
        copyCubeFace(Apotheneum.cube.exterior.front); // replicates to all 8 faces
        break;
      case CUBE_RING:
        canvas.copyTo(Apotheneum.cube.exterior, this.colors);
        copyCubeExterior();
        break;
      case CYLINDER:
        canvas.copyTo(Apotheneum.cylinder.exterior, this.colors);
        copyCylinderExterior();
        break;
    }
  }

  private SurfaceCanvas canvasFor(Geometry geom) {
    switch (geom) {
      case FACES: return this.facesCanvas;
      case CUBE_RING: return this.ringCanvas;
      default: return this.cylinderCanvas;
    }
  }

  // ---- Tempo sync planning ------------------------------------------------------

  /**
   * Rate scale for one vertex axis so its next wall hit lands on a boundary of
   * the current TempoDiv. msUntil is computed at the axis's base (unscaled)
   * rate, and the result REPLACES the previous scale — scales never compound.
   *
   * Traversal-cap guarantee: the upper clamp is min(1.4, 1/|vel|). Velocity
   * magnitudes are in cap units (<= 1), so |vel| * scale <= 1 always — a
   * retimed vertex can never exceed the energy-set traversal cap. The fastest
   * vertices (|vel| near 1) can therefore only be retimed by slowing down.
   */
  private double planScale(double pos, double vel) {
    final double axisSpeed = Math.abs(vel) * this.rateSpansPerSec; // spans/sec, unscaled
    if (!(axisSpeed > 0)) {
      return 1; // degenerate (also construction time, before the first frame)
    }
    final double dist = (vel > 0) ? (1 - pos) : pos;
    final double msUntil = 1000 * dist / axisSpeed;
    final double maxScale = Math.min(TempoLock.DEFAULT_MAX_SCALE, 1 / Math.abs(vel));
    return this.tempoLock.retime(msUntil, this.tempoDiv.getEnum(), TempoLock.DEFAULT_MIN_SCALE, maxScale);
  }

  /** Re-plan every vertex's sync scales. Event-rate only: sync engage, TempoDiv
   *  or geometry change, scatter/reverse, and each division crossing. On the
   *  wrapping topologies x has no bounce event, so its scale stays 1. */
  private void replanAll(boolean wrapX) {
    for (int i = 0; i < this.posX.length; ++i) {
      this.syncScaleX[i] = wrapX ? 1 : planScale(this.posX[i], this.velX[i]);
      this.syncScaleY[i] = planScale(this.posY[i], this.velY[i]);
    }
  }

  private void resetSyncScales() {
    Arrays.fill(this.syncScaleX, 1);
    Arrays.fill(this.syncScaleY, 1);
  }

  /** Trigger-handler hook: velocities just changed, so any active plan is stale */
  private void replanIfSyncOn() {
    if (this.sync.isOn()) {
      replanAll(this.geometry.getEnum() != Geometry.FACES);
    }
  }

  // ---- Simulation ----------------------------------------------------------------

  /** Integrate vertex motion; du is this frame's travel in canvas-spans at
   *  velocity magnitude 1. With sync on, per-axis sync scales retime each
   *  vertex so wall hits land on the tempo grid, and every bounce immediately
   *  re-plans that vertex's axis toward its next wall hit. */
  private void advance(double du, boolean wrapX, boolean syncOn) {
    for (int i = 0; i < this.posX.length; ++i) {
      this.posX[i] += this.velX[i] * this.syncScaleX[i] * du;
      this.posY[i] += this.velY[i] * this.syncScaleY[i] * du;
      if (wrapX) {
        this.posX[i] -= Math.floor(this.posX[i]);
      } else if (this.posX[i] < 0) {
        this.posX[i] = -this.posX[i];
        this.velX[i] = Math.abs(this.velX[i]);
        if (syncOn) {
          this.syncScaleX[i] = planScale(this.posX[i], this.velX[i]);
        }
      } else if (this.posX[i] > 1) {
        this.posX[i] = 2 - this.posX[i];
        this.velX[i] = -Math.abs(this.velX[i]);
        if (syncOn) {
          this.syncScaleX[i] = planScale(this.posX[i], this.velX[i]);
        }
      }
      // Vertical always bounces off top and bottom
      if (this.posY[i] < 0) {
        this.posY[i] = -this.posY[i];
        this.velY[i] = Math.abs(this.velY[i]);
        if (syncOn) {
          this.syncScaleY[i] = planScale(this.posY[i], this.velY[i]);
        }
      } else if (this.posY[i] > 1) {
        this.posY[i] = 2 - this.posY[i];
        this.velY[i] = -Math.abs(this.velY[i]);
        if (syncOn) {
          this.syncScaleY[i] = planScale(this.posY[i], this.velY[i]);
        }
      }
    }
  }

  /**
   * Refresh the two hues per polyline from the active palette swatch, rotated by
   * hueOffset. With an empty swatch, fall back to four evenly-spaced saturated
   * perceptual hues so the pattern is never colorless.
   */
  private void computeColors() {
    final List<LXDynamicColor> swatch = this.lx.engine.palette.swatch.colors;
    final int n = swatch.size();
    for (int k = 0; k < this.shapeColor.length; ++k) {
      final int idx = this.hueOffset + k;
      this.shapeColor[k] = (n > 0)
        ? swatch.get(idx % n).getColor()
        : PerceptualHue.color((idx % this.shapeColor.length) / (float) this.shapeColor.length);
    }
  }

  private void drawShapes(SurfaceCanvas canvas, boolean wrapX, double energyValue) {
    final int w = canvas.width;
    final int h = canvas.height;
    final int nShapes = this.shapes.getValuei();
    final int nVerts = this.vertices.getValuei();

    for (int s = 0; s < nShapes; ++s) {
      final int base = s * MAX_VERTS;
      final int colorA = this.shapeColor[2 * s];
      final int colorB = this.shapeColor[2 * s + 1];
      for (int i = 0; i < nVerts; ++i) {
        final int j = (i + 1) % nVerts;
        final int x0 = pixelX(this.posX[base + i], w, wrapX);
        final int y0 = pixelY(this.posY[base + i], h);
        int x1 = pixelX(this.posX[base + j], w, wrapX);
        final int y1 = pixelY(this.posY[base + j], h);
        if (wrapX) {
          // Take the short way around the ring; SurfaceCanvas wraps x for us
          final int dx = x1 - x0;
          if (dx > w / 2) {
            x1 -= w;
          } else if (dx < -w / 2) {
            x1 += w;
          }
        }
        canvas.line(x0, y0, x1, y1, ((i & 1) == 0) ? colorA : colorB);
      }
    }

    // Bass-hit flash: a bright white cross at every active vertex, fading ~0.5s.
    // Drawn onto the canvas so the pop inherits the trail decay.
    if (this.flash > 0.01) {
      final int flashColor = LXColor.gray(100 * this.flash * Ranges.lin(energyValue, 0.6, 1.0));
      for (int s = 0; s < nShapes; ++s) {
        final int base = s * MAX_VERTS;
        for (int i = 0; i < nVerts; ++i) {
          final int px = pixelX(this.posX[base + i], w, wrapX);
          final int py = pixelY(this.posY[base + i], h);
          canvas.set(px, py, flashColor);
          canvas.set(px, py - 1, flashColor);
          canvas.set(px, py + 1, flashColor);
          // x-neighbors: SurfaceCanvas.set wraps x, which is wrong on the
          // bounce topology — a face-edge flash must not smear onto the
          // opposite edge of the face
          if (wrapX || (px > 0)) {
            canvas.set(px - 1, py, flashColor);
          }
          if (wrapX || (px < w - 1)) {
            canvas.set(px + 1, py, flashColor);
          }
        }
      }
    }
  }

  private static int pixelX(double xn, int width, boolean wrapX) {
    // Wrap mode spans the full circumference; bounce mode pins endpoints to edges
    return wrapX ? (int) Math.round(xn * width) : (int) Math.round(xn * (width - 1));
  }

  private static int pixelY(double yn, int height) {
    return (int) Math.round(yn * (height - 1));
  }

  /** FACES mode: blit the 50x45 canvas onto the front exterior face, door-guarded */
  private void copyToFrontFace() {
    final Apotheneum.Cube.Face front = Apotheneum.cube.exterior.front;
    for (int x = 0; x < this.facesCanvas.width; ++x) {
      final Apotheneum.Column column = front.columns[x];
      final int h = Math.min(this.facesCanvas.height, column.points.length);
      for (int y = 0; y < h; ++y) {
        this.colors[column.points[y].index] = this.facesCanvas.get(x, y);
      }
    }
  }
}
