package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.port.ClienteRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.EspacoRepositorio;
import io.github.furlanettoeduardo.reservas.domain.port.ReservaRepositorio;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoSpringData;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaSpringData;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
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
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia nº 7 do 1B, e a mudança mais visível que a refatoração produziu num teste.
 *
 * <p>Antes, esta classe testava as entidades de <b>domínio</b>, porque elas eram as entidades
 * JPA. Todas as restrições da patologia — {@code instanceof} no equals, {@code hashCode}
 * constante, proxy divergindo em {@code getClass()} — eram restrições do domínio.
 *
 * <p>Agora elas são restrições do <b>adaptador</b>. {@code EspacoJpa} continua carregando as três,
 * e as medições continuam valendo para ela. O domínio ficou livre: pode ter {@code hashCode}
 * disperso, {@code getClass()} no equals, e as 2049 comparações por busca não existem lá.
 *
 * <p>É o padrão de sempre, e desta vez ele foi de graça: a correção moveu a patologia de camada,
 * e a camada onde ela caiu é a que ninguém coloca em {@code HashSet}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProxyEmHashSetIT {

    @Autowired
    private ReservaRepositorio reservas;
    @Autowired
    private EspacoRepositorio espacos;
    @Autowired
    private ClienteRepositorio clientes;
    @Autowired
    private ReservaSpringData reservasJpa;
    @Autowired
    private EspacoSpringData espacosJpa;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private JdbcTemplate jdbc;

    private Long reservaId;
    private Long espacoId;
    private ContadorDeQueries contador;

    @BeforeEach
    void semear() {
        LimpezaDeBase.limpar(jdbc);
        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacos.salvar(new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
            Cliente cliente = clientes.salvar(new Cliente("Ana", "ana@exemplo.com"));
            Instant inicio = Instant.parse("2026-09-01T13:00:00Z");
            espacoId = espaco.getId();
            reservaId = reservas.salvar(Reserva.nova(espaco, cliente,
                    new Periodo(inicio, inicio.plusSeconds(7200)))).getId();
        });
        contador = new ContadorDeQueries(emf);
    }

    /** Carregado em transação própria, para não ser o mesmo objeto que o proxy referencia. */
    private EspacoJpa espacoDesanexado() {
        return transacao.execute(status -> espacosJpa.findById(espacoId).orElseThrow());
    }

    // ----------------------------------------------- o adaptador, onde a patologia mora agora

    @Test
    void oProxyNaoEhDaClasseDaEntidadeMasEhInstanciaDela() {
        transacao.executeWithoutResult(status -> {
            EspacoJpa proxy = reservasJpa.findById(reservaId).orElseThrow().getEspaco();

            assertThat(Hibernate.isInitialized(proxy)).isFalse();
            assertThat(proxy.getClass())
                    .as("o proxy e uma subclasse gerada, entao getClass() nao devolve EspacoJpa")
                    .isNotEqualTo(EspacoJpa.class);
            assertThat(proxy)
                    .as("mas instanceof funciona, porque a subclasse E um EspacoJpa. Por isso o "
                            + "equals da entidade JPA usa instanceof e nao getClass().")
                    .isInstanceOf(EspacoJpa.class);
        });
    }

    @Test
    void aReceitaComGetClassHashCodeColocariaProxyEEntidadeEmBucketsDiferentes() {
        transacao.executeWithoutResult(status -> {
            EspacoJpa proxy = reservasJpa.findById(reservaId).orElseThrow().getEspaco();

            assertThat(proxy.getClass().hashCode())
                    .as("contrafactual: com hashCode() = getClass().hashCode(), proxy e entidade "
                            + "cairiam em buckets diferentes e contains() devolveria false mesmo "
                            + "com equals correto. E a receita que circula mais.")
                    .isNotEqualTo(EspacoJpa.class.hashCode());
        });
    }

    /**
     * O custo escondido: {@code HashSet} precisa de {@code hashCode}, e o proxy só responde
     * depois de inicializar. Colocar proxy num conjunto dispara SELECT.
     */
    @Test
    void contemProxyDisparaUmaQuery() {
        EspacoJpa desanexado = espacoDesanexado();
        Set<EspacoJpa> conjunto = new HashSet<>();
        conjunto.add(desanexado);

        transacao.executeWithoutResult(status -> {
            EspacoJpa proxy = reservasJpa.findById(reservaId).orElseThrow().getEspaco();
            assertThat(Hibernate.isInitialized(proxy)).isFalse();

            var medicao = contador.medir(() -> conjunto.contains(proxy));

            System.out.printf("%n[proxy] contains(proxy) -> %d query, inicializado=%b%n",
                    medicao.queries(), Hibernate.isInitialized(proxy));

            assertThat(medicao.resultado()).isTrue();
            assertThat(Hibernate.isInitialized(proxy))
                    .as("o conjunto forcou a inicializacao para poder calcular o bucket")
                    .isTrue();
            assertThat(medicao.queries()).isEqualTo(1);
        });
    }

    @Test
    void entidadeJpaAdicionadaAntesDoPersistContinuaNoConjuntoDepois() {
        Set<EspacoJpa> conjunto = new HashSet<>();

        transacao.executeWithoutResult(status -> {
            EspacoJpa novo = EspacoJpa.de(new Espaco("Sala Verde", 10, new BigDecimal("90.00")));
            assertThat(novo.getId()).isNull();

            conjunto.add(novo);
            int bucketAntes = novo.hashCode();

            espacosJpa.save(novo);

            assertThat(novo.getId()).isNotNull();
            assertThat(novo.hashCode())
                    .as("hash constante: o id apareceu e o bucket nao mudou")
                    .isEqualTo(bucketAntes);
            assertThat(conjunto.contains(novo))
                    .as("com hashCode derivado do id, o objeto teria ido para outro bucket no "
                            + "flush e este contains devolveria false")
                    .isTrue();
        });
    }

    // ----------------------------------------------- o dominio, onde ela deixou de existir

    /**
     * O ganho da refatoração, medido: o domínio não tem proxy, então pode ter {@code hashCode}
     * disperso — e um {@code HashSet} de espaços de domínio faz uma comparação por busca em vez
     * de 2049.
     */
    @Test
    void oDominioNaoTemProxyEPodeTerHashDisperso() {
        Espaco um = espacos.porId(espacoId).orElseThrow();
        Espaco outroCarregamento = espacos.porId(espacoId).orElseThrow();

        assertThat(outroCarregamento)
                .as("dois carregamentos, objetos diferentes, iguais por valor")
                .isNotSameAs(um)
                .isEqualTo(um);

        assertThat(new HashSet<>(Set.of(um)).contains(outroCarregamento))
                .as("encontra pelo hash de valor, sem inicializar proxy nenhum -- porque nao "
                        + "existe proxy nesta camada")
                .isTrue();

        assertThat(um.hashCode())
                .as("hash derivado do estado, nao constante de classe: dois espacos diferentes "
                        + "caem em buckets diferentes, e a busca volta a ser O(1)")
                .isNotEqualTo(new Espaco(999L, "Outra", 1, new BigDecimal("1.00"), null).hashCode());
    }

    /**
     * A armadilha da escala do {@code BigDecimal} dentro do próprio {@code hashCode} do domínio,
     * e por que o {@code stripTrailingZeros()} está lá.
     */
    @Test
    void oHashDoDominioIgnoraEscalaDoPreco() {
        Espaco calculado = new Espaco(1L, "Sala", 10, new BigDecimal("150.00"), null);
        Espaco lidoDoBanco = new Espaco(1L, "Sala", 10, new BigDecimal("150.0000"), null);

        assertThat(lidoDoBanco)
                .as("o equals compara preco com compareTo, entao ignora escala")
                .isEqualTo(calculado);
        assertThat(lidoDoBanco.hashCode())
                .as("e o hashCode tem que concordar. Sem stripTrailingZeros, BigDecimal.hashCode "
                        + "distinguiria as escalas e dois objetos iguais cairiam em buckets "
                        + "diferentes -- a patologia n.8 quebrando o contrato da n.7.")
                .isEqualTo(calculado.hashCode());
    }
}
