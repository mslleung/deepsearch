plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.jib)
    alias(libs.plugins.shadow)
}

group = "io.deepsearch"
version = "1.0.0"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    
    // Ktor server dependencies
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.compression)
    
    // Ktor client dependencies (for OAuth)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    
    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // DateTime
    implementation(libs.kotlinx.datetime)
    
    // Security (for admin password updates)
    implementation(libs.jbcrypt)
    
    // Logging
    implementation(libs.logback.classic)

    implementation(libs.google.genai)
    
    // Stripe payment processing
    implementation(libs.stripe)
    
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

// Get shared env vars from root project
val envVars: Map<String, String> by rootProject.extra

tasks.test {
    useJUnitPlatform()
    
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Xmx4g",
        "-Dorg.bytedeco.javacpp.maxphysicalbytes=0",
        "-Dorg.bytedeco.javacpp.maxbytes=0"
    )
    
    // Run tests in parallel using available CPU cores
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    
    // Better test output
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    // Load all environment variables from .env file, with system env taking precedence
    envVars.forEach { (key, value) ->
        environment(key, System.getenv(key) ?: value)
    }
}

// --- Shadow JAR configuration for Docker builds ---
tasks.shadowJar {
    isZip64 = true
    archiveBaseName.set("deepsearch")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    
    manifest {
        attributes["Main-Class"] = "io.ktor.server.netty.EngineMain"
    }
}

// --- Jib container image configuration ---
jib {
    from {
        image = "eclipse-temurin:24-jre"  // Standard JRE image (glibc-based, compatible with Netty native)
    }
    to {
        image = "deepsearch"
        tags = setOf("latest", version.toString())
    }
    container {
        mainClass = "io.ktor.server.netty.EngineMain"
        ports = listOf("8080")
        jvmFlags = listOf(
            "-XX:+UseContainerSupport",
            "-XX:MaxRAMPercentage=75.0",
            "--enable-native-access=ALL-UNNAMED"
        )
    }
}
