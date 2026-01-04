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
 * 
 * Uses a triples-based extraction approach (Subject-Predicate-Object) to ensure
 * that both entities in a relationship are always explicitly defined, preventing
 * dangling references.
 */
class EntityExtractionAgentGenAiImpl(
    private val client: com.google.genai.Client,
    private val dispatcherProvider: IDispatcherProvider
) : IEntityExtractionAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    // Schema for an entity embedded within a triple
    private val embeddedEntitySchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("An entity with its type and facts")
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
        .required(listOf("name", "type"))
        .build()

    // Schema for a knowledge graph triple (Subject-Predicate-Object)
    private val tripleSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A knowledge graph triple: subject entity, relationship, object entity")
        .properties(
            mapOf(
                "subject" to embeddedEntitySchema,
                "predicate" to Schema.builder()
                    .type("STRING")
                    .description("The type of relationship between subject and object")
                    .enum_(RelationType.entries.map { it.name })
                    .build(),
                "object" to embeddedEntitySchema,
                "confidence" to Schema.builder()
                    .type("NUMBER")
                    .description("Confidence score 0.0-1.0 for this relationship")
                    .build()
            )
        )
        .required(listOf("subject", "predicate", "object"))
        .build()

    // Schema for standalone entities (no relationships)
    private val standaloneEntitySchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A standalone entity with its type and facts")
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
        .required(listOf("name", "type"))
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Extracted knowledge graph from the content")
        .properties(
            mapOf(
                "triples" to Schema.builder()
                    .type("ARRAY")
                    .items(tripleSchema)
                    .description("List of knowledge graph triples (subject-predicate-object)")
                    .build(),
                "standaloneEntities" to Schema.builder()
                    .type("ARRAY")
                    .items(standaloneEntitySchema)
                    .description("Entities that don't have relationships but are still important")
                    .build()
            )
        )
        .required(listOf("triples", "standaloneEntities"))
        .build()

    private val systemInstruction = """
        You are a knowledge graph extraction agent. Extract structured knowledge as triples (Subject-Predicate-Object).
        
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
        
        Relationship Types (predicates):
        - HAS_PRICE: Entity has a price
        - HAS_FEATURE: Entity has a feature
        - INCLUDES: Entity includes another
        - PART_OF: Entity is part of another
        - INTEGRATES_WITH: Integration relationship
        - COMPETES_WITH: Competitor relationship
        - OWNED_BY: Ownership relationship
        - RELEASED_ON: Release date relationship
        - UPDATED_ON: Update date relationship
        - SUPERSEDES: Newer version replaces older
        - RELATED_TO: General relationship
        
        Extraction Guidelines:
        1. Extract knowledge as TRIPLES: each triple has a subject entity, a predicate (relationship), and an object entity
        2. Both subject and object must be fully defined entities with name, type, and optional facts
        3. Use exact text from source for entity names
        4. Include standalone entities that are important but don't have relationships
        5. Confidence should reflect how explicit the relationship is (0.0-1.0)
        6. Focus on important entities: products, pricing tiers, features, companies, integrations
        7. Avoid generic terms or common words as entities
        
        Example Output:
        {
          "triples": [
            {
              "subject": {"name": "Pro Plan", "type": "PRICING_TIER", "facts": ["Monthly subscription plan"]},
              "predicate": "HAS_PRICE",
              "object": {"name": "$79/month", "type": "PRICE", "facts": ["Billed monthly"]},
              "confidence": 0.95
            },
            {
              "subject": {"name": "Pro Plan", "type": "PRICING_TIER", "facts": []},
              "predicate": "HAS_FEATURE",
              "object": {"name": "SSO Integration", "type": "FEATURE", "facts": ["Single sign-on support"]},
              "confidence": 0.9
            }
          ],
          "standaloneEntities": [
            {"name": "Acme Corp", "type": "COMPANY", "facts": ["Founded in 2020", "Headquarters in San Francisco"]}
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
    private data class LlmTriple(
        val subject: LlmEntity,
        val predicate: String,
        val `object`: LlmEntity,
        val confidence: Float = 1.0f
    )

    @Serializable
    private data class LlmExtractionResponse(
        val triples: List<LlmTriple> = emptyList(),
        val standaloneEntities: List<LlmEntity> = emptyList()
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
            
            Extract knowledge graph triples and standalone entities from this content.
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

        // Convert triples-based response to domain model
        val extraction = convertTriplesToExtractionResult(response)

        logger.debug(
            "Extracted {} entities and {} relationships from URL: {}",
            extraction.entities.size,
            extraction.relationships.size,
            input.sourceUrl
        )

        return EntityExtractionOutput(
            extraction = extraction,
            tokenUsage = tokenUsage
        )
    }

    /**
     * Convert triples-based LLM response to domain model.
     * Extracts unique entities from all triples and builds relationships.
     */
    private fun convertTriplesToExtractionResult(response: LlmExtractionResponse): KgExtractionResult {
        // Collect all entities from triples and standalone entities
        // Use canonical name (lowercase + trimmed) + type as key for deduplication
        val entityMap = mutableMapOf<String, ExtractedEntity>()

        fun addOrMergeEntity(llmEntity: LlmEntity) {
            val entityType = try {
                EntityType.valueOf(llmEntity.type)
            } catch (e: IllegalArgumentException) {
                logger.warn("Unknown entity type '{}', defaulting to OTHER", llmEntity.type)
                EntityType.OTHER
            }

            val canonicalKey = "${llmEntity.name.lowercase().trim()}|${entityType.name}"

            val existing = entityMap[canonicalKey]
            if (existing != null) {
                // Merge facts from duplicate entity mentions
                val mergedFacts = (existing.facts + llmEntity.facts).distinct()
                entityMap[canonicalKey] = existing.copy(facts = mergedFacts)
            } else {
                entityMap[canonicalKey] = ExtractedEntity(
                    name = llmEntity.name,
                    type = entityType,
                    facts = llmEntity.facts
                )
            }
        }

        // Process entities from triples
        for (triple in response.triples) {
            addOrMergeEntity(triple.subject)
            addOrMergeEntity(triple.`object`)
        }

        // Process standalone entities
        for (entity in response.standaloneEntities) {
            addOrMergeEntity(entity)
        }

        // Build relationships from triples
        val relationships = response.triples.map { triple ->
            val relationType = try {
                RelationType.valueOf(triple.predicate)
            } catch (e: IllegalArgumentException) {
                logger.warn("Unknown relationship type '{}', defaulting to RELATED_TO", triple.predicate)
                RelationType.RELATED_TO
            }

            ExtractedRelationship(
                fromEntity = triple.subject.name,
                toEntity = triple.`object`.name,
                relationType = relationType,
                confidence = triple.confidence.coerceIn(0f, 1f)
            )
        }

        return KgExtractionResult(
            entities = entityMap.values.toList(),
            relationships = relationships
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
            
            Extract knowledge graph triples and standalone entities from this content.
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
            convertTriplesToExtractionResult(response)
        } catch (e: Exception) {
            logger.warn("Failed to parse batch response: {}", e.message)
            KgExtractionResult.empty()
        }
    }
}

