package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepositorioJpa;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepositorioJpa;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepositorioJpa;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reserva existente em todos os casos vai das 13:00 as 15:00 de 2026-09-01, CONFIRMADA.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Os adaptadores entram explicitamente: @DataJpaTest escaneia repositorios Spring Data, e nao
// beans @Repository comuns. Custo recorrente da inversao em teste de fatia -- a fatia passou a
// precisar saber quem implementa a porta.
@Import({TestcontainersConfiguration.class, ReservaRepositorioJpa.class,
        EspacoRepositorioJpa.class, ClienteRepositorioJpa.class})
@TestPropertySource(properties = {
        "spring.jpa.show-sql=true",
        "spring.jpa.properties.hibernate.format_sql=true"
})
class ReservaRepositoryIT {

    @Autowired
    private ReservaRepositorio reservas;

    @Autowired
    private EspacoRepositorio espacos;
    @Autowired
    private ClienteRepositorio clientes;

    private Espaco espaco;
    private Cliente cliente;

    private static Instant hora(String hhmm) {
        return Instant.parse("2026-09-01T%s:00Z".formatted(hhmm));
    }

    @BeforeEach
    void preparar() {
        espaco = espacos.salvar(new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
        cliente = clientes.salvar(new Cliente("Ana", "ana@exemplo.com"));
        reservas.salvar(Reserva.nova(espaco, cliente,
                new Periodo(hora("13:00"), hora("15:00"))));
    }

    @ParameterizedTest(name = "[{index}] {0}-{1} -> {2} ({3})")
    @CsvSource({
            "12:00, 14:00, true,  sobreposicao parcial no inicio",
            "14:00, 16:00, true,  sobreposicao parcial no fim",
            "13:30, 14:30, true,  contido dentro da existente",
            "12:00, 16:00, true,  envolve a existente inteira",
            "13:00, 15:00, true,  identico a existente",
            "11:00, 13:00, false, borda: termina exatamente quando a existente comeca",
            "15:00, 17:00, false, borda: comeca exatamente quando a existente termina",
            "10:00, 12:00, false, inteiramente antes",
            "16:00, 18:00, false, inteiramente depois"
    })
    void detectaSobreposicaoDeIntervaloMeioAberto(String inicio, String fim, boolean esperado, String caso) {
        boolean houve = reservas.existeSobreposicao(
                espaco.getId(), StatusReserva.CONFIRMADA, hora(inicio), hora(fim));

        assertThat(houve).as(caso).isEqualTo(esperado);
    }

    @Test
    void reservaCanceladaNaoBloqueia() {
        Reserva existente = reservas.confirmadasDoEspaco(espaco.getId()).getFirst();
        reservas.salvar(existente.cancelar());

        boolean houve = reservas.existeSobreposicao(
                espaco.getId(), StatusReserva.CONFIRMADA, hora("13:30"), hora("14:30"));

        assertThat(houve).as("so reserva CONFIRMADA ocupa o espaco").isFalse();
    }

    @Test
    void reservaDeOutroEspacoNaoBloqueia() {
        Espaco outro = espacos.salvar(new Espaco("Sala Verde", 10, new BigDecimal("90.00")));

        boolean houve = reservas.existeSobreposicao(
                outro.getId(), StatusReserva.CONFIRMADA, hora("13:30"), hora("14:30"));

        assertThat(houve).isFalse();
    }
}
