package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoSpringData;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adaptador da porta. A reconciliacao esta explicada em {@link ReservaRepositorioJpa}. */
@Repository
public class EspacoRepositorioJpa implements EspacoRepositorio {

    private final EspacoSpringData espacos;

    public EspacoRepositorioJpa(EspacoSpringData espacos) {
        this.espacos = espacos;
    }

    @Override
    public Espaco salvar(Espaco espaco) {
        if (espaco.getId() == null) {
            return espacos.save(EspacoJpa.de(espaco)).paraDominio();
        }
        EspacoJpa gerenciado = espacos.findById(espaco.getId()).orElseThrow();
        gerenciado.aplicar(espaco);
        return espacos.save(gerenciado).paraDominio();
    }

    @Override
    public Optional<Espaco> porId(Long id) {
        return espacos.findById(id).map(EspacoJpa::paraDominio);
    }

    @Override
    public List<Espaco> todos() {
        return espacos.findAll().stream().map(EspacoJpa::paraDominio).toList();
    }

    @Override
    public long contar() {
        return espacos.count();
    }
}
