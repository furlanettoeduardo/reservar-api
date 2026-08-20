package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sem Spring: Periodo e o calculo de valor sao dominio puro. */
class PeriodoTests {

    private static Instant hora(String hhmm) {
        return Instant.parse("2026-09-01T%s:00Z".formatted(hhmm));
    }

    @Test
    void rejeitaFimAntesDoInicio() {
        assertThatThrownBy(() -> new Periodo(hora("15:00"), hora("13:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posterior");
    }

    @Test
    void rejeitaPeriodoDeDuracaoZero() {
        assertThatThrownBy(() -> new Periodo(hora("13:00"), hora("13:00")))
                .as("meio-aberto: [13:00, 13:00) e vazio, nao e reserva")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void igualdadePorValorNaoPorIdentidade() {
        assertThat(new Periodo(hora("13:00"), hora("15:00")))
                .as("record: dois periodos com as mesmas bordas sao o mesmo periodo")
                .isEqualTo(new Periodo(hora("13:00"), hora("15:00")));
    }

    @ParameterizedTest(name = "[{index}] {0}-{1} vs 13:00-15:00 -> {2}")
    @CsvSource({
            "12:00, 14:00, true",
            "13:30, 14:30, true",
            "12:00, 16:00, true",
            "11:00, 13:00, false",
            "15:00, 17:00, false"
    })
    void sobrepoeEspelhaACondicaoDaJpql(String inicio, String fim, boolean esperado) {
        Periodo existente = new Periodo(hora("13:00"), hora("15:00"));

        assertThat(new Periodo(hora(inicio), hora(fim)).sobrepoe(existente)).isEqualTo(esperado);
        assertThat(existente.sobrepoe(new Periodo(hora(inicio), hora(fim))))
                .as("sobreposicao e simetrica")
                .isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] {0}/h por {1}-{2} = {3}")
    @CsvSource({
            "150.00, 13:00, 15:00, 300.00",
            "150.00, 13:00, 13:20, 50.00",
            "150.00, 13:00, 13:30, 75.00",
            "100.00, 13:00, 13:10, 16.67",
            "99.90,  13:00, 14:00, 99.90"
    })
    void calculaValorComArredondamentoNoCentavo(String precoHora, String inicio, String fim, String esperado) {
        Espaco espaco = new Espaco("Sala", 10, new BigDecimal(precoHora));

        assertThat(espaco.calcularValor(new Periodo(hora(inicio), hora(fim))))
                .isEqualByComparingTo(esperado);
    }

    /**
     * O caso que discrimina HALF_UP de HALF_DOWN e HALF_EVEN, e que faltava.
     *
     * <p>33,33/h por 30 min da 16,665 <b>exato</b> -- 33,33 x 1800 = 59994, dividido por 3600.
     * Com a metade exata na casa descartada, os tres modos discordam:
     *
     * <pre>
     * HALF_UP   -> 16,67
     * HALF_DOWN -> 16,66
     * HALF_EVEN -> 16,66   (o 6 anterior e par)
     * </pre>
     *
     * <p>Os outros casos deste arquivo nao discriminam: 100,00/h por 10 min da
     * 16,6666... e os tres modos concordam em 16,67, porque o digito descartado e 6 e nao 5.
     *
     * <p>Achado a mao, e nao por mutation testing: o Pitest gera 12 mutantes em calcularValor --
     * inclusive trocar multiply por divide -- e mata todos, mas <b>nenhum</b> deles altera o
     * RoundingMode, porque nao existe mutador para constante de enum. Cobertura de mutacao so
     * atesta o que o conjunto de mutadores consegue expressar.
     */
    @Test
    void arredondaParaCimaNaMetadeExata() {
        Espaco espaco = new Espaco("Sala", 10, new BigDecimal("33.33"));

        assertThat(espaco.calcularValor(new Periodo(hora("13:00"), hora("13:30"))))
                .as("16,665 exato: HALF_UP da 16,67, HALF_DOWN e HALF_EVEN dao 16,66. "
                        + "Valor cobrado arredonda a favor de quem cobra, e a escolha e "
                        + "comercial, nao estatistica -- banker's rounding existe para nao "
                        + "enviesar somas, e nao e o caso aqui.")
                .isEqualByComparingTo("16.67");
    }

    @Test
    void multiplicaAntesDeDividirParaNaoArredondarNoMeio() {
        Espaco espaco = new Espaco("Sala", 10, new BigDecimal("100.00"));

        assertThat(espaco.calcularValor(new Periodo(hora("13:00"), hora("13:10"))))
                .as("10 min = 1/6 de hora, dizima periodica: dividir primeiro forcaria "
                        + "arredondar antes de multiplicar, e o erro entraria no resultado")
                .isEqualByComparingTo("16.67");
    }
}
