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
    implementation(libs.jsoup)

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

// Create a test JAR to allow other modules to depend on test classes
tasks.register<Jar>("testJar") {
    archiveClassifier.set("test")
    from(sourceSets.test.get().output)
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

// Check that Node.js toolchain is available (without installing/modifying on every build)
val ensureNodeTooling by tasks.registering(Exec::class) {
    group = "build setup"
    description =
        "Check that Node.js tooling is available. On Windows: verify npx is on PATH. On Unix: ensure NVM is set up and install LTS if needed."
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) {
        // On Windows, just verify npx is available. Don't run nvm commands that require elevation.
        // If npx is not found, provide a helpful error message.
        commandLine = listOf(
            "cmd", "/c",
            "where npx >nul 2>&1 || (echo Node.js not found. Please install Node.js from https://nodejs.org/ or use nvm-windows: https://github.com/coreybutler/nvm-windows & exit /b 1)"
        )
    } else {
        // Use zsh on Unix-like systems (default on macOS)
        commandLine = listOf(
            "zsh", "-lc",
            """
            set -euo pipefail
            export NVM_DIR="${'$'}HOME/.nvm"
            if [ ! -d "${'$'}NVM_DIR" ]; then
              curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
            fi
            # shellcheck source=/dev/null
            [ -s "${'$'}NVM_DIR/nvm.sh" ] && . "${'$'}NVM_DIR/nvm.sh"
            nvm install --lts
            nvm use --lts
            npm install -g npm@latest
            """.trimIndent()
        )
    }
}

// Compile TypeScript resources using system Node via npx
val compileTypeScript by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile TypeScript in resources/src to resources/out"
    // Run from the resources directory where tsconfig.json lives
    workingDir = tsResourcesDir
    // Use npx to invoke the TypeScript compiler; ensure NVM/Node/npm are available first
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) {
        // On Windows, prefer existing npx on PATH
        commandLine = listOf("npx.cmd", "--yes", "-p", "typescript", "tsc")
    } else {
        // On Unix-like systems, use zsh and source NVM to ensure node/npm/npx are available
        commandLine = listOf(
            "zsh", "-lc",
            """
            set -euo pipefail
            export NVM_DIR="${'$'}HOME/.nvm"
            # shellcheck source=/dev/null
            [ -s "${'$'}NVM_DIR/nvm.sh" ] && . "${'$'}NVM_DIR/nvm.sh"
            nvm install --lts
            nvm use --lts
            npx --yes -p typescript tsc
            """.trimIndent()
        )
    }
    // Inputs/outputs for incremental builds
    inputs.files(fileTree(tsSrcDir) { include("**/*.ts") })
    inputs.file(file("src/main/resources/tsconfig.json"))
    outputs.dir(tsOutDir)
    dependsOn(ensureTsOutDir, ensureNodeTooling)
    // Helpful message if npx is not found
    isIgnoreExitValue = false
}

// Make sure resources include the compiled JS and that TS compiles before processing resources
tasks.named<ProcessResources>("processResources") {
    dependsOn(compileTypeScript)
}
