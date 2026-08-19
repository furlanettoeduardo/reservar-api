package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
import io.github.furlanettoeduardo.reservas.service.ConflitoDeReservaException;
import io.github.furlanettoeduardo.reservas.service.NovaReserva;
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Patologia n.6 do 1B: TOCTOU -- time of check to time of use.
 *
 * <p>{@code ReservaService.criar} verifica sobreposicao e depois grava. Sob READ_COMMITTED
 * (default do Postgres) a segunda transacao nao enxerga a linha ainda nao commitada da
 * primeira, entao as duas verificam, as duas veem livre, e as duas gravam.
 *
 * <p>O cruzamento e forcado por barreira, nao por sorte: sem sincronizacao o experimento
 * dependeria de as duas threads se intercalarem numa janela de milissegundos, e um teste que
 * so as vezes falha nao prova nada.
 *
 * <p>A barreira mora num proxy dinamico que embrulha o repositorio, e nao num spy do Mockito.
 * Motivo medido, nao teorico: {@code StubbedInvocationMatcher.answer()} e {@code synchronized},
 * entao as duas threads serializam dentro do proprio spy e nunca chegam juntas a barreira --
 * a primeira versao deste teste registrou "rejeitadas=2, confirmadas=0", que e o Mockito
 * segurando o lock, nao o banco. Ferramenta de teste com estado compartilhado nao serve para
 * testar concorrencia. O codigo de producao continua sem gancho de teste.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcorrenciaReservaIT {

    private static final Instant INICIO_A = Instant.parse("2026-09-01T13:00:00Z");
    private static final Instant FIM_A = Instant.parse("2026-09-01T15:00:00Z");
    private static final Instant INICIO_B = Instant.parse("2026-09-01T14:00:00Z");
    private static final Instant FIM_B = Instant.parse("2026-09-01T16:00:00Z");

    /** Publica a barreira para o proxy sem obrigar o wrapper a conhecer o teste. */
    private static final AtomicReference<CyclicBarrier> BARREIRA = new AtomicReference<>();

    @TestConfiguration
    static class RepositorioComBarreira {

        @Bean
        @Primary
        ReservaRepository reservaRepositoryComBarreira(
                @Qualifier("reservaRepository") ReservaRepository real) {
            return (ReservaRepository) Proxy.newProxyInstance(
                    ReservaRepository.class.getClassLoader(),
                    new Class<?>[]{ReservaRepository.class},
                    (proxy, metodo, argumentos) -> {
                        Object resultado;
                        try {
                            resultado = metodo.invoke(real, argumentos);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        CyclicBarrier barreira = BARREIRA.get();
                        if (barreira != null && metodo.getName().equals("existeSobreposicao")) {
                            barreira.await(15, TimeUnit.SECONDS);
                        }
                        return resultado;
                    });
        }
    }

    @Autowired
    private ReservaRepository reservaRepository;

    @Autowired
    private ReservaService service;
    @Autowired
    private EspacoRepository espacoRepository;
    @Autowired
    private ClienteRepository clienteRepository;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private JdbcTemplate jdbc;

    private Long espacoId;
    private Long clienteUm;
    private Long clienteDois;

    @BeforeEach
    void prepararDadosCommitados() {
        transacao.executeWithoutResult(status -> {
            reservaRepository.deleteAll();
            clienteRepository.deleteAll();
            espacoRepository.deleteAll();
        });
        transacao.executeWithoutResult(status -> {
            espacoId = espacoRepository.save(
                    new Espaco("Sala Azul", 30, new BigDecimal("150.00"))).getId();
            clienteUm = clienteRepository.save(new Cliente("Ana", "ana@exemplo.com")).getId();
            clienteDois = clienteRepository.save(new Cliente("Bruno", "bruno@exemplo.com")).getId();
        });
    }

    private long paresSobrepostosConfirmados() {
        return jdbc.queryForObject("""
                select count(*) from reserva a join reserva b
                  on a.id < b.id
                 and a.espaco_id = b.espaco_id
                 and a.status = 'CONFIRMADA' and b.status = 'CONFIRMADA'
                 and a.inicio < b.fim and b.inicio < a.fim
                """, Long.class);
    }

    @Test
    void sequencialmenteARegraSegura() {
        service.criar(new NovaReserva(espacoId, clienteUm, INICIO_A, FIM_A));

        assertThatThrownBy(() -> service.criar(new NovaReserva(espacoId, clienteDois, INICIO_B, FIM_B)))
                .as("controle: sem concorrencia a verificacao funciona -- e por isso que "
                        + "todo teste single-thread do 1A passa")
                .isInstanceOf(ConflitoDeReservaException.class);

        assertThat(paresSobrepostosConfirmados()).isZero();
    }

    @Test
    void concorrentementeAsDuasGravamEOEspacoFicaDuplamenteReservado() throws Exception {
        String isolamento = jdbc.queryForObject(
                "select current_setting('transaction_isolation')", String.class);

        // Segura cada thread depois de ela ter verificado e antes de gravar. Quando as duas
        // chegam aqui, as duas ja receberam false -- que e exatamente a janela do TOCTOU.
        BARREIRA.set(new CyclicBarrier(2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> resultados = pool.invokeAll(List.of(
                    tentativa(clienteUm, INICIO_A, FIM_A),
                    tentativa(clienteDois, INICIO_B, FIM_B)));

            List<Object> saidas = List.of(resultados.get(0).get(), resultados.get(1).get());
            long falhas = saidas.stream().filter(Exception.class::isInstance).count();

            long reservasConfirmadas = jdbc.queryForObject(
                    "select count(*) from reserva where status = 'CONFIRMADA'", Long.class);
            long pares = paresSobrepostosConfirmados();

            System.out.printf("%n[TOCTOU] isolamento=%s | rejeitadas=%d | confirmadas=%d "
                    + "| pares sobrepostos=%d%n", isolamento, falhas, reservasConfirmadas, pares);

            assertThat(isolamento).isEqualTo("read committed");
            assertThat(falhas).as("nenhuma das duas foi rejeitada pela regra").isZero();
            assertThat(reservasConfirmadas).isEqualTo(2);
            assertThat(pares)
                    .as("ESTADO INVALIDO GRAVADO: 13:00-15:00 e 14:00-16:00 no mesmo espaco. "
                            + "Quando a EXCLUDE entrar na V2, este numero vira 0 e uma das "
                            + "threads passa a receber DataIntegrityViolationException.")
                    .isEqualTo(1);
        } finally {
            BARREIRA.set(null);
            pool.shutdownNow();
        }
    }

    private Callable<Object> tentativa(Long clienteId, Instant inicio, Instant fim) {
        return () -> {
            try {
                return service.criar(new NovaReserva(espacoId, clienteId, inicio, fim));
            } catch (RuntimeException e) {
                return e;
            }
        };
    }
}
