import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

// Shared formatting and bytecode-analysis conventions: Spotless (google-java-format, AOSP style)
// and SpotBugs at MAX effort / MEDIUM confidence, sharing the one exclude filter at the
// repository root.
plugins {
    java
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

// Precompiled script plugins get no type-safe `libs.` accessors; read the catalog directly.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

spotless {
    java {
        target("src/**/*.java")
        targetExclude("build/**", "**/generated/**")
        // AOSP style = 4-space indent, 100-column, matching .editorconfig.
        googleJavaFormat(libs.findVersion("google-java-format").get().requiredVersion)
            .aosp()
            .reflowLongStrings()
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
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
}

// Limit SpotBugs to production code: test / fixture code uses assertion patterns that generate
// noisy false positives. The fixtures task only exists where java-test-fixtures applies, so
// match by name rather than named(...) to stay no-op where absent.
tasks.matching { it.name == "spotbugsTest" || it.name == "spotbugsTestFixtures" }
    .configureEach { enabled = false }

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
}
