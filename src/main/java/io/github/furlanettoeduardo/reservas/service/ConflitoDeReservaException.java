package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.Periodo;

/**
 * RuntimeException, e nao checked, por dois motivos: o padrao do Spring so faz rollback em
 * RuntimeException -- checked exception commita a transacao silenciosamente -- e conflito de
 * reserva nao e algo que o chamador possa tratar e retomar.
 */
public class ConflitoDeReservaException extends RuntimeException {

    public ConflitoDeReservaException(Long espacoId, Periodo periodo) {
        super("espaco %d ja tem reserva confirmada entre %s e %s"
                .formatted(espacoId, periodo.inicio(), periodo.fim()));
    }
}
