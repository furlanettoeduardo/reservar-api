package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
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
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia nº 3 do 1B, segunda metade: o custo do UPDATE cego, e o que a `V3` fez com ele.
 *
 * <p>Duas transações editando campos <b>diferentes</b> da mesma linha não conflitam logicamente,
 * e antes da `V3` uma apagava a outra em silêncio. Três estados medidos:
 *
 * <pre>
 * antes da V3:   falhas=0 | uma edição sobreviveu | a outra desapareceu em silêncio
 * depois da V3:  falhas=1 | uma edição sobreviveu | a outra foi REPORTADA como conflito
 * V3 + retry:    falhas=0 | as DUAS edições sobreviveram
 * </pre>
 *
 * <p>{@code @Version} não preserva as duas escritas: troca perda silenciosa por conflito
 * reportado. Preservar as duas é trabalho do retry.
 *
 * <p><b>A refatoração hexagonal mudou a forma deste teste, não o resultado.</b> O domínio virou
 * imutável, então {@code espaco.setNome(...)} deixou de existir e virou
 * {@code espacos.salvar(espaco.comNome(...))}. A proteção continua funcionando porque o
 * {@code salvar} do adaptador rebusca a instância gerenciada <b>na mesma transação</b> que a
 * carregou — o persistence context devolve o mesmo objeto, e o {@code @Version} vê a versão que
 * a transação leu. Se o salvar rodasse em outra transação, o lost update voltaria; está
 * registrado como limitação no ADR 0004.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AtualizacaoPerdidaIT {

    private static final String NOME_ORIGINAL = "Sala Azul";
    private static final int CAPACIDADE_ORIGINAL = 30;
    private static final String NOME_NOVO = "Sala Azul Reformada";
    private static final int CAPACIDADE_NOVA = 99;

    @Autowired
    private EspacoRepositorio espacos;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private JdbcTemplate jdbc;

    private Long espacoId;

    @BeforeEach
    void semear() {
        LimpezaDeBase.limpar(jdbc);
        transacao.executeWithoutResult(status -> espacoId = espacos.salvar(
                new Espaco(NOME_ORIGINAL, CAPACIDADE_ORIGINAL, new BigDecimal("150.00"))).getId());
    }

    @Test
    void duasEdicoesConcorrentes_aSegundaFalhaEmVezDeSobrescrever() throws Exception {
        CyclicBarrier ambasCarregaram = new CyclicBarrier(2);

        List<Exception> falhas = executar(
                editor(ambasCarregaram, e -> e.comNome(NOME_NOVO)),
                editor(ambasCarregaram, e -> e.comCapacidade(CAPACIDADE_NOVA)));

        String nome = nomeGravado();
        int capacidade = capacidadeGravada();
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
     * O terceiro estado, e o único em que as duas edições coexistem: detectar, recarregar e
     * reaplicar. {@code @Version} dá a informação, o retry usa a informação.
     */
    @Test
    void comRetryAsDuasEdicoesSobrevivem() throws Exception {
        CyclicBarrier ambasCarregaram = new CyclicBarrier(2);

        List<Exception> falhas = executar(
                editorComRetry(ambasCarregaram, e -> e.comNome(NOME_NOVO)),
                editorComRetry(ambasCarregaram, e -> e.comCapacidade(CAPACIDADE_NOVA)));

        System.out.printf("[lost update] com retry: falhas=%d | nome='%s' | capacidade=%d%n",
                falhas.size(), nomeGravado(), capacidadeGravada());

        assertThat(falhas).isEmpty();
        assertThat(nomeGravado()).isEqualTo(NOME_NOVO);
        assertThat(capacidadeGravada())
                .as("as duas edicoes coexistem: a que perdeu recarregou o estado ja com a "
                        + "alteracao da outra e reaplicou a sua")
                .isEqualTo(CAPACIDADE_NOVA);
    }

    /** Controle: em sequência, as duas edições coexistem sem retry nenhum. */
    @Test
    void sequencialmenteAsDuasEdicoesCoexistem() {
        editarUmaVez(null, e -> e.comNome(NOME_NOVO));
        editarUmaVez(null, e -> e.comCapacidade(CAPACIDADE_NOVA));

        assertThat(nomeGravado()).isEqualTo(NOME_NOVO);
        assertThat(capacidadeGravada()).isEqualTo(CAPACIDADE_NOVA);
    }

    private String nomeGravado() {
        return jdbc.queryForObject("select nome from espaco where id = ?", String.class, espacoId);
    }

    private int capacidadeGravada() {
        return jdbc.queryForObject(
                "select capacidade from espaco where id = ?", Integer.class, espacoId);
    }

    private Callable<Object> editor(CyclicBarrier ambasCarregaram, UnaryOperator<Espaco> edicao) {
        return () -> {
            try {
                editarUmaVez(ambasCarregaram, edicao);
                return "ok";
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    private Callable<Object> editorComRetry(CyclicBarrier ambasCarregaram,
                                            UnaryOperator<Espaco> edicao) {
        return () -> {
            try {
                editarUmaVez(ambasCarregaram, edicao);
            } catch (ObjectOptimisticLockingFailureException conflito) {
                // Recarrega em transacao nova, ja com a alteracao da outra thread visivel, e
                // reaplica a sua. Sem barreira desta vez: a corrida ja aconteceu.
                editarUmaVez(null, edicao);
            } catch (RuntimeException e) {
                return e;
            }
            return "ok";
        };
    }

    /**
     * Carrega, espera a outra thread, e grava a versão editada. O {@code salvar} dentro da mesma
     * transação é o que faz o {@code @Version} enxergar a versão lida.
     */
    private void editarUmaVez(CyclicBarrier ambasCarregaram, UnaryOperator<Espaco> edicao) {
        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacos.porId(espacoId).orElseThrow();
            if (ambasCarregaram != null) {
                aguardar(ambasCarregaram);
            }
            espacos.salvar(edicao.apply(espaco));
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
