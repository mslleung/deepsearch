package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import io.deepsearch.domain.agents.ITextToCypherAgent
import io.deepsearch.domain.agents.TextToCypherInput
import io.deepsearch.domain.agents.TextToCypherOutput
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Text-to-Cypher agent that generates Apache AGE-compatible Cypher queries from natural language.
 * Uses Gemini 2.5 Flash Lite to translate user queries into graph queries.
 */
class TextToCypherAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : ITextToCypherAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Generated Cypher query with explanation")
        .properties(
            mapOf(
                "cypherQuery" to Schema.builder()
                    .type("STRING")
                    .description("The generated Cypher query (without the ag_catalog wrapper)")
                    .build(),
                "explanation" to Schema.builder()
                    .type("STRING")
                    .description("Brief explanation of what the query does")
                    .build(),
                "isValid" to Schema.builder()
                    .type("BOOLEAN")
                    .description("Whether the query could be translated to valid Cypher")
                    .build()
            )
        )
        .required(listOf("cypherQuery", "explanation", "isValid"))
        .build()

    private fun buildSystemInstruction(domain: String?): String {
        return """
        You are a Cypher query generation agent for Apache AGE (PostgreSQL graph extension).
        
        Graph Schema:
        - Vertices have label :Entity with properties: id (UUID), name (TEXT), type (TEXT), domain (TEXT)
        - Entity types: PRODUCT, PRICING_TIER, FEATURE, COMPANY, PERSON, INTEGRATION, DATE, PRICE, TECHNOLOGY, OTHER
        - Edge types (relationships): HAS_PRICE, HAS_FEATURE, INCLUDES, PART_OF, INTEGRATES_WITH, COMPETES_WITH, OWNED_BY, RELEASED_ON, UPDATED_ON, SUPERSEDES, RELATED_TO
        - Edges also have a 'domain' property for filtering
            - Example: MATCH (e:Entity) WHERE e.domain = '$domain' AND e.name =~ '(?i).*pro.*' RETURN e.name
        Generate a Cypher query that answers the user's question. The query will be wrapped in:
        SELECT * FROM ag_catalog.cypher('knowledge_graph', $$ YOUR_QUERY $$) AS (result agtype)
        
        Apache AGE Cypher syntax notes:
        - Use standard Cypher syntax (MATCH, WHERE, RETURN, etc.)
        - Property access: e.name, e.type (not e['name'])
        - String matching: Use =~ for regex, e.g., e.name =~ '(?i).*pattern.*' for case-insensitive
        - Return meaningful aliases
        - Keep queries simple and focused
        - Limit results to avoid overwhelming output (use LIMIT)
        
        Output format:
        {
          "cypherQuery": "MATCH (e:Entity) WHERE ${if (domain != null) "e.domain = '$domain' AND " else ""}e.name =~ '(?i).*pro.*' RETURN e.name, e.type LIMIT 10",
          "explanation": "Finds entities with 'pro' in their name",
          "isValid": true
        }
        
        If the query cannot be reasonably translated to Cypher (e.g., too vague or not suitable for graph queries):
        {
          "cypherQuery": "",
          "explanation": "Reason why query cannot be translated",
          "isValid": false
        }
        
        Focus on queries that benefit from graph structure:
        - Finding entities by name or type
        - Following relationships (e.g., "what features does X include?")
        - Finding connected entities (e.g., "what integrates with X?")
        - Multi-hop traversals (e.g., "what is related to things that include Y?")
    """.trimIndent()
    }

    @Serializable
    private data class CypherResponse(
        val cypherQuery: String,
        val explanation: String,
        val isValid: Boolean
    )

    override suspend fun generate(input: TextToCypherInput): TextToCypherOutput {
        logger.debug("Generating Cypher for query: '{}', domain: {}", input.query, input.domain)

        val modelId = ModelIds.GEMINI_3_1_FLASH_LITE.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)
        
        val systemInstruction = buildSystemInstruction(input.domain)

        val userPrompt = """
            User Query: ${input.query}
            
            Additional Schema Information:
            ${input.schemaDescription}
            ${if (input.domain != null) "\nDomain Filter: ${input.domain} (MUST be included in all queries)" else ""}
            
            Generate a Cypher query to answer this question, or indicate if it cannot be translated.
        """.trimIndent()

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<CypherResponse>(this@TextToCypherAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
                        .temperature(1.0F)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingLevel(ThinkingLevel.Known.MINIMAL)
                                .build()
                        )
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                        .build()
                )

                result.checkFinishReason()

                // Extract token usage
                result.usageMetadata().ifPresent { metadata ->
                    tokenUsage = TokenUsageMetrics(
                        modelName = modelId,
                        promptTokens = metadata.promptTokenCount().orElse(0),
                        outputTokens = metadata.candidatesTokenCount().orElse(0),
                        totalTokens = metadata.totalTokenCount().orElse(0)
                    )
                }

                result.text() ?: throw RuntimeException("No text response from model")
            }
        }

        logger.debug(
            "Generated Cypher (valid={}): {} - {}",
            response.isValid,
            response.cypherQuery.take(100),
            response.explanation
        )

        return TextToCypherOutput(
            cypherQuery = response.cypherQuery,
            explanation = response.explanation,
            isValid = response.isValid,
            tokenUsage = tokenUsage
        )
    }
}

