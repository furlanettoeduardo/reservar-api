package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.show-sql=true",
        "spring.jpa.properties.hibernate.format_sql=true"
})
class ReservaPersistenceIT {

    @Autowired
    private TestEntityManager em;

    private Reserva novaReservaPersistida() {
        Espaco espaco = em.persist(new Espaco("Sala Azul", 30, new BigDecimal("150.00")));
        Cliente cliente = em.persist(new Cliente("Ana", "ana@exemplo.com"));
        Instant inicio = Instant.parse("2026-09-01T13:00:00Z");
        return em.persist(Reserva.nova(espaco, cliente,
                new Periodo(inicio, inicio.plusSeconds(7200))));
    }

    @Test
    void manyToOneNaoEhCarregadoAteAlguemUsar() {
        Long id = novaReservaPersistida().getId();
        em.flush();
        em.clear();

        Reserva reserva = em.find(Reserva.class, id);

        assertThat(Hibernate.isInitialized(reserva.getEspaco()))
                .as("getEspaco() devolve proxy; sem LAZY explicito o EAGER default ja teria feito join")
                .isFalse();
        assertThat(Hibernate.isInitialized(reserva.getCliente())).isFalse();

        assertThat(reserva.getEspaco().getNome()).isEqualTo("Sala Azul");

        assertThat(Hibernate.isInitialized(reserva.getEspaco()))
                .as("ler uma propriedade do proxy e o que dispara o SELECT -- a origem do N+1 em laco")
                .isTrue();
        assertThat(Hibernate.isInitialized(reserva.getCliente()))
                .as("cliente continua nao inicializado: cada associacao carrega por conta propria")
                .isFalse();
    }

    @Test
    void proxyExpoeOIdSemIrAoBanco() {
        Long id = novaReservaPersistida().getId();
        em.flush();
        em.clear();

        Reserva reserva = em.find(Reserva.class, id);

        assertThat(reserva.getEspaco().getId())
                .as("o id vem da FK que ja esta na linha de reserva")
                .isNotNull();
        assertThat(Hibernate.isInitialized(reserva.getEspaco())).isFalse();
    }

    @Test
    void statusEhGravadoComoTextoNaoComoIndice() {
        Reserva reserva = novaReservaPersistida();
        em.flush();

        Object status = em.getEntityManager()
                .createNativeQuery("select status from reserva where id = :id")
                .setParameter("id", reserva.getId())
                .getSingleResult();

        assertThat(status)
                .as("com o ORDINAL default isso seria 0, e inserir uma constante no meio do enum "
                        + "reescreveria o significado de toda linha ja gravada")
                .isEqualTo("CONFIRMADA");

        reserva.cancelar();
        em.flush();

        assertThat(em.find(Reserva.class, reserva.getId()).getStatus())
                .isEqualTo(StatusReserva.CANCELADA);
    }
}
