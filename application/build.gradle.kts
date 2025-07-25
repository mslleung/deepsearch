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

    implementation(libs.playwright)

    implementation(libs.google.adk)
    implementation(libs.google.adk.dev)

    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}