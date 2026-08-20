package io.github.furlanettoeduardo.reservas;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regras que valem <b>antes e depois</b> da refatoração hexagonal.
 *
 * <p>A separação em relação a {@link EstruturaAtualTests} é deliberada e se pagou: na refatoração
 * os seis invariantes originais passaram sem toque e os quatro testes de linha de base falharam.
 * O build vermelho foi legível sem precisar de investigação — se tivesse quebrado aqui, a
 * refatoração teria violado algo que não devia.
 *
 * <p>O dente destas regras foi testado: adicionando um {@code import org.springframework...} numa
 * classe de domínio e confirmando que o build quebra. {@code resideInAPackage("..domain..")}
 * passa em silêncio se o padrão estiver errado, então regra de arquitetura que nunca falhou não é
 * regra.
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

    /**
     * <b>Este era o alvo da refatoração, e virou invariante.</b>
     *
     * <p>Antes ele morava em {@link EstruturaAtualTests} como linha de base, afirmando que
     * {@code Espaco}, {@code Cliente} e {@code Reserva} dependiam de {@code jakarta.persistence}.
     * A refatoração fez aquele teste falhar; corrigi-lo foi movê-lo para cá com o sinal
     * invertido. A migração de arquivo é o registro de que o trabalho terminou.
     */
    @Test
    void dominioNaoDependeDeJpaNemDeHibernate() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate..")
                .as("o dominio nao conhece o mapeamento. Mutabilidade, construtor sem-args e "
                        + "igualdade por id deixaram de ser requisitos dele.")
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
    void dominioNaoDependeDeServicoNemDoAdaptadorDePersistencia() {
        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..repository..")
                .as("a dependencia aponta para dentro. As portas em domain.port sao a excecao "
                        + "aparente que confirma a regra: elas ficam DO LADO do dominio e sao "
                        + "implementadas de fora.")
                .check(CLASSES);
    }

    /**
     * As entidades JPA não escapam do adaptador. É o invariante que a refatoração criou: se uma
     * {@code EspacoJpa} aparecer numa assinatura de serviço ou de controller, a separação virou
     * teatro — o acoplamento volta com um nome diferente.
     */
    @Test
    void asEntidadesJpaNaoSaemDoAdaptador() {
        noClasses()
                .that().resideOutsideOfPackage("..repository..")
                .should().dependOnClassesThat().resideInAPackage("..repository.jpa..")
                .as("se a entidade JPA vaza para o servico, a separacao nao esta pagando nada")
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
    void aWebNaoAlcancaOAdaptadorDePersistencia() {
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
