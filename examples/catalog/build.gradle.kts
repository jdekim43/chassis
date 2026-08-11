plugins {
    id("convention.kotlin-jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":chassis-cli"))
    implementation(project(":chassis-worker"))
    implementation(project(":chassis-health"))
    implementation(project(":chassis-config"))
    implementation(project(":chassis-config-toml"))
    implementation(project(":chassis-ktor"))
    implementation(kt.ktor.server.cio)

    testImplementation(testFixtures(project(":chassis-cli")))
}

application { mainClass.set("example.catalog.MainKt") }

tasks.test {
    systemProperty("catalog.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
