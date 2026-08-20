package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Leituras de catalogo, para a tela ter o que colocar nos selects.
 *
 * <p>Somente leitura de proposito: criar espaco e cliente continua fora do escopo da API, e a
 * semeadura de exemplo vive no perfil dev. O que este servico resolve e o problema concreto que o
 * smoke test do 1A expos -- sem uma forma de descobrir ids validos, ninguem chega a um 201.
 *
 * <p>Sem paginacao ainda. Quando a listagem crescer, entra
 * {@code ListPagingAndSortingRepository} ao lado, como registrado no ADR 0003.
 */
@Service
public class CatalogoService {

    private final EspacoRepositorio espacos;
    private final ClienteRepositorio clientes;

    public CatalogoService(EspacoRepositorio espacos, ClienteRepositorio clientes) {
        this.espacos = espacos;
        this.clientes = clientes;
    }

    @Transactional(readOnly = true)
    public List<EspacoResponse> listarEspacos() {
        return espacos.todos().stream().map(EspacoResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public List<ClienteResponse> listarClientes() {
        return clientes.todos().stream().map(ClienteResponse::de).toList();
    }
}
