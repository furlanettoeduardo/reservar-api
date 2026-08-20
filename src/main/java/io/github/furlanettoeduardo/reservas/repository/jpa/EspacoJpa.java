package io.github.furlanettoeduardo.reservas.repository.jpa;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
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
import java.time.Instant;

/**
 * Mapeamento da tabela {@code espaco}. <b>Todas</b> as restricoes que o Hibernate impoe moram
 * aqui, e nao no dominio:
 *
 * <ul>
 *   <li>classe mutavel e nao-final, para dirty checking e proxy;
 *   <li>construtor sem-args, porque o Hibernate instancia por reflexao;
 *   <li>igualdade por id com {@code instanceof} e {@code hashCode} constante, para o proxy e
 *       para sobreviver ao id aparecendo no flush -- as patologias n.7 do 1B, que agora estao
 *       confinadas a esta camada.
 * </ul>
 *
 * <p>E a troca central da refatoracao: as tres restricoes acima eram requisitos do dominio
 * porque o dominio era a entidade. Deixaram de ser.
 */
@Entity
@Table(name = "espaco")
public class EspacoJpa {

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
     * Gerado pelo banco (DEFAULT now()). {@code @Generated(INSERT)} tira a coluna do INSERT --
     * condicao para o DEFAULT disparar -- e le o valor de volta; {@code updatable = false}
     * impede que um UPDATE posterior sobrescreva a data de criacao.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    /** Lock otimista. Sem setter: quem controla e o Hibernate. */
    @Version
    @Column(name = "versao", nullable = false)
    private Long versao;

    /** Exigido pelo Hibernate, que instancia por reflexao. */
    protected EspacoJpa() {
    }

    public static EspacoJpa de(Espaco espaco) {
        EspacoJpa jpa = new EspacoJpa();
        jpa.id = espaco.getId();
        jpa.aplicar(espaco);
        return jpa;
    }

    /**
     * Copia o estado mutavel do dominio para esta instancia gerenciada. E o coracao da
     * reconciliacao: chamado sobre a instancia que o persistence context ja tem, ele deixa o
     * dirty checking e o {@code @Version} funcionarem como antes da refatoracao.
     *
     * <p>Nao copia id nem criadoEm: um e identidade, o outro e {@code updatable = false}.
     */
    public void aplicar(Espaco espaco) {
        this.nome = espaco.getNome();
        this.capacidade = espaco.getCapacidade();
        this.precoHora = espaco.getPrecoHora();
    }

    public Espaco paraDominio() {
        return new Espaco(id, nome, capacidade, precoHora, criadoEm);
    }

    public Long getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EspacoJpa outro)) {
            return false;
        }
        return id != null && id.equals(outro.getId());
    }

    @Override
    public int hashCode() {
        return EspacoJpa.class.hashCode();
    }

    /** Nao toca em associacao nenhuma: esta classe convive com proxies. */
    @Override
    public String toString() {
        return "EspacoJpa{id=%d, nome='%s'}".formatted(id, nome);
    }
}
