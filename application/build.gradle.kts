import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("despeckle.java-conventions")
    id("despeckle.test-conventions")
    id("despeckle.quality-conventions")
}

// Orchestration: depends only on :domain + :port (the adapters are injected at the :app
// composition root). slf4j-api is the only third-party dependency.
//
// No PIT here: the services own thread pools, temp dirs and directory walks, so they are not the
// pure-logic target mutation testing is meaningful for (PIT lives on :domain). The services are
// unit-tested in isolation with hand-written fake ports; their I/O and native error paths are
// additionally covered end-to-end by :app's pipeline tests (see `just coverage`).
dependencies {
    implementation(project(":domain"))
    implementation(project(":port"))
    implementation(libs.slf4j.api)
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.65".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
