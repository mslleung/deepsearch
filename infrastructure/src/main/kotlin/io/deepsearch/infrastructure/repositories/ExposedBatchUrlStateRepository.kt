package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.BatchUrlProcessingStage
import io.deepsearch.domain.models.entities.BatchUrlState
import io.deepsearch.domain.repositories.BatchUrlStageCounts
import io.deepsearch.domain.repositories.IBatchUrlStateRepository
import io.deepsearch.infrastructure.database.BatchUrlStateTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedBatchUrlStateRepository(
    private val table: BatchUrlStateTable,
    private val transactionService: ITransactionService
) : IBatchUrlStateRepository {

    override suspend fun create(urlState: BatchUrlState): BatchUrlState = transactionService.withTransaction {
        val id = table.insert {
            it[jobId] = urlState.jobId
            it[url] = urlState.url
            it[createdAtMs] = urlState.createdAt.toEpochMilliseconds()
            it[updatedAtMs] = urlState.updatedAt.toEpochMilliseconds()
            it[version] = urlState.version
            it[stage] = urlState.stage.name
            it[errorMessage] = urlState.errorMessage
            it[snapshotData] = urlState.snapshotData
            it[title] = urlState.title
            it[description] = urlState.description
        }[table.id]

        urlState.id = id
        urlState
    }

    override suspend fun batchCreate(urlStates: List<BatchUrlState>): Unit = transactionService.withTransaction {
        if (urlStates.isEmpty()) return@withTransaction
        
        // Use individual inserts in a transaction for simplicity
        urlStates.forEach { urlState ->
            val id = table.insert {
                it[jobId] = urlState.jobId
                it[url] = urlState.url
                it[createdAtMs] = urlState.createdAt.toEpochMilliseconds()
                it[updatedAtMs] = urlState.updatedAt.toEpochMilliseconds()
                it[version] = urlState.version
                it[stage] = urlState.stage.name
                it[errorMessage] = urlState.errorMessage
                it[snapshotData] = urlState.snapshotData
                it[title] = urlState.title
                it[description] = urlState.description
            }[table.id]
            urlState.id = id
        }
    }

    override suspend fun update(urlState: BatchUrlState): Unit = transactionService.withTransaction {
        val affectedRows = table.update({ 
            (table.id eq (urlState.id ?: -1)) and (table.version eq urlState.version) 
        }) {
            it[updatedAtMs] = urlState.updatedAt.toEpochMilliseconds()
            it[version] = urlState.version + 1
            it[stage] = urlState.stage.name
            it[errorMessage] = urlState.errorMessage
            it[snapshotData] = urlState.snapshotData
            it[title] = urlState.title
            it[description] = urlState.description
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("BatchUrlState", urlState.id ?: -1, urlState.version)
        }
        
        urlState.version += 1
    }

    override suspend fun batchUpdate(urlStates: List<BatchUrlState>): Unit = transactionService.withTransaction {
        // Update each one individually to maintain optimistic locking
        urlStates.forEach { urlState ->
            update(urlState)
        }
    }

    override suspend fun findById(id: Long): BatchUrlState? = transactionService.withTransaction {
        table.selectAll()
            .where { table.id eq id }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun findByJobIdAndUrl(jobId: Long, url: String): BatchUrlState? = transactionService.withTransaction {
        table.selectAll()
            .where { (table.jobId eq jobId) and (table.url eq url) }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun findByJobId(jobId: Long): List<BatchUrlState> = transactionService.withTransaction {
        table.selectAll()
            .where { table.jobId eq jobId }
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun findByJobIdAndStage(
        jobId: Long,
        stage: BatchUrlProcessingStage
    ): List<BatchUrlState> = transactionService.withTransaction {
        table.selectAll()
            .where { (table.jobId eq jobId) and (table.stage eq stage.name) }
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun findNeedingContentLlmProcessing(jobId: Long): List<BatchUrlState> = transactionService.withTransaction {
        table.selectAll()
            .where { 
                (table.jobId eq jobId) and 
                (table.stage eq BatchUrlProcessingStage.EXTRACTED.name) and 
                table.errorMessage.isNull()
            }
            .orderBy(table.id to SortOrder.ASC)
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun findNeedingFinalLlmProcessing(jobId: Long): List<BatchUrlState> = transactionService.withTransaction {
        table.selectAll()
            .where { 
                (table.jobId eq jobId) and 
                (table.stage eq BatchUrlProcessingStage.CONTENT_LLM_DONE.name) and 
                table.errorMessage.isNull()
            }
            .orderBy(table.id to SortOrder.ASC)
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun findNeedingCaching(jobId: Long): List<BatchUrlState> = transactionService.withTransaction {
        table.selectAll()
            .where { 
                (table.jobId eq jobId) and 
                (table.stage eq BatchUrlProcessingStage.FINAL_LLM_DONE.name) and 
                table.errorMessage.isNull()
            }
            .orderBy(table.id to SortOrder.ASC)
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun countByStage(jobId: Long): BatchUrlStageCounts = transactionService.withTransaction {
        val allStates = table.selectAll()
            .where { table.jobId eq jobId }
            .map { mapRow(it) }
            .toList()
        
        BatchUrlStageCounts(
            total = allStates.size,
            pending = allStates.count { it.stage == BatchUrlProcessingStage.PENDING },
            extracted = allStates.count { it.stage == BatchUrlProcessingStage.EXTRACTED },
            contentLlmDone = allStates.count { it.stage == BatchUrlProcessingStage.CONTENT_LLM_DONE },
            finalLlmDone = allStates.count { it.stage == BatchUrlProcessingStage.FINAL_LLM_DONE },
            cached = allStates.count { it.stage == BatchUrlProcessingStage.CACHED },
            failed = allStates.count { it.errorMessage != null }
        )
    }

    override suspend fun deleteByJobId(jobId: Long): Unit = transactionService.withTransaction {
        table.deleteWhere { table.jobId eq jobId }
    }

    override suspend fun existsByJobIdAndUrl(jobId: Long, url: String): Boolean = transactionService.withTransaction {
        table.selectAll()
            .where { (table.jobId eq jobId) and (table.url eq url) }
            .count() > 0
    }

    private fun mapRow(row: ResultRow): BatchUrlState = BatchUrlState(
        id = row[table.id],
        jobId = row[table.jobId],
        url = row[table.url],
        createdAt = Instant.fromEpochMilliseconds(row[table.createdAtMs]),
        updatedAt = Instant.fromEpochMilliseconds(row[table.updatedAtMs]),
        version = row[table.version],
        stage = BatchUrlProcessingStage.valueOf(row[table.stage]),
        errorMessage = row[table.errorMessage],
        snapshotData = row[table.snapshotData],
        title = row[table.title],
        description = row[table.description]
    )
}
