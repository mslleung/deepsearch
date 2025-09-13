package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IBlinkTestAgent
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BlinkTestAgentAdkImpl : IBlinkTestAgent {

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

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("blinkTestAgent")
        description("Make a snap judgment whether the current page is relevant to the user's query")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0.1F)
                .build()
        )
        instruction(
            ("""
                You are the Blink Test agent. Given a user query and a screenshot of a webpage, decide whether the page seems RELEVANT or IRRELEVANT.
                Return ONLY a JSON object that matches the output schema with fields {"rationale", "decision"}.
                
                Expected output shape:
                {
                  "rationale": "Brief reason for the decision",
                  "decision": "One of: IRRELEVANT, RELEVANT"
                }
                """).trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class BlinkResponse(
        val decision: String,
        val rationale: String
    )

    override suspend fun generate(input: io.deepsearch.domain.agents.BlinkTestInput): io.deepsearch.domain.agents.BlinkTestOutput {
        logger.debug("Blink test for {}", input.searchQuery)

        val userPrompt = buildString {
            appendLine(input.searchQuery.query)
        }

        val session = runner
            .sessionService()
            .createSession(
                this::class.simpleName,
                this::class.simpleName,
                null,
                null
            )
            .await()

        var llmResponse = ""

        val eventsFlow = runner.runAsync(
            session,
            Content.fromParts(
                Part.fromBytes(input.screenshotBytes, "image/jpeg"),
                Part.fromText(userPrompt)
            ),
            RunConfig.builder().apply {
                setStreamingMode(RunConfig.StreamingMode.NONE)
                setMaxLlmCalls(1)
            }.build()
        ).asFlow()

        eventsFlow.collect { event ->
            if (event.finalResponse() && event.content().isPresent) {
                val content = event.content().get()
                if (content.parts().isPresent
                    && !content.parts().get().isEmpty()
                    && content.parts().get()[0].text().isPresent
                ) {
                    if (!event.partial().orElse(false)) {
                        llmResponse = content.parts().get()[0].text().get()
                    }
                }
            }
        }

        val response = Json.decodeFromString<BlinkResponse>(llmResponse)
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

        return io.deepsearch.domain.agents.BlinkTestOutput(
            decision = decision,
            rationale = response.rationale
        )
    }
}


