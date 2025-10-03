package io.deepsearch.application.services

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.models.entities.WebpageTable
import io.deepsearch.domain.repositories.IWebpageTableRepository
import kotlinx.serialization.json.Json
import java.security.MessageDigest

interface ITableIdentificationService {
    suspend fun identifyTables(input: TableIdentificationInput): List<TableIdentification>
}

class TableIdentificationService(
    private val tableIdentificationAgent: ITableIdentificationAgent,
    private val webpageTableRepository: IWebpageTableRepository
) : ITableIdentificationService {

    /**
     * Identifies tables in a webpage screenshot using an LLM agent.
     * Results are cached in the repository to avoid repeated calls with the same screenshot.
     */
    override suspend fun identifyTables(input: TableIdentificationInput): List<TableIdentification> {
        val bytesHash = MessageDigest.getInstance("SHA-256").digest(input.screenshotBytes)

        val existing = webpageTableRepository.findByHash(bytesHash)
        if (existing != null) {
            return Json.decodeFromString<List<TableIdentification>>(existing.tables)
        }

        val agentOutput = tableIdentificationAgent.generate(input)
        val tables = agentOutput.tables

        webpageTableRepository.upsert(
            WebpageTable(
                fullPageScreenshotHash = bytesHash,
                tables = Json.encodeToString(tables)
            )
        )

        return tables
    }
}

