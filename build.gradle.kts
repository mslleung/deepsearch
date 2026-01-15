plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.shadow) apply false
}

group = "io.deepsearch"

// Load environment variables from .env file for all subprojects
fun loadEnvFile(file: File): Map<String, String> {
    val envVars = mutableMapOf<String, String>()
    if (file.exists()) {
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val (key, value) = trimmed.split("=", limit = 2)
                envVars[key.trim()] = value.trim()
            }
        }
    }
    return envVars
}

val envFile = file(".env")
val envVars: Map<String, String> by extra(loadEnvFile(envFile))

// Disable configuration cache for all test tasks across all modules
// This ensures test annotation changes (like @ValueSource parameters) are always detected
subprojects {
    tasks.withType<Test>().configureEach {
        notCompatibleWithConfigurationCache("Test annotations may change independently of compiled classes")
    }
}