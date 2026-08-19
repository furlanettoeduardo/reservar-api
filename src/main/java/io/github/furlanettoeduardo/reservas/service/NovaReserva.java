package io.github.furlanettoeduardo.reservas.service;

import java.time.Instant;

/**
 * Comando de entrada do servico. Record de novo pelo motivo inverso ao da entidade: e um
 * dado de passagem, imutavel, sem identidade e sem ciclo de vida gerenciado por framework.
 *
 * <p>O servico recebe <b>ids</b>, nao entidades. Entidade vinda de fora ja veio destacada do
 * persistence context: o servico nao sabe se ela existe, se esta atualizada, nem se alguem
 * mexeu nela no caminho -- e {@code save()} de entidade destacada com id preenchido vira
 * UPDATE, sobrescrevendo a linha inteira em vez de falhar.
 */
public record NovaReserva(Long espacoId, Long clienteId, Instant inicio, Instant fim) {
}
