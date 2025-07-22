plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "io.deepsearch"

repositories {
    mavenCentral()
}

dependencies {
    // Domain layer should be pure - minimal dependencies only
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