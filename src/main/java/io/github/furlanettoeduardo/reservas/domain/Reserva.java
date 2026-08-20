package io.github.furlanettoeduardo.reservas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Reserva de um espaco por um cliente num periodo. Sem framework.
 *
 * <p><b>Aqui esta a decisao mais consequente da refatoracao.</b> Antes, {@code cancelar()}
 * mudava o objeto e o Hibernate emitia o UPDATE sozinho, por dirty checking -- o servico nao
 * chamava {@code save()}. Isso funcionava porque o objeto que o dominio mudava <b>era</b> o
 * objeto que o Hibernate observava.
 *
 * <p>Com a separacao, nao e mais. Mudar esta classe nao produz UPDATE nenhum, porque nada
 * observa este objeto. Duas saidas eram possiveis:
 *
 * <ol>
 *   <li>Manter mutabilidade e o adaptador detectar o que mudou -- ou seja, recriar em Java o
 *       dirty checking que o Hibernate ja faz.
 *   <li><b>Tornar o dominio imutavel</b> e o servico gravar explicitamente: {@code cancelar()}
 *       devolve uma reserva nova, e {@code reservas.salvar(...)} e o que persiste.
 * </ol>
 *
 * <p>Escolhida a segunda. Perde-se conveniencia -- uma chamada a mais no servico. Ganha-se que
 * <b>a gravacao fica visivel no codigo</b>: a patologia n.3 do 1B, o update fantasma, deixa de
 * ser possivel por construcao, porque nao existe mais o caminho em que alterar um objeto grava
 * no banco sem ninguem escrever isso.
 *
 * <p>O lado desconfortavel esta no ADR 0004: sem dirty checking, o adaptador grava todas as
 * colunas sempre, sem saber quais mudaram. O Hibernate tambem fazia isso -- o UPDATE cego da
 * mesma patologia n.3 -- entao na pratica nao piorou. Mas agora e escolha nossa, e nao mais um
 * default herdado que dava para trocar com {@code @DynamicUpdate}.
 */
public final class Reserva {

    private final Long id;
    private final Espaco espaco;
    private final Cliente cliente;
    private final Periodo periodo;
    private final StatusReserva status;
    private final BigDecimal valorTotal;
    private final Instant criadoEm;

    public Reserva(Long id, Espaco espaco, Cliente cliente, Periodo periodo,
                   StatusReserva status, BigDecimal valorTotal, Instant criadoEm) {
        this.id = id;
        this.espaco = espaco;
        this.cliente = cliente;
        this.periodo = periodo;
        this.status = status;
        this.valorTotal = valorTotal;
        this.criadoEm = criadoEm;
    }

    /**
     * Unica porta de entrada para reserva nova. {@code valorTotal} e <b>derivado</b> de espaco e
     * periodo -- se fosse parametro, existiria caminho para gravar uma reserva com valor que nao
     * corresponde a tarifa, e nenhuma constraint do banco pegaria isso.
     */
    public static Reserva nova(Espaco espaco, Cliente cliente, Periodo periodo) {
        return new Reserva(null, espaco, cliente, periodo, StatusReserva.CONFIRMADA,
                espaco.calcularValor(periodo), null);
    }

    /** Devolve uma reserva cancelada. Quem persiste e o servico, chamando salvar(). */
    public Reserva cancelar() {
        return new Reserva(id, espaco, cliente, periodo, StatusReserva.CANCELADA, valorTotal,
                criadoEm);
    }

    public boolean estaConfirmada() {
        return status == StatusReserva.CONFIRMADA;
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
        return periodo;
    }

    public Instant getInicio() {
        return periodo.inicio();
    }

    public Instant getFim() {
        return periodo.fim();
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
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Reserva outra = (Reserva) o;
        return Objects.equals(id, outra.id)
                && Objects.equals(espaco, outra.espaco)
                && Objects.equals(cliente, outra.cliente)
                && Objects.equals(periodo, outra.periodo)
                && status == outra.status
                && valorTotal.compareTo(outra.valorTotal) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, espaco, cliente, periodo, status,
                valorTotal == null ? null : valorTotal.stripTrailingZeros());
    }

    /**
     * Agora pode tocar espaco e cliente sem risco: nao ha proxy, entao nao ha SELECT escondido
     * dentro de um {@code log.info}. Era a armadilha que forcava este toString a omiti-los.
     */
    @Override
    public String toString() {
        return "Reserva{id=%d, espaco='%s', cliente='%s', periodo=%s, status=%s, valorTotal=%s}"
                .formatted(id, espaco.getNome(), cliente.getNome(), periodo, status, valorTotal);
    }
}
