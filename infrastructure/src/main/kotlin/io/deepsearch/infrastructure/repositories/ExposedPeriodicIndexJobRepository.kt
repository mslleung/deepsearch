package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.PeriodicIndexJob
import io.deepsearch.domain.models.entities.PeriodicIndexJobState
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.repositories.IPeriodicIndexJobRepository
import io.deepsearch.infrastructure.database.PeriodicIndexJobTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedPeriodicIndexJobRepository(
    private val periodicIndexJobTable: PeriodicIndexJobTable,
    private val transactionService: ITransactionService
) : IPeriodicIndexJobRepository {

    override suspend fun create(job: PeriodicIndexJob): PeriodicIndexJob = transactionService.withTransaction {
        val id = periodicIndexJobTable.insert {
            it[userId] = job.userId.value
            it[baseUrl] = job.baseUrl
            it[maxUrlCount] = job.maxUrlCount
            it[sitemapUrl] = job.sitemapUrl
            it[processedCount] = job.processedCount
            it[state] = job.state.name
            it[createdAtMs] = job.createdAt.toEpochMilliseconds()
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
            it[version] = job.version
        }[periodicIndexJobTable.id]

        job.id = id
        job
    }

    override suspend fun update(job: PeriodicIndexJob): PeriodicIndexJob = transactionService.withTransaction {
        val affectedRows = periodicIndexJobTable.update({ 
            (periodicIndexJobTable.id eq (job.id ?: -1)) and (periodicIndexJobTable.version eq job.version) 
        }) {
            it[processedCount] = job.processedCount
            it[state] = job.state.name
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
            it[version] = job.version + 1
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("PeriodicIndexJob", job.id ?: -1, job.version)
        }
        
        job.version += 1
        job
    }

    override suspend fun findById(id: Long): PeriodicIndexJob? = transactionService.withTransaction {
        periodicIndexJobTable.selectAll()
            .where { periodicIndexJobTable.id eq id }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun findActiveByBaseUrl(baseUrl: String): PeriodicIndexJob? = transactionService.withTransaction {
        periodicIndexJobTable.selectAll()
            .where { (periodicIndexJobTable.baseUrl eq baseUrl) }
            .map { mapRow(it) }
            .toList()
            .firstOrNull { it.state == PeriodicIndexJobState.IN_PROGRESS }
    }

    override suspend fun listAll(state: PeriodicIndexJobState?): List<PeriodicIndexJob> = transactionService.withTransaction {
        val query = periodicIndexJobTable.selectAll()
        if (state == null) {
            query.map { mapRow(it) }.toList()
        } else {
            query.map { mapRow(it) }.toList().filter { it.state == state }
        }
    }

    override suspend fun listByUserId(userId: UserId, state: PeriodicIndexJobState?, offset: Int, limit: Int): List<PeriodicIndexJob> = transactionService.withTransaction {
        val query = if (state != null) {
            periodicIndexJobTable.selectAll()
                .where { (periodicIndexJobTable.userId eq userId.value) and (periodicIndexJobTable.state eq state.name) }
        } else {
            periodicIndexJobTable.selectAll()
                .where { periodicIndexJobTable.userId eq userId.value }
        }
        
        query
            .orderBy(periodicIndexJobTable.createdAtMs, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun countByUserId(userId: UserId, state: PeriodicIndexJobState?): Long = transactionService.withTransaction {
        val query = if (state != null) {
            periodicIndexJobTable.selectAll()
                .where { (periodicIndexJobTable.userId eq userId.value) and (periodicIndexJobTable.state eq state.name) }
        } else {
            periodicIndexJobTable.selectAll()
                .where { periodicIndexJobTable.userId eq userId.value }
        }
        
        query.count()
    }

    override suspend fun listByUserIdAndBaseUrl(userId: UserId, baseUrl: String, state: PeriodicIndexJobState?, offset: Int, limit: Int): List<PeriodicIndexJob> = transactionService.withTransaction {
        val query = if (state != null) {
            periodicIndexJobTable.selectAll()
                .where { 
                    (periodicIndexJobTable.userId eq userId.value) and 
                    (periodicIndexJobTable.baseUrl eq baseUrl) and 
                    (periodicIndexJobTable.state eq state.name) 
                }
        } else {
            periodicIndexJobTable.selectAll()
                .where { 
                    (periodicIndexJobTable.userId eq userId.value) and 
                    (periodicIndexJobTable.baseUrl eq baseUrl) 
                }
        }
        
        query
            .orderBy(periodicIndexJobTable.createdAtMs, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { mapRow(it) }
            .toList()
    }

    override suspend fun countByUserIdAndBaseUrl(userId: UserId, baseUrl: String, state: PeriodicIndexJobState?): Long = transactionService.withTransaction {
        val query = if (state != null) {
            periodicIndexJobTable.selectAll()
                .where { 
                    (periodicIndexJobTable.userId eq userId.value) and 
                    (periodicIndexJobTable.baseUrl eq baseUrl) and 
                    (periodicIndexJobTable.state eq state.name) 
                }
        } else {
            periodicIndexJobTable.selectAll()
                .where { 
                    (periodicIndexJobTable.userId eq userId.value) and 
                    (periodicIndexJobTable.baseUrl eq baseUrl) 
                }
        }
        
        query.count()
    }

    private fun mapRow(row: ResultRow): PeriodicIndexJob = PeriodicIndexJob(
        id = row[periodicIndexJobTable.id],
        userId = UserId(row[periodicIndexJobTable.userId]),
        baseUrl = row[periodicIndexJobTable.baseUrl],
        maxUrlCount = row[periodicIndexJobTable.maxUrlCount],
        sitemapUrl = row[periodicIndexJobTable.sitemapUrl],
        createdAt = Instant.fromEpochMilliseconds(row[periodicIndexJobTable.createdAtMs]),
        updatedAt = Instant.fromEpochMilliseconds(row[periodicIndexJobTable.updatedAtMs]),
        processedCount = row[periodicIndexJobTable.processedCount],
        state = PeriodicIndexJobState.valueOf(row[periodicIndexJobTable.state]),
        version = row[periodicIndexJobTable.version]
    )
}

