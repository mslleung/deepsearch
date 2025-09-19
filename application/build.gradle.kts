plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "io.deepsearch"

repositories {
    mavenCentral()
}

dependencies {
    // Application layer depends only on domain
    implementation(project(":domain"))
    implementation(project(":infrastructure"))

    // Serialization for DTOs
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.jsoup)

    implementation(libs.playwright)

    implementation(libs.google.adk)
    implementation(libs.google.adk.dev)

    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)

    // Depend on domain and infrastructure test fixtures
    testImplementation(testFixtures(project(":domain")))
    testImplementation(testFixtures(project(":infrastructure")))
}

tasks.test {
    useJUnitPlatform()
}