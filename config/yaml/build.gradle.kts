plugins {
    id("convention.kotlin-jvm")
    id("convention.publish")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":chassis-config"))
    implementation(libs.snakeyaml)

    testImplementation(project(":chassis-config-toml"))
    testImplementation(project(":chassis-cli"))
    testImplementation(kt.kotlinx.coroutine.test)
}
