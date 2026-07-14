package apotheneum.jvyduna.arrange;

import java.util.ArrayList;
import java.util.List;

import heronarts.lx.LX;
import heronarts.lx.command.LXCommand;

/**
 * Groups multiple LXCommands into a single undoable operation. LX has no
 * built-in composite/batch command (verified on arrange HEAD 2026-07-08), so
 * multi-lane edits define their own.
 *
 * Atomicity: LXCommandEngine.perform clears both undo/redo stacks when a
 * command throws, so a half-performed composite would leave lanes
 * inconsistently edited with no way back. On a mid-list failure we undo the
 * already-performed prefix (in reverse) before rethrowing.
 */
class CompositeCommand extends LXCommand {

  private final String description;
  private final List<LXCommand> children;

  CompositeCommand(String description, List<? extends LXCommand> children) {
    this.description = description;
    this.children = new ArrayList<>(children);
  }

  boolean isEmpty() {
    return this.children.isEmpty();
  }

  @Override
  public String getDescription() {
    return this.description;
  }

  @Override
  public void perform(LX lx) throws InvalidCommandException {
    int performed = 0;
    // Roll back on RuntimeException too (e.g. an NPE from a stale
    // ComponentReference) — LXCommandEngine catches those the same way and
    // clears the undo stacks, so any partial state would be unrecoverable
    try {
      for (LXCommand child : this.children) {
        child.perform(lx);
        ++performed;
      }
    } catch (InvalidCommandException icx) {
      rollback(lx, performed);
      throw icx;
    } catch (RuntimeException rx) {
      rollback(lx, performed);
      throw rx;
    }
  }

  /** Undoes the performed prefix, in reverse, so the composite is all-or-nothing. */
  private void rollback(LX lx, int performed) {
    for (int i = performed - 1; i >= 0; --i) {
      try {
        this.children.get(i).undo(lx);
      } catch (Exception x) {
        LX.error(x, "[ArrangeTools] Failed rolling back partial composite: " + this.description);
      }
    }
  }

  @Override
  public void undo(LX lx) throws InvalidCommandException {
    // Reverse order: inserts are undone before a RemoveRange preState reload,
    // and later-performed commands never invalidate earlier undo indices.
    for (int i = this.children.size() - 1; i >= 0; --i) {
      this.children.get(i).undo(lx);
    }
  }

}
