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

class Effect(
  val id: String,
  val modifiers: Map<String, List<Modifier>> = emptyMap(),
  val durationTicks: Int? = null,
  val onApply: (Agent, CombatState) -> Unit = { _, _ -> },
  val onRemove: (Agent, CombatState) -> Unit = { _, _ -> }
) {
  var remainingTicks: Int? = durationTicks
    private set

  val isPermanent: Boolean get() = durationTicks == null
  val isExpired: Boolean get() = remainingTicks == 0

  fun tick() {
    val remaining = remainingTicks ?: return
    if (remaining > 0) remainingTicks = remaining - 1
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

  private val agents: MutableSet<Agent> = mutableSetOf()
  private val baseValues: MutableMap<Agent, MutableMap<String, Int>> = mutableMapOf()
  private val modifiers: MutableMap<Agent, MutableMap<String, MutableList<Modifier>>> = mutableMapOf()
  private val items: MutableMap<Agent, MutableMap<String, Item>> = mutableMapOf()
  private val effects: MutableMap<Agent, MutableMap<String, Effect>> = mutableMapOf()

  fun register(agent: Agent) {
    agents.add(agent)
  }

  fun contains(agent: Agent): Boolean = agent in agents

  fun agents(): Set<Agent> = agents.toSet()

  fun setAttribute(agent: Agent, id: String, baseValue: Int) {
    require(agent in agents) { "Agent '${agent.id}' is not registered" }
    baseValues.getOrPut(agent) { mutableMapOf() }[id] = baseValue
  }

  fun baseValue(agent: Agent, id: String): Int? =
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
    agentEffects[effect.id]?.onRemove?.invoke(agent, this)
    agentEffects[effect.id] = effect
    effect.onApply(agent, this)
  }

  fun removeEffect(agent: Agent, effectId: String): Effect? {
    val removed = effects[agent]?.remove(effectId) ?: return null
    removed.onRemove(agent, this)
    return removed
  }

  fun effects(agent: Agent): Set<Effect> =
    effects[agent]?.values?.toSet() ?: emptySet()

  fun effect(agent: Agent, effectId: String): Effect? =
    effects[agent]?.get(effectId)

  fun tick(agent: Agent) {
    val agentEffects = effects[agent] ?: return
    val expired = mutableListOf<Effect>()
    for (effect in agentEffects.values) {
      effect.tick()
      if (effect.isExpired) expired.add(effect)
    }
    for (effect in expired) {
      agentEffects.remove(effect.id)
      effect.onRemove(agent, this)
    }
  }

  fun modifiers(agent: Agent, attributeId: String): List<Modifier> {
    val direct = modifiers[agent]?.get(attributeId)?.toList() ?: emptyList()
    val fromItems = items[agent]?.values
      ?.flatMap { it.modifiers[attributeId].orEmpty() }
      ?: emptyList()
    val fromEffects = effects[agent]?.values
      ?.flatMap { it.modifiers[attributeId].orEmpty() }
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