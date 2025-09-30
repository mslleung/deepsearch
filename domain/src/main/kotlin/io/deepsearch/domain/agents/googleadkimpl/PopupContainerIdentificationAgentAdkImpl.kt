package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.IPopupContainerIdentificationAgent
import io.deepsearch.domain.agents.PopupContainerIdentificationInput
import io.deepsearch.domain.agents.PopupContainerIdentificationOutput
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PopupContainerIdentificationAgentAdkImpl : IPopupContainerIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Return unique XPath selectors to popup container root elements")
        .properties(
            mapOf(
                "popupContainerXPaths" to Schema.builder()
                    .type("ARRAY")
                    .description("List of unique XPath selectors targeting the root containers of visible popups/cookie banners. Empty list if no popups are present.")
                    .items(
                        Schema.builder()
                            .type("STRING")
                            .description("Unique XPath selector targeting a popup container root element")
                            .build()
                    )
                    .build(),
            )
        )
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("popupContainerIdentificationAgent")
        description("Identify and return XPath selectors of popup container root elements using screenshot and cleaned HTML")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
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
            Task: Find all visible popup/cookie banner containers and return unique XPaths to their root container elements.

            Inputs:
            - A screenshot of the webpage (current viewport)
            - CLEANED HTML (subset of DOM with key attributes)

            Guidelines:
            - Understand the website visually using the webpage screenshot
            - Identify ALL visible popups, cookie banners, modal dialogs, overlay containers, and promotional overlays
            - For each popup, identify the ROOT CONTAINER element (not buttons, not text, but the container that wraps the entire popup)
            - Return XPath selectors that point to these root container elements (e.g., <div>, <section>, <aside> that contain the popup)
            - Focus on containers that overlay the main content or appear as modal dialogs
            - Include containers with common popup/modal characteristics: fixed/absolute positioning, z-index layers, backdrop overlays
            - If no popups are visible, return an empty list

            Output structure:
            {
                "popupContainerXPaths": ["string"]
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class PopupContainerIdentificationResponse(
        val popupContainerXPaths: List<String> = emptyList()
    )

    override suspend fun generate(input: PopupContainerIdentificationInput): PopupContainerIdentificationOutput {
        val cleanedHtml = cleanHtml(input.html)

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
                Part.fromBytes(input.screenshotBytes, input.mimetype.value),
                Part.fromText("CLEANED_HTML:\n" + cleanedHtml)
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

        val response = Json.decodeFromString<PopupContainerIdentificationResponse>(llmResponse)
        val validXPaths = response.popupContainerXPaths.filter { it.isNotBlank() }

        return PopupContainerIdentificationOutput(popupContainerXPaths = validXPaths)
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Remove scripts, styles, and other non-visual elements
        doc.select("script, style, noscript, meta, link[rel=stylesheet]").remove()

        // Focus on elements that commonly contain popups/modals
        val relevantElements = doc.select(
            "div, section, aside, dialog, article, header, footer, nav, main, " +
            "[role=dialog], [role=banner], [role=alert], [role=alertdialog], " +
            ".modal, .popup, .overlay, .banner, .cookie, .consent, " +
            "[class*=modal], [class*=popup], [class*=overlay], [class*=banner], " +
            "[class*=cookie], [class*=consent], [class*=dialog], " +
            "[id*=modal], [id*=popup], [id*=overlay], [id*=banner], " +
            "[id*=cookie], [id*=consent], [id*=dialog]"
        )

        // Build simplified HTML with key attributes
        val sb = StringBuilder()
        for (element in relevantElements) {
            appendElementInfo(element, sb, 0)
        }

        return sb.toString().take(8000) // Limit size for LLM processing
    }

    private fun appendElementInfo(element: Element, sb: StringBuilder, depth: Int) {
        if (depth > 10) return // Prevent infinite recursion

        val indent = "  ".repeat(depth)
        val tagName = element.tagName()
        val id = element.attr("id")
        val className = element.attr("class")
        val role = element.attr("role")
        val style = element.attr("style")

        sb.append("$indent<$tagName")
        if (id.isNotEmpty()) sb.append(" id=\"$id\"")
        if (className.isNotEmpty()) sb.append(" class=\"$className\"")
        if (role.isNotEmpty()) sb.append(" role=\"$role\"")
        if (style.isNotEmpty()) sb.append(" style=\"${style.take(100)}\"")
        sb.append(">\n")

        // Only include text content for small elements to avoid noise
        val text = element.ownText().trim()
        if (text.isNotEmpty() && text.length < 200) {
            sb.append("$indent  $text\n")
        }

        // Recursively process children (limited depth)
        element.children().take(20).forEach { child ->
            appendElementInfo(child, sb, depth + 1)
        }

        sb.append("$indent</$tagName>\n")
    }
}
