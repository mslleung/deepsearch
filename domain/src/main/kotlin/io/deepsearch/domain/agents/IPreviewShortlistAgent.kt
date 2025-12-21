package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.PreviewShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult

/**
 * Input for preview shortlist agent.
 * Provides current shortlist and new batch of HTML previews to evaluate.
 */
data class PreviewShortlistInput(
    val query: String,
    val currentShortlist: List<PreviewShortlistedSource>,
    val newHtmlBatch: List<UrlContentResult.HtmlPreview>
) : IAgent.IAgentInput

/**
 * Output from preview shortlist agent.
 * Contains updated shortlist and confidence decision for answer synthesis.
 */
data class PreviewShortlistOutput(
    val updatedShortlist: List<PreviewShortlistedSource>,
    val isConfidentForAnswer: Boolean,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that curates a shortlist of high-quality sources from HTML previews.
 * 
 * This agent is VERY RESTRICTIVE - it only shortlists sources that:
 * - Contain unambiguous prose content
 * - Do NOT have tables, grids, images, or icons that affect the answer
 * - Have high confidence that the information directly answers the query
 * 
 * If uncertain, the agent does NOT shortlist - the main path will handle it
 * with full multimodal extraction.
 */
interface IPreviewShortlistAgent : IAgent<PreviewShortlistInput, PreviewShortlistOutput> {
    override suspend fun generate(input: PreviewShortlistInput): PreviewShortlistOutput
}
