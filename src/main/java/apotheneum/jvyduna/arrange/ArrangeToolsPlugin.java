package apotheneum.jvyduna.arrange;

import heronarts.lx.LX;
import heronarts.lx.LXPlugin;
import heronarts.lx.studio.LXStudio;

/**
 * Arrange Tools: composition/arrange-timeline editing helpers the arrange
 * host lacks — cross-lane range shift and event-range copy/paste. See
 * ArrangeTools.md (design doc) in this directory.
 *
 * Enable in Chromatik under CONTENT → PLUGINS. All features are editor-only:
 * they emit ordinary undoable clip-event commands inside keyboard-shortcut
 * handlers and never run during playback; saved .lxp data is
 * indistinguishable from hand-edited.
 *
 * This class deliberately references NO arrange-branch host types in its own
 * signatures or fields, so it always classloads. The controller (which does)
 * is constructed inside a Throwable guard: on a stock/non-arrange host the
 * resulting LinkageError is caught and the plugin stays inert.
 */
@LXPlugin.Name("Arrange Tools")
public class ArrangeToolsPlugin implements LXStudio.Plugin {

  @Override
  public void initialize(LX lx) {}

  @Override
  public void initializeUI(LXStudio lx, LXStudio.UI ui) {}

  @Override
  public void onUIReady(LXStudio lx, LXStudio.UI ui) {
    try {
      new ArrangeToolsController(lx, ui).register();
    } catch (Throwable t) {
      LX.error(t, "[ArrangeTools] Host incompatible with Arrange Tools; features disabled");
    }
  }

}
