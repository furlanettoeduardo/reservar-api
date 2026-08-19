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
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mede o "antes" da patologia n.1 do 1B. Nao corrige nada -- o numero medido aqui e o que da
 * substancia a correcao depois.
 *
 * <p>{@code @SpringBootTest} sem {@code @Transactional} de proposito: cada chamada de servico
 * abre e fecha a propria transacao, que e o que acontece em producao. Num
 * {@code @DataJpaTest} tudo compartilharia um persistence context e o N+1 sumiria por causa
 * do cache de primeiro nivel -- o teste passaria a medir o cache, nao a query.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ListagemNMaisUmIT {

    private static final int QUANTIDADE_DE_RESERVAS = 50;

    @Autowired
    private ReservaService service;
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

    private Statistics estatisticas() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void semearCinquentaReservasDeClientesDistintos() {
        // Limpeza em transacao SEPARADA de proposito. Na mesma transacao, o Hibernate ordena
        // o flush por tipo de operacao -- inserts antes de deletes -- entao os clientes novos
        // tentariam entrar antes de os antigos sairem e o UNIQUE de email estouraria. A ordem
        // em que voce chama os metodos nao e a ordem em que o SQL sai.
        transacao.executeWithoutResult(status -> {
            reservaRepository.deleteAll();
            clienteRepository.deleteAll();
            espacoRepository.deleteAll();
        });

        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacoRepository.save(new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
            espacoId = espaco.getId();

            Instant base = Instant.parse("2026-09-01T08:00:00Z");
            for (int i = 0; i < QUANTIDADE_DE_RESERVAS; i++) {
                Cliente cliente = clienteRepository.save(
                        new Cliente("Cliente " + i, "cliente%d@exemplo.com".formatted(i)));
                Periodo periodo = new Periodo(base.plusSeconds(i * 3600L), base.plusSeconds((i + 1) * 3600L));
                reservaRepository.save(Reserva.nova(espaco, cliente, periodo));
            }
        });
    }

    @Test
    void medeAsQueriesDaListagem() {
        estatisticas().clear();

        List<ReservaResponse> resposta = service.listarConfirmadasDoEspaco(espacoId);

        long queries = estatisticas().getPrepareStatementCount();
        System.out.printf("%n[N+1] %d reservas -> %d queries%n", resposta.size(), queries);

        assertThat(resposta).hasSize(QUANTIDADE_DE_RESERVAS);
        assertThat(resposta).allSatisfy(r -> {
            assertThat(r.espacoNome()).isEqualTo("Sala Azul");
            assertThat(r.clienteNome()).startsWith("Cliente ");
        });

        assertThat(queries)
                .as("MEDIDO, nao desejado: 1 listagem + 1 espaco (reusado do cache de 1o nivel) "
                        + "+ 1 por cliente distinto. Corrigir no 1B, nao aqui.")
                .isEqualTo(QUANTIDADE_DE_RESERVAS + 2);
    }

    @Test
    void entidadeForaDaTransacaoNaoSerializa() {
        List<Reserva> reservas = transacao.execute(status ->
                reservaRepository.findByEspacoIdAndStatusOrderByInicioAsc(espacoId, StatusReserva.CONFIRMADA));

        assertThatThrownBy(() -> reservas.getFirst().getCliente().getNome())
                .as("com open-in-view: false o persistence context ja fechou -- e por isso que "
                        + "o mapeamento para DTO tem que acontecer dentro do servico")
                .isInstanceOf(LazyInitializationException.class);
    }
}
