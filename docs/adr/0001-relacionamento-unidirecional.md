# ADR 0001 — Relacionamento unidirecional entre Reserva e Espaco/Cliente

## Status

Aceito — Bloco 1A.

## Contexto

`Reserva` referencia `Espaco` e `Cliente` por `@ManyToOne`. A pergunta é se as duas
entidades referenciadas ganham o lado inverso (`@OneToMany List<Reserva>`), o que a maioria
dos tutoriais recomenda com o argumento de "poder navegar dos dois lados".

## Decisão

Relacionamento **unidirecional**. Apenas `Reserva` conhece `Espaco` e `Cliente`; nenhuma das
duas tem coleção de reservas.

Os dois `@ManyToOne` são `fetch = FetchType.LAZY, optional = false`.

## Consequências

**A favor.** Uma coleção `@OneToMany` em `Espaco` significa que carregar um espaço pode
carregar todas as reservas dele — sem paginação, e crescendo indefinidamente com o tempo.
Nenhum caso de uso real quer *todas* as reservas de um espaço: quer um recorte ("as que
conflitam com este período", "as do mês que vem"). Recorte é query no repositório, não campo
na entidade. A navegação que o lado inverso oferece é precisamente a que produz o problema
de performance.

**Contra.** Não existe `espaco.getReservas()`. Toda consulta parte do `ReservaRepository`.
Na prática isso empurra a intenção para a assinatura do método, o que é desejável.

**Sobre `optional = false`.** É o que faz o `LAZY` funcionar de fato. Sem a garantia de
não-nulidade, o Hibernate precisaria consultar a linha só para decidir entre devolver `null`
e devolver um proxy — e consultar é exatamente o que o `LAZY` existe para evitar. Anotar
`LAZY` sem `optional = false` é a origem da crença de que "LAZY em to-one não funciona".

**Custo assumido.** Com `LAZY` nos dois lados e mapeamento para DTO, a listagem produz N+1.
Medido e registrado no README; a correção é assunto do Bloco 1B.
