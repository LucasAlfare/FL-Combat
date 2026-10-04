package com.lucasalfare.flcombat

import kotlin.test.*

class MainTests {

  // ---------- Agent ----------

  @Test
  fun agentIdentityIsStableAndValueBased() {
    val a = Agent("hero")
    val b = Agent("hero")
    val c = Agent("goblin")
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, c)
  }

  // ---------- CombatState: registro ----------

  @Test
  fun combatStateRegistersAgents() {
    val state = CombatState()
    val a = Agent("a")
    val b = Agent("b")
    state.register(a)
    state.register(b)
    assertTrue(state.contains(a))
    assertTrue(state.contains(b))
    assertEquals(setOf(a, b), state.agents())
  }

  // ---------- Atributos ----------

  @Test
  fun attributeWithoutModifiersHasEffectiveEqualToBase() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "strength", 10)
    val attr = state.attribute(a, "strength")!!
    assertEquals(10, attr.baseValue)
    assertEquals(10, attr.effectiveValue)
    assertNull(state.attribute(a, "missing"))
  }

  @Test
  fun removeAttributeDropsIt() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "hp", 30)
    state.removeAttribute(a, "hp")
    assertNull(state.attribute(a, "hp"))
    assertTrue(state.attributes(a).isEmpty())
  }

  // ---------- ModifierComposer ----------

  @Test
  fun additiveThenPercentageComposerAppliesAdditivesThenPercent() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "str", 10)
    state.addModifier(a, "str", Modifier(AdditiveThenPercentageComposer.ADDITIVE, 5))
    state.addModifier(a, "str", Modifier(AdditiveThenPercentageComposer.PERCENTAGE, 50))
    // (10 + 5) * 1.5 = 22.5 -> floor = 22
    assertEquals(22, state.attribute(a, "str")!!.effectiveValue)
  }

  @Test
  fun customModifierComposerIsUsed() {
    val composer = ModifierComposer { base, mods -> base + mods.sumOf { it.value } * 2 }
    val state = CombatState(composer)
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "x", 10)
    state.addModifier(a, "x", Modifier("any", 3))
    assertEquals(16, state.attribute(a, "x")!!.effectiveValue)
  }

  @Test
  fun removeModifierUpdatesEffectiveValue() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "str", 10)
    val m = Modifier(AdditiveThenPercentageComposer.ADDITIVE, 4)
    state.addModifier(a, "str", m)
    assertEquals(14, state.attribute(a, "str")!!.effectiveValue)
    state.removeModifier(a, "str", m)
    assertEquals(10, state.attribute(a, "str")!!.effectiveValue)
  }

  // ---------- Item ----------

  @Test
  fun itemModifiersAreIncludedInComposition() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "str", 10)
    val sword = Item(
      "sword",
      mapOf("str" to listOf(Modifier(AdditiveThenPercentageComposer.ADDITIVE, 5)))
    )
    state.addItem(a, sword)
    assertEquals(15, state.attribute(a, "str")!!.effectiveValue)
    assertEquals(setOf(sword), state.items(a))
    state.removeItem(a, "sword")
    assertEquals(10, state.attribute(a, "str")!!.effectiveValue)
  }

  // ---------- Effect ----------

  @Test
  fun effectModifiersAreIncludedInComposition() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "str", 10)
    val buff = Effect(
      "buff",
      mapOf("str" to listOf(Modifier(AdditiveThenPercentageComposer.ADDITIVE, 3)))
    )
    state.applyEffect(a, buff)
    assertEquals(13, state.attribute(a, "str")!!.effectiveValue)
    state.removeEffect(a, "buff")
    assertEquals(10, state.attribute(a, "str")!!.effectiveValue)
  }

  @Test
  fun temporaryEffectExpiresAfterTicks() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "str", 10)
    var removed = false
    val buff = Effect(
      "temp",
      mapOf("str" to listOf(Modifier(AdditiveThenPercentageComposer.ADDITIVE, 5))),
      durationTicks = 2,
      onRemove = { _, _ -> removed = true }
    )
    state.applyEffect(a, buff)
    assertEquals(2, state.remainingTicks(a, "temp"))

    state.tick(a)
    assertEquals(1, state.remainingTicks(a, "temp"))
    assertFalse(removed)
    assertEquals(15, state.attribute(a, "str")!!.effectiveValue)

    state.tick(a)
    assertNull(state.remainingTicks(a, "temp"))
    assertTrue(removed)
    assertEquals(10, state.attribute(a, "str")!!.effectiveValue)
  }

  @Test
  fun permanentEffectNeverExpires() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "str", 10)
    val buff = Effect(
      "perm",
      mapOf("str" to listOf(Modifier(AdditiveThenPercentageComposer.ADDITIVE, 5)))
    )
    state.applyEffect(a, buff)
    state.tick(a)
    state.tick(a)
    state.tick(a)
    assertNull(state.remainingTicks(a, "perm"))
    assertEquals(15, state.attribute(a, "str")!!.effectiveValue)
  }

  @Test
  fun applyingSameEffectIdReplacesPrevious() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    var firstRemoved = false
    val first = Effect("x", onRemove = { _, _ -> firstRemoved = true })
    val second = Effect("x")
    state.applyEffect(a, first)
    state.applyEffect(a, second)
    assertTrue(firstRemoved)
    assertEquals(second, state.effect(a, "x"))
  }

  @Test
  fun onApplyCallbackRuns() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    var applied = false
    state.applyEffect(a, Effect("x", onApply = { _, _ -> applied = true }))
    assertTrue(applied)
  }

  // ---------- RandomSource ----------

  @Test
  fun defaultRandomSourceRespectsInclusiveBounds() {
    repeat(100) {
      val v = DefaultRandomSource.nextInt(3, 7)
      assertTrue(v in 3..7, "value $v out of [3,7]")
    }
    assertEquals(5, DefaultRandomSource.nextInt(5, 5))
  }

  @Test
  fun deterministicRandomSourceYieldsProvidedValues() {
    val source = DeterministicRandomSource(4, 6)
    assertEquals(4, source.nextInt(3, 7))
    assertEquals(6, source.nextInt(3, 7))
  }

  @Test
  fun deterministicRandomSourceRejectsValuesOutOfRange() {
    val source = DeterministicRandomSource(99)
    assertFailsWith<IllegalArgumentException> { source.nextInt(1, 10) }
  }

  // ---------- DamageRange ----------

  @Test
  fun damageRangeValidatesBounds() {
    DamageRange(3, 5)
    DamageRange(4, 4)
    assertFailsWith<IllegalArgumentException> { DamageRange(5, 3) }
    assertFailsWith<IllegalArgumentException> { DamageRange(-1, 3) }
  }

  @Test
  fun fixedDamageIsRepresentedByMinEqualsMax() {
    val state = CombatState()
    val a = Agent("a");
    val b = Agent("b")
    state.register(a); state.register(b)
    val range = FixedDamageFormula(7).calculate(DamageContext(a, b, state))
    assertEquals(7, range.min)
    assertEquals(7, range.max)
  }

  // ---------- DamageFormula ----------

  @Test
  fun fixedDamageFormulaRejectsNegative() {
    assertFailsWith<IllegalArgumentException> { FixedDamageFormula(-1) }
  }

  @Test
  fun customDamageFormulaIsUsed() {
    val state = CombatState()
    val a = Agent("a");
    val b = Agent("b")
    state.register(a); state.register(b)
    val formula = DamageFormula { DamageRange(2, 10) }
    assertEquals(DamageRange(2, 10), formula.calculate(DamageContext(a, b, state)))
  }

  // ---------- DamageRoll ----------

  @Test
  fun deterministicRollReturnsMin() {
    assertEquals(3, DeterministicDamageRoll.roll(DamageRange(3, 9)))
    assertEquals(4, DeterministicDamageRoll.roll(DamageRange(4, 4)))
  }

  @Test
  fun uniformRollUsesRandomSourceInclusiveOnBothEnds() {
    val source = DeterministicRandomSource(3, 9, 6)
    val roll = UniformDamageRoll(source)
    assertEquals(3, roll.roll(DamageRange(3, 9)))
    assertEquals(9, roll.roll(DamageRange(3, 9)))
    assertEquals(6, roll.roll(DamageRange(3, 9)))
  }

  // ---------- Mitigation ----------

  private fun mitCtx() = MitigationContext(Agent("a"), Agent("b"), "physical", CombatState())

  @Test
  fun noMitigationReturnsRolledDamage() {
    assertEquals(10, NoMitigation.mitigate(10, mitCtx()))
  }

  @Test
  fun fixedReductionMitigationSubtracts() {
    assertEquals(4, FixedReductionMitigation(6).mitigate(10, mitCtx()))
    assertEquals(0, FixedReductionMitigation(20).mitigate(10, mitCtx()))
  }

  @Test
  fun percentageReductionMitigationFloors() {
    assertEquals(5, PercentageReductionMitigation(50).mitigate(10, mitCtx()))
    // floor(10 * 67 / 100) = floor(6.7) = 6
    assertEquals(6, PercentageReductionMitigation(33).mitigate(10, mitCtx()))
    assertEquals(0, PercentageReductionMitigation(150).mitigate(10, mitCtx()))
  }

  @Test
  fun mitigationChainAppliesInOrderGiven() {
    val chain = MitigationChain(
      FixedReductionMitigation(3),
      PercentageReductionMitigation(50)
    )
    // (10 - 3) = 7; floor(7 * 50 / 100) = 3
    assertEquals(3, chain.mitigate(10, mitCtx()))
  }

  @Test
  fun mitigationNeverProducesNegative() {
    assertEquals(0, FixedReductionMitigation(100).mitigate(5, mitCtx()))
    assertEquals(0, PercentageReductionMitigation(100).mitigate(5, mitCtx()))
    assertEquals(
      0, MitigationChain(
        PercentageReductionMitigation(80),
        FixedReductionMitigation(100)
      ).mitigate(50, mitCtx())
    )
  }

  // ---------- DamageResult ----------

  @Test
  fun damageResultRejectsNegativeValues() {
    DamageResult(Agent("a"), Agent("b"), "physical", 5, 3, 3)
    assertFailsWith<IllegalArgumentException> {
      DamageResult(Agent("a"), Agent("b"), "physical", -1, 0, 0)
    }
    assertFailsWith<IllegalArgumentException> {
      DamageResult(Agent("a"), Agent("b"), "physical", 1, -1, 0)
    }
    assertFailsWith<IllegalArgumentException> {
      DamageResult(Agent("a"), Agent("b"), "physical", 1, 1, -1)
    }
  }

  // ---------- Helpers para pipeline ----------

  private class HpApplication(private val hpId: String = "hp") : DamageApplication {
    override fun apply(mitigatedDamage: Int, context: DamageApplicationContext): Int {
      val attr = context.state.attribute(context.target, hpId) ?: return 0
      val newHp = (attr.baseValue - mitigatedDamage).coerceAtLeast(0)
      val applied = attr.baseValue - newHp
      context.state.setAttribute(context.target, hpId, newHp)
      if (newHp == 0) context.state.markDefeated(context.target)
      return applied
    }
  }

  private fun resolverWith(
    hit: HitResolution = AlwaysHit,
    formula: DamageFormula = FixedDamageFormula(10),
    roll: DamageRoll = DeterministicDamageRoll,
    mitigation: Mitigation = NoMitigation,
    application: DamageApplication = HpApplication()
  ) = CombatResolver(hit, formula, roll, mitigation, application)

  // ---------- Pipeline completo ----------

  @Test
  fun fullPipelineHitsAndAppliesDamage() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 20)

    val result = resolverWith().resolve(Attack(hero, goblin, "physical"), state)

    assertTrue(result.hit)
    val dmg = result.damageResult!!
    assertEquals(10, dmg.rolledDamage)
    assertEquals(10, dmg.mitigatedDamage)
    assertEquals(10, dmg.appliedDamage)
    assertEquals(10, state.attribute(goblin, "hp")!!.baseValue)
    assertFalse(state.isDefeated(goblin))
  }

  @Test
  fun missProducesNoDamageResultNorStateChange() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 20)

    val miss = HitResolution { _, _ -> false }
    val result = resolverWith(hit = miss).resolve(Attack(hero, goblin, "physical"), state)

    assertFalse(result.hit)
    assertNull(result.damageResult)
    assertEquals(20, state.attribute(goblin, "hp")!!.baseValue)
    assertTrue(result.events.any { it is CombatEvent.AttackMissed })
  }

  @Test
  fun appliedDamageIsCappedByRemainingHp() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 3)

    val result = resolverWith().resolve(Attack(hero, goblin, "physical"), state)
    val dmg = result.damageResult!!

    assertEquals(10, dmg.rolledDamage)
    assertEquals(10, dmg.mitigatedDamage)
    assertEquals(3, dmg.appliedDamage)
    assertEquals(0, state.attribute(goblin, "hp")!!.baseValue)
    assertTrue(state.isDefeated(goblin))
  }

  @Test
  fun defeatEventIsEmittedWhenHpReachesZero() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 5)

    val result = resolverWith().resolve(Attack(hero, goblin, "physical"), state)

    assertTrue(state.isDefeated(goblin))
    assertTrue(result.events.any { it is CombatEvent.Defeat && it.agent == goblin })
  }

  @Test
  fun pipelineWithCustomFormulaRollAndMitigationChain() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 50)

    val resolver = CombatResolver(
      hitResolution = AlwaysHit,
      damageFormula = DamageFormula { DamageRange(10, 20) },
      damageRoll = UniformDamageRoll(DeterministicRandomSource(16)),
      mitigation = MitigationChain(
        FixedReductionMitigation(2),
        PercentageReductionMitigation(50)
      ),
      damageApplication = HpApplication()
    )

    val result = resolver.resolve(Attack(hero, goblin, "fire"), state)
    val dmg = result.damageResult!!

    assertEquals(16, dmg.rolledDamage)
    // (16 - 2) = 14; floor(14 * 50 / 100) = 7
    assertEquals(7, dmg.mitigatedDamage)
    assertEquals(7, dmg.appliedDamage)
    assertEquals(43, state.attribute(goblin, "hp")!!.baseValue)
    assertEquals("fire", dmg.damageType)
  }

  @Test
  fun combatResultReferencesActionStateAndEvents() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 10)

    val action = Attack(hero, goblin, "physical")
    val result = resolverWith().resolve(action, state)

    assertSame(action, result.action)
    assertSame(state, result.state)
    assertTrue(result.events.any { it is CombatEvent.AttackPerformed })
    assertTrue(result.events.any { it is CombatEvent.DamageProduced })
    assertTrue(result.events.any { it is CombatEvent.DamageApplied })
  }

  @Test
  fun stateEventsProducedDuringResolutionAreSurfaced() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 5)

    val result = resolverWith().resolve(Attack(hero, goblin, "physical"), state)

    val kinds = result.events.map { it::class }
    val idxProduced = kinds.indexOf(CombatEvent.DamageProduced::class)
    val idxApplied = kinds.indexOf(CombatEvent.DamageApplied::class)
    val idxDefeat = kinds.indexOf(CombatEvent.Defeat::class)
    assertTrue(idxProduced >= 0 && idxApplied > idxProduced)
    assertTrue(idxDefeat in (idxProduced + 1) until idxApplied)
  }

  @Test
  fun nullApplicationReturnsZeroAppliedDamageAndKeepsHp() {
    val state = CombatState()
    val hero = Agent("hero");
    val goblin = Agent("goblin")
    state.register(hero); state.register(goblin)
    state.setAttribute(goblin, "hp", 20)

    val noOp = DamageApplication { _, _ -> 0 }
    val result = resolverWith(application = noOp).resolve(Attack(hero, goblin, "physical"), state)

    assertEquals(0, result.damageResult!!.appliedDamage)
    assertEquals(20, state.attribute(goblin, "hp")!!.baseValue)
    assertFalse(state.isDefeated(goblin))
  }

  @Test
  fun modifiersCombineDirectItemsAndEffects() {
    val state = CombatState()
    val a = Agent("a")
    state.register(a)
    state.setAttribute(a, "str", 10)
    state.addModifier(a, "str", Modifier("dir", 1))
    state.addItem(a, Item("sword", mapOf("str" to listOf(Modifier("item", 2)))))
    state.applyEffect(a, Effect("buff", mapOf("str" to listOf(Modifier("eff", 3)))))

    val mods = state.modifiers(a, "str")
    assertEquals(setOf("dir", "item", "eff"), mods.map { it.id }.toSet())
    assertEquals(3, mods.size)
  }
}