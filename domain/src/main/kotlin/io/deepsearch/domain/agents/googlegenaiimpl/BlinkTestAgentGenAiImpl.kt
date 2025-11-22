package io.deepsearch.domain.agents.googlegenaiimpl

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.BlinkTestInput
import io.deepsearch.domain.agents.BlinkTestOutput
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.retryLlmCall
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Blink test agent that performs quick visual assessment of a webpage screenshot.
 * Determines if the page is relevant to a search query based on visual inspection.
 */
class BlinkTestAgentGenAiImpl(
    private val client: Client
) : IBlinkTestAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Blink test decision with rationale")
        .properties(
            mapOf(
                "rationale" to Schema.builder()
                    .type("STRING")
                    .description("Brief reason for the decision")
                    .build(),
                "decision" to Schema.builder()
                    .type("STRING")
                    .description("One of: IRRELEVANT, RELEVANT")
                    .build()
            )
        )
        .required(listOf("decision", "rationale"))
        .build()

    private val systemInstruction = """
        You are the Blink Test agent. Given a user query and a screenshot of a webpage, decide whether the page seems RELEVANT or IRRELEVANT.
        Return ONLY a JSON object that matches the output schema with fields {"rationale", "decision"}.
        
        Expected output shape:
        {
          "rationale": "Brief reason for the decision",
          "decision": "One of: IRRELEVANT, RELEVANT"
        }
    """.trimIndent()

    @Serializable
    private data class BlinkTestResponse(
        val decision: String,
        val rationale: String
    )

    override suspend fun generate(input: BlinkTestInput): BlinkTestOutput {
        logger.debug("Blink test for {}", input.searchQuery)

        val userPrompt = buildString {
            appendLine(input.searchQuery.query)
        }

        val response = retryLlmCall<BlinkTestResponse> {
            val result = client.models.generateContent(
                ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId,
                listOf(
                    Content.fromParts(
                        Part.fromBytes(input.screenshotBytes, "image/jpeg"),
                        Part.fromText(userPrompt)
                    )
                ),
                GenerateContentConfig.builder()
                    .temperature(0.1F)
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
        
        val decision = when (response.decision.uppercase()) {
            "RELEVANT" -> IBlinkTestAgent.Decision.RELEVANT
            "IRRELEVANT" -> IBlinkTestAgent.Decision.IRRELEVANT
            else -> throw Error("Unknown decision: ${response.decision}")
        }

        logger.debug(
            "Blink test result for {} is {}, reason: {}",
            input.searchQuery,
            response.decision,
            response.rationale
        )

        return BlinkTestOutput(
            decision = decision,
            rationale = response.rationale
        )
    }
}


