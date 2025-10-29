package io.deepsearch.application.utils

import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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
suspend fun <T> withTransaction(block: suspend () -> T): T {
    return suspendTransaction {
        block()
    }
}

