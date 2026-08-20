package io.github.furlanettoeduardo.reservas;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regras que valem <b>antes e depois</b> da refatoração hexagonal.
 *
 * <p>A separação em relação a {@link EstruturaAtualTests} é deliberada e importa: aquelas
 * descrevem a estrutura de hoje e <b>devem</b> quebrar na refatoração; estas não devem. Sem a
 * separação, um build vermelho durante a refatoração tem duas leituras — "violei um invariante"
 * ou "a regra descrevia a estrutura antiga" — e a tentação é reescrever a regra para acomodar o
 * que foi feito. Aí o ArchUnit vira decoração.
 *
 * <p>O dente destas regras foi testado como o das bordas de intervalo: adicionando um
 * {@code import org.springframework...} numa classe de domínio e confirmando que o build quebra.
 * {@code resideInAPackage("..domain..")} passa em silêncio se o padrão estiver errado.
 */
class InvariantesArquiteturaisTests {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.furlanettoeduardo.reservas");

    @Test
    void dominioNaoDependeDeSpring() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .as("o dominio nao conhece o container de injecao")
                .check(CLASSES);
    }

    @Test
    void dominioNaoDependeDaCamadaWebNemDeSerializacao() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..web..", "jakarta.servlet..", "com.fasterxml.jackson..")
                .as("o modelo interno nao pode virar contrato de API por acidente")
                .check(CLASSES);
    }

    @Test
    void dominioNaoDependeDeServicoNemDeRepositorio() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..repository..")
                .as("a dependencia aponta para dentro: as camadas de fora conhecem o dominio, "
                        + "nao o contrario")
                .check(CLASSES);
    }

    @Test
    void somenteAWebConheceOProtocoloHttp() {
        noClasses()
                .that().resideOutsideOfPackage("..web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..", "org.springframework.http..",
                        "org.springframework.web..")
                .as("HTTP e detalhe de entrega. Vazar para servico ou dominio e o que impede "
                        + "trocar o transporte sem reescrever a regra.")
                .check(CLASSES);
    }

    @Test
    void aWebNaoAlcancaORepositorioDireto() {
        noClasses()
                .that().resideInAPackage("..web..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .as("controller tem que passar pelo servico -- e o servico que define a unidade "
                        + "de trabalho transacional")
                .check(CLASSES);
    }

    @Test
    void ninguemUsaAsApisDeDataLegadas() {
        noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("java.util.Date")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.Calendar")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.sql.Timestamp")
                .as("TIMESTAMPTZ mapeia para Instant. Date e Calendar carregam fuso implicito, "
                        + "que e a origem da classe de bug que a tela expos.")
                .check(CLASSES);
    }
}
