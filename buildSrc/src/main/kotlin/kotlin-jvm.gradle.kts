package convention

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.findLibrary("junit-jupiter-engine").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("chassis.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
