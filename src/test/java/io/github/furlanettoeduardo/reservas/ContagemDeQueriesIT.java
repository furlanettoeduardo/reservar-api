package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import io.github.furlanettoeduardo.reservas.service.NovaReserva;
import io.github.furlanettoeduardo.reservas.service.ReservaResponse;
import io.github.furlanettoeduardo.reservas.service.ReservaService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baselines de custo de query do caminho de produção, travados com igualdade exata para que
 * qualquer mudança em plano de fetch tenha que encarar o número.
 *
 * <p>{@code @SpringBootTest} <b>sem</b> {@code @Transactional}: dentro da transação de um
 * {@code @DataJpaTest} os clientes já estariam no persistence context e a listagem devolveria 1
 * query de qualquer jeito — o teste passaria medindo o cache em vez da query.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ContagemDeQueriesIT {

    private static final int QUANTIDADE_DE_RESERVAS = 50;

    @Autowired
    private ReservaService service;
    @Autowired
    private ReservaRepositorio reservas;
    @Autowired
    private EspacoRepositorio espacos;
    @Autowired
    private ClienteRepositorio clientes;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private JdbcTemplate jdbc;

    private Long espacoId;
    private Long clienteAvulso;
    private ContadorDeQueries contador;

    @BeforeEach
    void semearClientesDistintos() {
        LimpezaDeBase.limpar(jdbc);

        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacos.salvar(new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
            espacoId = espaco.getId();
            clienteAvulso = clientes.salvar(new Cliente("Avulso", "avulso@exemplo.com")).getId();

            Instant base = Instant.parse("2026-09-01T08:00:00Z");
            for (int i = 0; i < QUANTIDADE_DE_RESERVAS; i++) {
                Cliente cliente = clientes.salvar(
                        new Cliente("Cliente " + i, "cliente%d@exemplo.com".formatted(i)));
                reservas.salvar(Reserva.nova(espaco, cliente, new Periodo(
                        base.plusSeconds(i * 3600L), base.plusSeconds((i + 1) * 3600L))));
            }
        });

        contador = new ContadorDeQueries(emf);
    }

    @Test
    void medeAsQueriesDaListagem() {
        var medicao = contador.medir(() -> service.listarConfirmadasDoEspaco(espacoId));
        List<ReservaResponse> resposta = medicao.resultado();

        System.out.printf("%n[listagem] %d reservas -> %d queries%n",
                resposta.size(), medicao.queries());

        assertThat(resposta).hasSize(QUANTIDADE_DE_RESERVAS);
        assertThat(resposta).allSatisfy(r -> {
            assertThat(r.espacoNome()).isEqualTo("Sala Azul");
            assertThat(r.clienteNome()).startsWith("Cliente ");
        });

        // Igualdade exata, nao isLessThanOrEqualTo: um teto frouxo passaria calado tanto se o
        // N+1 voltasse dentro do teto quanto se o custo mudasse por outro motivo.
        //
        // Este numero era 52 antes do @EntityGraph, e foi o teste que falhou quando ele entrou.
        // Depois da refatoracao hexagonal continua 1, e isso e informacao: o mapeamento para
        // dominio toca espaco e cliente em TODAS as reservas, entao se o plano de fetch sumir o
        // custo volta na hora. Antes o N+1 dependia de alguem tocar as associacoes; agora o
        // mapeador sempre toca.
        assertThat(medicao.queries())
                .as("guarda de regressao: sem o plano de fetch no adaptador, este numero volta a "
                        + "crescer com a cardinalidade dos clientes")
                .isEqualTo(1);
    }

    @Test
    void medeAsQueriesDaCriacao() {
        // Bem depois das 50 horas consecutivas que o cenario semeia a partir de 01/09 08:00.
        Instant livre = Instant.parse("2026-09-20T08:00:00Z");

        var medicao = contador.medir(() -> service.criar(
                new NovaReserva(espacoId, clienteAvulso, livre, livre.plusSeconds(3600))));

        System.out.printf("%n[criar] 1 reserva -> %d queries%n", medicao.queries());

        assertThat(medicao.resultado().id()).isNotNull();
        assertThat(medicao.queries())
                .as("espaco + cliente + verificacao + insert. O salvar() do adaptador rebusca "
                        + "espaco e cliente, mas o persistence context ja os tem da mesma "
                        + "transacao -- entao a reconciliacao nao custou query nenhuma, e este "
                        + "numero nao mudou com a refatoracao.")
                .isEqualTo(4);
    }

    /**
     * A patologia nº 2 do 1B invertida pela refatoração.
     *
     * <p>Antes, o repositório devolvia entidade JPA com proxies, e tocá-los fora da transação
     * lançava {@code LazyInitializationException} — era o motivo estrutural para mapear dentro do
     * serviço. Agora a porta devolve domínio já materializado, então isso não pode mais
     * acontecer acima do adaptador.
     *
     * <p>A patologia não foi resolvida, foi <b>movida</b>: ela continua possível dentro do
     * adaptador, e é o mapeador que a absorve — ao custo de tocar sempre as duas associações.
     */
    @Test
    void dominioForaDaTransacaoContinuaUtilizavel() {
        List<Reserva> lista = transacao.execute(status -> reservas.confirmadasDoEspaco(espacoId));

        assertThat(lista.getFirst().getCliente().getNome())
                .as("fora da transacao, e sem erro: o mapeador ja materializou tudo")
                .startsWith("Cliente ");
        assertThat(lista.getFirst().getEspaco().getNome()).isEqualTo("Sala Azul");
    }
}
