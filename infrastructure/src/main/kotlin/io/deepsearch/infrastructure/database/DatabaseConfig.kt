package io.deepsearch.infrastructure.database

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File

object DatabaseConfig {
    fun configureDatabase(): R2dbcDatabase {
        // Switch to R2DBC with a file-based H2 database (embedded, not in-memory)
        // Data will be persisted in the project root directory
        val projectRoot = findProjectRoot()
        val dbFile = File(projectRoot, "deepsearch.h2")
        
        // Use the absolute path for cross-platform compatibility
        val dbPath = dbFile.absolutePath.replace("\\", "/")
        val database: R2dbcDatabase = R2dbcDatabase.connect(
            "r2dbc:h2:file:///$dbPath"
        )

        // Keep previous JDBC in-memory setup as a comment
//        val database = R2dbcDatabase.connect(
//            url = "r2dbc:h2:mem:test;DB_CLOSE_DELAY=-1",
//            user = "root",
//            driver = "org.h2.Driver",
//            password = ""
//        )

        // Initialize schema using a suspended transaction for R2DBC
        runBlocking {
            suspendTransaction {
                SchemaUtils.create(UserTable, WebpageIconTable)
            }
        }

        return database
    }

    /**
     * Finds the project root directory by looking for common project markers.
     * This approach is cross-platform and works regardless of how the application is run.
     */
    private fun findProjectRoot(): File {
        var currentDir = File(System.getProperty("user.dir"))
        
        // Look for project markers (settings.gradle.kts, build.gradle.kts, .git directory)
        while (currentDir.parent != null) {
            val settingsGradle = File(currentDir, "settings.gradle.kts")
            val buildGradle = File(currentDir, "build.gradle.kts")
            val gitDir = File(currentDir, ".git")
            
            if (settingsGradle.exists() || (buildGradle.exists() && gitDir.exists())) {
                return currentDir
            }
            
            currentDir = currentDir.parentFile
        }
        
        // Fallback to current working directory if no project root found
        return File(System.getProperty("user.dir"))
    }
}