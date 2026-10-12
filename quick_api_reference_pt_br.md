# Sumário da API Pública

## Visão Geral

Biblioteca Kotlin para modelagem de combate (`flcombat`). Fornece um estado autoritativo e imutável em relação aos
participantes (`CombatState`), sistema de atributos com modificadores, itens e efeitos, pipeline completo de resolução
de dano (hit → fórmula → roll → mitigação → aplicação) e eventos observáveis. Toda a lógica de jogo é injetável via
estratégias (`fun interface`), mantendo o core agnóstico a regras específicas.

## Métodos e Tipos Públicos

### `Agent(id: String)`

**Propósito:**  
Identidade estável e imutável de um participante do combate. Serve apenas como chave estrutural; todo estado mutável
fica no `CombatState`.

**Parâmetros:**

- `id` (`String`): Identificador estável definido pelo consumidor.

**Retorno:**  
`Agent`

---

### `Attribute(id: String, baseValue: Int, effectiveValue: Int)`

**Propósito:**  
Snapshot imutável de um atributo de um agente (valor base + valor efetivo após modificadores).

**Parâmetros:**

- `id` (`String`): Identificador do atributo.
- `baseValue` (`Int`): Valor antes dos modificadores.
- `effectiveValue` (`Int`): Valor após composição dos modificadores.

**Retorno:**  
`Attribute`

---

### `Modifier(id: String, value: Int)`

**Propósito:**  
Alteração opaca aplicável a um atributo. A semântica do `id` é definida exclusivamente pelo `ModifierComposer` em uso.

**Parâmetros:**

- `id` (`String`): Tag semântica consumida pelo composer.
- `value` (`Int`): Magnitude do modificador.

**Retorno:**  
`Modifier`

---

### `Item(id: String, modifiers: Map<String, List<Modifier>> = emptyMap())`

**Propósito:**  
Objeto possuído por um agente que contribui com modificadores para um ou mais atributos.

**Parâmetros:**

- `id` (`String`): Identificador estável do item.
- `modifiers` (`Map<String, List<Modifier>>`): Mapa de atributo → lista de modificadores.

**Retorno:**  
`Item`

---

###
`Effect(id: String, modifiers: Map<String, List<Modifier>> = emptyMap(), durationTicks: Int? = null, onApply: (Agent, CombatState) → Unit = { _, _ → }, onRemove: (Agent, CombatState) → Unit = { _, _ → })`

**Propósito:**  
Status (buff/debuff) aplicado a um agente, com duração opcional e callbacks de ciclo de vida.

**Parâmetros:**

- `id` (`String`): Identificador estável do efeito.
- `modifiers` (`Map<String, List<Modifier>>`): Modificadores contribuídos enquanto ativo.
- `durationTicks` (`Int?`): Duração em ticks (`null` = permanente).
- `onApply` / `onRemove`: Callbacks executados na aplicação/remoção.

**Retorno:**  
`Effect`

---

### `ModifierComposer` (fun interface)

**Propósito:**  
Estratégia que transforma valor base + coleção de modificadores no valor efetivo de um atributo.

Método:

- `compose(baseValue: Int, modifiers: Collection<Modifier>) → Int`

Implementação padrão: `AdditiveThenPercentageComposer` (soma aditivos, depois aplica porcentagem e arredonda para
baixo).

---

### `CombatState(modifierComposer: ModifierComposer = AdditiveThenPercentageComposer)`

**Propósito:**  
Fonte de verdade autoritativa do estado mutável de um combate. Gerencia agentes, atributos, modificadores, itens,
efeitos, derrota e eventos.

Métodos públicos principais:

| Método            | Assinatura                                                | Propósito                                                                                     |
|-------------------|-----------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `drainEvents`     | `() → List<CombatEvent>`                                  | Retorna e limpa todos os eventos acumulados desde a última chamada.                           |
| `register`        | `(agent: Agent)`                                          | Registra um participante (idempotente).                                                       |
| `contains`        | `(agent: Agent) → Boolean`                                | Verifica se o agente está registrado.                                                         |
| `agents`          | `() → Set<Agent>`                                         | Snapshot dos agentes registrados.                                                             |
| `markDefeated`    | `(agent: Agent)`                                          | Marca o agente como derrotado (emite evento na primeira vez).                                 |
| `isDefeated`      | `(agent: Agent) → Boolean`                                | Verifica se o agente está derrotado.                                                          |
| `setAttribute`    | `(agent: Agent, id: String, baseValue: Int)`              | Define/atualiza o valor base de um atributo.                                                  |
| `addModifier`     | `(agent: Agent, attributeId: String, modifier: Modifier)` | Adiciona um modificador direto.                                                               |
| `removeModifier`  | `(agent: Agent, attributeId: String, modifier: Modifier)` | Remove o primeiro modificador direto igual por igualdade estrutural.                          |
| `addItem`         | `(agent: Agent, item: Item)`                              | Anexa um item (substitui se o `id` já existir).                                               |
| `removeItem`      | `(agent: Agent, itemId: String)`                          | Remove um item pelo id.                                                                       |
| `items`           | `(agent: Agent) → Set<Item>`                              | Snapshot dos itens do agente.                                                                 |
| `applyEffect`     | `(agent: Agent, effect: Effect)`                          | Aplica (ou substitui) um efeito, executando callbacks e emitindo eventos.                     |
| `removeEffect`    | `(agent: Agent, effectId: String) → Effect?`              | Remove um efeito e retorna a instância removida (ou `null`).                                  |
| `effects`         | `(agent: Agent) → Set<Effect>`                            | Snapshot dos efeitos ativos.                                                                  |
| `effect`          | `(agent: Agent, effectId: String) → Effect?`              | Retorna o efeito ativo pelo id.                                                               |
| `remainingTicks`  | `(agent: Agent, effectId: String) → Int?`                 | Retorna ticks restantes de um efeito temporário.                                              |
| `tick`            | `(agent: Agent)`                                          | Avança em 1 tick as durações dos efeitos temporários do agente (expira os que chegam a zero). |
| `modifiers`       | `(agent: Agent, attributeId: String) → List<Modifier>`    | Lista agregada de todos os modificadores que afetam o atributo (diretos + itens + efeitos).   |
| `attribute`       | `(agent: Agent, id: String) → Attribute?`                 | Snapshot de um atributo (base + efetivo) ou `null`.                                           |
| `attributes`      | `(agent: Agent) → Set<Attribute>`                         | Snapshot de todos os atributos definidos no agente.                                           |
| `removeAttribute` | `(agent: Agent, id: String)`                              | Remove o atributo e seus modificadores diretos.                                               |

---

### `RandomSource` (fun interface)

**Propósito:**  
Fonte de números aleatórios limitados, usada pelo sistema de dano.

Método:

- `nextInt(fromInclusive: Int, toInclusive: Int) → Int`

Implementações:

- `DefaultRandomSource` — usa `Random.Default`.
- `DeterministicRandomSource(values: Iterator<Int>)` — sequência fixa para testes.

---

### `DamageRange(min: Int, max: Int)`

**Propósito:**  
Intervalo inclusivo de possíveis valores de dano (min e max não-negativos, min ≤ max).

---

### `DamageContext(attacker: Agent, target: Agent, state: CombatState)`

**Propósito:**  
Contexto passado a uma `DamageFormula`.

---

### `DamageFormula` (fun interface)

**Propósito:**  
Calcula o intervalo de dano possível para um contexto (puro, sem aleatoriedade).

Método:

- `calculate(context: DamageContext) → DamageRange`

Implementação de conveniência: `FixedDamageFormula(damage: Int)`.

---

### `DamageRoll` (fun interface)

**Propósito:**  
Seleciona um valor concreto dentro de um `DamageRange`.

Método:

- `roll(range: DamageRange) → Int`

Implementações:

- `DeterministicDamageRoll` — sempre retorna o mínimo.
- `UniformDamageRoll(randomSource: RandomSource)` — sorteio uniforme.

---

### `MitigationContext(origin: Agent, target: Agent, damageType: String, state: CombatState)`

**Propósito:**  
Contexto passado a uma `Mitigation`.

---

### `Mitigation` (fun interface)

**Propósito:**  
Transforma o dano rolado em dano mitigado (sempre não-negativo).

Método:

- `mitigate(rolledDamage: Int, context: MitigationContext) → Int`

Implementações:

- `NoMitigation`
- `FixedReductionMitigation(reduction: Int)`
- `PercentageReductionMitigation(percentage: Int)`
- `MitigationChain(mitigations: List<Mitigation>)`

---

### `DamageApplicationContext(origin: Agent, target: Agent, damageType: String, state: CombatState)`

**Propósito:**  
Contexto passado a uma `DamageApplication` (permite mutação do estado).

---

### `DamageApplication` (fun interface)

**Propósito:**  
Escreve o dano mitigado no estado e retorna quanto foi efetivamente aplicado.

Método:

- `apply(mitigatedDamage: Int, context: DamageApplicationContext) → Int`

---

### `DamageResult(...)`

**Propósito:**  
Resultado imutável da resolução de dano, preservando as três etapas: rolled → mitigated → applied.

Propriedades: `origin`, `target`, `damageType`, `rolledDamage`, `mitigatedDamage`, `appliedDamage`.

---

### `CombatAction` (interface)

**Propósito:**  
Intenção de interação de combate (atacante, alvo e tipo de dano).

Propriedades: `attacker: Agent`, `target: Agent`, `damageType: String`.

Implementação canônica: `Attack(attacker, target, damageType)`.

---

### `HitResolution` (fun interface)

**Propósito:**  
Decide se uma ação acerta o alvo.

Método:

- `resolve(action: CombatAction, state: CombatState) → Boolean`

Implementação: `AlwaysHit`.

---

### `CombatResult(...)`

**Propósito:**  
Resultado completo da resolução de uma ação.

Propriedades: `action`, `hit`, `damageResult: DamageResult?`, `state`, `events: List<CombatEvent>`.

---

### `CombatResolver(...)`

**Propósito:**  
Orquestrador do pipeline completo de resolução de ação:

`Ação → Hit → Fórmula → Roll → Mitigação → Aplicação`

Construtor:

```kotlin
CombatResolver(
  hitResolution: HitResolution,
  damageFormula: DamageFormula,
  damageRoll: DamageRoll,
  mitigation: Mitigation,
  damageApplication: DamageApplication
)
```

Método público:

- `resolve(action: CombatAction, state: CombatState) → CombatResult`

---

### `CombatEvent` (sealed interface)

**Propósito:**  
Eventos estruturados e sem efeitos colaterais emitidos durante o combate.

Subtipos:

- `AttackPerformed(action)`
- `AttackMissed(action)`
- `DamageProduced(result)`
- `DamageApplied(result)`
- `EffectApplied(agent, effect)`
- `EffectRemoved(agent, effect)`
- `Defeat(agent)`

---

## Como Usar

1. **Crie o estado e registre os participantes:**
   ```kotlin
   val state = CombatState()
   val hero = Agent("hero")
   val enemy = Agent("enemy")
   state.register(hero)
   state.register(enemy)
   ```

2. **Defina atributos, itens e efeitos:**
   ```kotlin
   state.setAttribute(hero, "hp", 100)
   state.setAttribute(hero, "strength", 15)
   state.addItem(hero, Item("sword", mapOf("strength" to listOf(Modifier("additive", 5)))))
   state.applyEffect(enemy, Effect("poison", durationTicks = 3, onApply = { a, s -> /* ... */ }))
   ```

3. **Monte o resolver com as estratégias desejadas:**
   ```kotlin
   val resolver = CombatResolver(
     hitResolution = AlwaysHit,
     damageFormula = FixedDamageFormula(20), // ou uma fórmula customizada
     damageRoll = UniformDamageRoll(DefaultRandomSource),
     mitigation = MitigationChain(
       FixedReductionMitigation(2),
       PercentageReductionMitigation(10)
     ),
     damageApplication = { mitigated, ctx ->
       // exemplo: subtrair de "hp" e marcar derrota se <= 0
       val hp = ctx.state.attribute(ctx.target, "hp") ?: return@DamageApplication 0
       val newHp = (hp.effectiveValue - mitigated).coerceAtLeast(0)
       ctx.state.setAttribute(ctx.target, "hp", newHp)
       if (newHp == 0) ctx.state.markDefeated(ctx.target)
       mitigated
     }
   )
   ```

4. **Resolva ações:**
   ```kotlin
   val action = Attack(hero, enemy, damageType = "physical")
   val result = resolver.resolve(action, state)

   // result.hit, result.damageResult, result.events
   result.events.forEach { /* processar eventos */ }
   ```

5. **Avance efeitos no tempo (quando seu modelo de turno exigir):**
   ```kotlin
   state.tick(enemy)
   val events = state.drainEvents() // pega EffectRemoved se algum expirou
   ```

O core é completamente agnóstico a regras de jogo: fórmulas de dano, chance de acerto, mitigação e aplicação de dano são
todas estratégias injetáveis. O `CombatState` centraliza o estado mutável e os eventos, permitindo integração fácil com
sistemas de turno (como o `flturns` anterior).