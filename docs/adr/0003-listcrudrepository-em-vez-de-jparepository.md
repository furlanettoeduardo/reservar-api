# ADR 0003 — `ListCrudRepository` em vez de `JpaRepository`

## Status

Aceito — Bloco 1A.

## Contexto

`JpaRepository` é o default de fato em projetos Spring Data JPA. Ele expõe cerca de vinte
métodos por herança, e a escolha costuma ser feita por hábito e não por análise.

## Decisão

As três interfaces estendem `ListCrudRepository<T, Long>`.

## Consequências

**O que se descarta em relação a `JpaRepository`, e por quê.**

- `flush()` / `saveAndFlush()` — quando o flush acontece é decisão do `EntityManager` dentro
  da transação, não do chamador. Expor isso convida o serviço a forçar flush "para garantir"
  e depois depender da ordem resultante.
- `deleteAllInBatch()` — apaga a tabela por DML direto, ignorando cascade e callbacks. Fica
  a uma tecla de distância de `deleteAll()` em qualquer autocompletar.
- `getReferenceById()` — devolve proxy. Útil, mas só para quem sabe o que isso significa; e
  neste domínio não serve, porque criar reserva precisa do `precoHora` real do espaço.
- Sobrecargas de `Page` — não usadas hoje.

**O que se ganha em relação a `CrudRepository` puro.** Retornos `List` em vez de `Iterable`,
que é o que a camada de serviço precisa para mapear com `stream()`. É a diferença inteira
entre os dois.

**Quando revisar.** Quando a listagem crescer a ponto de precisar de paginação, entra
`ListPagingAndSortingRepository` ao lado — traz `Page`/`Sort` sem arrastar o resto do
`JpaRepository` junto.
