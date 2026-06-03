import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("despeckle.java-conventions")
    id("despeckle.test-conventions")
    id("despeckle.quality-conventions")
}

// Throwable -> exit code mapping + the fatal uncaught handler. Depends only on :domain.
dependencies {
    implementation(project(":domain"))
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
