package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.EvaluatedSource
import io.deepsearch.domain.models.valueobjects.MarkdownSource
import io.deepsearch.domain.models.valueobjects.SearchQuery
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

/**
 * Input for markdown source evaluation agent.
 * Provides a single markdown source to evaluate and extract facts from.
 * 
 * The agent internally appends `site:<domain>` to the query for better context.
 */
data class MarkdownSourceEvalInput(
    val searchQuery: SearchQuery,
    val markdownSource: MarkdownSource
) : IAgent.IAgentInput

/**
 * Output from markdown source evaluation agent.
 * Contains the evaluated source with extracted facts, or null if no relevant facts found.
 * 
 * Unlike the HTML preview path, table facts are NOT filtered out since markdown
 * tables are properly processed and accurate.
 * 
 * @property evaluatedSource The evaluated source with extracted facts, or null if not relevant
 * @property expandedQuery Clarified/expanded version of the user query that captures the core intent.
 *           For example: "tell me about the pricing" → "What are the main subscription plans and pricing tiers?"
 * @property tokenUsage Token usage metrics for this evaluation
 */
data class MarkdownSourceEvalOutput(
    val evaluatedSource: EvaluatedSource?,
    val expandedQuery: String,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that evaluates a single markdown source and extracts classified facts.
 * 
 * For the source, the agent:
 * - Extracts facts relevant to the query
 * - Classifies the source type (OFFICIAL_LIVING_DOC, OFFICIAL_SNAPSHOT, OTHERS)
 * - Determines answer type and temporal metadata
 * - Handles image selection (relevantImageIds)
 * 
 * Unlike the HTML preview agent, this does NOT filter table facts since markdown
 * tables are properly processed and accurate.
 * 
 * This is a stateless agent that processes one source at a time, enabling parallel processing.
 * The isGoodEnough decision is made by the StreamingAnswerSynthesisAgent instead.
 */
interface IMarkdownSourceEvalAgent : IAgent<MarkdownSourceEvalInput, MarkdownSourceEvalOutput> {
    override suspend fun generate(input: MarkdownSourceEvalInput): MarkdownSourceEvalOutput
}

