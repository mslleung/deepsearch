package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJob
import io.deepsearch.domain.models.entities.BatchPeriodicIndexJobState
import io.deepsearch.domain.models.entities.BatchPipelineMode
import io.deepsearch.domain.models.valueobjects.OcrLanguage
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IBatchPeriodicIndexJobRepository
import io.deepsearch.infrastructure.database.BatchPeriodicIndexJobTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedBatchPeriodicIndexJobRepository(
    private val table: BatchPeriodicIndexJobTable,
    private val transactionService: ITransactionService
) : IBatchPeriodicIndexJobRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(job: BatchPeriodicIndexJob): BatchPeriodicIndexJob = transactionService.withTransaction {
        val id = table.insert {
            it[userId] = job.userId.value
            it[baseUrl] = job.baseUrl
            it[maxUrlCount] = job.maxUrlCount
            it[sitemapUrl] = job.sitemapUrl
            it[state] = job.state.name
            it[pipelineMode] = job.pipelineMode.name
            it[createdAtMs] = job.createdAt.toEpochMilliseconds()
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
            it[version] = job.version
            it[languagePattern] = job.languagePattern
            it[ocrLanguage] = job.ocrLanguage.code
            it[batchJobIds] = serializeBatchJobIds(job.batchJobIds)
            it[batchJobCreatedAtMs] = job.batchJobCreatedAt?.toEpochMilliseconds()
            it[lastResumedAtMs] = job.lastResumedAt?.toEpochMilliseconds()
            it[errorMessage] = job.errorMessage
            it[urlsProcessed] = job.urlsProcessed
            it[urlsContentProcessed] = job.urlsContentProcessed
            it[urlsFinalProcessed] = job.urlsFinalProcessed
            it[urlsCached] = job.urlsCached
        }[table.id]

        job.id = id
        job
    }

    override suspend fun update(job: BatchPeriodicIndexJob): Unit = transactionService.withTransaction {
        val affectedRows = table.update({ 
            (table.id eq (job.id ?: -1)) and (table.version eq job.version) 
        }) {
            it[state] = job.state.name
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
            it[version] = job.version + 1
            it[batchJobIds] = serializeBatchJobIds(job.batchJobIds)
            it[batchJobCreatedAtMs] = job.batchJobCreatedAt?.toEpochMilliseconds()
            it[lastResumedAtMs] = job.lastResumedAt?.toEpochMilliseconds()
            it[errorMessage] = job.errorMessage
            it[urlsProcessed] = job.urlsProcessed
            it[urlsContentProcessed] = job.urlsContentProcessed
            it[urlsFinalProcessed] = job.urlsFinalProcessed
            it[urlsCached] = job.urlsCached
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("BatchPeriodicIndexJob", job.id ?: -1, job.version)
        }
        
        job.version += 1
    }

    override suspend fun findById(id: Long): BatchPeriodicIndexJob? = transactionService.withTransaction {
        table.selectAll()
            .where { table.id eq id }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun findByUserId(userId: UserId): List<BatchPeriodicIndexJob> = transactionService.withTransaction {
        table.selectAll()
            .where { table.userId eq userId.value }
            .orderBy(table.createdAtMs, SortOrder.DESC)
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun findByState(state: BatchPeriodicIndexJobState): List<BatchPeriodicIndexJob> = transactionService.withTransaction {
        table.selectAll()
            .where { table.state eq state.name }
            .orderBy(table.createdAtMs, SortOrder.DESC)
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun findActiveJobs(): List<BatchPeriodicIndexJob> = transactionService.withTransaction {
        val terminalStates = listOf(
            BatchPeriodicIndexJobState.COMPLETED.name,
            BatchPeriodicIndexJobState.FAILED.name,
            BatchPeriodicIndexJobState.STOPPED.name
        )
        
        table.selectAll()
            .map { mapRow(it) }
            .toList()
            .filter { it.state.name !in terminalStates }
    }

    override suspend fun listAll(state: BatchPeriodicIndexJobState?): List<BatchPeriodicIndexJob> = transactionService.withTransaction {
        val query = table.selectAll()
            .orderBy(table.createdAtMs, SortOrder.DESC)
        
        if (state == null) {
            query.map { mapRow(it) }.toList()
        } else {
            query.map { mapRow(it) }.toList().filter { it.state == state }
        }
    }

    override suspend fun findLastCompletedByUserIdAndBaseUrl(userId: UserId, baseUrl: String): BatchPeriodicIndexJob? = transactionService.withTransaction {
        table.selectAll()
            .where { 
                (table.userId eq userId.value) and 
                (table.baseUrl eq baseUrl) and 
                (table.state eq BatchPeriodicIndexJobState.COMPLETED.name) 
            }
            .orderBy(table.createdAtMs, SortOrder.DESC)
            .limit(1)
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun delete(id: Long): Unit = transactionService.withTransaction {
        table.deleteWhere { table.id eq id }
    }

    private fun serializeBatchJobIds(ids: List<String>): String? {
        return if (ids.isEmpty()) null else json.encodeToString(ids)
    }

    private fun deserializeBatchJobIds(jsonStr: String?): List<String> {
        return if (jsonStr.isNullOrBlank()) emptyList() else json.decodeFromString(jsonStr)
    }

    private fun mapRow(row: ResultRow): BatchPeriodicIndexJob = BatchPeriodicIndexJob(
        id = row[table.id],
        userId = UserId(row[table.userId]),
        baseUrl = row[table.baseUrl],
        maxUrlCount = row[table.maxUrlCount],
        sitemapUrl = row[table.sitemapUrl],
        createdAt = Instant.fromEpochMilliseconds(row[table.createdAtMs]),
        updatedAt = Instant.fromEpochMilliseconds(row[table.updatedAtMs]),
        state = BatchPeriodicIndexJobState.valueOf(row[table.state]),
        pipelineMode = runCatching { BatchPipelineMode.valueOf(row[table.pipelineMode]) }
            .getOrDefault(BatchPipelineMode.LIGHTWEIGHT),
        version = row[table.version],
        languagePattern = row[table.languagePattern],
        ocrLanguage = OcrLanguage.fromCodeOrDefault(row[table.ocrLanguage]),
        batchJobIds = deserializeBatchJobIds(row[table.batchJobIds]),
        batchJobCreatedAt = row[table.batchJobCreatedAtMs]?.let { Instant.fromEpochMilliseconds(it) },
        lastResumedAt = row[table.lastResumedAtMs]?.let { Instant.fromEpochMilliseconds(it) },
        errorMessage = row[table.errorMessage],
        urlsProcessed = row[table.urlsProcessed],
        urlsContentProcessed = row[table.urlsContentProcessed],
        urlsFinalProcessed = row[table.urlsFinalProcessed],
        urlsCached = row[table.urlsCached]
    )
}
