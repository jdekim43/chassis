import kim.jade.gradle.plugin.cleanarch.plugin.module

rootProject.name = "chassis"

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

plugins {
    id("kim.jade.gradle.plugin.cleanarch") version "0.1.19"
}

module("core")
module("worker")
module("cli")
module("health")
module("ktor")
module("config")
module("config-toml")
module("config-yaml")
include(":examples:catalog")

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
    }

    versionCatalogs {
        create("kt") {
            from(files("gradle/kotlin.versions.toml"))
        }
    }
}
