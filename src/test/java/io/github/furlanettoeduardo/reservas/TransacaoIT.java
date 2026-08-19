package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.PatologiasTransacionais.FalhaDeNegocio;
import io.github.furlanettoeduardo.reservas.PatologiasTransacionais.Observacao;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Patologias n.4 (autoinvocacao) e n.5 (checked exception sem rollback) do 1B.
 *
 * <p>{@code @SpringBootTest} sem {@code @Transactional}, pelo mesmo motivo das outras medicoes:
 * dentro da transacao de um {@code @DataJpaTest} nao existe commit nem rollback de verdade, e
 * os dois experimentos sao justamente sobre commit e rollback.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, PatologiasTransacionais.class})
class TransacaoIT {

    @Autowired
    private PatologiasTransacionais patologias;
    @Autowired
    private ClienteRepository clientes;
    @Autowired
    private ReservaRepository reservas;
    @Autowired
    private TransactionTemplate transacao;

    @BeforeEach
    void limpar() {
        transacao.executeWithoutResult(status -> {
            reservas.deleteAll();
            clientes.deleteAll();
        });
    }

    // ---------------------------------------------------------------- n.4: autoinvocacao

    @Test
    void chamadaDeFora_passaPeloProxyEAbreTransacao() {
        Observacao observacao = patologias.anotado();

        assertThat(observacao.transacaoAtiva())
                .as("controle: a referencia injetada e o proxy, entao a anotacao vale")
                .isTrue();
        assertThat(observacao.nomeDaTransacao()).endsWith(".anotado");
    }

    @Test
    void chamadaViaThis_naoAbreTransacaoNenhuma() {
        Observacao observacao = patologias.chamaOAnotadoViaThis();

        assertThat(observacao.transacaoAtiva())
                .as("mesmo metodo @Transactional do teste anterior. A chamada nao sai do "
                        + "objeto, entao nao passa pelo proxy, entao o interceptor nao roda -- "
                        + "e a anotacao nao produz nada, sem aviso nenhum")
                .isFalse();
        assertThat(observacao.nomeDaTransacao()).isNull();
    }

    @Test
    void requiresNewViaThis_continuaNaTransacaoDeFora() {
        Observacao viaThis = patologias.pedeTransacaoNovaViaThis();
        Observacao viaProxy = patologias.pedeTransacaoNovaViaProxy();

        assertThat(viaThis.nomeDaTransacao())
                .as("REQUIRES_NEW ignorado: o nome e o do metodo de FORA, prova de que nenhuma "
                        + "transacao nova foi aberta")
                .endsWith(".pedeTransacaoNovaViaThis");

        assertThat(viaProxy.nomeDaTransacao())
                .as("pelo proxy, a transacao nova existe e leva o nome do metodo interno")
                .endsWith(".observaEmTransacaoNova");
    }

    @Test
    void requiresNewViaThis_perdeAAuditoriaNoRollback() {
        String email = "auditoria-this@exemplo.com";

        assertThatThrownBy(() -> patologias.gravaAuditoriaViaThisEFalha(email))
                .isInstanceOf(IllegalStateException.class);

        assertThat(clientes.existsByEmail(email))
                .as("a consequencia que custa dado: a auditoria que deveria sobreviver ao "
                        + "rollback entrou na mesma transacao e sumiu junto")
                .isFalse();
    }

    @Test
    void requiresNewViaProxy_preservaAAuditoriaNoRollback() {
        String email = "auditoria-proxy@exemplo.com";

        assertThatThrownBy(() -> patologias.gravaAuditoriaViaProxyEFalha(email))
                .isInstanceOf(IllegalStateException.class);

        assertThat(clientes.existsByEmail(email))
                .as("mesmo codigo, mesma anotacao, so mudou por onde a chamada passou")
                .isTrue();
    }

    // ------------------------------------------- n.5: checked exception sem rollback

    @Test
    void checkedExceptionNaoDisparaRollback() {
        String email = "checked@exemplo.com";

        assertThatThrownBy(() -> patologias.gravaEFalhaComChecked(email))
                .isInstanceOf(FalhaDeNegocio.class);

        assertThat(clientes.existsByEmail(email))
                .as("o metodo falhou e a linha ficou gravada. O rollback padrao do Spring so "
                        + "cobre RuntimeException e Error -- checked commita")
                .isTrue();
    }

    @Test
    void runtimeExceptionDisparaRollback() {
        String email = "runtime@exemplo.com";

        assertThatThrownBy(() -> patologias.gravaEFalhaComRuntime(email))
                .isInstanceOf(IllegalStateException.class);

        assertThat(clientes.existsByEmail(email))
                .as("controle: mesma estrutura, so muda a familia da excecao")
                .isFalse();
    }

    @Test
    void rollbackForDeclaradoCorrigeOCaso() throws Exception {
        String email = "declarada@exemplo.com";

        assertThatThrownBy(() -> patologias.gravaEFalhaComCheckedDeclarada(email))
                .isInstanceOf(FalhaDeNegocio.class);

        assertThat(clientes.existsByEmail(email))
                .as("rollbackFor = FalhaDeNegocio.class e a correcao")
                .isFalse();
    }

    @Test
    void excecaoCapturadaDentroNaoEhOMesmoProblema() {
        String email = "engolida@exemplo.com";

        patologias.gravaEEngoleAChecked(email);

        assertThat(clientes.existsByEmail(email))
                .as("aqui o commit esta CORRETO: a excecao nao atravessou a fronteira "
                        + "transacional, entao nao havia o que desfazer. Separar este caso do "
                        + "anterior evita 'corrigir' com rollbackFor onde nao ha problema")
                .isTrue();
    }
}
