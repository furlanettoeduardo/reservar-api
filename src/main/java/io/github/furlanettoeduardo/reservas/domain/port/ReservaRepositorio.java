package io.github.furlanettoeduardo.reservas.domain.port;

import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Porta de saida para persistencia de reservas. Declarada no dominio, implementada fora dele --
 * e a inversao de dependencia: o dominio diz o que precisa, o adaptador resolve como.
 *
 * <p>Assinaturas em termos de tipos de dominio, e nenhuma mencao a Spring Data, a
 * {@code Optional} de framework ou a paginacao de biblioteca. O que o dominio pode expressar
 * aqui e o que ele precisa saber; o resto e detalhe do adaptador.
 *
 * <p>Nomes em portugues, como o resto do dominio. {@code salvar} e nao {@code save} nao e
 * preciosismo: e o sinal de que esta interface pertence a este lado da fronteira, e nao e a
 * interface de uma biblioteca vazando para dentro.
 */
public interface ReservaRepositorio {

    Reserva salvar(Reserva reserva);

    Optional<Reserva> porId(Long id);

    /**
     * Dois intervalos meio-abertos se sobrepoem quando {@code a1 < b2 && b1 < a2}. A condicao
     * vive no adaptador em JPQL, e {@link io.github.furlanettoeduardo.reservas.domain.Periodo}
     * a espelha em memoria -- os dois lados existem de proposito, e ha teste comparando.
     */
    boolean existeSobreposicao(Long espacoId, StatusReserva status, Instant inicio, Instant fim);

    /**
     * Com plano de fetch: o adaptador carrega espaco e cliente na mesma consulta. Sem isso a
     * listagem custava 52 queries para 50 reservas.
     */
    List<Reserva> confirmadasDoEspaco(Long espacoId);

    long contar();
}
