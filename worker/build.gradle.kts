plugins {
    id("convention.kotlin-jvm")
    id("convention.publish")
}

dependencies {
    api(project(":chassis-core"))

    testImplementation(kt.kotlinx.coroutine.test)
}
