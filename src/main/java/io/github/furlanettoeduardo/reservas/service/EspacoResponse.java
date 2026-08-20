package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.Espaco;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Saida de leitura, para a tela ter o que colocar nos selects. Sem POST correspondente: criar
 * espaco continua fora do escopo da API.
 */
public record EspacoResponse(Long id, String nome, Integer capacidade, BigDecimal precoHora) {

    /** Escala fixada no centavo, pelo mesmo motivo de ReservaResponse: contrato consistente. */
    public static EspacoResponse de(Espaco espaco) {
        return new EspacoResponse(
                espaco.getId(),
                espaco.getNome(),
                espaco.getCapacidade(),
                espaco.getPrecoHora().setScale(2, RoundingMode.HALF_UP));
    }
}
