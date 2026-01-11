package io.deepsearch.infrastructure.services

import io.deepsearch.domain.config.IDispatcherProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux

interface ITransactionService {
    suspend fun <T> withTransaction(block: suspend R2dbcTransaction.() -> T): T

    /**
     * Execute a raw SQL query and return results as a list of maps.
     * Used for queries that Exposed doesn't directly support (e.g., Apache AGE Cypher).
     * 
     * @param sql The SQL query to execute
     * @param timeoutSeconds Maximum execution time (default 30 seconds)
     * @return List of result rows as maps (column name to string value)
     * @throws IllegalStateException if connection pool is not available
     */
    suspend fun executeRawQuery(sql: String, timeoutSeconds: Int = 30): List<Map<String, String>>
    
    /**
     * Execute a raw SQL statement that doesn't return results (CREATE, UPDATE, DELETE).
     * Used for Apache AGE mutations (creating/deleting vertices and edges).
     * 
     * @param sql The SQL statement to execute
     * @throws IllegalStateException if connection pool is not available
     */
    suspend fun executeRawUpdate(sql: String)
}

class TransactionService(
    private val databaseConfigurationService: IDatabaseConfigurationService,
    private val dispatchProvider: IDispatcherProvider
) : ITransactionService {

    private val logger = LoggerFactory.getLogger(TransactionService::class.java)

    /**
     * Wraps a block of code in a database transaction.
     *
     * When called within an existing transaction, this function will join that transaction
     * (nested transaction support via Exposed R2DBC). This provides atomicity for multi-step
     * operations in application services while keeping repository-level transactions as fallback.
     *
     * @param block The code block to execute within the transaction
     * @return The result of the block execution
     * @throws Exception if the transaction fails or the block throws an exception
     */
    override suspend fun <T> withTransaction(block: suspend R2dbcTransaction.() -> T): T {
        val start = System.currentTimeMillis()
        return withContext(dispatchProvider.io) {
            val afterContextSwitch = System.currentTimeMillis()
            suspendTransaction(databaseConfigurationService.getDatabase()) {
                val afterTxStart = System.currentTimeMillis()
                val result = block()
                val afterBlock = System.currentTimeMillis()
                val totalTime = afterBlock - start
                // Only log if transaction takes longer than 20ms (to reduce noise)
                if (totalTime > 20) {
                    logger.debug(
                        "TX timing: total={}ms, contextSwitch={}ms, txAcquire={}ms, block={}ms",
                        totalTime,
                        afterContextSwitch - start,
                        afterTxStart - afterContextSwitch,
                        afterBlock - afterTxStart
                    )
                }
                result
            }
        }
    }

    /**
     * Execute a raw SQL query using the R2DBC connection pool.
     * This bypasses Exposed's ORM layer to execute arbitrary SQL with result reading.
     */
    override suspend fun executeRawQuery(sql: String, timeoutSeconds: Int): List<Map<String, String>> {
        val pool = databaseConfigurationService.getConnectionPool()
            ?: throw IllegalStateException("Connection pool not available. Raw SQL execution requires PostgreSQL.")

        return withContext(dispatchProvider.io) {
            val connection = Flux.from(pool.create()).awaitFirstOrNull()
                ?: throw IllegalStateException("Failed to acquire database connection")

            try {
                // Ensure AGE is loaded and search_path includes ag_catalog for this connection.
                // The database-level session_preload_libraries setting only affects new connections,
                // so we explicitly initialize here to handle pooled connections that may have been
                // created before the database settings were applied.
                initializeAgeForConnection(connection)
                
                val statement = connection.createStatement(sql)
                val results = mutableListOf<Map<String, String>>()

                // Execute and collect results
                Flux.from(statement.execute())
                    .flatMap { result ->
                        Flux.from(result.map { row, metadata ->
                            val columnNames = (0 until metadata.columnMetadatas.size)
                                .map { metadata.columnMetadatas[it].name }

                            columnNames.associateWith { columnName ->
                                row.get(columnName)?.toString() ?: ""
                            }
                        })
                    }
                    .asFlow()
                    .toList(results)

                results
            } catch (e: Exception) {
                logger.error("Error executing raw SQL query: {}", e.message, e)
                throw e
            } finally {
                // Return connection to pool
                try {
                    Flux.from(connection.close()).awaitFirstOrNull()
                } catch (e: Exception) {
                    logger.warn("Error closing connection: {}", e.message)
                }
            }
        }
    }
    
    /**
     * Execute a raw SQL statement that doesn't return results.
     * Used for Apache AGE mutations (creating/deleting vertices and edges).
     */
    override suspend fun executeRawUpdate(sql: String) {
        val pool = databaseConfigurationService.getConnectionPool()
            ?: throw IllegalStateException("Connection pool not available. Raw SQL execution requires PostgreSQL.")

        withContext(dispatchProvider.io) {
            val connection = Flux.from(pool.create()).awaitFirstOrNull()
                ?: throw IllegalStateException("Failed to acquire database connection")

            try {
                // Ensure AGE is loaded and search_path includes ag_catalog for this connection.
                // The database-level session_preload_libraries setting only affects new connections,
                // so we explicitly initialize here to handle pooled connections that may have been
                // created before the database settings were applied.
                initializeAgeForConnection(connection)
                
                val statement = connection.createStatement(sql)
                // Execute and consume results (even for mutations, AGE returns rows)
                Flux.from(statement.execute())
                    .flatMap { result -> Flux.from(result.rowsUpdated) }
                    .asFlow()
                    .toList()
            } catch (e: Exception) {
                logger.error("Error executing raw SQL update: {}", e.message, e)
                throw e
            } finally {
                try {
                    Flux.from(connection.close()).awaitFirstOrNull()
                } catch (e: Exception) {
                    logger.warn("Error closing connection: {}", e.message)
                }
            }
        }
    }
    
    /**
     * Initialize a connection for Apache AGE queries.
     * Loads the AGE extension and sets the search_path to include ag_catalog.
     * This is idempotent - safe to call multiple times on the same connection.
     */
    private suspend fun initializeAgeForConnection(connection: io.r2dbc.spi.Connection) {
        try {
            // LOAD 'age' ensures the AGE library is loaded for this session
            // SET search_path ensures cypher() function is accessible without qualification
            val initSql = "LOAD 'age'; SET search_path = public, \"\$user\", ag_catalog;"
            Flux.from(connection.createStatement(initSql).execute())
                .flatMap { result -> Flux.from(result.rowsUpdated) }
                .asFlow()
                .toList()
        } catch (e: Exception) {
            // Log but don't fail - AGE might already be loaded via session_preload_libraries
            logger.debug("AGE initialization (may be redundant): {}", e.message)
        }
    }
}
