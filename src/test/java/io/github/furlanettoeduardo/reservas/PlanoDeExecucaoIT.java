package io.github.furlanettoeduardo.reservas;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia n.9 do 1B: indice e plano de execucao.
 *
 * <p>O experimento nao e "criar o indice e medir" -- {@code idx_reserva_espaco_periodo} existe
 * desde a V1, e criar-e-medir exigiria fingir que ele nao estava la. E <b>remover e medir</b>,
 * que mede o que ele de fato compra.
 *
 * <p>E a pergunta ficou melhor do que a original depois da V2: a EXCLUDE constraint criou um
 * indice GiST sobre {@code (espaco_id, tstzrange(inicio, fim))}, que cobre a mesma consulta.
 * Entao a pergunta e <b>o B-tree ainda se paga?</b> Se o GiST atender bem, o B-tree e indice
 * redundante ocupando espaco e custando escrita em cada INSERT -- e isso e otimizacao real, nao
 * exercicio.
 *
 * <p>Precisa de volume para ser honesto: com 50 linhas o planejador escolhe Seq Scan de
 * qualquer jeito, porque a tabela cabe em poucas paginas. O cenario semeia 40.000 reservas por
 * SQL direto e roda ANALYZE antes de medir.
 *
 * <p>Um teste so, com restauracao em {@code finally}: as tres medicoes derrubam objetos de
 * schema que os outros ITs dependem, e o container e compartilhado entre eles.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlanoDeExecucaoIT {

    private static final int ESPACOS = 200;
    private static final int RESERVAS_POR_ESPACO = 200;

    private static final String CONSULTA_DE_SOBREPOSICAO = """
            select count(*) > 0 from reserva r
            where r.espaco_id = %d
              and r.status = 'CONFIRMADA'
              and r.inicio < timestamptz '2026-01-05 12:00:00+00'
              and r.fim    > timestamptz '2026-01-05 10:00:00+00'
            """;

    /**
     * A mesma pergunta escrita como sobreposicao de intervalo, e nao como comparacao escalar.
     * O indice GiST da EXCLUDE indexa a <b>expressao</b> {@code tstzrange(inicio, fim, '[)')} --
     * para ele ser candidato, o predicado precisa mencionar essa expressao.
     */
    private static final String CONSULTA_COMO_INTERVALO = """
            select count(*) > 0 from reserva r
            where r.espaco_id = %d
              and r.status = 'CONFIRMADA'
              and tstzrange(r.inicio, r.fim, '[)')
                  && tstzrange(timestamptz '2026-01-05 10:00:00+00',
                               timestamptz '2026-01-05 12:00:00+00', '[)')
            """;

    private static final String CRIAR_BTREE =
            "CREATE INDEX idx_reserva_espaco_periodo ON reserva (espaco_id, inicio, fim)";

    private static final String CRIAR_EXCLUDE = """
            ALTER TABLE reserva ADD CONSTRAINT reserva_sem_sobreposicao
                EXCLUDE USING gist (
                    espaco_id WITH =,
                    tstzrange(inicio, fim, '[)') WITH &&
                ) WHERE (status = 'CONFIRMADA')
            """;

    @Autowired
    private JdbcTemplate jdbc;

    private record Plano(String cenario, String no, double milissegundos, String textoCompleto) {

        boolean usaSeqScan() {
            return no.contains("Seq Scan");
        }
    }

    private Plano explicar(String cenario, long espacoId) {
        return explicar(cenario, CONSULTA_DE_SOBREPOSICAO, espacoId);
    }

    private Plano explicar(String cenario, String consulta, long espacoId) {
        List<String> linhas = jdbc.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS) " + consulta.formatted(espacoId), String.class);

        String texto = String.join("\n", linhas);
        String no = linhas.stream()
                .filter(l -> l.contains("Scan"))
                .findFirst()
                .orElse(linhas.getFirst())
                .trim();
        double ms = linhas.stream()
                .filter(l -> l.startsWith("Execution Time:"))
                .mapToDouble(l -> Double.parseDouble(l.replaceAll("[^0-9.]", "")))
                .findFirst()
                .orElse(-1);

        return new Plano(cenario, no, ms, texto);
    }

    private void semear() {
        jdbc.execute("TRUNCATE reserva, cliente, espaco RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO espaco (nome, capacidade, preco_hora)
                SELECT 'Sala ' || n, 10, 150.00 FROM generate_series(1, ?) n
                """, ESPACOS);
        jdbc.update("""
                INSERT INTO cliente (nome, email)
                SELECT 'Cliente ' || n, 'c' || n || '@exemplo.com' FROM generate_series(1, ?) n
                """, ESPACOS);
        // Constantes interpoladas em vez de parametros: o modulo precisa dos dois lados com
        // tipo conhecido, e ? vindo do driver chega como unknown.
        jdbc.update("""
                INSERT INTO reserva (espaco_id, cliente_id, inicio, fim, status, valor_total)
                SELECT e.id,
                       c.id,
                       timestamptz '2026-01-01 00:00:00+00' + (h * interval '1 hour'),
                       timestamptz '2026-01-01 00:00:00+00' + ((h + 1) * interval '1 hour'),
                       'CONFIRMADA',
                       150.00
                  FROM (SELECT id, row_number() OVER (ORDER BY id) rn FROM espaco) e
                  CROSS JOIN generate_series(0, %d) h
                  JOIN (SELECT id, row_number() OVER (ORDER BY id) rn FROM cliente) c
                    ON c.rn = ((h %% %d) + 1)
                """.formatted(RESERVAS_POR_ESPACO - 1, ESPACOS));
        jdbc.execute("ANALYZE reserva");
    }

    @Test
    void mede_seOBtreeAindaSePagaDepoisDaExcludeConstraint() {
        semear();
        long espacoId = jdbc.queryForObject("select min(id) from espaco", Long.class);
        long linhas = jdbc.queryForObject("select count(*) from reserva", Long.class);
        assertThat(linhas).isEqualTo((long) ESPACOS * RESERVAS_POR_ESPACO);

        Plano comAmbos;
        Plano soGist;
        Plano soGistComoIntervalo;
        Plano semIndice;
        try {
            comAmbos = explicar("escalar, B-tree+GiST", espacoId);

            jdbc.execute("DROP INDEX idx_reserva_espaco_periodo");
            jdbc.execute("ANALYZE reserva");
            soGist = explicar("escalar, so GiST", espacoId);
            soGistComoIntervalo = explicar(
                    "intervalo, so GiST", CONSULTA_COMO_INTERVALO, espacoId);

            jdbc.execute("ALTER TABLE reserva DROP CONSTRAINT reserva_sem_sobreposicao");
            jdbc.execute("ANALYZE reserva");
            semIndice = explicar("escalar, nenhum indice", espacoId);
        } finally {
            restaurarSchema();
        }

        System.out.printf("%n[EXPLAIN] %d reservas, %d espacos%n", linhas, ESPACOS);
        for (Plano p : List.of(comAmbos, soGist, soGistComoIntervalo, semIndice)) {
            System.out.printf("  %-22s %8.3f ms  %s%n", p.cenario(), p.milissegundos(), p.no());
        }
        System.out.printf("%n--- plano com ambos ---%n%s%n", comAmbos.textoCompleto());
        System.out.printf("%n--- plano so com GiST, predicado escalar ---%n%s%n",
                soGist.textoCompleto());
        System.out.printf("%n--- plano so com GiST, predicado de intervalo ---%n%s%n",
                soGistComoIntervalo.textoCompleto());

        // Unica assercao sobre forma de plano que o teste controla: sem indice nenhum o
        // planejador nao tem alternativa. As outras duas sao escolha dele, entao ficam
        // registradas e nao assertadas -- ver a regra 3 em docs/jpa-patologias.md.
        assertThat(semIndice.usaSeqScan())
                .as("sem indice nao existe outro caminho: %s", semIndice.no())
                .isTrue();

        assertThat(comAmbos.milissegundos()).isPositive();
        assertThat(soGist.milissegundos()).isPositive();
        assertThat(soGistComoIntervalo.milissegundos()).isPositive();
        assertThat(semIndice.milissegundos()).isPositive();
    }

    private void restaurarSchema() {
        jdbc.execute("TRUNCATE reserva, cliente, espaco RESTART IDENTITY CASCADE");
        if (!existe("select 1 from pg_class where relname = 'idx_reserva_espaco_periodo'")) {
            jdbc.execute(CRIAR_BTREE);
        }
        if (!existe("select 1 from pg_constraint where conname = 'reserva_sem_sobreposicao'")) {
            jdbc.execute(CRIAR_EXCLUDE);
        }
        jdbc.execute("ANALYZE reserva");
    }

    private boolean existe(String sql) {
        return !jdbc.queryForList(sql).isEmpty();
    }
}
