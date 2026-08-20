package io.github.furlanettoeduardo.reservas;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Limpa as tabelas entre cenários.
 *
 * <p>Substituiu três chamadas a {@code deleteAll()} por um TRUNCATE. O motivo é consequência da
 * refatoração: as portas de domínio não expõem {@code apagarTudo()}, e não deveriam — limpar a
 * base é necessidade de teste, não do domínio. Poluir a porta para servir o teste inverteria
 * quem manda em quem.
 *
 * <p>Efeito colateral bem-vindo: TRUNCATE é mais rápido que carregar todas as entidades para
 * apagá-las uma a uma, que é o que {@code deleteAll()} faz.
 */
final class LimpezaDeBase {

    private LimpezaDeBase() {
    }

    static void limpar(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE reserva, cliente, espaco RESTART IDENTITY CASCADE");
    }
}
