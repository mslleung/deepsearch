package io.deepsearch.infrastructure.services

import io.deepsearch.domain.config.EnvironmentConfig
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.infrastructure.database.*
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

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
    private val userSubscriptionTable: UserSubscriptionTable,
    private val webpageIconCacheTable: WebpageIconCacheTable,
    private val webpageImageCacheTable: WebpageImageCacheTable,
    private val webpageImageLinkageTable: WebpageImageLinkageTable,
    private val webpagePopupCacheTable: WebpagePopupCacheTable,
    private val webpageTableCacheTable: WebpageTableCacheTable,
    private val webpageTableInterpretationCacheTable: WebpageTableInterpretationCacheTable,
    private val webpageSemanticElementCacheTable: WebpageSemanticElementCacheTable,
    private val webpageMarkdownCacheTable: WebpageMarkdownCacheTable,
    private val pdfMarkdownCacheTable: PdfMarkdownCacheTable,
    private val querySessionTable: QuerySessionTable,
    private val periodicIndexJobTable: PeriodicIndexJobTable,
    private val sitemapCacheTable: SitemapCacheTable,
    private val urlAccessTable: UrlAccessTable,
    private val llmTokenUsageTable: LlmTokenUsageTable,
    private val periodicIndexConfigTable: PeriodicIndexConfigTable,
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
                    userSubscriptionTable,
                    webpageIconCacheTable,
                    webpageImageCacheTable,
                    webpageImageLinkageTable,
                    webpagePopupCacheTable,
                    webpageTableCacheTable,
                    webpageTableInterpretationCacheTable,
                    webpageSemanticElementCacheTable,
                    webpageMarkdownCacheTable,
                    pdfMarkdownCacheTable,
                    querySessionTable,
                    periodicIndexJobTable,
                    sitemapCacheTable,
                    urlAccessTable,
                    llmTokenUsageTable,
                    periodicIndexConfigTable,
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

                // Create GIN index for full-text search on markdown content
                exec("""
                    CREATE INDEX IF NOT EXISTS webpage_markdowns_markdown_search_vector_idx
                    ON webpage_markdowns 
                    USING gin (markdown_search_vector)
                """.trimIndent())
                
                // Create trigger function to auto-update tsvector when sanitized markdown changes
                // Uses markdown_sanitized (not markdown) to avoid R2DBC issues with emoji/special chars in tsvector
                exec(
                    """
                    CREATE OR REPLACE FUNCTION webpage_markdowns_markdown_search_vector_update() 
                    RETURNS trigger AS $$
                    BEGIN
                      NEW.markdown_search_vector := to_tsvector('simple', COALESCE(NEW.markdown_sanitized, ''));
                      RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql;
                """.trimIndent())
                
                // Create trigger to automatically update tsvector on INSERT or UPDATE of sanitized markdown
                exec("""
                    DROP TRIGGER IF EXISTS webpage_markdowns_markdown_search_vector_trigger ON webpage_markdowns;
                    CREATE TRIGGER webpage_markdowns_markdown_search_vector_trigger
                    BEFORE INSERT OR UPDATE OF markdown_sanitized ON webpage_markdowns
                    FOR EACH ROW EXECUTE FUNCTION webpage_markdowns_markdown_search_vector_update();
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
     * Uses R2DBC with connection pooling. Pool settings are loaded from PostgresConfig.
     */
    private fun configurePostgreSqlDatabase(): R2dbcDatabase {
        val connectionFactory = PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(postgresConfig.host)
                .port(postgresConfig.port)
                .database(postgresConfig.database)
                .username(postgresConfig.username)
                .password(postgresConfig.password)
                .build()
        )

        val poolConfiguration = ConnectionPoolConfiguration.builder(connectionFactory)
            .maxSize(postgresConfig.poolMaxSize)
            .initialSize(postgresConfig.poolInitialSize)
            .maxIdleTime(Duration.ofMinutes(postgresConfig.poolMaxIdleTimeMinutes))
            .maxLifeTime(Duration.ofHours(1))
            .build()

        val pool = ConnectionPool(poolConfiguration)

        return R2dbcDatabase.connect(
            connectionFactory = pool,
            databaseConfig = R2dbcDatabaseConfig {
                defaultMaxAttempts = 1
                defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
                explicitDialect = PostgreSQLDialect()
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