package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaJpa;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.service.ConflitoDeReservaException;
import io.github.furlanettoeduardo.reservas.service.NovaReserva;
import io.github.furlanettoeduardo.reservas.service.ReservaResponse;
import io.github.furlanettoeduardo.reservas.service.RecursoNaoEncontradoException;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepositorioJpa;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepositorioJpa;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepositorioJpa;
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Os adaptadores das portas entram explicitamente: @DataJpaTest escaneia repositorios Spring
// Data, e nao beans @Repository comuns. E o primeiro custo concreto da inversao -- a fatia de
// teste passou a precisar saber quem implementa a porta.
@Import({TestcontainersConfiguration.class, ReservaService.class,
        ReservaRepositorioJpa.class, EspacoRepositorioJpa.class, ClienteRepositorioJpa.class})
class ReservaServiceIT {

    @Autowired
    private ReservaService service;

    @Autowired
    private TestEntityManager em;

    private Long espacoId;
    private Long clienteId;

    private static Instant hora(String hhmm) {
        return Instant.parse("2026-09-01T%s:00Z".formatted(hhmm));
    }

    @BeforeEach
    void preparar() {
        espacoId = em.persist(
                EspacoJpa.de(new Espaco("Sala Azul", 30, new BigDecimal("150.00")))).getId();
        clienteId = em.persist(ClienteJpa.de(new Cliente("Ana", "ana@exemplo.com"))).getId();
        em.flush();
    }

    @Test
    void criaComValorDerivadoDaTarifaEDaDuracao() {
        ReservaResponse reserva = service.criar(
                new NovaReserva(espacoId, clienteId, hora("13:00"), hora("15:00")));

        assertThat(reserva.id()).isNotNull();
        assertThat(reserva.status()).isEqualTo(StatusReserva.CONFIRMADA);
        assertThat(reserva.valorTotal())
                .as("2h x 150.00/h, calculado pelo dominio e nao informado pelo chamador")
                .isEqualByComparingTo("300.00");
    }

    @Test
    void rejeitaSobreposicaoComReservaConfirmada() {
        service.criar(new NovaReserva(espacoId, clienteId, hora("13:00"), hora("15:00")));

        assertThatThrownBy(() -> service.criar(
                new NovaReserva(espacoId, clienteId, hora("14:00"), hora("16:00"))))
                .isInstanceOf(ConflitoDeReservaException.class)
                .hasMessageContaining(String.valueOf(espacoId));
    }

    @Test
    void aceitaReservaQueEncostaNaBorda() {
        service.criar(new NovaReserva(espacoId, clienteId, hora("13:00"), hora("15:00")));

        ReservaResponse seguinte = service.criar(
                new NovaReserva(espacoId, clienteId, hora("15:00"), hora("17:00")));

        assertThat(seguinte.id()).as("intervalo meio-aberto: 15:00 nao conflita").isNotNull();
    }

    @Test
    void liberaOHorarioAposCancelamento() {
        ReservaResponse primeira = service.criar(
                new NovaReserva(espacoId, clienteId, hora("13:00"), hora("15:00")));

        service.cancelar(primeira.id());

        assertThat(service.criar(new NovaReserva(espacoId, clienteId, hora("13:30"), hora("14:30"))))
                .isNotNull();
        assertThat(service.listarConfirmadasDoEspaco(espacoId))
                .as("a cancelada sai da listagem de confirmadas")
                .hasSize(1);
    }

    @Test
    void cancelarGravaSemChamarSave() {
        ReservaResponse reserva = service.criar(
                new NovaReserva(espacoId, clienteId, hora("13:00"), hora("15:00")));
        em.flush();
        em.clear();

        service.cancelar(reserva.id());
        em.flush();
        em.clear();

        assertThat(em.find(ReservaJpa.class, reserva.id()).paraDominio().getStatus())
                .as("dirty checking: o servico so mudou o objeto, o UPDATE saiu no flush")
                .isEqualTo(StatusReserva.CANCELADA);
    }

    @Test
    void validaOPeriodoAntesDeIrAoBanco() {
        assertThatThrownBy(() -> service.criar(
                new NovaReserva(espacoId, clienteId, hora("15:00"), hora("13:00"))))
                .as("o Periodo barra na construcao, antes de qualquer SELECT")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void falhaQuandoOEspacoNaoExiste() {
        assertThatThrownBy(() -> service.criar(
                new NovaReserva(999_999L, clienteId, hora("13:00"), hora("15:00"))))
                .isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessageContaining("espaco");
    }
}
