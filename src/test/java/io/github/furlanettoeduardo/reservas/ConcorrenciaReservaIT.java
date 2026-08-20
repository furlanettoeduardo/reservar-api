package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepositorioJpa;
import io.github.furlanettoeduardo.reservas.service.ConflitoDeReservaException;
import io.github.furlanettoeduardo.reservas.service.NovaReserva;
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Patologia nº 6 do 1B: TOCTOU, e o efeito da `V2` sobre ela.
 *
 * <pre>
 * antes da V2:  rejeitadas=0 | confirmadas=2 | pares sobrepostos=1   &lt;- estado inválido
 * depois:       rejeitadas=1 | confirmadas=1 | pares sobrepostos=0
 * </pre>
 *
 * <p>A verificação em Java continua perdendo a corrida, e nem tinha como não perder. O que mudou
 * é que passou a existir algo segurando a linha: a `EXCLUDE` constraint. A regra virou caminho
 * rápido com mensagem boa; a constraint é a garantia.
 *
 * <p><b>O banco recusa de duas formas, e só uma é forçável.</b> Escalonadas dão
 * {@code exclusion_violation}; simultâneas podem dar deadlock, porque cada INSERT grava a tupla e
 * só então checa a exclusão — e essa janela é interna ao Postgres. Uma versão anterior assertava
 * {@code CannotAcquireLockException} e passou três vezes local antes de quebrar no CI. Por isso o
 * teste das simultâneas assere o <b>invariante</b> e apenas registra o mecanismo.
 *
 * <p>O gancho que força o cruzamento agora embrulha a <b>porta</b>, e não o repositório Spring
 * Data. É consequência da refatoração e uma melhora: a barreira passou a ficar exatamente na
 * fronteira que o serviço usa, em vez de num detalhe do adaptador. Continua sendo
 * {@code Proxy.newProxyInstance} e não spy do Mockito, porque
 * {@code StubbedInvocationMatcher.answer()} é {@code synchronized} — com o spy as duas threads
 * serializam dentro do próprio Mockito e nunca chegam juntas à barreira.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcorrenciaReservaIT {

    private static final Instant INICIO_A = Instant.parse("2026-09-01T13:00:00Z");
    private static final Instant FIM_A = Instant.parse("2026-09-01T15:00:00Z");
    private static final Instant INICIO_B = Instant.parse("2026-09-01T14:00:00Z");
    private static final Instant FIM_B = Instant.parse("2026-09-01T16:00:00Z");

    /** Roda depois da verificação de sobreposição e antes da gravação, por thread. */
    private static final ThreadLocal<Runnable> APOS_VERIFICAR = new ThreadLocal<>();

    private static final Runnable NADA = () -> {
    };

    @TestConfiguration
    static class PortaComGancho {

        @Bean
        @Primary
        ReservaRepositorio reservaRepositorioComGancho(
                @Qualifier("reservaRepositorioJpa") ReservaRepositorio real) {
            return (ReservaRepositorio) Proxy.newProxyInstance(
                    ReservaRepositorio.class.getClassLoader(),
                    new Class<?>[]{ReservaRepositorio.class},
                    (proxy, metodo, argumentos) -> {
                        Object resultado;
                        try {
                            resultado = metodo.invoke(real, argumentos);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        Runnable gancho = APOS_VERIFICAR.get();
                        if (gancho != null && metodo.getName().equals("existeSobreposicao")) {
                            gancho.run();
                        }
                        return resultado;
                    });
        }
    }

    @Autowired
    private ReservaService service;
    @Autowired
    private EspacoRepositorio espacos;
    @Autowired
    private ClienteRepositorio clientes;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private JdbcTemplate jdbc;

    private Long espacoId;
    private Long clienteUm;
    private Long clienteDois;

    @BeforeEach
    void prepararDadosCommitados() {
        LimpezaDeBase.limpar(jdbc);
        transacao.executeWithoutResult(status -> {
            espacoId = espacos.salvar(
                    new Espaco("Sala Azul", 30, new BigDecimal("150.00"))).getId();
            clienteUm = clientes.salvar(new Cliente("Ana", "ana@exemplo.com")).getId();
            clienteDois = clientes.salvar(new Cliente("Bruno", "bruno@exemplo.com")).getId();
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

    private long confirmadas() {
        return jdbc.queryForObject(
                "select count(*) from reserva where status = 'CONFIRMADA'", Long.class);
    }

    @Test
    void isolamentoEhReadCommitted() {
        assertThat(jdbc.queryForObject(
                "select current_setting('transaction_isolation')", String.class))
                .as("o resultado dos outros testes depende disto: em SERIALIZABLE a historia "
                        + "seria outra, e o teste deve dizer o motivo em vez de so quebrar")
                .isEqualTo("read committed");
    }

    @Test
    void sequencialmenteARegraSegura() {
        service.criar(new NovaReserva(espacoId, clienteUm, INICIO_A, FIM_A));

        assertThatThrownBy(() -> service.criar(
                new NovaReserva(espacoId, clienteDois, INICIO_B, FIM_B)))
                .as("controle: sem concorrencia a verificacao funciona -- e por isso que todo "
                        + "teste single-thread do 1A passa. A constraint nem chega a ser tocada.")
                .isInstanceOf(ConflitoDeReservaException.class);

        assertThat(paresSobrepostosConfirmados()).isZero();
    }

    @Test
    void simultaneasSaoRecusadasPeloBanco() throws Exception {
        CyclicBarrier ambasVerificaram = new CyclicBarrier(2);
        Runnable esperarAOutra = () -> aguardar(ambasVerificaram);

        List<Exception> rejeicoes = executar(
                tentativa(clienteUm, INICIO_A, FIM_A, esperarAOutra, NADA),
                tentativa(clienteDois, INICIO_B, FIM_B, esperarAOutra, NADA));

        relatar("simultaneas", rejeicoes);

        assertThat(rejeicoes).hasSize(1);
        assertThat(rejeicoes.getFirst())
                .as("quem recusou foi o BANCO, nao a regra: as duas passaram na verificacao, "
                        + "entao um ConflitoDeReservaException aqui significaria que a corrida "
                        + "nao aconteceu. Qual DataAccessException chega -- deadlock "
                        + "(CannotAcquireLockException) ou violacao -- nao esta sob controle do "
                        + "teste e nao e assertado.")
                .isInstanceOf(DataAccessException.class);
        assertThat(confirmadas()).isEqualTo(1);
        assertThat(paresSobrepostosConfirmados())
                .as("o estado invalido que este mesmo teste gravava antes da V2")
                .isZero();
    }

    @Test
    void escalonadasProduzemViolacaoDaConstraint() throws Exception {
        CyclicBarrier ambasVerificaram = new CyclicBarrier(2);
        CountDownLatch primeiraCommitou = new CountDownLatch(1);

        Runnable soEsperarAOutra = () -> aguardar(ambasVerificaram);
        Runnable esperarOCommitDaOutra = () -> {
            aguardar(ambasVerificaram);
            aguardar(primeiraCommitou);
        };

        List<Exception> rejeicoes = executar(
                tentativa(clienteUm, INICIO_A, FIM_A, soEsperarAOutra, primeiraCommitou::countDown),
                tentativa(clienteDois, INICIO_B, FIM_B, esperarOCommitDaOutra, NADA));

        relatar("escalonadas", rejeicoes);

        assertThat(rejeicoes).hasSize(1);
        assertThat(rejeicoes.getFirst())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reserva_sem_sobreposicao");
        assertThat(confirmadas()).isEqualTo(1);
        assertThat(paresSobrepostosConfirmados()).isZero();
    }

    private void relatar(String cenario, List<Exception> rejeicoes) {
        System.out.printf("%n[TOCTOU %s] rejeitadas=%d (%s) | confirmadas=%d | pares=%d%n",
                cenario, rejeicoes.size(),
                rejeicoes.isEmpty() ? "-" : rejeicoes.getFirst().getClass().getSimpleName(),
                confirmadas(), paresSobrepostosConfirmados());
    }

    private static void aguardar(CyclicBarrier barreira) {
        try {
            barreira.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barreira nao liberou", e);
        }
    }

    private static void aguardar(CountDownLatch trava) {
        try {
            if (!trava.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("trava nao liberou");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<Exception> executar(Callable<Object> uma, Callable<Object> outra) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futuros = pool.invokeAll(List.of(uma, outra));
            return List.of(futuros.get(0).get(), futuros.get(1).get()).stream()
                    .filter(Exception.class::isInstance)
                    .map(Exception.class::cast)
                    .toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Object> tentativa(Long clienteId, Instant inicio, Instant fim,
                                       Runnable aposVerificar, Runnable aposTerminar) {
        return () -> {
            APOS_VERIFICAR.set(aposVerificar);
            try {
                return service.criar(new NovaReserva(espacoId, clienteId, inicio, fim));
            } catch (RuntimeException e) {
                return e;
            } finally {
                APOS_VERIFICAR.remove();
                aposTerminar.run();
            }
        };
    }
}
