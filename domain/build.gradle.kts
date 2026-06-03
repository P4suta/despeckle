import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("despeckle.java-conventions")
    id("despeckle.test-conventions")
    id("despeckle.quality-conventions")
    alias(libs.plugins.pitest)
}

// The pure core: no project dependencies and no third-party runtime libraries.
// (jspecify is the conventions' compileOnly annotation dependency, not declared here.)

// Mutation testing (warning-only thresholds today; read the kill rate, then tighten).
pitest {
    pitestVersion = libs.versions.pitest.get()
    junit5PluginVersion = libs.versions.pitestJunit5Plugin.get()
    targetClasses = listOf("io.github.p4suta.despeckle.domain.*")
    failWhenNoMutations = false
    timestampedReports = false
    outputFormats = listOf("HTML", "XML")
    mutationThreshold = 0
    coverageThreshold = 0
}

// Domain is the most-tested layer: the strictest coverage floor.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
