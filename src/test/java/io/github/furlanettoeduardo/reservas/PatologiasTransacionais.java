package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteSpringData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Laboratorio das patologias n.4 e n.5 do 1B. Vive em codigo de teste de proposito: sao
 * metodos deliberadamente quebrados, e producao nao deve carregar isso.
 *
 * <p>Todos os metodos sao publicos porque o Spring so considera {@code @Transactional} em
 * metodo publico -- em metodo package-private ou protected a anotacao e ignorada em silencio,
 * que ja e uma variante da mesma patologia.
 */
public class PatologiasTransacionais {

    /** Checked de proposito: e a unica familia que o rollback padrao do Spring nao cobre. */
    public static class FalhaDeNegocio extends Exception {
        public FalhaDeNegocio(String mensagem) {
            super(mensagem);
        }
    }

    public record Observacao(boolean transacaoAtiva, String nomeDaTransacao) {

        static Observacao daThreadAtual() {
            return new Observacao(
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.getCurrentTransactionName());
        }
    }

    private final ClienteSpringData clientes;

    /**
     * A referencia ao proprio bean, que e o proxy. ObjectProvider e nao injecao direta para
     * nao criar dependencia circular na construcao -- a resolucao acontece na chamada.
     */
    private final ObjectProvider<PatologiasTransacionais> proxyDeSiMesmo;

    public PatologiasTransacionais(ClienteSpringData clientes,
                                   ObjectProvider<PatologiasTransacionais> proxyDeSiMesmo) {
        this.clientes = clientes;
        this.proxyDeSiMesmo = proxyDeSiMesmo;
    }

    // ---------------------------------------------------------------- n.4: autoinvocacao

    @Transactional
    public Observacao anotado() {
        return Observacao.daThreadAtual();
    }

    /**
     * Sem anotacao nenhuma, chamando o anotado por {@code this}. A chamada nao sai do objeto,
     * entao nao passa pelo proxy, entao o interceptor transacional nao roda. Nao existe
     * transacao alguma, apesar de {@code anotado()} estar anotado.
     */
    public Observacao chamaOAnotadoViaThis() {
        return this.anotado();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Observacao observaEmTransacaoNova() {
        return Observacao.daThreadAtual();
    }

    /** REQUIRES_NEW ignorado: a observacao volta com o nome da transacao <b>de fora</b>. */
    @Transactional
    public Observacao pedeTransacaoNovaViaThis() {
        return this.observaEmTransacaoNova();
    }

    @Transactional
    public Observacao pedeTransacaoNovaViaProxy() {
        return proxyDeSiMesmo.getObject().observaEmTransacaoNova();
    }

    // ------------------------------------------- n.4: a consequencia que custa dado

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registraAuditoria(String email) {
        clientes.save(ClienteJpa.de(new Cliente("Auditoria", email)));
    }

    /**
     * O padrao "grave a auditoria numa transacao separada para ela sobreviver ao rollback".
     * Via {@code this}, a auditoria entra na <b>mesma</b> transacao e some junto.
     */
    @Transactional
    public void gravaAuditoriaViaThisEFalha(String email) {
        this.registraAuditoria(email);
        throw new IllegalStateException("falha depois de registrar a auditoria");
    }

    @Transactional
    public void gravaAuditoriaViaProxyEFalha(String email) {
        proxyDeSiMesmo.getObject().registraAuditoria(email);
        throw new IllegalStateException("falha depois de registrar a auditoria");
    }

    // ------------------------------------------- n.5: checked exception sem rollback

    /**
     * Grava e entao lanca checked. A excecao atravessa a fronteira transacional -- que e a
     * condicao para o problema existir -- e o Spring <b>commita</b>: o rollback padrao so
     * dispara em RuntimeException e Error.
     */
    @Transactional
    public void gravaEFalhaComChecked(String email) throws FalhaDeNegocio {
        clientes.save(ClienteJpa.de(new Cliente("Checked", email)));
        throw new FalhaDeNegocio("gravou e nao deveria ter commitado");
    }

    @Transactional(rollbackFor = FalhaDeNegocio.class)
    public void gravaEFalhaComCheckedDeclarada(String email) throws FalhaDeNegocio {
        clientes.save(ClienteJpa.de(new Cliente("Declarada", email)));
        throw new FalhaDeNegocio("agora o rollback acontece");
    }

    /** Controle: mesma estrutura, excecao nao-checada. */
    @Transactional
    public void gravaEFalhaComRuntime(String email) {
        clientes.save(ClienteJpa.de(new Cliente("Runtime", email)));
        throw new IllegalStateException("rollback padrao cobre esta");
    }

    /**
     * A armadilha do "eu trato a excecao": capturada dentro do metodo, ela nunca atravessa a
     * fronteira transacional, entao nao ha o que fazer rollback -- e o commit e correto. Serve
     * para separar o problema real de um parecido que nao existe.
     */
    @Transactional
    public void gravaEEngoleAChecked(String email) {
        try {
            clientes.save(ClienteJpa.de(new Cliente("Engolida", email)));
            throw new FalhaDeNegocio("capturada aqui dentro");
        } catch (FalhaDeNegocio ignorada) {
            // nao propaga: a transacao segue valida e commita
        }
    }
}
