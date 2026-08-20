const BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

/**
 * Erro que carrega o ProblemDetail da API. O `status` e o `detail` vem do corpo RFC 9457,
 * entao a tela mostra a mensagem que o servidor escreveu em vez de inventar uma propria.
 */
export class ErroDaApi extends Error {
  constructor(problema, status) {
    super(problema?.detail ?? `HTTP ${status}`)
    this.problema = problema ?? {}
    this.status = status
  }

  /** Campo -> mensagem, quando o 400 vem de Bean Validation. */
  get errosDeCampo() {
    return this.problema.erros ?? null
  }

  get detectadoPor() {
    return this.problema.detectadoPor ?? null
  }
}

async function pedir(caminho, opcoes) {
  const resposta = await fetch(`${BASE}${caminho}`, opcoes)

  if (resposta.status === 204) return null

  // Erro do backend vem como application/problem+json; erro de rede ou de proxy pode nao vir.
  const tipo = resposta.headers.get('content-type') ?? ''
  const corpo = tipo.includes('json') ? await resposta.json() : null

  if (!resposta.ok) throw new ErroDaApi(corpo, resposta.status)
  return corpo
}

export const listarEspacos = () => pedir('/espacos')
export const listarClientes = () => pedir('/clientes')
export const listarReservas = (espacoId) => pedir(`/espacos/${espacoId}/reservas`)

export const criarReserva = (corpo) =>
  pedir('/reservas', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(corpo),
  })

export const cancelarReserva = (id) =>
  pedir(`/reservas/${id}/cancelamento`, { method: 'POST' })
