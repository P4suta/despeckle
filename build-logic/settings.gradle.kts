dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // Make the root project's version catalog visible inside this included build so the
    // precompiled convention plugins can read versions/libraries via VersionCatalogsExtension
    // (precompiled script plugins get no generated type-safe `libs.` accessors).
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
