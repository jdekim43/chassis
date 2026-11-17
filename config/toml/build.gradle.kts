plugins {
    id("convention.kotlin-jvm")
    id("convention.publish")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":chassis-config"))
    implementation(libs.tomlkt)

    testImplementation(project(":chassis-cli"))
    testImplementation(kt.kotlinx.coroutine.test)
    testImplementation(libs.r8)
}
