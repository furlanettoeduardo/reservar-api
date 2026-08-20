package io.github.furlanettoeduardo.reservas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Espaco locavel. Mapeia a tabela {@code espaco} criada em V1__cria_esquema_inicial.sql.
 *
 * <p>Classe (nao record) e nao-final: o Hibernate precisa de estado mutavel para o dirty
 * checking (compara o estado atual com o snapshot carregado no persistence context) e gera
 * proxies por subclasse para lazy loading.
 */
@Entity
@Table(name = "espaco")
public class Espaco {

    private static final BigDecimal SEGUNDOS_POR_HORA = BigDecimal.valueOf(3600);

    /** Valor cobrado arredonda no centavo; o NUMERIC(19,4) da coluna e folga, nao alvo. */
    private static final int ESCALA_MONETARIA = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nome", nullable = false, length = 150)
    private String nome;

    @Column(name = "capacidade", nullable = false)
    private Integer capacidade;

    @Column(name = "preco_hora", nullable = false, precision = 19, scale = 4)
    private BigDecimal precoHora;

    /**
     * Gerado pelo banco (DEFAULT now()). {@code @Generated(INSERT)} tira a coluna do INSERT
     * e faz o Hibernate ler o valor de volta; {@code updatable = false} garante que nenhum
     * UPDATE posterior sobrescreva a data de criacao.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    /**
     * Lock otimista. Com ele o UPDATE ganha {@code and versao = ?}, entao a segunda transacao a
     * commitar afeta 0 linhas e o Hibernate lanca OptimisticLockException -- em vez de
     * sobrescrever em silencio o campo que a outra alterou.
     *
     * <p>Nao tem setter: quem controla e o Hibernate. Entrou na V3, depois de o lost update ser
     * medido, porque adicionar antes teria escondido a evidencia.
     */
    @Version
    @Column(name = "versao", nullable = false)
    private Long versao;

    /** Exigido pelo Hibernate, que instancia por reflexao. protected = "nao chame isso". */
    protected Espaco() {
    }

    public Espaco(String nome, Integer capacidade, BigDecimal precoHora) {
        this.nome = nome;
        this.capacidade = capacidade;
        this.precoHora = precoHora;
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public Integer getCapacidade() {
        return capacidade;
    }

    public void setCapacidade(Integer capacidade) {
        this.capacidade = capacidade;
    }

    public BigDecimal getPrecoHora() {
        return precoHora;
    }

    public void setPrecoHora(BigDecimal precoHora) {
        this.precoHora = precoHora;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    /**
     * Preco do periodo. Mora aqui, e nao no servico, porque quem conhece a tarifa e o espaco --
     * assim {@code precoHora} nunca precisa sair por um getter para virar conta em outro lugar.
     *
     * <p>Multiplica <b>antes</b> de dividir. Na ordem inversa, {@code segundos / 3600} e
     * dizima periodica para qualquer duracao que nao seja multiplo de hora (10 min = 1/6), e
     * BigDecimal lanca ArithmeticException em divisao nao-exata sem escala declarada -- o erro
     * some se voce arredondar cedo, mas ai o arredondamento intermediario entra no resultado.
     */
    public BigDecimal calcularValor(Periodo periodo) {
        return precoHora
                .multiply(BigDecimal.valueOf(periodo.duracao().toSeconds()))
                .divide(SEGUNDOS_POR_HORA, ESCALA_MONETARIA, RoundingMode.HALF_UP);
    }

    /**
     * Identidade por id, nunca pelos campos de negocio. {@code instanceof} (e nao
     * {@code getClass()}) porque o proxy do Hibernate e uma subclasse; getters (e nao acesso
     * direto ao campo) porque ler o campo de um proxy nao inicializado devolve null.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Espaco outro)) {
            return false;
        }
        return id != null && id.equals(outro.getId());
    }

    /** Constante: o id muda de null para um valor no persist, o hashCode nao pode mudar junto. */
    @Override
    public int hashCode() {
        return Espaco.class.hashCode();
    }

    @Override
    public String toString() {
        return "Espaco{id=%d, nome='%s', capacidade=%d, precoHora=%s}"
                .formatted(id, nome, capacidade, precoHora);
    }
}
