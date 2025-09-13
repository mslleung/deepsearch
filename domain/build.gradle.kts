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
    testImplementation(libs.junit.jupiter.params)
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

// --- TypeScript compilation for Playwright page scripts ---
val tsResourcesDir = file("src/main/resources")
val tsSrcDir = file("src/main/resources/src")
val tsOutDir = file("src/main/resources/out")

// Ensure output directory exists
val ensureTsOutDir by tasks.registering {
    group = "build setup"
    outputs.dir(tsOutDir)
    doLast {
        tsOutDir.mkdirs()
    }
}

// Compile TypeScript resources using system Node via npx
val compileTypeScript by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile TypeScript in resources/src to resources/out"
    // Run from the resources directory where tsconfig.json lives
    workingDir = tsResourcesDir
    // Use npx to invoke the local/global TypeScript compiler; --yes for non-interactive
    val npxBinary = if (System.getProperty("os.name").lowercase().contains("windows")) "npx.cmd" else "npx"
    commandLine = listOf(npxBinary, "--yes", "-p", "typescript", "tsc")
    // Inputs/outputs for incremental builds
    inputs.files(fileTree(tsSrcDir) { include("**/*.ts") })
    inputs.file(file("src/main/resources/tsconfig.json"))
    outputs.dir(tsOutDir)
    dependsOn(ensureTsOutDir)
    // Helpful message if npx is not found
    isIgnoreExitValue = false
}

// Make sure resources include the compiled JS and that TS compiles before processing resources
tasks.named<ProcessResources>("processResources") {
    dependsOn(compileTypeScript)
}
