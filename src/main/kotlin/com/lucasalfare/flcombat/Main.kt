@file:Suppress("unused")

package com.lucasalfare.flcombat

import kotlin.math.floor
import kotlin.random.Random

/**
 * Stable, immutable identity of a combat participant.
 *
 * An [Agent] carries **no** mutable state of its own. It exists solely to serve as a
 * key under which a [CombatState] associates every mutable aspect of the combat:
 * attributes, modifiers, items, effects and defeat status. Because [Agent] is a
 * `data class` whose only property is [id], equality and hash code are structural
 * over [id] — two `Agent` instances constructed with the same [id] are considered
 * the *same* participant by every structure in this library.
 *
 * Design consequences:
 *
 * - A single instance may be registered in multiple independent [CombatState]
 *   instances; each state tracks its own view of the participant.
 * - Adding, removing or mutating anything about a participant never mutates the
 *   [Agent] itself; it always mutates a [CombatState].
 * - The consumer is responsible for choosing [id] values that are stable across
 *   the lifetime of a combat and unambiguous within the intended scope.
 *
 * @property id consumer-supplied, stable identifier used as the structural key of
 *   the participant. The library never interprets this value; it is only used for
 *   equality, hashing and diagnostics.
 *
 * @see CombatState
 */
data class Agent(val id: String)

/**
 * Snapshot of a consumer-defined attribute of a single [Agent].
 *
 * An attribute is any named numeric characteristic the consumer chooses to model
 * (for example "strength", "armor", "mana", "morale"). The library attaches no
 * meaning to [id] and does not maintain a fixed catalog of attributes.
 *
 * The distinction between [baseValue] and [effectiveValue] is central: the base
 * value is the raw value stored in a [CombatState], while the effective value is
 * the result of composing [baseValue] with every [Modifier] currently attached to
 * the attribute (through [CombatState.addModifier], [Item]s and [Effect]s) using a
 * [ModifierComposer]. With no modifiers, `effectiveValue == baseValue`.
 *
 * Instances are immutable snapshots produced by [CombatState.attribute] and
 * [CombatState.attributes]; they are not live views.
 *
 * @property id consumer-supplied stable identifier of the attribute.
 * @property baseValue value of the attribute before any modifiers are applied.
 * @property effectiveValue value of the attribute after all currently attached
 *   modifiers have been composed by the active [ModifierComposer].
 *
 * @see Modifier
 * @see ModifierComposer
 * @see CombatState.attribute
 */
data class Attribute(
  val id: String,
  val baseValue: Int,
  val effectiveValue: Int
)

/**
 * A single alteration applicable to an attribute's base value.
 *
 * A [Modifier] is intentionally opaque to the core: the library only sees an
 * [id] and an [Int] [value]. The meaning of [id] is defined entirely by the
 * [ModifierComposer] in use. For example, [AdditiveThenPercentageComposer]
 * interprets [id] `"additive"` as a flat addition and `"percentage"` as a
 * percentage applied *after* all additives; another composer may interpret the
 * same [id]s differently, or use different ones altogether.
 *
 * `Modifier` is a `data class`, so equality is structural over `(id, value)`.
 * This matters for [CombatState.removeModifier], which removes by equality.
 *
 * @property id consumer-supplied semantic tag consumed by the active
 *   [ModifierComposer]. The core never inspects this value.
 * @property value magnitude of the modifier. Sign is meaningful only insofar as
 *   the active [ModifierComposer] interprets it (typically positive for buffs,
 *   negative for debuffs).
 *
 * @see ModifierComposer
 * @see CombatState.addModifier
 * @see CombatState.removeModifier
 */
data class Modifier(val id: String, val value: Int)

/**
 * Consumer-defined object owned by an [Agent] that can contribute [Modifier]s to
 * one or more attributes.
 *
 * Items are pure data from the perspective of the core: the library never
 * distinguishes weapons from armor from trinkets. An item's only contribution is
 * the map from attribute identifier to the list of [Modifier]s it supplies.
 * Modifiers provided by items are merged with direct modifiers and effect
 * modifiers by [CombatState.modifiers] before composition, and are therefore
 * indistinguishable from any other modifier source at composition time.
 *
 * Item identity within a [CombatState] is [id]: adding an item whose [id] equals
 * an already-owned item's [id] replaces the previous one.
 *
 * @property id consumer-supplied stable identifier of the item. Used as the key
 *   under which the item is stored per agent.
 * @property modifiers map from attribute identifier to the list of modifiers that
 *   this item contributes to that attribute. Attributes not present in the map
 *   receive no modifiers from this item. Defaults to an empty map, i.e. an item
 *   that contributes nothing to any attribute.
 *
 * @see CombatState.addItem
 * @see CombatState.removeItem
 * @see CombatState.items
 */
data class Item(
  val id: String,
  val modifiers: Map<String, List<Modifier>> = emptyMap()
)

/**
 * Consumer-defined status applied to an [Agent] for a bounded or unbounded period
 * of time, optionally contributing [Modifier]s and running side effects on
 * application and removal.
 *
 * A single concept covers both buffs and debuffs: the library does not distinguish
 * them, and never inspects the sign or meaning of the modifiers carried by an
 * effect. Two kinds of durations are supported:
 *
 * - **Temporary**: [durationTicks] is a non-negative [Int]. The duration is
 *   decremented once per explicit [CombatState.tick] call. When it reaches zero,
 *   the effect is removed automatically and [onRemove] is invoked.
 * - **Permanent**: [durationTicks] is `null`. The effect never expires by ticking
 *   and must be removed explicitly through [CombatState.removeEffect].
 *
 * Effect identity within an agent in a [CombatState] is [id]. Applying an effect
 * whose [id] matches an already-applied one triggers [onRemove] on the previous
 * instance, emits an [CombatEvent.EffectRemoved] for it, and then installs the new
 * instance in its place.
 *
 * The [onApply] and [onRemove] callbacks receive the owning [Agent] and the live
 * [CombatState], allowing the consumer to read and mutate state as part of the
 * effect's lifecycle. Callbacks are executed synchronously by [CombatState];
 * they are never invoked by the library itself outside of apply/remove/tick.
 *
 * @property id consumer-supplied stable identifier of the effect, used as the key
 *   under which the effect is stored per agent.
 * @property modifiers map from attribute identifier to the list of modifiers that
 *   this effect contributes to that attribute while applied. Defaults to an empty
 *   map.
 * @property durationTicks duration of the effect in ticks, or `null` for a
 *   permanent effect. Must be `null` or a non-negative integer; a value of `0`
 *   means the effect expires on the first tick it observes.
 * @property onApply callback invoked immediately after the effect is installed by
 *   [CombatState.applyEffect]. Defaults to a no-op.
 * @property onRemove callback invoked whenever the effect is removed, whether
 *   explicitly via [CombatState.removeEffect], implicitly by replacement in
 *   [CombatState.applyEffect], or by expiration in [CombatState.tick]. Defaults
 *   to a no-op.
 *
 * @throws IllegalArgumentException if [durationTicks] is negative.
 *
 * @see CombatState.applyEffect
 * @see CombatState.removeEffect
 * @see CombatState.tick
 */
data class Effect(
  val id: String,
  val modifiers: Map<String, List<Modifier>> = emptyMap(),
  val durationTicks: Int? = null,
  val onApply: (Agent, CombatState) -> Unit = { _, _ -> },
  val onRemove: (Agent, CombatState) -> Unit = { _, _ -> }
) {
  init {
    require(durationTicks == null || durationTicks >= 0) {
      "durationTicks ($durationTicks) must be null or non-negative"
    }
  }
}

/**
 * Strategy that combines a base value with a set of [Modifier]s and produces the
 * effective value of an attribute.
 *
 * This is the only point in the core where modifier semantics are decided. The
 * library **never** imposes a formula: implementations may be additive,
 * multiplicative, additive-then-multiplicative, threshold-based, table-driven,
 * context-dependent on the attribute id, or anything else. The core only requires
 * that a composer be able to turn `(baseValue, modifiers)` into an [Int].
 *
 * Because the interface is a `fun interface`, the consumer can supply a composer
 * inline as a lambda, without introducing a named class.
 *
 * The core bundles [Attribute.id] and the concrete list of modifiers passed to
 * [compose] as described by [CombatState.modifiers]; nothing else is provided.
 * In particular, the composer does not receive the [Agent], the [CombatState], or
 * the attribute id, because the contract is intentionally narrow. Consumers that
 * need attribute-specific behavior should close over any additional information
 * they require when constructing the composer.
 *
 * @see AdditiveThenPercentageComposer
 * @see CombatState
 */
fun interface ModifierComposer {
  /**
   * Computes the effective value of an attribute.
   *
   * @param baseValue base value of the attribute, before any modifiers.
   * @param modifiers all modifiers currently affecting the attribute, in an
   *   unspecified but stable order for the duration of the call. The collection
   *   may be empty. Callers must not mutate it.
   * @return the effective value of the attribute. Consumers are free to return
   *   any [Int], including values smaller or larger than [baseValue].
   */
  fun compose(baseValue: Int, modifiers: Collection<Modifier>): Int
}

/**
 * Default [ModifierComposer] that first applies all additive modifiers, then
 * scales the result by the sum of percentage modifiers, rounding down.
 *
 * The semantics are:
 *
 * ```
 * additive   = sum of values of modifiers whose id == "additive"
 * percentage = sum of values of modifiers whose id == "percentage"
 * effective  = floor((baseValue + additive) * (100 + percentage) / 100.0)
 * ```
 *
 * Modifiers whose [Modifier.id] is neither [ADDITIVE] nor [PERCENTAGE] are
 * ignored by this composer. A percentage of `-100` yields a result of `0`; more
 * negative percentages produce a negative effective value, because this composer
 * does **not** clamp at zero (clamping is only a damage rule, not an attribute
 * rule).
 *
 * This implementation is a convenience only. The library does not require it, and
 * consumers are encouraged to replace it with a composer that fits their game.
 *
 * @see ModifierComposer
 */
object AdditiveThenPercentageComposer : ModifierComposer {
  /**
   * Identifier interpreted by this composer as a flat additive contribution.
   * Modifiers carrying this id are summed and added to the base value before the
   * percentage is applied.
   */
  const val ADDITIVE = "additive"

  /**
   * Identifier interpreted by this composer as a percentage contribution.
   * Modifiers carrying this id are summed and applied to the additive result as a
   * percentage (e.g. `+50` means `+50%`, `-25` means `-25%`).
   */
  const val PERCENTAGE = "percentage"

  /**
   * Applies additive modifiers, then percentage modifiers, then floors.
   *
   * @param baseValue base value before any modifiers.
   * @param modifiers modifiers to compose; only those with id [ADDITIVE] or
   *   [PERCENTAGE] participate.
   * @return the composed effective value, floored toward negative infinity.
   */
  override fun compose(baseValue: Int, modifiers: Collection<Modifier>): Int {
    val additive = modifiers.filter { it.id == ADDITIVE }.sumOf { it.value }
    val percentage = modifiers.filter { it.id == PERCENTAGE }.sumOf { it.value }
    val afterAdditives = baseValue + additive
    return floor(afterAdditives * (100 + percentage) / 100.0).toInt()
  }
}

/**
 * Authoritative, presentation-free source of truth for the mutable state of a
 * single combat.
 *
 * Every piece of mutable information about every participating [Agent] lives
 * here, keyed by agent identity. [Agent] itself stays immutable. A `CombatState`
 * is created empty and populated incrementally by the consumer; it is never
 * derived from a previous state.
 *
 * Responsibilities:
 *
 * - register agents and answer membership queries;
 * - store base attribute values, direct [Modifier]s, [Item]s and [Effect]s per
 *   agent, and compute effective attribute values through a [ModifierComposer];
 * - track defeat status per agent;
 * - accumulate [CombatEvent]s emitted by operations on the state so that callers
 *   can retrieve and react to them via [drainEvents].
 *
 * Ordering and determinism: the state does not use randomness and does not depend
 * on wall-clock time. Effect durations advance only when [tick] is called
 * explicitly, and events accumulate only when an operation is invoked. All
 * mutation is synchronous on the calling thread and no internal synchronization
 * is performed; a `CombatState` is not thread-safe.
 *
 * Attribute computation is delegated to a [ModifierComposer]. The library never
 * hard-codes a composition formula; the [AdditiveThenPercentageComposer] is only
 * the default used when the consumer does not supply one.
 *
 * @param modifierComposer strategy used to compute every effective attribute
 *   value produced by this state. Defaults to [AdditiveThenPercentageComposer].
 *
 * @see Agent
 * @see ModifierComposer
 * @see CombatEvent
 */
class CombatState(
  private val modifierComposer: ModifierComposer = AdditiveThenPercentageComposer
) {

  private class AppliedEffect(val effect: Effect, var remainingTicks: Int?)

  private val agents: MutableSet<Agent> = mutableSetOf()
  private val baseValues: MutableMap<Agent, MutableMap<String, Int>> = mutableMapOf()
  private val modifiers: MutableMap<Agent, MutableMap<String, MutableList<Modifier>>> = mutableMapOf()
  private val items: MutableMap<Agent, MutableMap<String, Item>> = mutableMapOf()
  private val effects: MutableMap<Agent, MutableMap<String, AppliedEffect>> = mutableMapOf()
  private val defeated: MutableSet<Agent> = mutableSetOf()
  private val pendingEvents: MutableList<CombatEvent> = mutableListOf()

  /**
   * Returns all [CombatEvent]s accumulated since the last call to this method and
   * clears the internal buffer.
   *
   * The returned list preserves emission order. After the call, the state's
   * pending event buffer is empty. Callers that need to process events
   * incrementally should invoke this method periodically.
   *
   * @return an immutable snapshot of every pending event, in emission order. May
   *   be empty.
   */
  fun drainEvents(): List<CombatEvent> {
    val drained = pendingEvents.toList()
    pendingEvents.clear()
    return drained
  }

  /**
   * Registers [agent] as a participant of this combat.
   *
   * Registering the same agent twice is idempotent: the second call has no
   * effect. Every mutating method on this class requires its target agent to be
   * registered first and throws [IllegalArgumentException] otherwise.
   *
   * @param agent the participant to register. Its state is unaffected by
   *   registration.
   */
  fun register(agent: Agent) {
    agents.add(agent)
  }

  /**
   * Reports whether [agent] is currently registered in this combat.
   *
   * @param agent the participant to query.
   * @return `true` if [agent] has been registered, `false` otherwise.
   */
  fun contains(agent: Agent): Boolean = agent in agents

  /**
   * Returns a snapshot of the currently registered agents.
   *
   * The returned set is a defensive copy: mutating it does not affect the state.
   *
   * @return an immutable set of the registered agents at the moment of the call.
   */
  fun agents(): Set<Agent> = agents.toSet()

  /**
   * Marks [agent] as defeated.
   *
   * If [agent] was not already defeated, a [CombatEvent.Defeat] event is enqueued
   * and will be returned by the next [drainEvents] call. Marking an already
   * defeated agent is idempotent: no additional event is enqueued.
   *
   * @param agent the participant to mark as defeated.
   *
   * @throws IllegalArgumentException if [agent] is not registered in this state.
   *
   * @see isDefeated
   */
  fun markDefeated(agent: Agent) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    if (defeated.add(agent)) {
      pendingEvents.add(CombatEvent.Defeat(agent))
    }
  }

  /**
   * Reports whether [agent] has been marked defeated in this combat.
   *
   * @param agent the participant to query.
   * @return `true` if [agent] was marked defeated via [markDefeated], `false`
   *   otherwise. Agents that are not registered also return `false`.
   */
  fun isDefeated(agent: Agent): Boolean = agent in defeated

  /**
   * Sets the base value of the attribute identified by [id] for [agent].
   *
   * If the attribute does not yet exist for [agent], it is created. If it already
   * exists, only its base value is replaced; existing modifiers, item-provided
   * modifiers and effect-provided modifiers remain attached.
   *
   * The effective value is not stored — it is recomputed on demand from the base
   * value and the current modifier set using the active [ModifierComposer].
   *
   * @param agent owner of the attribute.
   * @param id consumer-supplied attribute identifier.
   * @param baseValue new base value of the attribute. May be negative; the core
   *   does not constrain the range of attribute values.
   *
   * @throws IllegalArgumentException if [agent] is not registered in this state.
   *
   * @see attribute
   * @see Attribute
   */
  fun setAttribute(agent: Agent, id: String, baseValue: Int) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    baseValues.getOrPut(agent) { mutableMapOf() }[id] = baseValue
  }

  private fun baseValue(agent: Agent, id: String): Int? =
    baseValues[agent]?.get(id)

  /**
   * Attaches a direct [Modifier] to the attribute identified by [attributeId] for
   * [agent].
   *
   * Direct modifiers participate in composition alongside item- and
   * effect-provided modifiers through [modifiers]. They are not deduplicated:
   * adding two structurally identical modifiers results in both being present,
   * and each contributes independently to composition. Removal is by structural
   * equality through [removeModifier].
   *
   * @param agent owner of the attribute.
   * @param attributeId consumer-supplied identifier of the target attribute. The
   *   attribute does not need to exist yet; a modifier may be added before its
   *   base value is set, and it will take effect as soon as the base value
   *   exists.
   * @param modifier the modifier to attach.
   *
   * @throws IllegalArgumentException if [agent] is not registered in this state.
   *
   * @see removeModifier
   * @see modifiers
   */
  fun addModifier(agent: Agent, attributeId: String, modifier: Modifier) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    modifiers.getOrPut(agent) { mutableMapOf() }
      .getOrPut(attributeId) { mutableListOf() }
      .add(modifier)
  }

  /**
   * Removes the first direct modifier structurally equal to [modifier] from the
   * attribute identified by [attributeId] for [agent].
   *
   * Only direct modifiers added via [addModifier] are considered. Modifiers
   * provided by [Item]s or [Effect]s are not affected by this method. If the
   * agent, the attribute, or an equal modifier is absent, the call is a no-op.
   *
   * @param agent owner of the attribute.
   * @param attributeId consumer-supplied identifier of the target attribute.
   * @param modifier the modifier value to remove, matched by structural equality.
   *
   * @see addModifier
   */
  fun removeModifier(agent: Agent, attributeId: String, modifier: Modifier) {
    modifiers[agent]?.get(attributeId)?.remove(modifier)
  }

  /**
   * Attaches [item] to [agent], making its modifiers contribute to the agent's
   * attributes.
   *
   * If an item with the same [Item.id] is already owned by [agent], it is
   * replaced. Item ordering is not meaningful; contributions are recomputed from
   * the current set of items on every attribute lookup.
   *
   * @param agent owner of the item.
   * @param item the item to attach.
   *
   * @throws IllegalArgumentException if [agent] is not registered in this state.
   *
   * @see removeItem
   * @see items
   */
  fun addItem(agent: Agent, item: Item) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    items.getOrPut(agent) { mutableMapOf() }[item.id] = item
  }

  /**
   * Removes the item identified by [itemId] from [agent], if present.
   *
   * Removing an item that the agent does not own is a no-op.
   *
   * @param agent owner of the item.
   * @param itemId consumer-supplied identifier of the item to remove.
   *
   * @see addItem
   */
  fun removeItem(agent: Agent, itemId: String) {
    items[agent]?.remove(itemId)
  }

  /**
   * Returns a snapshot of the items currently owned by [agent].
   *
   * The returned set is a defensive copy; mutating it does not affect the state.
   *
   * @param agent the participant to query.
   * @return an immutable set of the agent's current items, or an empty set if the
   *   agent owns none or is not registered.
   */
  fun items(agent: Agent): Set<Item> =
    items[agent]?.values?.toSet() ?: emptySet()

  /**
   * Applies [effect] to [agent].
   *
   * If an effect with the same [Effect.id] is already applied to [agent], that
   * previous effect is first removed: its [Effect.onRemove] callback runs and an
   * [CombatEvent.EffectRemoved] event is enqueued for it. Then the new effect is
   * installed, its [Effect.onApply] callback runs, and an
   * [CombatEvent.EffectApplied] event is enqueued. The installation stores the
   * effect's initial [Effect.durationTicks] as its remaining duration.
   *
   * @param agent the participant that receives the effect.
   * @param effect the effect instance to install. It is stored as-is and reused
   *   for every subsequent callback of the same application.
   *
   * @throws IllegalArgumentException if [agent] is not registered in this state.
   *
   * @see removeEffect
   * @see effects
   */
  fun applyEffect(agent: Agent, effect: Effect) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    val agentEffects = effects.getOrPut(agent) { mutableMapOf() }
    agentEffects[effect.id]?.let { previous ->
      previous.effect.onRemove(agent, this)
      pendingEvents.add(CombatEvent.EffectRemoved(agent, previous.effect))
    }
    agentEffects[effect.id] = AppliedEffect(effect, effect.durationTicks)
    effect.onApply(agent, this)
    pendingEvents.add(CombatEvent.EffectApplied(agent, effect))
  }

  /**
   * Removes the effect identified by [effectId] from [agent], if present.
   *
   * On success, the effect's [Effect.onRemove] callback runs and an
   * [CombatEvent.EffectRemoved] event is enqueued.
   *
   * @param agent the participant owning the effect.
   * @param effectId consumer-supplied identifier of the effect to remove.
   * @return the removed [Effect], or `null` if the agent has no effect with that
   *   id or is not registered.
   *
   * @see applyEffect
   */
  fun removeEffect(agent: Agent, effectId: String): Effect? {
    val removed = effects[agent]?.remove(effectId) ?: return null
    removed.effect.onRemove(agent, this)
    pendingEvents.add(CombatEvent.EffectRemoved(agent, removed.effect))
    return removed.effect
  }

  /**
   * Returns a snapshot of the effects currently applied to [agent].
   *
   * The returned set is a defensive copy; mutating it does not affect the state.
   *
   * @param agent the participant to query.
   * @return an immutable set of the agent's currently applied effects, or an
   *   empty set if none are applied or the agent is not registered.
   */
  fun effects(agent: Agent): Set<Effect> =
    effects[agent]?.values?.map { it.effect }?.toSet() ?: emptySet()

  /**
   * Returns the effect currently applied to [agent] under [effectId], if any.
   *
   * @param agent the participant owning the effect.
   * @param effectId consumer-supplied identifier of the effect.
   * @return the applied [Effect], or `null` if there is none.
   */
  fun effect(agent: Agent, effectId: String): Effect? =
    effects[agent]?.get(effectId)?.effect

  /**
   * Returns the remaining duration in ticks of the effect identified by
   * [effectId] on [agent], if that effect is temporary.
   *
   * @param agent the participant owning the effect.
   * @param effectId consumer-supplied identifier of the effect.
   * @return the remaining ticks, or `null` if the effect is not applied, is not
   *   registered on this agent, or is permanent (its
   *   [Effect.durationTicks] was `null`).
   *
   * @see tick
   */
  fun remainingTicks(agent: Agent, effectId: String): Int? =
    effects[agent]?.get(effectId)?.remainingTicks

  /**
   * Advances the durations of every temporary effect currently applied to
   * [agent] by one tick, expiring effects whose remaining duration reaches zero.
   *
   * For every temporary effect, the remaining tick count is decremented by one.
   * Any effect whose remaining ticks become zero is removed: its
   * [Effect.onRemove] callback runs and an [CombatEvent.EffectRemoved] event is
   * enqueued. Permanent effects (those whose [Effect.durationTicks] was `null`)
   * are ignored. Calling this method for an agent that is not registered, or that
   * has no effects, is a no-op.
   *
   * The library does not decide *when* a tick occurs; the consumer is responsible
   * for invoking this method according to its own combat turn model.
   *
   * @param agent the participant whose effects should advance.
   */
  fun tick(agent: Agent) {
    val agentEffects = effects[agent] ?: return
    val expired = mutableListOf<AppliedEffect>()
    for (applied in agentEffects.values) {
      val remaining = applied.remainingTicks ?: continue
      if (remaining > 0) applied.remainingTicks = remaining - 1
      if (applied.remainingTicks == 0) expired.add(applied)
    }
    for (applied in expired) {
      agentEffects.remove(applied.effect.id)
      applied.effect.onRemove(agent, this)
      pendingEvents.add(CombatEvent.EffectRemoved(agent, applied.effect))
    }
  }

  /**
   * Returns the aggregate list of modifiers currently affecting the attribute
   * identified by [attributeId] on [agent].
   *
   * The aggregate is the concatenation of, in order:
   *
   * 1. direct modifiers added via [addModifier];
   * 2. modifiers contributed by owned [Item]s, flattened in item iteration order;
   * 3. modifiers contributed by applied [Effect]s, flattened in effect iteration
   *    order.
   *
   * The returned list is a defensive copy; mutating it does not affect the state.
   * Order across the three groups is stable but unspecified within each group.
   * This list is passed as the `modifiers` argument of
   * [ModifierComposer.compose] when computing the attribute's effective value.
   *
   * @param agent owner of the attribute.
   * @param attributeId consumer-supplied identifier of the attribute.
   * @return a list of the modifiers currently affecting the attribute. May be
   *   empty.
   */
  fun modifiers(agent: Agent, attributeId: String): List<Modifier> {
    val direct = modifiers[agent]?.get(attributeId)?.toList() ?: emptyList()
    val fromItems = items[agent]?.values
      ?.flatMap { it.modifiers[attributeId].orEmpty() }
      ?: emptyList()
    val fromEffects = effects[agent]?.values
      ?.flatMap { it.effect.modifiers[attributeId].orEmpty() }
      ?: emptyList()
    return direct + fromItems + fromEffects
  }

  /**
   * Returns the [Attribute] snapshot for the attribute identified by [id] on
   * [agent], or `null` if that attribute has no base value.
   *
   * The effective value is computed on demand by passing the current aggregate
   * modifier list to the active [ModifierComposer]. This method does not mutate
   * the state.
   *
   * @param agent owner of the attribute.
   * @param id consumer-supplied attribute identifier.
   * @return a snapshot attribute, or `null` if no base value is set for the
   *   attribute on that agent.
   *
   * @see setAttribute
   * @see Attribute
   */
  fun attribute(agent: Agent, id: String): Attribute? {
    val base = baseValue(agent, id) ?: return null
    val effective = modifierComposer.compose(base, modifiers(agent, id))
    return Attribute(id, base, effective)
  }

  /**
   * Returns a snapshot of every attribute currently defined on [agent].
   *
   * Only attributes that have a base value (set via [setAttribute]) are included;
   * modifiers attached to non-existent attributes are ignored by this method.
   * Effective values are computed with the active [ModifierComposer].
   *
   * @param agent owner of the attributes.
   * @return an immutable set of attribute snapshots. May be empty if no attribute
   *   has been set or the agent is not registered.
   */
  fun attributes(agent: Agent): Set<Attribute> =
    baseValues[agent]?.keys?.mapNotNull { attribute(agent, it) }?.toSet() ?: emptySet()

  /**
   * Removes the attribute identified by [id] from [agent], including its base
   * value and any direct modifiers attached to it.
   *
   * Modifiers contributed by [Item]s or [Effect]s are unaffected by this call;
   * they simply have no base value to compose against until a new base value is
   * set. Removing an attribute that does not exist is a no-op.
   *
   * @param agent owner of the attribute.
   * @param id consumer-supplied attribute identifier.
   */
  fun removeAttribute(agent: Agent, id: String) {
    baseValues[agent]?.remove(id)
    modifiers[agent]?.remove(id)
  }
}

/**
 * Abstraction over a source of bounded pseudo-random integers.
 *
 * The library never touches global randomness directly. Every random decision
 * that the library makes — currently only [UniformDamageRoll] — obtains its value
 * through a [RandomSource], allowing tests to substitute a deterministic
 * implementation.
 *
 * Because the interface is a `fun interface`, a source can be provided inline as
 * a lambda where a `RandomSource` is expected.
 *
 * @see DefaultRandomSource
 * @see DeterministicRandomSource
 */
fun interface RandomSource {
  /**
   * Returns a pseudo-random integer uniformly distributed over the inclusive
   * interval `[fromInclusive, toInclusive]`.
   *
   * @param fromInclusive lower bound, inclusive.
   * @param toInclusive upper bound, inclusive.
   * @return an integer `n` such that `fromInclusive <= n <= toInclusive`.
   *
   * @throws IllegalArgumentException if `fromInclusive > toInclusive`.
   */
  fun nextInt(fromInclusive: Int, toInclusive: Int): Int
}

/**
 * Conventional [RandomSource] backed by [kotlin.random.Random.Default].
 *
 * This is the implementation to use in production code when reproducibility is
 * not required. It is not seedable directly; consumers that need a deterministic
 * production source should implement [RandomSource] on top of their own
 * `kotlin.random.Random` instance, or use [DeterministicRandomSource] in tests.
 *
 * @see RandomSource
 */
object DefaultRandomSource : RandomSource {
  private val random = Random.Default

  /**
   * Returns a uniformly distributed integer in the inclusive interval
   * `[fromInclusive, toInclusive]`.
   *
   * When [fromInclusive] equals [toInclusive], that value is returned without
   * consuming randomness.
   *
   * @param fromInclusive lower bound, inclusive.
   * @param toInclusive upper bound, inclusive.
   * @return an integer within the interval.
   *
   * @throws IllegalArgumentException if `fromInclusive > toInclusive`.
   */
  override fun nextInt(fromInclusive: Int, toInclusive: Int): Int {
    require(fromInclusive <= toInclusive) {
      "fromInclusive ($fromInclusive) must be <= toInclusive ($toInclusive)"
    }
    if (fromInclusive == toInclusive) return fromInclusive
    return random.nextInt(fromInclusive, toInclusive + 1)
  }
}

/**
 * [RandomSource] that returns a pre-configured sequence of integers, enabling
 * reproducible tests.
 *
 * Values are consumed in order. Each value must lie within the inclusive interval
 * requested by the caller; otherwise [nextInt] throws, surfacing a mismatch
 * between the test's expectations and the caller's requested range. Exhausting
 * the sequence throws [NoSuchElementException] from the underlying iterator.
 *
 * @constructor Creates a source that draws values from [values] in order.
 * @param values iterator over the fixed sequence of integers to return.
 *
 * @see RandomSource
 */
class DeterministicRandomSource(
  private val values: Iterator<Int>
) : RandomSource {

  /**
   * Convenience constructor that draws values from the provided [values] in
   * order.
   *
   * @param values the sequence of integers that this source will return, in
   *   order.
   */
  constructor(vararg values: Int) : this(values.iterator())

  /**
   * Returns the next pre-configured value, validating it against the requested
   * interval.
   *
   * @param fromInclusive lower bound, inclusive.
   * @param toInclusive upper bound, inclusive.
   * @return the next value from the configured sequence.
   *
   * @throws IllegalArgumentException if `fromInclusive > toInclusive` or if the
   *   next configured value lies outside `[fromInclusive, toInclusive]`.
   * @throws NoSuchElementException if the configured sequence has been
   *   exhausted.
   */
  override fun nextInt(fromInclusive: Int, toInclusive: Int): Int {
    require(fromInclusive <= toInclusive) {
      "fromInclusive ($fromInclusive) must be <= toInclusive ($toInclusive)"
    }
    val next = values.next()
    require(next in fromInclusive..toInclusive) {
      "Deterministic value $next is outside [$fromInclusive, $toInclusive]"
    }
    return next
  }
}

/**
 * Inclusive range of possible damage values produced by a [DamageFormula].
 *
 * A fixed-damage formula is represented by a range whose [min] equals [max]. A
 * variable-damage formula is represented by a range with `min < max` and is
 * resolved by a [DamageRoll].
 *
 * Both bounds are inclusive and neither may be negative.
 *
 * @property min lower bound of the range, inclusive. Must be non-negative.
 * @property max upper bound of the range, inclusive. Must be `>= min`.
 *
 * @throws IllegalArgumentException if [min] is negative or `min > max`.
 *
 * @see DamageFormula
 * @see DamageRoll
 */
data class DamageRange(val min: Int, val max: Int) {
  init {
    require(min >= 0) { "Damage min ($min) must not be negative" }
    require(min <= max) { "DamageRange min ($min) must be <= max ($max)" }
  }
}

/**
 * Context passed to a [DamageFormula] when computing a [DamageRange].
 *
 * The context exposes only generic information: the attacker, the target and the
 * live [CombatState] in which the calculation occurs. The formula is free to read
 * any attribute, modifier, item or effect from [state]; the library imposes no
 * shape on the calculation beyond the inputs given here.
 *
 * @property attacker participant originating the damage.
 * @property target participant receiving the damage.
 * @property state live combat state in which the damage is being computed. The
 *   formula must not mutate it; formulas are pure functions of their inputs.
 *
 * @see DamageFormula
 */
data class DamageContext(
  val attacker: Agent,
  val target: Agent,
  val state: CombatState
)

/**
 * Strategy that determines the mathematical possibilities of damage for a given
 * [DamageContext].
 *
 * The formula describes *what damage is possible*, not *which damage occurred*.
 * Choosing a concrete value from the range is the responsibility of a
 * [DamageRoll]. A formula must not use randomness and must not mutate the
 * [CombatState] in the supplied [DamageContext].
 *
 * The library never imposes a formula. Consumers implement this interface to
 * express their game's damage model, whether by referencing attributes, items,
 * effects, or anything else reachable through the context.
 *
 * Because the interface is a `fun interface`, a formula can be provided inline as
 * a lambda.
 *
 * @see DamageRange
 * @see DamageRoll
 * @see FixedDamageFormula
 */
fun interface DamageFormula {
  /**
   * Computes the damage range for the given context.
   *
   * @param context the attacker, the target and the live combat state relevant to
   *   the calculation. Never `null`.
   * @return the inclusive range of possible damage values. Must be non-negative.
   */
  fun calculate(context: DamageContext): DamageRange
}

/**
 * Convenience [DamageFormula] that always yields the same fixed damage, modeled
 * as a [DamageRange] with `min == max`.
 *
 * This is the canonical fixed-damage formula: any roll over the produced range
 * returns exactly [damage], since the range is degenerate.
 *
 * @constructor Creates a fixed-damage formula.
 * @param damage the constant damage value produced by this formula. Must be
 *   non-negative.
 *
 * @throws IllegalArgumentException if [damage] is negative.
 *
 * @see DamageFormula
 */
class FixedDamageFormula(private val damage: Int) : DamageFormula {
  init {
    require(damage >= 0) { "Fixed damage ($damage) must not be negative" }
  }

  /**
   * Returns a degenerate [DamageRange] whose bounds are both [damage].
   *
   * @param context ignored by this implementation.
   * @return a [DamageRange] with `min == max == damage`.
   */
  override fun calculate(context: DamageContext): DamageRange =
    DamageRange(damage, damage)
}

/**
 * Strategy that selects a concrete damage value from a [DamageRange].
 *
 * The roll is the only step in the damage pipeline that introduces randomness. It
 * is deliberately separated from [DamageFormula] so that formulas remain pure and
 * testable, and so that a single formula can be paired with different randomness
 * policies.
 *
 * Because the interface is a `fun interface`, a roll can be provided inline as a
 * lambda.
 *
 * @see DamageRange
 * @see DeterministicDamageRoll
 * @see UniformDamageRoll
 */
fun interface DamageRoll {
  /**
   * Selects a concrete damage value from [range].
   *
   * @param range the inclusive range produced by a [DamageFormula].
   * @return a value within `[range.min, range.max]`, inclusive.
   */
  fun roll(range: DamageRange): Int
}

/**
 * [DamageRoll] that always selects the minimum of the given range.
 *
 * For a fixed range (`min == max`), this returns that common value. For a
 * variable range, it consistently returns the lower bound, which makes the roll
 * useful in tests and for consumers that want a "worst case" policy without
 * randomness.
 *
 * @see DamageRoll
 */
object DeterministicDamageRoll : DamageRoll {
  /**
   * Returns [DamageRange.min].
   *
   * @param range the range to roll over.
   * @return `range.min`.
   */
  override fun roll(range: DamageRange): Int = range.min
}

/**
 * [DamageRoll] that draws a uniformly distributed value from the inclusive range
 * `[min, max]` using a [RandomSource].
 *
 * The randomness is entirely delegated to the supplied source, so tests can
 * substitute a [DeterministicRandomSource] to make the roll fully reproducible.
 *
 * @constructor Creates a uniform roll over the supplied source.
 * @param randomSource source of randomness used to pick values.
 *
 * @see RandomSource
 * @see DeterministicRandomSource
 */
class UniformDamageRoll(
  private val randomSource: RandomSource
) : DamageRoll {

  /**
   * Draws a uniformly distributed value from `[range.min, range.max]`, inclusive.
   *
   * @param range the range to roll over.
   * @return a value `n` such that `range.min <= n <= range.max`.
   */
  override fun roll(range: DamageRange): Int =
    randomSource.nextInt(range.min, range.max)
}

/**
 * Context passed to a [Mitigation] when transforming rolled damage.
 *
 * The context exposes the origin and target of the damage, its consumer-defined
 * [damageType], and the live [CombatState]. This is enough for a mitigation to
 * consult attributes, effects, or any other state it needs.
 *
 * @property origin participant that produced the damage.
 * @property target participant that will receive the damage.
 * @property damageType consumer-supplied identifier of the damage type, used to
 *   distinguish, for example, physical from magical damage. The core never
 *   interprets this value.
 * @property state live combat state in which the mitigation occurs.
 *
 * @see Mitigation
 */
data class MitigationContext(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val state: CombatState
)

/**
 * Strategy that transforms [rolledDamage][DamageRoll] into `mitigatedDamage`
 * before application.
 *
 * A mitigation is a pure transformation: given a non-negative input it must
 * return a non-negative output. The pipeline guarantees that a mitigation is only
 * invoked with a non-negative input, and the core additionally clamps
 * intermediate results in [MitigationChain] and in the standard implementations.
 *
 * The library never imposes a mitigation formula; consumers may chain multiple
 * mitigations, substitute the standard ones, or implement their own. When no
 * mitigation is desired, use [NoMitigation].
 *
 * Because the interface is a `fun interface`, a mitigation can be provided inline
 * as a lambda.
 *
 * @see NoMitigation
 * @see FixedReductionMitigation
 * @see PercentageReductionMitigation
 * @see MitigationChain
 */
fun interface Mitigation {
  /**
   * Applies this mitigation to [rolledDamage].
   *
   * @param rolledDamage non-negative damage produced by a [DamageRoll].
   * @param context origin, target, damage type and live combat state relevant to
   *   the mitigation.
   * @return `mitigatedDamage`, a non-negative value. Implementations must clamp
   *   at zero rather than returning negative numbers.
   */
  fun mitigate(rolledDamage: Int, context: MitigationContext): Int
}

/**
 * [Mitigation] that leaves the rolled damage unchanged, clamping it at zero.
 *
 * This is the canonical "no mitigation" strategy. It is a valid identity for
 * chaining and is safe to use in tests that want to observe the rolled damage as
 * the mitigated damage.
 *
 * @see Mitigation
 */
object NoMitigation : Mitigation {
  /**
   * Returns [rolledDamage] clamped at zero.
   *
   * @param rolledDamage damage produced by a roll.
   * @param context ignored by this implementation.
   * @return `max(rolledDamage, 0)`.
   */
  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int =
    rolledDamage.coerceAtLeast(0)
}

/**
 * [Mitigation] that subtracts a fixed amount from the rolled damage, clamping the
 * result at zero.
 *
 * The formula is `max(rolledDamage - reduction, 0)`.
 *
 * @constructor Creates a fixed-reduction mitigation.
 * @param reduction non-negative amount subtracted from the rolled damage.
 *
 * @throws IllegalArgumentException if [reduction] is negative.
 *
 * @see Mitigation
 */
class FixedReductionMitigation(private val reduction: Int) : Mitigation {
  init {
    require(reduction >= 0) { "Fixed reduction ($reduction) must not be negative" }
  }

  /**
   * Subtracts [reduction] from [rolledDamage], clamped at zero.
   *
   * @param rolledDamage damage produced by a roll.
   * @param context ignored by this implementation.
   * @return `max(rolledDamage - reduction, 0)`.
   */
  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int =
    (rolledDamage - reduction).coerceAtLeast(0)
}

/**
 * [Mitigation] that reduces damage by a percentage, rounding down and clamping at
 * zero.
 *
 * The formula is `floor(rolledDamage * max(100 - percentage, 0) / 100)`, clamped
 * at zero. A [percentage] of `100` or more nullifies the damage; a [percentage]
 * of `0` leaves it unchanged.
 *
 * @constructor Creates a percentage-reduction mitigation.
 * @param percentage non-negative percentage by which damage is reduced.
 *
 * @throws IllegalArgumentException if [percentage] is negative.
 *
 * @see Mitigation
 */
class PercentageReductionMitigation(private val percentage: Int) : Mitigation {
  init {
    require(percentage >= 0) { "Percentage ($percentage) must not be negative" }
  }

  /**
   * Reduces [rolledDamage] by [percentage], rounding down and clamping at zero.
   *
   * @param rolledDamage damage produced by a roll.
   * @param context ignored by this implementation.
   * @return the reduced, non-negative damage.
   */
  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int {
    val factor = (100 - percentage).coerceAtLeast(0)
    return floor(rolledDamage * factor / 100.0).toInt().coerceAtLeast(0)
  }
}

/**
 * [Mitigation] that applies a sequence of mitigations in order, feeding the
 * output of each as the input of the next.
 *
 * The chain is deterministic and preserves order. Because each step is clamped at
 * zero by contract, the chain's output is guaranteed to be non-negative. An empty
 * chain behaves as [NoMitigation].
 *
 * @constructor Creates a chain over the provided mitigations.
 * @param mitigations ordered list of mitigations to apply. Each receives the
 *   result of the previous one.
 *
 * @see Mitigation
 */
class MitigationChain(
  private val mitigations: List<Mitigation>
) : Mitigation {

  /**
   * Convenience constructor that builds a chain from the provided mitigations, in
   * the given order.
   *
   * @param mitigations ordered mitigations to apply.
   */
  constructor(vararg mitigations: Mitigation) : this(mitigations.toList())

  /**
   * Applies each mitigation in order to the running damage value.
   *
   * @param rolledDamage initial damage produced by a roll.
   * @param context origin, target, damage type and live combat state relevant to
   *   the mitigation.
   * @return the damage after every mitigation has been applied, clamped at zero.
   */
  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int {
    var current = rolledDamage.coerceAtLeast(0)
    for (mitigation in mitigations) {
      current = mitigation.mitigate(current, context).coerceAtLeast(0)
    }
    return current
  }
}

/**
 * Context passed to a [DamageApplication] when writing damage into the state.
 *
 * The context exposes the origin, the target, the consumer-defined [damageType]
 * and the live [CombatState]. It is the only handle through which an application
 * can locate the target and mutate state.
 *
 * @property origin participant that produced the damage.
 * @property target participant that will receive the damage.
 * @property damageType consumer-supplied identifier of the damage type.
 * @property state live combat state in which the application occurs. Unlike
 *   formulas and mitigations, the application is expected to mutate this state.
 *
 * @see DamageApplication
 */
data class DamageApplicationContext(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val state: CombatState
)

/**
 * Strategy that writes `mitigatedDamage` into the [CombatState] and reports how
 * much damage was actually subtracted.
 *
 * The library does not presume how a game represents health or defeat. This
 * interface is the sole integration point where damage becomes state mutation:
 * implementations typically subtract from an attribute, clamp at zero, and call
 * [CombatState.markDefeated] when appropriate.
 *
 * The returned value is `appliedDamage`, which may differ from
 * `mitigatedDamage` — for example, if the target has less health than the
 * incoming damage, or if the implementation caps damage per hit.
 *
 * Because the interface is a `fun interface`, an application can be provided
 * inline as a lambda.
 *
 * @see DamageResult
 */
fun interface DamageApplication {
  /**
   * Applies [mitigatedDamage] to the state reachable through [context].
   *
   * @param mitigatedDamage non-negative damage produced by a [Mitigation].
   * @param context origin, target, damage type and live combat state.
   * @return the non-negative amount of damage effectively subtracted from the
   *   target.
   */
  fun apply(mitigatedDamage: Int, context: DamageApplicationContext): Int
}

/**
 * Structured, immutable result of resolving damage.
 *
 * The distinction between [rolledDamage], [mitigatedDamage] and [appliedDamage]
 * is preserved explicitly:
 *
 * - [rolledDamage] is the value produced by the [DamageRoll];
 * - [mitigatedDamage] is the value after every [Mitigation] has run;
 * - [appliedDamage] is the value effectively subtracted by the
 *   [DamageApplication].
 *
 * All three values are non-negative.
 *
 * @property origin participant that produced the damage.
 * @property target participant that received the damage.
 * @property damageType consumer-supplied identifier of the damage type.
 * @property rolledDamage value produced by the roll. Non-negative.
 * @property mitigatedDamage value after mitigation. Non-negative.
 * @property appliedDamage value effectively subtracted from the target.
 *   Non-negative.
 *
 * @throws IllegalArgumentException if any of the damage values is negative.
 *
 * @see DamageRoll
 * @see Mitigation
 * @see DamageApplication
 */
data class DamageResult(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val rolledDamage: Int,
  val mitigatedDamage: Int,
  val appliedDamage: Int
) {
  init {
    require(rolledDamage >= 0) { "rolledDamage ($rolledDamage) must not be negative" }
    require(mitigatedDamage >= 0) { "mitigatedDamage ($mitigatedDamage) must not be negative" }
    require(appliedDamage >= 0) { "appliedDamage ($appliedDamage) must not be negative" }
  }
}

/**
 * Intention to perform a combat interaction.
 *
 * A [CombatAction] carries only what any damage-related interaction needs:
 * attacker, target and damage type. It is intentionally free of embedded formulas
 * or mitigation logic; those are supplied to the resolver via dedicated
 * strategies. The library does not interpret [damageType]; it is passed through
 * unchanged to [MitigationContext] and [DamageApplicationContext] so that
 * strategies can react to it.
 *
 * The interface is open so consumers can define their own concrete actions
 * (spells, traps, area attacks, and so on) without touching the core.
 *
 * @see Attack
 * @see CombatResolver
 */
interface CombatAction {
  /** Participant originating the action. */
  val attacker: Agent

  /** Participant targeted by the action. */
  val target: Agent

  /** Consumer-supplied identifier of the damage type produced by this action. */
  val damageType: String
}

/**
 * Canonical [CombatAction] representing a direct attack.
 *
 * This is only a convenience: consumers may define their own action types, since
 * the pipeline depends solely on [CombatAction]. It carries no formula, no
 * mitigation and no hit rule; those are supplied externally to the
 * [CombatResolver].
 *
 * @property attacker participant performing the attack.
 * @property target participant being attacked.
 * @property damageType consumer-supplied identifier of the damage type of the
 *   attack.
 *
 * @see CombatAction
 * @see CombatResolver
 */
data class Attack(
  override val attacker: Agent,
  override val target: Agent,
  override val damageType: String
) : CombatAction

/**
 * Strategy that decides whether a [CombatAction] hits its target.
 *
 * Hit resolution is deliberately separated from damage calculation: an action
 * that misses produces no [DamageResult] and does not touch the target's state.
 * The library only requires that a resolution return a boolean for a given action
 * and state.
 *
 * Because the interface is a `fun interface`, a hit rule can be provided inline
 * as a lambda.
 *
 * @see AlwaysHit
 * @see CombatResolver
 */
fun interface HitResolution {
  /**
   * Decides whether the given action hits.
   *
   * @param action the action being resolved.
   * @param state the live combat state, available for rules that read attributes
   *   or effects. Implementations must not mutate the state.
   * @return `true` if the action hits, `false` otherwise.
   */
  fun resolve(action: CombatAction, state: CombatState): Boolean
}

/**
 * [HitResolution] that always reports a hit.
 *
 * Useful as a default and as a building block for tests that do not care about
 * hit chance.
 *
 * @see HitResolution
 */
object AlwaysHit : HitResolution {
  /**
   * Returns `true` unconditionally.
   *
   * @param action ignored by this implementation.
   * @param state ignored by this implementation.
   * @return `true`.
   */
  override fun resolve(action: CombatAction, state: CombatState): Boolean = true
}

/**
 * Full outcome of resolving a [CombatAction] through a [CombatResolver].
 *
 * The result wraps, rather than replaces, its parts:
 *
 * - [action] is the resolved action;
 * - [hit] reports whether the action hit;
 * - [damageResult] is present only when [hit] is `true`;
 * - [state] is the same [CombatState] instance that was passed to the resolver,
 *   after mutation;
 * - [events] is the ordered list of [CombatEvent]s produced during the
 *   resolution, including events emitted by user callbacks triggered inside the
 *   pipeline.
 *
 * @property action the action that was resolved.
 * @property hit `true` if the action hit, `false` otherwise.
 * @property damageResult the damage result, or `null` when [hit] is `false`.
 * @property state the combat state after the resolution.
 * @property events the events produced during the resolution, in emission order.
 *
 * @see CombatResolver
 * @see DamageResult
 * @see CombatEvent
 */
data class CombatResult(
  val action: CombatAction,
  val hit: Boolean,
  val damageResult: DamageResult?,
  val state: CombatState,
  val events: List<CombatEvent>
)

/**
 * Orchestrator that drives a [CombatAction] through the full damage pipeline:
 *
 * `Action -> Hit -> Formula -> Roll -> Mitigation -> Application`.
 *
 * Every step is a strategy provided at construction time, so the resolver itself
 * carries no game-specific logic and no fixed formula. It also does **not**
 * depend on [ModifierComposer]: a [DamageFormula] may use a composer internally
 * if it wishes, but that is not the resolver's concern.
 *
 * The resolver is stateless with respect to combat: all mutable state lives in
 * the [CombatState] passed to [resolve]. A single resolver instance can therefore
 * be reused across combats.
 *
 * @constructor Creates a resolver with the given strategies.
 * @param hitResolution strategy deciding whether an action hits.
 * @param damageFormula strategy computing the possible damage range of a hit.
 * @param damageRoll strategy selecting a concrete value from the range.
 * @param mitigation strategy transforming the rolled value before application.
 * @param damageApplication strategy writing the mitigated damage into the state.
 *
 * @see CombatAction
 * @see CombatResult
 */
class CombatResolver(
  private val hitResolution: HitResolution,
  private val damageFormula: DamageFormula,
  private val damageRoll: DamageRoll,
  private val mitigation: Mitigation,
  private val damageApplication: DamageApplication
) {

  /**
   * Resolves [action] against [state].
   *
   * Pipeline:
   *
   * 1. Events pending on [state] are drained and discarded, so that [CombatResult.events]
   *    contains only events produced by this resolution.
   * 2. [HitResolution.resolve] is consulted. On a miss, an
   *    [CombatEvent.AttackMissed] is emitted, the state is left unchanged, and a
   *    result with `hit == false` and `damageResult == null` is returned.
   * 3. On a hit, [CombatEvent.AttackPerformed] is emitted, then the formula, roll,
   *    mitigation and application steps run in order. [CombatEvent.DamageProduced]
   *    is emitted before the application step (so events emitted by user
   *    callbacks triggered during application can be interleaved correctly), and
   *    [CombatEvent.DamageApplied] is emitted afterwards.
   *
   * The same [state] instance is returned in the result; it may have been mutated
   * by the [DamageApplication] or by callbacks invoked from user strategies.
   *
   * @param action the action to resolve.
   * @param state the combat state against which the action is resolved. Mutated
   *   in place by the [DamageApplication].
   * @return the complete [CombatResult] for this resolution.
   */
  fun resolve(action: CombatAction, state: CombatState): CombatResult {
    state.drainEvents()

    if (!hitResolution.resolve(action, state)) {
      val events = state.drainEvents() + CombatEvent.AttackMissed(action)
      return CombatResult(action, hit = false, damageResult = null, state = state, events = events)
    }

    val events = mutableListOf<CombatEvent>()
    events.add(CombatEvent.AttackPerformed(action))

    val range = damageFormula.calculate(DamageContext(action.attacker, action.target, state))
    val rolledDamage = damageRoll.roll(range)
    val mitigatedDamage = mitigation.mitigate(
      rolledDamage,
      MitigationContext(action.attacker, action.target, action.damageType, state)
    )
    val appliedDamage = damageApplication.apply(
      mitigatedDamage,
      DamageApplicationContext(action.attacker, action.target, action.damageType, state)
    )

    val damageResult = DamageResult(
      origin = action.attacker,
      target = action.target,
      damageType = action.damageType,
      rolledDamage = rolledDamage,
      mitigatedDamage = mitigatedDamage,
      appliedDamage = appliedDamage
    )

    events.add(CombatEvent.DamageProduced(damageResult))
    events.addAll(state.drainEvents())
    events.add(CombatEvent.DamageApplied(damageResult))

    return CombatResult(action, hit = true, damageResult = damageResult, state = state, events = events)
  }
}

/**
 * Structured, rendering-free record of something that happened during combat.
 *
 * Events are the observable history of a resolution; they carry structured data
 * only and never produce text or side effects. The library emits events from
 * [CombatState] operations and from [CombatResolver.resolve]; consumers consume
 * them through [CombatState.drainEvents] and [CombatResult.events].
 *
 * The hierarchy is sealed so that consumers can exhaustively match subtypes.
 *
 * @see CombatState.drainEvents
 * @see CombatResult.events
 * @see CombatResolver
 */
sealed interface CombatEvent {
  /**
   * Emitted when a [CombatAction] has been confirmed as a hit and its damage is
   * about to be computed.
   *
   * @property action the action that hit.
   */
  data class AttackPerformed(val action: CombatAction) : CombatEvent

  /**
   * Emitted when a [CombatAction] fails its [HitResolution] and therefore
   * produces no damage.
   *
   * @property action the action that missed.
   */
  data class AttackMissed(val action: CombatAction) : CombatEvent

  /**
   * Emitted after roll and mitigation, before the damage is applied to the
   * state, so that listeners can react to the produced values before mutation.
   *
   * @property result the damage result produced by the pipeline at this stage.
   */
  data class DamageProduced(val result: DamageResult) : CombatEvent

  /**
   * Emitted after [DamageApplication] has written the damage into the state.
   *
   * @property result the final damage result, including `appliedDamage`.
   */
  data class DamageApplied(val result: DamageResult) : CombatEvent

  /**
   * Emitted when an [Effect] is applied to an agent through
   * [CombatState.applyEffect].
   *
   * @property agent the agent that received the effect.
   * @property effect the effect that was applied.
   */
  data class EffectApplied(val agent: Agent, val effect: Effect) : CombatEvent

  /**
   * Emitted when an [Effect] is removed from an agent, whether explicitly via
   * [CombatState.removeEffect], by replacement in [CombatState.applyEffect], or
   * by expiration in [CombatState.tick].
   *
   * @property agent the agent that owned the effect.
   * @property effect the effect that was removed.
   */
  data class EffectRemoved(val agent: Agent, val effect: Effect) : CombatEvent

  /**
   * Emitted the first time an agent is marked defeated via
   * [CombatState.markDefeated]. Subsequent calls for the same agent do not emit
   * further events.
   *
   * @property agent the agent that was marked defeated.
   */
  data class Defeat(val agent: Agent) : CombatEvent
}