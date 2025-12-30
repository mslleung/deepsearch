package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest

data class EntityExtractionInput(
    val markdown: String,
    val sourceUrl: String
) : IAgent.IAgentInput

data class EntityExtractionOutput(
    val extraction: KgExtractionResult,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that extracts entities and relationships from markdown content.
 * Used during knowledge graph indexing to build the graph structure.
 */
interface IEntityExtractionAgent :
    IAgent<EntityExtractionInput, EntityExtractionOutput> {
    
    override suspend fun generate(input: EntityExtractionInput): EntityExtractionOutput

    /**
     * Prepare a batch request for entity extraction.
     * Used by batch processing to create requests with the same prompts as interactive mode.
     * 
     * @param requestId Unique identifier for this request
     * @param markdown The markdown content to extract entities from
     * @param sourceUrl The source URL for provenance
     * @return BatchContentRequest for the Gemini Batch API
     */
    fun prepareBatchRequest(
        requestId: String,
        markdown: String,
        sourceUrl: String
    ): BatchContentRequest

    /**
     * Parse the response from a batch entity extraction request.
     * 
     * @param responseText The JSON response from the batch API
     * @return Extracted entities and relationships, or empty if parsing fails
     */
    fun parseBatchResponse(responseText: String): KgExtractionResult
}

