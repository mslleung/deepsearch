package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.models.entities.AgenticNavIteration
import io.deepsearch.domain.models.entities.ScreenshotRecord
import io.deepsearch.domain.models.entities.ScreenshotType
import io.deepsearch.domain.models.valueobjects.AgenticNavIterationId
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IAgenticNavIterationRepository
import io.deepsearch.infrastructure.database.AgenticNavIterationTable
import io.deepsearch.infrastructure.database.AgenticNavScreenshotTable
import io.deepsearch.infrastructure.services.ITransactionService
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll

class ExposedAgenticNavIterationRepository(
    private val iterationTable: AgenticNavIterationTable,
    private val screenshotTable: AgenticNavScreenshotTable,
    private val transactionService: ITransactionService
) : IAgenticNavIterationRepository {

    override suspend fun save(iteration: AgenticNavIteration): AgenticNavIteration = transactionService.withTransaction {
        val generatedId = iterationTable.insert {
            it[sessionId] = iteration.sessionId.toStorageString()
            it[url] = iteration.url
            it[this.iteration] = iteration.iteration
            it[observation] = iteration.observation
            it[decision] = iteration.decision
            it[actionsJson] = iteration.actionsJson
            it[durationMs] = iteration.durationMs
            it[createdAtEpochMs] = iteration.createdAtEpochMs
        }[iterationTable.id]

        for (screenshot in iteration.screenshots) {
            insertScreenshot(generatedId, screenshot)
        }

        iteration.withId(AgenticNavIterationId(generatedId))
    }

    override suspend fun addScreenshot(iterationId: AgenticNavIterationId, screenshot: ScreenshotRecord): Unit = transactionService.withTransaction {
        insertScreenshot(iterationId.value, screenshot)
    }

    override suspend fun findBySessionId(sessionId: SessionId): List<AgenticNavIteration> = transactionService.withTransaction {
        val storageString = sessionId.toStorageString()
        val iterations = iterationTable.selectAll()
            .where { iterationTable.sessionId eq storageString }
            .orderBy(iterationTable.iteration, SortOrder.ASC)
            .toList()

        val iterationIds = iterations.map { it[iterationTable.id] }
        val screenshotsByIteration = loadScreenshotsByIterationIds(iterationIds)

        iterations.map { row -> mapRowToEntity(row, screenshotsByIteration[row[iterationTable.id]] ?: emptyList()) }
    }

    override suspend fun findBySessionIdAndUrl(sessionId: SessionId, url: String): List<AgenticNavIteration> = transactionService.withTransaction {
        val storageString = sessionId.toStorageString()
        val iterations = iterationTable.selectAll()
            .where { (iterationTable.sessionId eq storageString) and (iterationTable.url eq url) }
            .orderBy(iterationTable.iteration, SortOrder.ASC)
            .toList()

        val iterationIds = iterations.map { it[iterationTable.id] }
        val screenshotsByIteration = loadScreenshotsByIterationIds(iterationIds)

        iterations.map { row -> mapRowToEntity(row, screenshotsByIteration[row[iterationTable.id]] ?: emptyList()) }
    }

    private suspend fun loadScreenshotsByIterationIds(iterationIds: List<Long>): Map<Long, List<ScreenshotRecord>> {
        if (iterationIds.isEmpty()) return emptyMap()

        return screenshotTable.selectAll()
            .where { screenshotTable.iterationId inList iterationIds }
            .toList()
            .groupBy(
                keySelector = { it[screenshotTable.iterationId] },
                valueTransform = { mapRowToScreenshot(it) }
            )
    }

    private suspend fun insertScreenshot(iterationId: Long, screenshot: ScreenshotRecord) {
        screenshotTable.insert {
            it[screenshotTable.iterationId] = iterationId
            it[screenshotTable.screenshotType] = screenshot.type.name
            it[screenshotTable.gcsPath] = screenshot.gcsPath
            it[screenshotTable.regionIndex] = screenshot.regionIndex
            it[screenshotTable.description] = screenshot.description
        }
    }

    private fun mapRowToEntity(row: ResultRow, screenshots: List<ScreenshotRecord>): AgenticNavIteration =
        AgenticNavIteration(
            id = AgenticNavIterationId(row[iterationTable.id]),
            sessionId = SessionId.fromStorageString(row[iterationTable.sessionId]),
            url = row[iterationTable.url],
            iteration = row[iterationTable.iteration],
            observation = row[iterationTable.observation],
            decision = row[iterationTable.decision],
            actionsJson = row[iterationTable.actionsJson],
            screenshots = screenshots,
            durationMs = row[iterationTable.durationMs],
            createdAtEpochMs = row[iterationTable.createdAtEpochMs]
        )

    private fun mapRowToScreenshot(row: ResultRow): ScreenshotRecord =
        ScreenshotRecord(
            type = ScreenshotType.valueOf(row[screenshotTable.screenshotType]),
            gcsPath = row[screenshotTable.gcsPath],
            regionIndex = row[screenshotTable.regionIndex],
            description = row[screenshotTable.description]
        )
}
