import org.gradle.kotlin.dsl.compileTestKotlin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
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

    // Database dependencies
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)

    // Ktor HTTP Client for content type resolution
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.playwright)
    
    // PDF processing
    implementation(libs.apache.pdfbox)
    
    // Lucene for multilingual keyword extraction
    implementation(libs.lucene.analysis.common)

    implementation(libs.google.adk)
    implementation(libs.google.adk.dev)

    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // DateTime
    implementation(libs.kotlinx.datetime)

    // Security
    implementation(libs.jbcrypt)
    implementation(libs.jwt)
    
    // Stripe payment processing
    implementation(libs.stripe)
    
    // ICU4J for multilingual sentence detection
    implementation(libs.icu4j)

    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    
    // Test dependencies on domain and infrastructure test JARs
    testImplementation(files("../domain/build/libs/domain-test.jar"))
    testImplementation(files("../infrastructure/build/libs/infrastructure-test.jar"))
}

// Get shared env vars from root project
val envVars: Map<String, String> by rootProject.extra

tasks.test {
    useJUnitPlatform()

    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules", "jdk.incubator.vector",
        "-Xmx4g",
        "-Dorg.bytedeco.javacpp.maxphysicalbytes=0",
        "-Dorg.bytedeco.javacpp.maxbytes=0"
    )
    
    // Run tests in parallel using available CPU cores
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    
    // Better test output
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    // Load all environment variables from .env file, with system env taking precedence
    envVars.forEach { (key, value) ->
        environment(key, System.getenv(key) ?: value)
    }
}

tasks.compileKotlin {
    dependsOn(project(":domain").tasks.named("processResources"))
}

// Ensure test JARs are built before compiling application tests
tasks.compileTestKotlin {
    dependsOn(project(":domain").tasks.named("testJar"))
    dependsOn(project(":infrastructure").tasks.named("testJar"))
}