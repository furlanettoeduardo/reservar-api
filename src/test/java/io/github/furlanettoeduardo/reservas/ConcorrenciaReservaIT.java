package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
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
 * Patologia n.6 do 1B: TOCTOU -- time of check to time of use -- e o efeito da V2 sobre ela.
 *
 * <p>{@code ReservaService.criar} verifica sobreposicao e depois grava. Sob READ_COMMITTED
 * (default do Postgres, conferido em teste proprio aqui) a segunda transacao nao enxerga a
 * linha ainda nao commitada da primeira, entao as duas verificam e as duas veem livre.
 *
 * <pre>
 * antes da V2:  rejeitadas=0 | confirmadas=2 | pares sobrepostos=1   &lt;- estado invalido
 * depois:       rejeitadas=1 | confirmadas=1 | pares sobrepostos=0
 * </pre>
 *
 * <p>A verificacao em Java continua perdendo a corrida, e nem tinha como nao perder. O que
 * mudou e que passou a existir algo segurando a linha. A regra virou caminho rapido com
 * mensagem boa; a EXCLUDE constraint da V2 e a garantia.
 *
 * <p><b>E o banco recusa de duas formas diferentes conforme o escalonamento.</b> Uma versao
 * anterior deste teste cobria as duas num caso so e era flaky -- passou e falhou com o mesmo
 * codigo, porque qual das duas ocorre depende de quem chega primeiro ao INSERT. Cada caminho
 * agora tem seu teste, forcado a acontecer. Os dois importam: chegam ao
 * {@code ApiExceptionHandler} como excecoes de familias distintas.
 *
 * <p>O cruzamento e forcado por gancho, nao por sorte. O gancho mora num proxy dinamico que
 * embrulha o repositorio, e nao num spy do Mockito: motivo medido, nao teorico --
 * {@code StubbedInvocationMatcher.answer()} e {@code synchronized}, entao as duas threads
 * serializam dentro do proprio spy e nunca chegam juntas a barreira. A primeira versao deste
 * teste registrou "rejeitadas=2, confirmadas=0", que e o Mockito segurando o lock, nao o
 * banco. Ferramenta de teste com estado compartilhado interno nao serve para testar
 * concorrencia. O codigo de producao continua sem gancho de teste.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcorrenciaReservaIT {

    private static final Instant INICIO_A = Instant.parse("2026-09-01T13:00:00Z");
    private static final Instant FIM_A = Instant.parse("2026-09-01T15:00:00Z");
    private static final Instant INICIO_B = Instant.parse("2026-09-01T14:00:00Z");
    private static final Instant FIM_B = Instant.parse("2026-09-01T16:00:00Z");

    /** Roda depois da verificacao de sobreposicao e antes da gravacao, por thread. */
    private static final ThreadLocal<Runnable> APOS_VERIFICAR = new ThreadLocal<>();

    @TestConfiguration
    static class RepositorioComGancho {

        @Bean
        @Primary
        ReservaRepository reservaRepositoryComGancho(
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
                        Runnable gancho = APOS_VERIFICAR.get();
                        if (gancho != null && metodo.getName().equals("existeSobreposicao")) {
                            gancho.run();
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

    /**
     * As duas gravam ao mesmo tempo. Cada INSERT insere a tupla e so entao checa a exclusao,
     * entao cada transacao encontra a tupla nao-commitada da outra e espera por ela: espera
     * mutua. O Postgres detecta o deadlock e mata uma -- o que chega como
     * {@link CannotAcquireLockException}, e nao como violacao de integridade.
     *
     * <p>Qual das duas morre e escolha do Postgres, entao o teste nao assume nenhuma.
     */
    @Test
    void simultaneasProduzemDeadlock() throws Exception {
        CyclicBarrier ambasVerificaram = new CyclicBarrier(2);
        Runnable esperarAOutra = () -> aguardar(ambasVerificaram);

        List<Exception> rejeicoes = executar(
                tentativa(clienteUm, INICIO_A, FIM_A, esperarAOutra, NADA),
                tentativa(clienteDois, INICIO_B, FIM_B, esperarAOutra, NADA));

        relatar("simultaneas", rejeicoes);

        assertThat(rejeicoes).hasSize(1);
        assertThat(rejeicoes.getFirst())
                .as("nao e ConflitoDeReservaException: as duas passaram na verificacao, entao "
                        + "quem recusou foi o banco -- e recusou por deadlock, nao por violacao")
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(confirmadas()).isEqualTo(1);
        assertThat(paresSobrepostosConfirmados())
                .as("o estado invalido que este mesmo teste gravava antes da V2")
                .isZero();
    }

    /**
     * As duas verificam, mas a primeira commita antes de a segunda gravar. Sem espera mutua
     * nao ha deadlock: o Postgres recusa por violacao da constraint. E o caminho mais provavel
     * em producao, e o unico que o handler previa antes.
     */
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

    private static final Runnable NADA = () -> {
    };

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
