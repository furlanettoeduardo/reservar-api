package io.github.furlanettoeduardo.reservas;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Linha de base da estrutura atual. <b>Estas asserções descrevem onde as coisas estão, e mudam
 * quando a estrutura muda</b> — ao contrário de {@link InvariantesArquiteturaisTests}, que não
 * deve mudar.
 *
 * <p>A separação se pagou na refatoração hexagonal: os seis invariantes passaram sem toque e os
 * quatro testes deste arquivo falharam. O vermelho foi legível de imediato, sem precisar decidir
 * se a refatoração estava errada ou se o teste estava velho.
 *
 * <p>Uma das asserções deste arquivo <b>saiu</b> dele: a que afirmava que
 * {@code Espaco, Cliente, Reserva} dependiam de {@code jakarta.persistence} virou o invariante
 * {@code dominioNaoDependeDeJpaNemDeHibernate}. A migração de arquivo é o registro de que o
 * trabalho terminou.
 */
class EstruturaAtualTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.furlanettoeduardo.reservas");

    private static final String RAIZ = "io.github.furlanettoeduardo.reservas";

    /**
     * O espelho da asserção que virou invariante: as três classes que carregavam JPA no domínio
     * têm agora contrapartes no adaptador, e são elas que carregam o mapeamento.
     *
     * <p>Lista exata de propósito. Se aparecer uma quarta entidade JPA sem uma decisão consciente
     * — por exemplo alguém anotando um DTO — este teste avisa.
     */
    @Test
    void hoje_oMapeamentoJpaVivePorInteiroNoAdaptador() {
        Set<String> comPersistencia = CLASSES.stream()
                .filter(EstruturaAtualTests::dependeDeJpa)
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());

        assertThat(comPersistencia)
                .as("um pacote so conhece jakarta.persistence")
                .containsExactly(RAIZ + ".repository.jpa");

        Set<String> entidades = CLASSES.stream()
                .filter(c -> c.getPackageName().equals(RAIZ + ".repository.jpa"))
                .filter(EstruturaAtualTests::dependeDeJpa)
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(entidades)
                .as("as tres entidades JPA, e nada mais")
                .containsExactlyInAnyOrder("EspacoJpa", "ClienteJpa", "ReservaJpa");
    }

    @Test
    void hoje_oDominioTemCincoTiposEUmPacoteDePortas() {
        Set<String> dominio = CLASSES.stream()
                .filter(c -> c.getPackageName().equals(RAIZ + ".domain"))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(dominio)
                .containsExactlyInAnyOrder("Espaco", "Cliente", "Reserva", "Periodo",
                        "StatusReserva");

        Set<String> portas = CLASSES.stream()
                .filter(c -> c.getPackageName().equals(RAIZ + ".domain.port"))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(portas)
                .as("uma porta por agregado que precisa de persistencia")
                .containsExactlyInAnyOrder("EspacoRepositorio", "ClienteRepositorio",
                        "ReservaRepositorio");
    }

    /**
     * Histórico desta asserção, que é o registro da refatoração acontecendo:
     *
     * <pre>
     * 1A a 1C:   raiz, domain, repository, service, web, dev
     * portas:    + domain.port         &lt;- inversão de dependência
     * separação: + repository.jpa      &lt;- entidades JPA saíram do domínio
     * </pre>
     */
    @Test
    void hoje_osPacotesConhecidos() {
        Set<String> pacotes = CLASSES.stream()
                .map(JavaClass::getPackageName)
                .filter(p -> p.startsWith(RAIZ))
                .map(p -> p.equals(RAIZ) ? "(raiz)" : p.substring(RAIZ.length() + 1))
                .collect(Collectors.toSet());

        assertThat(pacotes)
                .containsExactlyInAnyOrder("(raiz)", "domain", "domain.port", "repository",
                        "repository.jpa", "service", "web", "dev");
    }

    private static boolean dependeDeJpa(JavaClass classe) {
        return classe.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> {
                    String pacote = d.getTargetClass().getPackageName();
                    return pacote.startsWith("jakarta.persistence")
                            || pacote.startsWith("org.hibernate");
                });
    }
}
