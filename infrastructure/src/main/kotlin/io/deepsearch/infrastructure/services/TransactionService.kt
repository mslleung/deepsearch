package io.deepsearch.infrastructure.services

import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

interface ITransactionService {
    suspend fun <T> withTransaction(block: suspend R2dbcTransaction.() -> T): T
}

class TransactionService(
    private val databaseConfigurationService: IDatabaseConfigurationService
): ITransactionService {

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
        return suspendTransaction(databaseConfigurationService.getDatabase()) {
            block()
        }
    }
}
