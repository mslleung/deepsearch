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
    implementation(libs.exposed.r2dbc)
    implementation(libs.r2dbc.h2)
    implementation(libs.r2dbc.postgresql)
    
    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)
    
    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testImplementation(files("../domain/build/libs/domain-test.jar"))
}

tasks.test {
    useJUnitPlatform()
    
    // Pass environment variables to tests
    environment("GOOGLE_API_KEY", System.getenv("GOOGLE_API_KEY") ?: "")
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

tasks.compileTestKotlin {
    dependsOn(project(":domain").tasks.named("testJar"))
}