package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia n.3 do 1B, segunda metade: o custo real do UPDATE cego.
 *
 * <p>A primeira metade -- dirty checking emite UPDATE sem {@code save()} -- está em
 * {@code ReservaServiceIT}. Esta mede a consequência, que até agora estava <b>raciocinada e não
 * medida</b>: como o Hibernate reescreve todas as colunas em qualquer alteração, duas
 * transações editando campos <b>diferentes</b> da mesma linha não conflitam logicamente, e ainda
 * assim uma apaga a outra.
 *
 * <p>Mesma família do {@code total++} sem sincronização, com a região crítica sendo a linha
 * inteira em vez de um {@code int}.
 *
 * <p>Uma thread muda {@code nome}, a outra muda {@code capacidade}. Nada em conflito. A barreira
 * garante que as duas carreguem antes de qualquer uma gravar -- sem isso o experimento
 * dependeria de sorte, e teste de concorrência que depende de sorte não prova nada.
 *
 * <p>Três estados medidos:
 *
 * <pre>
 * antes da V3:        falhas=0 | uma edição sobreviveu | a outra desapareceu em silêncio
 * depois da V3:       falhas=1 | uma edição sobreviveu | a outra foi REPORTADA como conflito
 * V3 + retry:         falhas=0 | as DUAS edições sobreviveram
 * </pre>
 *
 * <p>O meio da tabela é o que costuma ser mal entendido: {@code @Version} <b>não</b> preserva as
 * duas escritas. Ele troca perda silenciosa por conflito reportado. Preservar as duas é trabalho
 * do retry, que usa a informação que o {@code @Version} passou a dar.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AtualizacaoPerdidaIT {

    private static final String NOME_ORIGINAL = "Sala Azul";
    private static final int CAPACIDADE_ORIGINAL = 30;
    private static final String NOME_NOVO = "Sala Azul Reformada";
    private static final int CAPACIDADE_NOVA = 99;

    @Autowired
    private EspacoRepository espacoRepository;
    @Autowired
    private ReservaRepository reservaRepository;
    @Autowired
    private ClienteRepository clienteRepository;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private JdbcTemplate jdbc;

    private Long espacoId;

    @BeforeEach
    void semear() {
        transacao.executeWithoutResult(status -> {
            reservaRepository.deleteAll();
            clienteRepository.deleteAll();
            espacoRepository.deleteAll();
        });
        transacao.executeWithoutResult(status -> espacoId = espacoRepository.save(
                new Espaco(NOME_ORIGINAL, CAPACIDADE_ORIGINAL, new BigDecimal("150.00"))).getId());
    }

    @Test
    void duasEdicoesConcorrentes_aSegundaFalhaEmVezDeSobrescrever() throws Exception {
        CyclicBarrier ambasCarregaram = new CyclicBarrier(2);

        List<Exception> falhas = executar(
                editor(ambasCarregaram, e -> e.setNome(NOME_NOVO)),
                editor(ambasCarregaram, e -> e.setCapacidade(CAPACIDADE_NOVA)));

        String nome = jdbc.queryForObject(
                "select nome from espaco where id = ?", String.class, espacoId);
        int capacidade = jdbc.queryForObject(
                "select capacidade from espaco where id = ?", Integer.class, espacoId);
        long versao = jdbc.queryForObject(
                "select versao from espaco where id = ?", Long.class, espacoId);

        boolean nomeSobreviveu = NOME_NOVO.equals(nome);
        boolean capacidadeSobreviveu = capacidade == CAPACIDADE_NOVA;

        System.out.printf("%n[lost update] apos V3: falhas=%d | nome='%s' | capacidade=%d "
                + "| versao=%d%n", falhas.size(), nome, capacidade, versao);

        assertThat(falhas)
                .as("ANTES da V3 este numero era 0: as duas transacoes 'davam certo'")
                .hasSize(1);
        assertThat(falhas.getFirst())
                .as("o UPDATE virou 'where id = ? and versao = ?', afetou 0 linhas, e o "
                        + "Hibernate reclamou em vez de sobrescrever")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(nomeSobreviveu ^ capacidadeSobreviveu)
                .as("o estado final e o MESMO de antes: uma edicao so. @Version nao preserva as "
                        + "duas escritas -- ele transforma perda silenciosa em conflito "
                        + "reportado. Preservar as duas exige retentar.")
                .isTrue();
        assertThat(versao).as("uma gravacao bem-sucedida incrementou a versao").isEqualTo(1);
    }

    /**
     * O terceiro estado, e o unico em que as duas edicoes coexistem: detectar, recarregar e
     * reaplicar. E o que fecha o raciocinio -- {@code @Version} da a informacao, o retry usa a
     * informacao.
     */
    @Test
    void comRetryAsDuasEdicoesSobrevivem() throws Exception {
        CyclicBarrier ambasCarregaram = new CyclicBarrier(2);

        List<Exception> falhas = executar(
                editorComRetry(ambasCarregaram, e -> e.setNome(NOME_NOVO)),
                editorComRetry(ambasCarregaram, e -> e.setCapacidade(CAPACIDADE_NOVA)));

        String nome = jdbc.queryForObject(
                "select nome from espaco where id = ?", String.class, espacoId);
        int capacidade = jdbc.queryForObject(
                "select capacidade from espaco where id = ?", Integer.class, espacoId);

        System.out.printf("[lost update] com retry: falhas=%d | nome='%s' | capacidade=%d%n",
                falhas.size(), nome, capacidade);

        assertThat(falhas).isEmpty();
        assertThat(nome).isEqualTo(NOME_NOVO);
        assertThat(capacidade)
                .as("as duas edicoes coexistem: a que perdeu recarregou o estado ja com a "
                        + "alteracao da outra e reaplicou a sua")
                .isEqualTo(CAPACIDADE_NOVA);
    }

    /** Controle: em sequencia, as duas edicoes coexistem sem retry nenhum. */
    @Test
    void sequencialmenteAsDuasEdicoesCoexistem() {
        transacao.executeWithoutResult(status ->
                espacoRepository.findById(espacoId).orElseThrow().setNome(NOME_NOVO));
        transacao.executeWithoutResult(status ->
                espacoRepository.findById(espacoId).orElseThrow().setCapacidade(CAPACIDADE_NOVA));

        Espaco depois = transacao.execute(status ->
                espacoRepository.findById(espacoId).orElseThrow());

        assertThat(depois.getNome()).isEqualTo(NOME_NOVO);
        assertThat(depois.getCapacidade()).isEqualTo(CAPACIDADE_NOVA);
    }

    private Callable<Object> editor(CyclicBarrier ambasCarregaram, Consumer<Espaco> mutacao) {
        return () -> {
            try {
                editarUmaVez(ambasCarregaram, mutacao);
                return "ok";
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    private Callable<Object> editorComRetry(CyclicBarrier ambasCarregaram,
                                           Consumer<Espaco> mutacao) {
        return () -> {
            try {
                editarUmaVez(ambasCarregaram, mutacao);
            } catch (ObjectOptimisticLockingFailureException conflito) {
                // Recarrega em transacao nova, ja com a alteracao da outra thread visivel, e
                // reaplica a sua. Sem barreira desta vez: a corrida ja aconteceu.
                transacao.executeWithoutResult(status ->
                        mutacao.accept(espacoRepository.findById(espacoId).orElseThrow()));
            } catch (RuntimeException e) {
                return e;
            }
            return "ok";
        };
    }

    private void editarUmaVez(CyclicBarrier ambasCarregaram, Consumer<Espaco> mutacao) {
        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacoRepository.findById(espacoId).orElseThrow();
            aguardar(ambasCarregaram);
            mutacao.accept(espaco);
            // commit ao retornar: dirty checking emite o UPDATE de todas as colunas,
            // agora com "and versao = ?" no where
        });
    }

    private static void aguardar(CyclicBarrier barreira) {
        try {
            barreira.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barreira nao liberou", e);
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
}
