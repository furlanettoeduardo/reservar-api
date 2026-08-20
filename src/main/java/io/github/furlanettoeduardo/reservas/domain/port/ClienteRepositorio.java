package io.github.furlanettoeduardo.reservas.domain.port;

import io.github.furlanettoeduardo.reservas.domain.Cliente;

import java.util.List;
import java.util.Optional;

public interface ClienteRepositorio {

    Cliente salvar(Cliente cliente);

    Optional<Cliente> porId(Long id);

    List<Cliente> todos();

    long contar();
}
