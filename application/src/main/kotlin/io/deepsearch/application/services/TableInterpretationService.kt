package io.deepsearch.application.services

import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.models.entities.WebpageTableInterpretation
import io.deepsearch.domain.models.valueobjects.SessionId
import io.deepsearch.domain.repositories.IWebpageTableInterpretationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

interface ITableInterpretationService {
    suspend fun interpretTable(input: TableInterpretationInput, sessionId: SessionId): String
    suspend fun interpretTablesBatch(inputs: List<TableInterpretationInput>, sessionId: SessionId): List<String>
}

class TableInterpretationService(
    private val tableInterpretationAgent: ITableInterpretationAgent,
    private val webpageTableInterpretationRepository: IWebpageTableInterpretationRepository,
    private val tokenUsageService: ILlmTokenUsageService
) : ITableInterpretationService {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Interprets a table using an LLM agent and returns markdown.
     * Results are cached in the repository to avoid repeated calls with the same input.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun interpretTable(input: TableInterpretationInput, sessionId: SessionId): String {
        // Create a hash from all input parameters that affect the interpretation
        val digest = MessageDigest.getInstance("SHA-256")
        val tableHtml = input.webpage.getElementHtmlByCssSelector(input.tableIdentification.cssSelector)
        digest.update(tableHtml.toByteArray())
        val dataHash = digest.digest()

        val existing = webpageTableInterpretationRepository.findByHash(dataHash)
        if (existing != null) {
            logger.debug("Cache hit for table {}", input.tableIdentification.auxiliaryInfo)
            return existing.markdown
        }

        val agentOutput = tableInterpretationAgent.generate(input)

        // Record token usage
        tokenUsageService.recordTokenUsage(
            sessionId = sessionId,
            agentName = "TableInterpretationAgent",
            modelName = agentOutput.tokenUsage.modelName,
            promptTokens = agentOutput.tokenUsage.promptTokens,
            outputTokens = agentOutput.tokenUsage.outputTokens,
            totalTokens = agentOutput.tokenUsage.totalTokens
        )

        val markdown = agentOutput.markdown

        webpageTableInterpretationRepository.upsert(
            WebpageTableInterpretation(
                tableDataHash = dataHash,
                markdown = markdown
            )
        )

        return markdown
    }

    /**
     * Interprets multiple tables in batch, leveraging caching and batch upsert
     * to reduce concurrent upsert issues.
     * 
     * @param inputs List of table interpretation inputs
     * @param sessionId Query session ID for token tracking
     * @return List of markdown strings in the same order as inputs
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun interpretTablesBatch(inputs: List<TableInterpretationInput>, sessionId: SessionId): List<String> {
        if (inputs.isEmpty()) return emptyList()

        // Initialize result storage
        val results = MutableList<String?>(inputs.size) { null }

        // Compute hashes for all inputs
        val hashes = inputs.map { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val tableHtml = input.webpage.getElementHtmlByCssSelector(input.tableIdentification.cssSelector)
            digest.update(tableHtml.toByteArray())
            digest.digest()
        }

        // Check cache and populate results where cached
        for (index in inputs.indices) {
            val cached = webpageTableInterpretationRepository.findByHash(hashes[index])
            if (cached != null) {
                logger.debug("Cache hit for table {}", index)
                results[index] = cached.markdown
            }
        }

        // Collect uncached inputs that need interpretation
        val uncachedEntries = inputs.indices
            .filter { results[it] == null }
            .map { index -> Triple(index, inputs[index], hashes[index]) }

        // Generate interpretations for uncached inputs in parallel
        val newInterpretations = coroutineScope {
            uncachedEntries.map { (index, input, hash) ->
                async {
                    val agentOutput = tableInterpretationAgent.generate(input)
                    
                    // Record token usage
                    tokenUsageService.recordTokenUsage(
                        sessionId = sessionId,
                        agentName = "TableInterpretationAgent",
                        modelName = agentOutput.tokenUsage.modelName,
                        promptTokens = agentOutput.tokenUsage.promptTokens,
                        outputTokens = agentOutput.tokenUsage.outputTokens,
                        totalTokens = agentOutput.tokenUsage.totalTokens
                    )
                    
                    index to WebpageTableInterpretation(
                        tableDataHash = hash,
                        markdown = agentOutput.markdown
                    )
                }
            }.awaitAll()
        }

        // Batch upsert all new interpretations
        if (newInterpretations.isNotEmpty()) {
            webpageTableInterpretationRepository.batchUpsert(
                newInterpretations.map { it.second }
            )
        }

        // Fill remaining results with new interpretations
        for ((index, interpretation) in newInterpretations) {
            results[index] = interpretation.markdown
        }

        // All results are now populated
        return results.map { it!! }
    }
}

