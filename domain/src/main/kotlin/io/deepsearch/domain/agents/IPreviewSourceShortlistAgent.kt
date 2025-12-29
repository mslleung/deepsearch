package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.ShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult

/**
 * Input for preview source shortlist agent.
 * Provides HTML preview sources to evaluate and extract facts from.
 */
data class PreviewSourceShortlistInput(
    val query: String,
    val htmlSources: List<UrlContentResult.HtmlPreview>
) : IAgent.IAgentInput

/**
 * Output from preview source shortlist agent.
 * Contains shortlisted sources with extracted facts.
 * 
 * Unlike the full markdown path, this agent does NOT determine isGoodEnough.
 * The answer synthesis agent determines completion via answerFound.
 * 
 * Facts where isInTable=true are filtered out before returning, since table
 * data in HTML previews may be inaccurate.
 */
data class PreviewSourceShortlistOutput(
    val shortlistedSources: List<ShortlistedSource>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that evaluates HTML preview sources and extracts classified facts.
 * 
 * For each source, the agent:
 * - Extracts facts relevant to the query
 * - Marks whether each fact comes from a table/grid (isInTable)
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * 
 * Facts from tables (isInTable=true) are filtered out before returning,
 * as table data in HTML previews may be inaccurate.
 * 
 * This is a non-streaming agent used in the preview path before answer synthesis.
 * Unlike the full markdown shortlist agent, this does NOT determine isGoodEnough.
 */
interface IPreviewSourceShortlistAgent : IAgent<PreviewSourceShortlistInput, PreviewSourceShortlistOutput> {
    override suspend fun generate(input: PreviewSourceShortlistInput): PreviewSourceShortlistOutput
}

