package io.github.furlanettoeduardo.reservas.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Intervalo meio-aberto [inicio, fim). Value object.
 *
 * <p>Aqui record e a escolha certa, pelos motivos exatamente inversos aos da entidade:
 * imutabilidade e o que se quer (um periodo nao "muda", vira outro periodo), igualdade e por
 * valor (dois periodos com as mesmas bordas sao o mesmo periodo, sem id envolvido), e nenhum
 * framework precisa instanciar por reflexao nem gerar subclasse.
 *
 * <p>Nao e {@code @Embeddable} de proposito: mantido fora do mapeamento, o tipo nao carrega
 * dependencia de JPA e pode ser usado no comando de entrada e nos testes unitarios sem
 * contexto de persistencia. A {@link Reserva} guarda duas colunas.
 */
public record Periodo(Instant inicio, Instant fim) {

    public Periodo {
        Objects.requireNonNull(inicio, "inicio e obrigatorio");
        Objects.requireNonNull(fim, "fim e obrigatorio");
        if (!fim.isAfter(inicio)) {
            throw new IllegalArgumentException(
                    "fim (%s) deve ser posterior ao inicio (%s)".formatted(fim, inicio));
        }
    }

    public Duration duracao() {
        return Duration.between(inicio, fim);
    }

    /**
     * [a1, a2) e [b1, b2) se sobrepoem quando {@code a1 < b2 && b1 < a2}. Comparacoes
     * estritas: dois periodos que so se tocam na borda nao se sobrepoem.
     *
     * <p>Espelha em memoria a condicao que a JPQL de
     * {@code ReservaRepository.existeSobreposicao} executa no banco.
     */
    public boolean sobrepoe(Periodo outro) {
        return inicio.isBefore(outro.fim) && outro.inicio.isBefore(fim);
    }
}
