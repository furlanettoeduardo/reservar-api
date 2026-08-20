package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.service.ConflitoDeReservaException;
import io.github.furlanettoeduardo.reservas.service.NovaReserva;
import io.github.furlanettoeduardo.reservas.service.RecursoNaoEncontradoException;
import io.github.furlanettoeduardo.reservas.service.ReservaResponse;
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import io.github.furlanettoeduardo.reservas.web.ReservaController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fatia web pura: sem banco, sem Hibernate. So roteamento, serializacao e status. */
@WebMvcTest(ReservaController.class)
class ReservaControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservaService service;

    private static final Instant INICIO = Instant.now().plus(30, ChronoUnit.DAYS);
    private static final Instant FIM = INICIO.plus(2, ChronoUnit.HOURS);

    private static ReservaResponse resposta() {
        return new ReservaResponse(7L, 1L, "Sala Azul", 2L, "Ana",
                INICIO, FIM, StatusReserva.CONFIRMADA, new BigDecimal("300.0000"));
    }

    /** JSON literal em vez de ObjectMapper: o payload de entrada e o contrato, e escrever o
     *  contrato a mao evita que um bug de serializacao no teste esconda um bug no controller. */
    private static String corpo(Object espacoId, Object clienteId, Object inicio, Object fim) {
        return """
                {"espacoId": %s, "clienteId": %s, "inicio": %s, "fim": %s}
                """.formatted(literal(espacoId), literal(clienteId), literal(inicio), literal(fim));
    }

    private static String literal(Object valor) {
        return switch (valor) {
            case null -> "null";
            case Number n -> n.toString();
            default -> "\"" + valor + "\"";
        };
    }

    @Test
    void criaComVinteUmEHeaderLocation() throws Exception {
        given(service.criar(any(NovaReserva.class))).willReturn(resposta());

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(1L, 2L, INICIO, FIM)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/reservas/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.espacoNome").value("Sala Azul"))
                .andExpect(jsonPath("$.clienteNome").value("Ana"))
                .andExpect(jsonPath("$.status").value("CONFIRMADA"));
    }

    @Test
    void serializaInstantComoIso8601ENaoComoEpoch() throws Exception {
        given(service.criar(any(NovaReserva.class))).willReturn(resposta());

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(1L, 2L, INICIO, FIM)))
                .andExpect(jsonPath("$.inicio").value(INICIO.toString()))
                .andExpect(jsonPath("$.valorTotal").value(300.0000));
    }

    @Test
    void conflitoDeRegraViraQuatrocentosENoveComProblemDetail() throws Exception {
        willThrow(new ConflitoDeReservaException(1L,
                new io.github.furlanettoeduardo.reservas.domain.Periodo(INICIO, FIM)))
                .given(service).criar(any(NovaReserva.class));

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(1L, 2L, INICIO, FIM)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflito de reserva"))
                .andExpect(jsonPath("$.type").value("urn:reservar:conflito-de-regra"))
                .andExpect(jsonPath("$.detectadoPor").value("regra"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void violacaoDeConstraintViraQuatrocentosENoveMarcadoComoConstraint() throws Exception {
        willThrow(new DataIntegrityViolationException("reserva_sem_sobreposicao"))
                .given(service).criar(any(NovaReserva.class));

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(1L, 2L, INICIO, FIM)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:reservar:conflito-de-constraint"))
                .andExpect(jsonPath("$.detectadoPor").value("constraint"));
    }

    @Test
    void deadlockViraQuatrocentosENoveRetentavelENaoQuinhentos() throws Exception {
        willThrow(new CannotAcquireLockException("deadlock detected"))
                .given(service).criar(any(NovaReserva.class));

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(1L, 2L, INICIO, FIM)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:reservar:conflito-concorrente"))
                .andExpect(jsonPath("$.detectadoPor").value("contencao"))
                .andExpect(jsonPath("$.retentavel").value(true));
    }

    @Test
    void conflitoDeLockOtimistaCaiNoMesmoHandlerDeContencao() throws Exception {
        willThrow(new ObjectOptimisticLockingFailureException("espaco", 1L))
                .given(service).criar(any(NovaReserva.class));

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(1L, 2L, INICIO, FIM)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:reservar:conflito-concorrente"))
                .andExpect(jsonPath("$.detectadoPor").value("contencao"))
                .andExpect(jsonPath("$.retentavel").value(true));
    }

    @Test
    void recursoInexistenteViraQuatrocentosEQuatro() throws Exception {
        willThrow(new RecursoNaoEncontradoException("espaco", 99L))
                .given(service).criar(any(NovaReserva.class));

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(99L, 2L, INICIO, FIM)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso nao encontrado"));
    }

    @Test
    void campoFaltandoViraQuatrocentosSemChegarNoServico() throws Exception {
        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(null, 2L, INICIO, FIM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.espacoId").value("espacoId e obrigatorio"));

        verifyNoInteractions(service);
    }

    @Test
    void dataNoPassadoViraQuatrocentos() throws Exception {
        Instant passado = Instant.now().minus(1, ChronoUnit.DAYS);

        mockMvc.perform(post("/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(1L, 2L, passado, FIM)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.inicio").value("inicio deve ser no futuro"));

        verifyNoInteractions(service);
    }

    @Test
    void listaReservasDoEspaco() throws Exception {
        given(service.listarConfirmadasDoEspaco(1L)).willReturn(List.of(resposta()));

        mockMvc.perform(get("/espacos/1/reservas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].clienteNome").value("Ana"));
    }

    @Test
    void cancelamentoViraDuzentosEQuatro() throws Exception {
        mockMvc.perform(post("/reservas/7/cancelamento"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }
}
