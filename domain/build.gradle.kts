import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

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
    // Domain layer should be pure - minimal dependencies only
    implementation(libs.playwright)
    implementation(libs.playwright.axe.core)

    implementation(libs.google.adk)
    implementation(libs.google.adk.dev)
    implementation(libs.google.genai)
    implementation(libs.google.auth.oauth2)
    implementation(libs.openai.java)
    
    implementation(libs.apache.pdfbox)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    // HTML to Markdown conversion
    implementation(libs.flexmark)
    implementation(libs.flexmark.html2md)

    // Apache HttpClient 5 - required for Google ADK Dev UI
    implementation(libs.apache.httpclient5)
    implementation(libs.apache.httpcore5)
    implementation(libs.apache.httpcore5.h2)

    // Ktor HTTP Client (CIO) for safe redirect resolution
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // OCR support
    implementation(libs.tesseract)
    implementation(libs.opencv)

    // Multi-language text processing (sentence boundary detection)
    implementation(libs.icu4j)

    // Image dimension extraction (WebP support for screenshot dimensions)
    implementation(libs.twelvemonkeys.imageio.webp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.rx3)
    
    // DateTime
    implementation(libs.kotlinx.datetime)

    // Security
    implementation(libs.jbcrypt)
    implementation(libs.jwt)

    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
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
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    // Load all environment variables from .env file, with system env taking precedence
    envVars.forEach { (key, value) ->
        environment(key, System.getenv(key) ?: value)
    }
}

// Create a test JAR to allow other modules to depend on test classes
tasks.register<Jar>("testJar") {
    archiveClassifier.set("test")
    from(sourceSets.test.get().output)
    exclude("junit-platform.properties")
    dependsOn(tasks.testClasses)
}

// Make the test JAR available as an artifact
configurations {
    create("testArtifacts") {
        extendsFrom(configurations.testImplementation.get())
    }
}

artifacts {
    add("testArtifacts", tasks.named("testJar"))
}

tasks.register<JavaExec>("runAdkWebServer") {
    group = "application"
    description = "Runs the ADK Web Server"
    mainClass = "com.google.adk.web.AdkWebServer"
    classpath = sourceSets.main.get().runtimeClasspath
    args("--adk.agents.source-dir=src/test/java")
}
