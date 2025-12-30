package io.deepsearch.domain.agents

import io.deepsearch.domain.agents.infra.IAgent
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics

data class TextToCypherInput(
    val query: String,
    val schemaDescription: String
) : IAgent.IAgentInput

data class TextToCypherOutput(
    val cypherQuery: String,
    val explanation: String,
    val isValid: Boolean,
    val tokenUsage: TokenUsageMetrics
) : IAgent.IAgentOutput

/**
 * Agent that generates Apache AGE-compatible Cypher queries from natural language.
 * Used during query time to enable graph-based retrieval.
 */
interface ITextToCypherAgent :
    IAgent<TextToCypherInput, TextToCypherOutput> {
    
    override suspend fun generate(input: TextToCypherInput): TextToCypherOutput
}

