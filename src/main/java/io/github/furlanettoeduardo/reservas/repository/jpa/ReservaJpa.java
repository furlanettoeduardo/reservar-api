package io.github.furlanettoeduardo.reservas.repository.jpa;

import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
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
 * Mapeamento da tabela {@code reserva}.
 *
 * <p>Os dois {@code @ManyToOne} continuam {@code LAZY} com {@code optional = false}, e continua
 * valendo tudo que foi medido no 1B: o default EAGER traria as duas associacoes em join sempre,
 * e sem {@code optional = false} o LAZY degrada porque o Hibernate consultaria a linha so para
 * decidir entre null e proxy.
 *
 * <p>O que mudou: <b>o proxy nunca sai desta camada</b>. O mapeador materializa espaco e cliente
 * ao converter para dominio, entao {@code LazyInitializationException} deixa de ser possivel
 * acima daqui. Em troca, o mapeamento sempre toca as duas associacoes -- o que antes era um N+1
 * acidental virou uma carga obrigatoria, e e por isso que o plano de fetch deixou de ser
 * otimizacao e passou a ser requisito.
 */
@Entity
@Table(name = "reserva")
public class ReservaJpa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "espaco_id", nullable = false)
    private EspacoJpa espaco;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id", nullable = false)
    private ClienteJpa cliente;

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

    @Version
    @Column(name = "versao", nullable = false)
    private Long versao;

    protected ReservaJpa() {
    }

    public static ReservaJpa de(Reserva reserva, EspacoJpa espaco, ClienteJpa cliente) {
        ReservaJpa jpa = new ReservaJpa();
        jpa.id = reserva.getId();
        jpa.espaco = espaco;
        jpa.cliente = cliente;
        jpa.aplicar(reserva);
        return jpa;
    }

    /**
     * Copia o estado mutavel do dominio. Nao copia espaco nem cliente: reserva nao troca de
     * espaco nem de cliente, e permitir isso aqui abriria um caminho de escrita que o dominio
     * nao oferece.
     */
    public void aplicar(Reserva reserva) {
        this.inicio = reserva.getInicio();
        this.fim = reserva.getFim();
        this.status = reserva.getStatus();
        this.valorTotal = reserva.getValorTotal();
    }

    /** Toca espaco e cliente: e aqui que os proxies morrem, de proposito e num lugar so. */
    public Reserva paraDominio() {
        return new Reserva(id, espaco.paraDominio(), cliente.paraDominio(),
                new Periodo(inicio, fim), status, valorTotal, criadoEm);
    }

    public Long getId() {
        return id;
    }

    /** Devolve o proxy sem inicializar. Usado por ProxyEmHashSetIT, que mede justamente isso. */
    public EspacoJpa getEspaco() {
        return espaco;
    }

    public ClienteJpa getCliente() {
        return cliente;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReservaJpa outra)) {
            return false;
        }
        return id != null && id.equals(outra.getId());
    }

    @Override
    public int hashCode() {
        return ReservaJpa.class.hashCode();
    }

    @Override
    public String toString() {
        return "ReservaJpa{id=%d, inicio=%s, fim=%s, status=%s}"
                .formatted(id, inicio, fim, status);
    }
}
