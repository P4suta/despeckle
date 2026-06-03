package io.github.p4suta.despeckle;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Pins the architectural boundaries the README promises, so a future edit that quietly violates
 * them fails the build instead of the design eroding.
 *
 * <p>The class graph is imported once (production classes only) and shared across every {@link
 * ArchTest} rule below.
 */
@AnalyzeClasses(
        packages = "io.github.p4suta.despeckle",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Any nullness annotation ({@code @Nullable}/{@code @NonNull}/...) not from JSpecify. */
    private static final DescribedPredicate<JavaClass> NULLNESS_ANNOTATION_NOT_FROM_JSPECIFY =
            simpleName("Nullable")
                    .or(simpleName("NonNull"))
                    .or(simpleName("Nonnull"))
                    .or(simpleName("NotNull"))
                    .and(not(resideInAPackage("org.jspecify.annotations")))
                    .as("a nullness annotation not from JSpecify");

    /**
     * The whole module as a strict onion: {@code Main} -> {@code cli} -> {@code runner} -> {@code
     * report}, every layer free to reach the reusable {@code core}. Nothing may reach back up, so a
     * future GUI can depend on {@code core} (and only {@code core}) unchanged.
     */
    @ArchTest
    static final ArchRule layeredArchitectureIsRespected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Main")
                    .definedBy("io.github.p4suta.despeckle")
                    .layer("Cli")
                    .definedBy("io.github.p4suta.despeckle.cli..")
                    .layer("Pipeline")
                    .definedBy("io.github.p4suta.despeckle.pipeline..")
                    .layer("Runner")
                    .definedBy("io.github.p4suta.despeckle.runner..")
                    .layer("Report")
                    .definedBy("io.github.p4suta.despeckle.report..")
                    .layer("Core")
                    .definedBy("io.github.p4suta.despeckle.core..")
                    .whereLayer("Main")
                    .mayNotBeAccessedByAnyLayer()
                    .whereLayer("Cli")
                    .mayOnlyBeAccessedByLayers("Main")
                    .whereLayer("Pipeline")
                    .mayOnlyBeAccessedByLayers("Cli")
                    .whereLayer("Runner")
                    .mayOnlyBeAccessedByLayers("Cli", "Pipeline")
                    .whereLayer("Report")
                    .mayOnlyBeAccessedByLayers("Runner", "Pipeline")
                    .whereLayer("Core")
                    .mayOnlyBeAccessedByLayers("Cli", "Pipeline", "Runner", "Report");

    /** No package may sit in a dependency cycle with another. */
    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching("io.github.p4suta.despeckle.(*)..").should().beFreeOfCycles();

    /**
     * The report writer is a leaf consumer of {@code core}; it must not reach sideways or up. This
     * and the next two directional rules overlap with the layered rule above, but are kept for the
     * sharper, single-edge failure message they give when a specific boundary is crossed.
     */
    @ArchTest
    static final ArchRule reportDoesNotDependOnCliOrRunner =
            noClasses()
                    .that()
                    .resideInAPackage("..report..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..cli..", "..runner..");

    /** The directory/thread driver takes a {@code Config}; it must not know about the CLI. */
    @ArchTest
    static final ArchRule runnerDoesNotDependOnCli =
            noClasses()
                    .that()
                    .resideInAPackage("..runner..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..cli..");

    /** The CLI builds a {@code Config} for the runner; it must not reach into the report writer. */
    @ArchTest
    static final ArchRule cliDoesNotDependOnReport =
            noClasses()
                    .that()
                    .resideInAPackage("..cli..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..report..");

    /**
     * The Foreign Function &amp; Memory API is the one piece of native, "restricted" surface; it
     * lives behind exactly two classes ({@code Pix}, the RAII handle, and {@code Leptonica}, the
     * binding island) so the rest of the code is plain, safe Java.
     */
    @ArchTest
    static final ArchRule foreignMemoryApiConfinedToPixAndLeptonica =
            noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName("io.github.p4suta.despeckle.core.Pix")
                    .and()
                    .doNotHaveFullyQualifiedName("io.github.p4suta.despeckle.core.Leptonica")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("java.lang.foreign..");

    /** Raw downcall handles ({@code java.lang.invoke}) stay inside the binding island alone. */
    @ArchTest
    static final ArchRule methodHandlesConfinedToLeptonica =
            noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName("io.github.p4suta.despeckle.core.Leptonica")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("java.lang.invoke..");

    /**
     * {@code java.nio.file.Files} (the filesystem read/write helper) belongs to the layers that own
     * I/O ({@code runner} walks the tree, {@code report} writes panels). {@code Leptonica} is the
     * one exception: it probes for the native library path at class-load time, which is not
     * pipeline I/O. This pins {@code Files} specifically, not every filesystem API.
     */
    @ArchTest
    static final ArchRule filesystemAccessConfined =
            noClasses()
                    .that()
                    .resideOutsideOfPackages("..runner..", "..report..", "..pipeline..")
                    .and()
                    .doNotHaveFullyQualifiedName("io.github.p4suta.despeckle.core.Leptonica")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.nio.file.Files");

    /**
     * Apache PDFBox — the one PDF-writing library — is confined to the {@code pipeline} package
     * (the JBIG2 assembler), so the rest of the tool stays a pure image pipeline that never touches
     * a PDF type. The same isolation discipline as the FFM/Leptonica island.
     */
    @ArchTest
    static final ArchRule pdfBoxConfinedToPipeline =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("io.github.p4suta.despeckle.pipeline..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.apache.pdfbox..", "org.apache.xmpbox..");

    /** Logging goes through SLF4J, never {@code java.util.logging}. */
    @ArchTest static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    /** No JodaTime — the JDK time APIs are the standard. */
    @ArchTest static final ArchRule noJodaTime = NO_CLASSES_SHOULD_USE_JODATIME;

    /**
     * Throw a meaningful exception type, never a bare {@code Exception}/{@code RuntimeException}.
     */
    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    /**
     * Only the CLI front end ({@code DespeckleCli}) may touch {@code System.out}/{@code
     * System.err}: it prints help, version and usage straight to the process streams like any
     * normal CLI, while every other layer routes user-facing output and progress through SLF4J.
     * (picocli used to hide this write inside its own library; Commons CLI hands it back to us, so
     * the carve-out is named.)
     */
    @ArchTest
    static final ArchRule noStandardStreams =
            noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName("io.github.p4suta.despeckle.cli.DespeckleCli")
                    .and()
                    .doNotHaveFullyQualifiedName("io.github.p4suta.despeckle.cli.PipelineCli")
                    .should(ACCESS_STANDARD_STREAMS)
                    .as(
                            "only the CLI front ends (DespeckleCli, PipelineCli) may access"
                                    + " standard streams")
                    .because(
                            "help/version/usage go straight to the process streams like a normal"
                                    + " CLI; every other layer logs through SLF4J");

    /**
     * Nullability is spoken in exactly one vocabulary — JSpecify — because that is what NullAway
     * reads. A nullness annotation from any other library would silently fall outside the
     * null-safety gate, so this allow-lists JSpecify and rejects every other source, including ones
     * not yet on the classpath (an allow-list, not a denylist of known offenders).
     */
    @ArchTest
    static final ArchRule nullnessAnnotationsComeFromJSpecify =
            noClasses().should().dependOnClassesThat(NULLNESS_ANNOTATION_NOT_FROM_JSPECIFY);
}
