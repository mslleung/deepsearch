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
import io.deepsearch.domain.agents.infra.retryLlmCall
import io.deepsearch.domain.agents.INavigationElementIdentificationAgent
import io.deepsearch.domain.agents.NavigationElementIdentificationInput
import io.deepsearch.domain.agents.NavigationElementIdentificationOutput
import io.deepsearch.domain.models.valueobjects.IdentifiedElement
import io.deepsearch.domain.models.valueobjects.SemanticElements
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class NavigationElementIdentificationAgentAdkImpl : INavigationElementIdentificationAgent {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val identifiedElementSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("An identified element with XPath and note")
        .properties(
            mapOf(
                "xpath" to Schema.builder().type("STRING").description("XPath to the container").build(),
                "note" to Schema.builder().type("STRING").description("Brief note of what this element is").build()
            )
        )
        .build()

    private val outputSchema: Schema = Schema.builder()
        .type("OBJECT")
        .description("Structured collection of all semantic elements on the webpage")
        .properties(
            mapOf(
                "header" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Navigation bar at the top of the page (optional)").nullable(true).build(),
                "footer" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Footer at the bottom of the page (optional)").nullable(true).build(),
                "navSidebar" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Navigation sidebar (optional)").nullable(true).build(),
                "breadcrumb" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Breadcrumb navigation bar (optional)").nullable(true).build(),
                "cookieBanner" to Schema.builder().type("OBJECT").properties(
                    identifiedElementSchema.properties().orElse(emptyMap())
                ).description("Cookie consent banner (optional)").nullable(true).build(),
                "adBanners" to Schema.builder().type("ARRAY").items(identifiedElementSchema)
                    .description("List of advertisement banners").build(),
                "popups" to Schema.builder().type("ARRAY").items(identifiedElementSchema)
                    .description("List of popups and modal dialogs").build()
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
                .thinkingConfig(
                    ThinkingConfig.builder()
                        .thinkingBudget(0)
                        .build()
                )
                .build()
        )
        instruction(
            """
            Task: Identify ALL navigational elements on the page and provide their XPath and a short note.

            Inputs:
            - CLEANED HTML (subset of DOM with key attributes)

            Guidelines:
            - Identify (if any): header/navigation bar, footer, navigation sidebar, breadcrumb bar, cookie banner, ad banners and popups.
            - For each element, return the ROOT CONTAINER that wraps the entire navigation region.
            - Provide a relative XPath that is robust and targets the container.
            - Do not use positional predicates, use class attributes or other information to create unique XPaths.
            - Write a brief note describing why it is considered navigation (e.g., "Top navbar with logo and menu", "Left sidebar with category links").
            - Return null for optional single elements if not found.
            - Return empty arrays for list elements if none found.

            Output structure:
            {
              "header": { "xpath": string, "note": string } | null,
              "footer": { "xpath": string, "note": string } | null,
              "navSidebar": { "xpath": string, "note": string } | null,
              "breadcrumb": { "xpath": string, "note": string } | null,
              "cookieBanner": { "xpath": string, "note": string } | null,
              "adBanners": [ { "xpath": string, "note": string }, ... ],
              "popups": [ { "xpath": string, "note": string }, ... ]
            }
            """.trimIndent()
        )
        build()
    }

    private val runner = InMemoryRunner(agent)

    override suspend fun generate(input: NavigationElementIdentificationInput): NavigationElementIdentificationOutput {
        val cleanedHtml = cleanHtml(input.html)

        if (cleanedHtml.isEmpty()) {
            return NavigationElementIdentificationOutput(
                elements = SemanticElements()
            )
        }

        val response = retryLlmCall<SemanticElements> {
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

            llmResponse
        }

        // Normalize all XPaths in the response
        val normalized = SemanticElements(
            header = response.header?.let { normalizeElement(it) },
            footer = response.footer?.let { normalizeElement(it) },
            navSidebar = response.navSidebar?.let { normalizeElement(it) },
            breadcrumb = response.breadcrumb?.let { normalizeElement(it) },
            cookieBanner = response.cookieBanner?.let { normalizeElement(it) },
            adBanners = response.adBanners.mapNotNull { normalizeElement(it) },
            popups = response.popups.mapNotNull { normalizeElement(it) }
        )

        return NavigationElementIdentificationOutput(
            elements = normalized
        )
    }

    /**
     * Normalizes an identified element by normalizing its XPath.
     * Returns null if the xpath is invalid or empty.
     */
    private fun normalizeElement(element: IdentifiedElement): IdentifiedElement? {
        val xpath = element.xpath.trim().ifEmpty { return null }
        val normalizedXPath = normalizeXPath(xpath)
        return element.copy(xpath = normalizedXPath)
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

        // Step 2: Strip attributes to only essentials for navigation identification
        // Keep FULL page structure - LLM needs context to distinguish navigation from content
        // Keep id, class, role for XPath uniqueness and semantic meaning
        doc.select("*").forEach { element ->
            val essentialAttrs = setOf("id", "class", "role", "aria-label", "data-testid")
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
                    // 10 chars is enough to understand "Home", "Login", "Products", etc.
                    val shortened = if (text.length > 10) text.take(10) + "..." else text
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

