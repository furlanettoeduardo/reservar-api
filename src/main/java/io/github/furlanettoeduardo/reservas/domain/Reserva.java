package io.github.furlanettoeduardo.reservas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reserva de um espaco por um cliente num periodo. Mapeia a tabela {@code reserva}.
 *
 * <p>Relacionamentos <b>unidirecionais</b>: nem {@link Espaco} nem {@link Cliente} tem a
 * colecao inversa. Ver a nota em {@link #espaco}.
 *
 * <p>{@code @Version} entrou na V3, depois de a falha de concorrencia ser medida -- primeiro o
 * TOCTOU da verificacao de sobreposicao, fechado pela EXCLUDE constraint da V2, e depois o lost
 * update do UPDATE cego, em AtualizacaoPerdidaIT. Adiciona-lo antes teria mascarado as duas
 * evidencias.
 */
@Entity
@Table(name = "reserva")
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * LAZY explicito: o default de {@code @ManyToOne} e EAGER, que faz todo carregamento de
     * reserva trazer o espaco junto num join, mesmo quando ninguem vai olhar para ele.
     *
     * <p>{@code optional = false} espelha o NOT NULL da FK e permite ao Hibernate devolver um
     * proxy sem checar existencia no banco -- sem isso, o LAZY em to-one degrada para EAGER,
     * porque ele precisaria consultar a linha so para saber se deve devolver null.
     *
     * <p>Sem {@code @OneToMany} do outro lado: uma colecao em Espaco significa que carregar um
     * espaco pode carregar todas as reservas dele, sem paginacao e crescendo para sempre.
     * Quem precisa das reservas de um espaco quer um recorte ("as do mes que vem", "as que
     * conflitam com este periodo"), e isso e uma query no repositorio, nao um campo.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "espaco_id", nullable = false)
    private Espaco espaco;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @Column(name = "inicio", nullable = false)
    private Instant inicio;

    @Column(name = "fim", nullable = false)
    private Instant fim;

    /** STRING, nunca ORDINAL. A migration declarou VARCHAR(20), entao so STRING valida. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusReserva status;

    @Column(name = "valor_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal valorTotal;

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

    protected Reserva() {
    }

    private Reserva(Espaco espaco, Cliente cliente, Periodo periodo, BigDecimal valorTotal) {
        this.espaco = espaco;
        this.cliente = cliente;
        this.inicio = periodo.inicio();
        this.fim = periodo.fim();
        this.valorTotal = valorTotal;
        this.status = StatusReserva.CONFIRMADA;
    }

    /**
     * Unica porta de entrada. O construtor e privado porque {@code valorTotal} e <b>derivado</b>
     * de espaco + periodo -- se ele fosse parametro, existiria caminho para gravar uma reserva
     * com valor que nao corresponde a tarifa, e nenhuma constraint do banco pegaria isso.
     */
    public static Reserva nova(Espaco espaco, Cliente cliente, Periodo periodo) {
        return new Reserva(espaco, cliente, periodo, espaco.calcularValor(periodo));
    }

    public void cancelar() {
        this.status = StatusReserva.CANCELADA;
    }

    public Long getId() {
        return id;
    }

    public Espaco getEspaco() {
        return espaco;
    }

    public Cliente getCliente() {
        return cliente;
    }

    public Periodo getPeriodo() {
        return new Periodo(inicio, fim);
    }

    public Instant getInicio() {
        return inicio;
    }

    public Instant getFim() {
        return fim;
    }

    public StatusReserva getStatus() {
        return status;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Reserva outra)) {
            return false;
        }
        return id != null && id.equals(outra.getId());
    }

    @Override
    public int hashCode() {
        return Reserva.class.hashCode();
    }

    /** Nao toca em espaco/cliente: getter de proxy dispara SELECT, e toString em log e comum. */
    @Override
    public String toString() {
        return "Reserva{id=%d, inicio=%s, fim=%s, status=%s, valorTotal=%s}"
                .formatted(id, inicio, fim, status, valorTotal);
    }
}
