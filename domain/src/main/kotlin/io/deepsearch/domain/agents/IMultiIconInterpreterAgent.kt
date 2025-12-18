package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.constants.ImageMimeType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest

/**
 * Input for multi-icon interpretation.
 * Contains multiple icons to be interpreted in a single LLM call.
 */
data class MultiIconInterpreterInput(
    val icons: List<IconItem>
) : IAgent.IAgentInput {
    data class IconItem(
        val bytes: ByteArray,
        val mimeType: ImageMimeType,
    )
}

/**
 * Output for multi-icon interpretation.
 * Contains interpretations for all icons that were sent in the input.
 * The order of interpretations matches the order of icons in the input.
 */
data class MultiIconInterpreterOutput(
    val interpretations: List<IconInterpretation>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput {
    data class IconInterpretation(
        val label: String?
    )
}

/**
 * Agent interface for interpreting multiple icons in a single LLM call.
 * This is more efficient than calling IconInterpreterAgent multiple times,
 * as it reduces the number of API calls and helps avoid rate limits.
 *
 * The output interpretations list will have the same size and order as the input icons list,
 * making it easy to match results back to the original icons by position.
 */
interface IMultiIconInterpreterAgent : IAgent<MultiIconInterpreterInput, MultiIconInterpreterOutput> {
    override suspend fun generate(input: MultiIconInterpreterInput): MultiIconInterpreterOutput

    /**
     * Prepare a batch request for icon interpretation.
     * Used by batch processing to create requests with the same prompts as interactive mode.
     * 
     * @param requestId Unique identifier for this request
     * @param icons List of icons with bytes and MIME type
     * @return BatchContentRequest with multimodal content (text + images)
     */
    fun prepareBatchRequest(
        requestId: String,
        icons: List<MultiIconInterpreterInput.IconItem>
    ): BatchContentRequest

    /**
     * Parse a batch response into icon interpretations.
     * Used by batch processing to parse responses with the same logic as interactive mode.
     * 
     * @param responseText The JSON response from the batch API
     * @return List of labels (or null for uninterpretable icons) in order
     */
    fun parseBatchResponse(responseText: String): List<String?>
}

