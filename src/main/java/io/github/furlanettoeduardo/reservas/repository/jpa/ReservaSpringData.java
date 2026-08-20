package io.github.furlanettoeduardo.reservas.repository.jpa;

import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repositorio Spring Data sobre {@link ReservaJpa}. Detalhe de adaptador: nada acima desta
 * camada conhece esta interface -- quem o servico ve e
 * {@code domain.port.ReservaRepositorio}.
 *
 * <p>Base {@code ListCrudRepository} pelos motivos do ADR 0003: sem {@code flush()},
 * {@code deleteAllInBatch()} nem {@code getReferenceById()}.
 */
public interface ReservaSpringData extends ListCrudRepository<ReservaJpa, Long> {

    /**
     * Dois intervalos meio-abertos [a1, a2) e [b1, b2) se sobrepoem quando
     * {@code a1 < b2 && b1 < a2}. Operadores estritos: reserva que termina 14:00 e outra que
     * comeca 14:00 nao conflitam.
     *
     * <p>JPQL explicito e nao nome derivado. O nome derivado equivalente teria dois
     * {@code Instant} posicionais cuja ordem correta contradiz o nome do metodo -- trocar os
     * dois compila, roda e devolve a resposta errada em silencio. Ver ADR 0002.
     */
    @Query("""
            select count(r) > 0 from ReservaJpa r
            where r.espaco.id = :espacoId
              and r.status = :status
              and r.inicio < :fim
              and r.fim > :inicio
            """)
    boolean existeSobreposicao(@Param("espacoId") Long espacoId,
                               @Param("status") StatusReserva status,
                               @Param("inicio") Instant inicio,
                               @Param("fim") Instant fim);

    /** Sem plano de fetch: as associacoes voltam como proxy. Usado para medir o N+1. */
    List<ReservaJpa> findByEspacoIdAndStatusOrderByInicioAsc(Long espacoId, StatusReserva status);

    /**
     * Com plano de fetch. Deixou de ser otimizacao e virou requisito: o mapeamento para dominio
     * materializa espaco e cliente sempre, entao sem o graph a listagem volta a custar uma query
     * por alvo distinto.
     */
    @EntityGraph(attributePaths = {"espaco", "cliente"})
    List<ReservaJpa> findComEspacoEClienteByEspacoIdAndStatusOrderByInicioAsc(
            Long espacoId, StatusReserva status);
}
