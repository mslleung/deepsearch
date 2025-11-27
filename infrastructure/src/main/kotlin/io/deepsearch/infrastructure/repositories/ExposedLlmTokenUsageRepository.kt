package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.LlmTokenUsage
import io.deepsearch.domain.models.valueobjects.PeriodicIndexSessionId
import io.deepsearch.domain.models.valueobjects.QuerySessionId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.ILlmTokenUsageRepository
import io.deepsearch.domain.repositories.TokenUsageSummary
import io.deepsearch.infrastructure.database.LlmTokenUsageTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Exposed ORM implementation of LlmTokenUsageRepository.
 * 
 * Handles both query sessions and periodic index jobs:
 * - For QuerySessionId: stores in query_session_id column
 * - For PeriodicIndexSessionId: stores in periodic_index_job_id column
 */
@OptIn(ExperimentalTime::class)
class ExposedLlmTokenUsageRepository(
    private val llmTokenUsageTable: LlmTokenUsageTable,
    private val transactionService: ITransactionService
) : ILlmTokenUsageRepository {

    override suspend fun save(usage: LlmTokenUsage): LlmTokenUsage = transactionService.withTransaction {
        llmTokenUsageTable.insert {
            it[id] = usage.id
            // Store session ID in appropriate column based on type
            when (val sessionId = usage.sessionId) {
                is QuerySessionId -> {
                    it[querySessionId] = sessionId.value
                    it[periodicIndexJobId] = null
                }
                is PeriodicIndexSessionId -> {
                    it[querySessionId] = null
                    it[periodicIndexJobId] = sessionId.jobId
                }
                null -> {
                    it[querySessionId] = null
                    it[periodicIndexJobId] = null
                }
            }
            it[agentName] = usage.agentName
            it[modelName] = usage.modelName
            it[promptTokens] = usage.promptTokens
            it[outputTokens] = usage.outputTokens
            it[totalTokens] = usage.totalTokens
            it[createdAtEpochMs] = usage.createdAt.toEpochMilliseconds()
        }
        
        usage
    }

    override suspend fun findBySessionId(sessionId: SessionId): List<LlmTokenUsage> = transactionService.withTransaction {
        when (sessionId) {
            is QuerySessionId -> {
                llmTokenUsageTable.selectAll()
                    .where { llmTokenUsageTable.querySessionId eq sessionId.value }
                    .map { mapRowToLlmTokenUsage(it) }
                    .toList()
            }
            is PeriodicIndexSessionId -> {
                llmTokenUsageTable.selectAll()
                    .where { llmTokenUsageTable.periodicIndexJobId eq sessionId.jobId }
                    .map { mapRowToLlmTokenUsage(it) }
                    .toList()
            }
        }
    }

    override suspend fun getTotalTokensBySessionId(sessionId: SessionId): TokenUsageSummary = transactionService.withTransaction {
        val records = when (sessionId) {
            is QuerySessionId -> {
                llmTokenUsageTable.selectAll()
                    .where { llmTokenUsageTable.querySessionId eq sessionId.value }
                    .map { it }
                    .toList()
            }
            is PeriodicIndexSessionId -> {
                llmTokenUsageTable.selectAll()
                    .where { llmTokenUsageTable.periodicIndexJobId eq sessionId.jobId }
                    .map { it }
                    .toList()
            }
        }
        
        val totalPromptTokens = records.sumOf { it[llmTokenUsageTable.promptTokens].toLong() }
        val totalOutputTokens = records.sumOf { it[llmTokenUsageTable.outputTokens].toLong() }
        val totalTokens = records.sumOf { it[llmTokenUsageTable.totalTokens].toLong() }
        val callCount = records.size.toLong()
        
        TokenUsageSummary(
            totalPromptTokens = totalPromptTokens,
            totalOutputTokens = totalOutputTokens,
            totalTokens = totalTokens,
            callCount = callCount
        )
    }

    private fun mapRowToLlmTokenUsage(row: ResultRow): LlmTokenUsage {
        // Reconstruct SessionId from either column
        val sessionId: SessionId? = when {
            row[llmTokenUsageTable.querySessionId] != null -> 
                QuerySessionId(row[llmTokenUsageTable.querySessionId]!!)
            row[llmTokenUsageTable.periodicIndexJobId] != null -> 
                PeriodicIndexSessionId(row[llmTokenUsageTable.periodicIndexJobId]!!)
            else -> null
        }
        
        return LlmTokenUsage(
            id = row[llmTokenUsageTable.id],
            sessionId = sessionId,
            agentName = row[llmTokenUsageTable.agentName],
            modelName = row[llmTokenUsageTable.modelName],
            promptTokens = row[llmTokenUsageTable.promptTokens],
            outputTokens = row[llmTokenUsageTable.outputTokens],
            totalTokens = row[llmTokenUsageTable.totalTokens],
            createdAt = Instant.fromEpochMilliseconds(row[llmTokenUsageTable.createdAtEpochMs])
        )
    }
}

