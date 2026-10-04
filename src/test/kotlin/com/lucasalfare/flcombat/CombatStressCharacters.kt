@file:Suppress("unused")

package com.lucasalfare.flcombat

import kotlin.math.floor

/**
 * Mario modeled as a combat/gameplay domain object.
 *
 * This deliberately models the classic Super Mario Bros. combat-facing rules:
 * Small -> Super -> Fire forms, temporary Star invincibility, fireballs and lives.
 * Platform physics, jumping, collision geometry, level scripts and flagpoles are
 * intentionally outside FLCombat and therefore outside this class.
 */
class Mario(
  val state: CombatState = CombatState(),
  val agent: Agent = Agent("mario"),
  initialLives: Int = 3
) {
  enum class Form { SMALL, SUPER, FIRE }

  private companion object {
    const val HEALTH = "health"
    const val MAX_HEALTH = "maxHealth"
    const val ATTACK_DAMAGE = "attackDamage"
    const val MOVEMENT_SPEED = "movementSpeed"
    const val JUMP_POWER = "jumpPower"
    const val LIVES = "lives"
    const val STAR = "star"

    const val SMALL_DAMAGE = 1
    const val SUPER_DAMAGE = 1
    const val FIREBALL_DAMAGE = 1
  }

  var form: Form = Form.SMALL
    private set

  init {
    require(initialLives > 0) { "initialLives must be positive" }

    state.register(agent)
    state.setAttribute(agent, MAX_HEALTH, 1)
    state.setAttribute(agent, HEALTH, 1)
    state.setAttribute(agent, ATTACK_DAMAGE, 1)
    state.setAttribute(agent, MOVEMENT_SPEED, 100)
    state.setAttribute(agent, JUMP_POWER, 100)
    state.setAttribute(agent, LIVES, initialLives)
  }

  /** Collects a Super Mushroom and upgrades Small Mario to Super Mario. */
  fun collectMushroom() {
    if (form == Form.SMALL) {
      form = Form.SUPER
      state.setAttribute(agent, HEALTH, 2)
      state.setAttribute(agent, MAX_HEALTH, 2)
    }
  }

  /**
   * Collects a Fire Flower.
   *
   * In classic Super Mario Bros., taking the flower while small also promotes
   * Mario through the Super state before obtaining the fire form.
   */
  fun collectFireFlower() {
    if (form == Form.SMALL) {
      collectMushroom()
    }
    form = Form.FIRE
  }

  /** Enables temporary invincibility. */
  fun collectStar(durationTicks: Int = 20) {
    require(durationTicks > 0) { "durationTicks must be positive" }

    state.applyEffect(
      agent,
      Effect(
        id = STAR,
        durationTicks = durationTicks
      )
    )
  }

  fun isInvincible(): Boolean = state.effect(agent, STAR) != null

  /**
   * Stomps an enemy.
   *
   * The actual spatial condition "Mario is descending onto the enemy" belongs to
   * the platformer layer. This method assumes that condition has already been met.
   */
  fun stomp(target: Agent): CombatResult =
    combatResolver(SMALL_DAMAGE).resolve(
      Stomp(agent, target),
      state
    )

  /** Throws a fireball when Mario is in Fire form. */
  fun fireball(target: Agent): CombatResult {
    require(form == Form.FIRE) {
      "Fireball requires Fire Mario"
    }

    return combatResolver(FIREBALL_DAMAGE).resolve(
      Fireball(agent, target),
      state
    )
  }

  /**
   * Applies one point of incoming damage.
   *
   * Small Mario loses a life. Super/Fire Mario first reverts to Small Mario.
   * A real level/game object would then respawn Mario and reposition him.
   */
  fun takeHit() {
    if (isInvincible()) return

    when (form) {
      Form.SMALL -> loseLife()
      Form.SUPER, Form.FIRE -> {
        form = Form.SMALL
        state.setAttribute(agent, MAX_HEALTH, 1)
        state.setAttribute(agent, HEALTH, 1)
      }
    }
  }

  fun lives(): Int = state.attribute(agent, LIVES)!!.baseValue

  fun isGameOver(): Boolean = lives() <= 0

  fun tick() {
    state.tick(agent)
  }

  private fun loseLife() {
    val lives = lives() - 1
    state.setAttribute(agent, LIVES, lives)

    if (lives <= 0) {
      state.markDefeated(agent)
    }
  }

  private fun combatResolver(damage: Int) =
    CombatResolver(
      hitResolution = AlwaysHit,
      damageFormula = FixedDamageFormula(damage),
      damageRoll = DeterministicDamageRoll,
      mitigation = { rolledDamage, _ -> rolledDamage },
      damageApplication = DamageApplication { mitigatedDamage, context ->
        if (state.isDefeated(context.target)) return@DamageApplication 0
        takeHitTarget(context.target, mitigatedDamage)
      }
    )

  private fun takeHitTarget(target: Agent, damage: Int): Int {
    if (target == agent) return 0
    // Mario's target mechanics are domain-defined: a stomp defeats the target.
    if (damage > 0) state.markDefeated(target)
    return damage
  }

  private data class Stomp(
    override val attacker: Agent,
    override val target: Agent
  ) : CombatAction {
    override val damageType = "stomp"
  }

  private data class Fireball(
    override val attacker: Agent,
    override val target: Agent
  ) : CombatAction {
    override val damageType = "fire"
  }
}

/**
 * Garen domain model built on FLCombat.
 *
 * Values are intentionally expressed as the champion's gameplay values rather than
 * attempting to turn the entire League ruleset into the combat core.
 */
class Garen(
  val state: CombatState = CombatState(),
  val agent: Agent = Agent("garen"),
  val level: Int = 1,
  private val qRank: Int = 5,
  private val wRank: Int = 5,
  private val eRank: Int = 5,
  private val rRank: Int = 3
) {
  enum class Ability { Q, W, E, R }

  private companion object {
    const val HEALTH = "health"
    const val MAX_HEALTH = "maxHealth"
    const val ATTACK_DAMAGE = "attackDamage"
    const val BONUS_HEALTH = "bonusHealth"
    const val ARMOR = "armor"
    const val MAGIC_RESISTANCE = "magicResistance"
    const val MOVEMENT_SPEED = "movementSpeed"
    const val ATTACK_SPEED = "attackSpeed"
    const val TENACITY = "tenacity"
    const val DAMAGE_REDUCTION = "damageReduction"
    const val JUDGMENT_TICKS = 6
    const val COURAGE_TICKS = 8
    const val Q_SPEED_TICKS = 8
  }

  private val cooldowns = Ability.entries.associateWithTo(mutableMapOf()) { 0.0 }
  private var silenceTicks = 0
  private var bonusDefenseStacks = 0
  private var ticksSinceDamaged = 0
  private var judgmentRemaining = 0
  private var courageRemaining = 0

  private val physicalMitigation = Mitigation { damage, context ->
    val armor = context.state
      .attribute(context.target, ARMOR)
      ?.effectiveValue
      ?: 0
    val multiplier = 100.0 / (100.0 + armor.coerceAtLeast(0))
    val afterArmor = floor(damage * multiplier).toInt()
    val reduction = context.state
      .attribute(context.target, DAMAGE_REDUCTION)
      ?.effectiveValue
      ?: 0
    floor(afterArmor * (100 - reduction).coerceAtLeast(0) / 100.0).toInt()
  }

  private val trueDamageMitigation = Mitigation { damage, _ ->
    damage.coerceAtLeast(0)
  }

  private val damageApplication = DamageApplication { damage, context ->
    val health = context.state.attribute(context.target, HEALTH)
      ?: error("Target '${context.target.id}' has no health")
    val applied = damage.coerceAtMost(health.baseValue)
    context.state.setAttribute(context.target, HEALTH, health.baseValue - applied)
    if (health.baseValue - applied == 0) context.state.markDefeated(context.target)
    applied
  }

  private val basicAttackResolver = CombatResolver(
    hitResolution = AlwaysHit,
    damageFormula = { context ->
      val ad = context.state.attribute(context.attacker, ATTACK_DAMAGE)!!.effectiveValue
      DamageRange(ad, ad)
    },
    damageRoll = DeterministicDamageRoll,
    mitigation = physicalMitigation,
    damageApplication = damageApplication
  )

  init {
    require(level in 1..18) { "level must be between 1 and 18" }
    require(qRank in 1..5) { "qRank must be between 1 and 5" }
    require(wRank in 1..5) { "wRank must be between 1 and 5" }
    require(eRank in 1..5) { "eRank must be between 1 and 5" }
    require(rRank in 1..3) { "rRank must be between 1 and 3" }

    val hp = 690 + 98 * (level - 1)
    val ad = 69 + floor(4.2 * (level - 1)).toInt()
    val armor = 38 + floor(4.2 * (level - 1)).toInt()
    val mr = 32 + floor(2.05 * (level - 1)).toInt()

    state.register(agent)
    state.setAttribute(agent, MAX_HEALTH, hp)
    state.setAttribute(agent, HEALTH, hp)
    state.setAttribute(agent, ATTACK_DAMAGE, ad)
    state.setAttribute(agent, BONUS_HEALTH, 0)
    state.setAttribute(agent, ARMOR, armor)
    state.setAttribute(agent, MAGIC_RESISTANCE, mr)
    state.setAttribute(agent, MOVEMENT_SPEED, 340)
    state.setAttribute(agent, ATTACK_SPEED, 679)
    state.setAttribute(agent, TENACITY, 0)
    state.setAttribute(agent, DAMAGE_REDUCTION, 0)
  }

  fun attack(target: Agent): CombatResult {
    requireTarget(target)
    val result = basicAttackResolver.resolve(Attack(agent, target, "physical"), state)
    cooldowns[Ability.Q] = (cooldowns.getValue(Ability.Q) - 1.0).coerceAtLeast(0.0)
    return result
  }

  fun decisiveStrike(target: Agent): CombatResult {
    requireReady(Ability.Q)
    requireTarget(target)

    state.removeEffect(agent, "slow")
    silenceTicks = Q_SPEED_TICKS
    state.setAttribute(agent, MOVEMENT_SPEED, 340 * 135 / 100)
    cooldowns[Ability.Q] = 8.0

    val ad = state.attribute(agent, ATTACK_DAMAGE)!!.effectiveValue
    val damage = intArrayOf(0, 30, 60, 90, 120, 150)[qRank] + ad / 2

    return resolver(FixedDamageFormula(damage), physicalMitigation)
      .resolve(QStrike(agent, target), state)
  }

  fun courage() {
    requireReady(Ability.W)

    cooldowns[Ability.W] = doubleArrayOf(0.0, 22.0, 19.5, 17.0, 14.5, 12.0)[wRank]
    courageRemaining = COURAGE_TICKS

    state.applyEffect(
      agent,
      Effect(
        id = "courage",
        modifiers = mapOf(
          DAMAGE_REDUCTION to listOf(
            Modifier("additive", intArrayOf(0, 250, 290, 330, 370, 410)[wRank])
          ),
          TENACITY to listOf(
            Modifier("additive", 60)
          )
        ),
        durationTicks = COURAGE_TICKS
      )
    )
  }

  fun judgment(target: Agent): List<CombatResult> {
    requireReady(Ability.E)
    requireTarget(target)

    cooldowns[Ability.E] = 6.0
    judgmentRemaining = JUDGMENT_TICKS

    val results = mutableListOf<CombatResult>()
    repeat(JUDGMENT_TICKS) {
      if (state.isDefeated(target)) return@repeat
      val ad = state.attribute(agent, ATTACK_DAMAGE)!!.effectiveValue
      val damage = intArrayOf(0, 4, 7, 10, 13, 16)[eRank] +
          floor(ad * doubleArrayOf(0.0, 0.40, 0.43, 0.46, 0.49, 0.52)[eRank]).toInt()
      results += resolver(FixedDamageFormula(damage), physicalMitigation)
        .resolve(JudgmentHit(agent, target), state)
    }
    judgmentRemaining = 0
    return results
  }

  fun demacianJustice(target: Agent): CombatResult {
    requireReady(Ability.R)
    requireTarget(target)

    cooldowns[Ability.R] = doubleArrayOf(120.0, 100.0, 80.0, 80.0)[rRank]

    val targetMax = state.attribute(target, MAX_HEALTH)?.baseValue
      ?: error("Target '${target.id}' has no max health")
    val targetHp = state.attribute(target, HEALTH)?.baseValue
      ?: error("Target '${target.id}' has no health")
    val missingHealth = targetMax - targetHp
    val damage = intArrayOf(0, 125, 200, 275)[rRank] +
        floor(missingHealth * doubleArrayOf(0.0, 0.25, 0.30, 0.35)[rRank]).toInt()

    return resolver(FixedDamageFormula(damage), trueDamageMitigation)
      .resolve(DemacianJustice(agent, target), state)
  }

  /** Called after Garen kills a unit, applying Courage's permanent defense growth. */
  fun unitKilled() {
    bonusDefenseStacks = (bonusDefenseStacks + 1).coerceAtMost(150)
  }

  /**
   * Records incoming damage for Perseverance and applies it directly to state.
   * An external combat resolver can still target Garen; this method models the
   * champion-specific "was damaged" signal needed by the passive.
   */
  fun receiveDamage(amount: Int) {
    require(amount >= 0) { "amount must not be negative" }
    val hp = state.attribute(agent, HEALTH)!!.baseValue
    state.setAttribute(agent, HEALTH, (hp - amount).coerceAtLeast(0))
    ticksSinceDamaged = 0
    if (hp - amount <= 0) state.markDefeated(agent)
  }

  fun tick() {
    for (ability in Ability.entries) {
      cooldowns[ability] = (cooldowns.getValue(ability) - 0.5).coerceAtLeast(0.0)
    }

    if (silenceTicks > 0) {
      silenceTicks--
      if (silenceTicks == 0) state.setAttribute(agent, MOVEMENT_SPEED, 340)
    }

    if (courageRemaining > 0) courageRemaining--
    if (judgmentRemaining > 0) judgmentRemaining--

    ticksSinceDamaged++
    if (ticksSinceDamaged >= 16 && ticksSinceDamaged % 10 == 0) {
      val maxHp = state.attribute(agent, MAX_HEALTH)!!.baseValue
      val healPercent = 1.5 + (level - 1) * (10.1 - 1.5) / 17.0
      val heal = floor(maxHp * healPercent / 100.0).toInt()
      state.setAttribute(agent, HEALTH, (state.attribute(agent, HEALTH)!!.baseValue + heal).coerceAtMost(maxHp))
    }

    state.tick(agent)
  }

  fun effectiveArmor(): Int =
    state.attribute(agent, ARMOR)!!.effectiveValue

  fun effectiveMagicResistance(): Int =
    state.attribute(agent, MAGIC_RESISTANCE)!!.effectiveValue

  fun courageDefenseStacks(): Int = bonusDefenseStacks

  private fun requireReady(ability: Ability) {
    require(cooldowns.getValue(ability) <= 0.0) {
      "$ability is on cooldown"
    }
  }

  private fun requireTarget(target: Agent) {
    require(state.contains(target)) { "Target '${target.id}' is not registered" }
    require(!state.isDefeated(target)) { "Target '${target.id}' is defeated" }
    require(state.attribute(target, HEALTH) != null) { "Target '${target.id}' has no health" }
  }

  private fun resolver(formula: DamageFormula, mitigation: Mitigation) =
    CombatResolver(
      hitResolution = AlwaysHit,
      damageFormula = formula,
      damageRoll = DeterministicDamageRoll,
      mitigation = mitigation,
      damageApplication = damageApplication
    )

  private data class QStrike(
    override val attacker: Agent,
    override val target: Agent
  ) : CombatAction {
    override val damageType = "physical"
  }

  private data class JudgmentHit(
    override val attacker: Agent,
    override val target: Agent
  ) : CombatAction {
    override val damageType = "physical"
  }

  private data class DemacianJustice(
    override val attacker: Agent,
    override val target: Agent
  ) : CombatAction {
    override val damageType = "true"
  }
}

/**
 * General Graardor domain model for Old School RuneScape-style PvM.
 *
 * This class is intentionally a stress test for several FLCombat ideas at once:
 * a boss, multiple attack styles, AoE ranged damage, deterministic/random rolls,
 * and three independent bodyguards living in the same CombatState.
 */
class GeneralGraardor(
  val state: CombatState = CombatState(),
  val agent: Agent = Agent("general-graardor"),
  private val randomSource: RandomSource
) {
  enum class AttackStyle { MELEE, RANGED }

  data class Bodyguard(
    val agent: Agent,
    val style: Style,
    val maxHit: Int,
    val maxHealth: Int
  ) {
    enum class Style { MELEE, MAGIC, RANGED }
  }

  private companion object {
    const val HEALTH = "health"
    const val MAX_HEALTH = "maxHealth"
    const val MELEE_DEFENCE = "meleeDefence"
    const val RANGED_DEFENCE = "rangedDefence"
    const val MAGIC_DEFENCE = "magicDefence"
    const val AGGRESSION = "aggression"
    const val ATTACK_SPEED_TICKS = 6
    const val ROOM_RADIUS = 10
  }

  val bodyguards: List<Bodyguard>

  private var attackTicks = 0
  private var lastAttacker: Agent? = null

  init {
    state.register(agent)
    state.setAttribute(agent, MAX_HEALTH, 255)
    state.setAttribute(agent, HEALTH, 255)
    state.setAttribute(agent, MELEE_DEFENCE, 350)
    state.setAttribute(agent, RANGED_DEFENCE, 350)
    state.setAttribute(agent, MAGIC_DEFENCE, 298)
    state.setAttribute(agent, AGGRESSION, 100)

    bodyguards = listOf(
      createBodyguard("strongstack", Bodyguard.Style.MELEE, 15, 146),
      createBodyguard("steelwill", Bodyguard.Style.MAGIC, 16, 127),
      createBodyguard("grimspike", Bodyguard.Style.RANGED, 21, 146)
    )
  }

  /**
   * Chooses Graardor's attack style using the documented 2/3 melee, 1/3 ranged
   * distribution.
   */
  fun chooseAttackStyle(): AttackStyle =
    if (randomSource.nextInt(1, 3) <= 2) AttackStyle.MELEE else AttackStyle.RANGED

  /** Performs Graardor's melee punch, up to 60 damage. */
  fun meleeAttack(target: Agent): CombatResult {
    requireTarget(target)
    lastAttacker = target
    return resolveBossAttack(
      target = target,
      range = DamageRange(0, 60),
      damageType = "melee"
    )
  }

  /**
   * Performs the room-wide ranged shockwave against all supplied players.
   * Each successful hit rolls from 15 to 35.
   */
  fun rangedShockwave(targets: List<Agent>): List<CombatResult> {
    require(targets.isNotEmpty()) { "rangedShockwave requires targets" }

    return targets.filter { state.contains(it) && !state.isDefeated(it) }.map { target ->
      lastAttacker = target
      resolveBossAttack(
        target = target,
        range = DamageRange(15, 35),
        damageType = "ranged"
      )
    }
  }

  /** Performs whichever attack style the boss rolled for this attack cycle. */
  fun attack(target: Agent, chamberTargets: List<Agent> = listOf(target)): Any {
    return when (chooseAttackStyle()) {
      AttackStyle.MELEE -> meleeAttack(target)
      AttackStyle.RANGED -> rangedShockwave(chamberTargets)
    }
  }

  /**
   * Resolves an attack from one of Graardor's three bodyguards.
   *
   * In the actual encounter, the bodyguards use three different combat styles:
   * melee, magic and ranged.
   */
  fun bodyguardAttack(
    bodyguard: Bodyguard,
    target: Agent
  ): CombatResult {
    requireTarget(target)
    require(!state.isDefeated(bodyguard.agent)) {
      "Bodyguard '${bodyguard.agent.id}' is defeated"
    }

    lastAttacker = target

    val damageType = when (bodyguard.style) {
      Bodyguard.Style.MELEE -> "melee"
      Bodyguard.Style.MAGIC -> "magic"
      Bodyguard.Style.RANGED -> "ranged"
    }

    return CombatResolver(
      hitResolution = AlwaysHit,
      damageFormula = { DamageRange(0, bodyguard.maxHit) },
      damageRoll = UniformDamageRoll(randomSource),
      mitigation = defenceMitigation(damageType),
      damageApplication = genericApplication()
    ).resolve(
      BossAttack(bodyguard.agent, target, damageType),
      state
    )
  }

  /**
   * Advances the boss attack timer by one game tick.
   * Graardor attacks every 6 ticks (3.6 seconds).
   */
  fun tick(target: Agent? = lastAttacker): Any? {
    attackTicks++
    if (attackTicks < ATTACK_SPEED_TICKS || target == null) return null
    attackTicks = 0
    return attack(target)
  }

  fun targetOfBodyguards(): Agent? = lastAttacker

  fun isDead(): Boolean = state.isDefeated(agent)

  private fun resolveBossAttack(
    target: Agent,
    range: DamageRange,
    damageType: String
  ): CombatResult {
    return CombatResolver(
      hitResolution = AlwaysHit,
      damageFormula = { range },
      damageRoll = UniformDamageRoll(randomSource),
      mitigation = defenceMitigation(damageType),
      damageApplication = genericApplication()
    ).resolve(
      BossAttack(agent, target, damageType),
      state
    )
  }

  private fun defenceMitigation(type: String): Mitigation = Mitigation { damage, context ->
    val defence = when (type) {
      "melee" -> context.state.attribute(context.target, MELEE_DEFENCE)?.effectiveValue ?: 0
      "ranged" -> context.state.attribute(context.target, RANGED_DEFENCE)?.effectiveValue ?: 0
      "magic" -> context.state.attribute(context.target, MAGIC_DEFENCE)?.effectiveValue ?: 0
      else -> 0
    }

    // This is intentionally an illustrative defence transform rather than a
    // claim of reproducing the complete OSRS accuracy/damage formula.
    floor(damage * 100.0 / (100.0 + defence)).toInt()
  }

  private fun genericApplication() = DamageApplication { damage, context ->
    val health = context.state.attribute(context.target, HEALTH)
      ?: error("Target '${context.target.id}' has no health")
    val applied = damage.coerceAtMost(health.baseValue)
    val remaining = health.baseValue - applied
    context.state.setAttribute(context.target, HEALTH, remaining)
    if (remaining == 0) context.state.markDefeated(context.target)
    applied
  }

  private fun createBodyguard(
    id: String,
    style: Bodyguard.Style,
    maxHit: Int,
    maxHealth: Int
  ): Bodyguard {
    val bodyguard = Agent(id)
    state.register(bodyguard)
    state.setAttribute(bodyguard, MAX_HEALTH, maxHealth)
    state.setAttribute(bodyguard, HEALTH, maxHealth)
    state.setAttribute(bodyguard, MELEE_DEFENCE, 0)
    state.setAttribute(bodyguard, RANGED_DEFENCE, 0)
    state.setAttribute(bodyguard, MAGIC_DEFENCE, 0)
    state.setAttribute(bodyguard, AGGRESSION, 100)
    return Bodyguard(bodyguard, style, maxHit, maxHealth)
  }

  private fun requireTarget(target: Agent) {
    require(state.contains(target)) { "Target '${target.id}' is not registered" }
    require(!state.isDefeated(target)) { "Target '${target.id}' is defeated" }
    require(state.attribute(target, HEALTH) != null) { "Target '${target.id}' has no health" }
  }

  private data class BossAttack(
    override val attacker: Agent,
    override val target: Agent,
    override val damageType: String
  ) : CombatAction
}

/**
 * A self-contained Master Yi domain model built on top of FLCombat.
 *
 * This class intentionally lives outside the FLCombat core. It demonstrates how a
 * concrete champion can provide game-specific meaning through attributes, effects,
 * actions, formulas, mitigation and damage application without modifying the core.
 *
 * Time is represented in half-second ticks. Real-time scheduling, animation,
 * movement and target selection remain responsibilities of the surrounding game.
 */
class MasterYi(
  val state: CombatState = CombatState(AdditiveThenPercentageComposer),
  val agent: Agent = Agent("master-yi"),
  private val qRank: Int = 5,
  private val wRank: Int = 5,
  private val eRank: Int = 5,
  private val rRank: Int = 3
) {
  enum class Ability { Q, W, E, R }

  data class BasicAttackResult(
    val strikes: List<CombatResult>,
    val doubleStrike: Boolean
  )

  data class AlphaStrikeResult(
    val hits: List<CombatResult>
  )

  companion object {
    private const val HALF_SECOND_TICKS = 1
    private const val FOUR_SECONDS = 8
    private const val SEVEN_SECONDS = 14

    private const val HEALTH = "health"
    private const val MAX_HEALTH = "maxHealth"
    private const val MANA = "mana"
    private const val MAX_MANA = "maxMana"
    private const val ATTACK_DAMAGE = "attackDamage"
    private const val BONUS_ATTACK_DAMAGE = "bonusAttackDamage"
    private const val ATTACK_SPEED = "attackSpeed"
    private const val ARMOR = "armor"
    private const val MAGIC_RESISTANCE = "magicResistance"
    private const val MOVEMENT_SPEED = "movementSpeed"
    private const val ATTACK_RANGE = "attackRange"

    /**
     * Stored as tenths of a percent.
     *
     * Example:
     * 700 = 70.0%
     * 475 = 47.5%
     */
    private const val DAMAGE_REDUCTION = "damageReduction"

    private const val ABILITY_POWER = "abilityPower"

    private const val DOUBLE_STRIKE_STACKS = 3
    private const val DOUBLE_STRIKE_SECOND_ATTACK_RATIO = 0.50

    private const val WUJU_EFFECT = "wuju-style"
    private const val HIGHLANDER_EFFECT = "highlander"
    private const val MEDITATE_EFFECT = "meditate"

    private val alphaBaseDamage = intArrayOf(
      0,
      20,
      40,
      60,
      80,
      100
    )

    private val alphaManaCost = intArrayOf(
      0,
      50,
      55,
      60,
      65,
      70
    )

    private val alphaCooldownTicks = intArrayOf(
      0,
      40,
      39,
      38,
      37,
      36
    )

    private val meditateMinHeal = intArrayOf(
      0,
      15,
      25,
      35,
      45,
      55
    )

    private val meditateMaxHeal = intArrayOf(
      0,
      30,
      50,
      70,
      90,
      110
    )

    private val meditateDamageReduction = intArrayOf(
      0,
      450,
      475,
      500,
      525,
      550
    )

    private val wujuTrueDamage = intArrayOf(
      0,
      20,
      25,
      30,
      35,
      40
    )

    private val highlanderAttackSpeed = intArrayOf(
      0,
      25,
      45,
      65
    )

    private val highlanderMovementSpeed = intArrayOf(
      0,
      40,
      50,
      60
    )

    private const val MEDITATE_MANA_COST = 40
    private const val MEDITATE_COOLDOWN_TICKS = 20

    private const val WUJU_COOLDOWN_TICKS = 28
    private const val WUJU_DURATION_TICKS = 10

    private const val HIGHLANDER_MANA_COST = 100
    private const val HIGHLANDER_COOLDOWN_TICKS = 170
  }

  private val cooldowns =
    Ability.entries.associateWithTo(mutableMapOf()) { 0 }

  private var doubleStrikeStacks = 0
  private var doubleStrikeTimer = 0

  private var wujuRemaining = 0
  private var highlanderRemaining = 0

  private var meditateRemaining = 0
  private var meditateElapsed = 0

  /**
   * Generic mitigation used by Yi's damage pipeline.
   *
   * Physical damage is reduced by target armor.
   * True damage ignores armor.
   *
   * Both damage types may still be affected by an explicit damage-reduction
   * mechanic such as Meditate.
   */
  private val physicalMitigation = Mitigation { rolledDamage, context ->
    val armor = context.state
      .attribute(context.target, ARMOR)
      ?.effectiveValue
      ?: 0

    val armorMultiplier = if (armor >= 0) {
      100.0 / (100.0 + armor)
    } else {
      2.0 - 100.0 / (100.0 - armor)
    }

    applyDamageReduction(
      rolledDamage * armorMultiplier,
      context
    )
  }

  private val trueDamageMitigation = Mitigation { rolledDamage, context ->
    applyDamageReduction(
      rolledDamage.toDouble(),
      context
    )
  }

  /**
   * Final state mutation for all damage produced by this model.
   *
   * FLCombat itself does not assume that an agent has HP. This class chooses to
   * represent health as the "health" attribute and defeat as reaching zero.
   */
  private val damageApplication = DamageApplication { mitigatedDamage, context ->
    val targetHealth = context.state
      .attribute(context.target, HEALTH)
      ?: error(
        "Agent '${context.target.id}' has no '$HEALTH' attribute"
      )

    val appliedDamage =
      mitigatedDamage.coerceAtMost(targetHealth.baseValue)

    val newHealth =
      targetHealth.baseValue - appliedDamage

    context.state.setAttribute(
      context.target,
      HEALTH,
      newHealth
    )

    if (newHealth == 0) {
      context.state.markDefeated(context.target)
    }

    appliedDamage
  }

  /**
   * Basic attack damage pipeline.
   */
  private val basicAttackResolver = CombatResolver(
    hitResolution = AlwaysHit,
    damageFormula = { context ->
      val attackDamage = context.state
        .attribute(context.attacker, ATTACK_DAMAGE)
        ?.effectiveValue
        ?: error(
          "Agent '${context.attacker.id}' has no '$ATTACK_DAMAGE' attribute"
        )

      DamageRange(
        min = attackDamage,
        max = attackDamage
      )
    },
    damageRoll = DeterministicDamageRoll,
    mitigation = physicalMitigation,
    damageApplication = damageApplication
  )

  /**
   * Second strike generated by Double Strike.
   */
  private val doubleStrikeResolver = CombatResolver(
    hitResolution = AlwaysHit,
    damageFormula = { context ->
      val attackDamage = context.state
        .attribute(context.attacker, ATTACK_DAMAGE)
        ?.effectiveValue
        ?: error(
          "Agent '${context.attacker.id}' has no '$ATTACK_DAMAGE' attribute"
        )

      val damage = floor(
        attackDamage * DOUBLE_STRIKE_SECOND_ATTACK_RATIO
      ).toInt()

      DamageRange(
        min = damage,
        max = damage
      )
    },
    damageRoll = DeterministicDamageRoll,
    mitigation = physicalMitigation,
    damageApplication = damageApplication
  )

  /**
   * Builds an Alpha Strike resolver for one specific hit multiplier.
   *
   * The FLCombat DamageFormula intentionally does not receive CombatAction,
   * so different damage semantics are represented by separate resolver
   * configurations instead of branching inside the core.
   */
  private fun alphaStrikeResolver(
    multiplier: Double
  ): CombatResolver =
    CombatResolver(
      hitResolution = AlwaysHit,
      damageFormula = { context ->
        val attackDamage = context.state
          .attribute(context.attacker, ATTACK_DAMAGE)
          ?.effectiveValue
          ?: error(
            "Agent '${context.attacker.id}' has no '$ATTACK_DAMAGE' attribute"
          )

        val raw =
          alphaBaseDamage[qRank] +
              attackDamage * 0.70

        val damage =
          floor(raw * multiplier).toInt()

        DamageRange(
          min = damage,
          max = damage
        )
      },
      damageRoll = DeterministicDamageRoll,
      mitigation = physicalMitigation,
      damageApplication = damageApplication
    )

  /**
   * Builds the Wuju Style on-hit damage resolver.
   */
  private fun wujuResolver(
    multiplier: Double
  ): CombatResolver =
    CombatResolver(
      hitResolution = AlwaysHit,
      damageFormula = { context ->
        val bonusAttackDamage = context.state
          .attribute(
            context.attacker,
            BONUS_ATTACK_DAMAGE
          )
          ?.effectiveValue
          ?: 0

        val raw =
          wujuTrueDamage[eRank] +
              bonusAttackDamage * 0.35

        val damage =
          floor(raw * multiplier).toInt()

        DamageRange(
          min = damage,
          max = damage
        )
      },
      damageRoll = DeterministicDamageRoll,
      mitigation = trueDamageMitigation,
      damageApplication = damageApplication
    )

  init {
    require(qRank in 1..5) {
      "qRank must be between 1 and 5"
    }

    require(wRank in 1..5) {
      "wRank must be between 1 and 5"
    }

    require(eRank in 1..5) {
      "eRank must be between 1 and 5"
    }

    require(rRank in 1..3) {
      "rRank must be between 1 and 3"
    }

    state.register(agent)

    /*
     * Level 1 base stats.
     *
     * Attack speed uses milli-AS:
     * 679 == 0.679
     */
    state.setAttribute(agent, MAX_HEALTH, 640)
    state.setAttribute(agent, HEALTH, 640)

    state.setAttribute(agent, MAX_MANA, 251)
    state.setAttribute(agent, MANA, 251)

    state.setAttribute(agent, ATTACK_DAMAGE, 65)
    state.setAttribute(agent, BONUS_ATTACK_DAMAGE, 0)

    state.setAttribute(agent, ATTACK_SPEED, 679)
    state.setAttribute(agent, ARMOR, 33)
    state.setAttribute(agent, MAGIC_RESISTANCE, 32)
    state.setAttribute(agent, MOVEMENT_SPEED, 355)
    state.setAttribute(agent, ATTACK_RANGE, 175)

    state.setAttribute(agent, DAMAGE_REDUCTION, 0)
    state.setAttribute(agent, ABILITY_POWER, 0)
  }

  /**
   * Registers an opponent in the same combat state.
   */
  fun registerTarget(
    target: Agent,
    maxHealth: Int,
    armor: Int = 0,
    magicResistance: Int = 0
  ) {
    require(maxHealth >= 0) {
      "maxHealth must not be negative"
    }

    state.register(target)

    state.setAttribute(
      target,
      MAX_HEALTH,
      maxHealth
    )

    state.setAttribute(
      target,
      HEALTH,
      maxHealth
    )

    state.setAttribute(
      target,
      ARMOR,
      armor
    )

    state.setAttribute(
      target,
      MAGIC_RESISTANCE,
      magicResistance
    )

    state.setAttribute(
      target,
      DAMAGE_REDUCTION,
      0
    )
  }

  fun currentHealth(): Int =
    state.attribute(agent, HEALTH)!!.baseValue

  fun currentMana(): Int =
    state.attribute(agent, MANA)!!.baseValue

  fun isDefeated(): Boolean =
    state.isDefeated(agent)

  fun isMeditating(): Boolean =
    meditateRemaining > 0

  fun isSlowImmune(): Boolean =
    highlanderRemaining > 0

  fun isCrippleImmune(): Boolean =
    highlanderRemaining > 0

  fun isGhosted(): Boolean =
    highlanderRemaining > 0

  fun doubleStrikeStacks(): Int =
    doubleStrikeStacks

  /**
   * Returns the remaining cooldown in seconds.
   *
   * Internally, the class uses half-second ticks.
   */
  fun remainingCooldown(
    ability: Ability
  ): Double =
    cooldowns.getValue(ability) / 2.0

  /**
   * Performs one basic attack.
   *
   * When Double Strike is ready, a second physical strike is generated.
   * If the first strike kills the target and [secondaryTarget] is supplied,
   * the second strike is redirected there.
   */
  fun basicAttack(
    target: Agent,
    secondaryTarget: Agent? = null
  ): BasicAttackResult {
    interruptMeditate()
    requireTarget(target)

    val empowered =
      doubleStrikeStacks >= DOUBLE_STRIKE_STACKS

    doubleStrikeStacks =
      if (empowered) {
        0
      } else {
        (doubleStrikeStacks + 1)
          .coerceAtMost(DOUBLE_STRIKE_STACKS)
      }

    doubleStrikeTimer = FOUR_SECONDS

    val strikes = mutableListOf<CombatResult>()

    strikes += basicAttackResolver.resolve(
      Attack(
        agent,
        target,
        "physical"
      ),
      state
    )

    reduceAlphaCooldown()

    if (!state.isDefeated(target) && hasWujuStyle()) {
      strikes += wujuResolver(1.0).resolve(
        WujuOnHit(agent, target),
        state
      )
    }

    if (empowered) {
      val secondTarget =
        if (!state.isDefeated(target)) {
          target
        } else {
          secondaryTarget
        }

      if (secondTarget != null) {
        requireTarget(secondTarget)

        if (!state.isDefeated(secondTarget)) {
          strikes += doubleStrikeResolver.resolve(
            DoubleStrike(
              agent,
              secondTarget
            ),
            state
          )

          reduceAlphaCooldown()

          if (!state.isDefeated(secondTarget) && hasWujuStyle()) {
            strikes += wujuResolver(1.0).resolve(
              WujuOnHit(
                agent,
                secondTarget
              ),
              state
            )
          }
        }
      }
    }

    return BasicAttackResult(
      strikes = strikes,
      doubleStrike = empowered
    )
  }

  /**
   * Resolves Alpha Strike against up to four marks.
   *
   * The caller provides the mark order because FLCombat has no concept of
   * spatial proximity, visibility or pathfinding.
   *
   * Repeated occurrences of the same target receive Alpha Strike's reduced
   * damage and reduced on-hit effectiveness.
   */
  fun alphaStrike(
    targets: List<Agent>
  ): AlphaStrikeResult {
    require(targets.isNotEmpty()) {
      "Alpha Strike requires at least one target"
    }

    require(targets.size <= 4) {
      "This model supports at most four Alpha Strike marks"
    }

    targets.forEach(::requireTarget)

    require(
      cooldowns.getValue(Ability.Q) == 0
    ) {
      "Alpha Strike is on cooldown"
    }

    require(
      canPay(alphaManaCost[qRank])
    ) {
      "Not enough mana for Alpha Strike"
    }

    interruptMeditate()

    spendMana(alphaManaCost[qRank])

    cooldowns[Ability.Q] =
      alphaCooldownTicks[qRank]

    val occurrences = mutableMapOf<Agent, Int>()
    val results = mutableListOf<CombatResult>()

    for (target in targets) {
      if (state.isDefeated(target)) {
        continue
      }

      val occurrence =
        occurrences.merge(
          target,
          1,
          Int::plus
        )!!

      val primary =
        occurrence == 1

      val damageMultiplier =
        if (primary) {
          1.0
        } else {
          0.25
        }

      results +=
        alphaStrikeResolver(
          damageMultiplier
        ).resolve(
          AlphaStrikeHit(
            agent,
            target,
            primary
          ),
          state
        )

      /*
       * Q applies on-hit effects:
       * 75% effectiveness on the primary hit,
       * 18.75% on subsequent hits against the same target.
       */
      if (!state.isDefeated(target) && hasWujuStyle()) {
        val onHitMultiplier =
          if (primary) {
            0.75
          } else {
            0.1875
          }

        results +=
          wujuResolver(
            onHitMultiplier
          ).resolve(
            AlphaStrikeWujuOnHit(
              agent,
              target,
              primary
            ),
            state
          )
      }

      refreshOnAlphaStrikeHit()
    }

    return AlphaStrikeResult(
      hits = results
    )
  }

  /**
   * Starts Meditate.
   *
   * The surrounding application decides when to call [tick] and when to
   * interrupt the channel.
   */
  fun meditate() {
    require(!isMeditating()) {
      "Meditate is already active"
    }

    require(
      cooldowns.getValue(Ability.W) == 0
    ) {
      "Meditate is on cooldown"
    }

    require(canPay(MEDITATE_MANA_COST)) {
      "Not enough mana for Meditate"
    }

    spendMana(MEDITATE_MANA_COST)

    cooldowns[Ability.W] =
      MEDITATE_COOLDOWN_TICKS

    meditateRemaining = 8
    meditateElapsed = 0

    /*
     * First 0.5 seconds:
     * 70% damage reduction.
     */
    applyMeditateReduction(700)
  }

  /**
   * Interrupts Meditate immediately.
   */
  fun interruptMeditate() {
    if (!isMeditating()) {
      return
    }

    meditateRemaining = 0
    meditateElapsed = 0

    state.removeEffect(
      agent,
      MEDITATE_EFFECT
    )
  }

  /**
   * Activates Wuju Style.
   */
  fun wujuStyle() {
    require(!hasWujuStyle()) {
      "Wuju Style is already active"
    }

    require(
      cooldowns.getValue(Ability.E) == 0
    ) {
      "Wuju Style is on cooldown"
    }

    interruptMeditate()

    cooldowns[Ability.E] =
      WUJU_COOLDOWN_TICKS

    wujuRemaining =
      WUJU_DURATION_TICKS

    state.applyEffect(
      agent,
      Effect(
        id = WUJU_EFFECT,
        durationTicks = null
      )
    )
  }

  /**
   * Activates Highlander.
   */
  fun highlander() {
    require(highlanderRemaining == 0) {
      "Highlander is already active"
    }

    require(
      cooldowns.getValue(Ability.R) == 0
    ) {
      "Highlander is on cooldown"
    }

    require(
      canPay(HIGHLANDER_MANA_COST)
    ) {
      "Not enough mana for Highlander"
    }

    interruptMeditate()

    spendMana(HIGHLANDER_MANA_COST)

    cooldowns[Ability.R] =
      HIGHLANDER_COOLDOWN_TICKS

    highlanderRemaining =
      SEVEN_SECONDS

    state.applyEffect(
      agent,
      Effect(
        id = HIGHLANDER_EFFECT,
        modifiers = mapOf(
          ATTACK_SPEED to listOf(
            Modifier(
              "percentage",
              highlanderAttackSpeed[rRank]
            )
          ),
          MOVEMENT_SPEED to listOf(
            Modifier(
              "percentage",
              highlanderMovementSpeed[rRank]
            )
          )
        ),
        durationTicks = null
      )
    )
  }

  /**
   * Applies the passive effect of a champion takedown.
   *
   * Basic ability cooldowns are reduced by 70%.
   * Highlander gains another 7 seconds while active.
   */
  fun championTakedown() {
    cooldowns[Ability.Q] =
      reduceBy70Percent(
        cooldowns.getValue(Ability.Q)
      )

    cooldowns[Ability.W] =
      reduceBy70Percent(
        cooldowns.getValue(Ability.W)
      )

    cooldowns[Ability.E] =
      reduceBy70Percent(
        cooldowns.getValue(Ability.E)
      )

    if (highlanderRemaining > 0) {
      highlanderRemaining +=
        SEVEN_SECONDS
    }
  }

  /**
   * Advances the model by 0.5 seconds.
   *
   * Cooldowns always advance.
   *
   * Wuju Style and Highlander remain paused while Meditate is active.
   */
  fun tick() {
    tickCooldowns()
    tickDoubleStrike()

    if (isMeditating()) {
      tickMeditate()
    } else {
      tickWuju()
      tickHighlander()
    }
  }

  private fun tickCooldowns() {
    for (ability in Ability.entries) {
      cooldowns[ability] =
        (
            cooldowns.getValue(ability) -
                HALF_SECOND_TICKS
            ).coerceAtLeast(0)
    }
  }

  private fun tickDoubleStrike() {
    if (doubleStrikeStacks == 0) {
      return
    }

    doubleStrikeTimer--

    if (doubleStrikeTimer <= 0) {
      doubleStrikeStacks = 0
      doubleStrikeTimer = 0
    }
  }

  private fun tickMeditate() {
    meditateElapsed++

    healSelf(
      meditateHealPerTick()
    )

    /*
     * After the first 0.5 seconds, the stronger initial reduction
     * becomes the rank-based sustained reduction.
     */
    if (meditateElapsed == 1) {
      applyMeditateReduction(
        meditateDamageReduction[wRank]
      )
    }

    /*
     * One Double Strike stack per second.
     */
    if (meditateElapsed % 2 == 0) {
      addDoubleStrikeStack()
    }

    meditateRemaining--

    if (meditateRemaining <= 0) {
      interruptMeditate()
    }
  }

  private fun tickWuju() {
    if (wujuRemaining <= 0) {
      return
    }

    wujuRemaining--

    if (wujuRemaining == 0) {
      state.removeEffect(
        agent,
        WUJU_EFFECT
      )
    }
  }

  private fun tickHighlander() {
    if (highlanderRemaining <= 0) {
      return
    }

    highlanderRemaining--

    if (highlanderRemaining == 0) {
      state.removeEffect(
        agent,
        HIGHLANDER_EFFECT
      )
    }
  }

  private fun meditateHealPerTick(): Int {
    val maxHealth =
      state.attribute(
        agent,
        MAX_HEALTH
      )!!.baseValue

    val health =
      currentHealth()

    val missingRatio =
      if (maxHealth == 0) {
        0.0
      } else {
        (
            maxHealth - health
            ).toDouble() / maxHealth
      }

    val min =
      meditateMinHeal[wRank]

    val max =
      meditateMaxHeal[wRank]

    val abilityPower =
      state.attribute(
        agent,
        ABILITY_POWER
      )?.effectiveValue
        ?: 0

    val minWithAp =
      min +
          floor(
            abilityPower * 0.125
          ).toInt()

    val maxWithAp =
      max +
          floor(
            abilityPower * 0.25
          ).toInt()

    return floor(
      minWithAp +
          (maxWithAp - minWithAp) *
          missingRatio
    ).toInt()
  }

  private fun healSelf(
    amount: Int
  ) {
    val maxHealth =
      state.attribute(
        agent,
        MAX_HEALTH
      )!!.baseValue

    val current =
      currentHealth()

    state.setAttribute(
      agent,
      HEALTH,
      (current + amount)
        .coerceAtMost(maxHealth)
    )
  }

  private fun applyMeditateReduction(
    percentageTenths: Int
  ) {
    state.applyEffect(
      agent,
      Effect(
        id = MEDITATE_EFFECT,
        modifiers = mapOf(
          DAMAGE_REDUCTION to listOf(
            Modifier(
              "additive",
              percentageTenths
            )
          )
        ),
        durationTicks = null
      )
    )
  }

  /**
   * Q refreshes the remaining durations of Wuju Style and Highlander.
   */
  private fun refreshOnAlphaStrikeHit() {
    if (hasWujuStyle()) {
      wujuRemaining =
        WUJU_DURATION_TICKS
    }

    if (highlanderRemaining > 0) {
      highlanderRemaining =
        SEVEN_SECONDS
    }
  }

  private fun addDoubleStrikeStack() {
    if (doubleStrikeStacks >= DOUBLE_STRIKE_STACKS) {
      return
    }

    doubleStrikeStacks++

    doubleStrikeTimer =
      FOUR_SECONDS
  }

  /**
   * Current patch behavior: each basic attack reduces Q's cooldown
   * by exactly one second.
   *
   * Internally one second equals two half-second ticks.
   */
  private fun reduceAlphaCooldown() {
    cooldowns[Ability.Q] =
      (
          cooldowns.getValue(Ability.Q) - 2
          ).coerceAtLeast(0)
  }

  private fun reduceBy70Percent(
    remaining: Int
  ): Int =
    remaining * 3 / 10

  private fun canPay(
    cost: Int
  ): Boolean =
    currentMana() >= cost

  private fun spendMana(
    cost: Int
  ) {
    state.setAttribute(
      agent,
      MANA,
      currentMana() - cost
    )
  }

  private fun hasWujuStyle(): Boolean =
    wujuRemaining > 0

  private fun requireTarget(
    target: Agent
  ) {
    require(state.contains(target)) {
      "Target '${target.id}' is not registered in this combat state"
    }

    require(
      state.attribute(target, HEALTH) != null
    ) {
      "Target '${target.id}' has no '$HEALTH' attribute"
    }

    require(!state.isDefeated(target)) {
      "Target '${target.id}' is defeated"
    }
  }

  private fun applyDamageReduction(
    rawDamage: Double,
    context: MitigationContext
  ): Int {
    val reduction =
      context.state
        .attribute(
          context.target,
          DAMAGE_REDUCTION
        )
        ?.effectiveValue
        ?.coerceIn(0, 1000)
        ?: 0

    val factor =
      (1000 - reduction) / 1000.0

    return floor(
      rawDamage * factor
    ).toInt().coerceAtLeast(0)
  }

  private data class DoubleStrike(
    override val attacker: Agent,
    override val target: Agent
  ) : CombatAction {
    override val damageType =
      "physical"
  }

  private data class WujuOnHit(
    override val attacker: Agent,
    override val target: Agent
  ) : CombatAction {
    override val damageType =
      "true"
  }

  private data class AlphaStrikeHit(
    override val attacker: Agent,
    override val target: Agent,
    val primary: Boolean
  ) : CombatAction {
    override val damageType =
      "physical"
  }

  private data class AlphaStrikeWujuOnHit(
    override val attacker: Agent,
    override val target: Agent,
    val primary: Boolean
  ) : CombatAction {
    override val damageType =
      "true"
  }
}
