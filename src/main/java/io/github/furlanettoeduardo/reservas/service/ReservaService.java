package io.github.furlanettoeduardo.reservas.service;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
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

    private final ReservaRepositorio reservas;
    private final EspacoRepositorio espacos;
    private final ClienteRepositorio clientes;

    public ReservaService(ReservaRepositorio reservas,
                         EspacoRepositorio espacos,
                         ClienteRepositorio clientes) {
        this.reservas = reservas;
        this.espacos = espacos;
        this.clientes = clientes;
    }

    /**
     * A verificacao aqui perde a corrida, e continua perdendo de proposito.
     *
     * <p>TOCTOU -- time of check to time of use. Sob READ_COMMITTED (default do Postgres) a
     * segunda transacao nao enxerga a linha ainda nao commitada da primeira: as duas chamam
     * existeSobreposicao(), as duas recebem false, as duas tentam gravar. Reproduzido em
     * ConcorrenciaReservaIT, que antes da V2 registrava duas reservas sobrepostas confirmadas.
     *
     * <p>Isto <b>nao</b> foi corrigido em Java, e nao deve ser. A V2 pos uma EXCLUDE constraint
     * com tstzrange no banco: a segunda gravacao agora e recusada la. A divisao de papeis e
     * deliberada -- esta verificacao e caminho rapido, para devolver 409 com mensagem util no
     * caso comum; a constraint e a garantia, e vale igual para import manual, script de carga
     * ou um segundo servico, que nenhum lock em Java alcancaria.
     *
     * <p>Consequencia que o chamador precisa conhecer: sob concorrencia, este metodo lanca
     * DataIntegrityViolationException (gravacoes escalonadas) ou CannotAcquireLockException
     * (gravacoes simultaneas, deadlock na checagem da exclusao). Ambas tratadas em
     * ApiExceptionHandler, com type distinto para que a razao entre elas meça a janela de
     * corrida.
     */
    @Transactional
    public ReservaResponse criar(NovaReserva comando) {
        Periodo periodo = new Periodo(comando.inicio(), comando.fim());

        Espaco espaco = espacos.porId(comando.espacoId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("espaco", comando.espacoId()));
        Cliente cliente = clientes.porId(comando.clienteId())
                .orElseThrow(() -> new RecursoNaoEncontradoException("cliente", comando.clienteId()));

        if (reservas.existeSobreposicao(
                espaco.getId(), StatusReserva.CONFIRMADA, periodo.inicio(), periodo.fim())) {
            throw new ConflitoDeReservaException(espaco.getId(), periodo);
        }

        return ReservaResponse.de(reservas.salvar(Reserva.nova(espaco, cliente, periodo)));
    }

    /**
     * Agora com salvar() explicito, e isso e a mudanca visivel da refatoracao neste arquivo.
     *
     * <p>Antes o dominio era a entidade JPA, entao mudar o objeto bastava: o dirty checking
     * emitia o UPDATE sozinho. Com o dominio separado e imutavel, {@code cancelar()} devolve uma
     * reserva nova e nada acontece se ninguem gravar. Uma linha a mais, e em troca a gravacao
     * deixou de ser efeito colateral invisivel -- o "update fantasma" da patologia n.3 nao tem
     * mais como existir.
     */
    @Transactional
    public void cancelar(Long reservaId) {
        Reserva reserva = reservas.porId(reservaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("reserva", reservaId));
        reservas.salvar(reserva.cancelar());
    }

    /**
     * Mapeia para DTO <b>dentro</b> da transacao -- e o unico lugar onde os proxies LAZY ainda
     * inicializam, e por isso o N+1 nascia aqui: 50 reservas custavam 52 queries.
     *
     * <p>Corrigido com plano de fetch no repositorio, nao mudando o mapeamento: 1 query.
     * As tres alternativas medidas estao em CorrecaoNMaisUmIT, e ContagemDeQueriesIT trava o
     * numero para que a regressao quebre o build.
     */
    @Transactional(readOnly = true)
    public List<ReservaResponse> listarConfirmadasDoEspaco(Long espacoId) {
        return reservas.confirmadasDoEspaco(espacoId)
                .stream()
                .map(ReservaResponse::de)
                .toList();
    }
}
