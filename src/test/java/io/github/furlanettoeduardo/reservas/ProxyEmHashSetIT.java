package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.repository.ClienteRepository;
import io.github.furlanettoeduardo.reservas.repository.EspacoRepository;
import io.github.furlanettoeduardo.reservas.repository.ReservaRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Patologia n.7 do 1B, parte que precisa do banco: entidade em {@code HashSet} com proxy no
 * meio.
 *
 * <p>Aqui as duas receitas mais comuns de {@code equals}/{@code hashCode} para entidade JPA
 * divergem, e a divergencia so aparece com um proxy de verdade. As contagens de comparacao
 * estao em {@code ContratoDeHashCodeTests}, que nao precisa de banco.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProxyEmHashSetIT {

    @Autowired
    private ReservaRepository reservaRepository;
    @Autowired
    private EspacoRepository espacoRepository;
    @Autowired
    private ClienteRepository clienteRepository;
    @Autowired
    private TransactionTemplate transacao;
    @Autowired
    private EntityManagerFactory emf;

    private Long reservaId;
    private Long espacoId;
    private ContadorDeQueries contador;

    @BeforeEach
    void semear() {
        transacao.executeWithoutResult(status -> {
            reservaRepository.deleteAll();
            clienteRepository.deleteAll();
            espacoRepository.deleteAll();
        });
        transacao.executeWithoutResult(status -> {
            Espaco espaco = espacoRepository.save(
                    new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
            Cliente cliente = clienteRepository.save(new Cliente("Ana", "ana@exemplo.com"));
            Instant inicio = Instant.parse("2026-09-01T13:00:00Z");
            espacoId = espaco.getId();
            reservaId = reservaRepository.save(Reserva.nova(espaco, cliente,
                    new Periodo(inicio, inicio.plusSeconds(7200)))).getId();
        });
        contador = new ContadorDeQueries(emf);
    }

    /** Carregado em transacao propria, para nao ser o mesmo objeto que o proxy referencia. */
    private Espaco espacoDesanexado() {
        return transacao.execute(status -> espacoRepository.findById(espacoId).orElseThrow());
    }

    @Test
    void oProxyNaoEhDaClasseDaEntidadeMasEhInstanciaDela() {
        transacao.executeWithoutResult(status -> {
            Espaco proxy = reservaRepository.findById(reservaId).orElseThrow().getEspaco();

            assertThat(Hibernate.isInitialized(proxy)).isFalse();
            assertThat(proxy.getClass())
                    .as("o proxy e uma subclasse gerada, entao getClass() nao devolve Espaco")
                    .isNotEqualTo(Espaco.class);
            assertThat(proxy)
                    .as("mas instanceof funciona, porque a subclasse E um Espaco. Por isso o "
                            + "equals das entidades usa instanceof e nao getClass().")
                    .isInstanceOf(Espaco.class);
        });
    }

    @Test
    void aReceitaComGetClassHashCodeColocariaProxyEEntidadeEmBucketsDiferentes() {
        transacao.executeWithoutResult(status -> {
            Espaco proxy = reservaRepository.findById(reservaId).orElseThrow().getEspaco();

            assertThat(proxy.getClass().hashCode())
                    .as("contrafactual: com hashCode() = getClass().hashCode(), proxy e "
                            + "entidade cairiam em buckets diferentes e contains() devolveria "
                            + "false mesmo com equals correto. E a receita que circula mais.")
                    .isNotEqualTo(Espaco.class.hashCode());

            assertThat(proxy.hashCode())
                    .as("com a constante Espaco.class.hashCode(), os dois hasheiam igual")
                    .isEqualTo(espacoDesanexado().hashCode());
        });
    }

    @Test
    void hashSetEncontraAEntidadePeloProxy() {
        Espaco desanexado = espacoDesanexado();
        Set<Espaco> conjunto = new HashSet<>();
        conjunto.add(desanexado);

        transacao.executeWithoutResult(status -> {
            Espaco proxy = reservaRepository.findById(reservaId).orElseThrow().getEspaco();

            assertThat(proxy).isNotSameAs(desanexado);
            assertThat(conjunto.contains(proxy))
                    .as("mesmo id, hash igual, equals por instanceof: encontra")
                    .isTrue();
        });
    }

    /**
     * O custo escondido: {@code HashSet} precisa de {@code hashCode}, e o proxy so responde
     * hashCode depois de inicializar. Colocar proxy num conjunto dispara SELECT.
     */
    @Test
    void contemProxyDisparaUmaQuery() {
        Espaco desanexado = espacoDesanexado();
        Set<Espaco> conjunto = new HashSet<>();
        conjunto.add(desanexado);

        transacao.executeWithoutResult(status -> {
            Espaco proxy = reservaRepository.findById(reservaId).orElseThrow().getEspaco();
            assertThat(Hibernate.isInitialized(proxy)).isFalse();

            var medicao = contador.medir(() -> conjunto.contains(proxy));

            System.out.printf("%n[proxy] contains(proxy) -> %d query, inicializado=%b%n",
                    medicao.queries(), Hibernate.isInitialized(proxy));

            assertThat(medicao.resultado()).isTrue();
            assertThat(Hibernate.isInitialized(proxy))
                    .as("o conjunto forcou a inicializacao para poder calcular o bucket")
                    .isTrue();
            assertThat(medicao.queries())
                    .as("uma colecao de entidades inicializa proxy em silencio -- e o N+1 da "
                            + "patologia n.1 entrando por outra porta")
                    .isEqualTo(1);
        });
    }

    /**
     * O motivo pelo qual o hashCode e constante e nao derivado do id: dentro de uma transacao,
     * o id vai de null para um valor no flush. Hash que dependesse dele mudaria de bucket, e a
     * entidade sumiria de qualquer conjunto em que ja estivesse.
     */
    @Test
    void entidadeAdicionadaAntesDoPersistContinuaNoConjuntoDepois() {
        Set<Espaco> conjunto = new HashSet<>();

        transacao.executeWithoutResult(status -> {
            Espaco novo = new Espaco("Sala Verde", 10, new BigDecimal("90.00"));
            assertThat(novo.getId()).isNull();

            conjunto.add(novo);
            int bucketAntes = novo.hashCode();

            // save() e nao saveAndFlush(): o ADR 0003 nao expoe saveAndFlush, e com
            // GenerationType.IDENTITY ele seria redundante -- o INSERT sai na hora, porque o
            // Hibernate precisa do id gerado pelo banco para registrar a entidade.
            espacoRepository.save(novo);

            assertThat(novo.getId()).isNotNull();
            assertThat(novo.hashCode())
                    .as("hash constante: o id apareceu e o bucket nao mudou")
                    .isEqualTo(bucketAntes);
            assertThat(conjunto.contains(novo))
                    .as("com hashCode derivado do id, o objeto teria ido para outro bucket no "
                            + "flush e este contains devolveria false -- entidade perdida "
                            + "dentro do proprio HashSet")
                    .isTrue();
        });
    }
}
