package io.github.furlanettoeduardo.reservas.web;

import io.github.furlanettoeduardo.reservas.service.NovaReserva;
import io.github.furlanettoeduardo.reservas.service.ReservaResponse;
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
public class ReservaController {

    private final ReservaService reservaService;

    public ReservaController(ReservaService reservaService) {
        this.reservaService = reservaService;
    }

    /** {@code @Valid} e o que ativa as anotacoes do request. Sem ele, elas nao rodam. */
    @PostMapping("/reservas")
    public ResponseEntity<ReservaResponse> criar(@Valid @RequestBody CriarReservaRequest request,
                                                 UriComponentsBuilder uri) {
        ReservaResponse criada = reservaService.criar(new NovaReserva(
                request.espacoId(), request.clienteId(), request.inicio(), request.fim()));

        URI local = uri.path("/reservas/{id}").buildAndExpand(criada.id()).toUri();
        return ResponseEntity.created(local).body(criada);
    }

    @GetMapping("/espacos/{espacoId}/reservas")
    public List<ReservaResponse> listarDoEspaco(@PathVariable Long espacoId) {
        return reservaService.listarConfirmadasDoEspaco(espacoId);
    }

    /** Cancelamento e criacao de um fato, nao DELETE: a reserva continua existindo cancelada. */
    @PostMapping("/reservas/{id}/cancelamento")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelar(@PathVariable Long id) {
        reservaService.cancelar(id);
    }
}
