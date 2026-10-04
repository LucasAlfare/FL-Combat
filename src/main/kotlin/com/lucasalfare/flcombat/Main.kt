@file:Suppress("unused")

package com.lucasalfare.flcombat

import kotlin.math.floor
import kotlin.random.Random

data class Agent(val id: String)

data class Attribute(
  val id: String,
  val baseValue: Int,
  val effectiveValue: Int
)

data class Modifier(val id: String, val value: Int)

data class Item(
  val id: String,
  val modifiers: Map<String, List<Modifier>> = emptyMap()
)

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

fun interface ModifierComposer {
  fun compose(baseValue: Int, modifiers: Collection<Modifier>): Int
}

object AdditiveThenPercentageComposer : ModifierComposer {
  const val ADDITIVE = "additive"
  const val PERCENTAGE = "percentage"

  override fun compose(baseValue: Int, modifiers: Collection<Modifier>): Int {
    val additive = modifiers.filter { it.id == ADDITIVE }.sumOf { it.value }
    val percentage = modifiers.filter { it.id == PERCENTAGE }.sumOf { it.value }
    val afterAdditives = baseValue + additive
    return floor(afterAdditives * (100 + percentage) / 100.0).toInt()
  }
}

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

  fun drainEvents(): List<CombatEvent> {
    val drained = pendingEvents.toList()
    pendingEvents.clear()
    return drained
  }

  fun register(agent: Agent) {
    agents.add(agent)
  }

  fun contains(agent: Agent): Boolean = agent in agents

  fun agents(): Set<Agent> = agents.toSet()

  fun markDefeated(agent: Agent) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    if (defeated.add(agent)) {
      pendingEvents.add(CombatEvent.Defeat(agent))
    }
  }

  fun isDefeated(agent: Agent): Boolean = agent in defeated

  fun setAttribute(agent: Agent, id: String, baseValue: Int) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    baseValues.getOrPut(agent) { mutableMapOf() }[id] = baseValue
  }

  private fun baseValue(agent: Agent, id: String): Int? =
    baseValues[agent]?.get(id)

  fun addModifier(agent: Agent, attributeId: String, modifier: Modifier) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    modifiers.getOrPut(agent) { mutableMapOf() }
      .getOrPut(attributeId) { mutableListOf() }
      .add(modifier)
  }

  fun removeModifier(agent: Agent, attributeId: String, modifier: Modifier) {
    modifiers[agent]?.get(attributeId)?.remove(modifier)
  }

  fun addItem(agent: Agent, item: Item) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    items.getOrPut(agent) { mutableMapOf() }[item.id] = item
  }

  fun removeItem(agent: Agent, itemId: String) {
    items[agent]?.remove(itemId)
  }

  fun items(agent: Agent): Set<Item> =
    items[agent]?.values?.toSet() ?: emptySet()

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

  fun removeEffect(agent: Agent, effectId: String): Effect? {
    val removed = effects[agent]?.remove(effectId) ?: return null
    removed.effect.onRemove(agent, this)
    pendingEvents.add(CombatEvent.EffectRemoved(agent, removed.effect))
    return removed.effect
  }

  fun effects(agent: Agent): Set<Effect> =
    effects[agent]?.values?.map { it.effect }?.toSet() ?: emptySet()

  fun effect(agent: Agent, effectId: String): Effect? =
    effects[agent]?.get(effectId)?.effect

  fun remainingTicks(agent: Agent, effectId: String): Int? =
    effects[agent]?.get(effectId)?.remainingTicks

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

  fun attribute(agent: Agent, id: String): Attribute? {
    val base = baseValue(agent, id) ?: return null
    val effective = modifierComposer.compose(base, modifiers(agent, id))
    return Attribute(id, base, effective)
  }

  fun attributes(agent: Agent): Set<Attribute> =
    baseValues[agent]?.keys?.mapNotNull { attribute(agent, it) }?.toSet() ?: emptySet()

  fun removeAttribute(agent: Agent, id: String) {
    baseValues[agent]?.remove(id)
    modifiers[agent]?.remove(id)
  }
}

fun interface RandomSource {
  fun nextInt(fromInclusive: Int, toInclusive: Int): Int
}

object DefaultRandomSource : RandomSource {
  private val random = Random.Default

  override fun nextInt(fromInclusive: Int, toInclusive: Int): Int {
    require(fromInclusive <= toInclusive) {
      "fromInclusive ($fromInclusive) must be <= toInclusive ($toInclusive)"
    }
    if (fromInclusive == toInclusive) return fromInclusive
    return random.nextInt(fromInclusive, toInclusive + 1)
  }
}

class DeterministicRandomSource(
  private val values: Iterator<Int>
) : RandomSource {

  constructor(vararg values: Int) : this(values.iterator())

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

data class DamageRange(val min: Int, val max: Int) {
  init {
    require(min >= 0) { "Damage min ($min) must not be negative" }
    require(min <= max) { "DamageRange min ($min) must be <= max ($max)" }
  }
}

data class DamageContext(
  val attacker: Agent,
  val target: Agent,
  val state: CombatState
)

fun interface DamageFormula {
  fun calculate(context: DamageContext): DamageRange
}

class FixedDamageFormula(private val damage: Int) : DamageFormula {
  init {
    require(damage >= 0) { "Fixed damage ($damage) must not be negative" }
  }

  override fun calculate(context: DamageContext): DamageRange =
    DamageRange(damage, damage)
}

fun interface DamageRoll {
  fun roll(range: DamageRange): Int
}

object DeterministicDamageRoll : DamageRoll {
  override fun roll(range: DamageRange): Int = range.min
}

class UniformDamageRoll(
  private val randomSource: RandomSource
) : DamageRoll {

  override fun roll(range: DamageRange): Int =
    randomSource.nextInt(range.min, range.max)
}

data class MitigationContext(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val state: CombatState
)

fun interface Mitigation {
  fun mitigate(rolledDamage: Int, context: MitigationContext): Int
}

object NoMitigation : Mitigation {
  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int =
    rolledDamage.coerceAtLeast(0)
}

class FixedReductionMitigation(private val reduction: Int) : Mitigation {
  init {
    require(reduction >= 0) { "Fixed reduction ($reduction) must not be negative" }
  }

  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int =
    (rolledDamage - reduction).coerceAtLeast(0)
}

class PercentageReductionMitigation(private val percentage: Int) : Mitigation {
  init {
    require(percentage >= 0) { "Percentage ($percentage) must not be negative" }
  }

  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int {
    val factor = (100 - percentage).coerceAtLeast(0)
    return floor(rolledDamage * factor / 100.0).toInt().coerceAtLeast(0)
  }
}

class MitigationChain(
  private val mitigations: List<Mitigation>
) : Mitigation {

  constructor(vararg mitigations: Mitigation) : this(mitigations.toList())

  override fun mitigate(rolledDamage: Int, context: MitigationContext): Int {
    var current = rolledDamage.coerceAtLeast(0)
    for (mitigation in mitigations) {
      current = mitigation.mitigate(current, context).coerceAtLeast(0)
    }
    return current
  }
}

data class DamageApplicationContext(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val state: CombatState
)

fun interface DamageApplication {
  fun apply(mitigatedDamage: Int, context: DamageApplicationContext): Int
}

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

interface CombatAction {
  val attacker: Agent
  val target: Agent
  val damageType: String
}

// example of an action only; interactions (combat actions) must be totally free.
data class Attack(
  override val attacker: Agent,
  override val target: Agent,
  override val damageType: String
) : CombatAction

fun interface HitResolution {
  fun resolve(action: CombatAction, state: CombatState): Boolean
}

object AlwaysHit : HitResolution {
  override fun resolve(action: CombatAction, state: CombatState): Boolean = true
}

data class CombatResult(
  val action: CombatAction,
  val hit: Boolean,
  val damageResult: DamageResult?,
  val state: CombatState,
  val events: List<CombatEvent>
)

class CombatResolver(
  private val hitResolution: HitResolution,
  private val damageFormula: DamageFormula,
  private val damageRoll: DamageRoll,
  private val mitigation: Mitigation,
  private val damageApplication: DamageApplication
) {

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

sealed interface CombatEvent {
  data class AttackPerformed(val action: CombatAction) : CombatEvent
  data class AttackMissed(val action: CombatAction) : CombatEvent
  data class DamageProduced(val result: DamageResult) : CombatEvent
  data class DamageApplied(val result: DamageResult) : CombatEvent
  data class EffectApplied(val agent: Agent, val effect: Effect) : CombatEvent
  data class EffectRemoved(val agent: Agent, val effect: Effect) : CombatEvent
  data class Defeat(val agent: Agent) : CombatEvent
}