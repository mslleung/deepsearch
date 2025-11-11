package io.deepsearch.infrastructure.services

import io.deepsearch.domain.config.EnvironmentConfig
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.infrastructure.database.*
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface IDatabaseConfigurationService {

    fun getDatabase(): R2dbcDatabase

}

/**
 * Service for configuring database connection and schema initialization.
 */
class DatabaseConfigurationService(
    private val environmentConfig: EnvironmentConfig,
    private val postgresConfig: PostgresConfig,
    private val userTable: UserTable,
    private val apiKeyTable: ApiKeyTable,
    private val rawApiKeyTable: RawApiKeyTable,
    private val apiKeyUsageTable: ApiKeyUsageTable,
    private val userSubscriptionTable: UserSubscriptionTable,
    private val webpageIconCacheTable: WebpageIconCacheTable,
    private val webpageImageCacheTable: WebpageImageCacheTable,
    private val webpagePopupCacheTable: WebpagePopupCacheTable,
    private val webpageTableCacheTable: WebpageTableCacheTable,
    private val webpageTableInterpretationCacheTable: WebpageTableInterpretationCacheTable,
    private val webpageSemanticElementCacheTable: WebpageSemanticElementCacheTable,
    private val webpageMarkdownCacheTable: WebpageMarkdownCacheTable,
    private val pdfMarkdownCacheTable: PdfMarkdownCacheTable,
    private val querySessionTable: QuerySessionTable,
    private val precacheJobTable: PrecacheJobTable,
    private val sitemapCacheTable: SitemapCacheTable,
    private val urlAccessTable: UrlAccessTable,
) : IDatabaseConfigurationService {

    private val _database: R2dbcDatabase = configureDatabase()

    override fun getDatabase() = _database

    /**
     * Configures database connection based on environment.
     * For development: H2 with R2DBC (SQLite-like, lightweight)
     * For production: PostgreSQL with R2DBC
     */
    private fun configureDatabase(): R2dbcDatabase {
        val database = if (environmentConfig.isDevelopmentMode) {
//             configureH2Database()
            configurePostgreSqlDatabase()
        } else {
            configurePostgreSqlDatabase()
        }

        // Initialize schema using R2DBC suspended transaction
        runBlocking {
            suspendTransaction(database) {
                // Enable pgvector extension for vector similarity search
                exec("CREATE EXTENSION IF NOT EXISTS vector")

                SchemaUtils.create(
                    userTable,
                    apiKeyTable,
                    rawApiKeyTable,
                    apiKeyUsageTable,
                    userSubscriptionTable,
                    webpageIconCacheTable,
                    webpageImageCacheTable,
                    webpagePopupCacheTable,
                    webpageTableCacheTable,
                    webpageTableInterpretationCacheTable,
                    webpageSemanticElementCacheTable,
                    webpageMarkdownCacheTable,
                    pdfMarkdownCacheTable,
                    querySessionTable,
                    precacheJobTable,
                    sitemapCacheTable,
                    urlAccessTable
                )

                // Create HNSW index for efficient vector similarity search on webpage embeddings
                // Using cosine distance operator (vector_cosine_ops) for cosine similarity
                // HNSW parameters: m=16 (connections per layer), ef_construction=64 (quality vs speed tradeoff)
                exec("""
                    CREATE INDEX IF NOT EXISTS webpage_markdowns_embedding_idx 
                    ON webpage_markdowns 
                    USING hnsw (embedding vector_cosine_ops)
                    WITH (m = 16, ef_construction = 64)
                """.trimIndent())
            }
        }

        return database
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

        return R2dbcDatabase.connect(
            "r2dbc:h2:file:///$encodedPath;DB_CLOSE_DELAY=-1",
            databaseConfig = R2dbcDatabaseConfig {
                defaultMaxAttempts = 1
                defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
            })
    }

    /**
     * Configures PostgreSQL database for production.
     * Uses R2DBC with configuration loaded from application.yaml.
     */
    private fun configurePostgreSqlDatabase(): R2dbcDatabase {
        val url = "r2dbc:postgresql://${postgresConfig.host}:${postgresConfig.port}/${postgresConfig.database}"
        return R2dbcDatabase.connect(
            url = url,
            user = postgresConfig.username,
            password = postgresConfig.password,
            databaseConfig = R2dbcDatabaseConfig {
                defaultMaxAttempts = 1
                defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
            }
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