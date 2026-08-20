package io.github.furlanettoeduardo.reservas.web;

import io.github.furlanettoeduardo.reservas.service.CatalogoService;
import io.github.furlanettoeduardo.reservas.service.ClienteResponse;
import io.github.furlanettoeduardo.reservas.service.EspacoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CatalogoController {

    private final CatalogoService catalogoService;

    public CatalogoController(CatalogoService catalogoService) {
        this.catalogoService = catalogoService;
    }

    @GetMapping("/espacos")
    public List<EspacoResponse> espacos() {
        return catalogoService.listarEspacos();
    }

    @GetMapping("/clientes")
    public List<ClienteResponse> clientes() {
        return catalogoService.listarClientes();
    }
}
