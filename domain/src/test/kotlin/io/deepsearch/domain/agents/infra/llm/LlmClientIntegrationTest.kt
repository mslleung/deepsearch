package io.deepsearch.domain.agents.infra.llm

import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.config.domainTestModule
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests verifying that both GenAI and OpenAI-compatible backends
 * work correctly through [ILlmClient] and [RoutingLlmClient].
 */
class LlmClientIntegrationTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension = IsolatedKoinExtension.create {
        modules(domainTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val llmClient by inject<ILlmClient>()

    @Serializable
    private data class CapitalResponse(val capital: String, val country: String)

    private val capitalSchema: Schema = Schema.builder()
        .type("OBJECT")
        .properties(
            mapOf(
                "capital" to Schema.builder().type("STRING")
                    .description("The capital city name")
                    .build(),
                "country" to Schema.builder().type("STRING")
                    .description("The country name")
                    .build()
            )
        )
        .required(listOf("capital", "country"))
        .build()

    @Test
    fun `GenAI client returns structured JSON via Gemini`() = runTest(testCoroutineDispatcher) {
        val modelId = ModelIds.GEMINI_3_5_FLASH_LITE.modelId
        val config = GenerateContentConfig.builder()
            .responseSchema(capitalSchema)
            .responseMimeType("application/json")
            .systemInstruction(Content.fromParts(Part.fromText("You answer geography questions.")))
            .build()

        val result = llmClient.generateContent(
            modelId,
            listOf(Content.fromParts(Part.fromText("What is the capital of France?"))),
            config
        )

        assertNotNull(result.text, "Response text should not be null")
        val parsed = Json.decodeFromString<CapitalResponse>(result.text!!)
        assertTrue(parsed.capital.contains("Paris", ignoreCase = true), "Expected Paris, got: ${parsed.capital}")
        assertTrue(result.inputTokens > 0, "Input tokens should be > 0")
        assertTrue(result.outputTokens > 0, "Output tokens should be > 0")
    }

    @Test
    fun `MaaS OpenAI client returns structured JSON via Gemma`() = runTest(testCoroutineDispatcher) {
        val modelId = ModelIds.GEMMA_4_26B_A4B.modelId
        val config = GenerateContentConfig.builder()
            .responseSchema(capitalSchema)
            .responseMimeType("application/json")
            .systemInstruction(Content.fromParts(Part.fromText("You answer geography questions. Return valid JSON.")))
            .build()

        val result = llmClient.generateContent(
            modelId,
            listOf(Content.fromParts(Part.fromText("What is the capital of Japan?"))),
            config
        )

        assertNotNull(result.text, "Response text should not be null")
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<CapitalResponse>(result.text!!)
        assertTrue(parsed.capital.contains("Tokyo", ignoreCase = true), "Expected Tokyo, got: ${parsed.capital}")
        assertTrue(result.inputTokens > 0, "Input tokens should be > 0")
        assertTrue(result.outputTokens > 0, "Output tokens should be > 0")
    }

    @Test
    fun `RoutingLlmClient routes Gemini model to GenAI backend`() = runTest(testCoroutineDispatcher) {
        val config = GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText("Be concise.")))
            .build()

        val result = llmClient.generateContent(
            ModelIds.GEMINI_3_5_FLASH_LITE.modelId,
            listOf(Content.fromParts(Part.fromText("Say hello in one word."))),
            config
        )

        assertNotNull(result.text, "Response text should not be null")
        assertTrue(result.text!!.isNotBlank(), "Response should not be blank")
    }

    @Test
    fun `RoutingLlmClient routes Gemma model to OpenAI backend`() = runTest(testCoroutineDispatcher) {
        val config = GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText("Be concise.")))
            .build()

        val result = llmClient.generateContent(
            ModelIds.GEMMA_4_26B_A4B.modelId,
            listOf(Content.fromParts(Part.fromText("Say hello in one word."))),
            config
        )

        assertNotNull(result.text, "Response text should not be null")
        assertTrue(result.text!!.isNotBlank(), "Response should not be blank")
    }
}
