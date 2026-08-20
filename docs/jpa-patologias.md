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

## Status

| # | Patologia | Estado | Evidência |
|---|---|---|---|
| 1 | N+1 na listagem | provada e corrigida — 52 → **1** | `CorrecaoNMaisUmIT`, `ContagemDeQueriesIT` |
| 2 | `LazyInitializationException` | provada na fronteira do serviço | `ContagemDeQueriesIT` |
| 3 | Update fantasma (dirty checking) | provada | `ReservaServiceIT.cancelarGravaSemChamarSave` |
| 4 | Autoinvocação de `@Transactional` | provada e corrigida | `TransacaoIT` |
| 5 | Checked exception sem rollback | provada e corrigida | `TransacaoIT` |
| 6 | TOCTOU na verificação de sobreposição | provada e fechada no banco | `ConcorrenciaReservaIT` + `V2` |
| — | Ordem de flush do Hibernate | descoberta acidental | `ContagemDeQueriesIT` (comentário) |
| 7 | Entidade em `HashSet` com proxy | pendente | — |
| 9 | Índice e plano de execução | pendente | — |
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

## 3. Update fantasma — dirty checking sem `save()`

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
