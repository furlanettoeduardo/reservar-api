package io.github.furlanettoeduardo.reservas.web;

import io.github.furlanettoeduardo.reservas.service.ConflitoDeReservaException;
import io.github.furlanettoeduardo.reservas.service.RecursoNaoEncontradoException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
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
     * banco: quando as duas transacoes gravam ao mesmo tempo, cada INSERT grava a tupla e so
     * entao checa a exclusao, encontra a tupla nao-commitada da outra e espera por ela. Espera
     * mutua, deadlock, e o Postgres mata uma.
     *
     * <p>Captura a familia {@code TransientDataAccessException} e nao a
     * {@code CannotAcquireLockException} especifica. O ramo da hierarquia do Spring <b>e</b> a
     * informacao: transient significa "tentar de novo pode funcionar", e o irmao
     * {@code NonTransientDataAccessException} -- onde mora DataIntegrityViolationException --
     * significa o contrario. Capturar a familia cobre tambem timeout de lock e falha de
     * serializacao, que produzem o mesmo 409 retentavel. Qual membro chega depende de
     * temporizacao: o mesmo cenario deu deadlock na maquina local e violacao no runner do CI.
     *
     * <p>409 e nao 500 porque a causa e conflito real de reserva, nao falha do servidor. Sem
     * este handler seria 500, porque TransientDataAccessException nao descende de
     * DataIntegrityViolationException -- as duas sao DataAccessException por ramos diferentes.
     *
     * <p>A V3 trouxe um segundo membro da familia para este mesmo handler:
     * ObjectOptimisticLockingFailureException, do @Version. O rotulo era "deadlock" e ficou
     * errado -- virou "contencao", que descreve a familia em vez de um membro. Terceira vez que
     * corrigir um invariante moveu a falha para outra camada, e a primeira em que a camada
     * afetada era um rotulo de contrato e nao um status code.
     *
     * <p>Retry automatico no servico continua candidato registrado e nao implementado: ele
     * mascararia a razao entre os contadores de detectadoPor, que e a medida da janela de
     * corrida. AtualizacaoPerdidaIT mostra o retry funcionando no nivel do teste.
     */
    @ExceptionHandler(TransientDataAccessException.class)
    public ProblemDetail conflitoConcorrente(TransientDataAccessException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "a operacao foi abortada por contencao concorrente; tentar de novo pode resolver");
        problema.setTitle("Conflito concorrente");
        problema.setType(TIPO_CONFLITO_CONCORRENTE);
        problema.setProperty("detectadoPor", "contencao");
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
