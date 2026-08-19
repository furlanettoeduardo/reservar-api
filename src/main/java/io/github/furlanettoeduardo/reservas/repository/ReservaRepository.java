package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Base escolhida: {@code ListCrudRepository}, nao {@code JpaRepository}.
 *
 * <p>O que se perde de proposito: {@code flush()} e {@code saveAndFlush()} (controle de flush
 * e do EntityManager, nao do chamador), {@code deleteAllInBatch()} (apaga a tabela ignorando
 * cascade e callbacks), {@code getReferenceById()} (devolve proxy -- util, mas so quando se
 * sabe o que isso significa) e as sobrecargas de {@code Page}. O que se ganha em relacao ao
 * {@code CrudRepository} puro: retornos {@code List} em vez de {@code Iterable}, que e o que
 * o controller precisa.
 *
 * <p>Revisar quando a listagem crescer: ai entra {@code ListPagingAndSortingRepository} ao
 * lado, para trazer paginacao sem trazer o resto do {@code JpaRepository} junto.
 */
public interface ReservaRepository extends ListCrudRepository<Reserva, Long> {

    /**
     * Dois intervalos meio-abertos [a1, a2) e [b1, b2) se sobrepoem quando
     * {@code a1 < b2 && b1 < a2}. Os operadores sao estritos: uma reserva que termina 14:00
     * e outra que comeca 14:00 nao conflitam.
     *
     * <p>JPQL explicito em vez de nome derivado. O nome derivado equivalente seria
     * {@code existsByEspacoIdAndStatusAndInicioLessThanAndFimGreaterThan(espacoId, status,
     * fim, inicio)} -- note que o parametro chamado "inicioLessThan" recebe o <b>fim</b> do
     * periodo consultado. Quatro condicoes e dois argumentos cuja ordem contradiz o proprio
     * nome do metodo: trocar os dois ultimos compila, roda, e devolve a resposta errada em
     * silencio. Com parametro nomeado o erro fica impossivel.
     */
    @Query("""
            select count(r) > 0 from Reserva r
            where r.espaco.id = :espacoId
              and r.status = :status
              and r.inicio < :fim
              and r.fim > :inicio
            """)
    boolean existeSobreposicao(@Param("espacoId") Long espacoId,
                               @Param("status") StatusReserva status,
                               @Param("inicio") Instant inicio,
                               @Param("fim") Instant fim);

    /** Tres condicoes, ordem dos argumentos obvia: aqui a derivacao ainda se paga. */
    List<Reserva> findByEspacoIdAndStatusOrderByInicioAsc(Long espacoId, StatusReserva status);
}
