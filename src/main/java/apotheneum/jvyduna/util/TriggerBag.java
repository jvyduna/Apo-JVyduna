package apotheneum.jvyduna.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import heronarts.lx.LX;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Meta-trigger framework for the screensaver pattern series.
 *
 * A pattern registers its normal triggers and its "jumpable" parameters, then
 * exposes a meta TriggerParameter bound to {@link #fire()}:
 *
 * <pre>
 *   private final TriggerBag bag = new TriggerBag("Mystify");
 *   public final TriggerParameter scatter = bag.register(
 *     new TriggerParameter("Scatter", this::scatter).setDescription("..."));
 *   public final TriggerParameter meta =
 *     new TriggerParameter("Meta", bag::fire).setDescription("Randomly fire a trigger or jump a parameter");
 *   // in constructor: bag.jumpable(trailDecay); bag.jumpable(speed, .1, .6);
 * </pre>
 *
 * fire() picks uniformly at random over (registered triggers + jumpables) and
 * executes exactly one action, logging it as "Meta[Name]: ..." so curation
 * sessions can correlate log lines in ~/Chromatik/Logs with what was seen.
 *
 * TriggerBag holds no serialized state; only plain registered parameters are
 * ever touched, so project files stay clean. The jumpable(...) registration
 * lines are the curation surface: each corresponds 1:1 to a row of the "Jump
 * candidates" table in the pattern's sidecar .md, where its status
 * (candidate/confirmed/dropped/re-ranged) is tracked.
 *
 * fire() is event-rate (not per-frame), so its minor logging allocation is
 * acceptable under the zero-alloc render rule.
 */
public class TriggerBag {

  private interface Action {
    void execute(Random random, String logName);
  }

  private static class TriggerAction implements Action {
    private final TriggerParameter trigger;

    private TriggerAction(TriggerParameter trigger) {
      this.trigger = trigger;
    }

    @Override
    public void execute(Random random, String logName) {
      LX.log("Meta[" + logName + "]: trigger " + this.trigger.getLabel());
      this.trigger.trigger();
    }
  }

  private static class CompoundJump implements Action {
    private final CompoundParameter parameter;
    private final double lo, hi;

    private CompoundJump(CompoundParameter parameter, double lo, double hi) {
      this.parameter = parameter;
      this.lo = lo;
      this.hi = hi;
    }

    @Override
    public void execute(Random random, String logName) {
      final double value = this.lo + random.nextDouble() * (this.hi - this.lo);
      LX.log("Meta[" + logName + "]: jump " + this.parameter.getLabel() + " -> " + String.format("%.3f", value));
      this.parameter.setValue(value);
    }
  }

  private static class DiscreteJump implements Action {
    private final DiscreteParameter parameter;

    private DiscreteJump(DiscreteParameter parameter) {
      this.parameter = parameter;
    }

    @Override
    public void execute(Random random, String logName) {
      final int value = this.parameter.getMinValue() + random.nextInt((int) this.parameter.getRange());
      LX.log("Meta[" + logName + "]: jump " + this.parameter.getLabel() + " -> " + value);
      this.parameter.setValue(value);
    }
  }

  private final String logName;
  private final Random random = new Random();
  private final List<Action> actions = new ArrayList<>();

  public TriggerBag(String logName) {
    this.logName = logName;
  }

  /**
   * Register a normal trigger into the meta pool. Returns the trigger for
   * inline field-declaration use.
   */
  public TriggerParameter register(TriggerParameter trigger) {
    this.actions.add(new TriggerAction(trigger));
    return trigger;
  }

  /** Register a parameter jump over its full declared range */
  public TriggerBag jumpable(CompoundParameter parameter) {
    return jumpable(parameter, parameter.range.min, parameter.range.max);
  }

  /** Register a parameter jump over a curated subrange */
  public TriggerBag jumpable(CompoundParameter parameter, double lo, double hi) {
    this.actions.add(new CompoundJump(parameter, lo, hi));
    return this;
  }

  /** Register a discrete/enum parameter jump over all its values */
  public TriggerBag jumpable(DiscreteParameter parameter) {
    this.actions.add(new DiscreteJump(parameter));
    return this;
  }

  /**
   * Meta action: uniformly pick one registered trigger or jump and execute it.
   * Bind this to the pattern's meta TriggerParameter.
   */
  public void fire() {
    if (this.actions.isEmpty()) {
      LX.log("Meta[" + this.logName + "]: nothing registered");
      return;
    }
    this.actions.get(this.random.nextInt(this.actions.size())).execute(this.random, this.logName);
  }
}
