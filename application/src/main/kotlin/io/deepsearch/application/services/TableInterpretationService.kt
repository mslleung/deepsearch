package io.deepsearch.application.services

import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.models.entities.WebpageTableInterpretation
import io.deepsearch.domain.repositories.IWebpageTableInterpretationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest
import kotlin.time.ExperimentalTime

interface ITableInterpretationService {
    suspend fun interpretTable(input: TableInterpretationInput): String
    suspend fun interpretTablesBatch(inputs: List<TableInterpretationInput>): List<String>
}

class TableInterpretationService(
    private val tableInterpretationAgent: ITableInterpretationAgent,
    private val webpageTableInterpretationRepository: IWebpageTableInterpretationRepository
) : ITableInterpretationService {

    /**
     * Interprets a table using an LLM agent and returns markdown.
     * Results are cached in the repository to avoid repeated calls with the same input.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun interpretTable(input: TableInterpretationInput): String {
        // Create a hash from all input parameters that affect the interpretation
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(input.html.toByteArray())
        val dataHash = digest.digest()

        val existing = webpageTableInterpretationRepository.findByHash(dataHash)
        if (existing != null) {
            return existing.markdown
        }

        val agentOutput = tableInterpretationAgent.generate(input)
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
     * @return List of markdown strings in the same order as inputs
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun interpretTablesBatch(inputs: List<TableInterpretationInput>): List<String> {
        if (inputs.isEmpty()) return emptyList()

        // Initialize result storage
        val results = MutableList<String?>(inputs.size) { null }

        // Compute hashes for all inputs
        val hashes = inputs.map { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(input.html.toByteArray())
            digest.digest()
        }

        // Check cache and populate results where cached
        for (index in inputs.indices) {
            val cached = webpageTableInterpretationRepository.findByHash(hashes[index])
            if (cached != null) {
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

