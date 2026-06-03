plugins {
    `kotlin-dsl`
}

// The catalog is wired into this included build via settings.gradle.kts, so the convention
// scripts read versions through VersionCatalogsExtension. The build script itself, however,
// needs the Gradle plugin *marker* artifacts on its classpath so the scripts can `id(...)`
// these third-party plugins. Versions are pulled from that same catalog to keep one source
// of truth. PIT is applied per-module via `id("info.solidsoft.pitest")`, so it needs no
// marker here.
val libs = versionCatalogs.named("libs")

dependencies {
    val spotless = libs.findVersion("spotless").get().requiredVersion
    val errorprone = libs.findVersion("errorprone-plugin").get().requiredVersion
    val spotbugs = libs.findVersion("spotbugs-plugin").get().requiredVersion

    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:$spotless")
    implementation("net.ltgt.errorprone:net.ltgt.errorprone.gradle.plugin:$errorprone")
    implementation("com.github.spotbugs:com.github.spotbugs.gradle.plugin:$spotbugs")
}
