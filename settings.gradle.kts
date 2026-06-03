pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "despeckle"

include(":domain", ":port", ":application", ":infrastructure", ":observability", ":app")
