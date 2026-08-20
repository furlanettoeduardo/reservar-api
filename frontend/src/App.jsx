import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  cancelarReserva,
  criarReserva,
  ErroDaApi,
  listarClientes,
  listarEspacos,
  listarReservas,
} from './api.js'

/** ISO com fuso -> valor de datetime-local (sem fuso, hora local do navegador). */
function paraCampoLocal(iso) {
  const d = new Date(iso)
  const deslocado = new Date(d.getTime() - d.getTimezoneOffset() * 60_000)
  return deslocado.toISOString().slice(0, 16)
}

/** datetime-local -> ISO UTC, que e o que a API espera. */
function paraIsoUtc(valorLocal) {
  return new Date(valorLocal).toISOString()
}

function formatarPeriodo(inicio, fim) {
  const opcoes = { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }
  const so = { hour: '2-digit', minute: '2-digit' }
  return `${new Date(inicio).toLocaleString('pt-BR', opcoes)} - ${new Date(fim).toLocaleTimeString('pt-BR', so)}`
}

const dinheiro = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

export default function App() {
  const [espacos, setEspacos] = useState([])
  const [clientes, setClientes] = useState([])
  const [reservas, setReservas] = useState([])

  const [espacoId, setEspacoId] = useState('')
  const [clienteId, setClienteId] = useState('')
  const [inicio, setInicio] = useState('')
  const [fim, setFim] = useState('')

  const [erro, setErro] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [carregando, setCarregando] = useState(true)
  const [enviando, setEnviando] = useState(false)

  // Carrega catalogo uma vez e ja posiciona o formulario num horario livre plausivel.
  useEffect(() => {
    Promise.all([listarEspacos(), listarClientes()])
      .then(([es, cs]) => {
        setEspacos(es)
        setClientes(cs)
        if (es.length) setEspacoId(String(es[0].id))
        if (cs.length) setClienteId(String(cs[0].id))

        const base = new Date()
        base.setDate(base.getDate() + 1)
        base.setHours(base.getHours(), 0, 0, 0)
        setInicio(paraCampoLocal(base.toISOString()))
        setFim(paraCampoLocal(new Date(base.getTime() + 3_600_000).toISOString()))
      })
      .catch((e) => setErro(e))
      .finally(() => setCarregando(false))
  }, [])

  const recarregarReservas = useCallback((id) => {
    if (!id) return Promise.resolve()
    return listarReservas(id).then(setReservas).catch(setErro)
  }, [])

  useEffect(() => {
    recarregarReservas(espacoId)
  }, [espacoId, recarregarReservas])

  const espacoSelecionado = useMemo(
    () => espacos.find((e) => String(e.id) === espacoId),
    [espacos, espacoId],
  )

  async function enviar(evento) {
    evento.preventDefault()
    setErro(null)
    setAviso(null)
    setEnviando(true)
    try {
      const criada = await criarReserva({
        espacoId: Number(espacoId),
        clienteId: Number(clienteId),
        inicio: paraIsoUtc(inicio),
        fim: paraIsoUtc(fim),
      })
      setAviso(`Reserva #${criada.id} confirmada por ${dinheiro.format(criada.valorTotal)}`)
      await recarregarReservas(espacoId)
    } catch (e) {
      setErro(e)
    } finally {
      setEnviando(false)
    }
  }

  async function cancelar(id) {
    setErro(null)
    setAviso(null)
    try {
      await cancelarReserva(id)
      setAviso(`Reserva #${id} cancelada, o horario voltou a ficar livre`)
      await recarregarReservas(espacoId)
    } catch (e) {
      setErro(e)
    }
  }

  return (
    <main>
      <header>
        <h1>reservar</h1>
        <p>Locação de espaços para eventos. Duas reservas confirmadas não podem se sobrepor.</p>
      </header>

      {carregando && <p className="estado">Carregando…</p>}

      {!carregando && !espacos.length && (
        <p className="estado">
          Nenhum espaço cadastrado. Suba a API com o perfil <code>dev</code> para semear os dados
          de exemplo.
        </p>
      )}

      {!carregando && espacos.length > 0 && (
        <>
          <section>
            <h2>Nova reserva</h2>
            <form onSubmit={enviar}>
              <label>
                Espaço
                <select value={espacoId} onChange={(e) => setEspacoId(e.target.value)}>
                  {espacos.map((e) => (
                    <option key={e.id} value={e.id}>
                      {e.nome} — {e.capacidade} lugares — {dinheiro.format(e.precoHora)}/h
                    </option>
                  ))}
                </select>
              </label>

              <label>
                Cliente
                <select value={clienteId} onChange={(e) => setClienteId(e.target.value)}>
                  {clientes.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.nome}
                    </option>
                  ))}
                </select>
              </label>

              <label>
                Início
                <input
                  type="datetime-local"
                  value={inicio}
                  onChange={(e) => setInicio(e.target.value)}
                  required
                />
              </label>

              <label>
                Fim
                <input
                  type="datetime-local"
                  value={fim}
                  onChange={(e) => setFim(e.target.value)}
                  required
                />
              </label>

              <button type="submit" disabled={enviando}>
                {enviando ? 'Reservando…' : 'Reservar'}
              </button>
            </form>

            {aviso && <p className="ok">{aviso}</p>}
            {erro && <Problema erro={erro} />}
          </section>

          <section>
            <h2>
              Reservas confirmadas
              {espacoSelecionado ? ` — ${espacoSelecionado.nome}` : ''}
            </h2>

            {!reservas.length && <p className="estado">Nenhuma reserva neste espaço.</p>}

            {reservas.length > 0 && (
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Período</th>
                    <th>Cliente</th>
                    <th>Valor</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {reservas.map((r) => (
                    <tr key={r.id}>
                      <td>{r.id}</td>
                      <td>{formatarPeriodo(r.inicio, r.fim)}</td>
                      <td>{r.clienteNome}</td>
                      <td>{dinheiro.format(r.valorTotal)}</td>
                      <td>
                        <button type="button" className="link" onClick={() => cancelar(r.id)}>
                          cancelar
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </main>
  )
}

/**
 * Mostra o ProblemDetail como o servidor mandou. O 409 de conflito e o caso interessante: a
 * mensagem diz qual espaço e qual período, e `detectadoPor` diz se foi a regra da aplicação ou
 * uma constraint do banco que barrou.
 */
function Problema({ erro }) {
  if (!(erro instanceof ErroDaApi)) {
    return (
      <div className="erro">
        <strong>Falha de rede.</strong> A API está no ar em <code>localhost:8080</code>?
      </div>
    )
  }

  const campos = erro.errosDeCampo
  const { inicio, fim } = erro.problema

  // Quando o servidor manda o periodo em campos, formata no fuso do navegador -- senao a lista
  // mostraria 11:00 e o erro mostraria 14:00Z para o mesmo horario.
  const mensagem =
    inicio && fim
      ? `Este espaço já tem reserva confirmada entre ${formatarPeriodo(inicio, fim)}`
      : erro.message

  return (
    <div className="erro">
      <strong>
        {erro.status} {erro.problema.title ?? 'Erro'}
      </strong>
      <p>{mensagem}</p>
      {campos && (
        <ul>
          {Object.entries(campos).map(([campo, mensagem]) => (
            <li key={campo}>
              <code>{campo}</code>: {mensagem}
            </li>
          ))}
        </ul>
      )}
      {erro.detectadoPor && (
        <p className="detalhe">
          detectado por: <code>{erro.detectadoPor}</code>
          {erro.problema.retentavel ? ' (tentar de novo pode resolver)' : ''}
        </p>
      )}
    </div>
  )
}
