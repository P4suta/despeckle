package io.github.p4suta.despeckle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Pins the architectural boundaries the README promises, so a future edit that quietly violates
 * them fails the build instead of the design eroding.
 */
class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("io.github.p4suta.despeckle");

    /**
     * {@code core} is the reusable image-science layer: it must not reach up into the CLI, the
     * directory/thread driver, or the report writer, so a future GUI can depend on {@code core}
     * alone.
     */
    @Test
    void coreDoesNotDependOnOuterLayers() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..core..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("..cli..", "..runner..", "..report..");
        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * The Foreign Function &amp; Memory API is the one piece of native, "restricted" surface; it
     * stays inside {@code core} (behind {@code Pix} / {@code Leptonica}) so the rest of the code is
     * plain, safe Java.
     */
    @Test
    void foreignMemoryApiIsConfinedToCore() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideOutsideOfPackage("..core..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("java.lang.foreign..");
        rule.check(PRODUCTION_CLASSES);
    }
}
