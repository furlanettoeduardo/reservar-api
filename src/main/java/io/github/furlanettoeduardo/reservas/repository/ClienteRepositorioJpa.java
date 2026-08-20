package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteSpringData;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ClienteRepositorioJpa implements ClienteRepositorio {

    private final ClienteSpringData clientes;

    public ClienteRepositorioJpa(ClienteSpringData clientes) {
        this.clientes = clientes;
    }

    @Override
    public Cliente salvar(Cliente cliente) {
        if (cliente.getId() == null) {
            return clientes.save(ClienteJpa.de(cliente)).paraDominio();
        }
        ClienteJpa gerenciado = clientes.findById(cliente.getId()).orElseThrow();
        gerenciado.aplicar(cliente);
        return clientes.save(gerenciado).paraDominio();
    }

    @Override
    public Optional<Cliente> porId(Long id) {
        return clientes.findById(id).map(ClienteJpa::paraDominio);
    }

    @Override
    public List<Cliente> todos() {
        return clientes.findAll().stream().map(ClienteJpa::paraDominio).toList();
    }

    @Override
    public long contar() {
        return clientes.count();
    }
}
