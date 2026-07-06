package apotheneum.jvyduna.patterns;

import apotheneum.Apotheneum;
import apotheneum.ApotheneumPattern;
import apotheneum.jvyduna.util.AudioReactive;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;

/**
 * Development smoke pattern for the screensaver series' audio plumbing.
 *
 * Cube: the GraphicMeter bands (16 by default) as vertical bars around the
 * exterior ring (hue by band), with a white flash on the top two rings on
 * bassHit. Cylinder: four wide bars — level, bass, mid, treble.
 *
 * No Audio depth knob is attached, so AudioReactive runs at full depth and
 * the scope always displays the real signal.
 *
 * If this pattern moves with music, AudioReactive is wired correctly and the
 * screensaver patterns in this series can trust their audio taps.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Audio Scope")
@LXComponent.Description("Dev scope: FFT bands on the cube, bass/mid/treble/level bars on the cylinder, flash on bass hits")
public class AudioScope extends ApotheneumPattern {

  private final AudioReactive audio;

  /** Bass-hit flash brightness envelope, 0..1 */
  private double flash = 0;

  public AudioScope(LX lx) {
    super(lx);
    this.audio = new AudioReactive(lx);
  }

  @Override
  protected void render(double deltaMs) {
    this.audio.tick(deltaMs);
    setApotheneumColor(LXColor.BLACK);

    if (this.audio.bassHit()) {
      this.flash = 1;
    } else {
      this.flash = Math.max(0, this.flash - deltaMs / 250);
    }

    renderCube();
    renderCylinder();

    copyCubeExterior();
    copyCylinderExterior();
  }

  private void renderCube() {
    final Apotheneum.Cube.Orientation cube = Apotheneum.cube.exterior;
    final int width = cube.width();   // 200
    final int height = cube.height(); // 45
    final int numBands = this.audio.numBands();
    final int barWidth = width / numBands;

    for (int band = 0; band < numBands; ++band) {
      final int barHeight = (int) (this.audio.band(band) * (height - 2));
      final int color = LXColor.hsb(band * 360.0 / numBands, 100, 100);
      // Leave a 1-column gap between bars so bands read as separate at LED scale
      for (int x = band * barWidth; x < (band + 1) * barWidth - 1; ++x) {
        final Apotheneum.Column column = cube.column(x);
        final int columnHeight = column.points.length;
        for (int i = 0; i < barHeight && i < columnHeight; ++i) {
          colors[column.points[columnHeight - 1 - i].index] = color;
        }
      }
    }

    // Bass-hit flash: top two rings of the cube
    if (this.flash > 0) {
      final int flashColor = LXColor.gray(100 * this.flash);
      for (int x = 0; x < width; ++x) {
        final Apotheneum.Column column = cube.column(x);
        colors[column.points[0].index] = flashColor;
        if (column.points.length > 1) {
          colors[column.points[1].index] = flashColor;
        }
      }
    }
  }

  private void renderCylinder() {
    final Apotheneum.Cylinder.Orientation cylinder = Apotheneum.cylinder.exterior;
    final int width = cylinder.width(); // 120
    final int quadrant = width / 4;

    drawCylinderBar(cylinder, 0, quadrant, this.audio.level, LXColor.gray(80));
    drawCylinderBar(cylinder, quadrant, quadrant, this.audio.bass, LXColor.hsb(0, 100, 100));
    drawCylinderBar(cylinder, 2 * quadrant, quadrant, this.audio.mid, LXColor.hsb(120, 100, 100));
    drawCylinderBar(cylinder, 3 * quadrant, quadrant, this.audio.treble, LXColor.hsb(240, 100, 100));
  }

  private void drawCylinderBar(Apotheneum.Cylinder.Orientation cylinder, int startColumn, int barWidth, double value, int color) {
    for (int x = startColumn; x < startColumn + barWidth - 2; ++x) {
      final Apotheneum.Column column = cylinder.column(x);
      final int columnHeight = column.points.length;
      final int barHeight = (int) (value * columnHeight);
      for (int i = 0; i < barHeight; ++i) {
        colors[column.points[columnHeight - 1 - i].index] = color;
      }
    }
  }
}
