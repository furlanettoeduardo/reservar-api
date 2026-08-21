# ADR 0005 — Não adotar MapStruct: mapeamento escrito à mão

## Status

Aceito — Bloco 1C. **Decisão de não adotar**, com medição.

## Contexto

O [ADR 0004](0004-arquitetura-hexagonal.md) separou domínio de entidade JPA e criou três pares de
mapeamento (`de`, `aplicar`, `paraDominio`), além do `ReservaResponse.de` que já existia. MapStruct
é a resposta padrão para esse tipo de código, e o roteiro do 1C previa adotá-lo.

A hipótese a testar: o ganho do MapStruct é proporcional à fração de mapeamento que é **cópia
campo-a-campo**. Onde há assimetria, ele precisa de `@Mapping` manual e o ganho encolhe.

O experimento foi construído de verdade — dependência, processador de anotação, mapeadores
compilando, código gerado inspecionado — e depois revertido. `unmappedTargetPolicy = ERROR` ligado,
porque é o ganho que não aparece em contagem de linhas.

## Medição

### Volume

| | linhas |
|---|---|
| Mapeamento à mão, três entidades (`de` + `aplicar` + `paraDominio`) | **36** |
| Interface MapStruct equivalente | 29 |
| Acessores adicionados nas entidades JPA **só para o gerador ler e escrever** | **94** |
| Anotação `@Default` própria + duas anotações no domínio | 19 |
| **Total MapStruct** | **142** |

**142 linhas para substituir 36.** E as 94 de acessores não são cerimônia inócua — ver abaixo.

### Fração de campos que exigiu `@Mapping` explícito

| Mapeamento | campos anotados / total |
|---|---|
| `paraDominio(ReservaJpa)` | 1 / 7 |
| `paraDominio(EspacoJpa)` | 2 / 5 |
| `paraDominio(ClienteJpa)` | 0 / 4 |
| `aplicar(Reserva, @MappingTarget ReservaJpa)` | **7 / 9** |
| **total** | **10 / 25 = 40%** |
| `ReservaResponse` (a direção DTO, teoricamente a favorável) | **7 / 9 = 78%** |

A direção DTO ser a pior é o resultado que inverte a expectativa: `ReservaResponse` é um
**achatamento** (`espaco.nome` → `espacoNome`), e achatamento é precisamente o que MapStruct
precisa que se diga campo a campo.

### O que ele fez com o `Periodo`

Nada — porque não tem como. `Periodo` é composto de duas colunas, então virou uma `expression`
com Java literal dentro de uma string de anotação:

```java
@Mapping(target = "periodo", expression = "java(new io.github.furlanettoeduardo.reservas"
        + ".domain.Periodo(jpa.getInicio(), jpa.getFim()))")
```

Java escrito dentro de string, sem verificação de tipo pelo compilador no momento da escrita, com
nome totalmente qualificado porque a expressão não vê os imports. O código gerado sai correto, mas
a fonte é pior que a linha equivalente escrita à mão.

## As três descobertas que decidiram

### 1. Ele lê `comNome` como setter

```
Unmapped target properties: "comNome, comCapacidade".
```

Os métodos *wither* do domínio imutável são métodos de um argumento, e a convenção de acessores do
MapStruct não distingue wither de setter. Com `unmappedTargetPolicy = ERROR` — a política que dá a
segurança — os dois viram **erro de compilação**, e a correção é dizer ao gerador que a
imutabilidade não é um defeito:

```java
@Mapping(target = "comNome", ignore = true)
@Mapping(target = "comCapacidade", ignore = true)
```

**A imutabilidade que o ADR 0004 comprou é exatamente o que a convenção do gerador combate.**

### 2. Ele exige alargar a superfície da entidade JPA

As entidades JPA expunham `getId()` e os métodos de mapeamento, e nada mais. MapStruct precisa ler
e escrever por acessores: 13 getters e 9 setters adicionados, 94 linhas.

Os getters são quase inócuos. **Os setters não são.** `aplicar()` existe para copiar *só* o estado
mutável, omitindo `id`, `criadoEm` e `versao` — e a omissão é uma decisão de segurança, não um
detalhe: copiar `versao` mataria o lock otimista. Escrito à mão, o método simplesmente não menciona
esses campos, e não há setter para alguém usar. Com MapStruct, os setters existem e são públicos.

### 3. O `ERROR` protege mais do que eu esperava — e a fragilidade está em outro lugar

A preocupação natural é que `@Mapping(target = "versao", ignore = true)` seja uma linha fácil de
alguém apagar "para simplificar", e o lock otimista morra em silêncio. **Medido: não é isso.** Com
`unmappedTargetPolicy = ERROR`, apagar aquela linha faz a **compilação falhar** — o alvo fica
sem mapeamento e o build para.

A fragilidade real é uma camada acima: se alguém trocar a política para `WARN` (o default), então
adicionar um campo passa a ser silencioso nas duas direções. **O que protege não é a linha, é a
política** — e a política é uma palavra numa anotação, sem teste que a defenda.

## Decisão

**Não adotar.** Mapeamento à mão, nas próprias entidades JPA.

O critério não é linhas economizadas — é **quanta regra fica implícita numa anotação**. Com 40% dos
campos anotados (78% na escrita e na direção DTO), a interface MapStruct não é mais curta que o
Java equivalente: é uma DSL diferente descrevendo o mesmo mapeamento, com menos verificação de tipo
e uma expressão Java dentro de string.

## O que se perde, e é real

**A verificação de campo esquecido.** Com `unmappedTargetPolicy = ERROR`, adicionar um campo ao
domínio e esquecer de mapear quebra a compilação. O mapeador manual não avisa: o campo
simplesmente não é copiado, e o sintoma é dado sumindo.

Isso responde parcialmente a reclamação de "cinco lugares para tocar ao adicionar um campo" do ADR
0004 — o compilador passaria a cobrar dois deles. **É o argumento mais forte a favor da adoção, e
ele não venceu os 142 contra 36.**

Mitigação escolhida: um teste que compara os campos do domínio com os que o mapeamento copia,
usando reflexão. Cobre a mesma falha, custa uma classe de teste em vez de uma dependência e 94
linhas de acessores públicos, e — diferente da política do MapStruct — é um teste, então não pode
ser desligado por uma palavra numa anotação. Fica registrado como pendência, não como feito.

## Quando isto se inverteria

- **DTOs de integração grandes e simétricos.** Trinta campos de mesmo nome e tipo entre dois
  records: aí é cópia campo-a-campo pura e MapStruct ganha por larga margem.
- **Domínio mutável com setters.** Se a decisão do ADR 0004 tivesse sido "manter mutabilidade", os
  acessores já existiriam e o custo de 94 linhas desapareceria. As duas decisões estão acopladas:
  **escolher imutabilidade foi escolher mapear à mão**, e vale saber disso.
- **Muitos pares de mapeamento.** Três pares não amortizam a dependência; trinta amortizariam.
