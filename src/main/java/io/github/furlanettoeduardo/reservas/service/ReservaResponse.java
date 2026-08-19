package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Saida do servico. Entidade nunca cruza a fronteira: serializar {@code Reserva} publicaria o
 * modelo interno como contrato de API -- renomear um campo viraria breaking change -- e com
 * {@code open-in-view: false} nem funcionaria, porque os {@code @ManyToOne} LAZY ja estariam
 * fora da transacao na hora da serializacao.
 *
 * <p>Mora no pacote de servico, e nao no web, porque {@link #de(Reserva)} precisa rodar
 * <b>dentro</b> da transacao. O custo assumido: este record e, na pratica, o contrato HTTP.
 * Se um dia a API precisar mudar sem o dominio mudar, entra um DTO proprio no web e este vira
 * modelo de aplicacao.
 */
public record ReservaResponse(
        Long id,
        Long espacoId,
        String espacoNome,
        Long clienteId,
        String clienteNome,
        Instant inicio,
        Instant fim,
        StatusReserva status,
        BigDecimal valorTotal) {

    /** Escala fixa no centavo. Sem isso o mesmo recurso serializa diferente conforme o
     *  caminho: 300.00 na resposta do POST (BigDecimal recem-calculado, escala 2) e 300.0000
     *  na listagem (lido do NUMERIC(19,4), escala 4). Mesmo valor, contrato inconsistente. */
    private static final int ESCALA_MONETARIA = 2;

    /** Toca espaco.getNome() e cliente.getNome(): dois proxies inicializados por reserva. */
    public static ReservaResponse de(Reserva reserva) {
        return new ReservaResponse(
                reserva.getId(),
                reserva.getEspaco().getId(),
                reserva.getEspaco().getNome(),
                reserva.getCliente().getId(),
                reserva.getCliente().getNome(),
                reserva.getInicio(),
                reserva.getFim(),
                reserva.getStatus(),
                reserva.getValorTotal().setScale(ESCALA_MONETARIA, RoundingMode.HALF_UP));
    }
}
