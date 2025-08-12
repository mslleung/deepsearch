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
    implementation(libs.playwright.axe.core)

    implementation(libs.google.adk)
    implementation(libs.google.adk.dev)

    implementation(libs.kotlinx.serialization.json)

    // Apache HttpClient 5 - required for Google ADK Dev UI
    implementation(libs.apache.httpclient5)
    implementation(libs.apache.httpcore5)
    implementation(libs.apache.httpcore5.h2)

    // Ktor HTTP Client (CIO) for safe redirect resolution
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    // OCR support
    implementation(libs.tesserect)
    implementation(libs.opencv)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.rx3)

    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runAdkWebServer") {
    group = "application"
    description = "Runs the ADK Web Server"
    mainClass = "com.google.adk.web.AdkWebServer"
    classpath = sourceSets.main.get().runtimeClasspath
    args("--adk.agents.source-dir=src/test/java")
}
