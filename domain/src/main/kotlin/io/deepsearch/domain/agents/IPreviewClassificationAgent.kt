package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult

/**
 * Classification of a source based on its authority and freshness.
 */
enum class SourceClassification {
    /** Official main pages (e.g., /pricing, /home, /features) intended to reflect current state */
    OFFICIAL_LIVING_DOC,
    /** Dated company updates (e.g., /blog, /press, /news) */
    OFFICIAL_SNAPSHOT,
    /** External reviews, news sites, forums, UGC, etc. */
    OTHERS
}

/**
 * A fact extracted from a source with metadata about its location and source classification.
 */
data class RelevantFact(
    val fact: String,
    val isInTable: Boolean,
    val classification: SourceClassification
)

/**
 * A source with its classified relevant facts.
 */
data class ClassifiedSource(
    val url: String,
    val relevantFacts: List<RelevantFact>
)

/**
 * Input for preview classification agent.
 * Provides HTML sources to classify and extract facts from.
 */
data class PreviewClassificationInput(
    val query: String,
    val htmlSources: List<UrlContentResult.HtmlPreview>
) : IAgent.IAgentInput

/**
 * Output from preview classification agent.
 * Contains classified sources with extracted facts.
 */
data class PreviewClassificationOutput(
    val sourceClassifications: List<ClassifiedSource>,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that classifies HTML preview sources and extracts relevant facts.
 * 
 * For each source, the agent:
 * - Extracts facts relevant to the query
 * - Marks whether each fact is from a table/grid
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * 
 * This is a non-streaming agent used in the preview path before answer synthesis.
 */
interface IPreviewClassificationAgent : IAgent<PreviewClassificationInput, PreviewClassificationOutput> {
    override suspend fun generate(input: PreviewClassificationInput): PreviewClassificationOutput
}

