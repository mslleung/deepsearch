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

    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnitPlatform()
}