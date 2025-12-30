package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.EntityExtractionInput
import io.deepsearch.domain.agents.EntityExtractionOutput
import io.deepsearch.domain.agents.IEntityExtractionAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.config.IDispatcherProvider
import io.deepsearch.domain.knowledgegraph.EntityType
import io.deepsearch.domain.knowledgegraph.ExtractedEntity
import io.deepsearch.domain.knowledgegraph.ExtractedRelationship
import io.deepsearch.domain.knowledgegraph.KgExtractionResult
import io.deepsearch.domain.knowledgegraph.RelationType
import io.deepsearch.domain.models.valueobjects.TokenUsageMetrics
import io.deepsearch.domain.services.BatchContentRequest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Entity extraction agent that extracts entities and relationships from markdown content.
 * Uses Gemini 2.5 Flash Lite to process full markdown documents (no chunking).
 */
class EntityExtractionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IEntityExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val entitySchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("An extracted entity with its type and facts")
        .properties(
            mapOf(
                "name" to Schema.builder()
                    .type("STRING")
                    .description("The entity name as it appears in the content")
                    .build(),
                "type" to Schema.builder()
                    .type("STRING")
                    .description("The entity type classification")
                    .enum_(EntityType.entries.map { it.name })
                    .build(),
                "facts" to Schema.builder()
                    .type("ARRAY")
                    .items(Schema.builder().type("STRING").build())
                    .description("Facts about this entity extracted from the content")
                    .build()
            )
        )
        .required(listOf("name", "type", "facts"))
        .build()

    private val relationshipSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A relationship between two entities")
        .properties(
            mapOf(
                "fromEntity" to Schema.builder()
                    .type("STRING")
                    .description("Name of the source entity")
                    .build(),
                "toEntity" to Schema.builder()
                    .type("STRING")
                    .description("Name of the target entity")
                    .build(),
                "relationType" to Schema.builder()
                    .type("STRING")
                    .description("The type of relationship")
                    .enum_(RelationType.entries.map { it.name })
                    .build(),
                "confidence" to Schema.builder()
                    .type("NUMBER")
                    .description("Confidence score 0.0-1.0")
                    .build()
            )
        )
        .required(listOf("fromEntity", "toEntity", "relationType"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Extracted entities and relationships from the content")
        .properties(
            mapOf(
                "entities" to Schema.builder()
                    .type("ARRAY")
                    .items(entitySchema)
                    .description("List of extracted entities")
                    .build(),
                "relationships" to Schema.builder()
                    .type("ARRAY")
                    .items(relationshipSchema)
                    .description("List of relationships between entities")
                    .build()
            )
        )
        .required(listOf("entities", "relationships"))
        .build()

    private val systemInstruction = """
        You are an entity and relationship extraction agent. Extract structured knowledge from the provided content.
        
        Entity Types (choose the most specific):
        - PRODUCT: Software products, services, or offerings
        - PRICING_TIER: Named pricing plans (e.g., "Pro Plan", "Enterprise")
        - FEATURE: Product features or capabilities
        - COMPANY: Organizations
        - PERSON: Named individuals
        - INTEGRATION: Third-party integrations
        - DATE: Specific dates or time periods
        - PRICE: Monetary values with currency
        - TECHNOLOGY: Technologies, languages, frameworks
        - OTHER: Anything else significant
        
        Relationship Types:
        - HAS_PRICE: Entity has a price (e.g., Pro Plan → $79/month)
        - HAS_FEATURE: Entity has a feature
        - INCLUDES: Entity includes another (e.g., Enterprise includes SSO)
        - PART_OF: Entity is part of another
        - INTEGRATES_WITH: Integration relationship
        - COMPETES_WITH: Competitor relationship
        - OWNED_BY: Ownership relationship
        - RELEASED_ON: Release date relationship
        - UPDATED_ON: Update date relationship
        - SUPERSEDES: Newer version replaces older
        - RELATED_TO: General relationship
        
        Extraction Guidelines:
        - Be precise with entity names (use exact text from source)
        - Extract prices with currency symbols
        - Include temporal information (dates, versions)
        - Confidence should reflect how explicit the relationship is (0.0-1.0)
        - Only extract entities and relationships that are clearly stated
        - Each fact should be a complete, standalone statement
        - Focus on the most important entities (products, pricing, features, companies)
        - Avoid extracting generic terms or common words as entities
        
        Output Format:
        {
          "entities": [
            {"name": "Pro Plan", "type": "PRICING_TIER", "facts": ["Costs $79/month", "Includes 10 users"]}
          ],
          "relationships": [
            {"fromEntity": "Pro Plan", "toEntity": "$79/month", "relationType": "HAS_PRICE", "confidence": 0.95}
          ]
        }
    """.trimIndent()

    @Serializable
    private data class LlmEntity(
        val name: String,
        val type: String,
        val facts: List<String> = emptyList()
    )

    @Serializable
    private data class LlmRelationship(
        val fromEntity: String,
        val toEntity: String,
        val relationType: String,
        val confidence: Float = 1.0f
    )

    @Serializable
    private data class LlmExtractionResponse(
        val entities: List<LlmEntity>,
        val relationships: List<LlmRelationship>
    )

    override suspend fun generate(input: EntityExtractionInput): EntityExtractionOutput {
        logger.debug(
            "Extracting entities from URL: {} ({} chars)",
            input.sourceUrl,
            input.markdown.length
        )

        val modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId
        var tokenUsage = TokenUsageMetrics.empty(modelId)

        val userPrompt = """
            Source URL: ${input.sourceUrl}
            
            Content:
            ${input.markdown}
            
            Extract all entities and relationships from this content.
        """.trimIndent()

        val response = withContext(dispatcherProvider.io) {
            retryLlmCall<LlmExtractionResponse>(this@EntityExtractionAgentGenAiImpl::class.simpleName!!) {
                val result = client.models.generateContent(
                    modelId,
                    userPrompt,
                    GenerateContentConfig.builder()
                        .temperature(0f)
                        .responseSchema(outputSchema)
                        .responseMimeType("application/json")
                        .thinkingConfig(
                            ThinkingConfig.builder()
                                .thinkingBudget(0)
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

        // Convert LLM response to domain model
        val entities = response.entities.map { llmEntity ->
            val entityType = try {
                EntityType.valueOf(llmEntity.type)
            } catch (e: IllegalArgumentException) {
                logger.warn("Unknown entity type '{}', defaulting to OTHER", llmEntity.type)
                EntityType.OTHER
            }
            
            ExtractedEntity(
                name = llmEntity.name,
                type = entityType,
                facts = llmEntity.facts
            )
        }

        val relationships = response.relationships.mapNotNull { llmRel ->
            val relationType = try {
                RelationType.valueOf(llmRel.relationType)
            } catch (e: IllegalArgumentException) {
                logger.warn("Unknown relationship type '{}', defaulting to RELATED_TO", llmRel.relationType)
                RelationType.RELATED_TO
            }
            
            ExtractedRelationship(
                fromEntity = llmRel.fromEntity,
                toEntity = llmRel.toEntity,
                relationType = relationType,
                confidence = llmRel.confidence.coerceIn(0f, 1f)
            )
        }

        val extraction = KgExtractionResult(
            entities = entities,
            relationships = relationships
        )

        logger.debug(
            "Extracted {} entities and {} relationships from URL: {}",
            entities.size,
            relationships.size,
            input.sourceUrl
        )

        return EntityExtractionOutput(
            extraction = extraction,
            tokenUsage = tokenUsage
        )
    }

    // ========== Batch Processing Methods ==========

    private val batchJson = Json { ignoreUnknownKeys = true }

    override fun prepareBatchRequest(
        requestId: String,
        markdown: String,
        sourceUrl: String
    ): BatchContentRequest {
        val userPrompt = """
            Source URL: $sourceUrl
            
            Content:
            $markdown
            
            Extract all entities and relationships from this content.
        """.trimIndent()

        return BatchContentRequest(
            requestId = requestId,
            modelId = ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            temperature = 0f
        ).withSchema(outputSchema)
    }

    override fun parseBatchResponse(responseText: String): KgExtractionResult {
        return try {
            val response = batchJson.decodeFromString<LlmExtractionResponse>(responseText)

            val entities = response.entities.map { llmEntity ->
                val entityType = try {
                    EntityType.valueOf(llmEntity.type)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Unknown entity type '{}', defaulting to OTHER", llmEntity.type)
                    EntityType.OTHER
                }

                ExtractedEntity(
                    name = llmEntity.name,
                    type = entityType,
                    facts = llmEntity.facts
                )
            }

            val relationships = response.relationships.map { llmRel ->
                val relationType = try {
                    RelationType.valueOf(llmRel.relationType)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Unknown relationship type '{}', defaulting to RELATED_TO", llmRel.relationType)
                    RelationType.RELATED_TO
                }

                ExtractedRelationship(
                    fromEntity = llmRel.fromEntity,
                    toEntity = llmRel.toEntity,
                    relationType = relationType,
                    confidence = llmRel.confidence.coerceIn(0f, 1f)
                )
            }

            KgExtractionResult(entities = entities, relationships = relationships)
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            KgExtractionResult.empty()
        }
    }
}

