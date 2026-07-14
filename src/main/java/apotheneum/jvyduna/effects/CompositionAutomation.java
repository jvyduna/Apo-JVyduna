package apotheneum.jvyduna.effects;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.GradientUtils;
import heronarts.lx.color.LXPalette;
import heronarts.lx.color.LXSwatch;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Automation-only effect whose parameters exist so that global engine state can
 * be recorded into (and played back from) an arrange composition.
 *
 * <p>The arrange recorder only captures parameter changes whose owning component
 * has an {@code LXBus} ancestor and whose bus is armed. Global engine state such
 * as {@code lx.engine.palette} lives on no bus, so changing it live is silently
 * dropped by the composition recorder (it <em>is</em> captured by a mixer master
 * <em>grid</em> clip, but the timeline cannot launch one). See
 * {@code docs/arrange-feedback/2026-07-08-palette-change-not-recorded-into-composition.md}.
 *
 * <p>Placing this effect on the <b>Master</b> bus gives its parameters a bus
 * ancestor, so they record like any other master-bus automation. Each parameter
 * is a bus-scoped stand-in that forwards to the real global state in its change
 * listener — the same net action a global macro modulator performs, but on a
 * parameter the composition can actually record. Listeners fire on both the live
 * user change (captured into the clip lane) and on clip playback (the lane drives
 * the parameter, the listener re-applies the state).
 *
 * <p>Controls:
 * <ul>
 * <li><b>PIndex</b> — 1-based index; recalls {@code palette.swatches[PIndex-1]}
 *     by <em>live position</em>. Simple, but not stable across reorder/delete.</li>
 * <li><b>PName</b> — a reorder-stable selector (by swatch name): a fixed set of
 *     {@value #SWATCH_SLOTS} slots, each bound to a swatch by its stable
 *     {@code getId()}. Because clip lanes persist a normalized value whose
 *     meaning is relative to the option count, the slot range must stay
 *     <em>fixed</em> for recordings to survive swatch create/delete; the
 *     slot&rarr;id registry (serialized on this effect) then keeps recordings
 *     correct across reorder and rename. Deleting a recorded swatch leaves its
 *     slot as a tombstone whose events become logged no-ops until the user
 *     reassigns/ignores it.</li>
 * <li><b>Trans / TrMode</b> — forward to the palette's own
 *     {@code transitionEnabled} / {@code transitionMode} so swatch-change
 *     crossfades can be automated.</li>
 * <li><b>TrnDiv</b> — a musical division ({@code 1/16} … {@code 32 bar}); on
 *     change it converts the division to seconds at the <em>current</em> BPM
 *     (bar-based options honor {@code tempo.beatsPerBar}) and writes that to the
 *     palette's {@code transitionTimeSecs}, floored at 100 ms (the palette's own
 *     minimum) and clamped to its 180 s max. Recomputing from live BPM on every
 *     change means a recorded division re-derives the right seconds at playback
 *     tempo. The option range is fixed so recorded clip values stay valid.</li>
 * <li><b>BeatRst</b> — momentary trigger that re-triggers the global metronome
 *     with the beat count reset to 0 ({@code lx.engine.tempo.trigger()}), so
 *     {@code getBasis(WHOLE)} (and every other division) immediately restarts a
 *     new cycle. Recorded as a trigger event on this effect's master-bus lane, so
 *     a composition can re-align tempo-relative patterns at a downbeat.</li>
 * </ul>
 *
 * <p>This effect never modifies the color buffer — it is a pure passthrough, so
 * it is safe to leave enabled on Master at all times. Named generically so other
 * composition-only automation hooks can be added here as more stand-in params.
 */
@LXCategory("Apotheneum/jvyduna")
@LXComponent.Name("Composition Automation")
@LXComponent.Description("Records global engine state (palette swatch + transitions) into a composition via bus-scoped stand-in parameters. Place on Master.")
public class CompositionAutomation extends LXEffect {

  /** Highest saved-swatch slot reachable by the positional {@code Palette} param (1-based). */
  private static final int MAX_PALETTE = 10;

  /**
   * Fixed number of slots for the stable {@code Swatch} selector. Must stay
   * constant: a clip event stores a normalized value that decodes to
   * {@code floor(normalized * range)}, so changing the range would shift every
   * previously recorded event. 32 gives generous headroom for saved swatches.
   */
  private static final int SWATCH_SLOTS = 32;

  private static final String LABEL_EMPTY = "—"; // em dash

  private static final String KEY_SWATCH_SLOTS = "swatchSlots";
  private static final String KEY_ID = "id";
  private static final String KEY_NAME = "name";

  // --- Selectors --------------------------------------------------------------

  public final DiscreteParameter paletteIndex =
    new DiscreteParameter("PIndex", 1, 1, MAX_PALETTE + 1)
    .setDescription("Active saved-swatch index (1-based, positional); recalls that swatch when it changes");

  public final DiscreteParameter paletteName =
    new DiscreteParameter("PName", 0, 0, SWATCH_SLOTS)
    .setDescription("Reorder-stable swatch selector (by name); each slot is bound to a swatch by stable id");

  // --- Transition stand-ins (forward to lx.engine.palette) --------------------

  public final BooleanParameter transitionEnabled =
    new BooleanParameter("Trans", false)
    .setDescription("Palette transition on/off: whether swatch changes crossfade");

  public final EnumParameter<GradientUtils.BlendMode> transitionMode =
    new EnumParameter<GradientUtils.BlendMode>("TrMode", GradientUtils.BlendMode.RGB)
    .setDescription("Palette transition blend mode");

  /** Labels for {@link #transitionDiv}; parallel to {@link #TRN_DIV_UNITS} / {@link #TRN_DIV_BARS}. */
  private static final String[] TRN_DIV_LABELS = {
    "1/16", "1/8", "1/4", "1/2", "1 bar", "2 bar", "4 bar", "8 bar", "16 bar", "32 bar"
  };

  /** Length of each division: beats for sub-bar entries, bars for bar entries (see {@link #TRN_DIV_BARS}). */
  private static final double[] TRN_DIV_UNITS = {
    0.25, 0.5, 1, 2, 1, 2, 4, 8, 16, 32
  };

  /** Whether each {@link #TRN_DIV_UNITS} entry is measured in bars (true) or beats (false). */
  private static final boolean[] TRN_DIV_BARS = {
    false, false, false, false, true, true, true, true, true, true
  };

  /** Default division index ("1 bar"). */
  private static final int TRN_DIV_DEFAULT = 4;

  /** Palette transition duration's minimum, even with transitions disabled (matches palette bounds). */
  private static final double MIN_TRANSITION_SECS = 0.1;

  public final DiscreteParameter transitionDiv =
    new DiscreteParameter("TrnDiv", TRN_DIV_LABELS, TRN_DIV_DEFAULT)
    .setDescription("Palette transition duration as a musical division at the current BPM (floored at 100ms)");

  // --- Transport stand-in (forwards to lx.engine.tempo) -----------------------

  public final TriggerParameter beatReset =
    new TriggerParameter("BeatRst", this::resetBeatCounter)
    .setDescription("Reset the global beat counter: re-triggers the metronome so getBasis(WHOLE) restarts a new cycle immediately");

  // --- Stable-slot registry (serialized on this effect) -----------------------

  /** Slot -> swatch id, or -1 for an empty slot. Source of truth for the Swatch selector. */
  private final int[] slotSwatchId = new int[SWATCH_SLOTS];

  /** Slot -> last-known swatch name, so a tombstoned (deleted) slot still labels sensibly. */
  private final String[] slotName = new String[SWATCH_SLOTS];

  /** Swatches whose label param we've attached a listener to (for live label refresh). */
  private final Set<LXSwatch> trackedLabels = new HashSet<LXSwatch>();

  // --- Listeners --------------------------------------------------------------

  private final LXParameterListener recallByIndexListener = p -> recallByIndex();
  private final LXParameterListener recallBySlotListener = p -> recallBySlot();
  private final LXParameterListener forwardTransEnabled =
    p -> this.lx.engine.palette.transitionEnabled.setValue(this.transitionEnabled.isOn());
  private final LXParameterListener forwardTransMode =
    p -> this.lx.engine.palette.transitionMode.setValue(this.transitionMode.getEnum());
  private final LXParameterListener applyTransDivListener = p -> applyTransitionDivision();

  /** Shared across every tracked swatch's label param; just refreshes the dropdown. */
  private final LXParameterListener labelListener = p -> rebuildLabels();

  private final LXPalette.Listener paletteListener = new LXPalette.Listener() {
    public void swatchAdded(LXPalette palette, LXSwatch swatch) {
      trackSwatch(swatch);
      rebuildLabels();
    }
    public void swatchRemoved(LXPalette palette, LXSwatch swatch) {
      untrackLabel(swatch); // keep the slot as a tombstone; last name is retained
      rebuildLabels();
    }
    public void swatchMoved(LXPalette palette, LXSwatch swatch) {
      rebuildLabels();
    }
  };

  public CompositionAutomation(LX lx) {
    super(lx);
    for (int i = 0; i < SWATCH_SLOTS; ++i) {
      this.slotSwatchId[i] = -1;
      this.slotName[i] = "";
    }

    addParameter("paletteIndex", this.paletteIndex);
    addParameter("paletteName", this.paletteName);
    addParameter("transitionEnabled", this.transitionEnabled);
    addParameter("transitionMode", this.transitionMode);
    addParameter("transitionDiv", this.transitionDiv);
    addParameter("beatReset", this.beatReset);

    this.lx.engine.palette.addListener(this.paletteListener);
    reconcileSlots();  // bind any already-existing swatches to slots + label listeners
    rebuildLabels();

    this.paletteIndex.addListener(this.recallByIndexListener);
    this.paletteName.addListener(this.recallBySlotListener);
    this.transitionEnabled.addListener(this.forwardTransEnabled);
    this.transitionMode.addListener(this.forwardTransMode);
    this.transitionDiv.addListener(this.applyTransDivListener);
  }

  // --- Recall paths -----------------------------------------------------------

  /** Recall by the positional {@code PIndex} index (breaks on reorder — by design, kept simple). */
  private void recallByIndex() {
    final List<LXSwatch> swatches = this.lx.engine.palette.swatches;
    final int index = this.paletteIndex.getValuei() - 1;
    if (index < 0 || index >= swatches.size()) {
      LX.log("CompositionAutomation: PIndex " + this.paletteIndex.getValuei()
        + " has no saved swatch (only " + swatches.size() + " defined); ignoring");
      return;
    }
    this.lx.engine.palette.setSwatch(swatches.get(index));
  }

  /** Recall by the stable {@code PName} slot: slot -> id -> swatch, or a logged no-op. */
  private void recallBySlot() {
    final int slot = this.paletteName.getValuei();
    final int id = this.slotSwatchId[slot];
    if (id < 0) {
      return; // empty slot: nothing bound, silently ignore
    }
    final LXSwatch found = findById(id);
    if (found == null) {
      LX.log("CompositionAutomation: PName slot " + slot + " -> id " + id
        + " (" + this.slotName[slot] + ") no longer exists; ignoring");
      return;
    }
    this.lx.engine.palette.setSwatch(found);
  }

  // --- Transition division ----------------------------------------------------

  /**
   * Convert the selected musical division to seconds at the current BPM and push
   * it to {@code lx.engine.palette.transitionTimeSecs}. Bar-based divisions honor
   * the live {@code tempo.beatsPerBar}; the result is floored at
   * {@value #MIN_TRANSITION_SECS}s (the palette's minimum) and clamped to the
   * palette param's max by {@code setValue}.
   */
  private void applyTransitionDivision() {
    final int i = this.transitionDiv.getValuei();
    final double beats = TRN_DIV_BARS[i]
      ? TRN_DIV_UNITS[i] * this.lx.engine.tempo.beatsPerBar.getValuei()
      : TRN_DIV_UNITS[i];
    // tempo.period is the quarter-note (one-beat) length in ms.
    final double seconds = beats * this.lx.engine.tempo.period.getValue() / 1000.0;
    this.lx.engine.palette.transitionTimeSecs.setValue(Math.max(MIN_TRANSITION_SECS, seconds));
  }

  // --- Transport reset --------------------------------------------------------

  /**
   * Re-trigger the global metronome with the beat count reset to 0. This sets
   * {@code target.beatCount = 0} and re-syncs the basis to the trigger instant,
   * so {@code lx.engine.tempo.getBasis(division)} restarts at 0 for every
   * division on the next engine loop — a new WHOLE cycle begins immediately.
   * Fires on both a live user press (captured into the clip lane) and clip
   * playback (the recorded trigger re-applies the reset).
   */
  private void resetBeatCounter() {
    this.lx.engine.tempo.trigger();
  }

  // --- Slot registry ----------------------------------------------------------

  private LXSwatch findById(int id) {
    for (LXSwatch s : this.lx.engine.palette.swatches) {
      if (s.getId() == id) {
        return s;
      }
    }
    return null;
  }

  private int slotForId(int id) {
    for (int i = 0; i < SWATCH_SLOTS; ++i) {
      if (this.slotSwatchId[i] == id) {
        return i;
      }
    }
    return -1;
  }

  /** Ensure a swatch is bound to a slot and its label is being watched. */
  private void trackSwatch(LXSwatch swatch) {
    if (slotForId(swatch.getId()) < 0) {
      int free = slotForId(-1);
      if (free < 0) {
        LX.log("CompositionAutomation: all " + SWATCH_SLOTS
          + " swatch slots in use; \"" + swatch.label.getString() + "\" is not selectable");
      } else {
        this.slotSwatchId[free] = swatch.getId();
        this.slotName[free] = swatch.label.getString();
      }
    }
    if (this.trackedLabels.add(swatch)) {
      swatch.label.addListener(this.labelListener);
    }
  }

  private void untrackLabel(LXSwatch swatch) {
    if (this.trackedLabels.remove(swatch)) {
      swatch.label.removeListener(this.labelListener);
    }
  }

  /** Bind every current swatch to a slot (appends only; never disturbs existing bindings). */
  private void reconcileSlots() {
    for (LXSwatch s : this.lx.engine.palette.swatches) {
      trackSwatch(s);
    }
  }

  /** Refresh the Swatch dropdown labels from the current registry + live swatch names. */
  private void rebuildLabels() {
    final String[] labels = new String[SWATCH_SLOTS];
    for (int i = 0; i < SWATCH_SLOTS; ++i) {
      final int id = this.slotSwatchId[i];
      if (id < 0) {
        labels[i] = LABEL_EMPTY;
        continue;
      }
      final LXSwatch s = findById(id);
      if (s != null) {
        this.slotName[i] = s.label.getString();
        labels[i] = this.slotName[i];
      } else {
        final String name = this.slotName[i].isEmpty() ? ("#" + id) : this.slotName[i];
        labels[i] = name + " (del)";
      }
    }
    this.paletteName.setOptions(labels, false); // range unchanged -> recorded normalized values stay valid
  }

  // --- Passthrough ------------------------------------------------------------

  @Override
  protected void run(double deltaMs, double enabledAmount) {
    // Pure passthrough: automation happens in parameter listeners, not here,
    // so the color buffer below this effect is left untouched.
  }

  // --- Persistence ------------------------------------------------------------

  @Override
  public void save(LX lx, JsonObject obj) {
    // Restore-order mirror of load(): write the registry, then the params.
    final JsonArray slots = new JsonArray();
    for (int i = 0; i < SWATCH_SLOTS; ++i) {
      final JsonObject entry = new JsonObject();
      entry.addProperty(KEY_ID, this.slotSwatchId[i]);
      entry.addProperty(KEY_NAME, this.slotName[i]);
      slots.add(entry);
    }
    obj.add(KEY_SWATCH_SLOTS, slots);
    super.save(lx, obj);
  }

  @Override
  public void load(LX lx, JsonObject obj) {
    // Restore the slot registry BEFORE super.load(), so that if loading the
    // parameters fires the Swatch recall listener, the slot->id map is ready.
    if (obj.has(KEY_SWATCH_SLOTS)) {
      final JsonArray slots = obj.getAsJsonArray(KEY_SWATCH_SLOTS);
      final int n = Math.min(slots.size(), SWATCH_SLOTS);
      for (int i = 0; i < n; ++i) {
        final JsonObject entry = slots.get(i).getAsJsonObject();
        this.slotSwatchId[i] = entry.has(KEY_ID) ? entry.get(KEY_ID).getAsInt() : -1;
        this.slotName[i] = entry.has(KEY_NAME) ? entry.get(KEY_NAME).getAsString() : "";
      }
    }
    reconcileSlots(); // bind any swatches created since this effect was last saved
    rebuildLabels();
    super.load(lx, obj);
  }

  // --- Lifecycle --------------------------------------------------------------

  @Override
  public void dispose() {
    this.paletteIndex.removeListener(this.recallByIndexListener);
    this.paletteName.removeListener(this.recallBySlotListener);
    this.transitionEnabled.removeListener(this.forwardTransEnabled);
    this.transitionMode.removeListener(this.forwardTransMode);
    this.transitionDiv.removeListener(this.applyTransDivListener);
    this.lx.engine.palette.removeListener(this.paletteListener);
    for (LXSwatch s : this.trackedLabels) {
      s.label.removeListener(this.labelListener);
    }
    this.trackedLabels.clear();
    super.dispose();
  }
}
