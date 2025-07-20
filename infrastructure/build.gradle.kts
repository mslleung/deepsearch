plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
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
    implementation(libs.exposed.jdbc)
    implementation(libs.h2)
    
    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnitPlatform()
}