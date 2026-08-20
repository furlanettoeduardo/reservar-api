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
import jakarta.persistence.EntityManager;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia nº 1 do 1B: as três correções do N+1, medidas contra o baseline de 52 queries.
 *
 * <p>Nenhuma das três é "a certa" em abstrato — elas resolvem o problema de formas diferentes, e
 * o teste existe para que a escolha seja feita com número e não com preferência.
 *
 * <p><b>A refatoração hexagonal mudou o que dispara o N+1, e não o número.</b> Antes, o
 * mapeamento para DTO tocava as associações e o N+1 era consequência de <i>alguém</i> tocá-las.
 * Agora o mapeador do adaptador toca as duas <b>sempre</b>, para materializar o domínio. Ou
 * seja: o plano de fetch deixou de ser otimização e passou a ser requisito — o custo virou
 * inevitável em vez de acidental. Mesmo 52, por um motivo mais forte.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class CorrecaoNMaisUmIT {

    private static final int QUANTIDADE_DE_RESERVAS = 50;

    private static final String JPQL_COM_JOIN_FETCH = """
            select r from ReservaJpa r
            join fetch r.espaco
            join fetch r.cliente
            where r.espaco.id = :espacoId and r.status = :status
            order by r.inicio
            """;

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
    private EntityManager em;
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

    /** Mapeia para domínio dentro da transação, que é o que o adaptador faz em produção. */
    private ContadorDeQueries.Medicao<List<Reserva>> medir(Supplier<List<ReservaJpa>> consulta) {
        return contador.medir(() -> transacao.execute(status ->
                consulta.get().stream().map(ReservaJpa::paraDominio).toList()));
    }

    private void conferirConteudo(List<Reserva> resposta) {
        assertThat(resposta).hasSize(QUANTIDADE_DE_RESERVAS);
        assertThat(resposta).allSatisfy(r -> {
            assertThat(r.getEspaco().getNome()).isEqualTo("Sala Azul");
            assertThat(r.getCliente().getNome()).startsWith("Cliente ");
        });
    }

    @Test
    void semPlanoDeFetch_umaQueryPorAlvoDistinto() {
        var medicao = medir(() -> springData
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
                em.createQuery(JPQL_COM_JOIN_FETCH, ReservaJpa.class)
                        .setParameter("espacoId", espacoId)
                        .setParameter("status", StatusReserva.CONFIRMADA)
                        .getResultList()
                        .stream().map(ReservaJpa::paraDominio).toList()));

        System.out.printf("%n[N+1] join fetch (JPQL)  -> %d queries%n", medicao.queries());
        conferirConteudo(medicao.resultado());

        assertThat(medicao.queries()).isEqualTo(1);
    }

    @Test
    void comEntityGraph_umaQuery() {
        var medicao = medir(() -> springData
                .findComEspacoEClienteByEspacoIdAndStatusOrderByInicioAsc(
                        espacoId, StatusReserva.CONFIRMADA));

        System.out.printf("%n[N+1] entity graph       -> %d queries%n", medicao.queries());
        conferirConteudo(medicao.resultado());

        assertThat(medicao.queries())
                .as("mesmo resultado do join fetch, com a clausula where em um lugar so")
                .isEqualTo(1);
    }

    /**
     * Prova que as duas correções resolvem o mesmo problema por caminhos equivalentes — e que a
     * escolha entre elas é sobre organização de código, não sobre custo.
     */
    @Test
    void joinFetchEEntityGraphCustamOMesmo() {
        var comJpql = contador.medir(() -> transacao.execute(status ->
                em.createQuery(JPQL_COM_JOIN_FETCH, ReservaJpa.class)
                        .setParameter("espacoId", espacoId)
                        .setParameter("status", StatusReserva.CONFIRMADA)
                        .getResultList().size()));

        var comGraph = contador.medir(() -> transacao.execute(status -> springData
                .findComEspacoEClienteByEspacoIdAndStatusOrderByInicioAsc(
                        espacoId, StatusReserva.CONFIRMADA).size()));

        assertThat(comGraph.queries()).isEqualTo(comJpql.queries());
    }
}
