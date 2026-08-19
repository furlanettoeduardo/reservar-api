package io.github.furlanettoeduardo.reservas.web;

import io.github.furlanettoeduardo.reservas.service.ConflitoDeReservaException;
import io.github.furlanettoeduardo.reservas.service.RecursoNaoEncontradoException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 9457 via {@link ProblemDetail}. Nenhuma resposta carrega stacktrace nem Map improvisado.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final URI TIPO_CONFLITO_DE_REGRA = URI.create("urn:reservar:conflito-de-regra");
    private static final URI TIPO_CONFLITO_DE_CONSTRAINT = URI.create("urn:reservar:conflito-de-constraint");
    private static final URI TIPO_CONFLITO_CONCORRENTE = URI.create("urn:reservar:conflito-concorrente");
    private static final URI TIPO_NAO_ENCONTRADO = URI.create("urn:reservar:nao-encontrado");
    private static final URI TIPO_REQUISICAO_INVALIDA = URI.create("urn:reservar:requisicao-invalida");

    /** Conflito detectado pela <b>regra</b>, antes de tocar o banco. */
    @ExceptionHandler(ConflitoDeReservaException.class)
    public ProblemDetail conflitoDeRegra(ConflitoDeReservaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Conflito de reserva");
        problema.setType(TIPO_CONFLITO_DE_REGRA);
        problema.setProperty("detectadoPor", "regra");
        return problema;
    }

    /**
     * Conflito detectado pela <b>constraint</b>, depois do flush. Mesmo 409, {@code type}
     * diferente: e aqui que cai a sobreposicao que o TOCTOU deixou passar, barrada pela
     * EXCLUDE da V2 (alem do UNIQUE de cliente.email). Separar da deteccao por regra e o que
     * permite medir a janela de corrida: a razao entre os dois contadores diz quantas vezes a
     * verificacao em Java perdeu para a concorrencia. Misturados num handler so, essa
     * informacao se perde.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflitoDeConstraint(DataIntegrityViolationException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "a operacao viola uma restricao de integridade");
        problema.setTitle("Conflito de integridade");
        problema.setType(TIPO_CONFLITO_DE_CONSTRAINT);
        problema.setProperty("detectadoPor", "constraint");
        return problema;
    }

    /**
     * Terceira forma de o mesmo conflito chegar, e a que so apareceu depois da V2 ir para o
     * banco: quando as duas transacoes gravam ao mesmo tempo, cada INSERT insere a tupla e so
     * entao checa a exclusao, encontra a tupla nao-commitada da outra e espera por ela. Espera
     * mutua, deadlock, e o Postgres mata uma -- o que chega como CannotAcquireLockException,
     * que <b>nao</b> e subclasse de DataIntegrityViolationException e sem este handler viraria
     * 500.
     *
     * <p>409 e nao 500 porque a causa e conflito real de reserva, nao falha do servidor.
     * Diferente das outras duas, esta e retentavel: a transacao morta nao chegou a gravar
     * nada, e uma segunda tentativa ou consegue o horario ou recebe um 409 honesto pela regra.
     * Retry automatico no servico e candidato registrado, nao implementado -- ele mascararia a
     * medicao da janela de corrida.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    public ProblemDetail conflitoConcorrente(CannotAcquireLockException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "a operacao foi abortada por contencao concorrente; tentar de novo pode resolver");
        problema.setTitle("Conflito concorrente");
        problema.setType(TIPO_CONFLITO_CONCORRENTE);
        problema.setProperty("detectadoPor", "deadlock");
        problema.setProperty("retentavel", true);
        return problema;
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ProblemDetail naoEncontrado(RecursoNaoEncontradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Recurso nao encontrado");
        problema.setType(TIPO_NAO_ENCONTRADO);
        return problema;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail requisicaoInvalida(MethodArgumentNotValidException e) {
        Map<String, String> erros = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(erro -> erros.putIfAbsent(erro.getField(), erro.getDefaultMessage()));

        ProblemDetail problema = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "a requisicao tem campos invalidos");
        problema.setTitle("Requisicao invalida");
        problema.setType(TIPO_REQUISICAO_INVALIDA);
        problema.setProperty("erros", erros);
        return problema;
    }

    /** Periodo invertido vem do construtor do Periodo, nao do Bean Validation. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail argumentoInvalido(IllegalArgumentException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Requisicao invalida");
        problema.setType(TIPO_REQUISICAO_INVALIDA);
        return problema;
    }
}
