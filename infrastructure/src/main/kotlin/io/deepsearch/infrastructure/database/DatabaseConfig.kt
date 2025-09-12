package io.deepsearch.infrastructure.database

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

object DatabaseConfig {
    fun configureDatabase(): R2dbcDatabase {
        // Switch to R2DBC with a file-based H2 database (embedded, not in-memory)
        // Data will be persisted under ./data/deepsearch.*
        val database: R2dbcDatabase = R2dbcDatabase.connect(
            "r2dbc:h2:file:///./h2db.h2"
        )

        // Keep previous JDBC in-memory setup as a comment
        /*
        val database = Database.connect(
            url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
            user = "root",
            driver = "org.h2.Driver",
            password = ""
        )
        */

        // Initialize schema using a suspended transaction for R2DBC
        runBlocking {
            suspendTransaction {
                SchemaUtils.create(UserTable)
            }
        }

        return database
    }
}