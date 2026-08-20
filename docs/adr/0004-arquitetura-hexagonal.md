# ADR 0004 — Portas e adaptadores: domínio separado da entidade JPA

## Status

Aceito — Bloco 1C.

## Contexto

Até aqui as entidades de domínio **eram** as entidades JPA. As anotações moviam três decisões
para dentro do domínio que não pertencem a ele, e cada uma tinha sido documentada como restrição
inevitável:

| Restrição | Motivo real |
|---|---|
| classe mutável, não-`final` | dirty checking compara com o snapshot; proxy é subclasse gerada |
| construtor sem-args acessível | o Hibernate instancia por reflexão |
| igualdade por id, `hashCode` constante | o proxy divergiria em `getClass()`; o id muda de `null` para valor no flush |

Nenhuma das três é requisito do problema de reservar salas. `Periodo` já era livre de framework, e
foi ele que mostrou que a separação era viável antes de aplicá-la às entidades.

## Decisão

Portas no domínio, adaptadores fora, entidades JPA confinadas.

```
domain/               Espaco, Cliente, Reserva, Periodo, StatusReserva   (zero framework)
domain/port/          EspacoRepositorio, ClienteRepositorio, ReservaRepositorio
service/              casos de uso, DTOs de entrada e saída
repository/           adaptadores que implementam as portas
repository/jpa/       EspacoJpa, ClienteJpa, ReservaJpa + Spring Data
web/                  controllers, ProblemDetail, CORS
```

O domínio virou **imutável**: `reserva.cancelar()` devolve uma reserva nova, e o serviço grava
explicitamente.

## O custo, medido

Isto é a parte que costuma virar nota de rodapé, e é a que decide se valeu.

| | antes | depois | delta |
|---|---|---|---|
| Produção | 1.427 linhas / 29 arquivos | 1.726 linhas / 32 arquivos | **+299 linhas, +20%** |
| Testes | 3.080 linhas | 3.142 linhas | +62 linhas |
| `mvn verify` | ~1min00 | ~1min14 | +14s |
| Erros de compilação de teste na transição | — | — | **122, em 10 arquivos** |

**+20% de código de produção** para o mesmo comportamento. Onde ele foi: três interfaces de porta
(~60 linhas), três adaptadores (~140), e três entidades JPA que duplicam os campos do domínio
(~240) — menos as ~140 linhas de anotação que saíram do domínio.

### O que ficou mais chato

**Toda leitura mapeia.** `ReservaJpa.paraDominio()` constrói três objetos onde antes havia
referência direta. Em listagem de 50 reservas são 150 alocações que não existiam.

**Escrita ficou em dois passos.** `salvar` de entidade existente faz `findById` e copia estado
sobre a instância gerenciada. No caminho medido isso não custou query nenhuma — o persistence
context já tinha o objeto — mas é código que existe para reconciliar dois modelos do mesmo dado.

**Teste de fatia precisa saber quem implementa a porta.** `@DataJpaTest` escaneia repositórios
Spring Data, não beans `@Repository` comuns, então `ReservaServiceIT` e `ReservaRepositoryIT
passaram a importar os três adaptadores explicitamente. Inversão de dependência move a decisão de
"quem implementa" para o configurador, e em teste de fatia o configurador é o teste.

**Duas representações de tudo.** Adicionar um campo agora exige tocar o domínio, a entidade JPA,
o `aplicar`, o `paraDominio` e o DTO. Cinco lugares onde antes eram dois.

## O que se ganhou, medido

**Nenhum número de desempenho mudou.** Isto era hipótese e virou medição:

```
listagem de 50 reservas    1 query      (era 1)
criação de uma reserva     4 queries    (era 4)
N+1 ingênuo               52 queries    (era 52)
TOCTOU                     invariante preservado, incluindo os dois modos de recusa
lost update                três estados preservados, incluindo o retry
```

A refatoração é neutra em custo de query. A reconciliação em `salvar` não gerou ida ao banco
porque roda dentro da transação que carregou.

**Três patologias do 1B mudaram de camada:**

| Patologia | Antes | Depois |
|---|---|---|
| nº 2 `LazyInitializationException` | possível acima do serviço | **impossível** — o mapeador materializa |
| nº 3 update fantasma | possível — mudar objeto gravava | **impossível** — sem `salvar` não grava |
| nº 7 `hashCode` constante e proxy | restrição do domínio | restrição do adaptador |

A nº 7 é a mais concreta: o domínio passou a ter `hashCode` derivado do estado, então um
`HashSet` de espaços faz **1 comparação por busca** em vez das 2049 medidas no 1B. E `getClass()`
no equals voltou a ser seguro, porque não há proxy nesta camada.

**Os oito invariantes de arquitetura passaram sem toque na refatoração.** Os quatro testes de
linha de base falharam — e um deles migrou de arquivo: a asserção de que o domínio dependia de
`jakarta.persistence` virou o invariante `dominioNaoDependeDeJpaNemDeHibernate`. A migração é o
registro de que o trabalho terminou.

## Consequências e limitações

### A reconciliação depende da transação

`salvar` de entidade existente busca a instância gerenciada e copia estado sobre ela. Isso
preserva dirty checking e `@Version` **porque a busca acontece na mesma transação que carregou** —
o persistence context devolve o mesmo objeto, e o `UPDATE` sai com a versão que a transação leu.

**Fora dessa condição, a proteção contra lost update desaparece.** Num fluxo de edição em duas
requisições — carrega numa, salva noutra — a busca lê a versão atual do banco, o `aplicar`
sobrescreve com estado velho, e o `@Version` não percebe. Para esse caso a versão teria que viajar
com o domínio ou com o comando, e a pergunta "versão é conceito de domínio?" precisaria de
resposta. Não é o caso hoje, e está registrado aqui em vez de descoberto depois.

### O plano de fetch deixou de ser otimização

Antes, o N+1 dependia de **alguém** tocar as associações — o mapeamento para DTO. Agora o
mapeador do adaptador toca as duas **sempre**, porque é isso que materializar o domínio significa.
O `@EntityGraph` virou requisito: sem ele o custo volta na hora, e não mais por acidente.

Mesmo 52, por um motivo mais forte. E é uma instância do padrão que o 1B nomeou: a correção moveu
a falha de acidental para estrutural, o que é melhor — falha estrutural não tem como passar
desapercebida.

### Perdemos o `@DynamicUpdate`

Sem dirty checking sobre o domínio, o adaptador copia todas as colunas sempre. O Hibernate também
fazia isso (o `UPDATE` cego da patologia nº 3), então na prática não piorou — mas antes havia um
botão para trocar, e agora é decisão nossa reimplementar se precisar.

## Quando isto não valeria

Registrado porque uma decisão sem condição de reversão é dogma:

- **CRUD sem regra de negócio.** Se as entidades não têm comportamento, separar produz duas
  cópias anêmicas do mesmo dado e nada mais. O que paga a separação aqui é `Periodo`,
  `calcularValor` e a regra de sobreposição.
- **Equipe de um, projeto curto, sem troca de tecnologia prevista.** As +299 linhas são custo
  imediato; o ganho é opcionalidade futura, que pode não se realizar.
- **Se a inversão nunca for exercida.** Nenhum teste substitui a implementação da porta hoje, e
  não há segundo adaptador. Enquanto isso for verdade, a camada de portas é documentação
  executável do contrato, não flexibilidade em uso.

O critério honesto: esta refatoração se paga aqui porque o domínio **tem** regra, e porque as
restrições que o JPA impunha eram visíveis e mensuráveis — não porque hexagonal seja melhor.
