package io.github.furlanettoeduardo.reservas.repository.jpa;

import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

public interface ClienteSpringData extends ListCrudRepository<ClienteJpa, Long> {

    Optional<ClienteJpa> findByEmail(String email);

    boolean existsByEmail(String email);
}
