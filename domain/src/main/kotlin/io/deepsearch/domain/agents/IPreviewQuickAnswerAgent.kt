package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.PreviewShortlistedSource
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.models.valueobjects.UrlContentResult
import kotlinx.coroutines.flow.Flow

/**
 * Input for preview quick answer agent.
 * Provides current shortlist and new batch of HTML previews to evaluate.
 */
data class PreviewQuickAnswerInput(
    val query: String,
    val currentShortlist: List<PreviewShortlistedSource>,
    val newHtmlBatch: List<UrlContentResult.HtmlPreview>
) : IAgent.IAgentInput

/**
 * Output from preview quick answer agent.
 * Contains updated shortlist, confidence decision, and answer when confident.
 */
data class PreviewQuickAnswerOutput(
    val updatedShortlist: List<PreviewShortlistedSource>,
    val isConfidentForAnswer: Boolean,
    val answer: String?,
    val answerFound: Boolean,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Items emitted during streaming preview quick answer generation.
 */
sealed class PreviewQuickAnswerStreamItem {
    /**
     * A chunk of answer text (streamed as generated).
     */
    data class AnswerChunk(val text: String) : PreviewQuickAnswerStreamItem()

    /**
     * Emitted after all chunks, contains final metadata including shortlist.
     */
    data class Complete(
        val updatedShortlist: List<PreviewShortlistedSource>,
        val isConfidentForAnswer: Boolean,
        val answerFound: Boolean,
        val tokenUsage: TokenUsageMetrics
    ) : PreviewQuickAnswerStreamItem()
}

/**
 * Agent that curates a shortlist of high-quality sources from HTML previews
 * and generates an answer in a single LLM call.
 * 
 * This agent is VERY RESTRICTIVE - it only shortlists sources that:
 * - Contain unambiguous prose content
 * - Do NOT have tables, grids, images, or icons that affect the answer
 * - Have high confidence that the information directly answers the query
 * 
 * When confident, the agent also generates an answer using the extracted facts.
 * If uncertain, the agent does NOT shortlist - the main path will handle it
 * with full multimodal extraction.
 */
interface IPreviewQuickAnswerAgent : IAgent<PreviewQuickAnswerInput, PreviewQuickAnswerOutput> {
    override suspend fun generate(input: PreviewQuickAnswerInput): PreviewQuickAnswerOutput

    /**
     * Stream answer generation while evaluating sources.
     * Emits answer text chunks as they are generated.
     * The last emission is a PreviewQuickAnswerStreamItem.Complete containing
     * the shortlist metadata and token usage.
     *
     * @param input The query, current shortlist, and new HTML batch to evaluate
     * @return Flow of PreviewQuickAnswerStreamItem (AnswerChunk for text, Complete for final metadata)
     */
    fun generateStream(input: PreviewQuickAnswerInput): Flow<PreviewQuickAnswerStreamItem>
}

