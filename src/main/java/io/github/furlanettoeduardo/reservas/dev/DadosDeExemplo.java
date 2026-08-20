package io.github.furlanettoeduardo.reservas.dev;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Semeadura de exemplo, so no perfil {@code dev}.
 *
 * <p>Existe por causa de um problema concreto: o 1A nao tem POST para espaco nem cliente, entao
 * quem clonava o repositorio nao chegava a um 201 sem rodar SQL na mao. Com isto,
 * {@code docker compose up} mais {@code spring-boot:run -Dspring-boot.run.profiles=dev} entrega
 * um sistema navegavel na primeira subida.
 *
 * <p><b>Nao</b> e migration. Dado de exemplo numa {@code V4__} rodaria em producao tambem, e
 * migration e para schema -- ou para dado que o dominio exige, o que nao e o caso aqui.
 *
 * <p>Idempotente por checagem de vazio, e nao por {@code ON CONFLICT}: se alguem ja mexeu nos
 * dados, a semeadura nao passa por cima.
 */
@Configuration
@Profile("dev")
public class DadosDeExemplo {

    private static final Logger log = LoggerFactory.getLogger(DadosDeExemplo.class);

    @Bean
    ApplicationRunner semearDadosDeExemplo(EspacoRepository espacos,
                                           ClienteRepository clientes,
                                           ReservaRepository reservas,
                                           TransactionTemplate transacao) {
        return argumentos -> transacao.executeWithoutResult(
                status -> semear(espacos, clientes, reservas));
    }

    private void semear(EspacoRepository espacos, ClienteRepository clientes,
                        ReservaRepository reservas) {
        if (espacos.count() > 0 || clientes.count() > 0) {
            log.info("Dados de exemplo: base ja populada, nada a fazer");
            return;
        }

        Espaco azul = espacos.save(new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
        Espaco verde = espacos.save(new Espaco("Sala Verde", 12, new BigDecimal("90.00")));
        espacos.save(new Espaco("Auditorio", 120, new BigDecimal("420.00")));

        Cliente ana = clientes.save(new Cliente("Ana Souza", "ana@exemplo.com"));
        Cliente bruno = clientes.save(new Cliente("Bruno Lima", "bruno@exemplo.com"));
        clientes.save(new Cliente("Carla Dias", "carla@exemplo.com"));

        // Amanha, hora cheia: a tela abre com horarios no futuro, entao o @Future dos DTOs
        // aceita o que o usuario digitar em volta deles.
        Instant base = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

        reservas.save(Reserva.nova(azul, ana,
                new Periodo(base, base.plus(Duration.ofHours(2)))));
        reservas.save(Reserva.nova(azul, bruno,
                new Periodo(base.plus(Duration.ofHours(3)), base.plus(Duration.ofHours(4)))));
        reservas.save(Reserva.nova(verde, ana,
                new Periodo(base.plus(Duration.ofHours(1)), base.plus(Duration.ofHours(2)))));

        log.info("Dados de exemplo: {} espacos, {} clientes, {} reservas",
                espacos.count(), clientes.count(), reservas.count());
    }
}
