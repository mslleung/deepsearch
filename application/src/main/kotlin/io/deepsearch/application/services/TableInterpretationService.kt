package io.deepsearch.application.services

import io.deepsearch.domain.agents.ITableInterpretationAgent
import io.deepsearch.domain.agents.TableInterpretationInput
import io.deepsearch.domain.models.entities.WebpageTableInterpretation
import io.deepsearch.domain.repositories.IWebpageTableInterpretationRepository
import java.security.MessageDigest

interface ITableInterpretationService {
    suspend fun interpretTable(input: TableInterpretationInput): String
}

class TableInterpretationService(
    private val tableInterpretationAgent: ITableInterpretationAgent,
    private val webpageTableInterpretationRepository: IWebpageTableInterpretationRepository
) : ITableInterpretationService {

    /**
     * Interprets a table using an LLM agent and returns markdown.
     * Results are cached in the repository to avoid repeated calls with the same input.
     */
    override suspend fun interpretTable(input: TableInterpretationInput): String {
        // Create a hash from all input parameters that affect the interpretation
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(input.screenshotBytes)
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
}

