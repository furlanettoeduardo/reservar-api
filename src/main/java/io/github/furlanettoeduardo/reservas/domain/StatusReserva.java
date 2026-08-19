package io.github.furlanettoeduardo.reservas.domain;

/**
 * Estado de uma reserva. Gravado como texto ({@code @Enumerated(EnumType.STRING)}), o que
 * torna seguro inserir constantes novas em qualquer posicao -- com ORDINAL, inserir
 * PENDENTE aqui no meio reescreveria o significado de toda linha ja gravada.
 */
public enum StatusReserva {
    CONFIRMADA,
    CANCELADA
}
