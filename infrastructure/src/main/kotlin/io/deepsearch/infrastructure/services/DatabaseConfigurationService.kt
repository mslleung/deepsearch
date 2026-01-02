package io.deepsearch.infrastructure.services

import io.deepsearch.domain.config.EnvironmentConfig
import io.deepsearch.domain.config.PostgresConfig
import io.deepsearch.infrastructure.database.DatabaseTables
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

interface IDatabaseConfigurationService {

    fun getDatabase(): R2dbcDatabase
    
    /**
     * Returns the R2DBC connection pool for raw SQL execution.
     * Used for executing queries that Exposed doesn't directly support (e.g., Apache AGE Cypher).
     * Returns null if not using PostgreSQL (e.g., H2 in development).
     */
    fun getConnectionPool(): ConnectionPool?

}

/**
 * Service for configuring database connection and schema initialization.
 */
class DatabaseConfigurationService(
    private val environmentConfig: EnvironmentConfig,
    private val postgresConfig: PostgresConfig,
    private val tables: DatabaseTables,
) : IDatabaseConfigurationService {

    private val logger = LoggerFactory.getLogger(DatabaseConfigurationService::class.java)
    
    // Connection pool stored separately for raw SQL execution (e.g., Apache AGE Cypher)
    private var _connectionPool: ConnectionPool? = null
    private val _database: R2dbcDatabase = configureDatabase()
    
    companion object {
        /**
         * Maximum text length (in characters) passed to PostgreSQL to_tsvector.
         * Prevents tsvector from growing too large (>100KB) which causes R2DBC buffer overflow.
         * 100,000 characters is approximately 100KB, well within R2DBC buffer limits.
         */
        private const val MAX_TSVECTOR_INPUT_LENGTH = 100_000
    }

    override fun getDatabase() = _database
    
    override fun getConnectionPool() = _connectionPool

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
                
                // Create implicit casts from text/varchar to vector so parameterized queries work.
                // R2DBC PostgreSQL doesn't have a built-in encoder for vector type, so we bind
                // vectors as text strings. These casts allow PostgreSQL to automatically convert them.
                exec("""
                    DO $$
                    BEGIN
                        -- Cast from text to vector
                        IF NOT EXISTS (
                            SELECT 1 FROM pg_cast 
                            WHERE castsource = 'text'::regtype 
                            AND casttarget = 'vector'::regtype
                        ) THEN
                            CREATE CAST (text AS vector) WITH INOUT AS IMPLICIT;
                        END IF;
                        
                        -- Cast from varchar to vector (R2DBC may bind strings as varchar)
                        IF NOT EXISTS (
                            SELECT 1 FROM pg_cast 
                            WHERE castsource = 'character varying'::regtype 
                            AND casttarget = 'vector'::regtype
                        ) THEN
                            CREATE CAST (character varying AS vector) WITH INOUT AS IMPLICIT;
                        END IF;
                    END $$;
                """.trimIndent())

                SchemaUtils.create(*tables.allTables)
                
                // Enable Apache AGE extension for graph database functionality
                // NOTE: Apache AGE must be installed on the PostgreSQL server
                // See: https://age.apache.org/age-manual/master/intro/setup.html
                exec("""
                    DO $$
                    DECLARE
                        db_name TEXT := current_database();
                    BEGIN
                        -- Check if AGE extension is available before trying to create it
                        IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'age') THEN
                            CREATE EXTENSION IF NOT EXISTS age;
                            
                            -- Configure database to auto-load AGE for all connections (required for R2DBC)
                            -- session_preload_libraries ensures 'age' is loaded for every new connection
                            EXECUTE format('ALTER DATABASE %I SET session_preload_libraries = ''age''', db_name);
                            
                            -- Set database-level search_path so ag_catalog is always accessible
                            EXECUTE format('ALTER DATABASE %I SET search_path = ag_catalog, "${'$'}user", public', db_name);
                            
                            -- Set search path for current session
                            SET search_path = ag_catalog, "${'$'}user", public;
                            
                            -- Create the knowledge_graph if it doesn't exist
                            IF NOT EXISTS (SELECT 1 FROM ag_graph WHERE name = 'knowledge_graph') THEN
                                PERFORM create_graph('knowledge_graph');
                            END IF;
                        ELSE
                            RAISE NOTICE 'Apache AGE extension is not available. Knowledge graph features will be disabled.';
                        END IF;
                    END $$;
                """.trimIndent())
                
                // Create HNSW index for entity embeddings (semantic search)
                exec("""
                    CREATE INDEX IF NOT EXISTS kg_entity_embeddings_embedding_idx
                    ON kg_entity_embeddings 
                    USING hnsw (embedding vector_cosine_ops)
                    WITH (m = 16, ef_construction = 64)
                """.trimIndent())

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
                // Limits text to first MAX_TSVECTOR_INPUT_LENGTH chars to prevent tsvector from growing too large (R2DBC buffer overflow)
                exec(
                    """
                    CREATE OR REPLACE FUNCTION webpage_markdowns_markdown_search_vector_update() 
                    RETURNS trigger AS $$
                    BEGIN
                      NEW.markdown_search_vector := to_tsvector('simple', 
                        LEFT(COALESCE(NEW.markdown_sanitized, ''), $MAX_TSVECTOR_INPUT_LENGTH)
                      );
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
        
        // Store pool for raw SQL execution (e.g., Apache AGE Cypher queries)
        _connectionPool = pool

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