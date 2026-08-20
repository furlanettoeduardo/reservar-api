package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class EspacoRepositorioJpa implements EspacoRepositorio {

    private final EspacoRepository espacos;

    public EspacoRepositorioJpa(EspacoRepository espacos) {
        this.espacos = espacos;
    }

    @Override
    public Espaco salvar(Espaco espaco) {
        return espacos.save(espaco);
    }

    @Override
    public Optional<Espaco> porId(Long id) {
        return espacos.findById(id);
    }

    @Override
    public List<Espaco> todos() {
        return espacos.findAll();
    }

    @Override
    public long contar() {
        return espacos.count();
    }
}
