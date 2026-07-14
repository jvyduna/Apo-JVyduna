package apotheneum.jvyduna.arrange;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import heronarts.glx.event.MouseEvent;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.LX;
import heronarts.lx.studio.LXStudio;

/**
 * The keyboard-shortcut reference panel, toggled from the status bar's
 * keyboard icon. Content is parsed at build time from the bundled
 * docs/Composition-Guide.md (jar resource /arrange/Composition-Guide.md) —
 * the guide stays the single source of truth; every markdown table under a
 * heading becomes a titled section of key/action rows.
 *
 * Shown via ui.showContextOverlay, which provides Escape and click-outside
 * dismissal for free; clicking the panel itself also dismisses it.
 */
class ShortcutsPanel extends UI2dContainer {

  private static final String RESOURCE = "/arrange/Composition-Guide.md";

  private static final float WIDTH = 640;
  private static final float PAD = 16;
  private static final float ROW_HEIGHT = 15;
  private static final float HEADER_HEIGHT = 24;
  private static final float SECTION_GAP = 8;
  private static final float KEY_COLUMN = 200;

  private static final class Section {
    final String title;
    final List<String[]> rows = new ArrayList<>();

    Section(String title) {
      this.title = title;
    }
  }

  private final LXStudio.UI lxUi;
  private final List<Section> sections;

  ShortcutsPanel(LXStudio.UI ui) {
    super(0, 0, WIDTH, 100);
    this.lxUi = ui;
    this.sections = parseGuide();
    float h = PAD;
    for (Section section : this.sections) {
      h += HEADER_HEIGHT + section.rows.size() * ROW_HEIGHT + SECTION_GAP;
    }
    setSize(WIDTH, h + PAD - SECTION_GAP);
    setBackgroundColor(ui.theme.deviceBackgroundColor);
    setBorderColor(ui.theme.controlBorderColor);
    setBorderRounding(4);
  }

  /** Centers the panel in the given window dimensions and returns this. */
  ShortcutsPanel center(float windowWidth, float windowHeight) {
    setPosition(
      Math.max(0, (windowWidth - getWidth()) / 2),
      Math.max(0, (windowHeight - getHeight()) / 2));
    return this;
  }

  boolean isShowing() {
    return getContainer() != null;
  }

  @Override
  protected void onMousePressed(MouseEvent mouseEvent, float mx, float my) {
    // Clicking the panel dismisses it (in addition to Escape/click-outside)
    mouseEvent.consume();
    this.lxUi.clearContextOverlay(this);
  }

  // Neutral text colors (request: no periwinkle) — white for the left
  // column (the shortcut/action name), gray for the right-side explanation
  private static final int COLOR_KEY = 0xffffffff;
  private static final int COLOR_DESCRIPTION = 0xffcccccc;
  private static final int COLOR_HEADER = 0xffcccccc;

  @Override
  protected void onDraw(heronarts.glx.ui.UI ui, VGraphics vg) {
    float y = PAD;
    for (Section section : this.sections) {
      vg.beginPath();
      vg.fillColor(COLOR_HEADER);
      vg.fontFace(ui.theme.getLabelFont());
      vg.fontSize(13);
      vg.textAlign(VGraphics.Align.LEFT, VGraphics.Align.TOP);
      vg.text(PAD, y + 3, section.title);
      vg.fill();
      y += HEADER_HEIGHT;
      for (String[] row : section.rows) {
        vg.beginPath();
        vg.fillColor(COLOR_KEY);
        vg.fontFace(ui.theme.getLabelFont());
        vg.fontSize(11);
        vg.textAlign(VGraphics.Align.LEFT, VGraphics.Align.TOP);
        vg.text(PAD + 8, y + 2, row[0]);
        vg.fill();
        vg.beginPath();
        vg.fillColor(COLOR_DESCRIPTION);
        vg.fontFace(ui.theme.getLabelFont());
        vg.fontSize(11);
        vg.textAlign(VGraphics.Align.LEFT, VGraphics.Align.TOP);
        vg.text(PAD + 8 + KEY_COLUMN, y + 2, row[1]);
        vg.fill();
        y += ROW_HEIGHT;
      }
      y += SECTION_GAP;
    }
  }

  /**
   * Extracts every |key|action| table row from the bundled guide, grouped
   * under the nearest preceding heading. Markdown emphasis/backticks are
   * stripped; header and |---| separator rows are skipped.
   */
  private List<Section> parseGuide() {
    final List<Section> parsed = new ArrayList<>();
    try (InputStream in = ShortcutsPanel.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing jar resource " + RESOURCE);
      }
      final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String heading = "Shortcuts";
      Section current = null;
      // A markdown table's header row is the row immediately before the
      // |---| separator — buffer one row so headers (Shortcut/Action,
      // Action/How, ...) can be discarded and never rendered
      String[] pending = null;
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        final boolean isTableRow = line.startsWith("|");
        if (!isTableRow || line.startsWith("#")) {
          if (pending != null && current != null) {
            current.rows.add(pending);
          }
          pending = null;
          if (line.startsWith("#")) {
            heading = line.replaceFirst("^#+\\s*", "");
            current = null;
          }
          continue;
        }
        if (line.contains("---")) {
          pending = null; // the buffered row was a column-header row
          continue;
        }
        final String[] cells = line.split("\\|");
        if (cells.length < 3) {
          continue;
        }
        if (pending != null) {
          if (current == null) {
            current = new Section(heading);
            parsed.add(current);
          }
          current.rows.add(pending);
        }
        if (current == null) {
          current = new Section(heading);
          parsed.add(current);
        }
        pending = new String[] { clean(cells[1]), clean(cells[2]) };
      }
      if (pending != null && current != null) {
        current.rows.add(pending);
      }
    } catch (Exception x) {
      LX.error(x, "[ArrangeTools] Could not parse bundled composition guide");
      Section fallback = new Section("Shortcut reference unavailable");
      fallback.rows.add(new String[] { "", "See docs/Composition-Guide.md (guide failed to parse from jar)" });
      parsed.add(fallback);
    }
    return parsed;
  }

  private static String clean(String cell) {
    return cell.trim()
      .replace("`", "")
      .replace("**", "")
      .replace("*", "");
  }

}
