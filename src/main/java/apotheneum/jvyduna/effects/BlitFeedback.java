package apotheneum.jvyduna.effects;

import java.util.Arrays;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumEffect;
import apotheneum.jvyduna.util.TempoLock;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.Tempo;
import heronarts.lx.blend.LXBlend;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.utils.LXUtils;

/**
 * Video-feedback glitch effect. While TriGate is held, a shape-masked region
 * of the pixel buffer is sampled at a crawling cursor and restamped one Step
 * along Angle on every Tempo tick — each copy baking in the previous copy's
 * result, like pointing a camera at its own monitor. Releasing the gate lets
 * the accumulated copies fade to transparent over a musical duration (Fade).
 *
 * Each of the four surfaces (cube/cylinder × exterior/interior) gets its own
 * persistent alpha-ARGB feedback layer that wraps horizontally and never
 * vertically. See BlitFeedback.md (beside this file) for the full design note.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Blit Feedback")
@LXComponent.Description("Video-feedback glitch: a shape-masked region crawls across each surface, restamping itself on tempo ticks")
public class BlitFeedback extends ApotheneumEffect {

  public enum Shape {
    RECTANGLE("Rect"),
    OVAL("Oval"),
    TRIANGLE("Tri");

    public final String label;

    private Shape(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public enum SampleSource {
    COMPOSITE("Composite"),
    BUFFER("Buffer");

    public final String label;

    private SampleSource(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public enum Mode {
    OVER("Over", LXColor::lerp),
    ADD("Add", LXColor::add),
    SCREEN("Screen", LXColor::screen),
    LIGHTEST("Lightest", LXColor::lightest),
    DIFFERENCE("Difference", LXColor::difference);

    public final String label;
    public final LXBlend.FunctionalBlend.BlendFunction function;

    private Mode(String label, LXBlend.FunctionalBlend.BlendFunction function) {
      this.label = label;
      this.function = function;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public final BooleanParameter triGate =
    new BooleanParameter("TriGate", false)
    .setMode(BooleanParameter.Mode.MOMENTARY)
    .setDescription("Hold to stamp feedback copies on the tempo grid; release to fade");

  public final EnumParameter<Tempo.Division> tempoDiv =
    new EnumParameter<Tempo.Division>("Tempo", Tempo.Division.SIXTEENTH)
    .setDescription("Tempo division between copies while the gate is held");

  public final EnumParameter<Tempo.Division> fade =
    new EnumParameter<Tempo.Division>("Fade", Tempo.Division.WHOLE)
    .setDescription("Musical duration for the copies to fade fully out once the gate is released");

  public final EnumParameter<SampleSource> sampleSrc =
    new EnumParameter<SampleSource>("SampleSrc", SampleSource.COMPOSITE)
    .setDescription("Composite: each copy samples the live output; Buffer: copies re-sample only prior copies");

  public final EnumParameter<Shape> shape =
    new EnumParameter<Shape>("Shape", Shape.RECTANGLE)
    .setDescription("Shape of the masked region that gets copied");

  public final CompoundParameter size =
    new CompoundParameter("Size", 0.33)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Post-rotation major dimension of the mask; 100% = surface height (45px cube, 43px cylinder)");

  public final CompoundParameter aspRatio =
    new CompoundParameter("AspRatio", 1, 0.1, 2)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Minor dimension as a fraction of Size (triangle: height relative to base width)");

  public final CompoundParameter blitAng =
    new CompoundParameter("BlitAng", 0, -180, 180)
    .setUnits(CompoundParameter.Units.DEGREES)
    .setWrappable(true)
    .setDescription("Rotation of the mask shape");

  public final CompoundParameter sourceX =
    new CompoundParameter("SourceX", 0)
    .setWrappable(true)
    .setDescription("Horizontal center of the source region; 0 = front face center, 0.25 = next face, wraps");

  public final CompoundParameter sourceY =
    new CompoundParameter("SourceY", 0.5)
    .setDescription("Vertical center of the source region; 0 = top");

  public final CompoundParameter step =
    new CompoundParameter("Step", 3, 0, 24)
    .setDescription("Pixels each copy is translated per tick");

  public final CompoundParameter angle =
    new CompoundParameter("Angle", 0, -180, 180)
    .setUnits(CompoundParameter.Units.DEGREES)
    .setWrappable(true)
    .setDescription("Direction the copies travel; 0 = right, positive = counterclockwise");

  public final BooleanParameter border =
    new BooleanParameter("Border", false)
    .setDescription("Stamp a 1px outline of the mask shape with each copy");

  public final CompoundParameter borBright =
    new CompoundParameter("BorBright", 1, 0, 2)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Border color: 0-100% dims the first palette color, 100-200% brightens toward pure white");

  public final EnumParameter<Mode> blend =
    new EnumParameter<Mode>("Blend", Mode.OVER)
    .setDescription("How the feedback copies composite over the live output");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1)
    .setUnits(CompoundParameter.Units.PERCENT_NORMALIZED)
    .setDescription("Amount of the feedback layer blended over the live output");

  public final BooleanParameter cubeExt = new BooleanParameter("CubeExt", true)
    .setDescription("Apply to the cube exterior");

  public final BooleanParameter cubeInt = new BooleanParameter("CubeInt", true)
    .setDescription("Apply to the cube interior");

  public final BooleanParameter cylExt = new BooleanParameter("CylExt", true)
    .setDescription("Apply to the cylinder exterior");

  public final BooleanParameter cylInt = new BooleanParameter("CylInt", true)
    .setDescription("Apply to the cylinder interior");

  /**
   * Scratch raster for one copy: sample pass writes here, stamp pass reads.
   * 128x128 covers the worst-case rotated mask bounding box (45px major at
   * AspRatio 200% -> ~101px diagonal). Also decouples the sample region from
   * the paste region, which overlap whenever Step < Size.
   */
  private static final int SCRATCH_DIM = 128;
  private static final int MAX_EXT = SCRATCH_DIM / 2 - 1;

  /**
   * Per-surface feedback layer: a persistent straight-ARGB raster
   * (0x00000000 = empty) plus the crawling cursor, in this surface's pixel
   * space (y = 0 is the top row). X wraps, Y never does.
   */
  private final class Layer {

    private final int width, height;
    private final int[] buf;
    private final double srcXOffset;
    private final BooleanParameter surface;

    private Apotheneum.Orientation orientation;
    private double cx, cy;

    private Layer(int width, int height, double srcXOffset, BooleanParameter surface) {
      this.width = width;
      this.height = height;
      this.buf = new int[width * height];
      this.srcXOffset = srcXOffset;
      this.surface = surface;
    }

    private boolean active() {
      return (this.orientation != null) && this.surface.isOn();
    }

    private void resetCursor() {
      this.cx = wrapX(sourceX.getValue() * this.width + this.srcXOffset, this.width);
      this.cy = sourceY.getValue() * (this.height - 1);
    }
  }

  private final Layer[] layers;
  private final int[] scratch = new int[SCRATCH_DIM * SCRATCH_DIM];
  private final TempoLock tempoLock;

  private boolean prevGate = false;
  private boolean firstGrab = false;

  // Mask state for the current tick/layer, shared by inside()/isBorder()
  private Shape maskShape = Shape.RECTANGLE;
  private double cosA = 1, sinA = 0;
  private double maskMajor = 1, maskMinor = 1;
  private int borderColor = LXColor.WHITE;

  public BlitFeedback(LX lx) {
    super(lx);
    this.tempoLock = new TempoLock(lx);
    this.layers = new Layer[] {
      // SourceX offsets center x=0 on the cube's front face (25 of 50 cols)
      // and the matching cylinder azimuth (15 of 30 cols per quarter)
      new Layer(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT, 25, this.cubeExt),
      new Layer(Apotheneum.Cube.Ring.LENGTH, Apotheneum.GRID_HEIGHT, 25, this.cubeInt),
      new Layer(Apotheneum.RING_LENGTH, Apotheneum.CYLINDER_HEIGHT, 15, this.cylExt),
      new Layer(Apotheneum.RING_LENGTH, Apotheneum.CYLINDER_HEIGHT, 15, this.cylInt)
    };
    addParameter("triGate", this.triGate);
    addParameter("tempo", this.tempoDiv);
    addParameter("fade", this.fade);
    addParameter("sampleSrc", this.sampleSrc);
    addParameter("shape", this.shape);
    addParameter("size", this.size);
    addParameter("aspRatio", this.aspRatio);
    addParameter("blitAng", this.blitAng);
    addParameter("sourceX", this.sourceX);
    addParameter("sourceY", this.sourceY);
    addParameter("step", this.step);
    addParameter("angle", this.angle);
    addParameter("border", this.border);
    addParameter("borBright", this.borBright);
    addParameter("blend", this.blend);
    addParameter("level", this.level);
    addParameter("cubeExt", this.cubeExt);
    addParameter("cubeInt", this.cubeInt);
    addParameter("cylExt", this.cylExt);
    addParameter("cylInt", this.cylInt);
  }

  @Override
  protected void onEnable() {
    super.onEnable();
    for (Layer layer : this.layers) {
      Arrays.fill(layer.buf, 0);
    }
    this.prevGate = false;
  }

  private static double wrapX(double x, int width) {
    x %= width;
    return (x < 0) ? x + width : x;
  }

  @Override
  protected void render(double deltaMs, double enabledAmount) {
    // Orientations are rebuilt on model changes; re-fetch every frame.
    // Interiors may be null (no-interior model) -> those layers stay inactive.
    this.layers[0].orientation = Apotheneum.cube.exterior;
    this.layers[1].orientation = Apotheneum.cube.interior;
    this.layers[2].orientation = Apotheneum.cylinder.exterior;
    this.layers[3].orientation = Apotheneum.cylinder.interior;

    final boolean gate = this.triGate.isOn();
    final boolean rising = gate && !this.prevGate;
    this.prevGate = gate;

    // Poll unconditionally every frame so the gate never fires a stale event
    final boolean tick = this.tempoLock.crossed(this.tempoDiv.getEnum());

    final LXBlend.FunctionalBlend.BlendFunction blendFn = this.blend.getEnum().function;
    final int levelMask = LXColor.blendMask(enabledAmount * this.level.getValue());

    if (rising) {
      for (Layer layer : this.layers) {
        layer.resetCursor();
      }
      this.firstGrab = true;
    }

    if (gate && (rising || tick)) {
      prepareTick();
      for (Layer layer : this.layers) {
        if (layer.active()) {
          blit(layer, blendFn, levelMask);
        }
      }
      this.firstGrab = false;
    }

    if (!gate) {
      fadeAll(deltaMs);
    }

    if (levelMask > 0) {
      for (Layer layer : this.layers) {
        if (layer.active()) {
          composite(layer, blendFn, levelMask);
        }
      }
    }
  }

  /**
   * Per-tick state shared across layers: mask rotation trig and border color.
   */
  private void prepareTick() {
    this.maskShape = this.shape.getEnum();
    final double theta = Math.toRadians(this.blitAng.getValue());
    this.cosA = Math.cos(theta);
    this.sinA = Math.sin(theta);

    final double b = this.borBright.getValue();
    final int base = lx.engine.palette.getColor();
    final int c = (b <= 1)
      ? LXColor.scaleBrightness(base, b)
      : LXColor.lerp(base, LXColor.WHITE, b - 1);
    this.borderColor = LXColor.ALPHA_MASK | (c & ~LXColor.ALPHA_MASK);
  }

  /**
   * One feedback copy on one layer: sample the masked region at the cursor
   * into scratch, advance the cursor one Step along Angle, stamp scratch at
   * the new position. Sample-then-stamp via scratch is required because the
   * two regions overlap whenever Step < Size.
   */
  private void blit(Layer layer, LXBlend.FunctionalBlend.BlendFunction blendFn, int levelMask) {
    this.maskMajor = Math.max(1, this.size.getValue() * layer.height);
    this.maskMinor = Math.max(1, this.aspRatio.getValue() * this.maskMajor);
    final int ext = Math.min(MAX_EXT,
      (int) Math.ceil(0.5 * Math.hypot(this.maskMajor, this.maskMinor)) + 1);

    final int sx = (int) Math.round(layer.cx);
    final int sy = (int) Math.round(layer.cy);

    final double travel = Math.toRadians(this.angle.getValue());
    layer.cx = wrapX(layer.cx + this.step.getValue() * Math.cos(travel), layer.width);
    // Buffer y grows downward; cartesian angle up is -y. Let the shape fully
    // exit the top/bottom but keep the cursor from drifting unboundedly.
    layer.cy = LXUtils.clamp(
      layer.cy - this.step.getValue() * Math.sin(travel),
      -2.0 * ext, layer.height - 1 + 2.0 * ext);

    final int px = (int) Math.round(layer.cx);
    final int py = (int) Math.round(layer.cy);

    final boolean drawBorder = this.border.isOn();
    // Composite mode always samples what the eye saw; Buffer mode does so
    // only for the first grab (the buffer is empty at gate rise), then
    // re-samples its own prior copies, fading alpha and all.
    final boolean sampleComposite =
      (this.sampleSrc.getEnum() == SampleSource.COMPOSITE) || this.firstGrab;
    final Apotheneum.Column[] cols = layer.orientation.columns();

    // Pass 1: masked sample -> scratch (alpha 0 marks "not part of this copy")
    for (int j = -ext; j <= ext; ++j) {
      final int row = (j + ext) * SCRATCH_DIM + ext;
      final int by = sy + j;
      for (int i = -ext; i <= ext; ++i) {
        int out = 0;
        if (inside(i, j)) {
          if (drawBorder && isBorder(i, j)) {
            out = this.borderColor;
          } else if ((by >= 0) && (by < layer.height)) {
            final int bx = Math.floorMod(sx + i, layer.width);
            final int buffered = layer.buf[by * layer.width + bx];
            out = sampleComposite
              ? blendFn.apply(this.colors[cols[bx].points[by].index], buffered, levelMask)
              : buffered;
          }
        }
        this.scratch[row + i] = out;
      }
    }

    // Pass 2: stamp scratch into the layer at the new cursor position
    for (int j = -ext; j <= ext; ++j) {
      final int dy = py + j;
      if ((dy < 0) || (dy >= layer.height)) {
        continue;
      }
      final int row = (j + ext) * SCRATCH_DIM + ext;
      final int dRow = dy * layer.width;
      for (int i = -ext; i <= ext; ++i) {
        final int c = this.scratch[row + i];
        if ((c >>> 24) != 0) {
          layer.buf[dRow + Math.floorMod(px + i, layer.width)] = c;
        }
      }
    }
  }

  /**
   * Mask test on buffer-space offsets from the copy center (i right, j down),
   * against the rotation and extents set up by prepareTick()/blit().
   */
  private boolean inside(double i, double j) {
    final double jc = -j;
    final double u = i * this.cosA + jc * this.sinA;
    final double v = -i * this.sinA + jc * this.cosA;
    final double halfMajor = 0.5 * this.maskMajor;
    final double halfMinor = 0.5 * this.maskMinor;
    switch (this.maskShape) {
    case OVAL:
      final double eu = u / halfMajor;
      final double ev = v / halfMinor;
      return eu * eu + ev * ev <= 1;
    case TRIANGLE:
      // Base width = major, height = minor, centered on the centroid, apex up:
      // base edge at v = -minor/3, apex at v = +2*minor/3
      final double h = this.maskMinor;
      return (v >= -h / 3) && (v <= 2 * h / 3)
          && (Math.abs(u) <= halfMajor * (2 * h / 3 - v) / h);
    default:
      return (Math.abs(u) <= halfMajor) && (Math.abs(v) <= halfMinor);
    }
  }

  /**
   * A pixel is border iff it is inside with at least one 4-neighbor outside —
   * stays uniformly 1px under any rotation and aspect, for every shape.
   */
  private boolean isBorder(int i, int j) {
    return !(inside(i + 1, j) && inside(i - 1, j) && inside(i, j + 1) && inside(i, j - 1));
  }

  /**
   * Exponential alpha-only fade sized so a full-alpha pixel is culled after
   * one Fade division at the current tempo. RGB is left untouched: every LX
   * blend function scales the src contribution by its alpha byte, so ghosts
   * keep their hue while going transparent under all Blend modes. Runs on all
   * four layers (even toggled-off surfaces) so stale content never pops back.
   */
  private void fadeAll(double deltaMs) {
    final double fadeMs = Math.max(1, this.tempoLock.divisionMs(this.fade.getEnum()));
    final int scale256 = (int) (Math.pow(1.0 / 255.0, deltaMs / fadeMs) * 256);
    for (Layer layer : this.layers) {
      final int[] buf = layer.buf;
      for (int i = 0; i < buf.length; ++i) {
        final int c = buf[i];
        final int a = c >>> 24;
        if (a == 0) {
          continue;
        }
        int a2 = (a * scale256) >> 8;
        if (a2 >= a) {
          a2 = a - 1; // integer rounding floor would otherwise stick forever
        }
        buf[i] = (a2 <= 0) ? 0 : (a2 << 24) | (c & ~LXColor.ALPHA_MASK);
      }
    }
  }

  private void composite(Layer layer, LXBlend.FunctionalBlend.BlendFunction blendFn, int levelMask) {
    final Apotheneum.Column[] cols = layer.orientation.columns();
    final int w = layer.width;
    for (int x = 0; x < w; ++x) {
      final LXPoint[] pts = cols[x].points;
      final int yMax = Math.min(layer.height, pts.length);
      for (int y = 0; y < yMax; ++y) {
        final int src = layer.buf[y * w + x];
        if ((src >>> 24) != 0) {
          final int idx = pts[y].index;
          this.colors[idx] = blendFn.apply(this.colors[idx], src, levelMask);
        }
      }
    }
  }
}
