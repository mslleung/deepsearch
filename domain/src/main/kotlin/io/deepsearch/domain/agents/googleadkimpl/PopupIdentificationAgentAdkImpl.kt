package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.IPopupIdentificationAgent
import io.deepsearch.domain.agents.PopupIdentificationInput
import io.deepsearch.domain.agents.PopupIdentificationOutput
import io.deepsearch.domain.agents.PopupIdentificationResult
import io.deepsearch.domain.agents.infra.ModelIds
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PopupIdentificationAgentAdkImpl : IPopupIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Detect presence of popup/cookie banner and provide a unique CSS selector to dismiss it")
        .properties(
            mapOf(
                "exists" to Schema.builder().type("BOOLEAN").description("Whether a popup/cookie banner exists").build(),
                "dismissSelector" to Schema.builder().type("STRING").description("A unique CSS selector of a button or control that dismisses the popup").build(),
            )
        )
        .required(listOf("exists"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("popupIdentificationAgent")
        description("Identify if a popup/cookie banner exists and provide a unique CSS selector to dismiss it")
        model(ModelIds.GEMINI_2_5_LITE.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .build()
        )
        instruction(
            """
            Your task is to detect whether any popup or cookie consent banner is currently visible on the webpage and, if present, return a unique CSS selector for the specific element to click in order to dismiss it.

            Input:
            A screenshot of a webpage.

            Instructions:
            - Determine if a popup banner or cookie consent banner is visible. If none is visible, set exists=false and leave dismissSelector empty.
            - If a banner is visible, return exists=true and generate a unique CSS selector that, when clicked, dismisses the popup.
            - The selector must make no assumptions about the underlying HTML structure. Prefer text-based matching on the button/control label that dismisses the banner.
            - Build a robust selector that uses :has-text(...) or text() matching when possible; otherwise, combine attributes and structure to ensure uniqueness.
            - The selector must target the direct clickable control (e.g., a button) that dismisses the popup. Examples of texts: "Accept all", "Accept", "I agree", "Got it", "OK", "Close", "Reject", "Continue", "Dismiss". If multiple dismissal options are available, prefer the most privacy-preserving option like "Reject all" or "Decline"; otherwise choose a neutral dismiss like "Dismiss".
            - Return only one selector string that is expected to be unique on the page.

            Expected output shape:
            {
              "exists": true,
              "dismissSelector": "string"
            }
            or when no popup exists:
            {
              "exists": false
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class PopupIdentificationResponse(
        val exists: Boolean,
        val dismissSelector: String? = null
    )

    override suspend fun generate(input: PopupIdentificationInput): PopupIdentificationOutput {
        logger.debug("Popup identification for screenshot")

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
                Part.fromBytes(input.screenshotBytes, input.mimetype.value)
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

        val response = Json.decodeFromString<PopupIdentificationResponse>(llmResponse)
        val result = PopupIdentificationResult(
            exists = response.exists,
            dismissSelector = response.dismissSelector
        )
        return PopupIdentificationOutput(result = result)
    }
}


