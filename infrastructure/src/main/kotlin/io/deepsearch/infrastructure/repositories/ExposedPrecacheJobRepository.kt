package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.PrecacheJob
import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.domain.repositories.IPrecacheJobRepository
import io.deepsearch.infrastructure.database.PrecacheJobTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

import io.deepsearch.domain.models.valueobjects.UserId

@OptIn(ExperimentalTime::class)
class ExposedPrecacheJobRepository(
    private val precacheJobTable: PrecacheJobTable,
    private val transactionService: ITransactionService
) : IPrecacheJobRepository {

    override suspend fun create(job: PrecacheJob): PrecacheJob = transactionService.withTransaction {
        val id = precacheJobTable.insert {
            it[userId] = job.userId?.value
            it[baseUrl] = job.baseUrl
            it[maxUrlCount] = job.maxUrlCount
            it[sitemapUrl] = job.sitemapUrl
            it[processedCount] = job.processedCount
            it[state] = job.state.name
            it[createdAtMs] = job.createdAt.toEpochMilliseconds()
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
            it[version] = job.version
        }[precacheJobTable.id]

        job.id = id
        job
    }

    override suspend fun update(job: PrecacheJob): PrecacheJob = transactionService.withTransaction {
        val affectedRows = precacheJobTable.update({ 
            (precacheJobTable.id eq (job.id ?: -1)) and (precacheJobTable.version eq job.version) 
        }) {
            it[processedCount] = job.processedCount
            it[state] = job.state.name
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
            it[version] = job.version + 1
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("PrecacheJob", job.id ?: -1, job.version)
        }
        
        job.version += 1
        job
    }

    override suspend fun findById(id: Long): PrecacheJob? = transactionService.withTransaction {
        precacheJobTable.selectAll()
            .where { precacheJobTable.id eq id }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun findActiveByBaseUrl(baseUrl: String): PrecacheJob? = transactionService.withTransaction {
        precacheJobTable.selectAll()
            .where { (precacheJobTable.baseUrl eq baseUrl) }
            .map { mapRow(it) }
            .toList()
            .firstOrNull { it.state == PrecacheJobState.IN_PROGRESS }
    }

    override suspend fun listAll(state: PrecacheJobState?): List<PrecacheJob> = transactionService.withTransaction {
        val query = precacheJobTable.selectAll()
        if (state == null) {
            query.map { mapRow(it) }.toList()
        } else {
            query.map { mapRow(it) }.toList().filter { it.state == state }
        }
    }

    private fun mapRow(row: ResultRow): PrecacheJob = PrecacheJob(
        id = row[precacheJobTable.id],
        userId = row[precacheJobTable.userId]?.let { UserId(it) },
        baseUrl = row[precacheJobTable.baseUrl],
        maxUrlCount = row[precacheJobTable.maxUrlCount],
        sitemapUrl = row[precacheJobTable.sitemapUrl],
        createdAt = Instant.fromEpochMilliseconds(row[precacheJobTable.createdAtMs]),
        updatedAt = Instant.fromEpochMilliseconds(row[precacheJobTable.updatedAtMs]),
        processedCount = row[precacheJobTable.processedCount],
        state = PrecacheJobState.valueOf(row[precacheJobTable.state]),
        version = row[precacheJobTable.version]
    )
}
