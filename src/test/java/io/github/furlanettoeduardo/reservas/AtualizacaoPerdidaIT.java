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
    void duasEdicoesDeCamposDiferentesEUmaApagaAOutra() throws Exception {
        CyclicBarrier ambasCarregaram = new CyclicBarrier(2);

        List<Exception> falhas = executar(
                editor(ambasCarregaram, e -> e.setNome(NOME_NOVO)),
                editor(ambasCarregaram, e -> e.setCapacidade(CAPACIDADE_NOVA)));

        String nome = jdbc.queryForObject(
                "select nome from espaco where id = ?", String.class, espacoId);
        int capacidade = jdbc.queryForObject(
                "select capacidade from espaco where id = ?", Integer.class, espacoId);

        boolean nomeSobreviveu = NOME_NOVO.equals(nome);
        boolean capacidadeSobreviveu = capacidade == CAPACIDADE_NOVA;

        System.out.printf("%n[lost update] falhas=%d | nome='%s' | capacidade=%d "
                        + "| sobreviveram=%d de 2%n",
                falhas.size(), nome, capacidade,
                (nomeSobreviveu ? 1 : 0) + (capacidadeSobreviveu ? 1 : 0));

        assertThat(falhas)
                .as("nenhuma das duas transacoes falhou: as duas 'deram certo'")
                .isEmpty();

        assertThat(nomeSobreviveu ^ capacidadeSobreviveu)
                .as("EXATAMENTE UMA das duas edicoes sobreviveu. Nao havia conflito logico -- "
                        + "campos diferentes -- mas o UPDATE reescreve todas as colunas a partir "
                        + "do snapshot que cada transacao carregou, e a segunda a commitar "
                        + "sobrescreve o campo da primeira com o valor velho. Silenciosamente.")
                .isTrue();
    }

    /** Controle: em sequencia, as duas edicoes coexistem. */
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
                transacao.executeWithoutResult(status -> {
                    Espaco espaco = espacoRepository.findById(espacoId).orElseThrow();
                    aguardar(ambasCarregaram);
                    mutacao.accept(espaco);
                    // commit ao retornar: dirty checking emite o UPDATE de todas as colunas
                });
                return "ok";
            } catch (RuntimeException e) {
                return e;
            }
        };
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
