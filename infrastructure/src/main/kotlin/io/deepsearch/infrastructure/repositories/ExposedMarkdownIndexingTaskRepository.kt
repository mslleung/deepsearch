package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.IndexingTaskStatus
import io.deepsearch.domain.models.entities.IndexingTaskType
import io.deepsearch.domain.models.entities.MarkdownIndexingTask
import io.deepsearch.domain.repositories.IMarkdownIndexingTaskRepository
import io.deepsearch.infrastructure.database.MarkdownIndexingTaskTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedMarkdownIndexingTaskRepository(
    private val table: MarkdownIndexingTaskTable,
    private val transactionService: ITransactionService
) : IMarkdownIndexingTaskRepository {

    override suspend fun create(task: MarkdownIndexingTask): MarkdownIndexingTask = transactionService.withTransaction {
        val id = table.insert {
            it[url] = task.url
            it[taskType] = task.taskType.name
            it[sessionId] = task.sessionId
            it[status] = task.status.name
            it[markdown] = task.markdown
            it[attempts] = task.attempts
            it[maxAttempts] = task.maxAttempts
            it[errorMessage] = task.errorMessage
            it[createdAtMs] = task.createdAt.toEpochMilliseconds()
            it[updatedAtMs] = task.updatedAt.toEpochMilliseconds()
            it[claimedAtMs] = task.claimedAt?.toEpochMilliseconds()
        }[table.id]

        task.copy(id = id)
    }

    override suspend fun createBatch(tasks: List<MarkdownIndexingTask>): List<MarkdownIndexingTask> = transactionService.withTransaction {
        if (tasks.isEmpty()) return@withTransaction emptyList()

        tasks.map { task ->
            val id = table.insert {
                it[url] = task.url
                it[taskType] = task.taskType.name
                it[sessionId] = task.sessionId
                it[status] = task.status.name
                it[markdown] = task.markdown
                it[attempts] = task.attempts
                it[maxAttempts] = task.maxAttempts
                it[errorMessage] = task.errorMessage
                it[createdAtMs] = task.createdAt.toEpochMilliseconds()
                it[updatedAtMs] = task.updatedAt.toEpochMilliseconds()
                it[claimedAtMs] = task.claimedAt?.toEpochMilliseconds()
            }[table.id]
            task.copy(id = id)
        }
    }

    override suspend fun claimPendingTask(taskType: IndexingTaskType): MarkdownIndexingTask? = transactionService.withTransaction {
        // Find a pending task
        val pendingTask = table.selectAll()
            .where { 
                (table.status eq IndexingTaskStatus.PENDING.name) and 
                (table.taskType eq taskType.name) 
            }
            .limit(1)
            .map { mapRow(it) }
            .firstOrNull()

        if (pendingTask == null) return@withTransaction null

        val taskId = pendingTask.id ?: return@withTransaction null
        val now = Clock.System.now()

        // Atomically update to IN_PROGRESS (only if still PENDING)
        val updated = table.update({
            (table.id eq taskId) and (table.status eq IndexingTaskStatus.PENDING.name)
        }) {
            it[status] = IndexingTaskStatus.IN_PROGRESS.name
            it[attempts] = pendingTask.attempts + 1
            it[claimedAtMs] = now.toEpochMilliseconds()
            it[updatedAtMs] = now.toEpochMilliseconds()
        }

        // If update failed (another worker claimed it), return null
        if (updated == 0) return@withTransaction null

        // Return the claimed task
        pendingTask.copy(
            status = IndexingTaskStatus.IN_PROGRESS,
            attempts = pendingTask.attempts + 1,
            claimedAt = now,
            updatedAt = now
        )
    }

    override suspend fun markCompleted(taskId: Long): Unit = transactionService.withTransaction {
        val now = Clock.System.now().toEpochMilliseconds()
        table.update({ table.id eq taskId }) {
            it[status] = IndexingTaskStatus.COMPLETED.name
            it[updatedAtMs] = now
        }
    }

    override suspend fun markFailed(taskId: Long, errorMessage: String): Unit = transactionService.withTransaction {
        val now = Clock.System.now().toEpochMilliseconds()
        table.update({ table.id eq taskId }) {
            it[status] = IndexingTaskStatus.FAILED.name
            it[table.errorMessage] = errorMessage
            it[updatedAtMs] = now
        }
    }

    override suspend fun retryOrFail(taskId: Long, errorMessage: String): Unit = transactionService.withTransaction {
        // Fetch current task to check attempts
        val task = table.selectAll()
            .where { table.id eq taskId }
            .map { mapRow(it) }
            .singleOrNull() ?: return@withTransaction

        val now = Clock.System.now().toEpochMilliseconds()

        if (task.canRetry()) {
            // Reset to PENDING for retry
            table.update({ table.id eq taskId }) {
                it[status] = IndexingTaskStatus.PENDING.name
                it[table.errorMessage] = errorMessage
                it[claimedAtMs] = null
                it[updatedAtMs] = now
            }
        } else {
            // Mark as permanently failed
            table.update({ table.id eq taskId }) {
                it[status] = IndexingTaskStatus.FAILED.name
                it[table.errorMessage] = errorMessage
                it[updatedAtMs] = now
            }
        }
    }

    override suspend fun countPending(taskType: IndexingTaskType): Long = transactionService.withTransaction {
        table.selectAll()
            .where { 
                (table.status eq IndexingTaskStatus.PENDING.name) and 
                (table.taskType eq taskType.name) 
            }
            .count()
    }

    override suspend fun countAllPending(): Long = transactionService.withTransaction {
        table.selectAll()
            .where { table.status eq IndexingTaskStatus.PENDING.name }
            .count()
    }

    override suspend fun findById(id: Long): MarkdownIndexingTask? = transactionService.withTransaction {
        table.selectAll()
            .where { table.id eq id }
            .map { mapRow(it) }
            .singleOrNull()
    }

    override suspend fun deleteCompletedOlderThan(olderThanMs: Long): Int = transactionService.withTransaction {
        val cutoffTime = Clock.System.now().toEpochMilliseconds() - olderThanMs
        table.deleteWhere { 
            (status eq IndexingTaskStatus.COMPLETED.name) and (updatedAtMs less cutoffTime)
        }
    }

    override suspend fun resetStaleTasks(staleTresholdMs: Long): Int = transactionService.withTransaction {
        val cutoffTime = Clock.System.now().toEpochMilliseconds() - staleTresholdMs
        val now = Clock.System.now().toEpochMilliseconds()

        // Find stale in-progress tasks and reset them to pending
        table.update({
            (table.status eq IndexingTaskStatus.IN_PROGRESS.name) and 
            (table.claimedAtMs lessEq cutoffTime)
        }) {
            it[status] = IndexingTaskStatus.PENDING.name
            it[claimedAtMs] = null
            it[updatedAtMs] = now
        }
    }

    private fun mapRow(row: ResultRow): MarkdownIndexingTask = MarkdownIndexingTask(
        id = row[table.id],
        url = row[table.url],
        taskType = IndexingTaskType.valueOf(row[table.taskType]),
        sessionId = row[table.sessionId],
        status = IndexingTaskStatus.valueOf(row[table.status]),
        markdown = row[table.markdown],
        attempts = row[table.attempts],
        maxAttempts = row[table.maxAttempts],
        errorMessage = row[table.errorMessage],
        createdAt = Instant.fromEpochMilliseconds(row[table.createdAtMs]),
        updatedAt = Instant.fromEpochMilliseconds(row[table.updatedAtMs]),
        claimedAt = row[table.claimedAtMs]?.let { Instant.fromEpochMilliseconds(it) }
    )
}

