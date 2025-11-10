import java.sql.DriverManager

buildscript {
    dependencies {
        classpath("org.postgresql:postgresql:42.7.3")
    }
}

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
    
    // pgvector support
    implementation("com.pgvector:pgvector:0.1.6")
    
    // Dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // DateTime
    implementation(libs.kotlinx.datetime)
    
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
    environment("SERPER_API_KEY", System.getenv("SERPER_API_KEY") ?: "")
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

/**
 * Gradle task to drop and recreate the PostgreSQL database.
 * 
 * Usage: ./gradlew :infrastructure:resetDatabase
 * 
 * Requires environment variables:
 * - DB_HOST: PostgreSQL host
 * - DB_PORT: PostgreSQL port
 * - DB_NAME: Database name to drop and recreate
 * - DB_USERNAME: Database username
 * - DB_PASSWORD: Database password
 * 
 * Note: The database user must have CREATEDB privilege or be a superuser.
 */
tasks.register("resetDatabase") {
    group = "database"
    description = "Drops and recreates the PostgreSQL database"
    
    doLast {
        val dbHost = System.getenv("DB_HOST") ?: error("DB_HOST environment variable is not set")
        val dbPort = System.getenv("DB_PORT") ?: error("DB_PORT environment variable is not set")
        val dbName = System.getenv("DB_NAME") ?: error("DB_NAME environment variable is not set")
        val dbUsername = System.getenv("DB_USERNAME") ?: error("DB_USERNAME environment variable is not set")
        val dbPassword = System.getenv("DB_PASSWORD") ?: error("DB_PASSWORD environment variable is not set")
        
        // Safety check: prevent dropping system databases
        if (dbName.lowercase() in listOf("postgres", "template0", "template1")) {
            error("Cannot drop system database '$dbName'. Please set DB_NAME to your application database.")
        }
        
        // Connect to the postgres maintenance database (not the target database)
        val maintenanceDb = "postgres"
        val maintenanceUrl = "jdbc:postgresql://$dbHost:$dbPort/$maintenanceDb"
        
        println("Connecting to PostgreSQL maintenance database '$maintenanceDb' at $dbHost:$dbPort...")
        
        DriverManager.getConnection(maintenanceUrl, dbUsername, dbPassword).use { connection ->
            connection.autoCommit = true
            
            // Verify we're connected to the maintenance database, not the target
            val currentDb = connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT current_database()").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
            println("Connected to database: $currentDb")
            
            if (currentDb.equals(dbName, ignoreCase = true)) {
                error("Cannot drop database '$dbName' while connected to it. Connected to: $currentDb")
            }
            
            // Step 1: Terminate all connections to the target database
            println("Terminating active connections to database '$dbName'...")
            val terminateStatement = connection.prepareStatement(
                """
                SELECT pg_terminate_backend(pid)
                FROM pg_stat_activity
                WHERE datname = ? AND pid <> pg_backend_pid()
                """.trimIndent()
            )
            terminateStatement.setString(1, dbName)
            val resultSet = terminateStatement.executeQuery()
            var terminatedCount = 0
            while (resultSet.next()) {
                if (resultSet.getBoolean(1)) {
                    terminatedCount++
                }
            }
            println("Terminated $terminatedCount active connection(s)")
            
            // Step 2: Drop the database if it exists
            println("Dropping database '$dbName' if it exists...")
            val dropStatement = connection.createStatement()
            dropStatement.execute("DROP DATABASE IF EXISTS \"$dbName\"")
            println("Database '$dbName' dropped successfully")
            
            // Step 3: Create the database
            println("Creating database '$dbName'...")
            val createStatement = connection.createStatement()
            createStatement.execute("CREATE DATABASE \"$dbName\"")
            println("Database '$dbName' created successfully")
        }
        
        println()
        println("✓ Database reset completed successfully!")
        println("  The schema will be automatically created when the application starts.")
    }
}