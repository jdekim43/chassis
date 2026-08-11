plugins {
    id("convention.kotlin-jvm")
    id("convention.publish")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":chassis-core"))
    api(kt.kotlinx.serialization.json)
    compileOnly(project(":chassis-cli"))

    testImplementation(project(":chassis-cli"))
    testImplementation(testFixtures(project(":chassis-cli")))
    testImplementation(kt.kotlinx.coroutine.test)
}
