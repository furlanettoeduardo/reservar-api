package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code @Transactional} fica aqui, e nao no controller nem no repositorio: e o servico que
 * define a unidade de trabalho ("verificar e gravar sao uma coisa so"). No repositorio a
 * transacao seria por metodo, quebrando essa unidade; no controller ela ficaria aberta durante
 * serializacao de resposta.
 */
@Service
public class ReservaService {

    private final ReservaRepository reservaRepository;
    private final EspacoRepository espacoRepository;
    private final ClienteRepository clienteRepository;

    public ReservaService(ReservaRepository reservaRepository,
                          EspacoRepository espacoRepository,
                          ClienteRepository clienteRepository) {
        this.reservaRepository = reservaRepository;
        this.espacoRepository = espacoRepository;
        this.clienteRepository = clienteRepository;
    }

    /**
     * Versao ingenua de proposito. Passa em qualquer teste single-thread.
     *
     * <p>TODO(1B): TOCTOU -- time of check to time of use. Sob READ_COMMITTED (default do
     * Postgres, confirmado no log de subida) a segunda transacao nao enxerga a linha ainda
     * nao commitada da primeira: as duas chamam existeSobreposicao(), as duas recebem false,
     * as duas gravam. Entre o if e o save() nao ha nada segurando a linha. Provar com duas
     * threads antes de corrigir -- a correcao esconde a evidencia. Candidatos, do mais forte
     * ao mais fraco: EXCLUDE constraint com tstzrange (V2, unica que vale para escrita vinda
     * de fora da app), lock pessimista no espaco, @Version na reserva.
     */
    @Transactional
    public ReservaResponse criar(NovaReserva comando) {
        Periodo periodo = new Periodo(comando.inicio(), comando.fim());

        Espaco espaco = espacoRepository.findById(comando.espacoId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("espaco", comando.espacoId()));
        Cliente cliente = clienteRepository.findById(comando.clienteId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("cliente", comando.clienteId()));

        if (reservaRepository.existeSobreposicao(
                espaco.getId(), StatusReserva.CONFIRMADA, periodo.inicio(), periodo.fim())) {
            throw new ConflitoDeReservaException(espaco.getId(), periodo);
        }

        return ReservaResponse.de(reservaRepository.save(Reserva.nova(espaco, cliente, periodo)));
    }

    /** Sem save(): dentro da transacao o dirty checking detecta a mudanca e emite o UPDATE. */
    @Transactional
    public void cancelar(Long reservaId) {
        Reserva reserva = reservaRepository.findById(reservaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("reserva", reservaId));
        reserva.cancelar();
    }

    /**
     * Mapeia para DTO <b>dentro</b> da transacao -- e o unico lugar onde os proxies LAZY ainda
     * inicializam. TODO(1B): e exatamente por isso que o N+1 nasce aqui. Ver a medicao em
     * ContagemDeQueriesIT antes de corrigir.
     */
    @Transactional(readOnly = true)
    public List<ReservaResponse> listarConfirmadasDoEspaco(Long espacoId) {
        return reservaRepository.findByEspacoIdAndStatusOrderByInicioAsc(
                        espacoId, StatusReserva.CONFIRMADA)
                .stream()
                .map(ReservaResponse::de)
                .toList();
    }
}
