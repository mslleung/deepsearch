plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.shadow) apply false
}

group = "io.deepsearch"