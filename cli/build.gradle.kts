plugins {
    id("convention.kotlin-jvm")
    id("convention.publish")
    `java-test-fixtures`
}

dependencies {
    api(project(":chassis-core"))
    api(libs.clikt.core)

    testImplementation(kt.kotlinx.coroutine.test)
}

// 테스트 지원 jar는 Gradle 모듈 메타데이터로만 고를 수 있다. POM에 담지 못한다는 경고를 끈다.
publishing {
    publications.withType<MavenPublication> {
        suppressPomMetadataWarningsFor("testFixturesApiElements")
        suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
    }
}
