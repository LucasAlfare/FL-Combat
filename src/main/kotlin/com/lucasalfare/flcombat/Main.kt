package com.lucasalfare.flcombat

data class Agent(val id: String)

class CombatState {

  private val agents: MutableSet<Agent> = mutableSetOf()

  fun register(agent: Agent) {
    agents.add(agent)
  }

  fun contains(agent: Agent): Boolean = agent in agents

  fun agents(): Set<Agent> = agents.toSet()
}