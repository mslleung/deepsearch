package io.deepsearch.domain.agents.googleadkimpl

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.ThinkingConfig
import io.deepsearch.domain.agents.infra.ModelIds
import io.deepsearch.domain.agents.infra.decodeFromStringWithCodeBlocks
import io.deepsearch.domain.agents.ISemanticIdentificationAgent
import io.deepsearch.domain.agents.SemanticIdentificationInput
import io.deepsearch.domain.agents.SemanticIdentificationOutput
import io.deepsearch.domain.agents.IdentifiedSemanticElement
import io.deepsearch.domain.models.valueobjects.SemanticElementType
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SemanticIdentificationAgentAdkImpl : ISemanticIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val elementSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("A semantic element XPath with type and note")
        .properties(
            mapOf(
                "xpath" to Schema.builder().type("STRING").description("XPath to the root container").build(),
                "type" to Schema.builder().type("STRING").description("Type of semantic element").enum_(
                    SemanticElementType.entries.map { it.name }
                ).build(),
                "note" to Schema.builder().type("STRING").description("Brief note of what this element is").build()
            )
        ).build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Return a list of semantic elements with xpaths, types, and notes")
        .properties(
            mapOf(
                "elements" to Schema.builder()
                    .type("ARRAY")
                    .items(elementSchema)
                    .description("All detected semantic elements")
                    .build()
            )
        )
        .build()

    private val agent: LlmAgent = LlmAgent.builder().run {
        name("semanticIdentificationAgent")
        description("Identify and return XPath selectors of all semantic elements (navigation elements, popups, etc.) using screenshot and cleaned HTML")
        model(ModelIds.GEMINI_2_5_FLASH_LITE_PREVIEW.modelId)
        outputSchema(outputSchema)
        disallowTransferToPeers(true)
        disallowTransferToParent(true)
        generateContentConfig(
            GenerateContentConfig.builder()
                .temperature(0F)
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
            Task: Identify ALL semantic elements on the page and provide their XPath, type, and a short note.

            Inputs:
            - A screenshot of the webpage (current viewport)
            - CLEANED HTML (subset of DOM with key attributes)

            Guidelines:
            - Include: header, footer, left/right sidebars (including aside/complementary regions), top navbars, sticky toolbars/bars, breadcrumb bars, cookie banners, chat widgets, ad banners, popups, modal dialogs, other persistent nav-like regions and the main content container.
            - For each element, return the ROOT CONTAINER that wraps the entire semantic region.
            - Provide a relative XPath that is robust and targets the container.
            - Do not use positional predicates, use class attributes or other information to create unique XPaths.
            - Classify each element with one of: HEADER, FOOTER, SIDEBAR_LEFT, SIDEBAR_RIGHT, NAVBAR, BREADCRUMB, STICKY_BAR, CHAT_WIDGET, COOKIE_BANNER, AD_BANNER, POPUP, MAIN_CONTENT, OTHER.
            - Write a brief note describing why it is considered a semantic element (e.g., "Top navbar with logo and menu", "Left sidebar with category links", "Cookie consent popup").
            - Use the screenshot to help identify visible popups and modal dialogs that interrupt the user experience.
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
    private data class SemanticElementsResponse(
        val elements: List<IdentifiedSemanticElement> = emptyList()
    )

    override suspend fun generate(input: SemanticIdentificationInput): SemanticIdentificationOutput {
        val cleanedHtml = cleanHtml(input.html)

        if (cleanedHtml.isEmpty()) {
            return SemanticIdentificationOutput(
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
                Part.fromBytes(input.screenshotBytes, input.mimetype.value),
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

        val response = Json.decodeFromStringWithCodeBlocks<SemanticElementsResponse>(llmResponse)

        val normalized = response.elements.mapNotNull { item ->
            val x = item.xpath.trim().ifEmpty { null } ?: return@mapNotNull null
            val normalizedXPath = normalizeXPath(x)
            item.copy(xpath = normalizedXPath)
        }

        return SemanticIdentificationOutput(
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

        // Step 1: Remove carousel/slider clones first (major source of duplication)
        // Slick carousel, Swiper, and similar libraries clone slides for infinite scrolling
        doc.select(".slick-cloned, [class*=swiper-slide-duplicate], [data-cloned=true]").remove()
        logger.debug("Removed carousel clones")
        
        // Step 1.5: Remove noise elements that don't contribute to structure
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

        // Step 2: Strip attributes to only essentials for semantic identification
        // Keep FULL page structure - LLM needs context to distinguish semantic elements from content
        // Keep id, class, role for XPath uniqueness and semantic meaning
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "aria-label", "aria-modal", "data-testid")
            val attrsToKeep = element.attributes().filter { attr ->
                attr.key in essentialAttrs
            }
            element.clearAttributes()
            attrsToKeep.forEach { attr ->
                element.attr(attr.key, attr.value)
            }
        }

        // Step 3: Aggressively minimize text content - only need structure for XPath
        // Keep only first few chars per text node for context
        doc.select("*").forEach { element ->
            element.textNodes().forEach { textNode ->
                val text = textNode.text().trim()
                if (text.isNotEmpty()) {
                    // Keep minimal text for context (helps LLM understand element purpose)
                    // 15 chars is enough to understand "Home", "Login", "Products", "Accept Cookies", etc.
                    val shortened = if (text.length > 15) text.take(15) + "..." else text
                    textNode.text(shortened)
                }
            }
        }
        
        // Step 3.5: Remove duplicate navigation structures (mobile/desktop variations)
        // Many sites have separate mobile and desktop navigation with similar content
        val mobileNavSelectors = listOf(
            ".mobile-nav", ".mobile-menu", "[class*=mobile-nav]", "[class*=mobile-menu]",
            ".nav-mobile", ".menu-mobile", "[id*=mobile-nav]", "[id*=mobile-menu]"
        )
        val desktopNavSelectors = listOf(
            ".desktop-nav", ".primary-nav", "[class*=desktop-nav]", "[class*=primary-nav]",
            ".nav-desktop", ".main-nav", "[id*=desktop-nav]", "[id*=primary-nav]"
        )
        
        // If both mobile and desktop nav exist, remove mobile (desktop usually has better structure)
        val hasMobileNav = doc.select(mobileNavSelectors.joinToString(",")).isNotEmpty()
        val hasDesktopNav = doc.select(desktopNavSelectors.joinToString(",")).isNotEmpty()
        if (hasMobileNav && hasDesktopNav) {
            doc.select(mobileNavSelectors.joinToString(",")).remove()
            logger.debug("Removed duplicate mobile navigation (desktop nav exists)")
        }
        
        // Step 3.6: Prune deeply nested elements to reduce noise
        // Navigation elements are typically at higher levels of the DOM tree
        // Deep nesting (>12 levels) is usually content, not navigation structure
        val maxDepth = 12
        doc.select("*").toList().forEach { element ->
            val depth = generateSequence(element) { it.parent() }.count()
            if (depth > maxDepth) {
                // For deep elements, keep only if they have id/class (potential targets)
                if (element.attr("id").isEmpty() && element.attr("class").isEmpty()) {
                    element.remove()
                }
            }
        }

        // Step 4: Remove empty elements iteratively to compact structure
        var changed = true
        while (changed) {
            changed = false
            val emptyElements = doc.select("*").filter { element ->
                element.children().isEmpty() &&
                element.ownText().isBlank() &&
                element.attr("id").isEmpty() &&
                element.attr("class").isEmpty() &&
                element.attr("role").isEmpty()
            }
            if (emptyElements.isNotEmpty()) {
                emptyElements.forEach { it.remove() }
                changed = true
            }
        }

        val cleanedHtml = doc.outerHtml()
        logger.debug("Cleaned HTML character count: {} (original: ~{})", cleanedHtml.length, rawHtml.length)
        return cleanedHtml
    }
}

