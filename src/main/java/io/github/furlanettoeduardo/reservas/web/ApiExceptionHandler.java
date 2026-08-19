package io.github.furlanettoeduardo.reservas.web;

import io.github.furlanettoeduardo.reservas.service.ConflitoDeReservaException;
import io.github.furlanettoeduardo.reservas.service.RecursoNaoEncontradoException;
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
     * diferente: hoje so o UNIQUE de cliente.email chega aqui, mas quando a EXCLUDE com
     * tstzrange entrar na V2 a sobreposicao perdida pelo TOCTOU vai comecar a cair neste
     * handler. Separar os dois agora significa que a metrica do 1B distingue "a regra pegou"
     * de "a regra deixou passar e o banco segurou" -- que e exatamente o numero que mede a
     * janela de corrida. Misturados num handler so, essa informacao se perde.
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
