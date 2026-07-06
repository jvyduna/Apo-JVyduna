package apotheneum.jvyduna.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

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
    private final int lo, hi;

    private DiscreteJump(DiscreteParameter parameter, int lo, int hi) {
      if ((lo < parameter.getMinValue()) || (hi > parameter.getMaxValue()) || (lo > hi)) {
        throw new IllegalArgumentException(
          "Bad jump subrange [" + lo + ", " + hi + "] for " + parameter.getLabel() +
          " [" + parameter.getMinValue() + ", " + parameter.getMaxValue() + "]");
      }
      this.parameter = parameter;
      this.lo = lo;
      this.hi = hi;
    }

    @Override
    public void execute(Random random, String logName) {
      final int value = this.lo + random.nextInt(this.hi - this.lo + 1);
      // Log the option label (e.g. enum name) when one exists, so curation
      // logs read "jump Geometry -> Cylinder" rather than a bare index
      final String[] options = this.parameter.getOptions();
      final String display = (options != null) ?
        options[value - this.parameter.getMinValue()] : String.valueOf(value);
      LX.log("Meta[" + logName + "]: jump " + this.parameter.getLabel() + " -> " + display);
      this.parameter.setValue(value);
    }
  }

  private final String logName;
  private final Random random = new Random();
  private final List<Action> actions = new ArrayList<>();
  private Consumer<Runnable> jumpScheduler = null;

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
    return jumpable(parameter, parameter.getMinValue(), parameter.getMaxValue());
  }

  /**
   * Register a discrete/enum parameter jump over a curated subrange of raw
   * values, both inclusive. For an EnumParameter the raw values are the enum
   * ordinals, so e.g. a Tempo.Division tempoDiv knob can join the meta pool
   * restricted to a musical window:
   * {@code jumpable(tempoDiv, Division.SIXTEENTH.ordinal(), Division.HALF.ordinal())}
   * (the full Division range spans 1/16 up to 16 bars, which is unusable as a
   * uniform jump).
   */
  public TriggerBag jumpable(DiscreteParameter parameter, int lo, int hi) {
    this.actions.add(new DiscreteJump(parameter, lo, hi));
    return this;
  }

  /**
   * Optional hook: route jump executions through the pattern, e.g. to defer
   * them to the next tempo-grid boundary the way trigger callbacks already
   * can. When set, fire() hands each selected <em>jump</em> action to the
   * scheduler as a Runnable instead of executing it inline; the pattern runs
   * it immediately (Sync off) or stores it and runs it on the boundary (the
   * jump value is chosen, and its Meta[...] line logged, when the Runnable
   * runs). Trigger actions are unaffected — they already route through the
   * pattern's own callbacks, which implement their own deferral.
   *
   * @param scheduler Jump execution hook, or null to restore inline execution
   */
  public TriggerBag setJumpScheduler(Consumer<Runnable> scheduler) {
    this.jumpScheduler = scheduler;
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
    final Action action = this.actions.get(this.random.nextInt(this.actions.size()));
    if ((this.jumpScheduler != null) && !(action instanceof TriggerAction)) {
      // fire() is event-rate, so this small capture is acceptable allocation
      this.jumpScheduler.accept(() -> action.execute(this.random, this.logName));
    } else {
      action.execute(this.random, this.logName);
    }
  }
}
