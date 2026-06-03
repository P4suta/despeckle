plugins {
    id("despeckle.java-conventions")
    id("despeckle.test-conventions")
    id("despeckle.quality-conventions")
    application
}

// The composition root: the only module that sees both :application and :infrastructure, so it
// can wire the adapters into the services. Commons CLI + Main live here, plus the ArchUnit
// cross-module rules (their test classpath spans every module).
dependencies {
    implementation(project(":domain"))
    implementation(project(":port"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation(project(":observability"))
    implementation(libs.commons.cli)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.archunit.junit5)
    testImplementation(testFixtures(project(":infrastructure")))
}

// FFM downcalls into Leptonica require native access to be enabled. installDist propagates this
// via applicationDefaultJvmArgs; jpackage via --java-options; tests/JavaExec via the convention.
val nativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")

// The module is :app, but the tool — its launcher, install dir and jar — is "despeckle". Pin both
// names so installDist produces build/install/despeckle/bin/despeckle (the path `just run` execs)
// and the jar is despeckle-<version>.jar (the name jpackage's --main-jar below expects).
application {
    mainClass = "io.github.p4suta.despeckle.Main"
    applicationName = "despeckle"
    applicationDefaultJvmArgs = nativeAccessArgs
}

base {
    archivesName = "despeckle"
}

// ---- Distribution: jlink + jpackage app-image ------------------------------
// Produces build/dist-jpackage/despeckle/ with a launcher and a trimmed JRE (jlink). The
// app-image's --input is installDist's lib/ (no shadow jar). Native binaries (jbig2/pdfimages/
// pdfinfo/qpdf/cwebp/img2webp + libleptonica) are NOT bundled: they are a documented PATH runtime
// dependency (dev container + `just doctor` supply/verify them). Leaving room to bundle qpdf via
// an Ivy repository later, as tate-yoko-pdf does, if a self-contained image is ever required.

val jpackageAppName = "despeckle"

// The minimal module set from import analysis (verify with `jdeps --print-module-deps`):
//   java.base       — FFM / NIO / concurrency / slf4j
//   java.desktop    — AWT + ImageIO (report rendering)
//   java.xml        — PDFBox / xmpbox
//   java.logging    — slf4j-simple over j.u.l consumers
//   jdk.unsupported — PDFBox' sun.misc.Unsafe
//   jdk.zipfs       — PDFBox zip-style stream filters
val bundledModules =
    listOf(
        "java.base",
        "java.desktop",
        "java.xml",
        "java.logging",
        "jdk.unsupported",
        "jdk.zipfs",
    )

// CC-safe tool resolution: capture java.home (or JAVA_HOME) as a Provider at configuration time,
// never read project.* inside the Exec actions.
val javaHomeProvider: Provider<String> =
    providers.systemProperty("java.home").orElse(providers.environmentVariable("JAVA_HOME"))

fun toolPath(tool: String): Provider<String> =
    javaHomeProvider.map { home ->
        val exe =
            if (org.gradle.internal.os.OperatingSystem
                    .current()
                    .isWindows
            ) {
                "$tool.exe"
            } else {
                tool
            }
        "$home/bin/$exe"
    }

// jlink/jpackage refuse to write into a pre-existing directory; Gradle eagerly creates declared
// output dirs. So each tool's *parent* is declared as the output and the tool writes a fixed child
// inside it, with separate Delete tasks to reset state config-cache-safely.
val jreOutputParent = layout.buildDirectory.dir("dist-jre")
val jreImageDir = jreOutputParent.map { it.dir("runtime") }
val jpackageOutputParent = layout.buildDirectory.dir("dist-jpackage")

// installDist stages the launcher + jars under build/install/despeckle; jpackage consumes its lib/.
val installInputDir = layout.buildDirectory.dir("install/$jpackageAppName/lib")

val cleanJreImage =
    tasks.register<Delete>("cleanJreImage") {
        delete(jreImageDir)
    }

val cleanJpackageImage =
    tasks.register<Delete>("cleanJpackageImage") {
        delete(jpackageOutputParent.map { it.dir(jpackageAppName) })
    }

val jreImage =
    tasks.register<Exec>("jreImage") {
        group = "distribution"
        description = "Run jlink to build a trimmed JRE under build/dist-jre/runtime/"
        dependsOn(cleanJreImage)

        commandLine =
            listOf(
                toolPath("jlink").get(),
                "--add-modules",
                bundledModules.joinToString(","),
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress=zip-9",
                "--output",
                jreImageDir.get().asFile.absolutePath,
            )
        inputs.property("modules", bundledModules.joinToString(","))
        outputs.dir(jreOutputParent)
    }

val jpackageImage =
    tasks.register<Exec>("jpackageImage") {
        group = "distribution"
        description = "Run jpackage to build the app-image under build/dist-jpackage/"
        dependsOn(jreImage, tasks.named("installDist"), cleanJpackageImage)

        val mainJarName = "$jpackageAppName-${project.version}.jar"

        commandLine =
            listOf(
                toolPath("jpackage").get(),
                "--type",
                "app-image",
                "--name",
                jpackageAppName,
                "--input",
                installInputDir.get().asFile.absolutePath,
                "--main-jar",
                mainJarName,
                "--main-class",
                "io.github.p4suta.despeckle.Main",
                "--runtime-image",
                jreImageDir.get().asFile.absolutePath,
                "--dest",
                jpackageOutputParent.get().asFile.absolutePath,
                // MaxRAMPercentage adapts heap to the host (and container cgroups) instead of a
                // fixed value; native access is required for the Leptonica FFM downcalls.
                "--java-options",
                "--enable-native-access=ALL-UNNAMED",
                "--java-options",
                "-XX:MaxRAMPercentage=75.0",
            )

        inputs.dir(jreImageDir)
        inputs.dir(installInputDir)
        outputs.dir(jpackageOutputParent)
    }
