plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(kt.kotlin.gradlePlugin)
    implementation(kt.kotlin.serialization.gradlePlugin)
    implementation(kt.dokka)
    implementation(kt.dokka.javadoc)
}
