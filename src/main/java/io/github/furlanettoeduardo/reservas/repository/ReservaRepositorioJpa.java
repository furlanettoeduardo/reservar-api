package io.github.furlanettoeduardo.reservas.repository;

import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteSpringData;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoSpringData;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaSpringData;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador da porta {@link ReservaRepositorio}.
 *
 * <h2>A reconciliacao entre estado de dominio e estado gerenciado</h2>
 *
 * <p>E a decisao central de ports &amp; adapters com JPA, e a que a maioria dos tutoriais nao
 * enfrenta. O dominio e imutavel e nada o observa; o Hibernate observa suas proprias entidades.
 * {@link #salvar(Reserva)} tem que ligar os dois, e o caminho depende de a reserva ser nova ou
 * existente:
 *
 * <ul>
 *   <li><b>Nova</b> (id nulo): monta uma {@code ReservaJpa}, persiste, devolve o dominio com o
 *       id que o banco gerou.
 *   <li><b>Existente</b>: busca a instancia gerenciada e <b>copia o estado sobre ela</b>. Como a
 *       busca acontece dentro da mesma transacao que carregou a reserva, o persistence context
 *       devolve <b>o mesmo objeto</b> -- entao o dirty checking e o {@code @Version} continuam
 *       funcionando exatamente como antes da refatoracao.
 * </ul>
 *
 * <p><b>E ha um limite nisso, e ele importa.</b> A protecao contra lost update depende de
 * {@code salvar} rodar na mesma transacao que carregou. Se um objeto de dominio for carregado
 * numa transacao e salvo em outra -- um fluxo de edicao em duas requisicoes, por exemplo -- a
 * busca le a versao atual do banco, o {@code aplicar} sobrescreve com estado velho, e o lost
 * update volta sem que o {@code @Version} perceba. Para esse caso, a versao teria que viajar com
 * o dominio ou com o comando, e ai a decisao de "versao e conceito de dominio ou nao" precisa ser
 * tomada de verdade. Nao e o caso hoje, e esta registrado como limitacao no ADR 0004.
 */
@Repository
public class ReservaRepositorioJpa implements ReservaRepositorio {

    private final ReservaSpringData reservas;
    private final EspacoSpringData espacos;
    private final ClienteSpringData clientes;

    public ReservaRepositorioJpa(ReservaSpringData reservas, EspacoSpringData espacos,
                                 ClienteSpringData clientes) {
        this.reservas = reservas;
        this.espacos = espacos;
        this.clientes = clientes;
    }

    @Override
    public Reserva salvar(Reserva reserva) {
        if (reserva.getId() == null) {
            EspacoJpa espaco = espacos.findById(reserva.getEspaco().getId()).orElseThrow();
            ClienteJpa cliente = clientes.findById(reserva.getCliente().getId()).orElseThrow();
            return reservas.save(ReservaJpa.de(reserva, espaco, cliente)).paraDominio();
        }

        ReservaJpa gerenciada = reservas.findById(reserva.getId()).orElseThrow();
        gerenciada.aplicar(reserva);
        return reservas.save(gerenciada).paraDominio();
    }

    @Override
    public Optional<Reserva> porId(Long id) {
        return reservas.findById(id).map(ReservaJpa::paraDominio);
    }

    @Override
    public boolean existeSobreposicao(Long espacoId, StatusReserva status,
                                      Instant inicio, Instant fim) {
        return reservas.existeSobreposicao(espacoId, status, inicio, fim);
    }

    @Override
    public List<Reserva> confirmadasDoEspaco(Long espacoId) {
        return reservas.findComEspacoEClienteByEspacoIdAndStatusOrderByInicioAsc(
                        espacoId, StatusReserva.CONFIRMADA)
                .stream()
                .map(ReservaJpa::paraDominio)
                .toList();
    }

    @Override
    public long contar() {
        return reservas.count();
    }
}
