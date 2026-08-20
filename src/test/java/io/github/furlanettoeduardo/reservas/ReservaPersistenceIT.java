package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Cliente;
import io.github.furlanettoeduardo.reservas.domain.Espaco;
import io.github.furlanettoeduardo.reservas.domain.Periodo;
import io.github.furlanettoeduardo.reservas.domain.Reserva;
import io.github.furlanettoeduardo.reservas.domain.StatusReserva;
import io.github.furlanettoeduardo.reservas.repository.jpa.ClienteJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.EspacoJpa;
import io.github.furlanettoeduardo.reservas.repository.jpa.ReservaJpa;
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

    private ReservaJpa novaReservaPersistida() {
        EspacoJpa espaco = em.persist(
                EspacoJpa.de(new Espaco("Sala Azul", 30, new BigDecimal("150.00"))));
        ClienteJpa cliente = em.persist(ClienteJpa.de(new Cliente("Ana", "ana@exemplo.com")));
        Instant inicio = Instant.parse("2026-09-01T13:00:00Z");
        return em.persist(ReservaJpa.de(
                Reserva.nova(espaco.paraDominio(), cliente.paraDominio(),
                        new Periodo(inicio, inicio.plusSeconds(7200))),
                espaco, cliente));
    }

    @Test
    void manyToOneNaoEhCarregadoAteAlguemUsar() {
        Long id = novaReservaPersistida().getId();
        em.flush();
        em.clear();

        ReservaJpa reserva = em.find(ReservaJpa.class, id);

        assertThat(Hibernate.isInitialized(reserva.getEspaco()))
                .as("getEspaco() devolve proxy; sem LAZY explicito o EAGER default ja teria feito join")
                .isFalse();
        assertThat(Hibernate.isInitialized(reserva.getCliente())).isFalse();

        assertThat(reserva.getEspaco().paraDominio().getNome()).isEqualTo("Sala Azul");

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

        ReservaJpa reserva = em.find(ReservaJpa.class, id);

        assertThat(reserva.getEspaco().getId())
                .as("o id vem da FK que ja esta na linha de reserva")
                .isNotNull();
        assertThat(Hibernate.isInitialized(reserva.getEspaco())).isFalse();
    }

    @Test
    void statusEhGravadoComoTextoNaoComoIndice() {
        ReservaJpa reserva = novaReservaPersistida();
        em.flush();

        Object status = em.getEntityManager()
                .createNativeQuery("select status from reserva where id = :id")
                .setParameter("id", reserva.getId())
                .getSingleResult();

        assertThat(status)
                .as("com o ORDINAL default isso seria 0, e inserir uma constante no meio do enum "
                        + "reescreveria o significado de toda linha ja gravada")
                .isEqualTo("CONFIRMADA");

        reserva.aplicar(reserva.paraDominio().cancelar());
        em.flush();

        assertThat(em.find(ReservaJpa.class, reserva.getId()).paraDominio().getStatus())
                .isEqualTo(StatusReserva.CANCELADA);
    }
}
