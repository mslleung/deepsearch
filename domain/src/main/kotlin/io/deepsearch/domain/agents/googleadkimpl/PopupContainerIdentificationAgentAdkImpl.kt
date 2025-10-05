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
            "script, style, noscript, template, svg, canvas, meta, link, iframe, object, embed"
        ).remove()

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

        val sb = StringBuilder()
        for (root in topLevelRelevant) {
            appendSanitizedOutline(root, sb, 0)
        }

        return sb.toString()
    }

    private fun appendSanitizedOutline(element: Element, sb: StringBuilder, depth: Int) {
        val MAX_DEPTH = 4
        val MAX_CHILDREN = 30
        if (depth > MAX_DEPTH) return

        val allowedTags = setOf(
            "dialog", "div", "section", "aside", "header", "footer", "nav", "ul", "li", "a", "button", "span", "p", "img"
        )

        val tagName = element.tagName()
        if (depth > 0 && !allowedTags.contains(tagName)) {
            return
        }

        val indent = "  ".repeat(depth)
        val id = element.attr("id").take(80)
        val classAttr = element.attr("class").split(" ")
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" ")
            .take(120)
        val role = element.attr("role").take(40)
        val ariaLabel = element.attr("aria-label").take(120)
        val style = element.attr("style").lowercase()

        // Keep a minimal hint from style to detect overlays without leaking big strings
        val styleFlags = buildList {
            if (style.contains("position:fixed") || style.contains("position: absolute")) add("pos")
            if (style.contains("z-index")) add("z")
            if (style.contains("backdrop") || style.contains("overlay")) add("overlay")
        }.joinToString(",")

        sb.append("$indent<${tagName}")
        if (id.isNotEmpty()) sb.append(" id=\"$id\"")
        if (classAttr.isNotEmpty()) sb.append(" class=\"$classAttr\"")
        if (role.isNotEmpty()) sb.append(" role=\"$role\"")
        if (ariaLabel.isNotEmpty()) sb.append(" aria-label=\"$ariaLabel\"")
        if (styleFlags.isNotEmpty()) sb.append(" flags=\"$styleFlags\"")
        sb.append(">\n")

        val text = element.ownText().trim()
        if (text.isNotEmpty() && text.length <= 160) {
            sb.append("$indent  ${text}\n")
        }

        var count = 0
        for (child in element.children()) {
            if (count >= MAX_CHILDREN) break
            appendSanitizedOutline(child, sb, depth + 1)
            count++
        }

        sb.append("$indent</${tagName}>\n")
    }
}
