plugins {
    id("convention.kotlin-jvm")
    id("convention.publish")
}

dependencies {
    api(project(":chassis-core"))
    api(kt.ktor.server.core)
    implementation(project(":chassis-health"))

    testImplementation(kt.ktor.server.cio)
    testImplementation(project(":chassis-health"))
}
