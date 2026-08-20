package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.Cliente;

public record ClienteResponse(Long id, String nome, String email) {

    public static ClienteResponse de(Cliente cliente) {
        return new ClienteResponse(cliente.getId(), cliente.getNome(), cliente.getEmail());
    }
}
