package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.GenerateAnswerInput
import io.deepsearch.domain.agents.GenerateAnswerOutput
import io.deepsearch.domain.agents.IGenerateAnswerAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Generate Answer agent that synthesizes markdown content from multiple crawled pages
 * to produce a comprehensive answer to the user's query.
 */
class GenerateAnswerAgentGenAiImpl(
    private val client: com.google.genai.Client
) : IGenerateAnswerAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Generated answer to the user's search query based on crawled content")
        .properties(
            mapOf(
                "answer" to Schema.builder()
                    .type("STRING")
                    .description("Comprehensive answer to the search query synthesized from all markdown content")
                    .build()
            )
        )
        .required(listOf("answer"))
        .build()

    private val systemInstruction = """
        You are a answer extractor agent. Given multiple markdowns and a user's search query, generate a comprehensive, accurate answer to the query.
        
        Instructions:
        - Directly address the user's specific search query
        - Provide a clear, well-structured, and comprehensive answer
        - Reference specific details, facts, and examples from the content
        - Do not invent information not present in the content
        - Organize the answer logically with proper formatting when appropriate
        - If the query cannot be answered based on the markdowns at all, return "No information found"
        
        Expected output shape:
        {
            "answer": string
        }
    """.trimIndent()

    @Serializable
    private data class GenerateAnswerResponse(
        val answer: String
    )

    override suspend fun generate(input: GenerateAnswerInput): GenerateAnswerOutput {
        logger.debug(
            "Generating answer for query: '{}' from {} chars of markdown",
            input.query,
            input.markdowns.length
        )

        val userPrompt = """
            Search Query: ${input.query}
            
            Markdown Content from Crawled Pages:
            ${input.markdowns}
        """.trimIndent()

        val response = retryLlmCall<GenerateAnswerResponse> {
            val result = client.models.generateContent(
                ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
                userPrompt,
                GenerateContentConfig.builder()
                    .temperature(0F)
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

            result.text() ?: throw RuntimeException("No text response from model")
        }

        logger.debug("Generated answer: {} chars", response.answer.length)
        return GenerateAnswerOutput(answer = response.answer)
    }
}


