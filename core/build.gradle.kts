plugins {
    id("convention.kotlin-jvm")
    id("convention.publish")
}

dependencies {
    api(libs.koin.core)
    api(kt.kotlinx.coroutine)
    api(libs.kotlinx.logger)
    implementation(libs.kotlinx.logger.integration.koin)
    implementation(libs.kotlinx.logger.integration.jvm)

    testImplementation(kt.kotlinx.coroutine.test)
}
