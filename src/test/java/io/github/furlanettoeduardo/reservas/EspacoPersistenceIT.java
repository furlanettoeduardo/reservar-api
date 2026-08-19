package io.github.furlanettoeduardo.reservas;

import io.github.furlanettoeduardo.reservas.domain.Espaco;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova que {@code @Generated(event = INSERT)} funciona: o banco preenche criado_em via
 * DEFAULT now() e o Hibernate le o valor de volta para o objeto em memoria.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.show-sql=true",
        "spring.jpa.properties.hibernate.format_sql=true"
})
class EspacoPersistenceIT {

    @Autowired
    private TestEntityManager em;

    @Test
    void criadoEmEhPreenchidoPeloBancoESincronizadoNoObjeto() {
        Espaco espaco = new Espaco("Sala Azul", 30, new BigDecimal("150.00"));

        assertThat(espaco.getCriadoEm()).isNull();

        Instant antes = Instant.now();
        em.persistAndFlush(espaco);

        assertThat(espaco.getId()).isNotNull();
        assertThat(espaco.getCriadoEm())
                .as("o valor gerado pelo banco tem que voltar para o objeto sem refresh()")
                .isNotNull()
                .isAfterOrEqualTo(antes.minusSeconds(5));
    }

    @Test
    void precoHoraSobreviveAoRoundTripComEscala() {
        Espaco espaco = em.persistFlushFind(new Espaco("Sala Verde", 10, new BigDecimal("99.90")));

        assertThat(espaco.getPrecoHora())
                .as("NUMERIC(19,4) volta como 99.9000 -- isEqualTo falharia, compareTo nao")
                .isEqualByComparingTo("99.90");
    }
}
