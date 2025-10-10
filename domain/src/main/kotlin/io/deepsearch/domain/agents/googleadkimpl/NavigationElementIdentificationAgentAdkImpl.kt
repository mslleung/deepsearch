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
import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.agents.NavigationElementIdentificationOutput
import io.deepsearch.domain.agents.IdentifiedNavigationElement
import io.deepsearch.domain.models.valueobjects.NavigationElementType
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class NavigationElementIdentificationAgentAdkImpl : INavigationElementIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val elementSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A navigation element XPath with type and note")
        .properties(
            mapOf(
                "xpath" to Schema.builder().type("STRING").description("XPath to the root container").build(),
                "type" to Schema.builder().type("STRING").description("Type of navigation element").enum_(
                    NavigationElementType.entries.map { it.name }
                ).build(),
                "note" to Schema.builder().type("STRING").description("Brief note of what this element is").build()
            )
        ).build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Return a list of navigation elements with xpaths, types, and notes")
        .properties(
            mapOf(
                "elements" to Schema.builder()
                    .type("ARRAY")
                    .items(elementSchema)
                    .description("All detected navigation elements")
                    .build()
            )
        )
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("navigationElementIdentificationAgent")
        description("Identify and return XPath selectors of all navigation elements (header, footer, sidebars, navbars, sticky bars, etc.) using cleaned HTML")
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
            Task: Identify ALL navigational elements on the page and provide their XPath, type, and a short note.

            Inputs:
            - CLEANED HTML (subset of DOM with key attributes)

            Guidelines:
            - Include: header, footer, left/right sidebars (including aside/complementary regions), top navbars, sticky toolbars/bars, breadcrumb bars, cookie banners, chat widgets, ad banners, other persistent nav-like regions.
            - For each element, return the ROOT CONTAINER that wraps the entire navigation region.
            - Provide a relative XPath that is robust and targets the container.
            - Do not use positional predicates, use class attributes or other information to create unique XPaths.
            - Classify each element with one of: HEADER, FOOTER, SIDEBAR_LEFT, SIDEBAR_RIGHT, NAVBAR, BREADCRUMB, STICKY_BAR, CHAT_WIDGET, COOKIE_BANNER, AD_BANNER, OTHER.
            - Write a brief note describing why it is considered navigation (e.g., "Top navbar with logo and menu", "Left sidebar with category links").
            - Return an empty list if none found.

            Output structure:
            {
              "elements": [ { "xpath": string, "type": string, "note": string }, ... ]
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    @Serializable
    private data class NavigationElementsResponse(
        val elements: List<IdentifiedNavigationElement> = emptyList()
    )

    override suspend fun generate(input: NavigationElementIdentificationInput): NavigationElementIdentificationOutput {
        val cleanedHtml = cleanHtml(input.html)

        if (cleanedHtml.isEmpty()) {
            return NavigationElementIdentificationOutput(
                elements = emptyList()
            )
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
                Part.fromText("CLEANED_HTML:\n$cleanedHtml")
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

        val response = Json.decodeFromStringWithCodeBlocks<NavigationElementsResponse>(llmResponse)

        val normalized = response.elements.mapNotNull { item ->
            val x = item.xpath.trim().ifEmpty { null } ?: return@mapNotNull null
            val normalizedXPath = normalizeXPath(x)
            item.copy(xpath = normalizedXPath)
        }

        return NavigationElementIdentificationOutput(
            elements = normalized
        )
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
            val normalized = "//$trimmed".replace("///", "//")
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

        // Define relevance for navigation-related containers
        val keywordRegex = "(?i)(header|navbar|nav|menu|topbar|toolbar|footer|foot|sidebar|sidenav|side-nav|aside|drawer|breadcrumb|breadcrumbs|sticky)"
        val roleRegex = "(?i)(banner|navigation|contentinfo|complementary|menubar|toolbar)"
        val relevanceSelector = listOf(
            // Semantically explicit containers
            "header", "footer", "nav", "aside",
            // ARIA roles commonly used for navigational/complementary regions
            "[role~=$roleRegex]",
            // Heuristic keywords in id/class/aria-label
            "[id~=$keywordRegex]",
            "[class~=$keywordRegex]",
            "[aria-label~=(?i)(breadcrumb|breadcrumbs|sidebar|navigation|menu|navbar)]"
        ).joinToString(", ")

        val relevant = doc.select(relevanceSelector)
        if (relevant.isEmpty()) {
            return "" // Nothing obviously relevant
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

        // Strip all attributes except those essential for navigation identification
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "aria-label", "aria-labelledby", "data-testid")
            val attrsToKeep = element.attributes().filter { attr -> 
                attr.key in essentialAttrs 
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr -> 
                element.attr(attr.key, attr.value) 
            }
        }

        // Truncate text content to reduce size (navigation identification doesn't need full text)
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

