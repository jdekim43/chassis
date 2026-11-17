dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
        create("kt") {
            from(files("../gradle/kotlin.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"
