# ADR 0002 — JPQL explícito em vez de query derivation na consulta de sobreposição

## Status

Aceito — Bloco 1A.

## Contexto

A regra central do domínio é detectar sobreposição de períodos. Spring Data oferece dois
caminhos: derivar a query do nome do método, ou escrever JPQL com `@Query`.

O nome derivado equivalente seria:

```java
existsByEspacoIdAndStatusAndInicioLessThanAndFimGreaterThan(espacoId, status, fim, inicio)
```

## Decisão

`@Query` com JPQL e parâmetros nomeados.

```java
@Query("""
        select count(r) > 0 from Reserva r
        where r.espaco.id = :espacoId
          and r.status = :status
          and r.inicio < :fim
          and r.fim > :inicio
        """)
boolean existeSobreposicao(...)
```

O critério **não** é contagem de condições. É: *existe par de parâmetros do mesmo tipo cuja
troca não quebra nada?* Se existe, o nome derivado é perigoso, independentemente do tamanho.

## Consequências

**O argumento decisivo.** No nome derivado, o parâmetro chamado `inicioLessThan` recebe o
**fim** do período consultado, e `fimGreaterThan` recebe o **início**. São dois `Instant`
posicionais cuja ordem correta contradiz o próprio nome do método. Trocar os dois compila,
roda e devolve a resposta errada em silêncio — nenhum teste de tipo pega. Com `@Param`
nomeado o erro deixa de ser possível.

**Onde a derivação continua valendo.** `findByEspacoIdAndStatusOrderByInicioAsc` ficou
derivado: argumentos de tipos distintos, ordem inequívoca.

**Nota sobre `r.espaco.id`.** Navegar até o `id` de um `@ManyToOne` em JPQL lê a FK que já
está na linha e **não** gera join — verificado no SQL emitido. A otimização é específica do
identificador: `r.espaco.nome` geraria join, porque o nome não está na linha da reserva.

**Semântica de intervalo.** As comparações são estritas (`<`, `>`), o que implementa
intervalo meio-aberto `[inicio, fim)`: uma reserva que termina 14:00 e outra que começa
14:00 não conflitam. Coberto por teste de borda; trocar `<` por `<=` faz exatamente dois
testes falharem, e nenhum outro mudar de resposta.
