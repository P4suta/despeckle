import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.testing.jacoco.tasks.JacocoReport

// Shared test stack and execution settings: JUnit 5 + ArchUnit, the native-access JVM arg the
// core tests need to reach Leptonica via FFM, and JaCoCo report wiring. Per-module coverage
// thresholds and class excludes live in each module's own build script.
plugins {
    java
    jacoco
}

// Precompiled script plugins get no type-safe `libs.` accessors; read the catalog directly.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("archunit-junit5").get())
    "testCompileOnly"(libs.findLibrary("jspecify").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "archunit")
    }
    // Core tests reach Leptonica through the Foreign Function & Memory API.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    finalizedBy(tasks.named("jacocoTestReport"))
    testLogging {
        events("failed")
        showStackTraces = true
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}
