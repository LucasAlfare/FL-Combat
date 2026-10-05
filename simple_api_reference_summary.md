# API Reference — FL-Combat

Kotlin library for turn-agnostic combat systems. Provides an authoritative mutable combat state, a pluggable damage
pipeline, and a structured event model. All game-specific logic (formulas, mitigations, hit rules, attribute
composition) is supplied by the consumer through strategy interfaces.

---

## Core Identities & Values

### `Agent`

```kotlin
data class Agent(val id: String)
```

**Purpose:** Stable, immutable identity of a combat participant. Carries **no** mutable state. Equality and hash code
are structural over `id`. A single `Agent` instance may be registered in multiple independent `CombatState`s.

| Property | Type     | Description                                                    |
|----------|----------|----------------------------------------------------------------|
| `id`     | `String` | Consumer-supplied stable identifier used as the structural key |

---

### `Attribute`

```kotlin
data class Attribute(
  val id: String,
  val baseValue: Int,
  val effectiveValue: Int
)
```

**Purpose:** Immutable snapshot of a consumer-defined numeric attribute of an agent. Produced by
`CombatState.attribute` / `attributes`.

| Property         | Type     | Description                                              |
|------------------|----------|----------------------------------------------------------|
| `id`             | `String` | Attribute identifier                                     |
| `baseValue`      | `Int`    | Value before modifiers                                   |
| `effectiveValue` | `Int`    | Value after composition by the active `ModifierComposer` |

---

### `Modifier`

```kotlin
data class Modifier(val id: String, val value: Int)
```

**Purpose:** Single opaque alteration applied to an attribute’s base value. Semantics of `id` are defined entirely by
the active `ModifierComposer`.

| Property | Type     | Description                                  |
|----------|----------|----------------------------------------------|
| `id`     | `String` | Semantic tag consumed by the composer        |
| `value`  | `Int`    | Magnitude (sign interpreted by the composer) |

---

### `Item`

```kotlin
data class Item(
  val id: String,
  val modifiers: Map<String, List<Modifier>> = emptyMap()
)
```

**Purpose:** Consumer-defined object owned by an agent that can contribute modifiers to one or more attributes. Identity
within a state is by `id` (replacement on conflict).

| Property    | Type                          | Description                                  |
|-------------|-------------------------------|----------------------------------------------|
| `id`        | `String`                      | Stable item identifier                       |
| `modifiers` | `Map<String, List<Modifier>>` | Attribute id → list of contributed modifiers |

---

### `Effect`

```kotlin
data class Effect(
  val id: String,
  val modifiers: Map<String, List<Modifier>> = emptyMap(),
  val durationTicks: Int? = null,
  val onApply: (Agent, CombatState) -> Unit = { _, _ -> },
  val onRemove: (Agent, CombatState) -> Unit = { _, _ -> }
)
```

**Purpose:** Consumer-defined status (buff/debuff) applied to an agent for a bounded or unbounded period. Supports
temporary (`durationTicks >= 0`) and permanent (`durationTicks == null`) durations. Identity within an agent is by
`id` (replacement triggers previous `onRemove`).

| Property        | Type                           | Description                                                     |
|-----------------|--------------------------------|-----------------------------------------------------------------|
| `id`            | `String`                       | Stable effect identifier                                        |
| `modifiers`     | `Map<String, List<Modifier>>`  | Attribute id → modifiers while applied                          |
| `durationTicks` | `Int?`                         | Remaining ticks, or `null` for permanent. Must be `null` or ≥ 0 |
| `onApply`       | `(Agent, CombatState) -> Unit` | Invoked immediately after installation                          |
| `onRemove`      | `(Agent, CombatState) -> Unit` | Invoked on explicit removal, replacement, or expiration         |

**Throws:** `IllegalArgumentException` if `durationTicks` is negative.

---

## Modifier Composition

### `ModifierComposer` (fun interface)

```kotlin
fun interface ModifierComposer {
  fun compose(baseValue: Int, modifiers: Collection<Modifier>): Int
}
```

**Purpose:** Sole point where modifier semantics are decided. The core never hard-codes a formula.

| Input                             | Output                  |
|-----------------------------------|-------------------------|
| `baseValue: Int`                  | `Int` (effective value) |
| `modifiers: Collection<Modifier>` |                         |

---

### `AdditiveThenPercentageComposer` (object)

**Purpose:** Default composer. Applies all `"additive"` modifiers first, then scales by the sum of `"percentage"`
modifiers, flooring toward −∞.

**Formula:**

```
additive   = sum of modifiers with id == "additive"
percentage = sum of modifiers with id == "percentage"
effective  = floor((baseValue + additive) * (100 + percentage) / 100.0)
```

| Constant     | Value          | Meaning                            |
|--------------|----------------|------------------------------------|
| `ADDITIVE`   | `"additive"`   | Flat addition                      |
| `PERCENTAGE` | `"percentage"` | Percentage applied after additives |

Modifiers with other ids are ignored. No zero-clamping is performed.

---

## Combat State

### `CombatState`

```kotlin
class CombatState(
  private val modifierComposer: ModifierComposer = AdditiveThenPercentageComposer
)
```

**Purpose:** Authoritative, presentation-free source of truth for the mutable state of a single combat. All mutable
information about every participating `Agent` lives here. Not thread-safe. Deterministic (no wall-clock or hidden
randomness).

| Parameter          | Type               | Default                          | Description                                       |
|--------------------|--------------------|----------------------------------|---------------------------------------------------|
| `modifierComposer` | `ModifierComposer` | `AdditiveThenPercentageComposer` | Strategy used for every effective attribute value |

#### Agent management

| Method                | Input   | Output       | Description                                                          |
|-----------------------|---------|--------------|----------------------------------------------------------------------|
| `register(agent)`     | `Agent` | —            | Registers a participant (idempotent)                                 |
| `contains(agent)`     | `Agent` | `Boolean`    | Whether the agent is registered                                      |
| `agents()`            | —       | `Set<Agent>` | Defensive snapshot of registered agents                              |
| `markDefeated(agent)` | `Agent` | —            | Marks defeated; emits `Defeat` on first call. Throws if unregistered |
| `isDefeated(agent)`   | `Agent` | `Boolean`    | Whether the agent is marked defeated                                 |

#### Attributes

| Method                               | Input                    | Output           | Description                                     |
|--------------------------------------|--------------------------|------------------|-------------------------------------------------|
| `setAttribute(agent, id, baseValue)` | `Agent`, `String`, `Int` | —                | Sets/creates base value. Throws if unregistered |
| `attribute(agent, id)`               | `Agent`, `String`        | `Attribute?`     | Snapshot, or `null` if no base value            |
| `attributes(agent)`                  | `Agent`                  | `Set<Attribute>` | All attributes with a base value                |
| `removeAttribute(agent, id)`         | `Agent`, `String`        | —                | Removes base value + direct modifiers           |

#### Direct modifiers

| Method                                         | Input                         | Output           | Description                                                        |
|------------------------------------------------|-------------------------------|------------------|--------------------------------------------------------------------|
| `addModifier(agent, attributeId, modifier)`    | `Agent`, `String`, `Modifier` | —                | Attaches a direct modifier. Throws if unregistered                 |
| `removeModifier(agent, attributeId, modifier)` | `Agent`, `String`, `Modifier` | —                | Removes first structurally equal direct modifier (no-op if absent) |
| `modifiers(agent, attributeId)`                | `Agent`, `String`             | `List<Modifier>` | Aggregate: direct + item + effect modifiers (defensive copy)       |

#### Items

| Method                      | Input             | Output      | Description                                          |
|-----------------------------|-------------------|-------------|------------------------------------------------------|
| `addItem(agent, item)`      | `Agent`, `Item`   | —           | Attaches/replaces item by id. Throws if unregistered |
| `removeItem(agent, itemId)` | `Agent`, `String` | —           | Removes item (no-op if absent)                       |
| `items(agent)`              | `Agent`           | `Set<Item>` | Defensive snapshot of owned items                    |

#### Effects

| Method                            | Input             | Output        | Description                                                                                   |
|-----------------------------------|-------------------|---------------|-----------------------------------------------------------------------------------------------|
| `applyEffect(agent, effect)`      | `Agent`, `Effect` | —             | Installs effect; replaces previous with same id (runs its `onRemove`). Throws if unregistered |
| `removeEffect(agent, effectId)`   | `Agent`, `String` | `Effect?`     | Removes effect and runs `onRemove`; returns the removed effect or `null`                      |
| `effects(agent)`                  | `Agent`           | `Set<Effect>` | Defensive snapshot of applied effects                                                         |
| `effect(agent, effectId)`         | `Agent`, `String` | `Effect?`     | Currently applied effect, or `null`                                                           |
| `remainingTicks(agent, effectId)` | `Agent`, `String` | `Int?`        | Remaining ticks, or `null` if permanent/absent                                                |
| `tick(agent)`                     | `Agent`           | —             | Advances temporary effects by one tick; expires those that reach zero                         |

#### Events

| Method          | Input | Output              | Description                                            |
|-----------------|-------|---------------------|--------------------------------------------------------|
| `drainEvents()` | —     | `List<CombatEvent>` | Returns and clears all pending events (emission order) |

---

## Randomness

### `RandomSource` (fun interface)

```kotlin
fun interface RandomSource {
  fun nextInt(fromInclusive: Int, toInclusive: Int): Int
}
```

**Purpose:** Abstraction over bounded pseudo-random integers. Library never touches global randomness directly.

| Input                                    | Output                                  | Throws                                                      |
|------------------------------------------|-----------------------------------------|-------------------------------------------------------------|
| `fromInclusive: Int`, `toInclusive: Int` | `Int` in `[fromInclusive, toInclusive]` | `IllegalArgumentException` if `fromInclusive > toInclusive` |

---

### `DefaultRandomSource` (object)

**Purpose:** Production source backed by `kotlin.random.Random.Default`.

---

### `DeterministicRandomSource`

```kotlin
class DeterministicRandomSource(private val values: Iterator<Int>) : RandomSource
```

**Purpose:** Returns a pre-configured sequence for reproducible tests.

| Constructor               | Description               |
|---------------------------|---------------------------|
| `(vararg values: Int)`    | Convenience over an array |
| `(values: Iterator<Int>)` | Primary                   |

**Throws on `nextInt`:**

- `IllegalArgumentException` if range is invalid or next value is outside the requested range
- `NoSuchElementException` if the sequence is exhausted

---

## Damage Pipeline

### `DamageRange`

```kotlin
data class DamageRange(val min: Int, val max: Int)
```

**Purpose:** Inclusive range of possible damage values. Fixed damage is modeled as `min == max`.

| Property | Constraint |
|----------|------------|
| `min`    | ≥ 0        |
| `max`    | ≥ `min`    |

**Throws:** `IllegalArgumentException` on invalid bounds.

---

### `DamageContext`

```kotlin
data class DamageContext(
  val attacker: Agent,
  val target: Agent,
  val state: CombatState
)
```

**Purpose:** Pure input to a `DamageFormula`. Formula must not mutate `state`.

---

### `DamageFormula` (fun interface)

```kotlin
fun interface DamageFormula {
  fun calculate(context: DamageContext): DamageRange
}
```

**Purpose:** Determines the mathematical possibilities of damage. Must be pure (no randomness, no mutation).

---

### `FixedDamageFormula`

```kotlin
class FixedDamageFormula(private val damage: Int) : DamageFormula
```

**Purpose:** Always yields a degenerate range `min == max == damage`.

| Input         | Constraint |
|---------------|------------|
| `damage: Int` | ≥ 0        |

---

### `DamageRoll` (fun interface)

```kotlin
fun interface DamageRoll {
  fun roll(range: DamageRange): Int
}
```

**Purpose:** Selects a concrete damage value from a range. Sole source of randomness in the pipeline.

---

### `DeterministicDamageRoll` (object)

**Purpose:** Always returns `range.min` (worst-case / test-friendly).

---

### `UniformDamageRoll`

```kotlin
class UniformDamageRoll(private val randomSource: RandomSource) : DamageRoll
```

**Purpose:** Draws a uniformly distributed value from `[min, max]` via the supplied `RandomSource`.

---

### `MitigationContext`

```kotlin
data class MitigationContext(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val state: CombatState
)
```

**Purpose:** Input to a `Mitigation`.

---

### `Mitigation` (fun interface)

```kotlin
fun interface Mitigation {
  fun mitigate(rolledDamage: Int, context: MitigationContext): Int
}
```

**Purpose:** Pure transformation of rolled damage into mitigated damage. Must return a non-negative value (clamped at
zero).

---

### Built-in Mitigations

| Type                                        | Formula                                                             | Notes                        |
|---------------------------------------------|---------------------------------------------------------------------|------------------------------|
| `NoMitigation`                              | `max(rolledDamage, 0)`                                              | Identity                     |
| `FixedReductionMitigation(reduction)`       | `max(rolledDamage - reduction, 0)`                                  | `reduction ≥ 0`              |
| `PercentageReductionMitigation(percentage)` | `floor(rolledDamage * max(100 - percentage, 0) / 100)` clamped at 0 | `percentage ≥ 0`             |
| `MitigationChain(mitigations)`              | Sequential application                                              | Empty chain ≡ `NoMitigation` |

---

### `DamageApplicationContext`

```kotlin
data class DamageApplicationContext(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val state: CombatState
)
```

**Purpose:** Input to a `DamageApplication`. Expected to mutate `state`.

---

### `DamageApplication` (fun interface)

```kotlin
fun interface DamageApplication {
  fun apply(mitigatedDamage: Int, context: DamageApplicationContext): Int
}
```

**Purpose:** Sole integration point where damage becomes state mutation (typically subtract from an attribute, clamp,
call `markDefeated`). Returns the amount actually subtracted (`appliedDamage`).

---

### `DamageResult`

```kotlin
data class DamageResult(
  val origin: Agent,
  val target: Agent,
  val damageType: String,
  val rolledDamage: Int,
  val mitigatedDamage: Int,
  val appliedDamage: Int
)
```

**Purpose:** Structured, immutable result of resolving damage. All three damage values are non-negative.

| Property          | Description                                  |
|-------------------|----------------------------------------------|
| `rolledDamage`    | Value from the roll                          |
| `mitigatedDamage` | Value after mitigation                       |
| `appliedDamage`   | Value effectively subtracted from the target |

---

## Actions & Resolution

### `CombatAction` (interface)

```kotlin
interface CombatAction {
  val attacker: Agent
  val target: Agent
  val damageType: String
}
```

**Purpose:** Intention to perform a combat interaction. Carries no embedded formula or mitigation; those are supplied
externally.

---

### `Attack`

```kotlin
data class Attack(
  override val attacker: Agent,
  override val target: Agent,
  override val damageType: String
) : CombatAction
```

**Purpose:** Canonical direct-attack action. Convenience only; consumers may define their own `CombatAction` subtypes.

---

### `HitResolution` (fun interface)

```kotlin
fun interface HitResolution {
  fun resolve(action: CombatAction, state: CombatState): Boolean
}
```

**Purpose:** Decides whether an action hits. Must not mutate state. A miss produces no damage and leaves the target
untouched.

---

### `AlwaysHit` (object)

**Purpose:** Always returns `true`. Useful as default and for tests.

---

### `CombatResult`

```kotlin
data class CombatResult(
  val action: CombatAction,
  val hit: Boolean,
  val damageResult: DamageResult?,
  val state: CombatState,
  val events: List<CombatEvent>
)
```

**Purpose:** Full outcome of resolving a `CombatAction`.

| Property       | Description                                             |
|----------------|---------------------------------------------------------|
| `action`       | Resolved action                                         |
| `hit`          | Whether the action hit                                  |
| `damageResult` | Present only when `hit == true`                         |
| `state`        | Same instance passed to the resolver (possibly mutated) |
| `events`       | Ordered events produced during resolution               |

---

### `CombatResolver`

```kotlin
class CombatResolver(
  private val hitResolution: HitResolution,
  private val damageFormula: DamageFormula,
  private val damageRoll: DamageRoll,
  private val mitigation: Mitigation,
  private val damageApplication: DamageApplication
)
```

**Purpose:** Stateless orchestrator that drives an action through the full pipeline:

```
Action → Hit → Formula → Roll → Mitigation → Application
```

| Parameter           | Role                     |
|---------------------|--------------------------|
| `hitResolution`     | Hit / miss decision      |
| `damageFormula`     | Possible damage range    |
| `damageRoll`        | Concrete value selection |
| `mitigation`        | Post-roll transformation |
| `damageApplication` | State mutation           |

#### `fun resolve(action: CombatAction, state: CombatState): CombatResult`

**Pipeline:**

1. Drains and discards any pre-existing pending events on `state`.
2. Consults `HitResolution`. On miss → emits `AttackMissed`, returns `hit = false`, `damageResult = null`.
3. On hit → emits `AttackPerformed`, runs formula → roll → mitigation → application, emits `DamageProduced` then
   `DamageApplied` (with any intermediate state events interleaved).

---

## Events

### `CombatEvent` (sealed interface)

**Purpose:** Structured, rendering-free record of something that happened. Emitted by `CombatState` operations and by
`CombatResolver.resolve`. Consumed via `drainEvents()` and `CombatResult.events`.

| Event                          | Properties        | When emitted                                          |
|--------------------------------|-------------------|-------------------------------------------------------|
| `AttackPerformed(action)`      | `CombatAction`    | Action confirmed as hit                               |
| `AttackMissed(action)`         | `CombatAction`    | Action failed hit resolution                          |
| `DamageProduced(result)`       | `DamageResult`    | After roll + mitigation, before application           |
| `DamageApplied(result)`        | `DamageResult`    | After application wrote damage into state             |
| `EffectApplied(agent, effect)` | `Agent`, `Effect` | Effect installed via `applyEffect`                    |
| `EffectRemoved(agent, effect)` | `Agent`, `Effect` | Effect removed (explicit, replacement, or expiration) |
| `Defeat(agent)`                | `Agent`           | First time an agent is marked defeated                |

---

## Architecture Overview

```
CombatState
  ├── Agents, Attributes, Modifiers, Items, Effects, Defeat status
  └── Event buffer → drainEvents()

CombatResolver
  Action
    → HitResolution          (hit / miss)
    → DamageFormula          (DamageRange)
    → DamageRoll             (rolledDamage)
    → Mitigation             (mitigatedDamage)
    → DamageApplication      (appliedDamage + state mutation)
  → CombatResult + CombatEvents
```

Clear separation of responsibilities:

- **CombatState** → mutable source of truth
- **ModifierComposer** → attribute effective-value formula
- **DamageFormula / DamageRoll / Mitigation / DamageApplication** → pluggable damage pipeline
- **HitResolution** → hit / miss policy
- **CombatEvent** → observable history