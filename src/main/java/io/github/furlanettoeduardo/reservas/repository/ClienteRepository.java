package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

public interface ClienteRepository extends ListCrudRepository<Cliente, Long> {

    /** Deriva bem: uma condicao so. Usado para dar erro util antes do UNIQUE do banco. */
    Optional<Cliente> findByEmail(String email);

    boolean existsByEmail(String email);
}
