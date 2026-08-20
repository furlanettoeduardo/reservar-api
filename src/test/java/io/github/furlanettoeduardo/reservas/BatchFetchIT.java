package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
import io.github.furlanettoeduardo.reservas.service.ReservaResponse;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terceira correcao do N+1: {@code default_batch_fetch_size}. Contexto separado porque e
 * propriedade global do Hibernate, e nao anotacao por consulta -- o que ja e a caracteristica
 * mais importante dela.
 *
 * <p>Nao elimina o N+1: agrupa as N cargas em lotes de ate {@code size} identificadores, via
 * {@code where id in (?, ?, ...)}. Com 50 clientes distintos e lote de 25, as 50 queries viram
 * 2. Continua sendo carga adicional, so mais barata.
 *
 * <p>O papel dela e diferente das outras duas: nao exige saber de antemao quais associacoes o
 * caso de uso vai tocar. E rede de seguranca global para o N+1 que ninguem previu, nao
 * substituto do plano de fetch explicito onde ele cabe.
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
    private ReservaRepository reservaRepository;
    @Autowired
    private EspacoRepository espacoRepository;
    @Autowired
    private ClienteRepository clienteRepository;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private EntityManagerFactory emf;

    private Long espacoId;
    private ContadorDeQueries contador;

    @BeforeEach
    void semearClientesDistintos() {
        transacao.executeWithoutResult(status -> {
            reservaRepository.deleteAll();
            clienteRepository.deleteAll();
            espacoRepository.deleteAll();
        });
        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacoRepository.save(
                    new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
            espacoId = espaco.getId();

            Instant base = Instant.parse("2026-09-01T08:00:00Z");
            for (int i = 0; i < QUANTIDADE_DE_RESERVAS; i++) {
                Cliente cliente = clienteRepository.save(
                        new Cliente("Cliente " + i, "cliente%d@exemplo.com".formatted(i)));
                reservaRepository.save(Reserva.nova(espaco, cliente, new Periodo(
                        base.plusSeconds(i * 3600L), base.plusSeconds((i + 1) * 3600L))));
            }
        });
        contador = new ContadorDeQueries(emf);
    }

    @Test
    void batchFetchAgrupaAsCargasSemEliminaLas() {
        var medicao = contador.medir(() -> transacao.execute(status -> reservaRepository
                .findByEspacoIdAndStatusOrderByInicioAsc(espacoId, StatusReserva.CONFIRMADA)
                .stream().map(ReservaResponse::de).toList()));

        List<ReservaResponse> resposta = medicao.resultado();
        System.out.printf("%n[N+1] batch fetch (%d)    -> %d queries%n",
                TAMANHO_DO_LOTE, medicao.queries());

        assertThat(resposta).hasSize(QUANTIDADE_DE_RESERVAS);
        assertThat(resposta).allSatisfy(r -> assertThat(r.clienteNome()).startsWith("Cliente "));

        int lotesDeClientes = (QUANTIDADE_DE_RESERVAS + TAMANHO_DO_LOTE - 1) / TAMANHO_DO_LOTE;
        assertThat(medicao.queries())
                .as("MESMO metodo de repositorio que custa 52 sem a propriedade: 1 listagem "
                        + "+ 1 espaco + %d lotes de clientes. Nao e 1 -- batch fetch reduz "
                        + "idas ao banco, nao elimina", lotesDeClientes)
                .isEqualTo(2 + lotesDeClientes);
    }
}
