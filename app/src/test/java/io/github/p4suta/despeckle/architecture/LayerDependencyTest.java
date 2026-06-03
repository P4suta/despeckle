package io.github.p4suta.despeckle.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Boundary rules that the Gradle module graph cannot already enforce.
 *
 * <p>Since the split into {@code :domain}, {@code :port}, {@code :application}, {@code
 * :infrastructure}, {@code :observability}, and {@code :app}, most of the original onion rules are
 * guaranteed at compile time by the absence of a {@code project()} dependency, so they are dropped
 * here: {@code layeredArchitectureIsRespected}, {@code reportDoesNotDependOnCliOrRunner}, {@code
 * runnerDoesNotDependOnCli}, {@code cliDoesNotDependOnReport}, {@code pdfBoxConfinedToPipeline},
 * and {@code filesystemAccessConfined} are simply not on the offending module's classpath — PDFBox
 * lives only in {@code :infrastructure}, {@code :application} cannot see the CLI or the report
 * adapter, and so on. Mirroring tate-yoko-pdf's {@code LayerDependencyTest}, what remains are the
 * intra-graph, class-level conventions a missing dependency does not catch: confining the Foreign
 * Function &amp; Memory API and method handles to the Leptonica island, keeping standard-stream
 * access in the CLI front ends, the cross-cutting coding rules, JSpecify-only nullness, and freedom
 * from package cycles.
 *
 * <p>Analyzed from {@code :app}, whose test classpath sees every module. The {@code testFixtures}
 * sourceSet of {@code :infrastructure} is excluded ({@link NoTestFixtures}) so fixtures may use
 * PDFBox directly to build PDFs without tripping these rules. The class graph is imported once
 * (production classes only) and shared across every {@link ArchTest} rule below.
 */
@AnalyzeClasses(
        packages = "io.github.p4suta.despeckle",
        importOptions = {
            ImportOption.DoNotIncludeTests.class,
            LayerDependencyTest.NoTestFixtures.class
        })
final class LayerDependencyTest {

    /**
     * Excludes the {@code testFixtures} sourceSet — fixtures may use PDFBox directly to build PDFs.
     */
    public static final class NoTestFixtures implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return !location.contains("testFixtures") && !location.contains("test-fixtures");
        }
    }

    /** Any nullness annotation ({@code @Nullable}/{@code @NonNull}/...) not from JSpecify. */
    private static final DescribedPredicate<JavaClass> NULLNESS_ANNOTATION_NOT_FROM_JSPECIFY =
            simpleName("Nullable")
                    .or(simpleName("NonNull"))
                    .or(simpleName("Nonnull"))
                    .or(simpleName("NotNull"))
                    .and(not(resideInAPackage("org.jspecify.annotations")))
                    .as("a nullness annotation not from JSpecify");

    /** No package may sit in a dependency cycle with another. */
    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching("io.github.p4suta.despeckle.(*)..").should().beFreeOfCycles();

    /**
     * The Foreign Function &amp; Memory API is the one piece of native, "restricted" surface; it
     * lives behind exactly two classes ({@code Pix}, the RAII handle, and {@code Leptonica}, the
     * binding island) so the rest of the code is plain, safe Java.
     */
    @ArchTest
    static final ArchRule foreignMemoryApiConfinedToPixAndLeptonica =
            noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName(
                            "io.github.p4suta.despeckle.infrastructure.leptonica.Pix")
                    .and()
                    .doNotHaveFullyQualifiedName(
                            "io.github.p4suta.despeckle.infrastructure.leptonica.Leptonica")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("java.lang.foreign..");

    /** Raw downcall handles ({@code java.lang.invoke}) stay inside the binding island alone. */
    @ArchTest
    static final ArchRule methodHandlesConfinedToLeptonica =
            noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName(
                            "io.github.p4suta.despeckle.infrastructure.leptonica.Leptonica")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("java.lang.invoke..");

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
                    .and()
                    .doNotHaveFullyQualifiedName("io.github.p4suta.despeckle.cli.TopdfCli")
                    .should(ACCESS_STANDARD_STREAMS)
                    .as(
                            "only the CLI front ends (DespeckleCli, PipelineCli, TopdfCli) may"
                                    + " access standard streams")
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
