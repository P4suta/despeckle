import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

// Shared Java compilation conventions for every production module: Java 25 toolchain, the
// Error Prone + NullAway zero-warning null-safety gate, and the Javadoc doclint gate.
// Module-specific dependencies and coverage rules live in each module's own build script.
plugins {
    java
    id("net.ltgt.errorprone")
}

group = "io.github.p4suta"
version = "0.1.0"

// Precompiled script plugins get no type-safe `libs.` accessors; read the catalog directly.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    // FFM is final since JDK 22; 25 is the current LTS. If Error Prone ever
    // lags a JDK, the floor that still builds is 22 (FFM is preview on 21).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // JSpecify @Nullable: the vocabulary NullAway reads to learn what may be null.
    "compileOnly"(libs.findLibrary("jspecify").get())

    "errorprone"(libs.findLibrary("errorprone-core").get())
    // NullAway runs as an Error Prone plugin (same `errorprone` configuration).
    "errorprone"(libs.findLibrary("nullaway").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Pin the documented JDK 25 API surface.
    options.release = 25
    // Warnings are errors. We exclude only the "options" category: the
    // "system modules path not set" note is an environmental artifact of
    // toolchain compilation, not a code-quality signal. Every code warning
    // (deprecation, unchecked, removal, ...) still fails the build.
    options.compilerArgs.addAll(listOf("-Xlint:all,-options", "-Werror"))
    // NullAway: a missing null check inside our own package is a build error.
    // Maximally strict — full JSpecify semantics (generics, type-use positions;
    // the source is @NullMarked per package-info), restrictive third-party
    // annotations honored, Optional/OptionalInt emptiness flow-checked, and every
    // override re-checked against its supertype. CheckContracts/AssertsEnabled are
    // pre-enabled (the code has no @Contract or assert yet, so they verify nothing
    // today) so future contracts/asserts are honored without a config change.
    options.errorprone {
        disableWarningsInGeneratedCode = true
        check("NullAway", CheckSeverity.ERROR)
        // AnnotatedPackages is the required baseline (NullAway demands exactly one
        // of AnnotatedPackages or OnlyNullMarked) and already marks every current
        // and future sub-package. The @NullMarked package-info files are kept as
        // in-source / IDE documentation and to stay honest if a package is ever
        // moved out from under this prefix.
        option("NullAway:AnnotatedPackages", "io.github.p4suta.despeckle")
        option("NullAway:JSpecifyMode", "true")
        option("NullAway:AcknowledgeRestrictiveAnnotations", "true")
        option("NullAway:CheckOptionalEmptiness", "true")
        // The codebase models nullable numerics as OptionalInt, which the emptiness
        // check ignores by default; name the primitive optionals so getAsInt() is
        // flow-checked (e.g. guarded by isPresent()) like java.util.Optional.get().
        option(
            "NullAway:CheckOptionalEmptinessCustomClasses",
            "java.util.OptionalInt,java.util.OptionalLong,java.util.OptionalDouble",
        )
        option("NullAway:CheckContracts", "true")
        option("NullAway:ExhaustiveOverride", "true")
        option("NullAway:AssertsEnabled", "true")
    }
}

// NullAway guards the tests too: they are @NullMarked, and the strict options
// above (JSpecifyMode, restrictive annotations, ...) are inherited from the
// shared JavaCompile block. HandleTestAssertionLibraries lets JUnit/Hamcrest/
// AssertJ assertions establish non-null facts when a test relies on them.
tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:HandleTestAssertionLibraries", "true")
    }
}

// The one place native access is granted; reused by run, test and any JavaExec.
// Published via `extra` so :app's distribution and the test conventions reuse the
// exact same flag list.
val nativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")
extra["nativeAccessArgs"] = nativeAccessArgs

tasks.withType<JavaExec>().configureEach {
    jvmArgs(nativeAccessArgs)
}

// Javadoc doclint: validate cross-references, HTML, and syntax of the Javadoc we ship.
// `-missing` keeps the gate on correctness of written docs rather than exhaustive coverage.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        addStringOption("Xdoclint:all,-missing", "-quiet")
        addBooleanOption("Werror", true)
    }
}

tasks.named("check") { dependsOn(tasks.named("javadoc")) }
