package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaSpringData;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terceira correção do N+1: {@code default_batch_fetch_size}. Contexto separado porque é
 * propriedade global do Hibernate, e não anotação por consulta — o que já é a característica
 * mais importante dela.
 *
 * <p>Não elimina o N+1: agrupa as N cargas em lotes de até {@code size} identificadores, via
 * {@code where id in (?, ?, …)}. Com 50 clientes distintos e lote de 25, as 50 queries viram 2.
 *
 * <p>A formulação que fecha o assunto: batch fetch transforma N queries em ⌈N/25⌉, então muda a
 * <b>constante</b>. Plano de fetch muda a <b>ordem de crescimento</b>, de N para 1. Um não
 * substitui o outro — são defesas em camadas diferentes, e o papel do batch fetch é não exigir
 * que se saiba de antemão quais associações o caso de uso vai tocar.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=25"
})
class BatchFetchIT {

    private static final int QUANTIDADE_DE_RESERVAS = 50;
    private static final int TAMANHO_DO_LOTE = 25;

    @Autowired
    private ReservaRepositorio reservas;
    @Autowired
    private EspacoRepositorio espacos;
    @Autowired
    private ClienteRepositorio clientes;
    @Autowired
    private ReservaSpringData springData;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private JdbcTemplate jdbc;

    private Long espacoId;
    private ContadorDeQueries contador;

    @BeforeEach
    void semearClientesDistintos() {
        LimpezaDeBase.limpar(jdbc);
        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacos.salvar(new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
            espacoId = espaco.getId();

            Instant base = Instant.parse("2026-09-01T08:00:00Z");
            for (int i = 0; i < QUANTIDADE_DE_RESERVAS; i++) {
                Cliente cliente = clientes.salvar(
                        new Cliente("Cliente " + i, "cliente%d@exemplo.com".formatted(i)));
                reservas.salvar(Reserva.nova(espaco, cliente, new Periodo(
                        base.plusSeconds(i * 3600L), base.plusSeconds((i + 1) * 3600L))));
            }
        });
        contador = new ContadorDeQueries(emf);
    }

    @Test
    void batchFetchAgrupaAsCargasSemEliminaLas() {
        var medicao = contador.medir(() -> transacao.execute(status -> springData
                .findByEspacoIdAndStatusOrderByInicioAsc(espacoId, StatusReserva.CONFIRMADA)
                .stream().map(ReservaJpa::paraDominio).toList()));

        List<Reserva> resposta = medicao.resultado();
        System.out.printf("%n[N+1] batch fetch (%d)    -> %d queries%n",
                TAMANHO_DO_LOTE, medicao.queries());

        assertThat(resposta).hasSize(QUANTIDADE_DE_RESERVAS);
        assertThat(resposta).allSatisfy(
                r -> assertThat(r.getCliente().getNome()).startsWith("Cliente "));

        int lotesDeClientes = (QUANTIDADE_DE_RESERVAS + TAMANHO_DO_LOTE - 1) / TAMANHO_DO_LOTE;
        assertThat(medicao.queries())
                .as("MESMA consulta que custa 52 sem a propriedade: 1 listagem + 1 espaco "
                        + "+ %d lotes de clientes. Nao e 1 -- batch fetch reduz idas ao banco, "
                        + "nao elimina", lotesDeClientes)
                .isEqualTo(2 + lotesDeClientes);
    }
}
