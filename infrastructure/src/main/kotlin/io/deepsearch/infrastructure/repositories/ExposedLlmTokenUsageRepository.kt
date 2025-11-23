package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.LlmTokenUsage
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
 */
@OptIn(ExperimentalTime::class)
class ExposedLlmTokenUsageRepository(
    private val llmTokenUsageTable: LlmTokenUsageTable,
    private val transactionService: ITransactionService
) : ILlmTokenUsageRepository {

    override suspend fun save(usage: LlmTokenUsage): LlmTokenUsage = transactionService.withTransaction {
        llmTokenUsageTable.insert {
            it[id] = usage.id
            it[querySessionId] = usage.querySessionId
            it[agentName] = usage.agentName
            it[modelName] = usage.modelName
            it[promptTokens] = usage.promptTokens
            it[outputTokens] = usage.outputTokens
            it[totalTokens] = usage.totalTokens
            it[createdAtEpochMs] = usage.createdAt.toEpochMilliseconds()
        }
        
        usage
    }

    override suspend fun findBySessionId(sessionId: String): List<LlmTokenUsage> = transactionService.withTransaction {
        llmTokenUsageTable.selectAll()
            .where { llmTokenUsageTable.querySessionId eq sessionId }
            .map { mapRowToLlmTokenUsage(it) }
            .toList()
    }

    override suspend fun getTotalTokensBySessionId(sessionId: String): TokenUsageSummary = transactionService.withTransaction {
        val records = llmTokenUsageTable.selectAll()
            .where { llmTokenUsageTable.querySessionId eq sessionId }
            .map { it }
            .toList()
        
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
        return LlmTokenUsage(
            id = row[llmTokenUsageTable.id],
            querySessionId = row[llmTokenUsageTable.querySessionId],
            agentName = row[llmTokenUsageTable.agentName],
            modelName = row[llmTokenUsageTable.modelName],
            promptTokens = row[llmTokenUsageTable.promptTokens],
            outputTokens = row[llmTokenUsageTable.outputTokens],
            totalTokens = row[llmTokenUsageTable.totalTokens],
            createdAt = Instant.fromEpochMilliseconds(row[llmTokenUsageTable.createdAtEpochMs])
        )
    }
}

