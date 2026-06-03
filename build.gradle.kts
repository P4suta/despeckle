plugins {
    base
    alias(libs.plugins.rewrite)
    // Whole-build coverage: merges every module's JaCoCo data into one cross-module report
    // (`./gradlew testCodeCoverageReport` / `just coverage`). Unlike the per-module floors, this
    // view credits a class for coverage from ANY module's tests — so the adapters exercised only by
    // :app's end-to-end pipeline tests show as covered here, even though :infrastructure's own
    // isolated report cannot see them.
    `jacoco-report-aggregation`
    // Load Spotless/Error Prone/SpotBugs once at the root scope (apply false) so the convention
    // plugins that apply them across the sibling modules all share a single plugin classloader.
    // Without this, Spotless's shared SpotlessTaskService is loaded per-project and Gradle fails
    // with "Cannot set the value of task ':<m>:spotlessJava' property 'taskService'".
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.errorprone) apply false
    alias(libs.plugins.spotbugs) apply false
}

group = "io.github.p4suta"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
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

    // Every production module feeds the aggregated coverage report.
    jacocoAggregation(project(":domain"))
    jacocoAggregation(project(":port"))
    jacocoAggregation(project(":application"))
    jacocoAggregation(project(":infrastructure"))
    jacocoAggregation(project(":observability"))
    jacocoAggregation(project(":app"))
}

// Wire the `testCodeCoverageReport` task to the `test` suite of every aggregated module; it
// produces build/reports/jacoco/testCodeCoverageReport/ (HTML to browse + XML for tooling such
// as scripts/CoverageSummary.java).
reporting {
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName = "test"
        }
    }
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
