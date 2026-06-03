import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("despeckle.java-conventions")
    id("despeckle.test-conventions")
    id("despeckle.quality-conventions")
    `java-test-fixtures`
}

// Adapters: FFM/Leptonica, PDFBox, AWT, and the jbig2/qpdf/cwebp exec wrappers all live here.
dependencies {
    implementation(project(":domain"))
    implementation(project(":port"))
    implementation(libs.pdfbox)
    // xmpbox ships with PDFBox and shares its version — it carries the source XMP packet.
    implementation(libs.xmpbox)
    implementation(libs.slf4j.api)

    // TestImages + the PDFBox-backed PDF builder are shared with :app's cross-module tests.
    testFixturesImplementation(libs.pdfbox)
    testFixturesImplementation(libs.jspecify)
}

// Adapters that shell out to external binaries (jbig2/qpdf/cwebp/img2webp/pdfimages) or cross the
// FFM/process boundary: their defensive branches (native tool resolution, ProcessBuilder timeouts,
// FFM downcalls) cannot be unit-tested without unnatural scaffolding. They ARE exercised end-to-end
// by :app's pipeline tests — see the true numbers via `just coverage` (the aggregated report). They
// are excluded only from THIS module's isolated floor, so it tracks the genuinely unit-testable
// adapter logic (Leptonica page cleaner, the report renderer + charts).
val coverageExcludes =
    listOf(
        "**/Leptonica.class",
        "**/NativeTools.class",
        "**/QpdfLinearizer.class",
        "**/Webp.class",
        "**/Flipbook.class",
        "**/PdfBoxJbig2Assembler*.class",
        "**/PdfImagesCliExtractor.class",
    )

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExcludes) } }),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.60".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }
