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

    @Test
    void multiplicaAntesDeDividirParaNaoArredondarNoMeio() {
        Espaco espaco = new Espaco("Sala", 10, new BigDecimal("100.00"));

        assertThat(espaco.calcularValor(new Periodo(hora("13:00"), hora("13:10"))))
                .as("10 min = 1/6 de hora, dizima periodica: dividir primeiro forcaria "
                        + "arredondar antes de multiplicar, e o erro entraria no resultado")
                .isEqualByComparingTo("16.67");
    }
}
