package io.github.furlanettoeduardo.reservas;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia n.7 do 1B, parte sem banco: os dois extremos do contrato de {@code hashCode},
 * medidos lado a lado.
 *
 * <p>As duas pontas <b>não</b> falham do mesmo jeito, e essa é a parte que costuma ser dita
 * errado:
 *
 * <ul>
 *   <li><b>Hash disperso demais</b> (identidade, sem override) quebra <b>correção</b>: objetos
 *       iguais por {@code equals} caem em buckets diferentes, e a busca não encontra nada.
 *       Nenhuma lentidão -- resposta errada, rápido.
 *   <li><b>Hash constante</b> quebra <b>desempenho</b>: encontra sempre, comparando com meio
 *       conjunto no caminho. Resposta certa, devagar.
 * </ul>
 *
 * <p>A medição conta <b>comparações de equals</b>, não tempo de parede: contagem é
 * determinística e o relógio não é. Mesma disciplina do contador de queries.
 */
class ContratoDeHashCodeTests {

    private static final int TAMANHO = 4096;

    private static final AtomicLong COMPARACOES = new AtomicLong();

    /** A receita que este repositório usa nas entidades: identidade por id, hash constante. */
    static final class ComHashConstante {
        private final Long id;

        ComHashConstante(long id) {
            this((Long) id);
        }

        ComHashConstante(Long id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            COMPARACOES.incrementAndGet();
            if (this == o) {
                return true;
            }
            if (!(o instanceof ComHashConstante outro)) {
                return false;
            }
            return id != null && id.equals(outro.id);
        }

        @Override
        public int hashCode() {
            return ComHashConstante.class.hashCode();
        }
    }

    /** A patologia do Bloco 0: equals sobrescrito, hashCode esquecido. */
    static final class ComHashDeIdentidade {
        private final Long id;

        ComHashDeIdentidade(Long id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            COMPARACOES.incrementAndGet();
            return o instanceof ComHashDeIdentidade outro && id.equals(outro.id);
        }
        // sem hashCode: herda o de Object
    }

    /** O que um value object faz: hash derivado do estado. */
    static final class ComHashDeValor {
        private final Long id;

        ComHashDeValor(long id) {
            this((Long) id);
        }

        ComHashDeValor(Long id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            COMPARACOES.incrementAndGet();
            return o instanceof ComHashDeValor outro && id.equals(outro.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    private long comparacoesPara(Runnable acao) {
        COMPARACOES.set(0);
        acao.run();
        return COMPARACOES.get();
    }

    @Test
    void hashDeIdentidadeQuebraCorrecao() {
        Set<ComHashDeIdentidade> conjunto = new HashSet<>();
        for (long i = 0; i < TAMANHO; i++) {
            conjunto.add(new ComHashDeIdentidade(i));
        }

        ComHashDeIdentidade procurado = new ComHashDeIdentidade(7L);
        long comparacoes = comparacoesPara(() -> conjunto.contains(procurado));

        assertThat(conjunto.contains(procurado))
                .as("equals diria true, mas o bucket e outro: a resposta e ERRADA, nao lenta")
                .isFalse();
        assertThat(conjunto).hasSize(TAMANHO);

        System.out.printf("%n[hashCode] identidade  -> contains=false, %d comparacoes%n",
                comparacoes);
        assertThat(comparacoes)
                .as("nem chega a comparar: o bucket errado esta vazio")
                .isZero();
    }

    @Test
    void hashConstanteQuebraDesempenhoMasNaoCorrecao() {
        Set<ComHashConstante> conjunto = new HashSet<>();
        for (long i = 0; i < TAMANHO; i++) {
            conjunto.add(new ComHashConstante(i));
        }

        ComHashConstante procurado = new ComHashConstante((long) TAMANHO - 1);
        long comparacoes = comparacoesPara(() -> conjunto.contains(procurado));

        assertThat(conjunto.contains(procurado)).as("a resposta e CERTA").isTrue();
        assertThat(conjunto).hasSize(TAMANHO);

        System.out.printf("[hashCode] constante   -> contains=true,  %d comparacoes (n=%d)%n",
                comparacoes, TAMANHO);
        assertThat(comparacoes)
                .as("um bucket so: a busca percorre a estrutura de colisao inteira")
                .isGreaterThan(1);
    }

    @Test
    void hashDeValorEhCorretoEConstante() {
        Set<ComHashDeValor> conjunto = new HashSet<>();
        for (long i = 0; i < TAMANHO; i++) {
            conjunto.add(new ComHashDeValor(i));
        }

        ComHashDeValor procurado = new ComHashDeValor((long) TAMANHO - 1);
        long comparacoes = comparacoesPara(() -> conjunto.contains(procurado));

        assertThat(conjunto.contains(procurado)).isTrue();

        System.out.printf("[hashCode] de valor    -> contains=true,  %d comparacoes (n=%d)%n",
                comparacoes, TAMANHO);
        assertThat(comparacoes)
                .as("bucket com um elemento: uma comparacao, independente de n")
                .isEqualTo(1);
    }

    /**
     * A separacao entre as duas receitas, medida de forma que nao dependa de sorte.
     *
     * <p>Buscar <b>um</b> elemento e loteria: com mais de 8 colisoes no mesmo bucket, o HashMap
     * converte a lista em arvore rubro-negra, e ordenar a arvore exige chaves {@code Comparable}
     * -- entidade nao e. O HashMap entao desempata por {@code System.identityHashCode}, e a
     * forma da arvore depende de onde os objetos cairam na heap. Cinco conjuntos do mesmo
     * tamanho medidos: {@code [888, 2874, 2327, 3611, 198]}. Uma assercao sobre o minimo teria
     * 198 contra um limite escolhido no chute.
     *
     * <p>(Os cinco valores repetem entre execucoes nesta JVM, porque o hash de identidade do
     * HotSpot vem de um PRNG com semente deterministica e a ordem de alocacao e a mesma. Isso
     * <b>nao</b> e garantia: outra JVM, outro GC ou outra ordem de alocacao muda os numeros.
     * Reprodutivel aqui nao e o mesmo que controlado pelo teste.)
     *
     * <p>Somar as N buscas remove a loteria: a soma percorre todas as posicoes da arvore, entao
     * a media interna estabiliza o total. E a soma e exatamente a formulacao do contrato --
     * N buscas em O(1) custam N comparacoes; N buscas em O(n) custam ordem de N².
     */
    @Test
    void hashConstanteCustaOrdensDeGrandezaMaisQueHashDeValor() {
        long constante = comparacoesBuscandoTodos(new HashSet<>(), TAMANHO,
                ComHashConstante::new, o -> new ComHashConstante(o));
        long deValor = comparacoesBuscandoTodos(new HashSet<>(), TAMANHO,
                ComHashDeValor::new, o -> new ComHashDeValor(o));

        System.out.printf("%n[hashCode] %d buscas em conjunto de %d:%n", TAMANHO, TAMANHO);
        System.out.printf("  hash de valor    -> %,d comparacoes (%.1f por busca)%n",
                deValor, (double) deValor / TAMANHO);
        System.out.printf("  hash constante   -> %,d comparacoes (%.1f por busca, %.0fx mais)%n",
                constante, (double) constante / TAMANHO, (double) constante / deValor);

        assertThat(deValor)
                .as("uma comparacao por busca, independente do tamanho do conjunto")
                .isEqualTo(TAMANHO);

        assertThat(constante)
                .as("hash constante: cada busca varre a estrutura de colisao. A ordem de "
                        + "grandeza e o que o teste controla; o numero exato depende da forma "
                        + "da arvore, que ele nao controla.")
                .isGreaterThan(50L * TAMANHO);
    }

    private <T> long comparacoesBuscandoTodos(Set<T> conjunto, int tamanho,
                                              java.util.function.LongFunction<T> criar,
                                              java.util.function.LongFunction<T> recriar) {
        for (long i = 0; i < tamanho; i++) {
            conjunto.add(criar.apply(i));
        }
        return comparacoesPara(() -> {
            for (long i = 0; i < tamanho; i++) {
                conjunto.contains(recriar.apply(i));
            }
        });
    }
}
