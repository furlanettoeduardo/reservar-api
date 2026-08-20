package io.github.furlanettoeduardo.reservas.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Espaco locavel. <b>Sem framework nenhum</b>: nem JPA, nem Spring, nem Jackson.
 *
 * <p>Antes da refatoracao esta classe era a entidade JPA. As anotacoes moviam duas decisoes para
 * dentro do dominio que nao pertencem a ele: como o objeto e construido (construtor sem-args
 * acessivel, campos nao-final) e como ele e identificado (igualdade por id, para o proxy
 * funcionar). O mapeamento agora vive em
 * {@code repository.jpa.EspacoJpa}, e esta classe pode ser o que o dominio precisa que ela seja.
 *
 * <p>O que mudou de concreto:
 *
 * <ul>
 *   <li><b>Campos finais.</b> Nao ha dirty checking observando o objeto, entao mutabilidade
 *       deixou de ser requisito. Mudar um espaco produz um espaco novo.
 *   <li><b>Igualdade por valor, com o id incluido.</b> Sem proxy no meio, {@code getClass()} e
 *       seguro e {@code hashCode} pode ser disperso -- as 2049 comparacoes por busca da
 *       patologia n.7 deixam de existir aqui. Elas continuam valendo para a entidade JPA.
 *   <li><b>Sem {@code criadoEm} gerado pelo banco.</b> O dominio recebe o instante; quem le do
 *       DEFAULT now() e o adaptador.
 * </ul>
 */
public final class Espaco {

    private static final BigDecimal SEGUNDOS_POR_HORA = BigDecimal.valueOf(3600);

    /** Valor cobrado arredonda no centavo; o NUMERIC(19,4) da coluna e folga, nao alvo. */
    private static final int ESCALA_MONETARIA = 2;

    private final Long id;
    private final String nome;
    private final Integer capacidade;
    private final BigDecimal precoHora;
    private final Instant criadoEm;

    public Espaco(Long id, String nome, Integer capacidade, BigDecimal precoHora,
                  Instant criadoEm) {
        this.id = id;
        this.nome = nome;
        this.capacidade = capacidade;
        this.precoHora = precoHora;
        this.criadoEm = criadoEm;
    }

    /** Espaco ainda nao persistido: sem id e sem instante de criacao. */
    public Espaco(String nome, Integer capacidade, BigDecimal precoHora) {
        this(null, nome, capacidade, precoHora, null);
    }

    /**
     * Preco do periodo. Mora aqui, e nao no servico, porque quem conhece a tarifa e o espaco --
     * assim {@code precoHora} nunca precisa sair por um getter para virar conta em outro lugar.
     *
     * <p>Multiplica <b>antes</b> de dividir. Na ordem inversa, {@code segundos / 3600} e
     * dizima periodica para qualquer duracao que nao seja multiplo de hora (10 min = 1/6), e
     * BigDecimal lanca ArithmeticException em divisao nao-exata sem escala declarada -- o erro
     * some se voce arredondar cedo, mas ai o arredondamento intermediario entra no resultado.
     *
     * <p>HALF_UP e nao HALF_EVEN: valor cobrado, nao estatistica. Ha teste com 33,33/h por
     * 30 min, que da 16,665 exato e e o unico caso que discrimina os dois modos.
     */
    public BigDecimal calcularValor(Periodo periodo) {
        return precoHora
                .multiply(BigDecimal.valueOf(periodo.duracao().toSeconds()))
                .divide(SEGUNDOS_POR_HORA, ESCALA_MONETARIA, RoundingMode.HALF_UP);
    }

    /** Renomear produz um espaco novo: o objeto e imutavel. */
    public Espaco comNome(String novoNome) {
        return new Espaco(id, novoNome, capacidade, precoHora, criadoEm);
    }

    public Espaco comCapacidade(Integer novaCapacidade) {
        return new Espaco(id, nome, novaCapacidade, precoHora, criadoEm);
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public Integer getCapacidade() {
        return capacidade;
    }

    public BigDecimal getPrecoHora() {
        return precoHora;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    /**
     * Igualdade por valor. Sem proxy do Hibernate no meio, {@code getClass()} e seguro e o
     * {@code hashCode} pode ser disperso -- as duas armadilhas da patologia n.7 sao do
     * adaptador, nao daqui.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Espaco outro = (Espaco) o;
        return java.util.Objects.equals(id, outro.id)
                && java.util.Objects.equals(nome, outro.nome)
                && java.util.Objects.equals(capacidade, outro.capacidade)
                && precoHora.compareTo(outro.precoHora) == 0;
    }

    /**
     * Note o {@code precoHora.stripTrailingZeros()}: {@code BigDecimal.hashCode} distingue
     * escala, entao 150.00 e 150.0000 -- o mesmo preco lido do banco ou calculado em memoria --
     * teriam hashes diferentes e quebrariam o contrato com o {@code compareTo} do equals. E a
     * patologia n.8 aparecendo dentro do proprio hashCode.
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, nome, capacidade,
                precoHora == null ? null : precoHora.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return "Espaco{id=%d, nome='%s', capacidade=%d, precoHora=%s}"
                .formatted(id, nome, capacidade, precoHora);
    }
}
