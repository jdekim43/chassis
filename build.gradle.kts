import org.jreleaser.model.Active
import org.jreleaser.model.Signing

plugins {
    id("java")
    alias(libs.plugins.jreleaser)
}

val releaseVersion = providers.gradleProperty("releaseVersion").getOrElse("1.0.0-SNAPSHOT")
val libraries = subprojects.filter { it.name.startsWith("${rootProject.name}-") }

allprojects {
    group = "kim.jade"
    version = releaseVersion
}

jreleaser {
    project {
        author("Jade Kim")
        license.set("Apache-2.0")
        links {
            vcsBrowser.set("https://github.com/jdekim43/chassis")
        }
        inceptionYear.set("2026")
    }

    signing {
        active.set(Active.ALWAYS)
        armored.set(true)
        mode.set(Signing.Mode.FILE)
    }

    deploy {
        maven {
            mavenCentral {
                create("release") {
                    active.set(Active.RELEASE)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    skipPublicationCheck.set(true)

                    libraries.forEach {
                        stagingRepository(it.layout.buildDirectory.dir("staging-deploy").get().asFile.absolutePath)
                    }
                }
            }
            nexus2 {
                create("snapshot") {
                    active.set(Active.SNAPSHOT)
                    url.set("https://central.sonatype.com/repository/maven-snapshots")
                    snapshotUrl.set("https://central.sonatype.com/repository/maven-snapshots")
                    applyMavenCentralRules.set(true)
                    snapshotSupported.set(true)
                    closeRepository.set(true)
                    releaseRepository.set(true)

                    libraries.forEach {
                        stagingRepository(it.layout.buildDirectory.dir("staging-deploy").get().asFile.absolutePath)
                    }
                }
            }
        }
    }

    release {
        github {
            repoOwner = "jdekim43"
            prerelease.pattern = ".*-*"
        }
    }
}

val clearStagingDirectory = tasks.register<Delete>("clearStagingDirectory") {
    libraries.forEach {
        delete(it.layout.buildDirectory.dir("staging-deploy"))
    }
}

tasks.register("publish") {
    group = "publishing"

    libraries.forEach {
        val publishTask = it.tasks.named("publish")
        publishTask.configure { dependsOn(clearStagingDirectory) }
        dependsOn(publishTask)
    }
}
