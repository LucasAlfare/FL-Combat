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

  fun modifiers(agent: Agent, attributeId: String): List<Modifier> =
    modifiers[agent]?.get(attributeId)?.toList() ?: emptyList()

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