package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ClienteRepositorioJpa implements ClienteRepositorio {

    private final ClienteRepository clientes;

    public ClienteRepositorioJpa(ClienteRepository clientes) {
        this.clientes = clientes;
    }

    @Override
    public Cliente salvar(Cliente cliente) {
        return clientes.save(cliente);
    }

    @Override
    public Optional<Cliente> porId(Long id) {
        return clientes.findById(id);
    }

    @Override
    public List<Cliente> todos() {
        return clientes.findAll();
    }

    @Override
    public long contar() {
        return clientes.count();
    }
}
