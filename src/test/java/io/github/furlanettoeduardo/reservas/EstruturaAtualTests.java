package io.github.furlanettoeduardo.reservas;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Linha de base da estrutura de hoje. <b>Estas regras devem quebrar na refatoração
 * hexagonal</b> — é o objetivo delas.
 *
 * <p>Separadas de {@link InvariantesArquiteturaisTests} para que um build vermelho durante a
 * refatoração seja legível: se quebrou aqui, a estrutura mudou como planejado e o teste é que
 * precisa ser atualizado; se quebrou lá, a refatoração violou algo que não devia.
 *
 * <p>É o mesmo desenho de {@code ConcorrenciaReservaIT} e {@code AtualizacaoPerdidaIT}: a
 * asserção afirma o estado atual, e corrigi-la é o que documenta a mudança.
 */
class EstruturaAtualTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.furlanettoeduardo.reservas");

    private static final String RAIZ = "io.github.furlanettoeduardo.reservas";

    /**
     * O ponto central da refatoração hexagonal: hoje as entidades de domínio carregam as
     * anotações de JPA, então o domínio depende de {@code jakarta.persistence}. Depois, o
     * mapeamento vira adaptador e este conjunto fica vazio.
     *
     * <p>A lista é exata, e não um "contém": {@code Periodo} e {@code StatusReserva} já são
     * livres de framework hoje, e é isso que torna a refatoração viável — o value object mostra
     * que o padrão funciona antes de aplicá-lo às entidades.
     */
    @Test
    void hoje_asEntidadesDeDominioCarregamAnotacoesDeJpa() {
        Set<String> comPersistencia = CLASSES.stream()
                .filter(c -> c.getPackageName().equals(RAIZ + ".domain"))
                .filter(EstruturaAtualTests::dependeDeJpa)
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(comPersistencia)
                .as("LINHA DE BASE: quando a refatoracao hexagonal tirar JPA do dominio, este "
                        + "conjunto fica vazio e este teste falha. A falha e o registro da "
                        + "mudanca.")
                .containsExactlyInAnyOrder("Espaco", "Cliente", "Reserva");
    }

    @Test
    void hoje_oDominioTemUmValueObjectLivreDeFramework() {
        Set<String> livres = CLASSES.stream()
                .filter(c -> c.getPackageName().equals(RAIZ + ".domain"))
                .filter(c -> !dependeDeJpa(c))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(livres).containsExactlyInAnyOrder("Periodo", "StatusReserva");
    }

    @Test
    void hoje_oRepositorioSoEhAlcancadoPeloServico() {
        noClasses()
                .that().resideOutsideOfPackages(RAIZ + ".repository", RAIZ + ".service", RAIZ + ".dev")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .as("LINHA DE BASE: hoje repository e um pacote proprio. Na arquitetura "
                        + "hexagonal ele deixa de existir como camada e vira porta mais "
                        + "adaptador, e esta regra perde sentido.")
                .check(CLASSES);
    }

    /**
     * Historico desta assercao, que e o registro da refatoracao acontecendo:
     *
     * <pre>
     * 1A a 1C:  raiz, domain, repository, service, web, dev
     * portas:   + domain.port          &lt;- inversao de dependencia
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
                .as("LINHA DE BASE da estrutura de pastas, para o diff da refatoracao ser "
                        + "comparavel a algo escrito antes dela")
                .containsExactlyInAnyOrder("(raiz)", "domain", "domain.port", "repository",
                        "service", "web", "dev");
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
