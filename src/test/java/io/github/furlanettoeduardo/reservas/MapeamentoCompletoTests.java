package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaJpa;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fecha a única pendência do ADR 0005: a verificação de campo esquecido no mapeamento.
 *
 * <p>O ganho que fez o MapStruct parecer valer a pena era {@code unmappedTargetPolicy = ERROR} —
 * adicionar um campo ao domínio e esquecer de mapear quebra a compilação. Sem ele, o campo
 * simplesmente não é copiado, e o sintoma é dado sumindo.
 *
 * <p>Este teste cobre a mesma falha por outro caminho: monta um objeto de domínio com <b>todos</b>
 * os campos preenchidos com valores distintos, faz o round-trip pelo mapeamento, e compara campo a
 * campo por reflexão. Campo novo não copiado volta {@code null} e o teste falha.
 *
 * <p><b>E há um motivo para preferir isto à política do MapStruct</b>, que só ficou claro medindo:
 * a proteção do MapStruct é uma palavra numa anotação. Trocar {@code ERROR} por {@code WARN}
 * desliga a verificação nas duas direções de uma vez, e nada testa essa palavra. Um teste não tem
 * ponto único de desativação — apagá-lo é apagar um arquivo, e isso aparece no diff.
 *
 * <p>A lista de campos deliberadamente não carregados é o equivalente ao
 * {@code @Mapping(ignore = true)}. A diferença é que aqui cada entrada tem o <b>motivo</b> escrito
 * ao lado, e o teste falha se alguém adicionar um campo à lista sem tocar nela conscientemente.
 */
class MapeamentoCompletoTests {

    /**
     * Campos que o mapeamento de domínio para entidade JPA não carrega, com o motivo. Se esta
     * lista crescer sem justificativa, é sinal de que o mapeamento está perdendo dado.
     */
    private static final Map<String, String> NAO_CARREGADOS = new LinkedHashMap<>(Map.of(
            "criadoEm", "gerado pelo banco (DEFAULT now()); o adaptador le de volta apos o INSERT"
    ));

    private static Espaco espacoCompleto() {
        return new Espaco(7L, "Sala Azul", 30, new BigDecimal("150.0000"),
                Instant.parse("2026-01-01T10:00:00Z"));
    }

    private static Cliente clienteCompleto() {
        return new Cliente(9L, "Ana Souza", "ana@exemplo.com",
                Instant.parse("2026-01-02T10:00:00Z"));
    }

    private static Reserva reservaCompleta() {
        return new Reserva(11L, espacoCompleto(), clienteCompleto(),
                new Periodo(Instant.parse("2026-09-01T13:00:00Z"),
                        Instant.parse("2026-09-01T15:00:00Z")),
                StatusReserva.CANCELADA, new BigDecimal("300.0000"),
                Instant.parse("2026-01-03T10:00:00Z"));
    }

    @Test
    void oMapeamentoDeEspacoNaoPerdeCampo() {
        Espaco original = espacoCompleto();

        Espaco relido = EspacoJpa.de(original).paraDominio();

        conferirTodosOsCampos(original, relido);
    }

    @Test
    void oMapeamentoDeClienteNaoPerdeCampo() {
        Cliente original = clienteCompleto();

        Cliente relido = ClienteJpa.de(original).paraDominio();

        conferirTodosOsCampos(original, relido);
    }

    @Test
    void oMapeamentoDeReservaNaoPerdeCampo() {
        Reserva original = reservaCompleta();

        Reserva relida = ReservaJpa.de(original,
                        EspacoJpa.de(original.getEspaco()),
                        ClienteJpa.de(original.getCliente()))
                .paraDominio();

        conferirTodosOsCampos(original, relida);
    }

    /**
     * O {@code aplicar} é a metade assimétrica do mapeamento: copia só o estado mutável sobre uma
     * instância gerenciada. Este teste garante que o que ele copia sobrevive — e a assimetria
     * deliberada (não tocar id, criadoEm nem versao) está na lista de não-carregados.
     */
    @Test
    void oAplicarCopiaTodoOEstadoMutavel() {
        Reserva original = reservaCompleta();
        ReservaJpa gerenciada = ReservaJpa.de(original,
                EspacoJpa.de(original.getEspaco()), ClienteJpa.de(original.getCliente()));

        Reserva alterada = original.cancelar();
        gerenciada.aplicar(alterada);

        conferirTodosOsCampos(alterada, gerenciada.paraDominio());
    }

    /**
     * A lista de exceções não pode crescer por descuido. Se alguém adicionar um campo aqui, o
     * teste obriga a atualizar este número — que é o mesmo mecanismo dos baselines de query.
     */
    @Test
    void aListaDeCamposNaoCarregadosEhPequenaEJustificada() {
        assertThat(NAO_CARREGADOS)
                .as("cada entrada e um dado que o dominio tem e o banco nao recebe. Uma so hoje, "
                        + "e ela existe porque quem gera o valor e o banco.")
                .hasSize(1);
        assertThat(NAO_CARREGADOS.values())
                .allSatisfy(motivo -> assertThat(motivo).isNotBlank());
    }

    /**
     * Compara campo a campo por reflexão, e não por {@code equals}: o {@code equals} do domínio
     * omite {@code criadoEm} de propósito, então usá-lo aqui esconderia exatamente a classe de
     * falha que este teste existe para pegar.
     */
    private static void conferirTodosOsCampos(Object original, Object relido) {
        Set<String> ignorados = NAO_CARREGADOS.keySet();

        for (Field campo : original.getClass().getDeclaredFields()) {
            if (campo.isSynthetic() || java.lang.reflect.Modifier.isStatic(campo.getModifiers())) {
                continue;
            }
            campo.setAccessible(true);

            Object esperado = valor(campo, original);
            Object obtido = valor(campo, relido);

            if (ignorados.contains(campo.getName())) {
                assertThat(obtido)
                        .as("%s.%s esta na lista de nao-carregados (%s), entao tem que voltar "
                                        + "nulo -- se voltou valor, a lista esta desatualizada",
                                original.getClass().getSimpleName(), campo.getName(),
                                NAO_CARREGADOS.get(campo.getName()))
                        .isNull();
                continue;
            }

            assertThat(obtido)
                    .as("%s.%s nao sobreviveu ao round-trip. Campo novo no dominio sem "
                                    + "mapeamento correspondente volta nulo, e o sintoma em "
                                    + "producao seria dado sumindo em silencio.",
                            original.getClass().getSimpleName(), campo.getName())
                    .isNotNull()
                    .satisfies(valorObtido -> assertThat(Objects.equals(esperado, valorObtido))
                            .as("%s.%s: esperado %s, obtido %s",
                                    original.getClass().getSimpleName(), campo.getName(),
                                    esperado, valorObtido)
                            .isTrue());
        }
    }

    private static Object valor(Field campo, Object alvo) {
        try {
            return campo.get(alvo);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("campo inacessivel: " + campo.getName(), e);
        }
    }
}
