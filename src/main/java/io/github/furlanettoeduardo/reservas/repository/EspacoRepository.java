package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Sem implementacao: o Spring Data gera um proxy em runtime a partir desta interface.
 *
 * <p>{@code ListCrudRepository} e nao {@code JpaRepository} -- ver a nota em
 * {@link ReservaRepository}.
 */
public interface EspacoRepository extends ListCrudRepository<Espaco, Long> {
}
