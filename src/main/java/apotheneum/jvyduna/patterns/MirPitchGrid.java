package apotheneum.jvyduna.patterns;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * MIR-verify pitch grid: displays a single MIDI note as a lit cell in a
 * pitch-class (12 columns) x octave (rows) grid, masked to a horizontal band
 * of the channel's view.
 *
 * Driven by a stepped Discrete clip lane on {@code note}: 0 = note off
 * (dark), otherwise the MIDI note number (normalized as midi/127). The
 * pattern derives column = note % 12 and row = (note - midiLo) / 12
 * (bottom-up), so a generated composition needs exactly one lane per stem.
 *
 * Meant to run on a single cube-face view (Left/Right/Front/Back) with
 * RELATIVE normalization; the horizontal axis is auto-detected from the
 * view's bounding box (Front/Back faces span X, Left/Right span Z).
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("MIR Pitch Grid")
@LXComponent.Description("MIR verification: one MIDI note as a lit cell in a pitch-class x octave grid within a horizontal band of the view")
public class MirPitchGrid extends LXPattern {

  public final DiscreteParameter note =
    new DiscreteParameter("Note", 0, 0, 128)
    .setDescription("Current MIDI note (0 = off); clip-lane target");

  public final DiscreteParameter midiLo =
    new DiscreteParameter("Lo", 24, 0, 128)
    .setDescription("Lowest MIDI note of the grid (bottom row's C-boundary reference)");

  public final DiscreteParameter midiHi =
    new DiscreteParameter("Hi", 96, 0, 128)
    .setDescription("Highest MIDI note of the grid");

  public final CompoundParameter yMin =
    new CompoundParameter("Y Min", 0)
    .setDescription("Bottom of this grid's band within the view (normalized)");

  public final CompoundParameter yMax =
    new CompoundParameter("Y Max", 1)
    .setDescription("Top of this grid's band within the view (normalized)");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 0, 0, 360)
    .setDescription("Hue of the lit cell");

  public final BooleanParameter flip =
    new BooleanParameter("Flip", false)
    .setDescription("Mirror the horizontal axis (per-face calibration)");

  public MirPitchGrid(LX lx) {
    super(lx);
    addParameter("note", this.note);
    addParameter("midiLo", this.midiLo);
    addParameter("midiHi", this.midiHi);
    addParameter("yMin", this.yMin);
    addParameter("yMax", this.yMax);
    addParameter("hue", this.hue);
    addParameter("flip", this.flip);
  }

  @Override
  protected void run(double deltaMs) {
    final int midi = this.note.getValuei();
    if (midi <= 0) {
      setColors(LXColor.BLACK);
      return;
    }

    final int lo = this.midiLo.getValuei();
    final int hi = Math.max(this.midiHi.getValuei(), lo + 1);
    final int clamped = Math.max(lo, Math.min(hi, midi));
    final int rowCount = ((hi - lo) / 12) + 1;
    final int row = (clamped - lo) / 12;
    final int col = clamped % 12;

    final float y0 = this.yMin.getValuef();
    final float y1 = Math.max(this.yMax.getValuef(), y0 + 1e-4f);
    final float rowH = (y1 - y0) / rowCount;
    final float cellY0 = y0 + row * rowH;
    final float cellY1 = cellY0 + rowH;
    final float cellX0 = col / 12f;
    final float cellX1 = (col + 1) / 12f;

    // Front/Back faces span X, Left/Right faces span Z: use the wider axis
    final boolean useX = this.model.xRange >= this.model.zRange;
    final boolean flip = this.flip.isOn();
    final int color = LXColor.hsb(this.hue.getValue(), 100, 100);

    for (LXPoint p : this.model.points) {
      float h = useX ? p.xn : p.zn;
      if (flip) {
        h = 1 - h;
      }
      final boolean lit =
        (h >= cellX0) && (h < cellX1) &&
        (p.yn >= cellY0) && (p.yn < cellY1);
      this.colors[p.index] = lit ? color : LXColor.BLACK;
    }
  }
}
