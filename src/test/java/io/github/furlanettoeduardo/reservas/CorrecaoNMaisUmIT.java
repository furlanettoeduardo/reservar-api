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
import jakarta.persistence.EntityManager;
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
 * Patologia n.1 do 1B: as tres correcoes do N+1, medidas contra o baseline de 52 queries.
 *
 * <p>Nenhuma das tres e "a certa" em abstrato -- elas resolvem problemas de forma diferente, e
 * o teste existe para que a escolha seja feita com numero e nao com preferencia. A adotada em
 * producao e o {@code @EntityGraph}; o motivo esta no Javadoc do repositorio e o custo relativo
 * esta aqui.
 *
 * <p>O caminho ingenuo continua vivo e medido, para que o "antes" nao vire folclore.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class CorrecaoNMaisUmIT {

    private static final int QUANTIDADE_DE_RESERVAS = 50;

    private static final String JPQL_COM_JOIN_FETCH = """
            select r from Reserva r
            join fetch r.espaco
            join fetch r.cliente
            where r.espaco.id = :espacoId and r.status = :status
            order by r.inicio
            """;

    @Autowired
    private ReservaRepository reservaRepository;
    @Autowired
    private EspacoRepository espacoRepository;
    @Autowired
    private ClienteRepository clienteRepository;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private EntityManager em;
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

    /** Mapeia dentro da transacao, como o servico faz -- senao os proxies morreriam antes. */
    private ContadorDeQueries.Medicao<List<ReservaResponse>> medir(
            java.util.function.Supplier<List<Reserva>> consulta) {
        return contador.medir(() -> transacao.execute(status ->
                consulta.get().stream().map(ReservaResponse::de).toList()));
    }

    private void conferirConteudo(List<ReservaResponse> resposta) {
        assertThat(resposta).hasSize(QUANTIDADE_DE_RESERVAS);
        assertThat(resposta).allSatisfy(r -> {
            assertThat(r.espacoNome()).isEqualTo("Sala Azul");
            assertThat(r.clienteNome()).startsWith("Cliente ");
        });
    }

    @Test
    void semPlanoDeFetch_umaQueryPorAlvoDistinto() {
        var medicao = medir(() -> reservaRepository
                .findByEspacoIdAndStatusOrderByInicioAsc(espacoId, StatusReserva.CONFIRMADA));

        System.out.printf("%n[N+1] ingenuo            -> %d queries%n", medicao.queries());
        conferirConteudo(medicao.resultado());

        assertThat(medicao.queries())
                .as("o baseline: 1 listagem + 1 espaco (cache de 1o nivel nos outros 49) "
                        + "+ 1 por cliente distinto")
                .isEqualTo(QUANTIDADE_DE_RESERVAS + 2);
    }

    @Test
    void comJoinFetchNaJpql_umaQuery() {
        var medicao = contador.medir(() -> transacao.execute(status ->
                em.createQuery(JPQL_COM_JOIN_FETCH, Reserva.class)
                        .setParameter("espacoId", espacoId)
                        .setParameter("status", StatusReserva.CONFIRMADA)
                        .getResultList()
                        .stream().map(ReservaResponse::de).toList()));

        System.out.printf("%n[N+1] join fetch (JPQL)  -> %d queries%n", medicao.queries());
        conferirConteudo(medicao.resultado());

        assertThat(medicao.queries()).isEqualTo(1);
    }

    @Test
    void comEntityGraph_umaQuery() {
        var medicao = medir(() -> reservaRepository
                .findComEspacoEClienteByEspacoIdAndStatusOrderByInicioAsc(
                        espacoId, StatusReserva.CONFIRMADA));

        System.out.printf("%n[N+1] entity graph       -> %d queries%n", medicao.queries());
        conferirConteudo(medicao.resultado());

        assertThat(medicao.queries())
                .as("mesmo resultado do join fetch, com a clausula where em um lugar so")
                .isEqualTo(1);
    }

    /**
     * Prova que as duas correcoes resolvem o mesmo problema por caminhos equivalentes -- e que
     * a escolha entre elas e sobre organizacao de codigo, nao sobre custo.
     */
    @Test
    void joinFetchEEntityGraphCustamOMesmo() {
        var comJpql = contador.medir(() -> transacao.execute(status ->
                em.createQuery(JPQL_COM_JOIN_FETCH, Reserva.class)
                        .setParameter("espacoId", espacoId)
                        .setParameter("status", StatusReserva.CONFIRMADA)
                        .getResultList().size()));

        var comGraph = contador.medir(() -> transacao.execute(status -> reservaRepository
                .findComEspacoEClienteByEspacoIdAndStatusOrderByInicioAsc(
                        espacoId, StatusReserva.CONFIRMADA).size()));

        assertThat(comGraph.queries()).isEqualTo(comJpql.queries());
    }
}
