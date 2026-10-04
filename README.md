<div align="center">
# FLCombat

> A presentation-free, strategy-driven combat engine for Kotlin.

[![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/license-MIT-informational.svg)](./LICENSE)
[![](https://jitpack.io/v/LucasAlfare/FL-Combat.svg)](https://jitpack.io/#LucasAlfare/FL-Combat)
</div>

FLCombat provides the mechanics needed to model combat without deciding what those mechanics mean for a specific game.

The core stores combat state, composes attributes and modifiers, manages items and timed effects, and resolves actions
through an explicit damage pipeline. Game-specific rules stay outside the engine and are supplied through small strategy
interfaces.

```text
Game rules
    │
    ├── HitResolution
    ├── DamageFormula
    ├── DamageRoll
    ├── Mitigation
    └── DamageApplication
            │
            ▼
       ┌─────────────┐
       │ CombatState │
       └─────────────┘
            │
            ├── Attributes
            ├── Modifiers
            ├── Items
            ├── Effects
            └── Defeat state
```

## Why FLCombat?

FLCombat is deliberately small at its core and deliberately open at its boundaries.

It does not hard-code:

- a health system;
- attack power or defense formulas;
- physical, magical, elemental, or other damage semantics;
- critical-hit rules;
- armor rules;
- a turn system;
- rendering or UI;
- game victory conditions.

Instead, the library provides the state model and the execution structure required to implement those rules cleanly.

The result is a combat core that can be used for RPGs, tactical games, card games, simulators, prototypes, tests, or any
other system where an interaction can be represented as an action resolved against combat state.

## Features

- Immutable participant identities with `Agent`.
- Mutable, presentation-free combat state through `CombatState`.
- Arbitrary consumer-defined numeric attributes.
- Direct, item-provided, and effect-provided modifiers.
- Customizable modifier composition through `ModifierComposer`.
- Built-in additive + percentage modifier composition.
- Temporary and permanent effects with synchronous lifecycle callbacks.
- Explicit effect ticking instead of wall-clock timers.
- Pluggable randomness with deterministic test implementations.
- Explicit damage formula, roll, mitigation, and application stages.
- Composable mitigation chains.
- Custom combat actions through `CombatAction`.
- Structured `CombatResult` values instead of text output.
- Structured, sealed `CombatEvent` history.
- No rendering, logging, UI, networking, or framework integration in the core.
- Stateless `CombatResolver` instances that can be reused across combats.

## What FLCombat is not

FLCombat is not a complete game framework.

It does not provide:

- a character class hierarchy;
- a predefined stat sheet;
- a universal HP/MP/resource system;
- AI;
- a turn scheduler;
- animations;
- input handling;
- networking;
- persistence;
- rendering;
- a mandatory entity-component system.

Those systems can live around FLCombat while the combat core remains focused on state and resolution.

## Requirements

FLCombat is written in plain Kotlin and does not require Android or a game framework.

For a Gradle consumer project, use:

- Kotlin/JVM;
- a JDK supported by your Kotlin and Gradle toolchain;
- Gradle Kotlin DSL (`build.gradle.kts` / `settings.gradle.kts`).

The source itself uses only Kotlin standard-library APIs.

## Installation

### JitPack

JitPack builds the library directly from the Git repository and exposes the result as a Maven dependency. Replace the
placeholders below with the GitHub repository and release version once the project is published.

### 1. Add JitPack to `settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}
```

Keep JitPack after your normal Maven repositories.

### 2. Add FLCombat to `build.gradle.kts`

```kotlin
dependencies {
  implementation("com.github.LucasAlfare:FL-Combat:1.0.0")
}
```

## Quick start

The smallest useful setup is a `CombatState` plus a `CombatResolver` configured with the rules of your game.

```kotlin
fun main() {
  val hero = Agent("hero")
  val goblin = Agent("goblin")

  val state = CombatState()
  state.register(hero)
  state.register(goblin)
  state.setAttribute(hero, "health", 100)
  state.setAttribute(goblin, "health", 50)

  val resolver = CombatResolver(
    hitResolution = AlwaysHit,
    damageFormula = FixedDamageFormula(15),
    damageRoll = DeterministicDamageRoll,
    mitigation = NoMitigation,
    damageApplication = DamageApplication { damage, context ->
      val health = context.state.attribute(context.target, "health")!!
      val applied = damage.coerceAtMost(health.baseValue)
      val newHealth = health.baseValue - applied

      context.state.setAttribute(context.target, "health", newHealth)

      if (newHealth == 0) {
        context.state.markDefeated(context.target)
      }

      applied
    }
  )

  val result = resolver.resolve(
    Attack(hero, goblin, damageType = "physical"),
    state
  )

  println("Hit: ${result.hit}")
  println("Applied damage: ${result.damageResult?.appliedDamage}")
  println("Goblin HP: ${state.attribute(goblin, "health")?.effectiveValue}")
  println("Events: ${result.events}")
}
```

The important part is not the example itself. The important part is that FLCombat does not know that `health` is health,
that `physical` means physical damage, or that reaching zero means defeat. Those meanings belong to the application.

## Core architecture

### 1. Combat state

`CombatState` is the authoritative mutable state of one combat.

It owns:

```text
Agent
 │
 └── CombatState
      ├── base attributes
      ├── direct modifiers
      ├── items
      ├── effects
      ├── defeated flag
      └── pending events
```

`Agent` itself contains no combat state. Its only purpose is to provide a stable identity/key.

```kotlin
val player = Agent("player-1")
val enemy = Agent("enemy-1")
```

The same `Agent` may participate in multiple independent `CombatState` instances without sharing mutable combat data.

### 2. Attributes

Attributes are intentionally generic.

```kotlin
state.setAttribute(player, "strength", 20)
state.setAttribute(player, "armor", 10)
state.setAttribute(player, "mana", 80)
```

An `Attribute` is returned as a snapshot containing:

- `id`;
- `baseValue`;
- `effectiveValue`.

`effectiveValue` is calculated on demand by the active `ModifierComposer`.

Attributes are not a fixed catalog. If your game calls something `rage`, `focus`, `shield`, `luck`, or `energy`,
FLCombat does not care.

### 3. Modifiers

A `Modifier` is intentionally opaque to the core:

```kotlin
Modifier("additive", 5)
Modifier("percentage", 20)
```

The meaning of the identifier is defined by the selected `ModifierComposer`.

Direct modifiers can be attached independently:

```kotlin
state.addModifier(player, "strength", Modifier("additive", 5))
```

Item and effect modifiers are merged with direct modifiers when the effective value is calculated.

Direct modifiers are not automatically deduplicated. Removing a modifier removes the first structurally equal direct
modifier.

### 4. Items

`Item` is a data object that contributes modifiers to attributes.

```kotlin
val sword = Item(
  id = "iron-sword",
  modifiers = mapOf(
    "strength" to listOf(Modifier("additive", 8))
  )
)

state.addItem(player, sword)
```

Items have no built-in categories or behavior. A sword, shield, ring, buff item, passive item, or anything else is
simply an `Item` with modifier contributions.

Within one `CombatState`, item identity is its `id`. Adding an item with an existing id replaces the previous item.

### 5. Effects

`Effect` represents a status with optional modifiers and lifecycle callbacks.

Temporary effect:

```kotlin
val enraged = Effect(
  id = "enraged",
  modifiers = mapOf(
    "strength" to listOf(Modifier("percentage", 25))
  ),
  durationTicks = 3
)
```

Permanent effect:

```kotlin
val passive = Effect(
  id = "bloodlust",
  durationTicks = null
)
```

Effects are advanced only when the application explicitly calls `tick`:

```kotlin
state.tick(player)
```

There is no wall-clock scheduler inside FLCombat.

A temporary effect with `durationTicks = 0` expires on the first tick that observes it.

Applying an effect with the same id replaces the existing effect. The previous effect receives `onRemove`, then the new
effect is installed and receives `onApply`.

## Modifier composition

Modifier semantics are delegated to `ModifierComposer`.

The built-in `AdditiveThenPercentageComposer` interprets two modifier ids:

```text
additive   = sum of all "additive" values
percentage = sum of all "percentage" values

effective = floor((baseValue + additive) * (100 + percentage) / 100)
```

Example:

```kotlin
val state = CombatState()
val hero = Agent("hero")
state.register(hero)
state.setAttribute(hero, "strength", 100)
state.addModifier(hero, "strength", Modifier("additive", 20))
state.addModifier(hero, "strength", Modifier("percentage", 50))

check(state.attribute(hero, "strength")!!.effectiveValue == 180)
```

Only the composer assigns meaning to modifier ids. A game that needs a different formula can provide its own
implementation:

```kotlin
val composer = ModifierComposer { baseValue, modifiers ->
  // Your game's formula.
  baseValue + modifiers.sumOf { it.value }
}

val state = CombatState(composer)
```

This keeps the state model independent from game-specific math.

## Damage resolution

The central pipeline is:

```text
CombatAction
     │
     ▼
HitResolution
     │
     ├── miss ───────────────► CombatResult(hit = false)
     │
     ▼
DamageFormula
     │
     ▼
DamageRange
     │
     ▼
DamageRoll
     │
     ▼
rolledDamage
     │
     ▼
Mitigation
     │
     ▼
mitigatedDamage
     │
     ▼
DamageApplication
     │
     ▼
appliedDamage
     │
     ▼
CombatResult
```

Each stage has one job.

### `CombatAction`

`CombatAction` contains only the generic data required by the pipeline:

- `attacker`;
- `target`;
- `damageType`.

`Attack` is the built-in direct-action implementation.

You can create richer actions without changing the core:

```kotlin
data class Fireball(
  override val attacker: Agent,
  override val target: Agent,
  override val damageType: String = "fire"
) : CombatAction
```

### `HitResolution`

Determines whether an action hits.

```kotlin
val hitResolution = HitResolution { action, state ->
  // Your hit rule.
  true
}
```

A miss produces no `DamageResult` and does not apply damage.

`AlwaysHit` is provided for simple rules and deterministic tests.

### `DamageFormula`

Describes what damage is possible, not which value occurred.

```kotlin
val formula = DamageFormula { context ->
  val strength = context.state
    .attribute(context.attacker, "strength")!!
    .effectiveValue

  DamageRange(
    min = strength / 2,
    max = strength
  )
}
```

`FixedDamageFormula` is available when the range is a single value.

### `DamageRoll`

Selects one value from the formula's inclusive `DamageRange`.

Two built-in policies are provided:

- `DeterministicDamageRoll` selects the minimum;
- `UniformDamageRoll` delegates to a `RandomSource`.

Randomness is deliberately isolated behind `RandomSource` so tests can replace it with `DeterministicRandomSource`.

### `Mitigation`

Transforms rolled damage before it reaches the state.

Built-in implementations include:

```kotlin
NoMitigation
FixedReductionMitigation(10)
PercentageReductionMitigation(25)
```

Multiple mitigation rules can be composed in order:

```kotlin
val mitigation = MitigationChain(
  PercentageReductionMitigation(20),
  FixedReductionMitigation(5)
)
```

Each step receives the result of the previous step, and the chain clamps intermediate values to zero.

### `DamageApplication`

This is the point where damage becomes game state mutation.

The core does not decide how health works. The application is responsible for reading and mutating `CombatState` and for
deciding when a participant becomes defeated.

That means the same resolver pipeline can support radically different resource models.

## Damage results

A successful resolution returns a `DamageResult` containing three distinct values:

```text
rolledDamage
     │
     ▼
mitigatedDamage
     │
     ▼
appliedDamage
```

This distinction matters when, for example:

- a roll produces more damage than the target can lose;
- mitigation reduces the incoming value;
- the application caps or otherwise transforms the actual subtraction.

`DamageResult` keeps all three values explicit.

## Events

FLCombat exposes structured events through `CombatEvent`.

The event hierarchy currently contains:

| Event             | Meaning                                               |
|-------------------|-------------------------------------------------------|
| `AttackPerformed` | A combat action passed hit resolution.                |
| `AttackMissed`    | A combat action failed hit resolution.                |
| `DamageProduced`  | A `DamageResult` was produced by the damage pipeline. |
| `DamageApplied`   | The resulting damage application completed.           |
| `EffectApplied`   | An effect was applied to an agent.                    |
| `EffectRemoved`   | An effect was removed or expired.                     |
| `Defeat`          | An agent was marked defeated for the first time.      |

State-level events can be consumed with:

```kotlin
val events = state.drainEvents()
```

`CombatResolver.resolve` also returns a `CombatResult` whose `events` list contains the events associated with that
resolution.

Events are structured data. They do not render text and do not perform UI work.

This makes them suitable for logging, animation triggers, replay systems, network messages, testing, telemetry, or other
application-level consumers.

## Deterministic testing

Randomness never comes directly from global random calls inside the combat pipeline. The random boundary is
`RandomSource`.

Production:

```kotlin
val roll = UniformDamageRoll(DefaultRandomSource)
```

Tests:

```kotlin
val roll = UniformDamageRoll(
  DeterministicRandomSource(10, 15, 12)
)
```

For completely predictable fixed-range behavior, `DeterministicDamageRoll` avoids randomness altogether.

This separation also makes the formula and mitigation stages independently testable.

## Reusing the resolver

`CombatResolver` does not own combat state.

A resolver is configured with strategies and can be reused with multiple independent `CombatState` instances:

```kotlin
val resolver = CombatResolver(
  hitResolution = AlwaysHit,
  damageFormula = FixedDamageFormula(10),
  damageRoll = DeterministicDamageRoll,
  mitigation = NoMitigation,
  damageApplication = yourDamageApplication
)

val firstCombat = CombatState()
val secondCombat = CombatState()

resolver.resolve(firstAction, firstCombat)
resolver.resolve(secondAction, secondCombat)
```

All mutable combat data remains inside each state instance.

## State semantics

A few rules are intentionally explicit:

### Snapshots are snapshots

`Attribute`, `items()`, `effects()`, `agents()`, and `drainEvents()` return snapshots or defensive copies. They are not
live mutable views into the internal collections.

### Registration comes first

Operations that mutate an agent's state require that agent to be registered first.

```kotlin
state.register(player)
```

### Ticks are explicit

Effects never advance because time passes. The consumer decides when a combat tick occurs:

```kotlin
state.tick(player)
```

### No implicit synchronization

`CombatState` mutates synchronously on the calling thread and is not thread-safe.

If your game runs simulation and rendering on different threads, synchronize or otherwise coordinate access at the
application boundary.

## Public API

The public API is intentionally small and organized around a handful of concepts.

### State

| Type          | Responsibility                                                    |
|---------------|-------------------------------------------------------------------|
| `Agent`       | Stable participant identity.                                      |
| `Attribute`   | Immutable attribute snapshot.                                     |
| `Modifier`    | Opaque attribute modification.                                    |
| `Item`        | Modifier contribution owned by an agent.                          |
| `Effect`      | Temporary/permanent status with optional callbacks and modifiers. |
| `CombatState` | Authoritative mutable combat state.                               |

### Modifier system

| Type                             | Responsibility                             |
|----------------------------------|--------------------------------------------|
| `ModifierComposer`               | Defines modifier semantics.                |
| `AdditiveThenPercentageComposer` | Built-in additive-then-percentage formula. |

### Randomness

| Type                        | Responsibility                                        |
|-----------------------------|-------------------------------------------------------|
| `RandomSource`              | Random integer abstraction.                           |
| `DefaultRandomSource`       | Production implementation backed by `Random.Default`. |
| `DeterministicRandomSource` | Reproducible sequence for tests.                      |

### Damage

| Type                      | Responsibility                            |
|---------------------------|-------------------------------------------|
| `DamageRange`             | Inclusive possible-damage interval.       |
| `DamageContext`           | Formula input context.                    |
| `DamageFormula`           | Computes a possible damage range.         |
| `FixedDamageFormula`      | Constant-damage formula.                  |
| `DamageRoll`              | Selects a concrete damage value.          |
| `DeterministicDamageRoll` | Selects the minimum value.                |
| `UniformDamageRoll`       | Uniformly selects through `RandomSource`. |
| `DamageResult`            | Rolled, mitigated, and applied damage.    |

### Mitigation

| Type                            | Responsibility                                  |
|---------------------------------|-------------------------------------------------|
| `MitigationContext`             | Input context for mitigation.                   |
| `Mitigation`                    | Damage transformation strategy.                 |
| `NoMitigation`                  | Identity mitigation.                            |
| `FixedReductionMitigation`      | Fixed subtraction with zero clamp.              |
| `PercentageReductionMitigation` | Percentage reduction with floor and zero clamp. |
| `MitigationChain`               | Ordered composition of mitigation strategies.   |

### Application and actions

| Type                       | Responsibility                              |
|----------------------------|---------------------------------------------|
| `DamageApplicationContext` | Context used when mutating combat state.    |
| `DamageApplication`        | Applies mitigated damage to the game state. |
| `CombatAction`             | Generic combat interaction.                 |
| `Attack`                   | Built-in direct attack action.              |
| `HitResolution`            | Hit/miss decision.                          |
| `AlwaysHit`                | Built-in always-hit rule.                   |
| `CombatResolver`           | Executes the complete resolution pipeline.  |
| `CombatResult`             | Complete outcome of one resolution.         |

### Events

| Type                          | Responsibility                                       |
|-------------------------------|------------------------------------------------------|
| `CombatEvent`                 | Sealed event hierarchy for observable combat events. |
| `CombatEvent.AttackPerformed` | Hit action event.                                    |
| `CombatEvent.AttackMissed`    | Miss event.                                          |
| `CombatEvent.DamageProduced`  | Produced damage event.                               |
| `CombatEvent.DamageApplied`   | Applied damage event.                                |
| `CombatEvent.EffectApplied`   | Effect application event.                            |
| `CombatEvent.EffectRemoved`   | Effect removal/expiration event.                     |
| `CombatEvent.Defeat`          | Defeat event.                                        |

For the complete contract, parameter documentation, invariants, and exception behavior, use the generated KDoc/API
reference once published.

**API reference placeholder:** `YOUR_API_DOCS_URL`

## Typical integration shape

A game can keep its own domain model and map it into FLCombat at the boundary.

```text
┌────────────────────────────────────────────────────────────┐
│                         Game / App                          │
│                                                            │
│  Characters  Skills  Equipment  Rules  UI  Networking      │
│       │         │        │        │                         │
│       └─────────┴────────┴────────┴───────┐                 │
│                                           ▼                 │
│                                  FLCombat strategies        │
│                                           │                 │
└───────────────────────────────────────────┼─────────────────┘
                                            ▼
                                  ┌───────────────────┐
                                  │   CombatResolver  │
                                  └─────────┬─────────┘
                                            │
                                  ┌─────────▼─────────┐
                                  │   CombatState     │
                                  └───────────────────┘
```

A practical integration normally looks like this:

1. Create stable `Agent` identities.
2. Register them in a `CombatState`.
3. Populate base attributes.
4. Attach modifiers, items, and effects as needed.
5. Define game-specific hit, damage, mitigation, and application rules.
6. Create one reusable `CombatResolver` for that ruleset.
7. Resolve `CombatAction` instances against each combat state.
8. Consume `CombatResult` and `CombatEvent` data in the application layer.

## Building from source

Clone the repository and use the Gradle wrapper.

### Unix / macOS

```bash
./gradlew build
```

### Windows

```powershell
./gradlew.bat build
```

Useful tasks for a normal library workflow:

```bash
./gradlew clean
./gradlew build
./gradlew test
```

The project should be kept framework-independent: the core library itself does not need Android Studio, a game engine, a
UI toolkit, or a networking stack.

## Design principles

### Game-agnostic core

The library does not know what an attribute, damage type, item, effect, or action means. Those semantics belong to the
consumer.

### Explicit strategy boundaries

Rules that are likely to differ from one game to another are interfaces rather than hidden branches in the core.

### State as the source of truth

Mutable combat information lives in `CombatState`; `Agent` stays immutable.

### Deterministic by default where possible

Time progression is explicit, randomness is injectable, and formulas are separate from rolls.

### Presentation-free

The core emits structured data instead of printing messages or controlling presentation.

### Small public surface

The public API is built from data classes, sealed events, small interfaces, and a single orchestration object rather
than a framework of tightly coupled abstractions.

## Documentation

The source code contains full KDoc for the public API.

Until the generated documentation is published, the source is the authoritative API reference.

## Contributing

Contributions are welcome.

Before opening a pull request:

1. keep changes focused on the combat core;
2. preserve the game-agnostic design;
3. add or update tests for behavioral changes;
4. keep the public API intentional and minimal;
5. document new public types and members with KDoc.

## License

FLCombat is available under the [MIT License](./LICENSE).