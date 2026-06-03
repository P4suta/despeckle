import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    application
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.rewrite)
}

group = "io.github.p4suta"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    // FFM is final since JDK 22; 25 is the current LTS. If Error Prone ever
    // lags a JDK, the floor that still builds is 22 (FFM is preview on 21).
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.commons.cli)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    // PDFBox builds the cleaned lossless-JBIG2 output PDF in `despeckle pipeline`.
    implementation(libs.pdfbox)
    implementation(libs.xmpbox)

    // JSpecify @Nullable: the vocabulary NullAway reads to learn what may be null.
    implementation(libs.jspecify)

    errorprone(libs.errorprone.core)
    // NullAway runs as an Error Prone plugin (same `errorprone` configuration).
    errorprone(libs.nullaway)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    // OpenRewrite recipe modules. Only the rewriteRun/rewriteDryRun tasks pull
    // these in; they are not part of the `build` graph, so a recipe never blocks
    // a commit.
    rewrite(platform(libs.rewrite.recipe.bom))
    rewrite(libs.rewrite.static.analysis)
    rewrite(libs.rewrite.testing.frameworks)
    rewrite(libs.rewrite.logging.frameworks)
    rewrite(libs.rewrite.migrate.java)
    // Not in the recipe BOM, so version-pinned in the catalog; the BOM platform
    // above still aligns its transitive rewrite-core.
    rewrite(libs.rewrite.java.security)
}

// The one place native access is granted; reused by run, test and any JavaExec.
val nativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")

application {
    mainClass = "io.github.p4suta.despeckle.Main"
    applicationDefaultJvmArgs = nativeAccessArgs
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

tasks.test {
    useJUnitPlatform()
    jvmArgs(nativeAccessArgs)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(nativeAccessArgs)
}

spotless {
    java {
        target("src/**/*.java")
        // AOSP style = 4-space indent, 100-column, matching .editorconfig.
        googleJavaFormat(
            libs.versions.google.java.format
                .get(),
        ).aosp().reflowLongStrings()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

spotbugs {
    effort = Effort.MAX
    reportLevel = Confidence.MEDIUM
    excludeFilter = file("config/spotbugs/exclude.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
}

// OpenRewrite: a curated pass defined declaratively in rewrite.yml (auto-discovered
// at the project root), run on demand with `./gradlew rewriteRun` / `just rewrite`
// (preview with `rewriteDryRun` / `just rewrite-check`). Deliberately not wired into
// `build`, so it never blocks a commit. After rewriteRun, `just rewrite` runs
// spotlessApply so google-java-format re-imposes the AOSP layout.
rewrite {
    activeRecipe("io.github.p4suta.despeckle.CuratedCleanup")
    // OpenRewrite's Kotlin support reformats the Gradle scripts in ways that fight
    // Spotless/ktlint (which own them), so keep rewrite off the build files. Both
    // globs are needed: the NIO glob matcher requires a separator, so "**/*.gradle.kts"
    // alone misses the repo-root build.gradle.kts.
    exclusion("**/*.gradle.kts", "*.gradle.kts")
}
