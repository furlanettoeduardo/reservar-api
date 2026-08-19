package io.github.furlanettoeduardo.reservas.service;

public class RecursoNaoEncontradoException extends RuntimeException {

    public RecursoNaoEncontradoException(String recurso, Long id) {
        super("%s %d nao encontrado".formatted(recurso, id));
    }
}
