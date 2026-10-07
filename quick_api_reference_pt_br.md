# FLCombat — Sumário de Referência Autocontido

## 1. Identidade e propósito

**Package:** `com.lucasalfare.flcombat`

**Natureza:** biblioteca de núcleo de combate, independente de interface, renderização, input, engine gráfica, relógio
de parede ou modelo específico de jogo.

**Princípio arquitetural central:**

> O jogo fornece significado. O core fornece estrutura.

A FLCombat fornece a estrutura necessária para representar estado de combate, atributos, modificadores, itens, efeitos,
cálculo de dano, mitigação, aplicação de dano, resolução de ações e eventos.

O core **não conhece semântica específica de jogo** como:

* HP;
* defesa;
* ataque físico ou mágico como conceito próprio;
* chance de acerto;
* resistência;
* morte;
* crítico;
* armadura;
* habilidades;
* feitiços;
* recursos;
* jogadores;
* inimigos;
* vitória ou derrota.

Esses significados devem ser fornecidos pelo consumidor por meio das abstrações extensíveis da biblioteca.

A biblioteca separa explicitamente:

**estado → definição de dano → sorteio → mitigação → aplicação → eventos**

---

# 2. Modelo estrutural geral

A FLCombat pode ser compreendida por seis áreas principais:

### Identidade e estado

`Agent → CombatState → Attribute / Modifier / Item / Effect`

### Composição de atributos

`Attribute + Modifier → ModifierComposer → effectiveValue`

### Produção de dano

`DamageContext → DamageFormula → DamageRange`

### Escolha do dano concreto

`DamageRange → DamageRoll → rolledDamage`

### Transformação do dano

`MitigationContext → Mitigation → mitigatedDamage`

### Execução da ação

`CombatAction → HitResolution → CombatResolver → DamageApplication → CombatResult`

Eventos atravessam o sistema de forma independente:

`CombatState / CombatResolver → CombatEvent → consumidor`

---

# 3. `Agent`

**Tipo:** `data class`

Representa a identidade estável de um participante do combate.

### Propriedade pública

`val id: String`

O `id` é a identidade do agente.

### Semântica

`Agent` não representa:

* atributos;
* vida;
* inventário;
* efeitos;
* estado mutável;
* comportamento.

Ele é apenas a identidade usada pelo estado de combate para associar dados a uma entidade.

A igualdade é estrutural, como em qualquer `data class`, portanto dois `Agent` com o mesmo `id` são considerados iguais.

### Papel arquitetural

`Agent` é a chave fundamental de `CombatState`.

---

# 4. `Attribute`

**Tipo:** `data class`

Representa um snapshot imutável de um atributo de um agente.

### Propriedades públicas

`val id: String`

Identificador do atributo.

`val baseValue: Int`

Valor-base armazenado no estado.

`val effectiveValue: Int`

Valor efetivo obtido após a composição dos modificadores.

### Semântica

`Attribute` é uma fotografia do estado naquele momento.

`effectiveValue` não é uma referência viva que será automaticamente atualizada.

A composição do valor efetivo é responsabilidade de `ModifierComposer`.

### Relação

`CombatState.attribute(...)` produz um `Attribute` a partir de:

* valor-base;
* modificadores diretos;
* modificadores provenientes de itens;
* modificadores provenientes de efeitos;
* `ModifierComposer`.

---

# 5. `Modifier`

**Tipo:** `data class`

Representa um modificador semântico associado a uma origem de estado.

### Propriedades públicas

`val id: String`

Identificador semântico do modificador.

`val value: Int`

Valor numérico do modificador.

### Semântica

O core não interpreta arbitrariamente o significado de `id`.

Exemplo conceitual:

* `"additive"`
* `"percentage"`
* `"strength"`
* `"armor"`
* `"fire-resistance"`

Todos são apenas identificadores até que uma implementação de `ModifierComposer` decida tratá-los de alguma forma.

### Igualdade e remoção

Por ser `data class`, a igualdade é estrutural.

`CombatState.removeModifier(...)` remove a **primeira ocorrência estruturalmente igual** entre os modificadores diretos.

Duplicatas são permitidas.

---

# 6. `Item`

**Tipo:** `data class`

Representa um objeto pertencente a um agente que fornece modificadores.

### Propriedades públicas

`val id: String`

Identidade do item dentro do inventário do agente.

`val modifiers: Map<String, List<Modifier>>`

Mapa de modificadores agrupados por atributo.

Valor padrão:

`emptyMap()`

### Semântica

`Item` não possui comportamento próprio.

Ele é apenas uma fonte de modificadores.

O significado desses modificadores pertence ao consumidor.

### Regra de identidade

Itens são armazenados por `id`.

Adicionar outro item com o mesmo `id` **substitui o item existente**.

---

# 7. `Effect`

**Tipo:** `data class`

Representa um efeito aplicado a um agente.

### Propriedades públicas

`val id: String`

Identidade do efeito.

`val modifiers: Map<String, List<Modifier>>`

Modificadores fornecidos pelo efeito.

`val durationTicks: Int?`

Duração:

* `null` = permanente;
* `0` = expira no primeiro `tick`;
* valor positivo = número de ticks restantes inicialmente.

`val onApply: (Agent, CombatState) -> Unit`

Callback executado quando o efeito é instalado.

Valor padrão: callback vazio.

`val onRemove: (Agent, CombatState) -> Unit`

Callback executado quando o efeito é removido.

Valor padrão: callback vazio.

### Validação

`durationTicks < 0` gera `IllegalArgumentException`.

### Substituição

Aplicar um efeito com `id` já existente não cria dois efeitos.

O efeito antigo é substituído.

A substituição segue esta ordem:

1. `onRemove` do efeito antigo;
2. emissão de `CombatEvent.EffectRemoved`;
3. instalação do novo efeito;
4. `onApply` do novo efeito;
5. emissão de `CombatEvent.EffectApplied`.

### Callbacks

Callbacks são síncronos.

Eles recebem acesso ao `CombatState` real e podem mutar o estado.

Portanto, callbacks fazem parte do comportamento de execução do combate, embora o core não imponha uma semântica
específica para eles.

---

# 8. `ModifierComposer`

**Tipo:** `fun interface`

Responsável por determinar como um valor-base e seus modificadores produzem um valor efetivo.

### Método público

`fun compose(baseValue: Int, modifiers: Collection<Modifier>): Int`

### Contrato

É o único ponto do core em que a semântica de composição de modificadores é decidida.

O compositor recebe apenas:

* valor-base;
* coleção de modificadores.

Ele não recebe:

* `Agent`;
* `CombatState`;
* id do atributo.

Isso mantém a composição isolada da identidade e do restante do estado.

### Extensibilidade

Consumidores podem fornecer sua própria implementação ou lambda.

---

# 9. `AdditiveThenPercentageComposer`

**Tipo:** `object`

Implementação padrão de `ModifierComposer`.

### Constantes públicas

`ADDITIVE = "additive"`

`PERCENTAGE = "percentage"`

### Regra

Todos os modificadores `additive` são somados.

Todos os modificadores `percentage` são somados.

A fórmula é:

`floor((base + additive) * (100 + percentage) / 100)`

### Características

Modificadores desconhecidos são ignorados.

Não existe clamp automático para zero.

Portanto um resultado efetivo pode ser negativo se os modificadores produzirem tal resultado.

### Ordem conceitual

1. base;
2. aditivos;
3. percentual sobre o resultado anterior.

---

# 10. `CombatState`

**Tipo:** `class`

É o estado autoritativo e mutável de um único combate.

### Construtor

Recebe opcionalmente um `ModifierComposer`.

Padrão:

`AdditiveThenPercentageComposer`

### Estado mantido internamente

O `CombatState` mantém:

* conjunto de agentes registrados;
* valores-base de atributos;
* modificadores diretos;
* itens;
* efeitos ativos;
* estado de derrota;
* fila de eventos pendentes.

Os efeitos temporários mantêm internamente seus ticks restantes.

### Características globais

O estado é:

* síncrono;
* mutável;
* não thread-safe;
* sem sincronização interna;
* sem uso de relógio de parede;
* sem aleatoriedade.

Toda passagem de tempo ocorre explicitamente por `tick`.

---

# 11. Registro de agentes

### `register(agent)`

Registra um agente.

A operação é idempotente.

Registrar o mesmo agente novamente não produz duplicação.

### `contains(agent): Boolean`

Indica se o agente está registrado.

### `agents(): Set<Agent>`

Retorna uma cópia defensiva do conjunto de agentes.

Modificar o conjunto retornado não altera o estado interno.

### Regra geral

A maior parte das operações mutáveis que atuam sobre um agente exige que ele esteja registrado.

Essas operações lançam `IllegalArgumentException` quando aplicável.

---

# 12. Derrota

### `markDefeated(agent)`

Marca um agente como derrotado.

O agente precisa estar registrado.

A operação é idempotente.

Apenas a primeira transição para derrotado gera:

`CombatEvent.Defeat`

### `isDefeated(agent): Boolean`

Retorna `true` apenas quando o agente foi efetivamente marcado como derrotado.

Agente não registrado retorna `false`.

### Importante

O core possui o conceito estrutural de “defeated”, mas **não define o que faz um agente ser derrotado**.

Isso continua pertencendo ao consumidor.

---

# 13. Atributos em `CombatState`

### `setAttribute(agent, id, baseValue)`

Cria ou substitui o valor-base do atributo.

A operação:

* altera o valor-base;
* preserva modificadores existentes;
* preserva itens;
* preserva efeitos.

### `attribute(agent, id): Attribute?`

Retorna o snapshot do atributo.

Retorna `null` quando não há valor-base registrado para aquele `id`.

### `attributes(agent): Set<Attribute>`

Retorna todos os atributos que possuem valor-base.

A coleção é defensiva.

### `removeAttribute(agent, id)`

Remove:

* o valor-base;
* os modificadores diretos daquele atributo.

Modificadores provenientes de itens e efeitos permanecem armazenados.

Como consequência, eles deixam de participar de uma composição porque não existe mais um valor-base correspondente.

---

# 14. Modificadores diretos

### `addModifier(agent, attributeId, modifier)`

Adiciona um modificador diretamente ao atributo.

Duplicatas são permitidas.

### `removeModifier(agent, attributeId, modifier)`

Remove a primeira ocorrência estruturalmente igual entre os modificadores diretos.

É no-op quando:

* o agente não está registrado;
* o atributo não existe;
* o modificador não foi encontrado.

### `modifiers(agent, attributeId): List<Modifier>`

Retorna todos os modificadores aplicáveis ao atributo.

A ordem lógica dos grupos é:

1. modificadores diretos;
2. modificadores de itens;
3. modificadores de efeitos.

Itens e efeitos são achatados na ordem de iteração de seus respectivos armazenamentos.

A lista retornada é uma cópia defensiva.

A ordem interna específica dessas estruturas não é um contrato de ordenação global.

---

# 15. Itens em `CombatState`

### `addItem(agent, item)`

Adiciona ou substitui um item.

A identidade do item é `item.id`.

Se já existir item com o mesmo id, ele é substituído.

### `removeItem(agent, itemId)`

Remove o item identificado pelo id.

Ausência não gera erro.

### `items(agent): Set<Item>`

Retorna os itens do agente.

É uma cópia defensiva.

Para agente inexistente, retorna coleção vazia.

---

# 16. Efeitos em `CombatState`

### `applyEffect(agent, effect)`

Aplica um efeito ao agente.

Se já existir efeito com o mesmo id, ocorre substituição seguindo o ciclo:

`onRemove → EffectRemoved → novo efeito → onApply → EffectApplied`

### `removeEffect(agent, effectId): Effect?`

Remove um efeito.

Executa seu `onRemove`.

Emite `EffectRemoved`.

Retorna o efeito removido.

Retorna `null` quando não existe.

### `effects(agent): Set<Effect>`

Retorna os efeitos atualmente aplicados.

É uma cópia defensiva.

Para agente inexistente, retorna coleção vazia.

### `effect(agent, effectId): Effect?`

Retorna um efeito específico.

### `remainingTicks(agent, effectId): Int?`

Retorna os ticks restantes para efeitos temporários.

Retorna `null` quando:

* o efeito não existe;
* o efeito é permanente.

---

# 17. Evolução temporal de efeitos

### `tick(agent)`

Avança um tick dos efeitos temporários do agente.

Não usa:

* `Clock`;
* `Instant`;
* threads;
* timers;
* tempo real.

A duração é puramente lógica.

### Regras

Efeitos permanentes são ignorados.

Para efeitos temporários:

* `remainingTicks > 0` é decrementado;
* quando chega a `0`, o efeito é removido;
* `onRemove` é executado;
* `EffectRemoved` é emitido.

Um efeito criado com duração `0` permanece instalado até o primeiro `tick`, momento em que expira.

### No-op

Para agente não registrado ou sem efeitos, `tick` não executa nada.

---

# 18. Eventos pendentes

### `drainEvents(): List<CombatEvent>`

Retorna todos os eventos atualmente pendentes em sua ordem de emissão.

Após a operação, a fila interna é limpa.

A lista retornada representa um snapshot e não expõe a fila interna.

### Objetivo

Permitir que:

* mutações de estado produzam eventos;
* o consumidor consuma esses eventos;
* `CombatResolver` incorpore eventos de estado ao resultado de uma resolução.

---

# 19. `RandomSource`

**Tipo:** `fun interface`

Abstração de fonte de números aleatórios.

### Método

`nextInt(fromInclusive, toInclusive): Int`

Os limites são **inclusivos**.

### Contrato

Se `fromInclusive > toInclusive`, a operação deve falhar.

A abstração existe para desacoplar as regras do combate da implementação de aleatoriedade.

---

# 20. `DefaultRandomSource`

**Tipo:** `object`

Implementação padrão baseada em `Random.Default`.

### Semântica especial

Quando os limites são iguais, retorna diretamente o único valor possível sem consumir aleatoriedade.

Caso contrário, produz um inteiro uniforme dentro do intervalo inclusivo.

---

# 21. `DeterministicRandomSource`

**Tipo:** `class`

Fonte determinística para testes e cenários reproduzíveis.

### Construtor principal

Recebe um `Iterator<Int>`.

### Construtor secundário

Aceita:

`vararg values: Int`

Os valores são consumidos na ordem em que foram fornecidos.

### Validação

Cada valor consumido precisa estar dentro do intervalo solicitado.

Caso contrário:

`IllegalArgumentException`

Se não houver mais valores configurados:

`NoSuchElementException`

### Finalidade

Permite testar aleatoriedade sem depender de RNG real.

---

# 22. `DamageRange`

**Tipo:** `data class`

Representa a faixa possível de dano.

### Propriedades

`val min: Int`

`val max: Int`

### Contratos

`min >= 0`

`min <= max`

Os limites são inclusivos.

`DamageRange` descreve possibilidades; não representa necessariamente o dano que será aplicado.

---

# 23. `DamageContext`

**Tipo:** `data class`

Contexto necessário para calcular dano.

### Propriedades

`val attacker: Agent`

`val target: Agent`

`val state: CombatState`

### Papel

Fornece à fórmula:

* origem;
* alvo;
* estado atual.

### Contrato

A fórmula de dano deve tratar esse contexto como leitura.

Ela não deve mutar o estado.

---

# 24. `DamageFormula`

**Tipo:** `fun interface`

Determina a faixa possível de dano de uma ação.

### Método

`calculate(context: DamageContext): DamageRange`

### Semântica

A fórmula determina:

**quanto dano pode existir**

Ela não determina o valor concreto sorteado.

Ela não deve:

* sortear aleatoriedade;
* mutar o estado.

A aleatoriedade pertence a `DamageRoll`.

---

# 25. `FixedDamageFormula`

**Tipo:** `class`

Implementação de dano fixo.

### Construtor

Recebe `damage: Int`.

`damage >= 0`.

### Regra

Ignora o contexto e retorna:

`DamageRange(damage, damage)`

Logo, o único dano possível é exatamente o valor configurado.

---

# 26. `DamageRoll`

**Tipo:** `fun interface`

Transforma uma faixa de dano em um dano concreto.

### Método

`roll(range: DamageRange): Int`

### Responsabilidade

Escolher o valor final dentro da faixa.

Não decide:

* fórmula de dano;
* mitigação;
* aplicação;
* mudança de estado.

---

# 27. `DeterministicDamageRoll`

**Tipo:** `object`

Implementação determinística.

Sempre retorna:

`range.min`

### Finalidade

Útil para:

* testes;
* cenários reproduzíveis;
* exemplos;
* simulações determinísticas.

---

# 28. `UniformDamageRoll`

**Tipo:** `class`

Implementação uniforme de `DamageRoll`.

### Dependência

Recebe um `RandomSource`.

### Regra

Escolhe uniformemente um valor dentro do intervalo inclusivo de `DamageRange`.

### Arquitetura

A implementação não depende diretamente de `Random.Default`.

Isso permite trocar a origem da aleatoriedade.

---

# 29. `MitigationContext`

**Tipo:** `data class`

Contexto de mitigação.

### Propriedades

`val origin: Agent`

Origem do dano.

`val target: Agent`

Alvo.

`val damageType: String`

Tipo semântico do dano.

`val state: CombatState`

Estado atual.

### Papel

Permite que uma mitigação tome decisões baseadas no contexto sem acoplar o core à semântica de atributos específicos.

---

# 30. `Mitigation`

**Tipo:** `fun interface`

Responsável por transformar o dano rolado.

### Método

`mitigate(rolledDamage: Int, context: MitigationContext): Int`

### Contrato

Recebe o dano concreto e produz dano mitigado.

A saída deve ser não negativa.

### Semântica

Mitigação é uma transformação.

Ela não é responsável pela aplicação da mudança no estado.

---

# 31. `NoMitigation`

**Tipo:** `object`

Não modifica o dano.

Retorna:

`rolledDamage.coerceAtLeast(0)`

Funciona como estratégia neutra.

---

# 32. `FixedReductionMitigation`

**Tipo:** `class`

Reduz uma quantidade fixa de dano.

### Construtor

`reduction >= 0`

### Regra

`max(rolledDamage - reduction, 0)`

Nunca produz dano negativo.

---

# 33. `PercentageReductionMitigation`

**Tipo:** `class`

Aplica redução percentual.

### Construtor

`percentage >= 0`

### Regra

O dano final é calculado como:

`floor(rolledDamage * max(100 - percentage, 0) / 100)`

### Consequências

Percentuais maiores que `100` equivalem efetivamente a redução total.

O resultado nunca é negativo.

---

# 34. `MitigationChain`

**Tipo:** `class`

Compõe múltiplas estratégias `Mitigation`.

### Construtor principal

Recebe uma `List<Mitigation>`.

### Construtor secundário

Aceita `vararg Mitigation`.

### Regra

As mitigações são aplicadas na ordem fornecida.

O resultado de uma entra como entrada da próxima.

Cada etapa é clampada para valor não negativo.

### Cadeia vazia

Uma cadeia vazia se comporta como `NoMitigation`.

### Importância

A ordem das mitigações é significativa.

Exemplo conceitual:

`redução fixa → redução percentual`

não é necessariamente equivalente a:

`redução percentual → redução fixa`

---

# 35. `DamageApplicationContext`

**Tipo:** `data class`

Contexto necessário para transformar dano mitigado em mudança no estado.

### Propriedades

`val origin: Agent`

`val target: Agent`

`val damageType: String`

`val state: CombatState`

---

# 36. `DamageApplication`

**Tipo:** `fun interface`

É o ponto central onde o dano deixa de ser apenas um valor e pode se tornar uma mutação real do estado.

### Método

`apply(mitigatedDamage: Int, context: DamageApplicationContext): Int`

### Responsabilidade

A implementação do consumidor decide o que significa aplicar dano.

Ela pode, por exemplo:

* subtrair HP;
* limitar HP em zero;
* marcar um agente como derrotado;
* alterar atributos;
* produzir efeitos;
* gerar outros eventos por meio do `CombatState`.

O core não presume nenhuma dessas regras.

### Retorno

Retorna o dano efetivamente aplicado.

Esse valor pode diferir do dano mitigado.

Exemplo conceitual:

`mitigatedDamage = 30`

mas restam somente `12` pontos de vida.

A implementação pode retornar:

`appliedDamage = 12`

---

# 37. `DamageResult`

**Tipo:** `data class`

Representa o resultado completo do pipeline de dano.

### Propriedades

`val origin: Agent`

`val target: Agent`

`val damageType: String`

`val rolledDamage: Int`

`val mitigatedDamage: Int`

`val appliedDamage: Int`

### Contratos

Todos os valores de dano devem ser `>= 0`.

### Distinção dos valores

`rolledDamage` = dano escolhido pela rolagem.

`mitigatedDamage` = dano após mitigação.

`appliedDamage` = dano realmente convertido em mutação.

Isso permite representar corretamente casos onde as três quantidades são diferentes.

---

# 38. `CombatAction`

**Tipo:** `interface`

Representa uma ação ofensiva executável.

### Propriedades

`val attacker: Agent`

`val target: Agent`

`val damageType: String`

### Extensibilidade

A interface é aberta para implementação pelo consumidor.

Isso permite representar ações como:

* ataques;
* magias;
* habilidades;
* armadilhas;
* técnicas;
* efeitos especiais.

Sem adicionar essas semânticas ao core.

### Importante

`CombatAction` não contém necessariamente:

* fórmula de dano;
* chance de acerto;
* mitigação;
* aplicação;
* lógica de estado.

Essas responsabilidades ficam separadas nas estratégias do resolver.

---

# 39. `Attack`

**Tipo:** `data class`

Implementação de conveniência de `CombatAction`.

### Propriedades

Implementa:

* `attacker`;
* `target`;
* `damageType`.

### Papel

Fornece um tipo simples para representar um ataque convencional sem obrigar o consumidor a criar uma implementação
própria.

---

# 40. `HitResolution`

**Tipo:** `fun interface`

Determina se uma ação acerta.

### Método

`resolve(action: CombatAction, state: CombatState): Boolean`

### Contrato

A resolução de acerto deve ser usada como consulta.

Ela não deve mutar o estado.

### Responsabilidade

Decidir somente:

**acertou ou errou?**

A implementação não aplica dano.

---

# 41. `AlwaysHit`

**Tipo:** `object`

Implementação de `HitResolution` que sempre retorna `true`.

Útil como estratégia padrão simples ou em testes.

---

# 42. `CombatResult`

**Tipo:** `data class`

Resultado da resolução de uma ação inteira.

### Propriedades

`val action: CombatAction`

A ação resolvida.

`val hit: Boolean`

Indica se houve acerto.

`val damageResult: DamageResult?`

Resultado do pipeline de dano.

`null` somente quando a ação errou.

`val state: CombatState`

A mesma instância de estado fornecida ao resolver, após a resolução.

`val events: List<CombatEvent>`

Eventos associados à resolução.

### Semântica

`CombatResult` é o resultado de uma operação de resolução, não uma cópia do estado.

O campo `state` aponta para o mesmo objeto mutável utilizado durante a operação.

---

# 43. `CombatResolver`

**Tipo:** `class`

Orquestrador principal da biblioteca.

### Dependências

Recebe:

`HitResolution`

`DamageFormula`

`DamageRoll`

`Mitigation`

`DamageApplication`

### Característica

O resolver é reutilizável entre diferentes `CombatState`.

Ele não mantém estado de combate próprio.

### Método principal

`resolve(action, state): CombatResult`

---

# 44. Pipeline de resolução

O pipeline completo é:

`Action`
→ `Hit`
→ `Formula`
→ `Roll`
→ `Mitigation`
→ `Application`

Mais precisamente:

### Etapa 1 — Limpeza dos eventos pendentes

Antes de começar, `CombatResolver` executa:

`state.drainEvents()`

Eventos anteriores ao início da resolução são descartados para garantir que o resultado contenha apenas eventos
pertencentes àquela resolução.

### Etapa 2 — Hit resolution

`HitResolution.resolve(...)`

Se retornar `false`, a ação é considerada um miss.

Nenhum pipeline de dano é executado.

### Etapa 3 — Miss

É produzido:

`CombatEvent.AttackMissed`

Não existe `DamageResult`.

O resultado retorna:

* `hit = false`;
* `damageResult = null`.

Como `HitResolution` não deve mutar o estado, normalmente nenhum evento de estado será produzido nessa etapa.

### Etapa 4 — Hit confirmado

Quando há acerto, o resolver registra:

`CombatEvent.AttackPerformed`

### Etapa 5 — Fórmula

Cria um `DamageContext` e chama `DamageFormula`.

Resultado:

`DamageRange`

### Etapa 6 — Roll

`DamageRoll` escolhe o valor concreto.

Resultado:

`rolledDamage`

### Etapa 7 — Mitigação

É criado um `MitigationContext`.

A estratégia `Mitigation` transforma:

`rolledDamage → mitigatedDamage`

### Etapa 8 — Aplicação

É criado um `DamageApplicationContext`.

`DamageApplication.apply(...)` transforma o dano mitigado em mudança efetiva no estado e retorna:

`appliedDamage`

### Etapa 9 — `DamageResult`

Os três valores são reunidos em:

`DamageResult`

### Etapa 10 — `DamageProduced`

O resolver adiciona:

`CombatEvent.DamageProduced(result)`

### Etapa 11 — Eventos produzidos pela aplicação

O resolver chama `state.drainEvents()`.

Isso coleta eventos produzidos durante a aplicação do dano ou pelos callbacks acionados durante as mutações.

Esses eventos são preservados na ordem em que foram emitidos.

### Etapa 12 — `DamageApplied`

Finalmente:

`CombatEvent.DamageApplied(result)`

é adicionado ao resultado.

---

# 45. Ordenação dos eventos de uma resolução

No caminho de sucesso, a estrutura geral é:

`AttackPerformed`

→ `DamageProduced`

→ eventos produzidos durante a aplicação

→ `DamageApplied`

Isso é deliberado.

Dessa forma, um evento gerado por uma mudança no estado pode aparecer **entre** a produção abstrata do dano e sua
conclusão como dano aplicado.

Exemplo conceitual:

`AttackPerformed`

`DamageProduced`

`EffectRemoved`

`Defeat`

`DamageApplied`

A biblioteca não força uma representação textual desses eventos.

O consumidor decide como reagir a eles.

---

# 46. `CombatEvent`

**Tipo:** `sealed interface`

Representa eventos estruturados ocorridos no combate.

É presentation-free.

Não contém:

* texto renderizado;
* UI;
* animação;
* áudio;
* lógica específica de jogo.

---

# 47. `CombatEvent.AttackPerformed`

**Tipo:** `data class`

### Propriedade

`val action: CombatAction`

Representa o início lógico de uma ação que acertou.

---

# 48. `CombatEvent.AttackMissed`

**Tipo:** `data class`

### Propriedade

`val action: CombatAction`

Representa uma ação que falhou na resolução de acerto.

Nenhum `DamageResult` é associado.

---

# 49. `CombatEvent.DamageProduced`

**Tipo:** `data class`

### Propriedade

`val result: DamageResult`

Indica que o pipeline determinou um resultado de dano.

Esse evento ocorre antes dos eventos produzidos pela aplicação.

---

# 50. `CombatEvent.DamageApplied`

**Tipo:** `data class`

### Propriedade

`val result: DamageResult`

Indica que a etapa de aplicação foi concluída.

`result.appliedDamage` representa o valor realmente aplicado.

---

# 51. `CombatEvent.EffectApplied`

**Tipo:** `data class`

### Propriedades

`val agent: Agent`

`val effect: Effect`

Representa a instalação efetiva de um efeito.

É produzido por `CombatState.applyEffect(...)`.

---

# 52. `CombatEvent.EffectRemoved`

**Tipo:** `data class`

### Propriedades

`val agent: Agent`

`val effect: Effect`

Representa a remoção efetiva de um efeito.

Pode ocorrer por:

* remoção explícita;
* substituição por outro efeito com o mesmo id;
* expiração por `tick`.

---

# 53. `CombatEvent.Defeat`

**Tipo:** `data class`

### Propriedade

`val agent: Agent`

Representa a primeira transição do agente para o estado de derrotado.

A emissão é idempotente: chamadas posteriores a `markDefeated` não produzem novos eventos `Defeat`.

---

# 54. Invariantes arquiteturais

A API estabelece as seguintes separações fundamentais.

### O estado não resolve combate

`CombatState` armazena e altera estado.

Ele não sabe:

* como atacar;
* como calcular dano;
* como acertar;
* como mitigar;
* como aplicar uma ação ofensiva.

### A fórmula não rola dano

`DamageFormula` produz `DamageRange`.

A concretização pertence a `DamageRoll`.

### A rolagem não mitiga

`DamageRoll` somente escolhe um valor dentro da faixa.

### A mitigação não aplica

`Mitigation` transforma o dano.

Não altera o estado.

### A aplicação é o ponto de mutação semântica do dano

`DamageApplication` é responsável por decidir como o dano afeta o estado.

### O resolver não conhece regras de jogo

`CombatResolver` apenas orquestra estratégias.

### Eventos não controlam o estado

`CombatEvent` descreve o que ocorreu.

Não é um sistema de comandos ou de mutação.

---

# 55. Imutabilidade e cópias defensivas

Os principais value objects são `data class` e imutáveis:

* `Agent`
* `Attribute`
* `Modifier`
* `Item`
* `Effect`
* `DamageRange`
* `DamageContext`
* `MitigationContext`
* `DamageApplicationContext`
* `DamageResult`
* `Attack`
* `CombatResult`
* eventos.

Coleções expostas pelo `CombatState` são retornadas como cópias defensivas quando aplicável.

O estado mutável real permanece encapsulado dentro de `CombatState`.

---

# 56. Tempo

A FLCombat não possui relógio interno.

A biblioteca não depende de:

* tempo real;
* delays;
* threads;
* schedulers;
* timers.

O tempo do combate é explícito e controlado pelo consumidor por meio de chamadas como `tick`.

Isso torna a lógica:

* determinística quando as entradas são determinísticas;
* fácil de testar;
* independente do ambiente de execução.

---

# 57. Aleatoriedade

Aleatoriedade não é embutida nas regras de dano.

Ela é injetada por:

`RandomSource`

Isso permite substituir:

* RNG real;
* RNG determinístico;
* RNG reproduzível para testes;
* outras fontes compatíveis.

O fluxo conceitual é:

`DamageFormula → DamageRange → DamageRoll → valor concreto`

Portanto a fórmula continua livre de aleatoriedade.

---

# 58. Reutilização e composição

As estratégias são independentes e combináveis.

Um consumidor pode criar, por exemplo:

`CombatResolver`

com:

* uma implementação de chance de acerto;
* uma fórmula baseada em atributos;
* uma rolagem uniforme;
* uma cadeia de mitigação;
* uma aplicação baseada em HP.

Sem modificar o core.

Da mesma forma, outro consumidor pode usar:

* acerto sempre verdadeiro;
* fórmula fixa;
* roll determinístico;
* sem mitigação;
* aplicação baseada em outro sistema completamente diferente.

---

# 59. Fluxo conceitual completo

Um combate pode ser modelado desta forma:

`CombatAction`

↓

`HitResolution`

↓

se errar:

`AttackMissed`

↓

se acertar:

`AttackPerformed`

↓

`DamageFormula`

↓

`DamageRange`

↓

`DamageRoll`

↓

`rolledDamage`

↓

`Mitigation`

↓

`mitigatedDamage`

↓

`DamageApplication`

↓

mutação de `CombatState`

↓

`DamageResult`

↓

`DamageProduced`

↓

eventos gerados pela mutação

↓

`DamageApplied`

↓

`CombatResult`

---

# 60. Responsabilidade do consumidor

A FLCombat deliberadamente deixa para a aplicação:

* definição de atributos;
* interpretação dos ids;
* fórmulas de dano;
* regras de acerto;
* regras de mitigação;
* cálculo de HP;
* aplicação de dano;
* condição real de derrota;
* efeitos de gameplay;
* interação com UI;
* animações;
* áudio;
* input;
* regras de turno;
* regras de vitória;
* apresentação dos eventos.

A biblioteca fornece somente o mecanismo necessário para conectar esses conceitos sem incorporá-los ao núcleo.

---

# 61. Filosofia final da API

A FLCombat foi estruturada para que o núcleo conheça **mecanismos**, mas não **significados de jogo**.

`Agent` diz quem participa.

`CombatState` diz o que existe.

`ModifierComposer` diz como valores são compostos.

`DamageFormula` diz qual dano é possível.

`DamageRoll` diz qual valor ocorreu.

`Mitigation` diz quanto permanece.

`DamageApplication` diz o que isso significa no estado.

`CombatResolver` coordena as etapas.

`CombatEvent` registra o que aconteceu.

Todo significado adicional pertence ao consumidor.