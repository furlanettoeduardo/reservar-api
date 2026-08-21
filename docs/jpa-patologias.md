# Patologias de JPA, Hibernate e transações — medidas neste repositório

Cada item aqui foi **reproduzido em teste executável** contra Postgres real antes de ser
corrigido, ou deliberadamente deixado sem correção com o motivo registrado. Nenhum número
neste documento é estimativa: todos saem de `Statistics` do Hibernate, contagem por SQL direto
ou tipo de exceção observado.

## Método

Três regras que emergiram do trabalho, na ordem em que doeram:

**1. Medir antes de corrigir.** A correção esconde a evidência. O N+1 tem baseline publicado
(52 queries) porque o "depois" só vale com o "antes"; o TOCTOU foi reproduzido com estado
inválido gravado antes de a constraint entrar.

**2. Estabelecer a pré-condição de ausência.** `assertThat(x).isNull()` antes do `persist` é o
que transforma "o campo está preenchido depois" em prova de que foi o banco que preencheu. Sem
isso, a hipótese alternativa — a aplicação preencheu — segue viva.

**3. Asserte o que o teste controla, registre o resto.** Um teste que aceita dois desfechos e
um teste que exige um desfecho que não governa falham pelo mesmo motivo: a asserção não
corresponde ao escopo de controle do teste. Frouxa demais num caso, apertada demais no outro,
mesma origem.

**4. Cobertura de mutação só atesta o que os mutadores expressam.** O Pitest matou 12 de 12
mutantes em `Espaco.calcularValor` sem gerar um único que alterasse o `RoundingMode` — não existe
mutador para constante de enum. O teste que fechou esse vazio de verdade moveu o score em zero.
Mesma família da regra 3: a ferramenta responde a pergunta que ela sabe fazer, não a que você
tem.

## Status

| # | Patologia | Estado | Evidência |
|---|---|---|---|
| 1 | N+1 na listagem | provada e corrigida — 52 → **1** | `CorrecaoNMaisUmIT`, `ContagemDeQueriesIT` |
| 2 | `LazyInitializationException` | provada na fronteira do serviço | `ContagemDeQueriesIT` |
| 3 | Update fantasma e lost update | provadas e corrigidas — `V3` | `ReservaServiceIT`, `AtualizacaoPerdidaIT` |
| 4 | Autoinvocação de `@Transactional` | provada e corrigida | `TransacaoIT` |
| 5 | Checked exception sem rollback | provada e corrigida | `TransacaoIT` |
| 6 | TOCTOU na verificação de sobreposição | provada e fechada no banco | `ConcorrenciaReservaIT` + `V2` |
| — | Ordem de flush do Hibernate | descoberta acidental | `ContagemDeQueriesIT` (comentário) |
| 7 | Entidade em `HashSet` com proxy | medida | `ContratoDeHashCodeTests`, `ProxyEmHashSetIT` |
| 8 | `BigDecimal.equals` vs `compareTo` | provada e corrigida | `EscalaDecimalIT` |
| 9 | Índice e plano de execução | medida — B-tree **não** é redundante | `PlanoDeExecucaoIT` |
| 10 | Paginação com `JOIN FETCH` de coleção | **não se aplica** | não há coleção — [ADR 0001](adr/0001-relacionamento-unidirecional.md) |

---

## 1. N+1 na listagem — 52 queries viraram 1

**Mecanismo.** Os dois `@ManyToOne` são `LAZY`. O mapeamento para DTO toca
`reserva.getEspaco().getNome()` e `reserva.getCliente().getNome()`, e cada proxy não
inicializado custa um `SELECT`.

**Medição do baseline.** 50 reservas: **52 queries** — 1 listagem + 1 espaço + 50 clientes.

O interessante não é o número, é por que não é 101: as 50 reservas compartilham o espaço, cujo
proxy inicializa uma vez e acerta o cache de primeiro nível nas outras 49. Os clientes são
distintos e custam um `SELECT` cada.

> **O N+1 escala com a cardinalidade dos alvos distintos, não com o tamanho da lista.**

Consequência prática: um benchmark com dados pouco diversos mede o cache, não o N+1. Um teste
com 50 reservas do mesmo cliente concluiria "2 queries" e a produção mostraria o contrário. O
cenário semeia 50 clientes distintos de propósito.

**Armadilha de medição uma camada acima.** A medição usa `@SpringBootTest` **sem**
`@Transactional`. Dentro da transação de um `@DataJpaTest` os 50 clientes já estariam no
persistence context e a listagem devolveria 1 query — um "antes" falso, contra o qual qualquer
correção pareceria inútil.

### As três correções, medidas

| Abordagem | Queries | Escopo | Elimina o N+1? |
|---|---|---|---|
| nenhuma (baseline) | **52** | — | — |
| `join fetch` na JPQL | **1** | por consulta | sim |
| `@EntityGraph` | **1** | por consulta | sim |
| `default_batch_fetch_size = 25` | **4** | global | não, agrupa |

**`join fetch` e `@EntityGraph` custam o mesmo** — há teste comparando as duas contagens
diretamente, para que a escolha entre elas seja reconhecida como decisão de organização de
código e não de performance.

**Adotado: `@EntityGraph`.** O critério é separação de responsabilidade: *o que selecionar* é
semântica da consulta, *o que carregar junto* é necessidade do caso de uso. Com o graph, a
cláusula `where` existe num lugar só e cada chamador escolhe seu plano de fetch; com `join
fetch` na JPQL, cada plano duplicaria a condição — e condição duplicada sai de sincronia.

Na prática ficaram dois métodos sobre a mesma derivação: um sem plano de fetch, para quem só
precisa das colunas da própria reserva e não deve pagar dois joins, e um com o graph, usado pela
listagem.

**`default_batch_fetch_size` não é a mesma coisa que as outras duas**, e não é substituto. Ela
não elimina as cargas: agrupa as N em lotes de até `size` identificadores, com
`where id in (...)`. Com 50 clientes distintos e lote de 25, as 50 queries viram 2, e o total
vai a 4 no mesmo método de repositório que custa 52 sem a propriedade. O papel dela é outro:
**não exige saber de antemão quais associações o caso de uso vai tocar**, então serve como rede
global para o N+1 que ninguém previu. Plano de fetch explícito onde se sabe; batch fetch para o
resto.

### `optional = false` se pagou uma terceira vez

O SQL do plano de fetch saiu com `join`, não `left join`:

```sql
from reserva r1_0
  join cliente c1_0 on c1_0.id=r1_0.cliente_id
  join espaco  e1_0 on e1_0.id=r1_0.espaco_id
```

Como a FK é declarada obrigatória no mapeamento, o Hibernate sabe que nenhuma linha se perde no
inner join e dispensa o outer. Três benefícios da mesma anotação: o `LAZY` funciona de fato, o
mapeamento espelha o `NOT NULL`, e o plano de fetch usa inner join.

### Por que a armadilha clássica do `join fetch` não aparece aqui

`join fetch` de **coleção** com paginação faz o Hibernate trazer tudo e paginar em memória
(`HHH000104`). Não acontece neste repositório porque as associações são to-one e não existe
`@OneToMany` — decisão do [ADR 0001](adr/0001-relacionamento-unidirecional.md), tomada por outro
motivo. É a razão pela qual a patologia nº 10 não se aplica.

### O teste falhando foi o que documentou a correção

`ContagemDeQueriesIT` travava a listagem em 52 com igualdade exata. Quando o `@EntityGraph`
entrou, esse teste quebrou — e atualizá-lo para `1` é o commit que registra a correção. A
asserção agora é guarda de regressão: se alguém remover o plano de fetch, o número volta a
crescer com a cardinalidade dos clientes.

O caminho ingênuo continua medido em 52 em `CorrecaoNMaisUmIT`, para o "antes" não virar
folclore.

**Baseline complementar.** A criação de uma reserva custa **4 queries** (espaço + cliente +
verificação + insert), fixado antes de a `V2` entrar, para que o custo de um eventual lock
pessimista apareça como diferença medida e não como impressão. Não mudou com esta correção.

---

## 2. `LazyInitializationException` — o motivo estrutural do mapeamento no serviço

**Mecanismo.** Com `open-in-view: false`, o persistence context fecha ao fim da transação do
serviço. Entidade devolvida ao chamador tem proxies mortos.

```java
List<Reserva> reservas = transacao.execute(status -> repositorio.findBy...);

assertThatThrownBy(() -> reservas.getFirst().getCliente().getNome())
        .isInstanceOf(LazyInitializationException.class);
```

**Consequência de design.** É por isso que `ReservaResponse.de(...)` roda **dentro** do
serviço: é o único lugar onde os proxies ainda inicializam. Serializar entidade direto também
publicaria o modelo interno como contrato de API, mas o motivo mecânico vem antes do
arquitetural.

**Onde mais isso morde.** `toString()` de entidade que toca associações dispara queries dentro
do logging. O `toString` de `Reserva` não toca em `espaco` nem `cliente` por causa disso — e o
sintoma, quando acontece, é tempo gasto em lugar nenhum no profiler.

---

## 3. Update fantasma e lost update — dirty checking e `UPDATE` cego

**Mecanismo.** Dentro da transação, mudar o objeto é suficiente: o dirty checking compara o
estado atual com o snapshot da carga e emite o `UPDATE` no flush.

```java
@Transactional
public void cancelar(Long reservaId) {
    Reserva reserva = reservaRepository.findById(reservaId).orElseThrow(...);
    reserva.cancelar();     // sem save()
}
```

O teste limpa o persistence context e relê, em vez de reler o objeto que ele mesmo alterou em
memória — senão provaria apenas que a atribuição funcionou.

**Achado colateral: o `UPDATE` é cego.** Mudar um campo reescreve todos:

```sql
update reserva set cliente_id=?, espaco_id=?, fim=?, inicio=?, status=?, valor_total=?
where id=?
```

Duas leituras. `criado_em` **não** está no `SET` — o `updatable = false` provado por ausência.
E seis colunas reescritas para uma alteração: é o comportamento padrão, que reusa o mesmo SQL
preparado para qualquer mudança na entidade. `@DynamicUpdate` muda isso ao custo de perder esse
reuso.

**O risco real do `UPDATE` cego** não é performance, é lost update: duas requisições editando
campos **diferentes** da mesma linha não conflitam logicamente, e mesmo assim uma apaga a outra,
porque ambas reescrevem as seis colunas. Sem `@Version`, o último gravador ganha, em silêncio.
É a mesma classe do `total++` sem sincronização — a região crítica é a linha inteira em vez de
um `int`.

### O lost update, medido em três estados

A afirmação acima — duas edições de campos diferentes e uma apaga a outra — ficou raciocinada e
não medida por um tempo, num documento cuja primeira regra é medir antes de corrigir. `V3`
adicionou `@Version`, e o teste mudou de lado no processo:

```
antes da V3:   falhas=0 | nome='Sala Azul'           | capacidade=99  <- rename perdido, em silêncio
depois da V3:  falhas=1 | nome='Sala Azul Reformada' | capacidade=30  <- conflito reportado
V3 + retry:    falhas=0 | nome='Sala Azul Reformada' | capacidade=99  <- as duas sobreviveram
```

**Antes: `falhas=0`.** Nenhuma das duas transações falhou. As duas "deram certo", e uma edição
desapareceu. A segunda a commitar reescreveu todas as colunas a partir do snapshot que ela
carregou, sobrescrevendo o campo da primeira com o valor velho.

**Depois:** o `UPDATE` ganhou o predicado de versão, afetou 0 linhas, e o Hibernate reclamou:

```
ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0)
[update espaco set capacidade=?, nome=?, preco_hora=?, versao=? where id=? and versao=?]
```

### O que `@Version` não faz

O estado final do segundo cenário é **o mesmo** do primeiro: uma edição só. Compare as duas
primeiras linhas da tabela — muda qual sobreviveu, não quantas.

> `@Version` não preserva as duas escritas. Ele troca **perda silenciosa** por **conflito
> reportado**.

Preservar as duas é trabalho do retry, que usa a informação que o `@Version` passou a dar:
recarregar em transação nova — já com a alteração da outra thread visível — e reaplicar a sua. O
terceiro cenário faz isso e as duas edições coexistem.

É a distinção entre detectar e resolver, e ela costuma ser colapsada numa só.

### E a `V3` moveu a falha para outra camada, de novo

`ObjectOptimisticLockingFailureException` descende de `TransientDataAccessException` — a mesma
família que o handler já capturava por causa do deadlock da `EXCLUDE`. Então o lock otimista
começou a cair num handler cuja propriedade dizia `detectadoPor: "deadlock"`, o que passou a
estar errado.

Correção: o rótulo virou `"contencao"`, que descreve a família em vez de um membro. Terceira
ocorrência do padrão, e a primeira em que a camada afetada era um **rótulo de contrato** e não um
status code — o tipo de erro que nenhum teste de status code pega.

### Nota de migration

```sql
ALTER TABLE espaco ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;
```

O `DEFAULT 0` não é estilo. `ADD COLUMN ... NOT NULL` sem default falha em tabela com linhas — e
essa é exatamente a classe de erro que schema vazio nunca pega, junto com `UNIQUE` sobre
duplicatas e `CHECK` sobre linhas fora do range. Migration é sobre dados que já existem.


---

## 4. Autoinvocação de `@Transactional`

**Mecanismo.** `@Transactional` funciona por proxy. Chamada que não sai do objeto não passa
pelo interceptor, e a anotação não produz nada — sem aviso.

| Chamada | Transação ativa | Nome da transação |
|---|---|---|
| de fora (pelo proxy) | `true` | `...anotado` |
| `this.anotado()` | **`false`** | `null` |
| `this.observaEmTransacaoNova()` com `REQUIRES_NEW` | `true` | `...pedeTransacaoNovaViaThis` — a de **fora** |
| a mesma, pelo proxy | `true` | `...observaEmTransacaoNova` |

A terceira linha é a mais traiçoeira: **existe** transação, então
`isActualTransactionActive()` diria `true` e esconderia o problema. O nome é o que prova que
nenhuma transação nova foi aberta.

**A consequência que custa dado.** O padrão "gravo a auditoria em `REQUIRES_NEW` para ela
sobreviver ao rollback": via `this`, a auditoria entra na mesma transação e some junto. Mesmo
código, mesma anotação, mesma exceção — só muda por onde a chamada passou. A patologia não dá
erro, dá silêncio: o registro que existia para explicar a falha desaparece exatamente quando a
falha acontece.

**Nota.** `@Transactional` em método não-público é ignorado em silêncio. É variante da mesma
patologia.

---

## 5. Checked exception não dispara rollback

**Mecanismo.** O rollback padrão do Spring cobre `RuntimeException` e `Error`. Checked
exception commita.

| Cenário | Linha gravada sobrevive? |
|---|---|
| `@Transactional` + checked propagada | **sim** — commitou apesar de ter falhado |
| `@Transactional` + `RuntimeException` | não |
| `@Transactional(rollbackFor = ...)` + checked | não — a correção |
| checked capturada **dentro** do método | sim — e está **correto** |

**Duas condições para o problema existir**: a exceção precisa atravessar a fronteira
transacional, e algo precisa ter sido gravado antes dela.

O quarto caso está no teste de propósito. Sem ele, a leitura fácil é "checked exception é
perigosa" e a reação é espalhar `rollbackFor = Exception.class` em tudo. Com ele fica claro que
o problema não é a família da exceção — é a exceção cruzando a fronteira depois de uma
gravação.

---

## 6. TOCTOU na verificação de sobreposição

**Mecanismo.** `ReservaService.criar` verifica e depois grava. Sob `READ_COMMITTED` (default do
Postgres, conferido em teste) a segunda transação não enxerga a linha ainda não commitada da
primeira: as duas verificam, as duas veem livre, as duas gravam.

| | rejeitadas | confirmadas | pares sobrepostos |
|---|---|---|---|
| antes da `V2` | 0 | 2 | **1** — estado inválido gravado |
| depois da `V2` | 1 | 1 | 0 |

**Correção: no dado, não no código.**

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE reserva
    ADD CONSTRAINT reserva_sem_sobreposicao
        EXCLUDE USING gist (
            espaco_id WITH =,
            tstzrange(inicio, fim, '[)') WITH &&
        ) WHERE (status = 'CONFIRMADA');
```

`btree_gist` porque a constraint mistura `=` em `bigint` com `&&` em intervalo, e o GiST nativo
não tem operator class para igualdade em tipo escalar. O `'[)'` repete a semântica de intervalo
meio-aberto do domínio; o `WHERE` parcial repete a regra de que reserva cancelada não ocupa.

**A verificação em Java continua perdendo a corrida, deliberadamente.** Ela é caminho rápido —
`409` com mensagem útil no caso comum. A constraint é a garantia, e vale também para import
manual, script de carga ou um segundo serviço. Lock otimista e pessimista defenderiam um caminho
de código; constraint defende o dado.

**O banco recusa de duas formas, e só uma é forçável.**

| Escalonamento | Erro | Exceção | Ramo |
|---|---|---|---|
| Uma commita antes de a outra gravar | `exclusion_violation` | `DataIntegrityViolationException` | **Non**Transient |
| As duas gravam ao mesmo tempo | `deadlock detected` **ou** o de cima | `CannotAcquireLockException` ou o de cima | Transient ou não |

O deadlock existe porque cada `INSERT` grava a tupla e **depois** checa a exclusão: cada
transação encontra a tupla não-commitada da outra e espera por ela. Isso é diferente de `UNIQUE`
em B-tree, onde o segundo insert bloqueia sem gravar e sai com violação limpa.

**Como forçar a corrida.** A barreira que solta as duas threads entre a verificação e a
gravação mora num `Proxy.newProxyInstance` sobre a interface do repositório, registrado como
`@Primary` numa `@TestConfiguration` aninhada — não num spy do Mockito.

> `StubbedInvocationMatcher.answer()` é `synchronized`. Com o spy, as duas threads serializam
> dentro do próprio Mockito e nunca chegam juntas à barreira: a primeira versão do teste
> registrou `rejeitadas=2, confirmadas=0` — o lock do Mockito, não o do banco. Lido de forma
> ingênua, isso diz "o TOCTOU não acontece", com evidência aparentemente sólida.
>
> **Ferramenta de teste com estado compartilhado interno não serve para testar concorrência.**
> Vale para qualquer spy, proxy de logging ou coletor de métricas inserido num teste de corrida.
> E a variante silenciosa é pior: um mock que serializa sem dar timeout transforma teste de
> corrida em teste sequencial que passa.

---

## 7. Entidade em `HashSet` — os dois extremos do contrato de `hashCode`

### As duas pontas não falham do mesmo jeito

Isto costuma ser dito errado, inclusive por mim antes de medir:

| Receita | Resposta | Custo |
|---|---|---|
| `equals` sobrescrito, `hashCode` esquecido (hash de identidade) | **errada** — `contains` devolve `false` | nenhum: 0 comparações |
| `hashCode` constante | certa | **2049 comparações por busca** com n=4096 |
| `hashCode` derivado do estado | certa | 1 comparação, em qualquer n |

Hash disperso demais quebra **correção**: objetos iguais por `equals` caem em buckets
diferentes, o bucket consultado está vazio, e a busca nem chega a comparar. Resposta errada,
rápido.

Hash constante quebra **desempenho**: encontra sempre, varrendo a estrutura de colisão. Resposta
certa, devagar.

Medido somando as N buscas num conjunto de N, com contador de chamadas a `equals`:

```
4096 buscas em conjunto de 4096:
  hash de valor    ->     4.096 comparações (1,0 por busca)
  hash constante   -> 8.394.751 comparações (2049,5 por busca, 2049x mais)
```

8,4 milhões é ordem de N²/2 — N buscas em O(n). Somar todas as buscas em vez de cronometrar
uma foi o que tornou a medida estável, e a razão está na nota de método abaixo.

### A árvore rubro-negra, e uma armadilha de asserção que quase passou

Com mais de 8 colisões no mesmo bucket, o `HashMap` converte a lista em árvore rubro-negra.
Ordenar a árvore exige chaves `Comparable`, e entidade não é — então o `HashMap` desempata por
`System.identityHashCode`, e a forma da árvore depende de onde os objetos caíram na heap.

Cinco conjuntos do mesmo tamanho, buscando um elemento cada:

```
n=512  -> [164, 294, 286]
n=4096 -> [1486, 3974, 621]   e depois [888, 2874, 2327, 3611, 198]
```

**As faixas se sobrepõem**: 621 em n=4096 é menor que 294 em n=512. A primeira versão do teste
assertava "o maior n custa mais" e passou — por sorte. Terceira vez que a mesma armadilha
apareceu nesta trilha, e a primeira em que foi vista antes de o CI reclamar: bastou olhar os
números em vez do checkmark verde.

Detalhe que corrigiu a explicação: os cinco valores **repetem** entre execuções nesta JVM,
porque o hash de identidade do HotSpot vem de um PRNG com semente determinística e a ordem de
alocação é a mesma. Reprodutível aqui não é o mesmo que controlado pelo teste — outra JVM, outro
GC ou outra ordem de alocação muda os números.

### O proxy, e por que a receita mais divulgada quebra

| | valor |
|---|---|
| `proxy.getClass()` | subclasse gerada, **≠** `Espaco.class` |
| `proxy instanceof Espaco` | `true` |
| `proxy.getClass().hashCode()` | **≠** `Espaco.class.hashCode()` |
| `proxy.hashCode()` (constante de classe) | **=** `entidade.hashCode()` |

A receita que circula mais — `hashCode()` devolvendo `getClass().hashCode()` — colocaria proxy e
entidade em **buckets diferentes**. `contains()` devolveria `false` com um `equals` perfeitamente
correto, porque o `equals` nunca chega a ser chamado. É a mesma falha do hash de identidade,
disfarçada de boa prática.

Por isso as entidades deste repositório usam `instanceof` no `equals` (a subclasse *é* um
`Espaco`) e uma constante de classe no `hashCode`.

### O custo escondido: `contains(proxy)` dispara um `SELECT`

Achado que não estava no plano do experimento:

```
[proxy] contains(proxy) -> 1 query, inicializado=true
```

`HashSet` precisa de `hashCode` para calcular o bucket, e o proxy só responde `hashCode` depois
de inicializar. **Uma coleção de entidades inicializa proxies em silêncio** — é o N+1 da
patologia nº 1 entrando por outra porta, e não aparece em nenhuma leitura do código, só na
contagem de queries.

### Por que o `hashCode` não pode derivar do id

Dentro de uma transação, o id vai de `null` para um valor quando o `INSERT` sai. Hash derivado do
id mudaria de bucket nesse instante, e a entidade **sumiria de qualquer conjunto em que já
estivesse** — encontrável por iteração, invisível por `contains`. Há teste: entidade adicionada
ao `HashSet` antes do `save` continua sendo encontrada depois.

É esse requisito que força a constante, e a constante é o que custa as 2049 comparações. **O
preço não é acidente, é consequência**: identidade que muda ao longo do ciclo de vida do objeto
é incompatível com `hashCode` estável e disperso ao mesmo tempo.

### Consequência prática

Não colocar entidade JPA em `HashSet` ou `HashMap` quando o conjunto puder crescer. As alternativas,
em ordem de preferência:

1. Coleção de **ids** (`Set<Long>`) — hash de valor, O(1), sem proxy para inicializar.
2. Value object extraído (como `Periodo`), quando o que interessa é o estado e não a identidade.
3. `List` com busca linear explícita, se o conjunto é pequeno — pelo menos o custo fica visível
   no código em vez de escondido atrás de um `Set`.

Neste repositório o caso não aparece em produção, porque não há coleção de entidades em lugar
nenhum — consequência do [ADR 0001](adr/0001-relacionamento-unidirecional.md), de novo.

### Nota de método

A asserção final compara **ordens de grandeza** e não valores: 1 comparação por busca contra
centenas, com a soma das N buscas em vez de uma amostra. O crescimento com n fica registrado no
log, não em asserção, porque depende da forma de uma árvore que o teste não controla.

Terceira aplicação da regra 3, e a primeira preventiva.

---

## 8. `BigDecimal.equals` compara escala

Este não é exemplo fabricado. O bug existiu aqui e foi encontrado por `curl`, não por teste: o
mesmo recurso serializava `300.00` na resposta do POST e `300.0000` na listagem.

```
POST /reservas             -> "valorTotal": 300.00
GET  /espacos/1/reservas   -> "valorTotal": 300.0000
```

No POST o `BigDecimal` vem recém-calculado pelo domínio, em escala 2. Na listagem vem lido da
coluna `NUMERIC(19,4)`, em escala 4. Mesmo valor, contrato inconsistente.

| Comparação | Resultado |
|---|---|
| `new BigDecimal("300.00").equals(new BigDecimal("300.0000"))` | `false` |
| `compareTo` | `0` |
| `hashCode` | **diferentes** — buckets diferentes num `HashMap` |

### Por que 48 testes não viram

`isEqualByComparingTo` é a asserção **correta** para regra de negócio, exatamente para não
tropeçar em escala. E é cega para escala por construção. A defesa contra a pegadinha foi o que
impediu de ver a pegadinha aparecer no contrato HTTP.

> **A asserção tolerante ao detalhe irrelevante para o domínio é cega ao detalhe relevante para
> o contrato.**

Para regra de negócio, `300.00 == 300.0000`. Para um cliente HTTP comparando string, ou um
snapshot test, não. São contratos diferentes e precisam de asserções diferentes.

Havia um segundo motivo estrutural: os testes de controller usavam serviço mockado com escala
fixa, então **os dois caminhos nunca se encontravam**. Cada camada isolada estava correta e a
inconsistência vivia na costura — a limitação de teste de fatia, e o argumento a favor de ter
algum caminho ponta a ponta. No caso, foi manual.

### A nº 8 quebrando o contrato da nº 7

Onde as duas se encontram, e só apareceu quando o domínio ganhou `hashCode` derivado do estado na
refatoração hexagonal:

```java
// Espaco.hashCode()
return Objects.hash(id, nome, capacidade,
        precoHora == null ? null : precoHora.stripTrailingZeros());
```

O `equals` compara preço com `compareTo`, então ignora escala — dois espaços com `150.00` e
`150.0000` são iguais. Mas `BigDecimal.hashCode` **distingue** escala. Sem o
`stripTrailingZeros()`, o mesmo espaço calculado em memória e lido do banco cairia em buckets
diferentes: dois objetos `equals` com hashes diferentes, que é a violação de contrato da patologia
nº 7 — provocada pela nº 8.

Não é hipótese: `ProxyEmHashSetIT.oHashDoDominioIgnoraEscalaDoPreco` assere os dois lados.

### A correção e o teste que a guarda

A normalização mora no DTO, não na entidade: a entidade deve espelhar a coluna, a fronteira do
contrato é o lugar de fixar escala.

```java
reserva.getValorTotal().setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP)
```

O teste de regressão compara os dois caminhos com **igualdade estrita** — `isEqualTo`, não
`isEqualByComparingTo`. Usar a asserção tolerante aqui deixaria o bug passar de novo, porque é
precisamente a tolerância que o esconde.

E `HALF_UP`, não `HALF_EVEN`: é valor cobrado, não estatística. Banker's rounding existe para
não enviesar somas grandes, e não é o caso.

---

## 9. Índice e plano de execução — o B-tree não é redundante

O experimento não é "criar o índice e medir": `idx_reserva_espaco_periodo` existe desde a `V1`, e
criar-e-medir exigiria fingir que ele não estava lá. É **remover e medir**.

E a pergunta ficou melhor depois da `V2`: a `EXCLUDE` criou um índice GiST sobre
`(espaco_id, tstzrange(inicio, fim, '[)'))`, que aparentemente cobre a mesma consulta. Então —
**o B-tree ainda se paga, ou é índice redundante ocupando espaço e custando escrita em cada
`INSERT`?**

40.000 reservas em 200 espaços, `ANALYZE` antes de medir. Com 50 linhas o planejador escolheria
Seq Scan de qualquer jeito, porque a tabela cabe em poucas páginas.

| Predicado | Índices disponíveis | Plano | Tempo | Buffers | Linhas descartadas |
|---|---|---|---|---|---|
| escalar | B-tree + GiST | **Index Scan** no B-tree | **0,204 ms** | **15** | 0 |
| escalar | só GiST | Seq Scan | 2,623 ms | 455 | 39.998 |
| intervalo (`&&`) | só GiST | Bitmap Heap Scan no GiST | 3,605 ms | 406 | 398 |
| escalar | nenhum | Seq Scan | 2,065 ms | 455 | 39.998 |

**Resposta: o B-tree se paga, com folga — 10× mais rápido e 30× menos buffers.** A hipótese da
redundância está errada, e o motivo é o que interessa.

### Por que o GiST não serviu para o predicado escalar

O índice da `EXCLUDE` indexa uma **expressão**, `tstzrange(inicio, fim, '[)')`. A consulta da
regra de sobreposição usa comparação escalar — `inicio < :fim and fim > :inicio` — e comparação
escalar não casa com índice de expressão. O planejador nem considerou o GiST: caiu em Seq Scan,
com o mesmo custo de não haver índice nenhum (2,623 vs 2,065 ms, dentro do ruído).

> **Índice de expressão só serve predicado escrito naquela expressão.** Ter o índice não basta;
> a consulta precisa mencioná-lo.

### E quando a consulta é reescrita para casar com o índice

Reescrevendo o predicado como sobreposição de intervalo, o GiST passa a ser usado — e fica
**mais lento que o Seq Scan**. O plano diz por quê:

```
Bitmap Index Scan on reserva_sem_sobreposicao
  Index Cond: (tstzrange(inicio, fim, '[)') && '[...]'::tstzrange)
  rows=400
Bitmap Heap Scan
  Filter: (espaco_id = 1)
  Rows Removed by Filter: 398
```

O GiST usou **só a parte de intervalo**, não o `espaco_id = 1`. Devolveu 400 candidatos — todas
as reservas que cruzam aquela janela de 2h, em todos os 200 espaços — e descartou 398 no heap.
Leu 400 linhas para devolver 2.

O B-tree `(espaco_id, inicio, fim)` vai direto às 2 linhas, porque a igualdade em `espaco_id` é o
primeiro termo e é altamente seletiva: 1 espaço entre 200. É exatamente o caso em que B-tree
composto ganha de GiST multicoluna.

### Conclusão prática

Manter os dois índices, e não por inércia:

- O **B-tree** serve a consulta da regra, que é o caminho quente de toda criação de reserva.
- O **GiST** existe para a `EXCLUDE` funcionar, não para acelerar leitura. É o preço da garantia
  de integridade, e o experimento mostra que ele não substitui o outro.

Reescrever a JPQL para usar `&&` e dispensar o B-tree seria pior em desempenho **e** exigiria
query nativa, porque JPQL não expressa `tstzrange(...) &&` sem função customizada do Hibernate.
Duas razões independentes para não fazer.

### Nota de método

A única asserção sobre forma de plano é que **sem índice nenhum o plano é Seq Scan** — aí o
planejador não tem alternativa, e isso o teste controla. As outras três formas são escolha dele:
ficam registradas no log e não assertadas. Mesma regra 3 que corrigiu o teste de concorrência.

O teste derruba objetos de schema que os outros ITs dependem, e o container é compartilhado —
então é um método só, com restauração em `finally`.

---

## Ordem de flush do Hibernate

Descoberta por acidente, ao limpar e ressemear dados na mesma transação:

```
ERROR: duplicate key value violates unique constraint "cliente_email_key"
```

O Hibernate ordena o flush **por tipo de operação** — inserts, updates, deletes — independente
da ordem em que os métodos foram chamados. Os clientes novos tentavam entrar antes de os antigos
saírem.

Prima do lost update: nos dois casos, a ordem que você escreveu não é a ordem que o banco vê, e
o compilador não ajuda em nenhum.

---

## Dois padrões meta

Estes não são patologias de JPA. São padrões de como correções falham, e são a parte mais
transferível do bloco.

### A correção de um invariante move a falha para outra camada

A `V2` fechou o buraco de integridade e **abriu um na API no mesmo movimento**:
`CannotAcquireLockException` não descende de `DataIntegrityViolationException` — as duas são
`DataAccessException` por ramos diferentes — e sem handler próprio o deadlock virava `500`.

Integridade garantida, API devolvendo erro de servidor para um conflito de negócio. Só apareceu
porque o teste existia **antes** da correção.

Consequência operacional: falha que muda de camada **aparece na sua suíte**, se a suíte cobrir
a outra camada.

### A correção move a falha para outro ambiente

O teste que forçava as duas gravações simultâneas assertava `CannotAcquireLockException` e
passou **três vezes seguidas** localmente. Quebrou no primeiro CI, com
`DataIntegrityViolationException`.

O deadlock exige que ambos os `INSERT`s gravem a tupla antes de qualquer um checar a exclusão, e
essa janela é **interna ao Postgres** — não existe ponto de sincronização acessível ao cliente.
A barreira sincroniza a verificação; entre a barreira e o commit é corrida livre. O runner do
CI, com menos núcleos, escalona diferente.

Três amostras de uma distribuição enviesada pelo hardware, e a conclusão errada é
indistinguível da certa até o ambiente mudar. Este é o modo de falha padrão de teste de
concorrência.

Consequência operacional, e é pior que a anterior: falha que muda de ambiente **não aparece em
lugar nenhum** até você rodar lá. E o sintoma é uma etiqueta — "flaky, roda de novo" — atrás da
qual o bug real sobrevive indefinidamente.

**A correção certa não era outra técnica de teste.** Era ajustar a asserção ao escopo de
controle: o invariante (uma recusada, uma confirmada, zero pares, e a recusa vindo do banco e
não da regra) é determinístico nos dois escalonamentos. O mecanismo vai para o log.

E o handler passou a capturar a família `TransientDataAccessException` em vez da subclasse
específica. O CI não demonstrou "existem outros membros da família" — demonstrou algo mais
forte: **qual membro chega depende do ambiente**, o que torna capturar a instância específica
errado por construção, não apenas incompleto. O sintoma seria `500` em produção sob carga, no
ambiente que menos se parece com um laptop.

### Os dois casos de "só num ambiente", lado a lado

| Bug | Ambiente | Como foi achado |
|---|---|---|
| `mvnw` com modo `100644` no índice | só Linux | leitura — previsível |
| Asserção de deadlock | só CI | execução — não previsível |

Um dava para prever, o outro não. É o argumento inteiro a favor de rodar Testcontainers no CI
em vez de deixar os testes de container para a máquina local.
