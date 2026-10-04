@file:Suppress("unused")

package com.lucasalfare.flcombat

import kotlin.math.floor

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

  fun modifiers(agent: Agent, attributeId: String): List<Modifier> {
    val direct = modifiers[agent]?.get(attributeId)?.toList() ?: emptyList()
    val fromItems = items[agent]?.values
      ?.flatMap { it.modifiers[attributeId].orEmpty() }
      ?: emptyList()
    return direct + fromItems
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