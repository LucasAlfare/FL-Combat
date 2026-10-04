package com.lucasalfare.flcombat

data class Agent(val id: String)

class CombatState {

  private val agents: MutableSet<Agent> = mutableSetOf()
  private val baseValues: MutableMap<Agent, MutableMap<String, Int>> = mutableMapOf()

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

  fun attribute(agent: Agent, id: String): Attribute? {
    val base = baseValue(agent, id) ?: return null
    return Attribute(id, base, base)
  }

  fun attributes(agent: Agent): Set<Attribute> =
    baseValues[agent]?.map { (id, base) -> Attribute(id, base, base) }?.toSet() ?: emptySet()

  fun removeAttribute(agent: Agent, id: String) {
    baseValues[agent]?.remove(id)
  }
}

data class Attribute(
  val id: String,
  val baseValue: Int,
  val effectiveValue: Int
)