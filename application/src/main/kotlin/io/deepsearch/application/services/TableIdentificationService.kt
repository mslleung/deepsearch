package io.deepsearch.application.services

import io.deepsearch.domain.agents.ITableIdentificationAgent
import io.deepsearch.domain.agents.TableIdentification
import io.deepsearch.domain.agents.TableIdentificationInput
import io.deepsearch.domain.browser.IBrowserPage
import io.deepsearch.domain.models.entities.WebpageTable
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IWebpageTableRepository
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

interface ITableIdentificationService {
    /**
     * Identifies tables in webpage HTML using an LLM agent.
     * 
     * Only requires the pre-captured page snapshot - no live browser needed.
     * The browser can be released before table identification begins.
     */
    suspend fun identifyTables(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): List<TableIdentification>
}

class TableIdentificationService(
    private val tableIdentificationAgent: ITableIdentificationAgent,
    private val webpageTableRepository: IWebpageTableRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : ITableIdentificationService {

    /**
     * Identifies tables in webpage HTML using an LLM agent.
     * Results are cached in the repository to avoid repeated calls with the same HTML.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun identifyTables(
        sessionId: SessionId,
        pageSnapshot: IBrowserPage.PageSnapshotWithMetadata
    ): List<TableIdentification> {
        // Use HTML hash for caching
        val htmlHash = MessageDigest.getInstance("SHA-256").digest(pageSnapshot.html.toByteArray())

        val existing = webpageTableRepository.findByHash(htmlHash)
        if (existing != null) {
            return Json.decodeFromString<List<TableIdentification>>(existing.tables)
        }

        val agentOutput = tableIdentificationAgent.generate(
            TableIdentificationInput(
                pageSnapshot = pageSnapshot
            )
        )

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "TableIdentificationAgent",
            modelName = agentOutput.tokenUsage.modelName,
            promptTokens = agentOutput.tokenUsage.promptTokens,
            outputTokens = agentOutput.tokenUsage.outputTokens,
            totalTokens = agentOutput.tokenUsage.totalTokens
        )

        val tables = agentOutput.tables

        webpageTableRepository.upsert(
            WebpageTable(
                webpageHtmlHash = htmlHash,
                tables = Json.encodeToString(tables)
            )
        )

        return tables
    }
}

