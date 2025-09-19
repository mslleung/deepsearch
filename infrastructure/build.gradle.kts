plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("java-test-fixtures")
}

group = "io.deepsearch"

repositories {
    mavenCentral()
}

dependencies {
    // Infrastructure depends on domain for repository interfaces
    implementation(project(":domain"))
    
    // Database dependencies
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.r2dbc.h2)
    
    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)

    // Expose reusable Koin test modules via test fixtures
    testFixturesImplementation(libs.koin.ktor)
    testFixturesImplementation(libs.koin.logger.slf4j)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(project(":domain"))
}

tasks.test {
    useJUnitPlatform()
}