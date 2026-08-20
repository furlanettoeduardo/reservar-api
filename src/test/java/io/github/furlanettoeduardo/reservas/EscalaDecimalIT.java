package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import io.github.furlanettoeduardo.reservas.service.NovaReserva;
import io.github.furlanettoeduardo.reservas.service.ReservaResponse;
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia n.8 do 1B: {@code BigDecimal.equals} compara escala, {@code compareTo} não.
 *
 * <p>Este não é um exemplo fabricado. O bug existiu neste repositório e foi encontrado por
 * {@code curl}, não por teste: o mesmo recurso serializava {@code 300.00} na resposta do POST e
 * {@code 300.0000} na listagem.
 *
 * <p>E a razão de nenhum dos 48 testes da época ter visto é a parte que interessa:
 * {@code isEqualByComparingTo} -- a asserção <b>correta</b> para regra de negócio, justamente
 * para não tropeçar em escala -- é cega para escala por construção. A defesa contra a pegadinha
 * foi o que impediu de ver a pegadinha aparecer no contrato HTTP.
 *
 * <blockquote>A asserção tolerante ao detalhe irrelevante para o domínio é cega ao detalhe
 * relevante para o contrato.</blockquote>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EscalaDecimalIT {

    @Autowired
    private ReservaService service;
    @Autowired
    private ReservaRepositorio reservas;
    @Autowired
    private EspacoRepositorio espacos;
    @Autowired
    private ClienteRepositorio clientes;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private JdbcTemplate jdbc;

    private Long espacoId;
    private Long clienteId;

    private static final Instant INICIO = Instant.parse("2026-09-01T13:00:00Z");
    private static final Instant FIM = Instant.parse("2026-09-01T15:00:00Z");

    @BeforeEach
    void semear() {
        LimpezaDeBase.limpar(jdbc);
        transacao.executeWithoutResult(status -> {
            espacoId = espacos.salvar(
                    new Espaco("Sala Azul", 30, new BigDecimal("150.00"))).getId();
            clienteId = clientes.salvar(new Cliente("Ana", "ana@exemplo.com")).getId();
        });
    }

    @Test
    void equalsEhSensivelAEscalaECompareToNao() {
        BigDecimal calculado = new BigDecimal("300.00");
        BigDecimal lidoDoBanco = new BigDecimal("300.0000");

        assertThat(calculado.equals(lidoDoBanco))
                .as("mesmo valor, escalas diferentes: equals diz que nao sao iguais")
                .isFalse();
        assertThat(calculado.compareTo(lidoDoBanco))
                .as("compareTo compara valor numerico e ignora escala")
                .isZero();
        assertThat(calculado.hashCode())
                .as("consequencia menos citada: hashCode tambem difere, entao os dois vao para "
                        + "buckets diferentes num HashMap")
                .isNotEqualTo(lidoDoBanco.hashCode());
    }

    @Test
    void aEntidadeMudaDeEscalaNoRoundTrip() {
        Long reservaId = service.criar(
                new NovaReserva(espacoId, clienteId, INICIO, FIM)).id();

        BigDecimal relido = transacao.execute(status ->
                reservas.porId(reservaId).orElseThrow().getValorTotal());

        assertThat(relido)
                .as("valor certo")
                .isEqualByComparingTo("300.00");
        assertThat(relido.scale())
                .as("NUMERIC(19,4): o banco devolve na escala da coluna, nao na do calculo")
                .isEqualTo(4);
        assertThat(relido.equals(new BigDecimal("300.00")))
                .as("e por isso equals contra o valor calculado falha")
                .isFalse();
    }

    /**
     * A regressão do bug real: os dois caminhos de código que produzem um
     * {@code ReservaResponse} -- a resposta do POST, com o BigDecimal recém-calculado, e a
     * listagem, com o valor lido da coluna -- tem que serializar igual.
     */
    @Test
    void osDoisCaminhosDoDtoProduzemAMesmaEscala() {
        ReservaResponse doPost = service.criar(new NovaReserva(espacoId, clienteId, INICIO, FIM));
        ReservaResponse daListagem = service.listarConfirmadasDoEspaco(espacoId).getFirst();

        assertThat(doPost.valorTotal().scale()).isEqualTo(2);
        assertThat(daListagem.valorTotal().scale()).isEqualTo(2);

        assertThat(doPost.valorTotal())
                .as("igualdade ESTRITA aqui, de proposito: isEqualByComparingTo passaria com "
                        + "300.00 contra 300.0000 e deixaria o bug do contrato HTTP passar de "
                        + "novo. Para contrato, a escala faz parte do valor.")
                .isEqualTo(daListagem.valorTotal());

        assertThat(doPost.valorTotal()).hasToString("300.00");
        assertThat(daListagem.valorTotal()).hasToString("300.00");
    }

    @Test
    void oDtoNormalizaAEscalaQueAEntidadeNaoNormaliza() {
        Long reservaId = service.criar(
                new NovaReserva(espacoId, clienteId, INICIO, FIM)).id();

        Reserva entidade = transacao.execute(status ->
                reservas.porId(reservaId).orElseThrow());
        ReservaResponse dto = service.listarConfirmadasDoEspaco(espacoId).getFirst();

        assertThat(entidade.getValorTotal().scale())
                .as("a entidade carrega a escala da coluna")
                .isEqualTo(4);
        assertThat(dto.valorTotal().scale())
                .as("o DTO e onde a normalizacao acontece -- a fronteira do contrato e o lugar "
                        + "de fixar escala, nao a entidade, que deve espelhar a coluna")
                .isEqualTo(2);
    }
}
