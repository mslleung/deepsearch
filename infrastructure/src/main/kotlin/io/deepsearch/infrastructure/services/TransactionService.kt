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
        return withContext(dispatchProvider.io) {
            suspendTransaction(databaseConfigurationService.getDatabase()) {
                block()
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
            val timeoutMs = timeoutSeconds * 1000L
            
            withTimeoutOrNull(timeoutMs) {
                val connection = Flux.from(pool.create()).awaitFirstOrNull()
                    ?: throw IllegalStateException("Failed to acquire database connection")
                
                try {
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
            } ?: run {
                logger.warn("Raw SQL query timed out after {} seconds", timeoutSeconds)
                emptyList()
            }
        }
    }
}
