package io.github.furlanettoeduardo.reservas.web;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Forma HTTP da entrada. Separada de {@code NovaReserva} de proposito: esta carrega as
 * anotacoes de Bean Validation e o formato do JSON; aquela e o comando do dominio de
 * aplicacao. Hoje sao quase iguais e a duplicacao incomoda -- e o boilerplate honesto do 1A.
 *
 * <p>As anotacoes so valem se o parametro do controller tiver {@code @Valid}. Sem ele nada
 * dispara e a validacao vira decoracao.
 */
public record CriarReservaRequest(
        @NotNull(message = "espacoId e obrigatorio") Long espacoId,
        @NotNull(message = "clienteId e obrigatorio") Long clienteId,
        @NotNull(message = "inicio e obrigatorio") @Future(message = "inicio deve ser no futuro") Instant inicio,
        @NotNull(message = "fim e obrigatorio") @Future(message = "fim deve ser no futuro") Instant fim) {
}
