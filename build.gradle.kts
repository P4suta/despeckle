import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

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
    implementation(libs.picocli)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    errorprone(libs.errorprone.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    // OpenRewrite recipe modules. Only the rewriteRun/rewriteDryRun tasks pull
    // these in; they are not part of the `build` graph, so a recipe never blocks
    // a commit.
    rewrite(platform(libs.rewrite.recipe.bom))
    rewrite(libs.rewrite.static.analysis)
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

// OpenRewrite: a curated static-analysis pass run on demand with
// `./gradlew rewriteRun` (preview with `rewriteDryRun`). Deliberately not wired
// into `build`, so it never blocks a commit.
rewrite {
    activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")
}
