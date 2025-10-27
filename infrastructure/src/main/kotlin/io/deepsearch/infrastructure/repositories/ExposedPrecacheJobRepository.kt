package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.PrecacheJob
import io.deepsearch.domain.models.entities.PrecacheJobState
import io.deepsearch.domain.repositories.IPrecacheJobRepository
import io.deepsearch.infrastructure.database.PrecacheJobTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedPrecacheJobRepository : IPrecacheJobRepository {

    override suspend fun create(job: PrecacheJob): PrecacheJob = suspendTransaction {
        val id = PrecacheJobTable.insert {
            it[baseUrl] = job.baseUrl
            it[maxUrlCount] = job.maxUrlCount
            it[processedCount] = job.processedCount
            it[state] = job.state.name
            it[createdAtMs] = job.createdAt.toEpochMilliseconds()
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
        }[PrecacheJobTable.id]

        job.id = id
        job
    }

    override suspend fun update(job: PrecacheJob): PrecacheJob = suspendTransaction {
        PrecacheJobTable.update({ PrecacheJobTable.id eq (job.id ?: -1) }) {
            it[processedCount] = job.processedCount
            it[state] = job.state.name
            it[updatedAtMs] = job.updatedAt.toEpochMilliseconds()
        }
        job
    }

    override suspend fun findById(id: Long): PrecacheJob? = suspendTransaction {
        PrecacheJobTable.selectAll()
            .where { PrecacheJobTable.id eq id }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun findActiveByBaseUrl(baseUrl: String): PrecacheJob? = suspendTransaction {
        PrecacheJobTable.selectAll()
            .where { (PrecacheJobTable.baseUrl eq baseUrl) }
            .map { mapRow(it) }
            .toList()
            .firstOrNull { it.state == PrecacheJobState.IN_PROGRESS }
    }

    override suspend fun listAll(state: PrecacheJobState?): List<PrecacheJob> = suspendTransaction {
        val query = PrecacheJobTable.selectAll()
        if (state == null) {
            query.map { mapRow(it) }.toList()
        } else {
            query.map { mapRow(it) }.toList().filter { it.state == state }
        }
    }

    private fun mapRow(row: ResultRow): PrecacheJob = PrecacheJob(
        id = row[PrecacheJobTable.id],
        baseUrl = row[PrecacheJobTable.baseUrl],
        maxUrlCount = row[PrecacheJobTable.maxUrlCount],
        createdAt = Instant.fromEpochMilliseconds(row[PrecacheJobTable.createdAtMs]),
        updatedAt = Instant.fromEpochMilliseconds(row[PrecacheJobTable.updatedAtMs]),
        processedCount = row[PrecacheJobTable.processedCount],
        state = PrecacheJobState.valueOf(row[PrecacheJobTable.state])
    )
}
