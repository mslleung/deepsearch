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
        .description("Detect presence of popup/cookie banner and provide a unique XPath selector to the popup container")
        .properties(
            mapOf(
                "exists" to Schema.builder().type("BOOLEAN").description("Whether a popup/cookie banner exists").build(),
                "containerXPath" to Schema.builder().type("STRING").description("A unique XPath selector that targets the popup container element. Returns null if no popup exists.").build(),
            )
        )
        .required(listOf("exists"))
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("popupIdentificationAgent")
        description("Identify if a popup/cookie banner exists and provide a unique XPath selector to the popup container element")
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
            Your task is to detect whether any popup or cookie consent banner is currently visible on the webpage.
            If present, return a unique XPath selector that identifies the POPUP CONTAINER ELEMENT itself, not a button.

            Input:
            A screenshot of a webpage.

            Instructions:
            - Determine if a popup banner or cookie consent banner is visible.
            - If visible, return exists=true and generate a unique XPath selector to the popup container node.
            - The selector must make no assumptions about the HTML structure. Prefer using * and text containment where applicable.
            - The XPath should try to capture a stable container (e.g., modal root, banner div) and avoid main page content.
            - If multiple popups exist, choose the most prominent one.
            - If no popup or banner is visible, return exists=false and omit containerXPath.

            Examples (illustrative only):
            //*[contains(@role, 'dialog') or contains(@class, 'modal')][contains(., 'cookies')]

            Expected output shape:
            {
              "exists": true,
              "containerXPath": "string"
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
        val containerXPath: String? = null
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
            containerXPath = response.containerXPath
        )
        return PopupIdentificationOutput(result = result)
    }
}


