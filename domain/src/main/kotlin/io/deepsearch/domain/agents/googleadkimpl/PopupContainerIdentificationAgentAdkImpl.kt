package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
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
            Task: Find all popup/cookie banner containers and return unique XPaths to their root container elements.

            Inputs:
            - A screenshot of the webpage (current viewport)
            - CLEANED HTML (subset of DOM with key attributes)

            Guidelines:
            - Identify all popups, cookie banners and modal dialogs
            - The identified elements should normally interrupt the user's use of the website, all other elements should be excluded
            - For each popup, identify the ROOT CONTAINER element (not buttons, not text, but the container that wraps the entire popup)
            - Return XPath selectors that point to these root container elements (e.g., <div>, <section>, <aside> that contain the popup)
            - If no popups are found, return an empty list

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

        val response = Json.decodeFromStringWithCodeBlocks<PopupContainerIdentificationResponse>(llmResponse)
        val validXPaths = response.popupContainerXPaths
            .filter { it.isNotBlank() }
            .map { normalizeXPath(it) }

        return PopupContainerIdentificationOutput(popupContainerXPaths = validXPaths)
    }

    /**
     * Normalizes XPath expressions to ensure they work correctly with Playwright.
     * 
     * Common issues fixed:
     * - Converts absolute paths like `/div[@id='x']` to relative paths `//div[@id='x']`
     *   (since `/div` expects div to be a direct child of the document root, which is invalid for HTML)
     * - Preserves valid absolute paths like `/html/body/div[@id='x']`
     */
    private fun normalizeXPath(xpath: String): String {
        val trimmed = xpath.trim()
        
        // If XPath starts with a single `/` followed by something other than `html`, convert to `//`
        // This handles cases like `/div[@id='x']` -> `//div[@id='x']`
        if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.startsWith("/html")) {
            val normalized = "/$trimmed"
            logger.debug("Normalized XPath from '{}' to '{}'", trimmed, normalized)
            return normalized
        }
        
        return trimmed
    }

    private fun cleanHtml(rawHtml: String): String {
        val doc: Document = Jsoup.parse(rawHtml)

        // Remove non-visual/noise elements aggressively
        doc.select(
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed, " +
            "img, video, audio, source, track, picture"
        ).remove()

        // Remove comments and processing instructions
        doc.select("*").forEach { element ->
            val nodesToRemove = element.childNodes().filter { node -> 
                node.nodeName() == "#comment" || node.nodeName() == "#pi" 
            }
            nodesToRemove.forEach { node -> node.remove() }
        }

        // Define relevance for popup/modal related containers
        val relevanceSelector = (
            "dialog, " +
            "[role=dialog], [role=alertdialog], [role=alert], " +
            "[id*~=modal|popup|dialog|overlay|banner|cookie|consent], " +
            "[class*~=modal|popup|dialog|overlay|banner|cookie|consent], " +
            // Common container tags that often hold overlays
            "div, section, aside"
        )

        val relevant = doc.select(relevanceSelector)
        if (relevant.isEmpty()) {
            return ""
        }

        // Keep only TOP-LEVEL relevant elements to avoid duplicates
        val topLevelRelevant = relevant.filter { el ->
            el.parents().none { parent -> parent.`is`(relevanceSelector) }
        }

        // Remove all elements that are not top-level relevant or their descendants
        doc.body().children().forEach { child ->
            if (!topLevelRelevant.contains(child) && topLevelRelevant.none { it.contains(child) }) {
                child.remove()
            }
        }

        // Strip all attributes except those essential for popup identification
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "aria-modal", "aria-label", "aria-labelledby", "data-testid")
            val attrsToKeep = element.attributes().filter { attr -> 
                attr.key in essentialAttrs 
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr -> 
                element.attr(attr.key, attr.value) 
            }
        }

        // Truncate text content to reduce size (popup identification doesn't need full text)
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.length > 50) {
                    textNode.text(text.take(50) + "...")
                } else if (text.isNotEmpty()) {
                    textNode.text(text)
                }
            }
        }

        // Remove empty elements iteratively
        var changed = true
        while (changed) {
            changed = false
            val emptyElements = doc.select("*").filter { element ->
                element.children().isEmpty() && element.ownText().isBlank() &&
                element.attr("id").isEmpty() && element.attr("class").isEmpty() && element.attr("role").isEmpty()
            }
            if (emptyElements.isNotEmpty()) {
                emptyElements.forEach { it.remove() }
                changed = true
            }
        }

        return doc.outerHtml()
    }
}
