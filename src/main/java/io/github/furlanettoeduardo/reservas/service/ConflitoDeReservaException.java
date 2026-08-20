package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.Periodo;

import java.time.Instant;

/**
 * RuntimeException, e nao checked, por dois motivos: o padrao do Spring so faz rollback em
 * RuntimeException -- checked exception commita a transacao silenciosamente -- e conflito de
 * reserva nao e algo que o chamador possa tratar e retomar.
 *
 * <p>Carrega o periodo em campos, e nao apenas na mensagem. O motivo apareceu na tela: a tabela
 * formata os horarios no fuso do navegador e a mensagem vinha em UTC, entao o usuario lia
 * "11:00 - 13:00" na lista e "14:00:00Z" no erro. Dado estruturado no ProblemDetail deixa o
 * cliente formatar no fuso dele; a mensagem continua la para quem consome a API sem interface.
 */
public class ConflitoDeReservaException extends RuntimeException {

    private final Long espacoId;
    private final Instant inicio;
    private final Instant fim;

    public ConflitoDeReservaException(Long espacoId, Periodo periodo) {
        super("espaco %d ja tem reserva confirmada entre %s e %s"
                .formatted(espacoId, periodo.inicio(), periodo.fim()));
        this.espacoId = espacoId;
        this.inicio = periodo.inicio();
        this.fim = periodo.fim();
    }

    public Long getEspacoId() {
        return espacoId;
    }

    public Instant getInicio() {
        return inicio;
    }

    public Instant getFim() {
        return fim;
    }
}
