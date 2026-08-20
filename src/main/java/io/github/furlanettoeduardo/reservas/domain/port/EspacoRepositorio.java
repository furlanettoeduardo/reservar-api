package io.github.furlanettoeduardo.reservas.domain.port;

import io.github.furlanettoeduardo.reservas.domain.Espaco;

import java.util.List;
import java.util.Optional;

public interface EspacoRepositorio {

    Espaco salvar(Espaco espaco);

    Optional<Espaco> porId(Long id);

    List<Espaco> todos();

    long contar();
}
