package convention

import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask

plugins {
    id("convention.kotlin-jvm")
    id("maven-publish")
    id("org.jetbrains.dokka")
    id("org.jetbrains.dokka-javadoc")
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    val html = tasks.named<DokkaGeneratePublicationTask>("dokkaGeneratePublicationHtml")
    dependsOn(html)
    archiveClassifier.set("javadoc")
    from(html.flatMap { it.outputDirectory })
}

configure<PublishingExtension> {
    repositories {
        maven {
            setUrl(layout.buildDirectory.dir("staging-deploy"))
        }
    }

    publications {
        create<MavenPublication>("lib") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(javadocJar)
            pom {
                name.set(project.name)
                description.set("Modular Kotlin application framework on Koin with lifecycle hooks, workers, a CLI host, configuration, health checks, and a Ktor server")
                url.set("https://github.com/jdekim43/chassis")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("jdekim43")
                        name.set("Jade Kim")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/jdekim43/chassis.git")
                    developerConnection.set("scm:git:ssh://git@github.com/jdekim43/chassis.git")
                    url.set("https://github.com/jdekim43/chassis")
                }
            }
        }
    }
}
