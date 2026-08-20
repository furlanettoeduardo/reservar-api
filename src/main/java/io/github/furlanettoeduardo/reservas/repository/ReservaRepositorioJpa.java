package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador da porta {@link ReservaRepositorio} para Spring Data JPA.
 *
 * <p>Delegacao fina, de proposito. O valor desta classe nao esta no que ela faz, esta em onde ela
 * <b>fica</b>: com ela, o servico depende de uma interface do dominio e nao de
 * {@code ListCrudRepository}. A partir daqui trocar Spring Data por JDBC direto, ou por outro
 * banco, e mexer aqui e em mais nada.
 *
 * <p>E o custo tambem esta visivel: uma classe por porta, que so repassa. Se a inversao nunca
 * for exercida -- nenhuma troca de tecnologia, nenhum teste que substitua a implementacao --
 * esta camada e cerimonia. O ADR 0004 registra esse trade-off com numeros.
 */
@Repository
public class ReservaRepositorioJpa implements ReservaRepositorio {

    private final ReservaRepository reservas;

    public ReservaRepositorioJpa(ReservaRepository reservas) {
        this.reservas = reservas;
    }

    @Override
    public Reserva salvar(Reserva reserva) {
        return reservas.save(reserva);
    }

    @Override
    public Optional<Reserva> porId(Long id) {
        return reservas.findById(id);
    }

    @Override
    public boolean existeSobreposicao(Long espacoId, StatusReserva status,
                                      Instant inicio, Instant fim) {
        return reservas.existeSobreposicao(espacoId, status, inicio, fim);
    }

    @Override
    public List<Reserva> confirmadasDoEspaco(Long espacoId) {
        return reservas.findComEspacoEClienteByEspacoIdAndStatusOrderByInicioAsc(
                espacoId, StatusReserva.CONFIRMADA);
    }

    @Override
    public long contar() {
        return reservas.count();
    }
}
