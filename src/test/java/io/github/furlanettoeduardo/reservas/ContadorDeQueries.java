package io.github.furlanettoeduardo.reservas;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import java.util.function.Supplier;

/**
 * Conta statements preparados durante uma acao. Exige
 * {@code hibernate.generate_statistics=true} no contexto do teste.
 *
 * <p>Existe para que "quantas queries isso custa" vire assercao em vez de observacao no log:
 * numero em log e lido quando alguem lembra de olhar; numero em assercao quebra o build.
 */
final class ContadorDeQueries {

    private final Statistics estatisticas;

    ContadorDeQueries(EntityManagerFactory emf) {
        this.estatisticas = emf.unwrap(SessionFactory.class).getStatistics();
    }

    <T> Medicao<T> medir(Supplier<T> acao) {
        estatisticas.clear();
        T resultado = acao.get();
        return new Medicao<>(resultado, estatisticas.getPrepareStatementCount());
    }

    record Medicao<T>(T resultado, long queries) {
    }
}
