package io.deepsearch.infrastructure.database

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object DatabaseConfig {
    /**
     * Configures database connection based on environment.
     * For development: H2 with R2DBC (SQLite-like, lightweight)
     * For production: PostgreSQL with R2DBC
     */
    fun configureDatabase(): R2dbcDatabase {
        val database = if (isDevelopmentMode()) {
            configureH2Database()
        } else {
            configurePostgreSqlDatabase()
        }

        // Initialize schema using R2DBC suspended transaction
        runBlocking {
            suspendTransaction {
                SchemaUtils.create(
                    UserTable,
                    WebpageIconTable,
                    WebpagePopupTable,
                    WebpageTableTable,
                    WebpageTableInterpretationTable,
                    WebpageNavigationElementTable
                )
            }
        }

        return database
    }

    /**
     * Determines if the application is running in development mode.
     * Checks for various development mode indicators in order of precedence:
     * 1. io.ktor.development system property
     * 2. io.ktor.development environment variable
     * 3. IDE debugging mode (-ea flag)
     * 4. Defaults to false for production
     */
    private fun isDevelopmentMode(): Boolean {
        // Check system property first (highest precedence)
        val systemProperty = System.getProperty("io.ktor.development")?.toBoolean()
        if (systemProperty != null) return systemProperty

        // Check environment variable
        val envVariable = System.getenv("io.ktor.development")?.toBoolean()
        if (envVariable != null) return envVariable

        // Check if assertions are enabled (indicates -ea flag, common in development)
        var assertionsEnabled = false
        assert({ assertionsEnabled = true; true }())
        if (assertionsEnabled) return true

        // Default to production mode
        return false
    }

    /**
     * Configures H2 database for development.
     * Uses R2DBC - H2 is lightweight and SQLite-like but supports R2DBC.
     */
    private fun configureH2Database(): R2dbcDatabase {
        val databaseDir = getDatabaseDirectory()
        val dbFile = File(databaseDir, "deepsearch-dev.h2")
        
        // Use H2 file-based database for development with R2DBC
        // URL-encode the path to handle spaces and special characters
        val dbPath = dbFile.absolutePath.replace("\\", "/")
        val encodedPath = URLEncoder.encode(dbPath, StandardCharsets.UTF_8)
            .replace("%2F", "/")  // Keep forward slashes unencoded
            .replace("+", "%20")  // Use proper space encoding for URLs
        
        return R2dbcDatabase.connect("r2dbc:h2:file:///$encodedPath;DB_CLOSE_DELAY=-1")
    }

    /**
     * Configures PostgreSQL database for production.
     * Uses R2DBC with environment variables or default connection parameters.
     */
    private fun configurePostgreSqlDatabase(): R2dbcDatabase {
        val host = System.getenv("DB_HOST") ?: "localhost"
        val port = System.getenv("DB_PORT") ?: "5432"
        val database = System.getenv("DB_NAME") ?: "deepsearch"
        val username = System.getenv("DB_USERNAME") ?: "deepsearch"
        val password = System.getenv("DB_PASSWORD") ?: "deepsearch"

        val url = "r2dbc:postgresql://$host:$port/$database"
        return R2dbcDatabase.connect(
            url = url,
            user = username,
            password = password
        )
    }

    /**
     * Gets the database directory.
     * Returns the project root directory.
     */
    private fun getDatabaseDirectory(): File {
        var currentDir = File(System.getProperty("user.dir"))
        
        // Walk up to find the Gradle project root (identified by gradlew)
        while (currentDir.parentFile != null) {
            if (File(currentDir, "gradlew").exists()) {
                return currentDir
            }
            currentDir = currentDir.parentFile
        }
        
        error("Could not find Gradle project root directory")
    }
}